package layout.template.model;

import constraints.ConstraintKind;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a template before it is saved or applied and says what is wrong in plain sentences, naming the element (the
 * node, constraint, port, component or link). Three levels: an {@link Severity#ERROR} blocks saving and applying (a
 * reference to a deleted node, a missing value, a cycle of components); {@link Severity#NOT_APPLICABLE} allows saving
 * without loss but blocks applying (a constraint type of a newer version); a {@link Severity#WARNING} blocks nothing.
 *
 * @author Niklas Gröne
 */
public final class TemplateValidator {

    public enum Severity { ERROR, NOT_APPLICABLE, WARNING }

    /** One finding. */
    public static final class Problem {
        public final Severity severity;
        /** What it is about, e.g. "Constraint k3 (Align top edges)" or "Port 'Input'". */
        public final String where;
        public final String message;
        /** Local id of the element (node, constraint, port, component, link or relation), or null. */
        public final String elementId;

        Problem(Severity severity, String where, String message, String elementId) {
            this.severity = severity;
            this.where = where;
            this.message = message;
            this.elementId = elementId;
        }

        @Override
        public String toString() {
            return (where == null ? "" : where + ": ") + message;
        }
    }

    private TemplateValidator() { }

    public static boolean canSave(List<Problem> problems) {
        for (Problem p : problems) if (p.severity == Severity.ERROR) return false;
        return true;
    }

    public static boolean canApply(List<Problem> problems) {
        for (Problem p : problems) if (p.severity != Severity.WARNING) return false;
        return true;
    }

    /** Every finding of {@code t} (with its components, resolved through {@code source}). */
    public static List<Problem> validate(TemplateDef t, TemplateSource source) {
        List<Problem> p = new ArrayList<>();
        validate(t, source, p, "", 0);
        return p;
    }

    private static void validate(TemplateDef t, TemplateSource source, List<Problem> p, String prefix, int depth) {
        if (t.id == null || t.id.trim().isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Template", "has no id.", null));
        if (t.name == null || t.name.trim().isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Template", "has no name.", null));
        if (t.isComposite()) validateComposite(t, source, p, prefix, depth);
        else validatePattern(t, p, prefix);
    }

    // ---------------------------------------------------------------- pattern templates

    private static void validatePattern(TemplateDef t, List<Problem> p, String prefix) {
        if (t.nodes.isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Pattern", "has no nodes.", null));
        Set<String> ids = new HashSet<>();
        for (PatternNode n : t.nodes) {
            String where = prefix + "Node '" + n.displayName() + "'";
            if (n.id == null || n.id.isEmpty()) p.add(new Problem(Severity.ERROR, where, "has no id.", null));
            else if (!ids.add(n.id)) p.add(new Problem(Severity.ERROR, where, "has the same id as another node (" + n.id + ").", n.id));
            if (!Double.isFinite(n.x) || !Double.isFinite(n.y)) p.add(new Problem(Severity.ERROR, where, "has no valid position.", n.id));
            if (!(n.width > 0) || !(n.height > 0)) p.add(new Problem(Severity.ERROR, where, "needs a width and height above 0.", n.id));
            checkConditions(n.conditions, where, n.id, p);
        }
        Set<String> edgeIds = new HashSet<>();
        for (PatternEdge e : t.edges) {
            String where = prefix + "Edge " + e.id;
            if (e.id == null || !edgeIds.add(e.id)) p.add(new Problem(Severity.ERROR, where, "has a missing or repeated id.", e.id));
            if (t.node(e.source) == null || t.node(e.target) == null)
                p.add(new Problem(Severity.ERROR, where, "joins a node that is not in the pattern (deleted?).", e.id));
            checkConditions(e.conditions, where, e.id, p);
        }
        Set<String> kIds = new HashSet<>();
        List<String> nodeIds = t.nodeIds(), eIds = t.edgeIds();
        for (ConstraintSpec c : t.constraints) {
            String where = prefix + "Constraint " + c.id + (c.kind() == null ? "" : " (" + ConstraintSpecs.title(c.kind()) + ")");
            if (c.id == null || !kIds.add(c.id)) p.add(new Problem(Severity.ERROR, where, "has a missing or repeated id.", c.id));
            if (!ConstraintSpecs.isKnown(c.type)) {
                p.add(new Problem(Severity.NOT_APPLICABLE, where, "has the type '" + c.type + "' of a newer version: it is kept "
                        + "unchanged when saving, but the template cannot be applied here.", c.id));
                continue;
            }
            for (String msg : ConstraintSpecs.validate(c, nodeIds, eIds)) p.add(new Problem(Severity.ERROR, where, msg, c.id));
        }
        checkPorts(t, p, prefix);
        int parts = components(t);
        if (parts > 1) p.add(new Problem(Severity.WARNING, prefix + "Pattern", "has " + parts + " separate parts: a match may "
                + "combine parts anywhere in the graph, and searches take longer.", null));
        if (t.importedWithoutConstraints && t.constraints.isEmpty())
            p.add(new Problem(Severity.WARNING, prefix + "Template", "was imported from a GML file and has no constraint "
                    + "definitions yet: applying it only places the nodes. Add constraints in the editor.", null));
    }

    private static void checkPorts(TemplateDef t, List<Problem> p, String prefix) {
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Port port : t.ports) {
            String where = prefix + "Port '" + port.name + "'";
            if (port.id == null || !ids.add(port.id)) p.add(new Problem(Severity.ERROR, where, "has a missing or repeated id.", port.id));
            if (port.name == null || port.name.trim().isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Port " + port.id, "has no name.", port.id));
            else if (!names.add(port.name.trim())) p.add(new Problem(Severity.ERROR, where, "has the same name as another port.", port.id));
            if (!t.isComposite() && (port.node == null || t.node(port.node) == null))
                p.add(new Problem(Severity.ERROR, where, "refers to a node that is not in the pattern (deleted?).", port.id));
        }
    }

    private static void checkConditions(List<Condition> cs, String where, String id, List<Problem> p) {
        Set<String> attrs = new HashSet<>();
        for (Condition c : cs) {
            if (c.attribute == null || c.attribute.trim().isEmpty()) {
                p.add(new Problem(Severity.ERROR, where, "has a condition without an attribute.", id));
                continue;
            }
            if (!attrs.add(c.attribute)) p.add(new Problem(Severity.ERROR, where, "has two conditions on '" + c.attribute + "'.", id));
            if (c.op == Condition.Op.EQUALS && (c.values.isEmpty() || c.values.get(0) == null))
                p.add(new Problem(Severity.ERROR, where, "condition on '" + c.attribute + "' needs a value.", id));
            if (c.op == Condition.Op.IN_LIST && c.values.isEmpty())
                p.add(new Problem(Severity.ERROR, where, "condition on '" + c.attribute + "' needs at least one value.", id));
        }
    }

    /** Number of weakly connected parts of the pattern graph. */
    static int components(TemplateDef t) {
        Map<String, List<String>> adj = new HashMap<>();
        for (PatternNode n : t.nodes) adj.put(n.id, new ArrayList<>());
        for (PatternEdge e : t.edges) {
            if (!adj.containsKey(e.source) || !adj.containsKey(e.target)) continue;
            adj.get(e.source).add(e.target);
            adj.get(e.target).add(e.source);
        }
        Set<String> seen = new HashSet<>();
        int parts = 0;
        for (String start : adj.keySet()) {
            if (!seen.add(start)) continue;
            parts++;
            Deque<String> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) for (String m : adj.get(q.poll())) if (seen.add(m)) q.add(m);
        }
        return parts;
    }

    // ---------------------------------------------------------------- composites

    private static void validateComposite(TemplateDef t, TemplateSource source, List<Problem> p, String prefix, int depth) {
        List<String> cycle = Templates.findCycle(t, source);
        if (cycle != null) {
            p.add(new Problem(Severity.ERROR, prefix + "Components", "form a cycle: " + String.join(" → ", cycle)
                    + ". A template cannot contain itself, directly or through others.", null));
            return;
        }
        if (t.components.isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Composite", "has no components yet.", null));
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        Map<String, TemplateDef> resolved = new HashMap<>();
        for (ComponentUse c : t.components) {
            String where = prefix + "Component '" + c.name + "'";
            if (c.id == null || !ids.add(c.id)) p.add(new Problem(Severity.ERROR, where, "has a missing or repeated id.", c.id));
            if (c.name == null || c.name.trim().isEmpty()) p.add(new Problem(Severity.ERROR, prefix + "Component " + c.id, "has no name.", c.id));
            else if (!names.add(c.name.trim())) p.add(new Problem(Severity.ERROR, where, "has the same name as another component; "
                    + "give each use its own role.", c.id));
            TemplateDef inner = Templates.resolve(c, source);
            if (inner == null) {
                p.add(new Problem(Severity.ERROR, where, "uses " + (c.ref == null ? "no template" : "'" + c.ref + "'")
                        + ", which is not available.", c.id));
                continue;
            }
            resolved.put(c.id, inner);
            if (depth < Templates.MAX_DEPTH) validate(inner, source, p, prefix + c.name + " › ", depth + 1);
            for (String k : c.disabledConstraints) {
                if (inner.constraint(k) == null && !inner.isComposite())
                    p.add(new Problem(Severity.WARNING, where, "switches off constraint " + k + ", which its template no longer has.", c.id));
            }
        }
        for (StructureLink l : t.links) {
            String where = prefix + "Link " + l.id + " (" + portText(t, resolved, l.fromComponent, l.fromPort) + " → "
                    + portText(t, resolved, l.toComponent, l.toPort) + ")";
            if (l.type == StructureLink.Type.PATH) {
                p.add(new Problem(Severity.NOT_APPLICABLE, where, "is a path condition, which this version cannot search for yet.", l.id));
            }
            checkPortRef(t, resolved, l.fromComponent, l.fromPort, where, l.id, p, source);
            checkPortRef(t, resolved, l.toComponent, l.toPort, where, l.id, p, source);
            if (l.fromComponent != null && l.fromComponent.equals(l.toComponent))
                p.add(new Problem(Severity.ERROR, where, "joins a component with itself; links join two different components.", l.id));
            checkConditions(l.edgeConditions, where, l.id, p);
        }
        for (LayoutRelation r : t.relations) {
            String where = prefix + "Layout relation " + r.id + " (" + r.type.label() + ")";
            if (r.type.onComponents()) {
                int need = r.type == LayoutRelation.Type.LEFT_OF || r.type == LayoutRelation.Type.ABOVE ? 2 : 0;
                if (need == 2 && r.components.size() != 2) p.add(new Problem(Severity.ERROR, where, "needs exactly two components.", r.id));
                if (need == 0 && r.components.size() < 2) p.add(new Problem(Severity.ERROR, where, "needs at least two components.", r.id));
                Set<String> seen = new HashSet<>();
                for (String c : r.components) {
                    if (t.component(c) == null) p.add(new Problem(Severity.ERROR, where, "refers to a component that was removed.", r.id));
                    if (!seen.add(c)) p.add(new Problem(Severity.ERROR, where, "names the same component twice.", r.id));
                }
                if (r.type == LayoutRelation.Type.GRID && r.columns < 1) p.add(new Problem(Severity.ERROR, where, "needs at least one column.", r.id));
            } else {
                if (r.ports.size() < 2) p.add(new Problem(Severity.ERROR, where, "needs at least two ports.", r.id));
                for (LayoutRelation.PortRef pr : r.ports) checkPortRef(t, resolved, pr.component, pr.port, where, r.id, p, source);
            }
            if (!Double.isFinite(r.gap) || r.gap < 0) p.add(new Problem(Severity.ERROR, where, "needs a distance of at least 0 px.", r.id));
            String against = r.enabled && r.type.onComponents() ? contradiction(t, r, source) : null;
            if (against != null) p.add(new Problem(Severity.WARNING, where, against, r.id));
        }
        for (Port port : t.ports) {
            String where = prefix + "Port '" + port.name + "'";
            checkPortRef(t, resolved, port.component, port.componentPort, where, port.id, p, source);
        }
        checkPorts(t, p, prefix);
        // Constraints of the composite itself, on qualified node ids of its components.
        List<String> nodeIds = new ArrayList<>(Templates.flatNodes(t, source).keySet());
        List<String> edgeIds = new ArrayList<>(Templates.flatEdges(t, source).keySet());
        Set<String> kIds = new HashSet<>();
        for (ConstraintSpec c : t.constraints) {
            String where = prefix + "Constraint " + c.id;
            if (c.id == null || !kIds.add(c.id)) p.add(new Problem(Severity.ERROR, where, "has a missing or repeated id.", c.id));
            if (!ConstraintSpecs.isKnown(c.type)) {
                p.add(new Problem(Severity.NOT_APPLICABLE, where, "has the type '" + c.type + "' of a newer version.", c.id));
                continue;
            }
            for (String msg : ConstraintSpecs.validate(c, nodeIds, edgeIds)) p.add(new Problem(Severity.ERROR, where, msg, c.id));
        }
        // Components that no link joins may match anywhere: say so.
        if (t.components.size() > 1) {
            Set<String> linked = new HashSet<>();
            for (StructureLink l : t.links) { linked.add(l.fromComponent); linked.add(l.toComponent); }
            for (ComponentUse c : t.components) {
                if (!linked.contains(c.id)) p.add(new Problem(Severity.WARNING, prefix + "Component '" + c.name + "'",
                        "is not linked to another component: any occurrence anywhere combines with the others.", c.id));
            }
        }
    }

    /**
     * Why the relation {@code r} between components cannot hold together with the constraints the template makes, or
     * null: one of its dividing lines would have to pass between two nodes that those constraints keep with their
     * centres on one line across it (e.g. rows joined at a shared node, and a relation that puts one below the other).
     */
    private static String contradiction(TemplateDef t, LayoutRelation r, TemplateSource source) {
        Templates.Groups[] lines = new Templates.Groups[2];
        for (LayoutRelation.Divide d : Templates.divides(t, r, source)) {
            int axis = d.vertical ? 0 : 1;
            if (lines[axis] == null) lines[axis] = centreLines(t, source, d.vertical);
            Map<String, String> before = new HashMap<>();
            for (String q : d.before) before.putIfAbsent(lines[axis].find(q), q);
            for (String q : d.after) {
                String a = before.get(lines[axis].find(q));
                if (a == null) continue;
                return "cannot hold: the constraints keep nodes of '" + componentName(t, a) + "' and of '"
                        + componentName(t, q) + "' on one " + (d.vertical ? "vertical" : "horizontal") + " line"
                        + (shareNode(t, source, a, q) ? ", joined at the node they share" : "")
                        + ", so no line can separate them.";
            }
        }
        return null;
    }

    /** Groups of qualified node ids whose centres the constraints of {@code t} keep on one vertical (horizontal) line. */
    private static Templates.Groups centreLines(TemplateDef t, TemplateSource source, boolean vertical) {
        Templates.Groups g = new Templates.Groups();
        for (Map.Entry<String, String> e : Templates.sharedNodes(t, source).entrySet()) g.union(e.getValue(), e.getKey());
        centreLines(t, source, "", true, Collections.emptySet(), vertical, g, 0);
        return g;
    }

    /** Joins the nodes that {@code t} (as a use with these settings) aligns, as applying it would make them. */
    private static void centreLines(TemplateDef t, TemplateSource source, String prefix, boolean withConstraints,
                                    Set<String> off, boolean vertical, Templates.Groups g, int depth) {
        if (t == null || depth > Templates.MAX_DEPTH) return;
        for (ComponentUse c : t.components) {
            centreLines(Templates.resolve(c, source), source, Templates.qualify(prefix, c.id),
                    withConstraints && c.includeConstraints, c.disabledConstraints, vertical, g, depth + 1);
        }
        if (!withConstraints) return;
        for (ConstraintSpec c : t.constraints) {
            if (!c.enabled || off.contains(c.id)) continue;
            String first = null;
            for (String id : onOneLine(c, vertical)) {
                if (id == null) continue;
                if (first == null) first = Templates.qualify(prefix, id);
                else g.union(first, Templates.qualify(prefix, id));
            }
        }
        LayoutRelation.Type ports = vertical ? LayoutRelation.Type.ALIGN_PORTS_V : LayoutRelation.Type.ALIGN_PORTS_H;
        for (LayoutRelation r : t.relations) {
            if (!r.enabled || off.contains(r.id) || r.type != ports) continue;
            String first = null;
            for (LayoutRelation.PortRef pr : r.ports) {
                String n = Templates.componentPortNode(t, pr.component, pr.port, source);
                if (n == null) continue;
                if (first == null) first = Templates.qualify(prefix, n);
                else g.union(first, Templates.qualify(prefix, n));
            }
        }
    }

    /** The nodes {@code c} puts with their centres on one vertical (or horizontal) line. */
    private static List<String> onOneLine(ConstraintSpec c, boolean vertical) {
        ConstraintKind k = c.kind();
        if (k == null) return Collections.emptyList();
        switch (k) {
            case ALIGN_CENTER: return vertical ? c.role("nodes") : Collections.emptyList();
            case ALIGN_MIDDLE: return vertical ? Collections.emptyList() : c.role("nodes");
            case COLUMN: return vertical ? c.role("ordered") : Collections.emptyList();
            case ROW: return vertical ? Collections.emptyList() : c.role("ordered");
            case EDGE_VERTICAL: return vertical ? c.role("ends") : Collections.emptyList();
            case EDGE_HORIZONTAL: return vertical ? Collections.emptyList() : c.role("ends");
            default: return Collections.emptyList();
        }
    }

    /** The name of the component a qualified node id lies in. */
    private static String componentName(TemplateDef t, String qualified) {
        int at = qualified.indexOf(Templates.SEP);
        ComponentUse c = t.component(at < 0 ? qualified : qualified.substring(0, at));
        return c == null ? qualified : c.name;
    }

    /** True if the components of the two qualified ids share a node (a {@link StructureLink.Type#SAME_NODE} link). */
    private static boolean shareNode(TemplateDef t, TemplateSource source, String a, String b) {
        String ca = a.split(Templates.SEP)[0], cb = b.split(Templates.SEP)[0];
        Map<String, Set<String>> byGroup = new HashMap<>();
        for (Map.Entry<String, String> e : Templates.sharedNodes(t, source).entrySet()) {
            byGroup.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(e.getKey().split(Templates.SEP)[0]);
        }
        for (Set<String> comps : byGroup.values()) if (comps.contains(ca) && comps.contains(cb)) return true;
        return false;
    }

    private static String portText(TemplateDef t, Map<String, TemplateDef> resolved, String component, String port) {
        ComponentUse c = t.component(component);
        TemplateDef inner = resolved.get(component);
        Port p = inner == null ? null : inner.port(port);
        return (c == null ? "?" : c.name) + "." + (p == null ? port : p.name);
    }

    private static void checkPortRef(TemplateDef t, Map<String, TemplateDef> resolved, String component, String port,
                                     String where, String id, List<Problem> p, TemplateSource source) {
        ComponentUse c = t.component(component);
        if (c == null) {
            p.add(new Problem(Severity.ERROR, where, "refers to a component that was removed.", id));
            return;
        }
        TemplateDef inner = resolved.get(component);
        if (inner == null) return;   // reported with the component
        Port pt = inner.port(port);
        if (pt == null) {
            p.add(new Problem(Severity.ERROR, where, "uses a port of '" + c.name + "' that '" + inner.name + "' (rev. "
                    + inner.revision + ") no longer has (deleted?). Choose another port or remove the rule.", id));
        } else if (Templates.portNode(inner, port, source) == null) {
            p.add(new Problem(Severity.ERROR, where, "uses the port '" + pt.name + "' of '" + c.name + "', whose node was deleted.", id));
        }
    }
}
