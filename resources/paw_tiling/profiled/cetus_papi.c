#ifdef CETUS_TIMING
typedef struct cetusprofile cetusprofile;
extern cetusprofile cetus_prof;
void cetus_tic(cetusprofile *, int);
void cetus_toc(cetusprofile *, int);
#endif /* CETUS_TIMING */


#include "cetus_papi.h"
int evid; //global evid
int alreadyRun = 0; //global alreadyRun
#include <stdio.h>
#include <stdlib.h>
#ifndef HAS_SYS_TIME_H
#include <sys/time.h>
#endif

#ifdef _OPENMP
# include <omp.h>
#endif
# include <papi.h>
# define MAX_NB_PAPI_COUNTERS 96
  char* _cetus_papi_eventlist[] = {
#include "papi_counters.list"
    NULL
  };

#ifndef CETUS_THREAD_MONITOR
# define CETUS_THREAD_MONITOR 0
#endif
  int cetus_papi_counters_threadid = CETUS_THREAD_MONITOR;
  int cetus_papi_eventset;
  unsigned int cetus_papi_eventlist[MAX_NB_PAPI_COUNTERS];
  long_long cetus_papi_values[MAX_NB_PAPI_COUNTERS];

void cetus_prepare_papi_instruments()
{
   #ifndef NO_FLUSH_CACHE
       cetus_flush_cache ();
   #endif
}

#ifndef CACHE_SIZE_KB
    # define CACHE_SIZE_KB 32770
#endif


static void test_fail(char *file, int line, char *call, int retval)
{
  char buf[128];
  memset(buf, '\0', sizeof(buf));
  if (retval != 0)
    fprintf (stdout,"%-40s FAILED\nLine # %d\n", file, line);
  else
    {
      fprintf (stdout,"%-40s SKIPPED\n", file);
      fprintf (stdout,"Line # %d\n", line);
    }
  if (retval == PAPI_ESYS)
    {
      sprintf (buf, "System error in %s", call);
      perror (buf);
    }
  else if (retval > 0)
    fprintf (stdout,"Error: %s\n", call);
  else if (retval == 0)
    fprintf (stdout,"Error: %s\n", call);
  else
    {
      char* errstring = PAPI_strerror(retval);
      // PAPI 5.4.3 has changed the API for PAPI_perror.
      //#if defined (PAPI_VERSION) && ((PAPI_VERSION_MAJOR(PAPI_VERSION) == 5 && PAPI_VERSION_MINOR(PAPI_VERSION) >= 4) || PAPI_VERSION_MAJOR(PAPI_VERSION) > 5)
      PAPI_perror(errstring);
      //##fprintf (stdout, "Error in %s: %s\n", call, PAPI_strerror(retval));
      //#else
      //PAPI_perror (retval, errstring, PAPI_MAX_STR_LEN);
      fprintf (stdout,"Error in %s: %s\n", call, errstring);
      //#endif
    }
  fprintf (stdout,"\n");
  if (PAPI_is_initialized ())
    PAPI_shutdown ();
  exit (1);
}


void cetus_flush_cache()
{
    int cs = CACHE_SIZE_KB * 1024 / sizeof(double);
    double* flush = (double*) calloc (cs, sizeof(double));
    int i;
    double tmp = 0.0;
    #ifdef _OPENMP
        #pragma omp parallel for reduction(+:tmp) private(i)
    #endif
    for (i = 0; i < cs; i++)
        tmp += flush[i];
    assert (tmp <= 10.0);
    free (flush);
}


void cetus_papi_init()
{
# ifdef _OPENMP
#pragma omp parallel
  {
#pragma omp master
    {
      if (omp_get_max_threads () < cetus_papi_counters_threadid)
    cetus_papi_counters_threadid = omp_get_max_threads () - 1;
    }
#pragma omp barrier
    if (omp_get_thread_num () == cetus_papi_counters_threadid)
      {
# endif
    int retval;
    cetus_papi_eventset = PAPI_NULL;
    #ifdef VERBOSE
    printf("VER %d\n",PAPI_VER_CURRENT);
    printf("VER LIB INIT %d\n",PAPI_library_init (PAPI_VER_CURRENT));
    #endif
    if ((retval = PAPI_library_init (PAPI_VER_CURRENT)) != PAPI_VER_CURRENT)
      test_fail (__FILE__, __LINE__, "PAPI_library_init", retval);
    if ((retval = PAPI_create_eventset (&cetus_papi_eventset)) != PAPI_OK)
      test_fail (__FILE__, __LINE__, "PAPI_create_eventset", retval);
    int k;
    for (k = 0; _cetus_papi_eventlist[k]; ++k)
      {
        if ((retval = PAPI_event_name_to_code (_cetus_papi_eventlist[k], &(cetus_papi_eventlist[k]))) != PAPI_OK)
          test_fail (__FILE__, __LINE__, "PAPI_event_name_to_code", retval);
      }
    cetus_papi_eventlist[k] = 0;
# ifdef _OPENMP
      }
  }
#pragma omp barrier
# endif
}


void cetus_papi_close()
{
# ifdef _OPENMP
#pragma omp parallel
  {
    if (omp_get_thread_num () == cetus_papi_counters_threadid)
      {
# endif
    int retval;
    if ((retval = PAPI_destroy_eventset (&cetus_papi_eventset))
        != PAPI_OK)
      test_fail (__FILE__, __LINE__, "PAPI_destroy_eventset", retval);
    if (PAPI_is_initialized ())
      PAPI_shutdown ();
# ifdef _OPENMP
      }
  }
#pragma omp barrier
# endif
}


int cetus_papi_start_counter(int evid)
{
# ifndef NO_FLUSH_CACHE
        cetus_flush_cache();
    # endif
    # ifdef _OPENMP
    # pragma omp parallel
    {
        if (omp_get_thread_num () == cetus_papi_counters_threadid)
        {
    # endif
        int retval = 1;
        char descr[PAPI_MAX_STR_LEN];
        PAPI_event_info_t evinfo;
        PAPI_event_code_to_name (cetus_papi_eventlist[evid], descr);
        if (PAPI_add_event (cetus_papi_eventset,
                    cetus_papi_eventlist[evid]) != PAPI_OK)
        test_fail (__FILE__, __LINE__, "PAPI_add_event", 1);
        if (PAPI_get_event_info (cetus_papi_eventlist[evid], &evinfo)
            != PAPI_OK)
        test_fail (__FILE__, __LINE__, "PAPI_get_event_info", retval);
        if ((retval = PAPI_start (cetus_papi_eventset)) != PAPI_OK)
        test_fail (__FILE__, __LINE__, "PAPI_start", retval);
    # ifdef _OPENMP
        }
    }
    #pragma omp barrier
    # endif
    return 0;
}

void cetus_papi_stop_counter(int evid)
{
# ifdef _OPENMP
# pragma omp parallel
  {
    if (omp_get_thread_num () == cetus_papi_counters_threadid)
      {
# endif
	int retval;
	long_long values[1];
	values[0] = 0;
	if ((retval = PAPI_read (cetus_papi_eventset, &values[0]))
	    != PAPI_OK)
	  test_fail (__FILE__, __LINE__, "PAPI_read", retval);

	if ((retval = PAPI_stop (cetus_papi_eventset, NULL)) != PAPI_OK)
	  test_fail (__FILE__, __LINE__, "PAPI_stop", retval);

	cetus_papi_values[evid] = values[0];

	if ((retval = PAPI_remove_event
	     (cetus_papi_eventset,
	      cetus_papi_eventlist[evid])) != PAPI_OK)
	  test_fail (__FILE__, __LINE__, "PAPI_remove_event", retval);
# ifdef _OPENMP
      }
  }
#pragma omp barrier
# endif
}

void cetus_papi_print()
{
# ifdef _OPENMP
# pragma omp parallel
  {
    if (omp_get_thread_num() == cetus_papi_counters_threadid)
      {
#ifdef VERBOSE
  printf ("On thread %d:\n", cetus_papi_counters_threadid);
#endif
#endif
   char descr[PAPI_MAX_STR_LEN];
	for (evid = 0; cetus_papi_eventlist[evid] != 0; ++evid)
	  {
      PAPI_event_code_to_name(cetus_papi_eventlist[evid], descr);
	   printf ("%s:%llu\n", descr, cetus_papi_values[evid]);
	  }
	printf ("\n");
# ifdef _OPENMP
      }
  }
#pragma omp barrier
# endif
}



