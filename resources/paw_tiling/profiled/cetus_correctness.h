#ifndef CETUS_CHECKPOINT_H
#define CETUS_CHECKPOINT_H

#include <stdio.h>
#include <stdlib.h>
#include <omp.h>
#include <time.h>
#include <sys/time.h>
#include <string.h>
#include <unistd.h>
void write_var_to_file(char *varName,void* var, size_t sizeOfType, size_t numElements);
void read_var_from_file(char *varName,void* var, size_t sizeOfType, size_t numElements);
void compare_vars_from_file(const char *varName, void *varRef, size_t varSize, size_t varElementsSize);
#endif /* CETUS_CHECKPOINT_H */



