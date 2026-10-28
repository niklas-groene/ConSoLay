package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * Alignment on a vertical line: the nodes share their left edges, their centres or their right edges (cola
 * AlignmentConstraint in x).
 */
public class VerticalConstraint extends Constraint {

    private final VerticalConstraintType type;

    public VerticalConstraintType getType() {
        return type;
    }

    /** Which part of the nodes lies on the line. */
    public enum VerticalConstraintType {
        LEFT, MIDDLE, RIGHT
    }

    @Override
    public ConstraintKind getKind() {
        return type == VerticalConstraintType.LEFT ? ConstraintKind.ALIGN_LEFT
                : type == VerticalConstraintType.RIGHT ? ConstraintKind.ALIGN_RIGHT : ConstraintKind.ALIGN_CENTER;
    }

    public VerticalConstraint(List<Node> nodes, VerticalConstraintType type) {
        super(nodes);
        this.type = type;
    }
}
