package cetus.utils.reuseAnalysis.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.FloatLiteral;
import cetus.hir.Identifier;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.utils.VariableDeclarationUtils;
import cetus.utils.reuseAnalysis.data.DataReuseAnalysis;

public class TemporalReuseAnalysis implements ReuseAnalysis {

    private static final int DEFAULT_CACHE_SIZE = 64;

    /**
     * It will use the
     * {@link TemporalReuseAnalysis#getReuseAnalysis(Loop, int)} with the
     * default cache size value: {@value #DEFAULT_CACHE_SIZE}
     * 
     * @see SimpleReuseAnalysis#runReuseAnalysis()
     * 
     * @return - Order of the loops in the nest for max reusability. From the less
     *         reuse to the max.
     * 
     */

    @Override
    public DataReuseAnalysis getReuseAnalysis(Loop loopNest) {
        return getReuseAnalysis(loopNest, DEFAULT_CACHE_SIZE);
    }

    public static Integer getArrayAccessSize(ArrayAccess arrayAccess) {
        int size = 1;
        for (Expression index : arrayAccess.getIndices()) {
            if (index instanceof IntegerLiteral) {
                size *= ((IntegerLiteral) index).getValue();
            } else if (index instanceof FloatLiteral) {
                size *= (int) ((FloatLiteral) index).getValue();
            } else if (index instanceof Identifier) {
                Expression declaredValue = VariableDeclarationUtils.getVariableDeclaredValue(
                        VariableDeclarationUtils.getVariableDeclarationSpace(index), (Identifier) index);
                if (declaredValue instanceof IntegerLiteral) {
                    size *= ((IntegerLiteral) declaredValue).getValue();
                } else if (declaredValue instanceof FloatLiteral) {
                    size *= (int) ((FloatLiteral) declaredValue).getValue();
                } else {
                    size *= 1;

                }
            } else if (index instanceof Expression) {
                size *= 1;
            } else {
                throw new RuntimeException("Unknown index type: " + index.getClass());
            }
        }
        return size;
    }

    private static List<ArrayAccess> getRelatedArrayAccesses(Loop loop) {
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        DFIterator<ArrayAccess> arrayAccessIterator = new DFIterator<ArrayAccess>(loop, ArrayAccess.class);
        while (arrayAccessIterator.hasNext()) {
            ArrayAccess arrayAccess = arrayAccessIterator.next();
            for (Expression index : arrayAccess.getIndices()) {
                Expression loopIndex = LoopTools.getIndexVariable(loop);
                boolean isRelated = index.equals(loopIndex);
                if (isRelated) {
                    arrayAccesses.add(arrayAccess);

                }
            }
        }
        return arrayAccesses;
    }

    @Override
    public DataReuseAnalysis getReuseAnalysis(Loop loopNest, int cacheSize) {

        // for each loop identify array access using loop index variable
        Map<Expression, Integer> loopCosts = new HashMap<>();
        DFIterator<Loop> loopIterator = new DFIterator<Loop>(loopNest, Loop.class);
        while (loopIterator.hasNext()) {
            Loop loop = loopIterator.next();
            List<ArrayAccess> arrayAccesses = getRelatedArrayAccesses(loop);
            Integer loopSize = 1;
            for (ArrayAccess arrayAccess : arrayAccesses) {
                Integer arrayAccessSize = getArrayAccessSize(arrayAccess);
                loopSize += arrayAccessSize;
            }
            loopCosts.put(LoopTools.getIndexVariable(loop), loopSize);
        }

        // order loop cost
        List<Map.Entry<Expression, Integer>> loopCostsList = new ArrayList<>(loopCosts.entrySet());
        Collections.sort(loopCostsList, new Comparator<Map.Entry<Expression, Integer>>() {
            @Override
            public int compare(Map.Entry<Expression, Integer> o1, Map.Entry<Expression, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        });

        // SimpleDataReuseAnalysis reuseAnalysis = new SimpleDataReuseAnalysis(loopNest,
        // loopCosts, LoopNestOrder);

        // return reuseAnalysis;

        return null;

    }

}
