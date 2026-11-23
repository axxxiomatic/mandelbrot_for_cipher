package com.cipher.core.service.encryption.util.impl;

import com.cipher.core.service.encryption.util.XOROperation;

import java.awt.image.BufferedImage;

public class CPUXOROperation implements XOROperation {
    @Override
    public BufferedImage xor(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("Images must have same dimensions for XOR");
        }
        int width = a.getWidth();
        int height = a.getHeight();
        BufferedImage result = new BufferedImage(width, height, a.getType());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgbA = a.getRGB(x, y);
                int rgbB = b.getRGB(x, y);
                result.setRGB(x, y, (rgbA ^ rgbB) | 0xFF000000);
            }
        }
        return result;
    }
}