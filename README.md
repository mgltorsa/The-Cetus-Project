# The Cetus Project

Cetus is a source-to-source compiler framework originally developed at Purdue University and extended at the University of Delaware. This repository contains improvements and new analysis/optimization passes for automatic parallelization and loop transformations.

## Table of Contents

- [Context and Motivation](#context-and-motivation)
- [Features and Recent Improvements](#features-and-recent-improvements)
- [How to Build and Run Cetus](#how-to-build-and-run-cetus)
- [Examples](#examples)
- [Benchmarking](#benchmarking)
- [Documentation and API Reference](#documentation-and-api-reference)
  - [Feature Documentation](#feature-documentation)
  - [Analysis Passes](#analysis-passes)
  - [Transformation Passes](#transformation-passes)
  - [Full API Documentation](#full-api-documentation)

## Context and Motivation

Cetus provides infrastructure for analyzing and transforming C programs, with a focus on parallelization (OpenMP) and advanced loop transformations. This branch adds new analyses and transformations such as subscripted-subscript analysis and parallel-aware tiling, along with several core bug fixes and enhancements.

## Features and Recent Improvements

1. **Handling of loop index initializations within `for` loop declarations**  
   When `for` loops are declared as `for (int i = 0; i < n; i++)`, earlier Cetus would remove the initialization of `i` from its place and hoist it to the top of the code block, following the design principle that all variable declarations appear at the top of the block. A loop cannot be parallelized without knowing the initial value of the loop index. Now, instead of a loop header of the form `for ( ; i < n; i++)`, we keep `for (i = 0; i < n; i++)` and `int i` appears at the top of the block.

2. **Support for logical and bitwise scalar reductions**  
   Scalar reductions of the form `x = x op expr`, where `op` is any of logical AND (`&&`), logical OR (`||`), bitwise OR (`|`), bitwise AND (`&`), or bitwise XOR (`^`) are now supported. Bitwise assignment operators of the form `&=`, `|=`, and `^=` are also recognized.

3. **Support for min and max reductions**  
   OpenMP added the reduction-identifiers `min` and `max` to the reduction clause starting from OpenMP 3.1. Cetus can now recognize min and max reductions implemented using the conditional operator (`? :`), subject to some expression restrictions.

4. **Support for multiple reductions using different operators**  
   Cetus previously had no issue recognizing multiple unique reduction statements within the same loop, but it could not create a separate reduction clause for each reduction-identifier within the same directive according to the latest OpenMP specification. Support for this has now been added, so a directive such as `#pragma omp parallel for private(i) reduction(max: maxl) reduction(&: b)` is handled correctly instead of trying to combine all identifiers and operators into one clause.

5. **Loop interchange pass improvements**  
   - Fixed minor bugs in the loop interchange legality algorithm.  
   - Added reusability analysis to determine the best order of loops in a nest for maximizing cache-line reuse (based on K. S. McKinley’s paper “Optimizing for Parallelism and Data Locality”).  
   - The pass can handle symbolic loop bounds.

## How to Build and Run Cetus

1. Download Cetus through the “Download Code” (green button) above or using `wget`.
2. Unpack the ZIP/TAR file and navigate to the main directory.
3. Run the build script:  
   ```bash
   ./build.sh bin
   ```
4. The Cetus executable is created in the `bin` directory.
5. Copy the `cetus` executable into your working directory (or add `bin` to your `PATH`).
6. Run the `cetus` executable with no arguments to see the list of available options and how to enable them.
7. To compile a source code using Cetus from the command line, type:  
   ```bash
   ./cetus [options] [C_FILE]
   # Example:
   ./cetus -parallelize-loops=2 foo.c
   ```
8. The output file after running Cetus is written to the `cetus_output` folder in your working directory.
9. Inside the `resource` directory, you can find example programs.

## Examples

- **Basic parallelization example**  
  See the command-line example above: `./cetus -parallelize-loops=2 foo.c`.

- **Feature-specific examples**  
  - Subscripted-subscript analysis examples and benchmarks are described in `docs/SubscriptedSubscriptAnalysis.md`.  
  - Parallel-aware tiling examples and benchmarks are described in `docs/Parallel-Aware-Tiling.md`.

## Benchmarking

This repository includes benchmark setups for evaluating new analyses and transformations:

- **Subscripted subscript analysis**: Benchmarks and scripts are documented in `docs/SubscriptedSubscriptAnalysis.md`.  
- **Parallel-aware tiling (PAW Tiling)**: Benchmark-oriented command lines (NAS Parallel Benchmarks, PolyBench, and others) are documented in `docs/Parallel-Aware-Tiling.md`.

Refer to those documents for detailed instructions, datasets, and interpretation guidelines.

## Documentation and API Reference

### Feature Documentation

- `docs/SubscriptedSubscriptAnalysis.md` – Subscripted subscript analysis pass  
- `docs/Parallel-Aware-Tiling.md` – Parallel-aware tiling (PAW Tiling) pass

### Analysis Passes

The following analysis passes are available in Cetus. See the [Javadoc API](api/index.html) for detailed documentation:

#### Loop Analysis
- [`LoopParallelizationPass`](api/cetus/analysis/LoopParallelizationPass.html) – Identifies and marks parallelizable loops
- [`LoopAnalysisPass`](api/cetus/analysis/LoopAnalysisPass.html) – Base class for loop analysis passes
- [`LoopInfo`](api/cetus/analysis/LoopInfo.html) – Loop information and metadata
- [`LoopTools`](api/cetus/analysis/LoopTools.html) – Utility functions for loop analysis
- [`SubscriptedSubscriptAnalysis`](api/cetus/analysis/SubscriptedSubscriptAnalysis.html) – Analysis for subscripted subscript patterns

#### Data Dependence Analysis
- [`DDTDriver`](api/cetus/analysis/DDTDriver.html) – Data dependence testing driver
- [`DDTest`](api/cetus/analysis/DDTest.html) – Interface for dependence testing algorithms
- [`BanerjeeTest`](api/cetus/analysis/BanerjeeTest.html) – Banerjee dependence test
- [`OmegaTest`](api/cetus/analysis/OmegaTest.html) – Omega test for dependence analysis
- [`RangeTest`](api/cetus/analysis/RangeTest.html) – Range-based dependence test
- [`DependenceVector`](api/cetus/analysis/DependenceVector.html) – Dependence vector representation
- [`DDGraph`](api/cetus/analysis/DDGraph.html) – Dependence graph data structure

#### Array and Memory Analysis
- [`ArrayPrivatization`](api/cetus/analysis/ArrayPrivatization.html) – Array privatization analysis
- [`ArrayParameterAnalysis`](api/cetus/analysis/ArrayParameterAnalysis.html) – Array parameter analysis
- [`AliasAnalysis`](api/cetus/analysis/AliasAnalysis.html) – Alias analysis for pointers
- [`PointsToAnalysis`](api/cetus/analysis/PointsToAnalysis.html) – Points-to analysis
- [`IPPointsToAnalysis`](api/cetus/analysis/IPPointsToAnalysis.html) – Interprocedural points-to analysis

#### Range and Value Analysis
- [`RangeAnalysis`](api/cetus/analysis/RangeAnalysis.html) – Range analysis for variables
- [`IPRangeAnalysis`](api/cetus/analysis/IPRangeAnalysis.html) – Interprocedural range analysis
- [`RangeDomain`](api/cetus/analysis/RangeDomain.html) – Range domain representation

#### Reduction Analysis
- [`Reduction`](api/cetus/analysis/Reduction.html) – Reduction variable analysis

#### Interprocedural Analysis
- [`IPAnalysis`](api/cetus/analysis/IPAnalysis.html) – Base class for interprocedural analyses
- [`IPAGraph`](api/cetus/analysis/IPAGraph.html) – Interprocedural analysis graph
- [`CallGraph`](api/cetus/analysis/CallGraph.html) – Call graph representation
- [`MayMod`](api/cetus/analysis/MayMod.html) – May-modify analysis

#### Other Analysis Passes
- [`InlineExpansion`](api/cetus/analysis/InlineExpansion.html) – Inline expansion analysis
- [`ControlFlowGraph`](api/cetus/analysis/ControlFlowGraph.html) – Control flow graph construction
- [`CFGraph`](api/cetus/analysis/CFGraph.html) – Control flow graph representation
- [`DataFlow`](api/cetus/analysis/DataFlow.html) – Data flow analysis framework
- [`Cache`](api/cetus/analysis/Cache.html) – Cache analysis utilities
- [`ReuseVectorAnalysis`](api/cetus/analysis/ReuseVectorAnalysis.html) – Reuse vector analysis

### Transformation Passes

The following transformation passes are available in Cetus:

#### Loop Transformations
- [`LoopTiling`](api/cetus/transforms/LoopTiling.html) – Loop tiling transformation
- [`LoopInterchange`](api/cetus/transforms/LoopInterchange.html) – Loop interchange transformation
- [`LoopNormalization`](api/cetus/transforms/LoopNormalization.html) – Loop normalization pass
- [`LoopProfiler`](api/cetus/transforms/LoopProfiler.html) – Loop profiling instrumentation
- [`LoopTransformPass`](api/cetus/transforms/LoopTransformPass.html) – Base class for loop transformations

#### Reduction Transformations
- [`ReductionTransform`](api/cetus/transforms/ReductionTransform.html) – Reduction variable transformation

#### Code Generation and Optimization
- [`InlineExpansionPass`](api/cetus/transforms/InlineExpansionPass.html) – Inline expansion transformation
- [`IVSubstitution`](api/cetus/transforms/IVSubstitution.html) – Induction variable substitution
- [`BranchEliminator`](api/cetus/transforms/BranchEliminator.html) – Branch elimination optimization

#### Code Normalization
- [`NormalizeReturn`](api/cetus/transforms/NormalizeReturn.html) – Return statement normalization
- [`SingleCall`](api/cetus/transforms/SingleCall.html) – Single call transformation
- [`SingleReturn`](api/cetus/transforms/SingleReturn.html) – Single return transformation
- [`SingleDeclarator`](api/cetus/transforms/SingleDeclarator.html) – Single declarator transformation
- [`RemoveUselessSpecifiers`](api/cetus/transforms/RemoveUselessSpecifiers.html) – Remove useless specifiers

#### Other Transformations
- [`AnnotationParser`](api/cetus/transforms/AnnotationParser.html) – Annotation parsing
- [`EventTimer`](api/cetus/transforms/EventTimer.html) – Event timing instrumentation
- [`ProcedureTransformPass`](api/cetus/transforms/ProcedureTransformPass.html) – Base class for procedure transformations
- [`TransformPass`](api/cetus/transforms/TransformPass.html) – Base class for all transformation passes

### Full API Documentation

- **Javadoc API Index**: [`api/index.html`](api/index.html) – Complete API documentation with search and navigation

Open `api/index.html` in a browser to explore the full API, search for classes, and navigate through packages.
