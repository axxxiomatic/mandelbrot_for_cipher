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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-реализация генератора фрактала: кадр делится на вертикальные полосы, по одной на ядро.
 * <p>
 * Пул потоков создаётся один раз и переиспользуется между кадрами: поиск валидного фрактала
 * может перебрать десятки кандидатов, и создавать по пулу на каждый кадр (как было раньше)
 * дороже самой генерации. Потоки — демоны, поэтому незакрытый пул не держит JVM.
 * </p>
 * <p>
 * Класс не бросает исключений в статическом инициализаторе и не держит состояния контекста,
 * поэтому безопасен в качестве fallback-реализации, когда CUDA недоступна или упала.
 * </p>
 */
public class CPUFractalGenerator implements FractalGenerator {
    private static final Logger log = LoggerFactory.getLogger(CPUFractalGenerator.class);

    private final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private volatile ExecutorService executor;

    @Override
    public BufferedImage generate(int width, int height, double zoom,
                                  double offsetX, double offsetY, int maxIter) {
        if (width <= 0 || height <= 0 || zoom <= 0 || maxIter <= 0) {
            throw new IllegalArgumentException("Invalid fractal parameters");
        }
        if (shutdown.get()) {
            throw new IllegalStateException("CPUFractalGenerator уже остановлен");
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ExecutorService pool = executor();

        int chunkWidth = Math.max(1, width / processors);
        List<Future<?>> futures = new ArrayList<>(processors);
        for (int i = 0; i < processors; i++) {
            int startX = i * chunkWidth;
            if (startX >= width) {
                break;
            }
            int w = (i == processors - 1) ? width - startX : Math.min(chunkWidth, width - startX);
            futures.add(pool.submit(new MandelbrotThread(
                    startX, 0, w, height,
                    zoom, maxIter, offsetX, offsetY, image
            )));
        }

        try {
            for (Future<?> future : futures) {
                // Исключение задачи больше не теряется: без get() отказ потока проходил молча,
                // и на выходе получался частично пустой кадр.
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            throw new IllegalStateException("Генерация фрактала прервана", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("Ошибка генерации фрактала", cause);
            throw new IllegalStateException("Ошибка генерации фрактала: " + cause.getMessage(), cause);
        }
        return image;
    }

    private ExecutorService executor() {
        ExecutorService current = executor;
        if (current == null) {
            synchronized (this) {
                current = executor;
                if (current == null) {
                    current = Executors.newFixedThreadPool(processors, runnable -> {
                        Thread thread = new Thread(runnable, "mandelbrot-cpu");
                        thread.setDaemon(true);
                        return thread;
                    });
                    executor = current;
                }
            }
        }
        return current;
    }

    /** Останавливает пул потоков. Вызывается Spring как {@code destroyMethod}. */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public String backendName() {
        return "CPU(" + processors + " потоков)";
    }

    @Override
    public int batchCapacity() {
        // Пакетный режим даёт выигрыш только на GPU; на CPU кадры идут чередой,
        // чтобы не держать в памяти сразу несколько фракталов 1920x1080.
        return 1;
    }
}
