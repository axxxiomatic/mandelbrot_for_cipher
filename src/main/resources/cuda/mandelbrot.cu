// mandelbrot.cu
// Компиляция: nvcc -ptx mandelbrot.cu -o mandelbrot.ptx
//            nvcc --fmad=false -ptx mandelbrot.cu -o mandelbrot.ptx   (обязательно для паритета)
//
// ВАЖНО: ядро обязано повторять FractalPalette (CPU) бит-в-бит.
//   * только IEEE-операции: + - * / sqrt, floor, приведение double -> float;
//   * никаких log/exp/pow (libdevice не совпадает с Math.* в пределах ulp);
//   * никакой FMA-контракции: собирать с --fmad=false;
//   * счётчик итераций прямой, как в CPU-версии.

__device__ void hsbToRgb(float hue, float saturation, float brightness,
                         unsigned char* r, unsigned char* g, unsigned char* b)
{
    float hStep = (hue - floorf(hue)) * 6.0f;
    float f = hStep - floorf(hStep);
    float p = brightness * (1.0f - saturation);
    float q = brightness * (1.0f - saturation * f);
    float t = brightness * (1.0f - (saturation * (1.0f - f)));
    int sector = (int) hStep;

    float red;
    float green;
    float blue;
    switch (sector) {
        case 0: red = brightness; green = t;          blue = p;          break;
        case 1: red = q;          green = brightness; blue = p;          break;
        case 2: red = p;          green = brightness; blue = t;          break;
        case 3: red = p;          green = q;          blue = brightness; break;
        case 4: red = t;          green = p;          blue = brightness; break;
        default: red = brightness; green = p;         blue = q;          break;
    }

    *r = (unsigned char) (red * 255.0f + 0.5f);
    *g = (unsigned char) (green * 255.0f + 0.5f);
    *b = (unsigned char) (blue * 255.0f + 0.5f);
}

extern "C" __global__ void mandelbrotKernel(
    unsigned char* output, int width, int height,
    double zoom, double offsetX, double offsetY, int maxIter)
{
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    // Те же координаты, что в MandelbrotThread.
    int centerX = width / 2;
    int centerY = height / 2;
    double cX = (x - centerX) / zoom + offsetX;
    double cY = (y - centerY) / zoom + offsetY;

    double zx = 0.0;
    double zy = 0.0;
    int iterations = 0;

    while (zx * zx + zy * zy < 4.0 && iterations < maxIter) {
        double tmp = zx * zx - zy * zy + cX;
        zy = 2.0 * zx * zy + cY;
        zx = tmp;
        iterations++;
    }

    int idx = (y * width + x) * 3;   // BGR-порядок в памяти

    if (iterations >= maxIter) {
        // Внутренняя точка, как в FractalPalette.INTERNAL_COLOR (0x000040, BGR)
        output[idx]     = 0x40;
        output[idx + 1] = 0x00;
        output[idx + 2] = 0x00;
    } else {
        double modZ = sqrt(zx * zx + zy * zy);

        // Повторяет FractalPalette.hueFor: только IEEE-операции.
        double fraction = 2.0 / modZ;
        if (!(fraction >= 0.0)) {
            fraction = 0.0;
        } else if (fraction > 1.0) {
            fraction = 1.0;
        }
        double progress = ((double) iterations + (1.0 - fraction)) / (double) maxIter;
        double hue = 0.95 + 2.0 * progress;
        hue = hue - floor(hue);

        unsigned char r, g, b;
        hsbToRgb((float) hue, 0.8f, 1.0f, &r, &g, &b);
        output[idx]     = b;
        output[idx + 1] = g;
        output[idx + 2] = r;
    }
}

// Пакетная генерация: каждый кадр — свой участок большого буфера.
extern "C" __global__ void mandelbrotBatchKernel(
    unsigned char* output, int width, int height, int frameSize, int count,
    const double* params)   // count * 4: zoom, offsetX, offsetY, maxIter (как double)
{
    int frame = blockIdx.z;
    if (frame >= count) return;

    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    double zoom = params[frame * 4];
    double offsetX = params[frame * 4 + 1];
    double offsetY = params[frame * 4 + 2];
    int maxIter = (int) params[frame * 4 + 3];

    int centerX = width / 2;
    int centerY = height / 2;
    double cX = (x - centerX) / zoom + offsetX;
    double cY = (y - centerY) / zoom + offsetY;

    double zx = 0.0;
    double zy = 0.0;
    int iterations = 0;

    while (zx * zx + zy * zy < 4.0 && iterations < maxIter) {
        double tmp = zx * zx - zy * zy + cX;
        zy = 2.0 * zx * zy + cY;
        zx = tmp;
        iterations++;
    }

    int idx = frame * frameSize + (y * width + x) * 3;

    if (iterations >= maxIter) {
        output[idx]     = 0x40;
        output[idx + 1] = 0x00;
        output[idx + 2] = 0x00;
    } else {
        double modZ = sqrt(zx * zx + zy * zy);
        double fraction = 2.0 / modZ;
        if (!(fraction >= 0.0)) {
            fraction = 0.0;
        } else if (fraction > 1.0) {
            fraction = 1.0;
        }
        double progress = ((double) iterations + (1.0 - fraction)) / (double) maxIter;
        double hue = 0.95 + 2.0 * progress;
        hue = hue - floor(hue);

        unsigned char r, g, b;
        hsbToRgb((float) hue, 0.8f, 1.0f, &r, &g, &b);
        output[idx]     = b;
        output[idx + 1] = g;
        output[idx + 2] = r;
    }
}
