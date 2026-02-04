package cetus.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.ForLoop;
import cetus.hir.Loop;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.analysis.ReuseVectorAnalysis.ReuseVector;

public class CacheMissesEquationsAnalysisPass extends AnalysisPass {

    public class CacheMissesEquation {
        public Loop loop;
        public Map<ArrayAccess, ReuseVector> reuseVectorMap;
    }

    private Map<ForLoop, List<CacheMissesEquation>> cacheMissesEquationsByLoops;

    public CacheMissesEquationsAnalysisPass(Program program) {
        super(program);
    }

    @Override
    public String getPassName() {
        return "CacheMissesEquations";
    }

    private List<ForLoop> filterValidLoops(Program program) {
        DFIterator<ForLoop> dfIterator = new DFIterator<>(program, ForLoop.class);
        List<ForLoop> validLoops = new ArrayList<>();
        while (dfIterator.hasNext()) {
            ForLoop loop = dfIterator.next();
            if (!LoopTools.isPerfectNest(loop)) {
                continue;
            }
            if (!LoopTools.isCanonical(loop)) {
                continue;
            }
            if (LoopTools.containsBreakStatement(loop)) {
                continue;
            }
            if (LoopTools.containsFunctionCall(loop)) {
                continue;
            }

            validLoops.add(loop);
        }
        return validLoops;
    }

    private List<ReuseVector> getReuseVectors(ArrayAccess arrayAccess) {
        List<ReuseVector> reuseVectors = new ArrayList<>();
        // DFIterator<ReuseVector> reuseVectorIterator = new DFIterator<>(arrayAccess,
        // ReuseVector.class);
        // while (reuseVectorIterator.hasNext()) {
        // reuseVectors.add(reuseVectorIterator.next());
        // }
        return reuseVectors;
    }

    @Override
    public void start() {
        List<ForLoop> loops = filterValidLoops(program);

        for (ForLoop loop : loops) {
            if (!cacheMissesEquationsByLoops.containsKey(loop)) {
                cacheMissesEquationsByLoops.put(loop, new ArrayList<>());
            }

            List<CacheMissesEquation> loopCacheMissesEquations = cacheMissesEquationsByLoops.get(loop);

            DFIterator<ArrayAccess> arrayAccessIterator = new DFIterator<>(loop, ArrayAccess.class);

            while (arrayAccessIterator.hasNext()) {
                ArrayAccess reference = arrayAccessIterator.next();

                CacheMissesEquation replacementMissesEquation = generateReplacementMissesEquation(reference);
                System.out.println("Generated replacement misses equation: " + replacementMissesEquation);
                loopCacheMissesEquations.add(replacementMissesEquation);

                List<ReuseVector> reuseVectors = getReuseVectors(reference);

                if (reuseVectors.size() == 0) {
                    System.err.println("No reuse vectors found for array access: " + reference);
                    continue;
                }
                for (ReuseVector reuseVector : reuseVectors) {
                    if (reuseVector.reuseValuesMap.size() == 0) {
                        System.err.println("No reuse values found for reuse vector: " + reuseVector);
                        continue;
                    }
                    CacheMissesEquation coldMissesEquation = generateColdMissesEquation(reuseVector);
                    System.out.println("Generated cold misses equation: " + coldMissesEquation);
                    loopCacheMissesEquations.add(coldMissesEquation);
                }
            }
        }
    }

    private CacheMissesEquation generateColdMissesEquation(ReuseVector reuseVector) {
        CacheMissesEquation cacheMissesEquation = new CacheMissesEquation();
        // cacheMissesEquation.loop = reuseVector.loop;
        // cacheMissesEquation.reuseVectorMap = new HashMap<>();
        // cacheMissesEquation.reuseVectorMap.put(reuseVector.reference, reuseVector);
        return cacheMissesEquation;
    }

    private CacheMissesEquation generateReplacementMissesEquation(ArrayAccess reference) {
        CacheMissesEquation cacheMissesEquation = new CacheMissesEquation();
        // cacheMissesEquation.loop = reuseVector.loop;
        // cacheMissesEquation.reuseVectorMap = new HashMap<>();
        // cacheMissesEquation.reuseVectorMap.put(reuseVector.reference, reuseVector);
        return cacheMissesEquation;
    }

}
