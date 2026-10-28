package layout.mental_map;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.CoordinateAttribute;

import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Locale;

/**
 * Extent and translation of a drawing in graph coordinates: the node rectangles (centre ± size / 2) and the edge
 * bends of the visible elements, as VANTED's "Move Network to Top-Left" (CenterLayouterAlgorithm) measures them. A
 * translation also moves SBGN-ED's port markers, the only other absolute coordinates of a drawing. VANTED cannot show
 * negative coordinates, so the mental-map timeline shows the live graph moved by a common offset, while the snapshots
 * keep the frame of the layout (see {@link Snapshot#restore(Graph, double, double)}).
 *
 * @author Niklas Gröne
 */
public final class GraphBounds {

    /**
     * Edge attributes (path "SBGN") in which SBGN-ED keeps the port of an arc end as an absolute "x;y"; its SBGN-ML
     * writers place the ports there (see {@link layout.sbgn.SbgnPortSync}).
     */
    private static final String[] PORT_MARKERS = {"BendOut", "BendIn"};

    private GraphBounds() {
    }

    /** Bounding box of the visible nodes and bends of {@code g}, or {@code null} for a graph without them. */
    public static Rectangle2D of(Graph g) {
        if (g == null) return null;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Node n : g.getNodes()) {
            if (AttributeHelper.isHiddenGraphElement(n)) continue;
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            Vector2d size = AttributeHelper.getSize(n);
            double hw = size == null ? 0 : size.x / 2, hh = size == null ? 0 : size.y / 2;
            minX = Math.min(minX, x - hw);
            minY = Math.min(minY, y - hh);
            maxX = Math.max(maxX, x + hw);
            maxY = Math.max(maxY, y + hh);
        }
        for (Edge e : g.getEdges()) {
            if (AttributeHelper.isHiddenGraphElement(e)) continue;
            for (CoordinateAttribute bend : AttributeHelper.getEdgeBendCoordinateAttributes(e)) {
                minX = Math.min(minX, bend.getX());
                minY = Math.min(minY, bend.getY());
                maxX = Math.max(maxX, bend.getX());
                maxY = Math.max(maxY, bend.getY());
            }
        }
        if (minX > maxX) return null;
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    /** Union of the boxes; {@code null} entries are skipped, and the result is {@code null} if all are. */
    public static Rectangle2D union(List<Rectangle2D> boxes) {
        Rectangle2D result = null;
        for (Rectangle2D box : boxes) result = union(result, box);
        return result;
    }

    /** Union of two boxes, either of which may be {@code null}. */
    public static Rectangle2D union(Rectangle2D a, Rectangle2D b) {
        if (a == null) return b == null ? null : (Rectangle2D) b.clone();
        if (b == null) return (Rectangle2D) a.clone();
        Rectangle2D result = new Rectangle2D.Double();
        Rectangle2D.union(a, b, result);
        return result;
    }

    /** Moves every node and edge of {@code g} by ({@code dx}, {@code dy}), hidden ones included (see {@link #translate(Edge, double, double)}). */
    public static void translate(Graph g, double dx, double dy) {
        if (g == null || (dx == 0 && dy == 0)) return;
        for (Node n : g.getNodes()) {
            AttributeHelper.setPosition(n, AttributeHelper.getPositionX(n) + dx, AttributeHelper.getPositionY(n) + dy);
        }
        for (Edge e : g.getEdges()) translate(e, dx, dy);
    }

    /** Moves the bends of {@code e} and its SBGN-ED port markers by ({@code dx}, {@code dy}). */
    public static void translate(Edge e, double dx, double dy) {
        if (dx == 0 && dy == 0) return;
        for (CoordinateAttribute bend : AttributeHelper.getEdgeBendCoordinateAttributes(e)) {
            bend.setCoordinate(bend.getX() + dx, bend.getY() + dy);
        }
        for (String marker : PORT_MARKERS) {
            Object value = AttributeHelper.getAttributeValue(e, "SBGN", marker, null, "", false);
            String[] xy = value == null ? new String[0] : value.toString().split(";");
            if (xy.length != 2) continue;
            try {
                double x = Double.parseDouble(xy[0].trim()) + dx, y = Double.parseDouble(xy[1].trim()) + dy;
                AttributeHelper.setAttribute(e, "SBGN", marker, String.format(Locale.ROOT, "%.3f;%.3f", x, y));
            } catch (NumberFormatException ignored) {
                // not a position: leave it
            }
        }
    }
}
