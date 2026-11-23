package com.cipher.gpu;

import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.FractalValidator;
import com.cipher.core.generator.impl.CPUFractalGenerator;
import com.cipher.core.generator.impl.CPUFractalValidator;
import com.cipher.core.service.encryption.util.XOROperation;
import com.cipher.core.service.encryption.util.impl.CPUXOROperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Фабрика бэкендов с гарантированным откатом на CPU.
 * <p>
 * Исходная {@code AppConfig} проверяла только {@code isCudaAvailable()} и затем безусловно
 * вызывала {@code new GPUFractalGenerator()}. Любая ошибка после этой проверки — отсутствие
 * NVRTC, неподдерживаемый PTX, ошибка контекста — выбрасывалась из статического инициализатора
 * как {@code ExceptionInInitializerError} и роняла создание Spring-бина, то есть приложение
 * не стартовало вовсе, хотя CPU-fallback формально был предусмотрен.
 * </p>
 * <p>
 * Здесь каждая GPU-реализация создаётся через {@link #create}, который ловит любую ошибку,
 * логирует её и возвращает CPU-аналог. Дополнительно поддерживается отключение GPU целиком
 * свойством {@code -Dcipher.cuda=false} — для отладки и машин с нестабильным драйвером.
 * </p>
 */
public final class GpuBackends {

    private static final Logger log = LoggerFactory.getLogger(GpuBackends.class);

    private GpuBackends() {
    }

    /** Доступна ли CUDA (с учётом {@code -Dcipher.cuda=false}). */
    public static boolean cudaRequested() {
        return Boolean.parseBoolean(System.getProperty("cipher.cuda", "true")) && CudaDevice.isAvailable();
    }

    public static FractalGenerator fractalGenerator() {
        if (!cudaRequested()) {
            log.info("Генератор фрактала: CPU (CUDA недоступна или отключена)");
            return new CPUFractalGenerator();
        }
        return create("GPUFractalGenerator", GPUFractalGenerator::new, CPUFractalGenerator::new);
    }

    public static XOROperation xorOperation() {
        if (!cudaRequested()) {
            log.info("XOR: CPU (CUDA недоступна или отключена)");
            return new CPUXOROperation();
        }
        return create("GPUXOROperation", GPUXOROperation::new, CPUXOROperation::new);
    }

    public static FractalValidator fractalValidator() {
        if (!cudaRequested()) {
            log.info("Валидатор фрактала: CPU (CUDA недоступна или отключена)");
            return new CPUFractalValidator();
        }
        return create("GPUFractalValidator", GPUFractalValidator::new, CPUFractalValidator::new);
    }

    /**
     * Создаёт GPU-бэкенд, откатываясь на CPU при любой ошибке.
     *
     * @param name        имя GPU-класса для лога
     * @param gpuSupplier конструктор GPU-реализации
     * @param cpuSupplier конструктор CPU-реализации
     */
    public static <T> T create(String name, Supplier<T> gpuSupplier, Supplier<T> cpuSupplier) {
        try {
            T backend = gpuSupplier.get();
            log.info("Бэкенд {} активен", name);
            return backend;
        } catch (Throwable t) {
            // Конструктор GPU-класса сам вызывает CudaDevice.release() в своём catch-блоке,
            // поэтому контекст здесь уже освобождён (а если он нужен другому бэкенду —
            // счётчик ссылок не даст его закрыть).
            log.warn("Бэкенд {} недоступен ({}): переключаюсь на CPU", name, t.toString());
            return cpuSupplier.get();
        }
    }
}
