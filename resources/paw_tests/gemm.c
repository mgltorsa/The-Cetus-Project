/*
 * Dense matrix multiply (thesis running example): 3 reference groups
 * (d[i][j], a[i][k], b[k][j]); every loop carries temporal reuse for one
 * of them, so the expected browse order is k, j, i (innermost-first ties)
 * and the whole nest is tiled. Loop i (and its tile loop ii) is parallel.
 */
#include <stdio.h>
#include <stdlib.h>

#define N 512

double a[N][N];
double b[N][N];
double d[N][N];

int main(int argc, char *argv[]) {
    int i, j, k;
    double checksum = 0.0;

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            a[i][j] = (double)(i + j) / N;
            b[i][j] = (double)(i - j) / N;
            d[i][j] = 0.0;
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            for (k = 0; k < N; k++) {
                d[i][j] = d[i][j] + a[i][k] * b[k][j];
            }
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            checksum += d[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
