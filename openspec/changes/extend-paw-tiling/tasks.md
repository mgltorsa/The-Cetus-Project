# Tasks: extend-paw-tiling

> **STATUS: IMPLEMENTED (2026-08-11).** All tasks below are complete; the
> full suite (`./run-paw-tests.sh`) passes 36/36 (20 JUnit + 16 end-to-end
> checks with and without PAPI instrumentation). Deviations from the
> original task text, all deliberate:
>
> 1. **FindB fidelity (RESOLVED — thesis corrected on 2026-08-11):**
>    thesis Algorithm 3.2 originally transcribed the column distance as
>    `dj = |addr mod N - N|`, losing the nearest-row branch of the original
>    Lam-Rothberg-Wolf algorithm. That form mis-handles conflicts at exact
>    row multiples: for N=1024, C=4096 it returns B0=1024 although rows
>    collide every 4 rows. Both the implementation AND the thesis
>    (`figures/lrw-algorithm.tex` and the FindB paragraph in
>    `chapters/chap2.tex` of the Masters_Thesis repo) now use the original
>    paper's form (`di = addr div N; dj = addr mod N; if dj > N/2 then
>    di += 1; dj = N - dj`), which returns the correct B0=4; a brute-force
>    self-interference checker in `TileSizeMathTest` validates it.
> 2. Raw tile sizes are computed on the ORIGINAL nest before browsing (the
>    browse candidates are the loops the method assigned a usable size to);
>    sizes never influence legality, so the result is the same as the
>    post-browse assignment in Algorithm 3.1 while letting Fixed's
>    "size<=1 means skip" semantics prune the browse.
> 3. Kernels shipped as `gemm.c`, `skewed_dep.c`, `small_footprint.c`,
>    `symbolic_bounds.c`; the runner class is
>    `cetus.unittest.paw_tiling.PawTestRunner`.
> 4. Tile-size variables are named `cetus_tile_<nestTag>_<index>` (e.g.
>    `cetus_tile_main_1_k`) so several nests in one function never share
>    size variables.
> 5. Two pre-existing bugs fixed on the way:
>    `Tiler.getFarthestAncestorLoop` dropped the first ancestor (2-level
>    nests lost their outer loop when tiling the inner one), and
>    `TiledLoop.clone` NPE'd when no loop was parallelizable.
> 6. `ArrayUtils.getFullSize` was found unreliable (takes only the RHS of
>    `N+1`-style dimensions, double-counts repeated accesses); the pass
>    computes its own footprint (sum of distinct arrays' simplified
>    dimension products) in `ParallelAwareTiling.calculateDataFullSize`.

> **For agentic workers:** execute top-to-bottom; each numbered task is
> independently reviewable and ends with a verification step. Read
> `design.md` (decisions D1–D9) and the delta specs in `specs/` before
> starting a task. Build with `./build.sh bin` from the repo root; run with
> `bin/cetus <flags> file.c`. Mark checkboxes as you complete steps and
> commit after every green verification (`git commit` on branch
> `feature/paw-tile-2025`).
>
> **Goal:** make `cetus.transforms.paw_tiling` implement thesis
> Algorithm 3.1 exactly, plus a PAPI instrumentation pass.

Cross-cutting constraints (apply to every task):

- Java 8 source level; no new external dependencies (JUnit 4 from
  `lib/junit.jar` only).
- Direction constants: `DependenceVector.nil=-1, any=0, less=1, equal=2,
  greater=3`.
- All cache quantities in **array elements** at the algorithm level; element
  size from `ArrayUtils.getTypeSizeInBits(access)`.
- Alignment always rounds **down** to the cache line (floor one line).
- Existing CLI flags keep working; new flags: `-tilingLevel`,
  `-papi_instrument`.

---

## 1. Test scaffolding

- [x] 1.1 Create `resources/paw_tests/` with small C kernels:
      `gemm.c` (i,j,k matmul, literal N=1024), `gemm_symbolic.c` (bounds
      from function params), `skewed.c` (a nest with DV `(<,>)`, e.g.
      `A[i][j] = A[i-1][j+1] + 1`), `stencil.c` (serial outer loop:
      `A[i] = A[i-1] + A[i+1]`).
- [x] 1.2 Create `src/cetus/unittest/paw_tiling/` package and a
      `PawTilingTestRunner.java` mirroring
      `src/cetus/unittest/TestTripletRunner.java` (manual
      `JUnitCore.runClasses`). Wire compilation into `build.sh compile`
      (sources under `src/` are already picked up; just verify).
- [x] 1.3 Add `run-paw-tests.sh` at repo root: builds, runs the JUnit
      runner, then for each kernel in `resources/paw_tests/` runs
      `bin/cetus -paw_tiling ...` and greps the emitted file for expected
      markers (fill patterns in as later tasks land).
- [x] Verify: `./build.sh bin && ./run-paw-tests.sh` passes (trivially at
      this point).

## 2. Lemmas engine (`legality/DirectionVectorLemmas.java`) — D1

Interfaces produced (later tasks consume these exact names):

```java
public final class DirectionVectorLemmas {
    // Lemma 4: returns rewritten vectors over newOrder (crossLoop precedes
    // inLoop at the strip-mined position); one input vector may map to 2.
    public static List<DependenceVector> stripMine(
        List<DependenceVector> dvs, List<Loop> oldOrder,
        Loop target, Loop crossLoop, Loop inLoop, List<Loop> newOrder);
    // Lemma 1
    public static List<DependenceVector> reorder(
        List<DependenceVector> dvs, List<Loop> oldOrder, List<Loop> newOrder);
    // Lemma 2 (any/'*' before the first '<' is illegal; nil skipped)
    public static boolean isLegal(List<DependenceVector> dvs, List<Loop> order);
    // Lemma 3
    public static boolean isParallel(List<DependenceVector> dvs,
                                     List<Loop> order, Loop p);
    public static Loop outermostParallelLoop(List<DependenceVector> dvs,
                                             List<Loop> order); // or null
}
```

Loop identity is matched by index-symbol name
(`LoopTools.getLoopIndexSymbol(loop).getSymbolName()`), centralizing the
lookup currently duplicated in `Tiler`/`TiledLoop`.

- [x] 2.1 Write failing JUnit tests `DirectionVectorLemmasTest` covering:
      `< → {(=,<),(<,*)}` split; `* → {(=,*),(*,*)}` split; `(<,>)` with
      inner tile loop hoisted outermost ⇒ `isLegal == false` (thesis
      Fig. dv-rewrite); `{(<,<)}` ⇒ `i` serial, `j` parallel,
      `outermostParallelLoop == j` (the case the old all-`=` rule gets
      wrong); `{(=,<)}` ⇒ outermost parallel is the outer loop; leading `*`
      ⇒ illegal. Build DVs directly with
      `new DependenceVector(nest)` + `setDirection`.
- [x] 2.2 Implement the class (port the split logic out of
      `Tiler.calculateAfterTilingDV`, add the missing `any` case and
      reorder/isLegal/isParallel).
- [x] 2.3 Tests green; commit.

## 3. Reuse-order analysis (`analysis/ReuseOrderAnalyzer.java`) — D2

Interface produced:

```java
public class ReuseOrderAnalyzer {
    /** Loops of the nest sorted by decreasing temporal reuse.
     *  Tie-breaks: McKinley cost (spatial) if computable, then innermost
     *  first. */
    public static List<ForLoop> reuseOrder(ForLoop nest);
    /** Temporal score: sum over reference groups not subscripted by the
     *  loop's index of the group's per-iteration footprint (symbolic ok). */
    public static Expression temporalScore(ForLoop loop, ForLoop nest);
    /** Reference groups: same array symbol + equal subscript expr lists
     *  (uniform constant offsets folded into one group). */
    public static List<List<ArrayAccess>> referenceGroups(ForLoop nest);
}
```

- [x] 3.1 Failing tests: matmul `d[i][j]+=a[i][k]*b[k][j]` ⇒ order
      `(k, j, i)` with equal literal trip counts (equal scores resolved
      innermost-first); a nest where one loop reuses two references ranks
      first; `referenceGroups` folds `A[i][j]` and `A[i][j+1]` into one
      group. (Parse tiny programs through the Cetus parser in the test, or
      construct HIR by hand — follow whichever `cetus.unittest` does.)
- [x] 3.2 Implement, using `ReuseVectorAnalysis`-style subscript-absence
      for the temporal predicate and `Symbolic` for footprints. Tiebreak
      hook calls `LoopInterchange.ReusabilityAnalysis` (public) guarded by
      try/catch — on failure fall back to innermost-first only.
- [x] 3.3 Tests green; commit.

## 4. Candidate browsing (`browse/CandidateBrowser.java`) — D3, Phase 2

Interface produced:

```java
public class CandidateBrowser {
    public static class BrowseResult {
        public TiledLoop nest;                    // final X
        public List<DependenceVector> dvs;        // rewritten DV set
        public List<ForLoop> tileLoops;           // committed cross-strip loops (reuse order)
        public Map<Expression, IDExpression> symbolicSizes; // index var -> tile-size symbol
    }
    public static BrowseResult browse(ForLoop nest,
        List<DependenceVector> originalDvs, List<ForLoop> reuseOrder,
        int depth, SymbolTable symtab) throws Exception;
}
```

- [x] 4.1 Add `-tilingLevel` to `TilingParams` (name constant
      `TILING_LEVEL_PARAM_NAME = "tilingLevel"`, default = nest depth) and
      register it in `Driver.registerOptions()` next to the other PAW
      options (`Driver.java` ~L366-404).
- [x] 4.2 Extend `Tiler.stripmining` so the tile size can be a *declared but
      uninitialized* symbolic identifier (`cetus_tile_<index>` via
      `VariableDeclarationUtils.declareVariable`); no numeric value at
      browse time.
- [x] 4.3 Implement `browse` per the design pseudocode: clone `X`,
      strip-mine next reuse-order loop, hoist cross loop outermost of `X`
      (existing `Tiler.tile` mechanics), rewrite DVs with
      `DirectionVectorLemmas.stripMine` (+`reorder`), test with `isLegal`;
      discard-and-continue vs commit. Stop at `depth`.
- [x] 4.4 Failing-then-green tests: `skewed.c` DV `(<,>)` ⇒ candidate that
      hoists the `j` tile loop outermost is discarded, nest still gets the
      legal candidates only; depth=1 on gemm ⇒ exactly one `_cetus_cross`
      loop; gemm depth=2 ⇒ tile loops appear in reuse order `(k, j)`
      outermost. (These can be end-to-end greps in `run-paw-tests.sh` if
      HIR-level assertions are awkward.)
- [x] 4.5 Commit.

## 5. Tile-size algorithms rewrite — D5, Phase 3 (raw sizes)

- [x] 5.1 Fix `TilingParams.createSelectionAlgo` to pass configured
      `cacheSizeInKB`, `cacheLineInBytes`, `numOfProcessors` (drop the
      hardcoded `1024`). Change `TileSizeSelectionAlgo` contract to
      `Map<Expression,Expression> getTileSizes(ForLoop nest, List<ForLoop> browseOrder)`
      so Fixed assigns in browse order (spec).
- [x] 5.2 **NT rewrite** (`NTSelectionAlgo`): failing tests first —
      thesis numbers: `CacheSize=3_276_800` elements (25 MiB doubles),
      `Cores=4`, `Refs=3`, `t_i=1000` ⇒ `t_j=3272` (after 8-elt align);
      square `B=56`. Implement
      `t_j = floor((C − cores*refs)/t_i)` with `Refs` = count of
      `ReuseOrderAnalyzer.referenceGroups`, `t_i` = trip count of the loop
      enclosing the top-temporal-reuse group's resident dimension; symbolic
      trip counts stay symbolic (`Symbolic.divide/subtract`); align down.
- [x] 5.3 **LRW/FindB rewrite** (`LRWSelectionAlgo`): failing tests first —
      (a) brute-force cross-check: for `N in {6, 100, 1000, 1024}`,
      `C in {16, 4096, 32768}`, FindB's `B0` equals the largest `B` such
      that rows `0..B-1` of width `B` starting at multiples of `N` have no
      two elements congruent mod `C`; (b) `N=1024, C=4096` ⇒ `B0 <
      floor(sqrt(C))`. Implement Algorithm 3.2 verbatim
      (`maxWidth=min(N,C); addr+=C; di=addr div N; dj=|addr mod N − N|; ...`),
      `N` = leading dimension in elements of the resident reference
      (`ArrayUtils.getArraySize`); fallback to NT square tile when `N` is
      not a literal; align `B0` down to the line.
- [x] 5.4 Tests green; commit.

## 6. Balanced tile + parallel loop selection (Phase 3 in the pass) — D5/D6

Interface produced:

```java
public final class BalancedTileCalculator {
    /** S = I / (ceil(I/(P*T)) * P), aligned down to lineElems (floor one
     *  line). Literal fast-path; otherwise symbolic expression. */
    public static Expression balancedSize(Expression tripCount,
        Expression rawTile, int processors, int lineElems);
}
```

- [x] 6.1 Failing tests: `I=512, T=80, P=4 ⇒ S=64` (thesis example);
      `I=1000, T=100, P=4, lineElems=8 ⇒ ceil(1000/400)=3, 1000/12=83 → 80`;
      symbolic `I` ⇒ expression contains `(I + P*T - 1)/(P*T)` shape.
- [x] 6.2 Implement; then rewrite
      `ParallelAwareTiling.balanceTileSizesAndEnsuringParallelizability` to:
      pick `p = DirectionVectorLemmas.outermostParallelLoop(result.dvs, order)`;
      set only `p`'s tile-loop symbolic size initializer to `S`
      (`VariableDeclarationUtils.replaceVariableDeclaration`); set the other
      tile-size symbols to their raw `T`. Delete the old
      `getBalancedTile` mod-adding alignment bug.
- [x] 6.3 End-to-end: gemm with `-selection=NT -cores=4` emits a tile-size
      variable initialized with the balanced expression on the parallel tile
      loop and raw sizes elsewhere; commit.

## 7. Orchestrator rewrite (`ParallelAwareTiling.start`) — Phases 1–4

- [x] 7.1 Restructure `processLoop` to the four phases:
      (1) `dvs = program.getDDGraph().getDirectionMatrix(nest)`;
      `order = ReuseOrderAnalyzer.reuseOrder(nest)`;
      (2) `CandidateBrowser.browse(...)`;
      (3) sizes via `TilingParams.getTileSizeSelectionAlgo()` +
      `BalancedTileCalculator` + parallel-loop pick;
      (4) emission (task 8). Remove the old size-map-drives-tiling flow.
- [x] 7.2 Annotate `p` with `CetusAnnotation("parallel","true")`
      (resurrect `tagParallelLoops`, now driven by the lemma result); strip
      stale `cetus parallel` annotations from loops of the nest whose
      status changed (cross-strip serial case, Theorem 1).
- [x] 7.3 Keep/verify `reRunPasses` (privatize → ddt → reduction) and add
      `RangeAnalysis.invalidate()` after IR restructuring; confirm `ompGen`
      (which runs after PAW in `Driver.runPasses` ~L838-912) emits
      `#pragma omp parallel for` with private/reduction clauses on `p`.
- [x] 7.4 End-to-end on all four test kernels:
      gemm ⇒ tiled + `omp parallel for` on the outermost legal tile loop;
      `stencil.c` ⇒ inner parallelism only or untouched (no illegal pragma);
      `skewed.c` ⇒ illegal candidate skipped;
      output of tiled binaries diffs clean against untiled. Commit.

## 8. Static-first profitability (`profitability/StaticProfitability.java`) — D7

- [x] 8.1 Failing tests: literal footprint < literal cache ⇒
      `PROVE_UNTILED`; literal footprint > cache and iterations >
      threshold ⇒ `PROVE_TILED`; symbolic with no range info ⇒ `UNKNOWN`.
- [x] 8.2 Implement using `RangeAnalysis.query((Statement) nest)` +
      `RangeDomain.compare/isGE/isLE` (`Relation.isUnknown()` ⇒ UNKNOWN);
      fold `createOptimizedStatement`'s literal path into it. Wire into
      Phase 4: guard emitted only on UNKNOWN.
- [x] 8.3 End-to-end: `gemm.c` (literal N, footprint > cache) ⇒ **no** `if`
      guard, tiled only; `gemm_symbolic.c` ⇒ two-version guard present.
      Commit.

## 9. PAPI instrumentation pass — D8

- [x] 9.1 Create `src/cetus/transforms/PAPIInstrumentation.java`
      (`extends TransformPass`, pass name `papi_instrument`). Register
      option `-papi_instrument[=events]` (CODEGEN type) in
      `Driver.registerOptions()`; invoke in `runPasses` **after** `ompGen`.
- [x] 9.2 Implement per spec: find outermost statements carrying the
      `c_paw_tiling` `PragmaAnnotation`; per TU insert guarded
      `#include <papi.h>` + init helper (follow `EventTimer`'s
      `CodeAnnotation`/`AnnotationDeclaration` pattern); wrap each region
      with event-set start/stop + `fprintf(stderr, "[papi] <name>
      PAPI_L3_DCM=%lld PAPI_TOT_CYC=%lld\n", ...)`; return-code checks
      degrade to `-1` counters; never instrument inside a parallel region.
- [x] 9.3 Verify: `bin/cetus -paw_tiling -papi_instrument gemm.c`; compile
      output `gcc -fopenmp out/gemm.c -lpapi` (if PAPI installed; otherwise
      assert emitted source contains the expected calls); run ⇒ one
      `[papi]` stderr line; numeric program output unchanged. A file where
      PAW tiles nothing ⇒ no PAPI code. Commit.

## 10. Docs and spec archive

- [x] 10.1 Update `Tiling.md`: four-phase description, corrected
      LRW-is-FindB section, NT thesis model, `-tilingLevel`,
      `-papi_instrument`, static-vs-guarded profitability; fix the "LRW =
      Largest Rectangle" naming (it is Lam–Rothberg–Wolf).
- [x] 10.2 Update `openspec/specs/paw-tiling/spec.md` to the post-change
      behavior (merge the deltas), add
      `openspec/specs/tile-size-selection/spec.md` and
      `openspec/specs/papi-instrumentation/spec.md`, and move
      `openspec/changes/extend-paw-tiling/` to
      `openspec/changes/archive/extend-paw-tiling/`.
- [x] 10.3 Refresh `AGENTS.md` if interfaces drifted. Final full run of
      `./run-paw-tests.sh`; commit.
