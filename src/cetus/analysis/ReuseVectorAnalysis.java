package cetus.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.Program;
import cetus.hir.Symbolic;
import cetus.hir.PrintTools;

public class ReuseVectorAnalysis extends AnalysisPass {

    Logger logger = Logger.getLogger(ReuseVectorAnalysis.class.getName());

    public class ReuseVector {
        public Loop loop;
        public Map<Expression, Expression> reuseValuesMap;
        public List<Expression> orderedIndexVariables;

        public ReuseVector(Loop loop, List<Expression> orderedIndexVariables,
                Map<Expression, Expression> reuseValuesMap) {
            this.loop = loop;
            this.reuseValuesMap = reuseValuesMap;
            this.orderedIndexVariables = orderedIndexVariables;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ReuseVector(");

            for (int i = 0; i < orderedIndexVariables.size(); i++) {
                Expression indexVar = orderedIndexVariables.get(i);
                Expression reuseValue = reuseValuesMap.get(indexVar);
                String stringValue = String.format("%s=%s", indexVar.toString(), reuseValue.toString());
                sb.append(stringValue);
                if (i < orderedIndexVariables.size() - 1) {
                    sb.append(",");
                }
            }

            sb.append(")");

            return sb.toString();
        }
    }

    public static Map<Loop, Map<ArrayAccess, ReuseVector>> reuseVectorMap;

    public ReuseVectorAnalysis(Program program) {
        super(program);
    }

    @Override
    public String getPassName() {
        return "[Reuse Vector Analysis]";
    }

    @Override
    public void start() {
        reuseVectorMap = new HashMap<>();
        PrintTools.printlnDebug("ReuseVectorAnalysis started");

        // Get all the loops in the program
        DFIterator<ForLoop> loopIterator = new DFIterator<ForLoop>(program, ForLoop.class);

        while (loopIterator.hasNext()) {
            ForLoop loop = loopIterator.next();

            if (!LoopTools.isOutermostLoop(loop))
                continue;
            String loopName = LoopTools.getLoopName(loop);

            List<ArrayAccess> arrayAccesses = getArrayAccesses(loop);
            for (ArrayAccess arrayAccess : arrayAccesses) {
                ReuseVector reuseVector = getReuseVector(loop, arrayAccess);
                if(!reuseVectorMap.containsKey(loop)){
                    reuseVectorMap.put(loop, new HashMap<>());
                }
                reuseVectorMap.get(loop).put(arrayAccess, reuseVector);
                System.out.println("ReuseVector for loop " + loopName + "-" + arrayAccess.toString() + ": " + reuseVector.toString());
            }
        }

        PrintTools.printlnDebug("ReuseVectorAnalysis finished");

    }

    private List<ArrayAccess> getArrayAccesses(ForLoop loop) {
        DFIterator<ArrayAccess> arrayAccessIterator = new DFIterator<ArrayAccess>(loop, ArrayAccess.class);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        while (arrayAccessIterator.hasNext()) {
            arrayAccesses.add(arrayAccessIterator.next());
        }
        return arrayAccesses;
    }

    public List<Expression> getOrderedIndexVariables(ForLoop loopNest) {
        List<Expression> orderedIndexVariables = new ArrayList<>();
        DFIterator<ForLoop> loopIterator = new DFIterator<>(loopNest, ForLoop.class);
        while (loopIterator.hasNext()) {
            ForLoop innerLoop = loopIterator.next();
            List<ArrayAccess> arrayAccesses = getArrayAccesses(loopNest);

            Expression indexVar = LoopTools.getIndexVariable(innerLoop);
            if (indexVar != null && !orderedIndexVariables.contains(indexVar)) {
                orderedIndexVariables.add(indexVar);
            }
        }
        return orderedIndexVariables;
    }

    private Set<Expression> getUseSet(Expression expression) {
        Set<Expression> useSet = new HashSet<Expression>();
        DFIterator<Expression> expressionIterator = new DFIterator<>(expression, Expression.class);
        while (expressionIterator.hasNext()) {
            Expression next = expressionIterator.next();
            useSet.add(next);
        }
        return useSet;
    }

    private ReuseVector getReuseVector(ForLoop loopNest, ArrayAccess arrayAccess) {
        List<Expression> orderedIndexVariables = getOrderedIndexVariables(loopNest);

        // Debug feature
        String loopName = LoopTools.getLoopName(loopNest);
        if (loopName.contains("kernel_3mm")) {
            PrintTools.printlnDebug("In matrix kernel");
        }

        // Get the reuse values map
        Map<Expression, Expression> reuseValuesMap = new HashMap<>();
        // Calculate the reuse values for each index variable in the loop nest
        // For each index variable, determine if the arrayAccess is reused across that loop

        // For each index variable, we will check if it appears in the array subscript
        // If it does not appear, then the array is reused across that loop
        // If it appears, then the reuse is broken at that loop

        // Get the list of index variables in order (outermost to innermost)
        for (Expression indexVar : orderedIndexVariables) {
            boolean reusedAcrossLoop = true;

            // Get all index expressions used in the array access
            List<Expression> subscriptList = arrayAccess.getIndices();

            // Check if the index variable is used in any of the subscript expressions
            for (Expression subscript : subscriptList) {
                Set<Expression> useSet = getUseSet(subscript);
                if (useSet.contains(indexVar)) {
                    reusedAcrossLoop = false;
                    break;
                }
            }

            // If reusedAcrossLoop is true, then the array is reused across this loop
            // We can represent this as 1 for reused, 0 for not reused
            // Here, we use Boolean.TRUE/FALSE, but you can use 1/0 or any other representation as needed
            reuseValuesMap.put(indexVar, new IntegerLiteral(reusedAcrossLoop ? 1 : 0));
        }
        
        return new ReuseVector(loopNest, orderedIndexVariables, reuseValuesMap);
    }
}