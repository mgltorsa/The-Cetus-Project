package cetus.transforms.paw_tiling.tile_size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;
import cetus.hir.SymbolTable;
import cetus.utils.ArrayUtils;
import cetus.utils.VariableDeclarationUtils;

public class LRWSelectionAlgo implements TileSizeSelectionAlgo {

    private static final int DEFAULT_DATA_SIZE = 10000 * 10000;
    private static final int DEFAULT_TILE_SIZE = 32;

    private long cacheSizeInBits;
    private int cacheLineSizeInBits;

    public LRWSelectionAlgo(long cacheSizeInKiB, int cacheLineSizeInBytes) {
        super();
        this.cacheSizeInBits = cacheSizeInKiB * 1024 * 8; // convert to bits
        this.cacheLineSizeInBits = cacheLineSizeInBytes * 8; // convert to bits
    }

    /**
     * THis methods implements the algorithm to compute the largest square block
     * without self interference
     * From Monica S. Lam, Edward Rothberg, and Michael E. Wolf. The cache
     * performance and optimizations of blocked algorithms.
     */
    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest) {
        DFIterator<ArrayAccess> arrayAccessesIter = new DFIterator<>(loopNest, ArrayAccess.class);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        while (arrayAccessesIter.hasNext()) {
            arrayAccesses.add(arrayAccessesIter.next());
        }
        int numberOfAccesses = arrayAccesses.size();

        if (numberOfAccesses == 0) {
            return computeDefaultTileSizes(loopNest);
        }

        Map<Expression, Expression> possibleTileSizes = new HashMap<>();
        int initialAddress = 0;

        for (ArrayAccess access : arrayAccesses) {
            // Compute the last cache line touched by the access.
            // The array holds arraySize elements; each cache line holds elementsPerLine
            // elements.
            // Thus, the last accessed cache line is given by:

            long largestBlockSize = findLargestSquareBlock(initialAddress, numberOfAccesses, cacheSizeInBits, loopNest,
                    access);

            initialAddress = getLastAddress(initialAddress, loopNest, access);

            for (Expression indexVar : access.getIndices()) {
                Long oldBlock = Long.MAX_VALUE;

                if (possibleTileSizes.containsKey(indexVar)) {
                    oldBlock = largestBlockSize;
                }
                long largestBlock = Math.min(oldBlock, largestBlockSize);
                if (largestBlock <= 1) {
                    continue;
                }
                possibleTileSizes.put(indexVar, new IntegerLiteral(largestBlock));
            }
        }

        return possibleTileSizes;
    }

    private Map<Expression, Expression> computeDefaultTileSizes(ForLoop loopNest) {
        HashMap<Expression, Expression> possibleTileSizes = new HashMap<>();
        DFIterator<ForLoop> loopIter = new DFIterator<>(loopNest, ForLoop.class);
        while (loopIter.hasNext()) {
            ForLoop loop = loopIter.next();
            Expression indexVar = LoopTools.getIndexVariable(loop);
            if (indexVar != null) {
                possibleTileSizes.put(indexVar, new IntegerLiteral(DEFAULT_TILE_SIZE));
            }
        }
        return possibleTileSizes;
    }

    private int getLastAddress(int initialAddress, ForLoop loop, ArrayAccess access) {
        // Each matrix element is 8 bytes, so number of elements per cache line is:
        SymbolTable symbolTable = VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent());

        int elementSize = ArrayUtils.getTypeSizeInBits(access);
        int elementsPerLine = cacheLineSizeInBits / elementSize;

        Expression arraySizeExpr = ArrayUtils.getArraySize(symbolTable, access);
        long arraySize = DEFAULT_DATA_SIZE;

        if (arraySizeExpr == null || !(arraySizeExpr instanceof IntegerLiteral)) {
            try {
                arraySize = ArrayUtils.getArraySizeFromBounds(loop, access);
            } catch (Exception e) {
                arraySize = DEFAULT_DATA_SIZE;
            }
        } else {
            arraySize = ((IntegerLiteral) arraySizeExpr).getValue();
        }

        // Compute the last cache line touched by the access.
        // The array holds arraySize elements; each cache line holds elementsPerLine
        // elements.
        // Thus, the last accessed cache line is given by:
        int lastAddress = initialAddress + (int) ((arraySize - 1) / elementsPerLine);
        return lastAddress;

    }

    /**
     * Returns the largest square block size (i.e. the edge length, in elements)
     * that can be used to multiply matrices without causing self interference
     * in caches. The idea is to choose block dimension b such that the working set
     * (the data for all three matrices) fits in the cache.
     *
     * The typical constraint is:
     * #blocks * b * b * elementSize <= cacheSize.
     *
     * Here allMatricesSize is taken as the total size (in bytes) of one
     * matrix (or equivalently, if the total size is given for three matrices,
     * one should divide by 3 or #blocks).
     * *
     * 
     * @param allMatricesSize the size (in bytes) of one full matrix (for current
     *                        implementation we assume matrices are contiguous)
     * @param cacheSizeInBits the size (in bytes) of the target cache (e.g. L3
     *                        cache)
     *
     * @return the block dimension (number of elements on each side)
     */
    private long findLargestSquareBlock(int initialAddress, int numberOfAccesses, long cacheSizeInBits, ForLoop loop,
            ArrayAccess access) {
        // assume each matrix element is 8 bytes (double precision)
        long elementSizeInBits = ArrayUtils.getTypeSizeInBits(access);

        // Compute maximum block dimension allowed by the cache:
        // block * b * b * elementSize <= cacheSize ==> b <= sqrt(cacheSize/(3*elementSize))
        long maxBlockForCache = (long) Math
                .floor(Math.sqrt((double) cacheSizeInBits / (numberOfAccesses * elementSizeInBits)));

        // Determine the number of elements in the matrix via the ArrayAccess.
        // We use the loop's symbol table to try to retrieve a declared size.
        SymbolTable symbolTable = VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent());
        Expression arraySizeExpr = ArrayUtils.getArraySize(symbolTable, access);
        long numElements;
        if (arraySizeExpr == null || !(arraySizeExpr instanceof IntegerLiteral)) {
            try {
                numElements = ArrayUtils.getArraySizeFromBounds(loop, access);
            } catch (Exception e) {
                numElements = DEFAULT_DATA_SIZE;
            }
        } else {
            numElements = ((IntegerLiteral) arraySizeExpr).getValue();
        }

        // The full matrix requires numElements elements; its (estimated) dimension is:
        long matrixDimension = (long) Math.floor(Math.sqrt(numElements));

        // We cannot choose a block size larger than the matrix.
        long blockDimension = Math.min(maxBlockForCache, matrixDimension);

        long alignment = cacheLineSizeInBits / elementSizeInBits;
        if (alignment > 0) {
            blockDimension = (blockDimension / alignment) * alignment;
            // Ensure blockDimension does not drop below one cache line's worth of elements.
            if (blockDimension < alignment) {
                blockDimension = alignment;
            }
        }
        return blockDimension;
    }
}