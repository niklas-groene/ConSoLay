package layout.sbgn;

import constraints.ConstraintStore;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, directed skeleton plan of the PD layout (-Dcblayout.skeletonShape=topology): embedded rings become
 * indivisible blocks, other directed cycles are opened at feedback arcs (keeping material flow before regulation), and
 * forward arcs impose a vertical order by rank. Each rank keeps its own horizontal order; only a disjoint set of
 * material-flow paths is also aligned, and only where the row-order graph stays acyclic, so alignments cannot
 * contradict separations. Coordinates are an output of the topology, never an input to a decision.
 */
final class TopologySkeleton {
    private static final double FLOW_GAP = 50;
    private static final double LANE_GAP = 70;
    private static final double RING_GAP = 30;
    /**
     * -Dcblayout.topologyPlacement=layered|columns (default layered): every rank keeps its own order, or aligned lanes
     * are packed into global columns.
     */
    private static final boolean LAYERED_PLACEMENT = !"columns".equalsIgnoreCase(
            System.getProperty("cblayout.topologyPlacement", "layered"));
    /**
     * -Dcblayout.topologyLongEdges=true|false (default false): the layer order splits long arcs at every rank they
     * pass, or counts direct neighbours only.
     */
    private static final boolean ORDER_LONG_EDGES = "true".equalsIgnoreCase(
            System.getProperty("cblayout.topologyLongEdges", "false"));
    private static final int LAYER_ORDER_SWEEPS = 12;
    private static final int MAX_ORDER_DUMMY_POINTS = 100000;
    /**
     * -Dcblayout.topologyColumnOrder=crossings|barycenter (default crossings): with columns placement, the column
     * order is improved by estimated crossings, or kept in barycenter order.
     */
    private static final boolean ORDER_COLUMNS_BY_CROSSINGS = !"barycenter".equalsIgnoreCase(
            System.getProperty("cblayout.topologyColumnOrder", "crossings"));
    private static final int COLUMN_ORDER_SWEEPS = 6;
    private static final int MAX_COLUMN_ORDER_EVALUATIONS = 12000;
    private static final long COLUMN_ORDER_PAIR_BUDGET = 25000000;
    private static final double GEOMETRY_EPSILON = 1e-7;
    private static final Comparator<Block> BY_KEY = Comparator.comparing(b -> b.key);

    private final Graph graph;
    private final Graph skeleton;
    private final Map<Node, Node> lookup;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Arc> arcs = new ArrayList<>();
    private final Map<Node, Block> blockOf = new HashMap<>();
    private final Map<Node, Set<Node>> owned = new HashMap<>();
    private final Map<Node, String> productDirections = new HashMap<>();
    private final Map<Block, List<Arc>> outgoing = new HashMap<>();
    private final Map<Block, List<Arc>> incoming = new HashMap<>();
    private final Map<Integer, List<Block>> layers = new TreeMap<>();

    /** Counts of the last {@link #run}. */
    int ranks, feedbackCount, alignments, separations, regulatoryEdges;

    private static final class Block {
        final List<Node> nodes = new ArrayList<>();
        final Map<Node, Point2D.Double> offsets = new HashMap<>();
        String key;
        boolean ring;
        int rank, column;
        double order, width, height, x, y;
        Node anchor() { return nodes.get(0); }
    }

    private static final class Arc {
        final Node source, target;
        final Block from, to;
        final boolean material;
        boolean feedback, aligned;
        Arc(Node source, Node target, Block from, Block to, boolean material) {
            this.source = source; this.target = target;
            this.from = from; this.to = to; this.material = material;
        }
        int weight() { return material ? 12 : 1; }
    }

    private static final class Lane {
        final List<Block> blocks = new ArrayList<>();
        int first = Integer.MAX_VALUE, last = -1;
        double order, width;
        String key;
    }

    private static final class Column {
        final List<Lane> lanes = new ArrayList<>();
        double width, order, x;
        String key;
        boolean fits(Lane candidate) {
            for (Lane lane : lanes) {
                if (candidate.first <= lane.last && lane.first <= candidate.last) return false;
            }
            return true;
        }
    }

    /** A straight skeleton segment at fixed rank heights, including the offsets inside a ring. */
    private static final class ColumnEdge {
        final Node source, target;
        final Column sourceColumn, targetColumn;
        final double sourceOffsetX, targetOffsetX, sourceY, targetY;
        final int weight;
        double sourceX, targetX;

        ColumnEdge(Node source, Node target, Block from, Block to,
                   Column sourceColumn, Column targetColumn, int weight) {
            this.source = source; this.target = target;
            this.sourceColumn = sourceColumn; this.targetColumn = targetColumn;
            Point2D.Double a = from.offsets.get(source), b = to.offsets.get(target);
            sourceOffsetX = a.x; targetOffsetX = b.x;
            sourceY = from.y + a.y; targetY = to.y + b.y;
            this.weight = weight;
        }

        void place() {
            sourceX = sourceColumn.x + sourceOffsetX;
            targetX = targetColumn.x + targetOffsetX;
        }
    }

    /** Crossings first, then weighted horizontal span. */
    private static final class Cost {
        final long crossings;
        final double span;
        Cost(long crossings, double span) { this.crossings = crossings; this.span = span; }
        boolean betterThan(Cost other) {
            return crossings < other.crossings || (crossings == other.crossings
                    && span + GEOMETRY_EPSILON < other.span);
        }
    }

    /** Temporary ordering vertices; virtual points never become graph nodes or constraints. */
    private static final class OrderPoint {
        final Block block;
        final String key;
        final List<OrderSegment> incoming = new ArrayList<>(), outgoing = new ArrayList<>();
        int index;
        double order;
        OrderPoint(Block block, String key, double order) {
            this.block = block; this.key = key; this.order = order;
        }
    }

    private static final class OrderSegment {
        final OrderPoint from, to;
        final int weight;
        OrderSegment(OrderPoint from, OrderPoint to, int weight) {
            this.from = from; this.to = to; this.weight = weight;
            from.outgoing.add(this); to.incoming.add(this);
        }
    }

    TopologySkeleton(Graph graph, Graph skeleton, Map<Node, Node> lookup) {
        this.graph = graph;
        this.skeleton = skeleton;
        this.lookup = lookup;
    }

    /** Stable sort key: the SBGN glyph id, else label, role and node id. */
    static String key(Node n) {
        Object id = AttributeHelper.getAttributeValue(n, "sbgn", "glyphid", "", "", false);
        if (id != null && !id.toString().trim().isEmpty()) return "0\u0000" + id.toString().trim();
        String label = AttributeHelper.getLabel(n, "");
        return "1\u0000" + (label == null ? "" : label.trim()) + "\u0000"
                + role(n) + "\u0000" + n.getID();
    }

    private static String role(Node n) { return ConstraintSBGNLayout.getSBGNRole(n); }
    private static String role(Edge e) { return ConstraintSBGNLayout.getSBGNRole(e); }
    private static boolean process(Node n) {
        String r = role(n);
        return r.contains("PROCESS") || r.contains("ASSOCIATION") || r.contains("DISSOCIATION");
    }
    private static boolean material(Edge e) {
        String r = role(e);
        return r.contains("CONSUMPTION") || r.contains("PRODUCTION")
                || r.contains("REVERSIBLELHS") || r.contains("REVERSIBLERHS");
    }
    private static boolean regulatory(Edge e) {
        String r = role(e);
        return r.contains("MODULATION") || r.contains("CATALYSIS") || r.contains("STIMULATION")
                || r.contains("INHIBITION") || r.contains("LOGIC");
    }
    private static boolean productSide(Edge e) {
        String r = role(e);
        return r.contains("PRODUCTION") || r.contains("REVERSIBLERHS");
    }

    /** The node an arc flows from by its SBGN role, whatever the orientation of the graph edge. */
    static Node flowSource(Edge e) {
        Node s = e.getSource(), t = e.getTarget();
        boolean sp = process(s), tp = process(t);
        if (sp != tp) {
            String r = role(e);
            Node p = sp ? s : t, other = sp ? t : s;
            if (r.contains("PRODUCTION") || r.contains("REVERSIBLERHS")) return p;
            if (r.contains("CONSUMPTION") || r.contains("REVERSIBLELHS")) return other;
            if (regulatory(e)) return other;
        }
        return s;
    }

    /** Irreversible arcs must agree around a ring; reversible processes may run either way. */
    static boolean isDirectedRing(Cycle cycle) {
        if (cycle == null || cycle.getEdges().size() < 4) return false;
        List<Edge> edges = cycle.getEdges();
        Node first = cycle.getStartingPoint(), n = first;
        Set<Node> seen = new HashSet<>();
        int sense = 0;
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i), previous = edges.get((i + edges.size() - 1) % edges.size());
            if (!material(edge) || !seen.add(n) || (edge.getSource() != n && edge.getTarget() != n)) return false;
            if (process(n)) {
                // A ring must pass between the two process sides, not between two co-substrates.
                if (productSide(previous) == productSide(edge)) return false;
            }
            if (!role(edge).contains("REVERSIBLE")) {
                int direction = flowSource(edge) == n ? 1 : -1;
                if (sense != 0 && sense != direction) return false;
                sense = direction;
            }
            n = other(edge, n);
        }
        return n == first;
    }

    /** Whether a material or regulatory skeleton arc joins {@code a} and {@code b}, so this plan places them. */
    boolean owns(Node a, Node b) {
        return owned.containsKey(a) && owned.get(a).contains(b);
    }

    /** Side of {@code process} its products leave on: right, down, left or up. */
    String productDirection(Node process) { return productDirections.getOrDefault(process, "down"); }

    /** Sets the product/substrate and reversible orientation of every process from its product direction. */
    void applyProcessOrientations(List<ProcessComponent> processes) {
        for (ProcessComponent p : processes) {
            String direction = productDirection(p.processNode);
            int orientation = "left".equals(direction) || "right".equals(direction) ? 0 : 1;
            boolean reverse = "left".equals(direction) || "up".equals(direction);
            p.setProdSubOrientation(orientation);
            p.setProdSubSwitch(reverse);
            p.setRevOrientation(orientation);
            p.setRevSwitch(reverse);
        }
    }

    /** Plans blocks, ranks and orders, adds the separations and alignments to the store and places every node. */
    void run(List<List<Node>> rings) {
        makeBlocks(rings);
        makeArcs();
        openCycles();
        assignRanks();
        orderLayers();
        if (LAYERED_PLACEMENT) placeLayers();
        else {
            choosePaths();
            placeColumns();
        }
        emitConstraints();
        placeAllNodes();
    }

    private Node original(Node n) { return lookup.getOrDefault(n, n); }
    private static List<Node> sortedNodes(Iterable<Node> nodes) {
        List<Node> result = new ArrayList<>();
        for (Node n : nodes) result.add(n);
        result.sort(Comparator.comparing(TopologySkeleton::key).thenComparingLong(Node::getID));
        return result;
    }

    private void makeBlocks(List<List<Node>> rings) {
        List<List<Node>> orderedRings = new ArrayList<>(rings);
        orderedRings.sort(Comparator.comparing(r -> key(r.get(0))));
        for (List<Node> ring : orderedRings) {
            if (ring.size() < 4) continue;
            // The cycle selector supplies disjoint rings; an overlapping one is skipped so no node gets two blocks.
            boolean overlap = false;
            for (Node n : ring) if (blockOf.containsKey(n)) overlap = true;
            if (overlap) continue;
            Block b = new Block();
            b.ring = true;
            b.nodes.addAll(ring);
            b.key = key(sortedNodes(ring).get(0));
            rectangle(b);
            register(b);
        }
        for (Node sn : sortedNodes(skeleton.getNodes())) {
            Node n = original(sn);
            if (blockOf.containsKey(n)) continue;
            Block b = new Block();
            b.nodes.add(n); b.key = key(n);
            b.width = width(n); b.height = height(n);
            b.offsets.put(n, new Point2D.Double());
            register(b);
        }
        blocks.sort(BY_KEY);
    }

    private void register(Block b) {
        blocks.add(b);
        for (Node n : b.nodes) blockOf.put(n, b);
        outgoing.put(b, new ArrayList<>());
        incoming.put(b, new ArrayList<>());
    }

    /** Same R/D/L/U runs as embedCycle, with opposite sides scaled to close odd-sized rings. */
    private void rectangle(Block b) {
        int k = b.nodes.size(), base = k / 4, rest = k % 4;
        int nr = base + (rest > 0 ? 1 : 0), nl = base + (rest > 1 ? 1 : 0);
        int nd = base + (rest > 2 ? 1 : 0), nu = base;
        double maxW = 0, maxH = 0;
        for (Node n : b.nodes) { maxW = Math.max(maxW, width(n)); maxH = Math.max(maxH, height(n)); }
        double w = Math.max(nr, nl) * (maxW + RING_GAP);
        double h = Math.max(nd, nu) * (maxH + RING_GAP);
        b.width = w + maxW; b.height = h + maxH;
        for (int i = 0; i < k; i++) {
            double x, y;
            String direction;
            if (i < nr) { x = w * i / nr; y = 0; direction = "right"; }
            else if (i < nr + nd) { x = w; y = h * (i - nr) / nd; direction = "down"; }
            else if (i < nr + nd + nl) {
                x = w * (1.0 - (double) (i - nr - nd) / nl); y = h; direction = "left";
            } else { x = 0; y = h * (1.0 - (double) (i - nr - nd - nl) / nu); direction = "up"; }
            Node n = b.nodes.get(i);
            b.offsets.put(n, new Point2D.Double(x - w / 2, y - h / 2));
            if (process(n)) productDirections.put(n, direction);
        }
        // A reversible reaction may be traversed against its canonical RHS convention. Preserve
        // that convention for its leaves and ports by taking the actual RHS side of the ring.
        for (int i = 0; i < b.nodes.size(); i++) {
            Node n = b.nodes.get(i);
            if (!process(n)) continue;
            for (Edge edge : n.getEdges()) {
                if (!productSide(edge)) continue;
                Node neighbor = other(edge, n);
                if (neighbor != b.nodes.get((i + 1) % k) && neighbor != b.nodes.get((i + k - 1) % k)) continue;
                Point2D.Double q = b.offsets.get(neighbor), p = b.offsets.get(n);
                if (q == null) continue;
                double dx = q.x - p.x, dy = q.y - p.y;
                productDirections.put(n, Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left")
                        : (dy > 0 ? "down" : "up"));
                break;
            }
        }
    }

    private void makeArcs() {
        List<Edge> edges = new ArrayList<>(skeleton.getEdges());
        edges.sort(Comparator.comparing((Edge e) -> key(original(flowSource(e))))
                .thenComparing(e -> key(original(other(e, flowSource(e)))))
                .thenComparing((Edge e) -> role(e)));
        Set<String> seen = new HashSet<>();
        for (Edge e : edges) {
            if (!material(e) && !regulatory(e)) continue;
            Node sn = flowSource(e), source = original(sn), target = original(other(e, sn));
            owned.computeIfAbsent(source, n -> new HashSet<>()).add(target);
            owned.computeIfAbsent(target, n -> new HashSet<>()).add(source);
            Block from = blockOf.get(source), to = blockOf.get(target);
            if (from == null || to == null) continue;
            boolean flow = material(e);
            // Repeated copies of the same semantic arc must not change the topology's weights.
            String signature = source.getID() + ":" + target.getID() + ":" + flow;
            if (!seen.add(signature)) continue;
            if (!flow) regulatoryEdges++;
            if (from == to) {
                if (!from.ring) feedbackCount++;
                continue;
            }
            Arc arc = new Arc(source, target, from, to, flow);
            arcs.add(arc);
            outgoing.get(from).add(arc); incoming.get(to).add(arc);
        }
    }

    private static Node other(Edge edge, Node n) { return edge.getSource() == n ? edge.getTarget() : edge.getSource(); }

    private void openCycles() {
        List<List<Block>> components = new ArrayList<>();
        Map<Block, Integer> index = new HashMap<>(), low = new HashMap<>();
        List<Block> stack = new ArrayList<>();
        Set<Block> onStack = new HashSet<>();
        int[] next = {0};
        for (Block b : blocks) if (!index.containsKey(b)) strongConnect(b, index, low, stack, onStack, next, components);
        for (List<Block> component : components) {
            if (component.size() < 2) continue;
            List<Block> order = feedbackOrder(component);
            Map<Block, Integer> position = new HashMap<>();
            for (int j = 0; j < order.size(); j++) position.put(order.get(j), j);
            for (Block b : component) for (Arc arc : outgoing.get(b)) {
                if (position.containsKey(arc.to) && position.get(arc.from) >= position.get(arc.to)) {
                    arc.feedback = true; feedbackCount++;
                }
            }
        }
    }

    /** Tarjan's search from {@code root} with a call stack of its own, so a long chain of blocks cannot overflow. */
    private void strongConnect(Block root, Map<Block, Integer> index, Map<Block, Integer> low,
                               List<Block> stack, Set<Block> onStack, int[] next, List<List<Block>> components) {
        List<Block> path = new ArrayList<>();
        List<Integer> cursor = new ArrayList<>();   // per block on the path: its next outgoing arc
        index.put(root, next[0]); low.put(root, next[0]++);
        stack.add(root); onStack.add(root);
        path.add(root); cursor.add(0);
        while (!path.isEmpty()) {
            int top = path.size() - 1;
            Block b = path.get(top);
            List<Arc> out = outgoing.get(b);
            int i = cursor.get(top);
            if (i < out.size()) {
                cursor.set(top, i + 1);
                Block to = out.get(i).to;
                if (!index.containsKey(to)) {
                    index.put(to, next[0]); low.put(to, next[0]++);
                    stack.add(to); onStack.add(to);
                    path.add(to); cursor.add(0);
                } else if (onStack.contains(to)) low.put(b, Math.min(low.get(b), index.get(to)));
                continue;
            }
            path.remove(top);
            cursor.remove(top);
            if (low.get(b).equals(index.get(b))) {
                List<Block> component = new ArrayList<>();
                Block popped;
                do {
                    popped = stack.remove(stack.size() - 1); onStack.remove(popped); component.add(popped);
                } while (popped != b);
                component.sort(BY_KEY);
                components.add(component);
            }
            if (top > 0) {
                Block parent = path.get(top - 1);
                low.put(parent, Math.min(low.get(parent), low.get(b)));
            }
        }
    }

    /** Weighted source/sink peeling, then maximum outgoing-minus-incoming flow weight. */
    private List<Block> feedbackOrder(List<Block> component) {
        Set<Block> remaining = new HashSet<>(component);
        List<Block> left = new ArrayList<>(), right = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Block source = null, sink = null, best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Block b : component) {
                if (!remaining.contains(b)) continue;
                int in = 0, out = 0;
                for (Arc a : incoming.get(b)) if (remaining.contains(a.from)) in += a.weight();
                for (Arc a : outgoing.get(b)) if (remaining.contains(a.to)) out += a.weight();
                if (in == 0 && source == null) source = b;
                if (out == 0 && sink == null) sink = b;
                if (out - in > bestScore) { bestScore = out - in; best = b; }
            }
            if (source != null) { left.add(source); remaining.remove(source); }
            else if (sink != null) { right.add(sink); remaining.remove(sink); }
            else { left.add(best); remaining.remove(best); }
        }
        Collections.reverse(right); left.addAll(right);
        return left;
    }

    private void assignRanks() {
        Map<Block, Integer> degree = new HashMap<>();
        PriorityQueue<Block> ready = new PriorityQueue<>(BY_KEY);
        for (Block b : blocks) {
            int count = 0;
            for (Arc arc : incoming.get(b)) if (!arc.feedback) count++;
            degree.put(b, count);
            if (count == 0) ready.add(b);
        }
        while (!ready.isEmpty()) {
            Block b = ready.remove();
            layers.computeIfAbsent(b.rank, n -> new ArrayList<>()).add(b);
            ranks = Math.max(ranks, b.rank + 1);
            for (Arc arc : outgoing.get(b)) {
                if (arc.feedback) continue;
                arc.to.rank = Math.max(arc.to.rank, b.rank + 1);
                degree.put(arc.to, degree.get(arc.to) - 1);
                if (degree.get(arc.to) == 0) ready.add(arc.to);
            }
        }
        double top = 100;
        for (List<Block> layer : layers.values()) {
            layer.sort(BY_KEY);
            double height = 0;
            for (Block b : layer) height = Math.max(height, b.height);
            for (Block b : layer) b.y = top + height / 2;
            top += height + FLOW_GAP;
            updateOrder(layer);
        }
    }

    private static void updateOrder(List<Block> layer) {
        for (int i = 0; i < layer.size(); i++) layer.get(i).order = (i + 0.5) / layer.size();
    }

    private void orderLayers() {
        long dummyPoints = 0;
        for (Arc arc : arcs) if (!arc.feedback) dummyPoints += Math.max(0, arc.to.rank - arc.from.rank - 1);
        if (!ORDER_LONG_EDGES || dummyPoints > MAX_ORDER_DUMMY_POINTS) orderLayersDirect();
        else orderLayersExpanded();
    }

    /** Alternating barycenter sweeps over the blocks joined by forward arcs; the last arrangement is kept. */
    private void orderLayersDirect() {
        List<Integer> rankOrder = new ArrayList<>(layers.keySet());
        for (int sweep = 0; sweep < 8; sweep++) {
            boolean down = sweep % 2 == 0;
            if (sweep > 0) Collections.reverse(rankOrder);
            for (Integer rank : rankOrder) {
                List<Block> layer = layers.get(rank);
                Map<Block, Double> barycenter = new HashMap<>();
                for (Block b : layer) {
                    double sum = 0, weight = 0;
                    for (Arc arc : down ? incoming.get(b) : outgoing.get(b)) {
                        if (arc.feedback) continue;
                        Block neighbor = down ? arc.from : arc.to;
                        sum += neighbor.order * arc.weight(); weight += arc.weight();
                    }
                    barycenter.put(b, weight == 0 ? b.order : sum / weight);
                }
                layer.sort(Comparator.comparingDouble((Block b) -> barycenter.get(b)).thenComparing(b -> b.key));
                updateOrder(layer);
            }
        }
    }

    /**
     * Barycenter sweeps with every long forward arc split at the ranks it passes (for the ordering only); the
     * arrangement with the fewest adjacent-layer crossings, then the smallest weighted span, is kept.
     */
    private void orderLayersExpanded() {
        Map<Integer, List<OrderPoint>> rows = new TreeMap<>();
        Map<Integer, List<OrderSegment>> segments = new TreeMap<>();
        Map<Block, OrderPoint> pointOf = new HashMap<>();
        for (Map.Entry<Integer, List<Block>> entry : layers.entrySet()) {
            List<OrderPoint> row = new ArrayList<>();
            rows.put(entry.getKey(), row);
            for (Block block : entry.getValue()) {
                OrderPoint point = new OrderPoint(block, block.key, block.order);
                row.add(point); pointOf.put(block, point);
            }
        }
        for (int i = 0; i < arcs.size(); i++) {
            Arc arc = arcs.get(i);
            if (arc.feedback) continue;
            OrderPoint previous = pointOf.get(arc.from);
            for (int rank = arc.from.rank + 1; rank <= arc.to.rank; rank++) {
                OrderPoint next;
                if (rank == arc.to.rank) next = pointOf.get(arc.to);
                else {
                    double fraction = (double) (rank - arc.from.rank) / (arc.to.rank - arc.from.rank);
                    next = new OrderPoint(null, "2\u0000" + i + "\u0000" + rank,
                            arc.from.order + fraction * (arc.to.order - arc.from.order));
                    rows.computeIfAbsent(rank, k -> new ArrayList<>()).add(next);
                }
                segments.computeIfAbsent(rank - 1, k -> new ArrayList<>())
                        .add(new OrderSegment(previous, next, arc.weight()));
                previous = next;
            }
        }
        for (List<OrderPoint> row : rows.values()) {
            row.sort(Comparator.comparingDouble((OrderPoint p) -> p.order).thenComparing(p -> p.key));
            updatePointOrder(row);
        }
        Cost best = layerCost(rows, segments);
        Map<Integer, List<OrderPoint>> bestRows = copyPointRows(rows);
        List<Integer> rankOrder = new ArrayList<>(rows.keySet());
        for (int sweep = 0; sweep < LAYER_ORDER_SWEEPS; sweep++) {
            boolean down = sweep % 2 == 0;
            for (int i = 0; i < rankOrder.size(); i++) {
                int rank = rankOrder.get(down ? i : rankOrder.size() - 1 - i);
                List<OrderPoint> row = rows.get(rank);
                Map<OrderPoint, Double> barycenter = new HashMap<>();
                for (OrderPoint point : row) {
                    double sum = 0, weight = 0;
                    for (OrderSegment segment : down ? point.incoming : point.outgoing) {
                        OrderPoint neighbor = down ? segment.from : segment.to;
                        sum += neighbor.order * segment.weight; weight += segment.weight;
                    }
                    barycenter.put(point, weight == 0 ? point.order : sum / weight);
                }
                row.sort(Comparator.comparingDouble((OrderPoint p) -> barycenter.get(p)).thenComparing(p -> p.key));
                updatePointOrder(row);
            }
            Cost trial = layerCost(rows, segments);
            if (trial.betterThan(best)) { best = trial; bestRows = copyPointRows(rows); }
        }
        for (Map.Entry<Integer, List<OrderPoint>> entry : bestRows.entrySet()) {
            updatePointOrder(entry.getValue());
            List<Block> layer = layers.get(entry.getKey());
            if (layer == null) continue;
            layer.clear();
            for (OrderPoint point : entry.getValue()) if (point.block != null) {
                point.block.order = point.order; layer.add(point.block);
            }
        }
    }

    private static void updatePointOrder(List<OrderPoint> row) {
        for (int i = 0; i < row.size(); i++) {
            row.get(i).index = i; row.get(i).order = (i + 0.5) / row.size();
        }
    }

    private static Map<Integer, List<OrderPoint>> copyPointRows(Map<Integer, List<OrderPoint>> rows) {
        Map<Integer, List<OrderPoint>> copy = new TreeMap<>();
        for (Map.Entry<Integer, List<OrderPoint>> entry : rows.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** Count inversions with a Fenwick tree, batching shared sources and excluding shared targets. */
    private static Cost layerCost(Map<Integer, List<OrderPoint>> rows, Map<Integer, List<OrderSegment>> segments) {
        long crossings = 0;
        double span = 0;
        for (Map.Entry<Integer, List<OrderSegment>> entry : segments.entrySet()) {
            List<OrderSegment> edges = new ArrayList<>(entry.getValue());
            edges.sort(Comparator.comparingInt((OrderSegment s) -> s.from.index)
                    .thenComparingInt(s -> s.to.index));
            int[] tree = new int[rows.get(entry.getKey() + 1).size() + 1];
            int seen = 0;
            for (int first = 0; first < edges.size();) {
                int end = first + 1;
                while (end < edges.size() && edges.get(end).from == edges.get(first).from) end++;
                for (int i = first; i < end; i++) {
                    OrderSegment edge = edges.get(i);
                    int prefix = 0;
                    for (int index = edge.to.index + 1; index > 0; index -= index & -index) prefix += tree[index];
                    crossings += seen - prefix;
                    span += Math.abs(edge.from.order - edge.to.order) * edge.weight;
                }
                for (int i = first; i < end; i++) {
                    for (int index = edges.get(i).to.index + 1; index < tree.length; index += index & -index) tree[index]++;
                    seen++;
                }
                first = end;
            }
        }
        return new Cost(crossings, span);
    }

    private List<Arc> alignmentCandidates() {
        List<Arc> candidates = new ArrayList<>();
        for (Arc a : arcs) if (!a.feedback && a.material && !a.from.ring && !a.to.ring) candidates.add(a);
        candidates.sort(Comparator.comparingInt((Arc a) -> a.to.rank - a.from.rank)
                .thenComparingDouble(a -> Math.abs(a.from.order - a.to.order))
                .thenComparing(a -> a.from.key).thenComparing(a -> a.to.key));
        return candidates;
    }

    private void choosePaths() {
        Set<Block> hasOut = new HashSet<>(), hasIn = new HashSet<>();
        for (Arc a : alignmentCandidates()) {
            if (hasOut.contains(a.from) || hasIn.contains(a.to)) continue;
            a.aligned = true; hasOut.add(a.from); hasIn.add(a.to);
        }
    }

    /**
     * Layered placement: every rank keeps its barycenter order. The horizontal quotient has one vertex per aligned x
     * class and one positive separation edge per pair of adjacent blocks in a row; two classes merge only if neither
     * reaches the other (earlier alignments join paths across ranks), so the quotient stays acyclic.
     */
    private void placeLayers() {
        Map<Block, Block> parent = new HashMap<>();
        for (Block b : blocks) parent.put(b, b);
        for (List<Block> layer : layers.values()) {
            // emitConstraints sorts by column; here that field denotes the local row position.
            for (int i = 0; i < layer.size(); i++) layer.get(i).column = i;
        }
        Map<Block, Map<Block, Double>> horizontal = horizontalQuotient(parent);
        Set<Block> hasOut = new HashSet<>(), hasIn = new HashSet<>();
        for (Arc arc : alignmentCandidates()) {
            if (hasOut.contains(arc.from) || hasIn.contains(arc.to)) continue;
            Block a = xClass(parent, arc.from), b = xClass(parent, arc.to);
            if (a == b || reaches(horizontal, a, b) || reaches(horizontal, b, a)) continue;
            // Stable representatives also make topological queue ties independent of hash order.
            if (a.key.compareTo(b.key) < 0) parent.put(b, a);
            else parent.put(a, b);
            arc.aligned = true;
            hasOut.add(arc.from); hasIn.add(arc.to);
            horizontal = horizontalQuotient(parent);
        }

        Map<Block, Integer> indegree = new HashMap<>();
        Map<Block, Double> position = new HashMap<>();
        for (Block b : horizontal.keySet()) {
            indegree.put(b, 0); position.put(b, 100.0);
        }
        for (Block b : blocks) {
            Block group = xClass(parent, b);
            position.put(group, Math.max(position.get(group), 100 + b.width / 2));
        }
        for (Map<Block, Double> edges : horizontal.values()) {
            for (Block to : edges.keySet()) indegree.put(to, indegree.get(to) + 1);
        }
        PriorityQueue<Block> ready = new PriorityQueue<>(BY_KEY);
        for (Block b : horizontal.keySet()) if (indegree.get(b) == 0) ready.add(b);
        int visited = 0;
        while (!ready.isEmpty()) {
            Block from = ready.remove(); visited++;
            for (Map.Entry<Block, Double> edge : horizontal.get(from).entrySet()) {
                Block to = edge.getKey();
                position.put(to, Math.max(position.get(to), position.get(from) + edge.getValue()));
                indegree.put(to, indegree.get(to) - 1);
                if (indegree.get(to) == 0) ready.add(to);
            }
        }
        if (visited != horizontal.size()) throw new IllegalStateException("Cyclic topology row order");
        for (Block b : blocks) b.x = position.get(xClass(parent, b));
    }

    private static Block xClass(Map<Block, Block> parent, Block block) {
        Block root = block;
        while (parent.get(root) != root) root = parent.get(root);
        while (block != root) {
            Block next = parent.get(block);
            parent.put(block, root); block = next;
        }
        return root;
    }

    private Map<Block, Map<Block, Double>> horizontalQuotient(Map<Block, Block> parent) {
        Map<Block, Map<Block, Double>> result = new LinkedHashMap<>();
        for (Block b : blocks) result.computeIfAbsent(xClass(parent, b), k -> new LinkedHashMap<>());
        for (List<Block> layer : layers.values()) {
            for (int i = 1; i < layer.size(); i++) {
                Block left = layer.get(i - 1), right = layer.get(i);
                Block from = xClass(parent, left), to = xClass(parent, right);
                if (from == to) throw new IllegalStateException("Aligned blocks share a topology rank");
                // Block centers include the full ring envelope; emitConstraints moves this separation onto the ring
                // anchors with their x offsets.
                double gap = left.width / 2 + right.width / 2 + LANE_GAP;
                result.get(from).merge(to, gap, Math::max);
            }
        }
        return result;
    }

    private static boolean reaches(Map<Block, Map<Block, Double>> graph, Block source, Block target) {
        Set<Block> visited = new HashSet<>();
        List<Block> pending = new ArrayList<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            Block next = pending.remove(pending.size() - 1);
            if (next == target) return true;
            if (visited.add(next)) pending.addAll(graph.get(next).keySet());
        }
        return false;
    }

    private void placeColumns() {
        Map<Block, Block> next = new HashMap<>();
        Set<Block> hasPrevious = new HashSet<>();
        for (Arc a : arcs) if (a.aligned) { next.put(a.from, a.to); hasPrevious.add(a.to); }
        List<Lane> lanes = new ArrayList<>();
        for (Block start : blocks) {
            if (hasPrevious.contains(start)) continue;
            Lane lane = new Lane(); lane.key = start.key;
            for (Block b = start; b != null; b = next.get(b)) {
                lane.blocks.add(b);
                lane.first = Math.min(lane.first, b.rank); lane.last = Math.max(lane.last, b.rank);
                lane.order += b.order; lane.width = Math.max(lane.width, b.width);
            }
            lane.order /= lane.blocks.size();
            lanes.add(lane);
        }
        lanes.sort(Comparator.comparingInt((Lane l) -> l.first).thenComparingDouble(l -> l.order).thenComparing(l -> l.key));
        List<Column> columns = new ArrayList<>();
        for (Lane lane : lanes) {
            Column best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Column column : columns) {
                double distance = Math.abs(lane.order - column.order / column.lanes.size());
                if (column.fits(lane) && distance < bestDistance) { best = column; bestDistance = distance; }
            }
            if (best == null) { best = new Column(); best.key = lane.key; columns.add(best); }
            best.lanes.add(lane); best.order += lane.order; best.width = Math.max(best.width, lane.width);
        }
        columns.sort(Comparator.comparingDouble((Column c) -> c.order / c.lanes.size()).thenComparing(c -> c.key));
        if (ORDER_COLUMNS_BY_CROSSINGS) improveColumnOrder(columns);
        placeColumnCenters(columns);
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            for (Lane lane : column.lanes) for (Block b : lane.blocks) { b.column = i; b.x = column.x; }
        }
    }

    private static void placeColumnCenters(List<Column> columns) {
        double left = 100;
        for (Column column : columns) {
            column.x = left + column.width / 2;
            left += column.width + LANE_GAP;
        }
    }

    /**
     * Columns placement: moves whole columns (which keeps every path alignment and one order per rank) to the position
     * with fewer crossing or touching pairs of straight skeleton segments without a common node, then a smaller
     * weighted horizontal span. All skeleton edges count; the geometry uses only rank heights, sizes and ring offsets.
     */
    private void improveColumnOrder(List<Column> columns) {
        if (columns.size() < 2) return;
        Map<Block, Column> columnOf = new HashMap<>();
        for (Column column : columns) for (Lane lane : column.lanes) for (Block b : lane.blocks) {
            columnOf.put(b, column);
        }
        List<Edge> skeletonEdges = new ArrayList<>(skeleton.getEdges());
        skeletonEdges.sort(Comparator.comparing((Edge e) -> key(original(e.getSource())))
                .thenComparing(e -> key(original(e.getTarget()))).thenComparing((Edge e) -> role(e)));
        List<ColumnEdge> edges = new ArrayList<>();
        for (Edge edge : skeletonEdges) {
            Node source = original(edge.getSource()), target = original(edge.getTarget());
            Block from = blockOf.get(source), to = blockOf.get(target);
            if (source == target || from == null || to == null) continue;
            edges.add(new ColumnEdge(source, target, from, to, columnOf.get(from), columnOf.get(to),
                    material(edge) ? 12 : 1));
        }
        // Y intervals never change during column permutation, so exclude impossible pairs once.
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            ColumnEdge a = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                ColumnEdge b = edges.get(j);
                if (a.source == b.source || a.source == b.target || a.target == b.source || a.target == b.target) continue;
                if (Math.max(a.sourceY, a.targetY) + GEOMETRY_EPSILON < Math.min(b.sourceY, b.targetY)
                        || Math.max(b.sourceY, b.targetY) + GEOMETRY_EPSILON < Math.min(a.sourceY, a.targetY)) continue;
                pairs.add(new int[]{i, j});
            }
        }
        Cost current = columnCost(columns, edges, pairs, Integer.MAX_VALUE);
        // Work is bounded by evaluations and segment-pair tests, never by time, so the result is deterministic.
        int limit = (int) Math.max(1, Math.min(MAX_COLUMN_ORDER_EVALUATIONS,
                COLUMN_ORDER_PAIR_BUDGET / Math.max(1L, pairs.size() + (long) edges.size())));
        int evaluations = 0;
        List<Column> visit = new ArrayList<>(columns);
        visit.sort(Comparator.comparing(c -> c.key));
        for (int sweep = 0; sweep < COLUMN_ORDER_SWEEPS && evaluations < limit; sweep++) {
            boolean changed = false;
            for (int k = 0; k < visit.size() && evaluations < limit; k++) {
                Column moving = visit.get(sweep % 2 == 0 ? k : visit.size() - 1 - k);
                int from = columns.indexOf(moving), bestPosition = from;
                Cost best = current;
                for (int to = 0; to < columns.size() && evaluations < limit; to++) {
                    if (to == from) continue;
                    columns.remove(from); columns.add(to, moving);
                    Cost trial = columnCost(columns, edges, pairs, best.crossings);
                    evaluations++;
                    columns.remove(to); columns.add(from, moving);
                    if (trial.betterThan(best)) { best = trial; bestPosition = to; }
                }
                if (bestPosition != from) {
                    columns.remove(from); columns.add(bestPosition, moving);
                    current = best;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private static Cost columnCost(List<Column> columns, List<ColumnEdge> edges, List<int[]> pairs,
                                   long bestCrossings) {
        placeColumnCenters(columns);
        double span = 0;
        for (ColumnEdge edge : edges) {
            edge.place();
            span += Math.abs(edge.targetX - edge.sourceX) * edge.weight;
        }
        int crossings = 0;
        for (int[] pair : pairs) {
            if (segmentsMeet(edges.get(pair[0]), edges.get(pair[1])) && ++crossings > bestCrossings) {
                // A candidate already worse in the primary objective cannot become an improvement.
                return new Cost(crossings, Double.POSITIVE_INFINITY);
            }
        }
        return new Cost(crossings, span);
    }

    /** Touches and collinear overlaps also obstruct a drawing when the edges have no common node. */
    private static boolean segmentsMeet(ColumnEdge a, ColumnEdge b) {
        if (Math.max(a.sourceX, a.targetX) + GEOMETRY_EPSILON < Math.min(b.sourceX, b.targetX)
                || Math.max(b.sourceX, b.targetX) + GEOMETRY_EPSILON < Math.min(a.sourceX, a.targetX)) return false;
        double o1 = cross(a.sourceX, a.sourceY, a.targetX, a.targetY, b.sourceX, b.sourceY);
        double o2 = cross(a.sourceX, a.sourceY, a.targetX, a.targetY, b.targetX, b.targetY);
        double o3 = cross(b.sourceX, b.sourceY, b.targetX, b.targetY, a.sourceX, a.sourceY);
        double o4 = cross(b.sourceX, b.sourceY, b.targetX, b.targetY, a.targetX, a.targetY);
        if (((o1 > GEOMETRY_EPSILON && o2 < -GEOMETRY_EPSILON) || (o1 < -GEOMETRY_EPSILON && o2 > GEOMETRY_EPSILON))
                && ((o3 > GEOMETRY_EPSILON && o4 < -GEOMETRY_EPSILON) || (o3 < -GEOMETRY_EPSILON && o4 > GEOMETRY_EPSILON))) return true;
        return (Math.abs(o1) <= GEOMETRY_EPSILON && onSegment(b.sourceX, b.sourceY, a))
                || (Math.abs(o2) <= GEOMETRY_EPSILON && onSegment(b.targetX, b.targetY, a))
                || (Math.abs(o3) <= GEOMETRY_EPSILON && onSegment(a.sourceX, a.sourceY, b))
                || (Math.abs(o4) <= GEOMETRY_EPSILON && onSegment(a.targetX, a.targetY, b));
    }

    private static double cross(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean onSegment(double x, double y, ColumnEdge edge) {
        return x >= Math.min(edge.sourceX, edge.targetX) - GEOMETRY_EPSILON
                && x <= Math.max(edge.sourceX, edge.targetX) + GEOMETRY_EPSILON
                && y >= Math.min(edge.sourceY, edge.targetY) - GEOMETRY_EPSILON
                && y <= Math.max(edge.sourceY, edge.targetY) + GEOMETRY_EPSILON;
    }

    private void emitConstraints() {
        for (Arc arc : arcs) {
            if (arc.feedback) continue;
            separate(arc.source, arc.target, false, height(arc.source) / 2 + height(arc.target) / 2 + FLOW_GAP);
            if (arc.aligned) {
                ConstraintStore.constraints.addConstraint(new VerticalConstraint(Arrays.asList(arc.source, arc.target),
                        VerticalConstraint.VerticalConstraintType.MIDDLE));
                alignments++;
            }
        }
        for (List<Block> layer : layers.values()) {
            layer.sort(Comparator.comparingInt((Block b) -> b.column).thenComparing(b -> b.key));
            for (int i = 1; i < layer.size(); i++) {
                Block left = layer.get(i - 1), right = layer.get(i);
                double distance = left.width / 2 - left.offsets.get(left.anchor()).x
                        + right.width / 2 + right.offsets.get(right.anchor()).x + LANE_GAP;
                separate(left.anchor(), right.anchor(), true, distance);
            }
        }
    }

    private void separate(Node a, Node b, boolean horizontal, double distance) {
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(Arrays.asList(a, b), horizontal
                ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                : SeparationConstraintGraph.SeparationConstraintType.VERTICAL, false, distance));
        separations++;
    }

    private void placeAllNodes() {
        List<Node> nodes = sortedNodes(graph.getNodes());
        Set<Node> placed = new HashSet<>();
        double right = 100;
        for (Block b : blocks) {
            right = Math.max(right, b.x + b.width / 2 + LANE_GAP);
            for (Node n : b.nodes) {
                Point2D.Double offset = b.offsets.get(n);
                AttributeHelper.setPosition(n, b.x + offset.x, b.y + offset.y); placed.add(n);
            }
        }
        // Isolated processes are anchors too. They must be initialized before their leaf species.
        int isolated = 0;
        for (Node n : nodes) if (!placed.contains(n) && process(n)) {
            AttributeHelper.setPosition(n, right + (isolated % 4) * 220, 160 + (isolated / 4) * 220);
            isolated++; placed.add(n);
        }
        Map<Node, Map<String, List<Node>>> leaves = new LinkedHashMap<>();
        List<Node> residual = new ArrayList<>();
        for (Node n : nodes) {
            if (placed.contains(n)) continue;
            Node owner = null;
            for (Node neighbor : sortedNodes(n.getNeighbors())) {
                if (!placed.contains(neighbor)) continue;
                if (owner == null || (!process(owner) && process(neighbor))) owner = neighbor;
            }
            if (owner == null) { residual.add(n); continue; }
            String side = "left";
            List<Edge> edges = new ArrayList<>(n.getEdges());
            edges.sort(Comparator.comparing(TopologySkeleton::role));
            for (Edge edge : edges) {
                if (other(edge, n) != owner || !material(edge)) continue;
                side = flowSource(edge) == owner ? productDirection(owner) : opposite(productDirection(owner));
                break;
            }
            leaves.computeIfAbsent(owner, k -> new TreeMap<>()).computeIfAbsent(side, k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Node, Map<String, List<Node>>> entry : leaves.entrySet()) {
            Node owner = entry.getKey();
            for (Map.Entry<String, List<Node>> group : entry.getValue().entrySet()) {
                String side = group.getKey(); List<Node> list = group.getValue();
                boolean vertical = "up".equals(side) || "down".equals(side);
                double breadth = 0;
                for (Node n : list) breadth += (vertical ? width(n) : height(n)) + RING_GAP;
                double offset = -breadth / 2;
                for (Node n : list) {
                    double across = vertical ? width(n) : height(n);
                    offset += across / 2 + RING_GAP / 2;
                    double along = (vertical ? height(owner) + height(n) : width(owner) + width(n)) / 2 + FLOW_GAP;
                    double dx = vertical ? offset : ("left".equals(side) ? -along : along);
                    double dy = vertical ? ("up".equals(side) ? -along : along) : offset;
                    AttributeHelper.setPosition(n, AttributeHelper.getPositionX(owner) + dx, AttributeHelper.getPositionY(owner) + dy);
                    offset += across / 2 + RING_GAP / 2;
                }
            }
        }
        // Unconnected decorations and components get a stable grid, never their input coordinates.
        for (int i = 0; i < residual.size(); i++) {
            AttributeHelper.setPosition(residual.get(i), right + (i % 6) * 160,
                    400 + ((isolated + 3) / 4) * 220 + (i / 6) * 120);
        }
        // Later stages read positions from the skeleton copies as well.
        for (Node sn : skeleton.getNodes()) {
            Node n = original(sn);
            AttributeHelper.setPosition(sn, AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n));
        }
    }

    private static double width(Node n) { return Math.max(1, AttributeHelper.getWidth(n)); }
    private static double height(Node n) { return Math.max(1, AttributeHelper.getHeight(n)); }
    static String opposite(String direction) {
        if ("left".equals(direction)) return "right";
        if ("right".equals(direction)) return "left";
        if ("up".equals(direction)) return "down";
        return "up";
    }
}
