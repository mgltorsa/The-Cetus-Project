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
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;
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
        /** Tiled versions built and tested for legality. */
        public int tried = 0;
        /** Of those, the legal ones. */
        public int legal = 0;
    }

    /** Upper bound on the versions {@link #enumerate} builds for one nest. */
    public static final int MAX_ENUMERATED = 2000;

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
            result.tried++;

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
            result.legal++;
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

    /**
     * The search of Pan et al., for comparison with {@link #browse}: every
     * tiled version is built, one for each ordered choice of up to
     * {@code depth} loops to strip-mine (each tile loop hoisted outermost,
     * as in the browse), and the legal one with the lowest structural cost
     * is kept. The cost does not look at a numeric tile size and is ordered:
     * the outermost parallel loop as far out as possible, then the most
     * temporal reuse over the strip-mined loops (the reuse scores of
     * {@link ReuseOrderAnalyzer}), then the fewest tile loops.
     */
    public static BrowseResult enumerate(ForLoop nest,
            List<DependenceVector> originalDvs, List<ForLoop> candidates,
            int depth, SymbolTable symtab, String nestTag) throws Exception {
        BrowseResult result = new BrowseResult();
        int maxTiled = depth <= 0 ? candidates.size() : Math.min(depth, candidates.size());
        Map<String, IDExpression> ids = new LinkedHashMap<>();
        Map<String, Double> reuse = new LinkedHashMap<>();
        for (ForLoop candidate : candidates) {
            Expression indexVar = LoopTools.getIndexVariable(candidate);
            if (indexVar == null) {
                continue;
            }
            String name = indexVar.toString();
            String sizeName = Tiler.IN_TILE_PREFIX + nestTag + "_" + name;
            IDExpression sizeId = VariableDeclarationUtils.getIdentifier(symtab, sizeName);
            if (sizeId == null) {
                sizeId = VariableDeclarationUtils.declareVariable(symtab, sizeName);
            }
            ids.put(name, sizeId);
            reuse.put(name, ReuseOrderAnalyzer.numericScore(candidate, nest));
        }
        List<List<String>> sequences = new ArrayList<>();
        sequencesOf(new ArrayList<>(ids.keySet()), maxTiled, new ArrayList<String>(), sequences);

        double[] bestKey = null;
        for (List<String> seq : sequences) {
            if (result.tried >= MAX_ENUMERATED) {
                break;
            }
            TiledLoop x = new TiledLoop(nest.clone(false), originalDvs);
            List<DependenceVector> dvs = x.getDependenceVectors();
            boolean built = true;
            for (String name : seq) {
                int pos = positionOf(x, name);
                if (pos < 0) {
                    built = false;
                    break;
                }
                x = Tiler.tile(symtab, x.clone(false), ids.get(name), pos, dvs);
                dvs = x.getDependenceVectors();
            }
            if (!built) {
                continue;
            }
            result.tried++;
            List<Loop> order = loopOrder(x);
            if (!DirectionVectorLemmas.isLegal(dvs, order)) {
                result.discarded++;
                continue;
            }
            result.legal++;
            Loop parallel = DirectionVectorLemmas.outermostParallelLoop(dvs, order);
            double parallelPos = parallel == null ? order.size() : order.indexOf(parallel);
            double score = 0;
            for (String name : seq) {
                score += reuse.get(name);
            }
            double[] key = {parallelPos, -score, seq.size()};
            if (bestKey == null || compareKeys(key, bestKey) < 0) {
                bestKey = key;
                result.nest = x;
                result.dvs = dvs;
                result.order = order;
                result.tiledOriginalLoops = new ArrayList<>();
                result.symbolicSizes = new LinkedHashMap<>();
                for (String name : seq) {
                    for (ForLoop candidate : candidates) {
                        Expression indexVar = LoopTools.getIndexVariable(candidate);
                        if (indexVar != null && indexVar.toString().equals(name)) {
                            result.tiledOriginalLoops.add(candidate);
                        }
                    }
                    result.symbolicSizes.put(name, ids.get(name));
                }
            }
        }
        return result;
    }

    private static int compareKeys(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Double.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Every ordered selection of 1..max distinct names. */
    private static void sequencesOf(List<String> names, int max, List<String> prefix,
            List<List<String>> out) {
        if (!prefix.isEmpty()) {
            out.add(new ArrayList<>(prefix));
        }
        if (prefix.size() == max) {
            return;
        }
        for (String name : names) {
            if (!prefix.contains(name)) {
                prefix.add(name);
                sequencesOf(names, max, prefix, out);
                prefix.remove(prefix.size() - 1);
            }
        }
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
