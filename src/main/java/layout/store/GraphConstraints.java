package layout.store;

import constraints.Constraints;
import layout.template.apply.InstanceRegistry;
import layout.template.io.Json;
import layout.template.model.TemplateSource;

import org.AttributeHelper;
import org.graffiti.attributes.Attributable;
import org.graffiti.attributes.Attribute;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Saves a graph's constraint store with the graph and reads it back: as the string attribute {@code cbl.constraints}
 * (the JSON of {@link StoreCodec}), which VANTED's GML and GraphML writers keep, or, for SBGN-ML, whose writer drops
 * foreign attributes, as a companion file {@code <map>.cbl.json}. The nodes and edges a constraint refers to carry a
 * stable id ({@code cbl.nid} / {@code cbl.eid}); without it a node is found by its SBGN glyph id or a unique label.
 * {@link #sync} writes the attribute only when the JSON changed, so an unchanged map is not marked modified, and a
 * graph's saved store is read once, when its session first becomes active ({@link #markAttached}, {@link #loadSaved}).
 *
 * @author Niklas Gröne
 */
public final class GraphConstraints {

    public static final String PATH = "cbl";
    public static final String STORE = "constraints";
    public static final String NID = "nid";
    public static final String EID = "eid";
    public static final String COMPANION_EXT = ".cbl.json";

    /**
     * -Dcblayout.saveWithGraph=true|false (default true): true saves the store with the graph and reads it back, false
     * writes nothing into a graph and reads nothing from it.
     */
    public static final boolean ENABLED = !"false".equals(System.getProperty("cblayout.saveWithGraph", "true"));

    private static final Map<Graph, String> LAST = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Graph> ATTACHED = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    /** Graphs whose saved store could not be read at all: their attribute is left as it is, never overwritten. */
    private static final Set<Graph> UNREADABLE = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private GraphConstraints() { }

    /** The stable id of {@code n}; {@code assign}: give it one if it has none. Null if it has none and none is assigned. */
    public static String nodeId(Node n, boolean assign) {
        return stableId(n, NID, assign);
    }

    /** The stable id of {@code e}, see {@link #nodeId}. */
    public static String edgeId(Edge e, boolean assign) {
        return stableId(e, EID, assign);
    }

    private static String stableId(Attributable a, String name, boolean assign) {
        String id = string(a, PATH, name);
        if (id == null && assign) {
            id = newId();
            AttributeHelper.setAttribute(a, PATH, name, id);
        }
        return id;
    }

    /** The SBGN glyph id SBGN-ED keeps on the node, or null. */
    public static String glyphId(Node n) {
        return string(n, "sbgn", "glyphid");
    }

    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String string(Attributable a, String path, String name) {
        try {
            if (!AttributeHelper.hasAttribute(a, path, name)) return null;
            Object v = AttributeHelper.getAttributeValue(a, path, name, null, "", false);
            String s = v == null ? null : String.valueOf(v);
            return s == null || s.isEmpty() ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Names the graph's elements for the codec; {@code assign}: give nodes and edges stable ids as they are named. */
    static final class GraphElements implements StoreCodec.Elements {
        private final Graph g;
        private final boolean assign;
        private Map<String, Node> byNid, byAlt;
        private Map<String, List<Node>> byLabel;
        private Map<String, Edge> byEid;

        GraphElements(Graph g, boolean assign) {
            this.g = g;
            this.assign = assign;
        }

        private boolean unique;

        @Override public String nodeId(Node n) {
            makeUnique();
            return GraphConstraints.nodeId(n, assign);
        }

        @Override public String edgeId(Edge e) {
            makeUnique();
            return GraphConstraints.edgeId(e, assign);
        }

        /**
         * Copy and paste copies the attributes, stable ids included: before ids are written, every id held by more than
         * one element stays with the first one in graph order and the others get fresh ids.
         */
        private void makeUnique() {
            if (!assign || unique) return;
            unique = true;
            Set<String> seen = new HashSet<>();
            for (Node n : g.getNodes()) {
                String id = GraphConstraints.nodeId(n, false);
                if (id != null && !seen.add(id)) AttributeHelper.setAttribute(n, PATH, NID, newId());
            }
            seen.clear();
            for (Edge e : g.getEdges()) {
                String id = GraphConstraints.edgeId(e, false);
                if (id != null && !seen.add(id)) AttributeHelper.setAttribute(e, PATH, EID, newId());
            }
        }

        @Override public String altId(Node n) { return glyphId(n); }

        private void index() {
            if (byNid != null) return;
            byNid = new HashMap<>();
            byAlt = new HashMap<>();
            byLabel = new HashMap<>();
            byEid = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();   // an id two elements carry (a pasted copy) identifies neither
            for (Node n : g.getNodes()) {
                String id = GraphConstraints.nodeId(n, false);
                if (id != null && byNid.put(id, n) != null) ambiguous.add(id);
                String alt = glyphId(n);
                if (alt != null) byAlt.put(alt, n);
                String label = AttributeHelper.getLabel(n, null);
                if (label != null && !label.isEmpty()) byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(n);
            }
            byNid.keySet().removeAll(ambiguous);
            ambiguous.clear();
            for (Edge e : g.getEdges()) {
                String id = GraphConstraints.edgeId(e, false);
                if (id != null && byEid.put(id, e) != null) ambiguous.add(id);
            }
            byEid.keySet().removeAll(ambiguous);
        }

        @Override public Node node(String id, String alt, String label) {
            index();
            Node n = id == null ? null : byNid.get(id);
            if (n == null && alt != null) n = byAlt.get(alt);
            if (n == null && label != null) {
                List<Node> l = byLabel.get(label);
                if (l != null && l.size() == 1) n = l.get(0);
            }
            return n;
        }

        @Override public Edge edge(String id, Node from, Node to, int index) {
            index();
            Edge e = id == null ? null : byEid.get(id);
            if (e != null || from == null || to == null) return e;
            int i = 0;
            for (Edge x : g.getEdges()) {
                if (x.getSource() != from || x.getTarget() != to) continue;
                if (i == index) return x;
                i++;
            }
            return null;
        }
    }

    /** The document of {@code store} on {@code g} (nodes and edges it refers to get their stable ids now). */
    public static Map<String, Object> document(Graph g, Constraints store) {
        return StoreCodec.write(g, store, new GraphElements(g, true));
    }

    /** The JSON text {@link #sync} writes into the graph. */
    public static String json(Graph g, Constraints store) {
        return Json.write(document(g, store));
    }

    /** The saved store the graph carries (the JSON text), or null. */
    public static String saved(Graph g) {
        String v = g == null ? null : string(g, PATH, STORE);
        return v == null ? null : decode(v);
    }

    /**
     * The attribute holds the JSON as Base64 of its UTF-8 bytes, marked by the prefix "b64:": VANTED's GML lexer accepts
     * neither backslashes nor characters beyond ASCII inside a string, and JSON has both (escaped quotes, labels).
     */
    static String encode(String json) {
        return "b64:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String stored) {
        if (!stored.startsWith("b64:")) return stored;   // plain JSON, should a writer ever keep it
        try {
            return new String(Base64.getDecoder().decode(stored.substring(4)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return stored;
        }
    }

    public static boolean hasSaved(Graph g) {
        return saved(g) != null;
    }

    /**
     * Marks {@code g} as attached: its saved store is read now (the caller does that, once), and from now on
     * {@link #sync} keeps the attribute up to date. Returns true the first time for this graph.
     */
    public static boolean markAttached(Graph g) {
        return ENABLED && g != null && ATTACHED.add(g);
    }

    /**
     * Writes the store into the graph attribute when it differs from what the graph carries (nothing for a graph not
     * yet attached); an empty store removes the attribute. Returns true if the graph changed.
     */
    public static boolean sync(Graph g, Constraints store) {
        if (!ENABLED || g == null || store == null || !ATTACHED.contains(g) || UNREADABLE.contains(g)) return false;
        boolean empty = store.getAllConstraints().isEmpty() && InstanceRegistry.of(g).isEmpty();
        if (empty) {
            LAST.remove(g);
            if (saved(g) == null) return false;
            try {
                g.removeAttribute(PATH + Attribute.SEPARATOR + STORE);
            } catch (RuntimeException ignored) {
                // not there any more
            }
            return true;
        }
        String json = json(g, store);
        String encoded = encode(json);
        if (json.equals(LAST.get(g)) && encoded.equals(string(g, PATH, STORE))) return false;
        AttributeHelper.setAttribute(g, PATH, STORE, encoded);
        LAST.put(g, json);
        return true;
    }

    /**
     * Reads the store the graph carries into {@code store} (adds; no node moves) and remembers it, so the next
     * {@link #sync} writes nothing. Null if the graph carries none.
     */
    public static StoreCodec.Report loadSaved(Graph g, Constraints store, TemplateSource templates) {
        String s = saved(g);
        if (s == null) return null;
        StoreCodec.Report r = read(s, g, store, templates);
        if (r.constraints == 0 && r.instances == 0 && !r.problems.isEmpty()) UNREADABLE.add(g);
        else LAST.put(g, json(g, store));
        return r;
    }

    /** Restores the JSON {@code text} into {@code store} on {@code g}; a wrong file gives a report with the problem. */
    public static StoreCodec.Report read(String text, Graph g, Constraints store, TemplateSource templates) {
        Object o;
        try {
            o = Json.parse(text);
        } catch (Json.ParseException e) {
            return failed("The saved constraints are no JSON: " + e.getMessage());
        }
        if (!(o instanceof Map)) return failed("The saved constraints are no JSON object.");
        @SuppressWarnings("unchecked") Map<String, Object> doc = (Map<String, Object>) o;
        return StoreCodec.read(doc, g, store, new GraphElements(g, false), templates);
    }

    private static StoreCodec.Report failed(String problem) {
        StoreCodec.Report r = new StoreCodec.Report();
        r.problems.add(problem);
        return r;
    }

    /** {@code map.sbgn} → {@code map.sbgn.cbl.json} next to it. */
    public static File companionOf(File graphFile) {
        return new File(graphFile.getAbsoluteFile().getParentFile(), graphFile.getName() + COMPANION_EXT);
    }

    /** Writes the store of {@code g} as a companion file. */
    public static void writeCompanion(Graph g, Constraints store, File f) throws IOException {
        Files.write(f.toPath(), json(g, store).getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a companion file into {@code store} on {@code g}. */
    public static StoreCodec.Report readCompanion(File f, Graph g, Constraints store, TemplateSource templates) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return read(text, g, store, templates);
    }
}
