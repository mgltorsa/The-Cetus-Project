package cetus.transforms.paw_tiling.browse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.Loop;
import cetus.hir.PrintTools;
import cetus.hir.SymbolTable;
import cetus.transforms.paw_tiling.TiledLoop;
import cetus.transforms.paw_tiling.Tiler;
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;
import cetus.utils.VariableDeclarationUtils;

/**
 * Phase 2 of the PAW algorithm (thesis sec. 3.2): reuse-ordered browsing of
 * candidate tiled versions. Walking the reuse order up to the tiling depth,
 * each candidate strip-mines the next loop with a SYMBOLIC tile size (no
 * numeric value enters the browse), hoists its tile loop outermost, rewrites
 * the dependence vectors with the strip-mining and reordering lemmas, and
 * discards the candidate if any rewritten vector is lexicographically
 * negative (permutability lemma). Surviving candidates are committed and
 * browsing continues on them.
 */
public final class CandidateBrowser {

    public static class BrowseResult {
        /** Final tiled version X (null when nothing was tiled). */
        public TiledLoop nest;
        /** Rewritten dependence vectors of X. */
        public List<DependenceVector> dvs = new ArrayList<>();
        /** Loop order of X, outermost to innermost. */
        public List<Loop> order = new ArrayList<>();
        /** Original loops that were strip-mined, in commit order. */
        public List<ForLoop> tiledOriginalLoops = new ArrayList<>();
        /** Original index variable name -> symbolic tile-size identifier. */
        public Map<String, IDExpression> symbolicSizes = new LinkedHashMap<>();
        /** Candidates discarded as illegal (for reporting). */
        public int discarded = 0;
    }

    private CandidateBrowser() {
    }

    /**
     * @param nest        the original perfect nest (left untouched; the
     *                    result is a rewritten clone)
     * @param originalDvs direction vectors of {@code nest}
     * @param candidates  loops of {@code nest} to browse, in decreasing
     *                    reuse order (already truncated to the loops the
     *                    tile-size method wants tiled)
     * @param depth       tiling depth d ({@code <= 0} means unlimited)
     * @param nestTag     unique tag for this nest, used to name the tile-size
     *                    variables ({@code cetus_tile_<tag>_<index>}) so
     *                    different nests in one function never share sizes
     */
    public static BrowseResult browse(ForLoop nest,
            List<DependenceVector> originalDvs, List<ForLoop> candidates,
            int depth, SymbolTable symtab, String nestTag) throws Exception {

        BrowseResult result = new BrowseResult();
        int maxTiled = depth <= 0 ? Integer.MAX_VALUE : depth;

        TiledLoop x = new TiledLoop(nest.clone(false), originalDvs);
        List<DependenceVector> currentDvs = x.getDependenceVectors();
        boolean tiledSomething = false;

        for (ForLoop candidate : candidates) {
            if (result.tiledOriginalLoops.size() >= maxTiled) {
                break;
            }
            Expression indexVar = LoopTools.getIndexVariable(candidate);
            if (indexVar == null) {
                continue;
            }
            String indexName = indexVar.toString();

            // Symbolic tile size: declared, initialized later in Phase 3.
            String sizeName = Tiler.IN_TILE_PREFIX + nestTag + "_" + indexName;
            IDExpression sizeId = VariableDeclarationUtils.getIdentifier(symtab, sizeName);
            if (sizeId == null) {
                sizeId = VariableDeclarationUtils.declareVariable(symtab, sizeName);
            }

            int pos = positionOf(x, indexName);
            if (pos < 0) {
                PrintTools.printlnDebug("[paw] loop " + indexName
                        + " not found in current candidate; skipping");
                continue;
            }

            TiledLoop v = Tiler.tile(symtab, x.clone(false), sizeId, pos,
                    currentDvs);
            List<Loop> vOrder = loopOrder(v);
            List<DependenceVector> vDvs = v.getDependenceVectors();

            if (currentDvs == null || currentDvs.isEmpty()) {
                PrintTools.printlnDebug("[paw-browse] empty DV set while tiling "
                        + indexName
                        + " (CONFIRMED_EMPTY or no recovered vectors) —"
                        + " Lemma 2/3 are vacuous: always legal;"
                        + " outermost treated as parallel");
            }

            if (!DirectionVectorLemmas.isLegal(vDvs, vOrder)) {
                result.discarded++;
                PrintTools.printlnDebug("[paw-browse] DISCARD tiling " + indexName
                        + " order=" + DirectionVectorLemmas.orderNames(vOrder)
                        + " dvs=" + DirectionVectorLemmas.formatVectors(vDvs, vOrder));
                continue;
            }

            PrintTools.printlnDebug("[paw-browse] COMMIT tiling " + indexName
                    + " order=" + DirectionVectorLemmas.orderNames(vOrder)
                    + " dvs=" + DirectionVectorLemmas.formatVectors(vDvs, vOrder));
            x = v;
            currentDvs = vDvs;
            tiledSomething = true;
            result.tiledOriginalLoops.add(candidate);
            result.symbolicSizes.put(indexName, sizeId);
        }

        if (tiledSomething) {
            result.nest = x;
            result.dvs = currentDvs;
            result.order = loopOrder(x);
        }
        return result;
    }

    /** DFS loop order (outermost to innermost). */
    public static List<Loop> loopOrder(ForLoop nest) {
        List<Loop> order = new ArrayList<>();
        new DFIterator<Loop>(nest, Loop.class).forEachRemaining(order::add);
        return order;
    }

    private static int positionOf(ForLoop nest, String indexName) {
        int pos = 0;
        for (Loop loop : loopOrder(nest)) {
            String name = DirectionVectorLemmas.loopName(loop);
            if (indexName.equals(name)) {
                return pos;
            }
            pos++;
        }
        return -1;
    }
}
