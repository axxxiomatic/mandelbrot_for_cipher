// mandelbrot.cu
// Компиляция: nvcc -ptx mandelbrot.cu -o mandelbrot.ptx

// Преобразование HSB в RGB (все компоненты в диапазоне [0,1] для float)
// Возвращает RGB как unsigned char[3] (порядок R, G, B)
__device__ void hsbToRgb(float hue, float saturation, float brightness,
                         unsigned char* r, unsigned char* g, unsigned char* b) {
    int i = (int)(hue * 6.0f);
    float f = hue * 6.0f - i;
    float p = brightness * (1.0f - saturation);
    float q = brightness * (1.0f - f * saturation);
    float t = brightness * (1.0f - (1.0f - f) * saturation);
    float red, green, blue;

    switch (i % 6) {
        case 0: red = brightness; green = t;        blue = p;        break;
        case 1: red = q;          green = brightness; blue = p;        break;
        case 2: red = p;          green = brightness; blue = t;        break;
        case 3: red = p;          green = q;         blue = brightness;break;
        case 4: red = t;          green = p;         blue = brightness;break;
        case 5: red = brightness; green = p;         blue = q;         break;
    }
    *r = (unsigned char)(red * 255.0f + 0.5f);
    *g = (unsigned char)(green * 255.0f + 0.5f);
    *b = (unsigned char)(blue * 255.0f + 0.5f);
}

extern "C" __global__ void mandelbrotKernel(
    unsigned char* output, int width, int height,
    double zoom, double offsetX, double offsetY, int maxIter)
{
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    double cX = (x - width / 2.0) / zoom + offsetX;
    double cY = (y - height / 2.0) / zoom + offsetY;
    double zx = 0.0, zy = 0.0;
    int iter = 0;

    while (zx*zx + zy*zy < 4.0 && iter < maxIter) {
        double tmp = zx*zx - zy*zy + cX;
        zy = 2.0 * zx * zy + cY;
        zx = tmp;
        iter++;
    }

    int idx = (y * width + x) * 3; // BGR-порядок в памяти

    if (iter == maxIter) {
        // Внутренняя точка – тёмно-синий 0x000040 (BGR: синий=0x40, зелёный=0, красный=0)
        output[idx]     = 0x40; // B
        output[idx + 1] = 0x00; // G
        output[idx + 2] = 0x00; // R
    } else {
        // Гладкое окрашивание (smooth coloring)
        double modZ = sqrt(zx*zx + zy*zy);
        double logModZ = log(max(modZ, 1.0E-7));
        double logLogModZ = log(max(logModZ, 1.0E-7));
        double smooth = iter + 1.0 - logLogModZ / log(2.0);

        // Тон – формула из Java-версии
        double hue = 0.95 + 2.0 * (smooth / maxIter);
        hue = hue - floor(hue); // приводим к диапазону [0, 1)
        float saturation = 0.8f;
        float brightness = 1.0f;

        unsigned char r, g, b;
        hsbToRgb((float)hue, saturation, brightness, &r, &g, &b);
        // Запись в порядке BGR
        output[idx]     = b;
        output[idx + 1] = g;
        output[idx + 2] = r;
    }
}