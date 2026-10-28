package constraints;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.Arrays;

/**
 * Makes the edge between two nodes horizontal (both centres on one y) or vertical (one x): cola
 * OrthogonalEdgeConstraint.
 *
 * @author Niklas Gröne
 */
public class OrthogonalEdgeConstraintGraph extends Constraint {

    private final boolean horizontal;
    /** The edge it straightens (null if made from two nodes); deleting the edge deletes the constraint. */
    private final Edge edge;

    public OrthogonalEdgeConstraintGraph(Node source, Node target, boolean horizontal) {
        super(Arrays.asList(source, target));
        this.horizontal = horizontal;
        this.edge = null;
    }

    public OrthogonalEdgeConstraintGraph(Edge edge, boolean horizontal) {
        super(Arrays.asList(edge.getSource(), edge.getTarget()));
        this.horizontal = horizontal;
        this.edge = edge;
    }

    public Edge getEdge() { return edge; }

    /** True: horizontal edge (same y); false: vertical edge (same x). */
    public boolean isHorizontal() { return horizontal; }

    @Override
    public ConstraintKind getKind() {
        return horizontal ? ConstraintKind.EDGE_HORIZONTAL : ConstraintKind.EDGE_VERTICAL;
    }
}
