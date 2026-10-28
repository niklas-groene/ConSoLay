package layout.mental_map;

import constraints.Constraint;
import constraints.SeparationConstraintGraph;
import layout.sbgn.SbgnUserTypesTest;
import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Improved mental-map layout keeps the order of two nodes that its reference draws almost on one vertical line:
 * the gap the order chain demands between them is the order tolerance, not the rounding noise between their
 * coordinates. Needs the Adaptagrams natives; run with {@code ant test}.
 */
public class ImprovedMentalMapTest {

    @BeforeClass
    public static void natives() throws Exception {
        SbgnUserTypesTest.natives();
    }

    private static Node node(Graph g, String label, double x, double y) {
        Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(x, y));
        AttributeHelper.setSize(n, 40, 20);
        AttributeHelper.setLabel(n, label);
        return n;
    }

    @Test
    public void nearlyEqualCoordinatesKeepTheirOrder() {
        Graph live = new AdjListGraph();
        Node a = node(live, "a", 100, 0), b = node(live, "b", 100 + 1e-9, 200), c = node(live, "c", 300, 100);
        for (Node n : Arrays.asList(a, b)) {
            live.addEdge(n, c, true, AttributeHelper.getDefaultGraphicsAttributeForEdge(Color.BLACK, Color.BLACK, true));
        }
        Snapshot reference = Snapshot.capture(live, "reference");

        ComparisonLayoutRunner runner = new ComparisonLayoutRunner();
        DerivedConstraints derived = runner.run(ComparisonLayoutRunner.Choice.IMPROVED, live, reference);

        double gap = Double.NaN;
        for (Constraint x : derived.getConstraints()) {
            if (x instanceof SeparationConstraintGraph && new HashSet<>(x.getNodes()).equals(new HashSet<>(Arrays.asList(a, b)))
                    && ((SeparationConstraintGraph) x).getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL) {
                gap = ((SeparationConstraintGraph) x).getDistance();
            }
        }
        assertEquals(new ImprovedMentalMapLayout().orderTolerance, gap, 0);
        assertTrue("a stays left of b", AttributeHelper.getPositionX(b) - AttributeHelper.getPositionX(a) > gap / 2);
    }
}
