package adaptagrams_bridge;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.DesiredPositionConstraintGraph;
import constraints.DistributionConstraintGraph;
import constraints.FixedRelativeConstraintGraph;
import constraints.HorizontalConstraint;
import constraints.LockConstraintGraph;
import constraints.Priority;
import constraints.SeparationConstraintGraph;

import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static constraints.SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The user solve on small graphs: rigid groups, targets and held groups as the conflict decision models them, the drag
 * solve of a part of the graph, the compartment levels of the hierarchical layout, the solver reports of the step
 * solves and the release of the solve's state. Needs the natives; run with {@code ant test}.
 */
public class UserSolveTest {

    @BeforeClass
    public static void natives() {
        System.setProperty("java.awt.headless", "true");
        NativeLoader.ensureLoaded();
    }

    @After
    public void reset() {
        CBLayoutAlgorithm.clusterMap = new HashMap<>();
    }

    private static Node node(Graph g, double x, double y, double w, double h) {
        Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(x, y));
        AttributeHelper.setSize(n, w, h);
        return n;
    }

    private static Node node(Graph g, double x, double y) {
        return node(g, x, y, 40, 40);
    }

    private static LockConstraintGraph pin(Node n, double x, double y) {
        Map<Node, double[]> at = new HashMap<>();
        at.put(n, new double[]{x, y});
        return new LockConstraintGraph(at);
    }

    private static DesiredPositionConstraintGraph target(Node n, double x, double y) {
        Map<Node, double[]> at = new HashMap<>();
        at.put(n, new double[]{x, y});
        return new DesiredPositionConstraintGraph(at, 1);
    }

    private static ArrayList<Node> list(Node... ns) {
        return new ArrayList<>(Arrays.asList(ns));
    }

    private static HorizontalConstraint row(Node a, Node b) {
        return new HorizontalConstraint(list(a, b), HorizontalConstraint.HorizontalConstraintType.MIDDLE);
    }

    private static SeparationConstraintGraph gap(Node a, Node b, boolean exact, double d) {
        return new SeparationConstraintGraph(list(a, b), HORIZONTAL, exact, d);
    }

    private static Map<Constraint, String> solve(Graph g, Constraints s) {
        return CBLayoutAlgorithm.solveUserConstraints(g, s.getAllConstraints(), false, false, false);
    }

    private static Constraints store(Constraint... cs) {
        Constraints store = new Constraints();
        for (Constraint c : cs) store.addConstraint(c);
        ConstraintStore.constraints = store;
        return store;
    }

    private static void at(Node n, double x, double y, String what) {
        assertEquals(what + " x", x, AttributeHelper.getPositionX(n), 1e-6);
        assertEquals(what + " y", y, AttributeHelper.getPositionY(n), 1e-6);
    }

    @Test
    public void targetsDoNotBendAPlacedRigidGroup() {
        Graph g = new AdjListGraph();
        Node a = node(g, 0, 0), b = node(g, 100, 0), c = node(g, 0, 100);
        Constraints s = store(new FixedRelativeConstraintGraph(list(a, b, c), false), pin(a, 0, 0), target(b, 300, 0));
        Map<Constraint, String> unmet = solve(g, s);
        assertTrue("not satisfied: " + unmet, unmet.isEmpty());
        at(a, 0, 0, "pinned member");
        at(b, 100, 0, "targeted member");
        at(c, 0, 100, "third member");

        // without a pin the group sits on its members' targets as a whole
        Graph g2 = new AdjListGraph();
        Node a2 = node(g2, 0, 0), b2 = node(g2, 100, 0), c2 = node(g2, 0, 100);
        Constraints s2 = store(new FixedRelativeConstraintGraph(list(a2, b2, c2), false), target(a2, 0, 0),
                target(b2, 300, 0));
        unmet = solve(g2, s2);
        assertTrue("not satisfied: " + unmet, unmet.isEmpty());
        at(a2, 100, 0, "first member");
        at(b2, 200, 0, "second member");
        at(c2, 100, 100, "third member");
    }

    @Test
    public void aTargetIsReachedOnceAndThenKept() {
        Graph g = new AdjListGraph();
        Node t = node(g, 0, 0);
        node(g, 0, 300);
        store(target(t, 400, 0));
        CBLayoutAlgorithm.executeUserConstraints(g, false, false);
        at(t, 400, 0, "after the first solve");
        CBLayoutAlgorithm.executeUserConstraints(g, false, false);
        at(t, 400, 0, "after the second solve");
    }

    @Test
    public void dragSolveWithATargetElsewhereEqualsFullSolve() {
        assertTrue("needs -Dcblayout.dragSolve=component (the default)", CBLayoutAlgorithm.DRAG_SOLVE_COMPONENT);
        Graph g = new AdjListGraph();
        Node x = node(g, 0, 0), y = node(g, 200, 30);
        Node t = node(g, 0, 400), u = node(g, 400, 500);
        Node free = node(g, 800, 800);
        // the target pulls t off the row with u: every full solve moves both a little further
        store(row(x, y), row(t, u), target(t, 0, 400));
        CBLayoutAlgorithm.executeUserConstraints(g, false, false);
        AttributeHelper.setPosition(x, 25, -10);
        Map<Node, double[]> dragged = positions(g);
        CBLayoutAlgorithm.executeUserConstraintsAround(g, Collections.singletonList(x), false, false);
        Map<Node, double[]> around = positions(g);
        for (Map.Entry<Node, double[]> e : dragged.entrySet()) {
            AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        CBLayoutAlgorithm.executeUserConstraints(g, false, false);
        Map<Node, double[]> full = positions(g);
        for (Node n : g.getNodes()) {
            assertEquals(around.get(n)[0], full.get(n)[0], 1e-6);
            assertEquals(around.get(n)[1], full.get(n)[1], 1e-6);
        }
        at(free, 800, 800, "unconstrained node");
    }

    private static Map<Node, double[]> positions(Graph g) {
        Map<Node, double[]> m = new HashMap<>();
        for (Node n : g.getNodes()) {
            m.put(n, new double[]{AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)});
        }
        return m;
    }

    @Test
    public void decisionAndSolvePlaceAHeldGroupOnTheSameTarget() {
        for (boolean highFar : new boolean[]{true, false}) {
            Graph g = new AdjListGraph();
            Node m = node(g, 100, 100), k = node(g, 200, 100), p = node(g, 300, 300);
            FixedRelativeConstraintGraph held = new FixedRelativeConstraintGraph(list(m, k), true);
            held.setPriority(Priority.VERY_HIGH);
            DesiredPositionConstraintGraph high = target(m, highFar ? 500 : 0, 100);
            DesiredPositionConstraintGraph low = target(m, highFar ? 0 : 500, 100);
            high.setPriority(Priority.HIGH);
            low.setPriority(Priority.LOW);
            LockConstraintGraph pin = pin(p, 250, 300);
            pin.setPriority(Priority.HIGH);
            SeparationConstraintGraph gap = gap(m, p, false, 100);
            Map<Constraint, String> unmet = solve(g, store(held, low, high, pin, gap));
            assertTrue("not satisfied: " + unmet, unmet.isEmpty());
            at(p, 250, 300, "pinned node");
            at(m, highFar ? 500 : 0, 100, "held member");
            if (highFar) assertNotNull("the gap contradicts the group on its higher target", gap.getDeactivation());
            else assertNull("the gap fits the group on its higher target", gap.getDeactivation());
        }
    }

    @Test
    public void compartmentContentKeepsItsPin() {
        Graph g = new AdjListGraph(), frames = new AdjListGraph();
        Node m1 = node(g, 0, 0), m2 = node(g, 200, 0, 100, 100), f = node(g, 600, 0);
        Node c = node(frames, 100, 0, 400, 300);
        Map<Node, ArrayList<Node>> clusters = new HashMap<>();
        clusters.put(c, list(m1, m2));
        CBLayoutAlgorithm.clusterMap = clusters;
        store(pin(m1, 0, 0));
        CBLayoutAlgorithm.layoutHierarchically(g);
        at(m1, 0, 0, "pinned node in a compartment");
        at(f, 600, 0, "free node");
    }

    @Test
    public void compartmentLevelKeepsTheUsersEqualSpacing() {
        Graph g = new AdjListGraph(), frames = new AdjListGraph();
        Node n1 = node(g, 0, 0), n2 = node(g, 60, 0), n3 = node(g, 300, 0);
        Node c = node(frames, 150, 0, 500, 200);
        Map<Node, ArrayList<Node>> clusters = new HashMap<>();
        clusters.put(c, list(n1, n2, n3));
        CBLayoutAlgorithm.clusterMap = clusters;
        store(new DistributionConstraintGraph(list(n1, n2, n3), HORIZONTAL, 120));
        CBLayoutAlgorithm.layoutHierarchically(g);
        assertEquals(120, AttributeHelper.getPositionX(n2) - AttributeHelper.getPositionX(n1), 1e-3);
        assertEquals(120, AttributeHelper.getPositionX(n3) - AttributeHelper.getPositionX(n2), 1e-3);
    }

    @Test
    public void stepSolveReportsTheExactGapItCannotHold() {
        Graph g = new AdjListGraph();
        Node a = node(g, 0, 0), b = node(g, 150, 0), c = node(g, 0, 150);
        SeparationConstraintGraph near = gap(a, b, true, 100), far = gap(a, b, true, 200);
        Constraints s = store(near, far);
        ConflictLog.clear();
        List<Node[]> edges = Arrays.asList(new Node[]{a, b}, new Node[]{a, c});
        CBLayoutAlgorithm.solveNodes(new ArrayList<>(g.getNodes()), null, edges, null, s.getAllConstraints(), true);
        boolean reported = false;
        for (ConflictLog.Entry e : ConflictLog.entries()) {
            reported |= e.kind == ConflictLog.Kind.UNSATISFIED && (e.constraint == near || e.constraint == far);
        }
        assertTrue("one of the two exact gaps is reported", reported);
        ConflictLog.clear();
    }

    @Test
    public void solveFreesItsNativeObjectsAndStateOfClosedGraphsGoes() {
        Graph g = new AdjListGraph();
        Node a = node(g, 0, 0), b = node(g, 150, 40);
        store(row(a, b));
        CBLayoutAlgorithm.executeUserConstraints(g, false, true);
        assertNull("the layout of the finished solve is released", CBLayoutAlgorithm.constraintFDLayout);
        assertNull(CBLayoutAlgorithm.rectPtrVector);
        double ya = AttributeHelper.getPositionY(a);
        assertEquals(ya, AttributeHelper.getPositionY(b), 1e-6);
        CBLayoutAlgorithm.executeUserConstraints(g, false, true);
        at(a, AttributeHelper.getPositionX(a), ya, "a after a second solve");

        CBLayoutAlgorithm.lastUserScope = new ConstraintConflicts.Scope(g.getNodes(), g, Double.NaN, false, false);
        CBLayoutAlgorithm.releaseSolveState(Collections.singletonList(g));
        assertFalse("an open graph keeps its state", CBLayoutAlgorithm.nodeIndex.isEmpty());
        assertNotNull(CBLayoutAlgorithm.lastUserScope);
        CBLayoutAlgorithm.releaseSolveState(Collections.<Graph>emptyList());
        assertTrue(CBLayoutAlgorithm.nodeIndex.isEmpty());
        assertNull(CBLayoutAlgorithm.lastUserScope);
        assertTrue(CBLayoutAlgorithm.movedByLastSolve().isEmpty());
    }
}
