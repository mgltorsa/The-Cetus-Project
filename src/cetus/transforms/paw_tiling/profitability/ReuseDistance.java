package cetus.transforms.paw_tiling.profitability;

import java.util.List;

import cetus.hir.ArrayAccess;
import cetus.hir.ForLoop;
import cetus.hir.PrintTools;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;

/**
 * Reuse distance of a perfect nest: how much data the nest touches between
 * two uses of the same array element.
 *
 * A loop L carries temporal reuse for a reference whose subscripts do not
 * contain L's index: every iteration of L touches the same elements again.
 * Between two such touches the program runs one whole iteration of L, which
 * touches, for every reference group, the product of the trip counts of the
 * loops inside L that subscript the group. That sum is the reuse distance of
 * L, in array elements.
 *
 * Tiling shortens the distance. When the longest distance of the nest
 * already fits in the cache a thread can count on, the reused data is still
 * resident at its next use and tiling can only add loop overhead. A nest
 * with no temporal reuse at all (a stencil sweep under a time loop, an NPB
 * right-hand-side sweep) has distance 0: strip-mining it reorders nothing
 * that is reused.
 */
public final class ReuseDistance {

    /** A trip count needed for the estimate is not known at compile time. */
    public static final long UNKNOWN = -1;

    private ReuseDistance() {
    }

    /**
     * Longest reuse distance of the nest in array elements, 0 when no loop
     * other than the innermost carries temporal reuse, {@link #UNKNOWN} when
     * a trip count cannot be estimated.
     */
    public static long longest(ForLoop nest) {
        List<ForLoop> loops = ReuseOrderAnalyzer.nestLoops(nest);
        List<ArrayAccess> groups = ReuseOrderAnalyzer.groupRepresentatives(nest);
        long longest = 0;
        // The innermost loop is left out: one of its iterations touches one
        // element per group.
        for (int d = 0; d < loops.size() - 1; d++) {
            ForLoop carrier = loops.get(d);
            boolean reuses = false;
            for (ArrayAccess group : groups) {
                if (!ReuseOrderAnalyzer.subscriptsContainIndex(group, carrier)) {
                    reuses = true;
                    break;
                }
            }
            if (!reuses) {
                continue;
            }
            long distance = 0;
            for (ArrayAccess group : groups) {
                long touched = 1;
                for (int e = d + 1; e < loops.size(); e++) {
                    ForLoop inner = loops.get(e);
                    if (!ReuseOrderAnalyzer.subscriptsContainIndex(group, inner)) {
                        continue;
                    }
                    long trip = ReuseOrderAnalyzer.estimatedTrip(inner, nest);
                    if (trip <= 0) {
                        PrintTools.printlnDebug("[paw-reuse] reuse distance unknown:"
                                + " no trip estimate for a loop of the nest");
                        return UNKNOWN;
                    }
                    touched = saturatingMultiply(touched, trip);
                }
                distance = saturatingAdd(distance, touched);
            }
            longest = Math.max(longest, distance);
        }
        return longest;
    }

    /**
     * True when the innermost loop walks some array with a stride longer
     * than one element: its index appears in a subscript other than the
     * last one (the column walk {@code A[j][k]} under {@code for (j)} in
     * syrk and syr2k). Each iteration then lands on a new cache line, and a
     * tile turns that walk into a block that is reused line by line. A nest
     * whose innermost loop is contiguous in every array it indexes gets no
     * such benefit from a tile.
     */
    public static boolean innermostLoopStrides(ForLoop nest) {
        List<ForLoop> loops = ReuseOrderAnalyzer.nestLoops(nest);
        if (loops.isEmpty()) {
            return false;
        }
        ForLoop innermost = loops.get(loops.size() - 1);
        cetus.hir.Expression indexVar = cetus.analysis.LoopTools.getIndexVariable(innermost);
        if (indexVar == null) {
            return false;
        }
        String name = indexVar.toString();
        for (ArrayAccess group : ReuseOrderAnalyzer.groupRepresentatives(nest)) {
            for (int i = 0; i < group.getNumIndices() - 1; i++) {
                if (ReuseOrderAnalyzer.expressionContainsName(group.getIndex(i), name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long saturatingMultiply(long a, long b) {
        return (a != 0 && b > Long.MAX_VALUE / a) ? Long.MAX_VALUE : a * b;
    }

    private static long saturatingAdd(long a, long b) {
        return (b > Long.MAX_VALUE - a) ? Long.MAX_VALUE : a + b;
    }
}
