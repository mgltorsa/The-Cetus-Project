package cetus.analysis.loopTools;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.ArrayAccess;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Traversable;
import cetus.utils.VariableDeclarationUtils;

public final class LoopInstructionsUtils {

    public static long getInstructionValue(ForLoop loopNest, BinaryExpression RHS) {
        BinaryExpression expr = RHS;
        Expression auxRHS = expr.getRHS();
        Expression LHS = expr.getLHS();
        Expression declaredRHS = auxRHS;
        if (auxRHS instanceof IDExpression) {
            declaredRHS = VariableDeclarationUtils.getVariableDeclaredValue(
                    VariableDeclarationUtils.getVariableDeclarationSpace((Traversable) loopNest),
                    (IDExpression) auxRHS);
        }

        Expression declaredLHS = LHS;
        if (LHS instanceof ArrayAccess) {
            LHS = ((ArrayAccess) LHS).getArrayName();
        }
        if (LHS instanceof IDExpression) {
            declaredLHS = VariableDeclarationUtils.getVariableDeclaredValue(
                    VariableDeclarationUtils.getVariableDeclarationSpace((Traversable) loopNest),
                    (IDExpression) LHS);
        }

        long rhsValue = ((IntegerLiteral) declaredRHS).getValue();
        long lhsValue = ((IntegerLiteral) declaredLHS).getValue();

        BinaryOperator op = expr.getOperator();

        long totalOfInstructions = 1;
        if (op == BinaryOperator.ADD) {
            totalOfInstructions *= (lhsValue + rhsValue);
        }

        if (op == BinaryOperator.MULTIPLY) {
            totalOfInstructions *= (lhsValue * rhsValue);
        }

        if (op == BinaryOperator.SUBTRACT) {
            totalOfInstructions *= (lhsValue - rhsValue);
        }

        if (op == BinaryOperator.DIVIDE) {
            totalOfInstructions *= (lhsValue - rhsValue);
        }

        if (op == BinaryOperator.MODULUS) {
            totalOfInstructions *= (lhsValue % rhsValue);
        }

        if (op == BinaryOperator.SHIFT_LEFT) {
            totalOfInstructions *= (lhsValue << rhsValue);
        }

        if (op == BinaryOperator.SHIFT_RIGHT) {
            totalOfInstructions *= (lhsValue >> rhsValue);
        }

        return totalOfInstructions;
    }

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
        if (RHS instanceof BinaryExpression) {
            totalOfInstructions *= getInstructionValue(loopNest, (BinaryExpression) RHS);
        } else if (RHS instanceof IDExpression) {
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
                } else if (RHS instanceof IntegerLiteral) {
                    totalOfInstructions *= ((IntegerLiteral) RHS).getValue();
                } else if (RHS instanceof BinaryExpression) {
                    totalOfInstructions *= getInstructionValue(loops.get(i), (BinaryExpression) RHS);
                }
            }
        }

        return new IntegerLiteral(totalOfInstructions);
    }
}
