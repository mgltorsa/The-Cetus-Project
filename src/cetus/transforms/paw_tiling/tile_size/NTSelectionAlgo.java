package cetus.transforms.paw_tiling.tile_size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;
import cetus.hir.PrintTools;
import cetus.hir.Symbolic;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;
import cetus.utils.ArrayUtils;

/**
 * Naive Tile (NT) method -- shared-cache capacity model (thesis
 * sec. 3.3.2). All quantities are in array elements; {@code CacheSize} is
 * the TOTAL shared L3 capacity (a single resident copy of the
 * highest-reuse reference serves all cores) and the {@code Cores x Refs}
 * term reserves capacity for the per-thread access streams:
 *
 * <pre>
 * t_i * t_j + Cores * Refs &lt;= CacheSize
 * t_j = floor((CacheSize - Cores * Refs) / t_i)      (rectangular 1 x t_j)
 * B   = floor(sqrt(t_j))                             (square B x B)
 * </pre>
 *
 * both aligned down to the cache line. Thesis example: 25 MiB L3 of
 * doubles = 3,276,800 elements, Cores=4, Refs=3, t_i=1000 gives
 * t_j = 3276 -&gt; 3272 aligned; B = 57 -&gt; 56 aligned.
 */
public class NTSelectionAlgo implements TileSizeSelectionAlgo {

    private final long cacheSizeInBytes;
    private final int cacheLineSizeInBytes;
    private final int cores;

    public NTSelectionAlgo(long cacheSizeInKiB, int cacheLineSizeInBytes, int cores) {
        this.cacheSizeInBytes = cacheSizeInKiB * 1024;
        this.cacheLineSizeInBytes = cacheLineSizeInBytes;
        this.cores = cores;
    }

    /** Pure model: rectangular tile width t_j (elements), unaligned. */
    public static long rectTile(long cacheElems, int cores, long refs, long ti) {
        if (ti <= 0) {
            return 0;
        }
        long free = cacheElems - (long) cores * refs;
        if (free <= 0) {
            return 0;
        }
        return free / ti;
    }

    /** Pure model: square tile edge B (elements), unaligned. */
    public static long squareTile(long cacheElems, int cores, long refs, long ti) {
        long tj = rectTile(cacheElems, cores, refs, ti);
        return tj <= 0 ? 0 : (long) Math.floor(Math.sqrt((double) tj));
    }

    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest,
            List<ForLoop> browseOrder) {
        Map<Expression, Expression> sizes = new LinkedHashMap<>();
        if (browseOrder.isEmpty()) {
            return sizes;
        }

        int elemBytes = elementBytes(loopNest);
        long cacheElems = cacheSizeInBytes / elemBytes;
        int lineElems = Math.max(cacheLineSizeInBytes / elemBytes, 1);
        long refs = ReuseOrderAnalyzer.referenceGroups(loopNest).size();

        // t_i: trip count of the loop that spans the resident reference's
        // row dimension (first subscript); outermost loop as fallback.
        ForLoop rowLoop = residentRowLoop(loopNest);
        Expression tiExpr = ReuseOrderAnalyzer.tripCount(
                rowLoop != null ? rowLoop : loopNest);

        if (browseOrder.size() == 1 && !(tiExpr instanceof IntegerLiteral)) {
            // Rectangular tile, symbolic-safe:
            // t_j = (C - cores*refs) / t_i
            Expression free = new IntegerLiteral(cacheElems - cores * refs);
            Expression tj = Symbolic.divide(free, tiExpr);
            put(sizes, browseOrder.get(0), tj);
            return sizes;
        }

        long dimFallback = LRWSelectionAlgo.residentLeadingDimension(loopNest);
        long ti = chooseTripForTiles(tiExpr, dimFallback);

        long raw = (browseOrder.size() == 1)
                ? rectTile(cacheElems, cores, refs, ti)
                : squareTile(cacheElems, cores, refs, ti);
        long aligned = BalancedTileCalculator.alignDown(raw, lineElems);
        if (aligned <= 1) {
            PrintTools.printlnDebug("[NT] tile size degenerate (" + aligned
                    + "); not tiling");
            return sizes;
        }
        for (ForLoop loop : browseOrder) {
            put(sizes, loop, new IntegerLiteral(aligned));
        }
        return sizes;
    }

    private static void put(Map<Expression, Expression> sizes, ForLoop loop,
            Expression value) {
        Expression indexVar = LoopTools.getIndexVariable(loop);
        if (indexVar != null) {
            sizes.put(indexVar, value);
        }
    }

    /** Loop whose index appears in the resident reference's first (row)
     * subscript. */
    static ForLoop residentRowLoop(ForLoop nest) {
        ArrayAccess resident = ReuseOrderAnalyzer.residentReference(nest);
        if (resident == null || resident.getNumIndices() == 0) {
            return null;
        }
        Expression rowSubscript = resident.getIndex(0);
        for (ForLoop loop : ReuseOrderAnalyzer.nestLoops(nest)) {
            Expression idx = LoopTools.getIndexVariable(loop);
            if (idx == null) {
                continue;
            }
            DFIterator<Expression> parts = new DFIterator<>(rowSubscript, Expression.class);
            while (parts.hasNext()) {
                if (parts.next().toString().equals(idx.toString())) {
                    return loop;
                }
            }
        }
        return null;
    }

    /**
     * Trip count used for the NT capacity model. Prefer a folded literal
     * trip; else a compile-time array dimension (PolyBench {@code (N+0)});
     * else {@code 1} so the square tile is capacity-bound. Never the
     * reuse-ranking stand-in {@code 1000}, which collapses tiles to one
     * cache line after alignment.
     */
    public static long chooseTripForTiles(Expression tiExpr, long literalDimFallback) {
        long trip = LiteralExpr.asPositiveLiteral(tiExpr);
        if (trip > 0) {
            return trip;
        }
        if (literalDimFallback > 0) {
            return literalDimFallback;
        }
        return 1L;
    }

    /** Element size in bytes from the nest's first array access (8 when
     * unavailable). */
    public static int elementBytes(ForLoop nest) {
        DFIterator<ArrayAccess> iter = new DFIterator<>(nest, ArrayAccess.class);
        if (iter.hasNext()) {
            int bits = ArrayUtils.getTypeSizeInBits(iter.next());
            if (bits > 0) {
                return Math.max(bits / 8, 1);
            }
        }
        return 8;
    }
}
