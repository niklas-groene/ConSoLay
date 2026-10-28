package layout.store;

import constraints.Constraint;
import constraints.ConstraintGroup;
import constraints.ConstraintKind;
import constraints.ConstraintOrigin;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.Priority;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.steps.StepTemplates;
import layout.template.apply.InstanceKeys;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;
import layout.template.io.TemplateExamples;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;
import layout.template.search.Match;
import layout.template.search.SearchRun;

import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugins.ios.exporters.gml.GMLWriter;
import org.graffiti.plugins.ios.exporters.graphml.GraphMLWriter;
import org.graffiti.plugins.ios.importers.gml.GMLReader;
import org.graffiti.plugins.ios.importers.graphml.GraphMLReader;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sbgn.SbgnUtil;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNToolsTab;
import org.sbgned.sbgnml.SBGNMLReader;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The constraints of a map are saved with the map: one constraint of each of the 25 kinds, with priorities, switches,
 * a group and a rank, comes back equal through GML and GraphML, with no node moved and the same stable ids, and so do
 * the "Catalysed reaction" instances applied to glycolysis, one of them switched off, through GML. A map whose format
 * dropped the stable ids (SBGN-ML) is read by its SBGN glyph ids, one without any id by unique labels. Run with
 * {@code ant test}.
 */
public class StoreRoundTripTest {

    @BeforeClass
    public static void init() throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        NativeLoader.ensureLoaded();
        new SBGNToolsTab();   // the SBGN-ED statics the reader needs; opens no window
    }

    // ================================================================== every kind through the formats

    @Test
    public void allKindsThroughGml() throws Exception {
        roundTrip("GML", StoreRoundTripTest::throughGml);
    }

    @Test
    public void allKindsThroughGraphML() throws Exception {
        roundTrip("GraphML", StoreRoundTripTest::throughGraphML);
    }

    private interface Through {
        Graph apply(Graph g) throws Exception;
    }

    private void roundTrip(String format, Through through) throws Exception {
        Graph g = smallGraph();
        Constraints store = new Constraints();
        ConstraintStore.constraints = store;
        List<Constraint> made = allKinds(g);
        for (Constraint c : made) store.addConstraint(c);
        Set<ConstraintKind> kinds = EnumSet.noneOf(ConstraintKind.class);
        for (Constraint c : made) kinds.add(c.getKind());
        assertEquals("one constraint of every kind", EnumSet.allOf(ConstraintKind.class), kinds);
        assertEquals(25, made.size());

        assertTrue(GraphConstraints.markAttached(g));
        assertTrue("the first sync writes the attribute", GraphConstraints.sync(g, store));
        assertTrue(GraphConstraints.hasSaved(g));
        assertFalse("nothing changed: nothing written", GraphConstraints.sync(g, store));
        Map<String, double[]> before = positions(g);
        Map<String, String> idsBefore = stableIds(g);

        Graph g2 = through.apply(g);
        assertEquals(format + " keeps the nodes", g.getNumberOfNodes(), g2.getNumberOfNodes());
        assertEquals(format + " keeps the edges", g.getNumberOfEdges(), g2.getNumberOfEdges());
        assertTrue(format + " keeps the graph attribute", GraphConstraints.hasSaved(g2));
        assertEquals(format + " keeps the stable ids", idsBefore, stableIds(g2));

        Constraints store2 = new Constraints();
        StoreCodec.Report r = GraphConstraints.loadSaved(g2, store2, null);
        assertNotNull(r);
        assertEquals("problems: " + r.problems, 0, r.problems.size());
        assertEquals(25, r.constraints);
        assertEquals(1, r.groups);
        assertEquals(signatures(made), signatures(store2.getAllConstraints()));
        assertEquals("the order (age) is kept", orderedKinds(made), orderedKinds(store2.getAllConstraints()));
        Map<String, double[]> after = positions(g2);
        assertEquals("loading moves no node", before.keySet(), after.keySet());
        for (Map.Entry<String, double[]> e : before.entrySet()) {
            double[] p = after.get(e.getKey());
            assertEquals(e.getKey() + " x", e.getValue()[0], p[0], 1e-9);
            assertEquals(e.getKey() + " y", e.getValue()[1], p[1], 1e-9);
        }
        // the restored store serialises to the same document: the next sync writes nothing
        assertTrue(GraphConstraints.markAttached(g2));
        assertFalse("the restored store equals the saved one", GraphConstraints.sync(g2, store2));

        // without any stable id (as a tool that drops the attributes would leave it) the nodes are found by their labels
        String json = GraphConstraints.json(g, store);
        for (Node n : g2.getNodes()) if (GraphConstraints.nodeId(n, false) != null) n.removeAttribute(GraphConstraints.PATH + "." + GraphConstraints.NID);
        for (Edge e : g2.getEdges()) if (GraphConstraints.edgeId(e, false) != null) e.removeAttribute(GraphConstraints.PATH + "." + GraphConstraints.EID);
        Constraints store3 = new Constraints();
        StoreCodec.Report r3 = GraphConstraints.read(json, g2, store3, null);
        assertEquals("problems: " + r3.problems, 0, r3.problems.size());
        assertEquals(signatures(made), signatures(store3.getAllConstraints()));

        // an emptied store takes the attribute out again
        store.clear();
        assertTrue(GraphConstraints.sync(g, store));
        assertFalse(GraphConstraints.hasSaved(g));
    }

    // ================================================================== template instances

    @Test
    public void instancesThroughGmlAndByGlyphId() throws Exception {
        Graph g = glycolysis();
        Constraints store = new Constraints();
        ConstraintStore.constraints = store;
        TemplateDef t = example("example:catalysed-reaction");
        SearchRun run = StepTemplates.search(g, null, Collections.singletonList(StepTemplates.task(t)), true, false);
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        TemplateApplier.apply(g, reqs, store, false, null);
        List<TemplateInstance> insts = InstanceRegistry.of(g);
        assertEquals(31, insts.size());
        int perInstance = insts.get(0).constraints().size();
        assertEquals(31 * perInstance, store.getAllConstraints().size());
        // one instance off, one constraint off on its own, one with a higher priority
        TemplateApplier.setOn(insts.get(3), "", false);
        store.getAllConstraints().get(5).setEnabled(false);
        store.getAllConstraints().get(7).setPriority(Priority.HIGH);
        Set<String> keys = new TreeSet<>();
        for (TemplateInstance i : insts) keys.add(i.key);

        assertTrue(GraphConstraints.markAttached(g));
        assertTrue(GraphConstraints.sync(g, store));
        TemplateSource src = new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) { return ref.id.equals(t.id) ? t : null; }
            @Override public TemplateDef latest(String id) { return id.equals(t.id) ? t : null; }
        };

        // through GML: the stable ids come back with the map
        Graph g2 = throughGml(g);
        Constraints store2 = new Constraints();
        StoreCodec.Report r = GraphConstraints.loadSaved(g2, store2, src);
        assertNotNull(r);
        assertEquals("problems: " + r.problems, 0, r.problems.size());
        assertEquals(31, r.instances);
        assertEquals(31, InstanceRegistry.of(g2).size());
        assertEquals(store.getAllConstraints().size(), store2.getAllConstraints().size());
        assertEquals(signatures(store.getAllConstraints()), signatures(store2.getAllConstraints()));
        checkInstances(g2, store2, keys, t, perInstance);

        // as SBGN-ED would save it: no stable ids, the SBGN glyph ids find the nodes
        String json = GraphConstraints.json(g, store);
        Graph g3 = glycolysis();
        Constraints store3 = new Constraints();
        StoreCodec.Report r3 = GraphConstraints.read(json, g3, store3, src);
        assertEquals("problems: " + r3.problems, 0, r3.problems.size());
        assertEquals(31, r3.instances);
        assertEquals(signatures(store.getAllConstraints()), signatures(store3.getAllConstraints()));
        checkInstances(g3, store3, keys, t, perInstance);
    }

    private static void checkInstances(Graph g, Constraints store, Set<String> keys, TemplateDef t, int perInstance) {
        Set<String> keys2 = new TreeSet<>();
        int off = 0;
        for (TemplateInstance i : InstanceRegistry.of(g)) {
            keys2.add(i.key);
            assertEquals("the key is that of the reloaded graph", InstanceKeys.key(i.plan, i.nodes, i.edges), i.key);
            assertEquals(t.id, i.template.id);
            assertEquals(t.revision, i.template.revision);
            assertEquals(perInstance, i.constraints().size());
            assertEquals("the plan comes from the template", t.constraints.size(), i.plan.items.size());
            for (Constraint c : i.constraints()) {
                assertNotNull(c.getOrigin());
                assertEquals(i.id, c.getOrigin().getInstanceId());
                assertTrue(store.getAllConstraints().contains(c));
            }
            if (!i.isOn()) {
                off++;
                for (Constraint c : i.constraints()) assertFalse("a constraint of a switched-off instance is off", c.isEnabled());
            }
        }
        assertEquals("one key per instance", keys.size(), keys2.size());
        assertEquals("one instance is switched off", 1, off);
    }

    // ================================================================== helpers

    /** Nine labelled nodes a–i on a grid with three edges a→b, b→c, c→d. */
    static Graph smallGraph() {
        Graph g = new AdjListGraph();
        List<Node> ns = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(100 + i * 90.0, 100 + (i % 3) * 80.0));
            AttributeHelper.setSize(n, 40, 30);
            AttributeHelper.setLabel(n, String.valueOf((char) ('a' + i)));
            ns.add(n);
        }
        for (int i = 0; i < 3; i++) g.addEdge(ns.get(i), ns.get(i + 1), true, AttributeHelper.getDefaultGraphicsAttributeForEdge(Color.BLACK, Color.BLACK, true));
        return g;
    }

    static Map<String, Node> byLabel(Graph g) {
        Map<String, Node> m = new LinkedHashMap<>();
        for (Node n : g.getNodes()) m.put(AttributeHelper.getLabel(n, "?"), n);
        return m;
    }

    static String edgeName(Edge e) {
        return AttributeHelper.getLabel(e.getSource(), "?") + ">" + AttributeHelper.getLabel(e.getTarget(), "?");
    }

    /** One constraint of every kind on the small graph, with varied priorities and switches, one automatic in a group, one ranked. */
    static List<Constraint> allKinds(Graph g) {
        Map<String, Node> nodes = byLabel(g);
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Edge e : g.getEdges()) edges.put(edgeName(e), e);
        List<ConstraintSpec> specs = new ArrayList<>();
        ConstraintKind[] aligns = {ConstraintKind.ALIGN_TOP, ConstraintKind.ALIGN_MIDDLE, ConstraintKind.ALIGN_BOTTOM,
                ConstraintKind.ALIGN_LEFT, ConstraintKind.ALIGN_CENTER, ConstraintKind.ALIGN_RIGHT};
        String[] labels = {"a", "b", "c", "d", "e", "f", "g", "h", "i"};
        String[] anchors = {"top", "middle", "bottom", "left", "centre", "right"};
        for (int i = 0; i < aligns.length; i++) specs.add(spec(aligns[i]).role("nodes", Arrays.asList(labels[i], labels[i + 1]))
                .param("axis", i < 3 ? "y" : "x").param("anchor", anchors[i]));
        specs.add(spec(ConstraintKind.SEPARATION_X).role("first", l("a")).role("second", l("c")).param("axis", "x").param("distance", 50.0).param("exact", true));
        specs.add(spec(ConstraintKind.SEPARATION_Y).role("first", l("b")).role("second", l("d")).param("axis", "y").param("distance", 60.0).param("exact", false));
        specs.add(spec(ConstraintKind.ORDER_X).role("ordered", l("a", "b", "c")).param("axis", "x").param("gap", 20.0).param("exact", false));
        specs.add(spec(ConstraintKind.ORDER_Y).role("ordered", l("d", "e", "f")).param("axis", "y").param("gap", 25.0).param("exact", true));
        specs.add(spec(ConstraintKind.DISTRIBUTE_X).role("ordered", l("a", "b", "c")).param("axis", "x").param("spacing", 30.0));
        specs.add(spec(ConstraintKind.DISTRIBUTE_Y).role("ordered", l("g", "h", "i")).param("axis", "y").param("spacing", 35.0));
        specs.add(spec(ConstraintKind.DIVIDE_X).role("before", l("a")).role("after", l("b", "c")).param("axis", "x").param("gap", 10.0));
        specs.add(spec(ConstraintKind.DIVIDE_Y).role("before", l("d", "e")).role("after", l("f")).param("axis", "y").param("gap", 12.0));
        Map<String, double[]> offsets = new LinkedHashMap<>();
        offsets.put("a", new double[]{0, 0});
        offsets.put("b", new double[]{100, 0});
        specs.add(spec(ConstraintKind.RIGID).role("nodes", l("a", "b")).param("fixedPosition", false).param("offsets", offsets));
        specs.add(spec(ConstraintKind.CLUSTER).role("nodes", l("c", "d")).param("padding", 15.0).param("margin", 10.0));
        specs.add(spec(ConstraintKind.AREA).role("nodes", l("e", "f")).param("bounds", new double[]{0, 0, 800, 600}).param("margin", 10.0));
        specs.add(spec(ConstraintKind.EDGE_HORIZONTAL).role("edge", l("a>b")).param("direction", "horizontal"));
        specs.add(spec(ConstraintKind.EDGE_VERTICAL).role("ends", l("c", "d")).param("direction", "vertical"));
        Map<String, double[]> pin = new LinkedHashMap<>();
        pin.put("g", new double[]{700, 100});
        specs.add(spec(ConstraintKind.PIN).role("nodes", l("g")).param("positions", pin));
        Map<String, double[]> target = new LinkedHashMap<>();
        target.put("h", new double[]{800, 200});
        specs.add(spec(ConstraintKind.TARGET).role("nodes", l("h")).param("targets", target).param("weight", 1.0));
        specs.add(spec(ConstraintKind.ROW).role("ordered", l("a", "b", "c")).param("gap", 80.0).param("even", false));
        specs.add(spec(ConstraintKind.COLUMN).role("ordered", l("d", "e", "f")).param("gap", 90.0).param("even", true));
        specs.add(spec(ConstraintKind.GRID).role("cells", l("a", "b", "c", "d")).param("columns", 2.0).param("columnGap", 50.0).param("rowGap", 40.0));
        specs.add(spec(ConstraintKind.STAR).role("hub", l("i")).role("ring", l("a", "b", "c")).param("radius", 100.0)
                .param("startAngle", 0.0).param("angles", new double[]{0, 120, 240}));

        ConstraintSpecs.Binding binding = new ConstraintSpecs.Binding() {
            @Override public Node node(String id) { return nodes.get(id); }
            @Override public Edge edge(String id) { return edges.get(id); }
            @Override public double[] toTarget(double x, double y) { return new double[]{x, y}; }
        };
        List<Constraint> made = new ArrayList<>();
        Priority[] ps = Priority.values();
        ConstraintGroup group = new ConstraintGroup("star", "Star of i", "Stars");
        for (int i = 0; i < specs.size(); i++) {
            ConstraintSpec s = specs.get(i);
            s.id = "k" + i;
            List<String> problems = ConstraintSpecs.validate(s, nodes.keySet(), edges.keySet());
            assertEquals(s.type + ": " + problems, 0, problems.size());
            s.priority = ps[i % ps.length];
            s.enabled = i % 3 != 2;
            Constraint c = ConstraintSpecs.create(s, binding);
            if (s.kind() == ConstraintKind.STAR) {
                c.setAutomatic(true);
                group.add(c);
            }
            if (i == 5) c.setRank(2);
            made.add(c);
        }
        return made;
    }

    private static ConstraintSpec spec(ConstraintKind k) {
        return new ConstraintSpec("x", k);
    }

    private static List<String> l(String... ids) {
        return Arrays.asList(ids);
    }

    /** What must come back: the constraint on its nodes (by label), its values, priority, switch, source, rank, group, origin. */
    static List<String> signatures(List<Constraint> cs) {
        List<String> l = new ArrayList<>();
        ConstraintSpecs.Naming byLabel = new ConstraintSpecs.Naming() {
            @Override public String node(Node n) { return "L:" + AttributeHelper.getLabel(n, "?"); }
            @Override public String edge(Edge e) { return "E:" + edgeName(e); }
            @Override public double[] toLocal(double x, double y) { return new double[]{x, y}; }
        };
        for (Constraint c : cs) {
            ConstraintSpec s = ConstraintSpecs.toSpec(c, byLabel);
            ConstraintOrigin o = c.getOrigin();
            ConstraintGroup g = c.getGroup();
            l.add(ConstraintSpecs.canonical(s, id -> id) + "|" + c.getPriority() + "|" + c.isSwitchedOnItself() + "|" + c.isAutomatic()
                    + "|" + c.getRank() + "|" + (g == null ? "-" : g.kind + ":" + g.label + ":" + g.step)
                    + "|" + (o == null ? "-" : o.getTemplateId() + "@" + o.getTemplateRevision() + ":" + o.pathText() + ":" + o.getSpecId()));
        }
        Collections.sort(l);
        return l;
    }

    static List<ConstraintKind> orderedKinds(List<Constraint> cs) {
        List<ConstraintKind> l = new ArrayList<>();
        for (Constraint c : cs) l.add(c.getKind());
        return l;
    }

    static Map<String, double[]> positions(Graph g) {
        Map<String, double[]> m = new TreeMap<>();
        for (Node n : g.getNodes()) m.put(AttributeHelper.getLabel(n, "?"), new double[]{AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)});
        return m;
    }

    static Map<String, String> stableIds(Graph g) {
        Map<String, String> m = new TreeMap<>();
        for (Node n : g.getNodes()) {
            String id = GraphConstraints.nodeId(n, false);
            if (id != null) m.put(AttributeHelper.getLabel(n, "?"), id);
        }
        return m;
    }

    static Graph throughGml(Graph g) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new GMLWriter().write(bo, g);
        return new GMLReader().read(new ByteArrayInputStream(bo.toByteArray()));
    }

    static Graph throughGraphML(Graph g) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new GraphMLWriter().write(bo, g);
        Graph g2 = new AdjListGraph();
        new GraphMLReader().read(new ByteArrayInputStream(bo.toByteArray()), g2);
        return g2;
    }

    static Graph glycolysis() throws Exception {
        Sbgn sbgn = SbgnUtil.readFromFile(new File("Files/glycolysis.sbgn"));
        Graph g = new AdjListGraph();
        SBGNMLReader.createGraph(sbgn, g);
        return g;
    }

    static TemplateDef example(String id) {
        for (TemplateDef x : TemplateExamples.all()) if (x.id.equals(id)) return x;
        throw new AssertionError("example " + id + " exists");
    }
}
