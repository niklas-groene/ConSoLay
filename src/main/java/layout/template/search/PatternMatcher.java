package layout.template.search;

import layout.template.model.Condition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds every way a pattern occurs in a graph snapshot: subgraph monomorphism in the manner of VF2, assigning the
 * pattern nodes one after the other next to an assigned neighbour and giving up a partial assignment as soon as a
 * pattern edge has no counterpart. Every pattern node gets its own graph node and every pattern edge its own graph edge
 * between the assigned nodes (in the pattern's direction if both are directed), so parallel edges and self-loops need
 * counterparts; with {@code exact} the graph may have no further edges between the assigned nodes, with
 * {@code semantics} the node and edge conditions must hold as well, and separate parts of a pattern are placed anywhere.
 * One graph-edge choice is reported per node assignment.
 *
 * @author Niklas Gröne
 */
final class PatternMatcher {

    /** Receives each complete assignment (pattern node → graph node, pattern edge → graph edge); false stops. */
    interface Sink {
        boolean accept(int[] nodeMap, int[] edgeMap);
    }

    private final CompiledPattern p;
    private final GraphSnapshot g;
    private final boolean semantics;
    private final boolean exact;
    private final AtomicBoolean cancel;

    private int[] order;
    private int[] parent;
    private int[][] candidates;
    private final int[] map;
    private final boolean[] used;
    private final int[] stamp;
    private int stampNo;
    private long steps;
    private boolean stopped;

    PatternMatcher(CompiledPattern p, GraphSnapshot g, boolean semantics, boolean exact, AtomicBoolean cancel) {
        this.p = p;
        this.g = g;
        this.semantics = semantics;
        this.exact = exact;
        this.cancel = cancel;
        this.map = new int[p.nodeCount()];
        this.used = new boolean[g.nodeCount()];
        this.stamp = new int[g.nodeCount()];
    }

    /** Runs the search; returns false if it was stopped (by the sink or by cancelling). */
    boolean run(Sink sink) {
        int n = p.nodeCount();
        if (n == 0 || g.nodeCount() == 0) return true;
        Arrays.fill(map, -1);
        candidates = new int[n][];
        for (int i = 0; i < n; i++) {
            List<Integer> l = new ArrayList<>();
            for (int v = 0; v < g.nodeCount(); v++) if (fits(i, v)) l.add(v);
            candidates[i] = l.stream().mapToInt(Integer::intValue).toArray();
            if (candidates[i].length == 0) return true;   // a node without any candidate: no match at all
        }
        planOrder();
        search(0, sink);
        return !stopped;
    }

    /** Could graph node {@code v} take the place of pattern node {@code i} (ignoring the other nodes)? */
    private boolean fits(int i, int v) {
        if (!g.isAllowed(v)) return false;
        if (g.incident[v].length < p.degree[i]) return false;
        if (p.selfLoops[i] > 0 || exact) {
            int loops = 0;
            for (int e : g.incident[v]) if (g.src[e] == v && g.dst[e] == v) loops++;
            if (loops < p.selfLoops[i] || (exact && loops != p.selfLoops[i])) return false;
        }
        if (semantics) {
            for (Condition c : p.nodeConditions.get(i)) if (!c.test(g.nodeValue(c.attribute, v))) return false;
        }
        return true;
    }

    /**
     * Order of assignment: in each separate part of the pattern, the node with the fewest candidates first, then always
     * the node with the most edges to the nodes placed so far (ties: fewer candidates). {@code parent[k]} is a placed
     * neighbour of the k-th node, or -1 (it starts a part).
     */
    private void planOrder() {
        int n = p.nodeCount();
        order = new int[n];
        parent = new int[n];
        boolean[] placed = new boolean[n];
        int[] links = new int[n];
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (placed[i]) continue;
                if (best < 0 || links[i] > links[best]
                        || (links[i] == links[best] && candidates[i].length < candidates[best].length)) best = i;
            }
            order[k] = best;
            placed[best] = true;
            parent[k] = -1;
            if (links[best] > 0) {
                // of the placed neighbours, the one with the fewest candidates
                for (int j = 0; j < k; j++) {
                    int q = order[j];
                    if (!p.between(best, q).isEmpty() && (parent[k] < 0 || candidates[q].length < candidates[parent[k]].length))
                        parent[k] = q;
                }
            }
            for (int i = 0; i < n; i++) if (!placed[i] && i != best) links[i] += p.between(i, best).isEmpty() ? 0 : 1;
        }
    }

    private void search(int k, Sink sink) {
        if (stopped) return;
        if ((++steps & 1023) == 0 && cancel != null && cancel.get()) {
            stopped = true;
            return;
        }
        int n = p.nodeCount();
        if (k == n) {
            int[] edgeMap = assignEdges();
            if (edgeMap != null && !sink.accept(map.clone(), edgeMap)) stopped = true;
            return;
        }
        int u = order[k];
        if (parent[k] < 0) {
            for (int v : candidates[u]) {
                if (used[v] || !consistent(u, v)) continue;
                place(u, v);
                search(k + 1, sink);
                unplace(u, v);
                if (stopped) return;
            }
            return;
        }
        int w = map[parent[k]];
        stampNo++;
        List<Integer> local = new ArrayList<>();
        for (int e : g.incident[w]) {
            int v = g.other(e, w);
            if (stamp[v] == stampNo) continue;
            stamp[v] = stampNo;
            local.add(v);
        }
        for (int v : local) {
            if (used[v] || !contains(candidates[u], v) || !consistent(u, v)) continue;
            place(u, v);
            search(k + 1, sink);
            unplace(u, v);
            if (stopped) return;
        }
    }

    private static boolean contains(int[] sorted, int v) {
        return Arrays.binarySearch(sorted, v) >= 0;
    }

    private void place(int u, int v) {
        map[u] = v;
        used[v] = true;
    }

    private void unplace(int u, int v) {
        map[u] = -1;
        used[v] = false;
    }

    /** Can pattern node {@code u} go to graph node {@code v}, given the nodes placed so far? */
    private boolean consistent(int u, int v) {
        map[u] = v;   // tentatively, for the orientation checks
        try {
            if (p.selfLoops[u] > 0 && assign(p.between(u, u), targetEdges(v, v), null) == null) return false;
            for (int q = 0; q < p.nodeCount(); q++) {
                int w = map[q];
                if (q == u || w < 0) continue;
                List<Integer> pe = p.between(u, q);
                if (pe.isEmpty() && !exact) continue;
                List<Integer> te = targetEdges(v, w);
                if (exact && te.size() != pe.size()) return false;
                if (!pe.isEmpty() && assign(pe, te, null) == null) return false;
            }
            return true;
        } finally {
            map[u] = -1;
        }
    }

    /** The graph edges between {@code v} and {@code w} (v == w: the self-loops of v). */
    private List<Integer> targetEdges(int v, int w) {
        List<Integer> l = new ArrayList<>();
        int a = g.incident[v].length <= g.incident[w].length ? v : w, b = a == v ? w : v;
        for (int e : g.incident[a]) {
            if (a == b) {
                if (g.src[e] == a && g.dst[e] == a) l.add(e);
            } else if (g.other(e, a) == b) {
                l.add(e);
            }
        }
        return l;
    }

    /**
     * An injective choice of graph edges {@code te} for the pattern edges {@code pe} (directions and, in semantic
     * searches, edge conditions respected), or null if there is none. Writes into {@code out} (by pattern edge) if given.
     */
    private int[] assign(List<Integer> pe, List<Integer> te, int[] out) {
        if (te.size() < pe.size()) return null;
        int[] choice = new int[pe.size()];
        boolean[] taken = new boolean[te.size()];
        if (!assign(pe, te, 0, choice, taken)) return null;
        if (out != null) for (int i = 0; i < pe.size(); i++) out[pe.get(i)] = te.get(choice[i]);
        return choice;
    }

    private boolean assign(List<Integer> pe, List<Integer> te, int i, int[] choice, boolean[] taken) {
        if (i == pe.size()) return true;
        int e = pe.get(i);
        for (int j = 0; j < te.size(); j++) {
            if (taken[j] || !edgeFits(e, te.get(j))) continue;
            taken[j] = true;
            choice[i] = j;
            if (assign(pe, te, i + 1, choice, taken)) return true;
            taken[j] = false;
        }
        return false;
    }

    /** Does graph edge {@code t} fit pattern edge {@code e} under the current assignment? */
    private boolean edgeFits(int e, int t) {
        if (p.directed[e] && g.directed[t]) {
            if (g.src[t] != map[p.src[e]] || g.dst[t] != map[p.dst[e]]) return false;
        }
        if (semantics) {
            for (Condition c : p.edgeConditions.get(e)) if (!c.test(g.edgeValue(c.attribute, t))) return false;
        }
        return true;
    }

    /** One graph edge per pattern edge for the complete node assignment, or null. */
    private int[] assignEdges() {
        int[] out = new int[p.edgeIds.length];
        Arrays.fill(out, -1);
        int n = p.nodeCount();
        for (int a = 0; a < n; a++) {
            for (int b = a; b < n; b++) {
                List<Integer> pe = p.between(a, b);
                if (pe.isEmpty()) continue;
                if (assign(pe, targetEdges(map[a], map[b]), out) == null) return null;
            }
        }
        return out;
    }
}
