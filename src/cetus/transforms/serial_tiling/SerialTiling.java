package cetus.transforms.serial_tiling;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.exec.Driver;
import cetus.hir.Annotatable;
import cetus.hir.Annotation;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.CetusAnnotation;
import cetus.hir.CodeAnnotation;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IRTools;
import cetus.hir.Loop;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Procedure;
import cetus.hir.Program;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.SymbolTools;
import cetus.hir.Symbolic;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.TransformPass;
import cetus.transforms.paw_tiling.TiledLoop;
import cetus.transforms.paw_tiling.TilingParams;
import cetus.transforms.paw_tiling.analysis.EmptyDvAuditor;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;
import cetus.transforms.paw_tiling.browse.CandidateBrowser;
import cetus.transforms.paw_tiling.browse.CandidateBrowser.BrowseResult;
import cetus.transforms.paw_tiling.profitability.ReuseDistance;
import cetus.transforms.paw_tiling.tile_size.LiteralExpr;
import cetus.transforms.paw_tiling.tile_size.NTSelectionAlgo;
import cetus.transforms.paw_tiling.tile_size.TileSizeSelectionAlgo;
import cetus.utils.ExperimentalSectionUtils;
import cetus.utils.VariableDeclarationUtils;

/**
 * A tiler that knows nothing about parallelism ({@code -serial_tiling}). It
 * is the baseline that Parallel-Aware Tiling is compared with, and it is
 * what a compiler has when tiling and parallelization are separate phases:
 *
 * <ul>
 * <li>tile first: this pass on the serial program, then the parallelizer on
 * its output;</li>
 * <li>tile after: the parallelizer, then this pass on the parallel
 * program.</li>
 * </ul>
 *
 * The program is treated as sequential code. Each perfect canonical nest is
 * strip-mined and its tile loops are hoisted, in decreasing reuse order, as
 * long as the sequential dependence vectors stay lexicographically
 * non-negative. Tile sizes assume one thread that owns the whole cache.
 *
 * What the pass does not do is the point of it. It reads no core count, it
 * selects no parallel loop, it balances no tile loop, and it runs no
 * privatization, dependence or reduction analysis afterwards. A pragma is
 * text attached to a loop statement: it stays on the loop that keeps the
 * original index (the in-tile loop) and its clauses are not rewritten. So an
 * OpenMP loop that is tiled forks once per tile, and tile indices introduced
 * inside an existing parallel region are not added to its private list.
 */
public class SerialTiling extends TransformPass {

    public static final String PASS_NAME = "serial_tiling";

    private static final CodeAnnotation MIN_MAX_MACROS = new CodeAnnotation(
            "#define MIN(a,b) ((a)<(b)?(a):(b))\n"
                    + "#define MAX(a,b) ((a)>(b)?(a):(b))\n");

    private final TilingParams params;

    public SerialTiling(Program program) {
        super(program);
        params = TilingParams.getTilingParams();
    }

    @Override
    public String getPassName() {
        return "[Serial Tiling]";
    }

    @Override
    public void start() {
        List<ForLoop> nests = collectNests();
        int tiled = 0;
        for (ForLoop nest : nests) {
            try {
                if (tile(nest)) {
                    tiled++;
                }
            } catch (Exception e) {
                PrintTools.println("[serial-tiling] " + LoopTools.getLoopName(nest)
                        + " left untiled: " + e.getMessage(), 0);
            }
        }
        if (tiled > 0) {
            for (Traversable t : program.getChildren()) {
                if (t instanceof TranslationUnit) {
                    ((TranslationUnit) t).addDeclarationFirst(
                            new AnnotationDeclaration(MIN_MAX_MACROS));
                }
            }
            LoopTools.addLoopName(program, true);
        }
        PrintTools.printlnDebug("[serial-tiling] tiled " + tiled + " of "
                + nests.size() + " nests");
    }

    // ------------------------------------------------------------ nests

    private List<ForLoop> collectNests() {
        LinkedHashSet<ForLoop> nests = new LinkedHashSet<>();
        List<List<Traversable>> sections =
                ExperimentalSectionUtils.findExperimentalSections(program);
        if (sections.isEmpty()) {
            nests.addAll(maximalPerfectNests(program));
        } else {
            for (List<Traversable> section : sections) {
                for (Traversable t : section) {
                    nests.addAll(maximalPerfectNests(t));
                }
            }
        }
        return new ArrayList<>(nests);
    }

    /** Maximal perfect canonical nests of depth two or more. */
    private List<ForLoop> maximalPerfectNests(Traversable root) {
        List<ForLoop> nests = new ArrayList<>();
        DFIterator<ForLoop> iter = new DFIterator<>(root, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            if (!isTilable(loop) || hasTilableAncestor(loop) || isSetupOrOutput(loop)
                    || ReuseOrderAnalyzer.nestLoops(loop).size() < 2) {
                continue;
            }
            nests.add(loop);
        }
        return nests;
    }

    /**
     * Sequential legality needs the dependence vectors of the nest. Cetus
     * builds none for a loop it cannot test, and vectors describe array
     * accesses only, so a nest with a scalar that carries a value between
     * iterations is left alone.
     */
    private boolean isTilable(ForLoop loop) {
        if (!LoopTools.isPerfectNest(loop) || !LoopTools.isCanonical(loop)) {
            return false;
        }
        DFIterator<ForLoop> nest = new DFIterator<>(loop, ForLoop.class);
        while (nest.hasNext()) {
            ForLoop member = nest.next();
            if (!LoopTools.checkDataDependenceEligibility(member)
                    || !carriedScalars(member, loop).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Set<Expression> carriedScalars(ForLoop member, ForLoop nest) {
        Set<Expression> result = new LinkedHashSet<>(
                LoopTools.collectScalarDependences(member));
        if (result.isEmpty()) {
            return result;
        }
        Set<Symbol> indices = new LinkedHashSet<>();
        DFIterator<ForLoop> loops = new DFIterator<>(nest, ForLoop.class);
        while (loops.hasNext()) {
            Symbol index = LoopTools.getLoopIndexSymbol(loops.next());
            if (index != null) {
                indices.add(index);
            }
        }
        result.removeIf(expr -> indices.contains(SymbolTools.getSymbolOf(expr)));
        return result;
    }

    private boolean hasTilableAncestor(ForLoop loop) {
        Traversable parent = loop.getParent();
        while (parent != null) {
            if (parent instanceof ForLoop && isTilable((ForLoop) parent)) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isSetupOrOutput(ForLoop loop) {
        Procedure proc = IRTools.getAncestorOfType(loop, Procedure.class);
        String name = proc == null ? null : proc.getSymbolName();
        return name != null && (name.startsWith("init_array") || name.startsWith("print_array"));
    }

    // ------------------------------------------------------------- tiling

    /** Tile sizes for one thread that owns the whole cache. */
    private TileSizeSelectionAlgo sizeMethod() {
        if (params.getTypeSelectionAlgo() == TilingParams.SelectionAlgorithm.NT) {
            return new NTSelectionAlgo(params.getCacheSizeInKB(),
                    params.getCacheLineInBytes(), 1);
        }
        return params.getTileSizeSelectionAlgo();
    }

    private boolean tile(ForLoop nest) throws Exception {
        String name = LoopTools.getLoopName(nest);
        SymbolTable symtab = VariableDeclarationUtils
                .getVariableDeclarationSpace(nest.getParent());

        LinkedList<Loop> nestLoops = new LinkedList<>();
        new DFIterator<Loop>(nest, Loop.class).forEachRemaining(nestLoops::add);
        List<DependenceVector> dvs = EmptyDvAuditor.audit(nest, nestLoops,
                program.getDDGraph()).dvs;

        List<ForLoop> reuseOrder = ReuseOrderAnalyzer.reuseOrder(nest);
        int depth = params.getTilingLevel() <= 0 ? nestLoops.size() : params.getTilingLevel();
        List<ForLoop> candidates = reuseOrder.subList(0, Math.min(depth, reuseOrder.size()));

        Map<Expression, Expression> sizes = sizeMethod().getTileSizes(nest, candidates);
        List<ForLoop> toTile = new ArrayList<>();
        for (ForLoop candidate : candidates) {
            Expression index = LoopTools.getIndexVariable(candidate);
            Expression size = index == null ? null : sizes.get(index);
            if (size == null || "1".equals(size.toString())) {
                continue;
            }
            // A tile as wide as the loop is no tile.
            long trip = ReuseOrderAnalyzer.estimatedTrip(candidate, nest);
            long literal = LiteralExpr.asPositiveLiteral(size);
            if (trip > 0 && literal > 0 && literal >= trip) {
                continue;
            }
            toTile.add(candidate);
        }
        if (toTile.isEmpty()) {
            PrintTools.printlnDebug("[serial-tiling] " + name + ": no usable tile size");
            return false;
        }

        // The profitability test of the tiler, for one thread: the cache it
        // compares the reuse distance with is the whole cache.
        if (params.isEnableReuseCheck()) {
            long cache = params.getCacheSizeInKB() * 1024L / NTSelectionAlgo.elementBytes(nest);
            long distance = ReuseDistance.longest(nest);
            if (distance == 0 || (distance != ReuseDistance.UNKNOWN && distance <= cache
                    && !ReuseDistance.innermostLoopStrides(nest))) {
                PrintTools.printlnDebug("[serial-tiling] " + name
                        + ": no reuse for a tile to capture (distance " + distance + ")");
                return false;
            }
        }

        String tag = name == null ? ("nest" + System.identityHashCode(nest))
                : name.replaceAll("[^A-Za-z0-9_]", "_");
        BrowseResult browse = CandidateBrowser.browse(nest, dvs, toTile, depth, symtab, tag);
        if (browse.nest == null) {
            PrintTools.printlnDebug("[serial-tiling] " + name + ": no legal tiled version");
            return false;
        }

        for (Map.Entry<String, IDExpression> entry : browse.symbolicSizes.entrySet()) {
            for (ForLoop candidate : toTile) {
                Expression index = LoopTools.getIndexVariable(candidate);
                if (index != null && index.toString().equals(entry.getKey())) {
                    VariableDeclarationUtils.replaceVariableDeclaration(symtab,
                            entry.getValue(), Symbolic.simplify(sizes.get(index)));
                }
            }
        }

        keepPragmas(nest, browse.nest);
        replace(nest, browse.nest);
        return true;
    }

    /**
     * Pragmas are carried over as they are. Each one goes to the loop of the
     * tiled nest that iterates the same index variable, which is the in-tile
     * loop when that loop was strip-mined. Analysis notes of Cetus are
     * dropped, because they describe the nest as it was.
     */
    private static void keepPragmas(ForLoop original, TiledLoop tiled) {
        List<ForLoop> tiledLoops = new ArrayList<>();
        new DFIterator<ForLoop>(tiled, ForLoop.class).forEachRemaining(tiledLoops::add);
        for (ForLoop loop : tiledLoops) {
            loop.removeAnnotations(PragmaAnnotation.class);
        }
        DFIterator<ForLoop> iter = new DFIterator<>(original, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop source = iter.next();
            Expression index = LoopTools.getIndexVariable(source);
            ForLoop target = null;
            for (ForLoop loop : tiledLoops) {
                Expression other = LoopTools.getIndexVariable(loop);
                if (index != null && other != null && index.toString().equals(other.toString())) {
                    target = loop;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            for (Annotation note : source.getAnnotations()) {
                if (note instanceof CetusAnnotation) {
                    continue;
                }
                if (note instanceof PragmaAnnotation) {
                    ((Annotatable) target).annotate(note.clone());
                } else if (note instanceof CodeAnnotation && source == original) {
                    tiled.annotateBefore(note.clone());
                }
            }
        }
    }

    private static void replace(ForLoop original, ForLoop tiled) {
        Traversable parent = original.getParent();
        List<Traversable> children = parent.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == original) {
                parent.setChild(i, tiled);
                return;
            }
        }
    }

    /** True when the option that runs this pass is set. */
    public static boolean isRequested() {
        String value = Driver.getOptionValue(PASS_NAME);
        return value != null && !value.equals("0");
    }
}
