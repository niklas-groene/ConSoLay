package layout.template.model;

import constraints.spec.ConstraintSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A stored constraint template: where it applies (a pattern graph with semantic conditions, or a composite of other
 * templates joined by structural links), and what it asks for (an explicit list of constraint definitions, and for
 * composites layout relations between the components). The pattern's drawing is also the preview and the starting
 * arrangement; the constraint definitions are what counts for the layout.
 * <p>
 * Identity: a stable {@link #id} (made once, never derived from VANTED ids) and a {@link #revision} counted up with
 * every save. Applied instances keep the revision they were made from.
 * <p>
 * Coordinates: local, see {@link #COORDINATES}.
 *
 * @author Niklas Gröne
 */
public final class TemplateDef {

    /** Version of the file format this code writes (and the newest it reads). */
    public static final int FORMAT_VERSION = 1;

    /** The meaning of the pattern's coordinates, also written into every file. */
    public static final String COORDINATES = "local: origin = top-left corner of the pattern's bounding box when the "
            + "template was made; x to the right, y downwards, in px; node positions are centres. Positions in constraint "
            + "definitions use the same coordinates; applying a template moves them with the instance.";

    public enum Kind { PATTERN, COMPOSITE }

    public String id;
    public int revision = 1;
    public String name = "Untitled template";
    public String description = "";
    public Kind kind = Kind.PATTERN;
    /** How the template was made: "selection", "editor", "gml-import", "example", … (for information). */
    public String source = "editor";
    /** Note shown with the template, e.g. that a GML import has no explicit constraint definitions yet. */
    public String note;
    /** True for a template imported from a GML file: its constraints are still to be added. */
    public boolean importedWithoutConstraints;

    // ---- pattern template
    public final List<PatternNode> nodes = new ArrayList<>();
    public final List<PatternEdge> edges = new ArrayList<>();
    /** The layout definition: constraints on pattern nodes (composites: on qualified ids "c1/n3"). */
    public final List<ConstraintSpec> constraints = new ArrayList<>();
    public final List<Port> ports = new ArrayList<>();

    // ---- composite template
    public final List<ComponentUse> components = new ArrayList<>();
    public final List<StructureLink> links = new ArrayList<>();
    public final List<LayoutRelation> relations = new ArrayList<>();
    /** Templates the components use (and theirs), embedded so the file works on its own; keyed by id@revision. */
    public final Map<String, TemplateDef> dependencies = new LinkedHashMap<>();

    /** Top-level fields of a newer format this version does not know: written back as read (JSON text). */
    public final Map<String, String> unknownFields = new LinkedHashMap<>();

    public TemplateDef() { }

    /** A new, empty template with a fresh id. */
    public static TemplateDef create(String name, Kind kind) {
        TemplateDef t = new TemplateDef();
        t.id = newId();
        t.name = name;
        t.kind = kind;
        return t;
    }

    public static String newId() {
        return "tpl-" + UUID.randomUUID();
    }

    public boolean isComposite() {
        return kind == Kind.COMPOSITE;
    }

    public TemplateRef ref() {
        return TemplateRef.of(this);
    }

    // ---- lookups

    public PatternNode node(String id) {
        for (PatternNode n : nodes) if (n.id.equals(id)) return n;
        return null;
    }

    public PatternEdge edge(String id) {
        for (PatternEdge e : edges) if (e.id.equals(id)) return e;
        return null;
    }

    public ConstraintSpec constraint(String id) {
        for (ConstraintSpec c : constraints) if (c.id.equals(id)) return c;
        return null;
    }

    public Port port(String id) {
        for (Port p : ports) if (p.id.equals(id)) return p;
        return null;
    }

    public Port portByName(String name) {
        for (Port p : ports) if (p.name.equals(name)) return p;
        return null;
    }

    public ComponentUse component(String id) {
        for (ComponentUse c : components) if (c.id.equals(id)) return c;
        return null;
    }

    public StructureLink link(String id) {
        for (StructureLink l : links) if (l.id.equals(id)) return l;
        return null;
    }

    public LayoutRelation relation(String id) {
        for (LayoutRelation r : relations) if (r.id.equals(id)) return r;
        return null;
    }

    public List<String> nodeIds() {
        List<String> l = new ArrayList<>();
        for (PatternNode n : nodes) l.add(n.id);
        return l;
    }

    public List<String> edgeIds() {
        List<String> l = new ArrayList<>();
        for (PatternEdge e : edges) l.add(e.id);
        return l;
    }

    /** The ports that name {@code nodeId}. */
    public List<Port> portsOf(String nodeId) {
        List<Port> l = new ArrayList<>();
        for (Port p : ports) if (nodeId.equals(p.node)) l.add(p);
        return l;
    }

    // ---- fresh local ids

    public String nextNodeId() { return next("n", nodeIds()); }

    public String nextEdgeId() { return next("e", edgeIds()); }

    public String nextConstraintId() {
        List<String> ids = new ArrayList<>();
        for (ConstraintSpec c : constraints) ids.add(c.id);
        return next("k", ids);
    }

    public String nextPortId() {
        List<String> ids = new ArrayList<>();
        for (Port p : ports) ids.add(p.id);
        return next("p", ids);
    }

    public String nextComponentId() {
        List<String> ids = new ArrayList<>();
        for (ComponentUse c : components) ids.add(c.id);
        return next("c", ids);
    }

    public String nextLinkId() {
        List<String> ids = new ArrayList<>();
        for (StructureLink l : links) ids.add(l.id);
        return next("l", ids);
    }

    public String nextRelationId() {
        List<String> ids = new ArrayList<>();
        for (LayoutRelation r : relations) ids.add(r.id);
        return next("r", ids);
    }

    /** "prefix" + the smallest number above every id of that form. */
    static String next(String prefix, Collection<String> ids) {
        int max = 0;
        for (String id : ids) {
            if (id == null || !id.startsWith(prefix)) continue;
            try {
                max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // another naming scheme
            }
        }
        return prefix + (max + 1);
    }

    // ---- geometry

    /** Bounding box {minX, minY, maxX, maxY} of the node boxes (local), or null without nodes. */
    public double[] bounds() {
        if (nodes.isEmpty()) return null;
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (PatternNode n : nodes) {
            b[0] = Math.min(b[0], n.x - n.width / 2);
            b[1] = Math.min(b[1], n.y - n.height / 2);
            b[2] = Math.max(b[2], n.x + n.width / 2);
            b[3] = Math.max(b[3], n.y + n.height / 2);
        }
        return b;
    }

    /** A deep copy (same id and revision): editing it never changes this one. */
    public TemplateDef copy() {
        TemplateDef t = new TemplateDef();
        t.id = id;
        t.revision = revision;
        t.name = name;
        t.description = description;
        t.kind = kind;
        t.source = source;
        t.note = note;
        t.importedWithoutConstraints = importedWithoutConstraints;
        for (PatternNode n : nodes) t.nodes.add(n.copy());
        for (PatternEdge e : edges) t.edges.add(e.copy());
        for (ConstraintSpec c : constraints) t.constraints.add(c.copy());
        for (Port p : ports) t.ports.add(p.copy());
        for (ComponentUse c : components) t.components.add(c.copy());
        for (StructureLink l : links) t.links.add(l.copy());
        for (LayoutRelation r : relations) t.relations.add(r.copy());
        for (Map.Entry<String, TemplateDef> e : dependencies.entrySet()) t.dependencies.put(e.getKey(), e.getValue().copy());
        t.unknownFields.putAll(unknownFields);
        return t;
    }

    @Override
    public String toString() {
        return name;
    }
}
