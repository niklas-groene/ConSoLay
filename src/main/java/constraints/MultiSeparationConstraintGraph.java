package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * Keeps nodes in the order of the list along an axis, consecutive centres at least {@code gap} apart (exactly, if
 * {@code exact}): cola MultiSeparationConstraint over one guideline per node.
 *
 * @author Niklas Gröne
 */
public class MultiSeparationConstraintGraph extends Constraint {

    private final Axis axis;
    private double gap;
    private boolean exact;

    /** @param ordered the nodes in their order along {@code axis} (left to right, top to bottom) */
    public MultiSeparationConstraintGraph(List<Node> ordered, Axis axis, double gap, boolean exact) {
        super(ordered);
        this.axis = axis;
        this.gap = gap;
        this.exact = exact;
    }

    public Axis getAxis() { return axis; }
    public double getGap() { return gap; }
    public void setGap(double gap) { this.gap = gap; }
    public boolean isExact() { return exact; }
    public void setExact(boolean exact) { this.exact = exact; }

    @Override
    public ConstraintKind getKind() {
        return axis == Axis.X ? ConstraintKind.ORDER_X : ConstraintKind.ORDER_Y;
    }
}
