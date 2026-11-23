package com.cipher.core.generator.impl;

import com.cipher.core.generator.FractalPalette;
import com.cipher.core.generator.FractalValidator;

import java.awt.image.BufferedImage;

/**
 * CPU-реализация проверки фрактала.
 * <p>
 * Используется как fallback, а также для <b>подтверждения</b> кандидата, который GPU признал
 * пригодным: итоговое решение всегда остаётся за CPU, но перебор пикселей на хосте для
 * отбракованных кандидатов больше не выполняется.
 * </p>
 * <p>
 * В отличие от исходной реализации, здесь нет приведения к {@code DataBufferInt}: пиксели
 * читаются через {@code getRGB}, поэтому проверка работает и для {@code TYPE_3BYTE_BGR},
 * который возвращает GPU-генератор.
 * </p>
 */
public class CPUFractalValidator implements FractalValidator {

    @Override
    public boolean isValid(BufferedImage fractal) {
        return metrics(fractal).meetsQuality();
    }

    @Override
    public Metrics metrics(BufferedImage fractal) {
        if (fractal == null) {
            throw new IllegalArgumentException("Изображение не может быть null");
        }
        int width = fractal.getWidth();
        int height = fractal.getHeight();
        int[] row = new int[width];
        int[] bins = new int[BINS];
        int internal = 0;
        int considered = 0;

        for (int y = 0; y < height; y++) {
            fractal.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x] & 0x00FFFFFF;
                if (rgb == INTERNAL_COLOR) {
                    internal++;
                    continue;
                }
                int bin = FractalPalette.hueBin(rgb, BINS);
                if (bin >= 0) {
                    bins[bin]++;
                    considered++;
                }
            }
        }

        int occupied = 0;
        int maxBin = 0;
        for (int c : bins) {
            if (c > 0) {
                occupied++;
            }
            if (c > maxBin) {
                maxBin = c;
            }
        }
        return new Metrics(width, height, internal, considered, occupied, maxBin);
    }
}
