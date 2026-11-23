package com.cipher.core.threading;

import com.cipher.core.generator.FractalPalette;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author @dankotyt Danil Kotlyarov
 * Генерирует прямоугольную часть множества Мандельброта в отдельном потоке.
 * <p>
 * Палитра и правило обхода зафиксированы в {@link FractalPalette}: счётчик итераций — прямой
 * (от 0 к {@code maxIter}), цвет внешних и внутренних точек считается по IEEE-операциям.
 * Это позволяет GPU-ядру повторить ту же арифметику и получить побитово равную картинку —
 * без этого фрактал нельзя использовать как гамму для XOR.
 * </p>
 */
public class MandelbrotThread implements Runnable {

    private final int startX;
    private final int startY;
    private final int width;
    private final int height;
    private final double zoom;
    private final int maxIter;
    private final double offsetX;
    private final double offsetY;
    private final BufferedImage image;
    private static final Logger logger = Logger.getLogger(MandelbrotThread.class.getName());

    /**
     * @param startX  начальная координата X области
     * @param startY  начальная координата Y области
     * @param width   ширина области
     * @param height  высота области
     * @param zoom    масштаб
     * @param maxIter предельное число итераций
     * @param offsetX смещение по X
     * @param offsetY смещение по Y
     * @param image   изображение {@code TYPE_INT_RGB}, в которое пишется результат
     */
    public MandelbrotThread(int startX, int startY, int width, int height,
                            double zoom, int maxIter, double offsetX, double offsetY,
                            BufferedImage image) {
        this.startX = startX;
        this.startY = startY;
        this.width = width;
        this.height = height;
        this.zoom = zoom;
        this.maxIter = maxIter;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.image = image;
    }

    @Override
    public void run() {
        if (!(image.getRaster().getDataBuffer() instanceof DataBufferInt intBuffer)) {
            // Аргумент image всегда создаётся CPUFractalGenerator с TYPE_INT_RGB;
            // сюда попадаем только при ошибочном использовании класса извне.
            logger.log(Level.WARNING, "MandelbrotThread требует BufferedImage.TYPE_INT_RGB");
            return;
        }
        int[] pixels = intBuffer.getData();

        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int endX = Math.min(startX + width, imageWidth);
        int endY = Math.min(startY + height, imageHeight);

        // Те же координаты, что и в GPU-ядре: середина кадра, а не историческое /1.75.
        int centerX = imageWidth / 2;
        int centerY = imageHeight / 2;

        for (int y = startY; y < endY; y++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            double cY = (y - centerY) / zoom + offsetY;
            int rowOffset = y * imageWidth;
            for (int x = startX; x < endX; x++) {
                double cX = (x - centerX) / zoom + offsetX;
                double zx = 0.0;
                double zy = 0.0;
                int iterations = 0;

                while (zx * zx + zy * zy < 4.0 && iterations < maxIter) {
                    double tmp = zx * zx - zy * zy + cX;
                    zy = 2.0 * zx * zy + cY;
                    zx = tmp;
                    iterations++;
                }

                int color;
                if (iterations >= maxIter) {
                    color = FractalPalette.INTERNAL_COLOR;
                } else {
                    double modZ = Math.sqrt(zx * zx + zy * zy);
                    color = FractalPalette.colorFor(iterations, maxIter, modZ);
                }
                pixels[rowOffset + x] = color;
            }
        }
    }
}
