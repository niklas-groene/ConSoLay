package constraints;

import constraints.HorizontalConstraint.HorizontalConstraintType;
import constraints.VerticalConstraint.VerticalConstraintType;
import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The per-graph stores around a side view (the PD skeleton window), which constraint the feasibility guard gives up
 * on a contradiction between step constraints of different priorities and a user constraint, and that it keeps the
 * order of the constraints it does not drop. Run with {@code ant test}.
 */
public class StoreAndGuardTest {

    @After
    public void noActiveGraph() {
        ConstraintStore.activate(null);
    }

    @Test
    public void runOnASideGraphWorksOnItsOwnStore() {
        Graph map = new AdjListGraph(), side = new AdjListGraph();
        ConstraintStore.activate(map);
        Constraints mapStore = ConstraintStore.constraints;
        ConstraintStore.markSideGraph(side);
        ConstraintStore.activate(side);
        assertSame("the side view shows the map's store", mapStore, ConstraintStore.constraints);

        ConstraintStore.pin(side);
        assertNotSame("a run on the side view uses the map's store", mapStore, ConstraintStore.constraints);
        assertSame(ConstraintStore.of(side), ConstraintStore.constraints);
        assertSame(mapStore, ConstraintStore.of(map));
        ConstraintStore.unpin();
        assertSame("the map is active again after the run", map, ConstraintStore.activeGraph());
        assertSame(mapStore, ConstraintStore.constraints);
    }

    @Test
    public void closedMapDoesNotStayActiveBehindItsSideView() {
        Graph map = new AdjListGraph(), side = new AdjListGraph();
        ConstraintStore.activate(map);
        Constraints mapStore = ConstraintStore.constraints;
        ConstraintStore.markSideGraph(side);
        ConstraintStore.retainOnly(Collections.singletonList(side));   // the map was closed
        ConstraintStore.activate(side);
        assertSame(side, ConstraintStore.activeGraph());
        assertNotSame(mapStore, ConstraintStore.constraints);
    }

    private static Node node(Graph g, double x, double y) {
        Node n = g.addNode();
        AttributeHelper.setPosition(n, x, y);
        AttributeHelper.setSize(n, 20, 20);
        return n;
    }

    private static SeparationConstraintGraph gap(Node a, Node b) {
        return new SeparationConstraintGraph(Arrays.asList(a, b),
                SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL, false, 50);
    }

    /** a before b before c before a in x: a High and a Normal step constraint and a Normal one of the user. */
    @Test
    public void guardDropsTheLowestStepConstraintBeforeTheUsers() {
        Graph g = new AdjListGraph();
        Node a = node(g, 0, 0), b = node(g, 100, 0), c = node(g, 200, 0);
        Constraint high = gap(a, b), normal = gap(b, c), user = gap(c, a);
        high.setAutomatic(true);
        high.setPriority(Priority.HIGH);
        normal.setAutomatic(true);
        Constraints store = new Constraints();
        for (Constraint x : Arrays.asList(high, normal, user)) store.addConstraint(x);

        List<ConstraintFeasibility.Dropped> dropped = ConstraintFeasibility.enforce(store, true);
        assertEquals(1, dropped.size());
        assertSame(normal, dropped.get(0).constraint);
        assertTrue(store.getAllConstraints().contains(high));
        assertNull("the user's constraint ranks above the dropped one", user.getDeactivation());
    }

    /** a and b would coincide; the newest alignment (c, d) does not separate them and keeps its place in the store. */
    @Test
    public void coincidenceKeepsTheOrderOfWhatStays() {
        Graph g = new AdjListGraph();
        Node a = node(g, 0, 0), b = node(g, 100, 100), c = node(g, 200, 0), d = node(g, 300, 0);
        Constraint row = new HorizontalConstraint(Arrays.asList(a, b), HorizontalConstraintType.MIDDLE);
        Constraint column = new VerticalConstraint(Arrays.asList(a, b), VerticalConstraintType.MIDDLE);
        Constraint other = new HorizontalConstraint(Arrays.asList(c, d), HorizontalConstraintType.MIDDLE);
        Constraints store = new Constraints();
        for (Constraint x : Arrays.asList(other, row, column)) {
            x.setAutomatic(true);
            store.addConstraint(x);
        }

        List<ConstraintFeasibility.Dropped> dropped = ConstraintFeasibility.enforce(store, true);
        assertEquals(1, dropped.size());
        assertSame(column, dropped.get(0).constraint);
        assertEquals(Arrays.asList(other, row), store.getAllConstraints());
    }
}
