package com.cipher.core.utils;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Проверки {@link RasterUtils} — замены двух небезопасных приведений растра
 * ({@code DataBufferInt}/{@code DataBufferByte}), из-за которых GPU-путь падал
 * с {@code ClassCastException}.
 */
class RasterUtilsTest {

    private static BufferedImage gradient(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 7) & 0xFF) << 16 | ((y * 11) & 0xFF) << 8 | ((x + y) & 0xFF));
            }
        }
        return image;
    }

    @Test
    void toBgrBytes_roundTrip_shouldPreservePixelsForIntRgb() {
        assertRoundTrip(BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void toBgrBytes_roundTrip_shouldPreservePixelsFor3ByteBgr() {
        // Именно этот тип возвращал GPU-генератор, и именно на нём падала валидация.
        assertRoundTrip(BufferedImage.TYPE_3BYTE_BGR);
    }

    @Test
    void toBgrBytes_roundTrip_shouldPreservePixelsForIntArgb() {
        assertRoundTrip(BufferedImage.TYPE_INT_ARGB);
    }

    private void assertRoundTrip(int type) {
        BufferedImage source = gradient(37, 23, type);
        byte[] bytes = RasterUtils.toBgrBytes(source);
        assertEquals(37 * 23 * 3, bytes.length);

        BufferedImage restored = RasterUtils.fromBgrBytes(bytes, 37, 23);
        assertEquals(BufferedImage.TYPE_INT_RGB, restored.getType());
        for (int y = 0; y < 23; y++) {
            for (int x = 0; x < 37; x++) {
                assertEquals(source.getRGB(x, y) & 0x00FFFFFF, restored.getRGB(x, y) & 0x00FFFFFF,
                        "пиксель (" + x + "," + y + ") для типа " + type);
            }
        }
    }

    @Test
    void toBgrBytes_ordersChannelsAsBlueGreenRed() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        assertArrayEquals(new byte[]{(byte) 0x33, (byte) 0x22, (byte) 0x11},
                RasterUtils.toBgrBytes(image));
    }

    @Test
    void fromBgrBytes_withWrongLength_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> RasterUtils.fromBgrBytes(new byte[5], 2, 2));
    }

    @Test
    void colorShare_countsOnlyMatchingPixels() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                image.setRGB(x, y, x < 3 ? 0x000040 : 0xFF0000);
            }
        }
        assertEquals(0.03, RasterUtils.colorShare(image, 0x000040), 1e-9);
    }

    @Test
    void solid_shouldFillAllPixels() {
        BufferedImage image = RasterUtils.solid(4, 5, 0x123456);
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0x123456, image.getRGB(x, y) & 0x00FFFFFF);
            }
        }
    }
}
