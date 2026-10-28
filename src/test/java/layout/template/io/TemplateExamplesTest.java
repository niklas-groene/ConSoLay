package layout.template.io;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConstraintConflicts;
import constraints.spec.ConstraintSpec;
import layout.template.apply.ConflictPreview;
import layout.template.apply.InstancePlan;
import layout.template.apply.TemplateApplier;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.LayoutRelation;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;
import layout.template.search.GraphSnapshot;
import layout.template.search.Match;
import layout.template.search.MatchCheck;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;

import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sbgn.SbgnUtil;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNHelper;
import org.sbgned.SBGNToolsTab;
import org.sbgned.sbgnml.SBGNMLReader;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The prepared building blocks ({@link TemplateExamples}) pass the validator, and their matches on the glycolysis map
 * ({@code Files/glycolysis.sbgn}, read with the SBGN-ED reader VANTED uses) are what the paper states: the catalysed
 * reaction matches 31 times at 9 of the 10 processes, two reactions in series 23 times. Run with {@code ant test}.
 */
public class TemplateExamplesTest {

    private static Graph glycolysis;

    @BeforeClass
    public static void load() throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        new SBGNToolsTab();   // the SBGN-ED statics the reader needs; opens no window
        Sbgn sbgn = SbgnUtil.readFromFile(new File("Files/glycolysis.sbgn"));
        glycolysis = new AdjListGraph();
        SBGNMLReader.createGraph(sbgn, glycolysis);
    }

    @Test
    public void everyExampleValidates() {
        for (TemplateDef t : TemplateExamples.all()) {
            List<TemplateValidator.Problem> ps = TemplateValidator.validate(t, TemplateSource.embeddedIn(t));
            for (TemplateValidator.Problem p : ps) {
                assertTrue(t.name + ": " + p, p.severity == TemplateValidator.Severity.WARNING);
            }
        }
    }

    @Test
    public void glycolysisAsVantedReadsIt() {
        assertEquals(44, glycolysis.getNumberOfNodes());
        assertEquals(44, glycolysis.getNumberOfEdges());
        Map<String, Integer> roles = new LinkedHashMap<>();
        for (Node n : glycolysis.getNodes()) roles.merge(SBGNHelper.getSBGNRole(n).toUpperCase(Locale.ROOT), 1, Integer::sum);
        assertEquals(10, (int) roles.get("PROCESS"));
        assertEquals(10, (int) roles.get("MACROMOLECULE"));
        assertEquals(24, (int) roles.get("SIMPLECHEMICAL"));
    }

    @Test
    public void catalysedReactionMatches31TimesAt9Processes() throws Exception {
        List<Match> ms = matches("example:catalysed-reaction");
        assertEquals(31, ms.size());
        Set<Node> processes = new HashSet<>();
        for (Match m : ms) {
            for (Node n : m.nodes()) if (SBGNHelper.getSBGNRole(n).toUpperCase(Locale.ROOT).equals("PROCESS")) processes.add(n);
        }
        assertEquals(9, processes.size());
    }

    @Test
    public void twoReactionsInSeriesMatch23Times() throws Exception {
        assertEquals(23, matches("example:reaction-series").size());
    }

    @Test
    public void reactionWithCofactorsMatches() throws Exception {
        List<Match> ms = matches("example:reaction-cofactors");
        assertTrue("expected matches on glycolysis, got " + ms.size(), ms.size() > 0);
    }

    @Test
    public void pathwayChainMatches() throws Exception {
        List<Match> ms = matches("example:pathway-chain");
        assertTrue("expected matches on glycolysis, got " + ms.size(), ms.size() > 0);
    }

    /** Every assignment of the pathway chain makes constraints that hold together: the lower series lies below. */
    @Test
    public void pathwayChainHoldsAtEveryMatch() throws Exception {
        List<Match> ms = matches("example:pathway-chain");
        ConstraintConflicts.Scope scope = CBLayoutAlgorithm.userScope(glycolysis, false, false);
        for (Match m : ms) {
            for (Match.Variant v : m.variants()) {
                TemplateApplier.Request q = new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title());
                MatchCheck c = ConflictPreview.check(glycolysis, Collections.emptyList(), Collections.singletonList(q),
                        scope, 0);
                assertEquals(m.title() + ": " + c.details, MatchCheck.Status.COMPATIBLE, c.status);
            }
        }
    }

    /**
     * Two series of reactions joined at a shared node: the shared node lies on neither side of a relation's dividing
     * line, and a relation that the joined rows contradict (one below the other) is reported; side by side they hold.
     */
    @Test
    public void relationAcrossSharedNodeIsChecked() {
        TemplateDef reaction = TemplateExamples.reaction(), series = TemplateExamples.reactionSeries(reaction);
        TemplateDef t = TemplateDef.create("Chain", TemplateDef.Kind.COMPOSITE);
        t.components.add(new ComponentUse("c1", "Upper", series.ref()));
        t.components.add(new ComponentUse("c2", "Lower", series.ref()));
        StructureLink l = new StructureLink("l1", "c1", "p2", "c2", "p1");
        l.type = StructureLink.Type.SAME_NODE;
        t.links.add(l);
        LayoutRelation r = new LayoutRelation("r1", LayoutRelation.Type.COLUMN);
        r.components.add("c1");
        r.components.add("c2");
        t.relations.add(r);
        t.dependencies.put(series.id + "@" + series.revision, series);
        t.dependencies.put(reaction.id + "@" + reaction.revision, reaction);
        TemplateSource source = TemplateSource.embeddedIn(t);

        List<ConstraintSpec> specs = InstancePlan.relationSpecs(t, r, source);
        assertEquals(1, specs.size());
        assertEquals(5, specs.get(0).role("before").size());
        assertFalse(specs.get(0).role("before").contains("c1/c2/n3"));
        assertFalse(specs.get(0).role("after").contains("c2/c1/n1"));
        List<String> w = warnings(t, source, "r1");
        assertEquals(w.toString(), 1, w.size());
        assertTrue(w.get(0), w.get(0).contains("cannot hold"));

        r.type = LayoutRelation.Type.LEFT_OF;
        assertTrue(warnings(t, source, "r1").isEmpty());
    }

    private static List<String> warnings(TemplateDef t, TemplateSource source, String elementId) {
        List<String> l = new ArrayList<>();
        for (TemplateValidator.Problem p : TemplateValidator.validate(t, source)) {
            assertEquals(p.toString(), TemplateValidator.Severity.WARNING, p.severity);
            if (elementId.equals(p.elementId)) l.add(p.message);
        }
        return l;
    }

    /** One search of the example {@code id} on the glycolysis map, with the semantics (role conditions) on. */
    static List<Match> matches(String id) throws Exception {
        TemplateDef t = null;
        for (TemplateDef x : TemplateExamples.all()) if (x.id.equals(id)) t = x;
        assertTrue("example " + id + " exists", t != null);
        TemplateSource source = TemplateSource.embeddedIn(t);
        Set<String> nodePaths = new HashSet<>(), edgePaths = new HashSet<>();
        for (PatternNode n : Templates.flatNodes(t, source).values()) for (Condition c : n.conditions) nodePaths.add(c.attribute);
        for (PatternEdge e : Templates.flatEdges(t, source).values()) for (Condition c : e.conditions) edgePaths.add(c.attribute);
        nodePaths.add(Condition.SBGN_ROLE);
        edgePaths.add(Condition.SBGN_ROLE);
        GraphSnapshot snap = GraphSnapshot.capture(glycolysis, null, nodePaths, edgePaths, 1);
        List<SearchTask> tasks = new ArrayList<>();
        tasks.add(new SearchTask(t, source, null, null));
        SearchRun run = new SearchRun(snap, tasks, new SearchRun.Options(true, false, 100000));
        run.runNow(new SearchRun.Listener() {
            @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) { }
            @Override public void finished(SearchRun r) { }
        });
        SwingUtilities.invokeAndWait(() -> { });   // the matches are delivered in portions on the event thread
        assertEquals(SearchRun.State.COMPLETE, run.state());
        return run.matches();
    }
}
