package com.cipher.core.generator;

import java.awt.image.BufferedImage;

/**
 * Проверка пригодности фрактала как гаммы.
 * <p>
 * Критерий: доля «внутренних» пикселей не выше {@link #MAX_INTERNAL_RATIO} и распределение тонов
 * по {@link #BINS} бинам достаточно широкое. Реализации не должны зависеть от типа растра
 * (это и был баг: приведение к {@code DataBufferInt} падало на {@code TYPE_3BYTE_BGR}).
 * </p>
 */
public interface FractalValidator {

    /** Цвет внутренней области множества. */
    int INTERNAL_COLOR = 0x000040;

    /** Количество бинов гистограммы тона. */
    int BINS = 36;

    /** Порог доли внутренних пикселей. */
    double MAX_INTERNAL_RATIO = 0.25;

    /** Минимальное количество занятых бинов. */
    int MIN_OCCUPIED_BINS = 20;

    /** Максимальная доля пикселей в одном бине. */
    double MAX_BIN_RATIO = 0.25;

    /** Пригоден ли фрактал. */
    boolean isValid(BufferedImage fractal);

    /** Человекочитаемая причина отказа (для логов и UI). */
    default String rejectionReason(BufferedImage fractal) {
        Metrics m = metrics(fractal);
        return m.rejectionReason();
    }

    /** Метрики фрактала. */
    Metrics metrics(BufferedImage fractal);

    /** Имя бэкенда — для логов и бенчмарка. */
    default String backendName() {
        return getClass().getSimpleName();
    }

    /** Набор метрик пригодности. */
    record Metrics(int width, int height, int internalPixels, int consideredPixels,
                   int occupiedBins, int maxBinCount) {

        public double internalRatio() {
            int total = width * height;
            return total == 0 ? 1.0 : (double) internalPixels / total;
        }

        public double maxBinRatio() {
            return consideredPixels == 0 ? 1.0 : (double) maxBinCount / consideredPixels;
        }

        public boolean meetsQuality() {
            return internalRatio() <= FractalValidator.MAX_INTERNAL_RATIO
                    && occupiedBins >= FractalValidator.MIN_OCCUPIED_BINS
                    && maxBinRatio() <= FractalValidator.MAX_BIN_RATIO;
        }

        public String rejectionReason() {
            StringBuilder sb = new StringBuilder();
            if (internalRatio() > FractalValidator.MAX_INTERNAL_RATIO) {
                sb.append(String.format("внутренних точек %.1f%% > %.0f%%; ",
                        internalRatio() * 100, FractalValidator.MAX_INTERNAL_RATIO * 100));
            }
            if (occupiedBins < FractalValidator.MIN_OCCUPIED_BINS) {
                sb.append(String.format("занято бинов %d < %d; ",
                        occupiedBins, FractalValidator.MIN_OCCUPIED_BINS));
            }
            if (maxBinRatio() > FractalValidator.MAX_BIN_RATIO) {
                sb.append(String.format("макс. доля бина %.1f%% > %.0f%%; ",
                        maxBinRatio() * 100, FractalValidator.MAX_BIN_RATIO * 100));
            }
            return sb.length() == 0 ? "ok" : sb.toString().trim();
        }

        @Override
        public String toString() {
            return String.format("%dx%d internal=%.2f%% bins=%d/%d maxBin=%.2f%% %s",
                    width, height, internalRatio() * 100, occupiedBins, FractalValidator.BINS,
                    maxBinRatio() * 100, meetsQuality() ? "OK" : "REJECT");
        }
    }
}
