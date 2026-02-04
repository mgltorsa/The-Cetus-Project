# Parallel-Aware Tiling (PAW Tiling)

## Table of Contents

- [Context and Motivation](#context-and-motivation)
- [When to Use](#when-to-use)
- [How to Run](#how-to-run)
  - [Prerequisites](#prerequisites)
  - [Basic Command Structure](#basic-command-structure)
  - [Command Line Options](#command-line-options)
  - [Integration with Other Passes](#integration-with-other-passes)
- [Examples](#examples)
- [Technical Details](#technical-details)
- [Benchmarking](#benchmarking)
- [Troubleshooting](#troubleshooting)
- [API Reference](#api-reference)
- [Related Publications](#related-publications)

## Context and Motivation

Parallel-Aware Tiling (PAW Tiling) is an advanced compiler optimization pass in Cetus that transforms nested loops to improve cache locality and enable parallelization. This transformation divides loop iterations into smaller "tiles" that fit better in cache memory while maintaining data dependencies and enabling parallel execution.

### Key Features

- **Cache Locality Optimization**: Divides large loop iterations into cache-friendly tiles. Currently limited to L3-cache optimization.
- **Parallelization Support**: Maintains data dependencies while enabling parallel execution.
- **Serial Tiling for Testing**: Exposes a way to do serial tiling by disabling parallelization and disabling certain passes that can interfere with serial tiling.
- **Multiple Algorithms**: Supports LRW (Lam-Rothberg-Wolf), NT (Naive Tile), and fixed tile-size selection algorithms.
- **Architecture Awareness**: Configurable for different cache sizes and processor counts.
- **Integration**: This pass integrates with existing Cetus analysis and transformation passes.

## When to Use

PAW Tiling is most effective for:

- Nested loops with regular access patterns
- Loops accessing large arrays or matrices
- Computationally intensive kernels
- Applications where cache performance is critical

## How to Run

### Prerequisites

1. **Java Environment**: Java 8 or higher
2. **Cetus Compiler**: Built and ready to use
3. **Input Code**: C source files with nested loops

### Basic Command Structure

```bash
cetus [options] -paw_tiling input_file.c
```

### Command Line Options

#### Main Tiling Option

```bash
-paw_tiling[=value]
```

- `=0`: Force serial tiling (disable parallelization)
- `=1`: Enable parallel-aware tiling (default)
- No value: Same as `=1`

#### Configuration Options

**Processor Configuration**:

```bash
-cores=N                    # Number of cores (default: 4)
```

**Cache Configuration**:

```bash
-cacheSize=N                # Cache size in KiB (default: 32768)
-cacheLine=N                # Cache line size in bytes (default: 64)
```

**Tile Size Selection**:

```bash
-selection=ALGORITHM         # LRW, NT, or FIXED (default: NT)
-tileSizes=size1,size2,...   # Fixed tile sizes (comma-separated)
```

**Analysis Options**:

```bash
-tile-profitability=N        # Enable/disable profitability analysis (default: 1)
-verbosity=N                 # Output verbosity level (0-4)
```

### Integration with Other Passes

PAW Tiling automatically integrates with these Cetus passes:

- **Data Dependence Testing**: `-ddt`
- **Array Privatization**: `-privatize`
- **Reduction Analysis**: `-reduction`
- **Loop Parallelization**: `-parallelize-loops`
- **OpenMP Generation**: `-ompGen`

## Examples

### Basic Examples

#### Simple Matrix Multiplication

```bash
# Basic tiling with default settings
cetus -paw_tiling matrix_mult.c

# With specific core count
cetus -paw_tiling -cores=8 matrix_mult.c
```

#### Advanced Configuration

```bash
# Custom cache and algorithm settings
cetus -paw_tiling -cores=4 -cacheSize=16384 -cacheLine=64 -selection=LRW matrix_mult.c

# Fixed tile sizes
cetus -paw_tiling -tileSizes=32,64 matrix_mult.c
```

### Real-World Benchmark Examples

Based on the `launch.json` configuration, here are practical examples used for benchmarking:

#### NAS Parallel Benchmarks (NPB)

```bash
# Parallel tiling for NAS BT benchmark
cetus -alias=3 -verbosity=0 -profitable-omp=0 -tile-profitability=0 \
      -cores=4 -cacheLine=64 -cacheSize=46080 -selection=LRW \
      -paw_tiling x_solve_b.c

# Serial tiling (no OpenMP generation)
cetus -alias=3 -verbosity=0 -profitable-omp=0 -tile-profitability=0 \
      -ompGen=0 -cores=4 -cacheLine=64 -cacheSize=46080 -tileSizes=32 \
      -paw_tiling rhs_b.c
```

#### PolyBench Benchmarks

```bash
# Matrix multiplication with parallel tiling
cetus -alias=3 -verbosity=0 -profitable-omp=0 -tile-profitability=0 \
      -cores=4 -cacheLine=64 -cacheSize=46080 -selection=NT \
      -paw_tiling gemm.c

# Fixed tile sizes for 3mm benchmark
cetus -alias=3 -verbosity=0 -profitable-omp=0 -tile-profitability=0 \
      -cores=4 -cacheLine=64 -cacheSize=46080 -tileSizes=64 \
      -paw_tiling 3mm.c
```

### Algorithm Comparison Examples

```bash
# Compare different algorithms on the same benchmark
cetus -selection=LRW -paw_tiling benchmark.c    # Lam-Rothberg-Wolf
cetus -selection=NT -paw_tiling benchmark.c     # Naive Tile
cetus -tileSizes=32 -paw_tiling benchmark.c     # Fixed 32x32 tiles
cetus -tileSizes=64 -paw_tiling benchmark.c     # Fixed 64x64 tiles
```

### Input/Output Example

**Input Code**:

```c
// Original nested loop
for (i = 0; i < N; i++) {
    for (j = 0; j < N; j++) {
        for (k = 0; k < N; k++) {
            C[i][j] += A[i][k] * B[k][j];
        }
    }
}
```

**Output Code** (after PAW tiling):

```c
// After PAW tiling transformation
for (i_cetus_cross = 0; i_cetus_cross < N; i_cetus_cross += tile_size_i) {
    for (j_cetus_cross = 0; j_cetus_cross < N; j_cetus_cross += tile_size_j) {
        for (k_cetus_cross = 0; k_cetus_cross < N; k_cetus_cross += tile_size_k) {
            for (i = i_cetus_cross; i < MIN(i_cetus_cross + tile_size_i, N); i++) {
                for (j = j_cetus_cross; j < MIN(j_cetus_cross + tile_size_j, N); j++) {
                    for (k = k_cetus_cross; k < MIN(k_cetus_cross + tile_size_k, N); k++) {
                        C[i][j] += A[i][k] * B[k][j];
                    }
                }
            }
        }
    }
}
```

## Technical Details

### Architecture Overview

**High-Level Flow**:

```text
Input Loop Nest → Loop Analysis → Tile Size Selection → Tiling Transformation + Parallelization/Memory Analysis → Output Tiled Code
```

### Core Components

1. **ParallelAwareTiling** – Main transformation driver
2. **TiledLoop** – Extended `ForLoop` with tiling metadata
3. **Tiler** – Core tiling transformation engine
4. **TilingParams** – Configuration management
5. **Tile Size Algorithms** – LRW, NT, and fixed-size algorithms

### Legality Check

The legality check for parallelization and tiling in PAW Tiling is a collaborative process that involves multiple analysis and transformation passes within the Cetus framework.

- **Dependency Analysis**: Before any tiling or parallelization can occur, array and loop dependencies are analyzed (usually within a pass like `LoopParallelizationPass`). This pass computes data dependence vectors for the loop nest, indicating which loops are safe for transformation and parallelization.

- **Dependence Vector Re-computation after Stripmining**: When the tiling transformation is performed (particularly stripmining), the dependence vectors for the transformed (tiled) nest may change. In `Tiler.java`, the method `calculateAfterTilingDVs` takes the original dependence vectors and computes their form after tiling, reflecting the new structure and semantics of the nested loops.

- **Legality Validation in TiledLoop**: Once the tiled loop nest is created, the class `TiledLoop.java` performs its own validation of the new dependence vectors using the `setInternalDependenceVectors` method. This method inserts the appropriate set of new (transformed) dependence vectors into the tiled loop object and checks (by propagating legality rules) whether parallelization is still valid for any loop(s) in the transformed nest.

**In summary**:

- The legality check for both tiling and parallelization is distributed.  
- *Global legality* is initiated by analysis passes (e.g., `LoopParallelizationPass`) computing the "source of truth" data dependences.  
- *Local/manual legality* is enforced inside the tiling transformation (`Tiler.calculateAfterTilingDVs`) and post-validation in the new loop objects (`TiledLoop.setInternalDependenceVectors`), ensuring the output code is correct and does not violate dependences after loop reorganization.

**Pending**: High-level description of the legality algorithm.

### Integration with Cetus Passes and some bug fixes

Since PAW Tiling can be a complex pass, it relies on and interacts with several existing Cetus passes:

- **Data Dependence Testing (DDT)**: Analyzes loop dependencies.
- **Array Privatization**: Enables parallelization.
- **Reduction Analysis**: Handles reduction variables.
- **Loop Parallelization**: Identifies parallelizable loops. Fixed a bug when checking private and reduction variables on parallelization (Lines 274–279).

```java
if (src_symbol == sink_symbol &&
        (LoopTools.isPrivate(src_symbol, l)
                || (LoopTools.isPrivate(src_access, enclosing_loop))
                || LoopTools.isReduction(src_symbol, l)
                || (LoopTools.isReduction(src_access, enclosing_loop)))) {
    serialize = false;
}
```

- **Loop Tools**: Detecting tile increments that were not parallelized due to a required symbolic validation on the increment variable (Lines: 1188 and 1190).

```java
public static boolean isIncrementEligible(Loop loop) {
    boolean eligible_inc = true;
    if (isTileIncrement(loop)) {
        return true;
    }
    ...
}
```

- **OpenMP Generation**: Generates parallel code.

### Tiling Selection Algorithms

#### LRW (Largest Rectangle Without Self-Interference)

**Algorithm**: Based on Monica Lam and Michael Wolf's paper: "The Cache Performance and Optimization of Blocked Algorithm".

**Mathematical Foundation**:

The algorithm computes the largest square block that can be used without causing self-interference in caches. The key constraint is:

```text
block_factor × b² × element_size ≤ cache_size
```

Where:

- `b` = block dimension (tile size)
- `element_size` = size of each array element in bits
- `cache_size` = total cache size in bits
- `block_factor` = total number of data structures. Usually a factor of 3 accounts for typical matrix operations (A, B, C matrices).

**Implementation Details**:

```java
// Cache alignment calculation
long alignment = cacheLineSizeInBits / elementSizeInBits;
if (alignment > 0) {
    blockDimension = (blockDimension / alignment) * alignment;
    if (blockDimension < alignment) {
        blockDimension = alignment;
    }
}
```

**Best for**:

- Matrix multiplication kernels (GEMM, 3MM, 2MM)
- Regular access patterns
- Square or rectangular data structures
- Cache-sensitive applications

#### NT (Naive Tile Algorithm)

**Algorithm**: A naive algorithm that calculates the amount of data used per loop iteration.

**Core Concept**:

The algorithm estimates how much data is loaded per iteration of each loop dimension and selects tile sizes that keep the working set within cache limits.

**Implementation Details**:

```java
// Data load analysis for each loop index variable
Expression numElementsExpr = new IntegerLiteral(1);
for (int k = pos + 1; k < indices.size(); k++) {
    Expression tripCount = computeTripCount(innerLoop);
    numElementsExpr = Symbolic.multiply(numElementsExpr, tripCount);
}

// Tile size computation
Expression maxTileSize = Symbolic.divide(
    new IntegerLiteral(cacheSizeInBits),
    totalDataLoaded
);
```

**Memory Layout Consideration**:

- Assumes row-major memory layout.
- Inner loop dimensions contribute more to data loaded.
- Considers trip counts of inner loops.

**Best for**:

- General-purpose nested loops
- More irregular access patterns
- Multi-dimensional arrays
- More complex memory access patterns

#### Fixed Tile Sizes

**Algorithm**: Uses user-specified tile sizes.

**Implementation Strategy**:

```java
private class LastValueIterator implements Iterator<Integer> {
    private final int[] values;
    private int index;

    @Override
    public Integer next() {
        Integer value = values[index];
        index = Math.min(index + 1, values.length - 1);
        return value;
    }
}
```

**Command Line Examples**:

```bash
-tileSizes=32          # Single size for all loops
-tileSizes=64,32       # 64 for outer loop, 32 for inner loops
-tileSizes=128,64,32   # Different sizes for each loop level
```

**Best for**:

- Known optimal tile sizes from profiling
- Performance tuning and experimentation
- Research and algorithm comparison
- Custom optimization strategies

### Modified Classes in Cetus

#### Core Classes Created

1. **`ParallelAwareTiling.java`** – Main transformation driver  
   - **Extends**: `TransformPass`  
   - **Responsibilities**: Main transformation driver, loop validation, integration with other Cetus passes  
   - **Key Methods**: `start()`, `processLoop()`, `isValidForTiling()`, `balanceTileSizesAndEnsuringParallelizability()`

2. **`TiledLoop.java`** – Extended `ForLoop` class  
   - **Extends**: `ForLoop` from Cetus HIR  
   - **Purpose**: Maintains tiling metadata and dependence vectors  
   - **Key Methods**: `calculateOutermostParallelLoop()`, `setTileSizes()`, `getDependenceVectors()`

3. **`Tiler.java`** – Core tiling engine  
   - **Purpose**: Implements stripmining transformation  
   - **Key Methods**: `tile()`, `stripmining()`, `calculateAfterTilingDVs()`

4. **`TilingParams.java`** – Configuration management  
   - **Pattern**: Singleton pattern for global configuration  
   - **Responsibilities**: Command-line option parsing, algorithm selection, parameter management

#### Tile Size Selection Algorithm Classes

5. **`TileSizeSelectionAlgo.java`** – Interface for tile size algorithms  
   - **Purpose**: Defines interface for tile size selection algorithms

6. **`LRWSelectionAlgo.java`** – Lam-Rothberg-Wolf algorithm implementation  
   - **Algorithm**: Based on Lam, Rothberg, and Wolf's cache performance optimization paper

7. **`NTSelectionAlgo.java`** – Naive Tile algorithm implementation  
   - **Algorithm**: Heuristic based on data loaded per loop iteration

8. **`FixedSizesAlgo.java`** – Fixed tile sizes implementation  
   - **Algorithm**: Uses user-specified tile sizes

#### Modified Existing Classes

9. **`Driver.java`** – Main driver class  
   - **Modifications**:
     - Added command-line option registration for PAW tiling  
     - Integrated PAW tiling pass into the compilation pipeline  
     - Added configuration options for cores, cache size, etc.  
   - **Key Integration Points**: Option registration and pass execution sites in `Driver.java`.

10. **`LoopParallelizationPass.java`**  
    - **Modifications**:
      - Fixed a bug related to not properly identifying private and reduction variables (around lines 274–278 in the original implementation).

11. **`LoopTools.java`**  
    - **Modifications**:
      - Added a way to detect tile increments (when increments are complex expressions or variable references):

      ```java
      public static boolean isIncrementEligible(Loop loop) {
          boolean eligible_inc = true;
          if (isTileIncrement(loop)) {
              return true;
          }
          ...
      }
      ```

## Benchmarking

Parallel-aware tiling has been evaluated using the real-world benchmark configurations in the [Examples](#examples) section:

- **NAS Parallel Benchmarks (NPB)**: See the commands under “NAS Parallel Benchmarks (NPB)” in [Real-World Benchmark Examples](#real-world-benchmark-examples).
- **PolyBench Benchmarks**: See the commands under “PolyBench Benchmarks” in [Real-World Benchmark Examples](#real-world-benchmark-examples).

To reproduce the benchmarking, run those commands on your target machine with and without `-paw_tiling` and compare:

- Execution time
- Cache miss rates
- Parallel speedup and scalability

For more detailed guidance on performance evaluation, see [Performance Analysis](#performance-analysis).

## Troubleshooting

### Common Issues

#### 1. No Loops Processed

**Symptoms**: No tiling transformation applied.  
**Causes**:

- Loops not perfectly nested
- Loops not in canonical form
- No outermost loops found

**Solutions**:

- Use `-normalize-loops` to canonicalize loops
- Check loop structure in source code
- Enable debug output with `-verbosity=4`

#### 2. Tile Size Too Small

**Symptoms**: Poor performance after tiling.  
**Causes**:

- Cache size too small
- Algorithm selection inappropriate
- Fixed tile sizes too small

**Solutions**:

- Increase the `-cacheSize` parameter
- Try different `-selection` algorithms
- Adjust `-tileSizes` values

#### 3. Parallelization Issues

**Symptoms**: Loops not parallelized after tiling.  
**Causes**:

- Data dependencies prevent parallelization
- Cross-strip loops not identified
- Missing analysis passes

**Solutions**:

- Avoid disabling `-ddt` for dependence analysis
- Do not disable `-privatize` for array privatization
- Check dependence vectors in debug output

### Debug Options

```bash
# Enable verbose output
cetus -paw_tiling -verbosity=4 input.c
```

### Performance Analysis

#### Before Tiling

- Profile original code
- Identify cache-miss patterns
- Measure loop execution time

#### After Tiling

- Compare cache-miss rates
- Measure parallelization efficiency
- Analyze tile size effectiveness

#### Optimization Tips

1. **Start with defaults**: Use default parameters first.
2. **Profile guided**: Use profiling to guide parameter selection.
3. **Iterative tuning**: Adjust parameters based on performance.
4. **Architecture specific**: Tune for target hardware.

### Error Messages

#### Common Error Messages

```text
Error processing loop: No tile sizes found
```

**Solution**: Check loop structure and algorithm selection.

```text
No balanced tile found for loop
```

**Solution**: Verify tile size calculation and cache parameters.

```text
Index does not exist in the given loop nest
```

**Solution**: Check loop index variable names and nesting.

### Getting Help

For additional support:

1. **Documentation**: Refer to the Cetus user manual.
2. **Debug Output**: Use `-verbosity=4` for detailed information.
3. **Community**: Check the Cetus project website.
4. **Issues**: Report bugs through the project issue tracker.

## API Reference

- **Full Cetus Javadoc index**: `../api/index.html`
- **Loop tiling transform**: `../api/cetus/transforms/LoopTiling.html`
- **Key analysis passes used by PAW Tiling**:
  - `../api/cetus/analysis/LoopParallelizationPass.html`
  - `../api/cetus/analysis/LoopTools.html`

## Related Publications

The parallel-aware tiling implementation is based on established cache optimization techniques:

- Lam, M. S., Rothberg, E. E., & Wolf, M. E. (1991). The cache performance and optimizations of blocked algorithms. ACM SIGPLAN Notices, 26(4), 63-74.
- McKinley, K. S., Carr, S., & Tseng, C. W. (1996). Improving data locality with loop transformations. ACM Transactions on Programming Languages and Systems (TOPLAS), 18(4), 424-453.

