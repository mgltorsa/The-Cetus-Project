package cetus.transforms.paw_tiling.tile_size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.ArraySpecifier;
import cetus.hir.Declaration;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IntegerLiteral;
import cetus.hir.PrintTools;
import cetus.hir.SymbolTable;
import cetus.hir.Traversable;
import cetus.hir.VariableDeclarator;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;
import cetus.utils.VariableDeclarationUtils;

/**
 * Lam-Rothberg-Wolf tile-size selection: FindB computes the critical
 * blocking factor B0, the largest square block of an N-wide row-major array
 * that suffers no self-interference in a direct-mapped cache of C elements
 * (thesis Algorithm 3.2, after Lam et al. ASPLOS'91). On set-associative
 * caches B0 is used unchanged: safe but conservative (a=1 is the worst
 * case). When N is not statically known, falls back to the NT square tile.
 *
 * Fidelity: this implementation follows the original paper (and the
 * corrected thesis Algorithm 3.2): {@code di = addr div N; dj = addr mod
 * N; if dj > N/2 then di += 1; dj = N - dj}. The nearest-row adjustment
 * is essential: an earlier thesis transcription used
 * {@code dj = |addr mod N - N|}, which mis-handles conflicts at exact row
 * multiples (e.g. N=1024, C=4096 would return 1024 even though rows
 * collide every 4 rows); the thesis was corrected on 2026-08-11 to the
 * form implemented here, which yields the small, correct B0 in the
 * power-of-two case.
 */
public class LRWSelectionAlgo implements TileSizeSelectionAlgo {

    private final long cacheSizeInBytes;
    private final int cacheLineSizeInBytes;
    private final int cores;

    public LRWSelectionAlgo(long cacheSizeInKiB, int cacheLineSizeInBytes, int cores) {
        this.cacheSizeInBytes = cacheSizeInKiB * 1024;
        this.cacheLineSizeInBytes = cacheLineSizeInBytes;
        this.cores = cores;
    }

    /**
     * FindB: critical blocking factor B0 for array width N (elements) and
     * cache capacity C (elements). O(N/sqrt(C)) iterations.
     */
    public static long findB(long n, long c) {
        if (n <= 0 || c <= 0) {
            return 1;
        }
        if (n * n <= c) {
            return n; // whole array fits: no self-interference possible
        }
        long maxWidth = Math.min(n, c);
        long addr = 0;
        while (true) {
            addr += c;
            long di = addr / n;
            long dj = addr % n;
            if (dj > n / 2) { // nearest-row adjustment (original paper)
                di += 1;
                dj = n - dj;
            }
            if (di > Math.min(maxWidth, dj)) {
                return Math.max(1, Math.min(maxWidth, di));
            }
            maxWidth = Math.min(maxWidth, dj == 0 ? 1 : dj);
        }
    }

    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest,
            List<ForLoop> browseOrder) {
        Map<Expression, Expression> sizes = new LinkedHashMap<>();
        if (browseOrder.isEmpty()) {
            return sizes;
        }

        int elemBytes = NTSelectionAlgo.elementBytes(loopNest);
        long cacheElems = cacheSizeInBytes / elemBytes;
        int lineElems = Math.max(cacheLineSizeInBytes / elemBytes, 1);

        long n = residentLeadingDimension(loopNest);
        if (n <= 0) {
            PrintTools.printlnDebug(
                    "[LRW] leading dimension unknown; falling back to NT");
            return new NTSelectionAlgo(cacheSizeInBytes / 1024,
                    cacheLineSizeInBytes, cores)
                    .getTileSizes(loopNest, browseOrder);
        }

        long b0 = findB(n, cacheElems);
        if (cores > 1) {
            long refs = Math.max(1L, ReuseOrderAnalyzer.referenceGroups(loopNest).size());
            long shared = sharedL3SquareCap(cacheElems, cores, refs, n);
            if (shared > 1 && shared < b0) {
                PrintTools.printlnDebug("[LRW] cap B0=" + b0 + " to shared-L3 "
                        + shared + " (cores=" + cores + ", refs=" + refs + ")");
                b0 = shared;
            }
        }
        long aligned = BalancedTileCalculator.alignDown(b0, lineElems);
        if (aligned <= 1) {
            aligned = Math.max(b0, 1); // keep small unaligned blocks
        }
        if (aligned <= 1) {
            PrintTools.printlnDebug("[LRW] degenerate B0; not tiling");
            return sizes;
        }
        for (ForLoop loop : browseOrder) {
            Expression indexVar = LoopTools.getIndexVariable(loop);
            if (indexVar != null) {
                sizes.put(indexVar, new IntegerLiteral(aligned));
            }
        }
        return sizes;
    }

    /** Leading (last, row-major) dimension in elements of the resident
     * reference's array, or -1 when not a compile-time literal. */
    static long residentLeadingDimension(ForLoop nest) {
        ArrayAccess resident = ReuseOrderAnalyzer.residentReference(nest);
        if (resident == null) {
            return -1;
        }
        SymbolTable symbols = VariableDeclarationUtils
                .getVariableDeclarationSpace(nest.getParent());
        try {
            Expression arrayName = resident.getArrayName();
            if (!(arrayName instanceof IDExpression)) {
                return -1;
            }
            Declaration declaration = symbols.findSymbol((IDExpression) arrayName);
            if (declaration == null) {
                return -1;
            }
            for (Traversable childObj : declaration.getChildren()) {
                if (!(childObj instanceof VariableDeclarator)) {
                    continue;
                }
                VariableDeclarator child = (VariableDeclarator) childObj;
                if (!child.getSymbolName().equals(arrayName.toString())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<ArraySpecifier> specs = child.getArraySpecifiers();
                for (ArraySpecifier spec : specs) {
                    int dims = spec.getNumDimensions();
                    if (dims == 0) {
                        continue;
                    }
                    Expression last = spec.getDimension(dims - 1);
                    long folded = LiteralExpr.asPositiveLiteral(last);
                    if (folded > 0) {
                        return folded;
                    }
                }
            }
        } catch (Exception e) {
            PrintTools.printlnDebug("[LRW] dimension lookup failed: " + e);
        }
        return -1;
    }

    /**
     * Largest square tile that still satisfies the NT shared-L3 inequality
     * {@code B·B + Cores·Refs ≤ CacheElems}. Used to stop FindB from returning
     * the full array width when one copy fits but P threads would not.
     */
    public static long sharedL3SquareCap(long cacheElems, int cores, long refs,
            long leadingDim) {
        long ti = leadingDim > 0 ? leadingDim : 1;
        return NTSelectionAlgo.squareTile(cacheElems, cores, refs, ti);
    }
}
