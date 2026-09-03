/*
 * Minimal PAPI stub for testing Cetus-instrumented code on machines
 * without libpapi. Counters return deterministic fake values so the
 * [cetus-papi] report lines can be asserted. Header-only: compile with
 * -I resources/paw_tests/papi_stub and WITHOUT -lpapi.
 */
#ifndef CETUS_PAPI_STUB_H
#define CETUS_PAPI_STUB_H

#define PAPI_OK 0
#define PAPI_NULL (-1)
#define PAPI_VER_CURRENT 7000000

static int cetus_papi_stub_nevents = 0;

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
    cetus_papi_stub_nevents++;
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
    return PAPI_OK;
}

static int PAPI_stop(int eventset, long long *values) {
    (void)eventset;
    values[0] = 42;
    values[1] = 43;
    return PAPI_OK;
}

#endif /* CETUS_PAPI_STUB_H */
