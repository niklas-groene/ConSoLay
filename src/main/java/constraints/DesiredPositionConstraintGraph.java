package constraints;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulls nodes towards target positions with a weight (cola DesiredPositions): a soft wish the force-directed layout
 * weighs against the edges and the other constraints. Without a force-directed run the nodes are put on their
 * targets before the constraints are enforced.
 *
 * @author Niklas Gröne
 */
public class DesiredPositionConstraintGraph extends Constraint {

    private final Map<Node, double[]> targets = new LinkedHashMap<>();
    private double weight;

    /** @param targets node → {x, y} its centre should move to */
    public DesiredPositionConstraintGraph(Map<Node, double[]> targets, double weight) {
        super(new ArrayList<>(targets.keySet()));
        for (Map.Entry<Node, double[]> e : targets.entrySet()) this.targets.put(e.getKey(), e.getValue().clone());
        this.weight = weight;
    }

    /** Target centre {x, y} of the node. */
    public double[] getTarget(Node n) { return targets.get(n); }

    public void setTarget(Node n, double x, double y) {
        if (targets.containsKey(n)) targets.put(n, new double[]{x, y});
    }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    @Override
    public boolean removeNode(Node n) {
        targets.remove(n);
        return super.removeNode(n);
    }

    @Override
    public void replaceNode(Node n, Node copy) {
        super.replaceNode(n, copy);
        replaceKey(targets, n, copy);
    }

    @Override
    public int getMinNodes() {
        return 1;
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.TARGET;
    }
}
