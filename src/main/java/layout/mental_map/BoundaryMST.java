package layout.mental_map;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Minimum spanning tree over the boundary distance, the smallest Euclidean gap between the axis-aligned bounding
 * boxes of two nodes (0 for overlapping or touching boxes). {@link ImprovedMentalMapLayout} clusters with it; the
 * boxes are passed explicitly, so the tree can be built on the reference positions instead of the live ones.
 *
 * @author Niklas Gröne
 */
public final class BoundaryMST {

    private BoundaryMST() { }

    /** A weighted MST edge (weight = boundary distance). */
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

    /** Edge weight of the implicit complete graph over vertex indices. */
    interface Weight {
        double between(int u, int v);
    }

    /**
     * Boundary distance between two boxes, each given as {@code {centreX, centreY, width, height}}; 0 if the boxes
     * overlap or touch.
     */
    public static double boundaryDistance(double[] a, double[] b) {
        double aMinX = a[0] - a[2] / 2, aMaxX = a[0] + a[2] / 2;
        double aMinY = a[1] - a[3] / 2, aMaxY = a[1] + a[3] / 2;
        double bMinX = b[0] - b[2] / 2, bMaxX = b[0] + b[2] / 2;
        double bMinY = b[1] - b[3] / 2, bMaxY = b[1] + b[3] / 2;

        double dx = Math.max(0.0, Math.max(aMinX - bMaxX, bMinX - aMaxX));
        double dy = Math.max(0.0, Math.max(aMinY - bMaxY, bMinY - aMaxY));
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Boundary-distance MST over {@code nodes}.
     *
     * @param box each node's box as {@code {centreX, centreY, width, height}}
     * @return the MST edges (size {@code n-1})
     */
    public static List<WeightedEdge> computeMST(List<Node> nodes, Map<Node, double[]> box) {
        int n = nodes.size();
        List<WeightedEdge> mst = new ArrayList<>(Math.max(0, n - 1));
        if (n <= 1) return mst;

        double[][] boxes = new double[n][];
        for (int i = 0; i < n; i++) boxes[i] = box.get(nodes.get(i));
        double[] key = new double[n];
        int[] parent = prim(n, (u, v) -> boundaryDistance(boxes[u], boxes[v]), key);
        for (int v = 1; v < n; v++) {
            if (parent[v] >= 0) mst.add(new WeightedEdge(nodes.get(parent[v]), nodes.get(v), key[v]));
        }
        return mst;
    }

    /**
     * Prim's algorithm on the complete graph over the vertices {@code 0..n-1}, started at vertex 0, in O(n^2) time.
     * Returns the parent of every vertex (-1 for the root) and leaves the weight of its tree edge in {@code key}.
     */
    static int[] prim(int n, Weight weight, double[] key) {
        boolean[] inMST = new boolean[n];
        int[] parent = new int[n];
        Arrays.fill(key, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        key[0] = 0.0;

        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (!inMST[i] && key[i] < best) {
                    best = key[i];
                    u = i;
                }
            }
            if (u == -1) break;   // only NaN or infinite weights leave a vertex unreached
            inMST[u] = true;

            for (int v = 0; v < n; v++) {
                if (inMST[v] || v == u) continue;
                double w = weight.between(u, v);
                if (w < key[v]) {
                    key[v] = w;
                    parent[v] = u;
                }
            }
        }
        return parent;
    }
}
