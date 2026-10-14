# Design: extend-paw-tiling

Maps each phase of thesis Algorithm 3.1 onto concrete Cetus APIs and records
the technical decisions. Read `proposal.md` first; requirements live in
`specs/`. Cetus API facts below were verified against the source
(file:line references are to the current `feature/paw-tile-2025` branch).

## Package layout (target)

```
src/cetus/transforms/paw_tiling/
  ParallelAwareTiling.java        // orchestrator: 4 phases per nest
  Tiler.java                      // strip-mine + permute (mechanics only)
  TiledLoop.java                  // tiled nest + metadata
  TilingParams.java               // options (add tilingLevel; fix cache wiring)
  legality/
    DirectionVectorLemmas.java    // NEW: lemmas 1-4 as pure static functions
  analysis/
    ReuseOrderAnalyzer.java       // NEW: Phase-1 reuse ranking
  browse/
    CandidateBrowser.java         // NEW: Phase-2 reuse-ordered browsing
  tile_size/
    TileSizeSelectionAlgo.java    // unchanged interface + raw-size contract
    FixedSizesAlgo.java
    NTSelectionAlgo.java          // REWRITE: thesis shared-cache model
    LRWSelectionAlgo.java         // REWRITE: FindB
    BalancedTileCalculator.java   // NEW: S = I/(ceil(I/(P*T))*P) + alignment
  profitability/
    StaticProfitability.java      // NEW: RangeAnalysis-based static decision
src/cetus/transforms/PAPIInstrumentation.java   // NEW pass
src/cetus/unittest/paw_tiling/    // NEW: JUnit 4 tests (lib/junit.jar exists)
```

## Key decisions

### D1. Lemmas as a pure, standalone module

`DirectionVectorLemmas` operates on `List<DependenceVector>` plus an ordered
`List<Loop>` (outermost→innermost). Direction constants are the existing
`DependenceVector.nil(-1)/any(0)/less(1)/equal(2)/greater(3)`
(`DependenceVector.java:20-31`).

- `stripMine(dvs, loops, target, crossLoop, inLoop)` — Lemma 4 split
  (`= → {(=,=)}`, `< → {(=,<),(<,*)}`, `> → {(=,>),(>,*)}`,
  `* → {(=,*),(*,*)}`). Refactors/replaces
  `Tiler.calculateAfterTilingDVs` (which already implements the `<`/`=`/`>`
  cases but is entangled with HIR lookups).
- `reorder(dvs, oldOrder, newOrder)` — Lemma 1 entry permutation.
- `isLegal(dvs, loopOrder)` — Lemma 2: every vector's leftmost non-`=`
  entry must be `less`. **`any` is conservative**: a leading `*` (before any
  `<`) makes the vector potentially lexicographically negative → illegal.
  `nil` entries are skipped (unresolved / no info on that loop, matching
  existing `TiledLoop` behavior of dropping all-nil vectors).
- `isParallel(dvs, loopOrder, p)` — Lemma 3: for every vector, direction at
  `p` is `equal`, OR some loop strictly outer than `p` holds the vector's
  leftmost `less` (the dependence is carried outside). A `*` or `<`/`>` at
  `p` not covered by an outer `<` ⇒ serial.
  `outermostParallelLoop(dvs, loopOrder)` returns the first `p` satisfying
  this — replaces `TiledLoop.calculateOutermostParallelLoop`'s
  all-`=` rule, which is wrong for e.g. `{(<,<)}` (inner loop IS parallel).

Rationale: unit-testable without a C parse; both the browser (legality) and
Phase 3 (parallelism) consume the same module — the thesis's "one shared set
of dependence vectors".

### D2. Reuse order: PAW-local scorer, temporal-first

Available infrastructure:

- `LoopInterchange.ReusabilityAnalysis(...)` (public,
  `LoopInterchange.java:388-803`) — McKinley cache-line cost model; returns
  a memory order (lowest cost innermost). Cache line hardcoded to 64 B.
- `ReuseVectorAnalysis` (`analysis/ReuseVectorAnalysis.java`, option
  `-reuse-vector-analysis`, default on) — per-access binary temporal-reuse
  vectors: entry is 1 iff the loop index appears in **none** of the
  reference's subscripts. Exactly the thesis §3.2 Phase-1 definition.

Decision: new `ReuseOrderAnalyzer` in the PAW package that ranks loops by
**decreasing temporal reuse** (priority per thesis; user requirement):

1. Temporal score of loop ℓ = Σ over reference groups whose subscripts do
   not contain ℓ's index of the group's per-iteration data footprint
   (elements touched by one full sweep of the loops inside ℓ; symbolic via
   `Symbolic`, same footprint machinery as `NTSelectionAlgo`). Reference
   grouping: same array + same subscript expressions (uniform small-offset
   groups count once) — reuse the grouping idea of
   `LoopInterchange.RefGroup` (private; reimplement minimally rather than
   widen its visibility).
2. Tie-breaks: (a) McKinley cost from `ReusabilityAnalysis` when available
   (captures spatial reuse — "optimizing both is good"), (b) innermost
   first (matches the thesis matmul sketch ranking `k` before `j` before
   `i`).

Rationale for not using `ReusabilityAnalysis` as the primary: its cost mixes
spatial (line-based) traffic, and its ideal *memory order* answers "which
loop should be innermost", not "which loop's blocking retains the most
re-touched data"; the thesis defines the browse order in terms of temporal
reuse. It stays as tiebreaker so spatial locality still matters.

### D3. Phase 2 browsing with symbolic tile sizes

`CandidateBrowser.browse(nest, dvs, reuseOrder, depth)`:

```
X = nest; DV = dvs; tiled = 0
for l in reuseOrder while tiled < depth:
    V   = clone(X) with l strip-mined; tile loop hoisted outermost of V
          (tile size = symbolic identifier cetus_tile_<idx>, declared but
           uninitialized until Phase 3)
    DV' = DirectionVectorLemmas.stripMine + reorder for V's loop order
    if !DirectionVectorLemmas.isLegal(DV', V.order): discard V; continue
    X = V; DV = DV'; tiled++
return (X, DV, tiledLoops)
```

- Mechanics reuse `Tiler.stripmining/createCrossStripLoop/createInStripLoop`
  (already correct: cross loop steps by `T`, in-strip bounded by
  `MIN(cross+T, ub)`); the cross loop is placed outermost of the current
  `X`, so committed tile loops end up outermost in reuse order — the thesis
  Figure "tiled-versions(c)" shape.
- Tile-size selection moves **after** browsing (current code computes sizes
  first and lets the size map drive which loops get tiled — inverted).
  `FixedSizesAlgo`'s "≤1 means skip" now means the browser skips
  strip-mining that loop.
- Depth option: `-tilingLevel=N` (new, default = nest depth) via
  `TilingParams`.

### D4. Dependence input

Original DVs come from `program.getDDGraph().getDirectionMatrix(nest)`
(`DDGraph.java:556-588`) after `AnalysisPass.run(new DDTDriver(program))` —
current behavior kept. After every strip-mining commit, DVs are *rewritten*
by lemmas, never re-tested on transformed code (thesis §3.4; this is the
whole point of the lemmas engine).

### D5. Tile-size algorithms (Phase 3, raw sizes)

- **NT** (rewrite): shared-L3 element-unit model,
  `t_j = floor((CacheSize − Cores×Refs)/t_i)`; square variant
  `B = floor(sqrt(t_j))`; align down to line. `Refs` = distinct reference
  groups in the nest; `t_i` = trip count of the resident sub-matrix's row
  loop (the loop enclosing the highest-temporal-reuse reference); symbolic
  trip counts stay symbolic.
- **LRW** (rewrite): FindB exactly as thesis Algorithm 3.2 over `N` =
  leading dimension (elements) of the resident reference, `C` = capacity in
  elements. Static-`N` requirement; fallback = NT square tile when `N`
  unknown. B0 used unchanged on associative caches (documented
  conservatism). Element size from `ArrayUtils.getTypeSizeInBits(access)`,
  not assumed 8 B.
- **BalancedTileCalculator** (new, used by the pass — not by the
  algorithms): `S = I / (ceil(I/(P·T))·P)`; integer fast-path when `I`
  literal; symbolic path emits
  `I / (((I + P*T - 1)/(P*T)) * P)` with `Symbolic` and hoists it into the
  tile-size variable's initializer. Align **down**:
  `S -= S mod lineElems`, floor 1 line — fixes `getBalancedTile`'s
  round-up bug. Only the parallel loop `p`'s tile loop gets `S`; others
  keep raw `T` (thesis §3.2 Phase 3).
- `TilingParams.createSelectionAlgo` must pass the **configured**
  `cacheSizeInKB`/`cacheLineInBytes`/`cores` (currently hardcodes 1024 KiB).

### D6. Parallel annotation and pass ordering

Phase 3 picks `p = DirectionVectorLemmas.outermostParallelLoop(DV', order)`
on the **tiled** vectors (Theorem 1). Phase 4 annotates `p` with
`CetusAnnotation("parallel","true")` (the `LoopParallelizationPass` pattern,
`LoopParallelizationPass.java:117-124`) — resurrecting the dead
`tagParallelLoops`.

Ordering in `Driver.runPasses` today: privatize → ddt → reduction →
interchange → **LoopParallelizationPass** → **ParallelAwareTiling** →
ompGen. A comment claims PAW must precede parallelization, but the thesis
§3.4 explicitly re-derives parallelism on the tiled nest and only "respects"
prior annotations, so we keep the order and, inside PAW after emission:
`RangeAnalysis.invalidate()`; re-run `ArrayPrivatization`, `DDTDriver`,
`Reduction` (existing `reRunPasses`), so `ompGen` sees fresh
private/reduction sets on the annotated tile loop. PAW must strip stale
`cetus parallel` annotations from loops whose parallel status changed
(cross-strip loops that became serial).

### D7. Static-first profitability (symbolic analysis)

`StaticProfitability.decide(loop, footprint, cacheElems, iterThreshold)`
uses `RangeAnalysis.query((Statement) loop)` → `RangeDomain` and
`RangeDomain.compare/isGE/isLE` (`RangeDomain.java:293-360`, returning
`Relation`; `isUnknown()` ⇒ inconclusive):

- `PROVE_UNTILED` (fits in cache or too few iterations) → emit original.
- `PROVE_TILED` → emit tiled only, no guard.
- `UNKNOWN` → current two-version `IfStatement` guard.

The existing literal-folding in `createOptimizedStatement` becomes the
trivial case of this module. `RangeAnalysis.invalidate()` is called after
any IR restructuring and before queries.

### D8. PAPI instrumentation pass

`PAPIInstrumentation extends TransformPass`, registered as CODEGEN-type
option `-papi_instrument[=events]`, run **after** `ompGen` in
`Driver.runPasses`. Insertion style follows `EventTimer`/`LoopProfiler`
(annotation-driven `CodeAnnotation` text blocks + `annotateBefore/After`,
`EventTimer.java`): regions = statements carrying the `c_paw_tiling`
`PragmaAnnotation` (emitted by `Tiler.createCrossStripLoop`); outermost such
statement per nest only. Generated skeleton per translation unit: guarded
`#include <papi.h>`, one-time init helper; per region: event-set
create/start before, stop + `fprintf(stderr, "[papi] ...")` after, outside
any parallel region. All PAPI calls check return codes and degrade to
counters = -1 with a warning. Default events `PAPI_L3_DCM,PAPI_TOT_CYC`.

### D9. Testing strategy

JUnit 4 (`lib/junit.jar`, pattern: `src/cetus/unittest/`, runner
`TestTripletRunner`-style manual `JUnitCore`). Pure-logic units (lemmas,
FindB, NT arithmetic, balanced size) get real unit tests. HIR-level behavior
(browsing, emission, PAPI) is verified end-to-end: compile
`resources/paw_tests/*.c` kernels with `bin/cetus` flags and assert on the
emitted C (grep for tile loops / pragmas / guard shape), plus
`gcc -fopenmp [-lpapi]` compile + run + output diff against the untiled
binary. Add a `run-paw-tests.sh` helper.

## Risks / open points

- `DDGraph.getDirectionMatrix` returns vectors keyed by `Loop` object
  references; after cloning nests, lookups go by index-symbol name (existing
  `TiledLoop.lookupLoop` approach). Keep name-based matching but centralize
  it in `DirectionVectorLemmas` to avoid the current scattered fragility.
- Symbolic tile-size variables must be declared per nest (existing
  `VariableDeclarationUtils.declareVariable`); Phase 3 sets initializers —
  watch shadowing when several nests are tiled in one scope (suffix with
  nest/loop name, existing `IN_TILE_PREFIX` convention).
- `LoopInterchange.ReusabilityAnalysis` hardcodes 64 B lines; acceptable for
  the tiebreak role. Do not fork it.
- NT needs "the reference with most reuse" — defined as the reference group
  with the highest temporal score w.r.t. the chosen parallel-candidate loop;
  ties → larger footprint.
