package layout.sbgn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where {@link SbgnPortSync} puts the port it inserts ({@link SbgnPortSync#portDistance}). Run with {@code ant test}.
 * The node of the examples is a logical operator, 42 px wide: border 21 px from its centre, drawn outline 22.5 px
 * (frame 2 px), so a bend counts as outside from 23.5 px on (clearance 2.5).
 */
public class SbgnPortSyncTest {

    private static final double BORDER = 21, CLEARANCE = 2.5, OUTLINE = 22.5, EPS = 1e-9;

    /** The output arc of OR in the PCR map: the route turns 44.3 px from the centre. */
    @Test
    public void portKeepsItsDistanceInFrontOfABend() {
        // clearance -1 (-Dcblayout.portSpot=half): inside the outline
        assertEquals(22.15, SbgnPortSync.portDistance(BORDER, 44.3, true, -1), EPS);
        assertEquals(BORDER + SbgnPortSync.PORT_GAP, SbgnPortSync.portDistance(BORDER, 44.3, true, CLEARANCE), EPS);
        // any bend the sync does not take for the port itself lies more than 15 px outside the border
        for (double dist = BORDER + SbgnPortSync.PORT_GAP + 5.01; dist < 200; dist += 0.37) {
            assertEquals(BORDER + SbgnPortSync.PORT_GAP, SbgnPortSync.portDistance(BORDER, dist, true, CLEARANCE), EPS);
        }
    }

    @Test
    public void portStaysInFrontOfACloseBend() {
        // one bend that is the port of the other end: this port lies before it, outside the outline
        double r = SbgnPortSync.portDistance(BORDER, BORDER + 8, true, CLEARANCE);
        assertEquals(BORDER + 7, r, EPS);
        assertTrue(r > OUTLINE);
        // a bend on the outline: the same distance as with clearance -1
        assertEquals(SbgnPortSync.portDistance(BORDER, BORDER + 3, true, -1), SbgnPortSync.portDistance(BORDER, BORDER + 3, true, CLEARANCE), EPS);
    }

    @Test
    public void straightArcSharesItsSegment() {
        // long enough: the first half
        assertEquals(BORDER + SbgnPortSync.PORT_GAP, SbgnPortSync.portDistance(BORDER, 100, false, CLEARANCE), EPS);
        assertEquals(29.5, SbgnPortSync.portDistance(BORDER, 59, false, CLEARANCE), EPS);
        assertEquals(29.5, SbgnPortSync.portDistance(BORDER, 59, false, -1), EPS);
        // two nodes close to each other: half the segment is inside the outline, the port is not
        assertEquals(BORDER + 1, SbgnPortSync.portDistance(BORDER, 40, false, -1), EPS);
        assertEquals(BORDER + CLEARANCE, SbgnPortSync.portDistance(BORDER, 40, false, CLEARANCE), EPS);
    }

    /** What the sync inserts it has to recognise as the port the next time (isStub: border < r <= border + gap + 5). */
    @Test
    public void insertedPortIsRecognisedAgain() {
        for (double dist = 1; dist < 200; dist += 0.37) {
            for (boolean toBend : new boolean[]{ false, true }) {
                double r = SbgnPortSync.portDistance(BORDER, dist, toBend, CLEARANCE);
                assertTrue(r > BORDER && r <= BORDER + SbgnPortSync.PORT_GAP + 5);
            }
        }
    }
}
