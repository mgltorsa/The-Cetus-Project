package cetus.transforms.paw_tiling.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IntegerLiteral;
import cetus.hir.PrintTools;
import cetus.hir.Symbolic;

/**
 * Phase-1 reuse analysis of the PAW algorithm (thesis sec. 3.2): ranks the
 * loops of a perfect nest by decreasing temporal reuse, producing the browse
 * order for candidate tiled versions.
 *
 * A loop carries temporal reuse for a reference when the loop's index
 * appears in none of that reference's subscripts (the same elements are
 * re-touched on every iteration -- Kennedy/McKinley). The score of a loop
 * aggregates, over reference groups it does not subscript, the group's data
 * footprint (product of the trip counts of the loops that do appear in the
 * subscripts). Temporal reuse has priority (thesis requirement); ties are
 * broken innermost-first, which reproduces the thesis matmul order
 * (k, j, i).
 *
 * Reference groups fold accesses to the same array whose subscripts differ
 * only by constant offsets (group reuse counted once).
 */
public class ReuseOrderAnalyzer {

    /** Numeric stand-in for symbolic trip counts when ranking. */
    private static final long DEFAULT_TRIP = 1000L;

    private ReuseOrderAnalyzer() {
    }

    /** Loops of the nest, outermost to innermost. */
    public static List<ForLoop> nestLoops(ForLoop nest) {
        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(nest, ForLoop.class).forEachRemaining(loops::add);
        return loops;
    }

    /**
     * Loops sorted by decreasing temporal reuse; equal scores keep
     * innermost-first order.
     */
    public static List<ForLoop> reuseOrder(ForLoop nest) {
        List<ForLoop> loops = nestLoops(nest);
        final Map<ForLoop, Double> scores = new LinkedHashMap<>();
        final Map<ForLoop, Integer> depth = new LinkedHashMap<>();
        for (int i = 0; i < loops.size(); i++) {
            ForLoop loop = loops.get(i);
            double score = numericScore(loop, nest);
            scores.put(loop, score);
            depth.put(loop, i);
            Expression indexVar = LoopTools.getIndexVariable(loop);
            PrintTools.printlnDebug("[paw-reuse] temporalScore("
                    + (indexVar == null ? "?" : indexVar.toString())
                    + ")=" + score
                    + " symbolic=" + temporalScore(loop, nest)
                    + " depth=" + i);
        }
        List<ForLoop> order = new ArrayList<>(loops);
        order.sort(new Comparator<ForLoop>() {
            @Override
            public int compare(ForLoop a, ForLoop b) {
                int byScore = Double.compare(scores.get(b), scores.get(a));
                if (byScore != 0) {
                    return byScore;
                }
                // innermost (deeper) first
                return Integer.compare(depth.get(b), depth.get(a));
            }
        });
        StringBuilder orderLog = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                orderLog.append(',');
            }
            Expression indexVar = LoopTools.getIndexVariable(order.get(i));
            orderLog.append(indexVar == null ? "?" : indexVar.toString());
            orderLog.append('=').append(scores.get(order.get(i)));
        }
        PrintTools.printlnDebug("[paw-reuse] browseOrder=[" + orderLog + "]"
                + " groups=" + referenceGroups(nest).size()
                + " resident=" + residentReference(nest));
        return order;
    }

    /**
     * Symbolic temporal-reuse score: sum over reference groups whose
     * subscripts do not contain the loop's index of the group's footprint.
     */
    public static Expression temporalScore(ForLoop loop, ForLoop nest) {
        Expression score = new IntegerLiteral(0);
        for (ArrayAccess rep : groupRepresentatives(nest)) {
            if (!subscriptsContainIndex(rep, loop)) {
                score = Symbolic.add(score, footprint(rep, nest));
            }
        }
        return Symbolic.simplify(score);
    }

    /** Numeric estimate of {@link #temporalScore} for sorting (symbolic
     * trip counts replaced by a fixed default). */
    public static double numericScore(ForLoop loop, ForLoop nest) {
        double score = 0;
        for (ArrayAccess rep : groupRepresentatives(nest)) {
            if (!subscriptsContainIndex(rep, loop)) {
                score += numericFootprint(rep, nest);
            }
        }
        return score;
    }

    /**
     * Reference groups of the nest: accesses to the same array whose
     * subscript expressions are equal after dropping constant addends.
     */
    public static List<List<ArrayAccess>> referenceGroups(ForLoop nest) {
        Map<String, List<ArrayAccess>> groups = new LinkedHashMap<>();
        DFIterator<ArrayAccess> iter = new DFIterator<>(nest, ArrayAccess.class);
        while (iter.hasNext()) {
            ArrayAccess access = iter.next();
            groups.computeIfAbsent(signature(access), k -> new ArrayList<>())
                    .add(access);
        }
        return new ArrayList<>(groups.values());
    }

    /** One representative access per reference group. */
    public static List<ArrayAccess> groupRepresentatives(ForLoop nest) {
        List<ArrayAccess> reps = new ArrayList<>();
        for (List<ArrayAccess> group : referenceGroups(nest)) {
            reps.add(group.get(0));
        }
        return reps;
    }

    /**
     * The reference with the most temporal reuse: among group
     * representatives missing at least one loop index (so some loop
     * re-touches them), the one with the largest footprint -- the working
     * set that tiling should keep resident (thesis sec. 3.3.2, e.g.
     * {@code b[k][j]} in matmul). Null when every reference uses every
     * index.
     */
    public static ArrayAccess residentReference(ForLoop nest) {
        ArrayAccess best = null;
        double bestFootprint = -1;
        List<ForLoop> loops = nestLoops(nest);
        for (ArrayAccess rep : groupRepresentatives(nest)) {
            boolean reused = false;
            for (ForLoop loop : loops) {
                if (!subscriptsContainIndex(rep, loop)) {
                    reused = true;
                    break;
                }
            }
            if (!reused) {
                continue;
            }
            double fp = numericFootprint(rep, nest);
            if (fp > bestFootprint) {
                bestFootprint = fp;
                best = rep;
            }
        }
        return best;
    }

    /** True when the loop's index symbol appears in any subscript. */
    public static boolean subscriptsContainIndex(ArrayAccess access, ForLoop loop) {
        Expression indexVar = LoopTools.getIndexVariable(loop);
        if (indexVar == null) {
            return false;
        }
        String name = indexVar.toString();
        for (int i = 0; i < access.getNumIndices(); i++) {
            if (expressionContainsName(access.getIndex(i), name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean expressionContainsName(Expression expr, String name) {
        if (expr instanceof IDExpression && expr.toString().equals(name)) {
            return true;
        }
        DFIterator<IDExpression> ids = new DFIterator<>(expr, IDExpression.class);
        while (ids.hasNext()) {
            if (ids.next().toString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Product of trip counts of the loops whose indices appear in the
     * reference's subscripts (symbolic). */
    public static Expression footprint(ArrayAccess access, ForLoop nest) {
        Expression result = new IntegerLiteral(1);
        for (ForLoop loop : nestLoops(nest)) {
            if (subscriptsContainIndex(access, loop)) {
                result = Symbolic.multiply(result, tripCount(loop));
            }
        }
        return Symbolic.simplify(result);
    }

    private static double numericFootprint(ArrayAccess access, ForLoop nest) {
        double result = 1;
        for (ForLoop loop : nestLoops(nest)) {
            if (subscriptsContainIndex(access, loop)) {
                result *= numericTrip(loop);
            }
        }
        return result;
    }

    /** Trip count {@code ceil((ub - lb + 1) / step)} (symbolic-safe). */
    public static Expression tripCount(ForLoop loop) {
        try {
            Expression lb = LoopTools.getLowerBoundExpression(loop);
            Expression ub = LoopTools.getUpperBoundExpression(loop);
            Expression inc = LoopTools.getIncrementExpression(loop);
            if (lb == null || ub == null || inc == null) {
                return new IntegerLiteral(0);
            }
            if (lb instanceof IntegerLiteral && ub instanceof IntegerLiteral
                    && inc instanceof IntegerLiteral) {
                long l = ((IntegerLiteral) lb).getValue();
                long u = ((IntegerLiteral) ub).getValue();
                long s = ((IntegerLiteral) inc).getValue();
                if (s == 0) {
                    return new IntegerLiteral(0);
                }
                return new IntegerLiteral(Math.max((u - l + s) / s, 0));
            }
            Expression span = Symbolic.add(Symbolic.subtract(ub, lb),
                    new IntegerLiteral(1));
            return Symbolic.divide(span, inc);
        } catch (Exception e) {
            return new IntegerLiteral(0);
        }
    }

    /** Numeric trip count for ranking; symbolic trips use a default. */
    public static long numericTrip(ForLoop loop) {
        Expression trip = tripCount(loop);
        if (trip instanceof IntegerLiteral) {
            long v = ((IntegerLiteral) trip).getValue();
            return v > 0 ? v : DEFAULT_TRIP;
        }
        return DEFAULT_TRIP;
    }

    /** Group signature: array name + subscripts with constant addends
     * dropped, so {@code A[i][j]} and {@code A[i][j+1]} share a group. */
    private static String signature(ArrayAccess access) {
        StringBuilder sb = new StringBuilder();
        sb.append(access.getArrayName().toString());
        for (int i = 0; i < access.getNumIndices(); i++) {
            sb.append('[');
            sb.append(normalizeSubscript(access.getIndex(i)));
            sb.append(']');
        }
        return sb.toString();
    }

    private static String normalizeSubscript(Expression subscript) {
        try {
            Expression simplified = Symbolic.simplify(subscript.clone());
            List<Expression> terms = Symbolic.getTerms(simplified);
            if (terms == null || terms.isEmpty()) {
                return simplified instanceof IntegerLiteral
                        ? "c" : simplified.toString();
            }
            List<String> keep = new ArrayList<>();
            for (Expression term : terms) {
                if (!(term instanceof IntegerLiteral)) {
                    keep.add(term.toString());
                }
            }
            if (keep.isEmpty()) {
                return "c";
            }
            keep.sort(null);
            return String.join("+", keep);
        } catch (Exception e) {
            return subscript.toString();
        }
    }
}
