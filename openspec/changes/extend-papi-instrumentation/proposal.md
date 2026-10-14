# Change: extend-papi-instrumentation (R1)

**Status:** Implemented 2026-08-12

Extends `-papi_instrument` in `PAPIInstrumentation.java`:

- Per-region `TIME_NS` (`clock_gettime`) + PAPI
- Program wrap: `TIME_NS` + **sum** of region counters (no nested PAPI)
- Region selection: `c_paw_tiling` else `c_paw_measure` / omp parallel for
- Stderr contract documented in `Tiling.md` / `AGENTS.md`
- `./run-paw-tests.sh` updated (41 assertions)

Related experiment layout: `tiling-experiments-2026/docs/superpowers/specs/2026-08-12-local-experiments-papi-design.md`
