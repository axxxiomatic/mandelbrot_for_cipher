package com.cipher.core.benchmark;

import com.cipher.core.generator.impl.CPUFractalGenerator;
import com.cipher.gpu.GPUFractalGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Паритет палитры CPU и GPU.
 * <p>
 * Фрактал используется как гамма для XOR, поэтому расхождение хотя бы в одном пикселе означает
 * несовместимость шифрования между машинами с CUDA и без неё. Именно этот тест должен был
 * поймать исходный дефект, когда CPU считал итерации в обратную сторону, а GPU — в прямую,
 * и {@code hue} получался зеркальным.
 * </p>
 * Тест выполняется только при доступной CUDA (иначе — {@code Assumptions.assumeTrue} → skipped).
 */
class FractalPaletteParityTest {

    private static final boolean CUDA = cudaAvailable();

    private static boolean cudaAvailable() {
        try {
            return com.cipher.gpu.CudaDetector.isCudaAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @ParameterizedTest(name = "{0}x{1} zoom={2} iter={3}")
    @DisplayName("GPU-палитра совпадает с CPU побитово")
    @CsvSource({
            "320, 240, 10000, 250",
            "320, 240, 50000, 750",
            "640, 480, 100000, 1250",
            "640, 360, 20000, 500"
    })
    void gpuMatchesCpu(int width, int height, double zoom, int maxIter) {
        Assumptions.assumeTrue(CUDA, "CUDA недоступна — тест паритета пропущен");

        BufferedImage cpu = new CPUFractalGenerator().generate(width, height, zoom, -0.7, 0.25, maxIter);
        BufferedImage gpu = new GPUFractalGenerator().generate(width, height, zoom, -0.7, 0.25, maxIter);

        ImageDiff.Diff diff = ImageDiff.compare(cpu, gpu);
        assertEquals(0, diff.differingPixels(), () -> String.format(
                "Палитры разошлись: %d/%d пикселей, макс. Δканала=%d. " +
                        "GPU-гамма несовместима с CPU — зашифрованный файл не расшифруется.",
                diff.differingPixels(), diff.totalPixels(), diff.maxChannelDelta()));
        assertEquals(ImageDiff.sha256(cpu), ImageDiff.sha256(gpu), "Отпечатки фракталов должны совпадать");
    }

    @Test
    @DisplayName("Метрики качества CPU-фрактала детерминированы")
    void metricsAreDeterministic() {
        BufferedImage a = new CPUFractalGenerator().generate(320, 240, 50_000, -0.7, 0.25, 500);
        BufferedImage b = new CPUFractalGenerator().generate(320, 240, 50_000, -0.7, 0.25, 500);
        FractalMetrics ma = FractalMetrics.of(a);
        FractalMetrics mb = FractalMetrics.of(b);

        assertEquals(ma.internalPixels(), mb.internalPixels());
        assertEquals(ma.occupiedBins(), mb.occupiedBins());
        assertEquals(ma.maxBinRatio(), mb.maxBinRatio(), 1e-12);

        // Метрики читают растр через getRGB и не зависят от типа изображения:
        // именно на этом ломался DataBufferInt-каст в MandelbrotService#isFractalValid.
        BufferedImage bgr = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(a, 0, 0, null);
        assertEquals(ma.internalPixels(), FractalMetrics.of(bgr).internalPixels());
        assertTrue(java.util.Objects.equals(ma.occupiedBins(), FractalMetrics.of(bgr).occupiedBins()));
    }
}
