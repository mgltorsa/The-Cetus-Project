# PAW readable tiled index & tile-size names

**Date:** 2026-08-12  
**Status:** Approved for implementation planning  
**Scope:** Parallel-Aware Tiling (`src/cetus/transforms/paw_tiling/`) code generation naming

## Problem

Generated PAW code uses opaque names:

- Cross-strip (tile) indexes: `i_cetus_cross`, `j_cetus_cross`, …
- Tile-size variables: `cetus_tile_main_1_k`, …

These are hard to read in experiments, thesis examples, and debugging.

## Goals

- Cross-strip indexes: `T` + original → `Ti`, `Tj`, `Tk`, …
- Tile-size variables: `S` + original → `Si`, `Sj`, `Sk`, …
- On name collision in the declaration scope: append `_1`, `_2`, … until unique
- Preserve correctness of strip-mining, DV rewrite, parallel-loop balancing, and e2e tests

## Non-goals

- Changing strip-mining / lemma / profitability algorithms
- Renaming in-strip (original) indexes (`i`, `j`, `k` stay)
- Structural (pragma-based) cross-loop detection beyond what naming helpers require

## Approach

**Prefix helpers + name-based matching** (Approach 1), extended to tile sizes.

Centralize naming in `Tiler` so call sites do not concatenate prefixes ad hoc.

## Design

### Naming API (`Tiler`)

Remove:

- `CROSS_TILE_SUFFIX = "_cetus_cross"`
- `IN_TILE_PREFIX = "cetus_tile_"` (as the public naming contract)

Add:

| Constant / API | Behavior |
| --- | --- |
| `CROSS_TILE_PREFIX = "T"` | Cross-strip index prefix |
| `TILE_SIZE_PREFIX = "S"` | Tile-size variable prefix |
| `crossIndexName(SymbolTable, String original)` | First free of `T`+original, then `T`+original+`_1`, `_2`, …; declare or return existing identifier as today |
| `tileSizeName(SymbolTable, String original)` | Same pattern with `S` |
| `isCrossIndexFor(String crossName, String original)` | True if `crossName` is `T`+original or `T`+original+`_<digits>` |
| `isCrossStripLoop(ForLoop)` | Uses the cross-name convention (no `_cetus_cross` suffix) |

Collision uniqueness is scoped to the `SymbolTable` used for declarations (typically the enclosing procedure), same as today.

### Call sites

**`Tiler.stripmining`**

- Declare the cross index only via `crossIndexName`.
- Remove the erroneous doubled-name lookup (`index + index`, e.g. searching for `ii` before falling back).
- When a non-literal / non-IDExpression tile size must be materialized as a variable, use `tileSizeName` instead of `IN_TILE_PREFIX + index`.

**`CandidateBrowser.browse`**

- Declare symbolic tile sizes via `tileSizeName(symtab, indexName)`.
- Stop embedding `nestTag` in the variable *name*. Nest isolation comes from collision fallback (`Si` then `Si_1` for a later nest in the same scope).
- `nestTag` may remain for debug/logging or be dropped from the browse signature if unused.

**`ParallelAwareTiling` Phase 3 (balanced tile sizes)**

- Replace `parallelName.equals(indexName + CROSS_TILE_SUFFIX)` with `Tiler.isCrossIndexFor(parallelName, indexName)` so `Ti` and `Ti_1` both match original `i`.

### Example emission

```c
int Ti, Tj, Si = 32, Sj = 32;
for (Ti = 0; Ti < N; Ti += Si)
  for (Tj = 0; Tj < N; Tj += Sj)
    for (i = Ti; i < MIN(N, Ti + Si); i++)
      for (j = Tj; j < MIN(N, Tj + Sj); j++)
        ...
```

### Tests, docs, examples

Update hard-coded old names in:

- `src/cetus/unittest/paw_tiling/DirectionVectorLemmasTest.java`
- `run-paw-tests.sh` (grep for readable names instead of `_cetus_cross` / `cetus_tile_`)
- `resources/examples/PawTiling_tiled.c`, `PawTiling_tiled_papi.c`
- `AGENTS.md`, `Tiling.md`, `openspec/specs/paw-tiling/spec.md` (and task notes that mention the old pattern)

### Success criteria

- `./run-paw-tests.sh` passes
- Typical gemm-like output uses `Ti`/`Tj`/`Tk` and `Si`/`Sj`/`Sk` (with `_N` only on collision)
- Parallel balancing still applies to the outermost parallel *cross-strip* loop

## Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Short names collide with user locals (`Si`, `Ti`) | `_N` fallback |
| Multiple nests in one function share preferred names | Same fallback; first nest gets `Si`, later gets `Si_1` |
| Brittle e2e greps | Prefer patterns that match `Ti`/`Si` style, not a single opaque substring |

## Out of scope follow-ups

- Thesis PDF / published prose updates (unless requested separately)
- Regenerating experiment trees under `tiling-experiments-2026` (re-run Cetus after this lands)
