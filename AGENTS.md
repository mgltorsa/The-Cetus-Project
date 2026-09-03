# AGENTS.md — Cetus + Parallel-Aware Tiling (PAW)

Onboarding for agents working on this repository. The active work is the
**Parallel-Aware Tiling pass** (`src/cetus/transforms/paw_tiling/`) on
branch `feature/paw-tile-2025`.

## What this project is

Cetus is a source-to-source parallelizing compiler for C, written in Java.
It parses C into a high-level IR (HIR, `cetus.hir`), runs analysis passes
(`cetus.analysis`) and transformation passes (`cetus.transforms`), and
prints C back out, typically annotated with OpenMP pragmas.

PAW tiling implements the Master's thesis *"Parallel-Aware Tiling"*
(M. Torres, Univ. of Delaware; sources at
`/mnt/d/workspace/ud-masters/Masters_Thesis/`). The algorithm
(`figures/heuristic-tiling.tex`, Algorithm 3.1) tiles a perfect canonical
loop nest in four phases:

1. **Analysis** — dependence direction vectors + loops ranked by decreasing
   temporal reuse.
2. **Reuse-ordered browsing** — strip-mine loops in reuse order (symbolic
   tile size), rewrite the direction vectors with the Pan et al. lemmas,
   discard lexicographically negative (illegal) candidates, stop at tiling
   depth `d`.
3. **Tile-size selection** — raw sizes from Fixed / NT / LRW(FindB); pick
   the outermost parallel loop of the *tiled* nest (Lemma "Parallelism");
   substitute the balanced size `S = I/(ceil(I/(P·T))·P)` on its tile loop,
   cache-line aligned (down).
4. **Emission** — annotate that loop parallel (→ OpenMP via ompGen);
   profitability decided statically via symbolic range analysis, with a
   runtime two-version guard only when inconclusive.

The four lemmas (strip-mining DV split, reordering, permutability,
parallelism) and Theorem "parallelism of tiled loops" are stated in thesis
Chapter 2 §2.2.3; they let the pass derive the tiled nest's dependence
vectors from the original ones without re-running dependence analysis.
Priority is **temporal** reuse (spatial is a tiebreaker), and tile sizes
target the **shared L3 cache** (all cores compete for it — that is the
"parallel-aware" part).

## Where the plan lives (read this first)

- `openspec/project.md` — project context, conventions, glossary.
- `openspec/specs/` — current-state capability specs.
- `openspec/changes/extend-paw-tiling/` — the active change:
  `proposal.md` (why/what), `design.md` (decisions D1–D9 mapping thesis →
  Cetus APIs), `tasks.md` (ordered, checkboxed implementation tasks — the
  work queue), `specs/` (target-behavior deltas with scenarios).
- `Tiling.md` — user-facing docs for the pass (CLI flags, examples).

**Status (2026-08-12):** PAW change IMPLEMENTED; PAPI pass extended (R1:
region + program `TIME_NS`, program counters = sum of regions;
fallback regions via `c_paw_measure` / omp / outermost `kernel_*` loops;
empty-region case runs PAPI around `main` so baselines still get counters).
`./run-paw-tests.sh` should stay green after PAPI changes.

## Build, run, test

```bash
./build.sh bin           # compile + create bin/cetus launcher (Java 8)
bin/cetus -paw_tiling -cores=4 -cacheSize=25600 -cacheLine=64 \
          -selection=LRW gemm.c        # output goes to ./cetus_output/
./run-paw-tests.sh       # PAW unit + end-to-end tests (task 1 creates it)
```

- No Maven/Gradle; jars in `lib/` (`antlr.jar`, `junit.jar`, ...).
- JUnit 4 tests live under `src/cetus/unittest/` and run via a manual
  `JUnitCore` runner class (see `TestTripletRunner.java`).
- Useful flags: `-verbosity=4` (debug prints), `-ddt=2`, `-privatize=2`,
  `-reduction=2` (defaults, keep on), `-tileSizes=a,b` (fixed sizes ⇒
  FIXED algo), `-tile-profitability=0|1`, `-tilingLevel=N` (tiling depth,
  added by the active change), `-papi_instrument` (PAPI pass, added by the
  active change).

## Code map (PAW-relevant)

| Path | Role |
| --- | --- |
| `src/cetus/transforms/paw_tiling/ParallelAwareTiling.java` | Pass entry (`PASS_NAME="paw_tiling"`); orchestrates the four phases |
| `src/cetus/transforms/paw_tiling/legality/DirectionVectorLemmas.java` | Pure lemma engine: `stripMine`, `reorder`, `isLegal`, `isParallel`, `outermostParallelLoop` (name-based loop matching) |
| `src/cetus/transforms/paw_tiling/analysis/ReuseOrderAnalyzer.java` | Temporal-reuse loop ranking, reference groups, resident reference, trip counts |
| `src/cetus/transforms/paw_tiling/analysis/EmptyDvAuditor.java` | Phase-1 empty-DV gate: nest-usable DDT filter + quick R/W recovery (`RECOVERED` / `CONFIRMED_EMPTY`) |
| `src/cetus/transforms/paw_tiling/browse/CandidateBrowser.java` | Phase-2 reuse-ordered browsing with symbolic tile sizes; discards illegal candidates |
| `src/cetus/transforms/paw_tiling/profitability/StaticProfitability.java` | Static-first profitability (literal fast-path, then `RangeAnalysis`); UNKNOWN ⇒ runtime guard |
| `src/cetus/transforms/paw_tiling/instrumentation/PAPIInstrumentation.java` | `-papi_instrument` pass; region + program wall time (`TIME_NS`); region PAPI; program line sums region counters (R1); regions = `kernel_*` outermost loops (baseline-aligned), else `c_paw_tiling` / `c_paw_measure` / omp; never `init_array`/`print_array` |
| `src/cetus/transforms/paw_tiling/Tiler.java` | Strip-mining mechanics (`stripmining`, `createCrossStripLoop`, `createInStripLoop`); DV rewrite delegates to the lemma engine |
| `src/cetus/transforms/paw_tiling/TiledLoop.java` | `ForLoop` subclass carrying DVs, tile sizes, parallel-loop metadata |
| `src/cetus/transforms/paw_tiling/TilingParams.java` | Singleton over CLI options (`cores`, `cacheSize`, `cacheLine`, `selection`, `tileSizes`, `tilingLevel`, ...); `reset()` for tests |
| `src/cetus/transforms/paw_tiling/tile_size/` | `TileSizeSelectionAlgo` + Fixed / NT (shared-L3 `Cores×Refs` model) / LRW (`findB`) + `BalancedTileCalculator` |
| `src/cetus/unittest/paw_tiling/` | JUnit suite (`PawTestRunner`, lemmas + tile-size math tests) |
| `resources/paw_tests/` | End-to-end C kernels + header-only PAPI stub (`papi_stub/papi.h`) |
| `run-paw-tests.sh` | Full test driver: build + JUnit + end-to-end with/without instrumentation |
| `src/cetus/exec/Driver.java` | Option registration (~L357–404) and pass pipeline `runPasses` (~L838–912) |
| `src/cetus/transforms/LoopInterchange.java` | `ReusabilityAnalysis(...)` (public): McKinley cache-line loop-cost model (line size hardcoded 64 B) |
| `src/cetus/analysis/ReuseVectorAnalysis.java` | Binary temporal-reuse vectors per array access (index absent from all subscripts ⇒ 1) |
| `src/cetus/analysis/DDTDriver.java`, `DDGraph.java`, `DependenceVector.java` | Dependence analysis |
| `src/cetus/analysis/RangeAnalysis.java`, `RangeDomain.java`, `Relation.java` | Symbolic value-range analysis |
| `src/cetus/analysis/LoopTools.java` | Loop queries: `isPerfectNest`, `isCanonical`, `getIndexVariable`, `getLowerBoundExpression`, `getUpperBoundExpression`, `getIncrementExpression`, `isPrivate`, `isReduction` |
| `src/cetus/hir/Symbolic.java` | Symbolic algebra: `simplify, add, subtract, multiply, divide, mod, le, ge, ...` |
| `src/cetus/transforms/EventTimer.java`, `LoopProfiler.java` | Existing instrumentation patterns (model for the PAPI pass) |
| `src/cetus/codegen/ompGen.java` | Converts `CetusAnnotation("parallel")` (+ private/reduction keys) into `#pragma omp parallel for` |

## Key API recipes

**Dependence vectors of a nest** (after DDT has run):

```java
AnalysisPass.run(new DDTDriver(program));            // if not already run
DDGraph ddg = program.getDDGraph();
LinkedList<Loop> nest = new LinkedList<>();          // outermost -> innermost
new DFIterator<Loop>(outerLoop, Loop.class).forEachRemaining(nest::add);
List<DependenceVector> dvs = ddg.getDirectionMatrix(nest);
// directions: DependenceVector.nil(-1) any(0)'*' less(1)'<' equal(2)'=' greater(3)'>'
```

**Symbolic comparison** (static profitability):

```java
RangeDomain rd = RangeAnalysis.query((Statement) loop);
Relation r = rd.compare(footprintExpr, cacheExpr);   // r.isUnknown() possible
boolean fits = rd.isLE(footprintExpr, cacheExpr);
```

**Mark a loop parallel** (ompGen picks it up):

```java
CetusAnnotation note = new CetusAnnotation();
note.put("parallel", "true");
loop.annotate(note);
```

**After restructuring IR:** call `RangeAnalysis.invalidate()` and re-run
`ArrayPrivatization`, `DDTDriver`, `Reduction` (see
`ParallelAwareTiling.reRunPasses`) so privatization/reduction annotations
match the new loop structure before `ompGen` runs.

## Gotchas

- **Loop identity**: `DependenceVector` keys on `Loop` object references;
  after cloning a nest you must re-match loops **by index-symbol name**
  (`LoopTools.getLoopIndexSymbol(l).getSymbolName()`). `TiledLoop.lookupLoop`
  and `Tiler.findOriginalDirection` do this; the active change centralizes
  it in `paw_tiling/legality/DirectionVectorLemmas`.
- **Pass order**: `runPasses` currently runs PAW *after*
  `LoopParallelizationPass` and *before* `ompGen`. A stale comment claims
  PAW must precede parallelization — it does not; PAW re-derives parallelism
  on the tiled nest (thesis Theorem 1), but it must strip stale
  `cetus parallel` annotations from loops whose status changed.
- **Cross-strip naming**: tile (cross-strip) loops use index suffix
  `_cetus_cross`; symbolic tile-size variables are per-nest:
  `cetus_tile_<nestTag>_<index>` (e.g. `cetus_tile_main_1_k`) — never share
  size variables between nests, a later nest would overwrite an earlier
  one's initializer. Tiled regions carry the `c_paw_tiling`
  `PragmaAnnotation` (the PAPI pass keys on it).
- **PAPI insertion**: never `annotateBefore` a loop that carries an
  `omp` pragma (code would print between the pragma and its `for`);
  `PAPIInstrumentation` inserts standalone `AnnotationStatement`s into the
  parent `CompoundStatement` instead. Test locally against
  `resources/paw_tests/papi_stub/` (no libpapi needed; counters are 42, 43).
  Program wrap always emits when the pass is on; region selection prefers
  outermost loops in `kernel_*` (stderr labels `kernel_3mm#0`,
  `kernel_syrk#0`, … from `LoopTools.getLoopName()`; fallback `region_N`
  only if unnamed). Then `c_paw_tiling` / `c_paw_measure` / omp
  `parallel for`. Never instrument `init_array` / `print_array`. Do not nest
  PAPI event sets (program totals = sum of regions).
- **Fixed on 2026-08-11** (regression-test in the suite):
  `Tiler.getFarthestAncestorLoop` used to drop the first ancestor, so
  tiling the inner loop of a 2-level nest silently deleted the outer loop;
  `TiledLoop.clone` NPE'd when no loop was parallelizable;
  `ArrayUtils.getFullSize` is unreliable for `N+1`-style dimensions — the
  pass computes its own footprint (`calculateDataFullSize`).
- **Audit (2026-08-12):** lemmas + reuse analysis reviewed with debug logs
  (`[paw-lemma]`, `[paw-reuse]`, `[paw-browse]`, `[paw-dv-audit]` at
  `-verbosity=2`). See `PAW_LEMMAS_REUSE_AUDIT.md`. Empty DDT sets are
  audited by `EmptyDvAuditor` (recover `(=,=)` for init writes, or
  `CONFIRMED_EMPTY`); parallelism still follows Lemma 3. Browse order is
  computed **after** `LoopInterchange` (gemm → `(j,k,i)`, not the thesis
  sketch `(k,j,i)` on raw `(i,j,k)`).
- **Units**: `-cacheSize` is KiB; the tile-size algorithms work in array
  **elements** (divide bytes by `ArrayUtils.getTypeSizeInBits(access)/8`).
  Alignment rounds **down** to the cache line, never up.
- **`TilingParams` is a singleton** read once from `Driver` options — in
  tests, set options before first `getTilingParams()` call (or reset the
  `_instance` field reflectively).
- `LoopTools` has **no** trip-count helper; use the bound getters plus the
  pattern in `NTSelectionAlgo.computeTripCount`.
- `CacheMissesEquationsAnalysisPass` is an unfinished, unregistered stub
  (NPEs if run) and `analysis/Cache.java` is a memoization map, **not** a
  hardware cache model — don't build on either.
- Generic `LoopTiling.java` (`-loop-tiling`) is dead/commented-out in the
  Driver; PAW does not use it.
- Windows/WSL: the repo sits on `/mnt/d`; avoid tools that choke on DrvFS
  permissions, and keep line endings LF.

## Thesis ↔ code cross-reference

| Thesis artifact | Code target |
| --- | --- |
| Algorithm 3.1 (heuristic-tiling) | `ParallelAwareTiling.start` / `processLoop` phases |
| Lemmas 1–4, Theorem 1 (chap2 §2.2.3) | `paw_tiling/legality/DirectionVectorLemmas` |
| Reuse order (chap3 Phase 1, Kennedy–McKinley) | `paw_tiling/analysis/ReuseOrderAnalyzer` |
| NT model `t_i·t_j + Cores×Refs ≤ CacheSize` (chap3 §3.3.2) | `tile_size/NTSelectionAlgo` |
| FindB / Algorithm 3.2 (chap2 §2.3.2, chap3 §3.3.3) | `tile_size/LRWSelectionAlgo` |
| Balanced size `S = I/(ceil(I/(P·T))·P)` (chap3 §3.3.3) | `tile_size/BalancedTileCalculator` |
| Two-version guard (chap3 Phase 4) | `profitability/StaticProfitability` |
| PAPI_L3_DCM / PAPI_TOT_CYC methodology (chap5) | `transforms/PAPIInstrumentation` |
