package com.cipher.gpu;

/**
 * Любая ошибка CUDA-слоя: отсутствие устройства, сбой компиляции ядра, ошибка запуска.
 * <p>
 * Наследуется от {@link RuntimeException}, чтобы не засорять сигнатуры интерфейсов ядра,
 * но при этом явно отделяется от логических ошибок приложения: фабрика бэкендов ловит именно
 * этот тип и переключается на CPU.
 * </p>
 */
public class CudaException extends RuntimeException {

    public CudaException(String message) {
        super(message);
    }

    public CudaException(String message, Throwable cause) {
        super(message, cause);
    }
}
