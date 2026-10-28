package layout.mental_map;

import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.OrthogonalEdgeConstraintGraph;

import org.AttributeHelper;
import org.graffiti.editor.actions.PasteAction;
import org.graffiti.event.GraphAdapter;
import org.graffiti.event.GraphEvent;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Restoring a {@link Snapshot} keeps the edge constraints on the edges it re-creates, a pasted node (which carries the
 * uid of its original) is a node of its own, and the {@link SnapshotHistory} cap never drops the pinned reference.
 * Needs no natives; run with {@code ant test}.
 */
public class SnapshotTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Before
    public void emptyStore() {
        ConstraintStore.constraints = new Constraints();
    }

    private static Node node(Graph g, String label, double x, double y) {
        Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(x, y));
        AttributeHelper.setLabel(n, label);
        return n;
    }

    private static Edge edge(Graph g, Node source, Node target) {
        return g.addEdge(source, target, true,
                AttributeHelper.getDefaultGraphicsAttributeForEdge(Color.BLACK, Color.BLACK, true));
    }

    /** Takes a deleted edge's horizontal / vertical constraints out of the store, as the add-on does in VANTED. */
    private static void dropConstraintsOfDeletedEdges(Graph g) {
        g.getListenerManager().addDelayedGraphListener(new GraphAdapter() {
            @Override
            public void preEdgeRemoved(GraphEvent e) {
                for (OrthogonalEdgeConstraintGraph c
                        : ConstraintStore.constraints.getConstraints(OrthogonalEdgeConstraintGraph.class)) {
                    if (c.getEdge() == e.getEdge()) ConstraintStore.constraints.removeConstraint(c);
                }
            }
        });
    }

    private static String labels(Edge e) {
        return AttributeHelper.getLabel(e.getSource(), "") + ">" + AttributeHelper.getLabel(e.getTarget(), "");
    }

    @Test
    public void edgeConstraintStaysOnARestoredEdge() {
        Graph g = new AdjListGraph();
        Node a = node(g, "A", 100, 100), b = node(g, "B", 300, 100), c = node(g, "C", 300, 300);
        Edge ab = edge(g, a, b);
        edge(g, b, c);
        OrthogonalEdgeConstraintGraph horizontal = new OrthogonalEdgeConstraintGraph(ab, true);
        ConstraintStore.constraints.addConstraint(horizontal);
        dropConstraintsOfDeletedEdges(g);

        Snapshot s = Snapshot.capture(g, "s");
        AttributeHelper.setPosition(c, 500, 500);
        s.restore(g, 0, 0);

        List<Constraint> all = ConstraintStore.constraints.getAllConstraints();
        assertEquals(1, all.size());
        assertSame(horizontal, all.get(0));
        assertTrue(g.getEdges().contains(horizontal.getEdge()));
        assertSame(a, horizontal.getEdge().getSource());
        assertSame(b, horizontal.getEdge().getTarget());
        assertEquals(2, horizontal.getNodes().size());
        assertEquals(300, AttributeHelper.getPositionX(c), 0);
        // the edges in the order of the snapshot
        List<String> order = new ArrayList<>();
        for (Edge e : g.getEdges()) order.add(labels(e));
        assertEquals("[A>B, B>C]", order.toString());
    }

    @Test
    public void edgeConstraintGoesWithAnEdgeTheSnapshotLacks() {
        Graph g = new AdjListGraph();
        Node a = node(g, "A", 100, 100), b = node(g, "B", 300, 100);
        Snapshot s = Snapshot.capture(g, "without edge");
        ConstraintStore.constraints.addConstraint(new OrthogonalEdgeConstraintGraph(edge(g, a, b), false));
        dropConstraintsOfDeletedEdges(g);

        s.restore(g, 0, 0);

        assertEquals(0, g.getNumberOfEdges());
        assertTrue(ConstraintStore.constraints.getAllConstraints().isEmpty());
    }

    /** A node as VANTED's copy and paste makes it: with every attribute of {@code original}, the uid too. */
    private static Node paste(Graph g, Node original) {
        Node pasted = g.addNodeCopy(original);
        AttributeHelper.setAttribute(pasted, "", PasteAction.PASTED_NODE, Boolean.TRUE);
        AttributeHelper.setPosition(pasted, 600, 500);
        assertEquals(Snapshot.uidOf(original), Snapshot.uidOf(pasted));
        return pasted;
    }

    @Test
    public void pastedCopyGetsAUidOfItsOwn() {
        Graph g = new AdjListGraph();
        Node a = node(g, "A", 100, 100), b = node(g, "B", 300, 100);
        edge(g, a, b);
        Snapshot before = Snapshot.capture(g, "before");
        String uid = Snapshot.uidOf(a);
        Node pasted = paste(g, a);

        Snapshot after = Snapshot.capture(g, "after");
        assertEquals(uid, Snapshot.uidOf(a));
        assertFalse(uid.equals(Snapshot.uidOf(pasted)));
        assertEquals(3, after.positionsByUid().size());
        Map<Node, Node> map = before.mapToLive(g);
        assertEquals(2, map.size());
        assertTrue(map.containsValue(a) && map.containsValue(b));

        g.deleteNode(b);
        after.restore(g, 0, 0);
        assertEquals(3, g.getNumberOfNodes());
        Set<String> uids = new HashSet<>();
        for (Node n : g.getNodes()) uids.add(Snapshot.uidOf(n));
        assertEquals(3, uids.size());
        assertEquals(1, a.getEdges().size());
    }

    /** A copy pasted after the capture is new: the restore removes it, and the original keeps its place and edge. */
    @Test
    public void restoreRemovesACopyPastedSinceTheCapture() {
        Graph g = new AdjListGraph();
        Node a = node(g, "A", 100, 100), b = node(g, "B", 300, 100);
        edge(g, a, b);
        Snapshot before = Snapshot.capture(g, "before");
        paste(g, a);
        AttributeHelper.setPosition(a, 150, 150);

        before.restore(g, 0, 0);
        assertEquals(2, g.getNumberOfNodes());
        assertTrue(g.containsNode(a) && g.containsNode(b));
        assertEquals(1, a.getEdges().size());
        assertEquals(100, AttributeHelper.getPositionX(a), 0);
    }

    @Test
    public void capKeepsThePinnedReference() {
        Graph g = new AdjListGraph();
        node(g, "A", 100, 100);
        SnapshotHistory h = new SnapshotHistory();
        Snapshot initial = h.add(Snapshot.capture(g, "Initial"));
        Snapshot last = null;
        for (int i = 0; i < 40; i++) last = h.add(Snapshot.capture(g, "After " + i), false);
        assertSame(initial, h.getReference());
        assertEquals(30, h.getAll().size());
        assertSame(initial, h.getAll().get(0));
        assertSame(last, h.getAll().get(29));
    }
}
