package cetus.unittest.paw_tiling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.Expression;
import cetus.hir.IntegerLiteral;
import cetus.hir.NameID;
import cetus.transforms.paw_tiling.tile_size.BalancedTileCalculator;
import cetus.transforms.paw_tiling.tile_size.LRWSelectionAlgo;
import cetus.transforms.paw_tiling.tile_size.LiteralExpr;
import cetus.transforms.paw_tiling.tile_size.NTSelectionAlgo;

public class TileSizeMathTest {

    // ---------- NT: thesis numeric example (sec. 3.3.2) ----------

    @Test
    public void ntThesisExample() {
        long cacheElems = 25L * 1024 * 1024 / 8; // 25 MiB of doubles
        assertEquals(3_276_800L, cacheElems);
        long tj = NTSelectionAlgo.rectTile(cacheElems, 4, 3, 1000);
        assertEquals(3276L, tj);
        assertEquals(3272L, BalancedTileCalculator.alignDown(tj, 8));
        long b = NTSelectionAlgo.squareTile(cacheElems, 4, 3, 1000);
        assertEquals(57L, b);
        assertEquals(56L, BalancedTileCalculator.alignDown(b, 8));
    }

    @Test
    public void ntDegenerateWhenCacheTooSmall() {
        assertEquals(0L, NTSelectionAlgo.rectTile(10, 4, 3, 1000));
    }

    // ---------- LRW FindB ----------

    /** Largest B such that a BxB block of an N-wide array maps to distinct
     * lines of a direct-mapped cache with C element-sized lines. */
    static long bruteForceB(long n, long c) {
        for (long b = Math.min(n, c);; b--) {
            if (b <= 1) {
                return 1;
            }
            Set<Long> lines = new HashSet<>();
            boolean ok = true;
            for (long r = 0; r < b && ok; r++) {
                for (long col = 0; col < b; col++) {
                    if (!lines.add((r * n + col) % c)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                return b;
            }
        }
    }

    @Test
    public void findBMatchesThesisFigureConfig() {
        // thesis Figure lrw-conflict: 16-line cache, 6-wide rows
        assertEquals(3L, LRWSelectionAlgo.findB(6, 16));
        assertEquals(bruteForceB(6, 16), LRWSelectionAlgo.findB(6, 16));
    }

    @Test
    public void findBPowerOfTwoIsConflictAware() {
        long b0 = LRWSelectionAlgo.findB(1024, 4096);
        assertEquals(4L, b0); // rows collide every C/N = 4 rows
        assertTrue(b0 < (long) Math.sqrt(4096)); // far below capacity bound
    }

    @Test
    public void findBNeverExceedsSelfInterferenceLimit() {
        long[][] cases = { { 6, 16 }, { 100, 64 }, { 100, 256 },
                { 1000, 4096 }, { 1024, 4096 }, { 500, 1024 } };
        for (long[] nc : cases) {
            long b0 = LRWSelectionAlgo.findB(nc[0], nc[1]);
            long brute = bruteForceB(nc[0], nc[1]);
            assertTrue("findB(" + nc[0] + "," + nc[1] + ")=" + b0
                    + " exceeds brute " + brute, b0 <= brute);
            assertTrue(b0 >= 1);
        }
    }

    @Test
    public void findBWholeArrayFits() {
        assertEquals(10L, LRWSelectionAlgo.findB(10, 200)); // 100 <= 200
    }

    @Test
    public void lrwSharedL3CapsWhenArrayFitsButCoresWouldOverflow() {
        long cacheElems = 1024L * 1024 / 8; // 1024 KiB of doubles
        long n = 190;
        assertEquals(n, LRWSelectionAlgo.findB(n, cacheElems));
        long cap = LRWSelectionAlgo.sharedL3SquareCap(cacheElems, 16, 3, n);
        assertTrue("shared cap should be a real block, got " + cap, cap > 8);
        assertTrue("shared cap should be < full dim, got " + cap, cap < n);
        // getTileSizes applies this cap only when cores > 1; serial FindB
        // keeps n when the array fits.
    }

    // ---------- Balanced tile (Pan et al. heuristic) ----------

    @Test
    public void balancedThesisExample() {
        // I=512, T=80, P=4: ceil(512/320)=2 -> S = 512/(2*4) = 64
        assertEquals(64L, BalancedTileCalculator.balancedSize(512, 80, 4, 8));
    }

    @Test
    public void balancedAlignsDown() {
        // I=1000, T=100, P=4: ceil(1000/400)=3 -> 1000/12 = 83 -> 80
        assertEquals(80L, BalancedTileCalculator.balancedSize(1000, 100, 4, 8));
    }

    @Test
    public void balancedNeverLargerThanRaw() {
        for (long i = 100; i <= 2000; i += 137) {
            for (long t = 8; t <= 256; t *= 2) {
                long s = BalancedTileCalculator.balancedSize(i, t, 4, 0);
                assertTrue("S=" + s + " > T=" + t + " (I=" + i + ")", s <= t);
                assertTrue(s >= 1);
            }
        }
    }

    @Test
    public void balancedSymbolicBuildsCeilDivision() {
        Expression trip = new NameID("n");
        Expression result = BalancedTileCalculator.balancedSize(
                trip, new IntegerLiteral(80), 4, 8);
        assertTrue(result instanceof BinaryExpression);
        String s = result.toString();
        assertTrue("missing ceil-division shape: " + s, s.contains("/"));
        assertTrue("trip count not referenced: " + s, s.contains("n"));
    }

    @Test
    public void alignDownFloorsAtOneLine() {
        assertEquals(5L, BalancedTileCalculator.alignDown(5, 8)); // below one line: keep
        assertEquals(8L, BalancedTileCalculator.alignDown(9, 8));
        assertEquals(56L, BalancedTileCalculator.alignDown(57, 8));
    }

    // ---------- PolyBench (N+0) dims and NT trip fallback ----------

    @Test
    public void polybenchPlusZeroDimensionFolds() {
        Expression dim = new BinaryExpression(new IntegerLiteral(180),
                BinaryOperator.ADD, new IntegerLiteral(0));
        assertEquals(180L, LiteralExpr.asPositiveLiteral(dim));
    }

    @Test
    public void chooseTripPrefersLiteralThenArrayDimNotDefault1000() {
        assertEquals(190L, NTSelectionAlgo.chooseTripForTiles(
                new IntegerLiteral(190), 180));
        assertEquals(180L, NTSelectionAlgo.chooseTripForTiles(
                new NameID("ni"), 180));
        // unknown trip AND unknown dim → capacity-bound (ti=1), never 1000
        assertEquals(1L, NTSelectionAlgo.chooseTripForTiles(
                new NameID("ni"), -1));
        long fake1000 = NTSelectionAlgo.squareTile(131072, 4, 3, 1000);
        long capacity = NTSelectionAlgo.squareTile(131072, 4, 3, 1);
        assertEquals(11L, fake1000);
        assertTrue("capacity-bound square should dwarf the ti=1000 tile",
                capacity > 100);
    }
}
