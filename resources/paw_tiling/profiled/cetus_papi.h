#ifdef CETUS_TIMING
typedef struct cetusprofile cetusprofile;
extern cetusprofile cetus_prof;
void cetus_tic(cetusprofile *, int);
void cetus_toc(cetusprofile *, int);
#endif /* CETUS_TIMING */


#ifndef CETUS_PAPI_H
#define CETUS_PAPI_H

//FOR PAPI_VERSION= 5.3
#include <stdio.h>
#include <stdlib.h>
#include <omp.h>
#include <time.h>
#include <sys/time.h>
#include <string.h>
#include <unistd.h>
#include <assert.h>
#include <papi.h>
extern int cetus_papi_start_counter(int evid);
extern void cetus_papi_stop_counter(int evid);
extern void cetus_papi_init();
extern void cetus_papi_close();
extern void cetus_papi_print();
extern void cetus_prepare_papi_instruments();
extern unsigned int cetus_papi_eventlist[];
#  undef cetus_start_instruments
#  undef cetus_stop_instruments
#  undef cetus_print_instruments


extern int evid;


extern int alreadyRun;


#define cetus_set_papi_thread_report(x)	\
cetus_papi_counters_threadid = x;
#define cetus_start_instruments				\
cetus_prepare_papi_instruments();				\
cetus_papi_init();					\
for (evid = 0; cetus_papi_eventlist[evid] != 0; evid++)	\
    {								\
      if(!alreadyRun){                      \
         if (cetus_papi_start_counter(evid))			\
           continue;						\
      }  \


#  define cetus_stop_instruments		\
    if(alreadyRun) {                       \
       break;                       \
    }                     \
    cetus_papi_stop_counter(evid);	\
    }						\
cetus_papi_close();			\


#  define cetus_print_instruments      \
   if(!alreadyRun) {                       \
       cetus_papi_print();           \
   }                     \


#  define cetus_finish_profile \
   alreadyRun = 1;  \


#endif /* CETUS_PAPI_H */



