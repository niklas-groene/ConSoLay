package layout.template.apply;

import constraints.ConstraintKind;
import constraints.Priority;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.model.ComponentUse;
import layout.template.model.LayoutRelation;
import layout.template.model.PatternNode;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What applying a template makes, before any graph is involved: every constraint definition of the template and of its
 * components (all levels, with the settings of each component use), the layout relations of composites turned into
 * constraint definitions, all on qualified node ids ("c1/n3"), each with the path it comes from. Also the local
 * positions of the nodes, grouped by the pattern they belong to ("part"), for placing a match like the template.
 * <p>
 * A plan is immutable once built and may be read by a background search (to tell apart matches that lead to different
 * applications).
 *
 * @author Niklas Gröne
 */
public final class InstancePlan {

    /** One constraint the application makes. */
    public static final class Item {
        /** The definition on qualified ids (a copy; priority and switch after the component settings). */
        public final ConstraintSpec spec;
        /** E.g. [Rule set, Source, Align centres]. */
        public final List<String> path;
        /** The component path it belongs to ("" for the template itself, "c1", "c1/c2"). */
        public final String part;
        /** The pattern part whose placement offset converts its positions ("" or a component path). */
        public final String positionPart;

        Item(ConstraintSpec spec, List<String> path, String part, String positionPart) {
            this.spec = spec;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.part = part;
            this.positionPart = positionPart;
        }
    }

    /** A named part (component use) of the template, for switching it on and off as a whole. */
    public static final class Part {
        public final String key;
        public final String name;
        /** Template name of the part. */
        public final String templateName;

        Part(String key, String name, String templateName) {
            this.key = key;
            this.name = name;
            this.templateName = templateName;
        }
    }

    public final TemplateDef template;
    public final List<Item> items;
    /** Qualified node id → local position {x, y} in its pattern. */
    public final Map<String, double[]> positions;
    /** Pattern part ("" or component path) → its qualified node ids. */
    public final Map<String, List<String>> patternParts;
    /** Qualified node id → its pattern part. */
    public final Map<String, String> partOfNode;
    /** Every component use (all levels) by key. */
    public final Map<String, Part> parts;

    private InstancePlan(TemplateDef template, List<Item> items, Map<String, double[]> positions,
                         Map<String, List<String>> patternParts, Map<String, String> partOfNode, Map<String, Part> parts) {
        this.template = template;
        this.items = Collections.unmodifiableList(items);
        this.positions = Collections.unmodifiableMap(positions);
        this.patternParts = Collections.unmodifiableMap(patternParts);
        this.partOfNode = Collections.unmodifiableMap(partOfNode);
        this.parts = Collections.unmodifiableMap(parts);
    }

    /**
     * Builds the plan of {@code t}; components are resolved through {@code source}. {@code priority}: a rule's
     * priority for every constraint, or null to keep theirs.
     */
    public static InstancePlan build(TemplateDef t, TemplateSource source, Priority priority) {
        List<Item> items = new ArrayList<>();
        Map<String, double[]> positions = new LinkedHashMap<>();
        Map<String, List<String>> patternParts = new LinkedHashMap<>();
        Map<String, String> partOfNode = new LinkedHashMap<>();
        Map<String, Part> parts = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        path.add(t.name);
        collect(t, source, "", path, true, null, Collections.emptySet(), items, positions, patternParts, partOfNode, parts, 0);
        if (priority != null) for (Item i : items) i.spec.priority = priority;
        return new InstancePlan(t, items, positions, patternParts, partOfNode, parts);
    }

    private static void collect(TemplateDef t, TemplateSource source, String prefix, List<String> path, boolean withConstraints,
                                Priority priority, Set<String> disabled, List<Item> items, Map<String, double[]> positions,
                                Map<String, List<String>> patternParts, Map<String, String> partOfNode, Map<String, Part> parts,
                                int depth) {
        if (t == null || depth > Templates.MAX_DEPTH) return;
        if (!t.isComposite()) {
            List<String> ids = new ArrayList<>();
            for (PatternNode n : t.nodes) {
                String q = Templates.qualify(prefix, n.id);
                positions.put(q, new double[]{n.x, n.y});
                partOfNode.put(q, prefix);
                ids.add(q);
            }
            patternParts.put(prefix, ids);
            if (withConstraints) {
                for (ConstraintSpec c : t.constraints) items.add(item(c, prefix, path, prefix, priority, disabled.contains(c.id), prefix));
            }
            return;
        }
        for (ComponentUse c : t.components) {
            TemplateDef inner = Templates.resolve(c, source);
            String key = Templates.qualify(prefix, c.id);
            parts.put(key, new Part(key, c.name, inner == null ? "?" : inner.name));
            List<String> p = new ArrayList<>(path);
            p.add(c.name);
            collect(inner, source, key, p, withConstraints && c.includeConstraints, c.priority != null ? c.priority : priority,
                    c.disabledConstraints, items, positions, patternParts, partOfNode, parts, depth + 1);
        }
        if (!withConstraints) return;
        for (ConstraintSpec c : t.constraints) {
            String first = null;
            for (String r : c.refs()) { first = Templates.qualify(prefix, r); break; }
            String posPart = first == null ? prefix : partOfNode.getOrDefault(first, prefix);
            items.add(item(c, prefix, path, prefix, priority, disabled.contains(c.id), posPart));
        }
        for (LayoutRelation r : t.relations) {
            for (ConstraintSpec s : relationSpecs(t, r, source)) {
                List<String> p = new ArrayList<>(path);
                p.add("Layout: " + r.describe(id -> {
                    ComponentUse u = t.component(id);
                    return u == null ? id : u.name;
                }));
                ConstraintSpec q = qualified(s, prefix);
                if (priority != null) q.priority = priority;
                if (disabled.contains(r.id)) q.enabled = false;
                items.add(new Item(q, p, prefix, prefix));
            }
        }
    }

    private static Item item(ConstraintSpec c, String prefix, List<String> path, String part, Priority priority, boolean off,
                             String positionPart) {
        ConstraintSpec q = qualified(c, prefix);
        if (priority != null) q.priority = priority;
        if (off) q.enabled = false;
        List<String> p = new ArrayList<>(path);
        p.add(c.name != null && !c.name.trim().isEmpty() ? c.name.trim()
                : c.kind() == null ? c.type : ConstraintSpecs.title(c.kind()));
        return new Item(q, p, part, positionPart);
    }

    /** A copy of {@code c} with every element id prefixed. */
    static ConstraintSpec qualified(ConstraintSpec c, String prefix) {
        ConstraintSpec q = c.copy();
        if (prefix == null || prefix.isEmpty()) return q;
        for (String r : new ArrayList<>(q.refs())) q.renameRef(r, Templates.qualify(prefix, r));
        return q;
    }

    /**
     * The constraint definitions a layout relation stands for, on the qualified ids relative to {@code t}. Relations
     * between components become dividing lines between the node sets of the components (half the gap on each side of
     * the line, so the bounding boxes end up at least the gap apart); port alignments align the centres of the port
     * nodes.
     */
    public static List<ConstraintSpec> relationSpecs(TemplateDef t, LayoutRelation r, TemplateSource source) {
        List<ConstraintSpec> l = new ArrayList<>();
        String id = "rel-" + r.id;
        switch (r.type) {
            case LEFT_OF:
            case ROW:
                for (int i = 0; i + 1 < r.components.size(); i++) {
                    l.add(divide(id + "-" + i, ConstraintKind.DIVIDE_X, nodes(t, r.components.get(i), source),
                            nodes(t, r.components.get(i + 1), source), r));
                }
                break;
            case ABOVE:
            case COLUMN:
                for (int i = 0; i + 1 < r.components.size(); i++) {
                    l.add(divide(id + "-" + i, ConstraintKind.DIVIDE_Y, nodes(t, r.components.get(i), source),
                            nodes(t, r.components.get(i + 1), source), r));
                }
                break;
            case GRID: {
                int cols = Math.max(1, r.columns), n = r.components.size();
                int rows = (n + cols - 1) / cols;
                for (int c = 0; c + 1 < cols; c++) {
                    List<String> left = new ArrayList<>(), right = new ArrayList<>();
                    for (int row = 0; row < rows; row++) {
                        int a = row * cols + c, b = row * cols + c + 1;
                        if (a < n) left.addAll(nodes(t, r.components.get(a), source));
                        if (b < n) right.addAll(nodes(t, r.components.get(b), source));
                    }
                    if (!left.isEmpty() && !right.isEmpty()) l.add(divide(id + "-c" + c, ConstraintKind.DIVIDE_X, left, right, r));
                }
                for (int row = 0; row + 1 < rows; row++) {
                    List<String> top = new ArrayList<>(), bottom = new ArrayList<>();
                    for (int c = 0; c < cols; c++) {
                        int a = row * cols + c, b = (row + 1) * cols + c;
                        if (a < n) top.addAll(nodes(t, r.components.get(a), source));
                        if (b < n) bottom.addAll(nodes(t, r.components.get(b), source));
                    }
                    if (!top.isEmpty() && !bottom.isEmpty()) l.add(divide(id + "-r" + row, ConstraintKind.DIVIDE_Y, top, bottom, r));
                }
                break;
            }
            case ALIGN_PORTS_H:
            case ALIGN_PORTS_V: {
                List<String> ns = new ArrayList<>();
                for (LayoutRelation.PortRef p : r.ports) {
                    ComponentUse c = t.component(p.component);
                    String inner = Templates.portNode(Templates.resolve(c, source), p.port, source);
                    if (c != null && inner != null) ns.add(Templates.qualify(c.id, inner));
                }
                boolean h = r.type == LayoutRelation.Type.ALIGN_PORTS_H;
                ConstraintSpec s = new ConstraintSpec(id, h ? ConstraintKind.ALIGN_MIDDLE : ConstraintKind.ALIGN_CENTER);
                s.role("nodes", new ArrayList<>(new LinkedHashSet<>(ns)));
                s.param("axis", h ? "y" : "x").param("anchor", h ? "middle" : "centre");
                s.priority = r.priority;
                s.enabled = r.enabled;
                l.add(s);
                break;
            }
            default:
                break;
        }
        return l;
    }

    private static ConstraintSpec divide(String id, ConstraintKind kind, List<String> before, List<String> after, LayoutRelation r) {
        ConstraintSpec s = new ConstraintSpec(id, kind);
        s.role("before", before).role("after", after);
        s.param("axis", kind == ConstraintKind.DIVIDE_X ? "x" : "y").param("gap", r.gap / 2);
        s.priority = r.priority;
        s.enabled = r.enabled;
        return s;
    }

    private static List<String> nodes(TemplateDef t, String component, TemplateSource source) {
        return Templates.componentNodes(t, component, source);
    }

    /** The parts' names from the template down to {@code part}, e.g. [Source] or [Pair, Source]. */
    public List<String> partNames(String part) {
        List<String> l = new ArrayList<>();
        if (part == null || part.isEmpty()) return l;
        String[] segs = part.split(Templates.SEP);
        StringBuilder key = new StringBuilder();
        for (String s : segs) {
            if (key.length() > 0) key.append(Templates.SEP);
            key.append(s);
            Part p = parts.get(key.toString());
            l.add(p == null ? s : p.name);
        }
        return l;
    }

    /** Number of constraints the plan makes. */
    public int size() {
        return items.size();
    }
}
