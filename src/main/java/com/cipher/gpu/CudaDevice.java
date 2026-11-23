package com.cipher.gpu;

import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdevice_attribute;
import jcuda.driver.JCudaDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static jcuda.driver.JCudaDriver.cuCtxSetCurrent;
import static jcuda.driver.JCudaDriver.cuDeviceGet;
import static jcuda.driver.JCudaDriver.cuDeviceGetAttribute;
import static jcuda.driver.JCudaDriver.cuDeviceGetName;
import static jcuda.driver.JCudaDriver.cuDevicePrimaryCtxRelease;
import static jcuda.driver.JCudaDriver.cuDevicePrimaryCtxRetain;
import static jcuda.driver.JCudaDriver.cuInit;

/**
 * Держатель первичного контекста CUDA на процесс.
 * <p>
 * Исходная реализация создавала контекст в статическом инициализаторе {@code GPUFractalGenerator},
 * то есть в потоке создания Spring-бина. Контекст CUDA является <b>потоколокальным</b>, поэтому
 * вызовы {@code cuMemAlloc}/{@code cuLaunchKernel} из пула {@code findValidFractalParallel}
 * и из JavaFX Task-потоков получали {@code CUDA_ERROR_INVALID_CONTEXT}.
 * </p>
 * <p>
 * Здесь используется primary context устройства: он один на процесс, разделяется между потоками,
 * а перед каждой работой поток делает его текущим ({@link #enter()}). Освобождается через
 * {@link #shutdown()} (Spring вызывает его как {@code destroyMethod}).
 * </p>
 */
public final class CudaDevice {

    private static final Logger log = LoggerFactory.getLogger(CudaDevice.class);

    private static final Object LOCK = new Object();
    private static final AtomicInteger REFERENCES = new AtomicInteger();

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile CUcontext context;
    private static volatile CUdevice device;
    private static volatile String deviceName = "unknown";
    private static volatile int computeMajor;
    private static volatile int computeMinor;

    private CudaDevice() {
    }

    /** Доступна ли CUDA: инициализация драйвера, получение устройства и primary context. */
    public static boolean isAvailable() {
        if (!initialized) {
            synchronized (LOCK) {
                if (!initialized) {
                    initialize();
                    // Если драйвера нет, повторять инициализацию на каждый вызов бессмысленно:
                    // это N холостых попыток загрузить .dll. После явного освобождения контекста
                    // флаг сбрасывается (см. releaseContextIfUnused), и инициализация повторяется.
                    initialized = true;
                }
            }
        }
        return available;
    }

    /**
     * Сбрасывает признак инициализации, чтобы следующий {@link #isAvailable()} мог поднять
     * контекст заново. Используется после успешного освобождения контекста: иначе один
     * {@code shutdown()} бэкенда выключал бы CUDA до конца жизни процесса.
     */
    private static void resetInitialization() {
        initialized = false;
    }

    private static void initialize() {
        if (!Boolean.parseBoolean(System.getProperty("cipher.cuda", "true"))) {
            log.info("CUDA отключена свойством -Dcipher.cuda=false");
            available = false;
            return;
        }
        try {
            JCudaDriver.setExceptionsEnabled(true);
            cuInit(0);
            CUdevice dev = new CUdevice();
            cuDeviceGet(dev, 0);
            device = dev;

            byte[] name = new byte[256];
            cuDeviceGetName(name, name.length, dev);
            deviceName = new String(name, 0, indexOfZero(name), java.nio.charset.StandardCharsets.UTF_8);

            int[] value = new int[1];
            cuDeviceGetAttribute(value, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, dev);
            computeMajor = value[0];
            cuDeviceGetAttribute(value, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, dev);
            computeMinor = value[0];

            CUcontext primary = new CUcontext();
            cuDevicePrimaryCtxRetain(primary, dev);
            context = primary;
            cuCtxSetCurrent(primary);

            available = true;
            log.info("CUDA доступна: {} (sm_{}{})", deviceName, computeMajor, computeMinor);
        } catch (Throwable t) {
            // Отсутствие драйвера, .dll или поддержки PTX не должно ронять приложение:
            // это штатный сценарий CPU-fallback.
            log.info("CUDA недоступна: {}", t.toString());
            available = false;
        }
    }

    private static int indexOfZero(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return bytes.length;
    }

    /**
     * Делает primary context текущим для вызывающего потока.
     *
     * @return {@code true}, если контекст был установлен
     */
    public static boolean makeCurrent() {
        if (!isAvailable()) {
            return false;
        }
        try {
            cuCtxSetCurrent(context);
            return true;
        } catch (Throwable t) {
            // Контекст не делаем «недоступным» навсегда: следующий вызов попробует снова,
            // а вызывающий код получит false и обработает отказ.
            log.error("Не удалось сделать контекст CUDA текущим", t);
            return false;
        }
    }

    /** Основной доступ к контексту для слоя компиляции и запуска ядер. */
    public static CUcontext context() {
        return context;
    }

    public static CUdevice device() {
        return device;
    }

    public static String deviceName() {
        return deviceName;
    }

    public static int computeMajor() {
        return computeMajor;
    }

    public static int computeMinor() {
        return computeMinor;
    }

    /** Целевая архитектура для NVRTC: например {@code sm_86}. */
    public static String targetArchitecture() {
        return "sm_" + computeMajor + computeMinor;
    }

    /**
     * Увеличивает счётчик пользователей контекста. Парный вызов — {@link #release()}.
     * Благодаря счётчику два GPU-бина разделяют один контекст.
     */
    public static boolean acquire() {
        if (!isAvailable()) {
            return false;
        }
        REFERENCES.incrementAndGet();
        return true;
    }

    public static void release() {
        if (REFERENCES.decrementAndGet() > 0) {
            return;
        }
        releaseContextIfUnused();
    }

    /**
     * Освобождает контекст, если им больше никто не пользуется.
     * <p>
     * В отличие от {@link #release()}, не трогает счётчик: вызов при {@code REFERENCES == 0}
     * безопасен, тогда как повторный {@code release()} сделал бы счётчик отрицательным.
     * </p>
     */
    public static void shutdown() {
        if (REFERENCES.get() > 0) {
            return;
        }
        releaseContextIfUnused();
    }

    private static void releaseContextIfUnused() {
        synchronized (LOCK) {
            if (!available || device == null) {
                return;
            }
            try {
                cuDevicePrimaryCtxRelease(device);
                log.info("Контекст CUDA освобождён");
            } catch (Throwable t) {
                log.warn("Не удалось освободить контекст CUDA: {}", t.toString());
            } finally {
                available = false;
                context = null;
                // Разрешаем повторную инициализацию: освобождение контекста одним бэкендом
                // (или завершением бенчмарка) не должно навсегда отключать CUDA в процессе.
                resetInitialization();
            }
        }
    }
}
