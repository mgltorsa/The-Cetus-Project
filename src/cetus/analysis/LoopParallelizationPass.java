package cetus.analysis;

import cetus.exec.Driver;
import cetus.hir.*;
import cetus.transforms.TransformPass;
import cetus.transforms.paw_tiling.TilingParams;
import cetus.transforms.ReductionTransform;

import java.util.*;

/**
 * Whole program analysis that uses data-dependence information to
 * internally annotate loops that are parallel
 */
public class LoopParallelizationPass extends AnalysisPass {

    // The level of parallelization requested from this pass
    private static final int PARALLELIZE_DISABLE_NESTED = 1;
    private static final int PARALLELIZE_LOOP_NEST = 2;

    // Store the level of parallelization required
    private int parallelization_level;

    // Enable/disable nested loop parallelism
    private boolean nested_parallelism;

    // Data structure for parallelization report.
    private Map<Loop, List<String>> report;

    // Flag for report generation.
    private boolean needs_report;

    // Pass name.
    private static final String pass_name = "[LoopParallelization]";

    /**
     * Constructs a new parallelization pass with the specified program.
     * 
     * @param program the program to be parallelized.
     */
    public LoopParallelizationPass(Program program) {
        super(program);

        parallelization_level = Integer.valueOf(Driver.getOptionValue("parallelize-loops")).intValue();
        // adjust the number if "report" is requested.
        if (parallelization_level > 2) {
            parallelization_level -= 2;
            needs_report = true;
        } else {
            needs_report = false;
        }
        report = new HashMap<Loop, List<String>>();
    }

    /**
     * Get Pass name
     */
    public String getPassName() {
        return pass_name;
    }

    /**
     * Start whole program loop parallelization analysis.
     */
    public void start() {
        // Implemented nested or non-nested parallelism as per user request
        switch (parallelization_level) {
            case PARALLELIZE_LOOP_NEST:
                nested_parallelism = true;
                parallelizeAllNests();
                break;
            case PARALLELIZE_DISABLE_NESTED:
                nested_parallelism = false;
                parallelizeAllNests();
                break;
        }
        reportParallelization();
        // Invokes reduction transformation.
    
        if(!nested_parallelism) {
            TransformPass.run(new ReductionTransform(program));
        }
    }

    /**
     * Performs parallelization of the loop nests in the program.
     */
    public void parallelizeAllNests() {
        DFIterator<Loop> iter = new DFIterator<Loop>(program, Loop.class);

        if (program.getDDGraph() == null) {
            PrintTools.println("[AUTOPAR] Program DDG not available Parallelization STOPS", 0);
            return;
        }
        iter.pruneOn(Loop.class);
        while (iter.hasNext()) {
            Loop nextloop = iter.next();
            parallelizeLoopNest(nextloop);
        }
    }

    private void addCetusNumThreadsAnnotation(Loop loop) {
        try {
            int numOfProcessors = Integer.parseInt(Driver.getOptionValue(TilingParams.CORES_PARAM_NAME));

            // CetusAnnotation numThreadsAnnot = new CetusAnnotation();
            // numThreadsAnnot.put("num_threads", numOfProcessors);
            // ((Annotatable) loop).annotate(numThreadsAnnot);

        } catch (Exception e) {
            PrintTools.printlnDebug("Error on setting num of processors. No value will be used");
        }
    }

    /**
     * Inserts cetus annotation if the given loop is proven to be parallel.
     */
    private void addCetusAnnotation(Loop loop, boolean parallel) {
        if (parallel &&
                Driver.isIncluded("parallelize-loops",
                        "Loop", LoopTools.getLoopName((Statement) loop))) {
            CetusAnnotation note = new CetusAnnotation();
            note.put("parallel", "true");
            ((Annotatable) loop).annotate(note);
        }
    }

    /**
     * Check if a specific loop in the program is parallel, irrespective of the
     * effects of parallelizing or serializing enclosing loops.
     * 
     * @param loop the for loop to check parallelism for based only on
     *             dependence analysis
     * @return true if it is parallel.
     */
    @Deprecated
    private boolean checkParallel(Loop loop) {
        boolean is_parallel = false;
        boolean nest_eligible = false;
        DDGraph loop_graph = null;
        DDGraph pdg = program.getDDGraph();
        // Check eligibility of enclosed nest for dependence testing
        List<Loop> entire_nest = LoopTools.calculateInnerLoopNest(loop);
        for (Loop l : entire_nest) {
            nest_eligible = LoopTools.checkDataDependenceEligibility(l);
            if (nest_eligible == false)
                break;
        }
        if (nest_eligible == true) {
            // Check if scalar dependences might exist
            if (LoopTools.scalarDependencePossible(loop) == true)
                is_parallel = false;
            // Check if early exit break statement might exist
            else if (LoopTools.containsBreakStatement(loop) == true)
                is_parallel = false;
            // check if array loop carried dependences exist
            else if (pdg.checkLoopCarriedDependence(loop) == true) {
                // Also check if loop carried dependences might be
                // because of private or reduction or induction variables
                loop_graph = pdg.getSubGraph(loop);
                ArrayList<DDGraph.Arc> loop_carried_deps = loop_graph.getLoopCarriedDependencesForGraph();
                for (DDGraph.Arc dep : loop_carried_deps) {
                    if (dep.isCarried(loop)) {
                        ArrayAccess dep_access = dep.getSource().getArrayAccess();
                        Symbol dep_symbol = SymbolTools.getSymbolOf((Expression) dep_access);
                        // Check if loop carried dependence is for private
                        // variable
                        if (LoopTools.isPrivate(dep_symbol, loop))
                            is_parallel = true;
                        // Check if loop carried dependence is for reduction
                        // variable
                        else if (LoopTools.isReduction(dep_symbol, loop))
                            is_parallel = true;
                        // else if (LoopTools.isInductionVariable(dep_symbol, l))
                        // is_parallel = true;
                        else {
                            is_parallel = false;
                            break;
                        }
                    } else
                        is_parallel = true;
                }
            }
            // No scalar or array dependences
            else
                is_parallel = true;
        } else
            is_parallel = false;

        return is_parallel;
    }

    /**
     * Using dependence information, parallelize the entire loop nest covered by
     * the enclosing loop. If an outer loop is found to be serial, serialize it
     * and eliminate all loop carried dependences originating from it, this will
     * in turn expose inner parallelism.
     * 
     * @param enclosing_loop the loop which encloses the nest to be parallelized.
     */
    private void parallelizeLoopNest(Loop enclosing_loop) {
        boolean is_parallel;

        DDGraph dependence_graph = program.getDDGraph();

        List<Loop> eligible_loops = LoopTools.extractOutermostDependenceTestEligibleLoops(enclosing_loop);

        for (int i = 0; i < eligible_loops.size(); i++) {
            Loop outer_loop = eligible_loops.get(i);

            DDGraph nest_ddgraph = dependence_graph.getSubGraph(outer_loop);

            List<Loop> contained_nest = LoopTools.calculateInnerLoopNest(outer_loop);

            // Records loops that are already scheduled for parallelization.
            List<Loop> scheduled = new ArrayList<Loop>(contained_nest.size());

            for (int j = 0; j < contained_nest.size(); j++) {
                boolean has_scheduled_outer_loop = false;
                Loop l = contained_nest.get(j);

                for (int k = 0; k < scheduled.size(); k++) {
                    if (IRTools.isAncestorOf(scheduled.get(k), l)) {
                        has_scheduled_outer_loop = true;
                        break;
                    }
                }
                // Does not analyze for parallelization if not necessary
                if (has_scheduled_outer_loop && !needs_report &&
                        !nested_parallelism) {
                    continue;
                }

                is_parallel = true;
                if (LoopTools.containsBreakStatement(l)) {
                    is_parallel = false;
                    addReport(l, "contains a break statement");
                    if (!needs_report) {
                        continue;
                    }
                }
                Set<Expression> scalar_deps = LoopTools.collectScalarDependences(l);

                if (!scalar_deps.isEmpty()) {
                    is_parallel = false;
                    addReport(l, "contains scalar dependences on {" +
                            PrintTools.collectionToString(scalar_deps, ", ") +
                            "}");
                    if (!needs_report) {
                        continue;
                    }
                }
                Set<Symbol> array_deps = new HashSet<Symbol>();
                ArrayList<DDGraph.Arc> all_arcs = nest_ddgraph.getAllArcs();
                for (int k = 0; k < all_arcs.size(); k++) {
                    DDGraph.Arc row = all_arcs.get(k);
                    DependenceVector dv = row.getDependenceVector();
                    // If direction is loop carried
                    if (!dv.getDirectionVector().containsKey(l) ||
                            dv.getDirection(l) == DependenceVector.equal ||
                            dv.getDirection(l) == DependenceVector.nil) {
                        continue;
                    }

                    ArrayAccess src_access = row.getSource().getArrayAccess();
                    Symbol src_symbol = SymbolTools.getSymbolOf((Expression) src_access);
                    ArrayAccess sink_access = row.getSink().getArrayAccess();
                    Symbol sink_symbol = SymbolTools.getSymbolOf((Expression) sink_access);
                    // Check if loop carried dependence is for
                    // private variable or reduction variable.
                    // If not, must serialize this loop
                    boolean serialize;
                    // Alias relationship may incur different src and sink,
                    // which is not handled in both array privatization and
                    // reduction.
                    // Only a privatized or reduction *array symbol* removes a
                    // carried dependence. An earlier revision also accepted any
                    // access whose first subscript was a private scalar (for
                    // example the loop index itself). That hid real
                    // dependences: cholesky, lu, trmm, symm and the NPB
                    // back-substitutions were emitted as parallel loops.
                    if (src_symbol == sink_symbol &&
                            (LoopTools.isPrivate(src_symbol, l)
                                    || LoopTools.isReduction(src_symbol, l))) {
                        serialize = false;
                    } else {
                        serialize = true;
                        array_deps.add(src_symbol);
                    }
                    if (serialize) {
                        is_parallel = false;
                        // Remove this dependence vector as serializing this loop
                        // will remove covered dependences (this direction will be
                        // < if the enclosing_loop passed into this loop is at the
                        // outermost level in the program. If not, it might be a >
                        // direction, but is assumed to be covered by an outer <
                        // direction and hence, the dependence vector can be deleted
                        // If the direction is any, there can be an equal direction
                        // as well and hence the row should not be deleted
                        if (dv.getDirection(l) != DependenceVector.any) {
                            all_arcs.remove(k--);
                        }
                    }
                }
                if (!array_deps.isEmpty()) {
                    addReport(l, "contains array dependences on {" +
                            PrintTools.collectionToString(array_deps, ", ") +
                            "}");
                }
                if (is_parallel && (syntacticCarriedDependence(l) ||
                        invariantSharedWrite(l))) {
                    is_parallel = false;
                    addReport(l, "contains a subscript self-dependence on " +
                            LoopTools.getLoopIndexSymbol(l));
                }
                if (is_parallel) {
                    // Arrays partitioned by this loop's index are shared, not
                    // private; drop them before the pointer check below.
                    shareIndexedArrays(l);
                }
                if (is_parallel && privatizesPointerParameter(l)) {
                    is_parallel = false;
                    addReport(l, "privatizes an array that is a pointer parameter");
                }
                if (is_parallel) {
                    addReport(l, "is parallel");

                    if (nested_parallelism || !has_scheduled_outer_loop) {
                        shareIndexedArrays(l);
                        addCetusAnnotation(l, true);
                        addCetusNumThreadsAnnotation(l);
                        addReport(l, "is scheduled for parallelization");
                        scheduled.add(l);

                    }
                } else {
                    addReport(l, "is serial");
                }
            }
        }

    }

    /**
     * An array parameter is a pointer. {@code private(a)} or
     * {@code firstprivate(a)} then copies the pointer, every thread still
     * writes the caller's array, and the loop races (PolyBench doitgen's
     * {@code sum}). Such a loop is kept serial; its inner loops are still
     * candidates.
     */
    public static boolean privatizesPointerParameter(Loop loop) {
        Annotatable annotated = (Annotatable) loop;
        for (String key : new String[] {"private", "firstprivate", "lastprivate"}) {
            CetusAnnotation note =
                    annotated.getAnnotation(CetusAnnotation.class, key);
            if (note == null) {
                continue;
            }
            Set<Symbol> symbols = note.get(key);
            if (symbols == null) {
                continue;
            }
            for (Symbol symbol : symbols) {
                if (SymbolTools.isFormal(symbol)
                        && (SymbolTools.isArray(symbol)
                                || SymbolTools.isPointer(symbol))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A global or parameter array written only at subscripts that use this
     * loop index is partitioned by the loop. Copying the whole array into a
     * private/firstprivate/lastprivate temporary keeps every iteration's
     * update except the lastprivate copy-out, which is what made NPB UA
     * {@code move} drop five of the six face updates.
     */
    public static void shareIndexedArrays(Loop loop) {
        Symbol index = LoopTools.getLoopIndexSymbol(loop);
        if (index == null) {
            return;
        }
        Annotatable annotated = (Annotatable) loop;
        for (String key : new String[] {"private", "firstprivate", "lastprivate"}) {
            CetusAnnotation note =
                    annotated.getAnnotation(CetusAnnotation.class, key);
            if (note == null) {
                continue;
            }
            Set<Symbol> symbols = note.get(key);
            if (symbols == null) {
                continue;
            }
            for (Symbol symbol : new LinkedHashSet<Symbol>(symbols)) {
                if (writesOnlyThroughLoopIndex(loop, symbol, index)) {
                    symbols.remove(symbol);
                }
            }
            if (symbols.isEmpty()) {
                note.detach();
            }
        }
    }

    private static boolean writesOnlyThroughLoopIndex(Loop loop, Symbol symbol,
            Symbol index) {
        boolean sawWrite = false;
        DFIterator<AssignmentExpression> iter = new DFIterator<AssignmentExpression>(
                (Traversable) loop, AssignmentExpression.class);
        while (iter.hasNext()) {
            Expression lhs = iter.next().getLHS();
            if (SymbolTools.getSymbolOf(lhs) != symbol) {
                continue;
            }
            sawWrite = true;
            if (!accessUses(lhs, index)) {
                return false;
            }
        }
        return sawWrite;
    }

    /**
     * Dependence analysis misses some loop-carried array updates, including
     * the BT/SP back-substitution {@code a[i] = a[i] - c * a[i+1]}. A write
     * and a read of the same array whose subscripts differ on this loop's
     * index is enough to keep the loop serial.
     */
    private static boolean syntacticCarriedDependence(Loop loop) {
        Symbol index = LoopTools.getLoopIndexSymbol(loop);
        if (index == null) {
            return false;
        }
        Set<Symbol> aliases = indexOffsetAliases(loop, index);
        List<ArrayAccess> accesses = new ArrayList<ArrayAccess>();
        DFIterator<ArrayAccess> iter =
                new DFIterator<ArrayAccess>((Traversable) loop, ArrayAccess.class);
        while (iter.hasNext()) {
            accesses.add(iter.next());
        }
        for (ArrayAccess write : accesses) {
            if (!(write.getParent() instanceof AssignmentExpression)) {
                continue;
            }
            if (((AssignmentExpression) write.getParent()).getLHS() != write) {
                continue;
            }
            Symbol array = SymbolTools.getSymbolOf(baseArray(write));
            if (array == null || !accessUses(write, index)) {
                continue;
            }
            List<Expression> written = flattenIndices(write);
            for (ArrayAccess read : accesses) {
                if (read == write || IRTools.isAncestorOf(write, read)) {
                    continue;
                }
                if (SymbolTools.getSymbolOf(baseArray(read)) != array) {
                    continue;
                }
                List<Expression> readIdx = flattenIndices(read);
                int dims = Math.min(written.size(), readIdx.size());
                for (int d = 0; d < dims; d++) {
                    if (written.get(d).toString().equals(readIdx.get(d).toString())) {
                        continue;
                    }
                    if (usesIndexOrAlias(written.get(d), index, aliases) &&
                            usesIndexOrAlias(readIdx.get(d), index, aliases)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Expression baseArray(ArrayAccess access) {
        Expression name = access;
        while (name instanceof ArrayAccess) {
            name = ((ArrayAccess) name).getArrayName();
        }
        return name;
    }

    private static List<Expression> flattenIndices(ArrayAccess access) {
        List<Expression> indices = new ArrayList<Expression>();
        Expression cursor = access;
        while (cursor instanceof ArrayAccess) {
            ArrayAccess array = (ArrayAccess) cursor;
            List<Expression> level = new ArrayList<Expression>();
            for (int i = 0; i < array.getNumIndices(); i++) {
                level.add(array.getIndex(i));
            }
            indices.addAll(0, level);
            cursor = array.getArrayName();
        }
        return indices;
    }

    /**
     * Every iteration writes the same location when the left-hand side does
     * not use the loop index. That is a race unless the location is private
     * or a reduction. The BT back-substitution inner loop is this pattern:
     * {@code rhs[m] = rhs[m] - lhs[n][m] * rhs_next[n]}. Nested loops count
     * too: SP {@code x_solve} writes the shared vectors {@code cv[i]} and
     * {@code rhon[i]} from a loop on {@code j}.
     */
    private static boolean invariantSharedWrite(Loop loop) {
        return sharedWriteMissesIndex(loop, true);
    }

    /**
     * Loop interchange can leave {@code cetus parallel} on a loop whose
     * index no longer partitions the writes (NPB UA {@code remap} accumulates
     * into {@code y[jj][iz][ii]} from {@code for (kk)}). Scalar reductions
     * stay parallel. An array update that ignores the parallel index is a
     * race even when the array is marked as a reduction. Tile loops
     * ({@code *_cetus_cross}) are left alone: their writes use the in-strip
     * index, which the parallelism lemma already checked.
     */
    public static void revokeIllegalParallel(Loop loop) {
        if (!(loop instanceof Annotatable)) {
            return;
        }
        Symbol index = LoopTools.getLoopIndexSymbol(loop);
        if (index != null && index.getSymbolName() != null
                && index.getSymbolName().endsWith("_cetus_cross")) {
            return;
        }
        Annotatable annotated = (Annotatable) loop;
        if (!annotated.containsAnnotation(CetusAnnotation.class, "parallel")) {
            return;
        }
        boolean carried = syntacticCarriedDependence(loop);
        boolean missesIndex = sharedWriteMissesIndex(loop, false);
        boolean pointerParam = privatizesPointerParameter(loop);
        if (!carried && !missesIndex && !pointerParam) {
            return;
        }
        PrintTools.printlnDebug("[revoke-parallel] " + LoopTools.getLoopName((Statement) loop)
                + " carried=" + carried + " missesIndex=" + missesIndex
                + " pointerParam=" + pointerParam);
        List<CetusAnnotation> notes =
                annotated.getAnnotations(CetusAnnotation.class);
        if (notes == null) {
            return;
        }
        for (CetusAnnotation note : new ArrayList<CetusAnnotation>(notes)) {
            if (note.get("parallel") == null) {
                continue;
            }
            note.remove("parallel");
            if (note.keySet().isEmpty()) {
                note.detach();
            }
        }
    }

    /**
     * @param exemptArrayReductions initial parallelization ignores array
     *        reductions here. The post-interchange revoke must not, because
     *        those clauses are dropped later when the subscript is defined
     *        inside the loop.
     */
    private static boolean sharedWriteMissesIndex(Loop loop,
            boolean exemptArrayReductions) {
        Symbol index = LoopTools.getLoopIndexSymbol(loop);
        if (index == null) {
            return false;
        }
        DFIterator<AssignmentExpression> iter = new DFIterator<AssignmentExpression>(
                (Traversable) loop, AssignmentExpression.class);
        while (iter.hasNext()) {
            AssignmentExpression assign = iter.next();
            Expression lhs = assign.getLHS();
            if (accessUses(lhs, index)) {
                continue;
            }
            Symbol symbol = SymbolTools.getSymbolOf(lhs);
            if (symbol == null || LoopTools.isPrivate(symbol, loop)) {
                continue;
            }
            if (LoopTools.isReduction(symbol, loop)
                    && (exemptArrayReductions || !(lhs instanceof ArrayAccess))) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Names assigned from the loop index plus or minus a constant, such as
     * {@code i1 = i + 1} in the SP Thomas algorithm.
     */
    private static Set<Symbol> indexOffsetAliases(Loop loop, Symbol index) {
        Set<Symbol> aliases = new LinkedHashSet<Symbol>();
        DFIterator<AssignmentExpression> iter = new DFIterator<AssignmentExpression>(
                (Traversable) loop, AssignmentExpression.class);
        while (iter.hasNext()) {
            AssignmentExpression assign = iter.next();
            if (!(assign.getLHS() instanceof Identifier)) {
                continue;
            }
            if (!isIndexOffset(assign.getRHS(), index)) {
                continue;
            }
            Symbol symbol = ((Identifier) assign.getLHS()).getSymbol();
            if (symbol != null) {
                aliases.add(symbol);
            }
        }
        return aliases;
    }

    private static boolean isIndexOffset(Expression rhs, Symbol index) {
        if (rhs instanceof Identifier) {
            return index.equals(SymbolTools.getSymbolOf(rhs));
        }
        if (!(rhs instanceof BinaryExpression)) {
            return false;
        }
        BinaryExpression binary = (BinaryExpression) rhs;
        String operator = binary.getOperator().toString();
        if (!operator.equals("+") && !operator.equals("-")) {
            return false;
        }
        return isIndexOrConstant(binary.getLHS(), index) &&
                isIndexOrConstant(binary.getRHS(), index) &&
                (accessUses(binary.getLHS(), index) ||
                        accessUses(binary.getRHS(), index));
    }

    private static boolean isIndexOrConstant(Expression expr, Symbol index) {
        Set<Symbol> symbols = SymbolTools.getAccessedSymbols(expr);
        if (symbols.isEmpty()) {
            return true;
        }
        return symbols.size() == 1 && symbols.contains(index);
    }

    private static boolean usesIndexOrAlias(Expression expr, Symbol index,
            Set<Symbol> aliases) {
        Set<Symbol> used = SymbolTools.getAccessedSymbols(expr);
        if (used.contains(index)) {
            return true;
        }
        for (Symbol symbol : used) {
            if (aliases.contains(symbol)) {
                return true;
            }
        }
        return false;
    }

    private static boolean accessUses(Traversable node, Symbol index) {
        return SymbolTools.getAccessedSymbols(node).contains(index);
    }

    /**
     * Adds a string entry in the report data structure.
     */
    protected void addReport(Loop loop, String text) {
        List<String> loop_report = report.get(loop);
        if (loop_report == null) {
            loop_report = new LinkedList<String>();
            report.put(loop, loop_report);
        }
        loop_report.add(text);
    }

    private void reportParallelization() {
        if (!needs_report) {
            return;
        }
        StringBuilder sb = new StringBuilder(400);
        String tag = "[AUTOPAR] ";
        // String sep = PrintTools.line_sep + tag + " ";
        String sep = "; ";
        DFIterator<ForLoop> iter = new DFIterator<ForLoop>(program, ForLoop.class);
        iter.pruneOn(VariableDeclaration.class);
        iter.pruneOn(ExpressionStatement.class);
        while (iter.hasNext()) {
            ForLoop floop = iter.next();
            // Loop name
            sb.append(tag);
            sb.append("Loop is named ");
            sb.append(LoopTools.getLoopName(floop));
            // Any manual parallelization
            if (floop.containsAnnotation(OmpAnnotation.class, "for")) {
                sb.append(sep);
                sb.append("was manually parallelized");
            }
            // Parallelization result
            List<String> floop_report = report.get(floop);
            if (floop_report != null) {
                for (String content : floop_report) {
                    sb.append(sep);
                    sb.append(content);
                }
            } else {
                sb.append(sep);
                sb.append("is not eligible for parallelization");
            }
            sb.append(PrintTools.line_sep);
        }
        System.out.println(sb + "");
    }

    /**
     * Prints summary of loop parallelization pass.
     */
    /*
     * private void reportParallelizationOld() {
     * if ( PrintTools.getVerbosity() < 1 )
     * return;
     * String tag = "[PARALLEL REPORT] ";
     * String separator = "";
     * for ( int i=0; i<80-tag.length(); i++ ) separator += ":";
     * StringBuilder legend = new StringBuilder(300);
     * legend.append(tag+separator+"\n");
     * legend.append(tag+"InputParallel: loop is parallel in the input program\n");
     * legend.append(tag+"CetusParallel: loop is auto-parallelized\n");
     * legend.append(tag+"NonCanonical : loop is not canonical\n");
     * legend.append(tag+"NonPerfect   : loop is not perfect nest\n");
     * legend.append(tag+"ControlFlow  : loop may exit prematually\n");
     * legend.append(tag+"SymbolicStep : loop step is symbolic\n");
     * legend.append(tag+"FunctionCall : loop contains function calls\n");
     * legend.append(tag+"I/O          : loop contains I/O calls\n");
     * legend.append(tag+separator+"\n");
     * System.out.print(legend);
     * 
     * String loop_name = "-";
     * boolean omp_found = false, cetus_parallel_found = false;
     * 
     * DepthFirstIterator iter = new DepthFirstIterator(program);
     * while ( iter.hasNext() )
     * {
     * Object o = iter.next();
     * 
     * if ( o instanceof ForLoop )
     * {
     * ForLoop for_loop = (ForLoop)o;
     * StringBuilder out = new StringBuilder(80);
     * out.append(LoopTools.getLoopName(for_loop));
     * if ( for_loop.containsAnnotation(OmpAnnotation.class, "for") )
     * out.append(", InputParallel");
     * if ( for_loop.containsAnnotation(CetusAnnotation.class, "parallel") )
     * out.append(", CetusParallel");
     * if ( !LoopTools.isCanonical(for_loop) )
     * out.append(", NonCanonical");
     * if ( !LoopTools.isPerfectNest(for_loop) )
     * out.append(", NonPerfect");
     * if ( LoopTools.containsControlFlowModifier(for_loop) )
     * out.append(", ControlFlow");
     * if ( !LoopTools.isIncrementEligible(for_loop) )
     * out.append(", SymbolicStep");
     * if ( IRTools.containsClass(for_loop, FunctionCall.class) )
     * out.append(", FunctionCall");
     * System.out.println(tag+out);
     * }
     * }
     * }
     */
}
