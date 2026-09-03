package cetus.unittest.paw_tiling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import cetus.analysis.DependenceVector;
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
import cetus.transforms.paw_tiling.legality.DirectionVectorLemmas;

public class DirectionVectorLemmasTest {

    /** Minimal canonical for-loop with the given index name. */
    static ForLoop loop(String name) {
        VariableDeclarator d = new VariableDeclarator(new NameID(name));
        Identifier id = new Identifier(d);
        Statement init = new ExpressionStatement(new AssignmentExpression(
                id.clone(), AssignmentOperator.NORMAL, new IntegerLiteral(0)));
        Expression cond = new BinaryExpression(id.clone(),
                BinaryOperator.COMPARE_LT, new IntegerLiteral(100));
        Expression step = new AssignmentExpression(id.clone(),
                AssignmentOperator.ADD, new IntegerLiteral(1));
        return new ForLoop(init, cond, step, new CompoundStatement());
    }

    static DependenceVector dv(List<Loop> order, int... dirs) {
        DependenceVector v = new DependenceVector();
        for (int i = 0; i < order.size(); i++) {
            v.setDirection(order.get(i), dirs[i]);
        }
        return v;
    }

    static int dir(DependenceVector v, Loop l) {
        return DirectionVectorLemmas.directionOf(v, l);
    }

    @Test
    public void stripMineSplitsLess() {
        Loop i = loop("i");
        Loop cross = loop("i_cetus_cross");
        Loop in = loop("i");
        List<Loop> oldOrder = Arrays.asList(i);
        List<Loop> newOrder = Arrays.asList(cross, in);

        List<DependenceVector> in_dvs = new ArrayList<>();
        in_dvs.add(dv(oldOrder, DependenceVector.less));

        List<DependenceVector> out = DirectionVectorLemmas.stripMine(
                in_dvs, i, cross, in, newOrder);

        assertEquals(2, out.size());
        // (=,<)
        assertEquals(DependenceVector.equal, dir(out.get(0), cross));
        assertEquals(DependenceVector.less, dir(out.get(0), in));
        // (<,*)
        assertEquals(DependenceVector.less, dir(out.get(1), cross));
        assertEquals(DependenceVector.any, dir(out.get(1), in));
    }

    @Test
    public void stripMineSplitsAny() {
        Loop i = loop("i");
        Loop cross = loop("i_cetus_cross");
        Loop in = loop("i");
        List<DependenceVector> in_dvs = new ArrayList<>();
        in_dvs.add(dv(Arrays.asList(i), DependenceVector.any));

        List<DependenceVector> out = DirectionVectorLemmas.stripMine(
                in_dvs, i, cross, in, Arrays.asList(cross, in));

        assertEquals(2, out.size());
        assertEquals(DependenceVector.equal, dir(out.get(0), cross));
        assertEquals(DependenceVector.any, dir(out.get(0), in));
        assertEquals(DependenceVector.any, dir(out.get(1), cross));
        assertEquals(DependenceVector.any, dir(out.get(1), in));
    }

    /** Thesis Figure dv-rewrite: nest (i,j) with vector (<,>): tiling j and
     * keeping the tile loop adjacent stays legal; hoisting it outermost
     * produces a leading '>' case and must be rejected. */
    @Test
    public void hoistingPastGreaterIsRejected() {
        Loop i = loop("i");
        Loop j = loop("j");
        Loop jCross = loop("j_cetus_cross");
        Loop jIn = loop("j");

        List<DependenceVector> orig = new ArrayList<>();
        orig.add(dv(Arrays.asList(i, j), DependenceVector.less, DependenceVector.greater));

        // Adjacent placement: (i, j_cross, j_in) -- legal: leading '<' at i.
        List<Loop> adjacent = Arrays.asList(i, jCross, jIn);
        List<DependenceVector> adjDvs = DirectionVectorLemmas.stripMine(
                orig, j, jCross, jIn, adjacent);
        assertTrue(DirectionVectorLemmas.isLegal(adjDvs, adjacent));

        // Hoisted outermost: (j_cross, i, j_in) -- the (>,*) branch yields a
        // leading '>' before the '<' at i: illegal.
        List<Loop> hoisted = Arrays.asList(jCross, i, jIn);
        List<DependenceVector> hoistDvs = DirectionVectorLemmas.stripMine(
                orig, j, jCross, jIn, hoisted);
        assertFalse(DirectionVectorLemmas.isLegal(hoistDvs, hoisted));
    }

    @Test
    public void leadingAnyIsIllegal() {
        Loop i = loop("i");
        Loop j = loop("j");
        List<Loop> order = Arrays.asList(i, j);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.any, DependenceVector.less));
        assertFalse(DirectionVectorLemmas.isLegal(dvs, order));
    }

    /** {(<,<)}: i carries the dependence (serial); j is parallel by
     * Lemma 3 -- the old all-'=' rule got this wrong. */
    @Test
    public void innerLoopParallelWhenOuterCarries() {
        Loop i = loop("i");
        Loop j = loop("j");
        List<Loop> order = Arrays.asList(i, j);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.less, DependenceVector.less));

        assertFalse(DirectionVectorLemmas.isParallel(dvs, order, i));
        assertTrue(DirectionVectorLemmas.isParallel(dvs, order, j));
        assertEquals("j", DirectionVectorLemmas.loopName(
                DirectionVectorLemmas.outermostParallelLoop(dvs, order)));
    }

    @Test
    public void outerParallelWhenDirectionEqual() {
        Loop i = loop("i");
        Loop j = loop("j");
        List<Loop> order = Arrays.asList(i, j);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.equal, DependenceVector.less));

        assertEquals("i", DirectionVectorLemmas.loopName(
                DirectionVectorLemmas.outermostParallelLoop(dvs, order)));
    }

    @Test
    public void allSerialReturnsNull() {
        Loop i = loop("i");
        List<Loop> order = Arrays.asList(i);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.less));
        // 1-loop nest, carried by itself, nothing outside carries it.
        assertNull(DirectionVectorLemmas.outermostParallelLoop(dvs, order));
    }

    /** '*' at p not covered by an outer '<' must be serial. */
    @Test
    public void anyAtLoopNotCoveredIsSerial() {
        Loop i = loop("i");
        Loop j = loop("j");
        List<Loop> order = Arrays.asList(i, j);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.equal, DependenceVector.any));
        assertFalse(DirectionVectorLemmas.isParallel(dvs, order, j));
        assertTrue(DirectionVectorLemmas.isParallel(dvs, order, i));
    }

    @Test
    public void reorderKeepsMappingChangesOrder() {
        Loop i = loop("i");
        Loop j = loop("j");
        List<Loop> order = Arrays.asList(i, j);
        List<DependenceVector> dvs = new ArrayList<>();
        dvs.add(dv(order, DependenceVector.less, DependenceVector.greater));

        List<Loop> swapped = Arrays.asList(j, i);
        List<DependenceVector> out = DirectionVectorLemmas.reorder(dvs, swapped);
        assertEquals(DependenceVector.greater, dir(out.get(0), j));
        assertEquals(DependenceVector.less, dir(out.get(0), i));
        // (>,<) after the swap: leading '>' -> illegal (Lemma 2).
        assertFalse(DirectionVectorLemmas.isLegal(out, swapped));
    }
}
