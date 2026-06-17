package com.cipher.core.service.encryption.util;

import java.awt.image.BufferedImage;

public interface XOROperation {
    BufferedImage xor(BufferedImage a, BufferedImage b);
}