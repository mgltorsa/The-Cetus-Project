#ifndef CETUS_PAPI_INSTRUMENTATION
#define CETUS_PAPI_INSTRUMENTATION
#include <stdio.h>
#include <papi.h>
static int cetus_papi_initialized = 0;
static void cetus_papi_lazy_init(void) {
	    if (!cetus_papi_initialized) {
		        int cetus_papi_rc = PAPI_library_init(PAPI_VER_CURRENT);
		        if (cetus_papi_rc != PAPI_VER_CURRENT) {
			            fprintf(stderr, "[cetus-papi] library init failed rc=%d\n", cetus_papi_rc);
		        }
		        cetus_papi_initialized = 1;
	    }
}
#endif

#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define ABS(x) ((x)<0?-(x):(x))
#define CEIL(x) ((int)((x)+0.5))
#define FLOOR(x) ((int)((x)-0.5))
#define ROUND(x) ((int)((x)+0.5))

/*
Copyright (C) 1991-2024 Free Software Foundation, Inc.
   This file is part of the GNU C Library.

   The GNU C Library is free software; you can redistribute it andor
   modify it under the terms of the GNU Lesser General Public
   License as published by the Free Software Foundation; either
   version 2.1 of the License, or (at your option) any later version.

   The GNU C Library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Lesser General Public License for more details.

   You should have received a copy of the GNU Lesser General Public
   License along with the GNU C Library; if not, see
   <https:www.gnu.org/licenses/>. 
*/
/*
This header is separate from features.h so that the compiler can
   include it implicitly at the start of every compilation.  It must
   not itself include <features.h> or any other header that includes
   <features.h> because the implicit include comes before any feature
   test macros that may be defined in a source file before it first
   explicitly includes a system header.  GCC knows the name of this
   header in order to preinclude it. 
*/
/*
glibc's intent is to support the IEC 559 math functionality, real
   and complex.  If the GCC (4.9 and later) predefined macros
   specifying compiler intent are available, use them to determine
   whether the overall intent is to support these features; otherwise,
   presume an older compiler has intent to support these features and
   define these macros by default. 
*/
/*
wchar_t uses Unicode 10.0.0.  Version 10.0 of the Unicode Standard is
   synchronized with ISOIEC 10646:2017, fifth edition, plus
   the following additions from Amendment 1 to the fifth edition:
   - 56 emoji characters
   - 285 hentaigana
   - 3 additional Zanabazar Square characters
*/
/*

 PawTiling.c - example for the Parallel-Aware Tiling (PAW) pass.
 *
 * The main kernel is a dense matrix multiply (i, j, k). Every loop carries
 * temporal reuse for one reference group (c[i][j], a[i][k], b[k][j]), so
 * the pass browses the loops in decreasing-reuse order, strip-mines the
 * whole nest with symbolic tile sizes, checks legality with the
 * direction-vector lemmas, re-derives parallelism on the TILED nest, and
 * balances the tile size of the parallel tile loop across the cores.
 *
 * Generated versions (committed next to this file):
 *   PawTiling_tiled.c       bin/cetus -paw_tiling=1 -cores=4 -cacheSize=1024
 *                                     -cacheLine=64 -selection=NT PawTiling.c
 *   PawTiling_tiled_papi.c  same + -papi_instrument=1
 *
 * The instrumented version needs PAPI at compile time:
 *   gcc -O2 -fopenmp PawTiling_tiled_papi.c -lpapi          (real counters)
 *   gcc -O2 -fopenmp -I resources/paw_tests/papi_stub  *       PawTiling_tiled_papi.c                              (stub, no PAPI)
)

*/
#include <stdio.h>
double a[768][768];
double b[768][768];
double c[768][768];
int main(void )
{
	int i, j, k;
	double checksum = 0.0;
	int _ret_val_0;
	int cetus_tile_main_0_j = 8;
	int j_cetus_cross;
	int cetus_tile_main_0_i = 8;
	int i_cetus_cross;
	int cetus_tile_main_1_j = 8;
	int cetus_tile_main_1_k = 8;
	int k_cetus_cross;
	int cetus_tile_main_1_i = 8;
	int cetus_tile_main_2_j = 8;
	int cetus_tile_main_2_i = 8;
int cetus_papi_es_0 = PAPI_NULL; long long cetus_papi_vals_0[2]; int cetus_papi_rc_0; { int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < 2; cetus_papi_k++) cetus_papi_vals_0[cetus_papi_k] = -1; }
	cetus_papi_lazy_init();
	cetus_papi_rc_0 = PAPI_create_eventset(&cetus_papi_es_0);
	if (cetus_papi_rc_0 == PAPI_OK) cetus_papi_rc_0 = PAPI_add_named_event(cetus_papi_es_0, "PAPI_L3_DCM");
	if (cetus_papi_rc_0 == PAPI_OK) cetus_papi_rc_0 = PAPI_add_named_event(cetus_papi_es_0, "PAPI_TOT_CYC");
	if (cetus_papi_rc_0 == PAPI_OK) cetus_papi_rc_0 = PAPI_start(cetus_papi_es_0);
	if (cetus_papi_rc_0 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_0 start failed rc=%d\n", cetus_papi_rc_0);
	#pragma cetus parallel 
	#pragma cetus private(i, i_cetus_cross, j, j_cetus_cross) 
	#pragma loop name main#0 
	#pragma c_paw_tiling main#0=j#8 
	#pragma c_paw_tiling main#0=i#8 
	#pragma loop name main#0 
	#pragma loop name main#0 
	#pragma omp parallel for private(i, i_cetus_cross, j, j_cetus_cross)
	for (i_cetus_cross=0; i_cetus_cross<768; i_cetus_cross+=cetus_tile_main_0_i)
	{
		#pragma cetus private(i, j, j_cetus_cross) 
		#pragma loop name main#0#0 
		#pragma loop name main#0#0 
		#pragma loop name main#0#0 
		for (j_cetus_cross=0; j_cetus_cross<768; j_cetus_cross+=cetus_tile_main_0_j)
		{
			#pragma cetus private(i, j) 
			#pragma loop name main#0#0#0 
			#pragma loop name main#0#0#0 
			#pragma loop name main#0#0#0 
			for (i=i_cetus_cross; i<MIN(768, (cetus_tile_main_0_i+i_cetus_cross)); i ++ )
			{
				#pragma cetus private(j) 
				#pragma loop name main#0#0 
				#pragma cetus private(j) 
				#pragma loop name main#0#0#0#0 
				#pragma loop name main#0#0#0#0 
				#pragma loop name main#0#0#0#0 
				for (j=j_cetus_cross; j<MIN(768, (cetus_tile_main_0_j+j_cetus_cross)); j ++ )
				{
					a[i][j]=(((double)(i+j))/768);
					b[i][j]=(((double)(i-j))/768);
					c[i][j]=0.0;
				}
			}
		}
	}
if (cetus_papi_rc_0 == PAPI_OK) { cetus_papi_rc_0 = PAPI_stop(cetus_papi_es_0, cetus_papi_vals_0); if (cetus_papi_rc_0 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_0 stop failed rc=%d\n", cetus_papi_rc_0); }
	fprintf(stderr, "[cetus-papi] region_0 PAPI_L3_DCM=%lld PAPI_TOT_CYC=%lld\n", cetus_papi_vals_0[0], cetus_papi_vals_0[1]);
int cetus_papi_es_1 = PAPI_NULL; long long cetus_papi_vals_1[2]; int cetus_papi_rc_1; { int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < 2; cetus_papi_k++) cetus_papi_vals_1[cetus_papi_k] = -1; }
	cetus_papi_lazy_init();
	cetus_papi_rc_1 = PAPI_create_eventset(&cetus_papi_es_1);
	if (cetus_papi_rc_1 == PAPI_OK) cetus_papi_rc_1 = PAPI_add_named_event(cetus_papi_es_1, "PAPI_L3_DCM");
	if (cetus_papi_rc_1 == PAPI_OK) cetus_papi_rc_1 = PAPI_add_named_event(cetus_papi_es_1, "PAPI_TOT_CYC");
	if (cetus_papi_rc_1 == PAPI_OK) cetus_papi_rc_1 = PAPI_start(cetus_papi_es_1);
	if (cetus_papi_rc_1 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_1 start failed rc=%d\n", cetus_papi_rc_1);
	#pragma cetus parallel 
	#pragma cetus private(i, i_cetus_cross, j, j_cetus_cross, k, k_cetus_cross) 
	#pragma loop name main#1 
	#pragma loop name main#1 
	#pragma c_paw_tiling main#1=j#8 
	#pragma c_paw_tiling main#1=k#8 
	#pragma c_paw_tiling main#1=i#8 
	#pragma loop name main#1 
	#pragma omp parallel for private(i, i_cetus_cross, j, j_cetus_cross, k, k_cetus_cross)
	for (i_cetus_cross=0; i_cetus_cross<768; i_cetus_cross+=cetus_tile_main_1_i)
	{
		#pragma cetus private(i, j, j_cetus_cross, k, k_cetus_cross) 
		#pragma loop name main#1#0 
		#pragma loop name main#1#0 
		#pragma loop name main#1#0 
		for (k_cetus_cross=0; k_cetus_cross<768; k_cetus_cross+=cetus_tile_main_1_k)
		{
			#pragma cetus private(i, j, j_cetus_cross, k) 
			#pragma loop name main#1#0#0 
			#pragma loop name main#1#0#0 
			#pragma loop name main#1#0#0 
			for (j_cetus_cross=0; j_cetus_cross<768; j_cetus_cross+=cetus_tile_main_1_j)
			{
				#pragma cetus private(i, j, k) 
				#pragma loop name main#1#0#0#0 
				#pragma loop name main#1#0#0#0 
				#pragma loop name main#1#0#0#0 
				for (i=i_cetus_cross; i<MIN(768, (cetus_tile_main_1_i+i_cetus_cross)); i ++ )
				{
					#pragma cetus private(j, k) 
					#pragma loop name main#1#0 
					#pragma cetus private(j, k) 
					#pragma loop name main#1#0#0#0#0 
					#pragma loop name main#1#0#0#0#0 
					#pragma loop name main#1#0#0#0#0 
					for (k=k_cetus_cross; k<MIN(768, (cetus_tile_main_1_k+k_cetus_cross)); k ++ )
					{
						#pragma cetus private(k) 
						#pragma loop name main#1#0#0 
						#pragma cetus private(j) 
						#pragma loop name main#1#0#0#0#0#0 
						#pragma loop name main#1#0#0#0#0#0 
						#pragma loop name main#1#0#0#0#0#0 
						for (j=j_cetus_cross; j<MIN(768, (cetus_tile_main_1_j+j_cetus_cross)); j ++ )
						{
							c[i][j]=(c[i][j]+(a[i][k]*b[k][j]));
						}
					}
				}
			}
		}
	}
if (cetus_papi_rc_1 == PAPI_OK) { cetus_papi_rc_1 = PAPI_stop(cetus_papi_es_1, cetus_papi_vals_1); if (cetus_papi_rc_1 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_1 stop failed rc=%d\n", cetus_papi_rc_1); }
	fprintf(stderr, "[cetus-papi] region_1 PAPI_L3_DCM=%lld PAPI_TOT_CYC=%lld\n", cetus_papi_vals_1[0], cetus_papi_vals_1[1]);
int cetus_papi_es_2 = PAPI_NULL; long long cetus_papi_vals_2[2]; int cetus_papi_rc_2; { int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < 2; cetus_papi_k++) cetus_papi_vals_2[cetus_papi_k] = -1; }
	cetus_papi_lazy_init();
	cetus_papi_rc_2 = PAPI_create_eventset(&cetus_papi_es_2);
	if (cetus_papi_rc_2 == PAPI_OK) cetus_papi_rc_2 = PAPI_add_named_event(cetus_papi_es_2, "PAPI_L3_DCM");
	if (cetus_papi_rc_2 == PAPI_OK) cetus_papi_rc_2 = PAPI_add_named_event(cetus_papi_es_2, "PAPI_TOT_CYC");
	if (cetus_papi_rc_2 == PAPI_OK) cetus_papi_rc_2 = PAPI_start(cetus_papi_es_2);
	if (cetus_papi_rc_2 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_2 start failed rc=%d\n", cetus_papi_rc_2);
	#pragma cetus parallel 
	#pragma cetus private(i, i_cetus_cross, j, j_cetus_cross) 
	#pragma cetus reduction(+: checksum) 
	#pragma loop name main#2 
	#pragma loop name main#2 
	#pragma loop name main#2 
	#pragma c_paw_tiling main#2=j#8 
	#pragma c_paw_tiling main#2=i#8 
	#pragma omp parallel for private(i, i_cetus_cross, j, j_cetus_cross) reduction(+: checksum)
	for (i_cetus_cross=0; i_cetus_cross<768; i_cetus_cross+=cetus_tile_main_2_i)
	{
		#pragma cetus private(i, j, j_cetus_cross) 
		#pragma cetus reduction(+: checksum) 
		#pragma loop name main#2#0 
		#pragma loop name main#2#0 
		#pragma loop name main#2#0 
		for (j_cetus_cross=0; j_cetus_cross<768; j_cetus_cross+=cetus_tile_main_2_j)
		{
			#pragma cetus private(i, j) 
			#pragma cetus reduction(+: checksum) 
			#pragma loop name main#2#0#0 
			#pragma loop name main#2#0#0 
			#pragma loop name main#2#0#0 
			for (i=i_cetus_cross; i<MIN(768, (cetus_tile_main_2_i+i_cetus_cross)); i ++ )
			{
				#pragma cetus private(j) 
				#pragma loop name main#2#0 
				/* #pragma cetus reduction(+: checksum)  */
				#pragma cetus private(j) 
				#pragma cetus reduction(+: checksum) 
				#pragma loop name main#2#0#0#0 
				#pragma loop name main#2#0#0#0 
				#pragma loop name main#2#0#0#0 
				for (j=j_cetus_cross; j<MIN(768, (cetus_tile_main_2_j+j_cetus_cross)); j ++ )
				{
					checksum+=c[i][j];
				}
			}
		}
	}
if (cetus_papi_rc_2 == PAPI_OK) { cetus_papi_rc_2 = PAPI_stop(cetus_papi_es_2, cetus_papi_vals_2); if (cetus_papi_rc_2 != PAPI_OK) fprintf(stderr, "[cetus-papi] region_2 stop failed rc=%d\n", cetus_papi_rc_2); }
	fprintf(stderr, "[cetus-papi] region_2 PAPI_L3_DCM=%lld PAPI_TOT_CYC=%lld\n", cetus_papi_vals_2[0], cetus_papi_vals_2[1]);
	printf("checksum=%.6f\n", checksum);
	_ret_val_0=0;
	return _ret_val_0;
}
