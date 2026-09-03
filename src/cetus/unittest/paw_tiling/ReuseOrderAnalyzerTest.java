package cetus.unittest.paw_tiling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

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
import cetus.hir.NameID;
import cetus.hir.Statement;
import cetus.hir.VariableDeclarator;
import cetus.transforms.paw_tiling.analysis.ReuseOrderAnalyzer;

/**
 * Phase-1 reuse-order tests (thesis sec. 3.2 / tasks.md §3.1).
 * Built as hand-constructed HIR — no full Cetus parse needed.
 */
public class ReuseOrderAnalyzerTest {

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

    /** Matmul body: d[i][j] = d[i][j] + a[i][k] * b[k][j] */
    static ForLoop matmulNest(int n) {
        Expression mul = new BinaryExpression(
                access2("a", "i", "k"), BinaryOperator.MULTIPLY,
                access2("b", "k", "j"));
        Expression add = new BinaryExpression(
                access2("d", "i", "j"), BinaryOperator.ADD, mul);
        Statement stmt = new ExpressionStatement(new AssignmentExpression(
                access2("d", "i", "j"), AssignmentOperator.NORMAL, add));

        CompoundStatement kBody = new CompoundStatement();
        kBody.addStatement(stmt);
        ForLoop k = loop("k", n, kBody);

        CompoundStatement jBody = new CompoundStatement();
        jBody.addStatement(k);
        ForLoop j = loop("j", n, jBody);

        CompoundStatement iBody = new CompoundStatement();
        iBody.addStatement(j);
        return loop("i", n, iBody);
    }

    static String indexName(ForLoop loop) {
        Expression v = LoopTools.getIndexVariable(loop);
        return v == null ? "?" : v.toString();
    }

    @Test
    public void matmulBrowseOrderIsKJI() {
        ForLoop nest = matmulNest(512);
        List<ForLoop> order = ReuseOrderAnalyzer.reuseOrder(nest);
        assertEquals(3, order.size());
        // Equal footprints → innermost-first tiebreak ⇒ k, j, i
        assertEquals("k", indexName(order.get(0)));
        assertEquals("j", indexName(order.get(1)));
        assertEquals("i", indexName(order.get(2)));
    }

    @Test
    public void matmulEqualTemporalScores() {
        ForLoop nest = matmulNest(100);
        List<ForLoop> loops = ReuseOrderAnalyzer.nestLoops(nest);
        double si = ReuseOrderAnalyzer.numericScore(loops.get(0), nest);
        double sj = ReuseOrderAnalyzer.numericScore(loops.get(1), nest);
        double sk = ReuseOrderAnalyzer.numericScore(loops.get(2), nest);
        // Each loop reuses one N×N reference group → identical scores
        assertEquals(si, sj, 1e-9);
        assertEquals(sj, sk, 1e-9);
        assertEquals(100.0 * 100.0, sk, 1e-9);
    }

    @Test
    public void referenceGroupsFoldConstantOffset() {
        // A[i][j] and A[i][j+1] share a group; B[i][j] is separate.
        Expression jPlus1 = new BinaryExpression(idx("j"), BinaryOperator.ADD,
                new IntegerLiteral(1));
        ArrayAccess a0 = access2("A", "i", "j");
        ArrayAccess a1 = new ArrayAccess(new NameID("A"),
                Arrays.asList(idx("i"), jPlus1));
        ArrayAccess b0 = access2("B", "i", "j");

        CompoundStatement body = new CompoundStatement();
        body.addStatement(new ExpressionStatement(a0));
        body.addStatement(new ExpressionStatement(a1));
        body.addStatement(new ExpressionStatement(b0));

        CompoundStatement jBody = new CompoundStatement();
        // wrap accesses in a j loop inside i
        ForLoop j = loop("j", 10, body);
        jBody.addStatement(j);
        ForLoop nest = loop("i", 10, jBody);

        List<List<ArrayAccess>> groups = ReuseOrderAnalyzer.referenceGroups(nest);
        assertEquals(2, groups.size());
        boolean foldedA = false;
        for (List<ArrayAccess> g : groups) {
            if (g.get(0).getArrayName().toString().equals("A")) {
                assertEquals(2, g.size());
                foldedA = true;
            }
        }
        assertTrue(foldedA);
    }

    @Test
    public void loopReusingTwoRefsRanksFirst() {
        // Nest (i,j): body uses A[j] and B[j] — only i carries temporal reuse
        // for both (i absent from both). j appears in both → score 0.
        CompoundStatement body = new CompoundStatement();
        body.addStatement(new ExpressionStatement(
                new ArrayAccess(new NameID("A"), idx("j"))));
        body.addStatement(new ExpressionStatement(
                new ArrayAccess(new NameID("B"), idx("j"))));

        ForLoop j = loop("j", 50, body);
        CompoundStatement iBody = new CompoundStatement();
        iBody.addStatement(j);
        ForLoop nest = loop("i", 50, iBody);

        List<ForLoop> order = ReuseOrderAnalyzer.reuseOrder(nest);
        assertEquals("i", indexName(order.get(0)));
        assertEquals("j", indexName(order.get(1)));
        assertTrue(ReuseOrderAnalyzer.numericScore(order.get(0), nest)
                > ReuseOrderAnalyzer.numericScore(order.get(1), nest));
    }

    @Test
    public void subscriptsContainIndex() {
        ForLoop nest = matmulNest(8);
        List<ForLoop> loops = ReuseOrderAnalyzer.nestLoops(nest);
        ForLoop i = loops.get(0);
        ForLoop j = loops.get(1);
        ForLoop k = loops.get(2);
        ArrayAccess d = access2("d", "i", "j");
        assertTrue(ReuseOrderAnalyzer.subscriptsContainIndex(d, i));
        assertTrue(ReuseOrderAnalyzer.subscriptsContainIndex(d, j));
        assertFalse(ReuseOrderAnalyzer.subscriptsContainIndex(d, k));
    }
}
