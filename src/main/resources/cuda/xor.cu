// xor.cu
// Компиляция: nvcc -ptx xor.cu -o xor.ptx            (или автоматически через NVRTC)
//
// Побайтовый XOR двух BGR-изображений. Байт альфы в буфер не входит (см. RasterUtils.toBgrBytes),
// поэтому результат совпадает с CPUXOROperation, которая ксерит упакованные ARGB и ставит 0xFF.

extern "C" __global__ void xorKernel(
    unsigned char* a, unsigned char* b, unsigned char* out, int totalBytes)
{
    int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx < totalBytes) {
        out[idx] = a[idx] ^ b[idx];
    }
}
