#include "cetus_correctness.h"
#ifdef CETUS_PAPI
#include "cetus_papi.h"
#endif
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#ifndef ROOT_FILE_BASE
#define ROOT_FILE_BASE "./"
#endif
#define RED "\x1B[31m"
#define GRN "\x1B[32m"
#define MAG "\x1B[35m"
#define RESET "\x1B[0m"
#define verbosity 0

#ifdef CETUS_VERBOSE
#undef verbosity
#define verbosity 1
#endif

bool is_printable_type(size_t sizeOfType)
{
    return (sizeOfType == sizeof(int) || sizeOfType == sizeof(double) || sizeOfType == sizeof(char));
}

void write_var_to_file(char *varName, void *var, size_t sizeOfType, size_t numElements)
{
    #ifdef _OPENMP
    #pragma omp barrier
    #endif
    #ifdef CETUS_PAPI
    if (alreadyRun)
    {
        return;
    }
    #endif
    char fileName[256]; // Adjust size if needed
    snprintf(fileName, sizeof(fileName), "%sresult%s", ROOT_FILE_BASE, varName);
    FILE *fp = fopen(fileName, "w");
    if (fp == NULL)
    {
        perror("Error writing file");
        return;
    }


    
    if(!is_printable_type(sizeOfType))
    {
        // Print a warning if the type is not printable
        printf("%sWarning: Variable %s of size %zu is not a printable type. Writing raw bytes.%s\n", RED, varName, sizeOfType, RESET);
        // Write raw bytes for unsupported types
        fwrite(var, sizeOfType, numElements, fp);
        return;
    }
    
    
    for (size_t i = 0; i < numElements; ++i)
    {
        void *element = (char *)var + i * sizeOfType;

        if((i+1)%20==0){
            printf("\n");
        }

        if (sizeOfType == sizeof(int))
        {
            fprintf(fp, "%d ", *(int *)element);
        }
        else if (sizeOfType == sizeof(double))
        {
            fprintf(fp, "%f ", *(double *)element);
        }
        else if (sizeOfType == sizeof(char))
        {
            fprintf(fp, "%c ", *(char *)element);
        }
        
    }

    if (verbosity > 1)
    {
        printf("%sThe value of variable %s is written to the file.%s\n", GRN, varName, RESET);
    }

    fclose(fp);

}

void read_var_from_file(char *varName, void *var, size_t sizeOfType, size_t numElements)
{
    #ifdef _OPENMP
    #pragma omp barrier
    #endif
    #ifdef CETUS_PAPI
    if (alreadyRun)
    {
        return;
    }
    #endif
    char fileName[256]; // Adjust size if needed
    snprintf(fileName, sizeof(fileName), "%sresult%s", ROOT_FILE_BASE, varName);
    FILE *fp = fopen(fileName, "rb");
    if (fp == NULL)
    {
        perror("Error opening file");
        return;
    }
    if (fread(var, sizeOfType, numElements, fp) != numElements)
    {
        if (feof(fp))
            printf("%sPremature end of file.%s\n", RED, RESET);
        else
            printf("%sFile read error of var %s %s\n", RED, varName, RESET);
    }
    else if (verbosity > 1)
    {
        printf("%sThe value of variable %s is read from the file.%s\n", GRN, varName, RESET);
    }
    fclose(fp);

}

void compare_vars_from_file(const char *varName, void *varRef, size_t varSize, size_t varElementsSize)
{
    #ifdef CETUS_PAPI
    if (alreadyRun)
    {
        return;
    }
    #endif
    char fileName[256];
    snprintf(fileName, sizeof(fileName), "%sresult%s", ROOT_FILE_BASE, varName);
    FILE *fp = fopen(fileName, "rb");
    if (fp == NULL)
    {
        perror("Error opening file for comparison");
        return;
    }
    void *readVar = malloc(varSize * varElementsSize);
    if (readVar == NULL)
    {
        perror("Error allocating memory for comparison");
        fclose(fp);
        return;
    }
    if (fread(readVar, varSize, varElementsSize, fp) != varElementsSize)
    {
        if (feof(fp))
        {
            printf("%sPremature end of file while reading %s.%s\n", RED, fileName, RESET);
        }
        else
        {
            printf("%sError reading file %s.%s\n", RED, fileName, RESET);
        }
    }
    else
    {
        if (memcmp(varRef, readVar, varSize * varElementsSize) == 0)
        {
            if (verbosity > 0)
            {
                printf("%sVariable %s matches the saved state in %s.%s\n", GRN, varName, fileName, RESET);
            }
        }
        else
        {
            printf("%sVariable %s does NOT match the saved state in %s.%s\n", RED, varName, fileName, RESET);
            if (verbosity > 1)
            {
                for (size_t i = 0; i < varElementsSize; ++i)
                {
                    if (memcmp((char *)varRef + i * varSize, (char *)readVar + i * varSize, varSize) != 0)
                    {
                        printf("%sMismatch at element %zu of variable %s in file %s.%s\n", MAG, i, varName, fileName, RESET);
                    }
                }
            }
        }
    }
    free(readVar);
    fclose(fp);
}

