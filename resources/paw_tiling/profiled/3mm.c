#ifdef CETUS_CHECKPOINT
#include "cetus_correctness.h"
#endif

#ifdef CETUS_TIMING
typedef struct cetusprofile cetusprofile;
extern cetusprofile cetus_prof;
void cetus_tic(cetusprofile *, int);
void cetus_toc(cetusprofile *, int);
#endif /* CETUS_TIMING */


#ifdef CETUS_PAPI
#include "cetus_papi.h"
#endif

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
#include <stdio.h>
#include <stdlib.h>
#ifdef CETUS_TIMING


#ifndef HAS_SYS_TIME_H
#include <sys/time.h>
#endif
/* structure for a timed event */
struct timeval;
struct cetusevent;
typedef struct cetusevent {
	  long count;                 /* number of invocations        */
	  long etime;                 /* elapsed time in microseconds */
	  long child_count;           /* number of child invocations  */
	  long child_etime;           /* elapsed time of child event  */
	  long child_count_cumul;     /* cumulative child invocations */
	  char *name;                 /* name of the event            */
	  struct timeval since;       /* latest time stamp            */
	  struct cetusevent *parent;  /* reference to parent event    */
} cetusevent;

/* structure for global profiling */
typedef struct cetusprofile {
	  int num_events;       /* number of timed events         */
	  long num_depths;      /* number of depths of all events */
	  long num_invocs;      /* number of invocated events     */
	  double overhead;      /* measured overhead per event    */
	  double overhead_in;   /* included overhead per event    */
	  cetusevent *current;  /* current ongoing event          */
	  cetusevent *event;    /* events to be profiled          */
} cetusprofile;

/* starts timing a single invocation of an event */
void cetus_tic(cetusprofile *prof, int id)
{
	  cetusevent *evt = prof->event+id;
	  evt->parent = prof->current;
	  prof->current = evt;
	  gettimeofday(&evt->since, 0);
}

/* finishes timing a single invocation of an event */
void cetus_toc(cetusprofile *prof, int id)
{
	  int i;
	  long diff;
	  struct timeval now;
	  cetusevent *ev, *evt = prof->event+id;
	
	  gettimeofday(&now, 0);
	  diff = 1000000 *
	      (now.tv_sec-evt->since.tv_sec)+now.tv_usec-evt->since.tv_usec;
	  /* updates count and elapsed time of the current event */
	  evt->count ++;
	  evt->etime += diff;
	  /* updates the child info of the parent event */
	  if (evt->parent) {
		    evt->parent->child_count ++;
		    evt->parent->child_etime += diff;
	  }
	  /* updates the number of cumulative child invocations */
	  for (i = 0, ev = evt->parent; ev; i++, ev = ev->parent)
	    ev->child_count_cumul ++;
	  /* updates global profile */
	  prof->num_depths += i;
	  prof->current = evt->parent;
	  evt->parent = 0;
}

/* initializes the data structure */
void cetus_init_timers(cetusprofile *prof, char names[][32])
{
	  int i;
	
	  /* initializes the event data */
	  prof->event = 
	    (cetusevent *)malloc(prof->num_events*sizeof(cetusevent));
	  for (i = 0; i < prof->num_events; i++) {
		    prof->event[i].count = 0;
		    prof->event[i].etime = 0;
		    prof->event[i].child_count = 0;
		    prof->event[i].child_etime = 0;
		    prof->event[i].child_count_cumul = 0;
		    prof->event[i].parent = 0;
		    if (names)
		      prof->event[i].name = names[i];
	  }
}

/* computes overheads from the profiling code */
void cetus_tune_timer(cetusprofile *prof)
{
	  int i, j, iter=1000000;
  cetusprofile p = {0, 0, 0, 0.0, 0.0, 0, 0};
	  /* computes the average number of depths */
	  for (i = 0; i < prof->num_events; i++)
	    prof->num_invocs += prof->event[i].count;
	  p.num_events = prof->num_depths/prof->num_invocs + 2;
	  /* measures overheads from timing with simulated events */
	  cetus_init_timers(&p, 0);
	  for (i = 0; i < p.num_events-1; i++)
	    cetus_tic(&p, i);
	  for (j = 0; j < iter; j++) {
		    cetus_tic(&p, i);
		    cetus_toc(&p, i);
	  }
	  while (i-- > 0)
	    cetus_toc(&p, i);
	  prof->overhead = p.event[0].etime/(double)iter;
	  prof->overhead_in = p.event[p.num_events-1].etime/(double)iter;
}

/* prints the profiling result to the specified file stream */
void cetus_print_timers(cetusprofile *prof, FILE * o)
{
	  int i;
	  double sum_measured = 0, sum_adjusted = 0, sum_exclusive = 0;
	  double measured, adjusted, exclusive;
	  cetus_tune_timer(prof);
	  fprintf(o, "\nCETUS_TIMING%32s%12s%12s%12s%12s\n",
	  "NAME", "INVOKED", "MEASURED", "ADJUSTED", "EXCLUSIVE");
	  for (i = 0; i < prof->num_events; i++) {
		    cetusevent *evt = prof->event+i;
		    measured = 1.0e-6 * evt->etime;
		    adjusted = 1.0e-6 * (evt->etime
		        /* overhead from child's timing calls */
		        - prof->overhead * evt->child_count_cumul
		        /* included overhead for the event */
		        - prof->overhead_in * evt->count);
		    exclusive = 1.0e-6 * (evt->etime
		        /* included overhead for the event */
		        - prof->overhead_in*evt->count
		        /* overhead not included in the child's timing calls */
		        - (prof->overhead - prof->overhead_in) * evt->child_count
		        /* elapsed time and overhead included in the child */
		        - evt->child_etime);
		    if (i < prof->num_events-1) {
			      sum_measured += measured;
			      sum_adjusted += adjusted;
			      sum_exclusive += exclusive;
		    }
		    fprintf(o, "CETUS_TIMING%32s%12.2e%12.2f%12.2f%12.2f\n",
		      evt->name, (double)evt->count, measured, adjusted, exclusive);
	  }
	  fprintf(o, "CETUS_TIMING%32s%12.2e%12.2f%12.2f%12.2f\n",
	    "EVENTSUM", 0.0, sum_measured, sum_adjusted, sum_exclusive);
	  fprintf(o, "CETUS_TIMING%32s%12.2e%12.2f%12.2f%12.2f\n",
	    "COVERAGE", 0.0, 100*(sum_measured/measured),
	                       100*(sum_adjusted/adjusted),
	                       100*(sum_exclusive/adjusted));
}

cetusprofile cetus_prof = {0, 0, 0, 0.0, 0.0, 0, 0};
#endif /* CETUS_TIMING */

void init_array(int ni, int nj, int nk, double A[ni][nk], double B[nk][nj], double C[ni][nj])
{
	int i;
	int j;
	#pragma event init_array#0 start
	#pragma loop name init_array#0 
	#pragma cetus private(i, j) 
	#pragma cetus parallel 
	#pragma omp parallel for if((10000<((1L+(3L*ni))+((3L*ni)*nk)))) private(i, j)
	for (i=0; i<ni; i ++ )
	{
		#pragma loop name init_array#0#0 
		#pragma cetus private(j) 
		for (j=0; j<nk; j ++ )
		{
			A[i][j]=(((double)(((i*j)+1)%ni))/ni);
		}
	}
	#pragma event init_array#0 stop
	#pragma event init_array#1 start
	#pragma loop name init_array#1 
	#pragma cetus private(i, j) 
	#pragma cetus parallel 
	#pragma omp parallel for if((10000<((1L+(3L*nk))+((3L*nj)*nk)))) private(i, j)
	for (i=0; i<nk; i ++ )
	{
		#pragma loop name init_array#1#0 
		#pragma cetus private(j) 
		for (j=0; j<nj; j ++ )
		{
			B[i][j]=(((double)(((i*j)+2)%nj))/nj);
		}
	}
	#pragma event init_array#1 stop
	#pragma event init_array#2 start
	#pragma loop name init_array#2 
	#pragma cetus private(i, j) 
	#pragma cetus parallel 
	#pragma omp parallel for if((10000<((1L+(3L*ni))+((3L*ni)*nj)))) private(i, j)
	for (i=0; i<ni; i ++ )
	{
		#pragma loop name init_array#2#0 
		#pragma cetus private(j) 
		for (j=0; j<nj; j ++ )
		{
			C[i][j]=0.0;
		}
	}
	#pragma event init_array#2 stop
	return ;
}

void matrix_multiply(double A[10000][10000], double B[10000][10000], double C[10000][10000])
{
	#pragma experimental section start 
	int i;
	int j;
	int k;
	#ifdef CETUS_TIMING
	char cetus_prof_names[][32] = {
		    "matrix_multiply#0", 
		    "PROGRAM"
	};
	cetus_prof.num_events = 2;
	cetus_init_timers(&cetus_prof, cetus_prof_names);
	cetus_tic(&cetus_prof, 1);
	#endif /* CETUS_TIMING */
	
	#pragma event matrix_multiply#0 start
	#if defined(CETUS_PAPI) && defined(MATRIX_MULTIPLY_0)
	cetus_start_instruments;
	#endif
	
	#if defined(CETUS_TIMING) && defined(MATRIX_MULTIPLY_0)
	cetus_tic(&cetus_prof, 0);
	#endif
	
	#pragma loop name matrix_multiply#0 
	#pragma cetus private(i, j, k) 
	#pragma cetus parallel 
	#pragma omp parallel for private(i, j, k)
	for (i=0; i<10000; i ++ )
	{
		#pragma loop name matrix_multiply#0#0 
		#pragma cetus private(j, k) 
		for (j=0; j<10000; j ++ )
		{
			#pragma loop name matrix_multiply#0#0#0 
			#pragma cetus private(k) 
			/* #pragma cetus reduction(+: C[i][j])  */
			for (k=0; k<10000; k ++ )
			{
				C[i][j]+=(A[i][k]*B[k][j]);
			}
		}
	}
	#pragma event matrix_multiply#0 stop
	#if defined(CETUS_PAPI) && defined(MATRIX_MULTIPLY_0)
	cetus_stop_instruments;
	cetus_print_instruments;
	#endif
	
	#if defined(CETUS_TIMING) && defined(MATRIX_MULTIPLY_0)
	cetus_toc(&cetus_prof, 0);
	#endif
	
	#if defined(CETUS_CHECKPOINT) && defined(MATRIX_MULTIPLY_0)
	write_var_to_file("matrix_multiply#0_i", &i, sizeof(int), 1);
	write_var_to_file("matrix_multiply#0_j", &j, sizeof(int), 1);
	write_var_to_file("matrix_multiply#0_k", &k, sizeof(int), 1);
	write_var_to_file("matrix_multiply#0_C", &C, sizeof(double), 10000);
	
	#endif// CETUS_CHECKPOINT - MATRIX_MULTIPLY_0
	
	#pragma experimental section stop 
	#if defined(CETUS_PAPI)
	cetus_finish_profile;
	#endif
	
	#ifdef CETUS_TIMING
	cetus_toc(&cetus_prof, 1);
	cetus_print_timers(&cetus_prof, stderr);
	#endif
	
	
	return ;
}

void print_array(int ni, int nj, double C[ni][nj])
{
	int i;
	int j;
	#pragma event print_array#0 start
	#pragma loop name print_array#0 
	#pragma cetus private(i, j) 
	for (i=0; i<ni; i ++ )
	{
		#pragma loop name print_array#0#0 
		#pragma cetus private(j) 
		for (j=0; j<nj; j ++ )
		{
			printf("%0.2lf ", C[i][j]);
		}
		printf("\n");
	}
	#pragma event print_array#0 stop
	return ;
}

int main()
{
	double (* A)[10000] = malloc((10000*10000)*sizeof (double));
	double (* B)[10000] = malloc((10000*10000)*sizeof (double));
	double (* C)[10000] = malloc((10000*10000)*sizeof (double));
	int _ret_val_0;
	init_array(10000, 10000, 10000, A, B, C);
	matrix_multiply(A, B, C);
	print_array(10000, 10000, C);
	free(A);
	free(B);
	free(C);
	_ret_val_0=0;
	return _ret_val_0;
}
