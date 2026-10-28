package headless;
import de.ipk_gatersleben.ag_nw.graffiti.AttributeConstants;
import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.attributes.LinkedHashMapAttribute;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.CoordinateAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;

import java.util.Collection;
import java.util.Collections;

/** Moves a whole drawing (node centres and edge bends) without changing its shape. */
public class HeadlessCenterLayout {

    /** Shifts every node centre and edge bend by (dx, dy). */
    public static void translate(Graph graph, double dx, double dy) {
        if (dx == 0 && dy == 0) return;
        shift(graph, dx, dy);
    }

    /**
     * Moves the drawing so that the top-left corner of its visible node boxes and bends lies at (offX, offY).
     *
     * @return the shift applied, {dx, dy}; {0, 0} for a drawing without visible elements
     */
    public static double[] moveGraphTopLeft(Graph graph, double offX, double offY) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;

        for (Node n : graph.getNodes()) {
            if (AttributeHelper.isHiddenGraphElement(n)) continue;

            CoordinateAttribute co = (CoordinateAttribute) n.getAttribute(GraphicAttributeConstants.COORD_PATH);
            Vector2d size = AttributeHelper.getSize(n);
            double topLeftX = co.getX() - size.x / 2.0;
            double topLeftY = co.getY() - size.y / 2.0;

            if (topLeftX < minX) minX = topLeftX;
            if (topLeftY < minY) minY = topLeftY;
        }

        for (Edge e : graph.getEdges()) {
            if (AttributeHelper.isHiddenGraphElement(e)) continue;
            for (Object b : bendValues(e)) {
                CoordinateAttribute bend = (CoordinateAttribute) b;
                if (bend.getX() < minX) minX = bend.getX();
                if (bend.getY() < minY) minY = bend.getY();
            }
        }

        if (!Double.isFinite(minX) || !Double.isFinite(minY)) return new double[] { 0, 0 };

        double dx = offX - minX, dy = offY - minY;
        shift(graph, dx, dy);
        return new double[] { dx, dy };
    }

    private static void shift(Graph graph, double dx, double dy) {
        for (Node n : graph.getNodes()) {
            CoordinateAttribute co = (CoordinateAttribute) n.getAttribute(GraphicAttributeConstants.COORD_PATH);
            co.setX(co.getX() + dx);
            co.setY(co.getY() + dy);
        }
        for (Edge e : graph.getEdges()) {
            for (Object b : bendValues(e)) {
                CoordinateAttribute bend = (CoordinateAttribute) b;
                bend.setX(bend.getX() + dx);
                bend.setY(bend.getY() + dy);
            }
        }
    }

    /** The values of an edge's bends attribute (normally CoordinateAttributes); empty if it has none. */
    static Collection<?> bendValues(Edge e) {
        LinkedHashMapAttribute bends = null;
        try {
            bends = (LinkedHashMapAttribute) e.getAttribute(AttributeConstants.BENDS);
        } catch (Exception ignore) { /* no bends */ }
        if (bends == null || bends.getCollection() == null) return Collections.emptyList();
        return bends.getCollection().values();
    }
}
