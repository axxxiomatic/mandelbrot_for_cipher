package com.cipher.gpu;

import com.cipher.core.dto.MandelbrotParams;
import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.utils.RasterUtils;
import jcuda.Pointer;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemAlloc;
import static jcuda.driver.JCudaDriver.cuMemAllocHost;
import static jcuda.driver.JCudaDriver.cuMemFree;
import static jcuda.driver.JCudaDriver.cuMemFreeHost;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuMemcpyHtoD;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;

/**
 * Генератор фрактала на CUDA.
 * <p>
 * Отличия от исходной версии:
 * <ul>
 *   <li>использует общий primary context ({@link CudaDevice}) и делает его текущим на каждый
 *       вызов — работает из любого потока, а не только из потока создания бина;</li>
 *   <li>ядро компилируется под реальную карту через NVRTC ({@link KernelCompiler}) —
 *       нет привязки к драйверу ветки 550+;</li>
 *   <li>device-буфер переиспользуется между кадрами;</li>
 *   <li>есть пакетный режим {@link #generateBatch} — несколько кандидатов одним запуском ядра;</li>
 *   <li>любая ошибка инициализации превращается в {@link CudaException}, который ловит фабрика
 *       бэкендов и переключается на CPU.</li>
 * </ul>
 * Результат всегда {@code TYPE_INT_RGB} и побитово совпадает с
 * {@link com.cipher.core.generator.impl.CPUFractalGenerator}, что проверяет тест паритета.
 * </p>
 */
public class GPUFractalGenerator implements FractalGenerator {

    private static final Logger log = LoggerFactory.getLogger(GPUFractalGenerator.class);

    private static final int BLOCK = 16;
    private static final int PARAMS_PER_FRAME = 6;

    private final Object lock = new Object();
    private CUmodule module;
    private CUfunction kernel;
    private CUfunction batchKernel;

    private CUdeviceptr deviceBuffer;
    private long deviceBufferBytes;

    /** Странично-закреплённый (pinned) буфер приёма: с него копирование D2H идёт на полной скорости PCIe. */
    private Pointer pinnedHost;
    private long pinnedHostBytes;

    private volatile long lastKernelNanos;
    private volatile long lastCopyNanos;

    public GPUFractalGenerator() {
        if (!CudaDevice.acquire()) {
            throw new CudaException("CUDA недоступна");
        }
        try {
            if (!CudaDevice.makeCurrent()) {
                throw new CudaException("Не удалось установить контекст CUDA");
            }
            module = KernelCompiler.loadModule("/cuda/mandelbrot.cu", "/cuda/mandelbrot.ptx");
            kernel = function("mandelbrotKernel");
            batchKernel = functionSafely("mandelbrotBatchKernel");
            log.info("GPU-генератор готов: {}", CudaDevice.deviceName());
        } catch (Throwable t) {
            CudaDevice.release();
            if (t instanceof CudaException cudaException) {
                throw cudaException;
            }
            throw new CudaException("Инициализация GPU-генератора не удалась", t);
        }
    }

    private CUfunction function(String name) {
        CUfunction f = new CUfunction();
        cuModuleGetFunction(f, module, name);
        return f;
    }

    private CUfunction functionSafely(String name) {
        try {
            return function(name);
        } catch (Throwable t) {
            log.warn("Ядро {} недоступно, пакетный режим отключён", name);
            return null;
        }
    }

    @Override
    public String backendName() {
        return "CUDA(" + CudaDevice.deviceName() + ")";
    }

    @Override
    public BufferedImage generate(int width, int height, double zoom,
                                  double offsetX, double offsetY, int maxIter) {
        if (width <= 0 || height <= 0 || zoom <= 0 || maxIter <= 0) {
            throw new IllegalArgumentException("Invalid fractal parameters");
        }
        int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 3);

        synchronized (lock) {
            ensureContext();
            CUdeviceptr output = deviceBuffer(frameBytes);

            Pointer params = Pointer.to(
                    Pointer.to(output),
                    Pointer.to(new int[]{width}),
                    Pointer.to(new int[]{height}),
                    Pointer.to(new double[]{zoom}),
                    Pointer.to(new double[]{offsetX}),
                    Pointer.to(new double[]{offsetY}),
                    Pointer.to(new int[]{maxIter})
            );

            long t0 = System.nanoTime();
            cuLaunchKernel(kernel, ceilDiv(width, BLOCK), ceilDiv(height, BLOCK), 1,
                    BLOCK, BLOCK, 1, 0, null, params, null);
            cuCtxSynchronize();
            lastKernelNanos = System.nanoTime() - t0;

            byte[] bytes = download(output, frameBytes);
            return RasterUtils.fromBgrBytes(bytes, width, height);
        }
    }

    /**
     * Пакетная генерация: {@code N} кадров одним запуском ядра.
     * <p>
     * В отличие от {@code N} конкурентных вызовов из пула (как было в
     * {@code findValidFractalParallel}), здесь нет ни лишних аллокаций, ни сериализации на одном
     * контексте, а поиск валидного кандидата укладывается в один round-trip драйвера.
     * </p>
     */
    @Override
    public List<BufferedImage> generateBatch(int width, int height, List<MandelbrotParams> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyList();
        }
        if (batchKernel == null) {
            return FractalGenerator.super.generateBatch(width, height, params);
        }
        int count = params.size();
        long frameBytes = (long) width * height * 3;
        long totalBytes = frameBytes * count;
        if (totalBytes > Integer.MAX_VALUE - 8) {
            // Пачка не должна требовать больше 2 ГБ на хосте — иначе byte[] не создать.
            // Проверка до аллокации: иначе ограничение всплыло бы как OOM посреди генерации.
            throw new IllegalArgumentException("Слишком большой пакет: " + totalBytes + " байт");
        }

        double[] packed = new double[count * PARAMS_PER_FRAME];
        for (int i = 0; i < count; i++) {
            MandelbrotParams p = params.get(i);
            packed[i * PARAMS_PER_FRAME] = p.zoom();
            packed[i * PARAMS_PER_FRAME + 1] = p.offsetX();
            packed[i * PARAMS_PER_FRAME + 2] = p.offsetY();
            packed[i * PARAMS_PER_FRAME + 3] = p.maxIter();
        }

        synchronized (lock) {
            ensureContext();
            CUdeviceptr output = deviceBuffer(totalBytes);
            CUdeviceptr paramsDevice = new CUdeviceptr();
            long paramsBytes = (long) packed.length * Double.BYTES;
            cuMemAlloc(paramsDevice, paramsBytes);
            try {
                cuMemcpyHtoD(paramsDevice, Pointer.to(packed), paramsBytes);

                Pointer kernelParams = Pointer.to(
                        Pointer.to(output),
                        Pointer.to(new int[]{width}),
                        Pointer.to(new int[]{height}),
                        Pointer.to(new int[]{(int) frameBytes}),
                        Pointer.to(new int[]{count}),
                        Pointer.to(paramsDevice)
                );

                long t0 = System.nanoTime();
                cuLaunchKernel(batchKernel, ceilDiv(width, BLOCK), ceilDiv(height, BLOCK), count,
                        BLOCK, BLOCK, 1, 0, null, kernelParams, null);
                cuCtxSynchronize();
                lastKernelNanos = System.nanoTime() - t0;

                byte[] bytes = download(output, (int) totalBytes);
                List<BufferedImage> images = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int offset = (int) (i * frameBytes);
                    byte[] frame = java.util.Arrays.copyOfRange(bytes, offset, offset + (int) frameBytes);
                    images.add(RasterUtils.fromBgrBytes(frame, width, height));
                }
                return images;
            } finally {
                cuMemFree(paramsDevice);
            }
        }
    }

    @Override
    public int batchCapacity() {
        return batchKernel == null ? 1 : 8;
    }

    private void ensureContext() {
        if (!CudaDevice.makeCurrent()) {
            throw new CudaException("Контекст CUDA недоступен");
        }
    }

    private CUdeviceptr deviceBuffer(long bytes) {
        if (deviceBuffer != null && deviceBufferBytes >= bytes) {
            return deviceBuffer;
        }
        if (deviceBuffer != null) {
            cuMemFree(deviceBuffer);
        }
        CUdeviceptr ptr = new CUdeviceptr();
        cuMemAlloc(ptr, bytes);
        deviceBuffer = ptr;
        deviceBufferBytes = bytes;
        return ptr;
    }

    /**
     * Копирует кадр из device-памяти в pinned-буфер, затем в массив.
     * <p>
     * Копирование напрямую в {@code byte[]} (pageable-память) вынуждает драйвер делать
     * промежуточную копию через свой внутренний pinned-буфер — на 1080p это заметная часть
     * времени кадра. Собственный pinned-буфер выделяется один раз и переиспользуется;
     * {@code cuMemcpyDtoH} в него идёт на полной скорости PCIe.
     * </p>
     */
    private byte[] download(CUdeviceptr source, int bytes) {
        long t0 = System.nanoTime();
        byte[] out = new byte[bytes];
        Pointer host = pinnedHost(bytes);
        if (host == null) {
            cuMemcpyDtoH(Pointer.to(out), source, bytes);
        } else {
            cuMemcpyDtoH(host, source, bytes);
            java.nio.ByteBuffer view = host.getByteBuffer();
            if (view == null) {
                throw new CudaException("Pinned-буфер недоступен как ByteBuffer");
            }
            view.get(0, out, 0, bytes);
        }
        lastCopyNanos = System.nanoTime() - t0;
        return out;
    }

    /** Pinned-буфер нужного размера; {@code null}, если закрепление не поддерживается. */
    private Pointer pinnedHost(int bytes) {
        if (pinnedHost != null && pinnedHostBytes >= bytes) {
            return pinnedHost;
        }
        freePinnedHost();
        Pointer host = new Pointer();
        try {
            cuMemAllocHost(host, bytes);
            pinnedHost = host;
            pinnedHostBytes = bytes;
            log.debug("Pinned-буфер приёма: {} байт", bytes);
            return host;
        } catch (Throwable t) {
            // Закрепление может быть недоступно (лимиты ОС) — тогда работает обычный путь.
            log.warn("Pinned-память недоступна, копирую в pageable-буфер: {}", t.toString());
            freePinnedHost();
            return null;
        }
    }

    private void freePinnedHost() {
        if (pinnedHost != null) {
            try {
                cuMemFreeHost(pinnedHost);
            } catch (Throwable t) {
                log.warn("Не удалось освободить pinned-буфер: {}", t.toString());
            }
            pinnedHost = null;
            pinnedHostBytes = 0;
        }
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    public long lastKernelNanos() {
        return lastKernelNanos;
    }

    public long lastCopyNanos() {
        return lastCopyNanos;
    }

    /** Освобождает device-буферы. Вызывается Spring как {@code destroyMethod}. */
    public void shutdown() {
        synchronized (lock) {
            try {
                if (deviceBuffer != null) {
                    cuMemFree(deviceBuffer);
                    deviceBuffer = null;
                    deviceBufferBytes = 0;
                }
                freePinnedHost();
            } catch (Throwable t) {
                log.warn("Ошибка освобождения буферов GPU: {}", t.toString());
            } finally {
                CudaDevice.release();
            }
        }
    }
}
