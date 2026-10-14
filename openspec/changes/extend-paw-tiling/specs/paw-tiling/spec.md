# paw-tiling (delta)

Target behavior of the PAW pass after this change. Source of truth:
thesis Algorithm 3.1 (`Masters_Thesis/figures/heuristic-tiling.tex`),
Chapter 3 (`chapters/chap3.tex`), lemmas in Chapter 2 §2.2.3
(`chapters/chap2.tex`).

## ADDED Requirement: Direction-vector lemmas engine

A dedicated legality module SHALL implement the four lemmas as pure,
unit-testable operations on lists of direction vectors:

- **Lemma 4 (Strip-mining)**: entry `[d]` of the strip-mined loop is replaced
  by the pair `(cross, in)`: `= → {(=,=)}`; `< → {(=,<), (<,*)}`;
  `> → {(=,>), (>,*)}`; `* → {(=,*), (*,*)}` — one source vector may yield
  several rewritten vectors; all other entries are copied unchanged.
- **Lemma 1 (Reordering)**: permuting loops permutes every vector's entries
  identically.
- **Lemma 2 (Permutability)**: a candidate is legal iff no rewritten vector
  is lexicographically negative, i.e. every vector's leftmost non-`=`
  (treating `*` as possibly-`>`, hence NOT legal-making) entry is `<`.
- **Lemma 3 (Parallelism)**: loop `p` is parallel iff for every vector,
  either the direction at `p` is `=`, or an enclosing (outer) loop already
  carries that dependence (holds the vector's leftmost `<`).

#### Scenario: strip-mining splits a `<` entry

- **GIVEN** a 1-D loop with a single vector `(<)`
- **WHEN** the loop is strip-mined
- **THEN** the rewritten set is exactly `{(=,<), (<,*)}` over
  (cross-strip, in-strip)

#### Scenario: hoisting past a `>` entry is rejected

- **GIVEN** a 2-loop nest with vector `(<,>)`
- **WHEN** the inner loop is strip-mined and its tile loop hoisted outermost
- **THEN** some rewritten vector has leftmost non-`=` entry `>` and the
  candidate is discarded (thesis Figure "dv-rewrite")

#### Scenario: inner loop parallel because outer carries the dependence

- **GIVEN** vectors `{(<,<)}` on nest `(i,j)`
- **WHEN** parallelism is computed
- **THEN** `i` is serial (carries it) and `j` is parallel by Lemma 3
  (the current all-`=` rule would wrongly mark `j` serial)

## ADDED Requirement: Phase 1 — reuse-ordered loop ranking

The pass SHALL compute a temporal-reuse score per loop of the nest and sort
loops by decreasing score to obtain the browse order. A loop carries
temporal reuse for a reference when the loop's index appears in none of that
reference's subscripts (same elements re-touched every iteration,
Kennedy–McKinley). Temporal reuse has priority; spatial reuse (index appears
only in the fastest-varying subscript) MAY be used as tiebreaker. The
implementation SHOULD reuse/refactor the reusability machinery of
`LoopInterchange` if it computes this quantity; otherwise a McKinley-style
scorer local to the PAW package is added. Group reuse (multiple references
to the same array with small constant offsets) MAY be approximated by
counting reference groups once.

#### Scenario: matmul reuse order

- **GIVEN** `d[i][j] += a[i][k] * b[k][j]` over `(i,j,k)`
- **WHEN** reuse scores are computed
- **THEN** `k` (temporal reuse of `d[i][j]`) and `j` (temporal reuse of
  `a[i][k]`) rank ahead of `i` (temporal reuse of `b[k][j]` only), and the
  browse order is `k, j, i` when trip counts are equal (score-equal loops
  keep innermost-first order)

## ADDED Requirement: Phase 2 — reuse-ordered candidate browsing

Starting from `X = L`, `tiled = 0`, the pass SHALL iterate the reuse order
while `tiled < d` (`d` = tiling depth, new option `-tilingLevel`, default =
nest depth): build candidate `V` by strip-mining the next loop **with a
symbolic tile size** and permuting its tile loop to the target outer
position; rewrite `DV` for `V` via Lemmas 4 then 1; if any rewritten vector
is lexicographically negative, discard `V` and continue with the next loop in
reuse order; otherwise commit (`X ← V`, `DV ← DV'`, `tiled++`). No numeric
tile size and no cost function are used during browsing.

#### Scenario: illegal candidate skipped, browsing continues

- **GIVEN** reuse order `(k, j, i)` where tiling `k` first is illegal but
  tiling `j` is legal
- **WHEN** Phase 2 runs with `d = 2`
- **THEN** the `k` candidate is discarded, `j` and then `i` are tiled, and
  the final nest has exactly 2 tile loops

#### Scenario: depth limit respected

- **GIVEN** a depth-3 nest and `-tilingLevel=1`
- **WHEN** Phase 2 runs
- **THEN** exactly one loop (the highest-reuse legal one) is strip-mined

## MODIFIED Requirement: Phase 3 — parallel loop selection and balanced tile

After a version `X` is fixed and raw sizes `T` computed (see
`tile-size-selection` spec), the pass SHALL:

1. Identify `p` = outermost loop of the **tiled** nest that is parallel by
   Lemma 3 over the rewritten vectors (never inherited from the untiled
   nest — Theorem 1).
2. Compute the balanced size for `p`'s tile loop:
   `S = I_p / (ceil(I_p / (P·T)) · P)` where `I_p` is `p`'s trip count and
   `P` the core count; align `S` **down** to the cache line
   (`S -= S mod lineElems`, floor at one line). When `I_p` is symbolic, emit
   `S` as a symbolic expression (integer arithmetic with CEIL of a division:
   `(I + P*T - 1)/(P*T)`), hoisted into a variable initialization before the
   nest.
3. Substitute `S` into `p`'s tile-loop step (and in-strip MIN bound); other
   tile loops keep their raw `T`.
4. Re-run privatization and reduction recognition so the emitted parallel
   annotation carries correct `private`/`reduction` sets.

#### Scenario: thesis worked example

- **GIVEN** `I = 512`, `T = 80`, `P = 4`
- **WHEN** the balanced size is computed
- **THEN** `ceil(512/320) = 2` and `S = 512/(2·4) = 64` (8 tiles, two per
  core)

## MODIFIED Requirement: Phase 4 — emission and static-first profitability

The pass SHALL annotate `p` as parallel (Cetus `parallel` annotation on the
tile loop so `ompGen` emits `#pragma omp parallel for` with correct
private/reduction clauses). Profitability SHALL be decided statically when
possible using symbolic analysis (`RangeAnalysis` / `Symbolic` comparison of
data footprint vs. cache size and trip count vs. parallelization threshold):

- If the compiler can prove the data fits in cache or the iteration count is
  too small → emit the untiled nest only.
- If it can prove the opposite → emit the tiled nest only (no runtime guard).
- Only when symbolic analysis is inconclusive → emit the two guarded
  versions (`if (fits || small) untiled else tiled`), as today.

#### Scenario: compile-time constants fold the guard

- **GIVEN** PolyBench gemm with literal `N` large enough that the footprint
  exceeds `-cacheSize`
- **WHEN** the pass runs with `-tile-profitability=1`
- **THEN** the output contains only the tiled version, no `if` guard

#### Scenario: symbolic bounds produce the two-version guard

- **GIVEN** a kernel whose bounds are function parameters with no known range
- **WHEN** the pass runs with `-tile-profitability=1`
- **THEN** the output contains the guarded two-version code

## MODIFIED Requirement: Pass orchestration

`ParallelAwareTiling.start()` SHALL execute exactly the four phases per
valid nest (perfect + canonical + outermost, honoring experimental
sections): analysis (DVs + reuse order), browsing, tile-size
selection/balancing, emission — replacing the current
tile-everything-then-fix-up flow. `LoopInterchange` MAY still run first to
canonicalize nests (establishing the algorithm's precondition). The pass
SHALL remain idempotent per nest and skip nests where no legal candidate
survives (leaving the original code untouched).
