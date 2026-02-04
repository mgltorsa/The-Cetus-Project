#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#define N 100
#define MAX_ITER 1000
#define TOL 1e-6

void jacobi(int n, int max_iter, double tol, double A[n][n], double b[n], double x[n]) {
    double x_new[n];
    int iter, i, j;
    double diff, sum;

    for (i = 0; i < n; i++)
        x[i] = 0.0;

    for (iter = 0; iter < max_iter; iter++) {
        // Compute new values
        for (i = 0; i < n; i++) {
            sum = 0.0;
            for (j = 0; j < n; j++) {
                if (j != i)
                    sum += A[i][j] * x[j];
            }
            x_new[i] = (b[i] - sum) / A[i][i];
        }

        // Compute difference and update x
        diff = 0.0;
        for (i = 0; i < n; i++) {
            diff += fabs(x_new[i] - x[i]);
            x[i] = x_new[i];
        }

        if (diff < tol)
            break;
    }
}

int main() {
    double A[N][N], b[N], x[N];
    int i, j;

    // Initialize A as diagonally dominant and b
    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            if (i == j)
                A[i][j] = 2.0;
            else
                A[i][j] = 1.0;
        }
        b[i] = N + 1.0;
    }

    jacobi(N, MAX_ITER, TOL, A, b, x);

    // Print first 10 results
    for (i = 0; i < 10; i++)
        printf("x[%d] = %f\n", i, x[i]);

    return 0;
}