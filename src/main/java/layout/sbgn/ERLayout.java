package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintFeasibility;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.HorizontalConstraint;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * The alternative ER layouts (-Dcblayout.erMode=contract or node), in which every hyperedge node gets an axis: its own
 * arc runs straight through it and every other arc there leaves it perpendicular; the edges are then routed
 * orthogonally along these axes, and no graph element is added or removed. Also holds the arc model and the helpers
 * {@link ERHyperedgeLayout} shares.
 */
final class ERLayout {

    // ------------------------------------------------------------------ switches

    /** -Dcblayout.erPipeline=er|pd (default er): ER maps run through the ER steps, or through the PD steps. */
    static boolean ENABLED = !"pd".equalsIgnoreCase(System.getProperty("cblayout.erPipeline", "er"));
    /** -Dcblayout.erPointLength=f (default 0.5): ideal length factor of a segment between two points of one arc. */
    static double POINT_LENGTH = ConstraintSBGNLayout.doubleProperty("cblayout.erPointLength", 0.5);
    /** -Dcblayout.erGap=px (default 20): free space between two consecutive nodes of an arc, edge to edge. */
    static double GAP = ConstraintSBGNLayout.doubleProperty("cblayout.erGap", 20);
    /**
     * -Dcblayout.erFit=edge|guard (default edge): each constraint group is tested in tier order and left out if it
     * does not fit the ones accepted before it, or all groups go to the feasibility guard at once.
     */
    static boolean FIT_PER_EDGE = !"guard".equalsIgnoreCase(System.getProperty("cblayout.erFit", "edge"));
    /**
     * -Dcblayout.erRouting=orthogonal|polyline (default orthogonal): every edge is routed axis-parallel along the
     * planned sides of its hyperedge nodes, or as in PD.
     */
    static boolean ROUTE_ORTHOGONAL = !"polyline".equalsIgnoreCase(System.getProperty("cblayout.erRouting", "orthogonal"));
    /**
     * -Dcblayout.erAxis=joint|local (default joint): the axes of an arc's hyperedge nodes are chosen together (see
     * planArc), or each from the force-directed chord between its two neighbours.
     */
    static boolean AXIS_LOCAL = "local".equalsIgnoreCase(System.getProperty("cblayout.erAxis", "joint"));
    /**
     * -Dcblayout.erSlots=loops|all (default loops): only the two legs of a U, or every arc that meets a glyph side
     * straight, may take a slot beside the centre of that side.
     */
    static boolean SLOTS_ALL = "all".equalsIgnoreCase(System.getProperty("cblayout.erSlots", "loops"));
    /** -Dcblayout.erOperators=sides|free (default sides): a logical operator gets input and output sides, or none. */
    static boolean OPERATOR_SIDES = !"free".equalsIgnoreCase(System.getProperty("cblayout.erOperators", "sides"));
    /**
     * -Dcblayout.erMode=contract|node (default hyperedge: {@link ERHyperedgeLayout}): contract leaves the hyperedge
     * nodes out of steps 1 and 2, which lay out each arc between two glyphs as one edge, and puts them back in step 3;
     * node plans them directly in 3 steps.
     */
    static boolean CONTRACT = !"node".equalsIgnoreCase(System.getProperty("cblayout.erMode", "contract"));
    /**
     * -Dcblayout.erProxyLength=f (default 1.5, 0: none): ideal length factor of the edges that tie a glyph in the
     * contracted graph to the end glyphs of the arcs it influences.
     */
    static double PROXY_LENGTH = ConstraintSBGNLayout.doubleProperty("cblayout.erProxyLength", 1.5);
    /** -Dcblayout.erDebug=true|false (default false): prints the statistics of each step, each constraint group that gives way, and each U-loop. */
    static boolean DEBUG = Boolean.getBoolean("cblayout.erDebug");
    /** Space between two connected components placed next to each other. */
    static final double COMPONENT_GAP = 80;

    // ------------------------------------------------------------------ state

    private final ConstraintSBGNLayout host;
    private final Graph graph;

    /**
     * One SBGN arc: the graph edges with the same arc id, as a path in edge direction. SBGN-ED splits an arc into a
     * chain of graph edges through its own points, its outcomes and the ports where other arcs attach (role
     * HYPEREDGENODE, 1 x 1 px), and gives all of them the arc's id (sbgn glyphid).
     */
    static final class Arc {
        final String id;
        final String role;
        /** Source glyph, the arc's own points, target glyph. Source == target for an arc from a glyph to itself. */
        final List<Node> chain = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        Arc(String id, String role) { this.id = id; this.role = role; }

        Node source() { return chain.get(0); }
        Node target() { return chain.get(chain.size() - 1); }
        boolean selfLoop() { return source() == target(); }
        List<Node> points() { return chain.subList(1, chain.size() - 1); }
    }

    final List<Arc> arcs = new ArrayList<>();
    /** Point (outcome or port) -> the arc it lies on. */
    private final Map<Node, Arc> ownerOf = new HashMap<>();
    /**
     * Node -> sides ("up", "down", "left", "right") already in use: at a hyperedge node by its own arc and by one other
     * arc per side, at a glyph by an edge aligned with its centre.
     */
    private final Map<Node, Set<String>> taken = new HashMap<>();
    private final List<List<Node>> components = new ArrayList<>();

    ERLayout(ConstraintSBGNLayout host) {
        this.host = host;
        this.graph = host.graph;
    }

    // ------------------------------------------------------------------ roles and geometry

    static boolean isPort(Node n) {
        return "HYPEREDGENODE".equals(ConstraintSBGNLayout.getSBGNRole(n));
    }

    static boolean isOutcome(Node n) {
        return "OUTCOME".equals(ConstraintSBGNLayout.getSBGNRole(n));
    }

    static boolean isPoint(Node n) {
        return isPort(n) || isOutcome(n);
    }

    /** Arcs between entities; everything else (influences, logic arcs) usually ends on one of them. */
    private static boolean isRelation(Arc a) {
        return a.role.contains("INTERACTION") || a.role.contains("ASSIGNMENT");
    }

    private static String key(Node n) {
        return TopologySkeleton.key(n);
    }

    static final Comparator<Node> BY_KEY = Comparator.comparing(ERLayout::key);

    private static double x(Node n) { return AttributeHelper.getPositionX(n); }
    private static double y(Node n) { return AttributeHelper.getPositionY(n); }
    private static double w(Node n) { return AttributeHelper.getWidth(n); }
    private static double h(Node n) { return AttributeHelper.getHeight(n); }

    private static String opposite(String side) {
        switch (side) {
            case "up": return "down";
            case "down": return "up";
            case "left": return "right";
            default: return "left";
        }
    }

    // ------------------------------------------------------------------ step 1: force-directed

    /**
     * Step 1: preparation as in PD (docking, compartments, tags) and one force-directed run, in contract mode over the
     * glyphs only, otherwise over all nodes with a shorter ideal length between two points of one arc.
     */
    void forceDirected() {
        host.prepareActivityFlow();
        // a plan left from an earlier run (GUI: Reset, then steps 1 and 3) must not steer this routing
        clearSides();
        arcAxis.clear();
        arcForward.clear();
        skeletonSlots.clear();
        buildArcs();
        buildComponents();
        if (CONTRACT) {
            Map<Node, double[]> loopOffsets = loopOffsets();
            solveContracted(Collections.<Constraint>emptyList());
            placePoints(loopOffsets);
        } else {
            solve(Collections.<Constraint>emptyList());
        }
        if (DEBUG) System.out.println("[cblayout] ER: " + arcs.size() + " arcs, " + ownerOf.size() + " points, "
                + components.size() + " component(s)" + (CONTRACT ? ", " + skeletonArcs().size() + " arcs between two glyphs (k12)" : ""));
    }

    // ------------------------------------------------------------------ contracted graph (erMode=contract)

    /** The arcs between two different glyphs: in the contracted graph each is one edge k12 between its ends. */
    private List<Arc> skeletonArcs() {
        List<Arc> l = new ArrayList<>();
        for (Arc a : arcs) if (!a.selfLoop() && !isPoint(a.source()) && !isPoint(a.target())) l.add(a);
        return l;
    }

    /** The glyphs a node stands for in the contracted graph: itself, or for a hyperedge node the ends of its arc. */
    private Set<Node> anchors(Node n, int depth) {
        Set<Node> s = new LinkedHashSet<>();
        if (!isPoint(n)) { s.add(n); return s; }
        Arc owner = ownerOf.get(n);
        if (owner == null || depth > 6) return s;
        s.addAll(anchors(owner.source(), depth + 1));
        if (!owner.selfLoop()) s.addAll(anchors(owner.target(), depth + 1));
        return s;
    }

    /**
     * One solver run on the contracted graph: the glyphs only; an arc between two glyphs is one edge (ideal length
     * growing with its number of nodes, so they fit in later); an arc from a glyph to a hyperedge node (an influence,
     * a logic arc) ties the glyph to the glyphs that node stands for ({@link #PROXY_LENGTH}); arcs between two
     * hyperedge nodes and arcs from a glyph back to itself are left out. The hyperedge nodes are not moved.
     */
    private void solveContracted(Collection<Constraint> all) {
        List<Constraint> cs = ConstraintConflicts.active(all);   // also for the snapping and packing below
        List<Node> nodes = new ArrayList<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(graph)) if (!isPoint(n)) nodes.add(n);
        nodes.sort(BY_KEY);
        List<Node[]> edges = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        for (Arc a : arcs) {
            if (a.selfLoop()) continue;
            boolean ps = isPoint(a.source()), pt = isPoint(a.target());
            if (!ps && !pt) {
                edges.add(new Node[]{ a.source(), a.target() });
                lengths.add(1.0 + POINT_LENGTH * a.points().size());
            } else if (ps != pt && PROXY_LENGTH > 0) {
                // an influence or logic arc at a hyperedge node keeps its glyph near the glyphs that node stands for
                Node glyph = ps ? a.target() : a.source(), point = ps ? a.source() : a.target();
                for (Node anchor : anchors(point, 0)) {
                    if (anchor == glyph) continue;
                    edges.add(new Node[]{ glyph, anchor });
                    lengths.add(PROXY_LENGTH);
                }
            }
        }
        CBLayoutAlgorithm.solveNodes(nodes, null, edges, lengths, cs, true);
        snapAlignments(cs);
        packComponents(cs);
    }

    /** Offset of every node of an arc from a glyph back to itself, relative to that glyph (it moves with the glyph). */
    private Map<Node, double[]> loopOffsets() {
        Map<Node, double[]> m = new HashMap<>();
        for (Arc a : arcs) {
            if (!a.selfLoop()) continue;
            for (Node p : a.points()) m.put(p, new double[]{ x(p) - x(a.source()), y(p) - y(a.source()) });
        }
        return m;
    }

    /**
     * Puts the hyperedge nodes back after a contracted solve: the nodes of an arc evenly on the straight line between
     * its two ends (border to border), arcs between glyphs first, then arcs whose end nodes are placed; the nodes of an
     * arc back to its own glyph keep their offset from that glyph.
     */
    private void placePoints(Map<Node, double[]> loopOffsets) {
        Set<Node> placed = new HashSet<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(graph)) if (!isPoint(n)) placed.add(n);
        for (Arc a : arcs) {
            if (!a.selfLoop()) continue;
            for (Node p : a.points()) {
                double[] o = loopOffsets.get(p);
                if (o != null) AttributeHelper.setPosition(p, x(a.source()) + o[0], y(a.source()) + o[1]);
                placed.add(p);
            }
        }
        List<Arc> open = new ArrayList<>();
        for (Arc a : arcs) if (!a.selfLoop() && !a.points().isEmpty()) open.add(a);
        boolean progress = true;
        while (!open.isEmpty() && progress) {
            progress = false;
            for (Iterator<Arc> it = open.iterator(); it.hasNext(); ) {
                Arc a = it.next();
                if (!placed.contains(a.source()) || !placed.contains(a.target())) continue;
                List<Node> pts = a.points();
                Node s = a.source(), t = a.target();
                for (int i = 0; i < pts.size(); i++) {
                    double f = (i + 1.0) / (pts.size() + 1.0);
                    AttributeHelper.setPosition(pts.get(i), x(s) + f * (x(t) - x(s)), y(s) + f * (y(t) - y(s)));
                    placed.add(pts.get(i));
                }
                it.remove();
                progress = true;
            }
        }
    }

    /**
     * Step 2 in contract mode: PD step 2 on the contracted graph. Every arc between two glyphs (k12) gets the axis of
     * its force-directed angle, the two glyphs are aligned on it (the centre slot of each side, one line per side) and
     * put in the arc's order with room for its hyperedge nodes; if a side is taken, the other axis; otherwise only the
     * order. Arcs with more nodes first, then shorter ones. Constrained force-directed run on the contracted graph.
     */
    void contractedSkeleton() {
        for (Constraint c : lastSkeleton) ConstraintStore.constraints.removeConstraint(c);
        lastSkeleton.clear();
        arcAxis.clear();
        arcForward.clear();
        skeletonSlots.clear();
        List<Arc> order = skeletonArcs();
        order.sort(Comparator.comparing((Arc a) -> -a.points().size()).thenComparingDouble(ERLayout::endDistance));
        Constraints accepted = testBase(false);
        List<Constraint> created = new ArrayList<>();
        int aligned = 0, rotated = 0, orderOnly = 0;
        for (Arc a : order) {
            Node u = a.source(), v = a.target();
            boolean pref = horizontalOrVertical(u, v);
            boolean done = false;
            for (boolean hor : new boolean[]{ pref, !pref }) {
                boolean plus = coordinate(v, hor) >= coordinate(u, hor);
                String su = slotKey(side(hor, plus), 0), sv = slotKey(side(hor, !plus), 0);
                if (!free(u, su) || !free(v, sv) || skeletonSlotTaken(u, su) || skeletonSlotTaken(v, sv)) continue;
                List<Constraint> cs = new ArrayList<>();
                cs.add(plus ? roomFor(a, u, v, hor) : roomFor(a, v, u, hor));
                cs.add(alignment(u, v, hor));
                if (!fits(accepted, cs)) continue;
                skeletonSlots.computeIfAbsent(u, k -> new HashMap<>()).put(su, a);
                skeletonSlots.computeIfAbsent(v, k -> new HashMap<>()).put(sv, a);
                arcAxis.put(a, hor);
                arcForward.put(a, plus);
                for (Constraint c : cs) accepted.addConstraint(c);
                created.addAll(cs);
                aligned++;
                if (hor != pref) rotated++;
                done = true;
                break;
            }
            if (!done) {
                boolean plus = coordinate(v, pref) >= coordinate(u, pref);
                List<Constraint> cs = Collections.<Constraint>singletonList(plus ? roomFor(a, u, v, pref) : roomFor(a, v, u, pref));
                if (fits(accepted, cs)) {
                    for (Constraint c : cs) accepted.addConstraint(c);
                    created.addAll(cs);
                    orderOnly++;
                }
            }
        }
        for (Constraint c : created) ConstraintStore.constraints.addConstraint(c);
        lastSkeleton.addAll(created);
        if (DEBUG) System.out.println("[cblayout] ER contracted skeleton: " + order.size() + " arcs between glyphs, " + aligned
                + " on a line (" + rotated + " on the other axis), " + orderOnly + " with the order only, "
                + created.size() + " constraints");
        host.enforceFeasibility("ER Skeleton");
        Map<Node, double[]> loopOffsets = loopOffsets();
        solveContracted(ConstraintStore.constraints.getAllConstraints());
        placePoints(loopOffsets);
    }

    private boolean skeletonSlotTaken(Node glyph, String key) {
        Map<String, Arc> m = skeletonSlots.get(glyph);
        return m != null && m.containsKey(key);
    }

    /** {@code second} beyond {@code first} along the axis, with room for the arc's hyperedge nodes between them. */
    private static SeparationConstraintGraph roomFor(Arc a, Node first, Node second, boolean horizontal) {
        double d = (horizontal ? w(first) + w(second) : h(first) + h(second)) / 2 + (a.points().size() + 1) * GAP;
        for (Node p : a.points()) d += horizontal ? w(p) : h(p);
        return rawSeparation(first, second, horizontal, d);
    }

    /**
     * Step 3 in contract mode: the hyperedge nodes are back (placePoints put them on their arc's line). A node of an
     * arc whose k12 line is aligned takes that line's axis and direction, so it lies in line with the arc's ends; every
     * other node is planned as in node mode. Then the node rules (see planAndConstrain) and a constrained run over all
     * glyphs.
     */
    void hyperedgeNodes() {
        clearPlan();
        planAndConstrain(true);
    }

    /** Groups the edges by arc id and orders each group as a path; a group that is no path splits into single edges. */
    void buildArcs() {
        arcs.clear();
        ownerOf.clear();
        Map<String, List<Edge>> byId = new LinkedHashMap<>();
        List<Edge> all = ConstraintSBGNLayout.layoutEdges(graph);
        Map<Edge, String> structural = null;
        for (Edge e : all) {
            Object id = AttributeHelper.getAttributeValue(e, "sbgn", "glyphid", "", "", false);
            String k = id == null ? "" : id.toString().trim();
            if (k.isEmpty()) {
                // no arc id (a map from SBGN-ED's examples tab, a .graphml): the arc is read from the structure
                if (structural == null) structural = structuralArcIds(all);
                k = structural.get(e);
            }
            byId.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Edge>> en : byId.entrySet()) {
            List<Edge> group = en.getValue();
            List<Node> chain = chainOf(group);
            if (chain != null) {
                addArc(en.getKey(), group, chain);
            } else {
                int i = 0;
                for (Edge e : group) addArc(en.getKey() + "#" + (i++), Collections.singletonList(e), Arrays.asList(e.getSource(), e.getTarget()));
            }
        }
        arcs.sort(Comparator.comparing((Arc a) -> key(a.source())).thenComparing(a -> key(a.target())).thenComparing(a -> a.id));
    }

    /**
     * Arc ids for a map whose edges carry none: an arc runs on through each of its own nodes (outcome or port), so at
     * every such node one incoming and one outgoing edge of the same role are joined, the straightest such pair in the
     * drawing if there are several; every other edge at the node (an influence, a logic arc) starts or ends there.
     */
    private static Map<Edge, String> structuralArcIds(List<Edge> edges) {
        Map<Edge, Edge> parent = new HashMap<>();
        for (Edge e : edges) parent.put(e, e);
        Map<Node, List<Edge>> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        for (Edge e : edges) {
            if (e.getSource() == e.getTarget()) continue;
            if (isPoint(e.getTarget())) in.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
            if (isPoint(e.getSource())) out.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<Node, List<Edge>> en : in.entrySet()) {
            Node p = en.getKey();
            Edge bestIn = null, bestOut = null;
            double best = Double.NEGATIVE_INFINITY;
            for (Edge i : en.getValue()) {
                for (Edge o : out.getOrDefault(p, Collections.<Edge>emptyList())) {
                    if (!ConstraintSBGNLayout.getSBGNRole(i).equals(ConstraintSBGNLayout.getSBGNRole(o))) continue;
                    double ax = x(p) - x(i.getSource()), ay = y(p) - y(i.getSource());
                    double bx = x(o.getTarget()) - x(p), by = y(o.getTarget()) - y(p);
                    double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
                    double straight = la < 1e-9 || lb < 1e-9 ? 0 : (ax * bx + ay * by) / (la * lb);
                    if (straight > best) { best = straight; bestIn = i; bestOut = o; }
                }
            }
            if (bestIn == null) continue;
            Edge ra = bestIn, rb = bestOut;
            while (parent.get(ra) != ra) ra = parent.get(ra);
            while (parent.get(rb) != rb) rb = parent.get(rb);
            if (ra != rb) parent.put(rb, ra);
        }
        Map<Edge, String> ids = new HashMap<>();
        Map<Edge, Integer> number = new HashMap<>();
        for (Edge e : edges) {
            Edge r = e;
            while (parent.get(r) != r) r = parent.get(r);
            Integer n = number.get(r);
            if (n == null) number.put(r, n = number.size());
            ids.put(e, "\u0001" + n);
        }
        return ids;
    }

    private void addArc(String id, List<Edge> edges, List<Node> chain) {
        Arc a = new Arc(id, ConstraintSBGNLayout.getSBGNRole(edges.get(0)));
        a.edges.addAll(edges);
        a.chain.addAll(chain);
        for (Node p : a.points()) ownerOf.putIfAbsent(p, a);
        arcs.add(a);
    }

    /** The nodes of {@code group} in edge direction, or null if the edges do not form one path (or one loop). */
    private static List<Node> chainOf(List<Edge> group) {
        Map<Node, Edge> out = new HashMap<>();
        Map<Node, Integer> in = new HashMap<>();
        for (Edge e : group) {
            if (out.put(e.getSource(), e) != null) return null;
            in.merge(e.getTarget(), 1, Integer::sum);
        }
        for (int c : in.values()) if (c > 1) return null;
        Node start = null;
        for (Edge e : group) if (!in.containsKey(e.getSource())) { start = e.getSource(); break; }
        if (start == null) {
            // closed: an arc from a glyph back to itself, starting at the glyph
            for (Edge e : group) if (!isPoint(e.getSource())) { start = e.getSource(); break; }
            if (start == null) return null;
        }
        List<Node> chain = new ArrayList<>();
        chain.add(start);
        Node cur = start;
        for (int i = 0; i < group.size(); i++) {
            Edge e = out.get(cur);
            if (e == null) return null;
            cur = e.getTarget();
            chain.add(cur);
            if (cur == start) break;
        }
        return chain.size() == group.size() + 1 ? chain : null;
    }

    private void buildComponents() {
        components.clear();
        components.addAll(componentsOf(graph));
    }

    /** The connected components of {@code graph}, the largest first; nodes and components in a fixed order. */
    static List<List<Node>> componentsOf(Graph graph) {
        List<List<Node>> components = new ArrayList<>();
        Map<Node, List<Node>> adj = new HashMap<>();
        for (Edge e : graph.getEdges()) {
            adj.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e.getTarget());
            adj.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e.getSource());
        }
        Set<Node> seen = new HashSet<>();
        List<Node> nodes = new ArrayList<>(ConstraintSBGNLayout.layoutNodes(graph));
        nodes.sort(BY_KEY);
        for (Node s : nodes) {
            if (!seen.add(s)) continue;
            List<Node> comp = new ArrayList<>();
            Deque<Node> queue = new ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                Node v = queue.poll();
                comp.add(v);
                for (Node nb : adj.getOrDefault(v, Collections.<Node>emptyList())) if (seen.add(nb)) queue.add(nb);
            }
            comp.sort(BY_KEY);
            components.add(comp);
        }
        components.sort((a, b) -> a.size() != b.size() ? Integer.compare(b.size(), a.size()) : key(a.get(0)).compareTo(key(b.get(0))));
        return components;
    }

    // ------------------------------------------------------------------ step 2: skeleton constraints

    /** Hyperedge node (outcome or port) -> its own arc runs through it horizontally (true) or vertically. */
    private final Map<Node, Boolean> axisOf = new HashMap<>();
    /** Hyperedge node -> the next node of its own arc lies on the + side (right / below) along its axis. */
    private final Map<Node, Boolean> forwardOf = new HashMap<>();
    /** Arcs from a glyph back to itself (drawn as a U), with the glyph side the U stands on. */
    private final Map<Arc, String> loopSide = new HashMap<>();
    /** Logical operator -> the side its output leaves on; its inputs arrive on the opposite side. */
    private final Map<Node, String> opOutSide = new HashMap<>();
    /** Planned side at both ends of every chain edge ("arc id#index of the first node"), handed to the router. */
    private final Map<String, String[]> plannedSides = new HashMap<>();
    /** Direction of every planned edge end ("arc id#index#0" / "#1"), computed once (it may claim a node side). */
    private final Map<String, String> endSide = new HashMap<>();
    /** Ends ("arc id#index#0" / "#1") whose order constraint had to give way: the router reads their side from positions. */
    private final Set<String> looseEnds = new HashSet<>();
    /** The constraints the node step added last time, removed when it runs again (Run all twice, no reset). */
    private final List<Constraint> lastCreated = new ArrayList<>();
    /** Contracted mode: the constraints the skeleton step added last time. */
    private final List<Constraint> lastSkeleton = new ArrayList<>();
    /** Contracted mode: arc between two glyphs -> axis of its k12 line (true = horizontal), if that line was aligned. */
    private final Map<Arc, Boolean> arcAxis = new HashMap<>();
    /** Contracted mode: arc -> its target lies on the + side of its source along arcAxis. */
    private final Map<Arc, Boolean> arcForward = new HashMap<>();
    /** Contracted mode, step 3: arcs between two glyphs were placed by the skeleton step (no glyph lines here). */
    private boolean glyphLinesDone;
    /** Contracted mode: glyph slot keys the k12 line of an arc took in the skeleton step ("side@0" -> that arc). */
    private final Map<Node, Map<String, Arc>> skeletonSlots = new HashMap<>();
    /** Accepted / dropped constraint groups per tier (see skeletonConstraints). */
    private final int[] tierAccepted = new int[TIERS], tierDropped = new int[TIERS];
    private int lShaped, cShaped, sideFlipped, glyphSideTaken;
    /**
     * Tiers of the constraint groups: 0 the order along a node's own arc, 1 the side of every other arc at a node,
     * 2 straight pieces of an arc, 3 straight pieces between two arcs, 4 edges meeting a glyph straight.
     */
    private static final int TIERS = 5;
    /** Distance of two slots on a glyph side, and the least distance of a slot from the side's corner (px). */
    static final double SLOT = 24, SLOT_INSET = 6;
    /** Viterbi weights: an axis against the force-directed chord, an influence that would not leave perpendicular. */
    private static final double W_CHORD = 1.0, W_FOREIGN = 0.3;

    /** An edge (index {@code i} of its first node in the arc's chain) with the side it leaves each end on (null at a glyph). */
    private static final class PlannedEdge {
        final Arc arc;
        final int i;
        final Node u, v;
        final String du, dv;
        PlannedEdge(Arc arc, int i, Node u, Node v, String du, String dv) {
            this.arc = arc; this.i = i; this.u = u; this.v = v; this.du = du; this.dv = dv;
        }
        String key() { return arc.id + "#" + i; }
    }

    /** Constraints that are accepted or dropped together, and the glyph sides they take. */
    private static final class Group {
        final int tier;
        final String kind;
        final PlannedEdge edge;
        final List<Constraint> cs = new ArrayList<>();
        final List<Object[]> claims = new ArrayList<>();   // {Node, side or slot key}
        /** The group holds the order constraint that makes the edge leave u (v) on its planned side. */
        boolean securesU, securesV;
        /** A line through a glyph slot: the node, the glyph, the side of the glyph it meets (chosen when accepted). */
        Node slotNode, slotGlyph;
        String slotSide;
        Group(int tier, String kind, PlannedEdge edge) { this.tier = tier; this.kind = kind; this.edge = edge; }
    }

    /**
     * Step 2 in node mode: every hyperedge node gets an axis along which its own arc runs straight through it, every
     * other arc there leaves it perpendicular, and constraints make that shape possible; a constrained run.
     */
    void skeletonConstraints() {
        clearPlan();
        planAndConstrain(false);
    }

    private void clearPlan() {
        clearSides();
        for (Constraint c : lastCreated) ConstraintStore.constraints.removeConstraint(c);
        lastCreated.clear();
        Arrays.fill(tierAccepted, 0);
        Arrays.fill(tierDropped, 0);
        lShaped = cShaped = sideFlipped = glyphSideTaken = 0;
    }

    /** Forgets the axes and sides of the last plan. */
    private void clearSides() {
        taken.clear();
        axisOf.clear();
        forwardOf.clear();
        loopSide.clear();
        opOutSide.clear();
        plannedSides.clear();
        endSide.clear();
        looseEnds.clear();
    }

    /**
     * The node rules: axes, sides, constraint groups in tiers, a constrained run over all glyphs. With
     * {@code fromSkeleton} (contracted mode) the nodes of an arc whose k12 line was aligned take that line.
     */
    private void planAndConstrain(boolean fromSkeleton) {
        List<Arc> order = new ArrayList<>(arcs);
        order.sort(Comparator.comparing((Arc a) -> !isRelation(a))
                .thenComparing(a -> -a.points().size())
                .thenComparingDouble(ERLayout::endDistance));
        // 1. Axis and direction of every hyperedge node, arc by arc; statements first, so an influence can read the
        //    axis of the node it ends on.
        int fromLine = 0;
        for (Arc a : order) {
            if (a.points().isEmpty()) continue;
            if (fromSkeleton && arcAxis.containsKey(a)) {
                boolean hor = arcAxis.get(a);
                for (Node p : a.points()) {
                    axisOf.put(p, hor);
                    forwardOf.put(p, arcForward.get(a));
                    claim(p, side(hor, false));
                    claim(p, side(hor, true));
                }
                fromLine++;
            } else if (a.selfLoop()) planLoop(a);
            else planArc(a);
        }
        if (DEBUG && fromSkeleton) System.out.println("[cblayout] ER: nodes of " + fromLine + " arcs in line with their k12 ends");
        glyphLinesDone = fromSkeleton;
        planOperators();
        // 2. The side each edge leaves each hyperedge node (and logical operator) on. Where both ends would leave the
        //    same way, the end at another arc's node turns round, so the edge stays straight or an L, not a C.
        List<PlannedEdge> planned = new ArrayList<>();
        for (Arc a : order) {
            for (int i = 1; i < a.chain.size(); i++) {
                Node u = a.chain.get(i - 1), v = a.chain.get(i);
                if (u == v) continue;
                String du = end(a, i - 1, 0, u, true, v), dv = end(a, i - 1, 1, v, false, u);
                if (du != null && du.equals(dv)) {
                    if (turnable(a, u) && free(u, opposite(dv))) du = turn(a, i - 1, 0, u, du, opposite(dv));
                    else if (turnable(a, v) && free(v, opposite(du))) dv = turn(a, i - 1, 1, v, dv, opposite(du));
                }
                PlannedEdge e = new PlannedEdge(a, i - 1, u, v, du, dv);
                planned.add(e);
                plannedSides.put(e.key(), new String[]{ e.du, e.dv });
            }
        }
        // 3. Constraint groups in tiers (see TIERS) over all edges, so a cosmetic alignment never costs a node its
        //    rule; each is tested against the user's constraints and the groups accepted before it.
        List<Group> groups = new ArrayList<>();
        for (PlannedEdge e : planned) groups.addAll(groupsOf(e));
        groups.sort(Comparator.comparingInt(g -> g.tier));   // stable: edge order within a tier
        Constraints accepted = testBase(true);
        // contracted mode: the skeleton step's lines hold, the node groups have to fit them
        if (fromSkeleton) for (Constraint c : lastSkeleton) accepted.addConstraint(c);
        List<Constraint> created = new ArrayList<>();
        for (Group g : groups) {
            if (g.slotGlyph != null) {
                List<Constraint> cs = slotConstraints(g, accepted);
                if (cs == null) {
                    glyphSideTaken++;
                    continue;
                }
                tierAccepted[g.tier]++;
                for (Constraint c : cs) accepted.addConstraint(c);
                created.addAll(cs);
                continue;
            }
            boolean sidesFree = true;
            for (Object[] c : g.claims) sidesFree &= free((Node) c[0], (String) c[1]);
            if (!sidesFree) {
                glyphSideTaken++;
                continue;
            }
            if (!fits(accepted, g.cs)) {
                tierDropped[g.tier]++;
                if (g.securesU) looseEnds.add(g.edge.key() + "#0");
                if (g.securesV) looseEnds.add(g.edge.key() + "#1");
                if (DEBUG) System.out.println("[cblayout] ER dropped tier " + g.tier + " " + g.kind + ": " + describe(g.edge.arc)
                        + " " + label(g.edge.u) + " -> " + label(g.edge.v));
                continue;
            }
            tierAccepted[g.tier]++;
            for (Object[] c : g.claims) claim((Node) c[0], (String) c[1]);
            for (Constraint c : g.cs) accepted.addConstraint(c);
            created.addAll(g.cs);
        }
        for (Constraint c : created) ConstraintStore.constraints.addConstraint(c);
        lastCreated.addAll(created);
        if (DEBUG) System.out.println("[cblayout] ER skeleton: " + axisOf.size() + " hyperedge nodes, " + loopSide.size() + " U-loops, "
                + opOutSide.size() + " logical operators, "
                + lShaped + " L and " + cShaped + " C edges, " + sideFlipped + " arcs moved to the free side of a node, "
                + glyphSideTaken + " glyph lines without a free slot; groups accepted per tier " + Arrays.toString(tierAccepted)
                + ", dropped " + Arrays.toString(tierDropped) + ", " + created.size() + " constraints");
        host.enforceFeasibility("ER Skeleton");
        solve(ConstraintStore.constraints.getAllConstraints());
    }

    /**
     * The user's constraints that a solve applies, against which the constraint groups are tested. A set that
     * contradicts itself would make every test fail, so it is then left out of the tests; the solver still gets it.
     */
    private Constraints testBase(boolean report) {
        Constraints accepted = Constraints.activeUserConstraintsOf(ConstraintStore.constraints, graph);
        if (!FIT_PER_EDGE || accepted.getAllConstraints().isEmpty()) return accepted;
        if (feasibilityWarnings(accepted, Collections.<Constraint>emptyList()) == 0 && feasible
                && differenceFeasible(accepted.getAllConstraints())) return accepted;
        if (report) System.out.println("[cblayout] ER: the user's constraints contradict each other;"
                + " step 2 tests its groups without them");
        return new Constraints();
    }

    /** Whether the group {@code cs} fits {@code accepted} ({@link #FIT_PER_EDGE}; without it every group fits). */
    private boolean fits(Constraints accepted, List<Constraint> cs) {
        return !FIT_PER_EDGE || feasibilityWarnings(accepted, cs) == 0 && feasible;
    }

    /**
     * Axes of the nodes of an arc between two different ends, chosen jointly (Viterbi over H/V per node): an axis
     * against the force-directed chord through the node costs up to {@link #W_CHORD}, an influence at the node that
     * would not leave perpendicular up to {@link #W_FOREIGN}, a change of axis between two nodes (an L) 1, a straight
     * piece whose ends are not in line up to 2 (a Z), an end that does not meet its glyph (or the node it starts or ends
     * on) straight 1. Each run of nodes with one axis then gets one direction, from the force-directed pass.
     */
    private void planArc(Arc a) {
        List<Node> pts = a.points();
        int k = pts.size();
        double[][] unary = new double[k][2];
        for (int i = 0; i < k; i++) {
            Node p = pts.get(i), prev = a.chain.get(i), next = a.chain.get(i + 2);
            unary[i][0] += W_CHORD * offAxis(prev, next, true);
            unary[i][1] += W_CHORD * offAxis(prev, next, false);
            for (Edge e : p.getEdges()) {
                if (a.edges.contains(e)) continue;
                Node w = e.getSource() == p ? e.getTarget() : e.getSource();
                unary[i][0] += W_FOREIGN * offAxis(p, w, false);   // along H the influence should be vertical
                unary[i][1] += W_FOREIGN * offAxis(p, w, true);
            }
        }
        for (int s = 0; s < 2; s++) {
            unary[0][s] += endCost(a.chain.get(0), pts.get(0), s == 0);
            unary[k - 1][s] += endCost(a.chain.get(k + 1), pts.get(k - 1), s == 0);
        }
        double[][] cost = new double[k][2];
        int[][] back = new int[k][2];
        cost[0] = unary[0].clone();
        for (int i = 1; i < k; i++) {
            for (int s = 0; s < 2; s++) {
                double best = Double.POSITIVE_INFINITY;
                for (int t = 0; t < 2; t++) {
                    double c = cost[i - 1][t] + (s == t ? zCost(pts.get(i - 1), pts.get(i), s == 0) : 1.0);
                    if (c < best - 1e-9) { best = c; back[i][s] = t; }
                }
                cost[i][s] = best + unary[i][s];
            }
        }
        int[] state = new int[k];
        state[k - 1] = cost[k - 1][1] < cost[k - 1][0] - 1e-9 ? 1 : 0;
        for (int i = k - 1; i > 0; i--) state[i - 1] = back[i][state[i]];
        if (AXIS_LOCAL) for (int i = 0; i < k; i++) state[i] = horizontalOrVertical(a.chain.get(i), a.chain.get(i + 2)) ? 0 : 1;
        for (int r = 0; r < k; ) {
            int t = r;
            while (t + 1 < k && state[t + 1] == state[r]) t++;
            boolean hor = state[r] == 0;
            // one direction for the run, from the force-directed positions of its nodes and its two neighbours
            double sum = 0;
            for (int i = r; i <= t + 1; i++) sum += coordinate(a.chain.get(i + 1), hor) - coordinate(a.chain.get(i), hor);
            for (int i = r; i <= t; i++) {
                Node p = pts.get(i);
                axisOf.put(p, hor);
                forwardOf.put(p, sum >= 0);
                claim(p, side(hor, false));
                claim(p, side(hor, true));
            }
            r = t + 1;
        }
    }

    /**
     * Logical operators (and, or, not, delay) get an input side and the opposite output side, as in the ER spec's
     * figures: the output leaves towards the side its target node is entered from (a port: the side planned for it,
     * perpendicular to the port's arc; else along the force-directed direction), all inputs arrive on the opposite side.
     */
    private void planOperators() {
        if (!OPERATOR_SIDES) return;
        for (Node op : sortedOperators()) {
            Arc outArc = null;
            for (Arc a : arcs) if (a.source() == op && !a.selfLoop()) { outArc = a; break; }
            String out;
            if (outArc != null) {
                Node target = outArc.chain.get(1);
                // the output faces the side its first node is entered from (the port side, planned and claimed now)
                String atTarget = axisOf.containsKey(target) ? end(outArc, 0, 1, target, false, op) : null;
                if (atTarget != null) {
                    out = opposite(atTarget);
                } else {
                    boolean hor = horizontalOrVertical(op, target);
                    out = side(hor, coordinate(target, hor) >= coordinate(op, hor));
                }
            } else {
                double cx = 0, cy = 0;
                int n = 0;
                for (Arc a : arcs) if (a.target() == op && !a.selfLoop()) { Node in = a.chain.get(a.chain.size() - 2); cx += x(in); cy += y(in); n++; }
                if (n == 0) continue;
                cx = cx / n - x(op);
                cy = cy / n - y(op);
                boolean hor = Math.abs(cx) >= Math.abs(cy);
                out = side(hor, (hor ? cx : cy) < 0);   // away from the inputs
            }
            opOutSide.put(op, out);
        }
    }

    private List<Node> sortedOperators() {
        List<Node> ops = new ArrayList<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(graph)) if (AFLayout.isLogicOperator(n)) ops.add(n);
        ops.sort(BY_KEY);
        return ops;
    }

    /**
     * An arc from a glyph back to itself becomes a U on the glyph side its nodes lie on in the force-directed pass (if
     * that side has room for the legs, see below): the first third of the nodes on the leg away from the glyph, the
     * last third on the leg back to it, the rest on the base; a single node lies on the base, both its edges turn back
     * into the glyph. An influence attaches on the outside of the U (the inner sides count as taken).
     */
    private void planLoop(Arc a) {
        Node g = a.source();
        List<Node> pts = a.points();
        int k = pts.size();
        double cx = 0, cy = 0;
        for (Node p : pts) { cx += x(p); cy += y(p); }
        cx /= k;
        cy /= k;
        double dx = cx - x(g), dy = cy - y(g);
        int n1 = k >= 2 ? (k + 2) / 3 : 0, n3 = n1;
        if (n1 + n3 > k) { n1 = k / 2; n3 = k - n1; }
        // The side the nodes lie on in the force-directed pass, unless its slots cannot hold the two legs that far
        // apart (the base between them needs its separations); then the side with room that faces the nodes best.
        String s = null;
        double bestScore = Double.NEGATIVE_INFINITY, bestRoom = Double.NEGATIVE_INFINITY;
        String roomiest = null;
        for (String side : new String[]{ "right", "left", "down", "up" }) {
            boolean legs = horizontalSide(side);   // legs along x for a left / right side
            double span = 0;
            if (k >= 2) {
                List<Node> across = new ArrayList<>(pts.subList(n1 - 1, k - n3 + 1));   // leg 1's last node, the base, leg 2's first
                for (int i = 1; i < across.size(); i++) {
                    Node u = across.get(i - 1), v = across.get(i);
                    span += (legs ? h(u) + h(v) : w(u) + w(v)) / 2 + GAP;
                }
            }
            double half = (legs ? h(g) : w(g)) / 2;
            double room = 2 * Math.floor(Math.max(0, half - SLOT_INSET) / SLOT) * SLOT;
            double score = (side.equals("right") ? dx : side.equals("left") ? -dx : side.equals("down") ? dy : -dy)
                    / (legs ? w(g) : h(g));
            if (room > bestRoom) { bestRoom = room; roomiest = side; }
            if (room + 1e-6 >= span && score > bestScore) { bestScore = score; s = side; }
        }
        if (s == null) s = roomiest;
        loopSide.put(a, s);
        boolean legH = horizontalSide(s);                      // the legs run along the side's normal
        boolean out = s.equals("right") || s.equals("down");   // away from the glyph is + along the legs
        double leg1 = 0, leg2 = 0;
        for (int i = 0; i < Math.max(1, n1); i++) leg1 += coordinate(pts.get(i), !legH) / Math.max(1, n1);
        for (int i = k - Math.max(1, n3); i < k; i++) leg2 += coordinate(pts.get(i), !legH) / Math.max(1, n3);
        boolean baseForward = leg2 >= leg1;
        if (DEBUG) System.out.println("[cblayout] ER U-loop " + describe(a) + " side " + s + ", legs " + n1 + "/" + n3
                + ", base " + (k - n1 - n3) + ", base forward " + baseForward);
        for (int i = 0; i < k; i++) {
            Node p = pts.get(i);
            String inner;
            if (i < n1) {
                axisOf.put(p, legH);
                forwardOf.put(p, out);
                inner = side(!legH, baseForward);
            } else if (i >= k - n3) {
                axisOf.put(p, legH);
                forwardOf.put(p, !out);
                inner = side(!legH, !baseForward);
            } else {
                axisOf.put(p, !legH);
                forwardOf.put(p, baseForward);
                inner = side(legH, !out);
            }
            claim(p, side(axisOf.get(p), false));
            claim(p, side(axisOf.get(p), true));
            claim(p, inner);
        }
    }

    /** sin^2 of the angle between the line u-v and the axis (0 on the axis, 1 across it; 0 for coinciding nodes). */
    private static double offAxis(Node u, Node v, boolean horizontal) {
        double dx = x(v) - x(u), dy = y(v) - y(u), l2 = dx * dx + dy * dy;
        if (l2 < 1e-9) return 0;
        return (horizontal ? dy * dy : dx * dx) / l2;
    }

    /** Cost of a straight piece between two nodes on one axis that are not in line: up to 2 bends (a Z). */
    private static double zCost(Node u, Node v, boolean horizontal) {
        return Math.min(2, 3 * offAxis(u, v, horizontal));
    }

    /**
     * Cost of the edge between an arc's end node {@code end} and its first (last) node {@code p} on axis
     * {@code horizontal}: at a glyph 0 if p lies within the glyph's extent across that axis (the edge meets it
     * straight), else 1 (an L); at another arc's node with a known axis the edge leaves that node perpendicular, so
     * the same axis gives a straight piece (or a Z) and the other one an L.
     */
    private double endCost(Node end, Node p, boolean horizontal) {
        Boolean endAxis = axisOf.get(end);
        if (endAxis != null) return !endAxis == horizontal ? zCost(end, p, horizontal) : 1.0;
        if (isPoint(end)) return 0;   // its arc is not planned yet
        return horizontal ? (Math.abs(y(p) - y(end)) <= h(end) / 2 ? 0 : 1) : (Math.abs(x(p) - x(end)) <= w(end) / 2 ? 0 : 1);
    }

    /** The planned direction of end {@code end} (0 = first node) of chain edge {@code i} of arc {@code a}, computed once. */
    private String end(Arc a, int i, int end, Node at, boolean otherIsNext, Node other) {
        String key = a.id + "#" + i + "#" + end;
        if (!endSide.containsKey(key)) endSide.put(key, direction(a, at, otherIsNext, other));
        return endSide.get(key);
    }

    /** An end whose side the plan may turn: at another arc's hyperedge node (not an operator, not the node's own arc). */
    private boolean turnable(Arc a, Node at) {
        Arc owner = ownerOf.get(at);
        return owner != null && owner != a && !opOutSide.containsKey(at);
    }

    private String turn(Arc a, int i, int end, Node at, String from, String to) {
        Set<String> t = taken.get(at);
        if (t != null) t.remove(from);
        claim(at, to);
        endSide.put(a.id + "#" + i + "#" + end, to);
        return to;
    }

    /**
     * The side on which the edge of arc {@code a} from {@code at} to {@code other} (the next node of the chain if
     * {@code otherIsNext}, else the previous one) leaves {@code at}; null at a glyph (any side). At a logical operator:
     * its output side for the arc that leaves it, the opposite side for its inputs. At a hyperedge node of
     * {@code a} itself: along the node's axis, towards {@code other}. At a hyperedge node of another arc (an influence
     * ending on a port, an arc leaving an outcome): perpendicular to that axis, on the side the force-directed pass
     * shows; a side takes one such edge, a second one goes to the opposite side if that is free.
     */
    private String direction(Arc a, Node at, boolean otherIsNext, Node other) {
        String out = opOutSide.get(at);
        if (out != null) return a.source() == at ? out : opposite(out);   // output leaves, inputs arrive opposite
        Boolean hor = axisOf.get(at);
        if (hor == null) return null;
        if (ownerOf.get(at) == a) return side(hor, forwardOf.get(at) == otherIsNext);
        boolean across = !hor;
        String s = side(across, coordinate(other, across) >= coordinate(at, across));
        if (!free(at, s) && free(at, opposite(s))) {
            s = opposite(s);
            sideFlipped++;
        }
        claim(at, s);
        return s;
    }

    /**
     * The constraint groups of one edge. Both ends at hyperedge nodes: facing each other on one axis, a straight piece
     * (order, then alignment); leaving to the same side on one axis, a C (the ends apart across the axis only);
     * different axes, an L (each end's far node beyond it along its side). A node and a glyph: the glyph beyond the
     * node, and the node in a free slot of that glyph side (see slotConstraints). Two glyphs: aligned along their
     * force-directed axis if both centre slots are free.
     */
    private List<Group> groupsOf(PlannedEdge e) {
        List<Group> gs = new ArrayList<>();
        Node u = e.u, v = e.v;
        boolean ownU = ownerOf.get(u) == e.arc, ownV = ownerOf.get(v) == e.arc;
        if (e.du != null && e.dv != null) {
            boolean alongX = horizontalSide(e.du);
            if (alongX == horizontalSide(e.dv)) {
                if (e.du.equals(opposite(e.dv))) {
                    Group order = group(gs, ownU || ownV ? 0 : 1, "order", e);
                    order.cs.add(beyond(u, v, e.du));
                    order.securesU = order.securesV = true;
                    group(gs, ownU && ownV ? 2 : 3, "straight", e).cs.add(alignment(u, v, alongX));
                } else {
                    boolean plus = coordinate(v, !alongX) >= coordinate(u, !alongX);
                    group(gs, 1, "C", e).cs.add(plus ? separation(u, v, !alongX) : separation(v, u, !alongX));
                    cShaped++;
                }
            } else {
                Group atU = group(gs, ownU ? 0 : 1, "L at source", e);
                atU.cs.add(beyond(u, v, e.du));
                atU.securesU = true;
                Group atV = group(gs, ownV ? 0 : 1, "L at target", e);
                atV.cs.add(beyond(v, u, e.dv));
                atV.securesV = true;
                lShaped++;
            }
            return gs;
        }
        if (e.du != null || e.dv != null) {
            Node p = e.du != null ? u : v, g = e.du != null ? v : u;
            String dp = e.du != null ? e.du : e.dv;
            String loop = loopSide.get(e.arc);
            if (loop != null && horizontalSide(dp) != horizontalSide(loop)) {
                // the single node of a one-node U lies on the base: both its edges run along the base and turn back
                // into the glyph, which lies across the base from it
                Group back = group(gs, 0, "one-node U", e);
                back.cs.add(beyond(p, g, opposite(loop)));
                return gs;
            }
            Group order = group(gs, ownerOf.get(p) == e.arc ? 0 : 1, "order at glyph", e);
            order.cs.add(beyond(p, g, dp));
            if (p == u) order.securesU = true;
            else order.securesV = true;
            Group line = group(gs, 4, "glyph slot", e);
            line.slotNode = p;
            line.slotGlyph = g;
            line.slotSide = opposite(dp);
            return gs;
        }
        if (glyphLinesDone) return gs;   // contracted mode: the skeleton step placed arcs between two glyphs
        boolean hor = horizontalOrVertical(u, v);
        String su = side(hor, coordinate(v, hor) >= coordinate(u, hor));
        Group line = group(gs, 4, "glyph line", e);
        line.cs.add(beyond(u, v, su));
        line.cs.add(alignment(u, v, hor));
        line.claims.add(new Object[]{ u, slotKey(su, 0) });
        line.claims.add(new Object[]{ v, slotKey(opposite(su), 0) });
        return gs;
    }

    /**
     * Where a node's edge meets a glyph straight: one of the glyph's slots on that side, the centre or a multiple of
     * {@link #SLOT} away from it (at least {@link #SLOT_INSET} from the corners, as in Fig. 1.2 of the SBGN ER
     * specification). The legs of a U take slots beside the centre, every other arc the centre ({@link #SLOTS_ALL}:
     * any slot). The free slot closest to the node's force-directed offset that fits the constraints accepted so far
     * wins: the centre by an alignment, any other slot by an equality separation across the edge. Null if none is free
     * or fits.
     */
    private List<Constraint> slotConstraints(Group g, Constraints accepted) {
        Node p = g.slotNode, glyph = g.slotGlyph;
        boolean edgeHorizontal = horizontalSide(g.slotSide);
        boolean across = !edgeHorizontal;   // the coordinate that picks the slot: y for a horizontal edge
        double half = (edgeHorizontal ? h(glyph) : w(glyph)) / 2;
        int max = (int) Math.floor(Math.max(0, half - SLOT_INSET) / SLOT);
        double want = coordinate(p, across) - coordinate(glyph, across);
        // the legs of a U always stand on both sides of the centre; other arcs take the centre unless erSlots=all
        boolean leg = loopSide.containsKey(g.edge.arc);
        List<Integer> slots = new ArrayList<>();
        for (int i = -max; i <= max; i++) if (leg ? i != 0 : i == 0 || SLOTS_ALL) slots.add(i);
        slots.sort(Comparator.comparingDouble((Integer i) -> Math.abs(i * SLOT - want)).thenComparingInt(Math::abs));
        for (int i : slots) {
            String key = slotKey(g.slotSide, i);
            Map<String, Arc> skel = skeletonSlots.get(glyph);
            Arc lineOwner = skel == null ? null : skel.get(key);
            if (lineOwner != null && lineOwner != g.edge.arc) continue;   // another arc's k12 line
            if (lineOwner == null && !free(glyph, key)) continue;
            List<Constraint> cs = new ArrayList<>();
            double off = i * SLOT;
            if (i == 0) cs.add(alignment(p, glyph, edgeHorizontal));
            else cs.add(off > 0 ? fixedSeparation(glyph, p, across, off) : fixedSeparation(p, glyph, across, -off));
            if (!fits(accepted, cs)) continue;
            claim(glyph, key);
            return cs;
        }
        return null;
    }

    private static String slotKey(String side, int slot) {
        return side + "@" + slot;
    }

    private static Group group(List<Group> gs, int tier, String kind, PlannedEdge e) {
        Group g = new Group(tier, kind, e);
        gs.add(g);
        return g;
    }

    /** {@code other} lies beyond {@code at} on side {@code side}, {@link #GAP} apart edge to edge. */
    private static SeparationConstraintGraph beyond(Node at, Node other, String side) {
        switch (side) {
            case "right": return separation(at, other, true);
            case "left": return separation(other, at, true);
            case "down": return separation(at, other, false);
            default: return separation(other, at, false);
        }
    }

    /** Same y (horizontal) or same x. */
    private static Constraint alignment(Node a, Node b, boolean horizontal) {
        List<Node> pair = new ArrayList<>(Arrays.asList(a, b));
        return horizontal ? new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE)
                          : new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE);
    }

    private static String side(boolean horizontal, boolean plus) {
        return horizontal ? (plus ? "right" : "left") : (plus ? "down" : "up");
    }

    private static boolean horizontalSide(String side) {
        return side.equals("left") || side.equals("right");
    }

    private void claim(Node n, String side) {
        taken.computeIfAbsent(n, k -> new HashSet<>()).add(side);
    }

    private boolean free(Node n, String side) {
        Set<String> s = taken.get(n);
        return s == null || !s.contains(side);
    }

    private static String label(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        return l == null || l.isEmpty() ? ConstraintSBGNLayout.getSBGNRole(n) + "#" + n.getID() : l.replaceAll("<[^>]*>", "");
    }

    private static String describe(Arc a) {
        StringBuilder sb = new StringBuilder(a.id + " " + a.role + " [");
        for (Node n : a.chain) sb.append(label(n)).append(' ');
        return sb.append(']').toString();
    }

    /** Set by {@link #feasibilityWarnings}: whether the guard would keep every constraint. */
    private boolean feasible;

    /**
     * Runs the guard on a copy of {@code accepted} plus {@code extra}; returns its warning count, sets {@link #feasible}.
     * Once an equality separation (a U leg's slot) is involved, the guard is not enough: it sees a separation only as
     * "at least", so a cycle closed by the "at most" half of an equality slips through and cola reports it unsatisfiable;
     * then the difference constraints are checked as well (see differenceFeasible).
     */
    private int feasibilityWarnings(Constraints accepted, List<Constraint> extra) {
        Constraints test = new Constraints();
        for (Constraint c : accepted.getAllConstraints()) test.addConstraint(c);
        for (Constraint c : extra) {
            c.setAutomatic(true);
            test.addConstraint(c);
        }
        List<Constraint> all = new ArrayList<>(test.getAllConstraints());
        feasible = ConstraintFeasibility.enforce(test).isEmpty();
        int warnings = ConstraintFeasibility.lastWarnings.size();
        if (feasible) {
            boolean equality = false;
            for (Constraint c : all) equality |= c instanceof SeparationConstraintGraph && ((SeparationConstraintGraph) c).isFixed();
            if (equality) feasible = differenceFeasible(all);
        }
        return warnings;
    }

    /**
     * Whether the separations and alignments of {@code cs} can hold together, per dimension: each is a difference
     * constraint (coordinate(b) >= coordinate(a) + gap; an equality or alignment in both directions), and the set is
     * feasible exactly when the graph of these arcs has no cycle of positive total gap (Bellman-Ford, longest paths).
     */
    static boolean differenceFeasible(Collection<Constraint> cs) {
        for (int dim = 0; dim < 2; dim++) {
            Map<Node, Integer> index = new HashMap<>();
            List<int[]> from = new ArrayList<>();
            List<Double> gap = new ArrayList<>();
            for (Constraint c : cs) {
                List<Node> ns = c.getNodes();
                if (c instanceof SeparationConstraintGraph && ns.size() == 2) {
                    SeparationConstraintGraph sc = (SeparationConstraintGraph) c;
                    if ((sc.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL ? 0 : 1) != dim) continue;
                    int a = idx(index, ns.get(0)), b = idx(index, ns.get(1));
                    from.add(new int[]{ a, b });
                    gap.add(sc.getDistance());
                    if (sc.isFixed()) { from.add(new int[]{ b, a }); gap.add(-sc.getDistance()); }
                } else if (dim == 0 ? c instanceof VerticalConstraint : c instanceof HorizontalConstraint) {
                    // cola puts every member at guideline + offset (CBLayoutAlgorithm.create*Align): member j - member 0
                    // is exactly offset(j) - offset(0)
                    for (int i = 1; i < ns.size(); i++) {
                        int a = idx(index, ns.get(0)), b = idx(index, ns.get(i));
                        double d = alignOffset(c, ns.get(i)) - alignOffset(c, ns.get(0));
                        from.add(new int[]{ a, b });
                        gap.add(d);
                        from.add(new int[]{ b, a });
                        gap.add(-d);
                    }
                }
            }
            int n = index.size();
            double[] dist = new double[n];
            boolean changed = true;
            for (int round = 0; round <= n && changed; round++) {
                changed = false;
                for (int e = 0; e < from.size(); e++) {
                    int a = from.get(e)[0], b = from.get(e)[1];
                    if (dist[a] + gap.get(e) > dist[b] + 1e-6) {
                        if (round == n) return false;   // still growing after n rounds: a positive cycle
                        dist[b] = dist[a] + gap.get(e);
                        changed = true;
                    }
                }
            }
        }
        return true;
    }

    /** The offset from the guideline CBLayoutAlgorithm hands cola for {@code n} in alignment {@code c}. */
    private static double alignOffset(Constraint c, Node n) {
        if (c instanceof HorizontalConstraint) {
            HorizontalConstraint.HorizontalConstraintType t = ((HorizontalConstraint) c).getType();
            return t == HorizontalConstraint.HorizontalConstraintType.TOP ? h(n) / 2
                    : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? -h(n) / 2 : 0;
        }
        VerticalConstraint.VerticalConstraintType t = ((VerticalConstraint) c).getType();
        return t == VerticalConstraint.VerticalConstraintType.LEFT ? w(n) / 2
                : t == VerticalConstraint.VerticalConstraintType.RIGHT ? -w(n) / 2 : 0;
    }

    private static int idx(Map<Node, Integer> index, Node n) {
        Integer i = index.get(n);
        if (i == null) { i = index.size(); index.put(n, i); }
        return i;
    }

    private static double endDistance(Arc a) {
        Node p = a.selfLoop() && a.chain.size() > 2 ? a.chain.get(1) : a.source();
        Node q = a.selfLoop() && a.chain.size() > 2 ? a.chain.get(a.chain.size() - 2) : a.target();
        return Math.hypot(x(q) - x(p), y(q) - y(p));
    }

    /** {@code second} lies right of (horizontal) or below {@code first}, {@link #GAP} apart edge to edge. */
    private static SeparationConstraintGraph separation(Node first, Node second, boolean horizontal) {
        return rawSeparation(first, second, horizontal, horizontal ? (w(first) + w(second)) / 2 + GAP : (h(first) + h(second)) / 2 + GAP);
    }

    /** {@code second}'s centre lies exactly {@code distance} right of (horizontal) or below {@code first}'s. */
    static SeparationConstraintGraph fixedSeparation(Node first, Node second, boolean horizontal, double distance) {
        return new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(first, second)),
                horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                           : SeparationConstraintGraph.SeparationConstraintType.VERTICAL, true, distance);
    }

    /** {@code second}'s centre lies at least {@code distance} right of (horizontal) or below {@code first}'s. */
    static SeparationConstraintGraph rawSeparation(Node first, Node second, boolean horizontal, double distance) {
        return new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(first, second)),
                horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                           : SeparationConstraintGraph.SeparationConstraintType.VERTICAL, false, distance);
    }

    private static double coordinate(Node n, boolean horizontal) {
        return horizontal ? x(n) : y(n);
    }

    /** True if the line between the two nodes is at most 45 degrees off the horizontal (PD step 2's rule). */
    private static boolean horizontalOrVertical(Node a, Node b) {
        double dx = Math.abs(x(b) - x(a)), dy = Math.abs(y(b) - y(a));
        return dy <= dx;
    }

    // ------------------------------------------------------------------ last step: edge routing

    /**
     * The last step: tags back, the edges routed (orthogonally along the planned sides, {@link #ROUTE_ORTHOGONAL}),
     * compartments back and the drawing moved to the top left.
     */
    void route() {
        ERRouting routing = new ERRouting();
        if (ROUTE_ORTHOGONAL) {
            for (Arc a : arcs) {
                for (int i = 1; i < a.chain.size(); i++) {
                    Node u = a.chain.get(i - 1), v = a.chain.get(i);
                    String key = a.id + "#" + (i - 1);
                    String[] dirs = { leaveDirection(a, key, 0, u, v), leaveDirection(a, key, 1, v, u) };
                    double[][] pass = dirs[0] != null && dirs[0].equals(dirs[1]) ? cBase(u, v, dirs[0]) : stubs(u, v, dirs);
                    for (Edge e : a.edges) {
                        if (e.getSource() != u || e.getTarget() != v) continue;
                        routing.directions.put(e, dirs);
                        if (pass != null) routing.checkpoints.put(e, pass);
                    }
                }
            }
        }
        host.finishActivityFlow(ROUTE_ORTHOGONAL, routing);
    }

    /**
     * Where an end at a hyperedge node (or operator) does not point at the other end (it lies less than a stub beyond it
     * on that side, e.g. an edge that turns into a glyph whose side is taken), or the edge is a straight piece whose
     * alignment had to give way (a Z), a point {@link #GAP} beyond the node on its side, so the route leaves it straight
     * for that long before it turns (spec Fig. 1.2: at least 21 px). Null if no end needs one.
     */
    private static double[][] stubs(Node u, Node v, String[] dirs) {
        boolean su = dirs[0] != null && !dirs[0].startsWith("h") && !dirs[0].startsWith("v");
        boolean sv = dirs[1] != null && !dirs[1].startsWith("h") && !dirs[1].startsWith("v");
        // a straight piece whose alignment had to give way becomes a Z: its jog belongs in the middle, not at a node
        boolean z = su && sv && dirs[0].equals(opposite(dirs[1]))
                && Math.abs(coordinate(u, !horizontalSide(dirs[0])) - coordinate(v, !horizontalSide(dirs[0]))) > 0.5;
        List<double[]> pass = new ArrayList<>();
        if (su && (z || !ahead(u, v, dirs[0]))) pass.add(stub(u, dirs[0]));
        if (sv && (z || !ahead(v, u, dirs[1]))) pass.add(stub(v, dirs[1]));
        return pass.isEmpty() ? null : pass.toArray(new double[0][]);
    }

    /** Whether {@code other} lies at least a stub beyond {@code at} on {@code side}. */
    private static boolean ahead(Node at, Node other, String side) {
        boolean alongX = horizontalSide(side);
        double sign = sign(side);
        double d = sign * (coordinate(other, alongX) - coordinate(at, alongX));
        return d >= (alongX ? w(at) + w(other) : h(at) + h(other)) / 2 + GAP - 1e-6;
    }

    /** +1 for a side towards growing coordinates (right, down), else -1. */
    private static double sign(String side) {
        return side.equals("right") || side.equals("down") ? 1 : -1;
    }

    private static double[] stub(Node at, String side) {
        boolean alongX = horizontalSide(side);
        double sign = sign(side);
        double d = sign * ((alongX ? w(at) : h(at)) / 2 + GAP);
        return alongX ? new double[]{ x(at) + d, y(at) } : new double[]{ x(at), y(at) + d };
    }

    /**
     * The two corners a C-shaped edge (both ends leave on side {@code side}) has to pass: {@link #GAP} beyond the farther
     * of its two nodes on that side, in line with each node, so both nodes are left straight and the base is straight.
     */
    private static double[][] cBase(Node u, Node v, String side) {
        boolean alongX = horizontalSide(side);
        double sign = sign(side);
        double fu = coordinate(u, alongX) + sign * (alongX ? w(u) : h(u)) / 2;
        double fv = coordinate(v, alongX) + sign * (alongX ? w(v) : h(v)) / 2;
        double base = (sign > 0 ? Math.max(fu, fv) : Math.min(fu, fv)) + sign * GAP;
        return alongX ? new double[][]{ { base, y(u) }, { base, y(v) } } : new double[][]{ { x(u), base }, { x(v), base } };
    }

    /**
     * The direction in which the edge of arc {@code a} (chain edge {@code key}, end 0 = its first node) leaves
     * {@code at} towards {@code other}: the side step 2 planned (along the node's own arc, perpendicular to it for
     * another arc's edge, the input or output side at a logical operator); null at a glyph or without a plan. Only an
     * end at another arc's node whose order constraint had to give way takes the side from the final positions, on the
     * planned axis.
     */
    private String leaveDirection(Arc a, String key, int end, Node at, Node other) {
        String[] planned = plannedSides.get(key);
        String side = planned == null ? null : planned[end];
        if (side == null) return null;
        boolean own = ownerOf.get(at) == a || opOutSide.containsKey(at);
        if (own || !looseEnds.contains(key + "#" + end)) return side;
        boolean alongX = horizontalSide(side);
        double d = alongX ? x(other) - x(at) : y(other) - y(at);
        if (Math.abs(d) < 1e-6) return alongX ? "horizontal" : "vertical";   // either side of that axis
        return alongX ? (d > 0 ? "right" : "left") : (d > 0 ? "down" : "up");
    }

    // ------------------------------------------------------------------ solver calls

    /** A force-directed run over all glyphs with {@code cs}, then the components side by side. */
    private void solve(Collection<Constraint> all) {
        List<Constraint> cs = ConstraintConflicts.active(all);   // also for the snapping and packing below
        List<Node> nodes = new ArrayList<>(ConstraintSBGNLayout.layoutNodes(graph));
        nodes.sort(BY_KEY);
        List<Node[]> edges = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        for (Arc a : arcs) {
            for (int i = 1; i < a.chain.size(); i++) {
                Node u = a.chain.get(i - 1), v = a.chain.get(i);
                edges.add(new Node[]{ u, v });
                boolean inside = ownerOf.get(u) == a && ownerOf.get(v) == a;
                lengths.add(inside ? POINT_LENGTH : 1.0);
            }
        }
        CBLayoutAlgorithm.solveNodes(nodes, null, edges, lengths, cs, true);
        snapAlignments(cs);
        packComponents(cs);
    }

    /**
     * Gives every class of centre-aligned nodes one exact coordinate (their mean): cola leaves sub-pixel differences,
     * and the orthogonal router would turn them into small jogs next to the hyperedge nodes.
     */
    static void snapAlignments(Collection<Constraint> cs) {
        for (boolean sameY : new boolean[]{ true, false }) {
            Map<Node, Node> parent = new LinkedHashMap<>();
            for (Constraint c : cs) {
                boolean middle = sameY ? c instanceof HorizontalConstraint
                        && ((HorizontalConstraint) c).getType() == HorizontalConstraint.HorizontalConstraintType.MIDDLE
                        : c instanceof VerticalConstraint
                        && ((VerticalConstraint) c).getType() == VerticalConstraint.VerticalConstraintType.MIDDLE;
                if (!middle || c.getNodes().size() < 2) continue;
                Node first = c.getNodes().get(0);
                for (Node n : c.getNodes()) union(parent, first, n);
            }
            Map<Node, List<Node>> classes = new LinkedHashMap<>();
            for (Node n : parent.keySet()) classes.computeIfAbsent(find(parent, n), k -> new ArrayList<>()).add(n);
            for (List<Node> cls : classes.values()) {
                double mean = 0;
                for (Node n : cls) mean += sameY ? y(n) : x(n);
                mean /= cls.size();
                for (Node n : cls) AttributeHelper.setPosition(n, sameY ? x(n) : mean, sameY ? mean : y(n));
            }
        }
    }

    static Node find(Map<Node, Node> parent, Node n) {
        parent.putIfAbsent(n, n);
        Node r = n;
        while (parent.get(r) != r) r = parent.get(r);
        while (parent.get(n) != r) { Node next = parent.get(n); parent.put(n, r); n = next; }
        return r;
    }

    static void union(Map<Node, Node> parent, Node a, Node b) {
        Node ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(rb, ra);
    }

    /**
     * cola exerts no force between components, so they are shelved next to each other (largest first) after every
     * solve. Components joined by a constraint (a user's) move as one unit, so every translation keeps the constraints.
     */
    private void packComponents(Collection<Constraint> cs) {
        packComponents(components, cs);
    }

    /** {@link #packComponents(Collection)} for the given components. */
    static void packComponents(List<List<Node>> components, Collection<Constraint> cs) {
        if (components.size() < 2) return;
        // a constraint between two components (a user's) makes them one unit, moved together
        Map<Node, Integer> compOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) for (Node n : components.get(i)) compOf.put(n, i);
        int[] unit = new int[components.size()];
        for (int i = 0; i < unit.length; i++) unit[i] = i;
        for (Constraint c : cs) {
            Integer first = null;
            for (Node n : c.getNodes()) {
                Integer ci = compOf.get(n);
                if (ci == null) continue;
                if (first == null) first = ci;
                else { int ra = root(unit, first), rb = root(unit, ci); if (ra != rb) unit[Math.max(ra, rb)] = Math.min(ra, rb); }
            }
        }
        List<List<Node>> units = new ArrayList<>();
        Map<Integer, List<Node>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) byRoot.computeIfAbsent(root(unit, i), k -> new ArrayList<>()).addAll(components.get(i));
        units.addAll(byRoot.values());
        if (units.size() < 2) return;
        double[][] boxes = new double[units.size()][];
        double widest = 0, area = 0;
        for (int i = 0; i < units.size(); i++) {
            double[] b = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
            for (Node n : units.get(i)) {
                b[0] = Math.min(b[0], x(n) - w(n) / 2);
                b[1] = Math.min(b[1], y(n) - h(n) / 2);
                b[2] = Math.max(b[2], x(n) + w(n) / 2);
                b[3] = Math.max(b[3], y(n) + h(n) / 2);
            }
            boxes[i] = b;
            widest = Math.max(widest, b[2] - b[0]);
            area += (b[2] - b[0] + COMPONENT_GAP) * (b[3] - b[1] + COMPONENT_GAP);
        }
        // shelves about as wide as a square of the total area, so several large units do not stack into one column
        double shelfWidth = Math.max(widest, Math.max(1000, 1.3 * Math.sqrt(area))), left = 0, top = 0, rowHeight = 0;
        for (int i = 0; i < units.size(); i++) {
            double[] b = boxes[i];
            double bw = b[2] - b[0], bh = b[3] - b[1];
            if (left > 0 && left + bw > shelfWidth) {
                left = 0;
                top += rowHeight + COMPONENT_GAP;
                rowHeight = 0;
            }
            double dx = left - b[0], dy = top - b[1];
            for (Node n : units.get(i)) AttributeHelper.setPosition(n, x(n) + dx, y(n) + dy);
            left += bw + COMPONENT_GAP;
            rowHeight = Math.max(rowHeight, bh);
        }
    }

    private static int root(int[] unit, int i) {
        while (unit[i] != i) i = unit[i];
        return i;
    }
}
