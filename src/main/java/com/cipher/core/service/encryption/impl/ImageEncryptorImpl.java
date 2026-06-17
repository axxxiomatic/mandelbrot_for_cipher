package com.cipher.core.service.encryption.impl;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.cipher.core.dto.*;
import com.cipher.core.dto.encryption.EncryptedData;
import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.service.encryption.*;
import com.cipher.core.service.encryption.util.HKDF;
import com.cipher.core.utils.*;
import com.cipher.core.service.encryption.util.XOROperation;
import javafx.geometry.Rectangle2D;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageEncryptorImpl implements ImageEncryptor {

    private final MandelbrotService mandelbrotService;
    private final SegmentShuffler segmentShuffler;
    private final ImageUtils imageUtils;
    private final FractalGenerator fractalGenerator;
    private final XOROperation xorOperation;

    private byte[] sessionSalt;
    private SecureRandom paramsPrng;
    private SecureRandom segmentationPrng;
    private AtomicInteger attemptCount;
    private BufferedImage fractal;
    private int successfulAttempt;

    /**
     * Подготавливает сессию шифрования на основе общего секрета.
     * Все криптографические материалы хранятся внутри ImageEncryptor.
     *
     * @param sharedSecret общий секрет от DH
     * @throws Exception если ошибка инициализации
     */
    @Override
    public void prepareSession(byte[] sharedSecret) throws Exception {
        if (sharedSecret == null) {
            throw new IllegalArgumentException("Shared secret cannot be null");
        }
        byte[] salt = new byte[16];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(salt);
        this.sessionSalt = salt.clone();

        byte[] prk = HKDF.extract(salt, sharedSecret);
        byte[] keyFractalParams = HKDF.expand(prk, "fractal-params".getBytes(StandardCharsets.UTF_8), 32);
        byte[] keySegmentation = HKDF.expand(prk, "segmentation".getBytes(StandardCharsets.UTF_8), 32);

        this.paramsPrng = SecureRandom.getInstance("SHA1PRNG");
        this.paramsPrng.setSeed(keyFractalParams);

        this.segmentationPrng = SecureRandom.getInstance("SHA1PRNG");
        this.segmentationPrng.setSeed(keySegmentation);

        this.attemptCount = new AtomicInteger(0);
        this.successfulAttempt = 0;
        this.fractal = null;

        log.info("Сессия подготовлена, сгенерирована новая соль");
    }

    /**
     * Генерирует следующий фрактал для текущей сессии.
     * Увеличивает счётчик попыток.
     */
    @Override
    public BufferedImage generateNextFractal(int width, int height) {
        successfulAttempt = attemptCount.incrementAndGet();
        MandelbrotParams params = mandelbrotService.generateParams(paramsPrng);
        log.debug("Генерация фрактала: попытка {}, params={}", attemptCount, params);

        fractal = fractalGenerator.generate(
                width, height,
                params.zoom(), params.offsetX(), params.offsetY(), params.maxIter()
        );
        return fractal;
    }

    @Override
    public BufferedImage findValidFractalParallel(int width, int height) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, cpuCores - 1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        AtomicReference<BufferedImage> resultImage = new AtomicReference<>(null);
        final int maxAttempts = 100;
        final Object lock = new Object();   // для синхронизации счётчика и генерации параметров

        Callable<Void> searchTask = () -> {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                MandelbrotParams params;
                int currentAttempt;

                synchronized (lock) {
                    if (resultImage.get() != null || attemptCount.get() >= maxAttempts) {
                        return null;   // результат уже найден или лимит исчерпан
                    }
                    currentAttempt = attemptCount.incrementAndGet();
                    params = mandelbrotService.generateParams(paramsPrng);
                }

                BufferedImage img = fractalGenerator.generate(
                        width, height,
                        params.zoom(), params.offsetX(), params.offsetY(), params.maxIter()
                );

                if (mandelbrotService.isFractalValid(img)) {
                    synchronized (lock) {
                        if (resultImage.compareAndSet(null, img)) {
                            successfulAttempt = currentAttempt;
                            log.info("Валидный фрактал найден на попытке {}", currentAttempt);
                        }
                    }
                    return null;   // независимо от того, мы ли установили результат, выходим
                }
            }
        };

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(searchTask));
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        // отменяем оставшиеся задачи
        for (Future<Void> f : futures) {
            f.cancel(true);
        }

        if (resultImage.get() == null) {
            throw new RuntimeException("Не удалось получить качественный фрактал за " + maxAttempts + " попыток");
        }

        this.fractal = resultImage.get();
        return this.fractal;
    }

    /**
     * Выполняет полное шифрование изображения.
     * Процесс включает:
     * <ol>
     *   <li>Генерацию фрактала с текущими параметрами</li>
     *   <li>Применение XOR к оригинальному изображению и фракталу</li>
     *   <li>Сегментацию и перемешивание результата XOR</li>
     *   <li>Сохранение в бинарный файл с метаданными</li>
     * </ol>
     *
     * @param originalImage оригинальное изображение для шифрования
     */
    @Override
    public EncryptedData encryptWhole(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        if (fractal == null || fractal.getWidth() != width || fractal.getHeight() != height) {
            log.info("Запуск параллельного поиска валидного фрактала...");
            findValidFractalParallel(width, height);
        }

        BufferedImage xored = xorOperation.xor(originalImage, fractal);
        BufferedImage finalImage = segmentShuffler.segmentAndShuffle(xored, segmentationPrng).shuffledImage();

        return new EncryptedData(sessionSalt, successfulAttempt, 0, 0,
                width, height, width, height, imageUtils.imageToBytes(finalImage));
    }

    /**
     * Выполняет частичное шифрование выбранной области изображения.
     * Шифруется только указанная область, остальная часть изображения остаётся неизменной.
     *
     * @param originalImage оригинальное изображение
     * @param selectedArea  прямоугольная область для шифрования
     */
    @Override
    public EncryptedData encryptPart(BufferedImage originalImage, Rectangle2D selectedArea) {
        int origWidth = originalImage.getWidth();
        int origHeight = originalImage.getHeight();

        int sx = (int) selectedArea.getMinX();
        int sy = (int) selectedArea.getMinY();
        int areaWidth = (int) selectedArea.getWidth();
        int areaHeight = (int) selectedArea.getHeight();

        if (fractal == null || fractal.getWidth() != areaWidth || fractal.getHeight() != areaHeight) {
            log.info("Запуск параллельного поиска фрактала под размер области...");
            findValidFractalParallel(areaWidth, areaHeight);
        }

        BufferedImage areaImage = originalImage.getSubimage(sx, sy, areaWidth, areaHeight);
        BufferedImage xoredArea = xorOperation.xor(areaImage, fractal);
        BufferedImage shuffledArea = segmentShuffler.segmentAndShuffle(xoredArea, segmentationPrng).shuffledImage();

        BufferedImage finalImage = new BufferedImage(origWidth, origHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = finalImage.createGraphics();
        g.drawImage(originalImage, 0, 0, null);
        g.drawImage(shuffledArea, sx, sy, null);
        g.dispose();

        return new EncryptedData(sessionSalt, successfulAttempt, sx, sy, areaWidth, areaHeight,
                origWidth, origHeight, imageUtils.imageToBytes(finalImage));
    }
}