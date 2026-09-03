/*
 * Nest with dependence vector (<, >) coming from w[i+1][j-1] read after
 * w[i][j] write: hoisting the j tile loop outermost would create a
 * lexicographically negative vector ((>,*) branch of the strip-mining
 * lemma), so the browser must discard that candidate. Tiling i stays legal.
 */
#include <stdio.h>

#define N 512

double w[N + 1][N + 1];

int main(int argc, char *argv[]) {
    int i, j;
    double checksum = 0.0;

    for (i = 0; i < N + 1; i++) {
        for (j = 0; j < N + 1; j++) {
            w[i][j] = (double)(i * j) / N;
        }
    }

    for (i = 0; i < N; i++) {
        for (j = 1; j < N; j++) {
            w[i][j] = w[i][j] + w[i + 1][j - 1];
        }
    }

    for (i = 0; i < N + 1; i++) {
        for (j = 0; j < N + 1; j++) {
            checksum += w[i][j];
        }
    }
    printf("checksum=%.6f\n", checksum);
    return 0;
}
