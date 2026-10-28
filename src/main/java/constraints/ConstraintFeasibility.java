package constraints;

import org.graffiti.graph.Node;

import java.util.*;

/**
 * Keeps the registered constraint set feasible before it reaches the solver. Per dimension the alignment classes are
 * contracted to one vertex and every separation with a positive gap becomes an arc: the set is feasible exactly when
 * both quotient graphs are acyclic; two nodes that share an x-class and a y-class would coincide. Both are detected in
 * near-linear time and resolved by dropping an automatic constraint involved (user constraints: see
 * {@link #enforce(Constraints, boolean)}), because VPSC never rejects an equality and drops separations by processing
 * order, which collapses the drawing.
 */
public final class ConstraintFeasibility {

    /** A constraint removed by {@link #enforce}, with the reason. */
    public static final class Dropped {
        public final Constraint constraint;
        public final String reason;
        public Dropped(Constraint constraint, String reason) { this.constraint = constraint; this.reason = reason; }
        @Override public String toString() { return reason + ": " + constraint; }
    }

    /** Contradictions among user constraints found by the last call; kept, but the solver will not satisfy all of them. */
    public static final List<String> lastWarnings = new ArrayList<>();

    /** The user constraints each of {@link #lastWarnings} names, by warning. */
    public static final Map<String, List<Constraint>> lastWarningConstraints = new HashMap<>();

    /**
     * -Dcblayout.feasPriority=class|age (default age): class drops the automatic constraint of the lowest
     * {@link Constraint#getRank rank} on a cycle, the youngest among equals, and also offers the alignments joining the
     * two nodes of a self-loop; age drops the youngest automatic constraint on the cycle (never an alignment).
     */
    public static boolean BY_CLASS = "class".equalsIgnoreCase(System.getProperty("cblayout.feasPriority", "age"));

    /** User constraints the last live {@link #enforce(Constraints, boolean)} did not apply (see there). */
    public static final List<Constraint> lastDeactivated = new ArrayList<>();

    private ConstraintFeasibility() { }

    /** {@link #enforce(Constraints, boolean)} on a copy: nothing in it is deactivated, a losing user constraint is taken out. */
    public static List<Dropped> enforce(Constraints constraints) {
        return enforce(constraints, false);
    }

    /**
     * Drops automatic constraints until the set is feasible (see the class comment): of the automatic constraints the
     * guard can drop on a contradiction, one of the lowest priority. The one exception follows the priorities: a user
     * constraint whose {@link Priority} is below that of every step constraint the guard could drop on the cycle loses
     * to them. With {@code live} ({@code constraints} is the store) it is then deactivated, kept with its settings and
     * its reason ({@link #lastDeactivated}); on a copy it is taken out like a dropped one. With the default priorities
     * the user's constraints always win.
     */
    public static List<Dropped> enforce(Constraints constraints, boolean live) {
        List<Dropped> dropped = new ArrayList<>();
        List<Constraint> deactivated = new ArrayList<>();
        // A user constraint deactivated for a step constraint that a later cycle drops is free again: decide once more.
        for (int round = 0; round < 3; round++) {
            dropped.addAll(enforceOnce(constraints, live));
            deactivated.addAll(lastDeactivated);
            if (!live || lastDeactivated.isEmpty() || !ConstraintConflicts.forgetStale(constraints.getAllConstraints())) break;
        }
        lastDeactivated.clear();
        for (Constraint c : deactivated) if (c.getDeactivation() != null && !lastDeactivated.contains(c)) lastDeactivated.add(c);
        return dropped;
    }

    private static List<Dropped> enforceOnce(Constraints constraints, boolean live) {
        lastDeactivated.clear();
        List<Dropped> dropped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<Constraint>> named = new HashMap<>();
        Set<String> warned = new HashSet<>();
        for (int guard = 0; guard < 10000; guard++) {
            Model m = Model.build(constraints);
            boolean changed = false;
            for (int dim = 0; dim < 2 && !changed; dim++) {
                List<Constraint> cycle = m.findCycle(dim);
                if (cycle == null) continue;
                // A gap between two nodes of one alignment class comes as a cycle of that gap alone: the alignments
                // joining the nodes belong to it when priorities (and, with feasPriority=class, ranks) are compared.
                List<Constraint> whole = cycle;
                if (cycle.size() == 1 && cycle.get(0).getNodes().size() == 2) {
                    List<Node> ns = cycle.get(0).getNodes();
                    List<Constraint> path = m.alignmentPath(dim, ns.get(0), ns.get(1));
                    if (path != null) { whole = new ArrayList<>(cycle); whole.addAll(path); }
                }
                // what the guard may drop: with feasPriority=class any automatic constraint, else no alignment
                List<Constraint> droppable = automatic(BY_CLASS ? whole : cycle);
                Constraint weak = weakerUser(whole, droppable);
                if (weak != null) {
                    String why = (dim == 0 ? "x" : "y") + " contradiction with step constraints of a higher priority";
                    if (live) {
                        List<Constraint> others = new ArrayList<>();
                        for (Constraint o : whole) if (ConstraintConflicts.RANK.compare(o, weak) < 0) others.add(o);
                        weak.setDeactivation(new Deactivation(weak, others, dim == 0 ? Axis.X : Axis.Y, Double.NaN));
                        lastDeactivated.add(weak);
                    } else {
                        constraints.removeConstraint(weak);
                        dropped.add(new Dropped(weak, why));
                    }
                    changed = true;
                    continue;
                }
                List<Constraint> lowest = ofLowestPriority(droppable);
                Constraint victim = BY_CLASS ? lowestRanked(lowest) : latestAutomatic(lowest);
                if (victim != null) {
                    constraints.removeConstraint(victim);
                    dropped.add(new Dropped(victim, (dim == 0 ? "x" : "y") + " contradiction: separations and alignments form a cycle"));
                    changed = true;
                } else if (warned.add(dim + ":" + ids(cycle))) {
                    String w = "User constraints contradict each other (" + (dim == 0 ? "x" : "y") + "): " + cycle;
                    warnings.add(w);
                    named.put(w, users(cycle));
                }
            }
            if (changed) continue;
            Node[] pair = m.findCoincidence();
            if (pair != null) {
                Constraint victim = resolveCoincidence(constraints, pair);
                if (victim != null) {
                    dropped.add(new Dropped(victim, "Nodes would coincide: " + pair[0] + " and " + pair[1]));
                    continue;
                }
                if (warned.add("coincide:" + pair[0].getID() + ":" + pair[1].getID())) {
                    String w = "User alignments make " + pair[0] + " and " + pair[1] + " coincide";
                    warnings.add(w);
                    List<Constraint> joining = new ArrayList<>();
                    for (int dim = 0; dim < 2; dim++) {
                        List<Constraint> path = m.alignmentPath(dim, pair[0], pair[1]);
                        if (path != null) joining.addAll(path);
                    }
                    named.put(w, users(joining));
                }
                // nothing droppable: the remaining coincidences are left to the solver
            }
            break;
        }
        lastWarnings.clear();
        lastWarnings.addAll(warnings);
        lastWarningConstraints.clear();
        lastWarningConstraints.putAll(named);
        return dropped;
    }

    private static List<Constraint> users(List<Constraint> cs) {
        List<Constraint> l = new ArrayList<>();
        for (Constraint c : cs) if (!c.isAutomatic() && !l.contains(c)) l.add(c);
        return l;
    }

    private static String ids(List<Constraint> cs) {
        StringBuilder sb = new StringBuilder();
        for (Constraint c : cs) sb.append(c.getId()).append(',');
        return sb.toString();
    }

    /**
     * The lowest ranked user constraint of {@code cycle} if its priority is below that of every constraint of
     * {@code droppable} (if none can be dropped: below that of a step constraint on the cycle), else null.
     */
    private static Constraint weakerUser(List<Constraint> cycle, List<Constraint> droppable) {
        Priority bar = null;
        if (droppable.isEmpty()) {
            for (Constraint c : cycle) {
                if (c.isAutomatic() && (bar == null || c.getPriority().compareTo(bar) > 0)) bar = c.getPriority();
            }
        } else {
            for (Constraint c : droppable) if (bar == null || c.getPriority().compareTo(bar) < 0) bar = c.getPriority();
        }
        if (bar == null) return null;
        Constraint weakest = null;
        for (Constraint c : cycle) {
            if (c.isAutomatic() || c.getPriority().compareTo(bar) >= 0) continue;
            if (weakest == null || ConstraintConflicts.RANK.compare(c, weakest) > 0) weakest = c;
        }
        return weakest;
    }

    private static List<Constraint> automatic(List<Constraint> cs) {
        List<Constraint> l = new ArrayList<>();
        for (Constraint c : cs) if (c.isAutomatic()) l.add(c);
        return l;
    }

    /** The constraints of {@code cs} that have the lowest priority among them. */
    private static List<Constraint> ofLowestPriority(List<Constraint> cs) {
        Priority low = null;
        for (Constraint c : cs) if (low == null || c.getPriority().compareTo(low) < 0) low = c.getPriority();
        List<Constraint> l = new ArrayList<>();
        for (Constraint c : cs) if (c.getPriority() == low) l.add(c);
        return l;
    }

    private static Constraint latestAutomatic(List<Constraint> cs) {
        Constraint best = null;
        for (Constraint c : cs) {
            if (!c.isAutomatic()) continue;
            if (best == null || c.getId() > best.getId()) best = c;
        }
        return best;
    }

    /** feasPriority=class: the automatic constraint of the lowest rank, the youngest among equals; null if none is automatic. */
    private static Constraint lowestRanked(List<Constraint> cs) {
        Constraint best = null;
        for (Constraint c : cs) {
            if (!c.isAutomatic()) continue;
            if (best == null || c.getRank() < best.getRank()
                    || (c.getRank() == best.getRank() && c.getId() > best.getId())) best = c;
        }
        return best;
    }

    /** Remove the newest automatic alignment whose removal separates the coinciding pair; returns it or null. */
    private static Constraint resolveCoincidence(Constraints constraints, Node[] pair) {
        List<Constraint> alignments = new ArrayList<>();
        for (Constraint c : constraints.getAllConstraints()) {
            if (c.isAutomatic() && c.isActive() && (c instanceof HorizontalConstraint || c instanceof VerticalConstraint)) alignments.add(c);
        }
        if (BY_CLASS) alignments.sort((a, b) -> a.getRank() != b.getRank()
                ? Integer.compare(a.getRank(), b.getRank()) : Long.compare(b.getId(), a.getId()));
        else alignments.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        for (Constraint c : alignments) {
            constraints.removeConstraint(c);
            Model m = Model.build(constraints);
            if (!m.coincide(pair[0], pair[1])) return c;
            // re-added at the end: the order does not matter for feasibility, but the solve gets it in a new order
            constraints.addConstraint(c);
        }
        return null;
    }

    // ------------------------------------------------------------------ model

    private static final class Model {
        final Map<Node, Integer> index = new HashMap<>();
        final List<Node> nodes = new ArrayList<>();
        int[][] parent;                                   // union-find per dimension
        final List<List<Object[]>> arcs = new ArrayList<>();   // per dimension: {fromClass, toClass, constraint}
        final List<List<Constraint>> aligns = new ArrayList<>();   // per dimension: the alignment constraints

        static Model build(Constraints constraints) {
            Model m = new Model();
            for (Constraint c : constraints.getAllConstraints()) for (Node n : c.getNodes()) m.node(n);
            int n = m.nodes.size();
            m.parent = new int[2][n];
            for (int d = 0; d < 2; d++) for (int i = 0; i < n; i++) m.parent[d][i] = i;
            m.aligns.add(new ArrayList<>());
            m.aligns.add(new ArrayList<>());
            for (Constraint c : constraints.getAllConstraints()) {
                int dim = c instanceof VerticalConstraint ? 0 : c instanceof HorizontalConstraint ? 1 : -1;
                if (dim < 0 || !c.isActive()) continue;   // a constraint no solve applies cannot contradict anything
                m.aligns.get(dim).add(c);
                List<Node> ns = c.getNodes();
                for (int i = 1; i < ns.size(); i++) m.union(dim, m.index.get(ns.get(0)), m.index.get(ns.get(i)));
            }
            m.arcs.add(new ArrayList<>());
            m.arcs.add(new ArrayList<>());
            for (Constraint c : constraints.getAllConstraints()) {
                if (!(c instanceof SeparationConstraintGraph) || c.getNodes().size() != 2 || !c.isActive()) continue;
                SeparationConstraintGraph s = (SeparationConstraintGraph) c;
                if (s.getDistance() <= 0) continue;
                int dim = s.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL ? 0 : 1;
                int a = m.find(dim, m.index.get(s.getNodes().get(0)));
                int b = m.find(dim, m.index.get(s.getNodes().get(1)));
                m.arcs.get(dim).add(new Object[]{ a, b, c });
            }
            return m;
        }

        void node(Node n) {
            if (!index.containsKey(n)) { index.put(n, nodes.size()); nodes.add(n); }
        }

        int find(int d, int i) {
            while (parent[d][i] != i) { parent[d][i] = parent[d][parent[d][i]]; i = parent[d][i]; }
            return i;
        }

        void union(int d, int a, int b) {
            int ra = find(d, a), rb = find(d, b);
            if (ra != rb) parent[d][ra] = rb;
        }

        /** Constraints on some directed cycle of the quotient graph of dimension {@code dim}, or null. */
        List<Constraint> findCycle(int dim) {
            Map<Integer, List<Object[]>> out = new HashMap<>();
            for (Object[] arc : arcs.get(dim)) {
                if (arc[0].equals(arc[1])) return Collections.singletonList((Constraint) arc[2]);   // self-loop
                out.computeIfAbsent((Integer) arc[0], k -> new ArrayList<>()).add(arc);
            }
            Map<Integer, Integer> color = new HashMap<>();
            Map<Integer, Object[]> via = new HashMap<>();
            for (Integer start : out.keySet()) {
                if (color.containsKey(start)) continue;
                Deque<Object[]> stack = new ArrayDeque<>();
                stack.push(new Object[]{ start, 0 });
                color.put(start, 1);
                while (!stack.isEmpty()) {
                    Object[] frame = stack.peek();
                    int u = (Integer) frame[0];
                    int i = (Integer) frame[1];
                    List<Object[]> outs = out.getOrDefault(u, Collections.emptyList());
                    if (i < outs.size()) {
                        frame[1] = i + 1;
                        Object[] arc = outs.get(i);
                        int v = (Integer) arc[1];
                        Integer cv = color.get(v);
                        if (cv == null) {
                            color.put(v, 1);
                            via.put(v, arc);
                            stack.push(new Object[]{ v, 0 });
                        } else if (cv == 1) {
                            // back arc u -> v closes a cycle: collect arcs from v down to u, then this arc
                            List<Constraint> cyc = new ArrayList<>();
                            cyc.add((Constraint) arc[2]);
                            int x = u;
                            while (x != v) {
                                Object[] p = via.get(x);
                                cyc.add((Constraint) p[2]);
                                x = (Integer) p[0];
                            }
                            return cyc;
                        }
                    } else {
                        color.put(u, 2);
                        stack.pop();
                    }
                }
            }
            return null;
        }

        /** The alignments of dimension {@code dim} on one path from a to b, or null if not joined. */
        List<Constraint> alignmentPath(int dim, Node a, Node b) {
            Map<Node, List<Constraint>> at = new HashMap<>();
            for (Constraint c : aligns.get(dim)) for (Node n : c.getNodes()) at.computeIfAbsent(n, k -> new ArrayList<>()).add(c);
            Map<Node, Node> prevNode = new HashMap<>();
            Map<Node, Constraint> prevVia = new HashMap<>();
            Deque<Node> queue = new ArrayDeque<>();
            queue.add(a);
            prevNode.put(a, a);
            while (!queue.isEmpty()) {
                Node u = queue.poll();
                if (u == b) break;
                for (Constraint c : at.getOrDefault(u, Collections.emptyList())) {
                    for (Node v : c.getNodes()) {
                        if (prevNode.containsKey(v)) continue;
                        prevNode.put(v, u);
                        prevVia.put(v, c);
                        queue.add(v);
                    }
                }
            }
            if (!prevNode.containsKey(b)) return null;
            List<Constraint> path = new ArrayList<>();
            for (Node x = b; x != a; x = prevNode.get(x)) path.add(prevVia.get(x));
            return path;
        }

        /** Two distinct nodes sharing both their x-class and their y-class, or null. */
        Node[] findCoincidence() {
            Map<Long, Node> seen = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                long key = ((long) find(0, i) << 32) | find(1, i);
                Node other = seen.putIfAbsent(key, nodes.get(i));
                if (other != null) return new Node[]{ other, nodes.get(i) };
            }
            return null;
        }

        boolean coincide(Node a, Node b) {
            Integer ia = index.get(a), ib = index.get(b);
            if (ia == null || ib == null) return false;
            return find(0, ia) == find(0, ib) && find(1, ia) == find(1, ib);
        }
    }
}
