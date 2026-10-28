package layout.template.search;

import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The search on small graphs built here: composite joins, the limits and cancelling of a run, the symmetric counts of
 * the variants and the revalidation of a match. No GUI and no solver. Run with {@code ant test}.
 */
public class SearchRunTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A link between components finds its edge even when another edge between the same nodes comes first. */
    @Test
    public void compositeFindsTheFittingEdgeBehindAnother() throws Exception {
        TemplateDef process = single("Process", "PROCESS"), protein = single("Protein", "MACROMOLECULE");
        TemplateDef t = composite("Regulated", process, protein);
        t.links.add(new StructureLink("l1", "c1", "p1", "c2", "p1"));
        for (boolean reverseFirst : new boolean[]{true, false}) {
            Graph g = new AdjListGraph();
            Node x = node(g, "PROCESS"), y = node(g, "MACROMOLECULE");
            if (reverseFirst) g.addEdge(y, x, true);
            g.addEdge(x, y, true);
            if (!reverseFirst) g.addEdge(y, x, true);
            SearchRun run = run(g, true, task(t));
            assertEquals(SearchRun.State.COMPLETE, run.state());
            assertEquals("reverse edge first: " + reverseFirst, 1, run.matches().size());
        }
    }

    /** A component cut off at the limit marks the run as limited, but the later rules are still searched. */
    @Test
    public void laterRulesAreSearchedAfterACutOffComponent() throws Exception {
        Graph g = new AdjListGraph();
        Node prev = null;
        for (int i = 0; i < 5; i++) {
            Node n = g.addNode();
            if (prev != null) g.addEdge(prev, n, true);
            prev = n;
        }
        TemplateDef any = single("Any", null);
        TemplateDef pair = composite("Pair", any, any);
        pair.links.add(new StructureLink("l1", "c1", "p1", "c2", "p1"));
        SearchTask first = task(pair), second = task(edge());
        int before = TemplateLimits.maxPartsPerComponent;
        TemplateLimits.maxPartsPerComponent = 3;
        try {
            SearchRun run = run(g, false, first, second);
            assertEquals(SearchRun.State.LIMITED, run.state());
            assertTrue(run.limitMessage(), run.limitMessage().contains("occurs more than 3 times"));
            int fromSecond = 0;
            for (Match m : run.matches()) if (m.task == second) fromSecond++;
            assertEquals(4, fromSecond);
        } finally {
            TemplateLimits.maxPartsPerComponent = before;
        }
    }

    /** Partial assignments that never complete are bounded too: the run stops as limited and says why. */
    @Test
    public void partialAssignmentsAreBounded() throws Exception {
        Graph g = new AdjListGraph();
        List<Node> left = new ArrayList<>();
        for (int i = 0; i < 4; i++) left.add(g.addNode());
        for (int i = 0; i < 40; i++) {
            Node r = g.addNode();
            for (Node l : left) g.addEdge(l, r, false);
        }
        int before = TemplateLimits.maxSteps;
        TemplateLimits.maxSteps = 10_000;
        try {
            SearchRun run = run(g, false, task(cycle(5)));   // odd cycles never fit a bipartite graph
            assertEquals(SearchRun.State.LIMITED, run.state());
            assertEquals(10_000, run.steps());
            assertTrue(run.limitMessage(), run.limitMessage().contains("10,000 partial assignments"));
        } finally {
            TemplateLimits.maxSteps = before;
        }
    }

    /** A cancel that arrives while a component's occurrences are collected leaves the run cancelled, not complete. */
    @Test
    public void cancelWhileCollectingComponents() throws Exception {
        Graph g = new AdjListGraph();
        Node a = g.addNode(), b = g.addNode();
        for (int i = 0; i < 600; i++) {
            Node r = g.addNode();
            g.addEdge(a, r, false);
            g.addEdge(b, r, false);
        }
        TemplateDef triangle = cycle(3);
        triangle.ports.add(new Port(triangle.nextPortId(), "Corner", "n1"));
        TemplateDef t = composite("Triangle and node", triangle, single("Any", null));
        t.links.add(new StructureLink("l1", "c1", "p1", "c2", "p1"));
        GraphSnapshot snap = GraphSnapshot.capture(g, null, new HashSet<>(), new HashSet<>(), 1);
        SearchRun run = new SearchRun(snap, Collections.singletonList(task(t)), new SearchRun.Options(false, false, 1000));
        run.cancel();
        runNow(run);
        assertEquals(SearchRun.State.CANCELLED, run.state());
    }

    /** Every variant of a match counts its symmetric assignments, not only the first one. */
    @Test
    public void everyVariantCountsItsSymmetricAssignments() throws Exception {
        Graph g = new AdjListGraph();
        for (int i = 0; i < 3; i++) g.addNode();
        TemplateDef t = TemplateDef.create("Three", TemplateDef.Kind.PATTERN);
        t.nodes.add(new PatternNode(t.nextNodeId(), 0, 0));
        t.nodes.add(new PatternNode(t.nextNodeId(), 0, 0));
        t.nodes.add(new PatternNode(t.nextNodeId(), 10, 0));
        SearchRun run = run(g, false, task(t));
        assertEquals(1, run.matches().size());
        Match m = run.matches().get(0);
        assertEquals(3, m.variants().size());
        for (Match.Variant v : m.variants()) assertEquals(2, v.symmetricCount());
    }

    /** Revalidation reads the attributes that the links of nested composites ask about. */
    @Test
    public void revalidationKnowsConditionsOfNestedLinks() throws Exception {
        Graph g = new AdjListGraph();
        Node a = g.addNode(), b = g.addNode();
        Edge e = g.addEdge(a, b, true);
        AttributeHelper.setAttribute(e, "trn", "effect", "activator");
        TemplateDef any = single("Any", null);
        TemplateDef inner = composite("Activation", any, any);
        StructureLink l = new StructureLink("l1", "c1", "p1", "c2", "p1");
        l.edgeConditions.add(Condition.equalsTo("trn.effect", "activator"));
        inner.links.add(l);
        TemplateDef outer = composite("Wrapped", inner);
        SearchRun run = run(g, true, task(outer));
        assertEquals(1, run.matches().size());
        SwingUtilities.invokeAndWait(() -> assertNull(MatchValidator.revalidate(run.matches().get(0), g)));
    }

    // ================================================================== building blocks

    /** A pattern of one node (with an SBGN role condition unless {@code role} is null) and a port on it. */
    private static TemplateDef single(String name, String role) {
        TemplateDef t = TemplateDef.create(name, TemplateDef.Kind.PATTERN);
        PatternNode n = new PatternNode(t.nextNodeId(), 0, 0);
        if (role != null) n.conditions.add(Condition.equalsTo(Condition.SBGN_ROLE, role));
        t.nodes.add(n);
        t.ports.add(new Port(t.nextPortId(), "Node", n.id));
        return t;
    }

    private static TemplateDef edge() {
        TemplateDef t = TemplateDef.create("Edge", TemplateDef.Kind.PATTERN);
        t.nodes.add(new PatternNode(t.nextNodeId(), 0, 0));
        t.nodes.add(new PatternNode(t.nextNodeId(), 50, 0));
        t.edges.add(new PatternEdge(t.nextEdgeId(), "n1", "n2", true));
        return t;
    }

    private static TemplateDef cycle(int n) {
        TemplateDef t = TemplateDef.create("Cycle", TemplateDef.Kind.PATTERN);
        for (int i = 0; i < n; i++) t.nodes.add(new PatternNode(t.nextNodeId(), 10 * i, 0));
        for (int i = 0; i < n; i++) {
            t.edges.add(new PatternEdge(t.nextEdgeId(), "n" + (i + 1), "n" + ((i + 1) % n + 1), false));
        }
        return t;
    }

    /** A composite of {@code parts} as components c1, c2, …, with the parts embedded. */
    private static TemplateDef composite(String name, TemplateDef... parts) {
        TemplateDef t = TemplateDef.create(name, TemplateDef.Kind.COMPOSITE);
        for (TemplateDef p : parts) {
            t.components.add(new ComponentUse(t.nextComponentId(), p.name + " " + (t.components.size() + 1), p.ref()));
            for (TemplateDef d : Templates.closure(p, TemplateSource.embeddedIn(p))) {
                t.dependencies.put(d.id + "@" + d.revision, d);
            }
        }
        return t;
    }

    private static Node node(Graph g, String role) {
        Node n = g.addNode();
        AttributeHelper.setAttribute(n, "sbgn", "role", role);
        return n;
    }

    private static SearchTask task(TemplateDef t) {
        return new SearchTask(t, TemplateSource.embeddedIn(t), null, null);
    }

    /** One run on {@code g}, capturing the attributes every condition of the tasks reads (links of all levels). */
    private static SearchRun run(Graph g, boolean semantics, SearchTask... tasks) throws Exception {
        Set<String> np = new HashSet<>(), ep = new HashSet<>();
        for (SearchTask t : tasks) {
            for (PatternNode n : Templates.flatNodes(t.template, t.source).values()) {
                for (Condition c : n.conditions) np.add(c.attribute);
            }
            for (PatternEdge e : Templates.flatEdges(t.template, t.source).values()) {
                for (Condition c : e.conditions) ep.add(c.attribute);
            }
            for (TemplateDef d : Templates.closure(t.template, t.source)) {
                for (StructureLink l : d.links) for (Condition c : l.edgeConditions) ep.add(c.attribute);
            }
        }
        GraphSnapshot snap = GraphSnapshot.capture(g, null, np, ep, 1);
        SearchRun run = new SearchRun(snap, Arrays.asList(tasks), new SearchRun.Options(semantics, false, 1000));
        runNow(run);
        return run;
    }

    private static void runNow(SearchRun run) throws Exception {
        run.runNow(new SearchRun.Listener() {
            @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) { }
            @Override public void finished(SearchRun r) { }
        });
        SwingUtilities.invokeAndWait(() -> { });   // the results are delivered on the event thread
    }
}
