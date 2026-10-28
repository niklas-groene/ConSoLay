package layout.mental_map;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * Constrained stress majorization of Sheng et al. (2021), "Inverse Markov Process Based Constrained Dynamic Graph
 * Layout", JCST 36(3): minimises {@code S(X) + lambda_edge * S_edge(X) + lambda_node * S_node(X)} (their eq. 11),
 * where S_node pulls a node towards its previous position and S_edge keeps an edge vector {@code x_i - x_j} at its
 * previous value. It is a numeric kernel on index arrays that knows nothing about VANTED graphs.
 *
 * @author Niklas Gröne
 */
final class ShengLayout {

    /** Maximum number of majorization iterations. */
    private static final int MAX_ITER = 60;
    /** Relative stress decrease below which the majorization is considered converged. */
    private static final double TOL = 1e-4;
    /** Guard against division by zero in the SMACOF right-hand side. */
    private static final double EPS = 1e-9;

    private ShengLayout() { }

    /** One pairwise edge-vector constraint: keep {@code x_i - x_j} at {@code (lx, ly)}. */
    static final class EdgeConstraint {
        final int i, j;
        final double lx, ly;      // the previous relative vector x_i^prev - x_j^prev
        final double weight;      // s_ij in [0, 1]
        EdgeConstraint(int i, int j, double lx, double ly, double weight) {
            this.i = i; this.j = j; this.lx = lx; this.ly = ly; this.weight = weight;
        }
    }

    /**
     * Minimises the objective and writes the result back into {@code px}/{@code py}.
     *
     * @param nbr         adjacency list of the current topology (both directions)
     * @param idealLength layout length of one graph-distance unit; l_ij = d_ij * idealLength
     * @param px          in: x of the starting layout (the warm start); out: x of the solution
     * @param py          in: y of the starting layout; out: y of the solution
     * @param anchorX     previous x per node; NaN for an inserted node, which has none
     * @param anchorY     previous y per node
     * @param sNode       s_i per node (0 disables S_node for that node)
     * @param edges       the pairwise terms of S_edge (eq. 10)
     * @param lambdaNode  weight of S_node (eq. 9), already on the normalised scale
     * @param lambdaEdge  weight of S_edge, same scale
     */
    static void run(int[][] nbr, double idealLength,
                    double[] px, double[] py,
                    double[] anchorX, double[] anchorY, double[] sNode,
                    List<EdgeConstraint> edges,
                    double lambdaNode, double lambdaEdge) {
        final int n = px.length;
        if (n <= 1) return;

        // l_ij = graph distance in layout units, w_ij = 1/l_ij^2; pairs in different components get w_ij = 0 and are
        // left to separateComponents, since the stress model cannot separate components.
        double[][] l = new double[n][n];
        double[][] w = new double[n][n];
        int[] hop = new int[n];
        for (int s = 0; s < n; s++) {
            bfs(nbr, s, hop);
            for (int t = 0; t < n; t++) {
                if (t == s || hop[t] < 0) { l[s][t] = 0; w[s][t] = 0; continue; }
                double lst = hop[t] * idealLength;
                l[s][t] = lst;
                w[s][t] = 1.0 / (lst * lst);
            }
        }

        // Left-hand side of the majorization system (eq. A1): L^w + lambda_node*S + lambda_edge*L^s.
        double[][] A = new double[n][n];
        for (int i = 0; i < n; i++) {
            double diag = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                A[i][j] = -w[i][j];
                diag += w[i][j];
            }
            A[i][i] = diag;
        }
        for (int i = 0; i < n; i++) {
            if (sNode[i] > 0 && !Double.isNaN(anchorX[i])) {
                A[i][i] += lambdaNode * sNode[i];
            }
        }
        double[] dEdgeX = new double[n], dEdgeY = new double[n];
        for (EdgeConstraint e : edges) {
            if (e.weight <= 0) continue;
            double c = lambdaEdge * e.weight;
            A[e.i][e.i] += c;  A[e.j][e.j] += c;
            A[e.i][e.j] -= c;  A[e.j][e.i] -= c;
            dEdgeX[e.i] += c * e.lx;  dEdgeX[e.j] -= c * e.lx;
            dEdgeY[e.i] += c * e.ly;  dEdgeY[e.j] -= c * e.ly;
        }

        // A component without a node term (each one if no node is anchored, or an inserted isolated node) makes the
        // system singular: pin its first node to its current position, weighted with the mean diagonal.
        int[] pinned = unanchoredComponents(nbr, edges, anchorX, sNode);
        double pin = 0;
        if (pinned.length > 0) {
            for (int i = 0; i < n; i++) pin += A[i][i];
            pin = (pin > 0) ? pin / n : 1.0;
            for (int r : pinned) A[r][r] += pin;
        }

        double[][] chol = cholesky(A);
        if (chol == null) return;                 // not positive definite: leave the warm start

        double[] rhsX = new double[n], rhsY = new double[n];
        // Seeded with the warm start's objective: a relative test against infinity would stop after one step.
        double prevStress = objective(px, py, w, l, anchorX, anchorY, sNode, edges,
                                      lambdaNode, lambdaEdge);
        for (int it = 0; it < MAX_ITER; it++) {
            // SMACOF right-hand side at Z = (px, py):
            // (L^Z Z)_i = sum_{j != i} w_ij * l_ij * (z_i - z_j) / ||z_i - z_j||.
            for (int i = 0; i < n; i++) {
                double sx = 0, sy = 0;
                for (int j = 0; j < n; j++) {
                    if (i == j || w[i][j] <= 0) continue;
                    double dx = px[i] - px[j], dy = py[i] - py[j];
                    double dd = Math.sqrt(dx * dx + dy * dy);
                    if (dd < EPS) continue;
                    double c = w[i][j] * l[i][j] / dd;
                    sx += c * dx;
                    sy += c * dy;
                }
                rhsX[i] = sx; rhsY[i] = sy;
            }
            for (int i = 0; i < n; i++) {
                if (sNode[i] > 0 && !Double.isNaN(anchorX[i])) {
                    rhsX[i] += lambdaNode * sNode[i] * anchorX[i];
                    rhsY[i] += lambdaNode * sNode[i] * anchorY[i];
                }
                rhsX[i] += dEdgeX[i];
                rhsY[i] += dEdgeY[i];
            }
            for (int r : pinned) { rhsX[r] += pin * px[r]; rhsY[r] += pin * py[r]; }

            cholSolve(chol, rhsX, px);
            cholSolve(chol, rhsY, py);

            double st = objective(px, py, w, l, anchorX, anchorY, sNode, edges,
                                  lambdaNode, lambdaEdge);
            if (prevStress - st <= TOL * Math.max(1.0, prevStress)) break;
            prevStress = st;
        }

        separateComponents(nbr, px, py, idealLength);
    }

    /**
     * The first node of every component without a node term, ascending; the components join the nodes of {@code nbr}
     * and of the edge terms with a positive weight.
     */
    private static int[] unanchoredComponents(int[][] nbr, List<EdgeConstraint> edges, double[] anchorX,
                                              double[] sNode) {
        int n = nbr.length;
        int[] root = new int[n];
        for (int i = 0; i < n; i++) root[i] = i;
        for (int i = 0; i < n; i++) for (int j : nbr[i]) union(root, i, j);
        for (EdgeConstraint e : edges) if (e.weight > 0) union(root, e.i, e.j);
        boolean[] anchored = new boolean[n];
        for (int i = 0; i < n; i++) if (sNode[i] > 0 && !Double.isNaN(anchorX[i])) anchored[find(root, i)] = true;
        int[] first = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) if (find(root, i) == i && !anchored[i]) first[count++] = i;
        return Arrays.copyOf(first, count);
    }

    /** Root of {@code i}'s component: its node with the smallest index (see {@link #union}). */
    private static int find(int[] root, int i) {
        while (root[i] != i) i = root[i] = root[root[i]];
        return i;
    }

    private static void union(int[] root, int a, int b) {
        int ra = find(root, a), rb = find(root, b);
        if (ra != rb) root[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    /** Value of the objective at the current layout; used only as the convergence test. */
    private static double objective(double[] px, double[] py, double[][] w, double[][] l,
                                    double[] ax, double[] ay, double[] sNode,
                                    List<EdgeConstraint> edges,
                                    double lambdaNode, double lambdaEdge) {
        int n = px.length;
        double s = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                if (w[i][j] <= 0) continue;
                double d = Math.hypot(px[i] - px[j], py[i] - py[j]) - l[i][j];
                s += w[i][j] * d * d;
            }
        for (int i = 0; i < n; i++)
            if (sNode[i] > 0 && !Double.isNaN(ax[i])) {
                double dx = px[i] - ax[i], dy = py[i] - ay[i];
                s += lambdaNode * sNode[i] * (dx * dx + dy * dy);
            }
        for (EdgeConstraint e : edges) {
            double dx = px[e.i] - px[e.j] - e.lx, dy = py[e.i] - py[e.j] - e.ly;
            s += lambdaEdge * e.weight * (dx * dx + dy * dy);
        }
        return s;
    }

    /**
     * Pushes overlapping connected components apart along the minimal penetration depth of their bounding
     * rectangles, as Sheng et al. do: each pair moves half the distance along the axis of smaller overlap, repeated
     * until no pair overlaps or 20 passes are done.
     */
    private static void separateComponents(int[][] nbr, double[] px, double[] py,
                                           double idealLength) {
        int n = px.length;
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int nc = 0;
        int[] hop = new int[n];
        for (int s = 0; s < n; s++) {
            if (comp[s] >= 0) continue;
            bfs(nbr, s, hop);
            for (int t = 0; t < n; t++) if (hop[t] >= 0) comp[t] = nc;
            nc++;
        }
        if (nc < 2) return;

        final double pad = idealLength;
        for (int pass = 0; pass < 20; pass++) {
            double[][] box = new double[nc][4];   // minX, minY, maxX, maxY
            for (int c = 0; c < nc; c++)
                box[c] = new double[]{ Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                                       Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
            for (int i = 0; i < n; i++) {
                double[] b = box[comp[i]];
                b[0] = Math.min(b[0], px[i]); b[1] = Math.min(b[1], py[i]);
                b[2] = Math.max(b[2], px[i]); b[3] = Math.max(b[3], py[i]);
            }
            boolean moved = false;
            for (int a = 0; a < nc && !moved; a++)
                for (int b = a + 1; b < nc && !moved; b++) {
                    double[] A = box[a], B = box[b];
                    double ox = Math.min(A[2], B[2]) - Math.max(A[0], B[0]) + pad;
                    double oy = Math.min(A[3], B[3]) - Math.max(A[1], B[1]) + pad;
                    if (ox <= 0 || oy <= 0) continue;             // at least pad apart
                    boolean alongX = ox <= oy;
                    double shift = (alongX ? ox : oy) / 2.0;
                    double sign = alongX ? Math.signum((A[0] + A[2]) - (B[0] + B[2]))
                                         : Math.signum((A[1] + A[3]) - (B[1] + B[3]));
                    if (sign == 0) sign = 1;
                    for (int i = 0; i < n; i++) {
                        if (comp[i] == a) { if (alongX) px[i] += sign * shift; else py[i] += sign * shift; }
                        else if (comp[i] == b) { if (alongX) px[i] -= sign * shift; else py[i] -= sign * shift; }
                    }
                    moved = true;
                }
            if (!moved) return;
        }
    }

    /** Hop distances from {@code src}; -1 for unreachable. */
    private static void bfs(int[][] nbr, int src, int[] out) {
        Arrays.fill(out, -1);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        out[src] = 0; q.add(src);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : nbr[u]) if (out[v] < 0) { out[v] = out[u] + 1; q.add(v); }
        }
    }

    /** Lower Cholesky factor of a symmetric positive definite matrix; null if it is not positive definite. */
    private static double[][] cholesky(double[][] a) {
        int n = a.length;
        double[][] L = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double s = a[i][j];
                for (int k = 0; k < j; k++) s -= L[i][k] * L[j][k];
                if (i == j) {
                    if (s <= 0) return null;
                    L[i][i] = Math.sqrt(s);
                } else {
                    L[i][j] = s / L[j][j];
                }
            }
        }
        return L;
    }

    /** Solve {@code L L^T x = b} for the factor produced by {@link #cholesky}. */
    private static void cholSolve(double[][] L, double[] b, double[] x) {
        int n = L.length;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= L[i][k] * y[k];
            y[i] = s / L[i][i];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < n; k++) s -= L[k][i] * x[k];
            x[i] = s / L[i][i];
        }
    }

    /**
     * Propagated movement degree of Sheng et al. (eq. 7), {@code P = sum_{k=1..maxK} gamma^k P_0 I^k}, with the
     * influence matrix {@code I_uv = 1/deg(v)} of unit edge weights; the sum stops early once a term is negligible.
     * It starts at k = 1, so a node's own value returns only through walks that come back to it.
     */
    static double[] propagate(int[][] nbr, double[] p0, double gamma, int maxK) {
        int n = p0.length;
        double[] acc = new double[n];
        double[] cur = p0.clone();
        double[] next = new double[n];
        double g = 1.0;
        for (int k = 1; k <= maxK; k++) {
            g *= gamma;
            Arrays.fill(next, 0.0);
            for (int v = 0; v < n; v++) {
                int dv = nbr[v].length;
                if (dv == 0) continue;
                double s = 0;
                for (int u : nbr[v]) s += cur[u];
                next[v] = s / dv;
            }
            double contrib = 0;
            for (int v = 0; v < n; v++) { acc[v] += g * next[v]; contrib += g * next[v]; }
            double[] t = cur; cur = next; next = t;
            if (contrib < 1e-12) break;
        }
        return acc;
    }
}
