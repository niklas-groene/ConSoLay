package constraints;

import org.AttributeHelper;
import org.graffiti.graph.Node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rigid group: the nodes keep the arrangement they had when the constraint was made and move only as a whole (cola
 * FixedRelativeConstraint). Before every solve the members are put back into that arrangement around their current
 * centroid, so a member dragged away returns to its place in the group. With {@code fixedPosition} the group also
 * resists moving at all.
 *
 * @author Niklas Gröne
 */
public class FixedRelativeConstraintGraph extends Constraint {

    /** Member → {dx, dy} of its centre from the members' centroid, taken at creation. */
    private final Map<Node, double[]> offsets = new LinkedHashMap<>();
    private boolean fixedPosition;

    public FixedRelativeConstraintGraph(List<Node> nodes, boolean fixedPosition) {
        super(nodes);
        this.fixedPosition = fixedPosition;
        double cx = 0, cy = 0;
        for (Node n : nodes) { cx += AttributeHelper.getPositionX(n); cy += AttributeHelper.getPositionY(n); }
        cx /= nodes.size();
        cy /= nodes.size();
        for (Node n : nodes) offsets.put(n, new double[]{AttributeHelper.getPositionX(n) - cx, AttributeHelper.getPositionY(n) - cy});
    }

    /**
     * A rigid group with a given arrangement (a template's): {@code offsets} maps each member to {dx, dy} of its centre
     * from the members' centroid; members without an entry get their current offset.
     */
    public FixedRelativeConstraintGraph(List<Node> nodes, boolean fixedPosition, Map<Node, double[]> offsets) {
        this(nodes, fixedPosition);
        for (Node n : nodes) {
            double[] o = offsets == null ? null : offsets.get(n);
            if (o != null) this.offsets.put(n, new double[]{o[0], o[1]});
        }
    }

    public boolean isFixedPosition() { return fixedPosition; }
    public void setFixedPosition(boolean fixedPosition) { this.fixedPosition = fixedPosition; }

    /** Offset {dx, dy} of the member's centre from the group centroid, as made. */
    public double[] getOffset(Node n) { return offsets.get(n); }

    @Override
    public boolean removeNode(Node n) {
        offsets.remove(n);
        return super.removeNode(n);
    }

    @Override
    public void replaceNode(Node n, Node copy) {
        super.replaceNode(n, copy);
        replaceKey(offsets, n, copy);
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.RIGID;
    }
}
