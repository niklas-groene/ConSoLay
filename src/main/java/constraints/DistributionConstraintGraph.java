package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * Equal spacing of nodes along one dimension, in the order of the node list: cola's DistributionConstraint over one
 * guideline per node, consecutive guidelines exactly {@code spacing} apart (libcola fixes the spacing to this value).
 * Used by the SBGN pipeline (-Dcblayout.rowPins=distribute) and by the "Distribute" button. The feasibility guard does
 * not know this type.
 */
public class DistributionConstraintGraph extends Constraint {

    private final SeparationConstraintGraph.SeparationConstraintType type;
    private double spacing;

    public DistributionConstraintGraph(List<Node> nodes, SeparationConstraintGraph.SeparationConstraintType type, double spacing) {
        super(nodes);
        this.type = type;
        this.spacing = spacing;
    }

    /** HORIZONTAL: equal x spacing; VERTICAL: equal y spacing. */
    public SeparationConstraintGraph.SeparationConstraintType getType() {
        return type;
    }

    public double getSpacing() {
        return spacing;
    }

    public void setSpacing(double spacing) {
        this.spacing = spacing;
    }

    @Override
    public ConstraintKind getKind() {
        return type == SeparationConstraintGraph.SeparationConstraintType.VERTICAL ? ConstraintKind.DISTRIBUTE_Y : ConstraintKind.DISTRIBUTE_X;
    }
}
