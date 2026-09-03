package cetus.transforms.paw_tiling.profitability;

import cetus.analysis.RangeAnalysis;
import cetus.analysis.RangeDomain;
import cetus.analysis.Relation;
import cetus.hir.Expression;
import cetus.hir.IntegerLiteral;
import cetus.hir.PrintTools;
import cetus.hir.Statement;
import cetus.hir.Symbolic;

/**
 * Static-first tiling profitability (thesis sec. 3.2, Phase 4). Tiling is
 * unprofitable when the data set fits in the cache OR the iteration count is
 * too small to amortize the transformation. The decision is made at compile
 * time whenever the literal values or Cetus symbolic range analysis can
 * prove it; only an inconclusive result leads to the two-version runtime
 * guard.
 */
public final class StaticProfitability {

    public enum Decision {
        /** Provably unprofitable: emit the original nest only. */
        PROVE_UNTILED,
        /** Provably profitable: emit the tiled nest only, no guard. */
        PROVE_TILED,
        /** Undecidable at compile time: emit the guarded two versions. */
        UNKNOWN
    }

    private StaticProfitability() {
    }

    /**
     * @param stmt          statement whose range context is queried (the
     *                      original loop)
     * @param iterations    total iteration count expression of the nest
     * @param cacheElems    cache capacity in array elements
     * @param footprint     data footprint of the nest in array elements
     * @param iterThreshold minimum iterations for tiling to amortize
     */
    public static Decision decide(Statement stmt, Expression iterations,
            Expression cacheElems, Expression footprint, long iterThreshold) {

        Expression iterSimple = Symbolic.simplify(iterations);
        Expression cacheSimple = Symbolic.simplify(cacheElems);
        Expression footSimple = Symbolic.simplify(footprint);
        IntegerLiteral threshold = new IntegerLiteral(iterThreshold);

        PrintTools.printlnDebug("[paw-profit] iterations=" + iterSimple
                + " cache=" + cacheSimple + " footprint=" + footSimple
                + " threshold=" + iterThreshold);

        // Literal fast-path.
        if (iterSimple instanceof IntegerLiteral
                && cacheSimple instanceof IntegerLiteral
                && footSimple instanceof IntegerLiteral) {
            long iter = ((IntegerLiteral) iterSimple).getValue();
            long cache = ((IntegerLiteral) cacheSimple).getValue();
            long foot = ((IntegerLiteral) footSimple).getValue();
            boolean unprofitable = foot <= cache || iter <= iterThreshold;
            return unprofitable ? Decision.PROVE_UNTILED : Decision.PROVE_TILED;
        }

        // Symbolic range analysis.
        try {
            RangeDomain rd = RangeAnalysis.query(stmt);
            if (rd != null) {
                Relation fits = rd.compare(footSimple, cacheSimple);
                Relation small = rd.compare(iterSimple, threshold);
                if (fits.isLE() || small.isLE()) {
                    return Decision.PROVE_UNTILED;
                }
                if (fits.isGT() && small.isGT()) {
                    return Decision.PROVE_TILED;
                }
            }
        } catch (Exception e) {
            PrintTools.printlnDebug("[paw] range query failed: " + e);
        }
        return Decision.UNKNOWN;
    }
}
