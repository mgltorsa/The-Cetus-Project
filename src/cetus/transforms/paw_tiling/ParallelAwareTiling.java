package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cetus.analysis.AnalysisPass;
import cetus.analysis.ArrayPrivatization;
import cetus.analysis.DDTDriver;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.analysis.RangeAnalysis;
import cetus.analysis.Reduction;
import cetus.exec.Driver;
import cetus.hir.Annotatable;
import cetus.hir.Annotation;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.ArrayAccess;
import cetus.hir.ArraySpecifier;
import cetus.hir.Declaration;
import cetus.hir.VariableDeclarator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CetusAnnotation;
import cetus.hir.CodeAnnotation;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IRTools;
import cetus.hir.IfStatement;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Procedure;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.LoopInterchange;
import cetus.transforms.TransformPass;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;
import cetus.transforms.paw_tiling.analysis.EmptyDvAuditor;
import cetus.transforms.paw_tiling.browse.CandidateBrowser;
import cetus.transforms.paw_tiling.browse.CandidateBrowser.BrowseResult;
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;
import cetus.transforms.paw_tiling.profitability.StaticProfitability;
import cetus.transforms.paw_tiling.tile_size.BalancedTileCalculator;
import cetus.transforms.paw_tiling.tile_size.LiteralExpr;
import cetus.transforms.paw_tiling.tile_size.NTSelectionAlgo;
import cetus.utils.ExperimentalSectionUtils;
import cetus.utils.MemoryUtils;
import cetus.utils.VariableDeclarationUtils;

/**
 * Parallel-Aware Tiling (PAW): reuse-ordered tiling with parallelism and
 * legality derived from one shared set of dependence direction vectors
 * (thesis Algorithm 3.1, extension of Pan et al. IWOMP'05). Four phases per
 * perfect canonical nest:
 *
 * <ol>
 * <li>Analysis: dependence vectors + loops ranked by decreasing temporal
 * reuse ({@link ReuseOrderAnalyzer}).</li>
 * <li>Reuse-ordered browsing of candidate tiled versions with symbolic tile
 * sizes; illegal candidates discarded by the permutability lemma
 * ({@link CandidateBrowser}).</li>
 * <li>Tile-size selection (Fixed/NT/LRW raw sizes) and identification of
 * the outermost parallel loop of the TILED nest (parallelism lemma); the
 * balanced size S = I/(ceil(I/(P*T))*P) is substituted on its tile loop
 * ({@link BalancedTileCalculator}).</li>
 * <li>Emission: parallel annotation for OpenMP; profitability decided
 * statically when possible ({@link StaticProfitability}), with the
 * two-version runtime guard only when inconclusive.</li>
 * </ol>
 */
public class ParallelAwareTiling extends TransformPass {

    public final static String PASS_NAME = "paw_tiling";

    private static final CodeAnnotation requiredCodeAnnotation = new CodeAnnotation(
            "#define MIN(a,b) ((a)<(b)?(a):(b))\n" +
                    "#define MAX(a,b) ((a)>(b)?(a):(b))\n" +
                    "#define ABS(x) ((x)<0?-(x):(x))\n" +
                    "#define CEIL(x) ((int)((x)+0.5))\n" +
                    "#define FLOOR(x) ((int)((x)-0.5))\n" +
                    "#define ROUND(x) ((int)((x)+0.5))\n");

    private TilingParams tilingParams;

    public ParallelAwareTiling(Program program) {
        super(program);
        tilingParams = TilingParams.getTilingParams();
    }

    private boolean isValidForTiling(ForLoop loop) {
        if (!LoopTools.isPerfectNest(loop))
            return false;

        if (!LoopTools.isCanonical(loop))
            return false;

        return true;
    }

    private List<ForLoop> filterValidLoopNests() {
        LinkedHashSet<ForLoop> validLoops = new LinkedHashSet<>();

        List<List<Traversable>> sections = ExperimentalSectionUtils.findExperimentalSections(program);

        if (sections.isEmpty()) {
            PrintTools.printlnDebug("No experimental sections found.");
            validLoops.addAll(collectMaximalPerfectNests(program));
        } else {
            PrintTools.printlnDebug("Experimental sections found: " + sections.size());
            for (List<Traversable> section : sections) {
                for (Traversable t : section) {
                    validLoops.addAll(collectMaximalPerfectNests(t));
                }
            }
        }

        return new ArrayList<>(validLoops);
    }

    /**
     * Maximal perfect canonical nests of depth ≥ 2. Inner perfect subnests of
     * an imperfect outer loop are included (PolyBench syrk); {@code init_array}
     * / {@code print_array} are skipped so PAPI does not measure setup I/O.
     */
    private List<ForLoop> collectMaximalPerfectNests(Traversable root) {
        List<ForLoop> nests = new ArrayList<>();
        if (root == null) {
            return nests;
        }
        DFIterator<ForLoop> iter = new DFIterator<>(root, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            if (!isValidForTiling(loop)) {
                continue;
            }
            if (hasValidTilingAncestor(loop)) {
                continue;
            }
            if (isSetupOrTeardown(loop)) {
                continue;
            }
            if (nestDepth(loop) < 2) {
                continue;
            }
            nests.add(loop);
        }
        return nests;
    }

    private boolean hasValidTilingAncestor(ForLoop loop) {
        Traversable parent = loop.getParent();
        while (parent != null) {
            if (parent instanceof ForLoop && isValidForTiling((ForLoop) parent)) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static int nestDepth(ForLoop nest) {
        int depth = 0;
        DFIterator<ForLoop> iter = new DFIterator<>(nest, ForLoop.class);
        while (iter.hasNext()) {
            iter.next();
            depth++;
        }
        return depth;
    }

    /**
     * Walk parents to the outermost enclosing {@code ForLoop}.
     * {@link LoopTools#getOutermostLoop} infinite-loops when the argument is
     * not already outermost (it never advances after finding the outer loop).
     */
    private static ForLoop enclosingOutermostFor(ForLoop loop) {
        ForLoop outer = loop;
        Traversable t = loop.getParent();
        while (t != null) {
            if (t instanceof ForLoop) {
                outer = (ForLoop) t;
            }
            t = t.getParent();
        }
        return outer;
    }

    private static boolean isSetupOrTeardown(ForLoop loop) {
        Procedure proc = IRTools.getAncestorOfType(loop, Procedure.class);
        if (proc == null) {
            return false;
        }
        String name = proc.getSymbolName();
        if (name == null) {
            return false;
        }
        return name.equals("init_array") || name.equals("print_array")
                || name.startsWith("init_array") || name.startsWith("print_array");
    }

    private boolean isSerialTiling() {
        return "0".equals(Driver.getOptionValue(PASS_NAME));
    }

    @Override
    public void start() {

        // Establish the algorithm's precondition: canonical memory order.
        try {
            TransformPass.run(new LoopInterchange(program));
        } catch (Exception e) {
            PrintTools.printlnDebug("LoopInterchange failed: " + e);
        }

        PrintTools.printlnDebug("Starting parallel-aware tiling transformation...");

        for (Traversable t : program.getChildren()) {
            if (!(t instanceof TranslationUnit)) {
                continue;
            }
            TranslationUnit tu = (TranslationUnit) t;

            tu.addDeclarationFirst(new AnnotationDeclaration(requiredCodeAnnotation));
        }

        List<ForLoop> validLoops = filterValidLoopNests();
        if (validLoops.isEmpty()) {
            PrintTools.printlnDebug("No tilable loops found.");
            return;
        }

        List<TiledLoop> updatedLoops = new ArrayList<>();
        for (ForLoop targetLoop : validLoops) {
            try {
                TiledLoop newLoop = processLoop(targetLoop);
                if (newLoop != null) {
                    updatedLoops.add(newLoop);
                }
            } catch (Exception e) {
                e.printStackTrace();
                PrintTools.print(
                        String.format("Error processing loop %s: %s", targetLoop.toString(), e.getMessage()), 0);
            }
        }

        reRunPasses();
        for (TiledLoop forLoop : updatedLoops) {
            setupTileSizesMetadata(forLoop);
        }

    }

    /**
     * Runs the four phases on one valid nest. Returns the final tiled nest
     * (attached to the program), or null when the nest was left untouched
     * (no legal candidate, no usable tile size, or provably unprofitable).
     */
    private TiledLoop processLoop(ForLoop targetLoop) throws Exception {
        PrintTools.printlnDebug("PAW tiling loop: " + LoopTools.getLoopName(targetLoop));

        SymbolTable symtab = VariableDeclarationUtils
                .getVariableDeclarationSpace(targetLoop.getParent());

        // ---------- Phase 1: loop-nest analysis ----------
        LinkedList<Loop> nestLoops = new LinkedList<>();
        new DFIterator<Loop>(targetLoop, Loop.class).forEachRemaining(nestLoops::add);

        List<DependenceVector> originalDvs = new ArrayList<>();
        EmptyDvAuditor.Result dvAudit = EmptyDvAuditor.audit(targetLoop, nestLoops,
                program.getDDGraph());
        originalDvs = dvAudit.dvs;
        PrintTools.printlnDebug("[paw] Phase1 DVs status=" + dvAudit.status
                + " (" + dvAudit.detail + ")");

        List<ForLoop> reuseOrder = ReuseOrderAnalyzer.reuseOrder(targetLoop);
        int depth = tilingParams.getTilingLevel() <= 0
                ? nestLoops.size()
                : tilingParams.getTilingLevel();
        List<ForLoop> candidates = reuseOrder.subList(0,
                Math.min(depth, reuseOrder.size()));

        // Raw tile sizes for the browse candidates (method M).
        Map<Expression, Expression> rawSizes = tilingParams
                .getTileSizeSelectionAlgo().getTileSizes(targetLoop, candidates);
        List<ForLoop> browseCandidates = new ArrayList<>();
        for (ForLoop candidate : candidates) {
            Expression indexVar = LoopTools.getIndexVariable(candidate);
            Expression raw = indexVar == null ? null : rawSizes.get(indexVar);
            if (raw == null || "1".equals(raw.toString())) {
                continue;
            }
            // Strip-mining a loop whose tile covers the whole trip is a
            // no-op that only adds extra loop overhead (and extra L3
            // traffic from worse prefetch). Skip it.
            Expression trip = ReuseOrderAnalyzer.tripCount(candidate);
            long tripLit = LiteralExpr.asPositiveLiteral(trip);
            long rawLit = LiteralExpr.asPositiveLiteral(raw);
            if (tripLit > 0 && rawLit > 0 && rawLit >= tripLit) {
                PrintTools.printlnDebug("[paw] skip no-op tile " + indexVar
                        + " size=" + rawLit + " >= trip=" + tripLit);
                continue;
            }
            browseCandidates.add(candidate);
        }
        if (browseCandidates.isEmpty()) {
            PrintTools.printlnDebug("[paw] no usable tile sizes; nest untouched");
            return null;
        }

        // ---------- Phase 4 precondition: profitability is queried while
        // the original nest is still attached to the program ----------
        Expression totalIterations = calculateTotalOfInstructions(targetLoop);
        Expression cacheElems = calculateCacheInNumberOfElements(targetLoop,
                tilingParams.getCacheSizeInKB());
        Expression footprint = calculateDataFullSize(targetLoop);

        StaticProfitability.Decision decision = tilingParams.isEnableTilingProfitability()
                ? StaticProfitability.decide(targetLoop, totalIterations,
                        cacheElems, footprint,
                        tilingParams.getMaxIterationsToParallelize())
                : StaticProfitability.Decision.PROVE_TILED;

        if (decision == StaticProfitability.Decision.PROVE_UNTILED) {
            PrintTools.printlnDebug("[paw] tiling provably unprofitable; nest untouched");
            return null;
        }

        // ---------- Phase 2: reuse-ordered version browsing ----------
        String nestTag = LoopTools.getLoopName(targetLoop);
        nestTag = nestTag == null ? ("nest" + System.identityHashCode(targetLoop))
                : nestTag.replaceAll("[^A-Za-z0-9_]", "_");
        BrowseResult browse = CandidateBrowser.browse(targetLoop, originalDvs,
                browseCandidates, depth, symtab, nestTag);
        if (browse.nest == null) {
            PrintTools.printlnDebug("[paw] no legal tiled version; nest untouched");
            return null;
        }

        // ---------- Phase 3: tile-size selection and balancing ----------
        boolean innerSubnest = !LoopTools.isOutermostLoop(targetLoop);
        ForLoop enclosingOuter = enclosingOutermostFor(targetLoop);

        Loop parallelLoop = DirectionVectorLemmas.outermostParallelLoop(
                browse.dvs, browse.order);
        String parallelName = parallelLoop == null ? null
                : DirectionVectorLemmas.loopName(parallelLoop);

        int elemBytes = NTSelectionAlgo.elementBytes(targetLoop);
        int lineElems = Math.max(tilingParams.getCacheLineInBytes() / elemBytes, 1);

        Map<Expression, Expression> finalSizes = new LinkedHashMap<>();
        for (Map.Entry<String, IDExpression> entry : browse.symbolicSizes.entrySet()) {
            String indexName = entry.getKey();
            IDExpression sizeId = entry.getValue();

            Expression raw = null;
            ForLoop originalLoop = null;
            for (ForLoop candidate : browseCandidates) {
                Expression indexVar = LoopTools.getIndexVariable(candidate);
                if (indexVar != null && indexVar.toString().equals(indexName)) {
                    raw = rawSizes.get(indexVar);
                    originalLoop = candidate;
                    break;
                }
            }
            if (raw == null || originalLoop == null) {
                continue;
            }

            Expression assigned = raw;
            boolean isParallelTileLoop = !innerSubnest && parallelName != null
                    && parallelName.equals(indexName + Tiler.CROSS_TILE_SUFFIX);
            if (isParallelTileLoop && !isSerialTiling()) {
                Expression trip = ReuseOrderAnalyzer.tripCount(originalLoop);
                assigned = BalancedTileCalculator.balancedSize(trip, raw,
                        tilingParams.getNumOfProcessors(), lineElems);
                PrintTools.printlnDebug("[paw] balanced tile for parallel loop "
                        + parallelName + ": " + assigned);
            }
            VariableDeclarationUtils.replaceVariableDeclaration(symtab, sizeId,
                    Symbolic.simplify(assigned));
            Expression indexVar = LoopTools.getIndexVariable(originalLoop);
            finalSizes.put(indexVar, assigned);
        }
        browse.nest.setTileSizes(finalSizes);
        browse.nest.setOriginalLoop(targetLoop);
        browse.nest.setOriginalDvs(originalDvs);

        // ---------- Phase 4: emission ----------
        // Stale parallel annotations do not survive tiling (Theorem 1).
        stripParallelAnnotations(browse.nest);
        if (!isSerialTiling()) {
            if (innerSubnest && enclosingOuter instanceof Annotatable) {
                // Keep OpenMP on the enclosing i loop; an inner parallel for
                // would fork a team on every outer iteration.
                CetusAnnotation note = new CetusAnnotation();
                note.put("parallel", "true");
                ((Annotatable) enclosingOuter).annotate(note);
            } else if (parallelLoop != null) {
                CetusAnnotation note = new CetusAnnotation();
                note.put("parallel", "true");
                ((Annotatable) parallelLoop).annotate(note);
            }
        }

        Statement replacement;
        if (decision == StaticProfitability.Decision.PROVE_TILED) {
            replacement = browse.nest;
        } else { // UNKNOWN: two guarded versions
            replacement = buildRuntimeGuard(totalIterations, cacheElems,
                    footprint, targetLoop.clone(false), browse.nest);
        }

        replaceLoop(targetLoop, replacement);
        return browse.nest;
    }

    private void stripParallelAnnotations(ForLoop nest) {
        DFIterator<ForLoop> iter = new DFIterator<>(nest, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            List<CetusAnnotation> notes = loop.getAnnotations(CetusAnnotation.class);
            if (notes == null) {
                continue;
            }
            for (CetusAnnotation note : notes) {
                if (note.get("parallel") != null) {
                    note.remove("parallel");
                }
            }
        }
    }

    /**
     * Two-version guard (thesis Phase 4): fall back to the untiled nest when
     * the data fits in the cache OR the iteration count is too small.
     */
    private Statement buildRuntimeGuard(Expression iterations, Expression cache,
            Expression footprint, Statement untiled, Statement tiled) {

        Expression fewIterations = new BinaryExpression(iterations.clone(),
                BinaryOperator.COMPARE_LE,
                new IntegerLiteral(tilingParams.getMaxIterationsToParallelize()));
        Expression fitsInCache = new BinaryExpression(cache.clone(),
                BinaryOperator.COMPARE_GE, footprint.clone());
        Expression unprofitable = new BinaryExpression(fewIterations,
                BinaryOperator.LOGICAL_OR, fitsInCache);

        CompoundStatement tiledBlock = new CompoundStatement();
        tiledBlock.addStatement(tiled);

        return new IfStatement(unprofitable, untiled, tiledBlock);
    }

    /**
     * Nest footprint in array elements: sum of the declared sizes of the
     * DISTINCT arrays accessed in the nest (each dimension symbolically
     * simplified, so {@code w[N+1][N+1]} contributes {@code (N+1)^2}).
     */
    private Expression calculateDataFullSize(ForLoop loop) {
        SymbolTable symbols = VariableDeclarationUtils
                .getVariableDeclarationSpace(loop.getParent());
        Set<String> seen = new LinkedHashSet<>();
        Expression total = new IntegerLiteral(0);

        DFIterator<ArrayAccess> iter = new DFIterator<>(loop, ArrayAccess.class);
        while (iter.hasNext()) {
            ArrayAccess access = iter.next();
            Expression arrayName = access.getArrayName();
            if (!(arrayName instanceof IDExpression)
                    || !seen.add(arrayName.toString())) {
                continue;
            }
            Expression size = declaredArraySize(symbols, (IDExpression) arrayName);
            if (size != null) {
                total = Symbolic.add(total, size);
            }
        }
        return Symbolic.simplify(total);
    }

    /** Product of the declared dimensions of the array, or null. */
    private Expression declaredArraySize(SymbolTable symbols, IDExpression arrayID) {
        Declaration declaration = symbols.findSymbol(arrayID);
        if (declaration == null) {
            return null;
        }
        Expression size = null;
        for (Traversable childObj : declaration.getChildren()) {
            if (!(childObj instanceof VariableDeclarator)) {
                continue;
            }
            VariableDeclarator child = (VariableDeclarator) childObj;
            if (!child.getSymbolName().equals(arrayID.toString())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<ArraySpecifier> specs = child.getArraySpecifiers();
            for (ArraySpecifier spec : specs) {
                for (int j = 0; j < spec.getNumDimensions(); j++) {
                    Expression dim = spec.getDimension(j);
                    if (dim == null) {
                        continue;
                    }
                    Expression simplified = Symbolic.simplify(dim.clone());
                    size = (size == null) ? simplified
                            : Symbolic.multiply(size, simplified);
                }
            }
        }
        return size;
    }

    private Expression calculateCacheInNumberOfElements(ForLoop loop, long cacheSizeInKB) {
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return new IntegerLiteral(MemoryUtils.getCacheInArrayElements(cacheSizeInKB, arrayAccesses));
    }

    private Expression calculateTotalOfInstructions(ForLoop loop) throws Exception {
        DFIterator<ForLoop> loopIterator = new DFIterator<>(loop, ForLoop.class);
        Expression totalInstructions = new IntegerLiteral(1);
        while (loopIterator.hasNext()) {
            ForLoop innerLoop = loopIterator.next();
            Expression instructions = LoopTools.getUpperBoundExpression(innerLoop);
            if (instructions == null)
                throw new Exception("Loop upper bound expression is null.");

            totalInstructions = new BinaryExpression(totalInstructions.clone(), BinaryOperator.MULTIPLY,
                    instructions.clone());

        }

        return totalInstructions;
    }

    public void reRunPasses() {

        boolean isSerialTiling = isSerialTiling();

        // Ranges were computed on the pre-tiling IR.
        RangeAnalysis.invalidate();

        String privatizeOption = Driver.getOptionValue("privatize");
        String ddtOption = Driver.getOptionValue("ddt");
        String reductionOption = Driver.getOptionValue("reduction");

        if (!isSerialTiling && privatizeOption != null && !privatizeOption.equals("0")) {
            AnalysisPass.run(new ArrayPrivatization(program));
        }
        if (!isSerialTiling && ddtOption != null && !ddtOption.equals("0")) {
            AnalysisPass.run(new DDTDriver(program));
        }
        if (!isSerialTiling && reductionOption != null && !reductionOption.equals("0")) {
            try {
                AnalysisPass.run(new Reduction(program));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Tiles are usually small; ompGen's profitability model would reject
        // them even though the tile loop distributes whole tiles per thread.
        Driver.setOptionValue("profitable-omp", "0");

    }

    private String calculateTileSizeValue(Expression tileSize) {
        if (tileSize instanceof IntegerLiteral) {
            return tileSize.toString();
        }
        long tileSizeValueLong = Symbolic.getConstantCoefficient(tileSize);
        if (tileSizeValueLong > 0) {
            return "coeff_" + String.valueOf(tileSizeValueLong);
        }
        return "symbolic";
    }

    private void setupTileSizesMetadata(TiledLoop loop) {
        if (loop.getParent() == null) {
            return; // not attached (should not happen)
        }
        LoopTools.addLoopName(program, true);
        ForLoop pragmaLoop = enclosingOutermostFor(loop);
        Map<Expression, Expression> tileSizes = loop.getTileSizes();
        for (Expression tileSizeIndexVar : tileSizes.keySet()) {
            String loopName = LoopTools.getLoopName(pragmaLoop);
            String tileSizeName = tileSizeIndexVar.toString();

            Expression actualTileSize = tileSizes.get(tileSizeIndexVar);
            String tileSizeValue = calculateTileSizeValue(actualTileSize);

            String metadata = String.format("%s=%s#%s", loopName, tileSizeName, tileSizeValue);

            String pragmaStr = String.format("c_paw_tiling %s", metadata);
            PragmaAnnotation pragmaAnnot = new PragmaAnnotation(pragmaStr);
            pragmaLoop.annotateBefore(pragmaAnnot);
        }
    }

    private void replaceLoop(ForLoop originalLoop, Statement newLoop) {
        Traversable originalParent = originalLoop.getParent();

        int originalLoopIdx = -1;
        for (int i = 0; i < originalParent.getChildren().size(); i++) {
            if (originalParent.getChildren().get(i) == originalLoop) {
                originalLoopIdx = i;
                break;
            }
        }

        cloneCodeAnnotations(originalLoop, newLoop);

        originalParent.setChild(originalLoopIdx, newLoop);

    }

    private void cloneCodeAnnotations(ForLoop originalLoop, Statement newLoop) {
        List<Annotation> annotations = originalLoop.getAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation instanceof CodeAnnotation) {
                newLoop.annotateBefore(annotation.clone());
            }
        }
    }

    @Override
    public String getPassName() {
        return "[Parallel-Aware Tiling]";
    }

}
