package com.cipher.core.benchmark;

import java.awt.image.BufferedImage;

/**
 * Метрики качества изображения-фрактала, используемого как гамма для XOR.
 * <p>
 * Считаются те же величины, на которые опирается критерий пригодности фрактала
 * в {@link com.cipher.core.generator.FractalValidator}: доля «внутренних» пикселей и распределение
 * цветовых тонов по бинам. В отличие от продовой реализации, здесь нет приведения растра
 * к {@code DataBufferInt}, поэтому метрики корректно считаются для изображений любого типа
 * (в том числе для {@code TYPE_3BYTE_BGR}, который возвращает GPU-генератор).
 * </p>
 */
public record FractalMetrics(
        int width,
        int height,
        int internalPixels,
        double internalRatio,
        int occupiedBins,
        double maxBinRatio,
        double hueEntropyBits
) {

    /** Количество бинов гистограммы тона — как в {@code FractalValidator#BINS}. */
    public static final int BINS = 36;

    /** Цвет внутренней области множества (совпадает с CPU- и GPU-генераторами). */
    private static final int INTERNAL_COLOR = 0x000040;

    /** Порог пригодности по доле внутренних пикселей. */
    public static final double MAX_INTERNAL_RATIO = 0.25;

    /** Минимальное число занятых бинов тона. */
    public static final int MIN_OCCUPIED_BINS = 20;

    /** Максимальная доля пикселей в одном бине. */
    public static final double MAX_BIN_RATIO = 0.25;

    public static FractalMetrics of(BufferedImage image) {
        return of(image, BINS);
    }

    public static FractalMetrics of(BufferedImage image, int bins) {
        int width = image.getWidth();
        int height = image.getHeight();
        int total = width * height;
        int[] binCounts = new int[bins];
        int internal = 0;
        int considered = 0;

        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x] & 0x00FFFFFF;
                if (rgb == INTERNAL_COLOR) {
                    internal++;
                    continue;
                }
                // Та же функция, что использует продовый валидатор: критерий в бенчмарке
                // и в приложении не должен расходиться (включая клэмп индекса бина).
                int bin = com.cipher.core.generator.FractalPalette.hueBin(rgb, bins);
                if (bin >= 0) {
                    binCounts[bin]++;
                    considered++;
                }
            }
        }

        int occupied = 0;
        int maxCount = 0;
        for (int c : binCounts) {
            if (c > 0) {
                occupied++;
            }
            if (c > maxCount) {
                maxCount = c;
            }
        }

        double entropy = 0.0;
        if (considered > 0) {
            for (int c : binCounts) {
                if (c == 0) {
                    continue;
                }
                double p = (double) c / considered;
                entropy -= p * (Math.log(p) / Math.log(2.0));
            }
        }

        return new FractalMetrics(
                width,
                height,
                internal,
                total == 0 ? 1.0 : (double) internal / total,
                occupied,
                considered == 0 ? 1.0 : (double) maxCount / considered,
                entropy
        );
    }

    /** Пригоден ли фрактал как гамма: те же три условия, что и в проде. */
    public boolean meetsQuality() {
        return internalRatio <= MAX_INTERNAL_RATIO
                && occupiedBins >= MIN_OCCUPIED_BINS
                && maxBinRatio <= MAX_BIN_RATIO;
    }

    /** Причина отказа — чтобы провал прогона был диагностируемым, а не «не повезло». */
    public String rejectionReason() {
        StringBuilder sb = new StringBuilder();
        if (internalRatio > MAX_INTERNAL_RATIO) {
            sb.append(String.format("внутренних точек %.1f%% > %.0f%%; ",
                    internalRatio * 100, MAX_INTERNAL_RATIO * 100));
        }
        if (occupiedBins < MIN_OCCUPIED_BINS) {
            sb.append(String.format("занято бинов %d < %d; ", occupiedBins, MIN_OCCUPIED_BINS));
        }
        if (maxBinRatio > MAX_BIN_RATIO) {
            sb.append(String.format("макс. доля бина %.1f%% > %.0f%%; ",
                    maxBinRatio * 100, MAX_BIN_RATIO * 100));
        }
        return sb.length() == 0 ? "ok" : sb.toString().trim();
    }

    @Override
    public String toString() {
        return String.format(
                "%dx%d internal=%.2f%% bins=%d/%d maxBin=%.2f%% entropy=%.3f bit %s",
                width, height, internalRatio * 100, occupiedBins, BINS,
                maxBinRatio * 100, hueEntropyBits, meetsQuality() ? "OK" : "REJECT");
    }
}
