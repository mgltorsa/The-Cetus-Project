package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CetusAnnotation;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IRTools;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.MinMaxExpression;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.Traversable;
import cetus.utils.VariableDeclarationUtils;

public class Tiler {

    public static final String CROSS_TILE_SUFFIX = "_cetus_cross";
    public static final String IN_TILE_PREFIX = "cetus_tile_";

    public static ForLoop getFarthestAncestorLoop(ForLoop loop) {
        ForLoop farthestAncestor = loop;
        ForLoop currentAncestor = IRTools.getAncestorOfType(loop, ForLoop.class);
        while (currentAncestor != null) {
            farthestAncestor = currentAncestor;
            currentAncestor = IRTools.getAncestorOfType(currentAncestor, ForLoop.class);
        }
        return farthestAncestor;
    }

    public static TiledLoop tile(SymbolTable variableDeclarationSpace, ForLoop outermostLoop, Expression tileSize,
            int targetLoopPos, List<DependenceVector> dependenceVectors)
            throws Exception {

        ForLoop tiledLoop = null;
        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(outermostLoop, ForLoop.class).forEachRemaining(loops::add);

        ForLoop targetLoop = loops.get(targetLoopPos);
        ForLoop[] stripminedLoops = stripmining(variableDeclarationSpace, targetLoop, tileSize);
        ForLoop crossStripLoop = (ForLoop) stripminedLoops[0];
        ForLoop inStripLoop = (ForLoop) stripminedLoops[1];

        // The nest root passed in is the tiling unit. Walking to the
        // program-outermost for-loop would pull in an enclosing imperfect
        // loop (e.g. syrk's i around a perfect k,j subnest) and clone it.
        ForLoop farthestAncestorLoop = outermostLoop;
        ForLoop newForLoop = crossStripLoop.clone(false);
        newForLoop.setBody(farthestAncestorLoop.clone(false));

        permuteInCrossStripLoop(newForLoop, inStripLoop, targetLoop);

        tiledLoop = newForLoop.clone(false);

        PrintTools.printlnDebug("New for loop: " + newForLoop.toString());

        List<DependenceVector> newDVS = calculateAfterTilingDVs(dependenceVectors, tiledLoop,
                (Loop) inStripLoop,
                (Loop) crossStripLoop);

        TiledLoop newTiledLoop = new TiledLoop(tiledLoop, newDVS);
        Expression indexVar = LoopTools.getIndexVariable(newTiledLoop);
        newTiledLoop.setTileSize(indexVar, tileSize);
        return newTiledLoop;
    }

    public static boolean isCrossStripLoop(ForLoop loop) {
        if (loop == null) {
            return false;
        }
        Expression indexVar = LoopTools.getIndexVariable(loop);
        return indexVar.toString().endsWith(Tiler.CROSS_TILE_SUFFIX);

    }

    /**
     * Rewrites the direction vectors for the strip-mined nest by Lemma 4
     * (strip-mining) and Lemma 1 (reordering), delegating to
     * {@link DirectionVectorLemmas}. All-nil vectors carry no information
     * and are dropped.
     */
    public static List<DependenceVector> calculateAfterTilingDVs(List<DependenceVector> originalDVs,
            Loop newLoopNest,
            Loop inStripLoop, Loop crossStripLoop) {

        List<DependenceVector> informative = new ArrayList<>();
        for (DependenceVector originalDV : originalDVs) {
            LinkedHashMap<Loop, Integer> directions = originalDV.getDirectionVector();
            boolean allNils = true;
            for (Entry<Loop, Integer> directionEntry : directions.entrySet()) {
                if (directionEntry.getValue() != DependenceVector.nil) {
                    allNils = false;
                    break;
                }
            }
            if (!allNils) {
                informative.add(originalDV);
            }
        }

        List<Loop> newOrder = new ArrayList<>();
        new DFIterator<Loop>(newLoopNest, Loop.class).forEachRemaining(newOrder::add);

        // Resolve the actual loop objects inside the new nest by index name.
        Loop actualIn = lookupByName(newOrder, inStripLoop);
        Loop actualCross = lookupByName(newOrder, crossStripLoop);

        // The in-strip loop keeps the original loop's index symbol, so it
        // stands in for the strip-mined target when reading old directions.
        return DirectionVectorLemmas.stripMine(informative, actualIn,
                actualCross, actualIn, newOrder);
    }

    private static Loop lookupByName(List<Loop> loops, Loop target) {
        String name = DirectionVectorLemmas.loopName(target);
        for (Loop loop : loops) {
            if (name != null && name.equals(DirectionVectorLemmas.loopName(loop))) {
                return loop;
            }
        }
        return target;
    }

    public static ForLoop[] stripmining(SymbolTable symbolTable, ForLoop loop, Expression tileSize)
            throws Exception {
        Statement loopInitialStatement = loop.getInitialStatement();
        if (loopInitialStatement.getChildren().size() > 1) {
            throw new Exception("Loop's initial statement has more than 2 expressions: " + loop.toString());
        }

        if (loopInitialStatement.getChildren().size() == 0) {
            throw new Exception("Loop's initial statement hasno expressions: " + loop.toString());
        }

        Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);

        IDExpression crossIndex = VariableDeclarationUtils
                .getIdentifier(symbolTable, loopSymbol.getSymbolName() + loopSymbol.getSymbolName());

        if (crossIndex == null) {
            crossIndex = VariableDeclarationUtils.declareVariable(symbolTable,
                    loopSymbol.getSymbolName() + CROSS_TILE_SUFFIX);

        }

        Expression strip = tileSize.clone();

        // An IDExpression tile size is an already-declared size variable
        // (e.g. the browser's per-nest symbolic size): use it directly.
        if (!(strip instanceof IntegerLiteral) && !(strip instanceof IDExpression)) {
            IDExpression stripIdentifier = VariableDeclarationUtils.getIdentifier(symbolTable,
                    IN_TILE_PREFIX + loopSymbol.getSymbolName());

            if (stripIdentifier == null) {
                stripIdentifier = VariableDeclarationUtils.declareVariable(symbolTable,
                        IN_TILE_PREFIX + loopSymbol.getSymbolName(), tileSize.clone());

            }

            strip = stripIdentifier;
        }

        ForLoop inStripLoop = createInStripLoop(loop, crossIndex, strip);

        ForLoop crossStripLoop = createCrossStripLoop(loop, strip, crossIndex, inStripLoop);

        return new ForLoop[] { crossStripLoop, inStripLoop };

    }

    public static void permuteInCrossStripLoop(ForLoop crossStripLoop, ForLoop inStripLoop,
            ForLoop originalTargetLoop) {
        // look for the target loop in the cross strip loop
        DFIterator<Traversable> children = new DFIterator<>(crossStripLoop.getBody(), Traversable.class);
        while (children.hasNext()) {
            Traversable child = children.next();
            if (child == null || !(child instanceof ForLoop)) {
                continue;
            }

            ForLoop childLoop = (ForLoop) child;
            Symbol childLoopSymbol = LoopTools.getLoopIndexSymbol(childLoop);

            if (!childLoopSymbol.getSymbolName()
                    .equals(LoopTools.getLoopIndexSymbol(originalTargetLoop).getSymbolName())) {
                continue;
            }

            // replace the target loop with the in strip loop

            ForLoop targetLoop = childLoop;
            Statement newInitStmt = inStripLoop.getInitialStatement().clone();
            Expression newCond = inStripLoop.getCondition().clone();
            Expression newStep = inStripLoop.getStep().clone();

            targetLoop.setInitialStatement(newInitStmt);
            targetLoop.setCondition(newCond);
            targetLoop.setStep(newStep);
        }

    }

    public static void swapIn(ForLoop loop, ForLoop targetLoop) {

        Statement originalInitStm = targetLoop.getInitialStatement().clone();
        Expression originalCond = targetLoop.getCondition().clone();
        Expression originalStep = targetLoop.getStep().clone();

        targetLoop.setInitialStatement(loop.getInitialStatement().clone());
        targetLoop.setCondition(loop.getCondition().clone());
        targetLoop.setStep(loop.getStep().clone());

        loop.setInitialStatement(originalInitStm);
        loop.setCondition(originalCond);
        loop.setStep(originalStep);
    }

    public static ForLoop createInStripLoop(ForLoop loop, Expression stripExpr, Expression newIndexVariable)
            throws Exception {
        Statement originalInitStatement = loop.getInitialStatement();
        List<Traversable> originalInitStatements = originalInitStatement.getChildren();
        if (originalInitStatements.size() > 1) {
            throw new Exception("Loop has more than one initial statment");
        }

        if (originalInitStatements.size() == 0) {
            throw new Exception("Loop has no initial statment");
        }

        Expression originalInitExpr = (Expression) originalInitStatements.get(0);
        Expression originalCondition = loop.getCondition();

        if (!(originalInitExpr instanceof AssignmentExpression)) {
            throw new Exception("Loop's init statement is not an assignment expression");
        }

        if (!(originalCondition instanceof BinaryExpression)) {
            throw new Exception("Loop has no binary expression as original condition");
        }

        AssignmentExpression oriAssignmentExp = (AssignmentExpression) originalInitExpr;
        Expression initLHSExp = oriAssignmentExp.getLHS();
        AssignmentOperator assignmentOperator = oriAssignmentExp.getOperator();

        Expression newLoopInitExp = new AssignmentExpression(initLHSExp.clone(), assignmentOperator,
                stripExpr.clone());
        newLoopInitExp.setParens(false);
        BinaryExpression originalLoopCondition = (BinaryExpression) originalCondition;
        Expression condRHS = originalLoopCondition.getRHS();
        Expression condLHS = originalLoopCondition.getLHS();
        BinaryOperator condOperator = originalLoopCondition.getOperator();

        Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);

        if (!loopSymbol.getSymbolName().equals(condLHS.toString())) {
            throw new Exception("LHS is not a symbol");
        }

        Expression tileSize = Symbolic.add(stripExpr, newIndexVariable);
        Expression loopUpperBound = condRHS.clone();

        MinMaxExpression minMaxExp = new MinMaxExpression(true, loopUpperBound, tileSize);

        Expression newLoopCondition = new BinaryExpression(
                condLHS.clone(), condOperator, minMaxExp);

        Expression newLoopStepExp = loop.getStep().clone();

        Statement newLoopInitStm = loop.getInitialStatement().clone();
        ((Expression) newLoopInitStm.getChildren().get(0)).swapWith(newLoopInitExp);

        Statement newLoopBody = loop.getBody().clone(false);

        ForLoop inStripLoop = new ForLoop(newLoopInitStm, newLoopCondition, newLoopStepExp, newLoopBody);

        return inStripLoop;
    }

    public static ForLoop createCrossStripLoop(ForLoop loop, Expression stripExpr, IDExpression newIndexVariable,
            ForLoop inStripLoop) throws Exception {

        Statement originalInitStatement = loop.getInitialStatement();
        List<Traversable> originalInitStatements = originalInitStatement.getChildren();
        if (originalInitStatements.size() > 1) {
            throw new Exception("Loop has more than one initial statment");
        }

        if (originalInitStatements.size() == 0) {
            throw new Exception("Loop has no initial statment");
        }

        Expression originalInitExpr = (Expression) originalInitStatements.get(0);
        Expression originalCondition = loop.getCondition();

        if (!(originalInitExpr instanceof AssignmentExpression)) {
            throw new Exception("Loop's init statement is not an assignment expression");
        }

        if (!(originalCondition instanceof BinaryExpression)) {
            throw new Exception("Loop has no binary expression as original condition");
        }

        AssignmentExpression oriAssignmentExp = (AssignmentExpression) originalInitExpr;
        Expression initRHSExp = oriAssignmentExp.getRHS();
        AssignmentOperator assignmentOperator = oriAssignmentExp.getOperator();

        // Symbol loopSymbol = newIndexVariable.getSymbol();
        String symbolName = newIndexVariable.getName();
        Expression newLoopInitExp = new AssignmentExpression(newIndexVariable.clone(), assignmentOperator,
                initRHSExp.clone());
        newLoopInitExp.setParens(false);

        BinaryExpression originalLoopCondition = (BinaryExpression) originalCondition;
        Expression condRHS = originalLoopCondition.getRHS();
        Expression condLHS = originalLoopCondition.getLHS();
        BinaryOperator condOperator = originalLoopCondition.getOperator();

        if (symbolName.equals(condLHS.toString())) {
            condLHS = newIndexVariable;

        } else if (symbolName.equals(condRHS.toString())) {
            condRHS = newIndexVariable;

        }

        Expression newLoopCondition = new BinaryExpression(
                newIndexVariable.clone(), condOperator, condRHS.clone());

        Expression stepLHS = newIndexVariable;
        Expression stepRHS = stripExpr;
        Expression newLoopStepExp = new AssignmentExpression(stepLHS.clone(), AssignmentOperator.ADD, stepRHS.clone());

        Statement newLoopInitStm = loop.getInitialStatement().clone();
        ((Expression) newLoopInitStm.getChildren().get(0)).swapWith(newLoopInitExp);

        ForLoop crossStripLoop = new ForLoop(newLoopInitStm, newLoopCondition, newLoopStepExp, inStripLoop);

        PragmaAnnotation tilingAnnotation = new PragmaAnnotation("c_paw_tiling");
        crossStripLoop.annotateBefore(tilingAnnotation);
        return crossStripLoop;
    }

}
