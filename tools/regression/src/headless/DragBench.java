package headless;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import layout.sbgn.ConstraintSBGNLayout;
import layout.steps.StepTemplates;
import layout.template.apply.TemplateApplier;
import layout.template.io.TemplateExamples;
import layout.template.model.RuleSystem;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.search.Match;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;

import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugins.ios.importers.gml.GMLReader;
import org.sbgn.Language;
import org.sbgn.bindings.Sbgn;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures the user solve as a drag step runs it ({@code CBLayoutAlgorithm.executeUserConstraints} after every
 * {@code mouseDragged} of VANTED's move tool): a node is moved by a few px, the solve runs, the phases of
 * {@code CBLayoutAlgorithm.PHASES} are timed; the median and the 95th percentile per drag (over all steps of all dragged
 * nodes) are printed, for the force-directed run and the overlap removal off and on. Headless, so the GUI refresh after
 * a solve (list, conflicts, overlay) is not in the numbers; the JIT is on, as in VANTED after warm-up.
 *
 * <pre>
 *   DragBench --scenario trn|pd|big [--steps 30] [--nodes 5] [--warmup 10] [--only fd0o0,fd0o1,fd1o0,fd1o1] [--around]
 *     --around: the solve a drag step runs (executeUserConstraintsAround: the components of the dragged node)
 *     --least: drag the nodes with the fewest constraints instead of the most constrained ones
 *     trn: the transcription network ecoli_sugar_regulation.gml with its rule set "Transcription network.cblrules.json"
 *     pd:  Files/Carbohydrate Metabolism.Glycolysis, Gluconeogenesis.sbgn after the PD algorithm (its automatic constraints)
 *     big: the largest map in Files (central_plant_metabolism.sbgn) with the reaction rule set of the examples applied
 * </pre>
 * Paths: --trn-gml, --trn-rules (defaults in ../PacificVis2027/example_trn), --files (default Files).
 */
public final class DragBench {

    private DragBench() { }

    public static void main(String[] argv) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        String scenario = "trn";
        int steps = 30, nodes = 5, warmup = 10;
        boolean around = false, least = false;
        List<String> only = Arrays.asList("fd0o0", "fd0o1", "fd1o0", "fd1o1");
        File trnGml = new File("../PacificVis2027/example_trn/ecoli_sugar_regulation.gml");
        File trnRules = new File("../PacificVis2027/example_trn/library/Transcription network.cblrules.json");
        File files = new File("Files");
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--scenario": scenario = argv[++i]; break;
                case "--steps": steps = Integer.parseInt(argv[++i]); break;
                case "--nodes": nodes = Integer.parseInt(argv[++i]); break;
                case "--warmup": warmup = Integer.parseInt(argv[++i]); break;
                case "--only": only = Arrays.asList(argv[++i].split(",")); break;
                case "--around": around = true; break;
                case "--least": least = true; break;
                case "--trn-gml": trnGml = new File(argv[++i]); break;
                case "--trn-rules": trnRules = new File(argv[++i]); break;
                case "--files": files = new File(argv[++i]); break;
                default: throw new IllegalArgumentException("Unknown argument " + argv[i]);
            }
        }
        PrintStream out = System.out;
        NativeLoader.ensureLoaded();
        new org.sbgned.SBGNToolsTab();
        Graph g;
        switch (scenario) {
            case "trn": g = trn(trnGml, trnRules, out); break;
            case "pd": g = pd(new File(files, "Carbohydrate Metabolism.Glycolysis, Gluconeogenesis.sbgn"), out); break;
            case "big": g = big(new File(files, "central_plant_metabolism.sbgn"), out); break;
            default: throw new IllegalArgumentException("scenario " + scenario);
        }
        int active = 0;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) if (c.isActive()) active++;
        out.println("SCENARIO " + scenario + " nodes=" + g.getNumberOfNodes() + " edges=" + g.getNumberOfEdges()
                + " constraints=" + ConstraintStore.constraints.getAllConstraints().size() + " active=" + active);
        List<Node> dragged = least ? leastConstrained(g, nodes) : mostConstrained(g, nodes);
        Map<Node, double[]> start = TemplateApplier.positions(g);
        for (String mode : only) {
            boolean fd = mode.startsWith("fd1"), ov = mode.endsWith("o1");
            restore(g, start);
            for (int i = 0; i < warmup; i++) CBLayoutAlgorithm.executeUserConstraints(g, fd, ov);
            restore(g, start);
            List<Long> totals = new ArrayList<>();
            int underCursor = 0, xKept = 0, yKept = 0;   // steps after which the dragged node is still where the drag put it
            List<List<Long>> perPhase = new ArrayList<>();
            for (int p = 0; p < CBLayoutAlgorithm.PHASES.length; p++) perPhase.add(new ArrayList<>());
            for (Node n : dragged) {
                restore(g, start);
                CBLayoutAlgorithm.executeUserConstraints(g, fd, ov);   // the state before the drag: solved
                for (int s = 0; s < steps; s++) {
                    AttributeHelper.setPosition(n, AttributeHelper.getPositionX(n) + 3, AttributeHelper.getPositionY(n) + 2);
                    long t = System.nanoTime();
                    double cx = AttributeHelper.getPositionX(n), cy = AttributeHelper.getPositionY(n);
                    if (around) CBLayoutAlgorithm.executeUserConstraintsAround(g, Collections.singletonList(n), fd, ov);
                    else CBLayoutAlgorithm.executeUserConstraints(g, fd, ov);
                    totals.add(System.nanoTime() - t);
                    boolean kx = Math.abs(AttributeHelper.getPositionX(n) - cx) < 1e-6, ky = Math.abs(AttributeHelper.getPositionY(n) - cy) < 1e-6;
                    if (kx) xKept++;
                    if (ky) yKept++;
                    if (kx && ky) underCursor++;
                    for (int p = 0; p < CBLayoutAlgorithm.PHASES.length; p++) perPhase.get(p).add(CBLayoutAlgorithm.phaseNanos[p]);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "MODE %sfd=%s overlaps=%s steps=%d total median=%.2f p95=%.2f ms |", around ? "around " : "", fd ? "on" : "off", ov ? "on" : "off",
                    totals.size(), ms(median(totals)), ms(p95(totals))));
            for (int p = 0; p < CBLayoutAlgorithm.PHASES.length; p++) {
                sb.append(String.format(Locale.ROOT, " %s=%.2f/%.2f", CBLayoutAlgorithm.PHASES[p], ms(median(perPhase.get(p))), ms(p95(perPhase.get(p)))));
            }
            sb.append(" | dragged node under the cursor after ").append(underCursor).append(" of ").append(totals.size()).append(" steps (x kept ")
                    .append(xKept).append(", y kept ").append(yKept).append(")");
            out.println(sb);
        }
        out.println("DONE");
        System.exit(0);
    }

    // ------------------------------------------------------------------ scenarios

    static Graph trn(File gml, File rules, PrintStream out) throws Exception {
        Graph g;
        try (InputStream in = new FileInputStream(gml)) {
            g = new GMLReader().read(in);
        }
        RuleSystem rs = StepTemplates.loadRules(rules);
        applyAll(g, StepTemplates.tasks(rs), out);
        return g;
    }

    static Graph pd(File sbgn, PrintStream out) throws Exception {
        HeadlessVantedRunner.resetStatics();
        Sbgn s = HeadlessVantedRunner.readSbgn(sbgn);
        PortHelper.readInputPorts(s);
        Graph g = new AdjListGraph();
        org.sbgned.sbgnml.SBGNMLReader.createGraph(s, g);
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        ConstraintSBGNLayout layout = new ConstraintSBGNLayout(g, Language.PD);
        long t = System.currentTimeMillis();
        layout.runAll();
        out.println("PD run " + (System.currentTimeMillis() - t) + " ms, " + ConstraintStore.constraints.getAllConstraints().size() + " constraints");
        return g;
    }

    static Graph big(File sbgn, PrintStream out) throws Exception {
        HeadlessVantedRunner.resetStatics();
        Sbgn s = HeadlessVantedRunner.readSbgn(sbgn);
        Graph g = new AdjListGraph();
        org.sbgned.sbgnml.SBGNMLReader.createGraph(s, g);
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        List<SearchTask> tasks = new ArrayList<>();
        for (TemplateDef t : TemplateExamples.all()) {
            if (t.id.equals("example:reaction") || t.id.equals("example:catalysed-reaction") || t.id.equals("example:reaction-series"))
                tasks.add(new SearchTask(t, TemplateSource.embeddedIn(t), t.name, null));
        }
        applyAll(g, tasks, out);
        return g;
    }

    /** Searches and applies every match of {@code tasks} on {@code g} without a solve (the bench solves itself). */
    static void applyAll(Graph g, List<SearchTask> tasks, PrintStream out) throws Exception {
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        long t = System.currentTimeMillis();
        SearchRun run = StepTemplates.search(g, null, tasks, true, false);
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        TemplateApplier.Result r = TemplateApplier.apply(g, reqs, ConstraintStore.constraints, false, null);
        out.println("search " + run.matches().size() + " matches (" + run.durationMillis() + " ms" + (run.limitMessage() == null ? "" : ", " + run.limitMessage())
                + "), applied " + r.made.size() + " instances, " + r.constraints + " constraints, " + (System.currentTimeMillis() - t) + " ms");
    }

    // ------------------------------------------------------------------ helpers

    /** The {@code k} nodes with the most constraints (the nodes a user would drag to see the constraints act). */
    static List<Node> mostConstrained(Graph g, int k) {
        Map<Node, Integer> count = constraintCount();
        List<Node> nodes = new ArrayList<>(g.getNodes());
        nodes.sort((a, b) -> {
            int d = Integer.compare(count.getOrDefault(b, 0), count.getOrDefault(a, 0));
            return d != 0 ? d : Long.compare(a.getID(), b.getID());
        });
        return nodes.subList(0, Math.min(k, nodes.size()));
    }

    /** The {@code k} nodes with the fewest constraints but at least one (a drag that few constraints answer). */
    static List<Node> leastConstrained(Graph g, int k) {
        Map<Node, Integer> count = constraintCount();
        List<Node> nodes = new ArrayList<>();
        for (Node n : g.getNodes()) if (count.containsKey(n)) nodes.add(n);
        nodes.sort((a, b) -> {
            int d = Integer.compare(count.get(a), count.get(b));
            return d != 0 ? d : Long.compare(a.getID(), b.getID());
        });
        return nodes.subList(0, Math.min(k, nodes.size()));
    }

    /** Number of constraints in the store per node. */
    private static Map<Node, Integer> constraintCount() {
        Map<Node, Integer> count = new HashMap<>();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) for (Node n : c.getNodes()) count.merge(n, 1, Integer::sum);
        return count;
    }

    static void restore(Graph g, Map<Node, double[]> at) {
        for (Map.Entry<Node, double[]> e : at.entrySet()) AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]);
    }

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    static long median(List<Long> l) {
        if (l.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    static long p95(List<Long> l) {
        if (l.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * 0.95)));
    }
}
