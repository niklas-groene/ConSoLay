package gui.template;

import layout.template.apply.InstanceRegistry;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.search.GraphSnapshot;
import layout.template.search.Match;
import layout.template.search.MatchCheck;
import layout.template.search.MatchValidator;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;

import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * How the Templates page keeps its matches up to date (without a window): after graph changes and after changes of the
 * constraint store. Run with {@code ant test}.
 */
public class TemplateWorkbenchTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** An edge between match nodes that is not part of the match may be deleted again: the match stays usable. */
    @Test
    public void deletingAnotherEdgeOnlyOutdatesTheMatch() throws Exception {
        Graph g = new AdjListGraph();
        Node a = g.addNode(), b = g.addNode();
        Edge ab = g.addEdge(a, b, true);
        Match m = searchAndWatch(g);
        SwingUtilities.invokeAndWait(() -> {
            Edge extra = g.addEdge(b, a, true);
            assertEquals(Match.State.STALE, m.state);
            g.deleteEdge(extra);
            assertEquals(Match.State.STALE, m.state);
            assertNull(MatchValidator.revalidate(m, g));
            g.deleteEdge(ab);
            assertEquals(Match.State.INVALID, m.state);
        });
    }

    /** A change of the constraint store makes the conflict checks of the matches be made again. */
    @Test
    public void storeChangesDropTheConflictChecks() throws Exception {
        Graph g = new AdjListGraph();
        g.addEdge(g.addNode(), g.addNode(), true);
        Match m = searchAndWatch(g);
        MatchCheck made = new MatchCheck(MatchCheck.Status.CONFLICT, "Conflict.", Collections.emptyList(), 0);
        SwingUtilities.invokeAndWait(() -> {
            m.check = made;
            TemplatePanel.constraintsChanged();   // what every edit of the Constraints tab and every instance action calls
            assertEquals(MatchCheck.Status.NOT_CHECKED, m.check.status);
            m.check = made;
            InstanceRegistry.fire();   // undo and redo of applications
            assertEquals(MatchCheck.Status.NOT_CHECKED, m.check.status);
        });
    }

    /** Searches a one-edge template on {@code g} and makes it the workbench's run there (which watches the graph). */
    private static Match searchAndWatch(Graph g) throws Exception {
        TemplateDef t = TemplateDef.create("Edge", TemplateDef.Kind.PATTERN);
        t.nodes.add(new PatternNode(t.nextNodeId(), 0, 0));
        t.nodes.add(new PatternNode(t.nextNodeId(), 50, 0));
        t.edges.add(new PatternEdge(t.nextEdgeId(), "n1", "n2", true));
        GraphSnapshot snap = GraphSnapshot.capture(g, null, new HashSet<>(), new HashSet<>(), 0);
        SearchTask task = new SearchTask(t, TemplateSource.embeddedIn(t), null, null);
        SearchRun run = new SearchRun(snap, Collections.singletonList(task), new SearchRun.Options(false, false, 1000));
        SwingUtilities.invokeAndWait(() -> {
            run.runNow(new SearchRun.Listener() {
                @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) { }
                @Override public void finished(SearchRun r) { }
            });
            TemplateWorkbench.get().state(g).run = run;
        });
        assertEquals(1, run.matches().size());
        return run.matches().get(0);
    }
}
