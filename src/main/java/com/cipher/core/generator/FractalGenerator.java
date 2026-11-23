package com.cipher.core.generator;

import com.cipher.core.dto.MandelbrotParams;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * Генератор изображения-гаммы (множества Мандельброта).
 * <p>
 * Реализации обязаны возвращать <b>побитово одинаковый</b> результат для одинаковых параметров
 * независимо от бэкенда: изображение ксорится с шифруемым и от него зависит возможность
 * расшифровки на другой машине. Общая арифметика зафиксирована в {@link FractalPalette}.
 * </p>
 */
public interface FractalGenerator {

    BufferedImage generate(int width, int height, double zoom,
                           double offsetX, double offsetY, int maxIter);

    /** Имя бэкенда для логов, метрик и UI. */
    default String backendName() {
        return getClass().getSimpleName();
    }

    /**
     * Пакетная генерация: по умолчанию — последовательный вызов {@link #generate}.
     * <p>
     * GPU-реализация переопределяет метод и выполняет несколько кадров одним запуском ядра,
     * что убирает накладные расходы на аллокацию и копирование буферов при поиске кандидатов.
     * </p>
     */
    default List<BufferedImage> generateBatch(int width, int height, List<MandelbrotParams> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyList();
        }
        List<BufferedImage> images = new java.util.ArrayList<>(params.size());
        for (MandelbrotParams p : params) {
            images.add(generate(width, height, p.zoom(), p.offsetX(), p.offsetY(), p.maxIter()));
        }
        return images;
    }

    /** Сколько кадров за раз имеет смысл просить у бэкенда (1 — без пакетного режима). */
    default int batchCapacity() {
        return 1;
    }
}
