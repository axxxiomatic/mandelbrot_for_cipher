package com.cipher.core.benchmark;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Сравнение двух изображений-фракталов и хеширование байтов растра.
 * <p>
 * Главная метрика здесь — {@link #pixelDiffRatio}: фрактал используется как гамма для XOR,
 * поэтому CPU- и GPU-генератор обязаны давать <b>побитово одинаковые</b> пиксели. Любое
 * расхождение означает, что файл, зашифрованный на одной машине, не расшифруется на другой
 * (ошибка не проявляется как исключение — пользователь получает «шум»).
 * </p>
 */
public final class ImageDiff {

    private ImageDiff() {
    }

    /** Результат сравнения: сколько пикселей разошлось и максимальное отклонение канала. */
    public record Diff(int totalPixels, int differingPixels, int maxChannelDelta) {
        public double ratio() {
            return totalPixels == 0 ? 0.0 : (double) differingPixels / totalPixels;
        }

        public boolean identical() {
            return differingPixels == 0;
        }
    }

    public static Diff compare(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException(String.format(
                    "Размеры не совпадают: %dx%d vs %dx%d",
                    a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight()));
        }
        int width = a.getWidth();
        int height = a.getHeight();
        int[] rowA = new int[width];
        int[] rowB = new int[width];
        int differing = 0;
        int maxDelta = 0;

        for (int y = 0; y < height; y++) {
            a.getRGB(0, y, width, 1, rowA, 0, width);
            b.getRGB(0, y, width, 1, rowB, 0, width);
            for (int x = 0; x < width; x++) {
                int pa = rowA[x] & 0x00FFFFFF;
                int pb = rowB[x] & 0x00FFFFFF;
                if (pa != pb) {
                    differing++;
                    for (int shift = 0; shift <= 16; shift += 8) {
                        int d = Math.abs(((pa >> shift) & 0xFF) - ((pb >> shift) & 0xFF));
                        if (d > maxDelta) {
                            maxDelta = d;
                        }
                    }
                }
            }
        }
        return new Diff(width * height, differing, maxDelta);
    }

    /** SHA-256 по пикселям (ARGB без альфы) — стабильный «отпечаток» гаммы. */
    public static String sha256(BufferedImage image) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int width = image.getWidth();
            int[] row = new int[width];
            byte[] buf = new byte[width * 3];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                int i = 0;
                for (int x = 0; x < width; x++) {
                    int rgb = row[x];
                    buf[i++] = (byte) ((rgb >> 16) & 0xFF);
                    buf[i++] = (byte) ((rgb >> 8) & 0xFF);
                    buf[i++] = (byte) (rgb & 0xFF);
                }
                md.update(buf);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
