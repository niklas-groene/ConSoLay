package constraints;

import org.AttributeHelper;
import org.graffiti.graph.Node;

import java.util.List;

/**
 * Two nodes in a given order along an axis: the centre of the second lies at least {@code distance} beyond the centre
 * of the first (cola SeparationConstraint), exactly {@code distance} if the constraint is fixed.
 */
public class SeparationConstraintGraph extends Constraint {

    private boolean fixed;

    private double distance;

    private final SeparationConstraintType type;

    public enum SeparationConstraintType {
        VERTICAL, HORIZONTAL
    }

    /** A separation whose distance is the current one from the centre of the first node to that of the second. */
    public SeparationConstraintGraph(List<Node> nodes, SeparationConstraintType separationConstraintType, boolean fixed) {
        super(nodes);
        this.type = separationConstraintType;
        this.fixed = fixed;
        if (separationConstraintType == SeparationConstraintType.HORIZONTAL) {
            this.distance = AttributeHelper.getPositionX(nodes.get(1)) - AttributeHelper.getPositionX(nodes.get(0));
        } else if (separationConstraintType == SeparationConstraintType.VERTICAL) {
            this.distance = AttributeHelper.getPositionY(nodes.get(1)) - AttributeHelper.getPositionY(nodes.get(0));
        } else {
            this.distance = 0;
        }
    }

    public SeparationConstraintGraph(List<Node> nodes, SeparationConstraintType separationConstraintType, boolean fixed, double distance) {
        super(nodes);
        this.type = separationConstraintType;
        this.fixed = fixed;
        this.distance = distance;
    }

    public boolean isFixed() {
        return fixed;
    }

    public void setFixed(boolean fixed) {
        this.fixed = fixed;
    }

    @Override
    public ConstraintKind getKind() {
        return type == SeparationConstraintType.VERTICAL ? ConstraintKind.SEPARATION_Y : ConstraintKind.SEPARATION_X;
    }

    public SeparationConstraintType getType() {
        return type;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }
}
