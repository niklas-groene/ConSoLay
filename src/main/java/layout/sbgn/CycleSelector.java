package layout.sbgn;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Selects the cycles of the skeleton that receive an orthogonal rectangle pattern: one per biconnected block of the
 * substrate / product / reversible subgraph (Tarjan), the block itself if it is a simple cycle, else its shortest
 * cycle. Since two blocks share at most one node, the selected cycles form a cactus, for which the union of the
 * rectangle patterns is always feasible (the separation order per dimension stays acyclic), so the patterns need no
 * feasibility check.
 */
public final class CycleSelector {

    /**
     * -Dcblayout.maxCycleEdges=n (default 12): longest cycle that gets a rectangle; longer rings are left to the
     * force-directed layout.
     */
    public static final int MAX_CYCLE_EDGES = ConstraintSBGNLayout.intProperty("cblayout.maxCycleEdges", 12);

    private CycleSelector() { }

    static boolean isProcessNode(Node n) {
        String r = ConstraintSBGNLayout.getSBGNRole(n);
        return r != null && (r.contains("PROCESS") || r.contains("ASSOCIATION") || r.contains("DISSOCIATION"));
    }

    /** Number of distinct non-process nodes on the cycle. */
    static int speciesCount(Cycle c) {
        Set<Node> s = new HashSet<>();
        for (Edge e : c.getEdges()) {
            if (!isProcessNode(e.getSource())) s.add(e.getSource());
            if (!isProcessNode(e.getTarget())) s.add(e.getTarget());
        }
        return s.size();
    }

    /**
     * Substrate, product and reversible arcs: the edges that can form a metabolic cycle (a ring closed through a
     * catalysis edge is a regulatory loop).
     */
    static boolean isFlowEdge(Edge e) {
        String r = ConstraintSBGNLayout.getSBGNRole(e);
        return r != null && (r.contains("CONSUMPTION") || r.contains("PRODUCTION") || r.contains("REVERSIBLE"));
    }

    /**
     * One cycle of at most {@code maxCycleEdges} edges per biconnected block of the flow subgraph, ordered by length.
     * Edges are skeleton edges in ring order.
     */
    public static List<Cycle> select(Graph skeleton, int maxCycleEdges) {
        List<Node> nodes = ConstraintSBGNLayout.layoutNodes(skeleton);
        int n = nodes.size();
        Map<Node, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(nodes.get(i), i);

        // undirected flow adjacency, one edge per node pair
        List<List<int[]>> adj = new ArrayList<>(n);      // {neighbour, edgeId}
        List<Edge> edges = new ArrayList<>();
        Set<Long> seenPairs = new HashSet<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (Edge e : ConstraintSBGNLayout.layoutEdges(skeleton)) {
            if (!isFlowEdge(e)) continue;
            Integer a = index.get(e.getSource()), b = index.get(e.getTarget());
            if (a == null || b == null || a.equals(b)) continue;
            long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
            if (!seenPairs.add(key)) continue;
            int id = edges.size();
            edges.add(e);
            adj.get(a).add(new int[]{b, id});
            adj.get(b).add(new int[]{a, id});
        }

        List<List<Integer>> blocks = biconnectedBlocks(n, adj);
        List<Cycle> result = new ArrayList<>();
        for (List<Integer> block : blocks) {
            if (block.size() < 3) continue;                       // a bridge or a 2-node block has no cycle
            List<Integer> ringEdges = ringOfBlock(block, edges, index);
            if (ringEdges == null) ringEdges = shortestCycle(block, adj, edges, index, n);
            if (ringEdges == null || ringEdges.size() > maxCycleEdges) continue;
            result.add(toCycle(ringEdges, edges));
        }
        result.sort(Comparator.comparingInt(c -> c.getEdges().size()));
        return result;
    }

    // ------------------------------------------------------------------ blocks

    private static List<List<Integer>> biconnectedBlocks(int n, List<List<int[]>> adj) {
        int[] disc = new int[n], low = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> blocks = new ArrayList<>();
        int[] time = {0};
        for (int s = 0; s < n; s++) {
            if (disc[s] == 0) dfs(s, -1, adj, disc, low, time, stack, blocks);
        }
        return blocks;
    }

    private static void dfs(int u, int parentEdge, List<List<int[]>> adj, int[] disc, int[] low, int[] time,
                            Deque<Integer> stack, List<List<Integer>> blocks) {
        disc[u] = low[u] = ++time[0];
        for (int[] nb : adj.get(u)) {
            int v = nb[0], e = nb[1];
            if (e == parentEdge) continue;
            if (disc[v] == 0) {
                stack.push(e);
                dfs(v, e, adj, disc, low, time, stack, blocks);
                low[u] = Math.min(low[u], low[v]);
                if (low[v] >= disc[u]) {
                    List<Integer> block = new ArrayList<>();
                    int x;
                    do { x = stack.pop(); block.add(x); } while (x != e);
                    blocks.add(block);
                }
            } else if (disc[v] < disc[u]) {
                stack.push(e);
                low[u] = Math.min(low[u], disc[v]);
            }
        }
    }

    // ------------------------------------------------------------------ cycles inside a block

    /** If the block is a simple cycle (every node has block degree 2), its edges in ring order; else null. */
    private static List<Integer> ringOfBlock(List<Integer> block, List<Edge> edges, Map<Node, Integer> index) {
        Map<Integer, List<Integer>> inc = incidence(block, edges, index);
        for (List<Integer> l : inc.values()) if (l.size() != 2) return null;
        if (inc.size() != block.size()) return null;
        return walk(block.get(0), inc, edges, index);
    }

    /** Shortest cycle of the block: for each edge (u,v) the shortest u-v path avoiding that edge. */
    private static List<Integer> shortestCycle(List<Integer> block, List<List<int[]>> adj, List<Edge> edges,
                                               Map<Node, Integer> index, int n) {
        Set<Integer> blockEdges = new HashSet<>(block);
        List<Integer> best = null;
        for (int skip : block) {
            int u = index.get(edges.get(skip).getSource()), v = index.get(edges.get(skip).getTarget());
            int[] viaEdge = new int[n];
            Arrays.fill(viaEdge, -1);
            boolean[] seen = new boolean[n];
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(u);
            seen[u] = true;
            boolean found = false;
            while (!queue.isEmpty() && !found) {
                int x = queue.poll();
                for (int[] nb : adj.get(x)) {
                    int y = nb[0], e = nb[1];
                    if (e == skip || !blockEdges.contains(e) || seen[y]) continue;
                    seen[y] = true;
                    viaEdge[y] = e;
                    if (y == v) { found = true; break; }
                    queue.add(y);
                }
            }
            if (!found) continue;
            List<Integer> path = new ArrayList<>();
            int cur = v;
            while (cur != u) {
                int e = viaEdge[cur];
                path.add(e);
                Edge ed = edges.get(e);
                cur = index.get(ed.getSource()) == cur ? index.get(ed.getTarget()) : index.get(ed.getSource());
            }
            path.add(skip);
            if (best == null || path.size() < best.size()) best = path;
        }
        if (best == null) return null;
        // bring into ring order starting at the first edge
        Map<Integer, List<Integer>> ringInc = incidence(best, edges, index);
        return walk(best.get(0), ringInc, edges, index);
    }

    private static Map<Integer, List<Integer>> incidence(List<Integer> edgeIds, List<Edge> edges, Map<Node, Integer> index) {
        Map<Integer, List<Integer>> inc = new HashMap<>();
        for (int e : edgeIds) {
            Edge ed = edges.get(e);
            inc.computeIfAbsent(index.get(ed.getSource()), k -> new ArrayList<>()).add(e);
            inc.computeIfAbsent(index.get(ed.getTarget()), k -> new ArrayList<>()).add(e);
        }
        return inc;
    }

    /** Walk a 2-regular edge set starting with {@code startEdge}; returns edges in ring order. */
    private static List<Integer> walk(int startEdge, Map<Integer, List<Integer>> inc, List<Edge> edges, Map<Node, Integer> index) {
        List<Integer> order = new ArrayList<>();
        int cur = index.get(edges.get(startEdge).getSource());
        int e = startEdge;
        int guard = inc.size() + 1;
        do {
            order.add(e);
            Edge ed = edges.get(e);
            int next = index.get(ed.getSource()) == cur ? index.get(ed.getTarget()) : index.get(ed.getSource());
            List<Integer> l = inc.get(next);
            if (l == null || l.size() != 2) return null;
            e = l.get(0) == e ? l.get(1) : l.get(0);
            cur = next;
        } while (e != startEdge && --guard > 0);
        return guard > 0 ? order : null;
    }

    private static Cycle toCycle(List<Integer> ringEdges, List<Edge> edges) {
        ArrayList<Edge> es = new ArrayList<>();
        for (int e : ringEdges) es.add(edges.get(e));
        Edge first = es.get(0), second = es.get(1);
        // starting point: the endpoint of the first edge that is NOT shared with the second edge
        Node start = (first.getSource() == second.getSource() || first.getSource() == second.getTarget())
                ? first.getTarget() : first.getSource();
        return new Cycle(start, es);
    }

    /**
     * Ring of graph nodes for a cycle whose edges are skeleton edges in ring order
     * ({@code lookup}: skeleton node to graph node), or {@code null} if inconsistent.
     */
    public static List<Node> ring(Cycle c, Map<Node, Node> lookup) {
        List<Edge> es = c.getEdges();
        if (es.isEmpty()) return null;
        List<Node> ring = new ArrayList<>(es.size());
        Node cur = c.getStartingPoint();
        for (Edge e : es) {
            Node next;
            if (e.getSource() == cur) next = e.getTarget();
            else if (e.getTarget() == cur) next = e.getSource();
            else return null;
            Node g = lookup.get(cur);
            if (g == null) return null;
            ring.add(g);
            cur = next;
        }
        return cur == c.getStartingPoint() ? ring : null;
    }
}
