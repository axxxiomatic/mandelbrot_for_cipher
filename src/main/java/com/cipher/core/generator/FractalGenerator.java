package com.cipher.core.generator;

import java.awt.image.BufferedImage;

public interface FractalGenerator {
    BufferedImage generate(int width, int height, double zoom,
                           double offsetX, double offsetY, int maxIter);
}