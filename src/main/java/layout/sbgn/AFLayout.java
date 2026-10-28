package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintFeasibility;
import constraints.ConflictLog;
import constraints.ConstraintGroup;
import constraints.ConstraintStore;
import constraints.HorizontalConstraint;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Constraint layout for SBGN Activity Flow maps, run as the AF steps of {@link ConstraintSBGNLayout}: trees of logical
 * operators stand in as placeholders while a minimal and stable feedback arc set turns the influences into a DAG, whose
 * longest path per component forms a column and whose levels form rows around it; then the trees are inserted, their
 * operators get port stubs and the arcs are routed. The layout never adds or removes graph elements: placeholders and
 * left-out units exist only in the solver calls.
 */
final class AFLayout {

    // ------------------------------------------------------------------ switches

    /** -Dcblayout.afPipeline=af|pd (default af): AF maps run through the steps of this class, or the PD steps. */
    static boolean ENABLED = !"pd".equalsIgnoreCase(System.getProperty("cblayout.afPipeline", "af"));
    /** -Dcblayout.afLevels=iterative|once (default iterative): one solve after every level, or one after all levels. */
    static boolean LEVELS_ONCE = "once".equalsIgnoreCase(System.getProperty("cblayout.afLevels", "iterative"));
    /** -Dcblayout.afLayerGap=px (default 100): free space between a unit and the level above or below it. */
    static double LAYER_GAP = ConstraintSBGNLayout.doubleProperty("cblayout.afLayerGap", 100);
    /** -Dcblayout.afSiblingGap=px (default 70): free space between two neighbours of a row. */
    static double SIBLING_GAP = ConstraintSBGNLayout.doubleProperty("cblayout.afSiblingGap", 70);
    /** -Dcblayout.afSpread=min|fixed (default min): rows keep order and minimum gaps, or get exactly equal gaps. */
    static boolean SPREAD_FIXED = "fixed".equalsIgnoreCase(System.getProperty("cblayout.afSpread", "min"));
    /**
     * -Dcblayout.afRows=constrain|seed (default constrain): a level's rows get order, gap and centring constraints, or
     * are start positions only.
     */
    static boolean ROWS_SEED_ONLY = "seed".equalsIgnoreCase(System.getProperty("cblayout.afRows", "constrain"));
    /** -Dcblayout.afTreeGap=px (default 60): free space between an operator and its inputs inside a tree. */
    static double TREE_GAP = ConstraintSBGNLayout.doubleProperty("cblayout.afTreeGap", 60);
    /** -Dcblayout.afTreePad=px (default 40): margin around a laid-out tree in its placeholder. */
    static double TREE_PAD = ConstraintSBGNLayout.doubleProperty("cblayout.afTreePad", 40);
    /**
     * -Dcblayout.afTreeCentre=free|row (default free): the inputs of an operator that also has an input from outside
     * its tree form two halves beside the operator's axis, where that arc arrives; row: always one row centred on it.
     */
    static boolean TREE_CENTRE_FREE = !"row".equalsIgnoreCase(System.getProperty("cblayout.afTreeCentre", "free"));
    /**
     * -Dcblayout.afIdealLength=px (default 150): ideal arc length in the solves of steps 3 to 6; a tree laid out on its
     * own in step 1 uses CBLayoutAlgorithm.dist.
     */
    static int IDEAL_LENGTH = ConstraintSBGNLayout.intProperty("cblayout.afIdealLength", 150);
    /**
     * -Dcblayout.afRunPatience=n (default 20; -1: libcola's own convergence test): a force-directed run stops after n
     * iterations in a row without lower stress and ends on the best iterate.
     */
    static int RUN_PATIENCE = ConstraintSBGNLayout.intProperty("cblayout.afRunPatience", 20);
    /**
     * -Dcblayout.afFasTie=negative|none (default negative): among equally small feedback arc sets the one with the most
     * negative influences wins, or the first one found.
     */
    static boolean FAS_TIE_NEGATIVE = !"none".equalsIgnoreCase(System.getProperty("cblayout.afFasTie", "negative"));
    /**
     * -Dcblayout.afFinal=fd|project (default fd): the final solve is a force-directed run, or only projects onto the
     * constraints and non-overlap.
     */
    static boolean FINAL_FD = !"project".equalsIgnoreCase(System.getProperty("cblayout.afFinal", "fd"));
    /** -Dcblayout.afNodePad=px (default 25): margin around every node in the AF solves; two nodes keep twice this. */
    static double NODE_PAD = ConstraintSBGNLayout.doubleProperty("cblayout.afNodePad", 25);
    /** -Dcblayout.afRouting=orthogonal|polyline (default orthogonal): how the arcs are routed. */
    static boolean ROUTE_ORTHOGONAL = !"polyline".equalsIgnoreCase(System.getProperty("cblayout.afRouting", "orthogonal"));
    /** -Dcblayout.afNudge=px (default 5): distance parallel arc segments are nudged apart. */
    static double NUDGING = ConstraintSBGNLayout.doubleProperty("cblayout.afNudge", 5);
    /** -Dcblayout.afShapeBuffer=px (default 8): distance an arc keeps from the nodes it passes. */
    static double SHAPE_BUFFER = ConstraintSBGNLayout.doubleProperty("cblayout.afShapeBuffer", 8);
    /**
     * -Dcblayout.afWiden=on|off (default on): where parallel arc segments end up closer than {@link #NUDGING}, the
     * nodes beyond that channel move away by the missing width and the arcs are routed again.
     */
    static boolean WIDEN = !"off".equalsIgnoreCase(System.getProperty("cblayout.afWiden", "on"));
    /**
     * -Dcblayout.afWidenCollapsed=on|off (default on): arcs lying exactly on one line count as too narrow unless they
     * share an operator port, and a bundle is widened once; off: arcs on one line never count.
     */
    static boolean WIDEN_COLLAPSED = !"off".equalsIgnoreCase(System.getProperty("cblayout.afWidenCollapsed", "on"));
    /** -Dcblayout.afCrossingPenalty=p (default 50; 0 = off): the router's penalty for a crossing of two arcs. */
    static double CROSSING_PENALTY = ConstraintSBGNLayout.doubleProperty("cblayout.afCrossingPenalty", 50);
    /** -Dcblayout.afCrossingMaxArcs=n (default 80): maps with more arcs are routed without {@link #CROSSING_PENALTY}. */
    static int CROSSING_MAX_ARCS = ConstraintSBGNLayout.intProperty("cblayout.afCrossingMaxArcs", 80);
    /**
     * -Dcblayout.afPins=heads|off (default heads): the arrowhead end of an arc gets a pin of its own on the activity's
     * border ({@link ERPins#forActivityFlow}), the other end stays at the centre; off: both ends at the centre.
     */
    static boolean HEAD_PINS = !"off".equalsIgnoreCase(System.getProperty("cblayout.afPins", "heads"));
    /** -Dcblayout.afPinSpacing=px (default 16): distance of two arrowheads on one side of an activity. */
    static double PIN_SPACING = ConstraintSBGNLayout.doubleProperty("cblayout.afPinSpacing", 16);
    /** -Dcblayout.afPinStub=px (default 16): length of the straight piece in front of an arrowhead. */
    static double PIN_STUB = ConstraintSBGNLayout.doubleProperty("cblayout.afPinStub", 16);
    /**
     * -Dcblayout.afPortJoin=fan|merge (default fan): the arcs of an operator port stay {@link #NUDGING} apart and each
     * reaches the port with a short 45-degree piece; merge: their last segments move onto the port's axis.
     */
    static boolean PORT_FAN = !"merge".equalsIgnoreCase(System.getProperty("cblayout.afPortJoin", "fan"));
    /**
     * -Dcblayout.afPortLead=px (default 12; 0 = off): an arc leaving an operator passes a point this far in front of the
     * output port, which gives the router a line to turn on right after the port.
     */
    static double PORT_LEAD = ConstraintSBGNLayout.doubleProperty("cblayout.afPortLead", 12);
    /** Routing rounds {@link #WIDEN} may add. */
    static final int WIDEN_ROUNDS = 8;
    /** Space between two connected components placed next to each other. */
    static final double COMPONENT_GAP = 120;

    // ------------------------------------------------------------------ state

    private final ConstraintSBGNLayout host;
    private final Graph graph;

    /** Operators joined by arcs, plus the inputs that feed only them. */
    static final class Tree {
        Node root;
        final List<Node> operators = new ArrayList<>();
        final List<Node> members = new ArrayList<>();
        /** Member -> offset from the box centre, laid out with the output downwards. */
        final Map<Node, double[]> offset = new LinkedHashMap<>();
        /** Member -> offset after step 5 turned the tree to {@link #side}. */
        final Map<Node, double[]> placed = new LinkedHashMap<>();
        double width, height;
        String side = "down";
    }

    private final List<Tree> trees = new ArrayList<>();
    private final Map<Node, Tree> treeOf = new HashMap<>();
    /** Step 5 has run: a root has its own size again. */
    private boolean expanded;
    /** A tree is being laid out on its own: its root has its own size. */
    private boolean layingOutTree;

    /** Nodes outside trees and one root per tree, in key order. */
    private final List<Node> units = new ArrayList<>();
    /** DAG over the units (feedback arcs removed), neighbour lists in key order. */
    private final Map<Node, List<Node>> succ = new HashMap<>(), pred = new HashMap<>();
    /** Units joined by any arc, feedback arcs included. */
    private final Map<Node, Set<Node>> neighbours = new HashMap<>();
    /** Graph edges that belong to a feedback arc. */
    private final Set<Edge> feedbackEdges = new HashSet<>();

    /** Placed units -> level (0 = skeleton), in placement order. */
    private final Map<Node, Integer> level = new LinkedHashMap<>();
    private final List<List<Node>> components = new ArrayList<>();
    /** Skeleton unit -> {previous, next} on its skeleton. */
    private final Map<Node, Node[]> skeletonNeighbours = new HashMap<>();
    /** Separations planned while roots stood for their trees; step 5 moves them onto the root. */
    private final List<SeparationConstraintGraph> planned = new ArrayList<>();
    /** What the steps of this run have put into the store; step 1 of the next run takes it out again. */
    private final List<Constraint> registered = new ArrayList<>();
    /** Node -> position in the input drawing. */
    private final Map<Node, double[]> inputPosition = new HashMap<>();

    AFLayout(ConstraintSBGNLayout host) {
        this.host = host;
        this.graph = host.graph;
    }

    // ------------------------------------------------------------------ roles and geometry

    static boolean isLogicOperator(Node n) {
        String r = ConstraintSBGNLayout.getSBGNRole(n);
        return r != null && (r.endsWith("OPERATOR") || r.equals("DELAY"));
    }

    private static boolean isNegative(Edge e) {
        String r = ConstraintSBGNLayout.getSBGNRole(e);
        return r != null && (r.contains("NEGATIVE") || r.contains("INHIBITION"));
    }

    private static String key(Node n) {
        return TopologySkeleton.key(n);
    }

    private static final Comparator<Node> BY_KEY = Comparator.comparing(AFLayout::key);

    private static double x(Node n) { return AttributeHelper.getPositionX(n); }
    private static double y(Node n) { return AttributeHelper.getPositionY(n); }

    /** Width in the solver: a root stands for its tree box until step 5. */
    private double w(Node n) {
        return isPlaceholder(n) ? treeOf.get(n).width : AttributeHelper.getWidth(n);
    }

    private double h(Node n) {
        return isPlaceholder(n) ? treeOf.get(n).height : AttributeHelper.getHeight(n);
    }

    private boolean isPlaceholder(Node n) {
        Tree t = treeOf.get(n);
        return !expanded && !layingOutTree && t != null && t.root == n;
    }

    private Node unit(Node n) {
        Tree t = treeOf.get(n);
        return t == null ? n : t.root;
    }

    private static String label(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        if (l == null || l.isEmpty()) l = ConstraintSBGNLayout.getSBGNRole(n);
        return l.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<Node> sortedNodes(Collection<Node> nodes) {
        List<Node> l = new ArrayList<>(nodes);
        l.sort(BY_KEY);
        return l;
    }

    private static boolean horizontalSide(String side) {
        return side.equals("left") || side.equals("right");
    }

    // ------------------------------------------------------------------ constraint helpers

    /** {@code other} lies on {@code side} of {@code ref}, at least {@code gap} apart (edge to edge). */
    private SeparationConstraintGraph onSide(Node ref, Node other, String side, double gap, List<Constraint> sink) {
        switch (side) {
            case "up": return separation(other, ref, false, gap, false, sink);
            case "down": return separation(ref, other, false, gap, false, sink);
            case "left": return separation(other, ref, true, gap, false, sink);
            default: return separation(ref, other, true, gap, false, sink);
        }
    }

    /** {@code second} lies right of (horizontal) or below {@code first}, {@code gap} apart edge to edge. */
    private SeparationConstraintGraph separation(Node first, Node second, boolean horizontal, double gap, boolean fixed,
                                                 List<Constraint> sink) {
        double distance = horizontal ? (w(first) + w(second)) / 2 + gap : (h(first) + h(second)) / 2 + gap;
        return separationRaw(first, second, horizontal, distance, fixed, sink);
    }

    private SeparationConstraintGraph separationRaw(Node first, Node second, boolean horizontal, double distance,
                                                    boolean fixed, List<Constraint> sink) {
        SeparationConstraintGraph s = new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(first, second)),
                horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                           : SeparationConstraintGraph.SeparationConstraintType.VERTICAL, fixed, distance);
        sink.add(s);
        if (!expanded) planned.add(s);
        return s;
    }

    /** Same x (vertical line) or same y. */
    private static void align(Node a, Node b, boolean sameX, List<Constraint> sink) {
        List<Node> pair = new ArrayList<>(Arrays.asList(a, b));
        sink.add(sameX ? new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE)
                       : new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
    }

    private void register(List<Constraint> created) {
        for (Constraint c : created) ConstraintStore.constraints.addConstraint(c);
        registered.addAll(created);
    }

    /**
     * A second run without Reset: the constraints of the last run go, this run makes its own. One the user has taken
     * over since ({@link constraints.ConflictLog#restore}) is the user's and stays.
     */
    private void removeRegistered() {
        if (ConstraintStore.constraints != null) {
            for (Constraint c : registered) if (c.isAutomatic()) ConstraintStore.constraints.removeConstraint(c);
        }
        registered.clear();
    }

    // ------------------------------------------------------------------ step 1: LO trees

    /**
     * Step 1: the logical operators joined by arcs form a tree, with the inputs that feed nothing but that tree; each
     * tree is laid out on its own and then stands in as its root operator with the tree's box plus {@link #TREE_PAD}.
     */
    void collapseTrees() {
        trees.clear();
        treeOf.clear();
        units.clear();
        succ.clear();
        pred.clear();
        neighbours.clear();
        feedbackEdges.clear();
        level.clear();
        components.clear();
        skeletonNeighbours.clear();
        planned.clear();
        inputPosition.clear();
        expanded = false;
        removeRegistered();   // before the preparation: it clears the conflict log, which then follows the store
        host.prepareActivityFlow();
        for (Node n : graph.getNodes()) inputPosition.put(n, new double[]{ x(n), y(n) });

        List<Node> nodes = sortedNodes(ConstraintSBGNLayout.layoutNodes(graph));
        Map<Node, Node> parent = new HashMap<>();
        for (Node n : nodes) if (isLogicOperator(n)) parent.put(n, n);
        for (Edge e : graph.getEdges()) {
            if (e.getSource() != e.getTarget() && parent.containsKey(e.getSource()) && parent.containsKey(e.getTarget())) {
                Node a = find(parent, e.getSource()), b = find(parent, e.getTarget());
                if (a != b) parent.put(a, b);
            }
        }
        Map<Node, List<Node>> groups = new LinkedHashMap<>();
        for (Node n : nodes) if (parent.containsKey(n)) groups.computeIfAbsent(find(parent, n), k -> new ArrayList<>()).add(n);
        for (List<Node> ops : groups.values()) {
            Tree t = new Tree();
            t.operators.addAll(ops);
            Set<Node> opSet = new HashSet<>(ops);
            Set<Node> members = new LinkedHashSet<>(ops);
            for (Node op : ops) {
                for (Edge e : op.getEdges()) {
                    Node in = e.getSource();
                    if (e.getTarget() == op && !opSet.contains(in) && feedsOnly(in, opSet)) members.add(in);
                }
            }
            t.members.addAll(sortedNodes(members));
            t.root = chooseRoot(t, ops);
            trees.add(t);
            for (Node m : t.members) treeOf.put(m, t);
        }
        for (Tree t : trees) layoutTree(t);
        for (Node n : nodes) if (treeOf.get(n) == null || treeOf.get(n).root == n) units.add(n);
    }

    private static Node find(Map<Node, Node> parent, Node n) {
        while (parent.get(n) != n) n = parent.get(n);
        return n;
    }

    /** Every arc of {@code n} goes from {@code n} into one of the operators. */
    private static boolean feedsOnly(Node n, Set<Node> operators) {
        if (n.getDegree() == 0) return false;
        for (Edge e : n.getEdges()) {
            if (e.getSource() != n || e.getTarget() == n || !operators.contains(e.getTarget())) return false;
        }
        return true;
    }

    /** The operator whose output leaves the tree, else one without an output inside it, else the first. */
    private static Node chooseRoot(Tree t, List<Node> ops) {
        for (Node op : ops) {
            for (Edge e : op.getEdges()) if (e.getSource() == op && !t.members.contains(e.getTarget())) return op;
        }
        Set<Node> opSet = new HashSet<>(ops);
        for (Node op : ops) {
            boolean feedsOperator = false;
            for (Edge e : op.getEdges()) feedsOperator |= e.getSource() == op && e.getTarget() != op && opSet.contains(e.getTarget());
            if (!feedsOperator) return op;
        }
        return ops.get(0);
    }

    /**
     * Lays the tree out on its own with the output downwards (see {@link #treeConstraints}), seeded as such a drawing
     * with rows ordered by input x; keeps the members' offsets from the box centre and the box size for the placeholder.
     */
    private void layoutTree(Tree t) {
        double cx = 0, cy = 0;
        for (Node m : t.members) { cx += x(m); cy += y(m); }
        cx /= t.members.size();
        cy /= t.members.size();
        layingOutTree = true;
        seedTree(t, t.root, cx, cy, new HashSet<>());
        if (t.members.size() > 1) {
            List<Constraint> local = new ArrayList<>();
            treeConstraints(t, "down", local);
            planned.removeAll(local);
            List<Node[]> edges = new ArrayList<>();
            for (Edge e : graph.getEdges()) {
                if (t.members.contains(e.getSource()) && t.members.contains(e.getTarget())) edges.add(new Node[]{ e.getSource(), e.getTarget() });
            }
            solveNodes(t.members, null, edges, local, true, CBLayoutAlgorithm.dist);
        }
        layingOutTree = false;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Node m : t.members) {
            minX = Math.min(minX, x(m) - AttributeHelper.getWidth(m) / 2);
            maxX = Math.max(maxX, x(m) + AttributeHelper.getWidth(m) / 2);
            minY = Math.min(minY, y(m) - AttributeHelper.getHeight(m) / 2);
            maxY = Math.max(maxY, y(m) + AttributeHelper.getHeight(m) / 2);
        }
        double bx = (minX + maxX) / 2, by = (minY + maxY) / 2;
        t.width = maxX - minX + 2 * TREE_PAD;
        t.height = maxY - minY + 2 * TREE_PAD;
        t.offset.clear();
        for (Node m : t.members) t.offset.put(m, new double[]{ x(m) - bx, y(m) - by });
        // The drawing of this step shows the tree laid out where its members were.
        for (Node m : t.members) AttributeHelper.setPosition(m, cx + t.offset.get(m)[0], cy + t.offset.get(m)[1]);
    }

    /** Inputs in the row of {@code op} inside the tree, ordered by input-drawing x ({@code byInputX}) or current x. */
    private List<Node> treeInputs(Tree t, Node op, boolean byInputX) {
        List<Node> ins = new ArrayList<>();
        for (Edge e : op.getEdges()) {
            Node in = e.getSource();
            if (e.getTarget() == op && in != op && t.members.contains(in) && !ins.contains(in) && rowOwner(t, in) == op) ins.add(in);
        }
        ins.sort(BY_KEY);
        final Map<Node, Double> pos = new HashMap<>();
        for (Node in : ins) pos.put(in, byInputX ? inputX(in) : x(in));
        ins.sort(Comparator.comparingDouble(pos::get));
        return ins;
    }

    /** The operator whose row an input of the tree belongs to: the first one (key order) it feeds. */
    private static Node rowOwner(Tree t, Node in) {
        for (Node op : t.operators) {
            for (Edge e : in.getEdges()) if (e.getSource() == in && e.getTarget() == op) return op;
        }
        return null;
    }

    private void seedTree(Tree t, Node op, double ox, double oy, Set<Node> seen) {
        if (!seen.add(op)) return;
        AttributeHelper.setPosition(op, ox, oy);
        List<Node> ins = treeInputs(t, op, true);
        double total = 0;
        for (Node in : ins) total += AttributeHelper.getWidth(in);
        total += TREE_GAP * Math.max(0, ins.size() - 1);
        double left = ox - total / 2;
        for (Node in : ins) {
            double iw = AttributeHelper.getWidth(in);
            double iy = oy - (AttributeHelper.getHeight(op) + AttributeHelper.getHeight(in)) / 2 - TREE_GAP;
            if (isLogicOperator(in)) seedTree(t, in, left + iw / 2, iy, seen);
            else if (seen.add(in)) AttributeHelper.setPosition(in, left + iw / 2, iy);
            left += iw + TREE_GAP;
        }
    }

    /**
     * Inside the tree, output towards {@code side}: every input on the input side of its operator (arcs that close a cycle
     * inside the tree are left out), the inputs of an operator in a row across the flow, centred on it; with
     * {@link #TREE_CENTRE_FREE} and an input from outside the tree, in two halves beside the operator's axis.
     */
    private void treeConstraints(Tree t, String side, List<Constraint> sink) {
        String inSide = TopologySkeleton.opposite(side);
        boolean flowVertical = !horizontalSide(side);
        List<Node[]> arcs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Edge e : graph.getEdges()) {
            Node a = e.getSource(), b = e.getTarget();
            if (a != b && t.members.contains(a) && t.members.contains(b) && seen.add(a.getID() + ">" + b.getID())) arcs.add(new Node[]{ a, b });
        }
        int[] from = new int[arcs.size()], to = new int[arcs.size()];
        for (int i = 0; i < arcs.size(); i++) {
            from[i] = t.members.indexOf(arcs.get(i)[0]);
            to[i] = t.members.indexOf(arcs.get(i)[1]);
        }
        boolean[] cyclic = FeedbackArcSet.compute(t.members.size(), from, to, null);
        for (int i = 0; i < arcs.size(); i++) {
            if (!cyclic[i]) onSide(arcs.get(i)[1], arcs.get(i)[0], inSide, TREE_GAP, sink);
        }
        for (Node op : t.operators) {
            List<Node> row = treeInputs(t, op, false);
            if (!flowVertical) row.sort(Comparator.comparingDouble(AFLayout::y));
            if (TREE_CENTRE_FREE && !row.isEmpty() && hasOutsideInput(t, op)) {
                // the row in two halves beside the operator's axis (an odd one out: on the side it has in the input
                // drawing), and with each input everything that feeds it
                int k = row.size(), left = k / 2;
                if (k % 2 == 1 && inputX(row.get(k / 2)) < inputX(op)) left++;
                for (int i = 1; i < k; i++) if (i != left) separation(row.get(i - 1), row.get(i), flowVertical, TREE_GAP, SPREAD_FIXED, sink);
                // (operators that feed each other in a circle lead back to the operator or to another input of the
                // row: those keep the side they have)
                Set<Node> sided = new HashSet<>(row);
                sided.add(op);
                for (int i = 0; i < k; i++) {
                    List<Node> feeding = new ArrayList<>();
                    feeding.add(row.get(i));
                    collectFeeding(t, row.get(i), feeding, sided);
                    for (Node m : feeding) {
                        if (i < left) separation(m, op, flowVertical, TREE_GAP / 2, false, sink);
                        else separation(op, m, flowVertical, TREE_GAP / 2, false, sink);
                    }
                }
                continue;
            }
            spreadRow(op, row, null, 0, !flowVertical, TREE_GAP, sink);
        }
    }

    /** Whether an arc from outside the tree ends at {@code op}. */
    private static boolean hasOutsideInput(Tree t, Node op) {
        for (Edge e : op.getEdges()) {
            if (e.getTarget() == op && e.getSource() != op && !t.members.contains(e.getSource())) return true;
        }
        return false;
    }

    /**
     * If {@code in} is an operator of the tree: everything in the tree that feeds it and is not in {@code sided} yet
     * goes to {@code out} (and to {@code sided}).
     */
    private void collectFeeding(Tree t, Node in, List<Node> out, Set<Node> sided) {
        if (!t.operators.contains(in)) return;
        for (Node m : treeInputs(t, in, false)) {
            if (!sided.add(m)) continue;
            out.add(m);
            collectFeeding(t, m, out, sided);
        }
    }

    // ------------------------------------------------------------------ step 2: feedback arc set

    /**
     * Step 2: on the graph of units (nodes outside trees and the placeholders) MS-FAS ({@link FeedbackArcSet}) removes a
     * minimal and stable set of arcs; what remains is a DAG.
     */
    void computeFeedbackArcs() {
        succ.clear();
        pred.clear();
        neighbours.clear();
        feedbackEdges.clear();
        Map<Node, Integer> index = new HashMap<>();
        for (Node u : units) {
            index.put(u, index.size());
            succ.put(u, new ArrayList<>());
            pred.put(u, new ArrayList<>());
            neighbours.put(u, new LinkedHashSet<>());
        }
        List<Edge> edges = new ArrayList<>(ConstraintSBGNLayout.layoutEdges(graph));
        edges.sort(Comparator.comparing((Edge e) -> key(e.getSource())).thenComparing(e -> key(e.getTarget()))
                .thenComparing(ConstraintSBGNLayout::getSBGNRole));
        Map<Long, Integer> arcOf = new LinkedHashMap<>();
        List<Node[]> arcs = new ArrayList<>();
        List<List<Edge>> arcEdges = new ArrayList<>();
        for (Edge e : edges) {
            Node u = unit(e.getSource()), v = unit(e.getTarget());
            if (u == v || !index.containsKey(u) || !index.containsKey(v)) continue;
            long k = ((long) index.get(u) << 32) | index.get(v);
            Integer a = arcOf.get(k);
            if (a == null) {
                a = arcs.size();
                arcOf.put(k, a);
                arcs.add(new Node[]{ u, v });
                arcEdges.add(new ArrayList<>());
            }
            arcEdges.get(a).add(e);
        }
        int[] from = new int[arcs.size()], to = new int[arcs.size()];
        double[] weight = new double[arcs.size()];
        for (int a = 0; a < arcs.size(); a++) {
            from[a] = index.get(arcs.get(a)[0]);
            to[a] = index.get(arcs.get(a)[1]);
            for (Edge e : arcEdges.get(a)) if (isNegative(e)) weight[a] = 1;
        }
        boolean[] feedback = FeedbackArcSet.compute(units.size(), from, to, FAS_TIE_NEGATIVE ? weight : null);
        for (int a = 0; a < arcs.size(); a++) {
            Node u = arcs.get(a)[0], v = arcs.get(a)[1];
            neighbours.get(u).add(v);
            neighbours.get(v).add(u);
            if (feedback[a]) {
                feedbackEdges.addAll(arcEdges.get(a));
            } else {
                succ.get(u).add(v);
                pred.get(v).add(u);
            }
        }
        for (Node u : units) {
            succ.get(u).sort(BY_KEY);
            pred.get(u).sort(BY_KEY);
        }
    }

    // ------------------------------------------------------------------ step 3: skeleton

    /**
     * Step 3: a longest path of the DAG per connected component is aligned in one column, top to bottom; all other
     * units stay out of the solver for now.
     */
    void layoutSkeleton() {
        level.clear();
        components.clear();
        skeletonNeighbours.clear();
        Set<Node> seen = new HashSet<>();
        for (Node u : units) {
            if (!seen.add(u)) continue;
            List<Node> comp = new ArrayList<>();
            Deque<Node> queue = new ArrayDeque<>(Collections.singletonList(u));
            while (!queue.isEmpty()) {
                Node v = queue.poll();
                comp.add(v);
                for (Node nb : sortedNodes(neighbours.get(v))) if (seen.add(nb)) queue.add(nb);
            }
            comp.sort(BY_KEY);
            components.add(comp);
        }
        components.sort((a, b) -> a.size() != b.size() ? Integer.compare(b.size(), a.size()) : key(a.get(0)).compareTo(key(b.get(0))));
        List<Constraint> created = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            List<Node> path = longestPath(components.get(i));
            int firstOfPath = created.size();
            double top = 0;
            for (int k = 0; k < path.size(); k++) {
                Node s = path.get(k);
                if (k > 0) top += (h(path.get(k - 1)) + h(s)) / 2 + LAYER_GAP;
                AttributeHelper.setPosition(s, 0, top);
                level.put(s, 0);
                skeletonNeighbours.put(s, new Node[]{ k > 0 ? path.get(k - 1) : null, k + 1 < path.size() ? path.get(k + 1) : null });
                if (k > 0) separation(path.get(k - 1), s, false, LAYER_GAP, false, created);
            }
            if (path.size() > 1) created.add(new VerticalConstraint(new ArrayList<>(path), VerticalConstraint.VerticalConstraintType.MIDDLE));
            if (created.size() > firstOfPath) {
                new ConstraintGroup("column", "Longest path " + label(path.get(0)) + " → " + label(path.get(path.size() - 1)), "AF Skeleton")
                        .addAll(created.subList(firstOfPath, created.size()));
            }
        }
        register(created);
        solvePlaced("AF Skeleton");
    }

    /** Longest paths enumerated at most for the chord tie-break; beyond that the degree tie-break alone decides. */
    private static final int MAX_PATHS = 20000;

    /**
     * A longest path of the component's DAG (number of arcs). Among equally long paths the one with the fewest chords
     * wins, i.e. unit pairs joined by an arc (feedback arcs included) that are not consecutive on the path: a chord runs
     * along the column and crosses every arc leaving it sideways. Then the path through more arcs (sum of DAG degrees),
     * then the first in key order. With more than {@link #MAX_PATHS} longest paths only the degree sum decides.
     */
    private List<Node> longestPath(List<Node> comp) {
        Map<Node, Integer> indeg = new HashMap<>();
        for (Node v : comp) indeg.put(v, pred.get(v).size());
        PriorityQueue<Node> free = new PriorityQueue<>(BY_KEY);
        for (Node v : comp) if (indeg.get(v) == 0) free.add(v);
        Map<Node, Integer> length = new HashMap<>(), score = new HashMap<>();
        Map<Node, Long> count = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        List<Node> topo = new ArrayList<>();
        while (!free.isEmpty()) {
            Node v = free.poll();
            topo.add(v);
            int degree = succ.get(v).size() + pred.get(v).size();
            length.put(v, 0);
            score.put(v, degree);
            count.put(v, 1L);
            for (Node p : pred.get(v)) {
                int l = length.get(p) + 1, s = score.get(p) + degree;
                if (l > length.get(v)) count.put(v, 0L);
                if (l >= length.get(v)) count.put(v, Math.min(MAX_PATHS + 1L, count.get(v) + count.get(p)));
                if (l > length.get(v) || (l == length.get(v) && s > score.get(v))) {
                    length.put(v, l);
                    score.put(v, s);
                    previous.put(v, p);
                }
            }
            for (Node s : succ.get(v)) {
                indeg.put(s, indeg.get(s) - 1);
                if (indeg.get(s) == 0) free.add(s);
            }
        }
        Node best = null;
        long paths = 0;
        int longest = -1;
        for (Node v : topo) longest = Math.max(longest, length.get(v));
        for (Node v : topo) {
            if (length.get(v) != longest) continue;
            paths = Math.min(MAX_PATHS + 1L, paths + count.get(v));
            if (best == null || score.get(v) > score.get(best)) best = v;
        }
        LinkedList<Node> path = new LinkedList<>();
        for (Node v = best; v != null; v = previous.get(v)) path.addFirst(v);
        if (paths <= MAX_PATHS && paths > 1) {
            List<List<Node>> all = new ArrayList<>();
            for (Node end : topo) if (length.get(end) == longest) collectPaths(end, length, new LinkedList<>(), all);
            int bestChords = chords(path);
            int bestScore = score.get(best);
            for (List<Node> candidate : all) {
                int c = chords(candidate), s = 0;
                for (Node v : candidate) s += succ.get(v).size() + pred.get(v).size();
                if (c < bestChords || (c == bestChords && s > bestScore)) {
                    path = new LinkedList<>(candidate);
                    bestChords = c;
                    bestScore = s;
                }
            }
        }
        return path;
    }

    /** All longest paths ending in {@code v} (predecessors in key order), prepended to {@code tail}. */
    private void collectPaths(Node v, Map<Node, Integer> length, LinkedList<Node> tail, List<List<Node>> out) {
        tail.addFirst(v);
        if (length.get(v) == 0) {
            out.add(new ArrayList<>(tail));
        } else {
            for (Node p : pred.get(v)) {
                if (length.get(p) == length.get(v) - 1) collectPaths(p, length, tail, out);
            }
        }
        tail.removeFirst();
    }

    /** Pairs of path units joined by an arc but not consecutive on the path. */
    private int chords(List<Node> path) {
        Map<Node, Integer> at = new HashMap<>();
        for (int i = 0; i < path.size(); i++) at.put(path.get(i), i);
        int c = 0;
        for (int i = 0; i < path.size(); i++) {
            for (Node nb : neighbours.get(path.get(i))) {
                Integer j = at.get(nb);
                if (j != null && j > i + 1) c++;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ step 4: levels

    /**
     * Step 4: level e holds the units joined to level e-1, below an anchor they come from and above one they point to,
     * in rows around their anchors (see {@link #placeLevel}); every other DAG arc between placed units points downwards.
     */
    void addLevels() {
        List<Node> frontier = new ArrayList<>(level.keySet());
        int e = 1;
        while (true) {
            List<Constraint> created = new ArrayList<>();
            List<Node> fresh = placeLevel(frontier, e, created);
            if (fresh.isEmpty()) break;
            if (!created.isEmpty()) new ConstraintGroup("level", "Level " + e, "AF Level " + e).addAll(created);
            register(created);
            if (!LEVELS_ONCE) solvePlaced("AF Level " + e);
            frontier = fresh;
            e++;
        }
        // A unit no level reached (not expected: a minimal feedback arc set keeps every component connected).
        for (Node u : units) if (!level.containsKey(u)) level.put(u, e);
        if (LEVELS_ONCE) solvePlaced("AF Levels");
    }

    /**
     * Places level {@code e}: a unit with one parent in e-1 joins that anchor's row, spread evenly around it with the
     * skeleton neighbour in the middle; a unit with several lies below (above) all of them and stays free across the
     * flow. Returns the new units (empty when done).
     */
    private List<Node> placeLevel(List<Node> frontier, int e, List<Constraint> sink) {
        Set<Node> frontierSet = new HashSet<>(frontier);
        LinkedHashSet<Node> freshSet = new LinkedHashSet<>();
        for (Node u : frontier) {
            for (Node v : pred.get(u)) if (!level.containsKey(v)) freshSet.add(v);
            for (Node v : succ.get(u)) if (!level.containsKey(v)) freshSet.add(v);
        }
        List<Node> fresh = new ArrayList<>(freshSet);
        if (fresh.isEmpty()) return fresh;

        // Parents: the placed predecessors in e-1 (the unit goes below them), else the placed successors (above them).
        // A unit with one parent joins that anchor's row; a unit with several stays free across the flow and starts
        // between its parents.
        Map<Node, List<Node>> parentsOf = new HashMap<>();
        Map<Node, Boolean> belowOf = new HashMap<>();
        Map<String, List<Node>> rows = new LinkedHashMap<>();
        Map<String, Node> rowAnchor = new HashMap<>();
        List<Node> shared = new ArrayList<>();
        for (Node v : fresh) {
            List<Node> parents = new ArrayList<>();
            for (Node p : pred.get(v)) if (frontierSet.contains(p)) parents.add(p);
            boolean below = !parents.isEmpty();
            if (!below) for (Node s : succ.get(v)) if (frontierSet.contains(s)) parents.add(s);
            parentsOf.put(v, parents);
            belowOf.put(v, below);
            if (parents.size() > 1) {
                shared.add(v);
                continue;
            }
            String rowKey = parents.get(0).getID() + (below ? "v" : "^");
            rows.computeIfAbsent(rowKey, k -> new ArrayList<>()).add(v);
            rowAnchor.put(rowKey, parents.get(0));
        }

        for (Map.Entry<String, List<Node>> row : rows.entrySet()) {
            Node u = rowAnchor.get(row.getKey());
            boolean below = row.getKey().endsWith("v");
            List<Node> members = row.getValue();
            final Map<Node, Double> desired = desiredX(members, u);
            members.sort(BY_KEY);
            members.sort(Comparator.comparingDouble(desired::get));

            Node[] skel = skeletonNeighbours.get(u);
            Node centre = skel == null ? null : (below ? skel[1] : skel[0]);
            String side = below ? "down" : "up";
            for (Node v : members) {
                onSide(u, v, side, LAYER_GAP, sink);
                AttributeHelper.setPosition(v, x(u), y(u) + (below ? 1 : -1) * ((h(u) + h(v)) / 2 + LAYER_GAP));
            }
            int left = centre == null ? 0 : bestSplit(u, members, centre, desired);
            if (!ROWS_SEED_ONLY) spreadRow(u, members, centre, left, false, SIBLING_GAP, sink);
            seedRow(u, members, centre, left);
        }

        for (Node v : shared) {
            boolean below = belowOf.get(v);
            List<Node> parents = parentsOf.get(v);
            double sx = 0, sy = below ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            boolean oneColumn = true;
            for (Node p : parents) {
                onSide(p, v, below ? "down" : "up", LAYER_GAP, sink);
                sx += x(p);
                oneColumn &= Math.abs(x(p) - x(parents.get(0))) < 1;
                double py = y(p) + (below ? 1 : -1) * ((h(p) + h(v)) / 2 + LAYER_GAP);
                sy = below ? Math.max(sy, py) : Math.min(sy, py);
            }
            sx /= parents.size();
            if (oneColumn) {   // parents stacked in one column: start beside it, on the side of the input drawing
                Node p = parents.get(0);
                sx += (inputX(v) >= inputX(p) ? 1 : -1) * ((w(p) + w(v)) / 2 + SIBLING_GAP);
            }
            AttributeHelper.setPosition(v, sx, sy);
        }

        for (Node v : fresh) level.put(v, e);
        // Every other DAG arc between a new unit and a placed one points downwards too (each arc once).
        Set<Node> freshLookup = new HashSet<>(fresh);
        for (Node v : fresh) {
            for (Node p : pred.get(v)) {
                if (!level.containsKey(p) || (belowOf.get(v) && parentsOf.get(v).contains(p))) continue;
                onSide(p, v, "down", LAYER_GAP, sink);
            }
            for (Node s : succ.get(v)) {
                if (!level.containsKey(s) || freshLookup.contains(s) || (!belowOf.get(v) && parentsOf.get(v).contains(s))) continue;
                onSide(s, v, "up", LAYER_GAP, sink);
            }
        }
        return fresh;
    }

    /**
     * Where each unit of a row would like to sit: above the mean x of its placed neighbours besides the anchor; without
     * such a neighbour at its offset to the anchor in the input drawing.
     */
    private Map<Node, Double> desiredX(List<Node> members, Node anchor) {
        Map<Node, Double> desired = new HashMap<>();
        for (Node v : members) {
            double sum = 0;
            int n = 0;
            for (Node nb : neighbours.get(v)) {
                if (nb != anchor && level.containsKey(nb)) { sum += x(nb); n++; }
            }
            desired.put(v, n > 0 ? sum / n : x(anchor) + inputX(v) - inputX(anchor));
        }
        return desired;
    }

    /**
     * How many units of the (ordered) row go left of the centre unit: the split whose slots lie closest to the desired
     * positions (sum of distances), the more balanced one on a tie.
     */
    private int bestSplit(Node anchor, List<Node> members, Node centre, Map<Node, Double> desired) {
        int k = members.size(), best = k / 2;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int m = 0; m <= k; m++) {
            double[] slots = rowSlots(anchor, members, centre, m);
            double cost = 0;
            for (int i = 0; i < k; i++) cost += Math.abs(desired.get(members.get(i)) - slots[i]);
            boolean moreBalanced = Math.abs(2 * m - k) < Math.abs(2 * best - k);
            if (cost < bestCost - 1e-6 || (Math.abs(cost - bestCost) <= 1e-6 && moreBalanced)) {
                bestCost = cost;
                best = m;
            }
        }
        return best;
    }

    /**
     * Seed x of each row unit (in {@code members} order) as {@link #spreadRow} holds the row: {@code left} units left of
     * the centre unit, which stays at the anchor's x; without a centre the row is centred on the anchor.
     */
    private double[] rowSlots(Node anchor, List<Node> members, Node centre, int left) {
        int k = members.size();
        List<Node> chain = new ArrayList<>(members);
        if (centre != null) chain.add(left, centre);
        double[] cx = new double[chain.size()];
        for (int i = 1; i < chain.size(); i++) cx[i] = cx[i - 1] + (w(chain.get(i - 1)) + w(chain.get(i))) / 2 + SIBLING_GAP;
        double shift;
        if (centre != null) shift = x(anchor) - cx[left];
        else if (k % 2 == 1) shift = x(anchor) - cx[k / 2];
        else shift = x(anchor) - (cx[k / 2 - 1] + cx[k / 2]) / 2;
        double[] slots = new double[k];
        for (int i = 0, j = 0; i < chain.size(); i++) {
            if (chain.get(i) != centre) slots[j++] = cx[i] + shift;
        }
        return slots;
    }

    /**
     * The row {@code members} across the flow of {@code anchor}, in the given order: neighbours keep their order and at
     * least {@code gap} between them (-Dcblayout.afSpread=fixed: exactly {@code gap}). With a {@code centre} unit (the
     * anchor's skeleton neighbour on this side) the first {@code left} units go left of it and the rest right, otherwise
     * the row is centred on the anchor: the middle unit is aligned with it, or the anchor lies between the two middle units.
     *
     * @param horizontalFlow the flow runs left/right, so the row is a column
     */
    private void spreadRow(Node anchor, List<Node> members, Node centre, int left, boolean horizontalFlow, double gap, List<Constraint> sink) {
        int k = members.size();
        if (k == 0) return;
        boolean acrossX = !horizontalFlow;
        List<Node> chain = new ArrayList<>(members);
        if (centre != null) chain.add(left, centre);
        for (int i = 1; i < chain.size(); i++) separation(chain.get(i - 1), chain.get(i), acrossX, gap, SPREAD_FIXED, sink);
        if (centre != null) return;
        if (k % 2 == 1) {
            align(anchor, members.get(k / 2), acrossX, sink);
        } else {
            Node a = members.get(k / 2 - 1), b = members.get(k / 2);
            double half = ((acrossX ? w(a) + w(b) : h(a) + h(b)) / 2 + gap) / 2;
            separationRaw(a, anchor, acrossX, half, SPREAD_FIXED, sink);
            separationRaw(anchor, b, acrossX, half, SPREAD_FIXED, sink);
        }
    }

    /** Seeds the x of a row as {@link #spreadRow} will hold it. */
    private void seedRow(Node anchor, List<Node> members, Node centre, int left) {
        double[] slots = rowSlots(anchor, members, centre, left);
        for (int i = 0; i < members.size(); i++) AttributeHelper.setPosition(members.get(i), slots[i], y(members.get(i)));
    }

    /** x of a unit in the input drawing; a root stands for the centre of its tree's members. */
    private double inputX(Node n) {
        Tree t = treeOf.get(n);
        if (t == null || t.root != n) return inputPosition.containsKey(n) ? inputPosition.get(n)[0] : x(n);
        double sum = 0;
        for (Node m : t.members) sum += inputPosition.containsKey(m) ? inputPosition.get(m)[0] : x(m);
        return sum / t.members.size();
    }

    // ------------------------------------------------------------------ step 5: insert LO trees

    /**
     * Step 5: the trees replace their placeholders, turned towards the side their output goes to; separations planned
     * against a placeholder are moved onto the root operator.
     */
    void expandTrees() {
        for (Tree t : trees) {
            double cx = x(t.root), cy = y(t.root);
            t.side = treeSide(t, cx, cy);
            t.placed.clear();
            for (Node m : t.members) {
                double[] o = turn(t.offset.get(m), t.side);
                t.placed.put(m, o);
                AttributeHelper.setPosition(m, cx + o[0], cy + o[1]);
            }
        }
        expanded = true;
        // A separation against a placeholder held its box centre; the root sits at its offset inside the box.
        Set<Constraint> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(ConstraintStore.constraints.getAllConstraints());
        for (SeparationConstraintGraph s : planned) {
            if (!all.contains(s)) continue;
            boolean horizontal = s.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
            double d = s.getDistance();
            Node a = s.getNodes().get(0), b = s.getNodes().get(1);
            Tree ta = treeOf.get(a), tb = treeOf.get(b);
            if (ta != null && ta.root == a) d -= ta.placed.get(a)[horizontal ? 0 : 1];
            if (tb != null && tb.root == b) d += tb.placed.get(b)[horizontal ? 0 : 1];
            s.setDistance(d);
        }
        planned.clear();
    }

    /** The side the tree's output goes to: outputs pull, inputs push, feedback arcs do not count; default down. */
    private String treeSide(Tree t, double cx, double cy) {
        double fx = 0, fy = 0;
        for (Node m : t.members) {
            for (Edge e : m.getEdges()) {
                Node o = e.getSource() == m ? e.getTarget() : e.getSource();
                if (t.members.contains(o) || feedbackEdges.contains(e)) continue;
                double dx = x(o) - cx, dy = y(o) - cy, len = Math.hypot(dx, dy);
                if (len < 1e-9) continue;
                double sign = e.getSource() == m ? 1 : -1;
                fx += sign * dx / len;
                fy += sign * dy / len;
            }
        }
        if (Math.abs(fx) < 1e-9 && Math.abs(fy) < 1e-9) return "down";
        if (Math.abs(fy) >= Math.abs(fx)) return fy >= 0 ? "down" : "up";
        return fx > 0 ? "right" : "left";
    }

    /** An offset of the downward tree drawing, turned so the output points to {@code side}. */
    private static double[] turn(double[] o, String side) {
        switch (side) {
            case "up": return new double[]{ o[0], -o[1] };
            case "right": return new double[]{ o[1], o[0] };
            case "left": return new double[]{ -o[1], o[0] };
            default: return new double[]{ o[0], o[1] };
        }
    }

    // ------------------------------------------------------------------ step 6: LO stubs

    /**
     * Step 6: inputs of an operator lie on its input side, its output on the other side, and the router gets a stub at
     * both ports ({@link ConstraintSBGNLayout#orientationLogic}); final solve over all nodes and arcs.
     */
    void addStubsAndSolve() {
        ConstraintSBGNLayout.orientationLogic.clear();
        List<Constraint> created = new ArrayList<>();
        for (Tree t : trees) {
            String out = t.side, in = TopologySkeleton.opposite(out);
            int firstOfTree = created.size();
            treeConstraints(t, out, created);
            Set<String> done = new HashSet<>();
            for (Node op : t.operators) {
                for (Edge e : op.getEdges()) {
                    if (e.getSource() == e.getTarget()) continue;
                    boolean incoming = e.getTarget() == op;
                    Node other = incoming ? e.getSource() : e.getTarget();
                    String[] sides = ConstraintSBGNLayout.orientationLogic.computeIfAbsent(e, k -> new String[2]);
                    sides[incoming ? 1 : 0] = incoming ? in : out;
                    if (t.members.contains(other) || op == t.root || feedbackEdges.contains(e)) continue;
                    if (done.add(op.getID() + ":" + other.getID())) onSide(op, other, incoming ? in : out, LAYER_GAP, created);
                }
            }
            if (created.size() > firstOfTree) {
                new ConstraintGroup("tree", "Operator tree at " + label(t.root), "AF Stubs").addAll(created.subList(firstOfTree, created.size()));
            }
        }
        register(created);
        host.enforceFeasibility("AF Stubs");
        List<Node> all = sortedNodes(ConstraintSBGNLayout.layoutNodes(graph));
        List<Node[]> edges = new ArrayList<>();
        for (Edge e : ConstraintSBGNLayout.layoutEdges(graph)) edges.add(new Node[]{ e.getSource(), e.getTarget() });
        solveNodes(all, null, edges, ConstraintStore.constraints.getAllConstraints(), FINAL_FD, IDEAL_LENGTH);
        packComponents();
    }

    // ------------------------------------------------------------------ step 7: edge routing

    /** Step 7: routes the arcs, with further rounds while {@link #widenChannels} makes room. */
    void route() {
        widened.clear();
        withoutPins = false;
        final Map<Node, double[]> grown = new HashMap<>();   // what ERPins keeps from one routing round to the next
        host.finishActivityFlow(ROUTE_ORTHOGONAL, NUDGING, SHAPE_BUFFER, () -> HEAD_PINS && !withoutPins ? grown : null,
                ROUTE_ORTHOGONAL && WIDEN ? this::widenChannels : null, WIDEN_ROUNDS);
    }

    /** {@link #widenChannels}: the bundles (their arcs and missing width) it has made room for in this routing. */
    private final Set<String> widened = new HashSet<>();
    /** {@link #widenChannels} has given up the arrowhead pins ({@link #HEAD_PINS}) for this map. */
    private boolean withoutPins;

    /** A bundle of arcs that is too narrow: where it lies, the width it lacks, and its arcs. */
    private static final class Bundle {
        final double middle, missing;
        final String arcs;

        Bundle(double middle, double missing, String arcs) {
            this.middle = middle;
            this.missing = missing;
            this.arcs = arcs;
        }
    }

    /**
     * Moves every node beyond a bundle of parallel arc segments the router could not nudge {@link #NUDGING} apart away
     * by the missing width (vertical bundles to the right, horizontal ones down), so no two nodes get closer; an exact
     * gap across a widened channel gets its new value. True if the arcs are to be routed again.
     * <p>
     * A bundle that comes back unchanged after it was widened does not lack room and gets no more. Pins fix the order
     * of the arc ends on a node, so once only such bundles are left, the arcs are routed once more without
     * {@link #HEAD_PINS}; if only such bundles are left again, the rounds end.
     */
    private boolean widenChannels() {
        if (positionsFixed("Arc bundles were not widened")) return false;
        // {position, from, to, number of the arc, key of the operator port the arc starts or ends at (0: none)}
        List<double[]> vertical = new ArrayList<>(), horizontal = new ArrayList<>();
        int arc = 0;
        for (Edge e : ConstraintSBGNLayout.layoutEdges(graph)) {
            List<Vector2d> bends = new ArrayList<>(AttributeHelper.getEdgeBends(e));
            arc++;
            String[] sides = ConstraintSBGNLayout.orientationLogic.get(e);
            double port = sides == null ? 0 : sides[1] != null ? e.getTarget().getID() + 1 : sides[0] != null ? -(e.getSource().getID() + 1) : 0;
            // the pieces at the two nodes are left out: how close they get is a matter of the node's size
            for (int i = 1; i < bends.size(); i++) {
                Vector2d p = bends.get(i - 1), q = bends.get(i);
                if (Math.abs(p.x - q.x) < 0.01 && Math.abs(p.y - q.y) > 1) vertical.add(new double[]{ p.x, Math.min(p.y, q.y), Math.max(p.y, q.y), arc, port });
                else if (Math.abs(p.y - q.y) < 0.01 && Math.abs(p.x - q.x) > 1) horizontal.add(new double[]{ p.y, Math.min(p.x, q.x), Math.max(p.x, q.x), arc, port });
            }
        }
        List<Bundle> cutsX = narrowBundles(vertical), cutsY = narrowBundles(horizontal);
        if (cutsX.isEmpty() && cutsY.isEmpty()) return false;
        if (WIDEN_COLLAPSED) {
            for (Iterator<Bundle> it = cutsX.iterator(); it.hasNext(); ) if (!widened.add("x" + it.next().arcs)) it.remove();
            for (Iterator<Bundle> it = cutsY.iterator(); it.hasNext(); ) if (!widened.add("y" + it.next().arcs)) it.remove();
        }
        if (cutsX.isEmpty() && cutsY.isEmpty()) {
            if (withoutPins || !HEAD_PINS) return false;
            withoutPins = true;
            widened.clear();
            return true;
        }
        List<Node> nodes = ConstraintSBGNLayout.layoutNodes(graph);
        Map<Node, double[]> shift = new HashMap<>();
        for (Node n : nodes) {
            double dx = 0, dy = 0;
            for (Bundle c : cutsX) if (x(n) > c.middle) dx += c.missing;
            for (Bundle c : cutsY) if (y(n) > c.middle) dy += c.missing;
            shift.put(n, new double[]{ dx, dy });
        }
        for (Node n : nodes) AttributeHelper.setPosition(n, x(n) + shift.get(n)[0], y(n) + shift.get(n)[1]);
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (!(c instanceof SeparationConstraintGraph) || !c.isAutomatic() || c.getNodes().size() != 2) continue;
            SeparationConstraintGraph sep = (SeparationConstraintGraph) c;
            double[] a = shift.get(c.getNodes().get(0)), b = shift.get(c.getNodes().get(1));
            if (!sep.isFixed() || a == null || b == null) continue;
            int axis = sep.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL ? 0 : 1;
            if (a[axis] != b[axis]) sep.setDistance(sep.getDistance() + b[axis] - a[axis]);
        }
        return true;
    }

    /**
     * Groups segments of one direction ({position, from, to, arc, operator port}) that run side by side (overlapping
     * in their extent) closer than {@link #NUDGING}. Every arc of a bundle needs a line of its own; the arcs of one
     * operator port share one (they merge there), and with -Dcblayout.afWidenCollapsed=off all segments on one line
     * do. Returns the bundles that are more than 0.5 px too narrow.
     */
    private static List<Bundle> narrowBundles(List<double[]> segs) {
        int n = segs.size();
        int[] group = new int[n];
        for (int i = 0; i < n; i++) group[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Math.abs(segs.get(i)[0] - segs.get(j)[0]);
                boolean beside = Math.min(segs.get(i)[2], segs.get(j)[2]) - Math.max(segs.get(i)[1], segs.get(j)[1]) > 1;
                boolean merged = segs.get(i)[3] == segs.get(j)[3] || (segs.get(i)[4] != 0 && segs.get(i)[4] == segs.get(j)[4]);
                // In a channel too narrow at one place, libavoid shrinks the gap of the whole bundle down to 0, so arcs
                // lying on one line are such a bundle too.
                if (!beside || (d < 0.01 && (!WIDEN_COLLAPSED || merged)) || d > NUDGING - 0.5) continue;
                int a = i, b = j;
                while (group[a] != a) a = group[a];
                while (group[b] != b) b = group[b];
                group[b] = a;
            }
        }
        Map<Integer, TreeMap<Long, List<double[]>>> lines = new TreeMap<>();   // bundle -> position -> its segments
        for (int i = 0; i < n; i++) {
            int r = i;
            while (group[r] != r) r = group[r];
            lines.computeIfAbsent(r, k -> new TreeMap<>()).computeIfAbsent(Math.round(segs.get(i)[0] * 100), k -> new ArrayList<>()).add(segs.get(i));
        }
        List<Bundle> cuts = new ArrayList<>();
        for (TreeMap<Long, List<double[]>> l : lines.values()) {
            double lo = l.firstKey() / 100.0, hi = l.lastKey() / 100.0;
            int tracks = 0;
            TreeSet<Long> arcs = new TreeSet<>();
            for (List<double[]> line : l.values()) {
                tracks += WIDEN_COLLAPSED ? deepestStack(line) : 1;
                for (double[] seg : line) arcs.add((long) seg[3]);
            }
            double missing = (tracks - 1) * NUDGING - (hi - lo);
            if (tracks > 1 && missing > 0.5) cuts.add(new Bundle((lo + hi) / 2, missing, arcs + ":" + Math.round(missing * 10)));
        }
        return cuts;
    }

    /**
     * The largest number of arcs that lie on top of each other at one place of a line; the arcs of one operator port
     * count once.
     */
    private static int deepestStack(List<double[]> line) {
        int deepest = 1;
        for (double[] a : line) {
            double at = a[1] + 1;   // a stack is deepest just behind the start of one of its segments
            Set<Double> arcs = new HashSet<>(), ports = new HashSet<>();
            int depth = 0;
            for (double[] b : line) {
                if (b[1] > at || b[2] < at) continue;
                if (!arcs.add(b[3]) || (b[4] != 0 && !ports.add(b[4]))) continue;
                depth++;
            }
            deepest = Math.max(deepest, depth);
        }
        return deepest;
    }

    // ------------------------------------------------------------------ solver calls

    /** CBLayoutAlgorithm.solveNodes with the AF node margin ({@link #NODE_PAD}) and {@code ideal} as ideal arc length. */
    private static void solveNodes(List<Node> nodes, Map<Node, double[]> boxes, List<Node[]> edges, Collection<Constraint> cs, boolean fd, int ideal) {
        double pad = CBLayoutAlgorithm.bboxConst;
        int dist = CBLayoutAlgorithm.dist;
        CBLayoutAlgorithm.bboxConst = NODE_PAD;
        CBLayoutAlgorithm.dist = ideal;
        try {
            CBLayoutAlgorithm.solveNodes(nodes, boxes, edges, null, cs, fd, RUN_PATIENCE);
        } finally {
            CBLayoutAlgorithm.bboxConst = pad;
            CBLayoutAlgorithm.dist = dist;
        }
    }

    /**
     * Guards the store for {@code phase}, then one force-directed solve over the placed units between two packings; a
     * root enters with its tree box until step 5.
     */
    private void solvePlaced(String phase) {
        host.enforceFeasibility(phase);
        packComponents();
        List<Node> nodes = new ArrayList<>(level.keySet());
        Map<Node, double[]> boxes = new HashMap<>();
        if (!expanded) for (Tree t : trees) if (level.containsKey(t.root)) boxes.put(t.root, new double[]{ t.width, t.height });
        List<Node[]> edges = new ArrayList<>();
        for (Node u : nodes) for (Node v : succ.get(u)) if (level.containsKey(v)) edges.add(new Node[]{ u, v });
        solveNodes(nodes, boxes, edges, ConstraintStore.constraints.getAllConstraints(), true, IDEAL_LENGTH);
        packComponents();
    }

    /**
     * True if a pin, area or target of the user fixes positions (sbgnUserTypes=all), so the drawing must not be
     * shifted; the conflict log then reports what was {@code skipped}.
     */
    private boolean positionsFixed(String skipped) {
        if (!CBLayoutAlgorithm.absoluteUserConstraintsOn(graph)) return false;
        ConflictLog.addDropped("SBGN", Collections.<ConstraintFeasibility.Dropped>emptyList(),
                Collections.singletonList(skipped + ": a pin, area or target of yours fixes positions."));
        return true;
    }

    /**
     * cola exerts no force between components, so they are shelved next to each other (largest first) after every
     * solve. Nothing constrains two components against each other, so a translation keeps every constraint satisfied.
     */
    private void packComponents() {
        if (components.size() < 2) return;
        if (positionsFixed("The components of the map were not packed")) return;
        List<List<Node>> placed = new ArrayList<>();
        List<double[]> boxes = new ArrayList<>();
        double widest = 0;
        for (int i = 0; i < components.size(); i++) {
            List<Node> nodes = new ArrayList<>();
            for (Node u : components.get(i)) {
                if (!level.containsKey(u)) continue;
                Tree t = treeOf.get(u);
                if (expanded && t != null) nodes.addAll(t.members);
                else nodes.add(u);
            }
            double[] b = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
            for (Node n : nodes) {
                b[0] = Math.min(b[0], x(n) - w(n) / 2);
                b[1] = Math.min(b[1], y(n) - h(n) / 2);
                b[2] = Math.max(b[2], x(n) + w(n) / 2);
                b[3] = Math.max(b[3], y(n) + h(n) / 2);
            }
            placed.add(nodes);
            boxes.add(b);
            if (!nodes.isEmpty()) widest = Math.max(widest, b[2] - b[0]);
        }
        double shelfWidth = Math.max(widest, 1000), left = 0, top = 0, rowHeight = 0;
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).isEmpty()) continue;
            double[] b = boxes.get(i);
            double bw = b[2] - b[0], bh = b[3] - b[1];
            if (left > 0 && left + bw > shelfWidth) {
                left = 0;
                top += rowHeight + COMPONENT_GAP;
                rowHeight = 0;
            }
            double dx = left - b[0], dy = top - b[1];
            for (Node n : placed.get(i)) AttributeHelper.setPosition(n, x(n) + dx, y(n) + dy);
            left += bw + COMPONENT_GAP;
            rowHeight = Math.max(rowHeight, bh);
        }
    }
}
