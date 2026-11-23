package com.cipher.gpu;

import jcuda.driver.CUmodule;
import jcuda.nvrtc.JNvrtc;
import jcuda.nvrtc.nvrtcProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static jcuda.driver.JCudaDriver.cuModuleLoadData;

/**
 * Компиляция CUDA-ядра под конкретную карту.
 * <p>
 * Порядок попыток:
 * <ol>
 *   <li><b>NVRTC</b> — компилирует {@code .cu} из classpath под архитектуру установленной карты
 *       ({@code sm_XY}). Снимает зависимость от версии драйвера: заранее собранный PTX 8.4
 *       (nvcc 12.4) требует драйвер ветки 550+, иначе {@code cuModuleLoadData} возвращает
 *       {@code CUDA_ERROR_UNSUPPORTED_PTX_VERSION}, и приложение падало в обход CPU-fallback.</li>
 *   <li><b>Предсобранный PTX</b> — резервный путь, если NVRTC недоступен. JNI-биндинг
 *       {@code jcuda.nvrtc.JNvrtc} лежит в артефакте {@code org.jcuda:jcuda}, а его нативная
 *       библиотека {@code JNvrtc-…dll} — в {@code jcuda-natives}. При этом JNI-слой сам
 *       загружает {@code nvrtc64_*.dll} из установленного CUDA Toolkit: без Toolkit биндинг
 *       падает с {@code UnsatisfiedLinkError}, и мы уходим в этот резервный путь.</li>
 * </ol>
 * Компиляция идёт с {@code --fmad=false}: FMA-контракция меняет число итераций у границы
 * множества и ломает побитовый паритет с CPU-палитрой ({@link com.cipher.core.generator.FractalPalette}).
 * </p>
 */
public final class KernelCompiler {

    private static final Logger log = LoggerFactory.getLogger(KernelCompiler.class);

    private KernelCompiler() {
    }

    /**
     * Загружает ядро в текущий контекст CUDA.
     *
     * @param sourceResource путь к {@code .cu} в classpath, например {@code /cuda/mandelbrot.cu}
     * @param ptxResource    путь к предсобранному {@code .ptx}
     * @return модуль CUDA с загруженным ядром
     */
    public static CUmodule loadModule(String sourceResource, String ptxResource) {
        if (!CudaDevice.makeCurrent()) {
            throw new CudaException("Контекст CUDA недоступен");
        }
        try {
            String source = readResource(sourceResource);
            String ptx = compileWithNvrtc(source, sourceResource);
            CUmodule module = new CUmodule();
            cuModuleLoadData(module, ptx);
            log.info("Ядро {} скомпилировано через NVRTC для {}", sourceResource,
                    CudaDevice.targetArchitecture());
            return module;
        } catch (Throwable t) {
            log.warn("NVRTC недоступен для {} ({}), использую предсобранный PTX",
                    sourceResource, t.toString());
        }

        try {
            byte[] ptx = readResourceBytes(ptxResource);
            CUmodule module = new CUmodule();
            cuModuleLoadData(module, ptx);
            log.info("Ядро {} загружено из предсобранного PTX", sourceResource);
            return module;
        } catch (Throwable t) {
            throw new CudaException("Не удалось загрузить ядро " + sourceResource, t);
        }
    }

    /** Компилирует исходник ядра под реальную архитектуру карты и отдаёт текст PTX. */
    public static String compileWithNvrtc(String source, String programName) {
        nvrtcProgram program = new nvrtcProgram();
        JNvrtc.nvrtcCreateProgram(program, source, programName, 0, null, null);
        try {
            String[] options = {
                    "--gpu-architecture=" + CudaDevice.targetArchitecture(),
                    // Без этого компилятор склеит zx*zx - zy*zy + cX в fma и паритет с CPU уплывёт.
                    "--fmad=false",
                    "--std=c++14",
                    "--device-as-default-execution-space"
            };
            int result = JNvrtc.nvrtcCompileProgram(program, options.length, options);
            String programLog = getProgramLog(program);
            if (result != 0) {
                throw new CudaException("NVRTC вернул код " + result
                        + (programLog.isBlank() ? "" : ": " + programLog));
            }
            if (!programLog.isBlank()) {
                log.debug("Лог NVRTC для {}: {}", programName, programLog);
            }
            return getPtx(program);
        } finally {
            JNvrtc.nvrtcDestroyProgram(program);
        }
    }

    private static String getProgramLog(nvrtcProgram program) {
        try {
            long[] size = new long[1];
            JNvrtc.nvrtcGetProgramLogSize(program, size);
            if (size[0] <= 1) {
                return "";
            }
            String[] logHolder = new String[1];
            JNvrtc.nvrtcGetProgramLog(program, logHolder);
            return logHolder[0] == null ? "" : logHolder[0].trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String getPtx(nvrtcProgram program) {
        long[] size = new long[1];
        JNvrtc.nvrtcGetPTXSize(program, size);
        String[] holder = new String[1];
        JNvrtc.nvrtcGetPTX(program, holder);
        if (holder[0] == null || holder[0].isEmpty()) {
            throw new CudaException("NVRTC не вернул PTX");
        }
        return holder[0];
    }

    private static String readResource(String path) {
        byte[] bytes = readResourceBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String path) {
        if (path == null) {
            throw new CudaException("Путь к ресурсу не задан");
        }
        try (InputStream is = KernelCompiler.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new CudaException("Ресурс не найден в classpath: " + path);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new CudaException("Не удалось прочитать " + path, e);
        }
    }

    /** Проверка, что JCuda-драйвер вообще загрузился (для диагностики и тестов). */
    public static boolean driverAvailable() {
        try {
            return CudaDevice.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
