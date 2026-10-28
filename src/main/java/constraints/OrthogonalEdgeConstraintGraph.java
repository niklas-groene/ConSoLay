package constraints;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.Arrays;
import java.util.List;

/**
 * Makes the edge between two nodes horizontal (both centres on one y) or vertical (one x): cola
 * OrthogonalEdgeConstraint.
 *
 * @author Niklas Gröne
 */
public class OrthogonalEdgeConstraintGraph extends Constraint {

    private final boolean horizontal;
    /** The edge it straightens (null if made from two nodes); deleting the edge deletes the constraint. */
    private Edge edge;

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

    /**
     * Binds the constraint to {@code copy}, an edge that takes the place of its edge (the edge is deleted and
     * re-created), so that the deletion does not take the constraint along; its nodes become the ends of the copy.
     */
    public void rebind(Edge copy) {
        edge = copy;
        List<Node> ends = getNodes();
        ends.clear();
        ends.add(copy.getSource());
        ends.add(copy.getTarget());
    }

    /** True: horizontal edge (same y); false: vertical edge (same x). */
    public boolean isHorizontal() { return horizontal; }

    @Override
    public ConstraintKind getKind() {
        return horizontal ? ConstraintKind.EDGE_HORIZONTAL : ConstraintKind.EDGE_VERTICAL;
    }
}
