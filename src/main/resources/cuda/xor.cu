// xor.cu
// Компиляция: nvcc -ptx xor.cu -o xor.ptx

extern "C" __global__ void xorKernel(
    unsigned char* a, unsigned char* b, unsigned char* out, int totalBytes)
{
    int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx < totalBytes) {
        out[idx] = a[idx] ^ b[idx];
    }
}