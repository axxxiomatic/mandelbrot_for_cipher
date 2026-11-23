package com.cipher.gpu;

import com.cipher.core.generator.FractalValidator;
import com.cipher.core.utils.RasterUtils;
import jcuda.Pointer;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemAlloc;
import static jcuda.driver.JCudaDriver.cuMemFree;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuMemcpyHtoD;
import static jcuda.driver.JCudaDriver.cuMemsetD8;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;

/**
 * Валидация фрактала на GPU.
 * <p>
 * Ядро {@code validateKernel} проходит по изображению и атомарно накапливает два вида данных:
 * число «внутренних» пикселей и гистограмму тона по 36 бинам. На хост возвращаются только
 * {@code 37} целых числа, поэтому CPU больше не перебирает 2 млн пикселей на каждого кандидата
 * (именно это съедало весь выигрыш от CUDA-генерации).
 * </p>
 * <p>
 * Решение о пригодности принимает CPU по этим счётчикам — так сохраняется контроль над
 * критерием и остаётся возможность объяснить отказ в логе.
 * </p>
 */
public class GPUFractalValidator implements FractalValidator {

    private static final Logger log = LoggerFactory.getLogger(GPUFractalValidator.class);
    private static final int BLOCK = 16;

    private final Object lock = new Object();
    private CUmodule module;
    private CUfunction kernel;

    private CUdeviceptr imageBuffer;
    private long imageBufferBytes;
    private CUdeviceptr countersBuffer;

    public GPUFractalValidator() {
        if (!CudaDevice.acquire()) {
            throw new CudaException("CUDA недоступна");
        }
        try {
            if (!CudaDevice.makeCurrent()) {
                throw new CudaException("Не удалось установить контекст CUDA");
            }
            module = KernelCompiler.loadModule("/cuda/validate.cu", "/cuda/validate.ptx");
            kernel = new CUfunction();
            cuModuleGetFunction(kernel, module, "validateKernel");
            countersBuffer = new CUdeviceptr();
            cuMemAlloc(countersBuffer, (long) (1 + BINS) * Integer.BYTES);
            log.info("GPU-валидатор готов");
        } catch (Throwable t) {
            CudaDevice.release();
            if (t instanceof CudaException cudaException) {
                throw cudaException;
            }
            throw new CudaException("Инициализация GPU-валидатора не удалась", t);
        }
    }

    @Override
    public String backendName() {
        return "CUDA(" + CudaDevice.deviceName() + ")";
    }

    @Override
    public boolean isValid(BufferedImage fractal) {
        return metrics(fractal).meetsQuality();
    }

    @Override
    public Metrics metrics(BufferedImage fractal) {
        if (fractal == null) {
            throw new IllegalArgumentException("Изображение не может быть null");
        }
        int width = fractal.getWidth();
        int height = fractal.getHeight();
        byte[] bgr = RasterUtils.toBgrBytes(fractal);

        int[] counters = new int[1 + BINS];

        synchronized (lock) {
            if (!CudaDevice.makeCurrent()) {
                throw new CudaException("Контекст CUDA недоступен");
            }
            ensureImageBuffer(bgr.length);
            cuMemcpyHtoD(imageBuffer, Pointer.to(bgr), bgr.length);
            // Гистограмму и счётчик внутренних точек нужно обнулить перед каждым прогоном.
            cuMemsetD8(countersBuffer, (byte) 0, countersBufferBytes());

            Pointer params = Pointer.to(
                    Pointer.to(imageBuffer),
                    Pointer.to(new int[]{width}),
                    Pointer.to(new int[]{height}),
                    Pointer.to(new int[]{BINS}),
                    Pointer.to(countersBuffer)
            );

            int gridX = (width + BLOCK - 1) / BLOCK;
            int gridY = (height + BLOCK - 1) / BLOCK;
            cuLaunchKernel(kernel, gridX, gridY, 1, BLOCK, BLOCK, 1, 0, null, params, null);
            cuCtxSynchronize();

            cuMemcpyDtoH(Pointer.to(counters), countersBuffer, countersBufferBytes());
        }

        int internal = counters[0];
        int occupied = 0;
        int maxBin = 0;
        int considered = 0;
        for (int i = 1; i < counters.length; i++) {
            int c = counters[i];
            if (c > 0) {
                occupied++;
            }
            if (c > maxBin) {
                maxBin = c;
            }
            considered += c;
        }
        return new Metrics(width, height, internal, considered, occupied, maxBin);
    }

    private long countersBufferBytes() {
        return (long) (1 + BINS) * Integer.BYTES;
    }

    private void ensureImageBuffer(int bytes) {
        if (imageBuffer != null && imageBufferBytes >= bytes) {
            return;
        }
        if (imageBuffer != null) {
            cuMemFree(imageBuffer);
        }
        imageBuffer = new CUdeviceptr();
        cuMemAlloc(imageBuffer, bytes);
        imageBufferBytes = bytes;
    }

    /** Вызывается Spring как {@code destroyMethod}. */
    public void shutdown() {
        synchronized (lock) {
            try {
                if (imageBuffer != null) {
                    cuMemFree(imageBuffer);
                    imageBuffer = null;
                    imageBufferBytes = 0;
                }
                if (countersBuffer != null) {
                    cuMemFree(countersBuffer);
                    countersBuffer = null;
                }
            } catch (Throwable t) {
                log.warn("Ошибка освобождения буферов валидатора: {}", t.toString());
            } finally {
                CudaDevice.release();
            }
        }
    }
}
