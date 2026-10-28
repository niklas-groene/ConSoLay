package constraints;

import org.AttributeHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which constraints a solve applies when they contradict each other, before libcola sees them (libcola treats
 * all constraints as equal, picks among them by an unstable sort and drops single parts, so both sides of a
 * contradiction end up half applied). Taken in {@link #RANK} order, a constraint is applied only if it fits everything
 * applied before it; otherwise it is left out as a whole and gets a {@link Deactivation} naming the constraints it
 * contradicts. Per axis the constraints are difference constraints whose constraint graph is kept free of negative
 * cycles; cluster sides and, with overlap removal, nodes fixed on top of each other are decided in the same pass, while
 * soft targets and node non-overlap in general are left to the solver and {@link ConstraintCheck}.
 *
 * @author Niklas Gröne
 */
public final class ConstraintConflicts {

    /** Tolerance in px: a contradiction smaller than this is none (the solver works to a similar precision). */
    private static final double EPS = 1e-6;

    /**
     * The order the constraints are applied in: the higher priority first; at the same priority the user's own before the
     * SBGN steps' ones; then the older (smaller id) first.
     */
    public static final Comparator<Constraint> RANK = (a, b) -> {
        int p = b.getPriority().compareTo(a.getPriority());
        if (p != 0) return p;
        if (a.isAutomatic() != b.isAutomatic()) return a.isAutomatic() ? 1 : -1;
        return Long.compare(a.getId(), b.getId());
    };

    /** The side of a cluster an outside node is kept on. */
    public enum Side { LEFT, RIGHT, ABOVE, BELOW }

    /** What one resolution looks at, and what it hands to the solve besides the deactivations. */
    public static final class Scope {
        /** The nodes the solve moves, in a fixed order (clusters take their outside nodes in this order). */
        public final List<Node> nodes;
        private final Set<Node> nodeSet;
        /**
         * Constraints lying in this graph but with a node the solve does not move are not decided here: their
         * deactivation is cleared, so no old decision sticks to them. Null: they are left as they are.
         */
        public final Graph graph;
        /**
         * How much larger than its node the solver's rectangle is per side. NaN: neither clusters nor overlaps are looked
         * at; else every node that is not a member stays on one side of each cluster ({@link #clusterSides}).
         */
        public final double rectPad;
        /** A force-directed run follows: a held rigid group is then not placed onto its members' targets first. */
        public final boolean forceDirected;
        /** The solve removes node overlaps (libcola's non-overlap). */
        public final boolean avoidOverlaps;
        /** Output: per applied cluster, the side each outside node is kept on. */
        public final Map<ClusterConstraintGraph, Map<Node, Side>> clusterSides = new IdentityHashMap<>();

        public Scope(Collection<Node> nodes, Graph graph, double rectPad, boolean forceDirected, boolean avoidOverlaps) {
            this.nodes = new ArrayList<>(nodes);
            this.nodeSet = new HashSet<>(nodes);
            this.graph = graph;
            this.rectPad = rectPad;
            this.forceDirected = forceDirected;
            this.avoidOverlaps = avoidOverlaps;
        }
    }

    private ConstraintConflicts() { }

    /**
     * Decides for every switched-on constraint of {@code cs} whose nodes are all in the scope whether it is applied
     * (its deactivation is cleared) or not (it gets one). A switched-off constraint loses its deactivation; one with a
     * node outside the scope is left as it is (or cleared, see {@link Scope#graph}).
     *
     * @return the deactivated constraints, in rank order
     */
    public static List<Constraint> resolve(Collection<? extends Constraint> cs, Scope scope) {
        List<Constraint> candidates = new ArrayList<>();
        scope.clusterSides.clear();
        for (Constraint c : cs) {
            if (!allNodes(c, scope.nodeSet::contains)) {
                if (scope.graph != null && allNodes(c, scope.graph::containsNode)) c.setDeactivation(null);
                continue;
            }
            if (!c.isEnabled()) {
                c.setDeactivation(null);
                continue;
            }
            candidates.add(c);
        }
        candidates.sort(RANK);
        Model model = new Model(scope, candidates);
        List<Constraint> applied = new ArrayList<>();
        Map<Constraint, Deactivation> decided = new IdentityHashMap<>();
        for (Constraint c : candidates) {
            Deactivation d = model.add(c);
            if (d != null && model.failedOnClusterSide) {
                // It failed on a side a cluster chose without it: choose the sides anew with it (and only what is applied).
                List<Constraint> with = new ArrayList<>(applied);
                with.add(c);
                Model plan = Model.plan(scope, candidates, with);
                if (plan != null) {
                    model = plan;
                    d = null;
                }
            }
            if (d == null) applied.add(c);
            decided.put(c, d);
        }
        scope.clusterSides.clear();
        scope.clusterSides.putAll(model.sides);
        for (Map.Entry<ClusterConstraintGraph, Map<Node, Side>> e : model.sides.entrySet()) {
            Map<Node, Integer> ordinals = new HashMap<>();
            for (Map.Entry<Node, Side> s : e.getValue().entrySet()) ordinals.put(s.getKey(), s.getValue().ordinal());
            e.getKey().rememberSides(ordinals);
        }
        List<Constraint> off = new ArrayList<>();
        for (Constraint c : candidates) {
            Deactivation d = decided.get(c);
            c.setDeactivation(d);
            if (d != null) off.add(c);
        }
        return off;
    }

    /**
     * "Prefer this": gives {@code c} the lowest priority that ranks it above the constraints it contradicts and resolves
     * again until it is applied; if even {@link Priority#VERY_HIGH} is not enough, {@code c} becomes very high and the
     * partners that still win become high. Nothing changes for a constraint that contradicts itself.
     *
     * @param all   the constraints to resolve again (the store)
     * @param scope the scope of the solve that follows
     * @return the partners whose priority was lowered (empty if raising {@code c} was enough)
     */
    public static List<Constraint> prefer(Constraint c, Collection<? extends Constraint> all, Scope scope) {
        List<Constraint> lowered = new ArrayList<>();
        // Each round raises c or lowers partners that rank above it at the top level, so it ends; the bound is a guard.
        for (int round = 0; round < all.size() + 8; round++) {
            Deactivation d = c.getDeactivation();
            if (d == null || d.contradictsItself() || !c.isEnabled()) break;
            boolean changed = raiseAbove(c, d.partners, lowered);
            resolve(all, scope);
            if (!changed) break;
        }
        return lowered;
    }

    private static boolean raiseAbove(Constraint c, List<Constraint> partners, List<Constraint> lowered) {
        Priority original = c.getPriority();
        for (Priority p = original; p != null; p = p.above()) {
            c.setPriority(p);
            if (ranksAboveAll(c, partners)) return p != original;
        }
        c.setPriority(Priority.VERY_HIGH);
        boolean changed = original != Priority.VERY_HIGH;
        for (Constraint partner : partners) {
            if (RANK.compare(c, partner) > 0) {
                partner.setPriority(Priority.HIGH);
                if (!lowered.contains(partner)) lowered.add(partner);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean ranksAboveAll(Constraint c, List<Constraint> others) {
        for (Constraint o : others) if (RANK.compare(c, o) > 0) return false;
        return true;
    }

    /** Forgets the deactivations of {@code cs}: they count as applied until a resolution decides again. */
    public static void clearDeactivations(Collection<? extends Constraint> cs) {
        for (Constraint c : cs) c.setDeactivation(null);
    }

    /**
     * Clears the deactivation of every constraint of {@code cs} that names a partner no longer in {@code cs} (deleted):
     * with it gone the reason is void, and the next solve decides again.
     *
     * @return true if one was cleared
     */
    public static boolean forgetStale(Collection<? extends Constraint> cs) {
        Set<Constraint> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(cs);
        boolean any = false;
        for (Constraint c : cs) {
            Deactivation d = c.getDeactivation();
            if (d != null && !present.containsAll(d.partners)) {
                c.setDeactivation(null);
                any = true;
            }
        }
        return any;
    }

    /** The constraints of {@code cs} a solve applies ({@link Constraint#isActive()}), in their order. */
    public static <T extends Constraint> List<T> active(Collection<T> cs) {
        List<T> l = new ArrayList<>();
        for (T c : cs) if (c.isActive()) l.add(c);
        return l;
    }

    /** True if {@code c} has nodes and all of them satisfy {@code in}. */
    private static boolean allNodes(Constraint c, Predicate<Node> in) {
        if (c == null || c.getNodes() == null || c.getNodes().isEmpty()) return false;
        for (Node n : c.getNodes()) if (!in.test(n)) return false;
        return true;
    }

    /** The applied constraints as one constraint graph per axis, plus what clusters and held rigid groups need. */
    private static final class Model {
        final Scope scope;
        final AxisGraph x = new AxisGraph(Axis.X), y = new AxisGraph(Axis.Y);
        /**
         * Where a held rigid group is placed, as UserConstraintTranslator.prepare() does: the pins applied so far (only
         * pins ranked above the group, the highest one per node), and without a run the targets (soft, never left out;
         * the highest-ranked one per node).
         */
        final Map<Node, double[]> pins = new HashMap<>(), targets = new HashMap<>();
        /** The equalities of the applied constraints, per axis: {u, v, d} for x_v − x_u = d, and their owners. */
        final List<double[]> eqX = new ArrayList<>(), eqY = new ArrayList<>();
        final List<Constraint> eqOwnerX = new ArrayList<>(), eqOwnerY = new ArrayList<>();
        /** Per applied cluster: its four lines {left, right, top, bottom}, its box at the start, the outside nodes' sides. */
        final Map<ClusterConstraintGraph, int[]> boxLines = new IdentityHashMap<>();
        final Map<ClusterConstraintGraph, double[]> boxes = new IdentityHashMap<>();
        final Map<ClusterConstraintGraph, Map<Node, Side>> sides = new IdentityHashMap<>();
        /** The last {@link #add} failed on a cycle through another cluster (a side it chose). */
        boolean failedOnClusterSide;

        Model(Scope scope, List<Constraint> candidates) {
            this.scope = scope;
            if (!scope.forceDirected) {
                for (Constraint c : candidates) {
                    if (c instanceof DesiredPositionConstraintGraph) {
                        DesiredPositionConstraintGraph d = (DesiredPositionConstraintGraph) c;
                        for (Node n : c.getNodes()) targets.putIfAbsent(n, d.getTarget(n));
                    }
                }
            }
        }

        AxisGraph graph(Axis a) {
            return a == Axis.X ? x : y;
        }

        /** Adds {@code c} if it fits; else leaves the model as it was and says why. */
        Deactivation add(Constraint c) {
            failedOnClusterSide = false;
            int[] mx = x.mark(), my = y.mark();
            Arcs arcs = new Arcs(this, c);
            boolean cluster = c instanceof ClusterConstraintGraph;
            if (cluster) {
                if (Double.isNaN(scope.rectPad)) return null;   // this solve does not keep other nodes out
                clusterBox((ClusterConstraintGraph) c, arcs);
            } else {
                arcs.build();
            }
            Cycle cycle = x.insert(arcs.x, c);
            if (cycle == null) cycle = y.insert(arcs.y, c);
            if (cycle == null && cluster) cycle = assignSides((ClusterConstraintGraph) c);
            Deactivation d = cycle == null ? null : cycle.deactivation(c);
            if (d == null && scope.avoidOverlaps && !Double.isNaN(scope.rectPad)) d = fixedOverlap(c, arcs);
            if (d != null) {
                if (cycle != null) for (Constraint o : cycle.owners) failedOnClusterSide |= o != c && o instanceof ClusterConstraintGraph;
                x.rollback(mx);
                y.rollback(my);
                boxLines.remove(c);
                boxes.remove(c);
                sides.remove(c);
                return d;
            }
            record(c, arcs);
            return null;
        }

        /** Keeps the equalities of an applied constraint, and its pins for the held groups ranked below it. */
        private void record(Constraint c, Arcs arcs) {
            for (double[] e : arcs.eqx) { eqX.add(e); eqOwnerX.add(c); }
            for (double[] e : arcs.eqy) { eqY.add(e); eqOwnerY.add(c); }
            if (c instanceof LockConstraintGraph) {
                for (Node n : c.getNodes()) {
                    double[] p = ((LockConstraintGraph) c).getPosition(n);
                    if (p != null) pins.putIfAbsent(n, p);
                }
            }
        }

        /**
         * A new model of exactly {@code set} (all applied before, in rank order, plus one), the clusters' sides chosen
         * after everything else is in, so they fit all of it. Null if some node finds no side (or, with overlap removal
         * on, two nodes end up fixed on top of each other).
         */
        static Model plan(Scope scope, List<Constraint> candidates, List<Constraint> set) {
            Model m = new Model(scope, candidates);
            List<ClusterConstraintGraph> clusters = new ArrayList<>();
            for (Constraint c : set) {
                Arcs arcs = new Arcs(m, c);
                if (c instanceof ClusterConstraintGraph) {
                    m.clusterBox((ClusterConstraintGraph) c, arcs);
                    clusters.add((ClusterConstraintGraph) c);
                } else {
                    arcs.build();
                }
                if (m.x.insert(arcs.x, c) != null || m.y.insert(arcs.y, c) != null) return null;
                m.record(c, arcs);
            }
            for (ClusterConstraintGraph cl : clusters) if (m.assignSides(cl) != null) return null;
            if (scope.avoidOverlaps && m.overlappingPair(m.eqX, m.eqY) != null) return null;
            return m;
        }

        /** A cluster's box: four lines around the members (their rectangles plus the padding inside). It always fits. */
        private void clusterBox(ClusterConstraintGraph cl, Arcs box) {
            double pad = scope.rectPad, padding = cl.getPadding();
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (Node m : cl.getNodes()) {
                minX = Math.min(minX, pos(m, Axis.X) - half(m, Axis.X) - pad);
                maxX = Math.max(maxX, pos(m, Axis.X) + half(m, Axis.X) + pad);
                minY = Math.min(minY, pos(m, Axis.Y) - half(m, Axis.Y) - pad);
                maxY = Math.max(maxY, pos(m, Axis.Y) + half(m, Axis.Y) + pad);
            }
            minX -= padding; maxX += padding; minY -= padding; maxY += padding;
            int left = x.vertex(minX), right = x.vertex(maxX), top = y.vertex(minY), bottom = y.vertex(maxY);
            for (Node m : cl.getNodes()) {
                double hw = half(m, Axis.X) + pad + padding, hh = half(m, Axis.Y) + pad + padding;
                box.ge(Axis.X, left, box.node(Axis.X, m), hw);
                box.ge(Axis.X, box.node(Axis.X, m), right, hw);
                box.ge(Axis.Y, top, box.node(Axis.Y, m), hh);
                box.ge(Axis.Y, box.node(Axis.Y, m), bottom, hh);
            }
            boxLines.put(cl, new int[]{ left, right, top, bottom });
            boxes.put(cl, new double[]{ minX, maxX, minY, maxY });
        }

        /**
         * Gives every node of the scope that is not a member of {@code cl} a side of it: the side it is furthest out of
         * among those that fit what is in the model. Returns null when every node found one (the sides go to
         * {@link #sides}), else the contradiction of the first node that fits none (on its best side); the caller rolls
         * back the sides given so far.
         */
        Cycle assignSides(ClusterConstraintGraph cl) {
            int[] lines = boxLines.get(cl);
            double[] b = boxes.get(cl);
            Set<Node> members = new HashSet<>(cl.getNodes());
            double pad = scope.rectPad, margin = cl.getMargin();
            Map<Node, Side> chosenSides = new LinkedHashMap<>();
            for (Node n : scope.nodes) {
                if (members.contains(n)) continue;
                double hw = half(n, Axis.X) + pad + margin, hh = half(n, Axis.Y) + pad + margin;
                double nx = pos(n, Axis.X), ny = pos(n, Axis.Y);
                double[] clear = { b[0] - (nx + hw), (nx - hw) - b[1], b[2] - (ny + hh), (ny - hh) - b[3] };
                Integer[] order = { 0, 1, 2, 3 };
                Arrays.sort(order, (p, q) -> Double.compare(clear[q], clear[p]));   // stable: ties keep L, R, A, B
                // The last solve's side first while the node is still clear of the box there, so a node at a corner does
                // not change sides (and with them what is applied) from one solve to the next.
                Integer last = cl.lastSide(n);
                if (last != null && clear[last] >= -EPS) {
                    List<Integer> l = new ArrayList<>(Arrays.asList(order));
                    l.remove(last);
                    l.add(0, last);
                    order = l.toArray(new Integer[0]);
                }
                Cycle first = null;
                Side chosen = null;
                for (int k : order) {
                    Arcs one = new Arcs(this, cl);
                    if (k == 0) one.ge(Axis.X, one.node(Axis.X, n), lines[0], hw);
                    else if (k == 1) one.ge(Axis.X, lines[1], one.node(Axis.X, n), hw);
                    else if (k == 2) one.ge(Axis.Y, one.node(Axis.Y, n), lines[2], hh);
                    else one.ge(Axis.Y, lines[3], one.node(Axis.Y, n), hh);
                    Cycle c = k < 2 ? x.insert(one.x, cl) : y.insert(one.y, cl);
                    if (c == null) {
                        chosen = Side.values()[k];
                        break;
                    }
                    if (first == null) first = c;
                }
                if (chosen == null) return first;
                chosenSides.put(n, chosen);
            }
            sides.put(cl, chosenSides);
            return null;
        }

        /**
         * With overlap removal on: whether {@code c} fixes two nodes relative to each other in x and in y so that the
         * nodes overlap (no solve could separate them), or leaves every pair of the scope fixed with overlapping
         * rectangles (libcola's non-overlap then does not end). The applied constraints have no such pair, so a pair
         * found now is c's doing.
         */
        Deactivation fixedOverlap(Constraint c, Arcs arcs) {
            if (arcs.eqx.isEmpty() && arcs.eqy.isEmpty()) return null;
            List<double[]> ex = new ArrayList<>(eqX), ey = new ArrayList<>(eqY);
            List<Constraint> ox = new ArrayList<>(eqOwnerX), oy = new ArrayList<>(eqOwnerY);
            for (double[] e : arcs.eqx) { ex.add(e); ox.add(c); }
            for (double[] e : arcs.eqy) { ey.add(e); oy.add(c); }
            Node[] pair = overlappingPair(ex, ey);
            if (pair == null) return null;
            Set<Constraint> fixing = new LinkedHashSet<>();
            fixing.addAll(pathOwners(ex, ox, x.n, x.nodes.get(pair[0]), x.nodes.get(pair[1])));
            fixing.addAll(pathOwners(ey, oy, y.n, y.nodes.get(pair[0]), y.nodes.get(pair[1])));
            fixing.remove(c);
            return new Deactivation(c, new ArrayList<>(fixing), null, Double.NaN, "Would fix '" + Deactivation.label(pair[0])
                    + "' and '" + Deactivation.label(pair[1]) + "' on top of each other, which the overlap removal cannot "
                    + "separate");
        }

        /**
         * Two scope nodes that the equalities fix relative to each other in x and y with overlapping node boxes, or, if all
         * scope nodes are fixed together with overlapping rectangles (padding included) pair by pair, two of them; null
         * otherwise. (libcola's non-overlap cannot move fixed pairs apart; it gives them up as long as some other pair is
         * free of overlap, and loops forever when none is.)
         */
        Node[] overlappingPair(List<double[]> ex, List<double[]> ey) {
            Uf ux = new Uf(x.n), uy = new Uf(y.n);
            for (double[] e : ex) ux.union((int) e[0], (int) e[1], e[2]);
            for (double[] e : ey) uy.union((int) e[0], (int) e[1], e[2]);
            Map<Long, List<Node>> groups = new HashMap<>();
            for (Node n : scope.nodes) {
                Integer vx = x.nodes.get(n), vy = y.nodes.get(n);
                if (vx == null || vy == null) continue;
                long key = ((long) ux.find(vx) << 32) | uy.find(vy);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
            }
            for (Node n : scope.nodes) {   // in scope order, so the pair found is the same every time
                Integer vx = x.nodes.get(n), vy = y.nodes.get(n);
                if (vx == null || vy == null) continue;
                for (Node o : groups.get(((long) ux.find(vx) << 32) | uy.find(vy))) {
                    if (o == n) break;   // pairs with the nodes before n only
                    if (overlap(n, o, ux, uy, 0)) return new Node[]{ o, n };
                }
            }
            // every node fixed to every other, all rectangles overlapping pair by pair (a small graph pressed together)
            if (groups.size() != 1 || scope.nodes.size() < 2) return null;
            List<Node> all = groups.values().iterator().next();
            if (all.size() != scope.nodes.size()) return null;
            for (int i = 0; i < all.size(); i++) {
                for (int j = i + 1; j < all.size(); j++) if (!overlap(all.get(i), all.get(j), ux, uy, scope.rectPad)) return null;
            }
            return new Node[]{ all.get(0), all.get(1) };
        }

        /** Whether the boxes of two fixed nodes, {@code pad} larger per side, overlap. */
        private boolean overlap(Node a, Node b, Uf ux, Uf uy, double pad) {
            double dx = ux.offset(x.nodes.get(b)) - ux.offset(x.nodes.get(a)), dy = uy.offset(y.nodes.get(b)) - uy.offset(y.nodes.get(a));
            return Math.abs(dx) < half(a, Axis.X) + half(b, Axis.X) + 2 * pad - EPS
                    && Math.abs(dy) < half(a, Axis.Y) + half(b, Axis.Y) + 2 * pad - EPS;
        }

        /** The owners of the equalities on a path from u to v (breadth-first), empty if none. */
        private static List<Constraint> pathOwners(List<double[]> eqs, List<Constraint> owners, int n, int u, int v) {
            List<List<int[]>> adj = new ArrayList<>();
            for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
            for (int i = 0; i < eqs.size(); i++) {
                int p = (int) eqs.get(i)[0], q = (int) eqs.get(i)[1];
                adj.get(p).add(new int[]{ q, i });
                adj.get(q).add(new int[]{ p, i });
            }
            int[] via = new int[n], prev = new int[n];
            Arrays.fill(via, -2);
            via[u] = -1;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(u);
            while (!queue.isEmpty() && via[v] == -2) {
                int p = queue.poll();
                for (int[] e : adj.get(p)) {
                    if (via[e[0]] != -2) continue;
                    via[e[0]] = e[1];
                    prev[e[0]] = p;
                    queue.add(e[0]);
                }
            }
            List<Constraint> l = new ArrayList<>();
            if (via[v] == -2) return l;
            for (int w = v; w != u; w = prev[w]) if (!l.contains(owners.get(via[w]))) l.add(owners.get(via[w]));
            return l;
        }

        /**
         * Where a held rigid group ("also resist moving") stays: its stored shape placed as
         * UserConstraintTranslator.prepare() places it, on a pinned member, else (without a run) on its members'
         * targets, else on its current centroid. Null for fewer than two members.
         */
        Map<Node, double[]> held(FixedRelativeConstraintGraph f) {
            double cx = 0, cy = 0, mx = 0, my = 0;
            int k = 0;
            for (Node n : f.getNodes()) {
                double[] o = f.getOffset(n);
                if (o == null) continue;
                cx += pos(n, Axis.X); cy += pos(n, Axis.Y); mx += o[0]; my += o[1];
                k++;
            }
            if (k < 2) return null;
            cx /= k; cy /= k; mx /= k; my /= k;
            double ox = cx, oy = cy;
            boolean anchored = false;
            for (Node n : f.getNodes()) {
                double[] o = f.getOffset(n), p = pins.get(n);
                if (o == null || p == null) continue;
                ox = p[0] - (o[0] - mx);
                oy = p[1] - (o[1] - my);
                anchored = true;
                break;
            }
            if (!anchored) {
                double tx = 0, ty = 0, sx = 0, sy = 0;
                int t = 0;
                for (Node n : f.getNodes()) {
                    double[] o = f.getOffset(n), p = targets.get(n);
                    if (o == null || p == null) continue;
                    tx += p[0]; ty += p[1]; sx += o[0] - mx; sy += o[1] - my;
                    t++;
                }
                if (t > 0) { ox = (tx - sx) / t; oy = (ty - sy) / t; }
            }
            Map<Node, double[]> at = new LinkedHashMap<>();
            for (Node n : f.getNodes()) {
                double[] o = f.getOffset(n);
                if (o != null) at.put(n, new double[]{ ox + o[0] - mx, oy + o[1] - my });
            }
            return at;
        }
    }

    /**
     * The arcs of one constraint, as "x_v − x_u ≥ c" relations ({@link #ge}) or equalities ({@link #eq}), stored in
     * the shortest-path form of the constraint graph: an arc p → q of weight w means x_q − x_p ≤ w.
     */
    private static final class Arcs {
        final Model model;
        final Constraint c;
        /** Per axis: from, to, weight as triples. */
        final List<double[]> x = new ArrayList<>(), y = new ArrayList<>();
        /** Per axis: the equalities among them, {u, v, d} for x_v − x_u = d. */
        final List<double[]> eqx = new ArrayList<>(), eqy = new ArrayList<>();

        Arcs(Model model, Constraint c) {
            this.model = model;
            this.c = c;
        }

        /** x_v − x_u ≥ gap. */
        void ge(Axis a, int u, int v, double gap) {
            (a == Axis.X ? x : y).add(new double[]{v, u, -gap});
        }

        /** x_v − x_u = d. */
        void eq(Axis a, int u, int v, double d) {
            ge(a, u, v, d);
            ge(a, v, u, -d);
            (a == Axis.X ? eqx : eqy).add(new double[]{ u, v, d });
        }

        int node(Axis a, Node n) {
            return model.graph(a).node(n);
        }

        /** An extra variable (a guideline, a dividing line) starting at {@code at}. */
        int aux(Axis a, double at) {
            return model.graph(a).vertex(at);
        }

        int origin(Axis a) {
            return model.graph(a).origin();
        }

        void build() {
            List<Node> ns = c.getNodes();
            if (c instanceof HorizontalConstraint) {
                HorizontalConstraint.HorizontalConstraintType t = ((HorizontalConstraint) c).getType();
                double sign = t == HorizontalConstraint.HorizontalConstraintType.TOP ? 1 : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? -1 : 0;
                align(Axis.Y, ns, sign);
            } else if (c instanceof VerticalConstraint) {
                VerticalConstraint.VerticalConstraintType t = ((VerticalConstraint) c).getType();
                double sign = t == VerticalConstraint.VerticalConstraintType.LEFT ? 1 : t == VerticalConstraint.VerticalConstraintType.RIGHT ? -1 : 0;
                align(Axis.X, ns, sign);
            } else if (c instanceof SeparationConstraintGraph) {
                SeparationConstraintGraph s = (SeparationConstraintGraph) c;
                if (ns.size() != 2) return;   // the solver skips it as well
                Axis a = Axis.of(s.getType());
                gap(a, ns.get(0), ns.get(1), s.getDistance(), s.isFixed());
            } else if (c instanceof DistributionConstraintGraph) {
                DistributionConstraintGraph d = (DistributionConstraintGraph) c;
                consecutive(Axis.of(d.getType()), ns, d.getSpacing(), true);
            } else if (c instanceof MultiSeparationConstraintGraph) {
                MultiSeparationConstraintGraph m = (MultiSeparationConstraintGraph) c;
                consecutive(m.getAxis(), ns, m.getGap(), m.isExact());
            } else if (c instanceof FixedRelativeConstraintGraph) {
                FixedRelativeConstraintGraph f = (FixedRelativeConstraintGraph) c;
                Node first = null;
                for (Node n : ns) {
                    if (f.getOffset(n) == null) continue;
                    if (first == null) { first = n; continue; }
                    double[] o0 = f.getOffset(first), o = f.getOffset(n);
                    eq(Axis.X, node(Axis.X, first), node(Axis.X, n), o[0] - o0[0]);
                    eq(Axis.Y, node(Axis.Y, first), node(Axis.Y, n), o[1] - o0[1]);
                }
                // "also resist moving": the solver holds the members where UserConstraintTranslator.prepare() puts
                // them, as firmly as a pin
                Map<Node, double[]> held = f.isFixedPosition() ? model.held(f) : null;
                if (held != null) {
                    for (Map.Entry<Node, double[]> e : held.entrySet()) {
                        eq(Axis.X, origin(Axis.X), node(Axis.X, e.getKey()), e.getValue()[0]);
                        eq(Axis.Y, origin(Axis.Y), node(Axis.Y, e.getKey()), e.getValue()[1]);
                    }
                }
            } else if (c instanceof BoundaryConstraintGraph) {
                BoundaryConstraintGraph b = (BoundaryConstraintGraph) c;
                Axis a = b.getAxis();
                double at = !b.getBefore().isEmpty() ? pos(b.getBefore().get(0), a) + half(b.getBefore().get(0), a) + b.getGap()
                        : !b.getAfter().isEmpty() ? pos(b.getAfter().get(0), a) - half(b.getAfter().get(0), a) - b.getGap() : 0;
                int line = aux(a, at);
                for (Node n : b.getBefore()) ge(a, node(a, n), line, half(n, a) + b.getGap());
                for (Node n : b.getAfter()) ge(a, line, node(a, n), half(n, a) + b.getGap());
            } else if (c instanceof PageBoundaryConstraintGraph) {
                PageBoundaryConstraintGraph p = (PageBoundaryConstraintGraph) c;
                for (Node n : ns) {
                    double hw = half(n, Axis.X), hh = half(n, Axis.Y);
                    ge(Axis.X, origin(Axis.X), node(Axis.X, n), p.getMinX() + hw);
                    ge(Axis.X, node(Axis.X, n), origin(Axis.X), -(p.getMaxX() - hw));
                    ge(Axis.Y, origin(Axis.Y), node(Axis.Y, n), p.getMinY() + hh);
                    ge(Axis.Y, node(Axis.Y, n), origin(Axis.Y), -(p.getMaxY() - hh));
                }
            } else if (c instanceof OrthogonalEdgeConstraintGraph) {
                if (ns.size() != 2) return;
                Axis a = ((OrthogonalEdgeConstraintGraph) c).isHorizontal() ? Axis.Y : Axis.X;   // horizontal edge: same y
                eq(a, node(a, ns.get(0)), node(a, ns.get(1)), 0);
            } else if (c instanceof LockConstraintGraph) {
                LockConstraintGraph l = (LockConstraintGraph) c;
                for (Node n : ns) {
                    double[] p = l.getPosition(n);
                    if (p == null) continue;
                    eq(Axis.X, origin(Axis.X), node(Axis.X, n), p[0]);
                    eq(Axis.Y, origin(Axis.Y), node(Axis.Y, n), p[1]);
                }
            } else if (c instanceof LinePattern) {
                LinePattern l = (LinePattern) c;
                align(l.isRow() ? Axis.Y : Axis.X, ns, 0);
                consecutive(l.getAxis(), ns, l.getGap(), l.isEven());
            } else if (c instanceof GridPattern) {
                GridPattern g = (GridPattern) c;
                lines(Axis.X, g.getColumns(), g.getColumnGap());
                lines(Axis.Y, g.getRows(), g.getRowGap());
            } else if (c instanceof StarPattern) {
                StarPattern s = (StarPattern) c;
                for (int i = 0; i < s.getRing().size(); i++) {
                    Node leaf = s.getRing().get(i);
                    double[] o = s.offset(i);
                    eq(Axis.X, node(Axis.X, s.getHub()), node(Axis.X, leaf), o[0]);
                    eq(Axis.Y, node(Axis.Y, s.getHub()), node(Axis.Y, leaf), o[1]);
                }
            }
            // DesiredPositionConstraintGraph (a soft pull) adds no arcs: it never contradicts anything here.
        }

        /** Each node's centre lies {@code sign} × half its size beyond one guideline (+1: top / left edges on it). */
        private void align(Axis a, List<Node> ns, double sign) {
            if (ns.isEmpty()) return;
            int line = aux(a, pos(ns.get(0), a) - sign * half(ns.get(0), a));
            for (Node n : ns) eq(a, line, node(a, n), sign * half(n, a));
        }

        private void gap(Axis a, Node from, Node to, double gap, boolean exact) {
            if (exact) eq(a, node(a, from), node(a, to), gap);
            else ge(a, node(a, from), node(a, to), gap);
        }

        /** Consecutive nodes at least (exactly) {@code gap} apart, in list order. */
        private void consecutive(Axis a, List<Node> ns, double gap, boolean exact) {
            for (int i = 1; i < ns.size(); i++) gap(a, ns.get(i - 1), ns.get(i), gap, exact);
        }

        /** Grid: the nodes of each line share a centre line, consecutive lines at least {@code gap} apart. */
        private void lines(Axis a, List<List<Node>> groups, double gap) {
            int previous = -1;
            for (List<Node> group : groups) {
                if (group.isEmpty()) continue;
                int line = aux(a, pos(group.get(0), a));
                for (Node n : group) eq(a, line, node(a, n), 0);
                if (previous >= 0) ge(a, previous, line, gap);
                previous = line;
            }
        }
    }

    private static double pos(Node n, Axis a) {
        return a == Axis.X ? AttributeHelper.getPositionX(n) : AttributeHelper.getPositionY(n);
    }

    private static double half(Node n, Axis a) {
        return (a == Axis.X ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n)) / 2;
    }

    /** Union-find with offsets: {@link #offset} is a vertex's value relative to its class's root. */
    private static final class Uf {
        final int[] parent;
        final double[] off;

        Uf(int n) {
            parent = new int[n];
            off = new double[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int v) {
            int r = v;
            while (parent[r] != r) r = parent[r];
            List<Integer> path = new ArrayList<>();
            for (int w = v; parent[w] != w; w = parent[w]) path.add(w);
            for (int i = path.size() - 1; i >= 0; i--) {   // nearest to the root first: its offset is final already
                int w = path.get(i);
                if (parent[w] != r) off[w] += off[parent[w]];
                parent[w] = r;
            }
            return r;
        }

        double offset(int v) {
            find(v);
            return off[v];
        }

        /** x_v − x_u = d. */
        void union(int u, int v, double d) {
            int ru = find(u), rv = find(v);
            if (ru == rv) return;
            // x_rv = x_v − off[v] = x_u + d − off[v] = x_ru + off[u] + d − off[v]
            parent[rv] = ru;
            off[rv] = off[u] + d - off[v];
        }
    }

    /** A negative cycle an insertion closed: its arcs' owners and its total weight. */
    private static final class Cycle {
        final Axis axis;
        final List<Constraint> owners;
        final double weight;

        Cycle(Axis axis, List<Constraint> owners, double weight) {
            this.axis = axis;
            this.owners = owners;
            this.weight = weight;
        }

        Deactivation deactivation(Constraint self) {
            List<Constraint> partners = new ArrayList<>();
            for (Constraint o : owners) if (o != self && !partners.contains(o)) partners.add(o);
            return new Deactivation(self, partners, axis, Double.isNaN(weight) ? Double.NaN : -weight);
        }
    }

    /**
     * The constraint graph of one axis with a potential {@code d} that satisfies every arc (d is a solution: x = d).
     * Adding arcs re-establishes the potential by relaxing from the new arcs; a relaxation that closes a cycle in the
     * tree of this insertion's relaxations proves a negative cycle, which always runs through a new arc because the graph
     * was feasible before. Every change of the potential is logged, so the graph can go back to any {@link #mark()}.
     */
    private static final class AxisGraph {
        final Axis axis;
        final Map<Node, Integer> nodes = new HashMap<>();
        int origin = -1;

        int n;
        double[] d = new double[64];
        int[][] out = new int[64][];
        int[] outSize = new int[64];

        int m;
        int[] from = new int[64], to = new int[64];
        double[] w = new double[64];
        Constraint[] owner = new Constraint[64];

        /** Undo log of the potential: vertex and its value before. */
        int[] logV = new int[256];
        double[] logD = new double[256];
        int logN;

        // per insertion
        int round;
        int[] stamp = new int[64];    // == round: relaxed in this insertion, its parent is valid
        int[] parent = new int[64];   // the arc that set its potential last
        int[] queued = new int[64];   // == round: in the queue
        long budget;

        AxisGraph(Axis axis) {
            this.axis = axis;
        }

        int node(Node node) {
            Integer v = nodes.get(node);
            if (v == null) {
                v = vertex(pos(node, axis));
                nodes.put(node, v);
            }
            return v;
        }

        int origin() {
            if (origin < 0) origin = vertex(0);
            return origin;
        }

        int vertex(double at) {
            if (n == d.length) {
                int cap = n * 2;
                d = Arrays.copyOf(d, cap);
                out = Arrays.copyOf(out, cap);
                outSize = Arrays.copyOf(outSize, cap);
                stamp = Arrays.copyOf(stamp, cap);
                parent = Arrays.copyOf(parent, cap);
                queued = Arrays.copyOf(queued, cap);
            }
            d[n] = at;
            out[n] = new int[4];
            outSize[n] = 0;
            return n++;
        }

        /** The current state: {arc count, log length}. Vertices made after it stay, without arcs. */
        int[] mark() {
            return new int[]{ m, logN };
        }

        /** Back to {@code mark}: the arcs added since are removed, the potential restored. */
        void rollback(int[] mark) {
            while (logN > mark[1]) {
                logN--;
                d[logV[logN]] = logD[logN];
            }
            for (int a = m - 1; a >= mark[0]; a--) {
                outSize[from[a]]--;
                owner[a] = null;
            }
            m = mark[0];
        }

        private void setPotential(int v, double value) {
            if (logN == logV.length) {
                logV = Arrays.copyOf(logV, logN * 2);
                logD = Arrays.copyOf(logD, logN * 2);
            }
            logV[logN] = v;
            logD[logN] = d[v];
            logN++;
            d[v] = value;
        }

        private void addArc(int f, int t, double weight, Constraint c) {
            if (m == from.length) {
                int cap = m * 2;
                from = Arrays.copyOf(from, cap);
                to = Arrays.copyOf(to, cap);
                w = Arrays.copyOf(w, cap);
                owner = Arrays.copyOf(owner, cap);
            }
            from[m] = f;
            to[m] = t;
            w[m] = weight;
            owner[m] = c;
            if (outSize[f] == out[f].length) out[f] = Arrays.copyOf(out[f], out[f].length * 2);
            out[f][outSize[f]++] = m;
            m++;
        }

        /**
         * Adds the arcs of {@code c}; returns null if the graph stays free of negative cycles, else the cycle, with the
         * graph and the potential as they were before.
         */
        Cycle insert(List<double[]> arcs, Constraint c) {
            if (arcs.isEmpty()) return null;
            int[] mark = mark();
            round++;
            for (double[] a : arcs) addArc((int) a[0], (int) a[1], a[2], c);
            Cycle cycle = relaxFrom(mark[0]);
            if (cycle != null) rollback(mark);
            return cycle;
        }

        private Cycle relaxFrom(int firstArc) {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            budget = 64L * (n + m) + 10000;   // relaxations plus parent walks; then the safe but slow way
            for (int a = firstArc; a < m; a++) {
                if (d[from[a]] + w[a] < d[to[a]] - EPS) {
                    Cycle c = relax(a, queue);
                    if (c != null) return c;
                    if (budget < 0) return bellmanFord();
                }
            }
            while (!queue.isEmpty()) {
                int u = queue.poll();
                queued[u] = 0;
                for (int i = 0; i < outSize[u]; i++) {
                    int a = out[u][i];
                    if (d[u] + w[a] < d[to[a]] - EPS) {
                        Cycle c = relax(a, queue);
                        if (c != null) return c;
                        if (budget < 0) return bellmanFord();
                    }
                }
            }
            return null;
        }

        /** Lowers the potential of the head of arc {@code a}; returns the cycle if this closes one in the parent tree. */
        private Cycle relax(int a, ArrayDeque<Integer> queue) {
            int u = from[a], v = to[a];
            // v an ancestor of u (or u itself): the parent arcs from u up to v plus a form a negative cycle
            int x = u;
            for (int steps = 0; steps <= n; steps++) {
                if (x == v) return cycleThrough(a);
                if (stamp[x] != round) break;
                x = from[parent[x]];
                budget--;
            }
            budget--;
            setPotential(v, d[u] + w[a]);
            stamp[v] = round;
            parent[v] = a;
            if (queued[v] != round) {
                queued[v] = round;
                queue.add(v);
            }
            return null;
        }

        private Cycle cycleThrough(int a) {
            List<Constraint> owners = new ArrayList<>();
            double total = w[a];
            owners.add(owner[a]);
            int x = from[a], v = to[a];
            for (int steps = 0; x != v && stamp[x] == round && steps <= n; steps++) {
                int p = parent[x];
                total += w[p];
                owners.add(owner[p]);
                x = from[p];
            }
            return new Cycle(axis, owners, total);
        }

        /**
         * Safety net if the incremental search runs too long: Bellman-Ford over the whole graph from potential 0, and the
         * cycle found by walking the parents once a pass still relaxes. Without a cycle its distances become the new
         * potential.
         */
        private Cycle bellmanFord() {
            double[] dist = new double[n];
            int[] par = new int[n];
            Arrays.fill(par, -1);
            int last = -1;
            for (int pass = 0; pass <= n; pass++) {
                last = -1;
                for (int a = 0; a < m; a++) {
                    if (dist[from[a]] + w[a] < dist[to[a]] - EPS) {
                        dist[to[a]] = dist[from[a]] + w[a];
                        par[to[a]] = a;
                        last = to[a];
                    }
                }
                if (last < 0) {
                    for (int v = 0; v < n; v++) if (d[v] != dist[v]) setPotential(v, dist[v]);
                    return null;
                }
            }
            int x = last;
            for (int i = 0; i < n && par[x] >= 0; i++) x = from[par[x]];   // now on the cycle
            Set<Constraint> owners = new LinkedHashSet<>();
            double total = 0;
            int y = x;
            do {
                int p = par[y];
                if (p < 0) break;
                total += w[p];
                owners.add(owner[p]);
                y = from[p];
            } while (y != x);
            return new Cycle(axis, new ArrayList<>(owners), total);
        }
    }
}
