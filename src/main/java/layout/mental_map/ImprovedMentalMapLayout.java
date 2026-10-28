package layout.mental_map;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.Constraints;
import constraints.HorizontalConstraint;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;
import org.AttributeHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Improved mental-map preserving layout, a sibling of {@link MentalMapLayout} with the same entry point
 * {@link #runMentalMapLayout(Graph, Snapshot)} and its own {@link Constraints} store. The survivors keep their
 * reference order per axis through hard separation chains seeded by an isotonic projection ({@link Pava}); eligible
 * survivors are clustered by Zahn pruning of a boundary MST with hysteresis, split until no two clusters interleave,
 * and inserted nodes are blended in by age. The cross-step state (ages, previous clustering) is keyed by node label,
 * belongs to one instance and is cleared by {@link #reset()}.
 *
 * @author Niklas Gröne
 */
public class ImprovedMentalMapLayout {

    /**
     * Tie threshold: reference coordinates at most this far apart on an axis form one tie class and are aligned.
     * 0 = only exactly equal coordinates tie.
     */
    public double epsilon = 0.0;
    /**
     * Upper bound of the gap between consecutive blocks of the order chain; the gap used is the smaller of this value
     * and the smallest reference gap between consecutive blocks. It must stay positive (an exact order needs strictly
     * increasing coordinates) and large enough for the native solver to tell it from equality.
     */
    public double orderTolerance = 0.01;
    /** Upper bound of the Java repair passes after the native solve (see {@link #postSolveOrderRepair}). */
    private static final int MAX_POSTCONDITION_PASSES = 64;
    /**
     * Ordering slack sigma: per axis the tie classes are grouped into blocks of reference width at most sigma; the
     * order is released inside a block and hard between blocks, so every pair more than sigma apart keeps its order.
     * 0 = every block is one tie class (the plain chain).
     */
    public double orderSlack = 0.0;
    /** Cluster threshold delta used when the MST yields none (fewer than two eligible nodes, or no MST edge). */
    public double defaultDelta = 30.0;
    /** Zahn sensitivity factor c: an MST edge is pruned if its weight exceeds c times the local median. */
    public double zahnFactor = 2.0;
    /** Radius of the Zahn neighbourhood in MST hops. */
    public int zahnHops = 2;
    /** Width of the hysteresis band as a fraction of the representative threshold delta. */
    public double hysteresisFraction = 0.25;
    /** Age at which an inserted node becomes eligible for cluster membership. */
    public int eligibilityAge = 3;
    /** Maximum displacement per step of a young node's blended target. */
    public double blendBudget = 60.0;

    /**
     * Aged integration of inserted nodes; false = an inserted node is placed at its unconstrained target at once (no
     * blend, no budget).
     */
    public boolean useAging = true;
    /** Region-compatibility filter; false = the Zahn components are used without splitting interleaved ones. */
    public boolean useRegionFilter = true;
    /** Hysteresis band against the previous clustering; false = the plain Zahn criterion decides every edge. */
    public boolean useHysteresis = true;
    /**
     * Cluster admission of inserted nodes: false (default) = eligible at {@link #eligibilityAge}, true = eligible in
     * the step they are inserted.
     */
    public boolean immediateClusterAdmission = false;
    /**
     * Java order repair after the native solve: false (default) = the drawing is what the constraint system produced;
     * true = a Java DAG projection replaces the native overlap pass, compacts both axes, relocates runaway newcomers
     * and repairs remaining ordering-slack violations by a final PAVA projection, throwing if it cannot.
     */
    public boolean postSolveOrderRepair = false;

    /** The constraints derived in the last step (rebuilt on every step, handed to executeMentalMap). */
    private final Constraints store = new Constraints();
    /** Copy of what the last step applied, for display. */
    private DerivedConstraints derived = DerivedConstraints.NONE;

    /** Age per node label, in steps survived (0 = inserted in the current step). */
    private final Map<String, Integer> ageByKey = new HashMap<>();
    /** Previous clustering: node label -> cluster id, used for the hysteresis band. */
    private final Map<String, Integer> prevClusterByKey = new HashMap<>();
    /** True until the first step of a sequence; the nodes of its reference drawing start mature, not at age 1. */
    private boolean freshSequence = true;

    /** Clear all cross-step state. Call once per sequence so sequences are independent. */
    public void reset() {
        ageByKey.clear();
        prevClusterByKey.clear();
        freshSequence = true;
    }

    /** What the last step applied: its constraints and clusters (empty before the first step or after a failed one). */
    public DerivedConstraints derived() {
        return derived;
    }

    private static String keyOf(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        return (l == null || l.isEmpty()) ? ("id#" + n.getID()) : l;
    }

    /** Derive the constraints from {@code reference} and re-layout the live graph; without a reference, do nothing. */
    public void runMentalMapLayout(Graph live, Snapshot reference) {
        derived = DerivedConstraints.NONE;
        if (reference == null) return;
        Graph ref = reference.referenceGraph;
        Map<Node, Node> fwd = reference.mapToLive(live); // reference (copy) node -> live node

        store.clear();

        // Insertion-ordered: Node hashes are identity hashes, so sums over a HashMap (centroid) would add the same
        // doubles in a different order in every JVM run.
        Map<Node, double[]> refPos = new LinkedHashMap<>(); // live node -> {x, y} in the reference
        List<Node> survivors = new ArrayList<>();
        for (Node c : ref.getNodes()) {
            Node l = fwd.get(c);
            if (l != null && live.containsNode(l)) {
                refPos.put(l, new double[]{ AttributeHelper.getPositionX(c), AttributeHelper.getPositionY(c) });
                survivors.add(l);
            }
        }

        // ref.getNodes() order is not stable across JVM runs, but centroid sums, the stable tie-class sort and with it
        // every emitted constraint depend on the survivor order; refPos is rebuilt in the same order.
        survivors.sort(Comparator.comparing(ImprovedMentalMapLayout::keyOf));
        Map<Node, double[]> ordered = new LinkedHashMap<>();
        for (Node s : survivors) ordered.put(s, refPos.get(s));
        refPos = ordered;

        updateAges(live, refPos.keySet());

        // Every solve runs in the engine's mental-map mode, where executeFD lays out all nodes and edges instead of
        // running the SBGN pipeline's force-directed step.
        boolean previousMode = CBLayoutAlgorithm.mentalMapMode;

        if (survivors.size() < 2) {
            CBLayoutAlgorithm.allCluster = new ArrayList<>();
            try {
                CBLayoutAlgorithm.mentalMapMode = true;
                CBLayoutAlgorithm.executeMentalMap(live, store);
                derived = DerivedConstraints.capture(store);
            } finally {
                CBLayoutAlgorithm.mentalMapMode = previousMode;
            }
            return;
        }

        // Ordering: targets from one unconstrained layout, then the isotonic projection per axis.
        seedPositions(live, refPos);
        try {
            CBLayoutAlgorithm.mentalMapMode = true;
            CBLayoutAlgorithm.executeFD(live);
        } finally {
            CBLayoutAlgorithm.mentalMapMode = previousMode;
        }
        Map<Node, double[]> target = currentPositions(live);

        // One AxisOrder per axis, shared by projection and constraint generation so both use identical gaps.
        AxisOrder orderX = new AxisOrder(survivors, refPos, 0);
        AxisOrder orderY = new AxisOrder(survivors, refPos, 1);

        double[] projX = projectAxis(survivors, orderX, refPos, target, 0, true);
        double[] projY = projectAxis(survivors, orderY, refPos, target, 1, true);
        applyPositions(live, survivors, projX, projY, target, refPos);
        // Compact, order-feasible geometry before the native cluster projection: the Java DAG repair uses it only as
        // an orientation and range guide and keeps the native result as its displacement target.
        Map<Node, double[]> dagGuide = currentPositions(live);

        emitOrderChains(orderX, 0);
        emitOrderChains(orderY, 1);

        List<Node> eligible = eligibleForClustering(live, survivors);
        // While a hard block order remains, the repair keeps rectangles away from libcola's overlap heuristic (its
        // constraints can form long alternating X/Y chains and an enormous drawing); the Java DAG repair takes over.
        boolean explicitNonOverlap = postSolveOrderRepair
                && CBLayoutAlgorithm.mentalMapFinalOverlapPass
                && (orderX.blockCount() > 1 || orderY.blockCount() > 1);
        double delta = buildAndSetClusters(eligible, refPos, orderX.blockIndexByNode(), orderY.blockIndexByNode());

        // Inter-cluster separation through the cluster hierarchy: margin delta/2.
        CBLayoutAlgorithm.clusterMargin = Math.max(2.0, delta / 2.0);
        CBLayoutAlgorithm.clusterPadding = 15;
        try {
            CBLayoutAlgorithm.mentalMapMode = true;
            CBLayoutAlgorithm.executeMentalMap(live, store, explicitNonOverlap);
            // before the finally below resets the cluster margin, which the capture records
            derived = DerivedConstraints.capture(store);

            // The native cluster projection is not repeated with an accumulating constraint set (its native
            // allocations can grow without bound); the remaining small corrections run in Java.
            if (explicitNonOverlap) {
                repairPartialOrderPostconditions(eligible, orderX, orderY, dagGuide);
                int remainingOverlap = countNodeOverlaps(eligible, orderX, orderY);
                int remainingOrder = countSlackGuaranteeViolations(survivors, refPos);
                if (remainingOverlap > 0 || remainingOrder > 0) {
                    throw new IllegalStateException("Java postcondition repair did not converge: "
                            + remainingOverlap + " overlap(s), " + remainingOrder
                            + " ordering violation(s)");
                }
                repairRunawayNewcomers(live, survivors);
            } else if (postSolveOrderRepair) {
                repairSlackGuaranteeIfNeeded(live, survivors, refPos, orderX, orderY);
            }
        } finally {
            CBLayoutAlgorithm.mentalMapMode = previousMode;
            // the defaults the prototype runs with
            CBLayoutAlgorithm.clusterMargin = 5;
            CBLayoutAlgorithm.clusterPadding = 15;
        }
    }

    /**
     * Re-project both axes when the native solve rounded a small hard separation to equality. Without a violation the
     * native result stays untouched.
     */
    private void repairSlackGuaranteeIfNeeded(Graph live, List<Node> survivors,
                                              Map<Node, double[]> refPos,
                                              AxisOrder orderX, AxisOrder orderY) {
        // Only the graded guarantee is checked, not every edge at a block boundary: re-projecting a 0.01 px edge
        // within sigma can oscillate forever against a rectangle separation.
        int lostOrder = countSlackGuaranteeViolations(survivors, refPos);
        if (lostOrder > 0) {
            Map<Node, double[]> nativeTarget = currentPositions(live);
            double[] repairedX = projectAxis(survivors, orderX, refPos, nativeTarget, 0, false);
            double[] repairedY = projectAxis(survivors, orderY, refPos, nativeTarget, 1, false);
            for (int i = 0; i < survivors.size(); i++)
                AttributeHelper.setPosition(survivors.get(i), repairedX[i], repairedY[i]);

            int remaining = countSlackGuaranteeViolations(survivors, refPos);
            if (remaining > 0) {
                throw new IllegalStateException("PAVA postcondition repair left " + remaining
                        + " ordering-slack violation(s) out of " + lostOrder);
            }
        }
    }

    // =================================================================
    // Aging
    // =================================================================
    private void updateAges(Graph live, Set<Node> survivors) {
        Set<String> present = new HashSet<>();
        for (Node n : live.getNodes()) {
            String k = keyOf(n);
            present.add(k);
            if (survivors.contains(n)) {
                Integer prev = ageByKey.get(k);
                if (prev != null) {
                    ageByKey.put(k, prev + 1);
                } else {
                    // First sighting as a survivor: on the first step a node of the reference drawing (mature),
                    // later only after a skipped reset(), where 1 is the conservative choice.
                    ageByKey.put(k, freshSequence ? eligibilityAge : 1);
                }
            } else {
                // Inserted. Plain put: a node reappearing under an earlier label is new and must not inherit its age.
                ageByKey.put(k, 0);
            }
        }
        ageByKey.keySet().retainAll(present);
        freshSequence = false;
    }

    private int ageOf(Node n) {
        return ageByKey.getOrDefault(keyOf(n), 0);
    }

    /**
     * The nodes offered to the clustering: survivors that have reached {@link #eligibilityAge}, or every node of the
     * current graph under {@link #immediateClusterAdmission}.
     */
    private List<Node> eligibleForClustering(Graph live, List<Node> survivors) {
        List<Node> e = new ArrayList<>();
        if (immediateClusterAdmission) {
            for (Node n : live.getNodes()) e.add(n);
            return e;
        }
        for (Node n : survivors) if (ageOf(n) >= eligibilityAge) e.add(n);
        return e;
    }

    // =================================================================
    // Tie classes, PAVA projection, order chains
    // =================================================================

    /** Surviving nodes grouped into ordered tie classes on {@code axis} (0 = X, 1 = Y). */
    private List<List<Node>> tieClasses(List<Node> survivors, Map<Node, double[]> refPos, int axis) {
        List<Node> sorted = new ArrayList<>(survivors);
        sorted.sort(Comparator.comparingDouble(n -> refPos.get(n)[axis]));
        List<List<Node>> classes = new ArrayList<>();
        List<Node> cur = null;
        double last = Double.NaN;
        for (Node n : sorted) {
            double c = refPos.get(n)[axis];
            if (cur == null || c - last > epsilon) {
                cur = new ArrayList<>();
                classes.add(cur);
            }
            cur.add(n);
            last = c;
        }
        return classes;
    }

    /**
     * The ordered tie classes and blocks of one axis together with the gaps the order chain enforces. Projection and
     * constraint generation both use this one object, so the seed handed to the solver satisfies the emitted chain.
     */
    private final class AxisOrder {
        final List<List<Node>> classes;
        /** Class index -> block index. */
        final int[] blockOf;
        /** Per block the inclusive class index range {@code {first, last}}. */
        final List<int[]> blocks;
        /** Cumulative required gap per block; {@code offset[0] = 0}. */
        final double[] offset;
        /** The gap between two consecutive blocks. */
        final double gMin;

        AxisOrder(List<Node> survivors, Map<Node, double[]> refPos, int axis) {
            this.classes = tieClasses(survivors, refPos, axis);
            int p = classes.size();
            double[] classRef = new double[p];
            for (int i = 0; i < p; i++) {
                double s = 0;
                for (Node n : classes.get(i)) s += refPos.get(n)[axis];
                classRef[i] = s / classes.get(i).size();
            }

            // Blocks: greedy runs of reference width <= orderSlack (a width bound cannot chain through near-ties like
            // single linkage), each cut at its widest internal gap so that the gap facing the next block is largest.
            blocks = new ArrayList<>();
            int first = 0;
            while (first < p) {
                int end = first;
                while (end + 1 < p && classRef[end + 1] - classRef[first] <= orderSlack) end++;
                if (end == p - 1) { blocks.add(new int[]{ first, p - 1 }); break; }
                int cut = end;
                double widest = classRef[end + 1] - classRef[end];
                for (int c = first; c < end; c++) {
                    double g = classRef[c + 1] - classRef[c];
                    if (g > widest) { widest = g; cut = c; }
                }
                blocks.add(new int[]{ first, cut });
                first = cut + 1;
            }
            blockOf = new int[p];
            for (int b = 0; b < blocks.size(); b++)
                for (int i = blocks.get(b)[0]; i <= blocks.get(b)[1]; i++) blockOf[i] = b;

            // gammaMin = smallest reference gap between the facing borders of two consecutive blocks; capping the gap
            // by it keeps the reference drawing a feasible witness of the emitted chain.
            double gammaMin = Double.POSITIVE_INFINITY;
            for (int b = 1; b < blocks.size(); b++) {
                double lo = classRef[blocks.get(b - 1)[1]];
                double hi = classRef[blocks.get(b)[0]];
                gammaMin = Math.min(gammaMin, hi - lo);
            }
            gMin = (blocks.size() < 2) ? orderTolerance
                                       : Math.max(0.0, Math.min(orderTolerance, gammaMin));

            offset = new double[Math.max(1, blocks.size())];
            for (int b = 1; b < blocks.size(); b++) offset[b] = offset[b - 1] + gMin;
        }

        int size() { return classes.size(); }

        int blockCount() { return blocks.size(); }

        /** Node -&gt; block index, i.e. the order the emitted chain enforces. */
        Map<Node, Integer> blockIndexByNode() {
            Map<Node, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < classes.size(); i++)
                for (Node n : classes.get(i)) m.put(n, blockOf[i]);
            return m;
        }

        /** Node -&gt; tie-class index; nodes of one class are aligned on this axis. */
        Map<Node, Integer> classIndexByNode() {
            Map<Node, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < classes.size(); i++)
                for (Node n : classes.get(i)) m.put(n, i);
            return m;
        }
    }

    /**
     * Optimal order-preserving coordinates for one axis, one per node of {@code survivors}: blocks enter with the
     * weighted mean target of their classes, the gap chain is removed by substitution and PAVA solves the rest.
     * {@code applyAgeBlend} = false projects the targets verbatim, for a repair after the young-node blend was applied.
     */
    private double[] projectAxis(List<Node> survivors, AxisOrder order,
                                 Map<Node, double[]> refPos, Map<Node, double[]> target,
                                 int axis, boolean applyAgeBlend) {
        int p = order.size();
        double[] classTarget = new double[p];
        double[] classWeight = new double[p];
        for (int i = 0; i < p; i++) {
            double sum = 0;
            for (Node n : order.classes.get(i)) {
                sum += applyAgeBlend ? blendedTarget(n, refPos, target, axis)
                                     : target.get(n)[axis];
            }
            classWeight[i] = order.classes.get(i).size();
            classTarget[i] = sum / order.classes.get(i).size();
        }

        int m = order.blockCount();
        double[] blockTarget = new double[m], blockWeight = new double[m];
        for (int b = 0; b < m; b++) {
            int[] r = order.blocks.get(b);
            double sum = 0, w = 0;
            for (int i = r[0]; i <= r[1]; i++) { sum += classTarget[i] * classWeight[i]; w += classWeight[i]; }
            blockWeight[b] = w;
            blockTarget[b] = sum / w;
        }
        // Substituting eta_b = xi_b - offset_b turns xi_b - xi_{b-1} >= gMin into plain monotonicity, so PAVA
        // returns the exact minimiser.
        double[] tilde = new double[m];
        for (int b = 0; b < m; b++) tilde[b] = blockTarget[b] - order.offset[b];
        double[] eta = Pava.isotonic(tilde, blockWeight);
        double[] xi = new double[m];
        for (int b = 0; b < m; b++) xi[b] = eta[b] + order.offset[b];

        Map<Node, Double> coordByNode = new LinkedHashMap<>();
        for (int b = 0; b < m; b++) {
            int[] r = order.blocks.get(b);
            if (r[0] == r[1]) {
                for (Node n : order.classes.get(r[0])) coordByNode.put(n, xi[b]);
                continue;
            }
            // Released block: each class keeps its own target, clipped to half the smaller adjacent gap (minus gMin)
            // around xi_b, which satisfies every cross-block constraint on both sides.
            double left  = (b > 0)     ? xi[b] - xi[b - 1] : Double.POSITIVE_INFINITY;
            double right = (b < m - 1) ? xi[b + 1] - xi[b] : Double.POSITIVE_INFINITY;
            double room  = Math.min(left, right);
            double h = Double.isInfinite(room) ? Double.POSITIVE_INFINITY
                                               : Math.max(0.0, (room - order.gMin) / 2.0);
            for (int i = r[0]; i <= r[1]; i++) {
                double c = Math.max(xi[b] - h, Math.min(xi[b] + h, classTarget[i]));
                for (Node n : order.classes.get(i)) coordByNode.put(n, c);
            }
        }
        double[] out = new double[survivors.size()];
        for (int i = 0; i < survivors.size(); i++) out[i] = coordByNode.get(survivors.get(i));
        return out;
    }

    /** Number of axis-wise survivor pairs more than {@link #orderSlack} apart in the reference whose order flipped. */
    private int countSlackGuaranteeViolations(List<Node> survivors, Map<Node, double[]> refPos) {
        int violations = 0;
        for (int i = 0; i < survivors.size(); i++) {
            Node a = survivors.get(i);
            double[] a0 = refPos.get(a);
            double ax = AttributeHelper.getPositionX(a), ay = AttributeHelper.getPositionY(a);
            for (int j = i + 1; j < survivors.size(); j++) {
                Node b = survivors.get(j);
                double[] b0 = refPos.get(b);
                double dx0 = a0[0] - b0[0], dy0 = a0[1] - b0[1];
                if (Math.abs(dx0) > orderSlack
                        && Math.signum(dx0) != Math.signum(ax - AttributeHelper.getPositionX(b)))
                    violations++;
                if (Math.abs(dy0) > orderSlack
                        && Math.signum(dy0) != Math.signum(ay - AttributeHelper.getPositionY(b)))
                    violations++;
            }
        }
        return violations;
    }

    /**
     * Projection target of a survivor: the unconstrained target, or for a young node a blend from its reference
     * position with weight {@code age / eligibilityAge}, clipped to {@link #blendBudget}.
     */
    private double blendedTarget(Node n, Map<Node, double[]> refPos, Map<Node, double[]> target, int axis) {
        double s = target.get(n)[axis];
        if (!useAging) return s;
        int a = ageOf(n);
        if (a >= eligibilityAge) return s;
        double p = refPos.get(n)[axis];
        double mu = (double) a / eligibilityAge;
        return clip(p, (1 - mu) * p + mu * s);
    }

    private void emitOrderChains(AxisOrder order, int axis) {
        List<List<Node>> classes = order.classes;
        // intra-class alignment (equal coordinate on this axis)
        for (List<Node> cls : classes) {
            if (cls.size() < 2) continue;
            if (axis == 0) {
                addAutomatic(
                        new VerticalConstraint(new ArrayList<>(cls), VerticalConstraint.VerticalConstraintType.MIDDLE));
            } else {
                addAutomatic(
                        new HorizontalConstraint(new ArrayList<>(cls), HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            }
        }
        // Every class of one block is separated from every class of the next (one representative per aligned class),
        // which keeps the order transitive across blocks; classes inside one block stay unordered.
        SeparationConstraintGraph.SeparationConstraintType t = (axis == 0)
                ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                : SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
        for (int b = 0; b + 1 < order.blockCount(); b++) {
            int[] cur = order.blocks.get(b), nxt = order.blocks.get(b + 1);
            for (int i = cur[0]; i <= cur[1]; i++) {
                Node lo = classes.get(i).get(0);
                for (int j = nxt[0]; j <= nxt[1]; j++) {
                    Node hi = classes.get(j).get(0);
                    List<Node> pair = new ArrayList<>(Arrays.asList(lo, hi));
                    addAutomatic(
                            new SeparationConstraintGraph(pair, t, false, order.gMin));
                }
            }
        }
    }

    /** Add a generated (not user-made) constraint to this layout's own store. */
    private void addAutomatic(Constraint c) {
        c.setAutomatic(true);
        store.addConstraint(c);
    }

    /**
     * Number of overlapping rectangle pairs among {@code eligible}, tested without the clearance the separation edges
     * add, so that an edge already in place never counts again. Pairs tied on both axes are skipped: they are aligned
     * twice and cannot be separated.
     */
    private int countNodeOverlaps(List<Node> eligible, AxisOrder orderX, AxisOrder orderY) {
        Map<Node, Integer> clsX = orderX.classIndexByNode();
        Map<Node, Integer> clsY = orderY.classIndexByNode();
        int overlaps = 0;
        for (int i = 0; i < eligible.size(); i++) {
            Node a = eligible.get(i);
            double ax = AttributeHelper.getPositionX(a), ay = AttributeHelper.getPositionY(a);
            for (int j = i + 1; j < eligible.size(); j++) {
                Node b = eligible.get(j);
                if (Objects.equals(clsX.get(a), clsX.get(b))
                        && Objects.equals(clsY.get(a), clsY.get(b))) continue;
                if (overlaps(a, ax, ay, b)) overlaps++;
            }
        }
        return overlaps;
    }

    /** A directed separation between two tie-class variables on one axis. */
    private static final class ClassSeparation {
        final int lo, hi;
        double gap;
        ClassSeparation(int lo, int hi, double gap) {
            this.lo = lo; this.hi = hi; this.gap = gap;
        }
    }

    /**
     * Deterministic Java feasibility projection for the partial block order plus rectangle non-overlap. Each axis is
     * a DAG ordered by (block, guide coordinate, class index) and solved by a longest-path sweep; no native memory is
     * allocated.
     */
    private void repairPartialOrderPostconditions(List<Node> eligible,
                                                  AxisOrder orderX, AxisOrder orderY,
                                                  Map<Node, double[]> guidePositions) {
        double[] baseX = classCoordinates(orderX, 0);
        double[] baseY = classCoordinates(orderY, 1);
        double[] guideX = classCoordinates(orderX, guidePositions, 0);
        double[] guideY = classCoordinates(orderY, guidePositions, 1);
        compactGuideRange(guideX, guideY, eligible);
        normaliseRunawayRange(baseX, guideX);
        normaliseRunawayRange(baseY, guideY);
        Map<Long, ClassSeparation> extraX = new LinkedHashMap<>();
        Map<Long, ClassSeparation> extraY = new LinkedHashMap<>();

        addCompactTotalOrderSeparations(orderX, orderY, guideX, guideY, extraX, extraY);
        solveAxisDag(orderX, 0, baseX, guideX, extraX);
        solveAxisDag(orderY, 1, baseY, guideY, extraY);

        // Defensive fallback: overlaps the compact witness leaves get separation edges, one pass at a time.
        for (int pass = 0; pass < MAX_POSTCONDITION_PASSES; pass++) {
            int overlaps = countNodeOverlaps(eligible, orderX, orderY);
            if (overlaps == 0) return;
            int added = addOverlapSeparations(
                    eligible, orderX, orderY, guideX, guideY, extraX, extraY);
            if (added == 0) {
                throw new IllegalStateException("DAG repair found " + overlaps
                        + " overlap(s), but could add no separation edge");
            }
            solveAxisDag(orderX, 0, baseX, guideX, extraX);
            solveAxisDag(orderY, 1, baseY, guideY, extraY);
        }
    }

    /**
     * Add a compact, order-consistent non-overlap witness: all X tie classes in one topological order with rectangle
     * width gaps, and inside each X class its Y classes in topological Y order. Pairs tied on both axes get no edge.
     */
    private void addCompactTotalOrderSeparations(AxisOrder orderX, AxisOrder orderY,
                                                 double[] guideX, double[] guideY,
                                                 Map<Long, ClassSeparation> extraX,
                                                 Map<Long, ClassSeparation> extraY) {
        List<Integer> xTopo = topologicalClassOrder(orderX, guideX);
        double[] halfWidth = new double[orderX.size()];
        for (int c = 0; c < orderX.size(); c++) {
            for (Node n : orderX.classes.get(c))
                halfWidth[c] = Math.max(halfWidth[c], AttributeHelper.getWidth(n) / 2.0);
        }
        for (int k = 0; k + 1 < xTopo.size(); k++) {
            int lo = xTopo.get(k), hi = xTopo.get(k + 1);
            putClassSeparation(extraX, lo, hi,
                    halfWidth[lo] + halfWidth[hi] + orderTolerance);
        }

        Map<Node, Integer> clsY = orderY.classIndexByNode();
        List<Integer> yTopo = topologicalClassOrder(orderY, guideY);
        int[] yRank = new int[orderY.size()];
        for (int k = 0; k < yTopo.size(); k++) yRank[yTopo.get(k)] = k;

        for (List<Node> xClass : orderX.classes) {
            Map<Integer, Double> halfHeight = new HashMap<>();
            for (Node n : xClass) {
                Integer cy = clsY.get(n);
                if (cy == null) continue;
                double h = AttributeHelper.getHeight(n) / 2.0;
                halfHeight.put(cy, Math.max(halfHeight.getOrDefault(cy, 0.0), h));
            }
            List<Integer> ys = new ArrayList<>(halfHeight.keySet());
            ys.sort(Comparator.comparingInt(c -> yRank[c]));
            for (int k = 0; k + 1 < ys.size(); k++) {
                int lo = ys.get(k), hi = ys.get(k + 1);
                putClassSeparation(extraY, lo, hi,
                        halfHeight.get(lo) + halfHeight.get(hi) + orderTolerance);
            }
        }
    }

    /** Add the edge {@code lo -> hi} or widen an existing one; returns 1 if the edge set changed, else 0. */
    private static int putClassSeparation(Map<Long, ClassSeparation> edges,
                                          int lo, int hi, double gap) {
        long key = classEdgeKey(lo, hi);
        ClassSeparation old = edges.get(key);
        if (old == null) {
            edges.put(key, new ClassSeparation(lo, hi, gap));
            return 1;
        }
        if (gap > old.gap + 1e-9) {
            old.gap = gap;
            return 1;
        }
        return 0;
    }

    private static List<Integer> topologicalClassOrder(AxisOrder order, double[] guide) {
        List<Integer> topo = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) topo.add(i);
        topo.sort((a, b) -> {
            int c = Integer.compare(order.blockOf[a], order.blockOf[b]);
            if (c != 0) return c;
            c = Double.compare(guide[a], guide[b]);
            return (c != 0) ? c : Integer.compare(a, b);
        });
        return topo;
    }

    private static long classEdgeKey(int lo, int hi) {
        return ((long) lo << 32) ^ (hi & 0xffffffffL);
    }

    /** Mean current coordinate per tie class. */
    private double[] classCoordinates(AxisOrder order, int axis) {
        double[] out = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            double sum = 0;
            for (Node n : order.classes.get(i))
                sum += (axis == 0) ? AttributeHelper.getPositionX(n) : AttributeHelper.getPositionY(n);
            out[i] = sum / order.classes.get(i).size();
        }
        return out;
    }

    /** Mean coordinate per tie class in {@code positions}. */
    private double[] classCoordinates(AxisOrder order, Map<Node, double[]> positions, int axis) {
        double[] out = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            double sum = 0;
            for (Node n : order.classes.get(i)) sum += positions.get(n)[axis];
            out[i] = sum / order.classes.get(i).size();
        }
        return out;
    }

    /** Preserve native relative geometry but do not let an already-runaway extent become the DAG target. */
    private static void normaliseRunawayRange(double[] base, double[] guide) {
        if (base.length < 2) return;
        double bMin = Double.POSITIVE_INFINITY, bMax = Double.NEGATIVE_INFINITY;
        double gMin = Double.POSITIVE_INFINITY, gMax = Double.NEGATIVE_INFINITY;
        for (double v : base) { bMin = Math.min(bMin, v); bMax = Math.max(bMax, v); }
        for (double v : guide) { gMin = Math.min(gMin, v); gMax = Math.max(gMax, v); }
        double bRange = bMax - bMin, gRange = gMax - gMin;
        if (!(bRange > 0) || !(gRange > 0) || bRange <= gRange) return;
        double scale = gRange / bRange;
        double bMid = (bMin + bMax) / 2.0, gMid = (gMin + gMax) / 2.0;
        for (int i = 0; i < base.length; i++) base[i] = gMid + (base[i] - bMid) * scale;
    }

    /**
     * Cap the range of both guides at a packing scale (2 sqrt(n) times the mean node diameter, at least 100), so that
     * a large previous step does not become the geometric unit of the next one.
     */
    private static void compactGuideRange(double[] guideX, double[] guideY, List<Node> nodes) {
        if (nodes.isEmpty()) return;
        double meanDiameter = 0;
        for (Node n : nodes)
            meanDiameter += Math.max(AttributeHelper.getWidth(n), AttributeHelper.getHeight(n));
        meanDiameter /= nodes.size();
        double limit = Math.max(100.0, 2.0 * Math.sqrt(nodes.size()) * meanDiameter);
        capRange(guideX, limit);
        capRange(guideY, limit);
    }

    private static void capRange(double[] values, double limit) {
        if (values.length < 2) return;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        double range = max - min;
        if (!(range > limit)) return;
        double mid = (min + max) / 2.0, scale = limit / range;
        for (int i = 0; i < values.length; i++) values[i] = mid + (values[i] - mid) * scale;
    }

    /**
     * Return newcomers that lie outside a guard band around the survivors to a free spot near their settled
     * neighbours: newcomers are not part of the order DAG, so a runaway newcomer alone could define the canvas.
     */
    private void repairRunawayNewcomers(Graph live, List<Node> survivors) {
        if (survivors.isEmpty()) return;
        Set<Node> survivorSet = new HashSet<>(survivors);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double meanDiameter = 0;
        for (Node n : live.getNodes())
            meanDiameter += Math.max(AttributeHelper.getWidth(n), AttributeHelper.getHeight(n));
        meanDiameter /= Math.max(1, live.getNumberOfNodes());
        for (Node n : survivors) {
            minX = Math.min(minX, AttributeHelper.getPositionX(n) - AttributeHelper.getWidth(n) / 2.0);
            maxX = Math.max(maxX, AttributeHelper.getPositionX(n) + AttributeHelper.getWidth(n) / 2.0);
            minY = Math.min(minY, AttributeHelper.getPositionY(n) - AttributeHelper.getHeight(n) / 2.0);
            maxY = Math.max(maxY, AttributeHelper.getPositionY(n) + AttributeHelper.getHeight(n) / 2.0);
        }
        double guard = Math.max(100.0, Math.sqrt(live.getNumberOfNodes()) * meanDiameter);
        double loX = minX - guard, hiX = maxX + guard;
        double loY = minY - guard, hiY = maxY + guard;

        List<Node> settled = new ArrayList<>(survivors);
        for (Node n : live.getNodes()) {
            if (survivorSet.contains(n)) continue;
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            if (Double.isFinite(x) && Double.isFinite(y)
                    && x >= loX && x <= hiX && y >= loY && y <= hiY) {
                settled.add(n);
                continue;
            }

            double sx = 0, sy = 0; int neighbours = 0;
            for (Node nb : n.getNeighbors()) {
                if (!settled.contains(nb)) continue;
                sx += AttributeHelper.getPositionX(nb);
                sy += AttributeHelper.getPositionY(nb);
                neighbours++;
            }
            double cx = (neighbours > 0) ? sx / neighbours : (minX + maxX) / 2.0;
            double cy = (neighbours > 0) ? sy / neighbours : (minY + maxY) / 2.0;
            cx = Math.max(loX, Math.min(hiX, cx));
            cy = Math.max(loY, Math.min(hiY, cy));

            double step = Math.max(meanDiameter,
                    Math.max(AttributeHelper.getWidth(n), AttributeHelper.getHeight(n))) + orderTolerance;
            boolean placed = false;
            int maxRing = 2 * (int) Math.ceil(Math.sqrt(live.getNumberOfNodes())) + 4;
            for (int ring = 0; ring <= maxRing && !placed; ring++) {
                for (int dx = -ring; dx <= ring && !placed; dx++) {
                    for (int dy = -ring; dy <= ring && !placed; dy++) {
                        if (ring > 0 && Math.abs(dx) != ring && Math.abs(dy) != ring) continue;
                        double px = cx + dx * step, py = cy + dy * step;
                        if (px < loX || px > hiX || py < loY || py > hiY) continue;
                        if (!overlapsAny(n, px, py, settled)) {
                            AttributeHelper.setPosition(n, px, py);
                            placed = true;
                        }
                    }
                }
            }
            if (!placed) AttributeHelper.setPosition(n, cx, cy); // bounded soft fallback
            settled.add(n);
        }
    }

    private static boolean overlapsAny(Node node, double x, double y, List<Node> others) {
        for (Node other : others) {
            if (overlaps(node, x, y, other)) return true;
        }
        return false;
    }

    /** True if the rectangle of {@code a}, centred at ({@code ax}, {@code ay}), overlaps the rectangle of {@code b}. */
    private static boolean overlaps(Node a, double ax, double ay, Node b) {
        double reqX = (AttributeHelper.getWidth(a) + AttributeHelper.getWidth(b)) / 2.0;
        double reqY = (AttributeHelper.getHeight(a) + AttributeHelper.getHeight(b)) / 2.0;
        return Math.abs(AttributeHelper.getPositionX(b) - ax) < reqX
                && Math.abs(AttributeHelper.getPositionY(b) - ay) < reqY;
    }

    /**
     * For every overlapping pair of eligible survivors, add a separation on the admissible axis that is cheaper in the
     * guide geometry, oriented by block order (inside a block by guide order); returns the number of changed edges.
     */
    private int addOverlapSeparations(List<Node> eligible, AxisOrder orderX, AxisOrder orderY,
                                      double[] guideX, double[] guideY,
                                      Map<Long, ClassSeparation> extraX,
                                      Map<Long, ClassSeparation> extraY) {
        List<Node> nodes = new ArrayList<>(eligible);
        nodes.sort(Comparator.comparing(ImprovedMentalMapLayout::keyOf));
        Map<Node, Integer> clsX = orderX.classIndexByNode();
        Map<Node, Integer> clsY = orderY.classIndexByNode();
        int added = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Node a = nodes.get(i);
            if (!clsX.containsKey(a) || !clsY.containsKey(a)) continue;
            for (int j = i + 1; j < nodes.size(); j++) {
                Node b = nodes.get(j);
                if (!clsX.containsKey(b) || !clsY.containsKey(b)) continue;
                int axClass = clsX.get(a), bxClass = clsX.get(b);
                int ayClass = clsY.get(a), byClass = clsY.get(b);
                boolean xAdmissible = axClass != bxClass, yAdmissible = ayClass != byClass;
                if (!xAdmissible && !yAdmissible) continue;

                double ax = AttributeHelper.getPositionX(a), ay = AttributeHelper.getPositionY(a);
                double bx = AttributeHelper.getPositionX(b), by = AttributeHelper.getPositionY(b);
                double reqX = (AttributeHelper.getWidth(a) + AttributeHelper.getWidth(b)) / 2.0
                        + orderTolerance;
                double reqY = (AttributeHelper.getHeight(a) + AttributeHelper.getHeight(b)) / 2.0
                        + orderTolerance;
                double needX = xAdmissible ? reqX - Math.abs(bx - ax) : Double.POSITIVE_INFINITY;
                double needY = yAdmissible ? reqY - Math.abs(by - ay) : Double.POSITIVE_INFINITY;
                if (needX <= 0.0 || needY <= 0.0) continue;
                double guideCostX = xAdmissible
                        ? reqX - Math.abs(guideX[axClass] - guideX[bxClass])
                        : Double.POSITIVE_INFINITY;
                double guideCostY = yAdmissible
                        ? reqY - Math.abs(guideY[ayClass] - guideY[byClass])
                        : Double.POSITIVE_INFINITY;
                int axis = (guideCostX < guideCostY
                        || (guideCostX == guideCostY && Math.abs(bx - ax) >= Math.abs(by - ay)))
                        ? 0 : 1;
                AxisOrder order = (axis == 0) ? orderX : orderY;
                double[] guide = (axis == 0) ? guideX : guideY;
                Map<Long, ClassSeparation> extra = (axis == 0) ? extraX : extraY;
                int ca = (axis == 0) ? axClass : ayClass;
                int cb = (axis == 0) ? bxClass : byClass;
                int ba = order.blockOf[ca], bb = order.blockOf[cb];
                boolean aFirst = (ba != bb) ? ba < bb
                        : (guide[ca] < guide[cb] || (guide[ca] == guide[cb] && ca < cb));
                int lo = aFirst ? ca : cb, hi = aFirst ? cb : ca;
                double gap = (axis == 0) ? reqX : reqY;
                added += putClassSeparation(extra, lo, hi, gap);
            }
        }
        return added;
    }

    /**
     * Longest-path sweep over the block chain plus {@code extra} edges in topological order, followed by the weighted
     * mean shift back to {@code base}; writes the class coordinates to the nodes.
     */
    private void solveAxisDag(AxisOrder order, int axis, double[] base, double[] guide,
                              Map<Long, ClassSeparation> extra) {
        int p = order.size();
        List<List<ClassSeparation>> outgoing = new ArrayList<>();
        for (int i = 0; i < p; i++) outgoing.add(new ArrayList<>());
        for (int b = 0; b + 1 < order.blockCount(); b++) {
            int[] lo = order.blocks.get(b), hi = order.blocks.get(b + 1);
            for (int i = lo[0]; i <= lo[1]; i++)
                for (int j = hi[0]; j <= hi[1]; j++)
                    outgoing.get(i).add(new ClassSeparation(i, j, order.gMin));
        }
        for (ClassSeparation e : extra.values()) outgoing.get(e.lo).add(e);

        List<Integer> topo = topologicalClassOrder(order, guide);
        int[] rank = new int[p];
        for (int i = 0; i < p; i++) rank[topo.get(i)] = i;

        double[] coord = base.clone();
        for (int u : topo) {
            for (ClassSeparation e : outgoing.get(u)) {
                if (rank[e.lo] >= rank[e.hi])
                    throw new IllegalStateException("Non-overlap edge contradicts block DAG");
                coord[e.hi] = Math.max(coord[e.hi], coord[e.lo] + e.gap);
            }
        }
        double shift = 0, weight = 0;
        for (int i = 0; i < p; i++) {
            double w = order.classes.get(i).size();
            shift += w * (coord[i] - base[i]);
            weight += w;
        }
        shift = (weight == 0) ? 0 : shift / weight;
        for (int i = 0; i < p; i++) {
            double c = coord[i] - shift;
            for (Node n : order.classes.get(i)) {
                if (axis == 0)
                    AttributeHelper.setPosition(n, c, AttributeHelper.getPositionY(n));
                else
                    AttributeHelper.setPosition(n, AttributeHelper.getPositionX(n), c);
            }
        }
    }

    // =================================================================
    // Clustering: boundary MST, Zahn pruning with hysteresis, region filter
    // =================================================================

    /**
     * Cluster the eligible nodes, set {@link CBLayoutAlgorithm#allCluster} and return the representative threshold
     * delta (the mean MST edge weight). The block indices are the order the chains enforce, used by the region filter.
     */
    private double buildAndSetClusters(List<Node> eligible, Map<Node, double[]> refPos,
                                       Map<Node, Integer> blkX, Map<Node, Integer> blkY) {
        CBLayoutAlgorithm.allCluster = new ArrayList<>();
        if (eligible.size() < 2) return defaultDelta;

        // Boxes from reference positions and live sizes; an eligible newcomer (immediate admission) has no reference
        // position and uses its current one.
        Map<Node, double[]> box = new LinkedHashMap<>();
        for (Node n : eligible) {
            double[] rp = refPos.get(n);
            double px = (rp != null) ? rp[0] : AttributeHelper.getPositionX(n);
            double py = (rp != null) ? rp[1] : AttributeHelper.getPositionY(n);
            box.put(n, new double[]{ px, py, AttributeHelper.getWidth(n), AttributeHelper.getHeight(n) });
        }

        List<BoundaryMST.WeightedEdge> mst = BoundaryMST.computeMST(eligible, box);
        if (mst.isEmpty()) return defaultDelta;

        double delta = 0;
        for (BoundaryMST.WeightedEdge e : mst) delta += e.w;
        delta /= mst.size();
        if (delta <= 0) delta = defaultDelta;

        List<BoundaryMST.WeightedEdge> kept = zahnPruneWithHysteresis(mst, eligible, delta);
        List<List<Node>> clusters = components(eligible, kept);
        if (useRegionFilter) {
            clusters = regionCompatibilityFilter(eligible, kept, box, blkX, blkY);
        }

        recordClustering(clusters);

        for (List<Node> c : clusters) {
            if (c.size() > 1) CBLayoutAlgorithm.allCluster.add(new ArrayList<>(c));
        }
        return delta;
    }

    /**
     * Zahn's local inconsistent-edge pruning: drop an MST edge whose weight exceeds {@code c} times the median weight
     * of the MST edges within {@code h} hops, with an asymmetric hysteresis band against the previous clustering.
     */
    private List<BoundaryMST.WeightedEdge> zahnPruneWithHysteresis(
            List<BoundaryMST.WeightedEdge> mst, List<Node> nodes, double delta) {
        Map<Node, List<Integer>> incident = new LinkedHashMap<>();
        for (Node n : nodes) incident.put(n, new ArrayList<>());
        for (int i = 0; i < mst.size(); i++) {
            incident.get(mst.get(i).u).add(i);
            incident.get(mst.get(i).v).add(i);
        }

        double band = useHysteresis ? hysteresisFraction * delta : 0.0;
        List<BoundaryMST.WeightedEdge> kept = new ArrayList<>();
        for (int i = 0; i < mst.size(); i++) {
            BoundaryMST.WeightedEdge e = mst.get(i);
            double theta = zahnFactor * localMedian(mst, incident, i);

            boolean wereTogether = useHysteresis && sameClusterPreviously(e.u, e.v);
            boolean keep;
            if (wereTogether) {
                keep = e.w <= theta + band;      // split only if clearly above
            } else if (useHysteresis && knownPreviously(e.u) && knownPreviously(e.v)) {
                keep = e.w <  theta - band;       // merge only if clearly below
            } else {
                keep = e.w <= theta;              // first sighting: plain criterion
            }
            if (keep) kept.add(e);
        }
        return kept;
    }

    /** Median weight of the MST edges within {@link #zahnHops} hops of edge {@code edgeIdx}. */
    private double localMedian(List<BoundaryMST.WeightedEdge> mst,
                               Map<Node, List<Integer>> incident, int edgeIdx) {
        Set<Integer> seen = new HashSet<>();
        seen.add(edgeIdx);
        List<Integer> frontier = new ArrayList<>();
        frontier.add(edgeIdx);
        for (int hop = 0; hop < zahnHops; hop++) {
            List<Integer> next = new ArrayList<>();
            for (int ei : frontier) {
                BoundaryMST.WeightedEdge e = mst.get(ei);
                for (Node end : new Node[]{ e.u, e.v }) {
                    for (int nb : incident.get(end)) {
                        if (seen.add(nb)) next.add(nb);
                    }
                }
            }
            frontier = next;
        }
        double[] ws = new double[seen.size()];
        int j = 0;
        for (int ei : seen) ws[j++] = mst.get(ei).w;
        Arrays.sort(ws);
        int m = ws.length;
        return (m % 2 == 1) ? ws[m / 2] : (ws[m / 2 - 1] + ws[m / 2]) / 2.0;
    }

    private boolean knownPreviously(Node n) {
        return prevClusterByKey.containsKey(keyOf(n));
    }

    private boolean sameClusterPreviously(Node a, Node b) {
        Integer ca = prevClusterByKey.get(keyOf(a));
        Integer cb = prevClusterByKey.get(keyOf(b));
        return ca != null && ca.equals(cb);
    }

    private void recordClustering(List<List<Node>> clusters) {
        prevClusterByKey.clear();
        int id = 0;
        for (List<Node> c : clusters) {
            for (Node n : c) prevClusterByKey.put(keyOf(n), id);
            id++;
        }
    }

    /** Connected components of the forest given by {@code edges} over {@code nodes}. */
    private static List<List<Node>> components(List<Node> nodes, List<BoundaryMST.WeightedEdge> edges) {
        Map<Node, List<Node>> adj = new LinkedHashMap<>();
        for (Node n : nodes) adj.put(n, new ArrayList<>());
        for (BoundaryMST.WeightedEdge e : edges) {
            adj.get(e.u).add(e.v);
            adj.get(e.v).add(e.u);
        }
        Set<Node> visited = new LinkedHashSet<>();
        List<List<Node>> comps = new ArrayList<>();
        for (Node start : nodes) {
            if (!visited.add(start)) continue;
            List<Node> comp = new ArrayList<>();
            ArrayDeque<Node> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                Node u = q.poll();
                comp.add(u);
                for (Node v : adj.get(u)) if (visited.add(v)) q.add(v);
            }
            comps.add(comp);
        }
        return comps;
    }

    /**
     * Region-compatibility filter: while two clusters, not both singletons, interleave (their index intervals overlap
     * on both axes, the only way a rectangular region system becomes infeasible), split the one whose heaviest
     * internal MST edge is heavier at that edge. Returns a region-compatible clustering.
     */
    private List<List<Node>> regionCompatibilityFilter(
            List<Node> eligible, List<BoundaryMST.WeightedEdge> kept, Map<Node, double[]> box,
            Map<Node, Integer> blkX, Map<Node, Integer> blkY) {
        Map<Node, Integer> clsX = enforcedIndices(eligible, box, 0, blkX);
        Map<Node, Integer> clsY = enforcedIndices(eligible, box, 1, blkY);

        List<BoundaryMST.WeightedEdge> forest = new ArrayList<>(kept);
        int guard = 0, maxSplits = eligible.size(); // at most n-1 splits
        while (guard++ <= maxSplits) {
            List<List<Node>> comps = components(eligible, forest);
            int m = comps.size();
            int[][] iv = new int[m][];
            for (int i = 0; i < m; i++) iv[i] = classInterval(comps.get(i), clsX, clsY);

            List<Node> toSplit = null;
            outer:
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    List<Node> ci = comps.get(i), cj = comps.get(j);
                    if (ci.size() < 2 && cj.size() < 2) continue;          // exempt
                    if (!(iv[i][0] <= iv[j][1] && iv[j][0] <= iv[i][1])) continue; // disjoint in x
                    if (!(iv[i][2] <= iv[j][3] && iv[j][2] <= iv[i][3])) continue; // disjoint in y
                    // Interleaved: split the member whose heaviest internal edge is heavier, the pair's weakest
                    // internal link, so the cut costs the least cohesion.
                    int ei = heaviestInternalEdge(ci, forest);
                    int ej = heaviestInternalEdge(cj, forest);
                    double wi = (ei < 0) ? Double.NEGATIVE_INFINITY : forest.get(ei).w;
                    double wj = (ej < 0) ? Double.NEGATIVE_INFINITY : forest.get(ej).w;
                    toSplit = (wi >= wj) ? ci : cj;
                    break outer;
                }
            }
            if (toSplit == null) return components(eligible, forest); // region-compatible

            int heaviest = heaviestInternalEdge(toSplit, forest);
            if (heaviest < 0) {
                // Never hand a compatibility violation to the native solver: libcola projecting an infeasible
                // hierarchy may not return.
                throw new IllegalStateException(
                        "region filter found an interleaved component with no splittable MST edge");
            }
            forest.remove(heaviest);
        }
        throw new IllegalStateException("region filter exceeded its finite MST split bound");
    }

    /**
     * Index per node in the order the chain enforces: in box-coordinate order (ties broken by key) the index advances
     * only where the chain block changes, because inside a block the order is released and may change. Nodes without
     * a block (newcomers under immediate admission) share the preceding index, so they never make two clusters
     * look disjoint.
     */
    private Map<Node, Integer> enforcedIndices(List<Node> nodes, Map<Node, double[]> box, int axis,
                                               Map<Node, Integer> chainBlock) {
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble((Node n) -> box.get(n)[axis])
                .thenComparing(ImprovedMentalMapLayout::keyOf));
        Map<Node, Integer> idx = new LinkedHashMap<>();
        int c = -1;
        Integer prevBlock = null;
        for (Node n : sorted) {
            Integer b = chainBlock.get(n);
            if (c < 0) c = 0;
            else if (b != null && prevBlock != null && !b.equals(prevBlock)) c++;
            idx.put(n, c);
            if (b != null) prevBlock = b;
        }
        return idx;
    }

    /** {@code {xLo, xHi, yLo, yHi}}: the cluster's index interval on each axis. */
    private static int[] classInterval(List<Node> cluster,
                                       Map<Node, Integer> clsX, Map<Node, Integer> clsY) {
        int xLo = Integer.MAX_VALUE, xHi = Integer.MIN_VALUE;
        int yLo = Integer.MAX_VALUE, yHi = Integer.MIN_VALUE;
        for (Node n : cluster) {
            int cx = clsX.get(n), cy = clsY.get(n);
            xLo = Math.min(xLo, cx); xHi = Math.max(xHi, cx);
            yLo = Math.min(yLo, cy); yHi = Math.max(yHi, cy);
        }
        return new int[]{ xLo, xHi, yLo, yHi };
    }

    /** Index in {@code forest} of the heaviest edge internal to {@code cluster}, or -1. */
    private static int heaviestInternalEdge(List<Node> cluster,
                                            List<BoundaryMST.WeightedEdge> forest) {
        Set<Node> set = new HashSet<>(cluster);
        int best = -1;
        double bw = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < forest.size(); k++) {
            BoundaryMST.WeightedEdge e = forest.get(k);
            if (set.contains(e.u) && set.contains(e.v) && e.w > bw) { bw = e.w; best = k; }
        }
        return best;
    }

    // =================================================================
    // Position helpers
    // =================================================================

    /** Survivors at their reference positions, newcomers at the centroid of their surviving neighbours (or of all). */
    private void seedPositions(Graph live, Map<Node, double[]> refPos) {
        for (Map.Entry<Node, double[]> e : refPos.entrySet()) {
            AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        double[] centroid = centroid(refPos.values());
        for (Node n : live.getNodes()) {
            if (refPos.containsKey(n)) continue;
            double[] seed = neighbourCentroid(n, refPos);
            if (seed == null) seed = centroid;
            AttributeHelper.setPosition(n, seed[0], seed[1]);
        }
    }

    private static double[] centroid(Collection<double[]> pts) {
        double sx = 0, sy = 0; int n = 0;
        for (double[] p : pts) { sx += p[0]; sy += p[1]; n++; }
        return (n == 0) ? new double[]{0, 0} : new double[]{ sx / n, sy / n };
    }

    private static double[] neighbourCentroid(Node n, Map<Node, double[]> refPos) {
        double sx = 0, sy = 0; int c = 0;
        for (Node nb : n.getNeighbors()) {
            double[] p = refPos.get(nb);
            if (p != null) { sx += p[0]; sy += p[1]; c++; }
        }
        return (c == 0) ? null : new double[]{ sx / c, sy / c };
    }

    private static Map<Node, double[]> currentPositions(Graph live) {
        Map<Node, double[]> m = new LinkedHashMap<>();
        for (Node n : live.getNodes()) {
            m.put(n, new double[]{ AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n) });
        }
        return m;
    }

    private void applyPositions(Graph live, List<Node> survivors,
                                double[] projX, double[] projY,
                                Map<Node, double[]> target, Map<Node, double[]> refPos) {
        for (int i = 0; i < survivors.size(); i++) {
            AttributeHelper.setPosition(survivors.get(i), projX[i], projY[i]);
        }
        // newcomers: aged blend from their seed toward the unconstrained target
        for (Node n : live.getNodes()) {
            if (refPos.containsKey(n)) continue;
            double[] t = target.get(n);
            if (!useAging) {
                AttributeHelper.setPosition(n, t[0], t[1]);
                continue;
            }
            int a = ageOf(n);
            double mu = Math.min(1.0, (double) a / eligibilityAge);
            double[] seed = neighbourCentroid(n, refPos);
            if (seed == null) seed = centroid(refPos.values());
            double bx = clip(seed[0], (1 - mu) * seed[0] + mu * t[0]);
            double by = clip(seed[1], (1 - mu) * seed[1] + mu * t[1]);
            AttributeHelper.setPosition(n, bx, by);
        }
    }

    /** {@code to}, moved back to within {@link #blendBudget} of {@code from}. */
    private double clip(double from, double to) {
        double d = to - from;
        if (d > blendBudget) return from + blendBudget;
        if (d < -blendBudget) return from - blendBudget;
        return to;
    }
}
