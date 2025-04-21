package cetus.transforms.paw_tiling.tile_size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.Symbolic;
import cetus.utils.ArrayUtils;

public class NTSelectionAlgo implements TileSizeSelectionAlgo {

    private long cacheSizeInBits;
    private int cacheLineSizeInBits;

    public NTSelectionAlgo(long cacheSizeInKiB, int cacheLineSizeInBytes) {
        super();
        this.cacheSizeInBits = cacheSizeInKiB * 1024 * 8; // convert to bits
        this.cacheLineSizeInBits = cacheLineSizeInBytes * 8; // convert to bits
    }

    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest) {
        Map<Expression, Map<ArrayAccess, Expression>> dataLoadedPerLoopIndexVar = getDataLoadedPerArrayAccessPerLoop(
                loopNest);

        Map<Expression, Expression> tileSizes = new LinkedHashMap<>();

        // Get the outermost loop index variable
        DFIterator<ForLoop> loopIter = new DFIterator<>(loopNest, ForLoop.class);
        if (!loopIter.hasNext()) {
            return new HashMap<>();
        }

        while (loopIter.hasNext()) {
            ForLoop loop = loopIter.next();
            Expression outerIndexVar = LoopTools.getIndexVariable(loop);

            // Get all array accesses for the outermost loop
            Map<ArrayAccess, Expression> accessDataMap = dataLoadedPerLoopIndexVar.get(outerIndexVar);

            // Sum the data loaded per iteration of the outermost loop
            Expression totalDataLoaded = new IntegerLiteral(0);
            for (Expression dataLoaded : accessDataMap.values()) {
                totalDataLoaded = Symbolic.add(totalDataLoaded, dataLoaded);
            }

            // Compute the maximum tile size for the outermost loop such that
            // totalDataLoaded * tileSize <= cacheSizeInBits
            // tileSize <= cacheSizeInBits / totalDataLoaded
            Expression maxTileSize = Symbolic.divide(new IntegerLiteral(cacheSizeInBits), totalDataLoaded);

            // Align the tile size to the cache line size (in bits)
            // tileSizeInBits = maxTileSize * totalDataLoaded
            // tileSizeInBitsAligned = (tileSizeInBits / cacheLineSizeInBits) *
            // cacheLineSizeInBits
            Expression tileSizeInBits = Symbolic.multiply(maxTileSize, totalDataLoaded);
            Expression alignedTileSizeInBits = Symbolic.multiply(
                    Symbolic.divide(tileSizeInBits, new IntegerLiteral(cacheLineSizeInBits)),
                    new IntegerLiteral(cacheLineSizeInBits));
            // alignedTileSize = alignedTileSizeInBits / totalDataLoaded
            Expression alignedTileSize = Symbolic.divide(alignedTileSizeInBits, totalDataLoaded);

            // Return a map with the outermost loop index variable and the computed tile
            // size
            tileSizes.put(outerIndexVar, alignedTileSize);
        }

        return tileSizes;
    }

    /**
     * For each loop index variable, computes the amount of data loaded per array
     * access
     * per iteration of that loop. Returns a map: indexVar -> (arrayAccess ->
     * dataLoadedInBits).
     */
    public Map<Expression, Map<ArrayAccess, Expression>> getDataLoadedPerArrayAccessPerLoop(ForLoop loopNest) {
        Map<Expression, Map<ArrayAccess, Expression>> result = new HashMap<>();

        // Collect all index variables in the loop nest (outermost to innermost)
        List<ForLoop> loops = new ArrayList<>();
        List<Expression> indexVars = new ArrayList<>();
        DFIterator<ForLoop> loopIter = new DFIterator<>(loopNest, ForLoop.class);
        while (loopIter.hasNext()) {
            ForLoop loop = loopIter.next();
            loops.add(loop);
            indexVars.add(LoopTools.getIndexVariable(loop));
        }

        // Find all array accesses in the loop nest
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        DFIterator<ArrayAccess> accessIter = new DFIterator<>(loopNest, ArrayAccess.class);
        while (accessIter.hasNext()) {
            arrayAccesses.add(accessIter.next());
        }

        // For each loop index variable (from outermost to innermost)
        for (int idx = 0; idx < indexVars.size(); idx++) {
            Expression indexVar = indexVars.get(idx);
            Map<ArrayAccess, Expression> accessDataMap = new HashMap<>();

            for (ArrayAccess access : arrayAccesses) {
                List<Expression> indices = access.getIndices();
                int typeSizeBits = ArrayUtils.getTypeSizeInBits(access);

                // Find the position of indexVar in the subscript list
                int pos = -1;
                for (int k = 0; k < indices.size(); k++) {
                    if (indices.get(k).toString().equals(indexVar.toString())) {
                        pos = k;
                        break;
                    }
                }

                // If indexVar is not used in this access, assume only one element loaded per
                // iteration
                if (pos == -1) {
                    accessDataMap.put(access, new IntegerLiteral(typeSizeBits));
                    continue;
                }

                // For row-major, the fastest-changing index is the last subscript
                // For each iteration of indexVar, all inner dimensions (pos+1 to end) are
                // traversed
                // So, per iteration of indexVar, the number of elements loaded is the product
                // of inner dimensions
                Expression numElementsExpr = new IntegerLiteral(1);

                for (int k = pos + 1; k < indices.size(); k++) {
                    // Try to get the trip count of the inner loop corresponding to this index
                    if (k < loops.size()) {
                        ForLoop innerLoop = loops.get(k);
                        Expression tripCount = computeTripCount(innerLoop);
                        numElementsExpr = Symbolic.multiply(numElementsExpr, tripCount);
                    } else {
                        numElementsExpr = Symbolic.multiply(numElementsExpr, new IntegerLiteral(1));
                    }
                }

                Expression dataLoadedExpr = Symbolic.multiply(new IntegerLiteral(typeSizeBits), numElementsExpr);
                accessDataMap.put(access, dataLoadedExpr);
            }
            result.put(indexVar, accessDataMap);
        }
        return result;
    }

    public static Map<Expression, Object> getLoopCountsPerLoop(Loop loopNest) {
        Map<Expression, Object> loopTripCountMap = new LinkedHashMap<>();

        DFIterator<ForLoop> loopIterator = new DFIterator<>(loopNest, ForLoop.class);

        while (loopIterator.hasNext()) {
            ForLoop loop = loopIterator.next();
            Expression tripCount = computeTripCount(loop);

            Expression loopIndex = LoopTools.getIndexVariable(loop);
            loopTripCountMap.put(loopIndex, tripCount);
        }

        return loopTripCountMap;
    }

    private static Expression computeTripCount(ForLoop loop) {
        Expression initExpr = LoopTools.getLowerBoundExpression(loop);
        Expression boundExpr = LoopTools.getUpperBoundExpression(loop);
        Expression incExpr = LoopTools.getIncrementExpression(loop);

        return computeTripCount(initExpr, boundExpr, incExpr);
    }

    /**
     * Computes the trip count given init, bound, and increment expressions.
     * 
     * @param init  Initial value of loop.
     * @param bound Upper bound of loop.
     * @param inc   Increment expression.
     * @return Trip count as Long (if computable) or a symbolic Expression.
     */
    private static Expression computeTripCount(Expression init, Expression bound, Expression inc) {
        try {
            if (init instanceof IntegerLiteral && bound instanceof IntegerLiteral && inc instanceof IntegerLiteral) {
                long initVal = ((IntegerLiteral) init).getValue();
                long boundVal = ((IntegerLiteral) bound).getValue();
                long incVal = ((IntegerLiteral) inc).getValue();

                if (incVal == 0)
                    return new IntegerLiteral(0L);

                long tripCount = Math.max((boundVal - initVal + incVal - 1) / incVal, 0);
                return new IntegerLiteral(tripCount);
            } else {
                // Return symbolic trip count expression: (bound - init) / inc
                Expression numerator = Symbolic.subtract(bound, init);
                return Symbolic.divide(numerator, inc);
            }
        } catch (Exception e) {
            return new IntegerLiteral(0); // Fallback for any parsing issue
        }
    }

}
