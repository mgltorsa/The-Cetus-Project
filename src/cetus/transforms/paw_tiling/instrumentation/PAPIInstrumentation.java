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
 * <p><b>R1 nesting rule:</b> each measured region starts/stops its own PAPI
 * event set and records wall time. The program-level line reports wall time
 * for {@code main} plus the <em>sum</em> of region counter values — program
 * scope never overlaps a live PAPI event set <em>when regions exist</em>.
 *
 * <p>When no regions are found (typical serial baseline with no
 * {@code c_paw_measure}/OpenMP), the program wrap itself runs PAPI around
 * {@code main} so baseline still reports hardware counters.
 *
 * <p>Regions (outermost only), in order:
 * <ol>
 *   <li>Outermost loops in procedures named {@code kernel_*} (PolyBench
 *       compute nests). Baseline and tiled variants share these names
 *       ({@code kernel_3mm#0}, {@code kernel_syrk#0}, …) so per-loop
 *       charts are comparable. {@code init_array}/{@code print_array}
 *       are never regions, even if they carry {@code c_paw_tiling} or
 *       OpenMP.</li>
 *   <li>else {@code c_paw_tiling} nests that are not setup/teardown;</li>
 *   <li>else {@code c_paw_measure} and/or OpenMP {@code parallel for}
 *       nests (still skipping setup/teardown);</li>
 *   <li>else the program wrap around {@code main}.</li>
 * </ol>
 *
 * <p>OpenMP regions: {@code PAPI_start}/{@code PAPI_stop} run on every
 * thread of the team (dummy parallel regions around the measured loop) and
 * counters are <em>summed</em>. Wall time remains elapsed time. Serial
 * regions keep a single-thread event set.
 *
 * <pre>
 * [cetus-papi] kernel_3mm#1 TIME_NS=&lt;n&gt; &lt;EVENT&gt;=&lt;v&gt; ...
 * [cetus-papi] program TIME_NS=&lt;n&gt; &lt;EVENT&gt;=&lt;v&gt; ...
 * </pre>
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

    private static final String TILING_PRAGMA_PREFIX = "c_paw_tiling";
    private static final String MEASURE_PRAGMA_PREFIX = "c_paw_measure";
    private static final int MAX_PAPI_THREADS = 256;

    private final String[] events;

    public PAPIInstrumentation(Program program) {
        super(program);
        String option = Driver.getOptionValue(EVENTS_PARAM_NAME);
        String eventList = (option == null || option.trim().isEmpty())
                ? DEFAULT_EVENTS
                : option;
        events = eventList.split("\\s*,\\s*");
    }

    @Override
    public String getPassName() {
        return "[PAPI-Instrumentation]";
    }

    @Override
    public void start() {
        LoopTools.addLoopName(program, false);
        List<Statement> regions = findInstrumentableRegions();
        Set<TranslationUnit> touchedUnits = new LinkedHashSet<>();

        // Program wrap first so its start sits before any region prologues.
        List<Procedure> mains = findMainProcedures();
        for (Procedure main : mains) {
            instrumentProgram(main, regions.size());
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

        for (TranslationUnit tu : touchedUnits) {
            tu.addDeclarationFirst(new AnnotationDeclaration(
                    new CodeAnnotation(headerBlob())));
        }

        PrintTools.printlnDebug("[papi] instrumented " + regionId
                + " region(s) + program wrap");
    }

    /**
     * Prefer {@code kernel_*} compute nests so baseline and tiled binaries
     * report the same loops. Fall back to tiling/measure/omp only when the
     * program has no {@code kernel_*} procedure (e.g. tests that nest in
     * {@code main}).
     */
    private List<Statement> findInstrumentableRegions() {
        List<Statement> kernelLoops = findOutermostKernelLoops();
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
     * Outermost for-loops inside procedures whose name starts with
     * {@code kernel_} (PolyBench convention). Used so serial baselines still
     * get region PAPI without hand-inserted {@code c_paw_measure}.
     */
    private List<Statement> findOutermostKernelLoops() {
        List<Statement> regions = new ArrayList<>();
        for (Procedure proc : IRTools.getProcedureList(program)) {
            String name = proc.getSymbolName();
            if (name == null || !name.startsWith("kernel_")) {
                continue;
            }
            DFIterator<ForLoop> iter = new DFIterator<>(proc, ForLoop.class);
            while (iter.hasNext()) {
                ForLoop loop = iter.next();
                if (hasForLoopAncestorWithin(loop, proc)) {
                    continue;
                }
                regions.add(loop);
            }
        }
        return regions;
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

    private void instrumentRegion(Statement region, int id) {
        boolean threaded = region instanceof ForLoop
                && hasOmpParallelFor((ForLoop) region);
        String label = regionLabel(region, id);
        String es = "cetus_papi_es_" + id;
        String vals = "cetus_papi_vals_" + id;
        String rc = "cetus_papi_rc_" + id;
        String nthr = "cetus_papi_nthr_" + id;
        String t0 = "cetus_papi_t0_" + id;
        String t1 = "cetus_papi_t1_" + id;
        String dt = "cetus_papi_dt_" + id;
        int n = events.length;

        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        if (threaded) {
            appendThreadedPrologue(before, id, label, es, vals, rc, nthr, t0, n);
            appendThreadedEpilogue(after, id, label, es, vals, rc, nthr, t0, t1, dt, n);
        } else {
            appendSerialPrologue(before, id, label, es, vals, rc, t0, n);
            appendSerialEpilogue(after, id, label, es, vals, rc, t0, t1, dt, n);
        }

        insertSiblingWrap(region, before.toString(), after.toString(), label);
    }

    /** Cetus loop name ({@code kernel_3mm#1}) when present; else {@code region_N}. */
    private static String regionLabel(Statement region, int id) {
        if (region instanceof ForLoop) {
            String name = LoopTools.getLoopName(region);
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return "region_" + id;
    }

    /** Escape a loop name for a C string literal used as a fprintf format. */
    private static String cFormatString(String label) {
        return label.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("%", "%%");
    }

    private void appendSerialPrologue(StringBuilder before, int id, String label,
            String es, String vals, String rc, String t0, int n) {
        before.append("int ").append(es).append(" = PAPI_NULL; ");
        before.append("long long ").append(vals).append("[").append(n).append("]; ");
        before.append("int ").append(rc).append("; ");
        before.append("struct timespec ").append(t0).append(", cetus_papi_t1_")
                .append(id).append("; ");
        before.append("long long cetus_papi_dt_").append(id).append(" = -1; ");
        before.append("{ int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < ")
                .append(n).append("; cetus_papi_k++) ").append(vals)
                .append("[cetus_papi_k] = -1; }\n");
        before.append("cetus_papi_lazy_init();\n");
        before.append(rc).append(" = PAPI_create_eventset(&").append(es).append(");\n");
        for (String event : events) {
            before.append("if (").append(rc).append(" == PAPI_OK) ").append(rc)
                    .append(" = PAPI_add_named_event(").append(es)
                    .append(", \"").append(event).append("\");\n");
        }
        before.append("clock_gettime(CLOCK_MONOTONIC, &").append(t0).append(");\n");
        before.append("if (").append(rc).append(" == PAPI_OK) ").append(rc)
                .append(" = PAPI_start(").append(es).append(");\n");
        before.append("if (").append(rc).append(" != PAPI_OK) fprintf(stderr, ")
                .append("\"[cetus-papi] ").append(cFormatString(label))
                .append(" start failed rc=%d\\n\", ").append(rc).append(");");
    }

    private void appendSerialEpilogue(StringBuilder after, int id, String label,
            String es, String vals, String rc, String t0, String t1, String dt, int n) {
        after.append("if (").append(rc).append(" == PAPI_OK) { ");
        after.append(rc).append(" = PAPI_stop(").append(es).append(", ")
                .append(vals).append("); ");
        after.append("if (").append(rc).append(" != PAPI_OK) fprintf(stderr, ")
                .append("\"[cetus-papi] ").append(cFormatString(label))
                .append(" stop failed rc=%d\\n\", ").append(rc).append("); }\n");
        after.append("clock_gettime(CLOCK_MONOTONIC, &").append(t1).append(");\n");
        appendRegionReport(after, label, vals, t0, t1, dt, n);
    }

    private void appendThreadedPrologue(StringBuilder before, int id, String label,
            String es, String vals, String rc, String nthr, String t0, int n) {
        before.append("int ").append(es).append("[").append(MAX_PAPI_THREADS)
                .append("]; ");
        before.append("long long ").append(vals).append("[").append(n).append("]; ");
        before.append("int ").append(rc).append(" = PAPI_OK; ");
        before.append("int ").append(nthr).append(" = 1; ");
        before.append("struct timespec ").append(t0).append(", cetus_papi_t1_")
                .append(id).append("; ");
        before.append("long long cetus_papi_dt_").append(id).append(" = -1; ");
        before.append("{ int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < ")
                .append(n).append("; cetus_papi_k++) ").append(vals)
                .append("[cetus_papi_k] = 0; }\n");
        before.append("cetus_papi_lazy_init();\n");
        before.append("#ifdef _OPENMP\n");
        before.append(nthr).append(" = omp_get_max_threads();\n");
        before.append("if (").append(nthr).append(" < 1) ").append(nthr)
                .append(" = 1;\n");
        before.append("if (").append(nthr).append(" > ").append(MAX_PAPI_THREADS)
                .append(") ").append(nthr).append(" = ").append(MAX_PAPI_THREADS)
                .append(";\n");
        before.append("#pragma omp parallel num_threads(").append(nthr).append(")\n");
        before.append("{\n");
        before.append("  int cetus_papi_tid = omp_get_thread_num();\n");
        before.append("  int cetus_papi_trc = PAPI_OK;\n");
        before.append("  PAPI_register_thread();\n");
        before.append("  ").append(es).append("[cetus_papi_tid] = PAPI_NULL;\n");
        before.append("  cetus_papi_trc = PAPI_create_eventset(&").append(es)
                .append("[cetus_papi_tid]);\n");
        for (String event : events) {
            before.append("  if (cetus_papi_trc == PAPI_OK) cetus_papi_trc = PAPI_add_named_event(")
                    .append(es).append("[cetus_papi_tid], \"").append(event)
                    .append("\");\n");
        }
        before.append("  if (cetus_papi_trc == PAPI_OK) cetus_papi_trc = PAPI_start(")
                .append(es).append("[cetus_papi_tid]);\n");
        before.append("  if (cetus_papi_tid == 0) ").append(rc)
                .append(" = cetus_papi_trc;\n");
        before.append("}\n");
        before.append("#else\n");
        before.append(es).append("[0] = PAPI_NULL;\n");
        before.append(rc).append(" = PAPI_create_eventset(&").append(es)
                .append("[0]);\n");
        for (String event : events) {
            before.append("if (").append(rc).append(" == PAPI_OK) ").append(rc)
                    .append(" = PAPI_add_named_event(").append(es)
                    .append("[0], \"").append(event).append("\");\n");
        }
        before.append("if (").append(rc).append(" == PAPI_OK) ").append(rc)
                .append(" = PAPI_start(").append(es).append("[0]);\n");
        before.append("#endif\n");
        before.append("if (").append(rc).append(" != PAPI_OK) fprintf(stderr, ")
                .append("\"[cetus-papi] ").append(cFormatString(label))
                .append(" start failed rc=%d\\n\", ").append(rc).append(");\n");
        before.append("clock_gettime(CLOCK_MONOTONIC, &").append(t0).append(");");
    }

    private void appendThreadedEpilogue(StringBuilder after, int id, String label,
            String es, String vals, String rc, String nthr, String t0, String t1, String dt,
            int n) {
        after.append("clock_gettime(CLOCK_MONOTONIC, &").append(t1).append(");\n");
        after.append("#ifdef _OPENMP\n");
        after.append("#pragma omp parallel num_threads(").append(nthr).append(")\n");
        after.append("{\n");
        after.append("  int cetus_papi_tid = omp_get_thread_num();\n");
        after.append("  long long cetus_papi_local[").append(n).append("];\n");
        after.append("  int cetus_papi_k;\n");
        after.append("  for (cetus_papi_k = 0; cetus_papi_k < ").append(n)
                .append("; cetus_papi_k++) cetus_papi_local[cetus_papi_k] = -1;\n");
        after.append("  PAPI_stop(").append(es)
                .append("[cetus_papi_tid], cetus_papi_local);\n");
        after.append("  #pragma omp critical\n");
        after.append("  {\n");
        after.append("    for (cetus_papi_k = 0; cetus_papi_k < ").append(n)
                .append("; cetus_papi_k++) {\n");
        after.append("      if (cetus_papi_local[cetus_papi_k] >= 0) ")
                .append(vals).append("[cetus_papi_k] += cetus_papi_local[cetus_papi_k];\n");
        after.append("    }\n");
        after.append("  }\n");
        after.append("  PAPI_unregister_thread();\n");
        after.append("}\n");
        after.append("#else\n");
        after.append("if (").append(rc).append(" == PAPI_OK) ").append(rc)
                .append(" = PAPI_stop(").append(es).append("[0], ").append(vals)
                .append(");\n");
        after.append("#endif\n");
        appendRegionReport(after, label, vals, t0, t1, dt, n);
    }

    private void appendRegionReport(StringBuilder after, String label, String vals,
            String t0, String t1, String dt, int n) {
        after.append(dt).append(" = (long long)(").append(t1).append(".tv_sec - ")
                .append(t0).append(".tv_sec) * 1000000000LL + (long long)(")
                .append(t1).append(".tv_nsec - ").append(t0).append(".tv_nsec);\n");
        after.append("{ int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < ")
                .append(n).append("; cetus_papi_k++) { if (")
                .append(vals).append("[cetus_papi_k] >= 0) cetus_papi_prog_sum[cetus_papi_k] += ")
                .append(vals).append("[cetus_papi_k]; } }\n");
        after.append("fprintf(stderr, \"[cetus-papi] ").append(cFormatString(label))
                .append(" TIME_NS=%lld");
        for (String event : events) {
            after.append(' ').append(event).append("=%lld");
        }
        after.append("\\n\", ").append(dt);
        for (int i = 0; i < n; i++) {
            after.append(", ").append(vals).append("[").append(i).append("]");
        }
        after.append(");");
    }

    /**
     * Program wrap: wall clock always. When {@code regionCount > 0}, counters
     * on the program line are the sum of region values (R1). When
     * {@code regionCount == 0}, start/stop PAPI around {@code main} so the
     * baseline still reports hardware counters.
     */
    private void instrumentProgram(Procedure main, int regionCount) {
        CompoundStatement body = main.getBody();
        if (body == null) {
            return;
        }

        int n = events.length;
        boolean measureMain = regionCount == 0;

        StringBuilder start = new StringBuilder();
        start.append("cetus_papi_lazy_init();\n");
        start.append("{ int cetus_papi_k; for (cetus_papi_k = 0; cetus_papi_k < ")
                .append(n).append("; cetus_papi_k++) cetus_papi_prog_sum[cetus_papi_k] = 0; }\n");
        if (measureMain) {
            start.append("cetus_papi_prog_es = PAPI_NULL;\n");
            start.append("cetus_papi_prog_rc = PAPI_create_eventset(&cetus_papi_prog_es);\n");
            for (String event : events) {
                start.append("if (cetus_papi_prog_rc == PAPI_OK) cetus_papi_prog_rc = PAPI_add_named_event(")
                        .append("cetus_papi_prog_es, \"").append(event).append("\");\n");
            }
            start.append("if (cetus_papi_prog_rc == PAPI_OK) cetus_papi_prog_rc = PAPI_start(cetus_papi_prog_es);\n");
            start.append("if (cetus_papi_prog_rc != PAPI_OK) fprintf(stderr, ")
                    .append("\"[cetus-papi] program start failed rc=%d\\n\", cetus_papi_prog_rc);\n");
        }
        start.append("clock_gettime(CLOCK_MONOTONIC, &cetus_papi_prog_t0);");

        StringBuilder stop = new StringBuilder();
        stop.append("{\n");
        stop.append("  struct timespec cetus_papi_prog_t1;\n");
        stop.append("  long long cetus_papi_prog_dt;\n");
        if (measureMain) {
            stop.append("  if (cetus_papi_prog_rc == PAPI_OK) {\n");
            stop.append("    cetus_papi_prog_rc = PAPI_stop(cetus_papi_prog_es, cetus_papi_prog_sum);\n");
            stop.append("    if (cetus_papi_prog_rc != PAPI_OK) fprintf(stderr, ")
                    .append("\"[cetus-papi] program stop failed rc=%d\\n\", cetus_papi_prog_rc);\n");
            stop.append("  }\n");
        }
        stop.append("  clock_gettime(CLOCK_MONOTONIC, &cetus_papi_prog_t1);\n");
        stop.append("  cetus_papi_prog_dt = (long long)(cetus_papi_prog_t1.tv_sec - cetus_papi_prog_t0.tv_sec) * 1000000000LL")
                .append(" + (long long)(cetus_papi_prog_t1.tv_nsec - cetus_papi_prog_t0.tv_nsec);\n");
        stop.append("  fprintf(stderr, \"[cetus-papi] program TIME_NS=%lld");
        for (String event : events) {
            stop.append(' ').append(event).append("=%lld");
        }
        stop.append("\\n\", cetus_papi_prog_dt");
        for (int i = 0; i < n; i++) {
            stop.append(", cetus_papi_prog_sum[").append(i).append("]");
        }
        stop.append(");\n");
        stop.append("}");

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
                        new CodeAnnotation(stop.toString())));
            }
        } else {
            for (ReturnStatement ret : returns) {
                Traversable parent = ret.getParent();
                if (parent instanceof CompoundStatement) {
                    ((CompoundStatement) parent).addStatementBefore(ret,
                            new AnnotationStatement(new CodeAnnotation(stop.toString())));
                }
            }
        }

        PrintTools.printlnDebug("[papi] program wrap on main (" + regionCount
                + " region(s); measureMain=" + measureMain + ")");
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

    private String headerBlob() {
        int n = events.length;
        return "#ifndef CETUS_PAPI_INSTRUMENTATION\n"
                + "#define CETUS_PAPI_INSTRUMENTATION\n"
                + "#include <stdio.h>\n"
                + "#include <time.h>\n"
                + "#include <papi.h>\n"
                + "#ifdef _OPENMP\n"
                + "#include <omp.h>\n"
                + "#include <pthread.h>\n"
                + "static unsigned long cetus_papi_tid(void) {\n"
                + "    return (unsigned long) pthread_self();\n"
                + "}\n"
                + "#endif\n"
                + "static int cetus_papi_initialized = 0;\n"
                + "static long long cetus_papi_prog_sum[" + n + "];\n"
                + "static struct timespec cetus_papi_prog_t0;\n"
                + "static int cetus_papi_prog_es = PAPI_NULL;\n"
                + "static int cetus_papi_prog_rc = PAPI_OK;\n"
                + "static void cetus_papi_lazy_init(void) {\n"
                + "    if (!cetus_papi_initialized) {\n"
                + "        int cetus_papi_rc = PAPI_library_init(PAPI_VER_CURRENT);\n"
                + "        if (cetus_papi_rc != PAPI_VER_CURRENT) {\n"
                + "            fprintf(stderr, \"[cetus-papi] library init failed rc=%d\\n\", cetus_papi_rc);\n"
                + "        }\n"
                + "#ifdef _OPENMP\n"
                + "        PAPI_thread_init(cetus_papi_tid);\n"
                + "#endif\n"
                + "        cetus_papi_initialized = 1;\n"
                + "    }\n"
                + "}\n"
                + "#endif\n";
    }

    @FunctionalInterface
    private interface LoopPredicate {
        boolean test(ForLoop loop);
    }
}
