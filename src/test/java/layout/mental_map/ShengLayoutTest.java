package layout.mental_map;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * {@link ShengLayout#run} also solves a step that adds an isolated node, which has neither neighbours nor a previous
 * position. Run with {@code ant test}.
 */
public class ShengLayoutTest {

    private static final double LENGTH = 100;

    @Test
    public void isolatedNewcomerDoesNotStopTheSolve() {
        // a path 0-1-2-3 whose first edge is far too short, and the isolated newcomer 4 on top of it
        int[][] nbr = { {1}, {0, 2}, {1, 3}, {2}, {} };
        double[] px = { 0, 40, 140, 240, 60 }, py = { 0, 0, 0, 0, 0 };
        double[] ax = px.clone(), ay = py.clone();
        ax[4] = Double.NaN;
        ay[4] = Double.NaN;
        double[] stability = { 1, 1, 1, 1, 0 };

        ShengLayout.run(nbr, LENGTH, px, py, ax, ay, stability, Collections.<ShengLayout.EdgeConstraint>emptyList(),
                3.0 / (LENGTH * LENGTH), 0);

        assertTrue("the stress pulls 0 and 1 apart", Math.hypot(px[1] - px[0], py[1] - py[0]) > 60);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
        }
        assertTrue(Double.isFinite(px[4]) && Double.isFinite(py[4]));
        assertTrue("the newcomer is moved off the path",
                px[4] < minX || px[4] > maxX || py[4] < minY || py[4] > maxY);
    }
}
