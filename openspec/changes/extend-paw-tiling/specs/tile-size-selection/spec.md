# tile-size-selection (delta)

Target behavior of the three tile-size methods (thesis §3.3, Table
"tile-size-methods"; NT model §3.3.2; LRW/FindB Algorithm 3.2 and
§2.3.2). All methods return **raw** sizes `T`; parallel balancing happens
afterwards in the PAW pass (Phase 3) and is not part of these algorithms.

Common units contract: `CacheSize` is the **shared L3 capacity in array
elements** (bytes / element size; element size from the accessed array's
type, 8 bytes for double); `CacheAlignment` is the cache line in elements
(64/8 = 8 for doubles); alignment always rounds **down** with a floor of one
cache line.

## MODIFIED Requirement: Fixed (Manual) sizes

`-tileSizes=a,b,...` SHALL assign sizes to the strip-mined loops in browse
order, repeating the last value. Values ≤ 1 mean "do not tile this loop".
(Behavior essentially as today; assignment order changes from nest order to
browse order.)

## MODIFIED Requirement: NT (Naive Tile) — shared-cache capacity model

`NTSelectionAlgo` SHALL implement the thesis model:

- Identify the reference with the most temporal reuse w.r.t. the parallel
  candidate loop (the resident working set — e.g. `b[k][j]` in matmul).
- Constraint: `t_i · t_j + Cores × Refs ≤ CacheSize` (all in elements),
  where `Refs` = number of distinct array references in the nest and
  `CacheSize` is the **total** shared capacity (a single resident copy
  serves all cores; the `Cores × Refs` term reserves room for the per-thread
  streams).
- With `t_i` fixed from the problem (rows of the resident sub-matrix, i.e.
  trip count of the corresponding loop): 
  `t_j = floor((CacheSize − Cores×Refs) / t_i)`, then align down to the
  cache line.
- Square-tile variant for `B × B` tiles: `B = floor(sqrt(t_j))`, aligned
  down.
- Symbolic trip counts SHALL be kept symbolic (Cetus `Symbolic` arithmetic)
  rather than replaced by magic defaults.

#### Scenario: thesis numeric example

- **GIVEN** 25 MiB L3 (`CacheSize = 3,276,800` doubles), `Cores = 4`,
  `Refs = 3`, `t_i = 1000`
- **WHEN** NT computes the tile
- **THEN** `t_j = floor((3,276,800 − 12)/1000) = 3276 → 3272` after 8-element
  alignment; square variant `B = floor(sqrt(3272)) = 57 → 56`

## MODIFIED Requirement: LRW — FindB critical blocking factor

`LRWSelectionAlgo` SHALL implement Lam–Rothberg–Wolf **FindB** (Algorithm
3.2) instead of the current capacity square-root:

```
maxWidth ← min(N, C); addr ← 0
loop:
  addr ← addr + C                     // next self-conflicting address
  di ← addr div N
  dj ← addr mod N
  if dj > N/2: di ← di + 1; dj ← N − dj     // nearest-row adjustment
  if di > min(maxWidth, dj): return min(maxWidth, di)   // B0
  else: maxWidth ← min(maxWidth, dj)
```

> **Fidelity note (RESOLVED):** an earlier thesis transcription of
> Algorithm 3.2 wrote the column distance as `dj = |addr mod N − N|`,
> dropping the nearest-row branch and mis-handling conflicts at exact row
> multiples (`addr mod N = 0` yields `dj = N` instead of `0`; for
> `N=1024, C=4096` it returns `B0 = 1024` although rows collide every 4
> rows — an UNSAFE tile). On 2026-08-11 the thesis
> (`figures/lrw-algorithm.tex`, FindB paragraph in `chapters/chap2.tex`)
> was corrected to the original Lam–Rothberg–Wolf form shown above, which
> is what `LRWSelectionAlgo.findB` implements, validated against a
> brute-force self-interference checker.

with `N` = array leading dimension (elements) of the resident reference and
`C` = cache capacity in elements. The returned `B0` is used as a square
blocking factor, aligned down to the cache line. On set-associative caches
`B0` is used unchanged (safe, conservative — thesis §2.3.2; the
associativity heuristic and copying are explicit non-goals). When `N` is
not statically known, the algorithm SHALL fall back to the NT square-tile
size (documented, deterministic fallback).

#### Scenario: FindB on a small direct-mapped cache

- **GIVEN** `N = 6`, `C = 16` (the thesis Figure "lrw-conflict"
  configuration: rows start at 0, 6, 12, 2, … mod 16)
- **WHEN** FindB runs
- **THEN** it terminates in O(N/sqrt(C)) steps and returns the largest `B0`
  such that a `B0 × B0` block of the `N`-wide array has no self-interference
  (validated in a unit test against a brute-force conflict checker)

#### Scenario: power-of-two leading dimension

- **GIVEN** `N = 1024`, `C = 4096`
- **WHEN** FindB runs
- **THEN** `B0` is small (severe self-interference), strictly smaller than
  the capacity bound `floor(sqrt(C))` — demonstrating conflict awareness

## ADDED Requirement: Selection-algorithm inputs from configuration

All algorithms SHALL take cache size (`-cacheSize`, KiB), cache line
(`-cacheLine`, bytes), and core count (`-cores`) from `TilingParams`; the
hardcoded `cacheSizeInKiB = 1024` in `TilingParams.createSelectionAlgo`
SHALL be removed in favor of the configured values. Element size SHALL be
derived from the array access type, not assumed.
