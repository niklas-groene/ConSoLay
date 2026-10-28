package constraints;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A star: a hub in the middle and the other nodes on a circle of {@code radius} around it, in the order of the list,
 * evenly spaced from {@code startAngle} on (radians, 0 = right, clockwise on screen because y points down), or at one
 * given {@link #getAngles() angle} each (a reaction's main substrate and product on the axis, co-factors on the
 * diagonals). Every ring node gets exact x and y offsets from the hub (cola SeparationConstraints with equality), so the
 * star keeps its shape and moves only as a whole.
 *
 * @author Niklas Gröne
 */
public class StarPattern extends Constraint {

    private final Node hub;
    private final List<Node> ring;
    private double radius;
    private final double startAngle;
    /** One angle (radians) per ring node, or null: evenly spaced from the start angle. */
    private double[] angles;

    public StarPattern(Node hub, List<Node> ring, double radius, double startAngle) {
        this(hub, ring, radius, startAngle, null);
    }

    /**
     * @param angles the angle of each ring node in radians (0 = right, clockwise on screen), or null for even spacing
     *               from {@code startAngle}; a shorter array leaves the remaining nodes evenly spaced
     */
    public StarPattern(Node hub, List<Node> ring, double radius, double startAngle, double[] angles) {
        super(withHub(hub, ring));
        this.hub = hub;
        this.ring = new ArrayList<>(ring);
        this.radius = radius;
        this.startAngle = startAngle;
        this.angles = angles == null ? null : angles.clone();
    }

    private static List<Node> withHub(Node hub, List<Node> ring) {
        List<Node> l = new ArrayList<>();
        l.add(hub);
        l.addAll(ring);
        return l;
    }

    public Node getHub() { return hub; }
    /** The ring nodes in their order around the hub. */
    public List<Node> getRing() { return ring; }
    public double getRadius() { return radius; }
    public void setRadius(double radius) { this.radius = radius; }
    public double getStartAngle() { return startAngle; }
    /** The given angle per ring node (radians), or null for even spacing. */
    public double[] getAngles() { return angles == null ? null : angles.clone(); }

    /** Offset {dx, dy} of ring node {@code i} from the hub centre. */
    public double[] offset(int i) {
        double a = angles != null && i < angles.length && !Double.isNaN(angles[i]) ? angles[i] : startAngle + 2 * Math.PI * i / ring.size();
        return new double[]{radius * Math.cos(a), radius * Math.sin(a)};
    }

    /** Without its hub the star is void; the ring closes up around a deleted ring node. */
    @Override
    public boolean removeNode(Node n) {
        int at = ring.indexOf(n);
        ring.remove(n);
        if (angles != null && at >= 0 && at < angles.length) {   // the ring closes up: the angles follow their nodes
            double[] rest = new double[angles.length - 1];
            System.arraycopy(angles, 0, rest, 0, at);
            System.arraycopy(angles, at + 1, rest, at, angles.length - at - 1);
            angles = rest;
        }
        boolean tooFew = super.removeNode(n);
        return n == hub || ring.isEmpty() || tooFew;
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.STAR;
    }
}
