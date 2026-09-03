/*
 * PawTiling.c - example for the Parallel-Aware Tiling (PAW) pass.
 *
 * The main kernel is a dense matrix multiply (i, j, k). Every loop carries
 * temporal reuse for one reference group (c[i][j], a[i][k], b[k][j]), so
 * the pass browses the loops in decreasing-reuse order, strip-mines the
 * whole nest with symbolic tile sizes, checks legality with the
 * direction-vector lemmas, re-derives parallelism on the TILED nest, and
 * balances the tile size of the parallel tile loop across the cores.
 *
 * Generated versions (committed next to this file):
 *   PawTiling_tiled.c       bin/cetus -paw_tiling=1 -cores=4 -cacheSize=1024
 *                                     -cacheLine=64 -selection=NT PawTiling.c
 *   PawTiling_tiled_papi.c  same + -papi_instrument=1
 *
 * The instrumented version needs PAPI at compile time:
 *   gcc -O2 -fopenmp PawTiling_tiled_papi.c -lpapi          (real counters)
 *   gcc -O2 -fopenmp -I resources/paw_tests/papi_stub \
 *       PawTiling_tiled_papi.c                              (stub, no PAPI)
 */
#include <stdio.h>

#define N 768

double a[N][N];
double b[N][N];
double c[N][N];

int main(void) {
    int i, j, k;
    double checksum = 0.0;

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            a[i][j] = (double)(i + j) / N;
            b[i][j] = (double)(i - j) / N;
            c[i][j] = 0.0;
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            for (k = 0; k < N; k++) {
                c[i][j] = c[i][j] + a[i][k] * b[k][j];
            }
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            checksum += c[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
