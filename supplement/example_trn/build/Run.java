import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.Constraints;
import constraints.Deactivation;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;
import layout.template.io.TemplateFiles;
import layout.template.model.*;
import layout.template.search.*;
import org.AttributeHelper;
import org.graffiti.graph.*;
import org.graffiti.plugins.ios.exporters.gml.GMLWriter;
import org.graffiti.plugins.ios.importers.gml.GMLReader;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Headless check of the transcription-network example: builds the graph from TSV, writes it with VANTED's GML
 * writer, reads it back, loads the rule set, searches, applies all matches and runs the conflict decision.
 * Usage: Run nodes.tsv edges.tsv rules.cblrules.json out.gml
 */
public class Run {

    public static void main(String[] a) throws Exception {
        Graph g0 = build(new File(a[0]), new File(a[1]));
        try (OutputStream o = new FileOutputStream(a[3])) {
            new GMLWriter().write(o, g0);
        }
        Graph g;
        try (InputStream in = new FileInputStream(a[3])) {
            g = new GMLReader().read(in);
        }
        System.out.println("GML read back: " + g.getNumberOfNodes() + " nodes, " + g.getNumberOfEdges() + " edges");

        RuleSystem rs = TemplateFiles.loadRules(new File(a[2]));
        TemplateSource rsSource = TemplateSource.of(rs);
        List<SearchTask> tasks = new ArrayList<>();
        Set<String> np = new HashSet<>(), ep = new HashSet<>();
        for (RuleSystem.Rule r : rs.rules) {
            TemplateDef t = rsSource.get(r.template);
            TemplateSource s = TemplateSource.chain(rsSource, TemplateSource.embeddedIn(t));
            List<TemplateValidator.Problem> ps = TemplateValidator.validate(t, s);
            System.out.println("Rule '" + r.name + "' -> " + t.name + " rev " + t.revision + ", priority " + r.priority
                    + ", problems: " + ps.size() + (ps.isEmpty() ? "" : " " + ps));
            tasks.add(new SearchTask(t, s, r.name, r.priority));
            for (PatternNode n : Templates.flatNodes(t, s).values()) for (Condition c : n.conditions) np.add(c.attribute);
            for (PatternEdge e : Templates.flatEdges(t, s).values()) for (Condition c : e.conditions) ep.add(c.attribute);
        }
        np.add("trn.regulator");
        np.add("label");
        ep.add("trn.effect");
        GraphSnapshot snap = GraphSnapshot.capture(g, null, np, ep, 1);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < snap.nodeCount(); i++) idx.put(snap.labels[i], i);
        for (String l : new String[]{"crp", "araC", "araA"}) {
            Integer i = idx.get(l);
            System.out.println("  node " + l + ": trn.regulator='" + (i == null ? "?" : snap.nodeValue("trn.regulator", i)) + "'");
        }
        System.out.println("  edge 0 trn.effect='" + snap.edgeValue("trn.effect", 0) + "'");

        SearchRun run = new SearchRun(snap, tasks, new SearchRun.Options(true, false, 100000));
        run.runNow(new SearchRun.Listener() {
            @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) { }
            @Override public void finished(SearchRun r) { }
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> { });  // matches are delivered in portions on the event thread
        System.out.println("Search: state " + run.state() + ", " + run.matches().size() + " matches, " + run.durationMillis()
                + " ms, " + run.assignments() + " partial assignments" + (run.limitMessage() == null ? "" : ", limit: " + run.limitMessage()));
        Map<String, Integer> perRule = new LinkedHashMap<>(), variants = new LinkedHashMap<>();
        for (Match m : run.matches()) {
            perRule.merge(m.task.ruleName, 1, Integer::sum);
            variants.merge(m.task.ruleName, m.variants().size(), Integer::sum);
        }
        System.out.println("  matches per rule: " + perRule + "; variants per rule: " + variants);

        // Apply every match (first variant) as one batch, without placing nodes and without a solve.
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : run.matches()) {
            Match.Variant v = m.variant();
            reqs.add(new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title()));
        }
        Constraints store = new Constraints();
        TemplateApplier.Result res = TemplateApplier.apply(g, reqs, store, false, null);
        Map<Constraint, String> ruleOf = new IdentityHashMap<>();
        Map<String, Integer> made = new LinkedHashMap<>();
        for (TemplateInstance inst : res.made) {
            for (Constraint c : inst.constraints()) {
                ruleOf.put(c, inst.ruleName);
                made.merge(inst.ruleName, 1, Integer::sum);
            }
        }
        System.out.println("Apply: " + res.made.size() + " instances, " + res.constraints + " constraints, skipped "
                + res.skipped.size() + "; constraints per rule: " + made);

        List<Node> nodes = new ArrayList<>(g.getNodes());
        ConstraintConflicts.Scope scope = new ConstraintConflicts.Scope(nodes, g, Double.NaN, true, false);
        long t0 = System.nanoTime();
        List<Constraint> off = ConstraintConflicts.resolve(store.getAllConstraints(), scope);
        long t1 = System.nanoTime();
        Map<String, Integer> lost = new LinkedHashMap<>();
        Map<String, Integer> against = new LinkedHashMap<>();
        for (Constraint c : off) {
            String r = ruleOf.get(c);
            lost.merge(r, 1, Integer::sum);
            Deactivation d = c.getDeactivation();
            Set<String> pr = new TreeSet<>();
            if (d != null) for (Constraint p : d.partners) pr.add(ruleOf.get(p));
            against.merge(r + " vs " + pr, 1, Integer::sum);
        }
        System.out.println("Decide: " + off.size() + " of " + store.getAllConstraints().size() + " not applied ("
                + (t1 - t0) / 1_000_000 + " ms); per rule: " + lost);
        System.out.println("  lost against: " + against);
        int shown = 0;
        for (Constraint c : off) {
            if (shown++ >= 40) break;
            Deactivation d = c.getDeactivation();
            System.out.println("   - [" + ruleOf.get(c) + "] " + names(c) + " : " + (d == null ? "" : d.shortReason()));
        }

        // Optional: the solve of the loop (stress majorization with the applied constraints, then overlap removal).
        if (a.length > 4) {
            writePositions(g, new File(a[4] + "_before.tsv"));
            org.adaptagrams.NativeLoader.ensureLoaded();
            long s0 = System.nanoTime();
            Map<Constraint, String> unmet = adaptagrams_bridge.CBLayoutAlgorithm.solveUserConstraints(g, store.getAllConstraints(), a.length > 5 && a[5].equals("fd"), true, false);
            long s1 = System.nanoTime();
            System.out.println("Solve (" + (a.length > 5 && a[5].equals("fd") ? "with" : "without") + " force-directed run, overlap removal on): " + (s1 - s0) / 1_000_000 + " ms, not satisfied after the solve: " + unmet.size());
            int k = 0;
            for (Map.Entry<Constraint, String> e : unmet.entrySet()) {
                if (k++ >= 6) break;
                System.out.println("   ~ [" + ruleOf.get(e.getKey()) + "] " + names(e.getKey()) + " : " + e.getValue());
            }
            writePositions(g, new File(a[4] + "_after.tsv"));
            try (OutputStream o = new FileOutputStream(a[4] + "_after.gml")) {
                new GMLWriter().write(o, g);
            }
        }
    }

    static void writePositions(Graph g, File f) throws IOException {
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            w.println("label\tx\ty\tw\th");
            for (Node n : g.getNodes()) {
                org.graffiti.attributes.Attribute s = null;
                w.println(AttributeHelper.getLabel(n, "?") + "\t" + AttributeHelper.getPositionX(n) + "\t"
                        + AttributeHelper.getPositionY(n) + "\t" + AttributeHelper.getWidth(n) + "\t" + AttributeHelper.getHeight(n));
            }
        }
    }

    static String names(Constraint c) {
        List<String> l = new ArrayList<>();
        for (Node n : c.getNodes()) l.add(AttributeHelper.getLabel(n, "?"));
        return c.getKind() + " " + l;
    }

    static Graph build(File nodesTsv, File edgesTsv) throws IOException {
        Graph g = new AdjListGraph();
        Map<String, Node> byId = new HashMap<>();
        List<String> nl = Files.readAllLines(nodesTsv.toPath(), StandardCharsets.UTF_8);
        for (String line : nl.subList(1, nl.size())) {
            String[] p = line.split("\t", -1);
            String id = p[0], label = p[1], cls = p[2];
            double x = Double.parseDouble(p[3]), y = Double.parseDouble(p[4]);
            double w = Math.max(44, 9 * label.length() + 14);
            Color fill = "global".equals(cls) ? new Color(244, 162, 97) : "local".equals(cls) ? new Color(142, 202, 230) : Color.WHITE;
            Node n = de.ipk_gatersleben.ag_nw.graffiti.GraphHelper.addNodeToGraph(g, x, y, 1, w, 24, Color.BLACK, fill);
            AttributeHelper.setLabel(n, label);
            if (!cls.isEmpty()) AttributeHelper.setAttribute(n, "trn", "regulator", cls);
            byId.put(id, n);
        }
        List<String> el = Files.readAllLines(edgesTsv.toPath(), StandardCharsets.UTF_8);
        for (String line : el.subList(1, el.size())) {
            String[] p = line.split("\t", -1);
            Node s = byId.get(p[0]), t = byId.get(p[1]);
            String eff = p[2];
            Color c = "activator".equals(eff) ? new Color(46, 125, 50) : "repressor".equals(eff) ? new Color(198, 40, 40)
                    : "dual".equals(eff) ? new Color(106, 27, 154) : Color.GRAY;
            Edge e = g.addEdge(s, t, true, AttributeHelper.getDefaultGraphicsAttributeForEdge(c, c, true));
            if ("repressor".equals(eff)) AttributeHelper.setArrowhead(e, "org.graffiti.plugins.views.defaults.InhibitorArrowShape");
            AttributeHelper.setAttribute(e, "trn", "effect", eff);
        }
        return g;
    }
}
