package constraints;

import org.graffiti.graph.Node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps nodes together in a rectangular cluster that no other node enters: each other node stays on one side of the
 * members' box (see ConstraintConflicts, which chooses the sides, and UserConstraintTranslator.excludeFromClusters).
 * {@code padding} is the space between the cluster border and its members, {@code margin} the space between the border
 * and the nodes outside.
 *
 * @author Niklas Gröne
 */
public class ClusterConstraintGraph extends Constraint {

    private double padding;
    private double margin;
    /** The side (ConstraintConflicts.Side ordinal) each other node was kept on by the last solve. */
    private final Map<Node, Integer> lastSides = new HashMap<>();

    public ClusterConstraintGraph(List<Node> nodes, double padding, double margin) {
        super(nodes);
        this.padding = padding;
        this.margin = margin;
    }

    public double getPadding() { return padding; }
    public void setPadding(double padding) { this.padding = padding; }
    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }

    /** The side the last solve kept {@code n} on, or null. */
    Integer lastSide(Node n) { return lastSides.get(n); }

    void rememberSides(Map<Node, Integer> sides) {
        lastSides.clear();
        lastSides.putAll(sides);
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.CLUSTER;
    }
}
