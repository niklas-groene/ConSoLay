package headless;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import layout.mental_map.ComparisonLayoutRunner;
import layout.mental_map.Snapshot;
import layout.sbgn.ConstraintSBGNLayout;

import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.CoordinateAttribute;
import org.sbgn.Language;
import org.sbgn.bindings.Sbgn;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Regression run of the layout algorithms, outside VANTED (developer tool, not part of the add-on jar).
 *
 * <pre>
 *   Regression --maps DIR --out DIR [--baseline DIR] [--include REGEX] [--exclude REGEX] [--no-mm] [--stepwise]
 * </pre>
 *
 * For every {@code *.sbgn} file of {@code --maps} it runs the SBGN layout of the map's language (PD, ER or AF: the
 * language the map declares, as the add-on's "auto" does) with "Run all steps", and writes
 * <ul>
 *   <li>{@code <map>.coords.txt}: every node (glyph id, label, centre, size) and every edge (ends, bends), sorted, with
 *       the exact double values, and the constraints the steps left in the store;</li>
 *   <li>{@code <map>.sbgn}: the laid-out map as SBGN-ML (the add-on's headless writers, byte-stable).</li>
 * </ul>
 * Then the mental-map layout: for the maps listed in {@link #MENTAL_MAP_MAPS} a reference snapshot is taken, the graph
 * is changed deterministically (some nodes deleted, some added) and the Prototype and its deterministic variants are run;
 * their coordinates go to {@code <map>.mm-<choice>.coords.txt}. The Prototype itself is not reproducible across runs
 * (libcola's final overlap pass), so it is compared with a tolerance.
 *
 * With {@code --baseline} every written file is compared byte for byte with the file of the same name there; the exit
 * code is 1 if anything differs. The summary at the end lists every map with "same", "DIFFERS" (plus the largest
 * coordinate deviation) or "noise" (mental-map Prototype within tolerance).
 */
public final class Regression {

    /** Maps the mental-map check runs on (PD maps of a size the Prototype handles in seconds). */
    static final String[] MENTAL_MAP_MAPS = { "glycolysis", "mapk_cascade", "neuronal_muscle_signalling", "principle_of_the_pcr" };

    /** Mental-map choices compared byte for byte (they end without libcola's non-deterministic overlap pass). */
    static final ComparisonLayoutRunner.Choice[] MM_EXACT = {
            ComparisonLayoutRunner.Choice.PROTOTYPE_NO_OVERLAP, ComparisonLayoutRunner.Choice.FROZEN,
            ComparisonLayoutRunner.Choice.UNCONSTRAINED_FD };

    /** -Dregression.mmTolerance=PX (default 1000): largest deviation of the mental-map Prototype reported as noise. */
    static final double MM_TOLERANCE = Double.parseDouble(System.getProperty("regression.mmTolerance", "1000"));

    private Regression() { }

    public static void main(String[] argv) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        File maps = null, out = null, baseline = null;
        Pattern include = null, exclude = null;
        boolean mm = true, stepwise = false;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--maps": maps = new File(need(argv, ++i, "--maps")); break;
                case "--out": out = new File(need(argv, ++i, "--out")); break;
                case "--baseline": baseline = new File(need(argv, ++i, "--baseline")); break;
                case "--include": include = Pattern.compile(need(argv, ++i, "--include")); break;
                case "--exclude": exclude = Pattern.compile(need(argv, ++i, "--exclude")); break;
                case "--no-mm": mm = false; break;
                case "--stepwise": stepwise = true; break;
                default: throw new IllegalArgumentException("Unknown argument " + argv[i]);
            }
        }
        if (maps == null || out == null) usage(null);
        out.mkdirs();
        final PrintStream log = System.out;
        long t0 = System.currentTimeMillis();
        NativeLoader.ensureLoaded();
        new org.sbgned.SBGNToolsTab();   // SBGN-ED statics the readers need; opens no window
        File[] files = maps.listFiles();
        if (files == null) throw new IOException("Not a directory: " + maps);
        Arrays.sort(files);
        Map<String, String> summary = new LinkedHashMap<>();
        int failed = 0;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".sbgn")) continue;
            String base = f.getName().substring(0, f.getName().length() - 5);
            if (include != null && !include.matcher(base).find()) continue;
            if (exclude != null && exclude.matcher(base).find()) continue;
            long ts = System.currentTimeMillis();
            try {
                Language lang = sbgnLayout(f, base, out, stepwise, log);
                log.println("MAP " + base + " " + lang + " " + (System.currentTimeMillis() - ts) + " ms");
                compare(base + ".coords.txt", out, baseline, summary, false);
                compare(base + ".sbgn", out, baseline, summary, false);
            } catch (Throwable t) {
                failed++;
                log.println("MAP " + base + " FAILED: " + t);
                t.printStackTrace(log);
                summary.put(base, "FAILED " + t);
            }
            if (mm && Arrays.asList(MENTAL_MAP_MAPS).contains(base)) {
                try {
                    mentalMap(f, base, out, log);
                    for (ComparisonLayoutRunner.Choice c : MM_EXACT) compare(base + ".mm-" + c.name() + ".coords.txt", out, baseline, summary, false);
                    compare(base + ".mm-PROTOTYPE.coords.txt", out, baseline, summary, true);
                } catch (Throwable t) {
                    failed++;
                    log.println("MM " + base + " FAILED: " + t);
                    t.printStackTrace(log);
                    summary.put(base + " (mental map)", "FAILED " + t);
                }
            }
        }
        log.println("SUMMARY " + (System.currentTimeMillis() - t0) + " ms");
        int differs = 0;
        for (Map.Entry<String, String> e : summary.entrySet()) {
            log.println("  " + e.getKey() + ": " + e.getValue());
            if (e.getValue().startsWith("DIFFERS") || e.getValue().startsWith("FAILED") || e.getValue().startsWith("MISSING")) differs++;
        }
        log.println(differs == 0 && failed == 0 ? "RESULT same" : "RESULT " + differs + " differ, " + failed + " failed");
        System.exit(differs == 0 && failed == 0 ? 0 : 1);
    }

    /** {@code argv[i]}, the value of the option {@code key}; without one, the usage is printed and the run ends. */
    private static String need(String[] argv, int i, String key) {
        if (i >= argv.length) usage(key + " expects a value");
        return argv[i];
    }

    private static void usage(String error) {
        if (error != null) System.err.println("ERROR " + error);
        System.err.println("Usage: Regression --maps DIR --out DIR [--baseline DIR] [--include REGEX] [--exclude REGEX] [--no-mm] [--stepwise]");
        System.exit(64);
    }

    // ------------------------------------------------------------------ SBGN

    static Language sbgnLayout(File f, String base, File out, boolean stepwise, PrintStream log) throws Exception {
        HeadlessVantedRunner.resetStatics();
        Sbgn sbgn = HeadlessVantedRunner.readSbgn(f);
        PortHelper.readInputPorts(sbgn);
        String mapLanguage = HeadlessVantedRunner.mapLanguage(sbgn);
        Language lang = HeadlessVantedRunner.resolveLanguage("auto", mapLanguage);
        Graph graph = new AdjListGraph();
        org.sbgned.sbgnml.SBGNMLReader.createGraph(sbgn, graph);
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        ConstraintSBGNLayout layout = new ConstraintSBGNLayout(graph, lang);
        if (lang != Language.PD) ConstraintSBGNLayout.view2D = HeadlessSvgExporter.createView(graph);
        if (stepwise) {
            for (int i = 0; i < layout.getSteps().size(); i++) layout.runStep(i);
        } else {
            layout.runAll();
        }
        writeCoords(graph, new File(out, base + ".coords.txt"), true);
        HeadlessVantedRunner.exportSbgn(graph, new File(out, base + ".sbgn"));
        return lang;
    }

    /** Nodes and edges of the drawing, sorted, with every double as Java prints it. */
    static void writeCoords(Graph g, File f, boolean withConstraints) throws IOException {
        List<String> lines = new ArrayList<>();
        Map<Node, String> names = new LinkedHashMap<>();
        for (Node n : g.getNodes()) names.put(n, nodeName(n));
        for (Node n : g.getNodes()) {
            lines.add("N " + names.get(n) + " " + AttributeHelper.getPositionX(n) + " " + AttributeHelper.getPositionY(n)
                    + " " + AttributeHelper.getWidth(n) + " " + AttributeHelper.getHeight(n));
        }
        for (Edge e : g.getEdges()) {
            StringBuilder sb = new StringBuilder("E " + names.get(e.getSource()) + " -> " + names.get(e.getTarget()));
            for (Object b : HeadlessCenterLayout.bendValues(e)) {
                if (b instanceof CoordinateAttribute) sb.append(" (").append(((CoordinateAttribute) b).getX()).append(',').append(((CoordinateAttribute) b).getY()).append(')');
            }
            lines.add(sb.toString());
        }
        Collections.sort(lines);
        if (withConstraints && ConstraintStore.constraints != null) {
            List<String> cs = new ArrayList<>();
            for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
                StringBuilder sb = new StringBuilder("C " + c.getKind() + (c.isAutomatic() ? " auto" : " user") + (c.isActive() ? "" : " off"));
                for (Node n : c.getNodes()) sb.append(' ').append(names.get(n));
                cs.add(sb.toString());
            }
            Collections.sort(cs);
            lines.add("# constraints " + cs.size());
            lines.addAll(cs);
        }
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            for (String l : lines) w.print(l + "\n");
        }
    }

    /** Glyph id if the node has one, else the label, always followed by the node id (sorted output needs unique names). */
    static String nodeName(Node n) {
        String id = "";
        try { id = String.valueOf(AttributeHelper.getAttributeValue(n, "sbgn", "glyphid", "", "", false)); } catch (Throwable ignore) { }
        String label = AttributeHelper.getLabel(n, "");
        label = label == null ? "" : HtmlPlainText.htmlToPlainText(label).replaceAll("\\s+", "_");
        return (id.isEmpty() ? label : id) + "#" + n.getID();
    }

    // ------------------------------------------------------------------ mental map

    /**
     * Reference = the map as loaded (uids from the glyph ids); live = the map with every fifth node (in name order)
     * deleted and three new nodes added, each joined to two surviving nodes, placed at the origin (the layout seeds
     * them). Every choice starts from the same live graph.
     */
    static void mentalMap(File f, String base, File out, PrintStream log) throws Exception {
        HeadlessVantedRunner.resetStatics();
        Sbgn sbgn = HeadlessVantedRunner.readSbgn(f);
        Graph reference = new AdjListGraph();
        org.sbgned.sbgnml.SBGNMLReader.createGraph(sbgn, reference);
        for (Node n : reference.getNodes()) AttributeHelper.setAttribute(n, Snapshot.UID_PATH, "uid", "u:" + nodeName(n));
        Snapshot ref = Snapshot.capture(reference, "reference");
        List<ComparisonLayoutRunner.Choice> choices = new ArrayList<>(Arrays.asList(MM_EXACT));
        choices.add(ComparisonLayoutRunner.Choice.PROTOTYPE);
        for (ComparisonLayoutRunner.Choice choice : choices) {
            Graph live = new AdjListGraph();
            org.sbgned.sbgnml.SBGNMLReader.createGraph(sbgn, live);
            for (Node n : live.getNodes()) AttributeHelper.setAttribute(n, Snapshot.UID_PATH, "uid", "u:" + nodeName(n));
            List<Node> ordered = new ArrayList<>(live.getNodes());
            ordered.sort(Comparator.comparing(Regression::nodeName));
            List<Node> gone = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) if (i % 5 == 0) gone.add(ordered.get(i));
            for (Node n : gone) live.deleteNode(n);
            List<Node> kept = new ArrayList<>(live.getNodes());
            kept.sort(Comparator.comparing(Regression::nodeName));
            for (int k = 0; k < 3 && kept.size() >= 2; k++) {
                Node n = live.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(0, 0));
                AttributeHelper.setSize(n, 60, 30);
                AttributeHelper.setLabel(n, "new" + k);
                AttributeHelper.setAttribute(n, Snapshot.UID_PATH, "uid", "u:new" + k);
                live.addEdge(kept.get((2 * k) % kept.size()), n, true, AttributeHelper.getDefaultGraphicsAttributeForEdge(java.awt.Color.BLACK, java.awt.Color.BLACK, true));
                live.addEdge(n, kept.get((2 * k + 1) % kept.size()), true, AttributeHelper.getDefaultGraphicsAttributeForEdge(java.awt.Color.BLACK, java.awt.Color.BLACK, true));
            }
            long ts = System.currentTimeMillis();
            new ComparisonLayoutRunner().run(choice, live, ref);
            log.println("MM " + base + " " + choice.name() + " " + (System.currentTimeMillis() - ts) + " ms");
            writeCoords(live, new File(out, base + ".mm-" + choice.name() + ".coords.txt"), false);
        }
    }

    // ------------------------------------------------------------------ comparison

    static void compare(String name, File out, File baseline, Map<String, String> summary, boolean tolerant) throws IOException {
        if (baseline == null) return;
        File a = new File(baseline, name), b = new File(out, name);
        if (!a.isFile()) { summary.put(name, "MISSING in baseline"); return; }
        byte[] x = Files.readAllBytes(a.toPath()), y = Files.readAllBytes(b.toPath());
        if (Arrays.equals(x, y)) { summary.put(name, "same"); return; }
        if (!name.endsWith(".coords.txt")) { summary.put(name, "DIFFERS (" + x.length + " vs " + y.length + " bytes)"); return; }
        double dev = maxDeviation(new String(x, StandardCharsets.UTF_8), new String(y, StandardCharsets.UTF_8));
        String s = String.format(Locale.ROOT, "%.3f px", dev);
        if (tolerant && dev <= MM_TOLERANCE && Double.isFinite(dev)) summary.put(name, "noise (max " + s + ", within the Prototype's known solver noise)");
        else summary.put(name, "DIFFERS (max coordinate deviation " + s + (Double.isFinite(dev) ? "" : ", or different elements") + ")");
    }

    /** Largest difference of a node centre between two coordinate dumps; infinite if the node sets differ. */
    static double maxDeviation(String a, String b) {
        Map<String, double[]> pa = positions(a), pb = positions(b);
        if (!pa.keySet().equals(pb.keySet())) return Double.POSITIVE_INFINITY;
        double max = 0;
        for (Map.Entry<String, double[]> e : pa.entrySet()) {
            double[] q = pb.get(e.getKey());
            max = Math.max(max, Math.max(Math.abs(q[0] - e.getValue()[0]), Math.abs(q[1] - e.getValue()[1])));
        }
        return max;
    }

    private static Map<String, double[]> positions(String dump) {
        Map<String, double[]> m = new TreeMap<>();
        for (String line : dump.split("\n")) {
            if (!line.startsWith("N ")) continue;
            String[] p = line.split(" ");
            if (p.length >= 4) m.put(p[1], new double[]{ Double.parseDouble(p[2]), Double.parseDouble(p[3]) });
        }
        return m;
    }
}
