package cetus.transforms.paw_tiling.tile_size;

import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.ConditionalExpression;
import cetus.hir.Expression;
import cetus.hir.IntegerLiteral;
import cetus.hir.Symbolic;

/**
 * Parallel load balancing of the tile loop that runs in parallel (thesis
 * sec. 3.3.3, after Pan et al.). Given raw tile size T, trip count I and P
 * processors, the loop is cut into k*P tiles with k = ceil(I / (P*T)):
 *
 * <pre>S = ceil(I / (k * P))</pre>
 *
 * S never exceeds T, and the number of tiles ceil(I / S) never exceeds k*P,
 * so a static schedule gives every thread k tiles. Rounding the quotient
 * down instead leaves a remainder that becomes extra tiles: I=198, P=16
 * gives S=12 and 17 tiles, and one thread then does twice the work of the
 * others. The balanced size is not aligned to the cache line, because
 * rounding it changes the tile count; the parallel tile loop is the
 * outermost one and its tiles are not contiguous in memory anyway.
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
        if (tripCount < processors) {
            return 1;
        }
        long pt = (long) processors * rawTile;
        long tiles = ((tripCount + pt - 1) / pt) * processors; // k*P
        return Math.max((tripCount + tiles - 1) / tiles, 1);
    }

    /**
     * Symbolic path: builds {@code (I + k*P - 1) / (k*P)} with
     * {@code k = (I + P*T - 1) / (P*T)} in integer arithmetic.
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
        // ceil(I / (k*P)) == (I + k*P - 1) / (k*P)
        Expression top = new BinaryExpression(new BinaryExpression(
                tripSimple.clone(), BinaryOperator.ADD, denom.clone()),
                BinaryOperator.SUBTRACT, new IntegerLiteral(1));
        Expression balanced = new BinaryExpression(top,
                BinaryOperator.DIVIDE, denom);
        // A trip count below P makes the quotient 0, and a trip count of 0
        // or less makes the divisor 0. NPB MG runs the same nest on grids
        // of 2 to 258 points, so both happen at run time: a zero step never
        // terminates and a zero divisor traps. Fall back to one iteration
        // per tile there.
        Expression small = new BinaryExpression(tripSimple.clone(),
                BinaryOperator.COMPARE_LT, p.clone());
        return new ConditionalExpression(small, new IntegerLiteral(1), balanced);
    }

    /** Rounds down to a multiple of lineElems, floor one line. */
    public static long alignDown(long value, int lineElems) {
        if (lineElems <= 0 || value < lineElems) {
            return value;
        }
        return (value / lineElems) * lineElems;
    }
}
