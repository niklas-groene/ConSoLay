package layout.mental_map;

import org.AttributeHelper;
import org.graffiti.event.ListenerManager;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A captured layout state: a restore point on the {@link SnapshotHistory} timeline and, when pinned, the reference
 * ("mental map") that the mental-map layouts preserve. It holds a deep copy of the graph; nodes are matched to the
 * live graph by a stable uid attribute, which {@link Graph#addNodeCopy(Node)} carries along when a node is deleted and
 * re-created.
 *
 * @author Niklas Gröne
 */
public class Snapshot {

    /** Attribute path under which the stable node UID is stored. */
    public static final String UID_PATH = "mentalmap";
    private static final String UID_NAME = "uid";

    /** Deep copy carrying the reference positions + topology + attributes at capture time. */
    final Graph referenceGraph;

    private final long timestamp;
    private final String label;
    private final int nodeCount;
    private ImageIcon thumbnail;
    private Map<String, Point2D> posByUid;
    private Rectangle2D bounds;

    private Snapshot(Graph referenceGraph, String label) {
        this.referenceGraph = referenceGraph;
        this.label = label;
        this.timestamp = System.currentTimeMillis();
        this.nodeCount = referenceGraph.getNumberOfNodes();
    }

    /** Capture the current layout of {@code live} as a new snapshot. */
    public static Snapshot capture(Graph live, String label) {
        for (Node n : live.getNodes()) {
            ensureUid(n);
        }
        Graph copy = new AdjListGraph(live, new ListenerManager());
        return new Snapshot(copy, label);
    }

    /**
     * Capture {@code live}, which shows the timeline moved by ({@code dx}, {@code dy}) (see {@link #restore(Graph,
     * double, double)}), in the frame of the timeline: every node and edge moved back. A node that still stands exactly
     * at {@code shown} + offset gets its position from {@code shown} (uid &rarr; position in the timeline frame) instead
     * of the subtraction, so a state the timeline wrote is captured bit for bit; {@code shown} may be {@code null}.
     */
    public static Snapshot capture(Graph live, String label, double dx, double dy, Map<String, Point2D> shown) {
        Snapshot s = capture(live, label);
        if (dx == 0 && dy == 0) return s;
        for (Node c : s.referenceGraph.getNodes()) {
            double x = AttributeHelper.getPositionX(c), y = AttributeHelper.getPositionY(c);
            Point2D p = shown == null ? null : shown.get(uidOf(c));
            if (p != null && x == p.getX() + dx && y == p.getY() + dy) {
                AttributeHelper.setPosition(c, p.getX(), p.getY());
            } else {
                AttributeHelper.setPosition(c, x - dx, y - dy);
            }
        }
        for (Edge e : s.referenceGraph.getEdges()) GraphBounds.translate(e, -dx, -dy);
        return s;
    }

    /** The node's stable UID, or "" if it has none yet. */
    public static String uidOf(Node n) {
        Object v = AttributeHelper.getAttributeValue(n, UID_PATH, UID_NAME, "", "");
        return v == null ? "" : v.toString();
    }

    /** Ensure that a live node can be matched against snapshots and comparison runs. */
    public static void ensureUid(Node n) {
        if (uidOf(n).isEmpty()) {
            AttributeHelper.setAttribute(n, UID_PATH, UID_NAME, UUID.randomUUID().toString());
        }
    }

    /** Reference positions keyed by node UID (cached; used for timeline interpolation). */
    public Map<String, Point2D> positionsByUid() {
        if (posByUid == null) {
            Map<String, Point2D> m = new HashMap<>();
            for (Node c : referenceGraph.getNodes()) {
                m.put(uidOf(c), new Point2D.Double(
                        AttributeHelper.getPositionX(c), AttributeHelper.getPositionY(c)));
            }
            posByUid = m;
        }
        return posByUid;
    }

    /** Extent of the captured drawing in graph coordinates (see {@link GraphBounds#of}); cached, {@code null} if empty. */
    public Rectangle2D bounds() {
        if (bounds == null) bounds = GraphBounds.of(referenceGraph);
        return bounds == null ? null : (Rectangle2D) bounds.clone();
    }

    /** Both snapshots hold the same nodes (by uid) at exactly the same positions, and the same edges. */
    public boolean sameState(Snapshot other) {
        return positionsByUid().equals(other.positionsByUid()) && edgesByUid().equals(other.edgesByUid());
    }

    /** Edges as "source uid > target uid" with their counts. */
    private Map<String, Integer> edgesByUid() {
        Map<String, Integer> m = new HashMap<>();
        for (Edge e : referenceGraph.getEdges()) {
            m.merge(uidOf(e.getSource()) + ">" + uidOf(e.getTarget()), 1, Integer::sum);
        }
        return m;
    }

    /** Map reference (copy) nodes to the live-graph nodes that share their UID. */
    public Map<Node, Node> mapToLive(Graph live) {
        Map<String, Node> liveByUid = byUid(live);
        Map<Node, Node> map = new HashMap<>();
        for (Node c : referenceGraph.getNodes()) {
            Node l = liveByUid.get(uidOf(c));
            if (l != null) map.put(c, l);
        }
        return map;
    }

    /** The nodes of {@code live} that have a uid, by uid. */
    private static Map<String, Node> byUid(Graph live) {
        Map<String, Node> result = new HashMap<>();
        for (Node l : live.getNodes()) {
            String uid = uidOf(l);
            if (!uid.isEmpty()) result.put(uid, l);
        }
        return result;
    }

    /**
     * Restores the layout state onto the live graph, matched by uid: re-adds the nodes and edges deleted since the
     * capture, removes those added since, and repositions the survivors, everything moved by ({@code dx}, {@code dy})
     * (the live graph may show the timeline moved out of negative coordinates, which VANTED cannot display).
     */
    public void restore(Graph live, double dx, double dy) {
        Map<String, Node> liveByUid = byUid(live);

        Set<String> snapUids = new HashSet<>();
        for (Node c : referenceGraph.getNodes()) {
            String uid = uidOf(c);
            snapUids.add(uid);
            Node l = liveByUid.get(uid);
            if (l == null) {
                l = live.addNodeCopy(c);
                liveByUid.put(uid, l);
                if (dx == 0 && dy == 0) continue;   // the copy carries the position
            }
            double x = AttributeHelper.getPositionX(c), y = AttributeHelper.getPositionY(c);
            AttributeHelper.setPosition(l, dx == 0 ? x : x + dx, dy == 0 ? y : y + dy);
        }

        List<Node> toRemove = new ArrayList<>();
        for (Node l : live.getNodes()) {
            if (!snapUids.contains(uidOf(l))) toRemove.add(l);
        }
        for (Node l : toRemove) {
            live.deleteNode(l);
        }

        // The edge set is rebuilt from the snapshot, so surviving edges also get their captured attributes back.
        List<Edge> liveEdges = new ArrayList<>(live.getEdges());
        for (Edge e : liveEdges) {
            live.deleteEdge(e);
        }
        for (Edge c : referenceGraph.getEdges()) {
            Node s = liveByUid.get(uidOf(c.getSource()));
            Node t = liveByUid.get(uidOf(c.getTarget()));
            if (s != null && t != null) {
                Edge copy = live.addEdgeCopy(c, s, t);
                if (copy != null) GraphBounds.translate(copy, dx, dy);
            }
        }
    }

    public String getLabel() {
        return label;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    /** Human-friendly relative age, e.g. "just now", "2 min ago", "1 h ago". */
    public String ago() {
        long secs = (System.currentTimeMillis() - timestamp) / 1000L;
        if (secs < 5) return "just now";
        if (secs < 60) return secs + " s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + " min ago";
        long hours = mins / 60;
        if (hours < 24) return hours + " h ago";
        return (hours / 24) + " d ago";
    }

    /** Lazily-rendered schematic thumbnail of the captured layout (nodes + edges). */
    public ImageIcon getThumbnail(int w, int h) {
        if (thumbnail == null || thumbnail.getIconWidth() != w || thumbnail.getIconHeight() != h) {
            thumbnail = new ImageIcon(render(w, h));
        }
        return thumbnail;
    }

    private BufferedImage render(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        Collection<Node> nodes = referenceGraph.getNodes();
        for (Node n : nodes) {
            double x = AttributeHelper.getPositionX(n);
            double y = AttributeHelper.getPositionY(n);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
        if (Double.isInfinite(minX)) {
            g2.dispose();
            return img;
        }

        double pad = 5;
        double spanX = Math.max(1e-6, maxX - minX);
        double spanY = Math.max(1e-6, maxY - minY);
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        double offX = pad + ((w - 2 * pad) - spanX * scale) / 2.0;
        double offY = pad + ((h - 2 * pad) - spanY * scale) / 2.0;

        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(0x85, 0xB7, 0xEB));
        for (Edge e : referenceGraph.getEdges()) {
            Node s = e.getSource(), t = e.getTarget();
            g2.drawLine(
                    px(s, minX, scale, offX), py(s, minY, scale, offY),
                    px(t, minX, scale, offX), py(t, minY, scale, offY));
        }

        g2.setColor(new Color(0x37, 0x8A, 0xDD));
        for (Node n : nodes) {
            int x = px(n, minX, scale, offX);
            int y = py(n, minY, scale, offY);
            g2.fillOval(x - 2, y - 2, 4, 4);
        }

        g2.dispose();
        return img;
    }

    private static int px(Node n, double minX, double scale, double offX) {
        return (int) Math.round(offX + (AttributeHelper.getPositionX(n) - minX) * scale);
    }

    private static int py(Node n, double minY, double scale, double offY) {
        return (int) Math.round(offY + (AttributeHelper.getPositionY(n) - minY) * scale);
    }
}
