package com.cipher.core.benchmark;

import com.cipher.core.generator.FractalGenerator;
import com.cipher.core.generator.impl.CPUFractalGenerator;
import com.cipher.core.service.encryption.util.XOROperation;
import com.cipher.core.service.encryption.util.impl.CPUXOROperation;
import com.cipher.gpu.CudaDetector;
import com.cipher.gpu.GPUFractalGenerator;
import com.cipher.gpu.GPUXOROperation;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * CLI-бенчмарк CUDA-параллелизации: сравнение CPU- и GPU-бэкендов генерации фрактала и XOR.
 * <p>
 * Запуск (Windows x86_64 — единственная поддерживаемая платформа GPU-контура):
 * <pre>
 *   ./gradlew benchmarkCuda
 *   ./gradlew benchmarkCuda -Pargs="--width 1920 --height 1080 --runs 20"
 *   ./gradlew benchmarkCuda -Pargs="--sizes 640x480,1280x720,1920x1080 --json"
 *   ./gradlew benchmarkCuda -Pargs="--sweep --width 1280 --height 720"
 * </pre>
 * <p>
 * Ключевая задача бенчмарка — не только «сколько миллисекунд», но и <b>паритет</b>: фрактал
 * здесь является гаммой для XOR, поэтому CPU и GPU обязаны выдавать одинаковые пиксели.
 * Нарушение паритета печатается построчно и приводит к коду возврата 1.
 * </p>
 * Коды возврата: 0 — успех, 1 — нарушен паритет CPU/GPU, 2 — CUDA недоступна.
 *
 * @author axxxiomatic
 */
public final class CudaBenchmark {

    private CudaBenchmark() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        boolean cuda = CudaDetector.isCudaAvailable();

        System.out.println("=== Mandelbrot CUDA benchmark ===");
        System.out.println("cuda available : " + cuda);
        System.out.println("java           : " + System.getProperty("java.version"));
        System.out.println("os             : " + System.getProperty("os.name")
                + " / " + System.getProperty("os.arch"));
        System.out.println("cpus           : " + Runtime.getRuntime().availableProcessors());

        if (!cuda) {
            System.out.println();
            System.out.println("CUDA не обнаружена: замер GPU невозможен, ниже — только CPU-база.");
        }

        List<String> rows = new ArrayList<>();
        boolean parityOk = true;

        for (int[] size : options.sizes) {
            int width = size[0];
            int height = size[1];
            System.out.printf(Locale.ROOT, "%n--- %dx%d ---%n", width, height);

            if (options.sweep) {
                parityOk &= sweep(options, width, height, cuda, rows);
            } else {
                parityOk &= singlePoint(options, width, height, cuda, rows);
            }

            if (cuda) {
                parityOk &= xorBenchmark(options, width, height, rows);
            }
        }

        if (options.json) {
            printJson(rows, cuda, parityOk);
        }

        System.out.println();
        if (!parityOk) {
            System.out.println("РЕЗУЛЬТАТ: ПАРИТЕТ НАРУШЕН — GPU-гамма не совпадает с CPU,"
                    + " зашифрованный файл не расшифруется на другом бэкенде.");
            System.exit(1);
        }
        if (!cuda) {
            System.out.println("РЕЗУЛЬТАТ: CUDA недоступна, паритет проверить нельзя.");
            System.exit(2);
        }
        System.out.println("РЕЗУЛЬТАТ: паритет CPU/GPU подтверждён.");
    }

    /** Одна точка параметров: серия прогонов CPU и GPU + построчная проверка паритета. */
    private static boolean singlePoint(Options o, int width, int height, boolean cuda, List<String> rows) {
        Random random = new Random(o.seed);
        List<Double> cpuSamples = new ArrayList<>();
        List<Double> gpuSamples = new ArrayList<>();
        FractalMetrics cpuMetrics = null;
        FractalMetrics gpuMetrics = null;
        boolean parityOk = true;
        int parityChecked = 0;

        var cpu = new CPUFractalGenerator();
        FractalGenerator gpu = cuda ? createGpu() : null;
        final boolean useGpu = gpu != null;

        for (int run = 0; run < o.warmup + o.runs; run++) {
            double zoom = o.zoom > 0 ? o.zoom : 10_000 + random.nextInt(701) * 140;
            double offsetX = -0.9998 + random.nextDouble() * 1.4498;
            double offsetY = random.nextBoolean()
                    ? -0.7 + random.nextDouble() * 0.6
                    : 0.1 + random.nextDouble() * 0.6;
            int maxIter = o.maxIter > 0 ? o.maxIter : 250 + random.nextInt(101) * 10;

            Timed timedCpu = time(() -> cpu.generate(width, height, zoom, offsetX, offsetY, maxIter));
            if (run >= o.warmup) {
                cpuSamples.add(timedCpu.millis);
                cpuMetrics = FractalMetrics.of(timedCpu.image);
            }

            if (!useGpu) {
                continue;
            }

            Timed timedGpu = time(() -> gpu.generate(width, height, zoom, offsetX, offsetY, maxIter));
            if (run >= o.warmup) {
                gpuSamples.add(timedGpu.millis);
                gpuMetrics = FractalMetrics.of(timedGpu.image);
            }

            if (parityApplies(o, run)) {
                parityChecked++;
                ImageDiff.Diff diff = ImageDiff.compare(timedCpu.image, timedGpu.image);
                if (!diff.identical()) {
                    parityOk = false;
                    System.out.printf(Locale.ROOT,
                            "  ! паритет нарушен: %d из %d пикселей (%.4f%%), макс. Δканала=%d"
                                    + " [zoom=%.0f iter=%d]%n",
                            diff.differingPixels(), diff.totalPixels(), diff.ratio() * 100,
                            diff.maxChannelDelta(), zoom, maxIter);
                }
            }
        }

        String cpuLine = "CPU " + width + "x" + height + " " + Stats.of(cpuSamples) + " " + cpuMetrics;
        System.out.println("  " + cpuLine);
        rows.add(cpuLine);

        if (useGpu) {
            Stats gpuStats = Stats.of(gpuSamples);
            String gpuLine = "GPU " + width + "x" + height + " " + gpuStats + " " + gpuMetrics;
            System.out.println("  " + gpuLine);
            double speedup = speedup(Stats.of(cpuSamples).median(), gpuStats.median());
            System.out.printf(Locale.ROOT, "  ускорение генерации фрактала: %.2fx   паритет: %d/%d прогонов%n",
                    speedup, parityOk ? parityChecked : 0, parityChecked);
            rows.add(gpuLine + String.format(Locale.ROOT, " speedup=%.2f", speedup));
        }
        return parityOk;
    }

    /** Сетка zoom × maxIter: показывает, как выигрыш зависит от глубины итераций. */
    private static boolean sweep(Options o, int width, int height, boolean cuda, List<String> rows) {
        double[] zooms = {10_000, 50_000, 100_000};
        int[] iterValues = {250, 750, 1250};
        boolean parityOk = true;

        System.out.printf(Locale.ROOT, "  %-9s %-8s %10s %10s %9s %s%n",
                "zoom", "maxIter", "CPU мс", "GPU мс", "ускор.", "паритет");

        var cpu = new CPUFractalGenerator();
        FractalGenerator gpu = cuda ? createGpu() : null;
        final boolean useGpu = gpu != null;

        for (double zoom : zooms) {
            for (int maxIter : iterValues) {
                List<Double> cpuSamples = new ArrayList<>();
                List<Double> gpuSamples = new ArrayList<>();
                BufferedImage lastCpu = null;
                BufferedImage lastGpu = null;

                for (int run = 0; run < o.warmup + o.runs; run++) {
                    Timed timedCpu = time(() -> cpu.generate(width, height, zoom, -0.7, 0.25, maxIter));
                    lastCpu = timedCpu.image;
                    if (run >= o.warmup) {
                        cpuSamples.add(timedCpu.millis);
                    }
                    if (useGpu) {
                        Timed timedGpu = time(() -> gpu.generate(width, height, zoom, -0.7, 0.25, maxIter));
                        lastGpu = timedGpu.image;
                        if (run >= o.warmup) {
                            gpuSamples.add(timedGpu.millis);
                        }
                    }
                }

                if (!useGpu) {
                    System.out.printf(Locale.ROOT, "  %-9.0f %-8d %10.2f %10s %9s %s%n",
                            zoom, maxIter, Stats.of(cpuSamples).median(), "-", "-", "-");
                    continue;
                }

                ImageDiff.Diff diff = ImageDiff.compare(lastCpu, lastGpu);
                String parity = diff.identical() ? "ok" : "FAIL " + diff.differingPixels() + "px";
                if (!diff.identical()) {
                    parityOk = false;
                }
                double cpuMedian = Stats.of(cpuSamples).median();
                double gpuMedian = Stats.of(gpuSamples).median();
                double speedup = speedup(cpuMedian, gpuMedian);
                System.out.printf(Locale.ROOT, "  %-9.0f %-8d %10.2f %10.2f %8.2fx %s%n",
                        zoom, maxIter, cpuMedian, gpuMedian, speedup, parity);
                rows.add(String.format(Locale.ROOT,
                        "%dx%d zoom=%.0f iter=%d cpu=%.2f gpu=%.2f speedup=%.2f parity=%s",
                        width, height, zoom, maxIter, cpuMedian, gpuMedian, speedup, parity));
            }
        }
        return parityOk;
    }

    /**
     * XOR: совместимость и скорость.
     * <p>
     * Пары подаются в {@code TYPE_INT_RGB} — именно такой тип возвращает
     * {@code ImageUtils#bytesToImage}, из которого собирается кадр при дешифровании.
     * Исходная реализация {@code GPUXOROperation} на этом типе падала с
     * {@code ClassCastException}, поэтому проверка встроена в бенчмарк.
     * </p>
     */
    private static boolean xorBenchmark(Options o, int width, int height, List<String> rows) {
        Random random = new Random(o.seed + 1);
        BufferedImage a = randomImage(width, height, random, BufferedImage.TYPE_INT_RGB);
        BufferedImage b = randomImage(width, height, random, BufferedImage.TYPE_INT_RGB);

        XOROperation cpuXor = new CPUXOROperation();
        BufferedImage cpuResult = cpuXor.xor(a, b);

        BufferedImage gpuResult;
        try {
            gpuResult = new GPUXOROperation().xor(a, b);
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT, "  XOR: GPU-путь упал (%s: %s) — несовместим с TYPE_INT_RGB%n",
                    t.getClass().getSimpleName(), t.getMessage());
            return false;
        }

        ImageDiff.Diff diff = ImageDiff.compare(cpuResult, gpuResult);
        double cpuMs = median(() -> cpuXor.xor(a, b), o.runs, o.warmup);
        double gpuMs = median(() -> new GPUXOROperation().xor(a, b), o.runs, o.warmup);

        double speedup = speedup(cpuMs, gpuMs);
        System.out.printf(Locale.ROOT,
                "  XOR %dx%d: CPU %.2f мс, GPU %.2f мс, ускорение %.2fx, расхождений %d px %s%n",
                width, height, cpuMs, gpuMs, speedup, diff.differingPixels(),
                diff.identical() ? "(совместимо)" : "(НЕсовместимо)");
        rows.add(String.format(Locale.ROOT, "xor %dx%d cpu=%.2f gpu=%.2f speedup=%.2f diff=%d",
                width, height, cpuMs, gpuMs, speedup, diff.differingPixels()));
        return diff.identical();
    }

    private static boolean parityApplies(Options o, int run) {
        return o.parity && run >= o.warmup;
    }

    /**
     * Создаёт GPU-генератор, перехватывая любую ошибку инициализации JCuda.
     * <p>
     * Исходный {@code GPUFractalGenerator} бросает {@code RuntimeException} из статического
     * инициализатора, что превращается в {@code ExceptionInInitializerError} и роняет всё
     * приложение в обход предусмотренного CPU-fallback. Бенчмарк обязан пережить это
     * и сообщить причину, а не упасть трейсом.
     * </p>
     */
    private static FractalGenerator createGpu() {
        try {
            return new GPUFractalGenerator();
        } catch (Throwable t) {
            System.out.println("  ! инициализация GPU-генератора не удалась: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static double speedup(double cpuMillis, double gpuMillis) {
        return gpuMillis <= 0 ? 0.0 : cpuMillis / gpuMillis;
    }

    private record Timed(BufferedImage image, double millis) {
    }

    private static Timed time(java.util.function.Supplier<BufferedImage> action) {
        long start = System.nanoTime();
        BufferedImage image = action.get();
        return new Timed(image, (System.nanoTime() - start) / 1_000_000.0);
    }

    private static double median(Runnable action, int runs, int warmup) {
        for (int i = 0; i < warmup; i++) {
            action.run();
        }
        double[] samples = new double[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static BufferedImage randomImage(int width, int height, Random random, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private static void printJson(List<String> rows, boolean cuda, boolean parityOk) {
        System.out.println();
        System.out.println("{\"cuda\":" + cuda + ",\"parityOk\":" + parityOk + ",\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            System.out.print("  \"" + rows.get(i).replace("\"", "\\\"") + "\"");
            System.out.println(i == rows.size() - 1 ? "" : ",");
        }
        System.out.println("]}");
    }

    /** Статистика по серии замеров. */
    record Stats(double min, double median, double p95, double max) {

        static Stats of(List<Double> samples) {
            if (samples.isEmpty()) {
                return new Stats(0, 0, 0, 0);
            }
            double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int last = sorted.length - 1;
            return new Stats(sorted[0], sorted[last / 2], sorted[(int) Math.round(last * 0.95)], sorted[last]);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "median=%.2f мс [min %.2f, p95 %.2f, max %.2f]", median, min, p95, max);
        }
    }

    /** Разбор аргументов командной строки. */
    record Options(int[][] sizes, int runs, int warmup, int maxIter, double zoom,
                   boolean parity, boolean sweep, boolean json, long seed) {

        static Options parse(String[] args) {
            List<int[]> sizes = new ArrayList<>();
            int runs = 10;
            int warmup = 3;
            int maxIter = -1;
            double zoom = 50_000;
            boolean parity = true;
            boolean sweep = false;
            boolean json = false;
            long seed = 42;
            int width = 1920;
            int height = 1080;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--width" -> width = Integer.parseInt(args[++i]);
                    case "--height" -> height = Integer.parseInt(args[++i]);
                    case "--runs" -> runs = Integer.parseInt(args[++i]);
                    case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                    case "--maxiter" -> maxIter = Integer.parseInt(args[++i]);
                    case "--zoom" -> zoom = Double.parseDouble(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--no-parity" -> parity = false;
                    case "--sweep" -> sweep = true;
                    case "--json" -> json = true;
                    case "--sizes" -> {
                        for (String part : args[++i].split(",")) {
                            String[] wh = part.toLowerCase(Locale.ROOT).trim().split("x");
                            sizes.add(new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])});
                        }
                    }
                    default -> System.out.println("Неизвестный аргумент: " + args[i]);
                }
            }
            if (sizes.isEmpty()) {
                sizes.add(new int[]{width, height});
            }
            return new Options(sizes.toArray(new int[0][]), runs, warmup, maxIter, zoom,
                    parity, sweep, json, seed);
        }
    }
}
