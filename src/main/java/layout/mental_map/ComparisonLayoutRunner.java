package layout.mental_map;

import adaptagrams_bridge.CBLayoutAlgorithm;
import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs one mental-map layout condition on the live graph relative to a pinned {@link Snapshot}: the own methods,
 * anchored methods from the literature, controls and diagnostics. The Mental Map panel offers {@link Choice#PROTOTYPE}
 * and shows {@link Choice#IMPROVED} as a placeholder; the other conditions serve comparisons and the regression tool.
 */
public final class ComparisonLayoutRunner {

    private static final double LAMBDA_FREE = 1e-6;
    private static final double LAMBDA_PIN = 1.0;
    private static final double SHENG_GAMMA = 0.8;
    private static final int SHENG_TERMS = 60;
    private static final double SHENG_EPS = 0.1;
    private static final double SIPA_ALPHA = 0.5;

    /** Selectable layout conditions; {@link #toString()} is the display label. */
    public enum Choice {
        /**
         * Main method: hard ordering constraints (half of each reference gap, at least 5 px unless the reference gap is
         * smaller), neighbourhood clusters and anchors; freed space is given back when the graph shrinks.
         */
        PROTOTYPE("Own method · Prototype"),
        /** Diagnostic: {@link #PROTOTYPE_THESIS} without the final overlap projection. */
        PROTOTYPE_NO_OVERLAP("Own method · Prototype without overlap"),
        /** The Prototype without the stable mode: full reference gaps, unmerged clusters, no anchors. */
        PROTOTYPE_THESIS("Own method · Prototype (thesis version)"),
        /** The improved constraint generator {@link ImprovedMentalMapLayout}. */
        IMPROVED("Own method · Improved / Algorithm 2"),
        /** Brandes–Mader anchored stress (APP) with lambda = 3e-4 inside the stress objective. */
        BRANDES_APP("Literature · Anchored stress (APP)"),
        /** Distance-based pinning after Frishman–Tal: BFS layers from the change, cutoff h = 3. */
        PINNING("Literature · Distance-based pinning"),
        /** Structure-based influence propagation and aging (SIPA) with beta = 0.35. */
        SIPA("Literature · SIPA"),
        /** Inverse Markov after Sheng et al. with node and pairwise edge terms (lambda node = 3, edge = 30). */
        INVERSE_MARKOV("Literature · Inverse Markov"),
        /** Control: survivors keep their reference positions; only inserted nodes are placed. */
        FROZEN("Control · Frozen"),
        /** Control: warm-started force-directed layout without an explicit stability term. */
        UNCONSTRAINED_FD("Control · Unconstrained FD"),
        /** Diagnostic: free layout aligned to the reference and blended 50:50 with it. */
        BLENDED_FD("Diagnostic · Blended FD"),
        /** Reduced SIPA using hop-decay influence. */
        SIPA_HOP("Fidelity · SIPA hop-decay"),
        /** Inverse-Markov node term on Sheng et al.'s stress-majorization engine. */
        INVERSE_MARKOV_SM_NODE("Fidelity · Inverse Markov, node term"),
        /** Node-only inverse-Markov influence on the shared anchored solver. */
        INVERSE_MARKOV_NODE("Fidelity · Inverse Markov, shared engine");

        private final String label;

        Choice(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final MentalMapLayout prototype = new MentalMapLayout();
    private final ImprovedMentalMapLayout improved = new ImprovedMentalMapLayout();
    private Snapshot lastReference;

    /**
     * Run one condition on the live graph relative to the pinned snapshot. Returns what the mental-map layouts
     * (Prototype, Improved) applied, for display; {@link DerivedConstraints#NONE} for every other condition.
     */
    public DerivedConstraints run(Choice choice, Graph live, Snapshot reference) {
        if (choice == null || live == null || reference == null || live.getNumberOfNodes() == 0) return DerivedConstraints.NONE;

        if (reference != lastReference) {
            improved.reset();
            lastReference = reference;
        }

        Context c = Context.from(live, reference);
        // Every condition starts from the pinned drawing on the survivors, not from the output of the previous run.
        resetSurvivorsAndSeedNewcomers(c);

        // The positions of the previous run are written back; its native solver objects can go.
        CBLayoutAlgorithm.clearNativeKeepAlive();

        // All conditions run in the engine's mental-map mode (executeFD lays out all nodes and edges) with edge
        // length 100 and padding 15, whatever -Dcblayout.fdDist and -Dcblayout.bboxPad are set to.
        boolean previousMode = CBLayoutAlgorithm.mentalMapMode;
        int previousDist = CBLayoutAlgorithm.dist;
        double previousBbox = CBLayoutAlgorithm.bboxConst;
        DerivedConstraints derived = DerivedConstraints.NONE;
        try {
            CBLayoutAlgorithm.mentalMapMode = true;
            CBLayoutAlgorithm.dist = 100;
            CBLayoutAlgorithm.bboxConst = 15;
            switch (choice) {
                case FROZEN:
                    break;
                case UNCONSTRAINED_FD:
                    CBLayoutAlgorithm.executeFD(live);
                    break;
                case BLENDED_FD:
                    blendedFD(c, 0.5);
                    break;
                case BRANDES_APP:
                    runBrandes(c, 3e-4);
                    break;
                case PINNING:
                    runPinning(c, 3);
                    break;
                case SIPA:
                    seedNewNodesSSBM(c);
                    runSipa(c, 0.35);
                    break;
                case SIPA_HOP:
                    runSipaHop(c, 0.5);
                    break;
                case INVERSE_MARKOV:
                    runSheng(c, 3.0, 30.0);
                    break;
                case INVERSE_MARKOV_SM_NODE:
                    runSheng(c, 3.0, 0.0);
                    break;
                case INVERSE_MARKOV_NODE:
                    runMarkovNodeOnly(c, 0.85);
                    break;
                case PROTOTYPE:
                    prototype.runMentalMapLayout(live, reference, MentalMapLayout.stableExtent);
                    derived = prototype.derived();
                    break;
                case PROTOTYPE_NO_OVERLAP:
                    runPrototypeWithoutOverlap(live, reference);
                    derived = prototype.derived();
                    break;
                case PROTOTYPE_THESIS:
                    prototype.runMentalMapLayout(live, reference, false);
                    derived = prototype.derived();
                    break;
                case IMPROVED:
                    improved.runMentalMapLayout(live, reference);
                    derived = improved.derived();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported layout choice: " + choice);
            }
        } finally {
            CBLayoutAlgorithm.mentalMapMode = previousMode;
            CBLayoutAlgorithm.dist = previousDist;
            CBLayoutAlgorithm.bboxConst = previousBbox;
        }
        return derived;
    }

    /** The Prototype without the stable mode and without the final overlap pass. */
    private void runPrototypeWithoutOverlap(Graph live, Snapshot reference) {
        boolean saved = CBLayoutAlgorithm.mentalMapFinalOverlapPass;
        CBLayoutAlgorithm.mentalMapFinalOverlapPass = false;
        try {
            prototype.runMentalMapLayout(live, reference, false);
        } finally {
            CBLayoutAlgorithm.mentalMapFinalOverlapPass = saved;
        }
    }

    private static void runBrandes(Context c, double lambda) {
        Map<Node, Point2D> anchors = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            Point2D p = c.refPos.get(e.getKey());
            if (p != null) anchors.put(e.getValue(), p);
        }
        CBLayoutAlgorithm.executeFDAnchored(c.live, anchors, lambda);
    }

    private static void runPinning(Context c, int cutoff) {
        Map<String, Integer> layers = bfsLayers(c.liveById, c.change.changed);
        Map<Node, Point2D> anchors = new LinkedHashMap<>();
        Map<Node, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            Point2D p = c.refPos.get(e.getKey());
            if (p == null) continue;
            Integer d = layers.get(e.getKey());
            double fraction = d == null ? 1.0 : Math.min(1.0, d / (double) cutoff);
            if (fraction <= 0) continue;
            anchors.put(e.getValue(), p);
            weights.put(e.getValue(), LAMBDA_FREE * Math.pow(LAMBDA_PIN / LAMBDA_FREE, fraction));
        }
        CBLayoutAlgorithm.executeFDAnchoredWeighted(c.live, anchors, weights);
    }

    private static void runSipa(Context c, double beta) {
        List<String> ids = sortedIds(c.liveById);
        Map<String, Integer> pos = positions(ids);
        Map<String, Double> mobility = sipaMobility(ids, adjacency(ids, pos, c.liveById), c, beta);
        runWeightedMobility(c, mobility);
    }

    private static void runSipaHop(Context c, double decay) {
        Map<String, Integer> layers = bfsLayers(c.liveById, c.change.changed);
        Map<String, Double> mobility = new HashMap<>();
        for (String id : c.liveById.keySet()) {
            Integer d = layers.get(id);
            double influence = d == null ? 0.0 : Math.pow(decay, d);
            double youth = c.change.changed.contains(id) ? 1.0 : 0.5;
            mobility.put(id, influence * youth);
        }
        runWeightedMobility(c, mobility);
    }

    private static void runMarkovNodeOnly(Context c, double damping) {
        runWeightedMobility(c, markovInfluence(c.liveById, c.change.changed, damping));
    }

    private static void runWeightedMobility(Context c, Map<String, Double> mobilityById) {
        Map<Node, Point2D> anchors = new LinkedHashMap<>();
        Map<Node, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            Point2D p = c.refPos.get(e.getKey());
            if (p == null) continue;
            Double value = mobilityById.get(e.getKey());
            double mobility = value == null ? 0.0 : Math.max(0.0, Math.min(1.0, value));
            if (mobility >= 0.999) continue;
            anchors.put(e.getValue(), p);
            weights.put(e.getValue(), Math.pow(LAMBDA_FREE, mobility) * LAMBDA_PIN);
        }
        CBLayoutAlgorithm.executeFDAnchoredWeighted(c.live, anchors, weights);
    }

    private static void blendedFD(Context c, double alpha) {
        CBLayoutAlgorithm.executeFD(c.live);
        List<String> survivors = new ArrayList<>();
        for (String id : c.liveById.keySet()) if (c.refPos.containsKey(id)) survivors.add(id);
        if (survivors.size() < 2) return;

        Map<String, Point2D> current = positionsByUid(c.live);
        double cx = 0, cy = 0, rx = 0, ry = 0;
        for (String id : survivors) {
            Point2D p = current.get(id), r = c.refPos.get(id);
            cx += p.getX(); cy += p.getY(); rx += r.getX(); ry += r.getY();
        }
        int n = survivors.size();
        cx /= n; cy /= n; rx /= n; ry /= n;
        double currentSpread = 0, referenceSpread = 0;
        for (String id : survivors) {
            Point2D p = current.get(id), r = c.refPos.get(id);
            currentSpread += square(p.getX() - cx) + square(p.getY() - cy);
            referenceSpread += square(r.getX() - rx) + square(r.getY() - ry);
        }
        double scale = currentSpread > 0 ? Math.sqrt(referenceSpread / currentSpread) : 1.0;
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            Point2D p = current.get(e.getKey());
            double x = rx + scale * (p.getX() - cx);
            double y = ry + scale * (p.getY() - cy);
            Point2D r = c.refPos.get(e.getKey());
            if (r != null) {
                x = (1 - alpha) * r.getX() + alpha * x;
                y = (1 - alpha) * r.getY() + alpha * y;
            }
            AttributeHelper.setPosition(e.getValue(), x, y);
        }
    }

    private static double square(double x) {
        return x * x;
    }

    // ---- SIPA ---------------------------------------------------------------------

    private static Map<String, Double> sipaMobility(List<String> ids, int[][] nbr,
                                                     Context c, double beta) {
        int n = ids.size();
        double[] influence = new double[n];
        boolean[] seed = new boolean[n];
        for (int i = 0; i < n; i++) {
            String id = ids.get(i);
            if (!c.refPos.containsKey(id)) {
                influence[i] = 1.0;
                seed[i] = true;
                continue;
            }
            int added = value(c.change.degAdd, id);
            int deleted = value(c.change.degDel, id);
            int previous = value(c.change.degPrev, id);
            if (added + deleted > 0) {
                influence[i] = previous <= 0 ? 1.0 : Math.min((added + deleted) / (double) previous, 1.0);
                seed[i] = true;
            }
        }

        boolean[] seen = seed.clone();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int i = n - 1; i >= 0; i--) if (seed[i]) stack.push(i);
        while (!stack.isEmpty()) {
            int u = stack.pop();
            for (int v : nbr[u]) {
                if (seen[v]) continue;
                seen[v] = true;
                influence[v] = influence[u] / Math.max(1, nbr[v].length);
                stack.push(v);
            }
        }

        double[] mobility = new double[n];
        double max = 0;
        for (int i = 0; i < n; i++) {
            int age = c.change.changed.contains(ids.get(i)) ? 1 : 2;
            mobility[i] = SIPA_ALPHA * influence[i] + (1 - SIPA_ALPHA) * Math.exp(-beta * age);
            max = Math.max(max, mobility[i]);
        }
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < n; i++) result.put(ids.get(i), max > 0 ? mobility[i] / max : 0.0);
        return result;
    }

    private static int value(Map<String, Integer> values, String id) {
        Integer value = values.get(id);
        return value == null ? 0 : value;
    }

    private static void seedNewNodesSSBM(Context c) {
        Set<String> placed = new HashSet<>();
        List<String> pending = new ArrayList<>();
        for (String id : new TreeSet<>(c.liveById.keySet())) {
            if (c.refPos.containsKey(id)) placed.add(id); else pending.add(id);
        }
        double[] centre = centroid(c.refPos.values());
        while (!pending.isEmpty()) {
            String best = null;
            int bestCount = -1;
            for (String id : pending) {
                int count = 0;
                for (Node neighbour : c.liveById.get(id).getNeighbors())
                    if (placed.contains(Snapshot.uidOf(neighbour))) count++;
                if (count > bestCount) { best = id; bestCount = count; }
            }
            pending.remove(best);
            Node node = c.liveById.get(best);
            double sx = 0, sy = 0;
            int count = 0;
            for (Node neighbour : node.getNeighbors()) {
                if (!placed.contains(Snapshot.uidOf(neighbour))) continue;
                sx += AttributeHelper.getPositionX(neighbour);
                sy += AttributeHelper.getPositionY(neighbour);
                count++;
            }
            double x = count > 0 ? sx / count : centre[0];
            double y = count > 0 ? sy / count : centre[1];
            double[] jitter = jitter(best);
            AttributeHelper.setPosition(node, x + jitter[0], y + jitter[1]);
            placed.add(best);
        }
    }

    // ---- inverse Markov / Sheng et al. -------------------------------------------

    private static void runSheng(Context c, double lambdaNode, double lambdaEdge) {
        List<String> ids = sortedIds(c.liveById);
        int n = ids.size();
        if (n < 2) return;
        Map<String, Integer> pos = positions(ids);
        int[][] nbrNow = adjacency(ids, pos, c.liveById);
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            Node node = c.liveById.get(ids.get(i));
            px[i] = AttributeHelper.getPositionX(node);
            py[i] = AttributeHelper.getPositionY(node);
        }

        double[] p0 = shengInitialMoving(ids, nbrNow, px, py, c);
        double[] moving = ShengLayout.propagate(nbrNow, p0, SHENG_GAMMA, SHENG_TERMS);
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) if (c.refPos.containsKey(ids.get(i))) min = Math.min(min, moving[i]);
        if (Double.isInfinite(min)) min = 0;
        double lambda2 = min + SHENG_EPS;

        double[] stability = new double[n], ax = new double[n], ay = new double[n];
        for (int i = 0; i < n; i++) {
            Point2D r = c.refPos.get(ids.get(i));
            if (r == null) {
                ax[i] = Double.NaN; ay[i] = Double.NaN;
            } else {
                ax[i] = r.getX(); ay[i] = r.getY();
                stability[i] = lambda2 / (moving[i] + SHENG_EPS);
            }
        }

        List<ShengLayout.EdgeConstraint> edgeConstraints = new ArrayList<>();
        if (lambdaEdge > 0 && !c.change.unionEdges.isEmpty()) {
            List<int[]> pairs = new ArrayList<>();
            for (String[] edge : c.change.unionEdges) {
                Integer i = pos.get(edge[0]), j = pos.get(edge[1]);
                if (i != null && j != null && !i.equals(j)) pairs.add(new int[]{i, j});
            }
            if (!pairs.isEmpty()) {
                int[][] nbrUnion = adjacency(n, pairs);
                double[] pairDegree = shengPairDegree(n, nbrUnion, nbrNow, pairs);
                for (int k = 0; k < pairs.size(); k++) {
                    int i = pairs.get(k)[0], j = pairs.get(k)[1];
                    Point2D ri = c.refPos.get(ids.get(i)), rj = c.refPos.get(ids.get(j));
                    if (ri == null || rj == null) continue;
                    edgeConstraints.add(new ShengLayout.EdgeConstraint(i, j,
                            ri.getX() - rj.getX(), ri.getY() - rj.getY(), pairDegree[k]));
                }
            }
        }

        double scale = CBLayoutAlgorithm.dist * (double) CBLayoutAlgorithm.dist;
        ShengLayout.run(nbrNow, CBLayoutAlgorithm.dist, px, py, ax, ay, stability,
                edgeConstraints, lambdaNode / scale, lambdaEdge / scale);
        for (int i = 0; i < n; i++) AttributeHelper.setPosition(c.liveById.get(ids.get(i)), px[i], py[i]);
    }

    private static double[] shengInitialMoving(List<String> ids, int[][] nbr,
                                                double[] px, double[] py, Context c) {
        double[] p0 = new double[ids.size()];
        double[] spatial = new double[ids.size()];
        for (int v = 0; v < ids.size(); v++) {
            if (nbr[v].length == 0) continue;
            for (int u : nbr[v]) {
                if (!c.refPos.containsKey(ids.get(u))) continue;
                spatial[v] += Math.abs(Math.hypot(px[u] - px[v], py[u] - py[v])
                        - CBLayoutAlgorithm.dist) / (nbr[v].length * CBLayoutAlgorithm.dist);
            }
        }
        for (int v = 0; v < ids.size(); v++) {
            String id = ids.get(v);
            if (!c.refPos.containsKey(id)) p0[v] = 1.0 + spatial[v];
            else if (value(c.change.degAdd, id) > 0)
                p0[v] = value(c.change.degAdd, id) / (double) Math.max(1, nbr[v].length) + spatial[v];
            else if (value(c.change.degDel, id) > 0) p0[v] = spatial[v];
        }
        return p0;
    }

    private static double[] shengPairDegree(int n, int[][] nbrUnion, int[][] nbrNow,
                                             List<int[]> pairs) {
        double[][] union = importanceRows(nbrUnion, n);
        double[][] now = importanceRows(nbrNow, n);
        double[] result = new double[pairs.size()];
        double max = 0;
        for (int k = 0; k < pairs.size(); k++) {
            int i = pairs.get(k)[0], j = pairs.get(k)[1];
            result[k] = Math.min(union[i][j] + union[j][i], now[i][j] + now[j][i]);
            max = Math.max(max, result[k]);
        }
        if (max > 0) for (int i = 0; i < result.length; i++) result[i] /= max;
        return result;
    }

    private static double[][] importanceRows(int[][] nbr, int n) {
        double[][] result = new double[n][];
        for (int v = 0; v < n; v++) {
            double[] seed = new double[n];
            seed[v] = 1.0;
            result[v] = ShengLayout.propagate(nbr, seed, SHENG_GAMMA, SHENG_TERMS);
        }
        return result;
    }

    private static Map<String, Double> markovInfluence(Map<String, Node> nodes,
                                                        Set<String> changed, double damping) {
        final double tolerance = 1e-9;
        final int maxIterations = Math.min(5000, Math.max(200,
                (int) Math.ceil(Math.log(tolerance) / Math.log(damping)) + 50));
        List<String> ids = sortedIds(nodes);
        Map<String, Integer> pos = positions(ids);
        int[][] nbr = adjacency(ids, pos, nodes);
        int[] degree = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) degree[i] = nbr[i].length;

        double[] base = new double[ids.size()];
        for (String id : changed) if (pos.containsKey(id)) base[pos.get(id)] = 1.0;
        double[] influence = base.clone(), next = new double[ids.size()];
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double delta = 0;
            for (int i = 0; i < ids.size(); i++) {
                double sum = 0;
                for (int j : nbr[i]) if (degree[j] > 0) sum += influence[j] / degree[j];
                next[i] = base[i] + damping * sum;
                delta = Math.max(delta, Math.abs(next[i] - influence[i]));
            }
            double[] swap = influence; influence = next; next = swap;
            if (delta < tolerance) break;
        }
        double max = 0;
        for (double v : influence) max = Math.max(max, v);
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) result.put(ids.get(i), max > 0 ? influence[i] / max : 0.0);
        return result;
    }

    // ---- graph comparison and deterministic initial state -------------------------

    private static final class Context {
        final Graph live;
        final Map<String, Node> liveById;
        final Map<String, Point2D> refPos;
        final StepChange change;

        private Context(Graph live, Map<String, Node> liveById,
                        Map<String, Point2D> refPos, StepChange change) {
            this.live = live;
            this.liveById = liveById;
            this.refPos = refPos;
            this.change = change;
        }

        static Context from(Graph live, Snapshot reference) {
            for (Node node : live.getNodes()) Snapshot.ensureUid(node);
            Map<String, Node> liveById = indexByUid(live);
            Map<String, Point2D> refPos = reference.positionsByUid();
            Set<String> refIds = new HashSet<>(refPos.keySet());
            Set<String> liveIds = new HashSet<>(liveById.keySet());
            StepChange change = stepChange(refIds, edgesByUid(reference.referenceGraph),
                    liveIds, edgesByUid(live));
            return new Context(live, liveById, refPos, change);
        }
    }

    private static final class StepChange {
        final Set<String> changed = new HashSet<>();
        final Map<String, Integer> degAdd = new HashMap<>();
        final Map<String, Integer> degDel = new HashMap<>();
        final Map<String, Integer> degPrev = new HashMap<>();
        final List<String[]> unionEdges = new ArrayList<>();
    }

    private static StepChange stepChange(Set<String> previousIds, List<String[]> previousEdges,
                                         Set<String> currentIds, List<String[]> currentEdges) {
        StepChange change = new StepChange();
        Set<String> previousSet = normalizedEdges(previousEdges);
        Set<String> currentSet = normalizedEdges(currentEdges);
        change.changed.addAll(changeLayer0(previousIds, previousEdges, currentIds, previousSet, currentSet));
        for (String id : currentIds) if (previousIds.contains(id)) {
            change.degAdd.put(id, 0);
            change.degDel.put(id, 0);
            change.degPrev.put(id, 0);
        }
        for (String[] edge : previousEdges) {
            bump(change.degPrev, edge[0]); bump(change.degPrev, edge[1]);
            if (!currentSet.contains(edgeKey(edge[0], edge[1]))) {
                bump(change.degDel, edge[0]); bump(change.degDel, edge[1]);
            }
        }
        for (String[] edge : currentEdges) {
            if (!previousSet.contains(edgeKey(edge[0], edge[1]))) {
                bump(change.degAdd, edge[0]); bump(change.degAdd, edge[1]);
            } else if (previousIds.contains(edge[0]) && previousIds.contains(edge[1])) {
                change.unionEdges.add(edge);
            }
        }
        return change;
    }

    /**
     * Nodes directly touched by the change: inserted nodes, neighbours of deleted nodes, and the current end nodes of
     * every inserted or deleted edge ({@code previousSet} and {@code currentSet} hold normalized edge keys).
     */
    private static Set<String> changeLayer0(Set<String> previousIds, List<String[]> previousEdges,
                                             Set<String> currentIds, Set<String> previousSet,
                                             Set<String> currentSet) {
        Set<String> result = new HashSet<>();
        for (String id : currentIds) if (!previousIds.contains(id)) result.add(id);
        Set<String> deleted = new HashSet<>(previousIds);
        deleted.removeAll(currentIds);
        for (String[] edge : previousEdges) {
            if (deleted.contains(edge[0]) && currentIds.contains(edge[1])) result.add(edge[1]);
            if (deleted.contains(edge[1]) && currentIds.contains(edge[0])) result.add(edge[0]);
        }
        Set<String> removed = new HashSet<>(previousSet);
        removed.removeAll(currentSet);
        Set<String> added = new HashSet<>(currentSet);
        added.removeAll(previousSet);
        removed.addAll(added);
        for (String key : removed) {
            int split = key.indexOf('\u0001');
            String a = key.substring(0, split), b = key.substring(split + 1);
            if (currentIds.contains(a)) result.add(a);
            if (currentIds.contains(b)) result.add(b);
        }
        return result;
    }

    private static void resetSurvivorsAndSeedNewcomers(Context c) {
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            Point2D p = c.refPos.get(e.getKey());
            if (p != null) AttributeHelper.setPosition(e.getValue(), p.getX(), p.getY());
        }
        double[] centre = centroid(c.refPos.values());
        for (Map.Entry<String, Node> e : c.liveById.entrySet()) {
            if (c.refPos.containsKey(e.getKey())) continue;
            double sx = 0, sy = 0;
            int count = 0;
            for (Node neighbour : e.getValue().getNeighbors()) {
                Point2D p = c.refPos.get(Snapshot.uidOf(neighbour));
                if (p != null) { sx += p.getX(); sy += p.getY(); count++; }
            }
            double[] jitter = jitter(e.getKey());
            AttributeHelper.setPosition(e.getValue(),
                    (count > 0 ? sx / count : centre[0]) + jitter[0],
                    (count > 0 ? sy / count : centre[1]) + jitter[1]);
        }
    }

    /** Deterministic offset of up to about 38 px per axis for a newcomer's seed, from its uid. */
    static double[] jitter(String id) {
        int hash = id.hashCode();
        return new double[]{((hash & 0xff) - 128) * 0.3, (((hash >> 8) & 0xff) - 128) * 0.3};
    }

    private static double[] centroid(Collection<Point2D> points) {
        double x = 0, y = 0;
        int count = 0;
        for (Point2D p : points) { x += p.getX(); y += p.getY(); count++; }
        return count == 0 ? new double[]{0, 0} : new double[]{x / count, y / count};
    }

    // ---- small graph helpers -------------------------------------------------------

    private static Map<String, Node> indexByUid(Graph graph) {
        Map<String, Node> result = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>(graph.getNodes());
        Collections.sort(nodes, Comparator.comparing(Snapshot::uidOf));
        for (Node node : nodes) result.put(Snapshot.uidOf(node), node);
        return result;
    }

    private static Map<String, Point2D> positionsByUid(Graph graph) {
        Map<String, Point2D> result = new HashMap<>();
        for (Node node : graph.getNodes()) result.put(Snapshot.uidOf(node), new Point2D.Double(
                AttributeHelper.getPositionX(node), AttributeHelper.getPositionY(node)));
        return result;
    }

    private static List<String[]> edgesByUid(Graph graph) {
        List<String[]> result = new ArrayList<>();
        for (Edge edge : graph.getEdges()) result.add(new String[]{
                Snapshot.uidOf(edge.getSource()), Snapshot.uidOf(edge.getTarget())});
        return result;
    }

    private static List<String> sortedIds(Map<String, Node> nodes) {
        List<String> ids = new ArrayList<>(nodes.keySet());
        Collections.sort(ids);
        return ids;
    }

    private static Map<String, Integer> positions(List<String> ids) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) result.put(ids.get(i), i);
        return result;
    }

    private static int[][] adjacency(List<String> ids, Map<String, Integer> pos,
                                     Map<String, Node> nodes) {
        List<List<Integer>> lists = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) lists.add(new ArrayList<Integer>());
        for (int i = 0; i < ids.size(); i++) {
            for (Node neighbour : nodes.get(ids.get(i)).getNeighbors()) {
                Integer j = pos.get(Snapshot.uidOf(neighbour));
                if (j != null && j != i && !lists.get(i).contains(j)) lists.get(i).add(j);
            }
        }
        return arrays(lists);
    }

    private static int[][] adjacency(int n, List<int[]> pairs) {
        List<List<Integer>> lists = new ArrayList<>();
        for (int i = 0; i < n; i++) lists.add(new ArrayList<Integer>());
        for (int[] pair : pairs) {
            if (!lists.get(pair[0]).contains(pair[1])) lists.get(pair[0]).add(pair[1]);
            if (!lists.get(pair[1]).contains(pair[0])) lists.get(pair[1]).add(pair[0]);
        }
        return arrays(lists);
    }

    private static int[][] arrays(List<List<Integer>> lists) {
        int[][] result = new int[lists.size()][];
        for (int i = 0; i < lists.size(); i++) {
            result[i] = new int[lists.get(i).size()];
            for (int j = 0; j < result[i].length; j++) result[i][j] = lists.get(i).get(j);
        }
        return result;
    }

    private static Map<String, Integer> bfsLayers(Map<String, Node> nodes, Set<String> layerZero) {
        Map<String, Integer> result = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (String id : layerZero) {
            Node node = nodes.get(id);
            if (node != null && !result.containsKey(id)) { result.put(id, 0); queue.add(node); }
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            int distance = result.get(Snapshot.uidOf(node));
            for (Node neighbour : node.getNeighbors()) {
                String id = Snapshot.uidOf(neighbour);
                if (!result.containsKey(id)) { result.put(id, distance + 1); queue.add(neighbour); }
            }
        }
        return result;
    }

    private static void bump(Map<String, Integer> values, String id) {
        Integer value = values.get(id);
        if (value != null) values.put(id, value + 1);
    }

    private static Set<String> normalizedEdges(List<String[]> edges) {
        Set<String> result = new HashSet<>();
        for (String[] edge : edges) result.add(edgeKey(edge[0], edge[1]));
        return result;
    }

    private static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + '\u0001' + b : b + '\u0001' + a;
    }
}
