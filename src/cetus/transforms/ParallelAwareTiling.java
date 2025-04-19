package cetus.transforms;

import java.util.ArrayList;
import java.util.List;

import cetus.analysis.LoopTools;
import cetus.hir.DFIterator;
import cetus.hir.Declaration;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IRTools;
import cetus.hir.Identifier;
import cetus.hir.IntegerLiteral;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.SymbolTools;

public class ParallelAwareTiling extends TransformPass {

    public ParallelAwareTiling(Program program) {
        super(program);
    }

    private boolean isValidForTiling(ForLoop loop) {
        // Check if the the loop is perfectly nested, if it has no branches, and if it
        // has clear boundaries

        if (!LoopTools.isOutermostLoop(loop))
            return false;

        if (!LoopTools.isPerfectNest(loop))
            return false;

        if (!LoopTools.isCanonical(loop))
            return false;

        return true;
    }

    private List<ForLoop> filterValidLoopNests() {
        List<ForLoop> validLoops = new ArrayList<>();

        DFIterator<ForLoop> loopIterator = new DFIterator<>(program, ForLoop.class);
        while (loopIterator.hasNext()) {
            ForLoop loop = loopIterator.next();
            // Check if the loop is valid for tiling
            if (!isValidForTiling(loop))
                continue;

            validLoops.add(loop);
        }

        return validLoops;
    }

    @Override
    public void start() {
        // Implementation of the parallel-aware tiling transformation
        // This is a placeholder for the actual implementation
        PrintTools.printlnDebug("Starting parallel-aware tiling transformation...");
        List<ForLoop> validLoops = filterValidLoopNests();
        boolean hasTilableLoops = !validLoops.isEmpty();
        if (!hasTilableLoops) {
            PrintTools.printlnDebug("No tilable loops found.");
            return;
        }

        for (ForLoop loop : validLoops) {
            // Perform tiling transformation on the loop
            // This is a placeholder for the actual implementation
            PrintTools.printlnDebug("Tiling loop: " + loop);
            tile(loop, new IntegerLiteral(32)); // Example tile size
        }
    }

    public void tile(ForLoop loop, Expression tileSize) {
        // Placeholder for the actual tiling implementation
        // This is where the tiling transformation would be applied to the loop
        PrintTools.printlnDebug("Tiling loop: " + loop + " with tile size: " + tileSize);
        
        Statement inStripInit = loop.getInitialStatement().clone();
        Expression condition = loop.getCondition().clone();
        Expression inStripCond = loop.getCondition().clone();
        Expression inStripStep =loop.getStep().clone();
        
        ForLoop inStripLoop = new ForLoop(inStripInit, inStripCond, inStripStep, loop.getBody().clone());

        Identifier crossStripIdentifier = getCrossStripIdentifier(loop); 
        Statement crossStripInit = loop.getInitialStatement().clone();
        Expression originalCondtion = loop.getCondition().clone();
        Expression crossTileStep = tileSize;
        Statement crossStripBody = inStripLoop;
        ForLoop crossStripLoop = new ForLoop(crossStripInit, originalCondtion, crossTileStep, crossStripBody);
        replaceLoop(loop, crossStripLoop);
    }

    

    private Identifier getCrossStripIdentifier(ForLoop loop) {
        // Placeholder for the actual implementation to get the cross strip identifier
        // This is where the logic to determine the cross strip identifier would go
        PrintTools.printlnDebug("Getting cross strip identifier for loop: " + loop);
        
        //check if init statement is a declaration
        Statement init = loop.getInitialStatement();
        return null;

    }

    private Statement getInStripInitStatement(ForLoop loop) {
        
        return loop.getInitialStatement().clone();
    }

    private void replaceLoop(ForLoop oldLoop, ForLoop newLoop) {
        PrintTools.printlnDebug("Replacing loop: " + oldLoop + " with: " + newLoop);
        
    }

    @Override
    public String getPassName() {
        return "[Parallel-Aware Tiling]";
    }

}