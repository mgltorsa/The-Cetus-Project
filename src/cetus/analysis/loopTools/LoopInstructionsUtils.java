package cetus.analysis.loopTools;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.BinaryExpression;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.Symbolic;
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
        Expression totalOfInstructions = RHS;
        if (RHS instanceof IDExpression) {
            totalOfInstructions = VariableDeclarationUtils.getVariableDeclaredValue(
                    VariableDeclarationUtils.getVariableDeclarationSpace((Traversable) loopNest), (IDExpression) RHS);

        }
        for (int i = 1; i < loops.size(); i++) {
            Expression loopCond = loops.get(i).getCondition();
            if ((loopCond instanceof BinaryExpression)) {
                RHS = ((BinaryExpression) loopCond).getRHS();
                Expression declaredValue = RHS;
                if (RHS instanceof IDExpression) {
                    declaredValue = VariableDeclarationUtils.getVariableDeclaredValue(
                            VariableDeclarationUtils.getVariableDeclarationSpace(loops.get(i)), (IDExpression) RHS);

                }
                totalOfInstructions = Symbolic.multiply(totalOfInstructions, declaredValue);
            }
        }

        return totalOfInstructions;
    }
}
