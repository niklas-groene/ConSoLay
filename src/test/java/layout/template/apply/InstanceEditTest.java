package layout.template.apply;

import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import layout.steps.StepTemplates;
import layout.template.io.TemplateExamples;
import layout.template.model.TemplateDef;
import layout.template.search.Match;
import layout.template.search.SearchRun;

import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Undo and redo of a template application (no GUI, no solver). Run with {@code ant test}. */
public class InstanceEditTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** Redo brings back what undo took away, not a constraint the user deleted after applying. */
    @Test
    public void redoKeepsConstraintsDeletedAfterApplying() {
        Graph g = new AdjListGraph();
        Node a = g.addNode(), b = g.addNode();
        AttributeHelper.setPosition(a, 100, 100);
        AttributeHelper.setPosition(b, 200, 150);
        g.addEdge(a, b, true);
        TemplateDef pair = null;
        for (TemplateDef t : TemplateExamples.all()) if (t.id.equals("example:pair")) pair = t;
        SearchRun run = StepTemplates.search(g, null, Collections.singletonList(StepTemplates.task(pair)), false, false);
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        Constraints store = ConstraintStore.of(g);
        int before = store.getAllConstraints().size();
        TemplateApplier.Result r = TemplateApplier.apply(g, reqs, store, false, null);
        InstanceEdit edit = InstanceEdit.applied("Apply template", g, r, null);
        assertEquals(before + 2, store.getAllConstraints().size());
        Constraint deleted = r.made.get(0).constraints().get(0);
        store.removeConstraint(deleted);

        edit.undo();
        assertEquals(before, store.getAllConstraints().size());
        edit.redo();
        assertEquals(before + 1, store.getAllConstraints().size());
        assertFalse(store.getAllConstraints().contains(deleted));
    }
}
