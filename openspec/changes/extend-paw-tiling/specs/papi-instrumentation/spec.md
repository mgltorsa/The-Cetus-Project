# papi-instrumentation (delta)

New capability: a code-generation pass that instruments PAW-optimized code
with PAPI hardware counters, matching the thesis evaluation methodology
(Chapter 5: `PAPI_L3_DCM` L3 data-cache misses and `PAPI_TOT_CYC` total
cycles, 4 threads, mean of 3 trials orchestrated externally by PCAOT).

## ADDED Requirement: PAPI instrumentation pass

A pass registered as `-papi_instrument[=events]` SHALL run **after** PAW
tiling (and after ompGen when enabled) and instrument each region that PAW
transformed (identified by the `c_paw_tiling` pragma/annotation emitted by
the tiling pass; when no PAW region exists, optionally the outermost loops of
experimental sections):

- Insert `#include <papi.h>` in the translation unit (guarded, once).
- Initialize the PAPI library once per program (`PAPI_library_init`),
  create an event set with `PAPI_L3_DCM` and `PAPI_TOT_CYC` (default;
  `=events` allows a comma-separated override, e.g.
  `-papi_instrument=PAPI_L2_DCM,PAPI_TOT_CYC`).
- Surround each instrumented region with `PAPI_start(...)` /
  `PAPI_stop(...)` and emit the counter values after the region
  (`fprintf(stderr, "[papi] <region-name> PAPI_L3_DCM=%lld PAPI_TOT_CYC=%lld\n", ...)`)
  so the PCAOT pipeline can parse them.
- Declare the long-long counter buffers in the innermost enclosing scope
  without shadowing existing symbols.
- Emitted code MUST compile with `gcc ... -lpapi` and MUST NOT be inserted
  inside a parallel region (measurement wraps the whole parallel loop, not
  each thread's body).

#### Scenario: tiled gemm gets counters

- **GIVEN** gemm compiled with `cetus -paw_tiling -papi_instrument gemm.c`
- **WHEN** the emitted C is compiled with `-fopenmp -lpapi` and run
- **THEN** stderr contains one `[papi]` line per PAW region with
  `PAPI_L3_DCM` and `PAPI_TOT_CYC` values, and program output is otherwise
  unchanged

#### Scenario: no PAW region

- **GIVEN** a file where PAW tiled nothing
- **WHEN** `-papi_instrument` runs
- **THEN** no PAPI code is inserted (or, with experimental sections present,
  only those sections are instrumented) and the program still compiles

## ADDED Requirement: Failure-tolerant runtime behavior

Generated init code SHALL check PAPI return codes; on failure it SHALL print
a warning to stderr and continue executing the program un-instrumented
(counters reported as -1), never aborting the computation.
