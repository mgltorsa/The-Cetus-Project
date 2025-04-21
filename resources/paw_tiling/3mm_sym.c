#include <stdio.h>
#include <stdlib.h>


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
    int ni = 10000;
    int nj = 10000;
    int nk = 10000;

    double (*A)[nk] = malloc(ni * nk * sizeof(double));
    double (*B)[nj] = malloc(nk * nj * sizeof(double));
    double (*C)[nj] = malloc(ni * nj * sizeof(double));

    init_array(ni, nj, nk, A, B, C);

    matrix_multiply(ni, nj, nk, A, B, C);

    print_array(ni, nj, C);

    free(A);
    free(B);
    free(C);

    return 0;
}
