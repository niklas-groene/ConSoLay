package layout.sbgn;

import java.util.*;
import java.util.function.IntToDoubleFunction;

/**
 * Minimal and stable feedback arc set: algorithm MS-FAS of Cavallaro, Cutello and Pavone, "Efficient heuristics to
 * compute minimal and stable feedback arc sets", Journal of Combinatorial Optimization 48:30 (2024),
 * doi:10.1007/s10878-024-01209-8, applied to every strongly connected component with at least two vertices
 * (Algorithm 1). Minimal: no arc of the set can be put back without closing a cycle. Stable: inside its component no
 * vertex loses more incoming arcs than it keeps outgoing ones, nor more outgoing arcs than it keeps incoming ones.
 */
final class FeedbackArcSet {

    private FeedbackArcSet() { }

    /**
     * @param n         number of vertices, numbered 0..n-1 (the order breaks ties, so pass a deterministic one)
     * @param from      arc tails
     * @param to        arc heads; the graph must be simple apart from self-loops (no two arcs u->v)
     * @param tieWeight per arc, may be null: among equally small sets the one with the largest sum wins
     * @return per arc whether it belongs to the feedback arc set; a self-loop always does
     */
    static boolean[] compute(int n, int[] from, int[] to, double[] tieWeight) {
        int m = from.length;
        boolean[] feedback = new boolean[m];
        List<List<Integer>> out = new ArrayList<>();
        for (int v = 0; v < n; v++) out.add(new ArrayList<>());
        for (int a = 0; a < m; a++) {
            if (from[a] == to[a]) feedback[a] = true;
            else out.get(from[a]).add(to[a]);
        }
        int[] comp = stronglyConnectedComponents(n, out);
        Map<Integer, List<Integer>> members = new TreeMap<>();
        for (int v = 0; v < n; v++) members.computeIfAbsent(comp[v], k -> new ArrayList<>()).add(v);
        for (List<Integer> vs : members.values()) {
            if (vs.size() < 2) continue;
            int[] local = new int[n];
            Arrays.fill(local, -1);
            for (int i = 0; i < vs.size(); i++) local[vs.get(i)] = i;
            List<Integer> arcIds = new ArrayList<>();
            for (int a = 0; a < m; a++) {
                if (from[a] != to[a] && local[from[a]] >= 0 && local[to[a]] >= 0) arcIds.add(a);
            }
            int[] f = new int[arcIds.size()], t = new int[arcIds.size()];
            double[] w = new double[arcIds.size()];
            for (int i = 0; i < arcIds.size(); i++) {
                int a = arcIds.get(i);
                f[i] = local[from[a]];
                t[i] = local[to[a]];
                w[i] = tieWeight == null ? 0 : tieWeight[a];
            }
            for (int i : new Component(vs.size(), f, t, w).msFas()) feedback[arcIds.get(i)] = true;
        }
        return feedback;
    }

    /** Component id per vertex (iterative Tarjan); ids are numbered in order of completion. */
    static int[] stronglyConnectedComponents(int n, List<List<Integer>> out) {
        int[] index = new int[n], low = new int[n], comp = new int[n], edgePos = new int[n];
        Arrays.fill(index, -1);
        boolean[] onStack = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>(), call = new ArrayDeque<>();
        int counter = 0, comps = 0;
        for (int s = 0; s < n; s++) {
            if (index[s] >= 0) continue;
            call.push(s);
            index[s] = low[s] = counter++;
            stack.push(s);
            onStack[s] = true;
            while (!call.isEmpty()) {
                int v = call.peek();
                if (edgePos[v] < out.get(v).size()) {
                    int w = out.get(v).get(edgePos[v]++);
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        call.push(w);
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    call.pop();
                    if (!call.isEmpty()) low[call.peek()] = Math.min(low[call.peek()], low[v]);
                    if (low[v] == index[v]) {
                        int w;
                        do {
                            w = stack.pop();
                            onStack[w] = false;
                            comp[w] = comps;
                        } while (w != v);
                        comps++;
                    }
                }
            }
        }
        return comp;
    }

    /** One strongly connected component with local vertex and arc numbers. */
    private static final class Component {
        final int n, m;
        final int[] from, to;
        final double[] tieWeight;
        final int[][] outArcs, inArcs;
        final Map<Long, Integer> arcOf = new HashMap<>();

        Component(int n, int[] from, int[] to, double[] tieWeight) {
            this.n = n;
            this.m = from.length;
            this.from = from;
            this.to = to;
            this.tieWeight = tieWeight;
            int[] outCount = new int[n], inCount = new int[n];
            for (int a = 0; a < m; a++) { outCount[from[a]]++; inCount[to[a]]++; }
            outArcs = new int[n][];
            inArcs = new int[n][];
            for (int v = 0; v < n; v++) { outArcs[v] = new int[outCount[v]]; inArcs[v] = new int[inCount[v]]; }
            Arrays.fill(outCount, 0);
            Arrays.fill(inCount, 0);
            for (int a = 0; a < m; a++) {
                outArcs[from[a]][outCount[from[a]]++] = a;
                inArcs[to[a]][inCount[to[a]]++] = a;
                arcOf.put(key(from[a], to[a]), a);
            }
        }

        static long key(int u, int v) { return ((long) u << 32) | (v & 0xffffffffL); }

        /**
         * Algorithm 2: the smallest minimal and stable set over the ten orderings and both removal directions; among
         * equally small sets the one with the largest total tie weight, then the first one found.
         */
        List<Integer> msFas() {
            List<Integer> best = null;
            for (int[] order : orderings()) {
                for (int dir = 0; dir < 2; dir++) {
                    List<Integer> f = minimalAndStable(removeArcs(order, dir == 0));
                    if (best == null || better(f, best)) best = f;
                }
            }
            return preferHeavier(best);
        }

        private double weight(List<Integer> set) {
            double w = 0;
            for (int a : set) w += tieWeight[a];
            return w;
        }

        /** Smaller, or equally small and heavier. */
        private boolean better(List<Integer> a, List<Integer> b) {
            return a.size() < b.size() || (a.size() == b.size() && weight(a) > weight(b) + 1e-9);
        }

        /**
         * Exchange pass beyond the paper, a no-op without positive tie weights: an arc of the set is swapped for a
         * heavier arc outside it when the result is still a feedback arc set, Algorithm 7 restores minimality and
         * stability, and the exchange is kept if the set got smaller, or equally small and heavier. On a single cycle
         * all twenty candidates coincide, so without this pass the vertex numbering alone would decide the cut arc.
         */
        List<Integer> preferHeavier(List<Integer> set) {
            boolean anyWeight = false;
            for (double w : tieWeight) anyWeight |= w > 0;
            if (!anyWeight) return set;
            List<Integer> f = set;
            boolean improved = true;
            while (improved) {
                improved = false;
                boolean[] inSet = new boolean[m];
                for (int a : f) inSet[a] = true;
                List<Integer> outs = new ArrayList<>(f), ins = new ArrayList<>();
                for (int a = 0; a < m; a++) if (!inSet[a]) ins.add(a);
                outs.sort(Comparator.comparingDouble(a -> tieWeight[a]));
                ins.sort(Comparator.comparingDouble(a -> -tieWeight[a]));
                search:
                for (int out : outs) {
                    for (int in : ins) {
                        if (tieWeight[in] <= tieWeight[out] + 1e-9) break;
                        boolean[] present = new boolean[m];
                        for (int a = 0; a < m; a++) present[a] = !inSet[a];
                        present[out] = true;
                        present[in] = false;
                        if (topologicalOrder(present) == null) continue;
                        List<Integer> g = new ArrayList<>(f);
                        g.remove(Integer.valueOf(out));
                        g.add(in);
                        g = minimalAndStable(g);
                        if (better(g, f)) {
                            f = g;
                            improved = true;
                            break search;
                        }
                    }
                }
            }
            return f;
        }

        /** Section 7: the orderings I to X, each a stable sort of the vertices by a degree key. */
        List<int[]> orderings() {
            double[] in = new double[n], out = new double[n];
            for (int v = 0; v < n; v++) { in[v] = inArcs[v].length; out[v] = outArcs[v].length; }
            // Inside a strongly connected component every vertex has in >= 1 and out >= 1, so the ratios are defined.
            List<double[]> keys = Arrays.asList(
                    negate(v -> (out[v] - in[v]) * (out[v] / in[v])),   // I    decreasing (out-in) * out/in
                    negate(v -> (in[v] - out[v]) * (in[v] / out[v])),   // II   decreasing (in-out) * in/out
                    values(v -> in[v] / out[v]),                        // III  increasing in/out
                    values(v -> out[v] / in[v]),                        // IV   increasing out/in
                    negate(v -> out[v] - in[v]),                        // V    decreasing out-in
                    values(v -> out[v] - in[v]),                        // VI   increasing out-in
                    negate(v -> out[v]),                                // VII  decreasing outdegree
                    values(v -> out[v]),                                // VIII increasing outdegree
                    negate(v -> in[v]),                                 // IX   decreasing indegree
                    values(v -> in[v]));                                // X    increasing indegree
            List<int[]> orders = new ArrayList<>();
            for (double[] k : keys) {
                List<Integer> vs = new ArrayList<>();
                for (int v = 0; v < n; v++) vs.add(v);
                vs.sort(Comparator.comparingDouble(v -> k[v]));
                int[] order = new int[n];
                for (int i = 0; i < n; i++) order[i] = vs.get(i);
                orders.add(order);
            }
            return orders;
        }

        private double[] values(IntToDoubleFunction f) {
            double[] r = new double[n];
            for (int v = 0; v < n; v++) r[v] = f.applyAsDouble(v);
            return r;
        }

        private double[] negate(IntToDoubleFunction f) {
            double[] r = values(f);
            for (int v = 0; v < n; v++) r[v] = -r[v];
            return r;
        }

        /** Algorithm 3 (RFA, {@code forward}: removes forward arcs) or Algorithm 4 (RBA: removes backward arcs). */
        private List<Integer> removeArcs(int[] order, boolean forward) {
            int[] pos = new int[n];
            for (int i = 0; i < n; i++) pos[order[i]] = i;
            boolean[] present = new boolean[m];
            Arrays.fill(present, true);
            List<Integer> removed = new ArrayList<>(), safe = new ArrayList<>();
            // Simple cycles a -> b -> a: RFA removes the forward arc of the pair, RBA the backward one.
            for (int v : order) {
                for (int a : outArcs[v]) {
                    int w = to[a];
                    boolean isForward = pos[v] < pos[w];
                    if (present[a] && isForward == forward && arcOf.containsKey(key(w, v))) {
                        present[a] = false;
                        removed.add(a);
                    }
                }
            }
            if (topologicalOrder(present) != null) return removed;
            for (int v : order) {
                // RFA: the outgoing forward arcs of v, safe when v has no incoming arc left.
                // RBA: the incoming backward arcs of v, safe when v has no outgoing arc left.
                int[] arcs = forward ? outArcs[v] : inArcs[v];
                boolean isSafe = forward ? degree(inArcs[v], present) == 0 : degree(outArcs[v], present) == 0;
                for (int a : arcs) {
                    if (!present[a]) continue;
                    int other = forward ? to[a] : from[a];
                    if (pos[other] <= pos[v]) continue;
                    present[a] = false;
                    (isSafe ? safe : removed).add(a);
                }
                if (topologicalOrder(present) != null) break;
            }
            for (int a : safe) present[a] = true;
            int[] topo = topologicalOrder(present);
            if (topo == null) {   // cannot happen (Theorem 2); keep the safe arcs out rather than return a non-FAS
                for (int a : safe) present[a] = false;
                removed.addAll(safe);
                topo = topologicalOrder(present);
            }
            int[] tpos = new int[n];
            for (int i = 0; i < n; i++) tpos[topo[i]] = i;
            for (Iterator<Integer> it = removed.iterator(); it.hasNext(); ) {
                int a = it.next();
                if (tpos[from[a]] < tpos[to[a]]) {
                    present[a] = true;
                    it.remove();
                }
            }
            return removed;
        }

        /** Algorithm 7: minimise and stabilise until stabilising no longer shrinks the set. */
        List<Integer> minimalAndStable(List<Integer> set) {
            List<Integer> f = set;
            while (true) {
                f = minimize(f);
                int k = f.size();
                f = stabilize(f);
                if (f.size() >= k) return f;
            }
        }

        /**
         * Algorithm 5: put arcs of the list back while the graph stays acyclic; the arc that moves up to the position
         * of one put back is skipped in this round. Unlike the pseudocode ({@code while h < k}, 1-based), every arc is
         * tested.
         */
        List<Integer> minimize(List<Integer> set) {
            List<Integer> f = new ArrayList<>(set);
            boolean[] present = new boolean[m];
            Arrays.fill(present, true);
            for (int a : f) present[a] = false;
            boolean[] tested = new boolean[m];
            int[] tpos = positions(topologicalOrder(present));
            boolean again = true;
            while (again) {
                again = false;
                int h = 0;
                while (h < f.size()) {
                    int a = f.get(h);
                    if (!tested[a]) {
                        present[a] = true;
                        if (tpos[from[a]] < tpos[to[a]]) {
                            f.remove(h);
                            again = true;
                        } else {
                            int[] topo = topologicalOrder(present);
                            if (topo != null) {
                                f.remove(h);
                                again = true;
                                tpos = positions(topo);
                            } else {
                                present[a] = false;
                                tested[a] = true;
                            }
                        }
                    }
                    h++;
                }
            }
            return f;
        }

        /**
         * Algorithm 6: in topological order, a vertex that lost more outgoing arcs than it keeps incoming ones gives up
         * its incoming arcs and gets all outgoing ones back (it becomes a source), and symmetrically for a vertex that
         * lost more incoming arcs than it keeps outgoing ones. Each such exchange keeps the graph acyclic and shrinks
         * the set.
         */
        List<Integer> stabilize(List<Integer> set) {
            boolean[] present = new boolean[m];
            Arrays.fill(present, true);
            for (int a : set) present[a] = false;
            List<Integer> newlyRemoved = new ArrayList<>();
            for (int v : topologicalOrder(present)) {
                int k1 = outArcs[v].length, k2 = inArcs[v].length;
                int k3 = degree(outArcs[v], present), k4 = degree(inArcs[v], present);
                if (k4 < k1 - k3) {
                    for (int a : inArcs[v]) if (present[a]) { present[a] = false; newlyRemoved.add(a); }
                    for (int a : outArcs[v]) present[a] = true;
                } else if (k3 < k2 - k4) {
                    for (int a : outArcs[v]) if (present[a]) { present[a] = false; newlyRemoved.add(a); }
                    for (int a : inArcs[v]) present[a] = true;
                }
            }
            List<Integer> f = new ArrayList<>();
            boolean[] listed = new boolean[m];
            for (int a : set) if (!present[a] && !listed[a]) { f.add(a); listed[a] = true; }
            for (int a : newlyRemoved) if (!present[a] && !listed[a]) { f.add(a); listed[a] = true; }
            return f;
        }

        private static int degree(int[] arcs, boolean[] present) {
            int d = 0;
            for (int a : arcs) if (present[a]) d++;
            return d;
        }

        private int[] positions(int[] topo) {
            int[] pos = new int[n];
            for (int i = 0; i < n; i++) pos[topo[i]] = i;
            return pos;
        }

        /** Kahn's algorithm over the present arcs, smallest free vertex first; null if the graph has a cycle. */
        int[] topologicalOrder(boolean[] present) {
            int[] indeg = new int[n];
            for (int a = 0; a < m; a++) if (present[a]) indeg[to[a]]++;
            PriorityQueue<Integer> free = new PriorityQueue<>();
            for (int v = 0; v < n; v++) if (indeg[v] == 0) free.add(v);
            int[] order = new int[n];
            int k = 0;
            while (!free.isEmpty()) {
                int v = free.poll();
                order[k++] = v;
                for (int a : outArcs[v]) {
                    if (present[a] && --indeg[to[a]] == 0) free.add(to[a]);
                }
            }
            return k == n ? order : null;
        }
    }
}
