package layout.mental_map;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.Vector2d;

import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns the live graph into the next state of a sequence, e.g. the network of the next time point read from a graph
 * file. Nodes are matched by their label, edges by the labels of their ends in either direction: a live edge stands for
 * one state edge between the same labels, one with its own direction first, so parallel and antiparallel arcs count one
 * by one. Everything the state does not change stays as it is, above all the positions of the kept nodes.
 *
 * @author Niklas Gröne
 */
public final class NextState {

    /** Gap between the drawing and the block of new nodes, and between the cells of the block. */
    private static final double BLOCK_GAP = 80, CELL_GAP_X = 30, CELL_GAP_Y = 20;

    /** What {@link #apply} changed. */
    public static final class Change {
        public final int nodesAdded, nodesRemoved, edgesAdded, edgesRemoved;

        Change(int nodesAdded, int nodesRemoved, int edgesAdded, int edgesRemoved) {
            this.nodesAdded = nodesAdded;
            this.nodesRemoved = nodesRemoved;
            this.edgesAdded = edgesAdded;
            this.edgesRemoved = edgesRemoved;
        }

        @Override
        public String toString() {
            return "+" + nodesAdded + " / −" + nodesRemoved + " nodes, +" + edgesAdded + " / −" + edgesRemoved + " edges";
        }
    }

    private NextState() {
    }

    /**
     * Why {@code live} and {@code state} cannot be matched (a node without a label, or a label twice), or
     * {@code null} if they can.
     */
    public static String problem(Graph live, Graph state) {
        String p = labelProblem(live.getNodes());
        if (p != null) return "The graph: " + p;
        p = labelProblem(state.getNodes());
        return p == null ? null : "The file: " + p;
    }

    /**
     * Turns {@code live} into {@code state}: removes what the state no longer has and copies what it adds with its
     * attributes (shape, colours, size). A new node gets the uid {@code earlier} holds for its label, so the timeline
     * matches a returning node with its earlier self, else {@link #uidFor} (never the one the file may carry); it
     * stands in a block right of the drawing until a layout run places it. {@link #problem} must be {@code null}.
     */
    public static Change apply(Graph live, Graph state, Map<String, String> earlier) {
        Map<String, Node> liveByLabel = byLabel(live.getNodes());
        Set<String> liveUids = new HashSet<>();
        for (Node n : live.getNodes()) liveUids.add(Snapshot.uidOf(n));
        Map<String, Node> stateByLabel = byLabel(state.getNodes());

        // A deleted node takes its edges along; they count as removed edges.
        int edgesBefore = live.getNumberOfEdges();
        int nodesRemoved = 0;
        for (Map.Entry<String, Node> e : new ArrayList<>(liveByLabel.entrySet())) {
            if (stateByLabel.containsKey(e.getKey())) continue;
            live.deleteNode(e.getValue());
            liveByLabel.remove(e.getKey());
            nodesRemoved++;
        }

        Map<String, List<Edge>> stateEdges = new HashMap<>();
        for (Edge e : state.getEdges()) stateEdges.computeIfAbsent(key(e), k -> new ArrayList<>()).add(e);
        // The state edges no live edge stands for, by key; a live edge that stands for none goes.
        Map<String, List<Edge>> unmatched = new HashMap<>();
        for (Map.Entry<String, List<Edge>> e : stateEdges.entrySet()) {
            unmatched.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        List<Edge> liveEdges = new ArrayList<>(live.getEdges());
        Set<Edge> kept = new HashSet<>();
        for (boolean sameDirection : new boolean[]{true, false}) {
            for (Edge e : liveEdges) {
                List<Edge> free = unmatched.get(key(e));
                if (kept.contains(e) || free == null) continue;
                for (Edge s : free) {
                    if (!sameDirection || label(s.getSource()).equals(label(e.getSource()))) {
                        free.remove(s);
                        kept.add(e);
                        break;
                    }
                }
            }
        }
        for (Edge e : liveEdges) {
            if (!kept.contains(e)) live.deleteEdge(e);
        }
        int edgesRemoved = edgesBefore - live.getNumberOfEdges();

        Rectangle2D drawing = GraphBounds.of(live);
        List<Node> added = new ArrayList<>();
        for (Map.Entry<String, Node> e : stateByLabel.entrySet()) {   // TreeMap: the block is sorted by label
            if (liveByLabel.containsKey(e.getKey())) continue;
            Node n = live.addNodeCopy(e.getValue());
            String uid = earlier.get(e.getKey());
            if (uid == null || liveUids.contains(uid)) uid = uidFor(e.getKey());
            AttributeHelper.setAttribute(n, Snapshot.UID_PATH, "uid", uid);
            liveByLabel.put(e.getKey(), n);
            added.add(n);
        }
        placeBlock(added, drawing);

        int edgesAdded = 0;
        for (String key : stateEdges.keySet()) {
            for (Edge s : unmatched.get(key)) {
                Node source = liveByLabel.get(label(s.getSource()));
                Node target = liveByLabel.get(label(s.getTarget()));
                if (source == null || target == null) continue;
                live.addEdgeCopy(s, source, target);
                edgesAdded++;
            }
        }
        return new Change(added.size(), nodesRemoved, edgesAdded, edgesRemoved);
    }

    /** Label &rarr; uid of the nodes of {@code snapshots}, a later snapshot winning. */
    public static Map<String, String> uidsByLabel(List<Snapshot> snapshots) {
        Map<String, String> uids = new HashMap<>();
        for (Snapshot s : snapshots) {
            for (Node n : s.referenceGraph.getNodes()) {
                String l = label(n), uid = Snapshot.uidOf(n);
                if (l != null && !l.isEmpty() && !uid.isEmpty()) uids.put(l, uid);
            }
        }
        return uids;
    }

    /**
     * Uid of a node the state adds for the first time, derived from its label: the layout seeds new nodes with a jitter
     * taken from the uid, so the same sequence gives the same layout on every run.
     */
    static String uidFor(String label) {
        return UUID.nameUUIDFromBytes(("mentalmap.state:" + label).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The nodes in a block of about square shape right of {@code drawing} (or at the origin), row by row. */
    private static void placeBlock(List<Node> nodes, Rectangle2D drawing) {
        if (nodes.isEmpty()) return;
        double cellW = 0, cellH = 0;
        for (Node n : nodes) {
            Vector2d size = AttributeHelper.getSize(n);
            cellW = Math.max(cellW, size == null ? 0 : size.x);
            cellH = Math.max(cellH, size == null ? 0 : size.y);
        }
        cellW += CELL_GAP_X;
        cellH += CELL_GAP_Y;
        int columns = (int) Math.ceil(Math.sqrt(nodes.size()));
        double left = drawing == null ? 0 : drawing.getMaxX() + BLOCK_GAP;
        double top = drawing == null ? 0 : drawing.getMinY();
        for (int i = 0; i < nodes.size(); i++) {
            AttributeHelper.setPosition(nodes.get(i),
                    left + cellW / 2 + (i % columns) * cellW,
                    top + cellH / 2 + (i / columns) * cellH);
        }
    }

    private static String label(Node n) {
        String l = AttributeHelper.getLabel(n, null);
        return l == null ? null : l.trim();
    }

    /** Labels of an edge's ends in sorted order: an edge matches in either direction. */
    private static String key(Edge e) {
        String s = label(e.getSource()), t = label(e.getTarget());
        return s.compareTo(t) <= 0 ? s + " -- " + t : t + " -- " + s;
    }

    private static Map<String, Node> byLabel(Collection<Node> nodes) {
        Map<String, Node> result = new TreeMap<>();
        for (Node n : nodes) result.put(label(n), n);
        return result;
    }

    private static String labelProblem(Collection<Node> nodes) {
        Map<String, Integer> count = new HashMap<>();
        int unlabelled = 0;
        for (Node n : nodes) {
            String l = label(n);
            if (l == null || l.isEmpty()) unlabelled++;
            else count.merge(l, 1, Integer::sum);
        }
        if (unlabelled > 0) return unlabelled + (unlabelled == 1 ? " node has" : " nodes have")
                + " no label; nodes are matched by their label.";
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > 1) return "the label \"" + e.getKey() + "\" occurs " + e.getValue()
                    + " times; nodes are matched by their label.";
        }
        return null;
    }
}
