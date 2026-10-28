package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * Alignment on a horizontal line: the nodes share their top edges, their centres or their bottom edges (cola
 * AlignmentConstraint in y).
 */
public class HorizontalConstraint extends Constraint {

    /** Which part of the nodes lies on the line. */
    public enum HorizontalConstraintType {
        TOP, MIDDLE, BOTTOM
    }

    private final HorizontalConstraintType type;

    public HorizontalConstraint(List<Node> nodes, HorizontalConstraintType type) {
        super(nodes);
        this.type = type;
    }

    public HorizontalConstraintType getType() {
        return type;
    }

    @Override
    public ConstraintKind getKind() {
        return type == HorizontalConstraintType.TOP ? ConstraintKind.ALIGN_TOP
                : type == HorizontalConstraintType.BOTTOM ? ConstraintKind.ALIGN_BOTTOM : ConstraintKind.ALIGN_MIDDLE;
    }
}
