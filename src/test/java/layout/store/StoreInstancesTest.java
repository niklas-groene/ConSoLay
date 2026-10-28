package layout.store;

import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.ConstraintOrigin;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.PageBoundaryConstraintGraph;
import constraints.SeparationConstraintGraph;
import constraints.StarPattern;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.steps.StepTemplates;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;
import layout.template.search.Match;
import layout.template.search.SearchRun;

import org.adaptagrams.NativeLoader;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sbgned.SBGNToolsTab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static constraints.SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What a saved store keeps of template instances that lost an element (their switches and origin), of the age of its
 * constraints, and what it says about values it cannot restore. Run with {@code ant test}.
 */
public class StoreInstancesTest {

    @BeforeClass
    public static void init() throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        NativeLoader.ensureLoaded();
        new SBGNToolsTab();
    }

    @Test
    public void switchedOffInstanceThatLostANodeStaysOff() throws Exception {
        Graph g = StoreRoundTripTest.glycolysis();
        Constraints store = new Constraints();
        ConstraintStore.constraints = store;
        TemplateDef t = StoreRoundTripTest.example("example:catalysed-reaction");
        SearchRun run = StepTemplates.search(g, null, Collections.singletonList(StepTemplates.task(t)), true, false);
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        TemplateApplier.apply(g, reqs, store, false, null);
        TemplateInstance inst = InstanceRegistry.of(g).get(0);
        TemplateApplier.setOn(inst, "", false);
        // a node of the instance that its alignment does not use
        Node gone = null;
        for (Node n : inst.nodes.values()) {
            boolean aligned = false;
            for (Constraint c : inst.constraints()) {
                aligned |= c.getKind() == ConstraintKind.ALIGN_CENTER && c.getNodes().contains(n);
            }
            if (!aligned) gone = n;
        }
        assertNotNull(gone);
        String before = GraphConstraints.json(g, store);   // the attribute as saved before the node went
        store.removeNode(gone);
        g.deleteNode(gone);
        String after = GraphConstraints.json(g, store);
        TemplateSource src = new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) { return ref.id.equals(t.id) ? t : null; }
            @Override public TemplateDef latest(String id) { return id.equals(t.id) ? t : null; }
        };
        for (String json : Arrays.asList(before, after)) {
            Graph g2 = StoreRoundTripTest.throughGml(g);
            Constraints store2 = new Constraints();
            GraphConstraints.read(json, g2, store2, src);
            int ofInstance = 0;
            for (Constraint c : store2.getAllConstraints()) {
                ConstraintOrigin o = c.getOrigin();
                assertNotNull("a template constraint keeps its origin", o);
                if (!inst.label().equals(o.getInstanceLabel())) continue;
                ofInstance++;
                assertFalse("a constraint of the switched-off instance stays off", c.isEnabled());
            }
            assertTrue(ofInstance > 0);
        }
    }

    @Test
    public void readingKeepsTheAgeOfTheConstraints() {
        Graph g = StoreRoundTripTest.smallGraph();
        Map<String, Node> n = StoreRoundTripTest.byLabel(g);
        List<Node> ab = new ArrayList<>(Arrays.asList(n.get("a"), n.get("b")));
        SeparationConstraintGraph older = new SeparationConstraintGraph(ab, HORIZONTAL, true, 100);
        SeparationConstraintGraph younger = new SeparationConstraintGraph(ab, HORIZONTAL, true, 200);
        Constraints store = new Constraints();
        store.addConstraint(younger);   // as after an undo that put the older one back at the end
        store.addConstraint(older);
        Constraints store2 = new Constraints();
        GraphConstraints.read(GraphConstraints.json(g, store), g, store2, null);
        Map<Double, Long> ids = new LinkedHashMap<>();
        for (Constraint c : store2.getAllConstraints()) {
            ids.put(((SeparationConstraintGraph) c).getDistance(), c.getId());
        }
        assertTrue("the older constraint is older after reading", ids.get(100.0) < ids.get(200.0));
    }

    @Test
    public void invalidSavedValuesAreReported() {
        Graph g = StoreRoundTripTest.smallGraph();
        Map<String, Node> n = StoreRoundTripTest.byLabel(g);
        Constraints store = new Constraints();
        store.addConstraint(new StarPattern(n.get("i"), Arrays.asList(n.get("a"), n.get("b"), n.get("c")), 0, 0));
        double[] inverted = {100, 100, 0, 0};
        store.addConstraint(new PageBoundaryConstraintGraph(Collections.singletonList(n.get("d")), inverted, 0));
        Constraints store2 = new Constraints();
        StoreCodec.Report r = GraphConstraints.read(GraphConstraints.json(g, store), g, store2, null);
        assertEquals(2, r.skipped);
        assertEquals(0, store2.getAllConstraints().size());
        assertTrue(r.problems.toString(), r.problems.toString().contains("radius"));
        assertTrue(r.problems.toString(), r.problems.toString().contains("inverted"));
    }

    @Test
    public void canonicalTextOfAMalformedPosition() {
        Map<String, double[]> positions = new LinkedHashMap<>();
        positions.put("n1", new double[]{5});
        ConstraintSpec s = new ConstraintSpec("x", ConstraintKind.PIN).role("nodes", Collections.singletonList("n1"))
                .param("positions", positions);
        assertTrue(ConstraintSpecs.canonical(s, id -> id).contains("n1:?"));
    }
}
