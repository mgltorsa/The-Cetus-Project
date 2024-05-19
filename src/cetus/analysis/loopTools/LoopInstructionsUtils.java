package cetus.analysis.loopTools;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.BinaryExpression;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Traversable;
import cetus.utils.VariableDeclarationUtils;

public final class LoopInstructionsUtils {

    public static Expression getTotalOfInstructions(ForLoop loopNest) throws Exception {
        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(loops::add);

        Expression loopNestCond = loopNest.getCondition();
        if (!(loopNestCond instanceof BinaryExpression)) {
            throw new Exception("Condition should be a binary expression");
        }

        BinaryExpression cond = (BinaryExpression) loopNestCond;
        Expression RHS = cond.getRHS();
        // Expression totalOfInstructions = RHS;
        long totalOfInstructions = 1;
        if (RHS instanceof IDExpression) {
            Expression declaredValue = VariableDeclarationUtils.getVariableDeclaredValue(
                    VariableDeclarationUtils.getVariableDeclarationSpace((Traversable) loopNest), (IDExpression) RHS);
            totalOfInstructions *= ((IntegerLiteral) declaredValue).getValue();

        }
        for (int i = 1; i < loops.size(); i++) {
            Expression loopCond = loops.get(i).getCondition();
            if ((loopCond instanceof BinaryExpression)) {
                RHS = ((BinaryExpression) loopCond).getRHS();
                Expression declaredValue = RHS;
                if (RHS instanceof IDExpression) {
                    declaredValue = VariableDeclarationUtils.getVariableDeclaredValue(
                            VariableDeclarationUtils.getVariableDeclarationSpace(loops.get(i)), (IDExpression) RHS);

                    totalOfInstructions *= ((IntegerLiteral) declaredValue).getValue();
                }
            }
        }

        return new IntegerLiteral(totalOfInstructions);
    }
}
