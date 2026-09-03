package cetus.unittest.paw_tiling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CompoundStatement;
import cetus.hir.Expression;
import cetus.hir.ExpressionStatement;
import cetus.hir.ForLoop;
import cetus.hir.Identifier;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.NameID;
import cetus.hir.Statement;
import cetus.hir.VariableDeclarator;
import cetus.transforms.paw_tiling.analysis.EmptyDvAuditor;
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;

public class EmptyDvAuditorTest {

    static ForLoop loop(String name, int ub, Statement body) {
        VariableDeclarator d = new VariableDeclarator(new NameID(name));
        Identifier id = new Identifier(d);
        Statement init = new ExpressionStatement(new AssignmentExpression(
                id.clone(), AssignmentOperator.NORMAL, new IntegerLiteral(0)));
        Expression cond = new BinaryExpression(id.clone(),
                BinaryOperator.COMPARE_LT, new IntegerLiteral(ub));
        Expression step = new AssignmentExpression(id.clone(),
                AssignmentOperator.ADD, new IntegerLiteral(1));
        return new ForLoop(init, cond, step, body);
    }

    static Expression idx(String name) {
        return new NameID(name);
    }

    static ArrayAccess access2(String array, String i0, String i1) {
        return new ArrayAccess(new NameID(array),
                Arrays.asList(idx(i0), idx(i1)));
    }

    static LinkedList<Loop> nestList(ForLoop nest) {
        LinkedList<Loop> list = new LinkedList<>();
        new cetus.hir.DFIterator<Loop>(nest, Loop.class).forEachRemaining(list::add);
        return list;
    }

    /** Init-like: a[i][j] = ... (write only). */
    static ForLoop initNest() {
        Statement stmt = new ExpressionStatement(new AssignmentExpression(
                access2("a", "i", "j"), AssignmentOperator.NORMAL,
                new IntegerLiteral(0)));
        CompoundStatement jBody = new CompoundStatement();
        jBody.addStatement(stmt);
        ForLoop j = loop("j", 64, jBody);
        CompoundStatement iBody = new CompoundStatement();
        iBody.addStatement(j);
        return loop("i", 64, iBody);
    }

    /** Read-only: checksum += d[i][j] style (array read only). */
    static ForLoop readOnlyNest() {
        Statement stmt = new ExpressionStatement(access2("d", "i", "j"));
        CompoundStatement jBody = new CompoundStatement();
        jBody.addStatement(stmt);
        ForLoop j = loop("j", 64, jBody);
        CompoundStatement iBody = new CompoundStatement();
        iBody.addStatement(j);
        return loop("i", 64, iBody);
    }

    @Test
    public void recoverInitEmitsEqualEqual() {
        ForLoop nest = initNest();
        LinkedList<Loop> loops = nestList(nest);
        List<DependenceVector> dvs = EmptyDvAuditor.recoverFromAccesses(nest, loops);
        assertFalse(dvs.isEmpty());
        DependenceVector dv = dvs.get(0);
        assertEquals(DependenceVector.equal,
                DirectionVectorLemmas.directionOf(dv, loops.get(0)));
        assertEquals(DependenceVector.equal,
                DirectionVectorLemmas.directionOf(dv, loops.get(1)));
        // Lemma 3: all '=' ⇒ outermost parallel
        assertEquals("i", DirectionVectorLemmas.loopName(
                DirectionVectorLemmas.outermostParallelLoop(dvs, loops)));
    }

    @Test
    public void readOnlyConfirmedEmpty() {
        ForLoop nest = readOnlyNest();
        LinkedList<Loop> loops = nestList(nest);
        EmptyDvAuditor.Result r = EmptyDvAuditor.audit(nest, loops, null);
        assertEquals(EmptyDvAuditor.Status.CONFIRMED_EMPTY, r.status);
        assertTrue(r.dvs.isEmpty());
        // Empty DV set: lemmas still report outermost as parallel
        assertEquals("i", DirectionVectorLemmas.loopName(
                DirectionVectorLemmas.outermostParallelLoop(r.dvs, loops)));
    }

    @Test
    public void auditRecoversWhenDdtMissing() {
        ForLoop nest = initNest();
        LinkedList<Loop> loops = nestList(nest);
        EmptyDvAuditor.Result r = EmptyDvAuditor.audit(nest, loops, null);
        assertEquals(EmptyDvAuditor.Status.RECOVERED, r.status);
        assertFalse(r.dvs.isEmpty());
        assertTrue(DirectionVectorLemmas.isLegal(r.dvs, loops));
    }

    @Test
    public void nestUsableRejectsNilProjection() {
        ForLoop nest = initNest();
        LinkedList<Loop> loops = nestList(nest);
        DependenceVector row = new DependenceVector();
        row.setDirection(loops.get(0), DependenceVector.less);
        row.setDirection(loops.get(1), DependenceVector.nil);
        assertFalse(EmptyDvAuditor.isNestUsable(row, loops));
    }

    @Test
    public void isWriteDetectsLhs() {
        ForLoop nest = initNest();
        cetus.hir.DFIterator<ArrayAccess> it =
                new cetus.hir.DFIterator<>(nest, ArrayAccess.class);
        assertTrue(it.hasNext());
        ArrayAccess lhs = it.next();
        assertTrue(EmptyDvAuditor.isWriteAccess(lhs));
        assertFalse(it.hasNext());
    }

    @Test
    public void flowPairOnMatmulStyleIsEqualOnIj() {
        // d[i][j] = d[i][j] + 1  → write/read same indexing ⇒ (=,=)
        Expression add = new BinaryExpression(access2("d", "i", "j"),
                BinaryOperator.ADD, new IntegerLiteral(1));
        Statement stmt = new ExpressionStatement(new AssignmentExpression(
                access2("d", "i", "j"), AssignmentOperator.NORMAL, add));
        CompoundStatement jBody = new CompoundStatement();
        jBody.addStatement(stmt);
        ForLoop j = loop("j", 32, jBody);
        CompoundStatement iBody = new CompoundStatement();
        iBody.addStatement(j);
        ForLoop nest = loop("i", 32, iBody);
        LinkedList<Loop> loops = nestList(nest);

        List<DependenceVector> dvs = EmptyDvAuditor.recoverFromAccesses(nest, loops);
        assertFalse(dvs.isEmpty());
        boolean foundEqEq = false;
        for (DependenceVector dv : dvs) {
            if (DirectionVectorLemmas.directionOf(dv, loops.get(0)) == DependenceVector.equal
                    && DirectionVectorLemmas.directionOf(dv, loops.get(1))
                            == DependenceVector.equal) {
                foundEqEq = true;
            }
        }
        assertTrue(foundEqEq);
        assertEquals("i", DirectionVectorLemmas.loopName(
                DirectionVectorLemmas.outermostParallelLoop(dvs, loops)));
    }
}
