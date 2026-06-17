package com.cipher.core.generator.impl;

import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.threading.MandelbrotThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CPUFractalGenerator implements FractalGenerator {
    private static final Logger log = LoggerFactory.getLogger(CPUFractalGenerator.class);

    @Override
    public BufferedImage generate(int width, int height, double zoom,
                                  double offsetX, double offsetY, int maxIter) {
        if (width <= 0 || height <= 0 || zoom <= 0 || maxIter <= 0) {
            throw new IllegalArgumentException("Invalid fractal parameters");
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors);
        List<Future<?>> futures = new ArrayList<>();

        int chunkWidth = width / processors;
        for (int i = 0; i < processors; i++) {
            int startX = i * chunkWidth;
            int w = (i == processors - 1) ? width - startX : chunkWidth;
            futures.add(executor.submit(new MandelbrotThread(
                    startX, 0, w, height,
                    zoom, maxIter, offsetX, offsetY, image
            )));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Fractal generation interrupted", e);
                executor.shutdownNow();
                return null;
            } catch (ExecutionException e) {
                log.error("Fractal generation error", e);
                executor.shutdownNow();
                throw new RuntimeException("CPU fractal generation failed", e);
            }
        }
        executor.shutdown();
        return image;
    }
}