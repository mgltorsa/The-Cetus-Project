# Project Context: Cetus Parallel-Aware Tiling (PAW)

## Purpose

Cetus is an extensible source-to-source parallelizing compiler for ISO/ANSI C
(Java implementation, `src/cetus/`). This fork (branch
`feature/paw-tile-2025`) develops the **Parallel-Aware Tiling (PAW) pass**
(`cetus.transforms.paw_tiling`), the implementation vehicle for the Master's
thesis *"Parallel-Aware Tiling"* (M. Torres, University of Delaware). The
thesis sources live at `/mnt/d/workspace/ud-masters/Masters_Thesis/`
(algorithm: `figures/heuristic-tiling.tex`, design: `chapters/chap3.tex`,
lemmas: `chapters/chap2.tex` §2.2.3).

The PAW pass applies loop tiling **in concert with** parallelization
(extension of Pan et al., IWOMP 2005, DOI 10.1007/978-3-540-68555-5_3):
legality and parallelism are both derived from one shared set of dependence
direction vectors, so the tiled nest is parallelized anew and cannot violate
the dependences that parallelization relies on. Tile sizes account for the
*shared* last-level (L3) cache that all cores compete for.

## Tech Stack

- Java 8+ (source level 8; no external dependency manager — libs in `lib/`)
- Build: `./build.sh bin` (ant wrapper, see `build.sh` / `build.xml`);
  produces the `cetus` launcher script
- Run: `cetus [options] -paw_tiling input.c` (see `Tiling.md`)
- Antlr-based C parser; HIR (High-level IR) in `cetus.hir`
- No JUnit infrastructure; testing is done by compiling C kernels and
  inspecting/diffing/executing the emitted C code

## Project Conventions

- Passes extend `cetus.transforms.TransformPass` (transforms) or
  `cetus.analysis.AnalysisPass` (analyses); registered and ordered in
  `cetus.exec.Driver` via `options.add(...)` and `runPasses(...)`.
- Command-line options are read with `Driver.getOptionValue(name)`.
- HIR manipulation: `DFIterator` for traversal, `Symbolic` for symbolic
  expression algebra, `LoopTools` for loop queries (bounds, index vars,
  canonical/perfect-nest checks), `DDGraph`/`DependenceVector` for
  dependences (direction constants: `DependenceVector.less/equal/greater/any/nil`).
- PAW-specific code stays under `src/cetus/transforms/paw_tiling/`;
  reusable helpers go in `src/cetus/utils/`.
- Debug output through `PrintTools.printlnDebug` (controlled by `-verbosity`).

## Domain Glossary

- **Tiled version / candidate**: a variant of a loop nest obtained by
  strip-mining one or more loops and permuting the resulting tile loops
  outward. The search space of the algorithm (thesis §3.1.1).
- **Cross-strip (tile) loop**: the loop that steps block-to-block.
  **In-strip (intra-tile) loop**: the loop iterating inside a block.
- **Direction vector**: one entry per loop (outermost→innermost) with
  direction `<`, `=`, `>`, or `*`; legal iff leftmost non-`=` entry is `<`.
- **Lemmas (Pan et al.)**: Reordering (permutation permutes DV entries),
  Permutability (legal iff no resulting DV is lexicographically negative),
  Parallelism (loop at leftmost `<` is serial and covers the dependence;
  loops inside it are parallel w.r.t. it), Strip-mining (entry `[d]` splits
  into `[=,d]` or `[d,*]`).
- **Reuse order**: loops sorted by decreasing temporal reuse
  (Kennedy–McKinley style); the browse order of candidates.
- **NT (Naive Tile)**: capacity-based tile size from
  `t_i·t_j + Cores×Refs ≤ CacheSize` (elements of the shared L3).
- **LRW / FindB**: Lam–Rothberg–Wolf critical blocking factor B0 — largest
  square block with no self-interference on a direct-mapped cache
  (safe/conservative on set-associative caches).
- **Balanced tile size**: `S = I / (ceil(I/(P·T)) · P)` — largest size ≤ T
  making the number of tiles a multiple of P processors, then aligned down
  to the cache line.

## Where Things Are

| Artifact | Location |
| --- | --- |
| PAW pass entry point | `src/cetus/transforms/paw_tiling/ParallelAwareTiling.java` |
| Strip-mining + DV rewrite | `src/cetus/transforms/paw_tiling/Tiler.java` |
| Tiled nest + legality/parallelism metadata | `src/cetus/transforms/paw_tiling/TiledLoop.java` |
| Options/params singleton | `src/cetus/transforms/paw_tiling/TilingParams.java` |
| Tile-size algorithms | `src/cetus/transforms/paw_tiling/tile_size/` |
| Reuse analysis (interchange pass) | `src/cetus/transforms/LoopInterchange.java`, `src/cetus/analysis/ReuseVectorAnalysis.java` |
| Dependence analysis | `src/cetus/analysis/DDTDriver.java`, `DDGraph.java`, `DependenceVector.java` |
| Symbolic/range analysis | `src/cetus/analysis/RangeAnalysis.java`, `cetus.hir.Symbolic` |
| Pass driver / options | `src/cetus/exec/Driver.java` |
| User documentation | `Tiling.md` (repo root) |
| Specs & change plans | `openspec/` (this directory) |
| Agent onboarding | `AGENTS.md` (repo root) |

## Active Changes

- `openspec/changes/extend-paw-tiling/` — extend the basic PAW implementation
  to the full thesis algorithm (reuse-ordered browsing, lemmas engine,
  NT/FindB tile sizes, balanced shared-L3 tiles, symbolic profitability,
  PAPI instrumentation). See its `proposal.md`, `design.md`, `tasks.md`.
