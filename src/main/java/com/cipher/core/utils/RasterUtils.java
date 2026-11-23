package com.cipher.core.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;

/**
 * Работа с растром изображения: извлечение байтов в BGR-порядке (для CUDA) и сборка
 * изображения {@code TYPE_INT_RGB} из таких байтов.
 * <p>
 * Заменяет два опасных приведения, из-за которых GPU-путь падал:
 * <ul>
 *   <li>{@code (DataBufferInt) raster.getDataBuffer()} в валидации фрактала — GPU отдаёт
 *       {@code TYPE_3BYTE_BGR}, и приведение бросало {@code ClassCastException};</li>
 *   <li>{@code (DataBufferByte) raster.getDataBuffer()} в XOR — при дешифровании изображение
 *       приходит как {@code TYPE_INT_RGB}, и приведение снова падало.</li>
 * </ul>
 * Поддержаны три практически важных типа: {@code TYPE_INT_RGB}, {@code TYPE_INT_ARGB}
 * и {@code TYPE_3BYTE_BGR}. Для остальных типов используется построчный {@code getRGB}.
 * </p>
 * <p>
 * Работа идёт напрямую с {@link DataBufferInt} без прохода через {@code ColorModel}, поэтому
 * {@link #toBgrBytes} и {@link #fromBgrBytes} — взаимно обратные и сохраняют пиксели бит-в-бит
 * (тест {@code RasterUtilsTest}).
 * </p>
 */
public final class RasterUtils {

    private RasterUtils() {
    }

    /** Поддерживается ли тип растра «быстрым» путём. */
    public static boolean isSupported(BufferedImage image) {
        int type = image.getType();
        return type == BufferedImage.TYPE_INT_RGB
                || type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_3BYTE_BGR;
    }

    /**
     * Байты растра в порядке B, G, R — именно так их читают CUDA-ядра.
     * Альфа-канал отбрасывается.
     */
    public static byte[] toBgrBytes(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 3];

        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(data, 0, out, 0, out.length);
            return out;
        }
        if (image.getType() == BufferedImage.TYPE_INT_RGB
                || image.getType() == BufferedImage.TYPE_INT_ARGB) {
            int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            int i = 0;
            for (int p : data) {
                out[i++] = (byte) (p & 0xFF);
                out[i++] = (byte) ((p >> 8) & 0xFF);
                out[i++] = (byte) ((p >> 16) & 0xFF);
            }
            return out;
        }
        return toBgrBytesViaRgb(image);
    }

    private static byte[] toBgrBytesViaRgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 3];
        int[] row = new int[width];
        int i = 0;
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int p = row[x];
                out[i++] = (byte) (p & 0xFF);
                out[i++] = (byte) ((p >> 8) & 0xFF);
                out[i++] = (byte) ((p >> 16) & 0xFF);
            }
        }
        return out;
    }

    /** Собирает {@code TYPE_INT_RGB} из байтов в порядке B, G, R (обратна {@link #toBgrBytes}). */
    public static BufferedImage fromBgrBytes(byte[] bgr, int width, int height) {
        int expected = width * height * 3;
        if (bgr.length != expected) {
            throw new IllegalArgumentException(
                    "Ожидалось " + expected + " байт BGR, получено " + bgr.length);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int i = 0;
        for (int p = 0; p < data.length; p++) {
            int b = bgr[i++] & 0xFF;
            int g = bgr[i++] & 0xFF;
            int r = bgr[i++] & 0xFF;
            data[p] = (r << 16) | (g << 8) | b;
        }
        return image;
    }

    /** Приводит изображение к {@code TYPE_INT_RGB}, если это ещё не так. */
    public static BufferedImage toIntRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    /** Доля пикселей заданного цвета (без альфы) — без привязки к типу растра. */
    public static double colorShare(BufferedImage image, int rgb) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] row = new int[width];
        int matches = 0;
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                if ((row[x] & 0x00FFFFFF) == (rgb & 0x00FFFFFF)) {
                    matches++;
                }
            }
        }
        int total = width * height;
        return total == 0 ? 1.0 : (double) matches / total;
    }

    /** Изображение {@code TYPE_INT_RGB}, залитое цветом. */
    public static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(data, rgb | 0xFF000000);
        return image;
    }

    /** Цвет пикселя как ARGB. */
    public static int argb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) | 0xFF000000;
    }

    /** Цвет по индексу тона — используется синтетическими изображениями в тестах. */
    public static int hsb(int hueIndex, int bins) {
        return Color.HSBtoRGB((float) hueIndex / bins, 1.0f, 1.0f);
    }
}
