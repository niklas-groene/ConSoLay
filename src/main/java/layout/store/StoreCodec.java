package layout.store;

import constraints.Constraint;
import constraints.ConstraintGroup;
import constraints.ConstraintOrigin;
import constraints.Constraints;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.apply.InstanceKeys;
import layout.template.apply.InstancePlan;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.InstanceRestorer;
import layout.template.apply.TemplateInstance;
import layout.template.io.TemplateCodec;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The constraint store of a graph as a JSON document ("cbl-store") and back: every constraint as in
 * {@link TemplateCodec#constraint(ConstraintSpec)} plus its source, rank, group and template instance, the applied
 * template instances and the groups of the steps. Nodes and edges are named by document-local ids ("n3", "e1") that a
 * table resolves to the graph's stable ids, and for nodes also to an alternative id and the label, so a file written by
 * a tool that drops the stable ids can still be read. No conflict decision and no position is saved: the loop decides
 * again after loading, and loading moves no node.
 *
 * @author Niklas Gröne
 */
public final class StoreCodec {

    public static final String FORMAT = "cbl-store";
    public static final int FORMAT_VERSION = 1;

    private StoreCodec() { }

    /** How the graph's elements are named in the document (writing) and found again (reading). */
    public interface Elements {
        /** The stable id of the node (assigned now if it has none), or null if the node cannot be named. */
        String nodeId(Node n);

        /** The stable id of the edge (assigned now if it has none), or null. */
        String edgeId(Edge e);

        /** A second id the file format keeps for the node (the SBGN glyph id), or null. */
        String altId(Node n);

        /** The node a saved entry stands for: by stable id, else by the alternative id, else by a unique label; null if none. */
        Node node(String id, String alt, String label);

        /** The edge: by stable id, else the {@code index}-th edge from {@code from} to {@code to}; null if none. */
        Edge edge(String id, Node from, Node to, int index);
    }

    /** What reading restored and what it could not. */
    public static final class Report {
        public int constraints, instances, groups, skipped;
        public final List<String> problems = new ArrayList<>();
        public final List<Constraint> added = new ArrayList<>();
        public final List<TemplateInstance> restored = new ArrayList<>();

        /** E.g. "Loaded 37 constraints and 3 template instances; 2 could not be restored." */
        public String summary() {
            StringBuilder sb = new StringBuilder("Loaded ").append(constraints).append(constraints == 1 ? " constraint" : " constraints");
            if (instances > 0) sb.append(" and ").append(instances).append(instances == 1 ? " template instance" : " template instances");
            if (skipped > 0) sb.append("; ").append(skipped).append(" could not be restored");
            return sb.append('.').toString();
        }
    }

    /** The document of {@code store} on {@code g}; constraints with a node outside {@code g} or of a type that cannot be stored are left out. */
    public static Map<String, Object> write(Graph g, Constraints store, Elements el) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("formatVersion", (double) FORMAT_VERSION);
        Map<Node, String> nodeIds = new IdentityHashMap<>();
        Map<Edge, String> edgeIds = new IdentityHashMap<>();
        List<Object> nodes = new ArrayList<>();
        List<Object> edges = new ArrayList<>();
        ConstraintSpecs.Naming naming = new ConstraintSpecs.Naming() {
            @Override public String node(Node n) {
                return nodeIds.computeIfAbsent(n, k -> {
                    String id = "n" + (nodeIds.size() + 1);
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", id);
                    String nid = el.nodeId(k);
                    if (nid != null) o.put("nid", nid);
                    String alt = el.altId(k);
                    if (alt != null) o.put("alt", alt);
                    String label = AttributeHelper.getLabel(k, null);
                    if (label != null) o.put("label", label);
                    nodes.add(o);
                    return id;
                });
            }
            @Override public String edge(Edge e) {
                return edgeIds.computeIfAbsent(e, k -> {
                    String id = "e" + (edgeIds.size() + 1);
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", id);
                    String eid = el.edgeId(k);
                    if (eid != null) o.put("eid", eid);
                    o.put("from", node(k.getSource()));
                    o.put("to", node(k.getTarget()));
                    o.put("index", (double) indexOf(g, k));
                    edges.add(o);
                    return id;
                });
            }
            @Override public double[] toLocal(double x, double y) { return new double[]{x, y}; }
        };
        Map<TemplateInstance, String> instIds = new IdentityHashMap<>();
        List<Object> insts = new ArrayList<>();
        for (TemplateInstance inst : InstanceRegistry.of(g)) {
            if (!assigned(g, inst)) continue;
            String id = "i" + (insts.size() + 1);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", id);
            o.put("label", inst.label());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", inst.template.id);
            t.put("revision", (double) inst.template.revision);
            t.put("name", inst.template.name);
            o.put("template", t);
            if (inst.ruleName != null) o.put("rule", inst.ruleName);
            o.put("key", inst.key);
            o.put("on", inst.isOn());
            List<String> off = InstanceRestorer.partsOff(inst);
            if (!off.isEmpty()) o.put("partsOff", new ArrayList<Object>(off));
            o.put("nodes", names(inst.nodes, naming::node));
            o.put("edges", names(inst.edges, naming::edge));
            if (!inst.links.isEmpty()) o.put("links", names(inst.links, naming::edge));
            insts.add(o);
            instIds.put(inst, id);
        }
        // oldest first: the order of the constraints is their age
        Map<ConstraintGroup, String> groupIds = new IdentityHashMap<>();
        List<Object> groups = new ArrayList<>();
        List<Object> cs = new ArrayList<>();
        for (Constraint c : store.getAllConstraints()) {
            if (c.getNodes() == null || !ConstraintSpecs.supports(c)) continue;
            boolean in = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) in &= g.containsNode(n);
            if (!in) continue;
            ConstraintSpec s;
            try {
                s = ConstraintSpecs.toSpec(c, naming);
            } catch (IllegalArgumentException e) {
                continue;
            }
            s.id = "c" + (cs.size() + 1);
            Map<String, Object> o = TemplateCodec.constraint(s);
            ConstraintOrigin or = c.getOrigin();
            o.put("source", or != null ? "template" : c.isAutomatic() ? "automatic" : "user");
            if (c.isAutomatic()) o.put("automatic", true);
            if (c.getRank() != 0) o.put("rank", (double) c.getRank());
            if (or != null) {
                TemplateInstance inst = InstanceRegistry.byId(or.getInstanceId());
                String iid = inst == null ? null : instIds.get(inst);
                if (iid != null) {
                    o.put("instance", iid);
                    o.put("part", inst.partOf(c));
                } else {
                    Map<String, Object> origin = new LinkedHashMap<>();
                    origin.put("instanceLabel", or.getInstanceLabel());
                    origin.put("templateId", or.getTemplateId());
                    origin.put("revision", (double) or.getTemplateRevision());
                    o.put("origin", origin);
                }
                o.put("path", new ArrayList<Object>(or.getPath()));
                if (or.getSpecId() != null) o.put("specId", or.getSpecId());
            }
            ConstraintGroup gr = c.getGroup();
            if (gr != null) {
                String gid = groupIds.get(gr);
                if (gid == null) {
                    gid = "g" + (groups.size() + 1);
                    Map<String, Object> go = new LinkedHashMap<>();
                    go.put("id", gid);
                    go.put("kind", gr.kind);
                    go.put("label", gr.label);
                    go.put("step", gr.step);
                    groups.add(go);
                    groupIds.put(gr, gid);
                }
                o.put("group", gid);
            }
            cs.add(o);
        }
        doc.put("nodes", nodes);
        doc.put("edges", edges);
        doc.put("groups", groups);
        doc.put("instances", insts);
        doc.put("constraints", cs);
        return doc;
    }

    private static boolean assigned(Graph g, TemplateInstance inst) {
        for (Node n : inst.nodes.values()) if (n == null || !g.containsNode(n)) return false;
        for (Edge e : inst.edges.values()) if (e == null || !g.containsEdge(e)) return false;
        for (Edge e : inst.links.values()) if (e == null || !g.containsEdge(e)) return false;
        return true;
    }

    private static <T> Map<String, Object> names(Map<String, T> m, Function<T, String> name) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (Map.Entry<String, T> e : m.entrySet()) o.put(e.getKey(), name.apply(e.getValue()));
        return o;
    }

    /** The position of {@code e} among the edges of {@code g} with the same source and target. */
    static int indexOf(Graph g, Edge e) {
        int i = 0;
        for (Edge x : g.getEdges()) {
            if (x == e) return i;
            if (x.getSource() == e.getSource() && x.getTarget() == e.getTarget()) i++;
        }
        return i;
    }

    /**
     * Restores the document into {@code store} on {@code g} (adds; nothing is removed, no node moves). Instances are
     * registered; their plans come from {@code templates} (may be null). What cannot be restored is in the report.
     */
    public static Report read(Map<String, Object> doc, Graph g, Constraints store, Elements el, TemplateSource templates) {
        Report r = new Report();
        Object format = doc.get("format");
        if (format != null && !FORMAT.equals(format)) {
            r.problems.add("Not a constraint store (format \"" + format + "\").");
            return r;
        }
        int version = (int) num(doc, "formatVersion", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            r.problems.add("The constraints were saved by a newer version (format " + version + "); this version reads format " + FORMAT_VERSION + ".");
            return r;
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Object o : list(doc, "nodes")) {
            Map<String, Object> m = map(o);
            if (m == null) continue;
            String id = str(m, "id");
            if (id == null) continue;
            Node n = el.node(str(m, "nid"), str(m, "alt"), str(m, "label"));
            if (n == null) r.problems.add("Node \"" + (str(m, "label") != null ? str(m, "label") : id) + "\" is not in the graph.");
            else nodes.put(id, n);
        }
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Object o : list(doc, "edges")) {
            Map<String, Object> m = map(o);
            if (m == null) continue;
            String id = str(m, "id");
            if (id == null) continue;
            Node from = nodes.get(str(m, "from")), to = nodes.get(str(m, "to"));
            Edge e = el.edge(str(m, "eid"), from, to, (int) num(m, "index", 0));
            if (e == null) r.problems.add("Edge " + id + " (" + label(from) + " → " + label(to) + ") is not in the graph.");
            else edges.put(id, e);
        }
        Map<String, ConstraintGroup> groups = new LinkedHashMap<>();
        for (Object o : list(doc, "groups")) {
            Map<String, Object> m = map(o);
            if (m == null || str(m, "id") == null) continue;
            groups.put(str(m, "id"), new ConstraintGroup(str(m, "kind"), str(m, "label"), str(m, "step")));
        }
        Map<String, TemplateInstance> insts = new LinkedHashMap<>();
        for (Object o : list(doc, "instances")) {
            Map<String, Object> m = map(o);
            if (m == null || str(m, "id") == null) continue;
            Map<String, Object> t = map(m.get("template"));
            if (t == null || str(t, "id") == null) {
                r.problems.add("Instance " + str(m, "label") + " names no template.");
                continue;
            }
            TemplateRef ref = new TemplateRef(str(t, "id"), (int) num(t, "revision", 1), str(t, "name") == null ? str(t, "id") : str(t, "name"));
            Map<String, Node> an = new LinkedHashMap<>();
            Map<String, Edge> ae = new LinkedHashMap<>(), al = new LinkedHashMap<>();
            boolean complete = resolve(map(m.get("nodes")), nodes, an);
            complete &= resolve(map(m.get("edges")), edges, ae);
            complete &= resolve(map(m.get("links")), edges, al);
            if (!complete) {
                r.problems.add("Instance " + str(m, "label") + " refers to nodes or edges that are not in the graph.");
                continue;
            }
            List<String> off = new ArrayList<>();
            for (Object p : list(m, "partsOff")) if (p != null) off.add(String.valueOf(p));
            InstancePlan plan = InstanceRestorer.plan(ref, templates);
            // the key names VANTED's session-local element ids: recompute it when the template itself is at hand
            String key = templates != null && templates.get(ref) != null ? InstanceKeys.key(plan, an, ae) : str(m, "key");
            TemplateInstance inst = InstanceRestorer.instance(ref, str(m, "rule"), g, an, ae, al, key == null ? "" : key, plan,
                    bool(m, "on", true), off);
            insts.put(str(m, "id"), inst);
        }
        ConstraintSpecs.Binding binding = new ConstraintSpecs.Binding() {
            @Override public Node node(String id) { return nodes.get(id); }
            @Override public Edge edge(String id) { return edges.get(id); }
            @Override public double[] toTarget(double x, double y) { return new double[]{x, y}; }
        };
        for (Object o : list(doc, "constraints")) {
            Map<String, Object> m = map(o);
            if (m == null) continue;
            if (!(m.get("id") instanceof String)) m.put("id", "c" + (r.constraints + r.skipped + 1));
            ConstraintSpec s;
            try {
                s = TemplateCodec.constraint(m);
            } catch (TemplateCodec.FormatException e) {
                r.problems.add(e.getMessage());
                r.skipped++;
                continue;
            }
            if (s.kind() == null) {
                r.problems.add("Constraint " + s.id + ": unknown type \"" + s.type + "\" (saved by a newer version).");
                r.skipped++;
                continue;
            }
            Constraint c;
            try {
                c = ConstraintSpecs.create(s, binding);
            } catch (RuntimeException e) {
                r.problems.add("Constraint " + s.id + " (" + s.type + "): " + e.getMessage());
                r.skipped++;
                continue;
            }
            c.setAutomatic(bool(m, "automatic", false));
            c.setRank((int) num(m, "rank", 0));
            List<String> path = new ArrayList<>();
            for (Object p : list(m, "path")) if (p != null) path.add(String.valueOf(p));
            TemplateInstance inst = insts.get(str(m, "instance"));
            if (inst != null) {
                InstanceRestorer.attach(inst, c, str(m, "part"), path, str(m, "specId"));
            } else {
                Map<String, Object> origin = map(m.get("origin"));
                if (origin != null) {
                    c.setOrigin(new ConstraintOrigin(null, str(origin, "instanceLabel"), str(origin, "templateId"),
                            (int) num(origin, "revision", 1), path.isEmpty() ? Collections.singletonList(String.valueOf(str(origin, "templateId"))) : path,
                            str(m, "specId"), null));
                }
            }
            ConstraintGroup gr = groups.get(str(m, "group"));
            if (gr != null) gr.add(c);
            store.addConstraint(c);
            r.added.add(c);
            r.constraints++;
        }
        for (ConstraintGroup gr : groups.values()) if (!gr.members().isEmpty()) r.groups++;
        for (TemplateInstance inst : insts.values()) {
            if (inst.constraints().isEmpty()) continue;   // nothing of it could be restored
            InstanceRestorer.register(inst);
            r.restored.add(inst);
            r.instances++;
        }
        if (!r.restored.isEmpty()) InstanceRegistry.fire();
        return r;
    }

    /** Puts the elements {@code names} refers to into {@code into}; false if one of them is missing. */
    private static <T> boolean resolve(Map<String, Object> names, Map<String, T> byId, Map<String, T> into) {
        boolean complete = true;
        if (names != null) for (Map.Entry<String, Object> e : names.entrySet()) {
            T x = byId.get(String.valueOf(e.getValue()));
            if (x == null) complete = false;
            else into.put(e.getKey(), x);
        }
        return complete;
    }

    private static String label(Node n) {
        return n == null ? "?" : AttributeHelper.getLabel(n, "?");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static List<?> list(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof List ? (List<?>) o : Collections.emptyList();
    }

    private static String str(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof String ? (String) o : null;
    }

    private static double num(Map<String, Object> m, String key, double def) {
        Object o = m.get(key);
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object o = m.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }
}
