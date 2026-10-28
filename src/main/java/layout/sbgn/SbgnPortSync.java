package layout.sbgn;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Keeps SBGN-ED's port markers (SBGN.BendOut / SBGN.BendIn, which its SBGN-ML writers expect as the first / last bend)
 * in line with the drawing, so that a laid-out map can be exported with File > Save As "SBGN-ML": for a marked end the
 * writers drop that bend, and on an arc without bends they fail with an IndexOutOfBoundsException. For every marked end
 * without a docking, a stub bend just outside the node becomes the port, or a point {@link #PORT_GAP} px outside the
 * border is inserted on the first / last segment, which leaves the drawing unchanged.
 */
public final class SbgnPortSync {

    /** Distance between node border and port, as in SBGN-ED's own files. */
    static final double PORT_GAP = 10;

    /**
     * -Dcblayout.portSpot=gap|half (default gap): gap puts an inserted port {@link #PORT_GAP} outside the border
     * whenever its segment reaches that far, and never closer than {@link #clearance}; half puts it on the first half
     * of its segment, at least 1 px outside the border, which can lie inside the node's drawn outline.
     */
    static boolean HALF_SEGMENT = "half".equalsIgnoreCase(System.getProperty("cblayout.portSpot", "gap"));

    private SbgnPortSync() { }

    /** Updates the port markers of every edge of {@code graph}, inserting a port bend where needed. */
    public static void apply(Graph graph) {
        for (Edge e : graph.getEdges()) {
            boolean src = marked(e, "BendOut") && !docked(e, "source");
            boolean tgt = marked(e, "BendIn") && !docked(e, "target");
            if (!src && !tgt) continue;

            ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(e);
            int n = bends.size();
            Node s = e.getSource(), t = e.getTarget();
            boolean srcStub = src && n > 0 && isStub(s, bends.get(0));
            // one bend next to both nodes cannot be both ports: the writer would remove it twice
            boolean tgtStub = tgt && n > 0 && isStub(t, bends.get(n - 1)) && !(srcStub && n == 1);

            Vector2d out = null, in = null;
            if (src) out = srcStub ? bends.get(0) : portOnSegment(s, n > 0 ? bends.get(0) : center(t), n > 0);
            if (tgt) in = tgtStub ? bends.get(n - 1) : portOnSegment(t, n > 0 ? bends.get(n - 1) : center(s), n > 0);

            if ((src && !srcStub) || (tgt && !tgtStub)) {
                ArrayList<Vector2d> updated = new ArrayList<>(bends);
                if (src && !srcStub) updated.add(0, out);
                if (tgt && !tgtStub) updated.add(in);
                AttributeHelper.removeEdgeBends(e);
                AttributeHelper.addEdgeBends(e, updated);
            }
            if (out != null) setMarker(e, "BendOut", out);
            if (in != null) setMarker(e, "BendIn", in);
        }
    }

    private static boolean marked(Edge e, String marker) {
        return AttributeHelper.getAttributeValue(e, "SBGN", marker, null, "", false) != null;
    }

    private static boolean docked(Edge e, String end) {
        Object docking = AttributeHelper.getAttributeValue(e, "graphics.docking", end, "", "", false);
        return docking != null && docking.toString().length() > 0;
    }

    private static Vector2d center(Node n) {
        return AttributeHelper.getPositionVec2d(n);
    }

    /**
     * A bend just outside the node border (the end of a routing stub, or a port this class inserted earlier)
     * already is the port.
     */
    private static boolean isStub(Node node, Vector2d bend) {
        Vector2d c = center(node);
        double dist = Math.hypot(bend.x - c.x, bend.y - c.y);
        if (dist < 1e-9) return false;
        double border = borderDistance(node, (bend.x - c.x) / dist, (bend.y - c.y) / dist);
        return dist > border && dist <= border + PORT_GAP + 5;
    }

    /** Distance from the node centre to its border along the unit direction (ux, uy). */
    private static double borderDistance(Node node, double ux, double uy) {
        Vector2d size = AttributeHelper.getSize(node);
        double halfW = Math.max(1, size.x / 2), halfH = Math.max(1, size.y / 2);
        return Math.min(Math.abs(ux) < 1e-9 ? Double.MAX_VALUE : halfW / Math.abs(ux),
                Math.abs(uy) < 1e-9 ? Double.MAX_VALUE : halfH / Math.abs(uy));
    }

    /**
     * The point PORT_GAP px outside the node border on the segment from the node centre towards {@code next}.
     * {@code toBend}: the segment ends at a bend of the arc, not at the centre of the node at its other end.
     */
    private static Vector2d portOnSegment(Node node, Vector2d next, boolean toBend) {
        Vector2d c = center(node);
        double dist = Math.hypot(next.x - c.x, next.y - c.y);
        double ux = dist < 1e-9 ? 1 : (next.x - c.x) / dist, uy = dist < 1e-9 ? 0 : (next.y - c.y) / dist;
        double border = borderDistance(node, ux, uy);
        double r = portDistance(border, dist, toBend, HALF_SEGMENT ? -1 : clearance(node));
        return new Vector2d(c.x + ux * r, c.y + uy * r);
    }

    /**
     * How far from the node centre the inserted port lies, on a segment of length {@code dist} that crosses the
     * node border at {@code border}. {@code clearance}: px a bend has to lie outside the border for the view to
     * clip the arc there ({@link #clearance}); negative: the half-segment rule alone ({@link #HALF_SEGMENT}).
     */
    static double portDistance(double border, double dist, boolean toBend, double clearance) {
        // stay on the first half of the segment, so the ports of both ends of a straight arc keep their order,
        // but outside the border, so the next sync recognises the point as the port
        double r = Math.max(Math.min(border + PORT_GAP, dist / 2), border + 1);
        if (clearance < 0) return r;
        double clear = border + clearance;
        if (!toBend) return Math.max(r, clear);
        // up to a bend the segment belongs to this end alone: the port keeps its distance, in front of the bend
        // (a bend inside the outline or on it: the half-segment rule, no point of that segment is drawn right)
        return dist - 1 >= clear ? Math.min(border + PORT_GAP, dist - 1) : r;
    }

    /**
     * How far outside the border of {@code node} a bend has to lie for the view to clip the arc at the node: beyond
     * the drawn outline (the shapes add the frame, rounded up to an odd number of px, to the node's size) with 1 px
     * to spare. With the bend inside the outline, VANTED's PolyLineEdgeShape finds no crossing and draws the arc from
     * the node centre.
     */
    private static double clearance(Node node) {
        double frame = Math.floor(Math.abs(AttributeHelper.getFrameThickNess(node)));   // negative: the frame is hidden
        return (frame % 2 == 0 ? frame + 1 : frame) / 2 + 1;
    }

    private static void setMarker(Edge e, String marker, Vector2d p) {
        String value = String.format(Locale.ROOT, "%.3f;%.3f", p.x, p.y);
        if (!value.equals(AttributeHelper.getAttributeValue(e, "SBGN", marker, null, "", false)))
            AttributeHelper.setAttribute(e, "SBGN", marker, value);
    }
}
