package cetus.transforms.paw_tiling.instrumentation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cetus.exec.Driver;
import cetus.analysis.LoopTools;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.AnnotationStatement;
import cetus.hir.CodeAnnotation;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.ForLoop;
import cetus.hir.GotoStatement;
import cetus.hir.IRTools;
import cetus.hir.OmpAnnotation;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Procedure;
import cetus.hir.Program;
import cetus.hir.ReturnStatement;
import cetus.hir.Statement;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.TransformPass;

/**
 * Post-tiling PAPI + wall-clock instrumentation (thesis ch. 5 metrics).
 *
 * <p><b>Regions.</b> Outermost for-loops of the procedures named by
 * {@code -papi-functions}, else of procedures named {@code kernel_*}. The
 * label is {@code <procedure>#<n>}. The rule looks only at the procedure, so
 * a serial baseline and its parallel or tiled variants measure the same set
 * and their sums can be compared. Pragma-driven selection
 * ({@code c_paw_tiling}, {@code c_paw_measure}, OpenMP) is a fallback for
 * programs with neither.
 *
 * <p><b>Runtime.</b> One free-running event set per thread; a region is the
 * difference of two samples, summed over the OpenMP team when the program
 * has any parallel pragma. Only the dynamically outermost region is
 * measured. Each region accumulates over all its calls and is printed once
 * at exit:
 *
 * <pre>
 * [cetus-papi] kernel_3mm#1 TIME_NS=&lt;n&gt; &lt;EVENT&gt;=&lt;v&gt; ... CALLS=&lt;c&gt;
 * [cetus-papi] program TIME_NS=&lt;n&gt; &lt;EVENT&gt;=&lt;v&gt; ... REGION_NS=&lt;n&gt; CALLS=&lt;c&gt; PAPI_ERRORS=&lt;e&gt;
 * </pre>
 *
 * <p>On the program line the counters and {@code REGION_NS} are sums over the
 * region lines. {@code TIME_NS} is the wall time of {@code main} when
 * {@code main} is in the instrumented file, else it equals
 * {@code REGION_NS}. Compare variants on {@code REGION_NS}: the wall time of
 * {@code main} includes array setup.
 */
public class PAPIInstrumentation extends TransformPass {

    public static final String PASS_NAME = "papi_instrument";
    public static final String EVENTS_PARAM_NAME = "papi-events";
    public static final String DEFAULT_EVENTS = "PAPI_L3_DCM,PAPI_TOT_CYC";
    public static final String PASS_CMD_DESCR =
            "Instrument regions + main with PAPI counters and wall time (=1 enable)";
    public static final String EVENTS_PARAM_DESCR =
            "Comma-separated PAPI event names measured around each region"
                    + " (default " + DEFAULT_EVENTS + ")";

    public static final String FUNCTIONS_PARAM_NAME = "papi-functions";
    public static final String FUNCTIONS_PARAM_DESCR =
            "Comma-separated procedures whose outermost for-loops are the"
                    + " measured regions (default: procedures named kernel_*)";

    private static final String TILING_PRAGMA_PREFIX = "c_paw_tiling";
    private static final String MEASURE_PRAGMA_PREFIX = "c_paw_measure";

    private final String[] events;
    /** Procedures named by {@code -papi-functions}; empty when not given. */
    private final Set<String> functions = new LinkedHashSet<>();
    private final List<String> labels = new ArrayList<>();
    /** Sample the whole OpenMP team: true when the program has a parallel pragma. */
    private boolean teamCounters = false;

    public PAPIInstrumentation(Program program) {
        super(program);
        String option = Driver.getOptionValue(EVENTS_PARAM_NAME);
        String eventList = (option == null || option.trim().isEmpty())
                ? DEFAULT_EVENTS
                : option;
        events = eventList.trim().split("\\s*,\\s*");
        String funcs = Driver.getOptionValue(FUNCTIONS_PARAM_NAME);
        if (funcs != null) {
            for (String name : funcs.split("[,\\s]+")) {
                if (!name.isEmpty()) {
                    functions.add(name);
                }
            }
        }
    }

    @Override
    public String getPassName() {
        return "[PAPI-Instrumentation]";
    }

    @Override
    public void start() {
        LoopTools.addLoopName(program, false);
        List<Statement> regions = new ArrayList<>();
        for (Statement region : findInstrumentableRegions()) {
            if (leavesEarly(region)) {
                PrintTools.println("[papi] region skipped: it contains return or goto", 0);
                continue;
            }
            if (!(region.getParent() instanceof CompoundStatement)) {
                continue;
            }
            regions.add(region);
        }
        teamCounters = programHasOmpParallel();
        // Labels are fixed before any wrap is inserted.
        for (int i = 0; i < regions.size(); i++) {
            labels.add(regionLabel(regions.get(i), i));
        }

        Set<TranslationUnit> touchedUnits = new LinkedHashSet<>();
        // Program wrap first so its start sits before any region prologues.
        for (Procedure main : findMainProcedures()) {
            instrumentProgram(main, regions.isEmpty());
            TranslationUnit tu = IRTools.getAncestorOfType(main, TranslationUnit.class);
            if (tu != null) {
                touchedUnits.add(tu);
            }
        }

        int regionId = 0;
        for (Statement region : regions) {
            instrumentRegion(region, regionId++);
            TranslationUnit tu = IRTools.getAncestorOfType(region, TranslationUnit.class);
            if (tu != null) {
                touchedUnits.add(tu);
            }
        }

        if (touchedUnits.size() > 1) {
            PrintTools.println("[papi] WARNING: regions span " + touchedUnits.size()
                    + " translation units; each prints its own program line", 0);
        }
        for (TranslationUnit tu : touchedUnits) {
            tu.addDeclarationFirst(new AnnotationDeclaration(
                    new CodeAnnotation(headerBlob())));
        }

        PrintTools.printlnDebug("[papi] instrumented " + regionId
                + " region(s) + program wrap");
    }

    /**
     * The region set must be the same in every variant of a kernel, or the
     * sums cannot be compared. So the first two rules look only at the
     * procedure a loop lives in, never at what a transformation left on it:
     * <ol>
     *   <li>{@code -papi-functions=f,g}: outermost for-loops of those
     *       procedures (NPB routines);</li>
     *   <li>procedures named {@code kernel_*} (PolyBench).</li>
     * </ol>
     * Only programs with neither fall back to the pragma-driven rules, which
     * depend on the variant.
     */
    private List<Statement> findInstrumentableRegions() {
        if (!functions.isEmpty()) {
            List<Statement> named = findOutermostLoopsIn(false);
            if (!named.isEmpty()) {
                return named;
            }
            PrintTools.println("[papi] WARNING: no loop found in -"
                    + FUNCTIONS_PARAM_NAME + "=" + functions, 0);
        }
        List<Statement> kernelLoops = findOutermostLoopsIn(true);
        if (!kernelLoops.isEmpty()) {
            return kernelLoops;
        }
        List<Statement> tiled = findOutermostMatching(loop ->
                hasTilingPragma(loop) && !isSetupOrTeardown(loop));
        if (!tiled.isEmpty()) {
            return tiled;
        }
        List<Statement> marked = findOutermostMatching(loop ->
                !isSetupOrTeardown(loop)
                        && (hasMeasurePragma(loop) || hasOmpParallelFor(loop)));
        if (!marked.isEmpty()) {
            return marked;
        }
        return new ArrayList<>();
    }

    /**
     * Outermost for-loops of the selected procedures: {@code kernel_*} when
     * {@code kernelPrefix} is set, else the {@code -papi-functions} list.
     */
    private List<Statement> findOutermostLoopsIn(boolean kernelPrefix) {
        List<Statement> regions = new ArrayList<>();
        for (Procedure proc : IRTools.getProcedureList(program)) {
            String name = proc.getSymbolName();
            if (name == null) {
                continue;
            }
            if (kernelPrefix ? !name.startsWith("kernel_") : !functions.contains(name)) {
                continue;
            }
            regions.addAll(unitsOf(proc));
        }
        return regions;
    }

    /**
     * Measurement units of a procedure, in source order: its outermost
     * for-loops, except that loops sitting inside one parallel block count as
     * that block. ReductionTransform turns {@code for (i) ...} into
     * {@code #pragma omp parallel { init loop; #pragma omp for (i) ...;
     * merge loop }}. Measuring the block keeps one unit where the baseline
     * has one loop, so labels still match, and it keeps the enter/exit calls
     * outside the parallel region, where wall time is counted once and not
     * once per thread.
     */
    private List<Statement> unitsOf(Procedure proc) {
        Set<Statement> units = new LinkedHashSet<>();
        DFIterator<ForLoop> iter = new DFIterator<>(proc, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            if (hasForLoopAncestorWithin(loop, proc)) {
                continue;
            }
            Statement unit = loop;
            Traversable t = loop.getParent();
            while (t != null && t != proc) {
                if (t instanceof Statement && !(t instanceof ForLoop)
                        && isParallelBlock((Statement) t)) {
                    unit = (Statement) t;
                }
                t = t.getParent();
            }
            units.add(unit);
        }
        return new ArrayList<>(units);
    }

    private static boolean isParallelBlock(Statement stmt) {
        return stmt.containsAnnotation(OmpAnnotation.class, "parallel")
                || stmt.containsAnnotation(cetus.hir.CetusAnnotation.class, "parallel");
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

    private boolean hasForLoopAncestorWithin(ForLoop loop, Procedure proc) {
        Traversable t = loop.getParent();
        while (t != null && t != proc) {
            if (t instanceof ForLoop) {
                return true;
            }
            t = t.getParent();
        }
        return false;
    }

    private List<Statement> findOutermostMatching(LoopPredicate pred) {
        List<Statement> regions = new ArrayList<>();
        DFIterator<ForLoop> iter = new DFIterator<>(program, ForLoop.class);
        while (iter.hasNext()) {
            ForLoop loop = iter.next();
            if (!pred.test(loop)) {
                continue;
            }
            if (hasMatchingAncestor(loop, pred)) {
                continue;
            }
            regions.add(loop);
        }
        return regions;
    }

    private boolean hasMatchingAncestor(ForLoop loop, LoopPredicate pred) {
        Traversable t = loop.getParent();
        while (t != null) {
            if (t instanceof ForLoop && pred.test((ForLoop) t)) {
                return true;
            }
            t = t.getParent();
        }
        return false;
    }

    private boolean hasTilingPragma(ForLoop loop) {
        return pragmaContains(loop, TILING_PRAGMA_PREFIX);
    }

    private boolean hasMeasurePragma(ForLoop loop) {
        if (pragmaContains(loop, MEASURE_PRAGMA_PREFIX)) {
            return true;
        }
        // Parser may keep unknown pragmas as sibling AnnotationStatements
        // rather than ForLoop annotations (NPB rhs.c, no kernel_* name).
        if (loop.getAnnotations() != null) {
            for (Object note : loop.getAnnotations()) {
                if (note != null && note.toString().contains(MEASURE_PRAGMA_PREFIX)) {
                    return true;
                }
            }
        }
        Traversable parent = loop.getParent();
        if (!(parent instanceof CompoundStatement)) {
            return false;
        }
        List<Traversable> children = parent.getChildren();
        int idx = children.indexOf(loop);
        for (int i = idx - 1; i >= 0; i--) {
            Traversable prev = children.get(i);
            if (!(prev instanceof AnnotationStatement)) {
                break;
            }
            String text = prev.toString();
            if (text != null && text.contains(MEASURE_PRAGMA_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOmpParallelFor(ForLoop loop) {
        if (loop.containsAnnotation(OmpAnnotation.class, "for")
                && loop.containsAnnotation(OmpAnnotation.class, "parallel")) {
            return true;
        }
        // Fallback: text search (some paths keep raw pragmas)
        List<PragmaAnnotation> pragmas = loop.getAnnotations(PragmaAnnotation.class);
        if (pragmas == null) {
            return false;
        }
        for (PragmaAnnotation pragma : pragmas) {
            String text = pragma.toString();
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase();
            if (lower.contains("omp") && lower.contains("parallel")
                    && lower.contains("for")) {
                return true;
            }
        }
        return false;
    }

    private boolean pragmaContains(ForLoop loop, String needle) {
        List<PragmaAnnotation> pragmas = loop.getAnnotations(PragmaAnnotation.class);
        if (pragmas == null) {
            return false;
        }
        for (PragmaAnnotation pragma : pragmas) {
            String text = pragma.toString();
            if (text != null && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<Procedure> findMainProcedures() {
        List<Procedure> mains = new ArrayList<>();
        for (Procedure proc : IRTools.getProcedureList(program)) {
            if ("main".equals(proc.getSymbolName())) {
                mains.add(proc);
            }
        }
        return mains;
    }

    /**
     * Wraps one region with {@code cetus_papi_enter}/{@code cetus_papi_exit}.
     * The runtime in {@link #headerBlob} keeps one accumulator per region and
     * prints it once, when the process exits.
     */
    private void instrumentRegion(Statement region, int id) {
        String label = labels.get(id);
        String frame = "cetus_papi_f_" + id;
        String before = "cetus_papi_frame " + frame + "; cetus_papi_enter(" + id
                + ", " + (teamCounters ? 1 : 0) + ", &" + frame + ");";
        String after = "cetus_papi_exit(" + id + ", &" + frame + ");";
        insertSiblingWrap(region, before, after, label);
    }

    /**
     * Region label {@code <procedure>#<n>}: n counts the measurement units
     * of the procedure ({@link #unitsOf}) in source order. Cetus gives outermost loops the same
     * name, and the count does not change when a nest is strip-mined, so the
     * serial baseline and every transformed variant report the same labels.
     */
    private String regionLabel(Statement region, int id) {
        Procedure proc = IRTools.getAncestorOfType(region, Procedure.class);
        if (proc != null) {
            int ordinal = unitsOf(proc).indexOf(region);
            if (ordinal >= 0) {
                return proc.getSymbolName() + "#" + ordinal;
            }
        }
        if (region instanceof ForLoop) {
            String name = LoopTools.getLoopName(region);
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return "region_" + id;
    }

    /** Escape a label for a C string literal. */
    private static String cString(String label) {
        return label.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** True when any statement of the program carries an OpenMP parallel pragma. */
    private boolean programHasOmpParallel() {
        DFIterator<Statement> iter = new DFIterator<>(program, Statement.class);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.containsAnnotation(OmpAnnotation.class, "parallel")) {
                return true;
            }
            List<PragmaAnnotation> pragmas = stmt.getAnnotations(PragmaAnnotation.class);
            if (pragmas != null) {
                for (PragmaAnnotation pragma : pragmas) {
                    if (isOmpParallelText(pragma.toString())) {
                        return true;
                    }
                }
            }
            if (stmt instanceof AnnotationStatement && isOmpParallelText(stmt.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOmpParallelText(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("omp") && lower.contains("parallel");
    }

    /**
     * A region left through {@code return} or {@code goto} would never reach
     * its exit call, and every later region would be treated as nested.
     */
    private static boolean leavesEarly(Statement region) {
        return !new DFIterator<ReturnStatement>(region, ReturnStatement.class)
                .getList().isEmpty()
                || !new DFIterator<GotoStatement>(region, GotoStatement.class)
                        .getList().isEmpty();
    }

    /**
     * Program wrap: wall clock of {@code main}. With no regions the body of
     * {@code main} is itself region 0, so a baseline still reports counters.
     */
    private void instrumentProgram(Procedure main, boolean mainIsRegion) {
        CompoundStatement body = main.getBody();
        if (body == null) {
            return;
        }
        StringBuilder start = new StringBuilder();
        start.append("cetus_papi_lazy_init();\n");
        start.append("cetus_papi_has_main = 1;\n");
        start.append("clock_gettime(CLOCK_MONOTONIC, &cetus_papi_prog_t0);");
        if (mainIsRegion) {
            start.append("\ncetus_papi_enter(0, ").append(teamCounters ? 1 : 0)
                    .append(", &cetus_papi_main_frame);");
        }
        String stop = "cetus_papi_main_done();";

        AnnotationStatement startStmt = new AnnotationStatement(
                new CodeAnnotation(start.toString()));
        // Insert after leading declarations only (not AnnotationStatements),
        // so later region prologues land after the program timer start.
        List<Traversable> children = body.getChildren();
        int insertAt = 0;
        while (insertAt < children.size()
                && children.get(insertAt) instanceof cetus.hir.DeclarationStatement) {
            insertAt++;
        }
        if (insertAt < children.size()) {
            body.addStatementBefore((Statement) children.get(insertAt), startStmt);
        } else {
            body.addStatement(startStmt);
        }

        List<ReturnStatement> returns =
                new DFIterator<ReturnStatement>(main, ReturnStatement.class).getList();
        if (returns.isEmpty()) {
            List<Traversable> kids = body.getChildren();
            if (!kids.isEmpty()) {
                Statement last = (Statement) kids.get(kids.size() - 1);
                body.addStatementAfter(last, new AnnotationStatement(
                        new CodeAnnotation(stop)));
            }
        } else {
            for (ReturnStatement ret : returns) {
                Traversable parent = ret.getParent();
                if (parent instanceof CompoundStatement) {
                    ((CompoundStatement) parent).addStatementBefore(ret,
                            new AnnotationStatement(new CodeAnnotation(stop)));
                }
            }
        }

        PrintTools.printlnDebug("[papi] program wrap on main (mainIsRegion="
                + mainIsRegion + ")");
    }

    private void insertSiblingWrap(Statement region, String before, String after, String label) {
        Traversable parent = region.getParent();
        if (parent instanceof CompoundStatement) {
            CompoundStatement block = (CompoundStatement) parent;
            block.addStatementBefore(region, new AnnotationStatement(
                    new CodeAnnotation(before)));
            block.addStatementAfter(region, new AnnotationStatement(
                    new CodeAnnotation(after)));
        } else {
            PrintTools.printlnDebug("[papi] region parent is not a block ("
                    + parent.getClass().getSimpleName() + "); skipping " + label);
        }
    }

    /**
     * C runtime shared by every region of one translation unit.
     *
     * <ul>
     *   <li>Each thread owns one event set. It is created and started the
     *       first time that thread is sampled and then runs until exit, so a
     *       region called a million times never creates a second set.</li>
     *   <li>A region is the difference of two samples. With {@code team=1}
     *       the sample is the sum over the OpenMP team; a region entered from
     *       inside a parallel region samples only its own thread.</li>
     *   <li>Only the dynamically outermost region is measured. A region
     *       entered while another one is active returns at once, so region
     *       lines never overlap and their sum is the program line.</li>
     *   <li>Totals are printed once from {@code atexit}.</li>
     * </ul>
     */
    private String headerBlob() {
        int n = events.length;
        int r = Math.max(1, labels.size());
        StringBuilder b = new StringBuilder();
        b.append("#ifndef CETUS_PAPI_INSTRUMENTATION\n");
        b.append("#define CETUS_PAPI_INSTRUMENTATION\n");
        // NPB UA has a global "double time". Hide libc's time() while the
        // system headers are read so the two declarations do not collide.
        b.append("#define time cetus_papi_libc_time\n");
        b.append("#include <stdio.h>\n#include <stdlib.h>\n#include <time.h>\n#include <papi.h>\n");
        b.append("#undef time\n");
        b.append("#ifdef _OPENMP\n#include <omp.h>\n#include <pthread.h>\n");
        b.append("static unsigned long cetus_papi_tid(void) {\n");
        b.append("    return (unsigned long) pthread_self();\n}\n#endif\n");
        b.append("#define CETUS_PAPI_NEV ").append(n).append("\n");
        b.append("#define CETUS_PAPI_NREG ").append(r).append("\n");
        b.append("static const char *cetus_papi_evname[CETUS_PAPI_NEV] = {");
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? "" : ", ").append('"').append(cString(events[i])).append('"');
        }
        b.append("};\n");
        b.append("static const char *cetus_papi_label[CETUS_PAPI_NREG] = {");
        if (labels.isEmpty()) {
            b.append("\"main\"");
        }
        for (int i = 0; i < labels.size(); i++) {
            b.append(i == 0 ? "" : ", ").append('"').append(cString(labels.get(i))).append('"');
        }
        b.append("};\n");
        b.append("typedef struct { long long v[CETUS_PAPI_NEV]; struct timespec t0; int active; int team; } cetus_papi_frame;\n");
        b.append("static long long cetus_papi_acc[CETUS_PAPI_NREG][CETUS_PAPI_NEV];\n");
        b.append("static long long cetus_papi_ns[CETUS_PAPI_NREG];\n");
        b.append("static long long cetus_papi_calls[CETUS_PAPI_NREG];\n");
        b.append("static int cetus_papi_initialized = 0;\n");
        b.append("static int cetus_papi_init_failed = 0;\n");
        b.append("static int cetus_papi_errors = 0;\n");
        b.append("static int cetus_papi_depth = 0;\n");
        b.append("static int cetus_papi_has_main = 0;\n");
        b.append("static long long cetus_papi_prog_ns = -1;\n");
        b.append("static struct timespec cetus_papi_prog_t0;\n");
        b.append("static cetus_papi_frame cetus_papi_main_frame;\n");
        b.append("static __thread int cetus_papi_es = PAPI_NULL;\n");
        b.append("static __thread int cetus_papi_es_state = 0;\n");
        b.append("static __thread int cetus_papi_tdepth = 0;\n");
        b.append("static void cetus_papi_report(void);\n");
        b.append("static long long cetus_papi_elapsed(const struct timespec *a) {\n");
        b.append("    struct timespec b;\n");
        b.append("    clock_gettime(CLOCK_MONOTONIC, &b);\n");
        b.append("    return (long long)(b.tv_sec - a->tv_sec) * 1000000000LL + (long long)(b.tv_nsec - a->tv_nsec);\n");
        b.append("}\n");
        b.append("static void cetus_papi_lazy_init(void) {\n");
        b.append("    if (cetus_papi_initialized) return;\n");
        b.append("#ifdef _OPENMP\n#pragma omp critical(cetus_papi_init)\n#endif\n");
        b.append("    {\n");
        b.append("        if (!cetus_papi_initialized) {\n");
        b.append("            int cetus_papi_rc = PAPI_library_init(PAPI_VER_CURRENT);\n");
        b.append("            if (cetus_papi_rc != PAPI_VER_CURRENT) {\n");
        b.append("                fprintf(stderr, \"[cetus-papi] library init failed rc=%d\\n\", cetus_papi_rc);\n");
        b.append("                cetus_papi_init_failed = 1;\n");
        b.append("            }\n");
        b.append("#ifdef _OPENMP\n");
        b.append("            else if (PAPI_thread_init(cetus_papi_tid) != PAPI_OK) {\n");
        b.append("                fprintf(stderr, \"[cetus-papi] thread init failed\\n\");\n");
        b.append("                cetus_papi_init_failed = 1;\n");
        b.append("            }\n");
        b.append("#endif\n");
        b.append("            atexit(cetus_papi_report);\n");
        b.append("            cetus_papi_initialized = 1;\n");
        b.append("        }\n");
        b.append("    }\n");
        b.append("}\n");
        b.append("/* Sample this thread's counters. The first call starts them and returns zeros. */\n");
        b.append("static void cetus_papi_read(long long *v) {\n");
        b.append("    int k, rc = PAPI_OK;\n");
        b.append("    for (k = 0; k < CETUS_PAPI_NEV; k++) v[k] = 0;\n");
        b.append("    if (cetus_papi_es_state < 0) return;\n");
        b.append("    if (cetus_papi_es_state == 0) {\n");
        b.append("        if (cetus_papi_init_failed) rc = PAPI_ENOINIT;\n");
        b.append("        if (rc == PAPI_OK) rc = PAPI_create_eventset(&cetus_papi_es);\n");
        b.append("        for (k = 0; k < CETUS_PAPI_NEV; k++)\n");
        b.append("            if (rc == PAPI_OK) rc = PAPI_add_named_event(cetus_papi_es, (char *) cetus_papi_evname[k]);\n");
        b.append("        if (rc == PAPI_OK) rc = PAPI_start(cetus_papi_es);\n");
        b.append("        cetus_papi_es_state = (rc == PAPI_OK) ? 1 : -1;\n");
        b.append("    } else {\n");
        b.append("        rc = PAPI_read(cetus_papi_es, v);\n");
        b.append("        if (rc != PAPI_OK) for (k = 0; k < CETUS_PAPI_NEV; k++) v[k] = 0;\n");
        b.append("    }\n");
        b.append("    if (rc != PAPI_OK) {\n");
        b.append("#ifdef _OPENMP\n#pragma omp critical(cetus_papi_err)\n#endif\n");
        b.append("        {\n");
        b.append("            if (cetus_papi_errors++ == 0) fprintf(stderr, \"[cetus-papi] counter failed rc=%d\\n\", rc);\n");
        b.append("        }\n");
        b.append("    }\n");
        b.append("}\n");
        b.append("static void cetus_papi_sample(int team, long long *sum) {\n");
        b.append("#ifdef _OPENMP\n");
        b.append("    if (team) {\n");
        b.append("        int k;\n");
        b.append("        for (k = 0; k < CETUS_PAPI_NEV; k++) sum[k] = 0;\n");
        b.append("#pragma omp parallel\n");
        b.append("        {\n");
        b.append("            long long cetus_papi_loc[CETUS_PAPI_NEV];\n");
        b.append("            int cetus_papi_j;\n");
        b.append("            cetus_papi_read(cetus_papi_loc);\n");
        b.append("#pragma omp critical(cetus_papi_sum)\n");
        b.append("            {\n");
        b.append("                for (cetus_papi_j = 0; cetus_papi_j < CETUS_PAPI_NEV; cetus_papi_j++) sum[cetus_papi_j] += cetus_papi_loc[cetus_papi_j];\n");
        b.append("            }\n");
        b.append("        }\n");
        b.append("        return;\n");
        b.append("    }\n");
        b.append("#endif\n");
        b.append("    cetus_papi_read(sum);\n");
        b.append("}\n");
        b.append("static int cetus_papi_in_parallel(void) {\n");
        b.append("#ifdef _OPENMP\n    return omp_in_parallel();\n#else\n    return 0;\n#endif\n");
        b.append("}\n");
        b.append("static void cetus_papi_enter(int id, int team, cetus_papi_frame *f) {\n");
        b.append("    int inpar = cetus_papi_in_parallel();\n");
        b.append("    (void) id;\n");
        b.append("    f->active = 0;\n");
        b.append("    cetus_papi_lazy_init();\n");
        b.append("    if (inpar) {\n");
        b.append("        if (cetus_papi_depth > 0) return;\n");
        b.append("        if (cetus_papi_tdepth++ > 0) return;\n");
        b.append("    } else if (cetus_papi_depth++ > 0) {\n");
        b.append("        return;\n");
        b.append("    }\n");
        b.append("    f->active = 1;\n");
        b.append("    f->team = team && !inpar;\n");
        b.append("    cetus_papi_sample(f->team, f->v);\n");
        b.append("    clock_gettime(CLOCK_MONOTONIC, &f->t0);\n");
        b.append("}\n");
        b.append("static void cetus_papi_exit(int id, cetus_papi_frame *f) {\n");
        b.append("    long long dt, v[CETUS_PAPI_NEV];\n");
        b.append("    int k, inpar = cetus_papi_in_parallel();\n");
        b.append("    if (inpar) {\n");
        b.append("        if (cetus_papi_depth > 0) return;\n");
        b.append("        cetus_papi_tdepth--;\n");
        b.append("    } else {\n");
        b.append("        cetus_papi_depth--;\n");
        b.append("    }\n");
        b.append("    if (!f->active) return;\n");
        b.append("    f->active = 0;\n");
        b.append("    dt = cetus_papi_elapsed(&f->t0);\n");
        b.append("    cetus_papi_sample(f->team, v);\n");
        b.append("#ifdef _OPENMP\n#pragma omp critical(cetus_papi_acc)\n#endif\n");
        b.append("    {\n");
        b.append("        cetus_papi_ns[id] += dt;\n");
        b.append("        cetus_papi_calls[id] += 1;\n");
        b.append("        for (k = 0; k < CETUS_PAPI_NEV; k++) cetus_papi_acc[id][k] += v[k] - f->v[k];\n");
        b.append("    }\n");
        b.append("}\n");
        b.append("static void cetus_papi_main_done(void) {\n");
        b.append("    if (cetus_papi_main_frame.active) cetus_papi_exit(0, &cetus_papi_main_frame);\n");
        b.append("    if (cetus_papi_has_main && cetus_papi_prog_ns < 0) cetus_papi_prog_ns = cetus_papi_elapsed(&cetus_papi_prog_t0);\n");
        b.append("}\n");
        b.append("static void cetus_papi_report(void) {\n");
        b.append("    long long tot[CETUS_PAPI_NEV], tns = 0, calls = 0;\n");
        b.append("    int i, k;\n");
        b.append("    cetus_papi_main_done();\n");
        b.append("    for (k = 0; k < CETUS_PAPI_NEV; k++) tot[k] = 0;\n");
        b.append("    for (i = 0; i < CETUS_PAPI_NREG; i++) {\n");
        b.append("        if (cetus_papi_calls[i] == 0) continue;\n");
        b.append("        fprintf(stderr, \"[cetus-papi] %s TIME_NS=%lld\", cetus_papi_label[i], cetus_papi_ns[i]);\n");
        b.append("        for (k = 0; k < CETUS_PAPI_NEV; k++) {\n");
        b.append("            fprintf(stderr, \" %s=%lld\", cetus_papi_evname[k], cetus_papi_acc[i][k]);\n");
        b.append("            tot[k] += cetus_papi_acc[i][k];\n");
        b.append("        }\n");
        b.append("        fprintf(stderr, \" CALLS=%lld\\n\", cetus_papi_calls[i]);\n");
        b.append("        tns += cetus_papi_ns[i];\n");
        b.append("        calls += cetus_papi_calls[i];\n");
        b.append("    }\n");
        b.append("    fprintf(stderr, \"[cetus-papi] program TIME_NS=%lld\", cetus_papi_prog_ns >= 0 ? cetus_papi_prog_ns : tns);\n");
        b.append("    for (k = 0; k < CETUS_PAPI_NEV; k++) fprintf(stderr, \" %s=%lld\", cetus_papi_evname[k], tot[k]);\n");
        b.append("    fprintf(stderr, \" REGION_NS=%lld CALLS=%lld PAPI_ERRORS=%d\\n\", tns, calls, cetus_papi_errors + cetus_papi_init_failed);\n");
        b.append("}\n");
        b.append("#endif\n");
        return b.toString();
    }

    @FunctionalInterface
    private interface LoopPredicate {
        boolean test(ForLoop loop);
    }
}
