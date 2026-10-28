package adaptagrams_bridge;

import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.Deactivation;
import layout.steps.StepTemplates;
import layout.template.apply.TemplateApplier;
import layout.template.model.RuleSystem;
import layout.template.search.Match;
import layout.template.search.SearchRun;

import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugins.ios.importers.gml.GMLReader;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The solve of a drag step ({@link CBLayoutAlgorithm#executeUserConstraintsAround}: the constraint components of the
 * dragged node only) gives the same drawing and the same decisions as the solve of the whole graph from the same
 * positions, on the transcription-network example with its rule set applied. Needs the natives; run with
 * {@code ant test}.
 */
public class DragSolveTest {

    private static Graph graph;

    @BeforeClass
    public static void load() throws Exception {
        System.setProperty("java.awt.headless", "true");
        NativeLoader.ensureLoaded();
        try (InputStream in = new FileInputStream(new File("supplement/example_trn/ecoli_sugar_regulation.gml"))) {
            graph = new GMLReader().read(in);
        }
        ConstraintStore.constraints = new Constraints();
        RuleSystem rs = StepTemplates.loadRules(new File("supplement/example_trn/library/Transcription network.cblrules.json"));
        SearchRun run = StepTemplates.search(graph, null, StepTemplates.tasks(rs), true, false);
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        TemplateApplier.apply(graph, reqs, ConstraintStore.constraints, false, null);
        assertEquals(261, ConstraintStore.constraints.getAllConstraints().size());
        CBLayoutAlgorithm.executeUserConstraints(graph, false, false);   // the state before a drag: solved
    }

    @Test
    public void dragSolveEqualsFullSolve() {
        assertTrue("needs -Dcblayout.dragSolve=component (the default)", CBLayoutAlgorithm.DRAG_SOLVE_COMPONENT);
        List<Node> nodes = new ArrayList<>(graph.getNodes());
        nodes.sort((a, b) -> Long.compare(a.getID(), b.getID()));
        for (int k = 0; k < 6; k++) {
            Node n = nodes.get((k * 7) % nodes.size());
            Map<Node, double[]> start = TemplateApplier.positions(graph);
            // the drag step: the node moved, then the solve around it
            AttributeHelper.setPosition(n, AttributeHelper.getPositionX(n) + 25, AttributeHelper.getPositionY(n) - 10);
            Map<Node, double[]> dragged = TemplateApplier.positions(graph);
            CBLayoutAlgorithm.executeUserConstraintsAround(graph, Collections.singletonList(n), false, false);
            Map<Node, double[]> around = TemplateApplier.positions(graph);
            Map<Constraint, Deactivation> decidedAround = decisions();
            // the same from the same positions, over the whole graph
            restore(dragged);
            CBLayoutAlgorithm.executeUserConstraints(graph, false, false);
            Map<Node, double[]> full = TemplateApplier.positions(graph);
            Map<Constraint, Deactivation> decidedFull = decisions();
            double max = 0;
            for (Node m : graph.getNodes()) {
                max = Math.max(max, Math.abs(around.get(m)[0] - full.get(m)[0]));
                max = Math.max(max, Math.abs(around.get(m)[1] - full.get(m)[1]));
            }
            assertTrue("drag " + k + ": the solve around the node differs from the full solve by " + max + " px", max <= 1e-6);
            assertEquals("drag " + k + ": the decisions differ", decidedFull.keySet(), decidedAround.keySet());
            restore(start);
            CBLayoutAlgorithm.executeUserConstraints(graph, false, false);
        }
    }

    private static Map<Constraint, Deactivation> decisions() {
        Map<Constraint, Deactivation> m = new IdentityHashMap<>();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) if (c.getDeactivation() != null) m.put(c, c.getDeactivation());
        return m;
    }

    private static void restore(Map<Node, double[]> at) {
        for (Map.Entry<Node, double[]> e : at.entrySet()) AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]);
    }
}
