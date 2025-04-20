#include <stdio.h>
#include <stdlib.h>

#define NI 256
#define NJ 256
#define NK 256

void init_array(int ni, int nj, int nk, double A[ni][nk], double B[nk][nj], double C[ni][nj]) {
    for (int i = 0; i < ni; i++) {
        for (int j = 0; j < nk; j++) {
            A[i][j] = (double)((i * j + 1) % ni) / ni;
        }
    }
    for (int i = 0; i < nk; i++) {
        for (int j = 0; j < nj; j++) {
            B[i][j] = (double)((i * j + 2) % nj) / nj;
        }
    }
    for (int i = 0; i < ni; i++) {
        for (int j = 0; j < nj; j++) {
            C[i][j] = 0.0;
        }
    }
}

void matrix_multiply(int ni, int nj, int nk, double A[ni][nk], double B[nk][nj], double C[ni][nj]) {
    #pragma experimental section start
    for (int i = 0; i < ni; i++) {
        for (int j = 0; j < nj; j++) {
            for (int k = 0; k < nk; k++) {
                C[i][j] += A[i][k] * B[k][j];
            }
        }
    }

    #pragma experimental section stop
}

void print_array(int ni, int nj, double C[ni][nj]) {
    for (int i = 0; i < ni; i++) {
        for (int j = 0; j < nj; j++) {
            printf("%0.2lf ", C[i][j]);
        }
        printf("\n");
    }
}

int main() {
    double (*A)[NK] = malloc(NI * NK * sizeof(double));
    double (*B)[NJ] = malloc(NK * NJ * sizeof(double));
    double (*C)[NJ] = malloc(NI * NJ * sizeof(double));

    init_array(NI, NJ, NK, A, B, C);

    matrix_multiply(NI, NJ, NK, A, B, C);

    print_array(NI, NJ, C);

    free(A);
    free(B);
    free(C);

    return 0;
}
