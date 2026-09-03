# Change: Extend PAW tiling to the full thesis algorithm

> **STATUS: IMPLEMENTED (2026-08-11).** `./run-paw-tests.sh` passes 36/36.
> See the STATUS block in `tasks.md` for the deviation list (incl. the
> FindB correction relative to thesis Algorithm 3.2).

## Why

The current `cetus.transforms.paw_tiling` pass is a basic implementation: it
strip-mines every loop the tile-size algorithm returns, in nest order, with
no reuse analysis, no candidate browsing, no balanced tile size, and
tile-size formulas that do not match the thesis (Chapter 3) or its references
(Pan et al. IWOMP'05; Lam–Rothberg–Wolf ASPLOS'91; Kennedy–McKinley). The
thesis defends an exact algorithm (`figures/heuristic-tiling.tex`,
Algorithm 3.1 "Reuse-ordered Parallel-Aware Tiling") — the implementation
must faithfully realize it, plus PAPI instrumentation for the evaluation
(PAPI_L3_DCM, PAPI_TOT_CYC).

## What Changes

1. **Lemmas engine** (`legality/DirectionVectorLemmas`): explicit, testable
   implementations of the four Pan-et-al. lemmas — strip-mining DV split,
   reordering, permutability (lexicographic non-negativity), parallelism
   (leftmost-`<` covering) — plus Theorem "parallelism of tiled loops"
   honored by re-deriving parallelism on tiled vectors.
2. **Reuse analysis / reuse order** (Phase 1): rank loops by decreasing
   temporal reuse (McKinley-style; reuse LoopInterchange's reusability
   machinery where possible, priority on temporal reuse, spatial as
   tiebreaker).
3. **Reuse-ordered candidate browsing** (Phase 2): walk the reuse order up to
   tiling depth `d` (new option `-tilingLevel`), build candidate tiled
   versions with symbolic tile sizes, rewrite DVs by the lemmas, discard
   lexicographically negative candidates, keep survivors.
4. **Tile-size selection** (Phase 3): rewrite NT to the thesis model
   (`t_i·t_j + Cores×Refs ≤ CacheSize`, shared-L3 semantics, element units,
   cache-line alignment) and LRW to the FindB algorithm (Algorithm 3.2);
   both parameterized by the shared L3 size/line and core count.
5. **Parallel loop selection + balanced tile** (Phase 3): pick the outermost
   parallel loop of the *tiled* nest via the Parallelism lemma; substitute
   the balanced size `S = I/(ceil(I/(P·T))·P)` (aligned down to cache line)
   on its tile loop; recompute privatization/reduction.
6. **Code emission** (Phase 4): annotate the parallel tile loop
   (`cetus parallel` → OpenMP via ompGen); static-first profitability using
   Cetus symbolic range analysis, emitting the two-version runtime guard only
   when trip counts are symbolic.
7. **PAPI instrumentation pass** (new, separate pass `-papi_instrument`):
   after PAW, instrument the transformed regions with PAPI counters for L3
   data cache misses and total cycles.
8. **Docs**: update `Tiling.md`; add repo-root `AGENTS.md`.

## Non-goals (explicit, from thesis)

- Set-associative LRW heuristic and the copying optimization
  (`B = sqrt(((a-1)/a)·C)`) — recorded as future work in the thesis; FindB's
  direct-mapped B0 is the deliverable.
- Pan et al.'s exhaustive cost-model enumeration — PAW deliberately replaces
  it with reuse-ordered browsing (no cost function step).
- Imperfect nests, non-canonical loops, runtime autotuning.

## Impact

- Affected specs: `paw-tiling` (heavily modified), new `tile-size-selection`,
  new `papi-instrumentation`.
- Affected code: `src/cetus/transforms/paw_tiling/**` (restructured),
  `src/cetus/exec/Driver.java` (new options / pass registration),
  new `src/cetus/transforms/PAPIInstrumentation.java` (or
  `paw_tiling/instrument/`), `Tiling.md`, `AGENTS.md`.
- Backward compatibility: existing options (`-paw_tiling`, `-cores`,
  `-cacheSize`, `-cacheLine`, `-selection`, `-tileSizes`,
  `-tile-profitability`) keep working; new options are additive
  (`-tilingLevel`, `-papi_instrument`).
