package gui.template;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintCheck;
import constraints.ConstraintKind;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.io.TemplateLibrary;
import layout.template.model.ComponentUse;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;

import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State of the template editor: its own copy of the template (the library changes only on saving), selection and undo
 * history, plus a private VANTED graph ({@link #graph()}) with the constraint objects made from the definitions, so that
 * the constraint tools and the solver work on the pattern without touching the main graph. The definitions are
 * authoritative; the graph and the constraint objects are rebuilt from them after each change.
 *
 * @author Niklas Gröne
 */
final class EditorModel {

    /** The template being edited (the editor's own copy). */
    private TemplateDef def;
    final TemplateLibrary library;
    private final Deque<TemplateDef> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Deque<String> undoNames = new ArrayDeque<>(), redoNames = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    /** Constraint id → why it changed or became invalid with the last edit (shown next to it; not saved). */
    final Map<String, String> notes = new LinkedHashMap<>();
    /** Latest message for the status line. */
    String message;
    private boolean dirty;

    final Set<String> selNodes = new LinkedHashSet<>();
    final Set<String> selEdges = new LinkedHashSet<>();
    String selConstraint;
    /** The constraint was chosen last, not a node or edge: the properties show it first. */
    boolean constraintFirst;
    String selComponent, selLink, selRelation;
    /** Key and end of the changes merged into one undo step, see {@link #changeMerging}. */
    private String mergeKey;
    private long mergeUntil;

    private Graph graph;
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, Edge> edges = new HashMap<>();
    private final Map<Node, String> nodeIds = new IdentityHashMap<>();
    private final Map<Edge, String> edgeIds = new IdentityHashMap<>();
    private final Map<String, Constraint> constraints = new LinkedHashMap<>();
    private final Map<String, String> buildErrors = new HashMap<>();
    private List<TemplateValidator.Problem> problems = new ArrayList<>();
    /** Constraint id → why it does not hold in the drawing (after a solve or a move). */
    final Map<String, String> unmet = new HashMap<>();

    EditorModel(TemplateDef def, TemplateLibrary library) {
        this.def = def;
        this.library = library;
        reserveKnownIds();
        rebuild();
    }

    /** Keeps the local ids of every known revision of the template from being handed out again (see lastIds). */
    private void reserveKnownIds() {
        def.reserveIdsOf(def);
        for (TemplateDef d : def.dependencies.values()) if (def.id != null && def.id.equals(d.id)) def.reserveIdsOf(d);
        if (library == null || def.id == null) return;
        for (TemplateDef t : library.templates()) {
            if (def.id.equals(t.id)) def.reserveIdsOf(t);
            for (TemplateDef d : t.dependencies.values()) if (def.id.equals(d.id)) def.reserveIdsOf(d);
        }
    }

    TemplateDef def() {
        return def;
    }

    TemplateSource source() {
        return TemplateSource.chain(library, TemplateSource.embeddedIn(def));
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void fire() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    boolean isDirty() {
        return dirty;
    }

    void setClean() {
        dirty = false;
        fire();
    }

    /** One undoable change: {@code change} mutates {@link #def()}; everything derived follows. */
    void change(String name, Runnable change) {
        beginStep(name);
        while (undo.size() > 200) {
            undo.removeLast();
            undoNames.removeLast();
        }
        notes.clear();
        change.run();
        endStep();
    }

    /**
     * {@link #change} for a value stepped repeatedly: changes with the same {@code key} less than a second apart (the
     * arrows of a spinner, the arrow keys) make one undo step.
     */
    void changeMerging(String key, String name, Runnable change) {
        long now = System.currentTimeMillis();
        if (key.equals(mergeKey) && now < mergeUntil && canUndo()) {
            notes.clear();
            change.run();
            endStep();
        } else {
            change(name, change);
        }
        mergeKey = key;
        mergeUntil = now + 1000;
    }

    /** Starts a change whose effects come in several steps (a drag): call {@link #endStep} at its end. */
    void beginStep(String name) {
        undo.push(def.copy());
        undoNames.push(name);
        redo.clear();
        redoNames.clear();
        mergeKey = null;
        message = null;
    }

    void endStep() {
        dirty = true;
        rebuild();
        fire();
    }

    boolean canUndo() { return !undo.isEmpty(); }

    boolean canRedo() { return !redo.isEmpty(); }

    String undoName() { return undoNames.peek(); }

    String redoName() { return redoNames.peek(); }

    void undo() {
        if (undo.isEmpty()) return;
        TemplateDef now = def;
        redo.push(def.copy());
        redoNames.push(undoNames.pop());
        def = undo.pop();
        keepIdentity(now);
        afterJump();
    }

    void redo() {
        if (redo.isEmpty()) return;
        TemplateDef now = def;
        undo.push(def.copy());
        undoNames.push(redoNames.pop());
        def = redo.pop();
        keepIdentity(now);
        afterJump();
    }

    /**
     * Undo and redo change the content only: the identity (id, revision, how it was made; set by saving) stays that of
     * {@code now}, and no id handed out before becomes free again. So a save after an undo makes the next revision.
     */
    private void keepIdentity(TemplateDef now) {
        def.id = now.id;
        def.revision = now.revision;
        def.source = now.source;
        def.reserveIdsOf(now);
    }

    private void afterJump() {
        mergeKey = null;
        notes.clear();
        dirty = true;
        selNodes.retainAll(def.nodeIds());
        selEdges.retainAll(def.edgeIds());
        if (selConstraint != null && def.constraint(selConstraint) == null) selConstraint = null;
        if (selComponent != null && def.component(selComponent) == null) selComponent = null;
        if (selLink != null && def.link(selLink) == null) selLink = null;
        if (selRelation != null && def.relation(selRelation) == null) selRelation = null;
        rebuild();
        fire();
    }

    /** Replaces the template (after "Save as copy", or navigation back to it). */
    void replace(TemplateDef t) {
        def = t;
        rebuild();
        fire();
    }

    /** Rebuilds the private graph, the constraint objects and the problem list from the definitions. */
    void rebuild() {
        graph = new AdjListGraph();
        nodes.clear();
        edges.clear();
        nodeIds.clear();
        edgeIds.clear();
        if (!def.isComposite()) {
            for (PatternNode p : def.nodes) {
                Node n = graph.addNode();
                AttributeHelper.setPosition(n, p.x, p.y);
                AttributeHelper.setSize(n, p.width, p.height);
                AttributeHelper.setLabel(n, p.displayName());
                if (p.role != null && !p.role.isEmpty()) AttributeHelper.setAttribute(n, "sbgn", "role", p.role);
                nodes.put(p.id, n);
                nodeIds.put(n, p.id);
            }
            for (PatternEdge e : def.edges) {
                Node a = nodes.get(e.source), b = nodes.get(e.target);
                if (a == null || b == null) continue;
                Edge x = graph.addEdge(a, b, e.directed);
                edges.put(e.id, x);
                edgeIds.put(x, e.id);
            }
        }
        rebuildConstraints();
        problems = TemplateValidator.validate(def, source());
    }

    private void rebuildConstraints() {
        constraints.clear();
        buildErrors.clear();
        if (def.isComposite()) return;
        List<String> nodeIdList = def.nodeIds(), edgeIdList = def.edgeIds();
        for (ConstraintSpec s : def.constraints) {
            List<String> v = ConstraintSpecs.validate(s, nodeIdList, edgeIdList);
            if (!v.isEmpty()) {
                buildErrors.put(s.id, v.get(0));
                continue;
            }
            try {
                constraints.put(s.id, ConstraintSpecs.create(s, binding()));
            } catch (IllegalArgumentException e) {
                buildErrors.put(s.id, e.getMessage());
            }
        }
    }

    Graph graph() {
        return graph;
    }

    Node node(String id) {
        return nodes.get(id);
    }

    Edge edge(String id) {
        return edges.get(id);
    }

    /** The constraint object of a valid definition, or null. */
    Constraint constraint(String id) {
        return constraints.get(id);
    }

    Collection<Constraint> constraintObjects() {
        return constraints.values();
    }

    /** Why the definition cannot be made (first problem), or null. */
    String buildError(String id) {
        return buildErrors.get(id);
    }

    List<TemplateValidator.Problem> problems() {
        return problems;
    }

    /** Local-coordinate naming for the editor graph (identity coordinates). */
    ConstraintSpecs.Naming naming() {
        return new ConstraintSpecs.Naming() {
            @Override public String node(Node n) { return nodeIds.get(n); }
            @Override public String edge(Edge e) { return edgeIds.get(e); }
            @Override public double[] toLocal(double x, double y) { return new double[]{x, y}; }
        };
    }

    ConstraintSpecs.Binding binding() {
        return new ConstraintSpecs.Binding() {
            @Override public Node node(String id) { return nodes.get(id); }
            @Override public Edge edge(String id) { return edges.get(id); }
            @Override public double[] toTarget(double x, double y) { return new double[]{x, y}; }
        };
    }

    ConstraintSpecs.Geometry geometry() {
        return new ConstraintSpecs.Geometry() {
            @Override public double[] position(String id) {
                PatternNode n = def.node(id);
                return n == null ? null : new double[]{n.x, n.y};
            }

            @Override public double[] size(String id) {
                PatternNode n = def.node(id);
                return n == null ? null : new double[]{n.width, n.height};
            }
        };
    }

    /** Writes the (edited) constraint object back into its definition, keeping id, name, switch and priority. */
    void writeBack(String id, Constraint c) {
        ConstraintSpec old = def.constraint(id);
        if (old == null) return;
        ConstraintSpec s = ConstraintSpecs.toSpec(c, naming());
        s.id = old.id;
        s.name = old.name;
        s.enabled = old.enabled;
        s.priority = old.priority;
        int i = def.constraints.indexOf(old);
        def.constraints.set(i, s);
    }

    /** Adds constraints made on the editor graph (e.g. by the catalogue) as definitions; returns their ids. */
    List<String> addConstraints(List<Constraint> made) {
        List<String> ids = new ArrayList<>();
        for (Constraint c : made) {
            ConstraintSpec s = ConstraintSpecs.toSpec(c, naming());
            s.id = def.nextConstraintId();
            def.constraints.add(s);
            ids.add(s.id);
        }
        return ids;
    }

    /**
     * Deletes pattern nodes and edges; the constraints that refer to them lose them (and say so), those that become
     * invalid stay in the list with the reason. Ports on deleted nodes are removed.
     */
    void deleteElements(Collection<String> nodeIdsToDelete, Collection<String> edgeIdsToDelete) {
        Set<String> gone = new LinkedHashSet<>(nodeIdsToDelete);
        Set<String> goneEdges = new LinkedHashSet<>(edgeIdsToDelete);
        for (PatternEdge e : def.edges) if (gone.contains(e.source) || gone.contains(e.target)) goneEdges.add(e.id);
        Map<String, String> names = new HashMap<>();
        for (String id : gone) {
            PatternNode n = def.node(id);
            if (n != null) names.put(id, "node '" + n.displayName() + "'");
        }
        for (String id : goneEdges) names.put(id, "edge " + id);
        def.nodes.removeIf(n -> gone.contains(n.id));
        def.edges.removeIf(e -> goneEdges.contains(e.id));
        def.ports.removeIf(p -> gone.contains(p.node));
        List<String> nodeIdList = def.nodeIds(), edgeIdList = def.edgeIds();
        for (ConstraintSpec s : def.constraints) {
            List<String> removed = new ArrayList<>();
            for (String r : new ArrayList<>(s.refs())) {
                if (!names.containsKey(r)) continue;
                removed.add(names.get(r));
                removeRef(s, r);
            }
            if (removed.isEmpty()) continue;
            List<String> v = ConstraintSpecs.validate(s, nodeIdList, edgeIdList);
            String what = String.join(", ", removed);
            notes.put(s.id, v.isEmpty() ? "Updated: " + what + " was deleted and left it."
                    : "Invalid: " + what + " was deleted. " + v.get(0) + " Add elements or delete the constraint.");
        }
        selNodes.removeAll(gone);
        selEdges.removeAll(goneEdges);
        int updated = 0, invalid = 0;
        for (String n : notes.values()) if (n.startsWith("Invalid")) invalid++; else updated++;
        message = (gone.size() + goneEdges.size()) + " elements deleted" + (updated > 0 ? "; " + updated + " constraints updated" : "")
                + (invalid > 0 ? "; " + invalid + " constraints are invalid now (see the list)" : "") + ".";
    }

    /**
     * Takes the element {@code id} out of {@code s} as the constraint objects do when a node goes: a grid keeps its
     * cell (empty), a star's angles follow their ring nodes, every other role closes up.
     */
    static void removeRef(ConstraintSpec s, String id) {
        for (Map.Entry<String, List<String>> e : s.roles.entrySet()) {
            List<String> l = e.getValue();
            if (s.kind() == ConstraintKind.GRID && "cells".equals(e.getKey())) {
                l.replaceAll(x -> id.equals(x) ? null : x);
            } else if (s.kind() == ConstraintKind.STAR && "ring".equals(e.getKey())) {
                for (int i = l.indexOf(id); i >= 0; i = l.indexOf(id)) removeRingNode(s, i);
            } else {
                l.removeIf(id::equals);
            }
        }
        for (Object v : s.params.values()) if (v instanceof Map) ((Map<?, ?>) v).remove(id);
    }

    /** Removes the {@code i}-th node of a star's ring together with its angle. */
    static void removeRingNode(ConstraintSpec s, int i) {
        List<String> ring = s.roles.get("ring");
        if (ring == null || i < 0 || i >= ring.size()) return;
        ring.remove(i);
        Object a = s.params.get("angles");
        if (a instanceof double[] && i < ((double[]) a).length) {
            double[] d = (double[]) a, rest = new double[d.length - 1];
            System.arraycopy(d, 0, rest, 0, i);
            System.arraycopy(d, i + 1, rest, i, d.length - i - 1);
            s.params.put("angles", rest);
        }
    }

    /** Ports of the pattern that name a node of {@code ids}. */
    List<Port> portsOn(Collection<String> ids) {
        List<Port> l = new ArrayList<>();
        for (Port p : def.ports) if (ids.contains(p.node)) l.add(p);
        return l;
    }

    /**
     * Solves the editor's constraints on its private graph (the main graph, its store and the conflict log stay
     * untouched) and writes the positions back into the definitions; returns the ids of the constraints that do not hold
     * afterwards, with the reason.
     */
    Map<String, String> solve(boolean avoidOverlaps) {
        unmet.clear();
        if (def.isComposite() || constraints.isEmpty()) return unmet;
        syncPositions();
        List<Constraint> cs = new ArrayList<>(constraints.values());
        Map<Constraint, String> result;
        try {
            result = CBLayoutAlgorithm.solveUserConstraints(graph, cs, false, avoidOverlaps, false);
        } catch (Throwable t) {
            message = "The solver is not available here: " + t.getMessage();
            return unmet;
        }
        for (PatternNode p : def.nodes) {
            Node n = nodes.get(p.id);
            if (n == null) continue;
            p.x = AttributeHelper.getPositionX(n);
            p.y = AttributeHelper.getPositionY(n);
        }
        Map<Constraint, String> idsBack = new IdentityHashMap<>();
        for (Map.Entry<String, Constraint> e : constraints.entrySet()) idsBack.put(e.getValue(), e.getKey());
        for (Map.Entry<Constraint, String> e : result.entrySet()) {
            String id = idsBack.get(e.getKey());
            if (id != null) unmet.put(id, e.getValue());
        }
        return unmet;
    }

    /** Puts the private graph's nodes where the definitions have them (during a drag without solving). */
    void syncPositions() {
        for (PatternNode p : def.nodes) {
            Node n = nodes.get(p.id);
            if (n != null) AttributeHelper.setPosition(n, p.x, p.y);
        }
    }

    /** Measures without solving which constraints do not hold in the current drawing. */
    Map<String, String> measure() {
        unmet.clear();
        syncPositions();
        Set<Node> all = new LinkedHashSet<>(nodes.values());
        for (Map.Entry<String, Constraint> e : constraints.entrySet()) {
            if (!e.getValue().isEnabled()) continue;
            String why = ConstraintCheck.violation(e.getValue(), all, ConstraintCheck.TOLERANCE);
            if (why != null) unmet.put(e.getKey(), why);
        }
        return unmet;
    }

    /** The first port of a component's template, or null if it has none. */
    String firstPort(String component) {
        TemplateDef inner = Templates.resolve(def.component(component), source());
        return inner == null || inner.ports.isEmpty() ? null : inner.ports.get(0).id;
    }

    /** "Component.Port" for lists and hints. */
    String portName(String component, String port) {
        ComponentUse c = def.component(component);
        TemplateDef inner = Templates.resolve(c, source());
        Port p = inner == null ? null : inner.port(port);
        return (c == null ? "?" : c.name) + "." + (p == null ? port + " (missing)" : p.name);
    }

    /** The definitions that name pattern node {@code id}. */
    List<ConstraintSpec> constraintsOf(String id) {
        List<ConstraintSpec> l = new ArrayList<>();
        for (ConstraintSpec s : def.constraints) if (s.refs().contains(id)) l.add(s);
        return l;
    }
}
