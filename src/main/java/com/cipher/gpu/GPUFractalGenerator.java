package com.cipher.gpu;

import com.cipher.core.generator.FractalGenerator;
import jcuda.Pointer;
import jcuda.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;

import static jcuda.driver.JCudaDriver.*;

public class GPUFractalGenerator implements FractalGenerator {
    private static final Logger log = LoggerFactory.getLogger(GPUFractalGenerator.class);
    private static CUmodule module;
    private static CUfunction kernel;

    static {
        try {
            CudaDetector.initializeCudaContext();
            loadModule();
        } catch (Exception e) {
            log.error("GPU fractal generator initialization failed", e);
            throw new RuntimeException(e);
        }
    }

    private static void loadModule() throws Exception {
        try (InputStream is = GPUFractalGenerator.class.getResourceAsStream("/cuda/mandelbrot.ptx")) {
            if (is == null) throw new RuntimeException("mandelbrot.ptx not found in classpath");
            byte[] ptxBytes = is.readAllBytes();
            module = new CUmodule();
            cuModuleLoadData(module, ptxBytes);
            kernel = new CUfunction();
            cuModuleGetFunction(kernel, module, "mandelbrotKernel");
            log.info("Mandelbrot GPU kernel loaded");
        }
    }

    @Override
    public BufferedImage generate(int width, int height, double zoom,
                                  double offsetX, double offsetY, int maxIter) {
        int size = width * height * 3;
        CUdeviceptr d_output = new CUdeviceptr();
        cuMemAlloc(d_output, size);

        Pointer kernelParams = Pointer.to(
                Pointer.to(d_output),
                Pointer.to(new int[]{width}),
                Pointer.to(new int[]{height}),
                Pointer.to(new double[]{zoom}),
                Pointer.to(new double[]{offsetX}),
                Pointer.to(new double[]{offsetY}),
                Pointer.to(new int[]{maxIter})
        );

        int blockSize = 16;
        int gridX = (width + blockSize - 1) / blockSize;
        int gridY = (height + blockSize - 1) / blockSize;

        cuLaunchKernel(kernel,
                gridX, gridY, 1,
                blockSize, blockSize, 1,
                0, null,
                kernelParams, null);
        cuCtxSynchronize();

        byte[] hostData = new byte[size];
        cuMemcpyDtoH(Pointer.to(hostData), d_output, size);
        cuMemFree(d_output);

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] raster = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        System.arraycopy(hostData, 0, raster, 0, size);
        return result;
    }
}