package layout.sbgn;

import layout.sbgn.ERAlignmentPlan.Alignment;
import layout.sbgn.ERAlignmentPlan.Candidate;
import layout.sbgn.ERAlignmentPlan.Chain;
import layout.sbgn.ERAlignmentPlan.Direction;
import layout.sbgn.ERAlignmentPlan.Model;
import layout.sbgn.ERAlignmentPlan.Offsets;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The graph-free parts of the ER layout ({@link ERAlignmentPlan}). Run with {@code ant test}. Screen coordinates:
 * y grows downwards.
 */
public class ERAlignmentPlanTest {

    private static final double CLEARANCE = 32;

    /** A model of {@code n} nodes, all {@code size} wide and high. */
    private static Model model(int n, double size) {
        double[] w = new double[n], h = new double[n];
        Arrays.fill(w, size);
        Arrays.fill(h, size);
        return new Model(w, h, CLEARANCE);
    }

    private static List<Candidate> plan(Model m, double[] x, double[] y, boolean onePerDirection, Candidate... candidates) {
        return ERAlignmentPlan.plan(m, x, y, new int[x.length], new ArrayList<>(Arrays.asList(candidates)), onePerDirection);
    }

    // ------------------------------------------------------------------ directions and sectors

    @Test
    public void directionsAreClockwiseOnScreen() {
        assertEquals(Direction.W, Direction.E.opposite());
        assertEquals(Direction.NW, Direction.SE.opposite());
        assertEquals(Direction.SE, Direction.E.turned(1));
        assertEquals(Direction.NE, Direction.E.turned(-1));
        assertEquals(Direction.E.bit() | Direction.SE.bit() | Direction.NE.bit(), Direction.E.side());
    }

    @Test
    public void alignmentOfADirection() {
        assertEquals(Alignment.HORIZONTAL, Direction.E.alignment());
        assertEquals(Alignment.HORIZONTAL, Direction.W.alignment());
        assertEquals(Alignment.VERTICAL, Direction.N.alignment());
        assertEquals(Alignment.VERTICAL, Direction.S.alignment());
        // the x axis turned 45 degrees clockwise runs from the top left to the bottom right: "\"
        assertEquals(Alignment.OCT_HORIZONTAL, Direction.SE.alignment());
        assertEquals(Alignment.OCT_HORIZONTAL, Direction.NW.alignment());
        // the y axis turned 45 degrees clockwise runs from the bottom left to the top right: "/"
        assertEquals(Alignment.OCT_VERTICAL, Direction.NE.alignment());
        assertEquals(Alignment.OCT_VERTICAL, Direction.SW.alignment());
    }

    @Test
    public void closestAxisInSectorsOf45Degrees() {
        assertEquals(Direction.E, ERAlignmentPlan.candidates(100, 0, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.N, ERAlignmentPlan.candidates(0, -50, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.SE, ERAlignmentPlan.candidates(30, 30, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.SW, ERAlignmentPlan.candidates(-30, 30, ERAlignmentPlan.ANY).get(0));
        // the sector border is at 22.5 degrees: tan(22.5) = 0.4142
        assertEquals(Direction.E, ERAlignmentPlan.candidates(100, 41, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.SE, ERAlignmentPlan.candidates(100, 42, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.NE, ERAlignmentPlan.candidates(100, -42, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.S, ERAlignmentPlan.candidates(41, 100, ERAlignmentPlan.ANY).get(0));
        assertEquals(Direction.NW, ERAlignmentPlan.candidates(-42, -100, ERAlignmentPlan.ANY).get(0));
        assertTrue(ERAlignmentPlan.candidates(0, 0, ERAlignmentPlan.ANY).isEmpty());
    }

    @Test
    public void secondChoiceIsTheNextSector() {
        assertEquals(Arrays.asList(Direction.E, Direction.SE), ERAlignmentPlan.candidates(100, 20, ERAlignmentPlan.ANY));
        assertEquals(Arrays.asList(Direction.E, Direction.NE), ERAlignmentPlan.candidates(100, -20, ERAlignmentPlan.ANY));
        assertEquals(Arrays.asList(Direction.SW, Direction.S), ERAlignmentPlan.candidates(-60, 100, ERAlignmentPlan.ANY));
    }

    @Test
    public void restrictedChoiceTakesTheClosestAllowedDirection() {
        int west = Direction.W.side();
        assertEquals(Arrays.asList(Direction.W, Direction.SW), ERAlignmentPlan.candidates(-100, 10, west));
        // the second node lies in the east: an alignment on the row would not take it to the west, a diagonal does
        assertEquals(Arrays.asList(Direction.SW, Direction.NW), ERAlignmentPlan.candidates(100, 0, west));
        assertTrue(ERAlignmentPlan.candidates(100, 0, 0).isEmpty());
    }

    // ------------------------------------------------------------------ known differences

    @Test
    public void offsetsJoinAndUndo() {
        Offsets o = new Offsets(4);
        assertTrue(Double.isNaN(o.difference(0, 1)));
        o.join(0, 1, 10);
        int mark = o.mark();
        o.join(2, 3, 5);
        o.join(1, 2, -3);
        assertEquals(10, o.difference(0, 1), 1e-12);
        assertEquals(7, o.difference(0, 2), 1e-12);
        assertEquals(12, o.difference(0, 3), 1e-12);
        assertEquals(-12, o.difference(3, 0), 1e-12);
        o.undo(mark);
        assertEquals(10, o.difference(0, 1), 1e-12);
        assertTrue(Double.isNaN(o.difference(0, 2)));
        assertTrue(Double.isNaN(o.difference(2, 3)));
    }

    @Test
    public void modelRejectsWhatContradictsIt() {
        Model m = model(3, 10);
        assertTrue(m.fix(true, 0, 1, 50));
        assertTrue(m.fix(true, 1, 2, 50));
        assertTrue(m.fix(true, 0, 2, 100));    // follows
        assertFalse(m.fix(true, 0, 2, 90));    // contradicts
        assertEquals(100, m.x.difference(0, 2), 1e-12);
    }

    // ------------------------------------------------------------------ step 2

    /** A column and two diagonals to a third node: the measured gaps (110 and 100) do not close the triangle. */
    @Test
    public void gapsAroundACycleAreMadeToFit() {
        // v (0,0) above u (0,200); w to the right of both
        double[] x = { 0, 0, 110 }, y = { 200, 0, 90 };
        Candidate uv = new Candidate(0, 1, ERAlignmentPlan.ANY), uw = new Candidate(0, 2, ERAlignmentPlan.ANY), vw = new Candidate(1, 2, ERAlignmentPlan.ANY);
        Model m = model(3, 10);
        List<Candidate> accepted = plan(m, x, y, true, uv, uw, vw);
        assertEquals(3, accepted.size());
        assertEquals(Direction.N, uv.direction);
        assertEquals(Direction.NE, uw.direction);
        assertEquals(Direction.SE, vw.direction);
        assertEquals(110, uw.gap, 1e-9);
        // x of w is fixed by the column and the first diagonal: the second diagonal takes that gap, not its measured 100
        assertEquals(110, vw.gap, 1e-9);
        assertEquals(2, vw.joins);   // only its y gap is a new constraint
        assertEquals(0, m.x.difference(0, 1), 1e-9);
        assertEquals(220, m.y.difference(1, 0), 1e-9);
    }

    @Test
    public void laterPairAdaptsToARowThatIsFixed() {
        // a - b on "\", a - c on a row, b - c on "/": c is measured 10 px below the row
        double[] x = { 0, 100, 200 }, y = { 0, 100, 10 };
        Candidate ab = new Candidate(0, 1, ERAlignmentPlan.ANY), ac = new Candidate(0, 2, ERAlignmentPlan.ANY), bc = new Candidate(1, 2, ERAlignmentPlan.ANY);
        List<Candidate> accepted = plan(model(3, 10), x, y, true, ab, ac, bc);
        assertEquals(Arrays.asList(ab, ac, bc), accepted);   // closest to an axis first: 0, 2.9 and 3.0 degrees
        assertEquals(Direction.E, ac.direction);
        assertEquals(Direction.NE, bc.direction);
        assertEquals(100, bc.gap, 1e-9);   // its y gap follows from the row and the first diagonal
        assertEquals(1, bc.joins);
    }

    /** Two neighbours in the same sector. */
    @Test
    public void oneAlignedNeighbourPerDirection() {
        // b and c both east of a, c nearer and 5.7 degrees off
        double[] x = { 0, 100, 50 }, y = { 0, 0, 5 };
        Candidate ab = new Candidate(0, 1, ERAlignmentPlan.ANY), ac = new Candidate(0, 2, ERAlignmentPlan.ANY), cb = new Candidate(2, 1, ERAlignmentPlan.ANY);
        Model m = model(3, 10);
        int[] taken = new int[3];
        List<Candidate> accepted = ERAlignmentPlan.plan(m, x, y, taken, new ArrayList<>(Arrays.asList(ab, ac, cb)), true);
        assertEquals(Direction.E, ab.direction);
        assertEquals(Direction.SE, ac.direction);   // east is taken at a: the next sector
        assertTrue(ac.second);
        assertEquals(42, ac.gap, 1e-9);             // at least what two 10 px nodes need: 10 + 32
        // west is taken at b as well; "/" fits, with the y gap the other two fix (a row leaves x free): a triangle
        assertEquals(Direction.NE, cb.direction);
        assertEquals(42, cb.gap, 1e-9);
        assertEquals(1, cb.joins);
        assertEquals(84, m.x.difference(0, 1), 1e-9);
        assertEquals(3, accepted.size());
        assertEquals(Direction.E.bit() | Direction.SE.bit(), taken[0]);
        assertEquals(0, m.overlaps());
    }

    @Test
    public void withoutTheRuleAllThreeLieOnOneRow() {
        double[] x = { 0, 100, 50 }, y = { 0, 0, 5 };
        Candidate ab = new Candidate(0, 1, ERAlignmentPlan.ANY), ac = new Candidate(0, 2, ERAlignmentPlan.ANY), cb = new Candidate(2, 1, ERAlignmentPlan.ANY);
        plan(model(3, 10), x, y, false, ab, ac, cb);
        assertEquals(Direction.E, ab.direction);
        assertEquals(Direction.E, ac.direction);
        assertEquals(Direction.E, cb.direction);
        assertEquals(0, cb.joins);   // follows from the other two
    }

    @Test
    public void nodesAreNotPutOnTopOfEachOther() {
        // a - b on "\"; c on a's row and in b's column would be 72 px right of a, but c is 100 px wide and needs 102
        double[] w = { 40, 40, 100 }, h = { 40, 40, 40 };
        Model m = new Model(w, h, CLEARANCE);
        double[] x = { 0, 60, 62 }, y = { 0, 60, 1 };
        Candidate ab = new Candidate(0, 1, ERAlignmentPlan.ANY), ac = new Candidate(0, 2, ERAlignmentPlan.ANY), bc = new Candidate(1, 2, ERAlignmentPlan.ANY);
        List<Candidate> accepted = plan(m, x, y, false, ab, ac, bc);
        assertEquals(72, ab.gap, 1e-9);
        assertEquals(2, accepted.size());
        assertEquals(0, m.overlaps());
    }

    /** The inputs of a logical operator whose output leaves to the east. */
    @Test
    public void operatorInputsLieOppositeTheOutput() {
        // node 0: the operator; 1: an input in the west; 2: an input in the east, on the output side
        double[] x = { 0, -100, 100 }, y = { 0, 0, 0 };
        int along = Direction.E.side();   // every edge at the operator runs east, north east or south east
        Candidate west = new Candidate(1, 0, along), east = new Candidate(2, 0, along);
        List<Candidate> accepted = plan(model(3, 10), x, y, true, west, east);
        assertEquals(2, accepted.size());
        assertEquals(Direction.E, west.direction);
        assertEquals(Direction.SE, east.direction);   // from the input to the operator: the input moves to the north west
        assertEquals(100 / Math.sqrt(2), east.gap, 1e-9);   // the edge keeps its length
    }

    // ------------------------------------------------------------------ step 3

    @Test
    public void chainOnADiagonal() {
        Model m = model(4, 1);
        int[] taken = new int[4];
        Chain c = ERAlignmentPlan.alignChain(m, taken, new int[]{ 0, 1, 2, 3 }, Direction.NE, 150);
        assertNotNull(c);
        assertEquals(50, c.gap, 1e-9);
        assertArrayEquals(new int[]{ 3, 3, 3 }, c.joins);
        assertEquals(150, m.x.difference(0, 3), 1e-9);
        assertEquals(-150, m.y.difference(0, 3), 1e-9);
        assertEquals(Direction.NE.bit(), taken[0]);
        assertEquals(Direction.SW.bit(), taken[3]);
        assertEquals(Direction.NE.bit() | Direction.SW.bit(), taken[1]);
        // too short for three pieces: every piece gets what two 1 px nodes need
        Chain tight = ERAlignmentPlan.alignChain(model(4, 1), null, new int[]{ 0, 1, 2, 3 }, Direction.NE, 30);
        assertEquals(33, tight.gap, 1e-9);
    }

    @Test
    public void chainBetweenTwoEndsThatAreFixed() {
        Model m = model(3, 1);
        assertTrue(m.fix(true, 0, 2, 90));
        assertTrue(m.fix(false, 0, 2, 90));
        Chain c = ERAlignmentPlan.alignChain(m, null, new int[]{ 0, 1, 2 }, Direction.SE, 200);
        assertNotNull(c);
        assertEquals(45, c.gap, 1e-9);
        assertArrayEquals(new int[]{ 3, 0 }, c.joins);   // the second piece follows
        // the two ends lie on "\": no row through them
        assertNull(ERAlignmentPlan.alignChain(m, null, new int[]{ 0, 1, 2 }, Direction.E, 0));
        assertEquals(45, m.x.difference(0, 1), 1e-9);    // the failed attempt changed nothing
    }

    @Test
    public void chainNeedsAFreeDirectionAtBothEnds() {
        int[] taken = new int[3];
        taken[2] = Direction.W.bit();
        assertNull(ERAlignmentPlan.alignChain(model(3, 1), taken, new int[]{ 0, 1, 2 }, Direction.E, 0));
        assertNotNull(ERAlignmentPlan.alignChain(model(3, 1), taken, new int[]{ 0, 1, 2 }, Direction.S, 0));
    }

    // ------------------------------------------------------------------ step 1 and step 5

    @Test
    public void peelTakesHyperedgeNodesOfDegreeZeroAndOne() {
        // 0, 1: glyphs; 2: a node on the arc 0 - 1 (degree 2); 3 - 4: what is left of a hyperedge that ended on 2;
        // 5: a node no edge is left at; 6: a glyph without edges
        int[][] edges = { { 0, 2 }, { 2, 1 }, { 2, 3 }, { 3, 4 } };
        boolean[] point = { false, false, true, true, true, true, false };
        assertArrayEquals(new int[]{ 4, 5, 3 }, ERAlignmentPlan.peel(7, edges, point));
    }

    @Test
    public void peelKeepsACycleOfHyperedgeNodes() {
        int[][] edges = { { 0, 1 }, { 1, 2 }, { 2, 0 } };
        assertEquals(0, ERAlignmentPlan.peel(3, edges, new boolean[]{ true, true, true }).length);
    }

    @Test
    public void closestDirectionAndSteps() {
        assertEquals(Direction.SE, ERAlignmentPlan.closest(100, 60, ERAlignmentPlan.ANY));
        assertEquals(Direction.E, ERAlignmentPlan.closest(100, 60, ERAlignmentPlan.STRAIGHT));
        assertEquals(Direction.S, ERAlignmentPlan.closest(100, 60, ERAlignmentPlan.STRAIGHT & ~Direction.E.bit()));
        assertEquals(Direction.E, ERAlignmentPlan.closest(100, 100, ERAlignmentPlan.STRAIGHT));   // a tie: the first of them
        assertNull(ERAlignmentPlan.closest(0, 0, ERAlignmentPlan.ANY));
        assertEquals(4, ERAlignmentPlan.steps(Direction.E, Direction.W));
        assertEquals(1, ERAlignmentPlan.steps(Direction.E, Direction.NE));
        assertEquals(3, ERAlignmentPlan.steps(Direction.N, Direction.SE));
    }

    @Test
    public void stubStandsAtARightAngleOnAStraightArc() {
        // the arc runs from west to east through the node; the hyperedge leads down and a little to the right
        assertEquals(Arrays.asList(Direction.S, Direction.N), ERAlignmentPlan.stubDirections(Direction.W, Direction.E, 30, 100));
        assertEquals(Direction.N, ERAlignmentPlan.stubDirections(Direction.W, Direction.E, 30, -100).get(0));
        // an arc on "\": the stub lies on "/"
        assertEquals(Arrays.asList(Direction.NE, Direction.SW), ERAlignmentPlan.stubDirections(Direction.NW, Direction.SE, 50, -10));
    }

    @Test
    public void stubOnTheOuterSideOfABend() {
        // the arc comes from the west and leaves to the south: only on the outside a stub is a right angle or more
        // away from both pieces
        assertEquals(Arrays.asList(Direction.NE, Direction.E, Direction.N), ERAlignmentPlan.stubDirections(Direction.W, Direction.S, 100, -100));
        // also for a hyperedge that leads to the inside of the bend
        assertEquals(Arrays.asList(Direction.E, Direction.N, Direction.NE), ERAlignmentPlan.stubDirections(Direction.W, Direction.S, -100, 100));
        // a straight and a diagonal piece
        assertEquals(Arrays.asList(Direction.N, Direction.NE), ERAlignmentPlan.stubDirections(Direction.W, Direction.SE, 0, -50));
    }

    @Test
    public void lineThroughABox() {
        assertTrue(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 100, 50, 50, 10, 10, 0));
        assertTrue(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 100, 50, 62, 10, 10, 4));    // only with the margin
        assertFalse(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 100, 50, 62, 10, 10, 0));
        assertFalse(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 100, 150, 150, 10, 10, 0));  // beyond the end
        assertTrue(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 0, 50, 0, 10, 10, 0));        // a horizontal line
        assertFalse(ERAlignmentPlan.segmentHitsBox(0, 0, 100, 0, 50, 20, 10, 10, 0));
    }

    // ------------------------------------------------------------------ room on a row or column

    @Test
    public void ordersWithoutKnownDifferencesFit() {
        // a chain 0 - 1 - 2 - 3 on one column, nothing else known: it gets as long as it needs
        assertEquals(-1, ERAlignmentPlan.contradictingOrder(new Offsets(4), new int[]{ 0, 1, 2 }, new int[]{ 1, 2, 3 }, new double[]{ 50, 50, 50 }));
    }

    @Test
    public void chainLongerThanTheGapBetweenItsEnds() {
        int[] from = { 0, 1, 2 }, to = { 1, 2, 3 };
        double[] room = { 50, 50, 50 };
        // exact gaps elsewhere hold the two ends 120 apart, the chain needs 150: its youngest order is named
        Offsets tight = new Offsets(4);
        tight.join(0, 3, 120);
        assertEquals(2, ERAlignmentPlan.contradictingOrder(tight, from, to, room));
        // with 150 between the ends it just fits
        Offsets fits = new Offsets(4);
        fits.join(0, 3, 150);
        assertEquals(-1, ERAlignmentPlan.contradictingOrder(fits, from, to, room));
        // the ends the other way round: node 3 is held above node 0, the chain leads downwards
        Offsets reversed = new Offsets(4);
        reversed.join(3, 0, 200);
        assertEquals(2, ERAlignmentPlan.contradictingOrder(reversed, from, to, room));
    }

    @Test
    public void onlyAnOrderOfTheTightChainIsNamed() {
        // 0 - 1 - 2 is too long for the 60 between 0 and 2; the younger order 4 - 5 has nothing to do with it
        Offsets y = new Offsets(6);
        y.join(0, 2, 60);
        assertEquals(1, ERAlignmentPlan.contradictingOrder(y, new int[]{ 0, 1, 4 }, new int[]{ 1, 2, 5 }, new double[]{ 50, 50, 50 }));
    }
}
