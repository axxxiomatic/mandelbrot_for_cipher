package com.cipher.gpu;

import jcuda.driver.JCudaDriver;
import jcuda.driver.CUdevice;
import jcuda.driver.CUcontext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jcuda.driver.JCudaDriver.*;

public class CudaDetector {
    private static final Logger log = LoggerFactory.getLogger(CudaDetector.class);
    private static volatile Boolean cudaAvailable = null;
    private static volatile boolean contextInitialized = false;

    public static boolean isCudaAvailable() {
        if (cudaAvailable == null) {
            synchronized (CudaDetector.class) {
                if (cudaAvailable == null) {
                    try {
                        JCudaDriver.setExceptionsEnabled(true);
                        cuInit(0);
                        CUdevice dev = new CUdevice();
                        cuDeviceGet(dev, 0);
                        cudaAvailable = true;
                        log.info("CUDA device detected");
                    } catch (UnsatisfiedLinkError | NoClassDefFoundError | Exception e) {
                        cudaAvailable = false;
                        log.info("CUDA not available: {}", e.toString());
                    }
                }
            }
        }
        return cudaAvailable;
    }

    public static synchronized void initializeCudaContext() {
        if (!isCudaAvailable() || contextInitialized) return;
        try {
            CUdevice device = new CUdevice();
            cuDeviceGet(device, 0);
            CUcontext context = new CUcontext();
            cuCtxCreate(context, 0, device);
            contextInitialized = true;
            log.info("CUDA context initialized");
        } catch (Exception e) {
            log.error("Failed to initialize CUDA context", e);
            cudaAvailable = false;
        }
    }
}