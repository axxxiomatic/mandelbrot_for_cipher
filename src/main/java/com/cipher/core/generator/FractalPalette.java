package com.cipher.core.generator;

/**
 * Единая палитра фрактала — общая для CPU- и GPU-генераторов.
 * <p>
 * <b>Почему это отдельный класс.</b> Фрактал здесь используется как гамма для XOR: если CPU и GPU
 * выдадут разные пиксели, файл, зашифрованный на одной машине, молча не расшифруется на другой.
 * Ранее CPU считал итерации в обратную сторону ({@code iter = MAX_ITER; iter--}), а GPU — в прямую,
 * из-за чего {@code smooth} и {@code hue} получались зеркальными.
 * </p>
 * <p>
 * <b>Почему палитра не использует {@code log}.</b> Формула smooth-coloring
 * {@code n + 1 - log(log|z|)/log 2} требует трансцендентных функций, а {@code Math.log} (JVM) и
 * {@code log} (CUDA libdevice) не обязаны совпадать бит-в-бит: достаточно разницы в 1 ulp, чтобы
 * значение уехало за границу округления при приведении к цвету. Поэтому прогресс считается через
 * {@code 2.0 / |z|}: только деление, сложение, умножение и {@code sqrt}, то есть операции, которые
 * IEEE 754 требует выполнять с корректным округлением и которые совпадают на обеих платформах.
 * </p>
 * <p>
 * <b>Условие паритета.</b> Ядро должно собираться без FMA-контракции
 * ({@code nvcc --fmad=false}, NVRTC {@code --fmad=false}) — иначе компилятор склеит
 * {@code zx*zx - zy*zy + cX} в {@code fma} и число итераций у границы множества разойдётся.
 * </p>
 *
 * @author axxxiomatic
 */
public final class FractalPalette {

    /** Цвет пикселей, которые остались внутри множества (совпадает с ядром CUDA). */
    public static final int INTERNAL_COLOR = 0x000040;

    /** Насыщенность для внешних точек. */
    public static final float SATURATION = 0.8f;

    /** Яркость для внешних точек. */
    public static final float BRIGHTNESS = 1.0f;

    private FractalPalette() {
    }

    /**
     * Цвет точки по числу итераций и модулю {@code z} в момент выхода.
     *
     * @param iterations число выполненных шагов итерации
     * @param maxIter    предельное число итераций
     * @param modZ       {@code sqrt(zx*zx + zy*zy)} после выхода из цикла
     * @return ARGB-цвет точки
     */
    public static int colorFor(int iterations, int maxIter, double modZ) {
        if (iterations >= maxIter) {
            return INTERNAL_COLOR;
        }
        double hue = hueFor(iterations, maxIter, modZ);
        return hsbToRgb((float) hue, SATURATION, BRIGHTNESS);
    }

    /**
     * Тон внешней точки в диапазоне {@code [0, 1)}.
     * <p>Все операции — IEEE-точные, поэтому результат совпадает на CPU и GPU.</p>
     */
    public static double hueFor(int iterations, int maxIter, double modZ) {
        double fraction = 2.0 / modZ;          // |z| >= 2 в момент выхода, поэтому fraction ∈ (0, 1]
        if (!(fraction >= 0.0)) {              // NaN-безопасная замена Math.min
            fraction = 0.0;
        } else if (fraction > 1.0) {
            fraction = 1.0;
        }
        double progress = ((double) iterations + (1.0 - fraction)) / (double) maxIter;
        double hue = 0.95 + 2.0 * progress;
        return hue - Math.floor(hue);
    }

    /**
     * Точная копия {@code java.awt.Color.HSBtoRGB} на {@code float}.
     * <p>
     * Совпадение с JDK проверяется тестом {@code FractalPaletteTest#hsbMatchesJdk}, а ядро CUDA
     * повторяет ту же арифметику в {@code float}, поэтому приведение {@code double -> float} в
     * {@link #colorFor} и в {@code mandelbrot.cu} даёт одинаковый результат.
     * </p>
     */
    public static int hsbToRgb(float hue, float saturation, float brightness) {
        int r = 0;
        int g = 0;
        int b = 0;
        if (saturation == 0.0f) {
            r = g = b = (int) (brightness * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1.0f - saturation);
            float q = brightness * (1.0f - saturation * f);
            float t = brightness * (1.0f - (saturation * (1.0f - f)));
            switch ((int) h) {
                case 0 -> {
                    r = (int) (brightness * 255.0f + 0.5f);
                    g = (int) (t * 255.0f + 0.5f);
                    b = (int) (p * 255.0f + 0.5f);
                }
                case 1 -> {
                    r = (int) (q * 255.0f + 0.5f);
                    g = (int) (brightness * 255.0f + 0.5f);
                    b = (int) (p * 255.0f + 0.5f);
                }
                case 2 -> {
                    r = (int) (p * 255.0f + 0.5f);
                    g = (int) (brightness * 255.0f + 0.5f);
                    b = (int) (t * 255.0f + 0.5f);
                }
                case 3 -> {
                    r = (int) (p * 255.0f + 0.5f);
                    g = (int) (q * 255.0f + 0.5f);
                    b = (int) (brightness * 255.0f + 0.5f);
                }
                case 4 -> {
                    r = (int) (t * 255.0f + 0.5f);
                    g = (int) (p * 255.0f + 0.5f);
                    b = (int) (brightness * 255.0f + 0.5f);
                }
                default -> {
                    r = (int) (brightness * 255.0f + 0.5f);
                    g = (int) (p * 255.0f + 0.5f);
                    b = (int) (q * 255.0f + 0.5f);
                }
            }
        }
        return 0xFF000000 | (r << 16) | (g << 8) | (b);
    }

    /**
     * Индекс бина тона для RGB-пикселя.
     * <p>
     * Возвращает {@code -1}, если пиксель не учитывается (внутренняя точка или чёрный фон),
     * и всегда попадает в {@code [0, bins)} для остальных — в том числе при {@code hue == 1.0},
     * когда наивное {@code (int)(hue*bins)} даёт {@code bins} и уводит запись за границу массива.
     * </p>
     */
    public static int hueBin(int rgb, int bins) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if ((rgb & 0x00FFFFFF) == 0 || (rgb & 0x00FFFFFF) == INTERNAL_COLOR) {
            return -1;                      // внутренние точки и чёрный фон тон не несут
        }
        float[] hsv = new float[3];
        java.awt.Color.RGBtoHSB(r, g, b, hsv);
        int bin = (int) (hsv[0] * bins);
        if (bin >= bins) {
            bin = bins - 1;                 // hue ровно 1.0 иначе уводит индекс за границу
        }
        return bin < 0 ? -1 : bin;
    }
}
