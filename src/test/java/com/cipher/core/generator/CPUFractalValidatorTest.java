package com.cipher.core.generator;

import com.cipher.core.generator.impl.CPUFractalValidator;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU-валидатор обязан работать на любом типе растра: раньше проверка приводила растр
 * к {@code DataBufferInt} и падала на {@code TYPE_3BYTE_BGR}, который возвращал GPU-генератор.
 */
class CPUFractalValidatorTest {

    private final CPUFractalValidator validator = new CPUFractalValidator();

    private static BufferedImage spectrum(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bin = (x * FractalValidator.BINS) / width;
                image.setRGB(x, y, FractalPalette.hsbToRgb((float) bin / FractalValidator.BINS, 1.0f, 1.0f));
            }
        }
        return image;
    }

    @Test
    void metrics_shouldWorkFor3ByteBgr() {
        FractalValidator.Metrics metrics = validator.metrics(spectrum(360, 36, BufferedImage.TYPE_3BYTE_BGR));
        assertEquals(0, metrics.internalPixels());
        assertTrue(metrics.occupiedBins() >= FractalValidator.MIN_OCCUPIED_BINS,
                "все бины должны быть заняты, получено " + metrics.occupiedBins());
    }

    @Test
    void metrics_shouldBeSameForIntRgbAnd3ByteBgr() {
        FractalValidator.Metrics rgb = validator.metrics(spectrum(360, 36, BufferedImage.TYPE_INT_RGB));
        FractalValidator.Metrics bgr = validator.metrics(spectrum(360, 36, BufferedImage.TYPE_3BYTE_BGR));
        assertEquals(rgb.internalPixels(), bgr.internalPixels());
        assertEquals(rgb.occupiedBins(), bgr.occupiedBins());
        assertEquals(rgb.maxBinCount(), bgr.maxBinCount());
    }

    @Test
    void isValid_withTooManyInternalPixels_shouldReject() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                image.setRGB(x, y, x < 5 ? FractalPalette.INTERNAL_COLOR : 0xFF0000);
            }
        }
        FractalValidator.Metrics metrics = validator.metrics(image);
        assertEquals(50, metrics.internalPixels());
        assertFalse(metrics.meetsQuality());
        assertTrue(metrics.rejectionReason().contains("внутренних"));
    }

    @Test
    void hueBin_shouldClampHueOneToLastBin() {
        // hue ровно 1.0 в RGB недостижим, но чисто красный даёт bin 0, а чуть меньше 1 — последний бин.
        int rgb = FractalPalette.hsbToRgb(0.9999f, 1.0f, 1.0f);
        int bin = FractalPalette.hueBin(rgb, FractalValidator.BINS);
        assertTrue(bin >= 0 && bin < FractalValidator.BINS, "бин " + bin + " вне диапазона");
    }

    @Test
    void hueBin_shouldSkipInternalAndBlackPixels() {
        assertEquals(-1, FractalPalette.hueBin(FractalPalette.INTERNAL_COLOR, FractalValidator.BINS));
        assertEquals(-1, FractalPalette.hueBin(0x000000, FractalValidator.BINS));
    }
}
