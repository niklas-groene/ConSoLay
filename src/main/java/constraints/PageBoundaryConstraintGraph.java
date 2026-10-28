package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * Keeps nodes inside a rectangular area (cola PageBoundaryConstraints). The area is the bounding box the nodes had
 * when the constraint was made, enlarged by {@code margin} on every side.
 *
 * @author Niklas Gröne
 */
public class PageBoundaryConstraintGraph extends Constraint {

    /** Bounding box of the node borders at creation: {minX, minY, maxX, maxY}. */
    private final double[] base;
    private double margin;

    public PageBoundaryConstraintGraph(List<Node> nodes, double[] base, double margin) {
        super(nodes);
        this.base = base.clone();
        this.margin = margin;
    }

    /** Bounding box of the node borders at creation, without the margin: {minX, minY, maxX, maxY} (a copy). */
    public double[] getBase() { return base.clone(); }

    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }

    public double getMinX() { return base[0] - margin; }
    public double getMinY() { return base[1] - margin; }
    public double getMaxX() { return base[2] + margin; }
    public double getMaxY() { return base[3] + margin; }

    @Override
    public int getMinNodes() {
        return 1;
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.AREA;
    }
}
