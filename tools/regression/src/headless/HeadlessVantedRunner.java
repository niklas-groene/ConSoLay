package headless;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintFeasibility;
import constraints.ConstraintStore;
import constraints.Constraints;
import layout.sbgn.ConstraintSBGNLayout;
import layout.steps.LayoutStep;
import org.AttributeHelper;
import org.ErrorMsg;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.CoordinateAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sbgn.Language;
import org.sbgn.SbgnUtil;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNHelper;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command line front end of the Constraint SBGN Layout without the VANTED GUI.
 *
 * <pre>
 *   HeadlessVantedRunner --in map.sbgn [--outdir DIR] [options]
 *   HeadlessVantedRunner --indir DIR [--outdir DIR] [--recursive] [--ext .sbgn] [options]
 *
 *   --language auto|PD|ER|AF   force the SBGN language (default: from the map)
 *   --steps N                  run only the first N pipeline steps
 *   --per-step                 write &lt;base&gt;_stepN.svg after every step (PD step 1: &lt;base&gt;_skeleton.svg,
 *                              drawn after step 2 so it shows the skeleton's force-directed positions)
 *   --original                 write &lt;base&gt;_original.svg of the input before layout
 *   --png                      write &lt;base&gt;_layout.png next to the SVG
 *   --json                     write &lt;base&gt;_result.json (graph stats, steps, constraints, conflicts)
 * </pre>
 *
 * Machine readable progress goes to stdout, one record per line:
 * RUNNER_VERSION, LANGUAGE, GRAPH, STEP_PLAN, STEP_START, STEP_DONE, WROTE_*, FILE_DONE, DONE, BATCH_DONE.
 * Errors go to stderr prefixed with ERROR; exit code 2 = layout failed, 3 = a file of the batch failed,
 * 64 = bad arguments.
 */
public class HeadlessVantedRunner {

    public static final int RUNNER_VERSION = 4;
    private static final int BORDER = 40;

    static class Args {
        String in;
        String indir;
        String outDir;
        boolean recursive = false;
        String ext = ".sbgn";
        String language = "auto";
        int steps = -1;
        boolean perStep = false;
        boolean original = false;
        boolean png = false;
        boolean json = false;
    }

    public static void main(String[] argv) {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        // VANTED prints its own progress to System.out as well; the records go to the stream captured here.
        final PrintStream out = System.out;
        Args a;
        try {
            a = parseArgs(argv);
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
            System.exit(64);
            return;
        }
        if (a.in == null && a.indir == null) {
            usage(null);
            System.exit(64);
            return;
        }
        out.println("RUNNER_VERSION=" + RUNNER_VERSION);
        long t0 = System.currentTimeMillis();
        try {
            NativeLoader.ensureLoaded();
            out.println("NATIVE_OK ms=" + (System.currentTimeMillis() - t0));
            // Initialises the SBGN-ED statics the readers depend on (no window is opened).
            new org.sbgned.SBGNToolsTab();
        } catch (Throwable t) {
            fail("Initialisation failed: " + t.getMessage(), t);
            return;
        }

        if (a.indir != null) {
            File dir = new File(a.indir).getAbsoluteFile();
            if (!dir.isDirectory()) {
                fail("Not a directory: " + dir, null);
                return;
            }
            int[] counters = new int[] { 0, 0 };
            processDirectory(dir, dir, a, counters, out);
            out.println("BATCH_DONE processed=" + counters[0] + " failed=" + counters[1]);
            out.println("DONE ms=" + (System.currentTimeMillis() - t0));
            System.exit(counters[1] > 0 ? 3 : 0);
        } else {
            File inFile = new File(a.in).getAbsoluteFile();
            if (!inFile.isFile()) {
                fail("SBGN file not found: " + inFile, null);
                return;
            }
            File outDir = (a.outDir != null) ? new File(a.outDir).getAbsoluteFile() : inFile.getParentFile();
            outDir.mkdirs();
            try {
                processFile(inFile, outDir, a, out);
            } catch (Throwable t) {
                fail("Layout failed: " + describe(t), t);
                return;
            }
            out.println("DONE ms=" + (System.currentTimeMillis() - t0));
            System.exit(0);
        }
    }

    // ------------------------------------------------------------------ batch

    private static void processDirectory(File root, File dir, Args a, int[] counters, PrintStream out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        java.util.Arrays.sort(entries);
        for (File f : entries) {
            if (f.isDirectory()) {
                if (a.recursive) processDirectory(root, f, a, counters, out);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(a.ext.toLowerCase(Locale.ROOT))) {
                File outDir;
                if (a.outDir != null) {
                    String rel = root.toPath().relativize(f.getParentFile().toPath()).toString();
                    outDir = rel.isEmpty() ? new File(a.outDir) : new File(a.outDir, rel);
                } else {
                    outDir = f.getParentFile();
                }
                outDir.mkdirs();
                try {
                    processFile(f, outDir, a, out);
                    counters[0]++;
                } catch (Throwable t) {
                    counters[1]++;
                    System.err.println("ERROR " + f.getAbsolutePath() + ": " + describe(t));
                    t.printStackTrace(System.err);
                    out.println("FILE_FAILED " + f.getAbsolutePath());
                }
            }
        }
    }

    // ------------------------------------------------------------------ one file

    static void processFile(File inFile, File outDir, Args a, PrintStream out) throws Exception {
        long tStart = System.currentTimeMillis();
        String base = stripExtension(inFile.getName());
        out.println("PROCESS " + inFile.getAbsolutePath());
        resetStatics();

        // 1) Load
        Sbgn sbgn = readSbgn(inFile);
        PortHelper.readInputPorts(sbgn);   // -Dcblayout.keepPorts=true: the PD writer groups arc ends by these port ids
        String mapLanguage = mapLanguage(sbgn);
        Language lang = resolveLanguage(a.language, mapLanguage);
        Graph graph = new AdjListGraph();
        org.sbgned.sbgnml.SBGNMLReader.createGraph(sbgn, graph);
        if (graph.getNumberOfNodes() == 0) {
            throw new IOException("The map contains no glyphs (map language: " + mapLanguage + ")");
        }
        long tLoaded = System.currentTimeMillis();
        int processes = 0, compartments = 0;
        for (Node n : graph.getNodes()) {
            String role = role(n);
            if (role.contains("PROCESS") || role.equals("ASSOCIATION") || role.equals("DISSOCIATION")) processes++;
            if (role.equals("COMPARTMENT")) compartments++;
        }
        out.println("LANGUAGE=" + lang.name());
        out.println("GRAPH nodes=" + graph.getNumberOfNodes() + " edges=" + graph.getNumberOfEdges()
                + " processes=" + processes + " compartments=" + compartments);

        JSONObject result = new JSONObject();
        result.put("runnerVersion", RUNNER_VERSION);
        result.put("input", inFile.getName());
        result.put("language", lang.name());
        result.put("mapLanguage", mapLanguage == null ? JSONObject.NULL : mapLanguage);
        JSONObject graphJson = new JSONObject();
        graphJson.put("nodes", graph.getNumberOfNodes());
        graphJson.put("edges", graph.getNumberOfEdges());
        graphJson.put("processes", processes);
        graphJson.put("compartments", compartments);
        graphJson.put("compartmentsDropped", ConstraintSBGNLayout.DROP_COMPARTMENTS && compartments > 0);
        result.put("graph", graphJson);
        JSONObject outputs = new JSONObject();

        if (a.original) {
            File f = new File(outDir, base + "_original.svg");
            HeadlessSvgExporter.exportSvg(graph, f, BORDER);
            out.println("WROTE_ORIGINAL_SVG=" + f.getAbsolutePath());
            outputs.put("originalSvg", f.getName());
        }

        // 2) Layout
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        ConstraintSBGNLayout layout = new ConstraintSBGNLayout(graph, lang);
        if (lang != Language.PD) {
            // The ER/AF steps route edges against ConstraintSBGNLayout.view2D but never create it.
            ConstraintSBGNLayout.view2D = HeadlessSvgExporter.createView(graph);
        }
        List<LayoutStep> steps = layout.getSteps();
        int n = (a.steps < 0) ? steps.size() : Math.min(a.steps, steps.size());
        // The plan first, so a client can list the steps still to come ("step 2 of 4") before they start.
        for (int i = 0; i < n; i++) out.println("STEP_PLAN " + (i + 1) + " " + steps.get(i).name);
        out.flush();
        JSONArray stepsJson = new JSONArray();
        long tLayout0 = System.currentTimeMillis();
        // The PD skeleton snapshot (step 1) shows the positions of step 2's force-directed pass on the skeleton;
        // without that pass (--steps 1, -Dcblayout.skeletonShape=topology) it shows the input positions.
        JSONObject pendingSkeleton = null;
        for (int i = 0; i < n; i++) {
            LayoutStep s = steps.get(i);
            out.println("STEP_START " + (i + 1) + " " + s.name);
            out.flush();
            long ts = System.currentTimeMillis();
            List<Constraint> created;
            try {
                created = layout.runStep(i);
            } catch (Throwable t) {
                // A run that fails in step 2 still writes the skeleton snapshot.
                if (pendingSkeleton != null) {
                    try {
                        writeSkeletonSnapshot(layout.skeleton, outDir, base, pendingSkeleton, "fd", a.png, out, outputs);
                    } catch (Throwable ignore) { /* report the layout failure, not this one */ }
                }
                throw t;
            }
            long ms = System.currentTimeMillis() - ts;
            out.println("STEP_DONE " + (i + 1) + " ms=" + ms + " constraints=" + created.size());
            out.flush();
            if (pendingSkeleton != null) {
                // Step 2 has run: the skeleton holds the positions of its force-directed pass.
                writeSkeletonSnapshot(layout.skeleton, outDir, base, pendingSkeleton, "fd", a.png, out, outputs);
                pendingSkeleton = null;
            }
            JSONObject sj = new JSONObject();
            sj.put("index", i + 1);
            sj.put("name", s.name);
            sj.put("description", s.description);
            sj.put("ms", ms);
            sj.put("constraintsAdded", created.size());
            if (a.perStep) {
                // PD step 1 builds the skeleton: draw that reduced graph rather than the barely changed input.
                boolean isSkeleton = i == 0 && layout.skeleton != null && layout.skeleton.getNumberOfNodes() > 0;
                if (isSkeleton) {
                    if (n >= 2 && !ConstraintSBGNLayout.usesTopologySkeleton()) {
                        pendingSkeleton = sj;
                    } else {
                        writeSkeletonSnapshot(layout.skeleton, outDir, base, sj, "input", a.png, out, outputs);
                    }
                } else {
                    File f = new File(outDir, base + "_step" + (i + 1) + ".svg");
                    HeadlessSvgExporter.exportSvg(graph, f, BORDER);
                    out.println("WROTE_STEP_SVG " + (i + 1) + "=" + f.getAbsolutePath());
                    sj.put("svg", f.getName());
                    if (a.png) {
                        File p = new File(outDir, base + "_step" + (i + 1) + ".png");
                        HeadlessSvgExporter.exportPng(graph, p, BORDER, 2400);
                        out.println("WROTE_STEP_PNG " + (i + 1) + "=" + p.getAbsolutePath());
                        sj.put("png", p.getName());
                    }
                }
            }
            stepsJson.put(sj);
        }
        long layoutMs = System.currentTimeMillis() - tLayout0;
        // Only the last pipeline step moves the network into the visible area.
        if (n < steps.size()) HeadlessCenterLayout.moveGraphTopLeft(graph, 50, 50);
        for (int i = n; i < steps.size(); i++) {
            JSONObject sj = new JSONObject();
            sj.put("index", i + 1);
            sj.put("name", steps.get(i).name);
            sj.put("description", steps.get(i).description);
            sj.put("skipped", true);
            stepsJson.put(sj);
        }
        result.put("steps", stepsJson);
        result.put("stepsRun", n);
        JSONObject after = new JSONObject();
        after.put("nodes", graph.getNumberOfNodes());
        after.put("edges", graph.getNumberOfEdges());
        result.put("graphAfterLayout", after);
        out.println("GRAPH_AFTER nodes=" + graph.getNumberOfNodes() + " edges=" + graph.getNumberOfEdges());

        // 3) Export
        long tExport0 = System.currentTimeMillis();
        JSONArray warnings = new JSONArray();
        File outSbgn = new File(outDir, base + "_layout.sbgn");
        try {
            exportSbgn(graph, outSbgn);
            out.println("WROTE_SBGN=" + outSbgn.getAbsolutePath());
            outputs.put("sbgn", outSbgn.getName());
        } catch (Exception e) {
            // The SBGN-ML writers inherited from SBGN-ED do not cover every ER/AF construct; the
            // drawing is still valuable, so keep going and report the missing file.
            String msg = "SBGN-ML export failed: " + describe(e);
            System.err.println("WARN " + msg);
            e.printStackTrace(System.err);
            out.println("SBGN_EXPORT_FAILED " + msg.replace('\n', ' '));
            outSbgn.delete();
            outputs.put("sbgn", JSONObject.NULL);
            warnings.put(msg);
        }
        File outSvg = new File(outDir, base + "_layout.svg");
        HeadlessSvgExporter.Bounds bounds = HeadlessSvgExporter.exportSvg(graph, outSvg, BORDER);
        out.println("WROTE_SVG=" + outSvg.getAbsolutePath());
        outputs.put("svg", outSvg.getName());
        if (a.png) {
            File outPng = new File(outDir, base + "_layout.png");
            HeadlessSvgExporter.exportPng(graph, outPng, BORDER, 4000);
            out.println("WROTE_PNG=" + outPng.getAbsolutePath());
            outputs.put("png", outPng.getName());
        }
        long exportMs = System.currentTimeMillis() - tExport0;

        // 4) Report
        JSONObject bj = new JSONObject();
        bj.put("x", bounds.x); bj.put("y", bounds.y); bj.put("width", bounds.width); bj.put("height", bounds.height);
        result.put("bounds", bj);
        result.put("constraints", constraintsJson(ConstraintStore.constraints));
        result.put("conflicts", conflictsJson(graph));
        JSONObject timings = new JSONObject();
        timings.put("loadMs", tLoaded - tStart);
        timings.put("layoutMs", layoutMs);
        timings.put("exportMs", exportMs);
        timings.put("totalMs", System.currentTimeMillis() - tStart);
        result.put("timings", timings);
        result.put("outputs", outputs);
        result.put("warnings", warnings);
        if (a.json) {
            File f = new File(outDir, base + "_result.json");
            Files.write(f.toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
            out.println("WROTE_JSON=" + f.getAbsolutePath());
        }
        out.println("FILE_DONE ms=" + (System.currentTimeMillis() - tStart)
                + " constraints=" + ConstraintStore.constraints.getAllConstraints().size()
                + " conflicts=" + ConflictLog.entries().size());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Writes the step-1 snapshot of a PD map: the skeleton as &lt;base&gt;_skeleton.svg (and .png with --png),
     * recorded under step index 1. The pipeline still reads the skeleton geometry afterwards, and the PNG
     * export shifts the graph without moving it back, so every coordinate is reset to its exact previous
     * value: the snapshot cannot influence the layout.
     *
     * @param positions which positions the drawing shows: "fd" (step 2's force-directed pass) or "input"
     */
    private static void writeSkeletonSnapshot(Graph skeleton, File outDir, String base, JSONObject sj, String positions,
                                              boolean png, PrintStream out, JSONObject outputs) throws Exception {
        List<CoordinateAttribute> coords = new ArrayList<>();
        List<double[]> saved = new ArrayList<>();
        for (Node nd : skeleton.getNodes()) {
            try {
                Object co = nd.getAttribute(GraphicAttributeConstants.COORD_PATH);
                if (co instanceof CoordinateAttribute) coords.add((CoordinateAttribute) co);
            } catch (Exception ignore) { /* no position */ }
        }
        for (Edge e : skeleton.getEdges()) {
            for (Object b : HeadlessCenterLayout.bendValues(e)) {
                if (b instanceof CoordinateAttribute) coords.add((CoordinateAttribute) b);
            }
        }
        for (CoordinateAttribute c : coords) saved.add(new double[] { c.getX(), c.getY() });

        File f = new File(outDir, base + "_skeleton.svg");
        try {
            HeadlessSvgExporter.exportSvg(skeleton, f, BORDER, true);
            out.println("WROTE_STEP_SVG 1=" + f.getAbsolutePath());
            sj.put("svg", f.getName());
            if (png) {
                File p = new File(outDir, base + "_skeleton.png");
                HeadlessSvgExporter.exportPng(skeleton, p, BORDER, 2400);
                out.println("WROTE_STEP_PNG 1=" + p.getAbsolutePath());
                sj.put("png", p.getName());
            }
        } finally {
            for (int k = 0; k < coords.size(); k++) {
                CoordinateAttribute c = coords.get(k);
                double[] xy = saved.get(k);
                if (Double.doubleToRawLongBits(c.getX()) != Double.doubleToRawLongBits(xy[0])) c.setX(xy[0]);
                if (Double.doubleToRawLongBits(c.getY()) != Double.doubleToRawLongBits(xy[1])) c.setY(xy[1]);
            }
        }
        sj.put("graph", "skeleton");
        sj.put("graphNodes", skeleton.getNumberOfNodes());
        sj.put("graphEdges", skeleton.getNumberOfEdges());
        sj.put("graphLayout", positions);
        outputs.put("skeletonSvg", f.getName());
    }

    private static JSONObject constraintsJson(Constraints cs) {
        JSONObject j = new JSONObject();
        if (cs == null) return j.put("total", 0);
        int automatic = 0, manual = 0;
        for (Constraint c : cs.getAllConstraints()) { if (c.isAutomatic()) automatic++; else manual++; }
        j.put("total", cs.getAllConstraints().size());
        j.put("horizontal", cs.getHorizontalConstraints().size());
        j.put("vertical", cs.getVerticalConstraints().size());
        j.put("separation", cs.getSeparationConstraints().size());
        j.put("automatic", automatic);
        j.put("manual", manual);
        return j;
    }

    /**
     * Every conflict carries the final position and size of the nodes its constraint involves, so a client can point
     * at exactly those nodes (labels are not unique, and process nodes have none).
     */
    private static JSONArray conflictsJson(Graph graph) {
        JSONArray arr = new JSONArray();
        for (ConflictLog.Entry e : ConflictLog.entries()) {
            JSONObject j = new JSONObject();
            j.put("kind", e.kind.name());
            j.put("title", e.title());
            j.put("phase", e.phase == null ? JSONObject.NULL : e.phase);
            j.put("reason", e.reason == null ? JSONObject.NULL : e.reason);
            if (e.constraint != null) {
                j.put("constraint", String.valueOf(e.constraint));
                j.put("constraintType", e.constraint.getClass().getSimpleName());
                j.put("constraintId", e.constraint.getId());
                JSONArray nodes = new JSONArray();
                for (Node nd : e.constraint.getNodes()) nodes.put(nodeJson(graph, nd));
                j.put("nodes", nodes);
            }
            arr.put(j);
        }
        return arr;
    }

    /** Identity and final geometry of one node, in the coordinates of the exported drawing. */
    private static JSONObject nodeJson(Graph graph, Node n) {
        JSONObject j = new JSONObject();
        j.put("label", labelOf(n));
        String role = role(n);
        if (!role.isEmpty()) j.put("role", role);
        j.put("id", n.getID());
        try {
            j.put("glyphId", AttributeHelper.getAttributeValue(n, "sbgn", "glyphid", "", "", false));
        } catch (Throwable ignore) { /* not an SBGN glyph */ }
        if (graph != null && graph.containsNode(n)) {
            try {
                double w = Math.max(1, AttributeHelper.getWidth(n)), h = Math.max(1, AttributeHelper.getHeight(n));
                j.put("x", AttributeHelper.getPositionX(n) - w / 2);
                j.put("y", AttributeHelper.getPositionY(n) - h / 2);
                j.put("width", w);
                j.put("height", h);
            } catch (Throwable ignore) { /* no geometry */ }
        }
        return j;
    }

    private static String labelOf(Node n) {
        String l = null;
        try { l = AttributeHelper.getLabel(n, (String) null); } catch (Throwable ignore) { }
        if (l == null || l.isEmpty()) {
            String role = role(n);
            return role.isEmpty() ? ("node " + n.getID()) : (role.toLowerCase(Locale.ROOT) + " " + n.getID());
        }
        return HtmlPlainText.htmlToPlainText(l).replace('\n', ' ').trim();
    }

    private static String role(Node n) {
        try {
            String r = SBGNHelper.getSBGNRole(n);
            return r == null ? "" : r.toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    static void resetStatics() {
        ConstraintSBGNLayout.reversibleRHS.clear();
        ConstraintSBGNLayout.orientationREV.clear();
        ConstraintSBGNLayout.orientationProdSub.clear();
        ConstraintSBGNLayout.orientationStim.clear();
        ConstraintSBGNLayout.orientationLogic.clear();
        ConstraintSBGNLayout.view2D = null;
        // The ER/AF edge routing reads this map unguarded; the PD pipeline replaces it in step 1.
        CBLayoutAlgorithm.clusterMap = new java.util.HashMap<>();
        ConstraintFeasibility.lastWarnings.clear();
        ConstraintFeasibility.lastDeactivated.clear();
        ConflictLog.clear();
        ConstraintStore.constraints = new Constraints();
        ErrorMsg.clearErrorMessages();
    }

    /** Language the map declares (its first map), or null if there is none. */
    static String mapLanguage(Sbgn sbgn) {
        return (sbgn.getMap() == null || sbgn.getMap().isEmpty()) ? null : sbgn.getMap().get(0).getLanguage();
    }

    /** Reads an SBGN-ML file; milestone 1/2 namespaces are upgraded to 0.3 when libSBGN rejects them. */
    static Sbgn readSbgn(File f) throws Exception {
        // Before any parser sees the file: libSBGN below configures none of them.
        assertNoDoctype(f);
        try {
            Sbgn s = SbgnUtil.readFromFile(f);
            if (s != null && s.getMap() != null && !s.getMap().isEmpty()) return s;
        } catch (Throwable first) {
            System.err.println("libSBGN could not read the file directly (" + first.getMessage() + "), retrying with namespace upgrade");
        }
        String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        xml = xml.replace("http://sbgn.org/libsbgn/pd/0.1", "http://sbgn.org/libsbgn/0.3")
                 .replace("http://sbgn.org/libsbgn/0.2", "http://sbgn.org/libsbgn/0.3");
        JAXBContext ctx = JAXBContext.newInstance(Sbgn.class);
        SAXSource source = new SAXSource(secureXmlReader(), new InputSource(new StringReader(xml)));
        Object o = ctx.createUnmarshaller().unmarshal(source);
        if (!(o instanceof Sbgn)) throw new IOException("Not an SBGN-ML document");
        Sbgn s = (Sbgn) o;
        if (s.getMap() == null || s.getMap().isEmpty()) throw new IOException("SBGN-ML document has no <map>");
        return s;
    }

    /** Longest prolog walked while looking for a document type declaration. */
    private static final int MAX_PROLOG_BYTES = 64 * 1024;

    /**
     * Refuses a document that carries a document type declaration, before it reaches a parser. SBGN-ML never uses
     * one, while the readers underneath (libSBGN, JAXB, Xerces 2.9.1) resolve external entities out of a DOCTYPE, and
     * {@link SbgnUtil} builds its own unmarshaller that cannot be configured from here. The prolog is whitelisted:
     * after an optional byte order mark and XML declaration only comments, processing instructions and whitespace
     * may precede the root element, so a DOCTYPE cannot hide behind padding.
     */
    private static void assertNoDoctype(File f) throws IOException {
        byte[] buf = new byte[MAX_PROLOG_BYTES];
        int n = 0;
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        if (n >= 2 && ((b(buf[0]) == 0xfe && b(buf[1]) == 0xff) || (b(buf[0]) == 0xff && b(buf[1]) == 0xfe))) {
            throw new IOException("Unsupported encoding: save the SBGN-ML file as UTF-8");
        }
        int start = (n >= 3 && b(buf[0]) == 0xef && b(buf[1]) == 0xbb && b(buf[2]) == 0xbf) ? 3 : 0;
        // ISO-8859-1 keeps one character per byte, so offsets stay exact; prolog markup is ASCII.
        String head = new String(buf, start, n - start, StandardCharsets.ISO_8859_1);
        // A UTF-16 document would hide its ASCII markup from the scan below.
        if (head.indexOf('\0') >= 0) throw new IOException("Unsupported encoding: save the SBGN-ML file as UTF-8");

        int i = skipSpace(head, 0);
        if (head.startsWith("<?xml", i) && i + 5 < head.length() && endsXmlDeclToken(head.charAt(i + 5))) {
            i = after(head, "?>", i, 2);
        }
        while (true) {
            i = skipSpace(head, i);
            if (i >= head.length()) throw new IOException("The document has no <sbgn> element");
            if (head.charAt(i) != '<') throw new IOException("The document does not start with an XML element");
            if (head.startsWith("<!--", i)) { i = after(head, "-->", i + 4, 3); continue; }
            if (head.startsWith("<?", i)) { i = after(head, "?>", i + 2, 2); continue; }
            // The only other "<!" construct legal here is a DOCTYPE, which is refused.
            if (head.startsWith("<!", i)) {
                throw new IOException("Document type declarations are not accepted: "
                        + "remove the <!DOCTYPE ...> line from the SBGN-ML file");
            }
            return; // the root element starts here; the unmarshaller checks what it is
        }
    }

    private static int b(byte v) {
        return v & 0xff;
    }

    private static boolean endsXmlDeclToken(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '?';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r' || s.charAt(i) == '\n')) i++;
        return i;
    }

    /** Index just past the next {@code close} at or after {@code from}, or a refusal when it never comes. */
    private static int after(String s, String close, int from, int len) throws IOException {
        int end = s.indexOf(close, from);
        if (end < 0) throw new IOException("The document starts with too much markup before its <sbgn> element");
        return end + len;
    }

    /**
     * A SAX reader that never looks outside the document: no document type declaration, no external
     * entities, no external DTD, no XInclude. The factory this JAR ships is Xerces 2.9.1, which does
     * all of those by default.
     */
    private static XMLReader secureXmlReader() throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        // Not guarded: if this cannot be switched on, the reader must not be used at all.
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        setIfSupported(spf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setIfSupported(spf, "http://xml.org/sax/features/external-general-entities", false);
        setIfSupported(spf, "http://xml.org/sax/features/external-parameter-entities", false);
        setIfSupported(spf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            spf.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Old factories have no XInclude support to switch off.
        }
        XMLReader reader = spf.newSAXParser().getXMLReader();
        // Any remaining request for an external resource gets an empty one.
        reader.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader(""));
            }
        });
        return reader;
    }

    private static void setIfSupported(SAXParserFactory spf, String feature, boolean value) {
        try {
            spf.setFeature(feature, value);
        } catch (Exception ignored) {
            // A parser that does not know the feature cannot act on it either.
        }
    }

    static Language resolveLanguage(String option, String mapLanguage) {
        String o = option == null ? "auto" : option.trim().toUpperCase(Locale.ROOT);
        if (o.equals("PD")) return Language.PD;
        if (o.equals("ER")) return Language.ER;
        if (o.equals("AF")) return Language.AF;
        String m = mapLanguage == null ? "" : mapLanguage.trim().toLowerCase(Locale.ROOT);
        if (m.startsWith("a")) return Language.AF;
        if (m.startsWith("e")) return Language.ER;
        return Language.PD;
    }

    static void exportSbgn(Graph graph, File f) throws Exception {
        ErrorMsg.clearErrorMessages();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            new WriterNoGui().write(fos, graph);
        }
        if (f.length() < 200) {
            String[] errors = ErrorMsg.getErrorMessages();
            throw new IOException("SBGN export produced no map"
                    + (errors != null && errors.length > 0 ? ": " + String.join("; ", errors) : ""));
        }
    }

    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String k = argv[i];
            switch (k) {
                case "--in":        a.in = need(argv, ++i, k); break;
                case "--outdir":    a.outDir = need(argv, ++i, k); break;
                case "--indir":     a.indir = need(argv, ++i, k); break;
                case "--recursive": a.recursive = true; break;
                case "--ext":       a.ext = need(argv, ++i, k); break;
                case "--language":  a.language = need(argv, ++i, k); break;
                case "--steps":
                    try { a.steps = Integer.parseInt(need(argv, ++i, k)); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("--steps expects a number"); }
                    break;
                case "--per-step":  a.perStep = true; break;
                case "--original":  a.original = true; break;
                case "--png":       a.png = true; break;
                case "--json":      a.json = true; break;
                case "--help": case "-h": throw new IllegalArgumentException((String) null);
                default: throw new IllegalArgumentException("Unknown argument: " + k);
            }
        }
        return a;
    }

    private static String need(String[] argv, int i, String key) {
        if (i >= argv.length) throw new IllegalArgumentException(key + " expects a value");
        return argv[i];
    }

    private static void usage(String error) {
        if (error != null) System.err.println("ERROR " + error);
        System.err.println("Usage:");
        System.err.println("  java -jar clean-runner.jar --in FILE.sbgn [--outdir DIR] [options]");
        System.err.println("  java -jar clean-runner.jar --indir DIR [--outdir DIR] [--recursive] [--ext .sbgn] [options]");
        System.err.println("Options:");
        System.err.println("  --language auto|PD|ER|AF  --steps N  --per-step  --original  --png  --json");
    }

    private static void fail(String message, Throwable t) {
        System.err.println("ERROR " + message);
        if (t != null) t.printStackTrace(System.err);
        System.err.flush();
        System.exit(2);
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

}
