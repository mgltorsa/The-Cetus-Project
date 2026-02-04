# Subscripted Subscript Analysis

## Table of Contents

- [Context and Motivation](#context-and-motivation)
- [When to Use](#when-to-use)
- [How to Run](#how-to-run)
  - [Prerequisites](#prerequisites)
  - [Downloading and Running Cetus (on this branch)](#downloading-and-running-cetus-on-this-branch)
  - [Enabling the Subscripted Subscript Analysis Pass](#enabling-the-subscripted-subscript-analysis-pass)
- [Examples](#examples)
- [Technical Details](#technical-details)
- [Benchmarking](#benchmarking)
- [Troubleshooting](#troubleshooting)
- [API Reference](#api-reference)
- [Related Publications](#related-publications)

## Context and Motivation

We have developed a new analysis technique for the automatic parallelization of subscripted
subscript loops. The technique analyzes loops that define and/or modify the subscript array
and determines array properties, which is sufficient to parallelize a class of subscripted
subscripts. This repository contains the source codes of not just the actual technique but
also of the benchmarks used to evaluate the capabilities of the technique. The
technique has been described in detail in the publications listed below.

## When to Use

Subscripted subscript analysis is most effective for:

- Loops containing array accesses with subscripted subscripts (e.g., `A[B[i]]`)
- Loops that define or modify the subscript array within the loop body
- Code patterns where traditional dependence analysis cannot determine parallelizability
- Applications with indirect array access patterns that need automatic parallelization

## How to Run

### Prerequisites

#### Software

- Linux (OS tested with: CentOS v7.4, Ubuntu v22.04)
- GNU C Compiler (GCC) v4.8.5 and above
- Python v3.8.0 and above
- OpenMP v4.0 and above
- gfortran

#### Python packages required

1. `subprocess`
2. `re`
3. `os`

#### Hardware

- Machine with x86-64 processors (preferably Sky Lake and beyond)
- ~4GB of disk space
- At least 8GB of memory

### Downloading and Running Cetus (on this branch)

1. Download Cetus through the "Download Code" (green button) above or through `wget`.
2. Unpack the ZIP/TAR file and navigate to the main directory.
3. Run the build script:
   ```bash
   ./build.sh bin
   ```
4. The Cetus executable is created in the `bin` directory.
5. Copy and paste the `cetus` executable in your working directory (or add `bin` to your `PATH`).
6. Run the `cetus` executable to see the list of available options and how to enable them.
7. To compile a source code using Cetus through the command line, type:
   ```bash
   ./cetus [options] [C FILE]
   # Example:
   ./cetus -parallelize-loops=2 foo.c
   ```
8. The output file after running Cetus is made available in the `cetus_output` folder
   in your working directory.
9. Inside the `resource` directory, you can find example programs.

### Enabling the Subscripted Subscript Analysis Pass

1. **Source code location**  
   The source code of the pass can be found in:

   ```text
   /src/cetus/analysis/SubscriptedSubscriptAnalysis.java
   ```

2. **Running the pass**  
   To enable subscripted subscript analysis on an input code, simply type:

   ```bash
   ./cetus -subsub_analysis -normalize-loops foo.c
   ```

## Examples

### Integration Testing

Examples for testing the subscripted subscript analysis pass have been placed in the
`subsub_egs` directory within `integration_test`.

To run the integration tests, use:

```bash
python3 SubSub_integration_test.py
```

The script takes user input and can perform testing on either one or all the test files.

## Technical Details

The subscripted subscript analysis pass analyzes loops that contain array accesses with subscripted subscripts (e.g., `A[B[i]]`). The technique determines array properties by analyzing how the subscript array is defined and modified, enabling automatic parallelization of a class of subscripted subscript patterns that would otherwise be difficult to parallelize.

The analysis integrates with Cetus's existing loop parallelization infrastructure, including:
- **Data Dependence Testing**: Works with `-ddt` to analyze dependencies
- **Loop Parallelization**: Integrates with `-parallelize-loops` to enable parallelization
- **Normalization**: Requires `-normalize-loops` for proper loop structure analysis

## Benchmarking

### Benchmarks for Evaluating the Technique

The benchmarks for evaluation have been placed in the `Evaluation_Benchmarks` directory.
The following benchmarks have been included:

| Code  | Source | Original Source link |
| ------------- | ------------- | ------------- |
| amgmk-v1.0  | CORAL Benchmark Codes | (https://asc.llnl.gov/coral-benchmarks) |
| UA-NPB-1.0.3 | NAS Parallel Benchmarks | (https://github.com/akshay9594/SNU_NPB-1.0.3) |
| CHOLMOD | SuiteSparse | (https://github.com/DrTimothyAldenDavis/SuiteSparse) |
| SDDMM (C version) | Published Paper | (https://github.com/isratnisa/SDDMM_GPU) |

### Running Subscripted Subscript Analysis on the Benchmarks

A Python script by the name `run-cetus.py` has been provided within each benchmark source
code. Build Cetus first and then execute the script `run-cetus.py` to get the Cetus
parallel version of the codes with subscripted subscript analysis:

```bash
python3 run-cetus.py
```

The translated files will be available in the `cetus_output` directory.

**Notes:**

1. For the CHOLMOD (SuiteSparse) benchmark, only the file `cholmod_super_numeric.c`
   is translated. This is due to the sheer number of dependencies present in this benchmark.
   `cholmod_super_numeric.c` contains the actual supernodal Cholesky factorization computation.

2. Use the Makefiles provided within each benchmark to compile and execute the codes. The
   Makefiles need to be modified to compile the Cetus translated version of the source codes.
   Refer to the publications below or the provided links above for more details on how to
   execute the codes.

## Troubleshooting

### Common Issues

#### No Parallelization Applied

**Symptoms**: Loops with subscripted subscripts are not parallelized.  
**Causes**:
- Subscript array properties cannot be determined
- Loop structure not normalized
- Dependencies prevent parallelization

**Solutions**:
- Ensure `-normalize-loops` is enabled
- Check that the subscript array is analyzable
- Review debug output with appropriate verbosity settings

### Debug Options

```bash
# Enable verbose output
./cetus -subsub_analysis -normalize-loops -verbosity=4 foo.c
```

## API Reference

- `SubscriptedSubscriptAnalysis` (analysis pass): `../api/cetus/analysis/SubscriptedSubscriptAnalysis.html`  
- `LoopParallelizationPass` (parallelization driver): `../api/cetus/analysis/LoopParallelizationPass.html`  
- `LoopTools` (loop utilities used by the pass): `../api/cetus/analysis/LoopTools.html`  
- Full Cetus API index: `../api/index.html`

## Related Publications

1. Akshay Bhosale and Rudolf Eigenmann. 2021. On the automatic parallelization of subscripted
   subscript patterns using array property analysis. In Proceedings of the ACM International
   Conference on Supercomputing (ICS '21). Association for Computing Machinery, New York, NY,
   USA, 392–403. (https://doi.org/10.1145/3447818.3460424)

