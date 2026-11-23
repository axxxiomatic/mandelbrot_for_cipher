# Форк `axxxiomatic/mandelbrot_for_cipher`: состояние репозитория и интеграция CUDA

> Дата анализа: 2026-11-23. Анализ выполнен по локальному клону, веткам `origin/master`,
> `origin/parallel`, `origin/patch-1` и истории upstream-репозитория `dankotyt/mandelbrot_for_cipher`.
> Код не запускался (в песочнице нет JDK/Gradle и GPU); все выводы о поведении получены статическим
> анализом исходников, PTX-файлов и системы сборки.

---

## 1. Краткое резюме (TL;DR)

| Что | Состояние |
|---|---|
| Форк создан | 2024-10-06, язык Java, размер ≈ 14.8 МБ, описание пустое |
| Upstream | `dankotyt/mandelbrot_for_cipher` («Mandelbrot cipher — one of the best ways to save your information secure»), 2 звезды, 2 форка |
| Ветки форка | `master` — легаси-прототип на Swing (1 коммит, 20.11.2024); `parallel` — полноценная переписка проекта на JavaFX + Spring Boot + Gradle (129 коммитов, последний 17.06.2026); `patch-1` — старая Swing-линия + одна строка readme |
| CUDA | Реализована **только в ветке `parallel`**, ровно одним коммитом `dda800b` «initial with parallel»; upstream этой работы не имеет и PR из форка не открывался |
| Объём CUDA-работы | 22 изменённых файла, +1115 / −102 строк: 3 Java-класса (`com.cipher.gpu`, 212 строк), 2 CUDA-ядра (`.cu`, 84 строки), 2 скомпилированных PTX (491 строка), изменения в 8 файлах ядра шифрования и системы сборки |
| Работоспособность GPU-пути | **Статически неработоспособен по трём независимым причинам** (см. §6): несовпадение типа растра (`DataBufferInt` vs `DataBufferByte`), контекст CUDA создаётся не в том потоке, ядро даёт другой keystream, чем CPU |
| Тесты / CI | GPU-код не покрыт ни одним тестом, CI отсутствует, документации по CUDA нет (README описывает только настройку запуска в IDEA) |
| Главный архитектурный риск | Изображение-фрактал используется как **гамма (keystream) для XOR**, поэтому CPU- и GPU-генераторы обязаны быть бит-в-бит идентичны. Сейчас они дают разные палитры → файл, зашифрованный на машине с GPU, не расшифруется на машине без GPU |

**Вывод:** CUDA-интеграция выполнена аккуратно с точки зрения *архитектуры* (интерфейсы, DI, fallback,
отдельный пакет, PTX в ресурсах) и заметно хуже — с точки зрения *корректности и проверяемости*:
смена вычислительного бэкенда молча меняет криптографический результат, а сам GPU-путь падает на
первом же вызове валидации фрактала. Это хороший каркас для предложения по JCuda и плохой кандидат
на включение в `main` в текущем виде.

---

## 2. Топология репозитория

```
dankotyt/mandelbrot_for_cipher (upstream)
├── main  (0336b27, 2026-04-18)  ← «портабельное ядро», Spring Boot + JavaFX + Gradle, 128 java-файлов
│   └── axxxiomatic/parallel  (dda800b, 2026-06-17)  ← +1 коммит: CUDA/GPU
│
└── (отдельная, исторически несвязанная линия 2024 года)
    └── axxxiomatic/master  (f69a4e7, 2024-11-20)  ← Swing-прототип, 12 java-файлов, 2812 строк
        └── axxxiomatic/patch-1 (9d2a714, 2024-11-06)  ← + Pair, сегментация, readme
```

Проверенные факты:

* `git merge-base origin/master origin/parallel` → **пусто**. У форка две независимые истории:
  «старый» прототип и «новая» переписка. Коммит `cb9807a` (2024-12-22, «интерфейс переписан под JavaFX»)
  является корневым для линии `parallel`.
* `origin/parallel` = `origin/main` upstream + **ровно 1 коммит**. То есть вся CUDA-работа изолирована
  в одном коммите `dda800b`, который легко перебазировать/оформить как PR.
* В форке **нет открытых/закрытых PR** (`gh pr list` пуст). В upstream был только PR #2 от `axxxiomatic`
  («Create readme», закрыт без merge) — то есть CUDA-предложение до upstream не доходило.
* `origin/master` форка побайтово совпадает с тем, что было выложено 20.11.2024 («Add files via upload»),
  и содержит жёстко прописанный путь `C:/Users/r10021998/ideaProjects/...` — проект не запускается
  вне машины автора.

### 2.1. Ветка `master` (легаси-прототип, то, что видит посетитель репозитория по умолчанию)

* 12 Java-файлов, ~2.8 тыс. строк, чистый Swing/AWT, без сборщика (только `.iml` для IntelliJ).
* `Main.java` — сценарий: читает `input.jpg`, читает готовый `mandelbrot1.png`, переводит пиксели в
  `double[][]`, «шифрует» сдвигом битов, режет на сегменты 32×16 и перемешивает, сохраняет
  `encrypted_image.jpg`, по подтверждению — расшифровывает.
* `Mandelbrot.java` / `MandelbrotThread.java` — генерация фрактала (многопоточная, по столбцам,
  `ExecutorService` = число ядер), критерий «разнообразия» — > 500 уникальных цветов и < 25 % на цвет.
* `Model_ImageMatrix`, `View_ImageMatrix`, два `FrameInterface` (один в корне, один в `iface/`) —
  дублирующиеся UI-классы, ~1.5 тыс. строк на Swing-формы.
* Каталог `resources/` — 6 PNG фракталов и результаты шифрования.
* Именно этот код соответствует отчёту «Шифр Мандельброта» (ПИН-36, МИЭТ, 2024), который лежит PDF
  в ветке `parallel`.

### 2.2. Ветка `parallel` (актуальная кодовая база, 129 коммитов)

Существенная переписка, далеко ушедшая от прототипа:

* **Сборка:** Gradle 8.13 wrapper, Java toolchain 21, JavaFX 21.0.8, Spring Boot 3.2.0, jlink/jpackage,
  fatJar + zip-дистрибутив.
* **Слои:** `core/` (шифрование, сеть, UI-контроллеры, DTO), `client/` (P2P-чат, discovery, auth),
  `server/` (REST + Postgres/Redis — часть зависимостей закомментирована), `common/`.
* **UI:** 24 FXML-экрана + 20 контроллеров, иконки в `resources/elements`.
* **Крипто-схема:** ECDH (secp256k1, bitcoinj/bouncycastle) → общий секрет → HKDF-SHA256 с
  метками `fractal-params` / `segmentation` → `SecureRandom("SHA1PRNG")` → 4 параметра фрактала
  (`zoom`, `offsetX`, `offsetY`, `maxIter`) → генерация фрактала → XOR с изображением → сегментация
  и перемешивание → бинарный файл `соль(16) + attempts(4) + 6×int + байты`.
* **Тесты:** 10 классов, 119 `@Test`, JUnit 5 + Mockito (в т.ч. интеграционные прогоны
  encrypt→decrypt на 640×480, 1024×768, 1920×1080).
* **Прочее:** `docker-compose.yml` с Redis, `.env.example` с кредами БД, PDF-отчёт на 19 страниц.

---

## 3. Как устроена интеграция CUDA

### 3.1. Точки расширения (правильный подход)

Вместо правки существующего кода введены два интерфейса в ядре, а GPU-реализации вынесены
в отдельный пакет `com.cipher.gpu`:

```java
// com/cipher/core/generator/FractalGenerator.java
BufferedImage generate(int width, int height, double zoom,
                       double offsetX, double offsetY, int maxIter);

// com/cipher/core/service/encryption/util/XOROperation.java
BufferedImage xor(BufferedImage a, BufferedImage b);
```

Реализации: `CPUFractalGenerator` (57 строк, обёртка над `MandelbrotThread`),
`GPUFractalGenerator` (78 строк), `CPUXOROperation` (24 строки), `GPUXOROperation` (84 строки).

Выбор бэкенда — в Spring-конфигурации, по результату детекта CUDA:

```java
// com/cipher/core/config/AppConfig.java:33-49
@Bean public FractalGenerator fractalGenerator() {
    if (CudaDetector.isCudaAvailable()) return new GPUFractalGenerator();
    else                                return new CPUFractalGenerator();
}
@Bean public XOROperation xorOperation() {
    if (CudaDetector.isCudaAvailable()) return new GPUXOROperation();
    else                                return new CPUXOROperation();
}
```

`MandelbrotService` перестал быть самодостаточным генератором и делегирует работу:

```java
// MandelbrotService.java:86-90  (было ~45 строк с ExecutorService, стало 3)
public BufferedImage generateImage(int width, int height, double ZOOM,
                                   double offsetX, double offsetY, int MAX_ITER) {
    this.image = generator.generate(width, height, ZOOM, offsetX, offsetY, MAX_ITER);
    return this.image;
}
```

`ImageEncryptorImpl` и `ImageDecryptorImpl` получили `XOROperation` через конструктор (Lombok
`@RequiredArgsConstructor`), вызовы `XOR.performXOR(...)` заменены на `xorOperation.xor(...)`.
Это чистое внедрение зависимости — тесты правятся заменой `new ImageEncryptorImpl(a,b,c)` на
`new ImageEncryptorImpl(a,b,c,cpuGenerator,cpuXor)`, что и сделано в 3 тестовых классах.

### 3.2. Детект CUDA — `CudaDetector` (50 строк)

Двухфазная схема с ленивой инициализацией и двойной проверкой (double-checked locking):

* `isCudaAvailable()` — `JCudaDriver.setExceptionsEnabled(true)`, `cuInit(0)`, `cuDeviceGet(dev, 0)`;
  ловит `UnsatisfiedLinkError | NoClassDefFoundError | Exception` и кеширует результат в
  `volatile Boolean`. То есть отсутствие `jcuda-natives` или драйвера **не роняет приложение**,
  а приводит к CPU-fallback. Это главный «плюс» всей интеграции.
* `initializeCudaContext()` — `cuDeviceGet` + `cuCtxCreate(context, 0, device)`, флаг
  `contextInitialized`. При ошибке `cudaAvailable` сбрасывается в `false`.

### 3.3. Генерация фрактала на GPU — `GPUFractalGenerator` + `mandelbrot.cu`

Java-сторона:

1. Статический блок: `CudaDetector.initializeCudaContext()` → `loadModule()`.
2. `loadModule()` читает `/cuda/mandelbrot.ptx` из classpath, `cuModuleLoadData`, `cuModuleGetFunction("mandelbrotKernel")`.
3. `generate()`: `cuMemAlloc(width*height*3)` → сборка `Pointer.to(...)` из 7 параметров →
   сетка `ceil(w/16) × ceil(h/16)`, блок `16×16` → `cuLaunchKernel` → `cuCtxSynchronize` →
   `cuMemcpyDtoH` → `cuMemFree` → копирование байтов в `BufferedImage(TYPE_3BYTE_BGR)` через
   `DataBufferByte.getData()`.

CUDA-ядро (`mandelbrot.cu`, 74 строки, скомпилировано `nvcc -ptx` в `mandelbrot.ptx`,
`.version 8.4`, `.target sm_52`, NVVM 7.0.1, CUDA 12.4):

* классическая итерация `z ← z² + c` в `double` с условием `|z|² < 4`;
* отображение плоскости `cX = (x - width/2)/zoom + offsetX` — **совпадает** с CPU-версией
  (`MandelbrotThread.java:70-77`, где `centerX = imageWidth/2`; исторический `/1.75` из отчёта
  в ветке `parallel` уже убран);
* smooth-coloring: `smooth = iter + 1 − log(log|z|)/log 2`, `hue = 0.95 + 2·smooth/maxIter`,
  `floor`, затем собственная реализация `hsbToRgb` во `__device__`-функции;
* внутренние точки — фиксированный `#000040`, внешние — BGR-триплет;
* защита от выхода за границы изображения (`if (x >= width || y >= height) return`).

### 3.4. XOR на GPU — `GPUXOROperation` + `xor.cu`

* `xor.cu` — 10 строк: один поток на байт, `out[idx] = a[idx] ^ b[idx]`, блок 256, сетка
  `ceil(size/256)`. Ядро идеально подходит для GPU (memory-bound, coalesced).
* Java-сторона: три `cuMemAlloc`, два `cuMemcpyHtoD`, запуск, `cuCtxSynchronize`, `cuMemcpyDtoH`,
  три `cuMemFree`, затем `System.arraycopy` в `BufferedImage(a.getType())`.

### 3.5. Система сборки (build.gradle)

```groovy
def jcudaNativeJar = layout.buildDirectory.dir("jcuda-native")
        .map { it.file("jcuda-natives-12.0.0-windows-x86_64.jar") }

tasks.register('downloadJcudaNatives', DefaultTask) {   // качает jar с repo1.maven.org
    ...
}

dependencies {
    implementation('org.jcuda:jcuda:12.0.0') { exclude group: 'org.jcuda', module: 'jcuda-natives' }
    runtimeOnly files(jcudaNativeJar) { builtBy 'downloadJcudaNatives' }
}

tasks.named('compileJava') { dependsOn 'downloadJcudaNatives' }
```

Приёмы грамотные: нативная часть вынесена из транзитивных зависимостей (иначе Maven подтягивает
classifier под конкретную ОС и сборка ломается на чужой платформе), версия зафиксирована, загрузка
идемпотентна (проверка `dest.exists()`), jar не попадает в Git (`.gitignore` содержит `*.jar`),
но попадает в `fatJar` через `runtimeClasspath`.

### 3.6. Что ещё изменилось «вокруг» GPU

* `ImageEncryptor.findValidFractalParallel(width, height)` — новый метод интерфейса: пул из
  `cpuCores − 1` потоков, до 100 попыток, общий `paramsPrng` под `synchronized`, ранний выход по
  `AtomicReference.compareAndSet`, таймаут 30 с. UI-контроллер `EncryptGenerateController`
  переведён с последовательного цикла на этот метод (было 50 попыток в одном потоке).
* `MandelbrotService` похудел на 42 строки (весь `ExecutorService` переехал в `CPUFractalGenerator`).
* Тесты переведены на явные CPU-реализации и увеличенные размеры изображений (200→640), т.к.
  при малых размерах `generateSegmentSize` даёт сегмент 1 px и проверки становятся вырожденными.

---

## 4. Что даёт CUDA по замыслу

* Генерация фрактала — это `w × h × maxIter` независимых вычислений; типичный кадр 1920×1080 при
  `maxIter ≤ 1250` — это единицы миллиардов итераций на один кандидат, а `findValidFractalParallel`
  может отбраковывать десятки кандидатов. Именно здесь GPU уместен.
* XOR — идеально параллельная memory-bound операция, выигрыш почти линейный по пропускной способности
  памяти (в теории до 5–10× против скалярного `getRGB/setRGB`).
* Архитектурно заложена деградация на CPU, то есть приложение остаётся работоспособным на машинах
  без NVIDIA — это правильное продуктовое решение для десктопа.

Замеров, подтверждающих выигрыш, в репозитории **нет** (ни бенчмарка, ни логирования времени,
ни раздела в README).

---

## 5. Сильные стороны реализации

1. **Чистая сегрегация бэкендов.** `com.cipher.gpu` не знает о шифровании, ядро не знает о CUDA;
   связывание — только через `AppConfig`. Это позволяет позже добавить OpenCL/Vulkan без правок ядра.
2. **Graceful fallback.** Детект ловит `UnsatisfiedLinkError`/`NoClassDefFoundError`, поэтому
   отсутствие `.dll` или драйвера не мешает запуску.
3. **PTX вместо JIT-компиляции на месте.** Ядра компилируются заранее (`nvcc -ptx`), в classpath
   лежит только текст PTX, SDK CUDA на машине пользователя не нужен — достаточно драйвера.
4. **Изоляция в один коммит.** Вся работа — один коммит поверх актуального upstream `main`,
   без «мусорных» merge-коммитов; ревью и перенос в upstream тривиальны.
5. **Нативная библиотека не в Git.** Скачивание по фиксированному URL + `.gitignore` — репозиторий
   не раздувается на 7.3 МБ бинарников.
6. **Тесты остались зелёными.** Тестовые классы переведены на CPU-реализации; GPU-специфичных
   поломок в существующих сценариях нет (при условии, что выбран CPU-бэкенд).

---

## 6. Критические проблемы (по убыванию тяжести)

### 6.1. 🔴 GPU и CPU дают *разные* фракталы → невозможна расшифровка между устройствами

Фрактал здесь — не картинка, а **гамма**: `encryptWhole` делает `xor(originalImage, fractal)`.
Значит, для расшифровки нужно восстановить ровно те же байты фрактала. А формулы палитры разошлись:

| | CPU (`MandelbrotThread`) | GPU (`mandelbrot.cu`) |
|---|---|---|
| счётчик итераций | обратный: `iter = MAX_ITER`, декремент, «внешняя» ⇔ `iter > 0` | прямой: `iter = 0`, инкремент, «внутренняя» ⇔ `iter == maxIter` |
| smooth | `iter + 1 − loglog/log2`, где `iter` — **остаток** | `iter + 1 − loglog/log2`, где `iter` — **число шагов** |
| hue | `0.95 + 2·smooth/maxIter` | `0.95 + 2·smooth/maxIter` |

Так как `smooth` в CPU-версии строится от остатка (`≈ maxIter − n`), а в GPU — от `n`, палитры
фактически зеркальны: `hue_cpu ≈ 0.95 + 2 − 2n/maxIter`, `hue_gpu ≈ 0.95 + 2n/maxIter`.
Даже при полностью совпадающей геометрии цвета пикселей различаются, а значит **XOR даёт мусор**.
Дополнительно накладываются неизбежные расхождения округлений (`Color.HSBtoRGB` в Java против
`hsbToRgb` на `float` во фрактальном ядре, FMA-контракция в PTX).

Практический сценарий: пользователь A (ноутбук с RTX) шифрует и отправляет файл пользователю B
(машина без CUDA). B запускает расшифровку на CPU, получает «шум» и не может понять, что дело
в железе. Это самый опасный дефект, потому что он **не проявляется как ошибка**, а тихо портит данные.

### 6.2. 🔴 `isFractalValid` падает на изображении из GPU (`ClassCastException`)

```java
// MandelbrotService.java:106-107, 146
int[] pixels = ((DataBufferInt) fractal.getRaster().getDataBuffer()).getData();
```

GPU-генератор возвращает `BufferedImage.TYPE_3BYTE_BGR` → в расте лежит `DataBufferByte` →
приведение к `DataBufferInt` бросает `ClassCastException`. Вызов происходит в
`ImageEncryptorImpl.findValidFractalParallel` **внутри `Callable`**, а `Future.get()` для этих
задач никто не вызывает → исключение молча проглатывается, `attemptCount` растёт до 100,
и через ~30 секунд пользователь получает `RuntimeException: Не удалось получить качественный
фрактал за 100 попыток`. То есть на машине с CUDA кнопка «сгенерировать фрактал» **не работает
никогда**, а ошибка выглядит как «не повезло с параметрами».

CPU-генератор возвращает `TYPE_INT_RGB` (`DataBufferInt`) — поэтому проблема не видна при
разработке и в тестах.

### 6.3. 🔴 Контекст CUDA создаётся не в том потоке

`cuCtxCreate` делает контекст текущим **только для вызвавшего потока**. Здесь:

1. `AppConfig.fractalGenerator()` вызывает `CudaDetector.isCudaAvailable()` — только `cuInit`, контекста нет;
2. конструктор/статический инициализатор `GPUFractalGenerator` вызывает `initializeCudaContext()`
   и `cuModuleLoadData(...)` в потоке создания Spring-бина;
3. `generate()` затем вызывается из JavaFX `Task`-потоков и из пула `findValidFractalParallel`
   (`cpuCores − 1` потоков) — там **нет current-контекста**, поэтому `cuMemAlloc`/`cuLaunchKernel`
   вернут `CUDA_ERROR_INVALID_CONTEXT` (а с `setExceptionsEnabled(true)` — бросят `CudaException`).

Правильная схема: `cuDevicePrimaryCtxRetain` + `cuCtxPushCurrent`/`cuCtxPopCurrent` на каждый
рабочий поток, либо один выделенный GPU-поток с очередью задач, либо `cuCtxSetCurrent` до и после
операций. Также нет `cuCtxDestroy`/`cuModuleUnload` при остановке.

Дополнительно: исключение в *статическом инициализаторе* `GPUFractalGenerator` превращается в
`ExceptionInInitializerError` → падение создания Spring-бина → **приложение не стартует**, хотя
формально CPU-fallback предусмотрен. Fallback срабатывает только если `isCudaAvailable()` вернул
`false`, а не если GP-инициализация сломалась позже (например, PTX не поддерживается драйвером).

### 6.4. 🟠 `GPUXOROperation` жёстко требует `DataBufferByte`

```java
byte[] dataA = ((DataBufferByte) a.getRaster().getDataBuffer()).getData(); // :49-50
```

* при шифровании: `originalImage` из `ImageIO.read(jpg)` обычно `TYPE_3BYTE_BGR` — везёт;
* при **дешифровании**: `imageUtils.bytesToImage(...)` создаёт `TYPE_INT_RGB` → `ClassCastException`
  на каждом файле.

То есть даже если обойти п. 6.2, путь «зашифровал на GPU → расшифровал на GPU» остаётся сломанным.
Плюс семантика XOR не совпадает с CPU: CPU ксорит упакованные ARGB-пиксели и принудительно ставит
альфу `0xFF` (`CPUXOROperation.java:20`), GPU ксорит сырые байты, включая альфа-байт у ARGB-растра.

### 6.5. 🟠 `findValidFractalParallel` не масштабируется на GPU и плохо отменяется

* `workers = cpuCores − 1` потоков, каждый генерирует **полноразмерный** фрактал. Для CPU это
  разумная перегрузка (каждая генерация сама по себе многопоточна → конкуренция за ядра), для GPU —
  бессмысленная: один контекст, одна карта, задачи сериализуются, но каждая ещё и аллоцирует
  `w·h·3` байт на устройстве.
* Дочерние задачи не проверяют прерывание: `MandelbrotThread.run()` глушит `Exception` и не
  смотрит на `Thread.interrupted()`; `future.cancel(true)` после `shutdownNow()` не останавливает
  уже считающийся фрактал. Отмена в UI (`cancelCurrentTask`) фактически не отменяет работу.
* По истечении 30 с результат выбрасывается, даже если валидный фрактал появился на 31-й секунде.
* Исключения задач не читаются (`Future.get()` не вызывается) — диагностика потеряна (см. 6.2).

### 6.6. 🟠 Валидация фрактала осталась на CPU и стала узким местом (закон Амдала)

`isFractalValid` + `checkHueDistribution` проходят по **всем** пикселям и на каждом вызывают
`Color.RGBtoHSB` с аллокацией `new float[3]`:

```java
for (int p : pixels) { ... float[] hsv = new float[3]; Color.RGBtoHSB(r, g, b, hsv); ... }
```

Для 1920×1080 это ~2 млн аллокаций и столько же преобразований HSB **на каждого кандидата**,
до 100 кандидатов. GPU-генерация может занимать десятки миллисекунд, а проверка — секунды.
Именно поэтому «ускорение CUDA» в текущем виде почти не изменит отзывчивость UI. Логичное
продолжение работы — считать гистограмму тонов тоже на GPU (atomicAdd в 36 биннингов) или
выборкой по подмножеству пикселей.

### 6.7. 🟡 Сборка и дистрибуция привязаны к Windows + свежему драйверу

* Нативная библиотека качается **только** `windows-x86_64` — на Linux/macOS GPU-путь не
  соберётся (macOS CUDA не поддерживается вовсе). Нужен выбор по `OperatingSystem`/`os.arch`.
* PTX собран nvcc **12.4** (`.version 8.4`, `.target sm_52`). PTX совместим только вперёд:
  на драйвере старше ветки 550 `cuModuleLoadData` вернёт `CUDA_ERROR_UNSUPPORTED_PTX_VERSION`
  → и, из-за п. 6.3, приложение не запустится. Нужны либо cubin/fatbin с несколькими `sm_*`,
  либо честная обработка ошибки загрузки модуля с деградацией на CPU.
* `jlink`/`jpackage`: JCuda — automatic module, а нативный jar подключён как `files(...)` (не
  модуль). Сборка образа/инсталлятора с JCuda не проверена и, вероятнее всего, потребует
  `--bind-services`/`mergeJars`; в `zip`-таске есть `from 'LICENSE'`, которого в репозитории нет.
* Нет способа принудительно отключить GPU (`-Dcipher.cuda=false`), что нужно и для отладки, и
  для пользователей с «полуживым» драйвером.

### 6.8. 🟡 Нулевая проверяемость

* Ни одного теста на `com.cipher.gpu`; `CudaDetector`, оба GPU-класса и оба ядра не исполнялись
  ни в одном автоматическом прогоне.
* Нет теста «CPU vs GPU дают одинаковый keystream» — того самого, который поймал бы 6.1.
* Нет CI (`.github/` отсутствует), нет бенчмарка, нет логирования времени генерации/валидации.
* README ветки `parallel` (10 строк) описывает только `Run → Edit Configurations → Gradle`;
  про CUDA, требования к драйверу и fallback — ни слова; контакт в README — Telegram `@eximun`.

### 6.9. 🟡 Общая криптографическая обвязка (не дефект CUDA, но влияет на приоритеты)

* Ключ к гамме — по сути **4 числа** (`zoom ∈ [10k, 108k]`, `offsetX ∈ [−0.9998, 0.45]`,
  `offsetY` в двух окнах, `maxIter` — 101 значение), детерминированно выводимых из общего секрета.
  Стойкость определяется ECDH-секретом, фрактал лишь расширяет гамму; но любые расхождения
  в его генерации (п. 6.1) полностью ломают совместимость.
* Детерминизм `SecureRandom.getInstance("SHA1PRNG")` + `setSeed` — свойство конкретного
  провайдера JDK, а не спецификации: разные JDK/платформы могут дать разные последовательности.
  Для «передал файл на другое устройство» это такой же риск несовместимости, как и CUDA.
* Нет MAC/аутентификации шифротекста: XOR + перемешивание сегментов молчаливо неотличимы от
  порчи данных; ошибка «не тот keystream» тоже неотличима от «не тот ключ».

---

## 7. Соответствие первоначальной цели («предложения по JCuda»)

| Критерий | Оценка | Комментарий |
|---|---|---|
| Демонстрация JCuda в реальном проекте | ✅ | Два ядра, полный цикл `PTX → module → kernel → memcpy`, не «hello world» |
| Изоляция GPU-кода | ✅ | Отдельный пакет, интерфейсы в ядре, DI через конфиг |
| Fallback на CPU | ⚠️ | Есть при отсутствии CUDA, нет при сбое инициализации/загрузки модуля/неподходящего драйвера |
| Кросс-платформенность | ❌ | Только Windows x86_64 + драйвер ≥ ~550 |
| Корректность (результат не зависит от бэкенда) | ❌ | Палитры разные → keystream разный (п. 6.1) |
| Работоспособность на GPU | ❌ | `ClassCastException` в валидации + контекст не в том потоке (6.2, 6.3) |
| Измеримый эффект | ❓ | Замеров нет; валидация на CPU съедает выигрыш |
| Тесты/CI/документация | ❌ | Нет ни одного из трёх |

Итог: как **прототип-предложение** («вот как JCuda встраивается в это приложение и что это даёт
архитектурно») — работа состоятельная и грамотно оформленная. Как **рабочая фича** — не готова:
её нельзя мержить в `main` без правок как минимум по 6.1–6.4.

---

## 8. Рекомендации (по приоритету)

**P0 — блокеры корректности**

1. **Зафиксировать контракт keystream.** Ввести версию алгоритма генерации (`keystreamVersion`)
   в заголовок файла и отказываться расшифровывать чужую версию с внятным сообщением. Ещё лучше:
   использовать фрактал только как источник параметров/подтверждения, а XOR-гамму строить
   детерминированным PRNG-потоком, независимым от рендеринга.
2. **Привести GPU-палитру к CPU бит-в-бит**: передавать в ядро счётчик `n` (прямой) и считать
   `smooth` по `maxIter − n`, либо наоборот — привести CPU к прямому счёту; убрать `--use_fast_math`,
   зафиксировать `prec-div=true, prec-sqrt=true, fmad=true` и добавить golden-тест
   «hash(cpuFractal) == hash(gpuFractal)» на фиксированных параметрах.
3. **Нормализовать типы изображений**: везде внутри пайплайна `TYPE_INT_RGB`, на границе GPU
   конвертировать `3BYTE_BGR ↔ INT_RGB` (или переписать `isFractalValid` на `getRGB`/`getRGB(...)`
   с `DataBuffer`-aware ветвлением). `GPUXOROperation` — конвертировать входы в `INT_RGB` (или BGR)
   перед копированием на устройство.
4. **Управление CUDA-контекстом**: `cuDevicePrimaryCtxRetain` + `cuCtxPushCurrent/PopCurrent`
   в `generate`/`xor`, либо выделенный GPU-поток; не создавать контекст в статическом инициализаторе.

**P1 — устойчивость**

5. Fallback при *любой* ошибке GP-инициализации: фабрика с `try/catch` вокруг создания GPU-бина,
   плюс системное свойство `-Dcipher.cuda=off` и переменная окружения.
6. Убрать 100 конкурентных GPU-генераций: для GPU-бэкенда генерировать кандидатов последовательно
   (возможен батч в одном вызове), параллелизм оставить CPU-бэкенду.
7. Пробрасывать исключения задач (`future.get()`) и логировать причину отказа; проверять
   `Thread.interrupted()` в `MandelbrotThread`, чтобы отмена в UI работала.
8. Мультиплатформенная загрузка natives (windows/linux, x86_64/aarch64) + два варианта артефактов
   (PTX для JIT и cubin для старых драйверов); проверять поддержку PTX и деградировать на CPU.

**P2 — производительность и качество**

9. Перенести расчёт гистограммы тонов на GPU (atomicAdd по 36 бинам) или семплировать пиксели —
   иначе выигрыш от CUDA теряется на CPU-валидации.
10. Переиспользовать device-буферы и pinned-память, запускать ядра в потоке, добавить
    логирование времени (генерация / копирование / валидация) и бенчмарк-профиль в README.
11. Рассмотреть `float`-ядро с проверкой допустимости по точности (для maxIter ≤ 1250 и zoom ≤ 1e5
    `double` на GeForce имеет FP64-ratio 1:64 — потенциально узкое место).
12. Гигиена репозитория: убрать `extends JPanel` из `MandelbrotService` (Swing-рудимент),
    удалить неиспользуемые `targetWidth/targetHeight`/`generateNextFractal`-ветки, добавить
    `LICENSE` (на него ссылается `zip`-таск) и раздел README «CUDA: требования, fallback, замеры».
13. Добавить CI (сборка + тесты на CPU-бэкенде) и, как минимум, один integration-тест,
    помеченный `@EnabledIfSystemProperty(named="cipher.cuda", matches="true")`.

---

## 9. Как воспроизвести сборку (для проверки выводов)

```bash
git checkout parallel
# нужен JDK 21 и доступ к repo1.maven.org (скачивание jcuda-natives)
./gradlew clean test          # CPU-путь; тесты GPU не затрагивают
./gradlew run                 # запуск приложения (JavaFX)
./gradlew fatJar              # mandelbrot_for_cipher-1.1.0-all.jar с нативом внутри
```

Для GPU-пути дополнительно: Windows x86_64, NVIDIA-драйвер с поддержкой PTX ISA 8.4
(ветка 550+), нативная библиотека `jcuda-natives-12.0.0-windows-x86_64.jar` в
`build/jcuda-native/`. При отсутствии драйвера приложение молча переключится на CPU —
именно поэтому дефект 6.1 легко не заметить при локальной разработке.

---

## 10. Итоговая оценка

Форк представляет собой **две разные эпохи** проекта в двух несвязанных историях: старый
Swing-прототип (`master`, то, что видно по умолчанию) и современную переписку
JavaFX + Spring + Gradle + P2P-чат (`parallel`), поверх которой одним аккуратным коммитом
пристроена CUDA-подсистема на JCuda.

Интеграция CUDA архитектурно убедительна — интерфейсы, DI, отдельный пакет, PTX в ресурсах,
graceful fallback, изоляция нативной библиотеки от Git — и это хороший ответ на вопрос
«как встроить JCuda в это приложение». Но она **не доведена до рабочего состояния**: смена
бэкенда меняет криптографический результат (палитра ядра зеркальна CPU-версии), GPU-изображение
не проходит валидацию из-за типа растра, контекст CUDA создаётся не в том потоке, а
`GPUXOROperation` не примет `TYPE_INT_RGB` при расшифровке. Ни один из этих дефектов не поймали
тесты, потому что GPU-код не покрыт ничем, а CI в репозитории нет.

Практический смысл текущего состояния: это **готовый каркас и честный прототип идеи**, который
после исправления P0-пунктов (детерминизм гаммы, нормализация типов, управление контекстом)
может стать реальным ускорением — при условии, что тяжёлую проверку качества фрактала тоже
перенесут на GPU, иначе закон Амдала оставит выигрыш на бумаге.
