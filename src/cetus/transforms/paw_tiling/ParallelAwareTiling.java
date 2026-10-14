package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
import cetus.analysis.LoopParallelizationPass;
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
import cetus.hir.DataFlowTools;
import cetus.hir.ExpressionStatement;
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
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.SymbolTools;
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
import cetus.transforms.paw_tiling.profitability.ReuseDistance;
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

    /**
     * Nests whose parallel loop was moved inward by the stride interchange,
     * with the index of that loop. Such a nest is tiled on that one loop so
     * that its tile loop, outermost and balanced over the threads, carries
     * the parallelism again (one fork for the nest, not one per outer
     * iteration).
     */
    private final Map<ForLoop, String> coarsen = new IdentityHashMap<>();
    /** The inner loop of each pair in {@link #coarsen}, to undo the interchange. */
    private final Map<ForLoop, ForLoop> coarsenInner = new IdentityHashMap<>();

    public ParallelAwareTiling(Program program) {
        super(program);
        tilingParams = TilingParams.getTilingParams();
    }

    private boolean isValidForTiling(ForLoop loop) {
        if (!LoopTools.isPerfectNest(loop))
            return false;

        if (!LoopTools.isCanonical(loop))
            return false;

        // The legality lemmas work on the dependence vectors of the nest.
        // Cetus builds none for a loop it cannot test: a call with side
        // effects, a jump out of the body, or a symbolic step. An empty
        // vector set would then read as "no dependence", and the nest would
        // be reordered and marked parallel (NPB FT: the loops around
        // Swarztrauber(), which shares a scratch array).
        //
        // Direction vectors describe array accesses only. A scalar that is
        // written in the nest and is neither private nor a reduction (the
        // running maximum in NPB MG norm2u3) is a dependence they do not
        // show, so such a nest is neither reordered nor marked parallel.
        DFIterator<ForLoop> nest = new DFIterator<>(loop, ForLoop.class);
        while (nest.hasNext()) {
            ForLoop member = nest.next();
            if (!LoopTools.checkDataDependenceEligibility(member)) {
                PrintTools.printlnDebug("[paw] not tiled: "
                        + LoopTools.getLoopName(member)
                        + " is not eligible for dependence testing");
                return false;
            }
            Set<Expression> scalars = unresolvedScalars(member, loop);
            if (!scalars.isEmpty()) {
                PrintTools.printlnDebug("[paw] not tiled: "
                        + LoopTools.getLoopName(member)
                        + " has scalar dependences on " + scalars);
                return false;
            }
        }
        return true;
    }

    /**
     * Scalars written in {@code member} that carry a dependence. Starts from
     * {@link LoopTools#collectScalarDependences} and drops what is known to
     * be harmless: the index of any loop of the nest, and a scalar declared
     * private on an enclosing statement (ReductionTransform moves the
     * {@code private} clause from the loop to the parallel block it builds).
     */
    private static Set<Expression> unresolvedScalars(ForLoop member, ForLoop nest) {
        Set<Expression> result = new LinkedHashSet<>(
                LoopTools.collectScalarDependences(member));
        if (result.isEmpty()) {
            return result;
        }
        Set<Symbol> harmless = new LinkedHashSet<>();
        DFIterator<ForLoop> loops = new DFIterator<>(nest, ForLoop.class);
        while (loops.hasNext()) {
            Symbol index = LoopTools.getLoopIndexSymbol(loops.next());
            if (index != null) {
                harmless.add(index);
            }
        }
        Traversable up = member.getParent();
        while (up != null && !(up instanceof Procedure)) {
            if (up instanceof Annotatable) {
                for (String key : new String[] {"private", "firstprivate", "lastprivate"}) {
                    CetusAnnotation note = ((Annotatable) up)
                            .getAnnotation(CetusAnnotation.class, key);
                    if (note != null && note.get(key) instanceof Set) {
                        for (Object symbol : (Set<?>) note.get(key)) {
                            if (symbol instanceof Symbol) {
                                harmless.add((Symbol) symbol);
                            }
                        }
                    }
                }
            }
            up = up.getParent();
        }
        result.removeIf(expr -> harmless.contains(SymbolTools.getSymbolOf(expr)));
        return result;
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
            if (isPostParallelTiling()) {
                loop = belowParallelLoop(loop);
                if (loop == null) {
                    continue;
                }
            }
            nests.add(loop);
        }
        return nests;
    }

    /**
     * Stride interchange of a perfect two-loop subnest that sits under an
     * imperfect outer loop (the k,j nest inside the row loop of syrk and
     * syr2k). There the innermost loop walks {@code A[j][k]} down a column,
     * one new cache line per iteration. When the other loop of the pair is
     * contiguous in every array it indexes, and the swap keeps every
     * dependence vector lexicographically non-negative, the two headers are
     * exchanged. The reuse check then sees a nest whose innermost loop is
     * unit-stride and tiles it only if the reused data exceeds the cache
     * share.
     *
     * @return true when at least one subnest was interchanged
     */
    private boolean interchangeInnerSubnests() {
        List<ForLoop> pairs = new ArrayList<>();
        DFIterator<ForLoop> iter = new DFIterator<>(program, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop outer = iter.next();
            if (IRTools.getAncestorOfType(outer, ForLoop.class) == null
                    || isSetupOrTeardown(outer) || nestDepth(outer) != 2
                    || !isValidForTiling(outer) || hasValidTilingAncestor(outer)) {
                continue;
            }
            pairs.add(outer);
        }
        boolean changed = false;
        for (ForLoop outer : pairs) {
            List<ForLoop> chain = ReuseOrderAnalyzer.nestLoops(outer);
            ForLoop inner = chain.get(1);
            Expression innerIdx = LoopTools.getIndexVariable(inner);
            if (innerIdx == null || !stridedDataExceedsShare(outer, innerIdx.toString())) {
                continue; // the column walk stays in cache: nothing to gain
            }
            if (!ReuseDistance.innermostLoopStrides(outer) || !isUnitAscending(outer)
                    || !isUnitAscending(inner) || boundsDependOn(outer, inner)
                    || boundsDependOn(inner, outer)) {
                continue;
            }
            // Read against the swapped order, the vectors are those of the
            // interchanged nest (reordering lemma).
            if (!interchangeIsLegal(outer, inner)) {
                continue;
            }
            boolean outerParallel = outer.containsAnnotation(CetusAnnotation.class, "parallel");
            boolean innerParallel = inner.containsAnnotation(CetusAnnotation.class, "parallel");
            boolean enclosed = enclosingParallelLoop(outer) != null;
            if (!isSerialTiling() && !enclosed && innerParallel && !outerParallel) {
                continue; // would need the annotations exchanged; not handled
            }
            String parallelIndex = LoopTools.getIndexVariable(outer).toString();
            swapHeaders(outer, inner);
            if (ReuseDistance.innermostLoopStrides(outer)) {
                swapHeaders(outer, inner); // no better: the other loop strides too
                continue;
            }
            PrintTools.printlnDebug("[paw] stride interchange in "
                    + LoopTools.getLoopName(outer));
            // A parallel mark belongs to an index, and the indices have
            // changed places.
            stripParallelAnnotations(outer);
            if (!isSerialTiling() && !enclosed && outerParallel) {
                coarsen.put(outer, parallelIndex);
                coarsenInner.put(outer, inner);
            }
            changed = true;
        }
        return changed;
    }

    private static boolean isUnitAscending(ForLoop loop) {
        Expression step = LoopTools.getIncrementExpression(loop);
        return step != null && "1".equals(step.toString());
    }

    /** True when the header of {@code loop} reads the index of {@code other}. */
    private static boolean boundsDependOn(ForLoop loop, ForLoop other) {
        Symbol index = LoopTools.getLoopIndexSymbol(other);
        return index == null
                || IRTools.containsSymbol(loop.getInitialStatement(), index)
                || IRTools.containsSymbol(loop.getCondition(), index);
    }

    private static void swapHeaders(ForLoop a, ForLoop b) {
        a.getInitialStatement().swapWith(b.getInitialStatement());
        a.getCondition().swapWith(b.getCondition());
        a.getStep().swapWith(b.getStep());
    }

    /**
     * Undoes the interchange of a nest whose coarsening did not happen and
     * marks the loop that iterates {@code index} parallel again. Left as
     * interchanged, the parallel loop would be the inner one and fork once
     * per iteration of the outer loop.
     */
    private void restoreInnerParallel(ForLoop nest, String index) {
        if (nest.getParent() == null) {
            return; // replaced by its tiled version
        }
        ForLoop inner = coarsenInner.get(nest);
        if (inner != null && inner.getParent() != null) {
            swapHeaders(nest, inner);
            PrintTools.printlnDebug("[paw] coarsening failed; interchange undone in "
                    + LoopTools.getLoopName(nest));
        }
        for (ForLoop loop : ReuseOrderAnalyzer.nestLoops(nest)) {
            Expression indexVar = LoopTools.getIndexVariable(loop);
            if (indexVar != null && indexVar.toString().equals(index)
                    && !loop.containsAnnotation(CetusAnnotation.class, "parallel")) {
                CetusAnnotation note = new CetusAnnotation();
                note.put("parallel", "true");
                loop.annotate(note);
            }
        }
    }

    /**
     * Loop distribution of an imperfect parallel loop. A loop L that the
     * parallelizer proved parallel carries no dependence between its
     * iterations, so its body can be split into consecutive loops, one per
     * group of statements, in the original order. It is done when one group
     * is an inner loop that walks an array down a column while L would walk
     * it along a row: that group becomes a perfect pair (L, inner), which the
     * stride interchange can reorder (covariance, correlation, gramschmidt,
     * trmm).
     *
     * Left alone: a loop with no enclosing loop (it is a measured region), a
     * loop with a privatized array or a reduction, a body with anything but
     * expression statements and loops, and a body in which a scalar carries
     * a value from one group to another.
     *
     * @return true when at least one loop was distributed
     */
    private boolean distributeParallelLoops() {
        List<ForLoop> parallel = new ArrayList<>();
        DFIterator<ForLoop> iter = new DFIterator<>(program, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            if (loop.containsAnnotation(CetusAnnotation.class, "parallel")
                    && IRTools.getAncestorOfType(loop, ForLoop.class) != null
                    && enclosingParallelLoop(loop) == null
                    && !isSetupOrTeardown(loop) && LoopTools.isCanonical(loop)
                    && isUnitAscending(loop)) {
                parallel.add(loop);
            }
        }
        boolean changed = false;
        for (ForLoop loop : parallel) {
            List<List<Statement>> groups = distributionGroups(loop);
            if (groups == null) {
                continue;
            }
            CompoundStatement parent = (CompoundStatement) loop.getParent();
            for (List<Statement> group : groups) {
                CompoundStatement body = new CompoundStatement();
                for (Statement stmt : group) {
                    // Moved, not cloned: a clone would also copy the
                    // statements' annotations, and that copy does not print.
                    ((CompoundStatement) loop.getBody()).removeStatement(stmt);
                    body.addStatement(stmt);
                }
                ForLoop piece = new ForLoop(loop.getInitialStatement().clone(),
                        loop.getCondition().clone(), loop.getStep().clone(), body);
                for (CetusAnnotation note : loop.getAnnotations(CetusAnnotation.class)) {
                    // Annotation.clone() turns the symbol sets into lists,
                    // which CetusAnnotation cannot print.
                    CetusAnnotation copy = new CetusAnnotation();
                    for (Map.Entry<String, Object> entry : note.entrySet()) {
                        Object value = entry.getValue();
                        copy.put(entry.getKey(), value instanceof java.util.Collection
                                ? new LinkedHashSet<Object>((java.util.Collection<?>) value)
                                : value);
                    }
                    piece.annotate(copy);
                }
                parent.addStatementBefore(loop, piece);
            }
            parent.removeStatement(loop);
            PrintTools.printlnDebug("[paw] distributed parallel loop into "
                    + groups.size() + " loops");
            changed = true;
        }
        return changed;
    }

    /**
     * The statement groups {@code loop} is to be split into, or null when
     * it is not to be distributed.
     */
    private List<List<Statement>> distributionGroups(ForLoop loop) {
        if (!(loop.getParent() instanceof CompoundStatement)
                || !(loop.getBody() instanceof CompoundStatement)
                || loop.containsAnnotation(CetusAnnotation.class, "reduction")) {
            return null;
        }
        for (String key : new String[] {"private", "firstprivate", "lastprivate"}) {
            CetusAnnotation note = loop.getAnnotation(CetusAnnotation.class, key);
            if (note != null && note.get(key) instanceof Set) {
                for (Object symbol : (Set<?>) note.get(key)) {
                    if (!(symbol instanceof Symbol) || !SymbolTools.isScalar((Symbol) symbol)) {
                        return null; // a private array carries values through the body
                    }
                }
            }
        }
        List<List<Statement>> groups = new ArrayList<>();
        List<Statement> run = null;
        for (Traversable child : loop.getBody().getChildren()) {
            if (child instanceof ForLoop) {
                run = null;
                List<Statement> alone = new ArrayList<>();
                alone.add((Statement) child);
                groups.add(alone);
            } else if (child instanceof ExpressionStatement) {
                if (run == null) {
                    run = new ArrayList<>();
                    groups.add(run);
                }
                run.add((Statement) child);
            } else {
                return null;
            }
        }
        if (groups.size() < 2) {
            return null;
        }

        // One group must be the loop the distribution is done for.
        String outerIndex = LoopTools.getIndexVariable(loop).toString();
        boolean wanted = false;
        for (List<Statement> group : groups) {
            if (!(group.get(0) instanceof ForLoop)) {
                continue;
            }
            ForLoop inner = (ForLoop) group.get(0);
            Expression innerIndex = LoopTools.getIndexVariable(inner);
            if (innerIndex != null && nestDepth(inner) == 1 && LoopTools.isCanonical(inner)
                    && isUnitAscending(inner) && !boundsDependOn(inner, loop)
                    && strides(inner, innerIndex.toString()) && !strides(inner, outerIndex)
                    && stridedDataExceedsShare(inner, innerIndex.toString())
                    && interchangeIsLegal(loop, inner)) {
                wanted = true;
            }
        }
        if (!wanted) {
            return null;
        }

        // A scalar written in the body may be met in one group only, unless
        // it is the index of that group's own loop, which the loop sets.
        Map<Symbol, Integer> home = new LinkedHashMap<>();
        Set<Symbol> written = new LinkedHashSet<>();
        for (List<Statement> group : groups) {
            for (Statement stmt : group) {
                for (Symbol symbol : DataFlowTools.getDefSymbol(stmt)) {
                    if (SymbolTools.isScalar(symbol)) {
                        written.add(symbol);
                    }
                }
            }
        }
        for (int g = 0; g < groups.size(); g++) {
            List<Statement> group = groups.get(g);
            Symbol ownIndex = group.get(0) instanceof ForLoop
                    ? LoopTools.getLoopIndexSymbol((ForLoop) group.get(0)) : null;
            for (Statement stmt : group) {
                Set<Symbol> met = new LinkedHashSet<>(DataFlowTools.getDefSymbol(stmt));
                met.addAll(DataFlowTools.getUseSymbol(stmt));
                for (Symbol symbol : met) {
                    if (!written.contains(symbol) || symbol == ownIndex) {
                        continue;
                    }
                    Integer first = home.put(symbol, g);
                    if (first != null && first != g) {
                        return null;
                    }
                }
            }
        }
        return groups;
    }

    /**
     * The dependence vectors that constrain a reordering of {@code nestLoops},
     * a nest that sits under other loops. The range test of Cetus reports
     * each vector over all the loops around a statement. A vector whose
     * leftmost non-{@code =} entry belongs to an enclosing loop is carried
     * by that loop: its source and sink lie in different iterations of it,
     * so no reordering inside one iteration can reverse it. Read on the nest
     * loops alone, such a vector looks like a constraint (trmm, lu: the
     * read of {@code B[k][j]}, {@code k > i}, against the write of
     * {@code B[i][j]}), and the nest was left alone. Those vectors are
     * dropped here. A {@code *} at an enclosing loop proves nothing and the
     * vector is kept.
     *
     * @return null when the dependence graph has no vector for the nest
     */
    private List<DependenceVector> vectorsInsideEnclosingLoops(ForLoop nestRoot,
            List<? extends Loop> nestLoops) {
        LinkedList<Loop> enclosing = new LinkedList<>();
        Traversable up = nestRoot.getParent();
        while (up != null && !(up instanceof Procedure)) {
            if (up instanceof ForLoop) {
                enclosing.addFirst((Loop) up);
            }
            up = up.getParent();
        }
        LinkedList<Loop> all = new LinkedList<>(enclosing);
        all.addAll(nestLoops);
        List<DependenceVector> matrix = program.getDDGraph() == null ? null
                : program.getDDGraph().getDirectionMatrix(all);
        if (matrix == null) {
            return null;
        }
        List<DependenceVector> kept = new ArrayList<>();
        boolean any = false;
        for (DependenceVector row : matrix) {
            if (!EmptyDvAuditor.isNestUsable(row, new ArrayList<Loop>(nestLoops))) {
                continue;
            }
            any = true;
            boolean carriedOutside = false;
            for (Loop loop : enclosing) {
                int d = DirectionVectorLemmas.directionOf(row, loop);
                if (d == DependenceVector.nil || d == DependenceVector.equal) {
                    continue;
                }
                carriedOutside = d == DependenceVector.less;
                break;
            }
            if (!carriedOutside) {
                // Projected on the nest: the tiler accepts no other loop.
                DependenceVector inNest = new DependenceVector(
                        new LinkedList<Loop>(nestLoops));
                for (Loop loop : nestLoops) {
                    inNest.setDirection(loop, DirectionVectorLemmas.directionOf(row, loop));
                }
                kept.add(inNest);
            }
        }
        return any ? kept : null;
    }

    /**
     * Whether {@code inner} may be moved outside {@code outer}, judged on the
     * dependence vectors that involve both loops. Asked before a loop is
     * distributed: a distribution that the interchange cannot follow only
     * multiplies the parallel regions (trmm).
     */
    private boolean interchangeIsLegal(ForLoop outer, ForLoop inner) {
        LinkedList<Loop> pair = new LinkedList<>();
        pair.add(outer);
        pair.add(inner);
        List<Loop> swapped = new ArrayList<>();
        swapped.add(inner);
        swapped.add(outer);
        List<DependenceVector> inside = vectorsInsideEnclosingLoops(outer, pair);
        if (inside != null) {
            return DirectionVectorLemmas.isLegal(inside, swapped);
        }
        EmptyDvAuditor.Result audit = EmptyDvAuditor.audit(outer, pair,
                program.getDDGraph());
        if (audit.status == EmptyDvAuditor.Status.SUSPECT) {
            return false;
        }
        return DirectionVectorLemmas.isLegal(audit.dvs, swapped);
    }

    /**
     * Whether the arrays that {@code index} walks across cache lines (its
     * index sits in a subscript other than the last) are larger than one
     * thread's share of the cache. Only then does a stride interchange pay:
     * an array that fits the share is reused from cache anyway, and the
     * interchange would move the loop that accumulates into a scalar or a
     * register outward, turning that accumulation into a memory update per
     * iteration (doitgen: C4 is 583 KB, PAW ran at 1.87x against 8.28x for
     * parallel-only code). An array whose declared size is not a literal is
     * assumed to be large.
     */
    private boolean stridedDataExceedsShare(ForLoop loop, String index) {
        SymbolTable symbols = VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent());
        long share = tilingParams.getCacheSizeInKB() * 1024L
                / Math.max(isSerialTiling() ? 1 : tilingParams.getNumOfProcessors(), 1);
        int bytes = NTSelectionAlgo.elementBytes(loop);
        long total = 0;
        Set<String> seen = new LinkedHashSet<>();
        DFIterator<ArrayAccess> accesses = new DFIterator<>(loop, ArrayAccess.class);
        while (accesses.hasNext()) {
            ArrayAccess access = accesses.next();
            boolean strided = false;
            for (int d = 0; d < access.getNumIndices() - 1; d++) {
                strided |= ReuseOrderAnalyzer.expressionContainsName(access.getIndex(d), index);
            }
            Expression name = access.getArrayName();
            if (!strided || !(name instanceof IDExpression) || !seen.add(name.toString())) {
                continue;
            }
            Expression size = declaredArraySize(symbols, (IDExpression) name);
            long elems = size == null ? -1 : LiteralExpr.asPositiveLiteral(Symbolic.simplify(size));
            if (elems <= 0) {
                return true;
            }
            total += elems * bytes;
        }
        return total > share;
    }

    /** True when {@code index} sits in a subscript other than the last one. */
    private static boolean strides(ForLoop loop, String index) {
        DFIterator<ArrayAccess> accesses = new DFIterator<>(loop, ArrayAccess.class);
        while (accesses.hasNext()) {
            ArrayAccess access = accesses.next();
            for (int d = 0; d < access.getNumIndices() - 1; d++) {
                if (ReuseOrderAnalyzer.expressionContainsName(access.getIndex(d), index)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * {@code -paw_tiling=2}: the baseline PAW is compared with, a tiler that
     * runs after parallelization and knows nothing about it. It must not
     * touch a loop that is already parallel, so it tiles only the loops
     * strictly inside one, with a tile sized for a single thread, and it
     * never adds or moves a parallel loop. A nest with no parallel loop is
     * tiled as in serial tiling.
     */
    private boolean isPostParallelTiling() {
        return "2".equals(Driver.getOptionValue(PASS_NAME));
    }

    /**
     * For {@code -paw_tiling=2}: the part of a perfect nest that lies inside
     * its outermost parallel loop, or the nest itself when no loop of it is
     * parallel. Null when fewer than two loops remain to tile.
     */
    private static ForLoop belowParallelLoop(ForLoop nest) {
        List<ForLoop> chain = ReuseOrderAnalyzer.nestLoops(nest);
        for (int d = 0; d < chain.size(); d++) {
            if (chain.get(d).containsAnnotation(CetusAnnotation.class, "parallel")) {
                return chain.size() - (d + 1) >= 2 ? chain.get(d + 1) : null;
            }
        }
        return nest;
    }

    @Override
    public void start() {

        // Establish the algorithm's precondition: canonical memory order.
        try {
            TransformPass.run(new LoopInterchange(program));
        } catch (Exception e) {
            PrintTools.printlnDebug("LoopInterchange failed: " + e);
        }

        // LoopInterchange looks only at nests rooted at an outermost loop.
        if (!isPostParallelTiling()) {
            // Imperfect parallel loops are split first, so that the loop
            // around a striding inner loop becomes a perfect pair.
            if (!isSerialTiling() && distributeParallelLoops()) {
                LoopTools.addLoopName(program, true);
                AnalysisPass.run(new DDTDriver(program));
            }
            if (interchangeInnerSubnests()) {
                // The dependence graph is keyed by loop objects whose
                // headers have just been swapped.
                AnalysisPass.run(new DDTDriver(program));
            }
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
            for (Map.Entry<ForLoop, String> entry : coarsen.entrySet()) {
                restoreInnerParallel(entry.getKey(), entry.getValue());
            }
            // Interchange already ran. A parallel annotation can now sit on
            // a loop that writes locations its index does not cover.
            revokeStaleParallelism();
            PrintTools.printlnDebug("No tilable loops found.");
            return;
        }

        List<TiledLoop> updatedLoops = new ArrayList<>();
        for (ForLoop targetLoop : validLoops) {
            TiledLoop newLoop = null;
            try {
                newLoop = processLoop(targetLoop);
                if (newLoop != null) {
                    updatedLoops.add(newLoop);
                }
            } catch (Exception e) {
                e.printStackTrace();
                PrintTools.print(
                        String.format("Error processing loop %s: %s", targetLoop.toString(), e.getMessage()), 0);
            }
            if (newLoop == null && coarsen.containsKey(targetLoop)) {
                // The nest stays as interchanged. Its parallel loop is the
                // inner one now; mark it, so that the parallelism is kept.
                restoreInnerParallel(targetLoop, coarsen.get(targetLoop));
            }
        }

        for (Map.Entry<ForLoop, String> entry : coarsen.entrySet()) {
            if (!validLoops.contains(entry.getKey())) {
                restoreInnerParallel(entry.getKey(), entry.getValue());
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
        if (coarsen.containsKey(targetLoop)) {
            // The interchange of this nest was judged without the vectors
            // that an enclosing loop carries; its tile loop is judged alike.
            List<DependenceVector> inside = vectorsInsideEnclosingLoops(targetLoop, nestLoops);
            if (inside != null) {
                originalDvs = inside;
            }
        }

        List<ForLoop> reuseOrder = ReuseOrderAnalyzer.reuseOrder(targetLoop);
        int depth = tilingParams.getTilingLevel() <= 0
                ? nestLoops.size()
                : tilingParams.getTilingLevel();
        List<ForLoop> candidates = reuseOrder.subList(0,
                Math.min(depth, reuseOrder.size()));

        // Coarsening: tile only the loop that holds the parallelism, with a
        // raw size of the whole loop; balancing turns that into one tile per
        // thread and the tile loop, hoisted outermost, becomes the parallel
        // loop.
        String coarsenIndex = isSerialTiling() ? null : coarsen.get(targetLoop);
        Map<Expression, Expression> rawSizes;
        List<ForLoop> browseCandidates = new ArrayList<>();
        if (coarsenIndex != null) {
            rawSizes = new LinkedHashMap<>();
            for (ForLoop candidate : ReuseOrderAnalyzer.nestLoops(targetLoop)) {
                Expression indexVar = LoopTools.getIndexVariable(candidate);
                if (indexVar != null && indexVar.toString().equals(coarsenIndex)) {
                    rawSizes.put(indexVar, ReuseOrderAnalyzer.tripCount(candidate));
                    browseCandidates.add(candidate);
                }
            }
            candidates = new ArrayList<>(browseCandidates);
        } else {
            // Raw tile sizes for the browse candidates (method M).
            rawSizes = tilingParams
                    .getTileSizeSelectionAlgo().getTileSizes(targetLoop, candidates);
        }
        for (ForLoop candidate : coarsenIndex != null ? new ArrayList<ForLoop>() : candidates) {
            Expression indexVar = LoopTools.getIndexVariable(candidate);
            Expression raw = indexVar == null ? null : rawSizes.get(indexVar);
            if (raw == null || "1".equals(raw.toString())) {
                continue;
            }
            // Strip-mining a loop whose tile covers the whole trip is a
            // no-op that only adds extra loop overhead (and extra L3
            // traffic from worse prefetch). Skip it.
            Expression trip = ReuseOrderAnalyzer.tripCount(candidate);
            // A symbolic bound still has an upper limit when the index
            // subscripts an array of declared size (PolyBench passes n as
            // a parameter): heat-3d got a 2424-wide tile on a 200-point
            // grid, one tile per loop.
            long tripLit = ReuseOrderAnalyzer.estimatedTrip(candidate, targetLoop);
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

        // ---------- Reuse check: is there anything for a tile to keep? ----
        // Two cases are left untiled. (1) No loop of the nest re-touches
        // data (a stencil sweep, an NPB rhs sweep): a tile reorders nothing
        // that is reused. (2) The data touched between two uses already
        // fits in one thread's share of the cache and the innermost loop is
        // contiguous: the reuse is captured as it is, and a tile only adds
        // bounds and shortens the unit-stride runs (the k,j subnest of
        // gemm). A nest whose innermost loop strides across lines is kept,
        // because there a tile restores line reuse (syrk, syr2k).
        if (coarsenIndex == null && tilingParams.isEnableReuseCheck()) {
            int bytes = NTSelectionAlgo.elementBytes(targetLoop);
            long share = tilingParams.getCacheSizeInKB() * 1024L / bytes
                    / Math.max(tilingParams.getNumOfProcessors(), 1);
            long distance = ReuseDistance.longest(targetLoop);
            boolean strides = ReuseDistance.innermostLoopStrides(targetLoop);
            PrintTools.printlnDebug("[paw] reuse distance=" + distance
                    + " elements, per-thread cache share=" + share
                    + ", innermost strides=" + strides);
            if (distance == 0) {
                PrintTools.printlnDebug("[paw] not tiled: "
                        + LoopTools.getLoopName(targetLoop)
                        + " has no temporal reuse to capture");
                return null;
            }
            if (distance != ReuseDistance.UNKNOWN && distance <= share && !strides) {
                PrintTools.printlnDebug("[paw] not tiled: "
                        + LoopTools.getLoopName(targetLoop)
                        + " already reuses its data within the cache");
                return null;
            }
        }

        // ---------- Phase 4 precondition: profitability is queried while
        // the original nest is still attached to the program ----------
        Expression totalIterations = calculateTotalOfInstructions(targetLoop);
        Expression cacheElems = calculateCacheInNumberOfElements(targetLoop,
                tilingParams.getCacheSizeInKB());
        Expression footprint = calculateDataFullSize(targetLoop);

        StaticProfitability.Decision decision = coarsenIndex != null
                ? StaticProfitability.Decision.PROVE_TILED
                : tilingParams.isEnableTilingProfitability()
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
        boolean enumerate = coarsenIndex == null
                && "enumerate".equals(Driver.getOptionValue("paw_browse"));
        long searchStart = System.nanoTime();
        BrowseResult browse = enumerate
                ? CandidateBrowser.enumerate(targetLoop, originalDvs,
                        browseCandidates, depth, symtab, nestTag)
                : CandidateBrowser.browse(targetLoop, originalDvs,
                        browseCandidates, depth, symtab, nestTag);
        // One line per nest, read by the browse-order comparison.
        PrintTools.println(String.format("[paw-search] nest=%s mode=%s candidates=%d tried=%d legal=%d chosen=%s micros=%d",
                nestTag, enumerate ? "enumerate" : "reuse", browseCandidates.size(), browse.tried,
                browse.legal, browse.symbolicSizes.keySet(), (System.nanoTime() - searchStart) / 1000), 0);
        if (browse.nest == null) {
            PrintTools.printlnDebug("[paw] no legal tiled version; nest untouched");
            return null;
        }

        // ---------- Phase 3: tile-size selection and balancing ----------
        // An enclosing loop that the parallelizer already proved parallel
        // keeps its region: one fork for the whole nest. Moving OpenMP to a
        // tile loop inside it forks once per outer iteration (gemm entered
        // a region per row and per tile). The tiles then run serially
        // inside each thread.
        boolean insideParallel = !isSerialTiling() && enclosingParallelLoop(targetLoop) != null;

        Loop parallelLoop = insideParallel || isPostParallelTiling() ? null
                : DirectionVectorLemmas.outermostParallelLoop(browse.dvs, browse.order);
        String parallelName = parallelLoop == null ? null
                : DirectionVectorLemmas.loopName(parallelLoop);
        if (coarsenIndex != null && (parallelName == null
                || !parallelName.equals(coarsenIndex + Tiler.CROSS_TILE_SUFFIX))) {
            PrintTools.printlnDebug("[paw] coarsening: the tile loop of "
                    + coarsenIndex + " is not the parallel loop; nest untouched");
            return null;
        }

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
            // Every parallel tile loop is balanced, also in a nest under a
            // sequential loop. Left at the raw size, a tile wider than the
            // loop gave the parallel loop one iteration (stencils, NPB rhs).
            boolean isParallelTileLoop = parallelName != null
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
        // A loop marked "cetus for" is the work-sharing loop of a parallel
        // region that ReductionTransform built around private reduction
        // arrays. The region stays, so the tiled nest must keep exactly one
        // work-sharing loop and it has to be the outermost tile loop.
        // Without it every thread runs the whole nest (PolyBench bicg). When
        // the parallelism lemma does not pick that loop, leave the nest
        // untiled.
        boolean workShared = !isSerialTiling()
                && targetLoop.containsAnnotation(CetusAnnotation.class, "for");
        if (workShared && parallelLoop != browse.nest) {
            PrintTools.printlnDebug("[paw] work-sharing loop: outermost tile loop"
                    + " is not parallel; nest untouched");
            return null;
        }
        // Stale parallel annotations do not survive tiling (Theorem 1).
        stripParallelAnnotations(browse.nest);
        if (workShared) {
            browse.nest.annotate(new CetusAnnotation("for", ""));
        } else if (!isSerialTiling() && parallelLoop instanceof Annotatable) {
            // Annotate the loop the parallelism lemma selected. Hoisting that
            // annotation onto an enclosing loop races on scratch arrays the
            // outer iterations share (NPB FT's plane[]).
            CetusAnnotation note = new CetusAnnotation();
            note.put("parallel", "true");
            ((Annotatable) parallelLoop).annotate(note);
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

    /** Nearest enclosing loop that carries {@code cetus parallel}, or null. */
    private static ForLoop enclosingParallelLoop(ForLoop loop) {
        Traversable t = loop.getParent();
        while (t != null && !(t instanceof Procedure)) {
            if (t instanceof ForLoop && ((ForLoop) t)
                    .containsAnnotation(CetusAnnotation.class, "parallel")) {
                return (ForLoop) t;
            }
            t = t.getParent();
        }
        return null;
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

        // Privatization runs again on the tiled nest and can put a whole
        // array back into firstprivate/lastprivate. That races when the
        // array is partitioned by the parallel loop index.
        DFIterator<ForLoop> parallelLoops =
                new DFIterator<ForLoop>(program, ForLoop.class);
        while (parallelLoops.hasNext()) {
            ForLoop loop = parallelLoops.next();
            if (loop.containsAnnotation(CetusAnnotation.class, "parallel")
                    || loop.containsAnnotation(CetusAnnotation.class, "for")) {
                LoopParallelizationPass.shareIndexedArrays(loop);
            }
        }
        revokeStaleParallelism();

        // The OpenMP profitability model is left as the user set it.
        // ProfitableOMP skips tile loops itself, so the other parallel loops
        // of the file are judged the same way with and without tiling.
    }

    private void revokeStaleParallelism() {
        DFIterator<ForLoop> loops =
                new DFIterator<ForLoop>(program, ForLoop.class);
        while (loops.hasNext()) {
            LoopParallelizationPass.revokeIllegalParallel(loops.next());
        }

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
