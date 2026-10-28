package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintFeasibility;
import constraints.ConstraintCheck;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.HorizontalConstraint;
import constraints.OrthogonalEdgeConstraintGraph;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;
import layout.sbgn.ERAlignmentPlan.Candidate;
import layout.sbgn.ERAlignmentPlan.Chain;
import layout.sbgn.ERAlignmentPlan.Direction;
import layout.sbgn.ERAlignmentPlan.Model;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.plugin.view.ArrowShape;
import org.graffiti.plugins.views.defaults.SupportsHollowDrawing;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * The default ER layout in five steps: the hyperedges are removed and the rest is laid out force-directed, every pair
 * of neighbours gets a horizontal, vertical or diagonal alignment, the hyperedge nodes and then the hyperedges are
 * inserted again, and the edges are routed orthogonally with planned diagonals left straight. VANTED cannot draw an arc
 * that ends on another arc, so SBGN-ED puts a hyperedge node (role HYPEREDGENODE, or an outcome) on the arc it ends on
 * and splits that arc into a chain of edges; a hyperedge is an influence arc ({@link #HYPEREDGE_ROLES}).
 * {@link ERAlignmentPlan} holds the parts of this pipeline that need no graph, {@link ERLayout} the alternative ones.
 */
final class ERHyperedgeLayout {

    // ------------------------------------------------------------------ switches

    /** -Dcblayout.erMode=hyperedge|dfs|contract|node (default hyperedge): hyperedge and dfs select this pipeline. */
    static boolean ENABLED = Arrays.asList("hyperedge", "dfs").contains(System.getProperty("cblayout.erMode", "hyperedge").toLowerCase(Locale.ROOT));
    /** -Dcblayout.erOctilinearEdges=straight|route (default straight): diagonal edges stay straight, or are routed. */
    static boolean OCTILINEAR_STRAIGHT = !"route".equalsIgnoreCase(System.getProperty("cblayout.erOctilinearEdges", "straight"));
    /** -Dcblayout.erSectors=one|nearest (default one): one aligned neighbour per direction at a node, or no limit. */
    static boolean ONE_PER_DIRECTION = !"nearest".equalsIgnoreCase(System.getProperty("cblayout.erSectors", "one"));
    /** -Dcblayout.erOperatorSides=plan|route (default plan): step 2 or only step 5 sets an operator's output side. */
    static boolean PLAN_OPERATOR_SIDES = !"route".equalsIgnoreCase(System.getProperty("cblayout.erOperatorSides", "plan"));
    /** -Dcblayout.erNodeAlign=degrees (default 10, 0: never): step 3 aligns a hyperedge this close to an axis. */
    static double NODE_ALIGN = ConstraintSBGNLayout.doubleProperty("cblayout.erNodeAlign", 10);
    /** -Dcblayout.erAlignments=class|pair (default class): one alignment per row or column, or one per aligned pair. */
    static boolean ALIGNMENT_CLASSES = !"pair".equalsIgnoreCase(System.getProperty("cblayout.erAlignments", "class"));
    /** -Dcblayout.erAlignOrder=on|off (default on): aligned pairs on a row or column also keep their order, or not. */
    static boolean ALIGN_ORDER = !"off".equalsIgnoreCase(System.getProperty("cblayout.erAlignOrder", "on"));
    /** -Dcblayout.erStart=graph|input (default graph): step 1 starts from a run on the whole map, or from the input. */
    static boolean START_FROM_GRAPH = !"input".equalsIgnoreCase(System.getProperty("cblayout.erStart", "graph"));
    /** -Dcblayout.erStartHyperLength=f (default 2): ideal length factor of a hyperedge in the start run of step 1. */
    static double START_HYPER_LENGTH = ConstraintSBGNLayout.doubleProperty("cblayout.erStartHyperLength", 2);
    /** -Dcblayout.erPack=graph|on|off (default graph): packs the parts of the whole map, of the graph now, or none. */
    static boolean PACK = !"off".equalsIgnoreCase(System.getProperty("cblayout.erPack", "graph"));
    static boolean PACK_WHOLE_GRAPH = !"on".equalsIgnoreCase(System.getProperty("cblayout.erPack", "graph"));
    /**
     * -Dcblayout.erIsolated=neighbours|start (default start, neighbours with erStart=input): a node that has only
     * hyperedges starts the run of step 4 between its neighbours, or where it is.
     */
    static boolean START_AT_NEIGHBOURS = "neighbours".equalsIgnoreCase(
            System.getProperty("cblayout.erIsolated", START_FROM_GRAPH ? "start" : "neighbours"));
    /** -Dcblayout.erOverlap=run|feasible (default run): runs keep nodes apart, or overlaps are removed afterwards. */
    static boolean OVERLAPS_IN_RUN = !"feasible".equalsIgnoreCase(System.getProperty("cblayout.erOverlap", "run"));
    /**
     * -Dcblayout.erRunPatience=n (default 20, -1: libcola's convergence test): a run ends after n iterations in a row
     * that do not lower the stress, on the best iterate it has seen.
     */
    static int RUN_PATIENCE = ConstraintSBGNLayout.intProperty("cblayout.erRunPatience", 20);
    /** -Dcblayout.erStub=px (default 20, 0: none): length of the stub at which a hyperedge leaves a hyperedge node. */
    static double STUB = ConstraintSBGNLayout.doubleProperty("cblayout.erStub", 20);
    /** -Dcblayout.erShapeBuffer=px (default 6): distance a route keeps from the nodes it does not end at. */
    static double SHAPE_BUFFER = ConstraintSBGNLayout.doubleProperty("cblayout.erShapeBuffer", 6);
    /** -Dcblayout.erNudge=px (default 6): distance between two parallel routes. */
    static double NUDGING = ConstraintSBGNLayout.doubleProperty("cblayout.erNudge", 6);
    /** -Dcblayout.erStubSnap=keep|snap (default keep): a stub end nudged by the router stays there, or snaps back. */
    static boolean KEEP_NUDGED_STUBS = !"snap".equalsIgnoreCase(System.getProperty("cblayout.erStubSnap", "keep"));
    /**
     * -Dcblayout.erPinSpots=clear|any (default clear): an edge that meets its glyph straight takes a free pin instead
     * of a corner, the spot of another edge or the end of a straight diagonal, or it ends where it meets the glyph.
     */
    static boolean CLEAR_PIN_SPOTS = !"any".equalsIgnoreCase(System.getProperty("cblayout.erPinSpots", "clear"));
    /**
     * -Dcblayout.erPieceAim=stub|node (default stub): a routed piece of an arc leaves its hyperedge node towards the
     * end of the stub at its other end, or towards the node there.
     */
    static boolean AIM_AT_STUBS = !"node".equalsIgnoreCase(System.getProperty("cblayout.erPieceAim", "stub"));
    /**
     * -Dcblayout.erStep3Run=always|constraints (default constraints): step 3 always ends with a run, or only if it
     * added a constraint or an inserted node lies on another node.
     */
    static boolean STEP3_RUN_ALWAYS = "always".equalsIgnoreCase(System.getProperty("cblayout.erStep3Run", "constraints"));

    /** The arcs step 1 removes as hyperedges, by SBGN role; logic arcs, interactions and assignments stay. */
    static final Set<String> HYPEREDGE_ROLES = new HashSet<>(Arrays.asList(
            "MODULATION", "STIMULATION", "NECESSARYSTIMULATION", "ABSOLUTESTIMULATION", "INHIBITION", "ABSOLUTEINHIBITION"));

    /** Non-overlap margin of the solver around a node (CBLayoutAlgorithm.bboxConst) plus its 1 px border, both nodes. */
    private static final double CLEARANCE = 2 * (CBLayoutAlgorithm.bboxConst + 1);
    /** Distance of a logical operator's port from its border (as SBGN-ML ends the arc there). */
    private static final double PORT_DISTANCE = 10;
    /** Step 5: two nodes count as lying on a diagonal if their x and y distances differ by less than this (px). */
    private static final double DIAGONAL_TOLERANCE = 1;
    /** Step 5: a straight diagonal keeps this far (px) from every other node, else it is routed. */
    private static final double LINE_MARGIN = 2;
    /** Step 5: a stub keeps this far (px) from every other node. */
    private static final double STUB_MARGIN = 4;
    /** Step 5: a stub with room for less than this (px) is left out. */
    private static final double SHORTEST_STUB = 8;
    /** Step 5: a symbol's keep-out starts this far (px) beyond the node's border and ends this far before the stub. */
    private static final double KEEP_OUT_GAP = 4, KEEP_OUT_END = 3;
    /** Step 5: a stub whose end the router has nudged stays at least this much (px) longer than its symbol. */
    private static final double SYMBOL_ON_STUB = 3;

    // ------------------------------------------------------------------ state

    private final ConstraintSBGNLayout host;
    private final Graph graph;

    /** All arcs of the map, as step 1 read them (with the nodes and edges the map had then). */
    private final List<ERLayout.Arc> arcs = new ArrayList<>();
    /** The removed hyperedges. */
    private final List<ERLayout.Arc> hyperedges = new ArrayList<>();
    /** Hyperedge node -> the arc it lies on (its own arc: the one that runs through it). */
    private final Map<Node, ERLayout.Arc> ownerOf = new HashMap<>();
    /** What step 1 removed, with the end nodes every edge had. */
    private final List<Node> removedNodes = new ArrayList<>();
    private final List<Edge> removedEdges = new ArrayList<>();
    private final Map<Edge, Node[]> endsOf = new HashMap<>();
    /** A copy an earlier run without Reset inserted -&gt; the node or edge of the input map it stands for (for Reset). */
    private final Map<Node, Node> inputNodeOf = new HashMap<>();
    private final Map<Edge, Edge> inputEdgeOf = new HashMap<>();
    /** The connected parts of the whole map as step 1 found it, hyperedges included: what packing moves as one. */
    private final List<List<Node>> parts = new ArrayList<>();
    /** Removed node / edge -> its copy in the graph once it is inserted again. */
    private final Map<Node, Node> live = new HashMap<>();
    private final Map<Edge, Edge> liveEdge = new LinkedHashMap<>();
    /** The user's constraints on what step 1 removed; they move on to the copies. */
    private final List<Membership> memberships = new ArrayList<>();
    /** Constraints of {@link #memberships} that the removal of their nodes made void (the add-on dropped them). */
    private final Set<Constraint> voidedByRemoval = new HashSet<>();
    private final Map<Edge, List<OrthogonalEdgeConstraintGraph>> edgeConstraints = new HashMap<>();
    /** Steps 2 and 3: the neighbours that have an alignment, by {@link #pairKey}. */
    private final Map<Long, Aligned> aligned = new LinkedHashMap<>();
    /** Node -> the directions in which it has an aligned neighbour (bits of {@link Direction}). */
    private final Map<Node, Integer> taken = new HashMap<>();
    /** Step 2: logical operator -> the side its output leaves on. */
    private final Map<Node, Direction> outSide = new HashMap<>();
    private final List<Constraint> created = new ArrayList<>();
    /** The step that ran last (0: none, or reset). */
    private int stage;
    /** True while a constraint of the user is back in the store and the add-on's lists do not show it yet. */
    private boolean listsChanged;

    /** Two neighbours with an alignment: the direction from a to b, and the constraints that only this pair has. */
    private static final class Aligned {
        final Node a, b;
        final Direction direction;
        final List<Constraint> own = new ArrayList<>();

        Aligned(Node a, Node b, Direction direction) {
            this.a = a;
            this.b = b;
            this.direction = direction;
        }
    }

    /** Removed hyperedge nodes that follow each other on one hyperedge, between two nodes that are in the graph. */
    private static final class Run {
        final Node from, to;
        final List<Node> points;
        /** Several runs between the same two nodes lie side by side: this one's place among them. */
        int lane, lanes = 1;

        Run(Node from, Node to, List<Node> points) {
            this.from = from;
            this.to = to;
            this.points = points;
        }
    }

    /** A hyperedge node at which hyperedges start or end: the two pieces of its own arc, and those hyperedges' edges there. */
    private static final class Junction {
        final Node node;
        final List<Edge> pieces = new ArrayList<>(2);
        final List<Edge> attached = new ArrayList<>();

        Junction(Node node) {
            this.node = node;
        }
    }

    /** A constraint of the user on a removed node, and where the node stood in its list. */
    private static final class Membership {
        final Constraint constraint;
        final Node node;
        final int index;

        Membership(Constraint constraint, Node node, int index) {
            this.constraint = constraint;
            this.node = node;
            this.index = index;
        }
    }

    ERHyperedgeLayout(ConstraintSBGNLayout host) {
        this.host = host;
        this.graph = host.graph;
    }

    private static double x(Node n) { return AttributeHelper.getPositionX(n); }
    private static double y(Node n) { return AttributeHelper.getPositionY(n); }
    private static double w(Node n) { return AttributeHelper.getWidth(n); }
    private static double h(Node n) { return AttributeHelper.getHeight(n); }

    private static final Comparator<Node> ORDER = ERLayout.BY_KEY.thenComparingLong(Node::getID);

    /** The nodes of the graph in a fixed order. */
    private List<Node> sortedNodes() {
        List<Node> nodes = new ArrayList<>(graph.getNodes());
        nodes.sort(ORDER);
        return nodes;
    }

    private static Map<Node, Integer> index(List<Node> nodes) {
        Map<Node, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i), i);
        return index;
    }

    private static long pairKey(Node a, Node b) {
        return Math.min(a.getID(), b.getID()) << 32 | Math.max(a.getID(), b.getID());
    }

    private static void log(String text) {
        if (ERLayout.DEBUG) System.out.println("[cblayout] ER " + text);
    }

    /** A node's label for the log (SBGN-ED writes operator labels as HTML, the delay operator's tau as an entity). */
    private static String name(Node n) {
        String label = AttributeHelper.getLabel(n, "").replaceAll("<[^>]*>", "").replace("&#964;", "tau").trim();
        return label.isEmpty() ? ConstraintSBGNLayout.getSBGNRole(n) : label;
    }

    // ------------------------------------------------------------------ step 1

    /**
     * Step 1: removes the hyperedges and then, again and again, the hyperedge nodes of degree 0 or 1, and lays out what
     * is left force-directed without constraints.
     */
    void removeAndForceDirected() {
        putBack(false);   // a second run without Reset first puts back what the last run removed
        host.prepareActivityFlow();
        removeCreated();

        parts.addAll(ERLayout.componentsOf(graph));
        if (START_FROM_GRAPH) startRun();

        ERLayout reader = new ERLayout(host);
        reader.buildArcs();
        for (ERLayout.Arc a : reader.arcs) {
            arcs.add(a);
            for (Node p : a.points()) ownerOf.putIfAbsent(p, a);
            if (!HYPEREDGE_ROLES.contains(a.role)) continue;
            hyperedges.add(a);
            for (Edge e : a.edges) remove(e);
        }
        int ofHyperedges = removedEdges.size();
        // then, again and again, the hyperedge nodes of degree 0 or 1
        List<Node> nodes = sortedNodes();
        Map<Node, Integer> index = index(nodes);
        List<Edge> edges = new ArrayList<>(graph.getEdges());
        int[][] ends = new int[edges.size()][];
        for (int i = 0; i < ends.length; i++) ends[i] = new int[]{ index.get(edges.get(i).getSource()), index.get(edges.get(i).getTarget()) };
        boolean[] point = new boolean[nodes.size()];
        for (int i = 0; i < point.length; i++) point[i] = ERLayout.isPoint(nodes.get(i));
        for (int i : ERAlignmentPlan.peel(nodes.size(), ends, point)) {
            Node n = nodes.get(i);
            rememberUserConstraints(n);
            host.holdUserConstraints(n, ERHyperedgeLayout::keepsNodeData);
            for (Edge e : new ArrayList<>(n.getEdges())) remove(e);
            removedNodes.add(n);
            graph.deleteNode(n);
        }
        for (Membership m : memberships) {
            if (!ConstraintStore.of(graph).getAllConstraints().contains(m.constraint)) voidedByRemoval.add(m.constraint);
        }
        int others = removedEdges.size() - ofHyperedges;
        log("step 1: removed " + hyperedges.size() + " hyperedges (" + ofHyperedges + " edges) and " + removedNodes.size()
                + " hyperedge nodes of degree 0 or 1" + (others > 0 ? " (with them " + others + " edges that are no hyperedges)" : "")
                + "; left: " + graph.getNumberOfNodes() + " nodes, " + graph.getNumberOfEdges() + " edges");
        stage = 1;
        solve(userConstraints(), true);
    }

    /** The user's constraints in the store: the solves of step 1 apply them like every later solve. */
    private static List<Constraint> userConstraints() {
        List<Constraint> l = new ArrayList<>();
        if (ConstraintStore.constraints == null) return l;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) if (!c.isAutomatic()) l.add(c);
        return l;
    }

    /**
     * The start of step 1 ({@link #START_FROM_GRAPH}): a force-directed run over the whole map without constraints.
     * Nodes may pass each other here (overlaps are only removed at the end), so every part finds the side its
     * hyperedges pull it to; the runs of the five steps keep nodes apart and can no longer change that.
     */
    private void startRun() {
        List<Node[]> edges = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        runEdges(edges, lengths, true);
        CBLayoutAlgorithm.solveNodes(sortedNodes(), null, edges, lengths, userConstraints(), true, RUN_PATIENCE, false);
    }

    /**
     * The edges of the graph for a run, without loops, and their ideal length factors: shorter at a hyperedge node, so
     * that an arc with many nodes does not get many times longer, and longer for a hyperedge in the start run.
     */
    private void runEdges(List<Node[]> edges, List<Double> lengths, boolean startRun) {
        for (Edge e : graph.getEdges()) {
            if (e.getSource() == e.getTarget()) continue;
            edges.add(new Node[]{ e.getSource(), e.getTarget() });
            double length = ERLayout.isPoint(e.getSource()) || ERLayout.isPoint(e.getTarget()) ? ERLayout.POINT_LENGTH : 1.0;
            boolean longer = startRun && HYPEREDGE_ROLES.contains(ConstraintSBGNLayout.getSBGNRole(e));
            lengths.add(longer ? length * START_HYPER_LENGTH : length);
        }
    }

    /** Takes an edge out of the graph and remembers its two ends. */
    private void remove(Edge e) {
        if (ConstraintStore.constraints != null) {
            for (OrthogonalEdgeConstraintGraph c : ConstraintStore.constraints.getConstraints(OrthogonalEdgeConstraintGraph.class)) {
                if (c.getEdge() == e && !c.isAutomatic()) edgeConstraints.computeIfAbsent(e, k -> new ArrayList<>()).add(c);
            }
        }
        removedEdges.add(e);
        endsOf.put(e, new Node[]{ e.getSource(), e.getTarget() });
        graph.deleteEdge(e);
    }

    /**
     * A force-directed run over everything that is in the graph, with {@code cs}. {@code pack}: afterwards the parts
     * that are not connected (of the whole map, see {@link #PACK_WHOLE_GRAPH}) are put side by side, the largest
     * first (parts joined by a constraint move as one).
     */
    private void solve(Collection<Constraint> cs, boolean pack) {
        List<Constraint> active = ConstraintConflicts.active(cs);
        List<Node> nodes = sortedNodes();
        List<Node[]> edges = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        runEdges(edges, lengths, false);
        CBLayoutAlgorithm.solveNodes(nodes, null, edges, lengths, active, true, RUN_PATIENCE, OVERLAPS_IN_RUN);
        ERLayout.snapAlignments(active);
        reportUnsatisfied(active, nodes);
        if (pack && PACK && CBLayoutAlgorithm.absoluteUserConstraintsOn(graph)) {
            // packing would move a part away from a pin, area or target of the user (no projection follows)
            ConflictLog.addDropped("SBGN", Collections.<ConstraintFeasibility.Dropped>emptyList(),
                    Collections.singletonList("The parts of the map were not packed: a pin, area or target of yours fixes positions."));
            pack = false;
        }
        if (pack && PACK) ERLayout.packComponents(PACK_WHOLE_GRAPH ? partsNow() : ERLayout.componentsOf(graph), active);
        // move the drawing into the visible area; a translation keeps every constraint but a pin, area or target
        if (CBLayoutAlgorithm.absoluteUserConstraintsOn(graph)) return;
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        for (Node n : nodes) { left = Math.min(left, x(n) - w(n) / 2); top = Math.min(top, y(n) - h(n) / 2); }
        for (Node n : nodes) AttributeHelper.setPosition(n, x(n) - left + 50, y(n) - top + 50);
    }

    /** The parts of the whole map ({@link #parts}) with the nodes that stand for theirs in the graph now. */
    private List<List<Node>> partsNow() {
        List<List<Node>> now = new ArrayList<>();
        for (List<Node> part : parts) {
            List<Node> here = new ArrayList<>();
            for (Node n : part) {
                Node node = now(n);
                if (node != null) here.add(node);
            }
            if (!here.isEmpty()) now.add(here);
        }
        return now;
    }

    /**
     * The constraints of {@code active} that the run applies (alignments, gaps) and that do not hold in the drawing,
     * measured: they go to the conflict list. cola's own reports are off while it keeps nodes from overlapping
     * during the run (CBLayoutAlgorithm.solveNodes).
     */
    private void reportUnsatisfied(List<Constraint> active, List<Node> nodes) {
        Set<Node> known = new HashSet<>(nodes);
        int count = 0;
        for (Constraint c : active) {
            if (!(c instanceof HorizontalConstraint || c instanceof VerticalConstraint || c instanceof SeparationConstraintGraph)) continue;
            if (c.getNodes().size() < 2 || !known.containsAll(c.getNodes())) continue;
            String why = ConstraintCheck.violation(c, nodes, ConstraintCheck.TOLERANCE);
            if (why == null) continue;
            ConflictLog.addUnsatisfied(c, "ER", "the solver did not satisfy it (" + why + ")");
            count++;
        }
        if (count > 0) log("run: " + count + " constraints do not hold in the drawing");
    }

    // ------------------------------------------------------------------ step 2

    /**
     * Step 2: every pair of neighbours gets the alignment its line is closest to (vertical, horizontal, or a diagonal
     * as exact gaps in x and y), the pairs closest to an axis first, unless the direction is taken at one of its nodes
     * or the gaps would not add up around a cycle; then a run with these constraints.
     */
    void constraints() {
        if (stage != 1 && stage != 2) return;   // step 1 has not run
        removeCreated();
        aligned.clear();
        taken.clear();
        outSide.clear();

        List<Node> nodes = sortedNodes();
        Map<Node, Integer> index = index(nodes);
        if (PLAN_OPERATOR_SIDES) {
            for (Node op : nodes) {
                Direction side = AFLayout.isLogicOperator(op) ? plannedOutputSide(op) : null;
                if (side != null) outSide.put(op, side);
            }
        }
        // the neighbours: the two ends of every edge, each pair once: {u, w, the directions it may take}
        List<int[]> pairs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Edge e : graph.getEdges()) {
            Node u = e.getSource(), w = e.getTarget();
            if (u != w && seen.add(pairKey(u, w))) pairs.add(new int[]{ index.get(u), index.get(w), allowed(u, w) });
        }
        pairs.sort(Comparator.comparingInt((int[] p) -> Math.min(p[0], p[1])).thenComparingInt(p -> Math.max(p[0], p[1])));
        double[] xs = new double[nodes.size()], ys = new double[nodes.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = x(nodes.get(i)); ys[i] = y(nodes.get(i)); }
        int[] slots;
        Model planned;
        List<Candidate> candidates, accepted;
        int crowded = 0;
        while (true) {
            candidates = new ArrayList<>();
            for (int[] p : pairs) candidates.add(new Candidate(p[0], p[1], p[2]));
            slots = new int[nodes.size()];
            planned = model(nodes, index);
            accepted = ERAlignmentPlan.plan(planned, xs, ys, slots, candidates, ONE_PER_DIRECTION);
            // ALIGN_ORDER: where the exact gaps of the plan leave the nodes of a row or column too little room, the
            // youngest pair there takes another direction and the plan is made again
            Candidate tight = ALIGN_ORDER && crowded < pairs.size() ? withoutRoom(accepted, planned, nodes) : null;
            if (tight == null) break;
            for (int[] p : pairs) {
                if (p[0] == tight.u && p[1] == tight.w) p[2] &= ~(tight.direction.bit() | tight.direction.opposite().bit());
            }
            crowded++;
        }
        for (int i = 0; i < slots.length; i++) if (slots[i] != 0) taken.put(nodes.get(i), slots[i]);

        // the constraints, in the order the pairs got their alignment: the pairs closest to their axis are the oldest,
        // so the feasibility guard (it drops the youngest constraint of a contradiction) keeps them longest
        List<Constraint> alignments = new ArrayList<>(), gaps = new ArrayList<>(), orders = new ArrayList<>();
        List<Node[]> rows = new ArrayList<>(), columns = new ArrayList<>();
        int[] count = new int[4];
        int second = 0;
        for (Candidate c : accepted) {
            Node u = nodes.get(c.u), w = nodes.get(c.w);
            Aligned a = new Aligned(u, w, c.direction);
            aligned.put(pairKey(u, w), a);
            count[c.direction.alignment().ordinal()]++;
            if (c.second) second++;
            if (c.direction.diagonal()) {
                a.own.addAll(gaps(u, w, c.direction, c.gap, c.joins));
                gaps.addAll(a.own);
            } else if (c.joins != 0) {
                if (ALIGNMENT_CLASSES) (c.direction.dx == 0 ? columns : rows).add(new Node[]{ u, w });
                else {
                    a.own.add(alignment(Arrays.asList(u, w), c.direction));
                    alignments.addAll(a.own);
                }
            }
            if (ALIGN_ORDER && ordered(c, planned)) {
                // the two keep their order along the line (see ALIGN_ORDER); it goes when the pair loses its alignment
                boolean column = c.direction.dx == 0, forward = column ? c.direction.dy > 0 : c.direction.dx > 0;
                Constraint order = ERLayout.rawSeparation(forward ? u : w, forward ? w : u, !column, orderRoom(c, nodes));
                a.own.add(order);
                orders.add(order);
            }
        }
        alignments.addAll(classes(rows, Direction.E));
        alignments.addAll(classes(columns, Direction.S));
        add(alignments);
        add(gaps);
        add(orders);
        log("step 2: " + count[ERAlignmentPlan.Alignment.VERTICAL.ordinal()] + " vertical, "
                + count[ERAlignmentPlan.Alignment.HORIZONTAL.ordinal()] + " horizontal, "
                + count[ERAlignmentPlan.Alignment.OCT_VERTICAL.ordinal()] + " octilinear vertical, "
                + count[ERAlignmentPlan.Alignment.OCT_HORIZONTAL.ordinal()] + " octilinear horizontal (" + second
                + " of them in the next sector), " + (candidates.size() - accepted.size()) + " pairs without alignment; "
                + (alignments.size() + gaps.size() + orders.size()) + " constraints" + (orders.isEmpty() ? "" : " (" + orders.size() + " keep an order)")
                + (crowded == 0 ? "" : "; " + crowded + " pairs took another direction, their row or column had no room for them") + (outSide.isEmpty() ? "" : "; operator output sides " + sides(outSide)));
        host.enforceFeasibility("ER Constraints");
        verifyAlignments();
        solve(ConstraintStore.constraints.getAllConstraints(), true);
        stage = 2;
    }

    private static String sides(Map<Node, Direction> sides) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<Node, Direction> en : sides.entrySet()) l.add(name(en.getKey()) + " " + en.getValue());
        Collections.sort(l);
        return l.toString();
    }

    /**
     * The directions an edge from {@code from} to {@code to} may take: any, except at a logical operator with a planned
     * output side. There every edge runs along that side, its two neighbours included: the output leaves on it, an
     * input comes in from the opposite side.
     */
    private int allowed(Node from, Node to) {
        int mask = ERAlignmentPlan.ANY;
        Direction leaves = outSide.get(from), enters = outSide.get(to);
        if (leaves != null) mask &= leaves.side();
        if (enters != null) mask &= enters.side();
        return mask;
    }

    /** {@link #ALIGN_ORDER}: whether the pair is on a row or column on which the plan does not say how far apart the two are. */
    private static boolean ordered(Candidate c, Model planned) {
        if (c.direction.diagonal()) return false;
        return Double.isNaN(c.direction.dx == 0 ? planned.y.difference(c.u, c.w) : planned.x.difference(c.u, c.w));
    }

    /** {@link #ALIGN_ORDER}: the centre distance two aligned nodes keep along their row or column. */
    private static double orderRoom(Candidate c, List<Node> nodes) {
        Node u = nodes.get(c.u), w = nodes.get(c.w);
        return (c.direction.dx == 0 ? h(u) + h(w) : w(u) + w(w)) / 2 + CLEARANCE;
    }

    /**
     * {@link #ALIGN_ORDER}: the youngest pair of {@code accepted} on a row or column whose nodes need more room one
     * behind the other than the exact gaps of the plan leave them ({@link ERAlignmentPlan#contradictingOrder}); null
     * if every row and column has room.
     */
    private static Candidate withoutRoom(List<Candidate> accepted, Model planned, List<Node> nodes) {
        for (int axis = 0; axis < 2; axis++) {   // columns (distances in y), then rows
            List<Candidate> on = new ArrayList<>();
            for (Candidate c : accepted) if (ordered(c, planned) && (c.direction.dx == 0) == (axis == 0)) on.add(c);
            int[] from = new int[on.size()], to = new int[on.size()];
            double[] room = new double[on.size()];
            for (int i = 0; i < on.size(); i++) {
                Candidate c = on.get(i);
                boolean forward = axis == 0 ? c.direction.dy > 0 : c.direction.dx > 0;
                from[i] = forward ? c.u : c.w;
                to[i] = forward ? c.w : c.u;
                room[i] = orderRoom(c, nodes);
            }
            int tight = ERAlignmentPlan.contradictingOrder(axis == 0 ? planned.y : planned.x, from, to, room);
            if (tight >= 0) return on.get(tight);
        }
        return null;
    }

    /**
     * Step 2: the side on which the output of a logical operator is to leave it, on the axis that is closer: from its
     * inputs towards the target of its output arc (of several: their mean), so that the operator comes to lie between
     * them. Where the operator itself lies does not count: the force-directed runs so far did not know its output arc.
     * Without inputs: from the operator towards the target; without an output: away from the inputs. Null if nothing
     * tells a side.
     */
    private Direction plannedOutputSide(Node op) {
        double[] target = mean(outputTargets(op)), inputs = mean(inputs(op));
        double[] from = target != null && inputs != null ? inputs : new double[]{ x(op), y(op) };
        double[] to = target != null ? target : inputs == null ? null : new double[]{ 2 * x(op) - inputs[0], 2 * y(op) - inputs[1] };
        return to == null ? null : axis(to[0] - from[0], to[1] - from[1]);
    }

    /**
     * Step 5: the side on which the output of a logical operator leaves it: towards the target of its output arc, on
     * the axis that is closer. The side step 2 planned stays while the target lies beyond the operator's port on that
     * side, so the inputs that were aligned opposite it arrive there. Without an output: the planned side, else away
     * from the inputs.
     */
    private Direction outputSide(Node op, Direction planned) {
        double[] target = mean(outputTargets(op));
        if (target == null) return planned != null ? planned : plannedOutputSide(op);
        double dx = target[0] - x(op), dy = target[1] - y(op);
        if (planned != null && dx * planned.dx + dy * planned.dy >= (planned.dx != 0 ? w(op) : h(op)) / 2 + PORT_DISTANCE) return planned;
        Direction towards = axis(dx, dy);
        return towards != null ? towards : planned;
    }

    /** The straight direction closest to the vector (dx, dy), a horizontal one on a tie; null for no vector. */
    private static Direction axis(double dx, double dy) {
        if (dx == 0 && dy == 0) return null;
        return Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? Direction.E : Direction.W) : (dy >= 0 ? Direction.S : Direction.N);
    }

    /** The mean place of {@code nodes} (see {@link #place}); null if none of them has one. */
    private double[] mean(List<Node> nodes) {
        double sx = 0, sy = 0;
        int n = 0;
        for (Node node : nodes) {
            double[] p = place(node, 0);
            if (p == null) continue;
            sx += p[0];
            sy += p[1];
            n++;
        }
        return n == 0 ? null : new double[]{ sx / n, sy / n };
    }

    /** The nodes the arcs into a logical operator come from. */
    private static List<Node> inputs(Node op) {
        List<Node> inputs = new ArrayList<>();
        for (Edge e : op.getEdges()) if (e.getTarget() == op && e.getSource() != op) inputs.add(e.getSource());
        return inputs;
    }

    /** The targets of the arcs that start at a logical operator: of the removed hyperedges, and of the edges it has in the graph. */
    private List<Node> outputTargets(Node op) {
        List<Node> targets = new ArrayList<>();
        for (ERLayout.Arc a : hyperedges) if (a.source() == op && !a.selfLoop()) targets.add(a.target());
        Collection<Edge> copies = liveEdge.values();   // pieces of those hyperedges once they are back
        for (Edge e : op.getEdges()) {
            if (e.getSource() == op && e.getTarget() != op && !copies.contains(e)) targets.add(e.getTarget());
        }
        return targets;
    }

    /** Where a node is; for one that is removed at the moment, the middle of the two ends of the arc it lies on. */
    private double[] place(Node n, int depth) {
        Node here = now(n);
        if (here != null) return new double[]{ x(here), y(here) };
        ERLayout.Arc a = ownerOf.get(n);
        if (a == null || depth > 6) return null;
        double[] s = place(a.source(), depth + 1), t = place(a.target(), depth + 1);
        if (s == null || t == null) return s != null ? s : t;
        return new double[]{ (s[0] + t[0]) / 2, (s[1] + t[1]) / 2 };
    }

    /**
     * What the constraints that are applied now (the user's, and this pipeline's) say about the places of
     * {@code nodes} relative to each other: alignments and exact gaps. A new alignment has to fit that.
     */
    private Model model(List<Node> nodes, Map<Node, Integer> index) {
        double[] ws = new double[nodes.size()], hs = new double[nodes.size()];
        for (int i = 0; i < ws.length; i++) { ws[i] = w(nodes.get(i)); hs[i] = h(nodes.get(i)); }
        Model m = new Model(ws, hs, CLEARANCE);
        if (ConstraintStore.constraints == null) return m;
        for (Constraint c : ConstraintConflicts.active(ConstraintStore.constraints.getAllConstraints())) {
            List<Node> ns = c.getNodes();
            if (ns.size() < 2 || !index.keySet().containsAll(ns)) continue;
            int first = index.get(ns.get(0));
            if (c instanceof HorizontalConstraint) {
                // the same top, centre or bottom: the centres differ by half the difference of the heights
                HorizontalConstraint.HorizontalConstraintType type = ((HorizontalConstraint) c).getType();
                double sign = type == HorizontalConstraint.HorizontalConstraintType.TOP ? 0.5
                        : type == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? -0.5 : 0;
                for (int i = 1; i < ns.size(); i++) m.fix(false, first, index.get(ns.get(i)), sign * (h(ns.get(i)) - h(ns.get(0))));
            } else if (c instanceof VerticalConstraint) {
                VerticalConstraint.VerticalConstraintType type = ((VerticalConstraint) c).getType();
                double sign = type == VerticalConstraint.VerticalConstraintType.LEFT ? 0.5
                        : type == VerticalConstraint.VerticalConstraintType.RIGHT ? -0.5 : 0;
                for (int i = 1; i < ns.size(); i++) m.fix(true, first, index.get(ns.get(i)), sign * (w(ns.get(i)) - w(ns.get(0))));
            } else if (c instanceof SeparationConstraintGraph && ns.size() == 2 && ((SeparationConstraintGraph) c).isFixed()) {
                SeparationConstraintGraph s = (SeparationConstraintGraph) c;
                m.fix(s.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL, first, index.get(ns.get(1)), s.getDistance());
            }
        }
        return m;
    }

    /** The alignment of the nodes of one row (direction E or W: the same y) or column (the same x). */
    private static Constraint alignment(List<Node> nodes, Direction d) {
        return d.dy == 0 ? new HorizontalConstraint(new ArrayList<>(nodes), HorizontalConstraint.HorizontalConstraintType.MIDDLE)
                         : new VerticalConstraint(new ArrayList<>(nodes), VerticalConstraint.VerticalConstraintType.MIDDLE);
    }

    /** One alignment per class of nodes that {@code pairs} join into a row (column), the classes in the order they first occur. */
    private static List<Constraint> classes(List<Node[]> pairs, Direction d) {
        Map<Node, Node> parent = new LinkedHashMap<>();
        for (Node[] pair : pairs) ERLayout.union(parent, pair[0], pair[1]);
        Map<Node, List<Node>> members = new LinkedHashMap<>();
        for (Node n : parent.keySet()) members.computeIfAbsent(ERLayout.find(parent, n), k -> new ArrayList<>()).add(n);
        List<Constraint> cs = new ArrayList<>();
        for (List<Node> nodes : members.values()) {
            nodes.sort(ORDER);
            cs.add(alignment(nodes, d));
        }
        return cs;
    }

    /**
     * The exact gaps that put {@code w} in the diagonal direction {@code d} of {@code u}: {@code gap} in x and the
     * same in y, as far as they are new ({@code joins}, see {@link Model#place}); cola has no constraint for "on a
     * diagonal".
     */
    private static List<Constraint> gaps(Node u, Node w, Direction d, double gap, int joins) {
        List<Constraint> cs = new ArrayList<>(2);
        if ((joins & 1) != 0) cs.add(ERLayout.fixedSeparation(d.dx > 0 ? u : w, d.dx > 0 ? w : u, true, gap));
        if ((joins & 2) != 0) cs.add(ERLayout.fixedSeparation(d.dy > 0 ? u : w, d.dy > 0 ? w : u, false, gap));
        return cs;
    }

    private void add(List<Constraint> cs) {
        for (Constraint c : cs) ConstraintStore.constraints.addConstraint(c);
        created.addAll(cs);
    }

    /** The constraints of the last run go; one the user has taken over since (ConflictLog.restore) stays. */
    private void removeCreated() {
        if (ConstraintStore.constraints != null) {
            for (Constraint c : created) if (c.isAutomatic()) ConstraintStore.constraints.removeConstraint(c);
        }
        created.clear();
    }

    /**
     * After the feasibility guard: an alignment that does not follow from the constraints that are left is none any
     * more (the guard dropped one of its constraints, or one it leaned on). What is left of its own constraints goes
     * too, half a diagonal is only a rigid gap; step 5 then routes the pair's edge like any other.
     */
    private void verifyAlignments() {
        int lost = 0;
        for (boolean again = true; again; ) {
            again = false;
            List<Node> nodes = sortedNodes();
            Map<Node, Integer> index = index(nodes);
            Model m = model(nodes, index);
            for (Iterator<Aligned> it = aligned.values().iterator(); it.hasNext() && !again; ) {
                Aligned a = it.next();
                Integer i = index.get(a.a), j = index.get(a.b);
                if (i == null || j == null || holds(m, i, j, a.direction)) continue;
                it.remove();
                lost++;
                for (Constraint c : a.own) {
                    created.remove(c);
                    if (!c.isAutomatic()) continue;   // taken over by the user
                    // another pair may have leaned on a constraint that goes now: look at all of them again
                    again |= ConstraintStore.constraints.getAllConstraints().contains(c);
                    ConstraintStore.constraints.removeConstraint(c);
                }
            }
        }
        if (lost > 0) log("feasibility: " + lost + " pairs lost their alignment");
    }

    private static boolean holds(Model m, int a, int b, Direction d) {
        double dx = m.x.difference(a, b), dy = m.y.difference(a, b);   // NaN if the constraints do not say
        if (!d.diagonal()) return d.dx == 0 ? Math.abs(dx) <= ERAlignmentPlan.EPS : Math.abs(dy) <= ERAlignmentPlan.EPS;
        return dx * d.dx > 0 && dy * d.dy > 0 && Math.abs(Math.abs(dx) - Math.abs(dy)) <= ERAlignmentPlan.EPS;
    }

    // ------------------------------------------------------------------ step 3

    /**
     * Step 3: the removed hyperedge nodes are inserted again, evenly on the straight line between the two ends of their
     * hyperedge (several hyperedges between the same two nodes side by side), and aligned with the ends if that line
     * is within {@link #NODE_ALIGN} degrees of an axis.
     */
    void hyperedgeNodes() {
        if (stage != 2) return;   // steps 1 and 2 have not run
        // the removed nodes of every hyperedge: those that follow each other, between two nodes that stayed in the graph
        Set<Node> gone = new HashSet<>(removedNodes);
        List<Run> runs = new ArrayList<>();
        for (ERLayout.Arc a : hyperedges) {
            for (int i = 1; i < a.chain.size() - 1; i++) {
                if (!gone.contains(a.chain.get(i))) continue;
                int j = i;
                while (j + 1 < a.chain.size() - 1 && gone.contains(a.chain.get(j + 1))) j++;
                runs.add(new Run(a.chain.get(i - 1), a.chain.get(j + 1), new ArrayList<>(a.chain.subList(i, j + 1))));
                i = j;
            }
        }
        // several hyperedges between the same two nodes: side by side, not on top of each other
        Map<Long, List<Run>> between = new LinkedHashMap<>();
        for (Run r : runs) between.computeIfAbsent(pairKey(r.from, r.to), k -> new ArrayList<>()).add(r);
        for (List<Run> group : between.values()) {
            for (int i = 0; i < group.size(); i++) {
                group.get(i).lane = i;
                group.get(i).lanes = group.size();
            }
        }
        int nodes = 0;
        List<Run> open = new ArrayList<>(runs), placed = new ArrayList<>();
        for (boolean progress = true; progress; ) {
            progress = false;
            for (Iterator<Run> it = open.iterator(); it.hasNext(); ) {
                Run r = it.next();
                Node s = now(r.from), t = now(r.to);
                if (s == null || t == null) continue;   // an end is a node of another hyperedge that is not back yet
                place(r, s, t);
                nodes += r.points.size();
                placed.add(r);
                it.remove();
                progress = true;
            }
        }
        nodes += insertRest();
        List<Constraint> cs = NODE_ALIGN > 0 ? alignRuns(placed) : new ArrayList<Constraint>();
        add(cs);
        log("step 3: " + nodes + " hyperedge nodes inserted, " + cs.size() + " constraints");
        if (!cs.isEmpty()) {
            host.enforceFeasibility("ER Hyperedge Nodes");
            verifyAlignments();
        }
        // the inserted nodes have no edges before step 4: without a new constraint a run has nothing to move, unless
        // one of them came to lie on another node (the line between the two ends of its hyperedge runs through it)
        if (!cs.isEmpty() || STEP3_RUN_ALWAYS || insertedOnANode()) solve(ConstraintStore.constraints.getAllConstraints(), false);
        refreshLists();
        stage = 3;
    }

    /** True if a node that was inserted again overlaps another node. */
    private boolean insertedOnANode() {
        for (Node copy : live.values()) {
            for (Node n : graph.getNodes()) {
                if (n != copy && Math.abs(x(n) - x(copy)) < (w(n) + w(copy)) / 2 && Math.abs(y(n) - y(copy)) < (h(n) + h(copy)) / 2) return true;
            }
        }
        return false;
    }

    /** Puts the nodes of a run back, evenly on the straight line from {@code s} to {@code t}. */
    private void place(Run r, Node s, Node t) {
        int k = r.points.size();
        double spacing = CLEARANCE;
        for (Node p : r.points) spacing = Math.max(spacing, Math.max(w(p), h(p)) + CLEARANCE);
        if (s == t) {
            // an arc from a node back to itself: its nodes on a circle beside the node, away from its neighbours
            double ax = 0, ay = 0;
            for (Node nb : s.getNeighbors()) { ax += x(s) - x(nb); ay += y(s) - y(nb); }
            double out = (Math.hypot(ax, ay) < 1e-9 ? 0 : Math.atan2(ay, ax)) + 2 * Math.PI * r.lane / r.lanes;
            double radius = Math.max(spacing * (k + 1) / (2 * Math.PI), spacing / 2);
            double centre = Math.hypot(w(s), h(s)) / 2 + radius;
            for (int i = 0; i < k; i++) {
                double angle = out + Math.PI + 2 * Math.PI * (i + 1) / (k + 1);
                AttributeHelper.setPosition(insert(r.points.get(i)), x(s) + centre * Math.cos(out) + radius * Math.cos(angle),
                        y(s) + centre * Math.sin(out) + radius * Math.sin(angle));
            }
            return;
        }
        // one normal for all hyperedges between the two nodes, whatever their direction
        Node a = s.getID() <= t.getID() ? s : t, b = a == s ? t : s;
        double nx = -(y(b) - y(a)), ny = x(b) - x(a), length = Math.hypot(nx, ny);
        double shift = length < 1e-9 ? 0 : (r.lane - (r.lanes - 1) / 2.0) * spacing / length;
        for (int i = 0; i < k; i++) {
            double f = (i + 1.0) / (k + 1.0);
            AttributeHelper.setPosition(insert(r.points.get(i)), x(s) + f * (x(t) - x(s)) + shift * nx, y(s) + f * (y(t) - y(s)) + shift * ny);
        }
    }

    /**
     * The removed nodes no hyperedge puts back (a node that went with a hyperedge although its own arc is none, or two
     * hyperedges that end on each other): each next to the nodes it was attached to. Returns how many.
     */
    private int insertRest() {
        List<Node> rest = new ArrayList<>();
        for (Node n : removedNodes) if (!live.containsKey(n)) rest.add(n);
        int count = rest.size();
        for (boolean progress = true; progress && !rest.isEmpty(); ) {
            progress = false;
            for (Iterator<Node> it = rest.iterator(); it.hasNext(); ) {
                Node n = it.next();
                double cx = 0, cy = 0, widest = 0;
                int k = 0;
                for (Edge e : removedEdges) {
                    Node[] ends = endsOf.get(e);
                    if ((ends[0] == n) == (ends[1] == n)) continue;   // not at n, or from n to n
                    Node other = now(ends[0] == n ? ends[1] : ends[0]);
                    if (other == null) continue;
                    cx += x(other);
                    cy += y(other);
                    widest = Math.max(widest, w(other));
                    k++;
                }
                if (k == 0) continue;
                // between its neighbours; beside it if there is only one
                AttributeHelper.setPosition(insert(n), cx / k + (k == 1 ? (widest + w(n)) / 2 + CLEARANCE : 0), cy / k);
                it.remove();
                progress = true;
            }
        }
        for (Node n : rest) insert(n);   // attached to nothing that is in the graph: where it was
        return count;
    }

    /**
     * A hyperedge whose two ends lie almost on one axis ({@link #NODE_ALIGN}): its inserted nodes and the two ends are
     * aligned on that axis, so the hyperedge is a straight line. Like every alignment only if the direction is free at
     * both ends and it fits the constraints there are. Returns the new constraints.
     */
    private List<Constraint> alignRuns(List<Run> placed) {
        List<Constraint> cs = new ArrayList<>();
        List<Node> nodes = sortedNodes();
        Map<Node, Integer> index = index(nodes);
        Model model = model(nodes, index);
        int[] slots = new int[nodes.size()];
        for (int i = 0; i < slots.length; i++) slots[i] = taken.getOrDefault(nodes.get(i), 0);
        for (Run r : placed) {
            Node s = now(r.from), t = now(r.to);
            if (r.lanes != 1 || s == t) continue;
            double dx = x(t) - x(s), dy = y(t) - y(s);
            List<Direction> options = ERAlignmentPlan.candidates(dx, dy, allowed(s, t));
            if (options.isEmpty() || options.get(0).deviation(dx, dy) > NODE_ALIGN) continue;
            Direction d = options.get(0);
            List<Node> line = new ArrayList<>();
            line.add(s);
            for (Node p : r.points) line.add(live.get(p));
            line.add(t);
            int[] chain = new int[line.size()];
            for (int i = 0; i < chain.length; i++) chain[i] = index.get(line.get(i));
            Chain result = ERAlignmentPlan.alignChain(model, ONE_PER_DIRECTION ? slots : null, chain, d, (Math.abs(dx) + Math.abs(dy)) / 2);
            if (result == null) continue;
            boolean fresh = false;
            for (int joins : result.joins) fresh |= joins != 0;
            if (!d.diagonal() && ALIGNMENT_CLASSES && fresh) cs.add(alignment(line, d));
            for (int i = 0; i + 1 < line.size(); i++) {
                Aligned a = new Aligned(line.get(i), line.get(i + 1), d);
                if (d.diagonal()) a.own.addAll(gaps(a.a, a.b, d, result.gap, result.joins[i]));
                else if (!ALIGNMENT_CLASSES && result.joins[i] != 0) a.own.add(alignment(Arrays.asList(a.a, a.b), d));
                cs.addAll(a.own);
                aligned.put(pairKey(a.a, a.b), a);
            }
        }
        for (int i = 0; i < slots.length; i++) if (slots[i] != 0) taken.put(nodes.get(i), slots[i]);
        return cs;
    }

    /** The node that stands for {@code n} in the graph now: itself, or its copy once it is inserted again (else null). */
    private Node now(Node n) {
        if (graph.containsNode(n)) return n;
        return live.get(n);
    }

    // ------------------------------------------------------------------ step 4

    /** Step 4: the hyperedges are inserted again, each on the nodes it was attached to; a run with all constraints. */
    void hyperedges() {
        if (stage != 3) return;   // steps 1 to 3 have not run
        // the nodes without an edge (not the hyperedge nodes step 3 has just placed)
        List<Node> alone = new ArrayList<>();
        Collection<Node> inserted = live.values();
        for (Node n : sortedNodes()) if (n.getDegree() == 0 && !inserted.contains(n)) alone.add(n);
        int edges = 0;
        for (Edge e : removedEdges) if (insert(e) != null) edges++;
        log("step 4: " + hyperedges.size() + " hyperedges inserted (" + edges + " edges)"
                + (START_AT_NEIGHBOURS && !alone.isEmpty() ? "; " + alone.size() + " nodes with hyperedges only start between their neighbours" : ""));
        if (START_AT_NEIGHBOURS) startAtNeighbours(alone);
        // a run with all constraints, so a glyph that has only hyperedges finds its place next to them
        solve(ConstraintStore.constraints.getAllConstraints(), true);
        refreshLists();
        stage = 4;
    }

    /**
     * A node that has only hyperedges had no edge in steps 1 to 3, so no run has given it a place (packing put it at
     * the side). The run of step 4 starts it in the middle of the nodes its hyperedges lead to, beside that node if
     * there is only one; nodes do not pass each other during a run ({@link #OVERLAPS_IN_RUN}), so from the side it
     * would only reach the border of the drawing.
     */
    private void startAtNeighbours(List<Node> alone) {
        Set<Node> waiting = new HashSet<>(alone);
        for (boolean progress = true; progress && !waiting.isEmpty(); ) {
            progress = false;
            for (Node n : alone) {
                if (!waiting.contains(n)) continue;
                double sx = 0, sy = 0, widest = 0;
                int k = 0;
                for (Node nb : new LinkedHashSet<>(n.getNeighbors())) {
                    if (nb == n || waiting.contains(nb)) continue;   // a neighbour without a place of its own yet
                    sx += x(nb);
                    sy += y(nb);
                    widest = Math.max(widest, w(nb));
                    k++;
                }
                if (k == 0) continue;
                AttributeHelper.setPosition(n, sx / k + (k == 1 ? (widest + w(n)) / 2 + CLEARANCE : 0), sy / k);
                waiting.remove(n);
                progress = true;
            }
        }
    }

    /**
     * Inside VANTED, SBGN-ED gives every element that is added to a map a new glyph id. An inserted copy has to keep
     * the id (and the other SBGN attributes) of the element it stands for: the edges of one arc share their arc's id.
     */
    private static void keepSbgnAttributes(org.graffiti.attributes.Attributable from, org.graffiti.attributes.Attributable to) {
        try {
            org.graffiti.attributes.CollectionAttribute sbgn = (org.graffiti.attributes.CollectionAttribute) from.getAttribute("sbgn");
            for (Map.Entry<String, org.graffiti.attributes.Attribute> en : sbgn.getCollection().entrySet()) {
                Object value = en.getValue().getValue();
                Object now = AttributeHelper.getAttributeValue(to, "sbgn", en.getKey(), null, value, false);
                if (value != null && !value.equals(now)) AttributeHelper.setAttribute(to, "sbgn", en.getKey(), value);
            }
            // no id for a copy whose original had none: its arc is then read from the structure (ERLayout.buildArcs)
            Object id = AttributeHelper.getAttributeValue(from, "sbgn", "glyphid", "", "", false);
            if (id == null || id.toString().trim().isEmpty()) AttributeHelper.setAttribute(to, "sbgn", "glyphid", "");
        } catch (Exception e) {
            // an element without SBGN attributes
        }
    }

    /** Puts a removed node back into the graph (as a copy with all its attributes). */
    private Node insert(Node removed) {
        Node copy = live.get(removed);
        if (copy == null) {
            copy = graph.addNodeCopy(removed);
            keepSbgnAttributes(removed, copy);
            live.put(removed, copy);
            restoreUserConstraints(removed, copy);
            host.releaseUserConstraints(removed, copy);
        }
        return copy;
    }

    /** Puts a removed edge back between the nodes that stand for its ends now; null if one of them is not in the graph. */
    private Edge insert(Edge removed) {
        Edge copy = liveEdge.get(removed);
        if (copy != null) return copy;
        Node[] ends = endsOf.get(removed);
        Node s = now(ends[0]), t = now(ends[1]);
        if (s == null || t == null) return null;
        copy = graph.addEdgeCopy(removed, s, t);
        keepSbgnAttributes(removed, copy);
        AttributeHelper.removeEdgeBends(copy);
        liveEdge.put(removed, copy);
        restoreUserConstraints(removed, copy);
        return copy;
    }

    // ------------------------------------------------------------------ the user's constraints on what is removed

    /**
     * Step 1 deletes {@code n}, and the copy that comes back is another node. The user's constraints on {@code n} are
     * remembered so that the copy takes its place in them; without that they are lost (inside VANTED the add-on takes a
     * deleted node out of every constraint). Only constraints that keep nothing per node but their list of nodes; the
     * others wait outside the store ({@link #keepsNodeData}).
     */
    private void rememberUserConstraints(Node n) {
        if (ConstraintStore.constraints == null) return;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            int i = c.getNodes().indexOf(n);
            if (i >= 0 && !c.isAutomatic() && plainNodeList(c)) memberships.add(new Membership(c, n, i));
        }
    }

    /** A pin, target, rigid group, area, grid or star: the deletion would lose what it keeps for the node. */
    private static boolean keepsNodeData(Constraint c) {
        boolean edgeBound = c instanceof OrthogonalEdgeConstraintGraph
                && ((OrthogonalEdgeConstraintGraph) c).getEdge() != null;
        return !edgeBound && !plainNodeList(c);
    }

    private static boolean plainNodeList(Constraint c) {
        if (c instanceof OrthogonalEdgeConstraintGraph && ((OrthogonalEdgeConstraintGraph) c).getEdge() != null) return false;   // goes with its edge
        try {
            return c.getClass().getMethod("removeNode", Node.class).getDeclaringClass() == Constraint.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private void restoreUserConstraints(Node removed, Node copy) {
        Constraints store = ConstraintStore.of(graph);
        for (Membership m : memberships) {
            if (m.node != removed) continue;
            // gone from the store, not by the removal: the user deleted it since, and it stays deleted
            if (!voidedByRemoval.contains(m.constraint) && !store.getAllConstraints().contains(m.constraint)) continue;
            List<Node> nodes = m.constraint.getNodes();
            int at = nodes.indexOf(removed);
            if (at >= 0) nodes.set(at, copy);   // nobody took the deleted node out of the constraint
            else if (!nodes.contains(copy)) nodes.add(Math.min(m.index, nodes.size()), copy);
            if (nodes.size() >= m.constraint.getMinNodes()) host.keepUserConstraint(m.constraint);
            listsChanged = true;
        }
    }

    /**
     * A constraint on a removed edge (horizontal / vertical edge) is bound to that edge: it moves on to the copy, as the
     * same constraint (its id, and so its rank among the user's constraints, stays).
     */
    private void restoreUserConstraints(Edge removed, Edge copy) {
        for (OrthogonalEdgeConstraintGraph c : edgeConstraints.getOrDefault(removed, Collections.<OrthogonalEdgeConstraintGraph>emptyList())) {
            c.rebind(copy);
            host.keepUserConstraint(c);
            listsChanged = true;
        }
    }

    /** The constraint lists of the add-on show the user's constraints that are back. */
    private void refreshLists() {
        if (!listsChanged) return;
        listsChanged = false;
        try { ConstraintStore.onListsChanged.run(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ step 5

    /**
     * Step 5: one orthogonal routing. An edge along a planned diagonal stays straight, the output of a logical operator
     * leaves towards its target and its inputs arrive opposite, and a hyperedge leaves a hyperedge node with a stub at
     * a right angle to the arc that runs through the node.
     */
    void route() {
        if (stage != 4) return;   // steps 1 to 4 have not run
        host.refreshView();
        List<Node> nodes = sortedNodes();
        // a logical operator's output leaves towards its target, its inputs arrive on the opposite side (with free
        // sides the ER writer puts both on one port)
        Map<Node, Direction> side = new HashMap<>();
        for (Node op : nodes) {
            if (!AFLayout.isLogicOperator(op)) continue;
            Direction planned = outSide.get(op), d = outputSide(op, planned);
            if (d != null) side.put(op, d);
            if (planned != null && d != planned) log("step 5: the output of " + name(op) + " leaves " + d + ", not " + planned + " as planned (its target lies there now)");
        }
        // the edges that stay straight lines along their diagonal; of several between two nodes one
        Set<Edge> straight = new LinkedHashSet<>();
        if (OCTILINEAR_STRAIGHT) {
            Set<Long> pairs = new HashSet<>();
            for (Edge e : graph.getEdges()) {
                if (diagonalLine(e, nodes) && pairs.add(pairKey(e.getSource(), e.getTarget()))) straight.add(e);
            }
        }
        // stub: hyperedge -> its stub's direction {at source, at target}; along: piece of a hyperedge node's own arc ->
        // the direction it leaves {source, target}, so that the stubs stand at a right angle to what is drawn
        Map<Edge, Direction[]> stub = new HashMap<>(), along = new HashMap<>();
        Map<Edge, double[]> stubLength = new HashMap<>();
        List<Junction> junctions = STUB > 0 ? junctions() : Collections.<Junction>emptyList();
        // a hyperedge drawn as a straight diagonal at another angle is routed instead, which can change the pieces at
        // its other end: repeat until nothing changes
        for (boolean again = !junctions.isEmpty(); again; ) {
            again = false;
            stub.clear();
            stubLength.clear();
            along.clear();
            for (Junction j : junctions) {
                Direction[] u = pieceDirections(j, straight);
                if (u == null) continue;
                for (int k = 0; k < 2; k++) {
                    Edge piece = j.pieces.get(k);
                    if (!straight.contains(piece)) along.computeIfAbsent(piece, x -> new Direction[2])[piece.getSource() == j.node ? 0 : 1] = u[k];
                }
                // first the hyperedges that end here (one arrowhead per stub direction while one is free), each in the
                // order of how clearly its other end lies on one side
                List<Edge> order = new ArrayList<>(j.attached);
                Map<Edge, List<Direction>> options = new HashMap<>();
                for (Edge e : order) {
                    Node other = e.getSource() == j.node ? e.getTarget() : e.getSource();
                    options.put(e, ERAlignmentPlan.stubDirections(u[0], u[1], x(other) - x(j.node), y(other) - y(j.node)));
                }
                order.sort(Comparator.comparing((Edge e) -> e.getTarget() != j.node).thenComparingDouble(e -> -clearness(j.node, e, options.get(e))));
                Set<Direction> heads = EnumSet.noneOf(Direction.class);
                for (Edge e : order) {
                    Node other = e.getSource() == j.node ? e.getTarget() : e.getSource();
                    boolean ends = e.getTarget() == j.node;
                    if (straight.contains(e)) {
                        Direction line = ERAlignmentPlan.closest(x(other) - x(j.node), y(other) - y(j.node), ERAlignmentPlan.ANY);
                        if (options.get(e).contains(line) && !(ends && heads.contains(line))) {   // stands at a right angle (or wider) already
                            if (ends) heads.add(line);
                            continue;
                        }
                        straight.remove(e);
                        again = true;
                    }
                    // the best direction in which the whole stub has room and which no other arrowhead uses (0); else
                    // the best with room for the whole stub (1); else the one with the most room, a shorter stub (2)
                    double wanted = Math.max(STUB, symbol(e, ends)[0] + STUB / 2);   // a longer symbol plus half a stub
                    Direction d = null;
                    double length = 0;
                    int rank = 3;
                    for (Direction option : options.get(e)) {
                        double room = stubRoom(j.node, option, wanted, nodes);
                        if (room < SHORTEST_STUB) continue;
                        int r = room < wanted ? 2 : ends && heads.contains(option) ? 1 : 0;
                        if (r < rank || (r == 2 && room > length)) {
                            d = option;
                            length = room;
                            rank = r;
                        }
                        if (rank == 0) break;
                    }
                    if (d == null) continue;   // no room for a stub on any side
                    if (ends) heads.add(d);
                    stub.computeIfAbsent(e, x -> new Direction[2])[ends ? 1 : 0] = d;
                    stubLength.computeIfAbsent(e, x -> new double[2])[ends ? 1 : 0] = length;
                }
            }
        }
        if (AIM_AT_STUBS) aimAtStubEnds(junctions, straight, stub, stubLength, along);
        ERRouting routing = new ERRouting();
        routing.straight.addAll(straight);
        routing.shapeBuffer = SHAPE_BUFFER;
        routing.nudging = NUDGING;
        routing.keepNudgedStubs = KEEP_NUDGED_STUBS;
        routing.clearStraightPins = CLEAR_PIN_SPOTS;
        routing.removeSmallJogs = true;
        int stubs = 0;
        double longest = 0;
        for (Edge e : graph.getEdges()) {
            if (straight.contains(e)) continue;
            Direction leaves = side.get(e.getSource()), enters = side.get(e.getTarget());
            // every routed edge is listed (also without a direction): it is routed as an ER edge
            String[] directions = { leaves == null ? null : sideName(leaves), enters == null ? null : sideName(enters.opposite()) };
            List<double[]> ports = new ArrayList<>();
            if (leaves != null) ports.add(port(e.getSource(), leaves));
            if (enters != null) ports.add(port(e.getTarget(), enters.opposite()));
            if (!ports.isEmpty()) routing.checkpoints.put(e, ports.toArray(new double[0][]));
            Direction[] own = along.get(e), across = stub.get(e);
            double[][] ends = new double[2][];
            double[] reach = new double[2];
            for (int k = 0; k < 2; k++) {
                Node at = k == 0 ? e.getSource() : e.getTarget();
                if (own != null && own[k] != null) directions[k] = sideName(own[k]);
                if (across == null || across[k] == null) continue;
                Direction d = across[k];
                ends[k] = stubEnd(at, d, stubLength.get(e)[k]);
                // the symbol stays on the stub; a stub that is already shorter than its symbol gets no shorter
                reach[k] = Math.max(w(at), h(at)) / 2 + Math.min(stubLength.get(e)[k], symbol(e, k == 1)[0] + SYMBOL_ON_STUB);
                longest = Math.max(longest, stubLength.get(e)[k]);
                routing.keepOut.addAll(keepOut(at, d, stubLength.get(e)[k], symbol(e, k == 1), nodes));
                // from the stub's end the route goes on the way the stub points or turns off; it does not come back
                // (a diagonal stub: along either of its two axes)
                directions[k] = d.diagonal() ? sideName(d.dx > 0 ? Direction.E : Direction.W) + "," + sideName(d.dy > 0 ? Direction.S : Direction.N)
                                             : sideName(d) + "," + sideName(d.turned(2)) + "," + sideName(d.turned(-2));
                stubs++;
            }
            routing.directions.put(e, directions);
            if (ends[0] != null || ends[1] != null) {
                routing.stubs.put(e, ends);
                routing.stubReach.put(e, reach);
            }
        }
        log("step 5: " + routing.directions.size() + " edges routed, " + routing.straight.size() + " straight diagonals, " + stubs
                + " stubs at hyperedge nodes" + (stubs > 0 ? " (up to " + Math.round(longest) + " px), " + routing.keepOut.size() + " boxes keep their symbols clear" : ""));
        host.finishActivityFlow(true, routing);
        for (Edge e : routing.straight) {
            if (!graph.containsEdge(e)) continue;
            AttributeHelper.removeEdgeBends(e);
            AttributeHelper.setEdgeBendStyle(e, GraphicAttributeConstants.STRAIGHTLINE_CLASSNAME);
            EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
            ega.getDocking().setSource("");
            ega.getDocking().setTarget("");
        }
        stage = 5;
    }

    /**
     * {@link #AIM_AT_STUBS}: a piece of a hyperedge node's own arc that is itself a hyperedge with a stub at its other
     * end leaves the node towards the end of that stub ({@code along} is corrected). Only if the node keeps what the
     * stubs need: the piece does not leave the way the node's other piece does, and every stub at the node still
     * stands at a right angle (or wider) on it.
     */
    private void aimAtStubEnds(List<Junction> junctions, Set<Edge> straight, Map<Edge, Direction[]> stub,
                               Map<Edge, double[]> stubLength, Map<Edge, Direction[]> along) {
        for (Junction j : junctions) {
            for (int k = 0; k < 2; k++) {
                Edge piece = j.pieces.get(k), second = j.pieces.get(1 - k);
                if (straight.contains(piece) || along.get(piece) == null || stub.get(piece) == null) continue;
                int here = piece.getSource() == j.node ? 0 : 1;
                Direction far = stub.get(piece)[1 - here], now = along.get(piece)[here];
                if (far == null || now == null) continue;
                Node other = here == 0 ? piece.getTarget() : piece.getSource();
                double[] end = stubEnd(other, far, stubLength.get(piece)[1 - here]);
                Direction aim = ERAlignmentPlan.closest(end[0] - x(j.node), end[1] - y(j.node), ERAlignmentPlan.STRAIGHT);
                if (aim == null || aim == now) continue;
                Node behind = second.getSource() == j.node ? second.getTarget() : second.getSource();
                Direction[] planned = along.get(second);
                Direction back = straight.contains(second) || planned == null ? null : planned[second.getSource() == j.node ? 0 : 1];
                if (back == null) back = ERAlignmentPlan.closest(x(behind) - x(j.node), y(behind) - y(j.node), ERAlignmentPlan.ANY);
                boolean fits = back == null || aim.dx * back.dx + aim.dy * back.dy <= 0;
                for (Edge e : j.attached) {
                    Direction[] across = stub.get(e);
                    Direction d = across == null ? null : across[e.getTarget() == j.node ? 1 : 0];
                    if (d != null) fits &= aim.dx * d.dx + aim.dy * d.dy <= 0;
                }
                if (fits) along.get(piece)[here] = aim;
            }
        }
    }

    /** Where a stub ends that leaves the hyperedge node {@code at} in direction {@code d}: {@code length} beyond its border. */
    private static double[] stubEnd(Node at, Direction d, double length) {
        double reach = (Math.max(w(at), h(at)) / 2 + length) / Math.hypot(d.dx, d.dy);
        return new double[]{ x(at) + d.dx * reach, y(at) + d.dy * reach };
    }

    /**
     * How long a stub of at most {@code length} can be that leaves {@code at} in direction {@code d} and stays clear
     * of all other nodes; 0 if not even {@link #SHORTEST_STUB}.
     */
    private static double stubRoom(Node at, Direction d, double length, List<Node> nodes) {
        for (double l = length; l >= SHORTEST_STUB; l -= 2) {
            double[] end = stubEnd(at, d, l);
            boolean blocked = false;
            for (Node n : nodes) {
                blocked = n != at && ERAlignmentPlan.segmentHitsBox(x(at), y(at), end[0], end[1], x(n), y(n), w(n), h(n), STUB_MARGIN);
                if (blocked) break;
            }
            if (!blocked) return l;
        }
        return 0;
    }

    /**
     * The symbol at one end of an edge (the arrowhead at its target, the tail at its source): {how far it reaches
     * back along the edge from its tip, how wide it is across the edge}, measured on the shape VANTED draws
     * (LineEdgeShape.attachTargetArrow); {0, 0} without a symbol.
     */
    static double[] symbol(Edge e, boolean atTarget) {
        try {
            EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
            String shape = atTarget ? ega.getArrowhead() : ega.getArrowtail();
            if (shape == null || shape.isEmpty()) return new double[]{ 0, 0 };
            ArrowShape arrow = (ArrowShape) Class.forName(shape.replace("Thin", "")).getDeclaredConstructor().newInstance();
            if (arrow instanceof SupportsHollowDrawing) ((SupportsHollowDrawing) arrow).setHollow(shape.contains("Thin"));
            arrow.updateSize(ega.getThickness());
            java.awt.geom.Rectangle2D placed = arrow.affix(new java.awt.geom.Point2D.Double(0, 0), new java.awt.geom.Point2D.Double(-1000, 0),
                    ega.getThickness() + ega.getFrameThickness()).getBounds2D();
            return new double[]{ Math.max(0, -placed.getMinX()), placed.getHeight() };
        } catch (Exception | LinkageError ex) {
            return new double[]{ 0, 0 };   // an unknown shape: no symbol to keep clear of
        }
    }

    /**
     * The rectangles {left, top, right, bottom} that keep other routes out of the symbol on the stub of
     * {@code length} that leaves {@code at} in direction {@code d}: from {@link #KEEP_OUT_GAP} beyond the node's border
     * to {@link #KEEP_OUT_END} before the stub's end (the route starts there), as wide as the symbol. A straight stub
     * gets one rectangle, narrower where a node stands beside it. The orthogonal router takes an obstacle's bounding
     * box, which for one rectangle along a diagonal stub would cover the node and the stub's end: a diagonal stub gets
     * a row of squares along it instead, each reaching half the symbol's width across. Nothing where there is no
     * symbol or no room.
     */
    private static List<double[]> keepOut(Node at, Direction d, double length, double[] symbol, List<Node> nodes) {
        List<double[]> boxes = new ArrayList<>();
        if (symbol[0] <= 0) return boxes;
        double half = Math.max(w(at), h(at)) / 2, from = half + KEEP_OUT_GAP, to = half + length - KEEP_OUT_END;
        if (d.diagonal()) {
            double side = Math.max(6, symbol[1] / Math.sqrt(2)), reach = side / Math.sqrt(2);
            for (double s = from + reach; s <= to - reach + 1e-9; s += side / 2) {
                double cx = x(at) + d.dx * s / Math.sqrt(2), cy = y(at) + d.dy * s / Math.sqrt(2);
                double[] box = { cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2 };
                if (clearOfNodes(box, at, nodes)) boxes.add(box);
            }
            return boxes;
        }
        if (to - from < 4) return boxes;
        for (double across = symbol[1] / 2 + 1; across >= 4; across -= 2) {
            double[] box = d.dx != 0
                    ? new double[]{ x(at) + Math.min(d.dx * from, d.dx * to), y(at) - across, x(at) + Math.max(d.dx * from, d.dx * to), y(at) + across }
                    : new double[]{ x(at) - across, y(at) + Math.min(d.dy * from, d.dy * to), x(at) + across, y(at) + Math.max(d.dy * from, d.dy * to) };
            if (clearOfNodes(box, at, nodes)) {
                boxes.add(box);
                break;
            }
        }
        return boxes;
    }

    /** True if the box {left, top, right, bottom} touches no node but {@code at}. */
    private static boolean clearOfNodes(double[] box, Node at, List<Node> nodes) {
        for (Node n : nodes) {
            if (n == at) continue;
            if (!(x(n) + w(n) / 2 + LINE_MARGIN < box[0] || x(n) - w(n) / 2 - LINE_MARGIN > box[2]
                    || y(n) + h(n) / 2 + LINE_MARGIN < box[1] || y(n) - h(n) / 2 - LINE_MARGIN > box[3])) return false;
        }
        return true;
    }

    /** How clearly the other end of a hyperedge lies in its best stub direction: the cosine of the angle between them. */
    private static double clearness(Node at, Edge e, List<Direction> options) {
        Node other = e.getSource() == at ? e.getTarget() : e.getSource();
        double dx = x(other) - x(at), dy = y(other) - y(at), length = Math.hypot(dx, dy);
        if (options.isEmpty() || length < 1e-9) return -1;
        Direction d = options.get(0);
        return (d.dx * dx + d.dy * dy) / (Math.hypot(d.dx, d.dy) * length);
    }

    /** The hyperedge nodes of the graph at which a hyperedge starts or ends, each with the two pieces of its own arc. */
    private List<Junction> junctions() {
        List<Junction> junctions = new ArrayList<>();
        for (ERLayout.Arc a : arcs) {
            Set<Edge> own = new HashSet<>();
            for (Edge e : a.edges) own.add(graph.containsEdge(e) ? e : liveEdge.get(e));
            for (Node point : a.points()) {
                Node p = now(point);
                if (p == null) continue;
                Junction j = new Junction(p);
                for (Edge e : p.getEdges()) {
                    if (e.getSource() == e.getTarget()) continue;
                    if (own.contains(e)) {
                        if (j.pieces.size() < 2) j.pieces.add(e);
                    } else if (HYPEREDGE_ROLES.contains(ConstraintSBGNLayout.getSBGNRole(e))) {
                        j.attached.add(e);
                    }
                }
                if (j.pieces.size() == 2 && !j.attached.isEmpty()) junctions.add(j);
            }
        }
        return junctions;
    }

    /**
     * The directions in which the two pieces of a hyperedge node's own arc leave it: a piece that stays a straight
     * line, along its diagonal; a routed one, along the axis its other end is closer to. Two routed pieces do not
     * leave the same way: the one further from that axis takes its next one. Null if a piece has no direction (its
     * other end lies on the node).
     */
    private Direction[] pieceDirections(Junction j, Set<Edge> straight) {
        Direction[] u = new Direction[2];
        double[] dx = new double[2], dy = new double[2];
        for (int k = 0; k < 2; k++) {
            Edge e = j.pieces.get(k);
            Node other = e.getSource() == j.node ? e.getTarget() : e.getSource();
            dx[k] = x(other) - x(j.node);
            dy[k] = y(other) - y(j.node);
            u[k] = ERAlignmentPlan.closest(dx[k], dy[k], straight.contains(e) ? ERAlignmentPlan.ANY : ERAlignmentPlan.STRAIGHT);
            if (u[k] == null) return null;
        }
        if (u[0] == u[1]) {
            int k = u[0].deviation(dx[0], dy[0]) >= u[1].deviation(dx[1], dy[1]) ? 0 : 1;
            if (straight.contains(j.pieces.get(k))) k = 1 - k;
            if (!straight.contains(j.pieces.get(k))) u[k] = ERAlignmentPlan.closest(dx[k], dy[k], ERAlignmentPlan.STRAIGHT & ~u[k].bit());
        }
        return u;
    }

    /**
     * True if {@code e} stays a straight line: its two nodes were aligned on a diagonal and lie on one (the alignment
     * may have given way since), none of them is a logical operator (its edges use its ports), and no other node lies
     * on the line.
     */
    private boolean diagonalLine(Edge e, List<Node> nodes) {
        Node u = e.getSource(), w = e.getTarget();
        if (u == w) return false;
        Aligned a = aligned.get(pairKey(u, w));
        if (a == null || !a.direction.diagonal()) return false;
        if (AFLayout.isLogicOperator(u) || AFLayout.isLogicOperator(w)) return false;
        if (Math.abs(Math.abs(x(w) - x(u)) - Math.abs(y(w) - y(u))) > DIAGONAL_TOLERANCE) return false;
        for (Node n : nodes) {
            if (n != u && n != w && ERAlignmentPlan.segmentHitsBox(x(u), y(u), x(w), y(w), x(n), y(n), w(n), h(n), LINE_MARGIN)) return false;
        }
        return true;
    }

    /** The router's word for a side. */
    private static String sideName(Direction d) {
        switch (d) {
            case E: return "right";
            case W: return "left";
            case S: return "down";
            default: return "up";
        }
    }

    /** The port of a logical operator on side {@code d}: {@link #PORT_DISTANCE} outside its border. */
    private static double[] port(Node op, Direction d) {
        double reach = (d.dx != 0 ? w(op) : h(op)) / 2 + PORT_DISTANCE;
        return new double[]{ x(op) + d.dx * reach, y(op) + d.dy * reach };
    }

    // ------------------------------------------------------------------ reset

    /**
     * Reset in the panel: everything step 1 removed and no step has inserted yet comes back, and every inserted node
     * returns to the place it had before step 1 (the host does the same for the nodes that stayed in the graph).
     */
    void reset() {
        putBack(true);
    }

    /**
     * Undoes the removal and forgets the run. {@code toInputPositions} (Reset): the inserted nodes return to the place
     * the nodes they stand for had before the first step; not for a new run on the drawing as it is, which starts from
     * where the nodes are now (the copies then stand for the input map's nodes and edges until Reset).
     */
    private void putBack(boolean toInputPositions) {
        stage = 0;
        for (Node n : removedNodes) insert(n);
        for (Edge e : removedEdges) insert(e);
        for (Map.Entry<Node, Node> en : live.entrySet()) {
            Node input = inputNodeOf.getOrDefault(en.getKey(), en.getKey());
            if (!toInputPositions) {
                inputNodeOf.put(en.getValue(), input);
                continue;
            }
            Point2D.Double p = host.inputPosition(input);
            if (p != null && graph.containsNode(en.getValue())) AttributeHelper.setPosition(en.getValue(), p.x, p.y);
        }
        // the docking of the input map sits on its edges (ConstraintSBGNLayout.restoreDocking); hand it on
        for (Map.Entry<Edge, Edge> en : liveEdge.entrySet()) {
            Edge input = inputEdgeOf.getOrDefault(en.getKey(), en.getKey());
            if (!toInputPositions) inputEdgeOf.put(en.getValue(), input);
            if (!graph.containsEdge(en.getValue())) continue;
            EdgeGraphicAttribute from = (EdgeGraphicAttribute) input.getAttribute(GraphicAttributeConstants.GRAPHICS);
            EdgeGraphicAttribute to = (EdgeGraphicAttribute) en.getValue().getAttribute(GraphicAttributeConstants.GRAPHICS);
            to.getDocking().setSource(from.getDocking().getSource());
            to.getDocking().setTarget(from.getDocking().getTarget());
        }
        refreshLists();
        arcs.clear();
        hyperedges.clear();
        ownerOf.clear();
        removedNodes.clear();
        removedEdges.clear();
        endsOf.clear();
        if (toInputPositions) {
            inputNodeOf.clear();
            inputEdgeOf.clear();
        }
        parts.clear();
        live.clear();
        liveEdge.clear();
        memberships.clear();
        voidedByRemoval.clear();
        edgeConstraints.clear();
        aligned.clear();
        taken.clear();
        outSide.clear();
    }
}
