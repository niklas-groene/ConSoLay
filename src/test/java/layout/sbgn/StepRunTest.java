package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ClusterConstraintGraph;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintCheck;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.HorizontalConstraint;
import constraints.LockConstraintGraph;
import constraints.OrthogonalEdgeConstraintGraph;

import org.AttributeHelper;
import org.graffiti.event.GraphAdapter;
import org.graffiti.event.GraphEvent;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sbgn.Language;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs of the SBGN step layouts that a fresh "Run all steps" does not cover: steps run again without Reset, a reset
 * after several runs, an unfinished run that another one replaces, and user constraints on the glyphs, nodes and
 * edges that a step takes out of the graph for a while. The graphs listen to deletions as the add-on does inside
 * VANTED. Needs the Adaptagrams natives; run with {@code ant test}.
 */
public class StepRunTest {

    private static final String PCR = "Files/principle_of_the_pcr.sbgn";
    private static final String CALCIUM =
            "Files/regulation_of_calcium_calmoduline_kinase_ii_effect_on_synaptic_plasticity.sbgn";
    private static final String SUBMAP = "Files/insulin-like_growth_factor_signaling_withSubmap_mapk_cascade.sbgn";

    @BeforeClass
    public static void natives() throws Exception {
        SbgnUserTypesTest.natives();
    }

    /** A map with a listener that takes a deleted node or edge out of the store's constraints, as the add-on does. */
    static Graph load(String file) throws Exception {
        Graph g = SbgnUserTypesTest.load(file);
        SbgnUserTypesTest.fresh();
        g.getListenerManager().addDelayedGraphListener(new GraphAdapter() {
            @Override
            public void preNodeRemoved(GraphEvent e) {
                ConstraintStore.constraints.removeNode(e.getNode());
            }

            @Override
            public void preEdgeRemoved(GraphEvent e) {
                Constraints store = ConstraintStore.constraints;
                for (OrthogonalEdgeConstraintGraph c : store.getConstraints(OrthogonalEdgeConstraintGraph.class)) {
                    if (c.getEdge() == e.getEdge()) store.removeConstraint(c);
                }
            }
        });
        return g;
    }

    static ConstraintSBGNLayout layout(Graph g, Language language) throws Exception {
        if (language != Language.PD) ConstraintSBGNLayout.view2D = SbgnUserTypesTest.headlessView(g);
        return new ConstraintSBGNLayout(g, language);
    }

    static int count(Graph g, String role) {
        return SbgnUserTypesTest.ofRole(g, role).size();
    }

    /** The nodes of {@code role} (contained in the role name), in a stable order. */
    static List<Node> withRole(Graph g, String role) {
        List<Node> l = new ArrayList<>();
        for (Node n : g.getNodes()) if (ConstraintSBGNLayout.getSBGNRole(n).contains(role)) l.add(n);
        return l;
    }

    /** The places in the node list of the map (as read) of the nodes that ER step 1 takes out of the graph. */
    static List<Integer> removedByErStepOne(String file) throws Exception {
        Graph g = load(file);
        List<Node> nodes = new ArrayList<>(g.getNodes());
        layout(g, Language.ER).runStep(0);
        List<Integer> removed = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) if (!g.containsNode(nodes.get(i))) removed.add(i);
        assertFalse(removed.isEmpty());
        return removed;
    }

    static boolean inStore(Constraint c) {
        for (Constraint x : ConstraintStore.constraints.getAllConstraints()) if (x == c) return true;
        return false;
    }

    static boolean inGraph(Constraint c, Graph g) {
        for (Node n : c.getNodes()) if (!g.containsNode(n)) return false;
        return true;
    }

    static List<Node> component(Node start) {
        List<Node> part = new ArrayList<>(Collections.singletonList(start));
        Set<Node> seen = new HashSet<>(part);
        for (int i = 0; i < part.size(); i++) {
            for (Node m : part.get(i).getNeighbors()) if (seen.add(m)) part.add(m);
        }
        return part;
    }

    @Test
    public void pdStepOneAgainKeepsTheCompartmentsAndTags() throws Exception {
        Graph g = load(SUBMAP);
        Node submap = SbgnUserTypesTest.ofRole(g, "SUBMAP").get(0);
        int compartments = count(g, "COMPARTMENT"), submapDegree = submap.getDegree();
        Map<Node, Integer> tagDegree = new HashMap<>();
        for (Node t : withRole(g, "TAG")) tagDegree.put(t, t.getDegree());
        assertTrue(compartments > 0 && !tagDegree.isEmpty());

        ConstraintSBGNLayout layout = layout(g, Language.PD);
        layout.runStep(0);
        layout.runStep(0);
        layout.runAll();
        layout.resetToOriginal();
        assertEquals("compartments after Reset", compartments, count(g, "COMPARTMENT"));
        assertEquals("submap degree after Reset", submapDegree, submap.getDegree());
        for (Map.Entry<Node, Integer> e : tagDegree.entrySet()) {
            assertEquals("tag degree after Reset", (int) e.getValue(), e.getKey().getDegree());
        }
    }

    @Test
    public void resetAfterTwoRunsReturnsToTheInput() throws Exception {
        Graph g = load(SUBMAP);
        Map<Node, double[]> position = new HashMap<>();
        for (Node n : g.getNodes()) {
            if (ConstraintSBGNLayout.isCompartment(n)) continue;
            position.put(n, new double[]{ AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n) });
        }
        Map<Edge, String> docking = new HashMap<>();
        for (Edge e : g.getEdges()) docking.put(e, dockingOf(e));
        ConstraintSBGNLayout layout = layout(g, Language.PD);
        layout.runAll();
        layout.runAll();
        layout.resetToOriginal();
        for (Map.Entry<Node, double[]> e : position.entrySet()) {
            assertEquals(e.getValue()[0], AttributeHelper.getPositionX(e.getKey()), 1e-9);
            assertEquals(e.getValue()[1], AttributeHelper.getPositionY(e.getKey()), 1e-9);
        }
        for (Map.Entry<Edge, String> e : docking.entrySet()) assertEquals(e.getValue(), dockingOf(e.getKey()));
    }

    private static String dockingOf(Edge e) {
        EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
        return ega.getDocking().getSource() + "|" + ega.getDocking().getTarget();
    }

    @Test
    public void secondRunReplacesTheConstraintsOfTheFirst() throws Exception {
        Graph g = load("Files/glycolysis.sbgn");
        ConstraintSBGNLayout layout = layout(g, Language.PD);
        layout.runAll();
        Set<Constraint> first = Collections.newSetFromMap(new IdentityHashMap<>());
        first.addAll(ConstraintStore.constraints.getAllConstraints());
        assertFalse(first.isEmpty());
        layout.runAll();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            assertFalse("a constraint of the first run is left", first.contains(c));
        }
        Set<Constraint> step3 = Collections.newSetFromMap(new IdentityHashMap<>());
        step3.addAll(layout.runStep(2));
        assertFalse(step3.isEmpty());
        layout.runStep(2);
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            assertFalse("step 3 ran again on top of its constraints", step3.contains(c));
        }
    }

    @Test
    public void userConstraintsOnCompartmentsStay() throws Exception {
        Graph g = load(SUBMAP);
        List<Node> compartments = SbgnUserTypesTest.ofRole(g, "COMPARTMENT");
        List<Node> mm = SbgnUserTypesTest.ofRole(g, "MACROMOLECULE");
        HorizontalConstraint frames = new HorizontalConstraint(Arrays.asList(compartments.get(0), compartments.get(1)),
                HorizontalConstraint.HorizontalConstraintType.MIDDLE);
        HorizontalConstraint row = new HorizontalConstraint(Arrays.asList(compartments.get(0), mm.get(0), mm.get(1)),
                HorizontalConstraint.HorizontalConstraintType.MIDDLE);
        ConstraintStore.constraints.addConstraint(frames);
        ConstraintStore.constraints.addConstraint(row);
        ConstraintSBGNLayout layout = layout(g, Language.PD);
        layout.runStep(0);
        assertFalse("not applied while its compartment is out of the graph", inStore(row));
        layout.runStep(1);
        layout.runAll();
        assertEquals("unchanged by the run", 3, row.getNodes().size());
        layout.resetToOriginal();
        for (Constraint c : Arrays.asList(frames, row)) {
            assertTrue("kept by Reset", inStore(c) && inGraph(c, g));
            assertTrue(ConstraintSBGNLayout.isCompartment(c.getNodes().get(0)));
        }
        assertEquals(3, row.getNodes().size());
        assertEquals(compartments.size(), count(g, "COMPARTMENT"));

        // a finished run that another one replaces: the frames it dropped leave the constraints, as after a deletion
        layout.runAll();
        layout.discardRun();
        assertTrue(inStore(row) && inGraph(row, g));
        assertEquals(Arrays.asList(mm.get(0), mm.get(1)), row.getNodes());
        assertFalse(inStore(frames));
    }

    @Test
    public void erKeepsPinsAndEdgeConstraintsOnRemovedElements() throws Exception {
        List<Integer> removed = removedByErStepOne(CALCIUM);
        Graph g = load(CALCIUM);
        Node o = g.getNodes().get(removed.get(0));
        Map<Node, double[]> at = new HashMap<>();
        at.put(o, new double[]{ 500, 400 });
        LockConstraintGraph pin = new LockConstraintGraph(at);
        Edge hyperedge = null;
        for (Edge e : g.getEdges()) {
            boolean ofHyperedge = ERHyperedgeLayout.HYPEREDGE_ROLES.contains(ConstraintSBGNLayout.getSBGNRole(e));
            if (hyperedge == null && ofHyperedge) hyperedge = e;
        }
        assertNotNull(hyperedge);
        OrthogonalEdgeConstraintGraph straight = new OrthogonalEdgeConstraintGraph(hyperedge, true);
        long id = straight.getId();
        ConstraintStore.constraints.addConstraint(pin);
        ConstraintStore.constraints.addConstraint(straight);

        ConstraintSBGNLayout layout = layout(g, Language.ER);
        layout.runStep(0);
        assertFalse("not applied while its node is out of the graph", inStore(pin));
        layout.runAll();
        assertTrue("pin kept by the run", inStore(pin) && inGraph(pin, g));
        assertNotNull(pin.getPosition(pin.getNodes().get(0)));
        assertTrue("edge constraint kept, as the same constraint", inStore(straight) && straight.getId() == id);
        assertTrue(g.containsEdge(straight.getEdge()));
        layout.resetToOriginal();
        assertTrue("pin kept by Reset", inStore(pin) && inGraph(pin, g));
        assertTrue("edge constraint kept by Reset", inStore(straight) && g.containsEdge(straight.getEdge()));
    }

    @Test
    public void erLeavesAConstraintTheUserDeletedBetweenSteps() throws Exception {
        List<Integer> removed = removedByErStepOne(CALCIUM);
        Graph g = load(CALCIUM);
        List<Node> entities = SbgnUserTypesTest.ofRole(g, "ENTITY");
        Node o = g.getNodes().get(removed.get(0));
        HorizontalConstraint c = new HorizontalConstraint(Arrays.asList(entities.get(0), entities.get(1), o),
                HorizontalConstraint.HorizontalConstraintType.MIDDLE);
        ConstraintStore.constraints.addConstraint(c);
        ConstraintSBGNLayout layout = layout(g, Language.ER);
        layout.runStep(0);
        assertTrue(inStore(c));
        ConstraintStore.constraints.removeConstraint(c);
        for (int i = 1; i < 4; i++) layout.runStep(i);
        assertFalse("deleted by the user, back after the steps", inStore(c));
        layout.resetToOriginal();
        assertFalse("deleted by the user, back after Reset", inStore(c));
    }

    @Test
    public void erStepTwoAgainKeepsAConstraintTheUserTookOver() throws Exception {
        Graph g = load(PCR);
        ConstraintSBGNLayout layout = layout(g, Language.ER);
        layout.runStep(0);
        List<Constraint> created = layout.runStep(1);
        Constraint taken = null;
        for (Constraint c : created) if (taken == null && inStore(c)) taken = c;
        assertNotNull(taken);
        taken.setAutomatic(false);   // as ConflictLog.restore does
        layout.runStep(1);
        assertTrue("the user's constraint went with the step's", inStore(taken));
    }

    @Test
    public void resetOfAMapThatIsNotActiveUsesItsOwnStore() throws Exception {
        List<Integer> removed = removedByErStepOne(CALCIUM);
        Graph g = load(CALCIUM);
        Graph other = new AdjListGraph();
        try {
            ConstraintStore.activate(g);
            Node e = SbgnUserTypesTest.ofRole(g, "ENTITY").get(0);
            HorizontalConstraint c = new HorizontalConstraint(Arrays.asList(e, g.getNodes().get(removed.get(0))),
                    HorizontalConstraint.HorizontalConstraintType.MIDDLE);
            ConstraintStore.constraints.addConstraint(c);
            ConstraintSBGNLayout layout = layout(g, Language.ER);
            layout.runStep(0);
            ConstraintStore.activate(other);
            layout.resetToOriginal();
            assertTrue("in the store of its map", ConstraintStore.of(g).getAllConstraints().contains(c));
            assertFalse("in the store of the active map", ConstraintStore.of(other).getAllConstraints().contains(c));
        } finally {
            ConstraintStore.activate(null);
        }
    }

    @Test
    public void anUnfinishedRunIsDiscardedAFinishedOneStays() throws Exception {
        Graph g = load(PCR);
        int edges = g.getNumberOfEdges(), nodes = g.getNumberOfNodes();
        ConstraintSBGNLayout layout = layout(g, Language.ER);
        layout.runStep(0);
        assertTrue(g.getNumberOfEdges() < edges);
        layout.discardRun();
        assertEquals(edges, g.getNumberOfEdges());
        assertEquals(nodes, g.getNumberOfNodes());

        layout.runAll();
        Map<Node, Double> x = new HashMap<>();
        for (Node n : g.getNodes()) x.put(n, AttributeHelper.getPositionX(n));
        layout.discardRun();
        for (Map.Entry<Node, Double> en : x.entrySet()) {
            assertEquals(en.getValue(), AttributeHelper.getPositionX(en.getKey()), 0);
        }
    }

    @Test
    public void afStepsDoNotPileUpPortBends() throws Exception {
        Graph g = load("Files/regulation_of_tgfbeta-induced_metastasis.sbgn");
        ConstraintSBGNLayout layout = layout(g, Language.AF);
        for (int i = 0; i < layout.getSteps().size() - 1; i++) {
            layout.runStep(i);
            for (Edge e : g.getEdges()) {
                assertTrue("bends of an arc after step " + (i + 1), AttributeHelper.getEdgeBends(e).size() <= 2);
            }
        }
    }

    /** The third activity of one chain aligned with the second of another: packing moves the two chains as one. */
    @Test
    public void afKeepsAnAlignmentAcrossComponents() throws Exception {
        Graph g = load("Files/the_role_voltage-gated_sodium_channel_plays_in_action_potential_generation.sbgn");
        Node a = labelled(g, "conductance"), b = labelled(g, "sodiumchannel");
        assertFalse(component(a).contains(b));
        HorizontalConstraint c = new HorizontalConstraint(Arrays.asList(a, b),
                HorizontalConstraint.HorizontalConstraintType.MIDDLE);
        ConstraintStore.constraints.addConstraint(c);
        layout(g, Language.AF).runAll();
        String why = ConstraintCheck.violation(c, g.getNodes(), ConstraintCheck.TOLERANCE);
        assertNull(why, why);
    }

    static Node labelled(Graph g, String text) {
        for (Node n : g.getNodes()) if (AttributeHelper.getLabel(n, "").replaceAll("<[^>]*>", "").contains(text)) return n;
        return null;
    }

    /** -Dcblayout.erMode=node, a map with a second part (an entity of its own): the part is not packed off its pin. */
    @Test
    public void erNodeModeKeepsAPin() throws Exception {
        boolean hyperedge = ERHyperedgeLayout.ENABLED, contract = ERLayout.CONTRACT;
        ERHyperedgeLayout.ENABLED = false;
        ERLayout.CONTRACT = false;
        try {
            Graph g = load(PCR);
            Node alone = g.addNode();
            AttributeHelper.setAttribute(alone, "sbgn", "role", "ENTITY");
            AttributeHelper.setSize(alone, 60, 30);
            AttributeHelper.setPosition(alone, 900, 700);
            assertTrue(ERLayout.componentsOf(g).size() > 1);
            Map<Node, double[]> at = new HashMap<>();
            at.put(alone, new double[]{ 900, 700 });
            LockConstraintGraph pin = new LockConstraintGraph(at);
            ConstraintStore.constraints.addConstraint(pin);
            layout(g, Language.ER).runAll();
            String why = ConstraintCheck.violation(pin, g.getNodes(), ConstraintCheck.TOLERANCE);
            assertNull(why, why);
        } finally {
            ERHyperedgeLayout.ENABLED = hyperedge;
            ERLayout.CONTRACT = contract;
        }
    }

    /** A cluster of the two top-level complexes: the glyphs drawn inside them are no intruders. */
    @Test
    public void clusterOfComplexesIsNotReportedForTheirContents() throws Exception {
        Graph g = load("Files/activated_stat1alpha_induction_of_the_irf1_gene.sbgn");
        List<Node> complexes = SbgnUserTypesTest.ofRole(g, "COMPLEX"), top = new ArrayList<>();
        for (Node c : complexes) {
            boolean inner = false;
            for (Node d : complexes) inner |= d != c && ConstraintSBGNLayout.isContained(d, c);
            if (!inner) top.add(c);
        }
        assertEquals(2, top.size());
        ClusterConstraintGraph cluster = new ClusterConstraintGraph(top, 10, 10);
        ConstraintStore.constraints.addConstraint(cluster);
        layout(g, Language.PD).runAll();
        assertNull(ConstraintCheck.violation(cluster, CBLayoutAlgorithm.userSolveNodes(g), ConstraintCheck.TOLERANCE));
        for (ConflictLog.Entry e : ConflictLog.entries()) {
            assertFalse("listed: " + e.reason, e.constraint == cluster && e.kind == ConflictLog.Kind.UNSATISFIED);
        }
    }
}
