/*
 * Loop bounds unknown at compile time (argv): profitability is
 * inconclusive, so the pass must emit the two-version runtime guard
 * (if (unprofitable) untiled else tiled) with symbolic tile sizes.
 */
#include <stdio.h>
#include <stdlib.h>

#define MAXN 1024

double x[MAXN][MAXN];
double y[MAXN][MAXN];
double z[MAXN][MAXN];

int main(int argc, char *argv[]) {
    int n = (argc > 1) ? atoi(argv[1]) : 256;
    int i, j, k;
    double checksum = 0.0;

    if (n > MAXN) {
        n = MAXN;
    }

    for (i = 0; i < n; i++) {
        for (j = 0; j < n; j++) {
            x[i][j] = (double)(i + j) / (n + 1);
            y[i][j] = (double)(i - j) / (n + 1);
            z[i][j] = 0.0;
        }
    }

    for (i = 0; i < n; i++) {
        for (j = 0; j < n; j++) {
            for (k = 0; k < n; k++) {
                z[i][j] = z[i][j] + x[i][k] * y[k][j];
            }
        }
    }

    for (i = 0; i < n; i++) {
        for (j = 0; j < n; j++) {
            checksum += z[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
