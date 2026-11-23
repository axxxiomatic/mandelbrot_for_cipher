package com.cipher.core.service.encryption;

import com.cipher.core.dto.MandelbrotParams;
import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.FractalValidator;

import java.awt.image.BufferedImage;
import java.security.SecureRandom;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author @dankotyt Danil Kotlyarov
 * <p>
 * Генерация параметров фрактала и делегирование работы выбранному бэкенду.
 * </p>
 * <p>
 * Класс больше не наследует {@code JPanel}: Swing-компонент в Spring-бине сервисного слоя был
 * рудиментом, из-за которого сервис нельзя было использовать в headless-тестах и в CLI.
 * Валидация фрактала вынесена в {@link FractalValidator}, чтобы её можно было считать на GPU.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class MandelbrotService {
    private static final Logger logger = LoggerFactory.getLogger(MandelbrotService.class);

    private final FractalGenerator generator;
    private final FractalValidator validator;

    /**
     * Генерирует параметры множества Мандельброта.
     * <p>
     * Диапазоны сохранены как в исходной версии: zoom 10 000…108 000 с шагом 140,
     * offsetX ∈ [-0.9998, 0.45], offsetY — в одном из двух «интересных» окон,
     * maxIter 250…1250 с шагом 10.
     * </p>
     */
    public MandelbrotParams generateParams(SecureRandom prng) {
        if (prng == null) {
            throw new IllegalArgumentException("PRNG cannot be null");
        }
        double zoom = 10_000 + prng.nextInt(701) * 140;
        double offsetX = -0.9998 + prng.nextDouble() * (0.45 + 0.9998);
        double offsetY = prng.nextBoolean()
                ? -0.7 + prng.nextDouble() * 0.6
                : 0.1 + prng.nextDouble() * 0.6;
        int maxIter = 250 + prng.nextInt(101) * 10;
        return new MandelbrotParams(zoom, offsetX, offsetY, maxIter);
    }

    /**
     * Генерирует изображение фрактала выбранным бэкендом (CPU или CUDA).
     */
    public BufferedImage generateImage(int width, int height,
                                       double zoom, double offsetX, double offsetY, int maxIter) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }
        if (zoom <= 0) {
            throw new IllegalArgumentException("Zoom must be positive: " + zoom);
        }
        if (maxIter <= 0) {
            throw new IllegalArgumentException("MAX_ITER must be positive: " + maxIter);
        }
        logger.debug("Генерация фрактала {}x{} бэкендом {}", width, height, generator.backendName());
        return generator.generate(width, height, zoom, offsetX, offsetY, maxIter);
    }

    /** Проверяет пригодность фрактала (на GPU, если он доступен). */
    public boolean isFractalValid(BufferedImage fractal) {
        if (fractal == null) {
            throw new IllegalArgumentException("Fractal cannot be null");
        }
        return validator.isValid(fractal);
    }

    /** Метрики пригодности — для логов, бенчмарка и UI. */
    public FractalValidator.Metrics fractalMetrics(BufferedImage fractal) {
        return validator.metrics(fractal);
    }

    /** Имя активного генератора. */
    public String backendName() {
        return generator.backendName();
    }
}
