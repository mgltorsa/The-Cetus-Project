package cetus.transforms.paw_tiling.tile_size;

import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.Expression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Symbolic;

/**
 * Pan et al.'s parallel load-balancing heuristic (thesis sec. 3.3.3):
 * given raw tile size T, trip count I of the parallel loop, and P
 * processors, the balanced size is
 *
 * <pre>S = I / (ceil(I / (P*T)) * P)</pre>
 *
 * the largest size not exceeding T that makes the number of tiles a
 * multiple of P. The result is aligned DOWN to the cache line (never up),
 * with a floor of one cache line.
 */
public final class BalancedTileCalculator {

    private BalancedTileCalculator() {
    }

    /** Literal fast-path. lineElems <= 0 disables alignment. */
    public static long balancedSize(long tripCount, long rawTile,
            int processors, int lineElems) {
        if (tripCount <= 0 || rawTile <= 0 || processors <= 0) {
            return Math.max(rawTile, 1);
        }
        long pt = (long) processors * rawTile;
        long tiles = (tripCount + pt - 1) / pt; // ceil(I/(P*T))
        long s = tripCount / (tiles * processors);
        if (s < 1) {
            s = 1;
        }
        return alignDown(s, lineElems);
    }

    /**
     * Symbolic path: builds
     * {@code I / (((I + P*T - 1) / (P*T)) * P)} with integer arithmetic.
     * Alignment is applied only when the result simplifies to a literal;
     * otherwise the unaligned symbolic expression is returned (correctness
     * does not depend on alignment).
     */
    public static Expression balancedSize(Expression tripCount,
            Expression rawTile, int processors, int lineElems) {
        Expression tripSimple = Symbolic.simplify(tripCount);
        Expression tileSimple = Symbolic.simplify(rawTile);

        if (tripSimple instanceof IntegerLiteral
                && tileSimple instanceof IntegerLiteral) {
            long s = balancedSize(((IntegerLiteral) tripSimple).getValue(),
                    ((IntegerLiteral) tileSimple).getValue(),
                    processors, lineElems);
            return new IntegerLiteral(s);
        }

        IntegerLiteral p = new IntegerLiteral(processors);
        // P*T
        Expression pt = Symbolic.multiply(p, tileSimple);
        // ceil(I/(P*T)) == (I + P*T - 1) / (P*T) for positive integers
        Expression numer = Symbolic.subtract(
                Symbolic.add(tripSimple, pt), new IntegerLiteral(1));
        // Keep the ceil-division explicit (uninterpreted integer division)
        // so the emitted C computes it at run time.
        Expression tiles = new BinaryExpression(numer.clone(),
                BinaryOperator.DIVIDE, pt.clone());
        Expression denom = new BinaryExpression(tiles,
                BinaryOperator.MULTIPLY, p.clone());
        return new BinaryExpression(tripSimple.clone(),
                BinaryOperator.DIVIDE, denom);
    }

    /** Rounds down to a multiple of lineElems, floor one line. */
    public static long alignDown(long value, int lineElems) {
        if (lineElems <= 0 || value < lineElems) {
            return value;
        }
        return (value / lineElems) * lineElems;
    }
}
