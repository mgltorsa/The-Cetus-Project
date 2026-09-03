package cetus.transforms.paw_tiling.tile_size;

import cetus.hir.Expression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Symbolic;

/**
 * Fold compile-time integer expressions (including PolyBench's
 * {@code (N+0)} array dimensions) to a positive literal.
 */
public final class LiteralExpr {

    private LiteralExpr() {
    }

    /**
     * @return the positive integer value of {@code e} after symbolic
     *         simplification, or {@code -1} when it is not a positive
     *         compile-time constant
     */
    public static long asPositiveLiteral(Expression e) {
        if (e == null) {
            return -1;
        }
        try {
            Expression simplified = Symbolic.simplify(e.clone());
            if (simplified instanceof IntegerLiteral) {
                long value = ((IntegerLiteral) simplified).getValue();
                return value > 0 ? value : -1;
            }
        } catch (Exception ignored) {
            return -1;
        }
        return -1;
    }
}
