#line 1 "NonTiled.c"

#pragma startinclude #include <stdio.h>
#line 2
#include <stdio.h>
#pragma endinclude
#line 3

int main(int argc, char const *argv[])
{

    int m, n;

    if (argc == 0)
    {
        m = 20;
    }

    if (argc > 0)
    {
        n = argv[0];
    }

    float a[m*n], b[n], c[m];

    int i, j;
    
    for (i = 0; i < m; i++)
    {
        for (j = 0; j < n; j++)
        {
            c[i] += a[i * n + j] * b[j];
        }
    }

    return 0;
}
