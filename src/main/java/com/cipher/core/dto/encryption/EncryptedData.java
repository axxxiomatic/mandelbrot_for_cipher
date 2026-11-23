package com.cipher.core.dto.encryption;

/**
 * DTO зашифрованных данных.
 * <p>
 * {@code algorithmVersion} — версия палитры гаммы. Она попадает в заголовок файла: файл,
 * зашифрованный палитрой, отличной от текущей, нельзя расшифровать (XOR с другой гаммой даёт
 * не «слегка другой результат», а шум), поэтому такие файлы отвергаются явной ошибкой вместо
 * молчаливой порчи данных.
 * </p>
 */
public record EncryptedData(int algorithmVersion, byte[] sessionSalt, int attemptCount,
                            int startX, int startY, int areaWidth, int areaHeight,
                            int originalWidth, int originalHeight,
                            byte[] imageBytes) {
}
