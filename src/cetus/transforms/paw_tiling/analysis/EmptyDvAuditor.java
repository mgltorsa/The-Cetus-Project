package cetus.transforms.paw_tiling.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import cetus.analysis.DDGraph;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IRTools;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.PrintTools;
import cetus.hir.Symbolic;
import cetus.hir.Traversable;
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;

/**
 * Phase-1 DV gate for PAW: when DDT yields no <em>nest-usable</em> direction
 * vectors, quickly audit whether that emptiness is correct or whether
 * loop-independent (or conservative) DVs were dropped / never recorded.
 *
 * <p>
 * DDT drops many {@code (=,…,=)} output deps via its equal-DV reachability
 * filter (write-only init nests). {@link DDGraph#getDirectionMatrix} also
 * projects whole-program arcs onto the nest and fills missing loops with
 * {@code nil}, which PAW then discards — leaving an empty usable set even
 * when other nests have arcs.
 *
 * <p>
 * Policy (approved): still tile; parallelism follows the lemmas. Empty or
 * all-{@code =} recovered vectors imply the outermost loop is parallel by
 * Lemma 3.
 */
public final class EmptyDvAuditor {

    public enum Status {
        /** Nest-usable DVs came from DDT. */
        DDT,
        /** Empty DDT set; synthesized DVs from array R/W pairs. */
        RECOVERED,
        /** Empty DDT set; no array conflict pairs — emptiness confirmed. */
        CONFIRMED_EMPTY,
        /** Recovered DVs contain {@code *} (conservative / ambiguous). */
        SUSPECT
    }

    public static final class Result {
        public final Status status;
        public final List<DependenceVector> dvs;
        public final String detail;

        public Result(Status status, List<DependenceVector> dvs, String detail) {
            this.status = status;
            this.dvs = dvs;
            this.detail = detail;
        }
    }

    private EmptyDvAuditor() {
    }

    /**
     * Returns nest-usable DVs for PAW Phase 1. Never returns null.
     */
    public static Result audit(ForLoop nest, LinkedList<Loop> nestLoops,
            DDGraph ddg) {
        List<DependenceVector> fromDdt = collectNestUsable(nestLoops, ddg);
        if (!fromDdt.isEmpty()) {
            PrintTools.printlnDebug("[paw-dv-audit] status=DDT count="
                    + fromDdt.size() + " dvs="
                    + DirectionVectorLemmas.formatVectors(fromDdt, nestLoops));
            return new Result(Status.DDT, fromDdt, "nest-usable DDT vectors");
        }

        List<DependenceVector> recovered = recoverFromAccesses(nest, nestLoops);
        if (recovered.isEmpty()) {
            PrintTools.printlnDebug("[paw-dv-audit] status=CONFIRMED_EMPTY"
                    + " — no array write conflict pairs; tiling may proceed;"
                    + " Lemma 3 treats empty DV set as outermost parallel");
            return new Result(Status.CONFIRMED_EMPTY, recovered,
                    "no same-array write/write or write/read pairs");
        }

        boolean suspect = containsAny(recovered, nestLoops);
        Status status = suspect ? Status.SUSPECT : Status.RECOVERED;
        PrintTools.printlnDebug("[paw-dv-audit] status=" + status
                + " recovered=" + recovered.size() + " dvs="
                + DirectionVectorLemmas.formatVectors(recovered, nestLoops)
                + " (DDT empty/unusable; quick R/W audit)");
        return new Result(status, recovered,
                suspect ? "recovered with conservative *" : "recovered (=) vectors");
    }

    /**
     * Nest-usable rows: every direction keyed to {@code nestLoops} is
     * non-{@code nil}. Whole-program arcs that only touch other nests become
     * all-nil projections and are dropped here.
     */
    public static List<DependenceVector> collectNestUsable(
            LinkedList<Loop> nestLoops, DDGraph ddg) {
        List<DependenceVector> usable = new ArrayList<>();
        if (ddg == null || nestLoops == null || nestLoops.isEmpty()) {
            return usable;
        }
        List<DependenceVector> matrix = ddg.getDirectionMatrix(nestLoops);
        if (matrix == null) {
            return usable;
        }
        for (DependenceVector row : matrix) {
            if (isNestUsable(row, nestLoops)) {
                usable.add(row);
            }
        }
        return usable;
    }

    public static boolean isNestUsable(DependenceVector dv, List<Loop> nestLoops) {
        if (dv == null || nestLoops == null || nestLoops.isEmpty()) {
            return false;
        }
        for (Loop loop : nestLoops) {
            int d = DirectionVectorLemmas.directionOf(dv, loop);
            if (d == DependenceVector.nil) {
                return false;
            }
        }
        return true;
    }

    /**
     * Quick recovery: for each same-array write–write or write–read pair,
     * emit one DV over {@code nestLoops}.
     */
    public static List<DependenceVector> recoverFromAccesses(ForLoop nest,
            List<Loop> nestLoops) {
        List<DependenceVector> out = new ArrayList<>();
        if (nest == null || nestLoops == null || nestLoops.isEmpty()) {
            return out;
        }

        List<Access> accesses = collectAccesses(nest);
        Set<String> seen = new LinkedHashSet<>();

        for (int i = 0; i < accesses.size(); i++) {
            Access a = accesses.get(i);
            for (int j = i; j < accesses.size(); j++) {
                Access b = accesses.get(j);
                if (!a.arrayName.equals(b.arrayName)) {
                    continue;
                }
                // Need at least one write (skip read–read).
                if (!a.write && !b.write) {
                    continue;
                }
                // Self-pair only for writes (output dep on one access).
                if (i == j && !a.write) {
                    continue;
                }

                DependenceVector dv = buildPairVector(a.access, b.access, nestLoops);
                String key = DirectionVectorLemmas.formatVector(dv, nestLoops)
                        + "|" + a.arrayName;
                if (seen.add(key)) {
                    out.add(dv);
                }
            }
        }
        return out;
    }

    private static DependenceVector buildPairVector(ArrayAccess a, ArrayAccess b,
            List<Loop> nestLoops) {
        DependenceVector dv = new DependenceVector();
        for (Loop loop : nestLoops) {
            int dir;
            if (loop instanceof ForLoop) {
                dir = directionForLoop(a, b, (ForLoop) loop);
            } else {
                dir = DependenceVector.any;
            }
            dv.setDirection(loop, dir);
        }
        dv.setValid(true);
        return dv;
    }

    /**
     * Per-loop direction for a quick audit (not a full Banerjee test):
     * <ul>
     * <li>index in neither subscript → {@code =}</li>
     * <li>index in both with same normalized form → {@code =}</li>
     * <li>otherwise → {@code *} (conservative)</li>
     * </ul>
     */
    static int directionForLoop(ArrayAccess a, ArrayAccess b, ForLoop loop) {
        boolean inA = ReuseOrderAnalyzer.subscriptsContainIndex(a, loop);
        boolean inB = ReuseOrderAnalyzer.subscriptsContainIndex(b, loop);
        if (!inA && !inB) {
            return DependenceVector.equal;
        }
        if (inA && inB && sameIndexingOnLoop(a, b, loop)) {
            return DependenceVector.equal;
        }
        return DependenceVector.any;
    }

    private static boolean sameIndexingOnLoop(ArrayAccess a, ArrayAccess b,
            ForLoop loop) {
        Expression indexVar = LoopTools.getIndexVariable(loop);
        if (indexVar == null) {
            return false;
        }
        String name = indexVar.toString();
        int dims = Math.min(a.getNumIndices(), b.getNumIndices());
        if (a.getNumIndices() != b.getNumIndices()) {
            return false;
        }
        for (int d = 0; d < dims; d++) {
            Expression ia = a.getIndex(d);
            Expression ib = b.getIndex(d);
            boolean aHas = expressionContainsName(ia, name);
            boolean bHas = expressionContainsName(ib, name);
            if (aHas != bHas) {
                return false;
            }
            if (aHas && !normalize(ia).equals(normalize(ib))) {
                return false;
            }
        }
        return true;
    }

    private static boolean expressionContainsName(Expression expr, String name) {
        if (expr == null) {
            return false;
        }
        DFIterator<Expression> ids = new DFIterator<>(expr, Expression.class);
        while (ids.hasNext()) {
            Expression e = ids.next();
            if (e.toString().equals(name)) {
                return true;
            }
        }
        return expr.toString().equals(name);
    }

    private static String normalize(Expression subscript) {
        try {
            Expression simplified = Symbolic.simplify(subscript.clone());
            List<Expression> terms = Symbolic.getTerms(simplified);
            if (terms == null || terms.isEmpty()) {
                return simplified instanceof IntegerLiteral
                        ? "c" : simplified.toString();
            }
            List<String> keep = new ArrayList<>();
            for (Expression term : terms) {
                if (!(term instanceof IntegerLiteral)) {
                    keep.add(term.toString());
                }
            }
            if (keep.isEmpty()) {
                return "c";
            }
            java.util.Collections.sort(keep);
            return String.join("+", keep);
        } catch (Exception e) {
            return subscript.toString();
        }
    }

    private static List<Access> collectAccesses(ForLoop nest) {
        List<Access> list = new ArrayList<>();
        DFIterator<ArrayAccess> iter = new DFIterator<>(nest, ArrayAccess.class);
        while (iter.hasNext()) {
            ArrayAccess acc = iter.next();
            Expression nameExpr = acc.getArrayName();
            if (nameExpr == null) {
                continue;
            }
            list.add(new Access(acc, nameExpr.toString(), isWriteAccess(acc)));
        }
        return list;
    }

    /** True when this access is under the LHS of an assignment. */
    public static boolean isWriteAccess(ArrayAccess acc) {
        AssignmentExpression ae = IRTools.getAncestorOfType(acc,
                AssignmentExpression.class);
        if (ae == null) {
            return false;
        }
        Expression lhs = ae.getLHS();
        if (lhs == null) {
            return false;
        }
        if (lhs == acc) {
            return true;
        }
        Traversable t = acc;
        while (t != null && t != ae) {
            if (t == lhs) {
                return true;
            }
            t = t.getParent();
        }
        return IRTools.containsExpression(lhs, acc);
    }

    private static boolean containsAny(List<DependenceVector> dvs,
            List<Loop> order) {
        for (DependenceVector dv : dvs) {
            for (Loop loop : order) {
                if (DirectionVectorLemmas.directionOf(dv, loop)
                        == DependenceVector.any) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Access {
        final ArrayAccess access;
        final String arrayName;
        final boolean write;

        Access(ArrayAccess access, String arrayName, boolean write) {
            this.access = access;
            this.arrayName = arrayName;
            this.write = write;
        }
    }
}
