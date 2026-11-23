package com.cipher.core.config;

import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.FractalValidator;
import com.cipher.core.service.encryption.util.XOROperation;
import com.cipher.gpu.GpuBackends;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Конфигурация бэкендов.
 * <p>
 * Выбор CPU/GPU инкапсулирован в {@link GpuBackends}: фабрика проверяет доступность CUDA,
 * а при любой ошибке инициализации GPU-класса (нет NVRTC, неподдерживаемый PTX, сбой контекста)
 * возвращает CPU-реализацию вместо падения приложения. {@code destroyMethod} освобождает
 * device-буферы и primary context CUDA при остановке контекста Spring.
 * </p>
 */
@Configuration
@ComponentScan("com.cipher")
public class AppConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setUncaughtExceptionHandler((t, e) -> {
                LoggerFactory.getLogger(AppConfig.class).error(
                        "Необработанное исключение в потоке ExecutorService: {}", e.getMessage(), e);
            });
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public FractalGenerator fractalGenerator() {
        return GpuBackends.fractalGenerator();
    }

    @Bean(destroyMethod = "shutdown")
    public XOROperation xorOperation() {
        return GpuBackends.xorOperation();
    }

    @Bean(destroyMethod = "shutdown")
    public FractalValidator fractalValidator() {
        return GpuBackends.fractalValidator();
    }
}
