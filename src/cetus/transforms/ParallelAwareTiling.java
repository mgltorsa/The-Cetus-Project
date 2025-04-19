package cetus.transforms;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import cetus.analysis.LoopTools;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CodeAnnotation;
import cetus.hir.ConditionalExpression;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.MinMaxExpression;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.utils.VariableDeclarationUtils;

public class ParallelAwareTiling extends TransformPass {

    private static final String TILE_PREFIX = "T";

    private static final CodeAnnotation requiredCodeAnnotation = new CodeAnnotation(
            "#define MIN(a,b) ((a)<(b)?(a):(b))\n" +
                    "#define MAX(a,b) ((a)>(b)?(a):(b))\n" +
                    "#define ABS(x) ((x)<0?-(x):(x))\n" +
                    "#define CEIL(x) ((int)((x)+0.5))\n" +
                    "#define FLOOR(x) ((int)((x)-0.5))\n" +
                    "#define ROUND(x) ((int)((x)+0.5))\n");

    public ParallelAwareTiling(Program program) {
        super(program);
    }

    private boolean isValidForTiling(ForLoop loop) {
        // Check if the the loop is perfectly nested, if it has no branches, and if it
        // has clear boundaries

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
            if (!isValidForTiling(loop) || !LoopTools.isOutermostLoop(loop))
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

        PrintTools.printlnDebug("Adding math library to the program.");
        for (Traversable t : program.getChildren()) {
            if (!(t instanceof TranslationUnit)) {
                continue;
            }
            TranslationUnit tu = (TranslationUnit) t;

            tu.addDeclarationFirst(new AnnotationDeclaration(requiredCodeAnnotation));
        }

        List<ForLoop> validLoops = filterValidLoopNests();
        boolean hasTilableLoops = !validLoops.isEmpty();
        if (!hasTilableLoops) {
            PrintTools.printlnDebug("No tilable loops found.");
            return;
        }

        for (ForLoop outermostLoop : validLoops) {
            // Perform tiling transformation on the loop
            // This is a placeholder for the actual implementation
            PrintTools.printlnDebug("Tiling loop: " + outermostLoop);
            SymbolTable outermostSymbolTable = VariableDeclarationUtils
                    .getVariableDeclarationSpace(outermostLoop.getParent());

            DFIterator<ForLoop> nestedLoopIterator = new DFIterator<>(outermostLoop, ForLoop.class);

            try {
                while (nestedLoopIterator.hasNext()) {
                    ForLoop targetLoop = nestedLoopIterator.next();

                    if (!isValidForTiling(targetLoop)) {
                        PrintTools.printlnDebug("Skipping non-tilable loop: " + targetLoop);
                        continue;
                    }
                    tile(outermostSymbolTable, outermostLoop, new IntegerLiteral(32)); // Example tile size
                }

            } catch (Exception e) {
                e.printStackTrace();
                PrintTools.print(
                        String.format("Error processing loop %s: %s", outermostLoop.toString(), e.getMessage()), 0);
            }
        }
    }

    public void tile(SymbolTable symbolTable, ForLoop loop, Expression tileSize) throws Exception {
        // Placeholder for the actual tiling implementation
        // This is where the tiling transformation would be applied to the loop
        PrintTools.printlnDebug("Tiling loop: " + loop + " with tile size: " + tileSize);

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
                    loopSymbol.getSymbolName() + loopSymbol.getSymbolName());

        }

        IDExpression stripIdentifier = VariableDeclarationUtils.getIdentifier(symbolTable,
                TILE_PREFIX + loopSymbol.getSymbolName());

        if (stripIdentifier == null) {
            stripIdentifier = VariableDeclarationUtils.declareVariable(symbolTable,
                    TILE_PREFIX + loopSymbol.getSymbolName(), tileSize);

        }
        ForLoop inStripLoop = createInStripLoop(loop, crossIndex, stripIdentifier);
        ForLoop crossStripLoop = createCrossStripLoop(loop, stripIdentifier, crossIndex, inStripLoop);
        replaceLoop(loop, crossStripLoop);
    }

    public ForLoop createInStripLoop(ForLoop loop, Expression stripExpr, IDExpression newIndexVariable)
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
                newIndexVariable.clone());

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

    public ForLoop createCrossStripLoop(ForLoop loop, Expression stripExpr, IDExpression newIndexVariable,
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

        return crossStripLoop;
    }

    private void replaceLoop(ForLoop oldLoop, ForLoop newLoop) {
        PrintTools.printlnDebug("Replacing loop: " + oldLoop + " with: " + newLoop);
        Traversable parent = oldLoop.getParent();
        int oldLoopIdx = parent.getChildren().indexOf(oldLoop);
        parent.setChild(oldLoopIdx, newLoop);
    }

    @Override
    public String getPassName() {
        return "[Parallel-Aware Tiling]";
    }

}