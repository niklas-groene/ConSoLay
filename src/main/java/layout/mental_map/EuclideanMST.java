package layout.mental_map;

import org.AttributeHelper;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Euclidean minimum spanning tree over a set of nodes (implicit complete graph, weight = distance of the node
 * positions). {@link MentalMapLayout} derives its neighbourhood clusters from it.
 */
public final class EuclideanMST {

    /** A weighted MST edge (weight = centre distance). */
    public static final class WeightedEdge {
        public final Node u;
        public final Node v;
        public final double w;

        public WeightedEdge(Node u, Node v, double w) {
            this.u = u;
            this.v = v;
            this.w = w;
        }
    }

    /**
     * Euclidean MST over the current positions of {@code nodes}, in O(n^2) time.
     *
     * @return the MST edges (size {@code n-1}) in a modifiable list
     */
    public static List<WeightedEdge> computeMST(Collection<Node> nodes) {
        List<Node> V = (nodes instanceof List) ? (List<Node>) nodes : new ArrayList<>(nodes);
        int n = V.size();

        List<WeightedEdge> mst = new ArrayList<>(Math.max(0, n - 1));
        if (n <= 1) return mst;

        double[] key = new double[n];
        int[] parent = BoundaryMST.prim(n, (u, v) -> euclideanDistance(V.get(u), V.get(v)), key);
        for (int v = 1; v < n; v++) {
            if (parent[v] >= 0) mst.add(new WeightedEdge(V.get(parent[v]), V.get(v), key[v]));
        }
        return mst;
    }

    private static double euclideanDistance(Node a, Node b) {
        double ax = AttributeHelper.getPositionX(a);
        double ay = AttributeHelper.getPositionY(a);
        double bx = AttributeHelper.getPositionX(b);
        double by = AttributeHelper.getPositionY(b);

        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
