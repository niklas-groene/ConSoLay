package layout.template.apply;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.Constraints;
import constraints.spec.ConstraintSpecs;
import layout.template.search.MatchValidator;
import layout.template.search.SearchTask;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies templates to places of a graph: the stored constraint definitions go through the assignment onto the graph
 * (positions moved into the instance's frame), the constraints join the store with their origin, optionally the
 * assigned nodes are placed like the template, and then the layout is solved once for the whole batch with the
 * add-on's solver and conflict handling. Only the assigned nodes are placed directly and only the bends of the
 * template's own edges between them are removed (what else moves is up to the solve), and an application that already
 * exists (same template revision, same constraints on the same nodes) is skipped. The GUI turns the {@link Result}
 * into one undoable edit ({@link InstanceEdit}).
 *
 * @author Niklas Gröne
 */
public final class TemplateApplier {

    private TemplateApplier() { }

    /** One place to apply a template to. */
    public static final class Request {
        public final SearchTask task;
        public final Map<String, Node> nodes;
        public final Map<String, Edge> edges;
        public final Map<String, Edge> links;
        /** Shown in messages, e.g. "#12 Chain". */
        public final String title;

        public Request(SearchTask task, Map<String, Node> nodes, Map<String, Edge> edges, Map<String, Edge> links, String title) {
            this.task = task;
            this.nodes = nodes;
            this.edges = edges;
            this.links = links;
            this.title = title;
        }
    }

    /** What an application did. */
    public static final class Result {
        public final List<TemplateInstance> made = new ArrayList<>();
        /** Requests left out, with the reason (already applied, no longer fits, …). */
        public final List<String> skipped = new ArrayList<>();
        /** Requests left out because an equal instance exists, with that instance. */
        public final Map<Request, TemplateInstance> duplicates = new IdentityHashMap<>();
        public int constraints;
        public long createNanos, placeNanos, solveNanos, totalNanos;
        /** Positions of every node before the application and after it (for undo). */
        public Map<Node, double[]> before, after;
        /** Bends removed from the template's edges (for undo). */
        public final Map<Edge, List<Vector2d>> bendsRemoved = new LinkedHashMap<>();
    }

    /**
     * Applies the requests on {@code g} as one change.
     *
     * @param store the constraint store the constraints join
     * @param place place the assigned nodes like the template before solving
     * @param solve the solve of the graph (the GUI's user solve); runs once, after all constraints are added
     */
    public static Result apply(Graph g, List<Request> requests, Constraints store, boolean place, Runnable solve) {
        long t0 = System.nanoTime();
        Result r = new Result();
        r.before = positions(g);
        Set<String> keys = new HashSet<>();
        List<TemplateInstance> made = new ArrayList<>();
        Map<TemplateInstance, Map<String, double[]>> offsets = new IdentityHashMap<>();
        for (Request q : requests) {
            String problem = MatchValidator.missing(g, q.nodes, q.edges, q.links);
            if (problem != null) {
                r.skipped.add(q.title + ": " + problem);
                continue;
            }
            String key = InstanceKeys.key(q.task.plan, q.nodes, q.edges);
            TemplateInstance existing = InstanceRegistry.find(g, key);
            if (existing != null || !keys.add(key)) {
                r.skipped.add(q.title + ": already applied" + (existing == null ? " in this step" : " (" + existing.label() + ")"));
                if (existing != null) r.duplicates.put(q, existing);
                continue;
            }
            TemplateInstance inst = new TemplateInstance(q.task.template.ref(), q.task.ruleName, g, q.nodes, q.edges, q.links, key, q.task.plan);
            Map<String, double[]> off = offsets(q.task.plan, q.nodes);
            try {
                for (Built b : instantiate(q.task.plan, q.nodes, q.edges, off)) {
                    InstanceRestorer.attach(inst, b.constraint, b.item.part, b.item.path, b.item.spec.id);
                }
            } catch (IllegalArgumentException e) {
                r.skipped.add(q.title + ": " + e.getMessage());
                continue;
            }
            made.add(inst);
            offsets.put(inst, off);
        }
        r.createNanos = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        if (place && !made.isEmpty()) {
            g.getListenerManager().transactionStarted(TemplateApplier.class);
            try {
                for (TemplateInstance inst : made) place(inst, offsets.get(inst), r.bendsRemoved);
            } finally {
                g.getListenerManager().transactionFinished(TemplateApplier.class);
            }
        }
        r.placeNanos = System.nanoTime() - t1;
        for (TemplateInstance inst : made) {
            for (Constraint c : inst.constraints()) store.addConstraint(c);
            InstanceRegistry.add(inst);
            r.constraints += inst.constraints().size();
        }
        r.made.addAll(made);
        long t2 = System.nanoTime();
        if (!made.isEmpty() && solve != null) solve.run();
        r.solveNanos = System.nanoTime() - t2;
        r.after = positions(g);
        r.totalNanos = System.nanoTime() - t0;
        if (!made.isEmpty()) InstanceRegistry.fire();
        return r;
    }

    /** A made constraint with the plan item it comes from. */
    public static final class Built {
        public final Constraint constraint;
        public final InstancePlan.Item item;

        Built(Constraint constraint, InstancePlan.Item item) {
            this.constraint = constraint;
            this.item = item;
        }
    }

    /**
     * The constraints of a plan on the assigned elements (without origin; nothing is added anywhere). Positions go
     * through the offset of the item's pattern part.
     *
     * @throws IllegalArgumentException if a definition cannot be made (unknown type, element not assigned)
     */
    public static List<Built> instantiate(InstancePlan plan, Map<String, Node> nodes, Map<String, Edge> edges,
                                          Map<String, double[]> offsets) {
        List<Built> l = new ArrayList<>();
        for (InstancePlan.Item item : plan.items) {
            double[] off = offsets.getOrDefault(item.positionPart, new double[]{0, 0});
            Constraint c = ConstraintSpecs.create(item.spec, new ConstraintSpecs.Binding() {
                @Override public Node node(String id) { return nodes.get(id); }
                @Override public Edge edge(String id) { return edges.get(id); }
                @Override public double[] toTarget(double x, double y) { return new double[]{x + off[0], y + off[1]}; }
            });
            l.add(new Built(c, item));
        }
        return l;
    }

    /**
     * Per pattern part: the shift from the template's local coordinates into the graph, chosen so that the part's nodes
     * keep their current centroid (graph centroid of the assigned nodes minus local centroid).
     */
    public static Map<String, double[]> offsets(InstancePlan plan, Map<String, Node> nodes) {
        Map<String, double[]> m = new HashMap<>();
        for (Map.Entry<String, List<String>> part : plan.patternParts.entrySet()) {
            double lx = 0, ly = 0, gx = 0, gy = 0;
            int k = 0;
            for (String q : part.getValue()) {
                Node n = nodes.get(q);
                double[] p = plan.positions.get(q);
                if (n == null || p == null) continue;
                lx += p[0];
                ly += p[1];
                gx += AttributeHelper.getPositionX(n);
                gy += AttributeHelper.getPositionY(n);
                k++;
            }
            m.put(part.getKey(), k == 0 ? new double[]{0, 0} : new double[]{(gx - lx) / k, (gy - ly) / k});
        }
        return m;
    }

    /** Puts the instance's nodes where the template has them (shifted by the part offsets); removes their edges' bends. */
    private static void place(TemplateInstance inst, Map<String, double[]> offsets, Map<Edge, List<Vector2d>> bendsRemoved) {
        Set<Node> moved = new HashSet<>();
        for (Map.Entry<String, Node> e : inst.nodes.entrySet()) {
            double[] p = inst.plan.positions.get(e.getKey());
            String part = inst.plan.partOfNode.getOrDefault(e.getKey(), "");
            double[] off = offsets.getOrDefault(part, new double[]{0, 0});
            if (p == null) continue;
            AttributeHelper.setPosition(e.getValue(), p[0] + off[0], p[1] + off[1]);
            moved.add(e.getValue());
        }
        for (Edge e : inst.edges.values()) {
            if (!moved.contains(e.getSource()) || !moved.contains(e.getTarget())) continue;
            List<Vector2d> bends = AttributeHelper.getEdgeBends(e);
            if (bends == null || bends.isEmpty()) continue;
            if (!bendsRemoved.containsKey(e)) bendsRemoved.put(e, new ArrayList<>(bends));
            AttributeHelper.removeEdgeBends(e);
        }
    }

    /** Centre of every node of {@code g}. */
    public static Map<Node, double[]> positions(Graph g) {
        Map<Node, double[]> m = new IdentityHashMap<>();
        for (Node n : g.getNodes()) m.put(n, new double[]{AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)});
        return m;
    }

    // ================================================================== instances: switch, remove

    /**
     * Switches an instance (or one of its component parts) on or off. The constraints keep their own switches; only the
     * effective state changes. Returns the constraints whose effective state changed.
     */
    public static List<Constraint> setOn(TemplateInstance inst, String part, boolean on) {
        Map<Constraint, Boolean> before = new IdentityHashMap<>();
        for (Constraint c : inst.constraints()) before.put(c, c.isEnabled());
        if (part == null || part.isEmpty()) inst.setOn(on);
        else inst.setPartOn(part, on);
        List<Constraint> changed = new ArrayList<>();
        for (Constraint c : inst.constraints()) {
            if (before.get(c) != c.isEnabled()) changed.add(c);
        }
        InstanceRegistry.fire();
        return changed;
    }

    /** Removes an instance and exactly its own constraints from the store; returns them. */
    public static List<Constraint> remove(TemplateInstance inst, Constraints store) {
        List<Constraint> gone = unapply(inst, store);
        InstanceRegistry.fire();
        return gone;
    }

    /** Puts a removed instance back (undo), with its constraints. */
    static void restore(TemplateInstance inst, List<Constraint> constraints, Constraints store) {
        for (Constraint c : constraints) if (!store.getAllConstraints().contains(c)) store.addConstraint(c);
        if (InstanceRegistry.byId(inst.id) == null) InstanceRegistry.add(inst);
    }

    /** Takes an instance and its constraints out of the store and the registry without notifying; returns them. */
    static List<Constraint> unapply(TemplateInstance inst, Constraints store) {
        List<Constraint> gone = inst.present(store);
        for (Constraint c : gone) store.removeConstraint(c);
        InstanceRegistry.remove(inst);
        ConflictLog.forget(gone);
        return gone;
    }
}
