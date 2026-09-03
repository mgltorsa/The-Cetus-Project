/*
 * Tiny data set and iteration count: the static profitability check must
 * prove tiling unprofitable and leave the nest untouched (no tile-size
 * variables, no runtime guard).
 */
#include <stdio.h>

#define N 16

double s[N][N];
double t[N][N];

int main(int argc, char *argv[]) {
    int i, j, k;
    double checksum = 0.0;

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            s[i][j] = (double)(i + j);
            t[i][j] = 0.0;
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            for (k = 0; k < N; k++) {
                t[i][j] = t[i][j] + s[i][k] * s[k][j];
            }
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            checksum += t[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
