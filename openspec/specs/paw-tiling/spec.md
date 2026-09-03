# paw-tiling (current state)

This documents what the PAW tiling pass does **today** (baseline before the
`extend-paw-tiling` change). It is intentionally a description of current
behavior, including known gaps; the target behavior lives in
`openspec/changes/extend-paw-tiling/specs/`.

## Requirement: Basic tiling of perfect canonical nests

The pass (`-paw_tiling`) SHALL find outermost loops that are perfect nests in
canonical form (via `LoopTools.isPerfectNest` / `isCanonical`), optionally
restricted to experimental sections, run `LoopInterchange` first, and tile
each valid nest.

#### Scenario: matmul nest is tiled

- **GIVEN** a perfectly nested canonical `(i,j,k)` matrix-multiplication loop
- **WHEN** `cetus -paw_tiling gemm.c` runs
- **THEN** the output contains cross-strip loops (index suffix
  `_cetus_cross`) stepping by the tile size and in-strip loops bounded by
  `MIN(cross+T, N)`

## Requirement: Tile-size selection algorithms (current forms)

The pass SHALL select tile sizes with one of three algorithms chosen by
`-selection={LRW,NT}` or `-tileSizes=a,b,...` (FIXED):

- `FixedSizesAlgo`: user-provided sizes, last value repeated.
- `NTSelectionAlgo`: per-loop capacity estimate `cache / dataLoadedPerIter`,
  aligned to cache line. (Does **not** implement the thesis
  `t_i·t_j + Cores×Refs ≤ CacheSize` model.)
- `LRWSelectionAlgo`: capacity square-root bound
  `b ≤ sqrt(cache/(refs·elemSize))` aligned to cache line. (Does **not**
  implement Lam–Rothberg–Wolf FindB; no self-interference modeling.)

## Requirement: Dependence-vector rewrite after strip-mining

`Tiler.calculateAfterTilingDVs` SHALL rewrite each original direction vector
for the strip-mined nest: `=` → `(=,=)`; `<` → `(=,<)` and `(<,*)`;
`>` → `(=,>)` and `(>,*)` (cross-strip entry listed first). Vectors made of
all-`nil` entries are dropped.

## Requirement: Parallel loop detection (current, conservative)

`TiledLoop.calculateOutermostParallelLoop` SHALL mark as parallelizable the
outermost loop whose direction is `=` in **every** dependence vector. (This
is more conservative than Lemma "Parallelism": it ignores dependences already
carried by an enclosing loop.)

## Requirement: Runtime profitability guard

When `-tile-profitability=1`, the pass SHALL wrap the tiled nest in
`if (iterations <= MAX && cache > dataSize) untiled else tiled`, folding the
guard statically when all quantities are integer literals.

## Requirement: Post-pass re-analysis

After tiling (parallel mode, `-paw_tiling=1`), the pass SHALL re-run
`ArrayPrivatization`, `DDTDriver`, and `Reduction` so privatization/reduction
attributes exist for the restructured nests, and sets `profitable-omp=0`.

## Known gaps (closed by change `extend-paw-tiling`)

- No reuse analysis / no reuse-ordered candidate browsing; loops are tiled in
  nest order for every index the size-algorithm returns.
- No per-candidate legality pruning loop (lexicographic-negativity test only
  happens implicitly in `TiledLoop.setInternalDependenceVectors`).
- No tiling depth `d` option.
- Balanced tile size `S = I/(ceil(I/(P·T))·P)` is not computed;
  `getBalancedTile` only cache-line-aligns (and rounds the wrong way: adds
  `mod` instead of subtracting).
- Parallel loop is not annotated (`tagParallelLoops` is dead code); the pass
  relies on a later `LoopParallelizationPass`/`ompGen` run.
- NT and LRW do not match the thesis/paper models; no shared-cache
  `Cores×Refs` correction; no FindB.
- No PAPI instrumentation pass.
- Profitability guard is runtime-first; symbolic range analysis is not used
  to fold it at compile time.
