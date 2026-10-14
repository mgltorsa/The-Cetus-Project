/*
 * Minimal PAPI stub for testing Cetus-instrumented code on machines
 * without libpapi. Header-only: compile with
 * -I resources/paw_tests/papi_stub and WITHOUT -lpapi.
 *
 * The instrumentation keeps one free-running event set per thread and
 * samples it with PAPI_read. The stub models every event as the calling
 * thread's CPU time in nanoseconds, so a counter grows only while that
 * thread works and team sums behave like real counters.
 */
#ifndef CETUS_PAPI_STUB_H
#define CETUS_PAPI_STUB_H

#include <time.h>

#define PAPI_OK 0
#define PAPI_NULL (-1)
#define PAPI_ENOINIT (-16)
#define PAPI_VER_CURRENT 7000000
#define CETUS_PAPI_STUB_MAX_EVENTS 16

static __thread int cetus_papi_stub_nevents = 0;
static __thread long long cetus_papi_stub_t0 = 0;

static long long cetus_papi_stub_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return (long long) ts.tv_sec * 1000000000LL + (long long) ts.tv_nsec;
}

static int PAPI_library_init(int version) {
    (void)version;
    return PAPI_VER_CURRENT;
}

static int PAPI_create_eventset(int *eventset) {
    *eventset = 1;
    cetus_papi_stub_nevents = 0;
    return PAPI_OK;
}

static int PAPI_add_named_event(int eventset, const char *name) {
    (void)eventset;
    (void)name;
    if (cetus_papi_stub_nevents < CETUS_PAPI_STUB_MAX_EVENTS) cetus_papi_stub_nevents++;
    return PAPI_OK;
}

static int PAPI_thread_init(unsigned long (*id_fn)(void)) {
    (void)id_fn;
    return PAPI_OK;
}

static int PAPI_register_thread(void) {
    return PAPI_OK;
}

static int PAPI_unregister_thread(void) {
    return PAPI_OK;
}

static int PAPI_start(int eventset) {
    (void)eventset;
    cetus_papi_stub_t0 = cetus_papi_stub_now();
    return PAPI_OK;
}

static int PAPI_read(int eventset, long long *values) {
    int k;
    long long dt = cetus_papi_stub_now() - cetus_papi_stub_t0;
    (void)eventset;
    for (k = 0; k < cetus_papi_stub_nevents; k++) values[k] = dt;
    return PAPI_OK;
}

static int PAPI_stop(int eventset, long long *values) {
    return PAPI_read(eventset, values);
}

#endif /* CETUS_PAPI_STUB_H */
