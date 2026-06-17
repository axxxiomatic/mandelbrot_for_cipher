package com.cipher.gpu;

import com.cipher.core.service.encryption.util.XOROperation;
import jcuda.Pointer;
import jcuda.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;

import static jcuda.driver.JCudaDriver.*;

public class GPUXOROperation implements XOROperation {
    private static final Logger log = LoggerFactory.getLogger(GPUXOROperation.class);
    private static CUmodule module;
    private static CUfunction kernel;

    static {
        try {
            CudaDetector.initializeCudaContext();
            loadModule();
        } catch (Exception e) {
            log.error("GPU XOR initialization failed", e);
            throw new RuntimeException(e);
        }
    }

    private static void loadModule() throws Exception {
        try (InputStream is = GPUXOROperation.class.getResourceAsStream("/cuda/xor.ptx")) {
            if (is == null) throw new RuntimeException("xor.ptx not found in classpath");
            byte[] ptxBytes = is.readAllBytes();
            module = new CUmodule();
            cuModuleLoadData(module, ptxBytes);
            kernel = new CUfunction();
            cuModuleGetFunction(kernel, module, "xorKernel");
            log.info("XOR GPU kernel loaded");
        }
    }

    @Override
    public BufferedImage xor(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("Images must have same dimensions for XOR");
        }
        int width = a.getWidth();
        int height = a.getHeight();
        byte[] dataA = ((DataBufferByte) a.getRaster().getDataBuffer()).getData();
        byte[] dataB = ((DataBufferByte) b.getRaster().getDataBuffer()).getData();
        int size = dataA.length;

        CUdeviceptr d_a = new CUdeviceptr();
        CUdeviceptr d_b = new CUdeviceptr();
        CUdeviceptr d_out = new CUdeviceptr();
        cuMemAlloc(d_a, size);
        cuMemAlloc(d_b, size);
        cuMemAlloc(d_out, size);
        cuMemcpyHtoD(d_a, Pointer.to(dataA), size);
        cuMemcpyHtoD(d_b, Pointer.to(dataB), size);

        Pointer kernelParams = Pointer.to(
                Pointer.to(d_a),
                Pointer.to(d_b),
                Pointer.to(d_out),
                Pointer.to(new int[]{size})
        );

        int blockSize = 256;
        int gridSize = (size + blockSize - 1) / blockSize;
        cuLaunchKernel(kernel, gridSize, 1, 1, blockSize, 1, 1, 0, null, kernelParams, null);
        cuCtxSynchronize();

        byte[] outData = new byte[size];
        cuMemcpyDtoH(Pointer.to(outData), d_out, size);
        cuMemFree(d_a);
        cuMemFree(d_b);
        cuMemFree(d_out);

        BufferedImage result = new BufferedImage(width, height, a.getType());
        byte[] raster = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        System.arraycopy(outData, 0, raster, 0, size);
        return result;
    }
}