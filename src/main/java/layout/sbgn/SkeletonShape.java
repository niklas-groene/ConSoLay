package layout.sbgn;

import constraints.ConstraintStore;
import constraints.HorizontalConstraint;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/**
 * Structural variant of step 2: derives the skeleton constraints from the structure of the skeleton instead of from a
 * force-directed pass. The flow edges outside the embedded cycles are decomposed into chains that follow the flow, and
 * each chain gets one direction (a free side at both of its ends), an alignment per link and a separation between
 * consecutive nodes, so both its axis and its order are fixed.
 */
final class SkeletonShape {

    /** Extra gap between two consecutive nodes of a chain, on top of their half extents. */
    static final double CHAIN_GAP = 30;

    private static final List<String> SIDES = Arrays.asList("right", "left", "down", "up");

    /** A path of flow edges as {@link #buildChains} decomposes them. */
    static final class Chain {
        final List<Node> nodes = new ArrayList<>();   // skeleton nodes, in walk order
        Edge firstEdge, lastEdge;                     // the edges at the two ends, for the flow role
        String direction;                             // where the chain points, seen from nodes.get(0)

        void reverse() {
            Collections.reverse(nodes);
            Edge t = firstEdge; firstEdge = lastEdge; lastEdge = t;
        }

        Node start() { return nodes.get(0); }
        Node end() { return nodes.get(nodes.size() - 1); }
        int length() { return nodes.size(); }
    }

    private final Map<Node, Node> lookup;                     // skeleton node -> original node
    private final Map<Node, Point2D.Double> inputPositions;   // original node -> coordinate of the input map
    private final Map<Node, List<Edge>> adjacency = new HashMap<>();
    private final Map<Node, TreeMap<String, Chain>> claimed = new HashMap<>();
    private final Map<Node, Integer> branchCounter = new HashMap<>();
    private final boolean fromInputMap;


    /** Placeholder occupant for a side that an embedded cycle rectangle already uses. */
    private static final Chain RING = new Chain();

    /**
     * @param fromInputMap take each chain's direction from the coordinates of the input map, which
     *        keeps the drawing close to what the user handed in. False builds the directions purely
     *        from the topology, which makes the result independent of the starting layout.
     */
    SkeletonShape(Graph skeleton, Map<Node, Node> lookup, Map<Node, Point2D.Double> inputPositions, boolean fromInputMap) {
        this.lookup = lookup;
        this.inputPositions = inputPositions;
        this.fromInputMap = fromInputMap;
    }

    /**
     * Builds the chains from {@code edges}, assigns every chain a direction and registers the
     * resulting constraints. {@code cycleNodes} are the skeleton nodes an embedded cycle already
     * fixed; a chain ends at such a node instead of running through it.
     */
    void run(List<Edge> edges, Set<Node> cycleNodes, Map<Node, Set<String>> sidesTakenByCycles) {
        // A cycle rectangle already runs along two sides of each of its nodes. Book them as occupied
        // so a chain attaches elsewhere instead of laying itself on top of the ring.
        if (sidesTakenByCycles != null) {
            for (Map.Entry<Node, Set<String>> e : sidesTakenByCycles.entrySet()) {
                for (String side : e.getValue()) claim(e.getKey(), side, RING);
            }
        }
        for (Edge e : edges) {
            adjacency.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        List<Chain> chains = buildChains(cycleNodes);

        // Long chains first: the spine of the pathway should get the direction it asks for, and the
        // short side branches arrange themselves around it.
        chains.sort(Comparator
                .comparingInt((Chain c) -> -c.length())
                .thenComparing(c -> key(c.start()))
                .thenComparing(c -> key(c.end()))
                .thenComparingLong(c -> c.start().getID()));

        if (fromInputMap) {
            for (Chain c : chains) assign(c);
        } else {
            // Grow outwards from the longest chain, so every chain but the first has a neighbour that
            // already knows its direction and can be arranged relative to it.
            List<Chain> remaining = new ArrayList<>(chains);
            List<Chain> placed = new ArrayList<>(chains.size());
            while (!remaining.isEmpty()) {
                Chain next = null;
                for (Chain c : remaining) {
                    if (anchored(c.start()) || anchored(c.end())) { next = c; break; }
                }
                if (next == null) next = remaining.get(0);   // a fresh component
                remaining.remove(next);
                if (!anchored(next.start()) && anchored(next.end())) next.reverse();
                assign(next);
                placed.add(next);
            }
            chains = placed;
        }
        for (Chain c : chains) emit(c);
    }

    // ---------------------------------------------------------------- chains

    /** Edges whose flow leaves this node, i.e. the node is their source. */
    private List<Edge> outEdges(Node n) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : adjacency.getOrDefault(n, Collections.emptyList())) {
            if (e.getSource() == n && e.getTarget() != n) out.add(e);
        }
        out.sort(Comparator.comparing((Edge e) -> key(e.getTarget())).thenComparingLong(e -> e.getTarget().getID()));
        return out;
    }

    private boolean hasIncoming(Node n) {
        for (Edge e : adjacency.getOrDefault(n, Collections.emptyList())) {
            if (e.getTarget() == n && e.getSource() != n) return true;
        }
        return false;
    }

    /**
     * Decomposes the flow edges into as few, as long paths as possible.
     *
     * <p>A path does not stop at every node of degree three. It stops where the flow stops: it arrives
     * along an incoming arc and continues along an outgoing one, so a species that is the product of
     * one process and the substrate of the next stays <em>inside</em> the chain and only the remaining
     * arcs branch off. That is the same rule SBGN applies to a process, applied to species as well.
     * Without it almost every species in a dense metabolic map ends a chain, and the decomposition
     * degenerates into one decision per edge.
     */
    private List<Chain> buildChains(Set<Node> cycleNodes) {
        List<Chain> chains = new ArrayList<>();
        Set<Edge> used = new HashSet<>();
        List<Node> nodes = new ArrayList<>(adjacency.keySet());
        // Ordered by content, so the same network gives the same chains whatever the file order was.
        nodes.sort(Comparator.comparing(this::key).thenComparingLong(Node::getID));

        // Sources of the pathway first: they produce the longest runs.
        for (Node n : nodes) {
            if (hasIncoming(n)) continue;
            growAll(n, used, cycleNodes, chains);
        }
        // Then everything the sources did not reach.
        for (Node n : nodes) growAll(n, used, cycleNodes, chains);
        // Anything left over has no usable flow direction; take it undirected.
        for (Node n : nodes) {
            for (Edge e : new ArrayList<>(adjacency.get(n))) {
                if (!used.contains(e)) chains.add(walk(n, e, used, cycleNodes));
            }
        }
        return chains;
    }

    private void growAll(Node n, Set<Edge> used, Set<Node> cycleNodes, List<Chain> chains) {
        for (Edge e : outEdges(n)) {
            if (!used.contains(e)) chains.add(walk(n, e, used, cycleNodes));
        }
    }

    /** Follows the flow from {@code from} along {@code via} for as long as it continues. */
    private Chain walk(Node from, Edge via, Set<Edge> used, Set<Node> cycleNodes) {
        Chain c = new Chain();
        c.nodes.add(from);
        c.firstEdge = via;
        Node prev = from;
        Edge edge = via;
        while (true) {
            used.add(edge);
            Node next = edge.getSource() == prev ? edge.getTarget() : edge.getSource();
            c.nodes.add(next);
            c.lastEdge = edge;
            // A cycle rectangle already owns its nodes; the chain ends there.
            if (cycleNodes.contains(next)) break;
            Edge onward = null;
            for (Edge e : outEdges(next)) {
                if (!used.contains(e)) { onward = e; break; }
            }
            if (onward == null) break;
            prev = next;
            edge = onward;
        }
        return c;
    }

    // ------------------------------------------------------------ directions

    private Point2D.Double position(Node skeletonNode) {
        Node original = lookup.get(skeletonNode);
        if (original != null) {
            Point2D.Double p = inputPositions == null ? null : inputPositions.get(original);
            if (p != null) return p;
            return new Point2D.Double(AttributeHelper.getPositionX(original), AttributeHelper.getPositionY(original));
        }
        return new Point2D.Double(AttributeHelper.getPositionX(skeletonNode), AttributeHelper.getPositionY(skeletonNode));
    }

    /** How well a side matches the direction the chain has in the input map. */
    private static double score(String side, double dx, double dy) {
        switch (side) {
            case "right": return dx;
            case "left":  return -dx;
            case "down":  return dy;
            case "up":    return -dy;
            default:      return 0;
        }
    }

    private static String opposite(String side) {
        switch (side) {
            case "left":  return "right";
            case "right": return "left";
            case "up":    return "down";
            default:      return "up";
        }
    }

    /**
     * A key that identifies a node by what it <em>is</em>, not by the order it happens to have in the
     * file: node ids follow the glyph order of the input, so ordering by them would make the result
     * depend on the input again.
     */
    private String key(Node skeletonNode) {
        Node original = lookup.get(skeletonNode);
        Node n = original != null ? original : skeletonNode;
        String label = null;
        try { label = AttributeHelper.getLabel(n, (String) null); } catch (Throwable ignore) { }
        String role = ConstraintSBGNLayout.getSBGNRole(n);
        return (label == null ? "" : label.trim()) + "\u0000" + (role == null ? "" : role)
                + "\u0000" + adjacency.getOrDefault(skeletonNode, Collections.emptyList()).size();
    }

    private boolean isProcess(Node skeletonNode) {
        Node original = lookup.get(skeletonNode);
        return CycleSelector.isProcessNode(original != null ? original : skeletonNode);
    }

    private Chain occupant(Node n, String side) {
        Map<String, Chain> m = claimed.get(n);
        return m == null ? null : m.get(side);
    }

    private void claim(Node n, String side, Chain c) {
        claimed.computeIfAbsent(n, k -> new TreeMap<>()).put(side, c);
    }

    /**
     * Records which sides this chain occupies. At the two ends that is one side each; at every node it
     * passes through it is two opposite ones, so a branch cannot claim a side the chain runs along.
     */
    private void claimAll(Chain c, String dir) {
        claim(c.start(), dir, c);
        claim(c.end(), opposite(dir), c);
        for (int i = 1; i + 1 < c.nodes.size(); i++) {
            claim(c.nodes.get(i), dir, c);
            claim(c.nodes.get(i), opposite(dir), c);
        }
    }

    private boolean anchored(Node n) {
        TreeMap<String, Chain> m = claimed.get(n);
        return m != null && !m.isEmpty();
    }

    /** The side a chain already occupies at this junction, smallest side name first for determinism. */
    private String anchorSide(Node n) {
        TreeMap<String, Chain> m = claimed.get(n);
        return m == null || m.isEmpty() ? null : m.firstKey();
    }

    /**
     * The order in which the four sides are tried for this chain. With {@code fromInputMap} the order
     * follows the direction the chain has in the input drawing. Otherwise it is built from the
     * topology alone: the first chain of a component runs downwards, and every later chain arranges
     * itself around the neighbour that is already placed — straight on when it continues the flow
     * through a process, perpendicular otherwise, alternating left and right per junction.
     */
    private List<String> preferenceOrder(Chain c) {
        if (fromInputMap) {
            Point2D.Double a = position(c.start()), b = position(c.end());
            double dx = b.x - a.x, dy = b.y - a.y;
            List<String> order = new ArrayList<>(SIDES);
            order.sort(Comparator.comparingDouble(s -> -score(s, dx, dy)));
            return order;
        }

        String anchor = anchorSide(c.start());
        if (anchor == null) {
            // Spine of a component: metabolic maps read from top to bottom.
            return Arrays.asList("down", "right", "up", "left");
        }
        Chain neighbour = occupant(c.start(), anchor);
        if (neighbour == RING) neighbour = null;
        List<String> order = new ArrayList<>(4);

        boolean straightThrough = isProcess(c.start()) && neighbour != null
                && isProduction(edgeAt(c, c.start())) != isProduction(edgeAt(neighbour, c.start()));
        if (straightThrough) order.add(opposite(anchor));

        int turn = branchCounter.merge(c.start(), 1, Integer::sum);
        boolean horizontalAnchor = "left".equals(anchor) || "right".equals(anchor);
        String first = horizontalAnchor ? (turn % 2 == 1 ? "down" : "up") : (turn % 2 == 1 ? "right" : "left");
        order.add(first);
        order.add(opposite(first));
        if (!straightThrough) order.add(opposite(anchor));
        order.add(anchor);
        return order;
    }

    private void assign(Chain c) {
        List<String> order = preferenceOrder(c);
        Node s = c.start(), e = c.end();
        for (String dir : order) {
            if (s == e) break;                       // degenerate, take the preferred one below
            if (occupant(s, dir) != null) continue;
            if (occupant(e, opposite(dir)) != null) continue;
            // A process draws its flow straight through: keep the side opposite a chain that already
            // arrived there free for the counterpart, unless this chain is that counterpart.
            if (isProcess(s) && occupant(s, opposite(dir)) == null && reservedFor(s, dir, c)) continue;
            c.direction = dir;
            claimAll(c, dir);
            return;
        }
        // Every side taken at one of the ends: share one.
        c.direction = order.get(0);
        claimAll(c, c.direction);
    }

    /**
     * True when the side opposite {@code dir} at the process {@code p} should stay free because a
     * chain of the other flow role still has to arrive there.
     */
    private boolean reservedFor(Node p, String dir, Chain c) {
        List<Edge> es = adjacency.get(p);
        if (es == null || es.size() < 3) return false;
        Edge own = edgeAt(c, p);
        boolean mine = isProduction(own);
        for (Edge e : es) {
            if (e == own) continue;
            if (chainOn(p, e) != null) continue;            // that chain already has its side
            if (isProduction(e) != mine) return true;       // the counterpart is still to come
        }
        return false;
    }

    private Chain chainOn(Node junction, Edge e) {
        Map<String, Chain> m = claimed.get(junction);
        if (m == null) return null;
        for (Chain c : m.values()) {
            if (c.firstEdge == e || c.lastEdge == e) return c;
        }
        return null;
    }

    private Edge edgeAt(Chain c, Node junction) {
        return c.start() == junction ? c.firstEdge : c.lastEdge;
    }

    private static boolean isProduction(Edge e) {
        if (e == null) return false;
        String role = ConstraintSBGNLayout.getSBGNRole(e);
        return role != null && (role.contains("PRODUCTION") || role.contains("REVERSIBLERHS"));
    }

    // ------------------------------------------------------------ constraints

    private void emit(Chain c) {
        boolean horizontal = "right".equals(c.direction) || "left".equals(c.direction);
        boolean forward = "right".equals(c.direction) || "down".equals(c.direction);

        // One alignment per link instead of one over the chain: the solver sees the same equivalence class, but
        // the feasibility guard then drops a single joint instead of the whole line.
        for (int i = 0; i + 1 < c.nodes.size(); i++) {
            Node a = lookup.get(c.nodes.get(i)), b = lookup.get(c.nodes.get(i + 1));
            if (a == null || b == null || a == b) continue;
            List<Node> link = new ArrayList<>(2);
            link.add(a);
            link.add(b);
            if (horizontal) {
                ConstraintStore.constraints.addConstraint(new HorizontalConstraint(link, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            } else {
                ConstraintStore.constraints.addConstraint(new VerticalConstraint(link, VerticalConstraint.VerticalConstraintType.MIDDLE));
            }
        }

        // The order along the chain.
        for (int i = 0; i + 1 < c.nodes.size(); i++) {
            Node a = lookup.get(c.nodes.get(i)), b = lookup.get(c.nodes.get(i + 1));
            if (a == null || b == null || a == b) continue;
            List<Node> pair = new ArrayList<>(2);
            pair.add(forward ? a : b);
            pair.add(forward ? b : a);
            double distance = horizontal
                    ? AttributeHelper.getWidth(a) / 2 + AttributeHelper.getWidth(b) / 2 + CHAIN_GAP
                    : AttributeHelper.getHeight(a) / 2 + AttributeHelper.getHeight(b) / 2 + CHAIN_GAP;
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(pair,
                    horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                               : SeparationConstraintGraph.SeparationConstraintType.VERTICAL,
                    false, distance));
        }
    }
}
