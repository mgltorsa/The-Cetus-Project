/*
 * PolyBench-style imperfect nest (syrk shape): a perfect init nest in
 * init_array, and a kernel whose outermost loop is imperfect (scale +
 * rank-k update). PAW must tile the inner perfect compute nest, not
 * init_array, and keep OpenMP on the enclosing i loop.
 */
#include <stdio.h>
#include <stdlib.h>

#define N 64

double C[N][N];
double A[N][N];

void init_array(void)
{
    int i, j;
    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            A[i][j] = (double)(i + j) / N;
            C[i][j] = (double)(i - j) / N;
        }
    }
}

void kernel_compute(void)
{
    int i, j, k;
    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            C[i][j] = C[i][j] * 1.25;
        }
        for (k = 0; k < N; k++) {
            for (j = 0; j < N; j++) {
                C[i][j] = C[i][j] + A[i][k] * A[j][k];
            }
        }
    }
}

int main(int argc, char *argv[])
{
    int i, j;
    double checksum = 0.0;
    (void)argc;
    (void)argv;
    init_array();
    kernel_compute();
    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            checksum += C[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
