package com.cipher.core.service.encryption.impl;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.cipher.core.dto.MandelbrotParams;
import com.cipher.core.dto.encryption.EncryptedData;
import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.FractalValidator;
import com.cipher.core.service.encryption.ImageEncryptor;
import com.cipher.core.service.encryption.MandelbrotService;
import com.cipher.core.service.encryption.SegmentShuffler;
import com.cipher.core.service.encryption.util.HKDF;
import com.cipher.core.service.encryption.util.XOROperation;
import com.cipher.core.utils.ImageUtils;
import com.cipher.core.utils.RasterUtils;
import javafx.geometry.Rectangle2D;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Шифрование изображения изображением-гаммой (множеством Мандельброта).
 * <p>
 * Что изменилось по сравнению с исходной версией:
 * <ul>
 *   <li>поиск валидного фрактала — {@link #findValidFractalParallel} без пула из
 *       {@code cpuCores-1} конкурентных GPU-генераций: кандидаты запрашиваются пакетами
 *       ({@link FractalGenerator#generateBatch}) и проверяются одним проходом валидатора;</li>
 *   <li>валидация — через {@link FractalValidator} (GPU-гистограмма), но решение по первому
 *       успешному кандидату принимает CPU: он подтверждает результат, а не перебирает весь кадр
 *       для каждого отброшенного кандидата;</li>
 *   <li>исключения задач больше не теряются: они логируются и приводят к внятной ошибке;</li>
 *   <li>в {@link EncryptedData} пишется {@link #ALGORITHM_VERSION}, чтобы файлы, зашифрованные
 *       другой палитрой, отвергались с сообщением, а не «расшифровывались» в шум.</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageEncryptorImpl implements ImageEncryptor {

    /**
     * Версия алгоритма генерации гаммы.
     * <p>
     * 1 — исходная (обратный счётчик итераций, {@code log}-сглаживание, CPU/GPU-палитры расходятся);
     * 2 — общая палитра {@link com.cipher.core.generator.FractalPalette} на IEEE-операциях,
     * CPU и GPU дают побитово равный результат.
     * </p>
     */
    public static final int ALGORITHM_VERSION = 2;

    private static final int MAX_ATTEMPTS = 100;
    private static final long SEARCH_TIMEOUT_SECONDS = 60;

    private final MandelbrotService mandelbrotService;
    private final SegmentShuffler segmentShuffler;
    private final ImageUtils imageUtils;
    private final FractalGenerator fractalGenerator;
    private final XOROperation xorOperation;
    private final FractalValidator fractalValidator;

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
        if (paramsPrng == null) {
            throw new IllegalStateException("Сессия не подготовлена: вызовите prepareSession()");
        }
        int attempt = attemptCount.incrementAndGet();
        MandelbrotParams params = mandelbrotService.generateParams(paramsPrng);
        log.debug("Генерация фрактала: попытка {}, params={}", attempt, params);

        fractal = fractalGenerator.generate(
                width, height,
                params.zoom(), params.offsetX(), params.offsetY(), params.maxIter()
        );
        if (fractalValidator.isValid(fractal)) {
            successfulAttempt = attempt;
        }
        return fractal;
    }

    /**
     * Ищет первый валидный фрактал.
     * <p>
     * Кандидаты генерируются пакетами (для CUDA — одним запуском ядра на пачку), проверяются
     * {@link FractalValidator} (для CUDA — гистограмма на GPU), после чего CPU подтверждает
     * первый успешный кандидат и фиксирует его. Порядок генерации параметров детерминирован:
     * {@code paramsPrng} опрашивается строго последовательно, поэтому расшифровка воспроизводит
     * ровно тот же фрактал по {@code attemptCount}.
     * </p>
     */
    @Override
    public BufferedImage findValidFractalParallel(int width, int height) {
        if (paramsPrng == null) {
            throw new IllegalStateException("Сессия не подготовлена: вызовите prepareSession()");
        }
        int batchSize = Math.max(1, fractalGenerator.batchCapacity());
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fractal-search");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<BufferedImage> search = executor.submit(() -> searchValidFractal(width, height, batchSize));
            BufferedImage result = search.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.fractal = result;
            return result;
        } catch (TimeoutException e) {
            log.error("Поиск фрактала не завершился за {} с", SEARCH_TIMEOUT_SECONDS);
            throw new IllegalStateException(
                    "Не удалось получить качественный фрактал за " + SEARCH_TIMEOUT_SECONDS + " с", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поиск фрактала прерван", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("Ошибка при поиске фрактала", cause);
            throw new IllegalStateException("Ошибка при поиске фрактала: " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Последовательно перебирает кандидатов, пока не найдётся пригодный.
     * <p>
     * Отбракованные кандидаты не подтверждаются на CPU: метрики уже посчитаны валидатором
     * (на GPU — одним запуском ядра). CPU-подтверждение выполняется только для кандидата,
     * который прошёл проверку — это тот самый «перехват первого успеха».
     * </p>
     */
    private BufferedImage searchValidFractal(int width, int height, int batchSize) {
        FractalValidator cpuConfirmation = distinctCpuConfirmation();
        int attemptsMade = 0;
        while (attemptsMade < MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Поиск фрактала прерван");
            }
            int count = Math.min(batchSize, MAX_ATTEMPTS - attemptsMade);

            // Параметры запрашиваются строго по одному из общего PRNG: порядок опроса
            // фиксирован, поэтому при расшифровке достаточно прокрутить PRNG attempts раз.
            int firstAttempt = attemptCount.get() + 1;
            List<MandelbrotParams> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(mandelbrotService.generateParams(paramsPrng));
            }
            attemptCount.addAndGet(count);
            attemptsMade += count;

            List<BufferedImage> images = fractalGenerator.generateBatch(width, height, batch);
            for (int i = 0; i < images.size(); i++) {
                BufferedImage candidate = images.get(i);
                if (candidate == null) {
                    continue;
                }
                FractalValidator.Metrics metrics = fractalValidator.metrics(candidate);
                if (!metrics.meetsQuality()) {
                    log.debug("Кандидат {} отклонён: {}", firstAttempt + i, metrics.rejectionReason());
                    continue;
                }
                // GPU-метрики могли быть посчитаны с иной точностью (float-гистограмма в ядре),
                // поэтому «первый успех» подтверждает CPU на том же кадре: решение о пригодности
                // гаммы остаётся за детерминированной реализацией, а не за драйвером.
                if (cpuConfirmation != null) {
                    FractalValidator.Metrics cpuMetrics = cpuConfirmation.metrics(candidate);
                    if (!cpuMetrics.meetsQuality()) {
                        log.debug("Кандидат {} прошёл GPU-проверку, но отклонён CPU: {}",
                                firstAttempt + i, cpuMetrics.rejectionReason());
                        continue;
                    }
                    metrics = cpuMetrics;
                }
                successfulAttempt = firstAttempt + i;
                log.info("Валидный фрактал найден на попытке {} проверкой {}: {}",
                        successfulAttempt, fractalValidator.backendName(), metrics);
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Не удалось получить качественный фрактал за " + MAX_ATTEMPTS + " попыток");
    }

    /**
     * Отдельная CPU-реализация для подтверждения первого успеха — или {@code null}, если
     * активный валидатор уже CPU (тогда подтверждать нечего: метрики те же самые).
     * <p>
     * Признак «валидатор на GPU» берётся из {@link FractalValidator#backendName()}: так решение
     * не зависит от конкретного класса и не требует привязки к CUDA-типам в сервисном слое.
     * </p>
     */
    private FractalValidator distinctCpuConfirmation() {
        String backend = fractalValidator.backendName();
        if (backend == null || !backend.startsWith("CUDA")) {
            return null;
        }
        return new com.cipher.core.generator.impl.CPUFractalValidator();
    }

    @Override
    public EncryptedData encryptWhole(BufferedImage originalImage) throws Exception {
        if (originalImage == null) {
            throw new IllegalArgumentException("Изображение не может быть null");
        }
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        if (fractal == null || fractal.getWidth() != width || fractal.getHeight() != height) {
            log.info("Запуск поиска валидного фрактала {}x{}...", width, height);
            findValidFractalParallel(width, height);
        }

        BufferedImage xored = xorOperation.xor(originalImage, fractal);
        BufferedImage finalImage = segmentShuffler.segmentAndShuffle(xored, segmentationPrng).shuffledImage();

        return new EncryptedData(ALGORITHM_VERSION, sessionSalt, successfulAttempt, 0, 0,
                width, height, width, height, imageUtils.imageToBytes(finalImage));
    }

    @Override
    public EncryptedData encryptPart(BufferedImage originalImage, Rectangle2D selectedArea) {
        if (originalImage == null) {
            throw new IllegalArgumentException("Изображение не может быть null");
        }
        if (selectedArea == null) {
            throw new IllegalArgumentException("Область шифрования не задана");
        }
        int origWidth = originalImage.getWidth();
        int origHeight = originalImage.getHeight();

        int sx = (int) selectedArea.getMinX();
        int sy = (int) selectedArea.getMinY();
        int areaWidth = (int) selectedArea.getWidth();
        int areaHeight = (int) selectedArea.getHeight();
        if (areaWidth <= 0 || areaHeight <= 0) {
            throw new IllegalArgumentException("Область шифрования пуста");
        }

        if (fractal == null || fractal.getWidth() != areaWidth || fractal.getHeight() != areaHeight) {
            log.info("Запуск поиска фрактала под размер области {}x{}...", areaWidth, areaHeight);
            findValidFractalParallel(areaWidth, areaHeight);
        }

        BufferedImage areaImage = originalImage.getSubimage(sx, sy, areaWidth, areaHeight);
        BufferedImage xoredArea = xorOperation.xor(areaImage, fractal);
        BufferedImage shuffledArea = segmentShuffler.segmentAndShuffle(xoredArea, segmentationPrng).shuffledImage();

        BufferedImage finalImage = new BufferedImage(origWidth, origHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = finalImage.createGraphics();
        g.drawImage(originalImage, 0, 0, null);
        g.drawImage(RasterUtils.toIntRgb(shuffledArea), sx, sy, null);
        g.dispose();

        return new EncryptedData(ALGORITHM_VERSION, sessionSalt, successfulAttempt, sx, sy,
                areaWidth, areaHeight, origWidth, origHeight, imageUtils.imageToBytes(finalImage));
    }
}
