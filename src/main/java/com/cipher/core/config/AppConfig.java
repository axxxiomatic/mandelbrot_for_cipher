package com.cipher.core.config;

import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.impl.CPUFractalGenerator;
import com.cipher.core.service.encryption.util.XOROperation;
import com.cipher.core.service.encryption.util.impl.CPUXOROperation;
import com.cipher.gpu.CudaDetector;
import com.cipher.gpu.GPUFractalGenerator;
import com.cipher.gpu.GPUXOROperation;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@ComponentScan("com.cipher")
public class AppConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setUncaughtExceptionHandler((t, e) -> {
                LoggerFactory.getLogger(AppConfig.class).error("Необработанное исключение в потоке ExecutorService: {}", e.getMessage(), e);
            });
            return thread;
        });
    }

    @Bean
    public FractalGenerator fractalGenerator() {
        if (CudaDetector.isCudaAvailable()) {
            return new GPUFractalGenerator();
        } else {
            return new CPUFractalGenerator();
        }
    }

    @Bean
    public XOROperation xorOperation() {
        if (CudaDetector.isCudaAvailable()) {
            return new GPUXOROperation();
        } else {
            return new CPUXOROperation();
        }
    }
}
