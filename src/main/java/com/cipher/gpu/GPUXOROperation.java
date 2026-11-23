package com.cipher.gpu;

import com.cipher.core.service.encryption.util.XOROperation;
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
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;

/**
 * Побайтовый XOR двух изображений на CUDA.
 * <p>
 * Исходная реализация падала с {@code ClassCastException} на каждом вызове при дешифровании:
 * она кастовала растр к {@code DataBufferByte}, а {@code ImageUtils#bytesToImage} возвращает
 * {@code TYPE_INT_RGB} с {@code DataBufferInt}. Здесь оба входа проходят через
 * {@link RasterUtils#toBgrBytes}, поэтому поддерживаются {@code TYPE_INT_RGB},
 * {@code TYPE_INT_ARGB} и {@code TYPE_3BYTE_BGR}, а результат всегда {@code TYPE_INT_RGB}.
 * </p>
 */
public class GPUXOROperation implements XOROperation {

    private static final Logger log = LoggerFactory.getLogger(GPUXOROperation.class);

    private static final int BLOCK = 256;

    private final Object lock = new Object();
    private CUmodule module;
    private CUfunction kernel;

    private CUdeviceptr bufferA;
    private CUdeviceptr bufferB;
    private CUdeviceptr bufferOut;
    private long bufferBytes;

    public GPUXOROperation() {
        if (!CudaDevice.acquire()) {
            throw new CudaException("CUDA недоступна");
        }
        try {
            if (!CudaDevice.makeCurrent()) {
                throw new CudaException("Не удалось установить контекст CUDA");
            }
            module = KernelCompiler.loadModule("/cuda/xor.cu", "/cuda/xor.ptx");
            kernel = new CUfunction();
            cuModuleGetFunction(kernel, module, "xorKernel");
            log.info("GPU XOR готов");
        } catch (Throwable t) {
            CudaDevice.release();
            if (t instanceof CudaException cudaException) {
                throw cudaException;
            }
            throw new CudaException("Инициализация GPU XOR не удалась", t);
        }
    }

    @Override
    public BufferedImage xor(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Изображения не могут быть null");
        }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("Images must have same dimensions for XOR");
        }
        int width = a.getWidth();
        int height = b.getHeight();

        byte[] dataA = RasterUtils.toBgrBytes(a);
        byte[] dataB = RasterUtils.toBgrBytes(b);
        int size = dataA.length;

        synchronized (lock) {
            if (!CudaDevice.makeCurrent()) {
                throw new CudaException("Контекст CUDA недоступен");
            }
            allocate(size);

            cuMemcpyHtoD(bufferA, Pointer.to(dataA), size);
            cuMemcpyHtoD(bufferB, Pointer.to(dataB), size);

            Pointer params = Pointer.to(
                    Pointer.to(bufferA),
                    Pointer.to(bufferB),
                    Pointer.to(bufferOut),
                    Pointer.to(new int[]{size})
            );

            int gridSize = (size + BLOCK - 1) / BLOCK;
            cuLaunchKernel(kernel, gridSize, 1, 1, BLOCK, 1, 1, 0, null, params, null);
            cuCtxSynchronize();

            byte[] out = new byte[size];
            cuMemcpyDtoH(Pointer.to(out), bufferOut, size);
            return RasterUtils.fromBgrBytes(out, width, height);
        }
    }

    private void allocate(int bytes) {
        if (bufferA != null && bufferBytes >= bytes) {
            return;
        }
        freeBuffers();
        bufferA = new CUdeviceptr();
        bufferB = new CUdeviceptr();
        bufferOut = new CUdeviceptr();
        cuMemAlloc(bufferA, bytes);
        cuMemAlloc(bufferB, bytes);
        cuMemAlloc(bufferOut, bytes);
        bufferBytes = bytes;
    }

    private void freeBuffers() {
        if (bufferA != null) {
            cuMemFree(bufferA);
        }
        if (bufferB != null) {
            cuMemFree(bufferB);
        }
        if (bufferOut != null) {
            cuMemFree(bufferOut);
        }
        bufferA = null;
        bufferB = null;
        bufferOut = null;
        bufferBytes = 0;
    }

    /** Вызывается Spring как {@code destroyMethod}. */
    public void shutdown() {
        synchronized (lock) {
            try {
                freeBuffers();
            } catch (Throwable t) {
                log.warn("Ошибка освобождения буферов XOR: {}", t.toString());
            } finally {
                CudaDevice.release();
            }
        }
    }
}
