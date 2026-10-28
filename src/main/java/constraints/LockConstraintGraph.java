package constraints;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pins nodes to fixed positions (cola Locks while the force-directed layout runs, fixed guidelines for the final
 * projection). The positions are the ones the nodes had when the constraint was made, until {@link #setPosition}.
 *
 * @author Niklas Gröne
 */
public class LockConstraintGraph extends Constraint {

    private final Map<Node, double[]> positions = new LinkedHashMap<>();

    /** @param positions node → {x, y} of its centre */
    public LockConstraintGraph(Map<Node, double[]> positions) {
        super(new ArrayList<>(positions.keySet()));
        for (Map.Entry<Node, double[]> e : positions.entrySet()) this.positions.put(e.getKey(), e.getValue().clone());
    }

    /** Centre {x, y} the node is pinned to. */
    public double[] getPosition(Node n) { return positions.get(n); }

    public void setPosition(Node n, double x, double y) {
        if (positions.containsKey(n)) positions.put(n, new double[]{x, y});
    }

    @Override
    public boolean removeNode(Node n) {
        positions.remove(n);
        return super.removeNode(n);
    }

    @Override
    public int getMinNodes() {
        return 1;
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.PIN;
    }
}
