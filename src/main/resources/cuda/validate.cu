// validate.cu
// Компиляция: nvcc -ptx validate.cu -o validate.ptx
//
// Подсчёт метрик пригодности фрактала прямо на GPU:
//   counters[0]        — число «внутренних» пикселей (цвет 0x000040);
//   counters[1 + bin]  — гистограмма тона по bins бинам.
//
// CPU после запуска читает только эти счётчики и принимает решение — перебирать пиксели
// на хосте больше не нужно (это была главная причина, по которой выигрыш CUDA не доходил
// до пользователя: валидация 2 млн пикселей на кандидата делалась на CPU).
//
// ВАЖНО: индекс бина обязан клампиться в [0, bins). При hue == 1.0 наивное (int)(hue*bins)
// даёт bins и atomicAdd пишет за границу массива — портит память и метрики.

extern "C" __global__ void validateKernel(
    const unsigned char* image, int width, int height, int bins, int* counters)
{
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    int idx = (y * width + x) * 3;   // BGR
    int b = image[idx] & 0xFF;
    int g = image[idx + 1] & 0xFF;
    int r = image[idx + 2] & 0xFF;

    if (r == 0x00 && g == 0x00 && b == 0x40) {
        atomicAdd(&counters[0], 1);
        return;
    }
    // Чёрный фон, как и внутренние точки, в гистограмму не попадает (см. FractalPalette.hueBin).
    if (r == 0 && g == 0 && b == 0) {
        return;
    }

    float rf = (float) r / 255.0f;
    float gf = (float) g / 255.0f;
    float bf = (float) b / 255.0f;

    float max = rf > gf ? rf : gf;
    if (bf > max) max = bf;
    float min = rf < gf ? rf : gf;
    if (bf < min) min = bf;
    float delta = max - min;

    float hue = 0.0f;
    if (delta > 0.0f) {
        if (max == rf) {
            hue = (gf - bf) / delta;
            if (gf < bf) hue += 6.0f;
        } else if (max == gf) {
            hue = (bf - rf) / delta + 2.0f;
        } else {
            hue = (rf - gf) / delta + 4.0f;
        }
        hue /= 6.0f;
    }

    // Знаменатель ядра — то же значение, что и в CPU-валидаторе (FractalValidator.BINS).
    // CPU-я гистограмма обязана совпадать с GPU: иначе первый успешный кандидат на GPU
    // может быть отвергнут подтверждением на CPU, и поиск будет «мигать».
    int bin = (int) (hue * 36.0f);
    if (bin < 0) {
        bin = 0;
    } else if (bin >= bins) {
        bin = bins - 1;
    }
    atomicAdd(&counters[1 + bin], 1);
}
