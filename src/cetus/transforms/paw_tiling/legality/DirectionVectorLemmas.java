package cetus.transforms.paw_tiling.legality;

import java.util.ArrayList;
import java.util.List;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.hir.Loop;
import cetus.hir.PrintTools;
import cetus.hir.Symbol;

/**
 * Pure implementations of the four direction-vector lemmas of Pan et al.
 * (IWOMP 2005) used by the Parallel-Aware Tiling pass (thesis ch. 2,
 * sec. 2.2.3):
 *
 * <ul>
 * <li>Lemma 1 (Reordering): permuting loops permutes every vector's entries
 * the same way ({@link #reorder}).</li>
 * <li>Lemma 2 (Permutability): a loop order is legal iff no vector is
 * lexicographically negative ({@link #isLegal}).</li>
 * <li>Lemma 3 (Parallelism): the loop at a vector's leftmost {@code <} is
 * serial and carries the dependence; loops nested inside it are parallel
 * w.r.t. that dependence ({@link #isParallel},
 * {@link #outermostParallelLoop}).</li>
 * <li>Lemma 4 (Strip-mining): entry {@code [d]} of the strip-mined loop is
 * replaced by a pair over (cross-strip, in-strip): {@code = -> (=,=)};
 * {@code < -> (=,<) | (<,*)}; {@code > -> (=,>) | (>,*)};
 * {@code * -> (=,*) | (*,*)} ({@link #stripMine}).</li>
 * </ul>
 *
 * Loop identity is matched by index-symbol name, because dependence vectors
 * key on {@link Loop} object references that do not survive nest cloning.
 * Direction constants are the {@link DependenceVector} ones:
 * nil(-1), any(0)='*', less(1)='&lt;', equal(2)='=', greater(3)='&gt;'.
 * A {@code nil} entry means "no information recorded for this loop" and is
 * treated as non-constraining (consistent with the pre-existing behavior of
 * dropping all-nil vectors).
 */
public final class DirectionVectorLemmas {

    private DirectionVectorLemmas() {
    }

    /** Index-symbol name of a loop, or null when unavailable. */
    public static String loopName(Loop loop) {
        Symbol symbol = LoopTools.getLoopIndexSymbol(loop);
        return symbol == null ? null : symbol.getSymbolName();
    }

    /**
     * Direction of {@code dv} at the loop whose index symbol matches
     * {@code loop}'s (name-based lookup); {@link DependenceVector#nil} when
     * the vector has no entry for it.
     */
    public static int directionOf(DependenceVector dv, Loop loop) {
        String name = loopName(loop);
        if (name == null) {
            return DependenceVector.nil;
        }
        for (Loop dvLoop : dv.getLoops()) {
            if (name.equals(loopName(dvLoop))) {
                return dv.getDirection(dvLoop);
            }
        }
        return DependenceVector.nil;
    }

    /**
     * Lemma 4. Rewrites every vector of {@code dvs} for the nest obtained by
     * strip-mining {@code target} into {@code crossLoop} (tile loop) and
     * {@code inLoop} (intra-tile loop). {@code newOrder} is the loop order
     * (outermost to innermost) of the new nest and must contain both new
     * loops; entries for all other loops are copied by name. One input
     * vector may produce two output vectors (the across-tile case), which is
     * why vectors are recomputed after every strip-mining step.
     */
    public static List<DependenceVector> stripMine(List<DependenceVector> dvs,
            Loop target, Loop crossLoop, Loop inLoop, List<Loop> newOrder) {

        List<DependenceVector> result = new ArrayList<>();
        String crossName = loopName(crossLoop);
        String inName = loopName(inLoop);
        PrintTools.printlnDebug("[paw-lemma] Lemma4 stripMine target="
                + loopName(target) + " cross=" + crossName + " in=" + inName
                + " newOrder=" + orderNames(newOrder)
                + " inputCount=" + dvs.size());

        for (DependenceVector dv : dvs) {
            int d = directionOf(dv, target);

            // (cross, in) pairs per Lemma 4.
            List<int[]> pairs = new ArrayList<>();
            switch (d) {
                case DependenceVector.equal:
                    pairs.add(new int[] { DependenceVector.equal, DependenceVector.equal });
                    break;
                case DependenceVector.less:
                    pairs.add(new int[] { DependenceVector.equal, DependenceVector.less });
                    pairs.add(new int[] { DependenceVector.less, DependenceVector.any });
                    break;
                case DependenceVector.greater:
                    pairs.add(new int[] { DependenceVector.equal, DependenceVector.greater });
                    pairs.add(new int[] { DependenceVector.greater, DependenceVector.any });
                    break;
                case DependenceVector.any:
                    pairs.add(new int[] { DependenceVector.equal, DependenceVector.any });
                    pairs.add(new int[] { DependenceVector.any, DependenceVector.any });
                    break;
                default: // nil: no info for the split loop; keep nil on both
                    pairs.add(new int[] { DependenceVector.nil, DependenceVector.nil });
                    break;
            }

            for (int[] pair : pairs) {
                DependenceVector newDV = new DependenceVector();
                for (Loop loop : newOrder) {
                    String name = loopName(loop);
                    int direction;
                    if (name != null && name.equals(crossName)) {
                        direction = pair[0];
                    } else if (name != null && name.equals(inName)) {
                        direction = pair[1];
                    } else {
                        direction = directionOf(dv, loop);
                    }
                    newDV.setDirection(loop, direction);
                }
                result.add(newDV);
            }
        }
        PrintTools.printlnDebug("[paw-lemma] Lemma4 stripMine output="
                + formatVectors(result, newOrder));
        return result;
    }

    /**
     * Lemma 1. Returns vectors whose entries are stored in {@code newOrder}
     * (outermost to innermost). The mapping loop-to-direction is unchanged;
     * only the entry order differs, which is what the lexicographic tests
     * consume.
     */
    public static List<DependenceVector> reorder(List<DependenceVector> dvs,
            List<Loop> newOrder) {
        List<DependenceVector> result = new ArrayList<>();
        for (DependenceVector dv : dvs) {
            DependenceVector newDV = new DependenceVector();
            for (Loop loop : newOrder) {
                newDV.setDirection(loop, directionOf(dv, loop));
            }
            result.add(newDV);
        }
        PrintTools.printlnDebug("[paw-lemma] Lemma1 reorder newOrder="
                + orderNames(newOrder) + " out=" + formatVectors(result, newOrder));
        return result;
    }

    /**
     * Lemma 2. Legal iff every vector's leftmost non-{@code =} entry (walking
     * {@code order} outermost to innermost, skipping {@code nil}) is
     * {@code <}. A leading {@code *} is conservatively illegal (it may hide a
     * {@code >}).
     */
    public static boolean isLegal(List<DependenceVector> dvs, List<Loop> order) {
        for (DependenceVector dv : dvs) {
            if (!isVectorLegal(dv, order)) {
                PrintTools.printlnDebug("[paw-lemma] Lemma2 isLegal=false order="
                        + orderNames(order) + " illegal="
                        + formatVector(dv, order));
                return false;
            }
        }
        PrintTools.printlnDebug("[paw-lemma] Lemma2 isLegal=true order="
                + orderNames(order) + " dvs=" + formatVectors(dvs, order));
        return true;
    }

    private static boolean isVectorLegal(DependenceVector dv, List<Loop> order) {
        for (Loop loop : order) {
            int d = directionOf(dv, loop);
            if (d == DependenceVector.nil || d == DependenceVector.equal) {
                continue;
            }
            return d == DependenceVector.less;
        }
        return true; // all '=' (loop-independent) is legal
    }

    /**
     * Lemma 3. {@code p} is parallel iff for every vector either the
     * direction at {@code p} is {@code =} (or {@code nil}), or a loop
     * strictly outer than {@code p} in {@code order} carries the dependence
     * (holds the vector's leftmost {@code <}).
     */
    public static boolean isParallel(List<DependenceVector> dvs,
            List<Loop> order, Loop p) {
        int pIdx = indexOf(order, p);
        if (pIdx < 0) {
            return false;
        }
        for (DependenceVector dv : dvs) {
            int d = directionOf(dv, p);
            if (d == DependenceVector.equal || d == DependenceVector.nil) {
                continue;
            }
            if (!carriedOutside(dv, order, pIdx)) {
                return false;
            }
        }
        return true;
    }

    /** True when the vector's leftmost non-'='/nil entry before position
     * {@code pIdx} is a '<' (the dependence is carried by an outer loop). */
    private static boolean carriedOutside(DependenceVector dv,
            List<Loop> order, int pIdx) {
        for (int i = 0; i < pIdx; i++) {
            int d = directionOf(dv, order.get(i));
            if (d == DependenceVector.nil || d == DependenceVector.equal) {
                continue;
            }
            return d == DependenceVector.less;
        }
        return false;
    }

    /**
     * The outermost loop of {@code order} that is parallel by Lemma 3, or
     * null when every loop is serial. Selecting this on the tiled nest's
     * vectors (never inheriting from the untiled nest) is what Theorem 1
     * ("parallelism of tiled loops") requires.
     */
    public static Loop outermostParallelLoop(List<DependenceVector> dvs,
            List<Loop> order) {
        for (Loop loop : order) {
            boolean parallel = isParallel(dvs, order, loop);
            PrintTools.printlnDebug("[paw-lemma] Lemma3 isParallel("
                    + loopName(loop) + ")=" + parallel);
            if (parallel) {
                PrintTools.printlnDebug("[paw-lemma] Theorem1 outermostParallel="
                        + loopName(loop));
                return loop;
            }
        }
        PrintTools.printlnDebug("[paw-lemma] Theorem1 outermostParallel=null (all serial)");
        return null;
    }

    private static int indexOf(List<Loop> order, Loop p) {
        String name = loopName(p);
        for (int i = 0; i < order.size(); i++) {
            if (name != null && name.equals(loopName(order.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** Direction as {@code < = > *} for debug / tests. */
    public static String directionChar(int d) {
        switch (d) {
            case DependenceVector.less:
                return "<";
            case DependenceVector.equal:
                return "=";
            case DependenceVector.greater:
                return ">";
            case DependenceVector.any:
                return "*";
            case DependenceVector.nil:
                return ".";
            default:
                return "?";
        }
    }

    public static String orderNames(List<Loop> order) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String n = loopName(order.get(i));
            sb.append(n == null ? "?" : n);
        }
        sb.append(')');
        return sb.toString();
    }

    public static String formatVector(DependenceVector dv, List<Loop> order) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(directionChar(directionOf(dv, order.get(i))));
        }
        sb.append(')');
        return sb.toString();
    }

    public static String formatVectors(List<DependenceVector> dvs,
            List<Loop> order) {
        if (dvs == null || dvs.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dvs.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(formatVector(dvs.get(i), order));
        }
        sb.append(']');
        return sb.toString();
    }
}
