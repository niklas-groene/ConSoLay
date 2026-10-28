package constraints;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A dividing line between two groups of nodes (cola BoundaryConstraint): along {@link Axis#X} a vertical line with
 * {@link #getBefore() before} on its left and {@link #getAfter() after} on its right, along {@link Axis#Y} a
 * horizontal line with before above and after below. Every node keeps at least {@code gap} between its border and
 * the line; the line itself may move.
 *
 * @author Niklas Gröne
 */
public class BoundaryConstraintGraph extends Constraint {

    private final Axis axis;
    private final List<Node> before;
    private final List<Node> after;
    private double gap;

    public BoundaryConstraintGraph(List<Node> before, List<Node> after, Axis axis, double gap) {
        super(concat(before, after));
        this.axis = axis;
        this.before = new ArrayList<>(before);
        this.after = new ArrayList<>(after);
        this.gap = gap;
    }

    private static List<Node> concat(List<Node> a, List<Node> b) {
        List<Node> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    public Axis getAxis() { return axis; }
    /** Nodes left of (x) or above (y) the line. */
    public List<Node> getBefore() { return before; }
    /** Nodes right of (x) or below (y) the line. */
    public List<Node> getAfter() { return after; }
    public double getGap() { return gap; }
    public void setGap(double gap) { this.gap = gap; }

    /** A divider needs a node on each side. */
    @Override
    public boolean removeNode(Node n) {
        before.remove(n);
        after.remove(n);
        super.removeNode(n);
        return before.isEmpty() || after.isEmpty();
    }

    @Override
    public ConstraintKind getKind() {
        return axis == Axis.X ? ConstraintKind.DIVIDE_X : ConstraintKind.DIVIDE_Y;
    }
}
