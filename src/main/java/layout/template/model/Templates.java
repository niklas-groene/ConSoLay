package layout.template.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hierarchy of composite templates: resolving components, the closure of used templates, cycle checks, qualified
 * element ids ("c1/n3" = node n3 of component c1; "c2/c1/n3" one level deeper) and the graph node a port stands for.
 *
 * @author Niklas Gröne
 */
public final class Templates {

    /** Separator of qualified ids. */
    public static final String SEP = "/";
    /** How deep composites may nest. */
    public static final int MAX_DEPTH = 8;

    private Templates() { }

    public static String qualify(String prefix, String id) {
        return prefix == null || prefix.isEmpty() ? id : prefix + SEP + id;
    }

    /** The template a component uses, or null if the source does not have that revision. */
    public static TemplateDef resolve(ComponentUse c, TemplateSource source) {
        return c == null || c.ref == null || source == null ? null : source.get(c.ref);
    }

    /** {@code t} and every template it uses, directly or indirectly (each id@revision once; unresolvable ones skipped). */
    public static List<TemplateDef> closure(TemplateDef t, TemplateSource source) {
        Map<String, TemplateDef> seen = new LinkedHashMap<>();
        collect(t, source, seen, 0);
        return new ArrayList<>(seen.values());
    }

    private static void collect(TemplateDef t, TemplateSource source, Map<String, TemplateDef> seen, int depth) {
        if (t == null || depth > MAX_DEPTH || seen.containsKey(t.id + "@" + t.revision)) return;
        seen.put(t.id + "@" + t.revision, t);
        for (ComponentUse c : t.components) collect(resolve(c, source), source, seen, depth + 1);
    }

    /**
     * The names along a cycle of template uses through {@code t}, e.g. [A, B, A], or null if there is none. Templates
     * count as the same by id (whatever revision): a template can never contain itself.
     */
    public static List<String> findCycle(TemplateDef t, TemplateSource source) {
        List<String> path = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        return cycle(t, source, ids, path);
    }

    private static List<String> cycle(TemplateDef t, TemplateSource source, List<String> ids, List<String> names) {
        if (t == null) return null;
        int at = ids.indexOf(t.id);
        if (at >= 0) {
            List<String> c = new ArrayList<>(names.subList(at, names.size()));
            c.add(t.name);
            return c;
        }
        if (ids.size() > MAX_DEPTH) {
            List<String> c = new ArrayList<>(names);
            c.add(t.name + " (nested more than " + MAX_DEPTH + " levels)");
            return c;
        }
        ids.add(t.id);
        names.add(t.name);
        for (ComponentUse c : t.components) {
            List<String> found = cycle(resolve(c, source), source, ids, names);
            if (found != null) return found;
        }
        ids.remove(ids.size() - 1);
        names.remove(names.size() - 1);
        return null;
    }

    /**
     * Why {@code candidate} cannot become a component of {@code container}, or null if it can: it is the container
     * itself, or it already uses the container (directly or through other templates), which would make an endless
     * nesting.
     */
    public static String whyNotComponent(TemplateDef container, TemplateDef candidate, TemplateSource source) {
        if (candidate == null) return "The template is not available.";
        if (candidate.id.equals(container.id)) return "A template cannot contain itself.";
        List<String> path = pathTo(candidate, container.id, source, new ArrayList<>(), 0);
        if (path != null) {
            return "'" + candidate.name + "' already contains '" + container.name + "' (" + String.join(" → ", path)
                    + "); using it here would nest the templates endlessly.";
        }
        return null;
    }

    private static List<String> pathTo(TemplateDef from, String targetId, TemplateSource source, List<String> names, int depth) {
        if (from == null || depth > MAX_DEPTH) return null;
        names.add(from.name);
        if (from.id.equals(targetId)) return new ArrayList<>(names);
        for (ComponentUse c : from.components) {
            List<String> p = pathTo(resolve(c, source), targetId, source, names, depth + 1);
            if (p != null) return p;
        }
        names.remove(names.size() - 1);
        return null;
    }

    /**
     * The qualified id of the pattern node the port {@code portId} of {@code t} stands for (through composites down to a
     * pattern node), or null if it cannot be resolved.
     */
    public static String portNode(TemplateDef t, String portId, TemplateSource source) {
        return portNode(t, portId, source, 0);
    }

    private static String portNode(TemplateDef t, String portId, TemplateSource source, int depth) {
        if (t == null || depth > MAX_DEPTH) return null;
        Port p = t.port(portId);
        if (p == null) return null;
        if (!t.isComposite()) return p.node != null && t.node(p.node) != null ? p.node : null;
        ComponentUse c = t.component(p.component);
        String inner = portNode(resolve(c, source), p.componentPort, source, depth + 1);
        return inner == null ? null : qualify(c.id, inner);
    }

    /** Every pattern node of {@code t} with its qualified id (a composite: the nodes of all its components). */
    public static Map<String, PatternNode> flatNodes(TemplateDef t, TemplateSource source) {
        Map<String, PatternNode> m = new LinkedHashMap<>();
        flatNodes(t, source, "", m, 0);
        return m;
    }

    private static void flatNodes(TemplateDef t, TemplateSource source, String prefix, Map<String, PatternNode> m, int depth) {
        if (t == null || depth > MAX_DEPTH) return;
        if (!t.isComposite()) {
            for (PatternNode n : t.nodes) m.put(qualify(prefix, n.id), n);
            return;
        }
        for (ComponentUse c : t.components) flatNodes(resolve(c, source), source, qualify(prefix, c.id), m, depth + 1);
    }

    /** Every pattern edge of {@code t} with its qualified id. */
    public static Map<String, PatternEdge> flatEdges(TemplateDef t, TemplateSource source) {
        Map<String, PatternEdge> m = new LinkedHashMap<>();
        flatEdges(t, source, "", m, 0);
        return m;
    }

    private static void flatEdges(TemplateDef t, TemplateSource source, String prefix, Map<String, PatternEdge> m, int depth) {
        if (t == null || depth > MAX_DEPTH) return;
        if (!t.isComposite()) {
            for (PatternEdge e : t.edges) m.put(qualify(prefix, e.id), e);
            return;
        }
        for (ComponentUse c : t.components) flatEdges(resolve(c, source), source, qualify(prefix, c.id), m, depth + 1);
    }

    /** The qualified node ids of one component (all levels below it). */
    public static List<String> componentNodes(TemplateDef composite, String componentId, TemplateSource source) {
        ComponentUse c = composite.component(componentId);
        TemplateDef inner = resolve(c, source);
        if (inner == null) return Collections.emptyList();
        return prefixed(c.id, flatNodes(inner, source).keySet());
    }

    /** The qualified id (relative to {@code composite}) of the node a component's port stands for, or null. */
    static String componentPortNode(TemplateDef composite, String componentId, String portId, TemplateSource source) {
        ComponentUse c = composite.component(componentId);
        String inner = c == null ? null : portNode(resolve(c, source), portId, source);
        return inner == null ? null : qualify(c.id, inner);
    }

    /**
     * The qualified node ids of {@code t} that stand for one graph node because {@link StructureLink.Type#SAME_NODE}
     * links join them (at every level): each such id → the same id of its group. Ids that share no node are absent.
     */
    public static Map<String, String> sharedNodes(TemplateDef t, TemplateSource source) {
        Groups g = new Groups();
        sharedNodes(t, source, "", g, 0);
        return g.all();
    }

    private static void sharedNodes(TemplateDef t, TemplateSource source, String prefix, Groups g, int depth) {
        if (t == null || !t.isComposite() || depth > MAX_DEPTH) return;
        for (StructureLink l : t.links) {
            if (l.type != StructureLink.Type.SAME_NODE) continue;
            String a = componentPortNode(t, l.fromComponent, l.fromPort, source);
            String b = componentPortNode(t, l.toComponent, l.toPort, source);
            if (a != null && b != null) g.union(qualify(prefix, a), qualify(prefix, b));
        }
        for (ComponentUse c : t.components) sharedNodes(resolve(c, source), source, qualify(prefix, c.id), g, depth + 1);
    }

    /**
     * The dividing lines a relation between components ({@link LayoutRelation.Type#onComponents()}) stands for, with
     * the qualified node ids on either side. A node that both sides share (through {@link StructureLink.Type#SAME_NODE}
     * links) lies on neither side, as no line can run through it; a line without nodes on one side is left out.
     */
    public static List<LayoutRelation.Divide> divides(TemplateDef t, LayoutRelation r, TemplateSource source) {
        List<LayoutRelation.Divide> l = new ArrayList<>();
        Map<String, String> shared = sharedNodes(t, source);
        switch (r.type) {
            case LEFT_OF:
            case ROW:
            case ABOVE:
            case COLUMN: {
                boolean vertical = r.type == LayoutRelation.Type.LEFT_OF || r.type == LayoutRelation.Type.ROW;
                for (int i = 0; i + 1 < r.components.size(); i++) {
                    addDivide(l, String.valueOf(i), vertical, componentNodes(t, r.components.get(i), source),
                            componentNodes(t, r.components.get(i + 1), source), shared);
                }
                break;
            }
            case GRID: {
                int cols = Math.max(1, r.columns), n = r.components.size();
                int rows = (n + cols - 1) / cols;
                for (int c = 0; c + 1 < cols; c++) {
                    List<String> left = new ArrayList<>(), right = new ArrayList<>();
                    for (int row = 0; row < rows; row++) {
                        int a = row * cols + c, b = row * cols + c + 1;
                        if (a < n) left.addAll(componentNodes(t, r.components.get(a), source));
                        if (b < n) right.addAll(componentNodes(t, r.components.get(b), source));
                    }
                    addDivide(l, "c" + c, true, left, right, shared);
                }
                for (int row = 0; row + 1 < rows; row++) {
                    List<String> top = new ArrayList<>(), bottom = new ArrayList<>();
                    for (int c = 0; c < cols; c++) {
                        int a = row * cols + c, b = (row + 1) * cols + c;
                        if (a < n) top.addAll(componentNodes(t, r.components.get(a), source));
                        if (b < n) bottom.addAll(componentNodes(t, r.components.get(b), source));
                    }
                    addDivide(l, "r" + row, false, top, bottom, shared);
                }
                break;
            }
            default:
                break;
        }
        return l;
    }

    private static void addDivide(List<LayoutRelation.Divide> l, String key, boolean vertical, List<String> before,
                                  List<String> after, Map<String, String> shared) {
        Set<String> a = new HashSet<>(), b = new HashSet<>();
        for (String q : before) a.add(shared.getOrDefault(q, q));
        for (String q : after) b.add(shared.getOrDefault(q, q));
        List<String> x = new ArrayList<>(), y = new ArrayList<>();
        for (String q : before) if (!b.contains(shared.getOrDefault(q, q))) x.add(q);
        for (String q : after) if (!a.contains(shared.getOrDefault(q, q))) y.add(q);
        if (!x.isEmpty() && !y.isEmpty()) l.add(new LayoutRelation.Divide(key, vertical, x, y));
    }

    /** Groups of ids (union-find): ids put together with {@link #union} end up in one group. */
    static final class Groups {
        private final Map<String, String> parent = new HashMap<>();

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(rb, ra);
        }

        /** The id that stands for the group of {@code id} ({@code id} itself if it was never joined). */
        String find(String id) {
            String r = id;
            for (String p = parent.get(r); p != null && !p.equals(r); p = parent.get(r)) r = p;
            if (!r.equals(id)) parent.put(id, r);
            return r;
        }

        /** Every id that was joined → the id of its group. */
        Map<String, String> all() {
            Map<String, String> m = new LinkedHashMap<>();
            for (String id : new ArrayList<>(parent.keySet())) {
                m.put(id, find(id));
                m.putIfAbsent(find(id), find(id));
            }
            return m;
        }
    }

    private static List<String> prefixed(String prefix, Set<String> ids) {
        List<String> l = new ArrayList<>();
        for (String id : ids) l.add(qualify(prefix, id));
        return l;
    }

    /** The highest revision among {@code templates} of the template with this id, or null. */
    static TemplateDef newest(Iterable<TemplateDef> templates, String id) {
        TemplateDef best = null;
        for (TemplateDef d : templates) {
            if (d.id.equals(id) && (best == null || d.revision > best.revision)) best = d;
        }
        return best;
    }
}
