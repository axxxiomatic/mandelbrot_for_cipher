package com.cipher.core.generator;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки {@link FractalPalette}.
 * <p>
 * Главное здесь — {@link #hsbMatchesJdk()}: палитра обязана быть <b>точной копией</b>
 * {@code java.awt.Color.HSBtoRGB} на {@code float}. Если она разойдётся с JDK, то разойдётся
 * и с ядром CUDA, которое повторяет ту же арифметику, — а фрактал используется как гамма,
 * и любое расхождение делает файл нерасшифровываемым на другой машине.
 * </p>
 */
class FractalPaletteTest {

    @Test
    void hsbMatchesJdk() {
        for (int hueStep = 0; hueStep <= 1000; hueStep++) {
            float hue = hueStep / 1000.0f;
            for (float saturation : new float[]{0.0f, 0.25f, 0.8f, 1.0f}) {
                for (float brightness : new float[]{0.0f, 0.5f, 1.0f}) {
                    assertEquals(Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF,
                            FractalPalette.hsbToRgb(hue, saturation, brightness) & 0x00FFFFFF,
                            "hue=" + hue + " s=" + saturation + " b=" + brightness);
                }
            }
        }
    }

    @Test
    void hueFor_staysInUnitInterval() {
        for (int iterations = 0; iterations < 1250; iterations += 7) {
            for (double modZ : new double[]{2.0, 2.5, 10.0, 1e6, 1e300}) {
                double hue = FractalPalette.hueFor(iterations, 1250, modZ);
                assertTrue(hue >= 0.0 && hue < 1.0,
                        "hue=" + hue + " при iterations=" + iterations + " modZ=" + modZ);
            }
        }
    }

    @Test
    void hueFor_isNaNSafe() {
        // modZ = 0 невозможен в цикле (|z| >= 2 при выходе), но деление не должно давать NaN.
        double hue = FractalPalette.hueFor(10, 250, 0.0);
        assertTrue(Double.isFinite(hue), "hue не должен быть NaN/Inf: " + hue);
    }

    @Test
    void colorFor_internalPointsUseInternalColor() {
        assertEquals(FractalPalette.INTERNAL_COLOR, FractalPalette.colorFor(250, 250, 2.0));
        assertEquals(FractalPalette.INTERNAL_COLOR, FractalPalette.colorFor(300, 250, 2.0));
    }

    @Test
    void colorFor_externalPointsAreOpaque() {
        int rgb = FractalPalette.colorFor(10, 250, 2.5);
        assertEquals(0xFF, (rgb >>> 24) & 0xFF, "цвет должен быть непрозрачным");
        assertTrue((rgb & 0x00FFFFFF) != FractalPalette.INTERNAL_COLOR);
    }

    @Test
    void hueBin_distributesSpectrumOverAllBins() {
        boolean[] seen = new boolean[FractalValidator.BINS];
        for (int i = 0; i < FractalValidator.BINS; i++) {
            int rgb = FractalPalette.hsbToRgb((float) i / FractalValidator.BINS, 1.0f, 1.0f);
            int bin = FractalPalette.hueBin(rgb, FractalValidator.BINS);
            assertTrue(bin >= 0 && bin < FractalValidator.BINS, "бин " + bin + " вне диапазона");
            seen[bin] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "бин " + i + " не достигнут ни одним чистым тоном");
        }
    }
}
