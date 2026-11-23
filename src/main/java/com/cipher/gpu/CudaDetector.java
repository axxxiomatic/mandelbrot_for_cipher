package com.cipher.gpu;

/**
 * Совместимый фасад над {@link CudaDevice}.
 * <p>
 * Оставлен, потому что на него ссылаются существующие классы и тесты. Вся работа с устройством,
 * контекстом и проверка свойства {@code -Dcipher.cuda} перенесены в {@link CudaDevice}:
 * исходная реализация создавала контекст статически (в потоке создания Spring-бина), из-за чего
 * пул {@code findValidFractalParallel} получал {@code CUDA_ERROR_INVALID_CONTEXT}.
 * </p>
 */
public final class CudaDetector {

    private CudaDetector() {
    }

    /** Доступна ли CUDA: драйвер загружен, устройство найдено, primary context получен. */
    public static boolean isCudaAvailable() {
        return CudaDevice.isAvailable();
    }

    /**
     * Инициализация контекста — оставлена для обратной совместимости.
     * Контекст создаётся лениво в {@link CudaDevice#isAvailable()}.
     */
    public static void initializeCudaContext() {
        CudaDevice.isAvailable();
    }

    /** Имя устройства, например {@code NVIDIA GeForce RTX 3060}. */
    public static String deviceName() {
        return CudaDevice.deviceName();
    }

    /** Архитектура для NVRTC, например {@code sm_86}. */
    public static String targetArchitecture() {
        return CudaDevice.targetArchitecture();
    }
}
