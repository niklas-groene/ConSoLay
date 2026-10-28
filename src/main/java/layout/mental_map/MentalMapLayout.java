package layout.mental_map;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.SeparationConstraintGraph;
import layout.sbgn.ConstraintSBGNLayout;
import org.AttributeHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Mental-map preserving layout: derives relative-ordering constraints and neighbourhood clusters from a reference
 * {@link Snapshot} and re-lays out the live graph with the constraint-based force-directed engine of
 * {@link CBLayoutAlgorithm}. The derived constraints live in this instance's own {@link Constraints} store, never in
 * the user's {@link ConstraintStore#constraints}; in stable mode ({@link #stableExtent}) the drawing keeps its area
 * per node when the layout is chained over time steps.
 *
 * @author Niklas Gröne
 */
public class MentalMapLayout {

    /** The derived ordering constraints of the last run (rebuilt on every run). */
    private final Constraints store = new Constraints();
    /** Copy of what the last run applied, for display. */
    private DerivedConstraints derived = DerivedConstraints.NONE;

    /** Reference graph copy (carries the reference positions and topology). */
    private Graph g;
    /** Reference (copy) node -&gt; live node. */
    private Map<Node, Node> forwardMapNodes;

    /** Reference nodes without a live counterpart; they take part in neither ordering nor clustering. */
    private List<Node> ignoreNodesOrdering;
    /** Reference nodes that still have a live counterpart; the domain of the clustering. */
    private List<Node> survivingRefNodes;

    private List<List<Node>> clusters;

    /**
     * -Dmentalmap.stableExtent=true|false (default true): true keeps the drawing's area per node over chained steps
     * (reduced order gaps, anchors, seeded inserted nodes, merged clusters, an unchanged graph only projected, drift
     * removal); false keeps the full reference gaps and runs the unanchored layout without these.
     */
    public static boolean stableExtent = !"false".equalsIgnoreCase(System.getProperty("mentalmap.stableExtent", "true"));
    /**
     * Smallest gap of a relative-ordering constraint in stable mode (px): a gap the native solver can still tell from
     * equality, the value {@link ImprovedMentalMapLayout#orderTolerance} uses for the same reason.
     */
    private static final double ORDER_GAP_MIN = 0.01;
    /**
     * -Dmentalmap.orderGapFraction (default 0.5; 1 = the full gap): share of each pair's reference gap that an ordering
     * constraint keeps in stable mode, see {@link #stableOrderGap}.
     */
    public static double orderGapFraction = ConstraintSBGNLayout.doubleProperty("mentalmap.orderGapFraction", 0.5);
    /**
     * -Dmentalmap.orderGapFloor (default 5 px; 0 = no floor): smallest gap {@link #orderGapFraction} may shrink an
     * ordering pair to in stable mode; a pair whose reference gap is smaller keeps its reference gap.
     */
    public static double orderGapFloor = ConstraintSBGNLayout.doubleProperty("mentalmap.orderGapFloor", 5);
    /**
     * -Dmentalmap.anchorWeight (default 2e-2, in libcola's stress units): weight of the survivors' anchors in stable
     * mode; larger keeps survivors closer to where they were, smaller lets the force-directed pass rearrange them.
     */
    public static double anchorWeight = ConstraintSBGNLayout.doubleProperty("mentalmap.anchorWeight", 2e-2);
    /**
     * -Dmentalmap.newcomerAnchor (default 0.3; 0 = inserted nodes are free): anchor weight of an inserted node at its
     * seeded position, relative to {@link #anchorWeight}.
     */
    public static double newcomerAnchor = ConstraintSBGNLayout.doubleProperty("mentalmap.newcomerAnchor", 0.3);
    /**
     * -Dmentalmap.reclaimMin (default 0.9): smallest contraction of the survivors' anchor targets in one step when the
     * graph shrinks, see {@link #collectAnchors(Graph, Map, Map, boolean)}.
     */
    public static double reclaimMin = ConstraintSBGNLayout.doubleProperty("mentalmap.reclaimMin", 0.9);
    /**
     * -Dmentalmap.reclaimDensity (default 3.5; 0 = no floor): density below which the survivors' anchor targets are
     * never contracted, the bounding-box area of the survivors' solver rectangles (size + 2
     * {@link CBLayoutAlgorithm#bboxConst}) over the summed solver-rectangle area of all live nodes.
     */
    public static double reclaimDensity = ConstraintSBGNLayout.doubleProperty("mentalmap.reclaimDensity", 3.5);
    /**
     * -Dmentalmap.driftCompensation (default 0.001; 0 = off): contraction of the anchor targets on every changed step
     * against the outward drift of the force-directed pass, bounded by the {@link #reclaimDensity} floor.
     */
    public static double driftCompensation = ConstraintSBGNLayout.doubleProperty("mentalmap.driftCompensation", 0.001);
    /** Ring spacing of the free-spot search for inserted nodes, as a fraction of the ideal edge length. */
    private static final double SEED_RING_STEP = 0.25;
    /**
     * Largest ring count of the seed search ({@link #seedNewcomers(Graph)}), a guard against degenerate sizes and
     * coordinates only: 2000 rings reach 50 000 px at the default ideal edge length.
     */
    private static final int SEED_RING_GUARD = 2000;
    /**
     * -Dmentalmap.seedRadius (default 1.5; 0 = unbounded free-spot search): reach of the seed search beyond the padded
     * boundary of an inserted node's placed neighbours, in ideal edge lengths; it also bounds how far from its
     * neighbours' cluster an inserted node may start and still join it.
     */
    public static double seedRadius = ConstraintSBGNLayout.doubleProperty("mentalmap.seedRadius", 1.5);
    /**
     * -Dmentalmap.seedOverlapMax (default 15 px; 0 = never): largest overlap the seed search accepts for an inserted
     * node next to an overlapping neighbourhood when no spot within {@link #seedRadius} is free.
     */
    public static double seedOverlapMax = ConstraintSBGNLayout.doubleProperty("mentalmap.seedOverlapMax", 15);

    /** Mode of the current run (set by every run). */
    private boolean stable;

    /**
     * Sort key of a node: its label, or {@code "id#" + id} for an unlabelled node (the id follows the creation order,
     * so it is only the fallback). Every derived order (ordering lists, MST, components, clusters) is fixed by
     * {@link #BY_KEY}, since the iteration order of {@code g.getNodes()} is not guaranteed to be the same between JVM
     * runs.
     */
    static String keyOf(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        return (l == null || l.isEmpty()) ? ("id#" + n.getID()) : l;
    }

    /**
     * Comparator on the sort key, nodes with the same label (frequent in SBGN maps) by their uid; the final tie-break
     * behind every other criterion.
     */
    static final Comparator<Node> BY_KEY = Comparator.comparing(MentalMapLayout::keyOf).thenComparing(Snapshot::uidOf);

    /** Comparator on the first members of node lists (components, clusters), see {@link #BY_KEY}. */
    private static final Comparator<List<Node>> BY_FIRST = (a, b) -> BY_KEY.compare(a.get(0), b.get(0));

    /**
     * Derives mental-map constraints from {@code reference} and re-lays out the live graph so that the relative
     * ordering and neighbourhood structure of the reference are preserved; {@code stableExtent} selects the mode
     * (see {@link #stableExtent}). Does nothing without a reference.
     */
    public void runMentalMapLayout(Graph live, Snapshot reference, boolean stableExtent) {
        this.stable = stableExtent;
        derived = DerivedConstraints.NONE;
        if (reference == null) {
            return;
        }
        this.g = reference.referenceGraph;
        this.forwardMapNodes = reference.mapToLive(live);

        store.clear();

        exclusionList(live);
        addOrderingConstraints(Comparator.comparingDouble(AttributeHelper::getPositionX)
                        .thenComparingDouble(AttributeHelper::getPositionY),
                SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL);
        addOrderingConstraints(Comparator.comparingDouble(AttributeHelper::getPositionY)
                        .thenComparingDouble(AttributeHelper::getPositionX),
                SeparationConstraintGraph.SeparationConstraintType.VERTICAL);

        computeClusters();
        if (stable) mergeClustersOverlappingInReference();
        addAdaptagramsClusters();

        boolean previousMode = CBLayoutAlgorithm.mentalMapMode;
        try {
            CBLayoutAlgorithm.mentalMapMode = true;
            if (stable) {
                seedNewcomers(live);
                addNewcomersToNeighbourClusters(live);   // after the seeding: joins are decided at the seeds
                // Insertion-ordered maps: the engine reads them per node in its own stable order anyway.
                Map<Node, java.awt.geom.Point2D> anchors = new LinkedHashMap<>();
                Map<Node, Double> weights = new LinkedHashMap<>();
                boolean runForces = !graphUnchanged(live);
                collectAnchors(live, anchors, weights, runForces);
                CBLayoutAlgorithm.executeMentalMap(live, store, false, anchors, weights, runForces);
                removeDrift(live);
            } else {
                CBLayoutAlgorithm.executeMentalMap(live, store);
            }
            derived = DerivedConstraints.capture(store);
        } finally {
            CBLayoutAlgorithm.mentalMapMode = previousMode;
        }
    }

    /** What the last run applied: its constraints and clusters (empty before the first run or after a failed one). */
    public DerivedConstraints derived() {
        return derived;
    }

    /** Drops every reference to the graphs of the last run. */
    void release() {
        store.clear();
        derived = DerivedConstraints.NONE;
        g = null;
        forwardMapNodes = null;
        ignoreNodesOrdering = null;
        survivingRefNodes = null;
        clusters = null;
    }

    /** Splits the reference nodes into those without a live counterpart and the survivors. */
    private void exclusionList(Graph live) {
        ignoreNodesOrdering = new ArrayList<>();
        survivingRefNodes = new ArrayList<>();
        Set<Node> liveNodes = new HashSet<>(live.getNodes());
        for (Node n : g.getNodes()) {
            if (!liveNodes.contains(forwardMapNodes.get(n))) {
                ignoreNodesOrdering.add(n);
            } else {
                survivingRefNodes.add(n);
            }
        }
        // Prim breaks a tie between two equally long edges by index, so the clustering domain needs a fixed order.
        survivingRefNodes.sort(BY_KEY);
    }

    /**
     * Relative ordering along one axis: the surviving reference nodes sorted by {@code order}, and a separation
     * constraint between the live counterparts of every two consecutive ones.
     */
    private void addOrderingConstraints(Comparator<Node> order,
                                        SeparationConstraintGraph.SeparationConstraintType type) {
        List<Node> ordering = new ArrayList<>(g.getNodes());
        ordering.removeAll(ignoreNodesOrdering);
        // BY_KEY last: the sort is stable, so two nodes at the same position would keep the graph's order.
        ordering.sort(order.thenComparing(BY_KEY));
        for (int i = 0; i < ordering.size() - 1; i++) {
            List<Node> ids = new ArrayList<>();
            ids.add(forwardMapNodes.get(ordering.get(i)));
            ids.add(forwardMapNodes.get(ordering.get(i + 1)));
            addAutomatic(orderingConstraint(ids, type));
        }
    }

    /**
     * One relative-ordering constraint between two consecutive live nodes. The reference gap is read off the live nodes
     * (which the runner has just reset to their reference positions); stable mode reduces it to
     * {@link #stableOrderGap}.
     */
    private SeparationConstraintGraph orderingConstraint(List<Node> ids,
                                                         SeparationConstraintGraph.SeparationConstraintType type) {
        SeparationConstraintGraph full = new SeparationConstraintGraph(ids, type, false);
        return stable
                ? new SeparationConstraintGraph(ids, type, false, stableOrderGap(full.getDistance()))
                : full;
    }

    /**
     * The gap an ordering pair with reference gap {@code ref} keeps in stable mode:
     * {@code max(ORDER_GAP_MIN, min(ref, max(orderGapFloor, orderGapFraction * ref)))}.
     */
    private static double stableOrderGap(double ref) {
        return Math.max(ORDER_GAP_MIN, Math.min(ref, Math.max(orderGapFloor, orderGapFraction * ref)));
    }

    /**
     * Anchor targets and weights of stable mode. Survivors, in {@link #BY_KEY} order ({@link #survivingRefNodes}): the
     * reference position contracted towards the survivors' reference centroid {@code c}, {@code c + s * (ref - c)},
     * weight {@link #anchorWeight}. {@code s} is the smaller of {@code max(reclaimMin, sqrt(live / reference nodes))}
     * when the graph shrinks (the drawing keeps its area per node) and {@code 1 - driftCompensation} when the graph
     * changed, but never below the {@link #reclaimDensity} floor ({@link #scaleForDensity}; 1 if the reference is
     * already denser); 1 for an unchanged graph. The contraction is uniform, so it preserves the order; where the
     * contracted drawing is too dense, the edge springs and the overlap projection keep the space that is needed.
     * Inserted nodes, in {@link #BY_KEY} order: their seeded position, weight {@link #newcomerAnchor} x
     * {@link #anchorWeight} (no anchor when that is 0). Reference positions are read from the reference graph, never
     * written.
     */
    private void collectAnchors(Graph live, Map<Node, java.awt.geom.Point2D> anchors, Map<Node, Double> weights,
                                boolean changed) {
        if (survivingRefNodes.isEmpty()) return;
        double cx = 0, cy = 0;
        for (Node n : survivingRefNodes) {
            cx += AttributeHelper.getPositionX(n);
            cy += AttributeHelper.getPositionY(n);
        }
        cx /= survivingRefNodes.size();
        cy /= survivingRefNodes.size();
        List<Node> newcomers = newcomers(live);
        double s = 1;
        int refNodes = g.getNumberOfNodes(), liveNodes = live.getNumberOfNodes();
        if (liveNodes < refNodes) s = Math.max(reclaimMin, Math.sqrt(liveNodes / (double) refNodes));
        if (changed && driftCompensation > 0) s = Math.min(s, 1 - driftCompensation);
        if (s < 1 && reclaimDensity > 0) s = Math.min(1, Math.max(s, scaleForDensity(reclaimDensity, cx, cy, newcomers)));
        for (Node n : survivingRefNodes) {
            double x = cx + s * (AttributeHelper.getPositionX(n) - cx);
            double y = cy + s * (AttributeHelper.getPositionY(n) - cy);
            Node l = forwardMapNodes.get(n);
            anchors.put(l, new java.awt.geom.Point2D.Double(x, y));
            weights.put(l, anchorWeight);
        }
        if (newcomerAnchor > 0) {
            for (Node l : newcomers) {
                anchors.put(l, new java.awt.geom.Point2D.Double(
                        AttributeHelper.getPositionX(l), AttributeHelper.getPositionY(l)));
                weights.put(l, newcomerAnchor * anchorWeight);
            }
        }
    }

    /** The live nodes without a reference counterpart (the inserted nodes), in {@link #BY_KEY} order. */
    private List<Node> newcomers(Graph live) {
        Set<Node> survivors = new HashSet<>();                       // lookups only
        for (Node n : survivingRefNodes) survivors.add(forwardMapNodes.get(n));
        List<Node> newcomers = new ArrayList<>();
        for (Node l : live.getNodes()) if (!survivors.contains(l)) newcomers.add(l);
        newcomers.sort(BY_KEY);
        return newcomers;
    }

    /**
     * Smallest contraction {@code s} in [0, 1] about (cx, cy) at which the survivors' reference drawing still has the
     * density {@code floor} (see {@link #reclaimDensity}): bounding box of their solver rectangles, centres contracted
     * by {@code s}, over the summed solver-rectangle area of the survivors and {@code newcomers} (all live nodes;
     * summed in {@link #BY_KEY} order). 1 when even the uncontracted reference is denser. The box grows monotonically
     * with {@code s >= 0} (each extent is a maximum of lines through its value at 0), so 40 bisection steps find it to
     * 1e-12.
     */
    private double scaleForDensity(double floor, double cx, double cy, List<Node> newcomers) {
        double pad = CBLayoutAlgorithm.bboxConst;
        int k = survivingRefNodes.size();
        double[] dx = new double[k], dy = new double[k], hw = new double[k], hh = new double[k];
        double areas = 0;
        for (int i = 0; i < k; i++) {
            Node n = survivingRefNodes.get(i), l = forwardMapNodes.get(n);
            dx[i] = AttributeHelper.getPositionX(n) - cx;
            dy[i] = AttributeHelper.getPositionY(n) - cy;
            hw[i] = AttributeHelper.getWidth(l) / 2 + pad;
            hh[i] = AttributeHelper.getHeight(l) / 2 + pad;
            areas += 4 * hw[i] * hh[i];
        }
        for (Node l : newcomers) {
            areas += (AttributeHelper.getWidth(l) + 2 * pad) * (AttributeHelper.getHeight(l) + 2 * pad);
        }
        double need = floor * areas;
        if (!(boxArea(1, dx, dy, hw, hh) > need)) return 1;
        double lo = 0, hi = 1;
        for (int it = 0; it < 40; it++) {
            double mid = 0.5 * (lo + hi);
            if (boxArea(mid, dx, dy, hw, hh) >= need) hi = mid; else lo = mid;
        }
        return hi;
    }

    /** Area of the bounding box of the rectangles (half extents hw, hh) centred at s * (dx, dy). */
    private static double boxArea(double s, double[] dx, double[] dy, double[] hw, double[] hh) {
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < dx.length; i++) {
            x0 = Math.min(x0, s * dx[i] - hw[i]); x1 = Math.max(x1, s * dx[i] + hw[i]);
            y0 = Math.min(y0, s * dy[i] - hh[i]); y1 = Math.max(y1, s * dy[i] + hh[i]);
        }
        return (x1 - x0) * (y1 - y0);
    }

    /**
     * Stable mode: places the inserted nodes next to their neighbours; survivors never move here. One by one, most
     * placed neighbours first (ties by the {@link #BY_KEY} order of the pending nodes), each node's target is the
     * centroid of its placed neighbours (survivors at their reference position, and inserted nodes placed before),
     * or where the runner seeded it if none is placed; if that spot is taken, the node goes to the first free spot on
     * rings of spacing {@link #SEED_RING_STEP} x ideal edge length around it ({@link Rings}), up to
     * {@link #seedRadius} beyond the neighbours' boundary. "Free" means the rectangle, padded by the solver's
     * {@link CBLayoutAlgorithm#bboxConst} per side, overlaps no padded placed rectangle, the clearance the overlap
     * projection guarantees; a free spot also avoids a near-coincident pair, where libcola's p-stress is most strongly
     * non-convex. A node without placed neighbours (and every node when {@link #seedRadius} is 0) takes the first free
     * ring sample however far out. With no free spot within the radius, the node's own placed neighbours and the
     * overlaps near its target decide:
     * <ul>
     *   <li><b>None of them overlaps another node, or no overlapping node lies within the radius around the target:</b>
     *       the first free ring sample further out. The rings go on until they leave the bounding box of everything
     *       placed, where every sample is free, so such a node never gets an overlapping seed that would push survivors
     *       which already keep their clearance.</li>
     *   <li><b>One of them overlaps another node, and an overlapping node lies within the radius around the target</b>
     *       (overlapping, see {@link Obstacles}: a survivor closer to another survivor than the solver's clearance in
     *       the reference, or an inserted node seeded with an overlap): the spot that overlaps least within the radius
     *       if by at most {@link #seedOverlapMax} ({@link #leastOverlapSpot}), else the first ring sample outside every
     *       placed neighbour and every node inserted in this step ({@link #outsideSpot}), else the least-overlapping
     *       sample. The projection spreads such a neighbourhood anyway.</li>
     * </ul>
     * The decision is made per node and near its target, so an overlap elsewhere in the drawing does not change it; an
     * inserted node seeded with an overlap counts as overlapping, so chains of inserted nodes in an overlapping input
     * stay next to it. Only the last resort, the least-overlapping sample, can seed a node inside a placed neighbour.
     */
    private void seedNewcomers(Graph live) {
        Set<Node> placed = new LinkedHashSet<>();
        for (Node n : survivingRefNodes) placed.add(forwardMapNodes.get(n));
        List<Node> pending = newcomers(live);
        if (pending.isEmpty()) return;
        double pad = CBLayoutAlgorithm.bboxConst;
        Rings rings = new Rings(seedRingStep());
        Obstacles obstacles = new Obstacles(placed.size() + pending.size(), 4 * rings.step);
        for (Node o : placed) obstacles.add(o, pad, false);
        obstacles.markOverlappingPairs();
        int firstInserted = obstacles.size();
        while (!pending.isEmpty()) {
            Node best = null;
            int bestCount = -1;
            for (Node n : pending) {
                int count = 0;
                for (Node nb : n.getNeighbors()) if (placed.contains(nb)) count++;
                if (count > bestCount) { best = n; bestCount = count; }
            }
            pending.remove(best);
            double tx = 0, ty = 0, reach = 0;
            List<Node> nbs = new ArrayList<>(best.getNeighbors());
            nbs.sort(BY_KEY);
            List<Node> placedNbs = new ArrayList<>();
            boolean overlappingNeighbour = false;
            for (Node nb : nbs) {
                if (!placed.contains(nb)) continue;
                tx += AttributeHelper.getPositionX(nb);
                ty += AttributeHelper.getPositionY(nb);
                reach = Math.max(reach, paddedHalfExtent(nb, pad));
                placedNbs.add(nb);
                overlappingNeighbour |= obstacles.overlapping(nb);
            }
            int k = placedNbs.size();
            if (k > 0) { tx /= k; ty /= k; }
            else { tx = AttributeHelper.getPositionX(best); ty = AttributeHelper.getPositionY(best); }
            double hw = AttributeHelper.getWidth(best) / 2 + pad, hh = AttributeHelper.getHeight(best) / 2 + pad;
            double[] p = null;
            int searched = -1;   // rings already searched for a free spot
            if (k > 0 && seedRadius > 0) {
                double radius = seedRadius * CBLayoutAlgorithm.dist + reach + paddedHalfExtent(best, pad);
                int within = ringsFor(radius, rings.step);
                p = freeSpot(tx, ty, hw, hh, obstacles, rings, 0, within);
                searched = within;
                if (p == null && overlappingNeighbour && obstacles.flaggedWithin(tx, ty, radius)) {
                    if (seedOverlapMax > 0) {
                        p = leastOverlapSpot(tx, ty, hw, hh, obstacles, rings, within, seedOverlapMax);
                    }
                    if (p == null) p = outsideSpot(tx, ty, placedNbs, pad, obstacles, firstInserted, rings, within);
                    if (p == null) {
                        p = leastOverlapSpot(tx, ty, hw, hh, obstacles, rings, within, Double.POSITIVE_INFINITY);
                    }
                }
            }
            if (p == null) {
                int leave = obstacles.ringsToLeave(tx, ty, hw, hh, rings.step);
                p = freeSpot(tx, ty, hw, hh, obstacles, rings, searched + 1, leave);
            }
            if (p == null) p = obstacles.besideBox(tx, ty, hw, hh);
            boolean overlapsPlaced = obstacles.depth(p[0], p[1], hw, hh) > 0.5;
            AttributeHelper.setPosition(best, p[0], p[1]);
            placed.add(best);
            obstacles.add(best, pad, overlapsPlaced);
        }
    }

    /** Rings of spacing {@code step} needed to reach {@code radius} px, at most {@link #SEED_RING_GUARD}. */
    private static int ringsFor(double radius, double step) {
        double r = Math.ceil(radius / step - 1e-9);
        return r < SEED_RING_GUARD ? Math.max(0, (int) r) : SEED_RING_GUARD;
    }

    /** Half the larger side of {@code n}'s rectangle plus {@code pad}: how far its padded rectangle reaches at most. */
    private static double paddedHalfExtent(Node n, double pad) {
        return Math.max(AttributeHelper.getWidth(n), AttributeHelper.getHeight(n)) / 2 + pad;
    }

    /** Ring spacing of the free-spot search in px: max(5, {@link #SEED_RING_STEP} x ideal edge length). */
    private static double seedRingStep() {
        return Math.max(5, SEED_RING_STEP * CBLayoutAlgorithm.dist);
    }

    /**
     * The ring samples of the seed search: ring {@code r} (spacing {@link #step}) has {@code 8 r} samples at the angles
     * {@code 2 pi j / (8 r)}, ring 0 is the centre itself; sample j of ring r around (tx, ty) is
     * {@code (tx + r * step * cos[j], ty + r * step * sin[j])}, {@code {cos, sin} = of(r)}. The cosines and sines of a
     * ring are computed once per {@link #seedNewcomers(Graph)} and shared by all its inserted nodes (rings up to
     * {@link #CACHED_RINGS}; beyond, per use): the same doubles as computing them for every sample.
     */
    private static final class Rings {
        /** Rings whose cosines and sines are kept: 8 x 256 x 257 / 2 values of each, 4 MB, at most. */
        private static final int CACHED_RINGS = 256;
        final double step;
        private final double[][][] cache = new double[CACHED_RINGS + 1][][];

        Rings(double step) {
            this.step = step;
        }

        /** {cosines, sines} of the sample angles of ring {@code ring}. */
        double[][] of(int ring) {
            if (ring <= CACHED_RINGS && cache[ring] != null) return cache[ring];
            int m = ring == 0 ? 1 : 8 * ring;
            double[] c = new double[m], s = new double[m];
            for (int j = 0; j < m; j++) {
                double a = 2 * Math.PI * j / m;
                c[j] = Math.cos(a);
                s[j] = Math.sin(a);
            }
            double[][] cs = {c, s};
            if (ring <= CACHED_RINGS) cache[ring] = cs;
            return cs;
        }
    }

    /**
     * First free centre for a rectangle of padded half extents (hw, hh) on the rings {@code fromRing} ..
     * {@code toRing} around (tx, ty), in ring order and within a ring by angle ({@link Rings}); null if none is free.
     */
    private static double[] freeSpot(double tx, double ty, double hw, double hh, Obstacles obstacles, Rings rings,
                                     int fromRing, int toRing) {
        for (int ring = fromRing; ring <= toRing; ring++) {
            double[][] cs = rings.of(ring);
            double[] c = cs[0], s = cs[1];
            double r = ring * rings.step;
            for (int j = 0; j < c.length; j++) {
                double x = tx + r * c[j], y = ty + r * s[j];
                if (obstacles.isFree(x, y, hw, hh)) return new double[]{x, y};
            }
        }
        return null;
    }

    /**
     * The ring sample around (tx, ty) within {@code maxRings} rings (same rings and order as {@link #freeSpot}) whose
     * padded rectangle (half extents hw, hh) overlaps the placed padded rectangles least, measured by
     * {@link Obstacles#depth}; the first such sample in ring order on ties; null if even that one overlaps by more
     * than {@code maxDepth} px. The inserted nodes already placed count like survivors, so a sample on top of one is
     * never the least-overlapping one while any sample overlaps less.
     */
    private static double[] leastOverlapSpot(double tx, double ty, double hw, double hh, Obstacles obstacles,
                                             Rings rings, int maxRings, double maxDepth) {
        double bestDepth = Double.POSITIVE_INFINITY;
        double[] best = null;
        for (int ring = 0; ring <= maxRings; ring++) {
            double[][] cs = rings.of(ring);
            double[] c = cs[0], s = cs[1];
            double r = ring * rings.step;
            for (int j = 0; j < c.length; j++) {
                double x = tx + r * c[j], y = ty + r * s[j];
                double d = obstacles.depth(x, y, hw, hh);
                if (d < bestDepth - 1e-9) {
                    bestDepth = d;
                    best = new double[]{x, y};
                }
            }
        }
        return bestDepth <= maxDepth ? best : null;
    }

    /**
     * The first ring sample around (tx, ty) within {@code maxRings} rings (same rings and order as {@link #freeSpot})
     * whose centre lies outside the padded rectangle of every node in {@code neighbours} and of every node inserted in
     * this step (obstacle index {@code >= firstInserted}); null if there is none. The sample may overlap other
     * survivors: this stage is reached only for a node with an overlapping neighbour ({@link #seedNewcomers(Graph)}).
     * Skipping the nodes inserted in this step keeps several nodes with the same neighbours (or neighbours at the same
     * place) from being seeded on top of each other.
     */
    private static double[] outsideSpot(double tx, double ty, List<Node> neighbours, double pad, Obstacles obstacles,
                                        int firstInserted, Rings rings, int maxRings) {
        for (int ring = 0; ring <= maxRings; ring++) {
            double[][] cs = rings.of(ring);
            double[] c = cs[0], s = cs[1];
            double r = ring * rings.step;
            for (int j = 0; j < c.length; j++) {
                double x = tx + r * c[j], y = ty + r * s[j];
                boolean inside = false;
                for (Node nb : neighbours) {
                    double w2 = AttributeHelper.getWidth(nb) / 2 + pad, h2 = AttributeHelper.getHeight(nb) / 2 + pad;
                    if (Math.abs(x - AttributeHelper.getPositionX(nb)) < w2
                            && Math.abs(y - AttributeHelper.getPositionY(nb)) < h2) {
                        inside = true;
                        break;
                    }
                }
                if (!inside && !obstacles.containsCentre(x, y, firstInserted)) return new double[]{x, y};
            }
        }
        return null;
    }

    /**
     * The rectangles the seed search of {@link #seedNewcomers(Graph)} must keep clear of: centre and padded half extent
     * (size / 2 + pad) of every placed node, read once when the node is added (survivors first, then every inserted
     * node right after it is placed; nobody moves while seeding), with a flag whether the node overlaps: a survivor
     * whose padded rectangle overlaps another survivor's by more than 0.5 px on both axes, an inserted node seeded with
     * an overlap of more than 0.5 px.
     *
     * <p>A grid index (square cells of four ring spacings) limits a query to the obstacles registered in the cells its
     * rectangle touches. An obstacle is registered in every cell its rectangle, grown by 1 px, touches, so every
     * obstacle a query could overlap is tested, whatever the rounding; one spanning more than {@link #WIDE_CELLS} cells
     * is tested by every query instead, and a query spanning more cells than that, or than there are obstacles, tests
     * every obstacle. The answers are exactly those of testing every obstacle. Since the search goes on until it finds
     * free space, testing every obstacle for every ring sample would grow with rings^2 x obstacles.
     */
    private static final class Obstacles {
        /**
         * Obstacles that would be registered in more cells than this (at the default cell size a rectangle more than
         * about 6400 px wide and high) are kept in {@link #wide} and tested by every query; it also bounds the cells an
         * obstacle is registered in.
         */
        private static final int WIDE_CELLS = 4096;
        private double[] x, y, hw, hh;
        private boolean[] overlapping;
        private int size;
        /** Bounding box of all padded rectangles. */
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        /** Obstacle index of a placed node (lookups only). */
        private final Map<Node, Integer> index = new HashMap<>();
        private final double cell;
        /** Grid index: open addressing from a packed cell key to the list {count, obstacle...} of that cell. */
        private long[] cellKeys = new long[256];
        private int[][] cellLists = new int[256][];
        private int cellCount;
        private int[] wide = new int[4];
        private int wideSize;

        Obstacles(int capacity, double cell) {
            int c = Math.max(1, capacity);
            x = new double[c];
            y = new double[c];
            hw = new double[c];
            hh = new double[c];
            overlapping = new boolean[c];
            this.cell = cell;
        }

        int size() {
            return size;
        }

        /** Append {@code o} at its current position, half extents padded by {@code pad}, flagged if {@code overlaps}. */
        void add(Node o, double pad, boolean overlaps) {
            if (size == x.length) {
                int c = 2 * size;
                x = Arrays.copyOf(x, c);
                y = Arrays.copyOf(y, c);
                hw = Arrays.copyOf(hw, c);
                hh = Arrays.copyOf(hh, c);
                overlapping = Arrays.copyOf(overlapping, c);
            }
            int i = size++;
            x[i] = AttributeHelper.getPositionX(o);
            y[i] = AttributeHelper.getPositionY(o);
            hw[i] = AttributeHelper.getWidth(o) / 2 + pad;
            hh[i] = AttributeHelper.getHeight(o) / 2 + pad;
            overlapping[i] = overlaps;
            minX = Math.min(minX, x[i] - hw[i]);
            maxX = Math.max(maxX, x[i] + hw[i]);
            minY = Math.min(minY, y[i] - hh[i]);
            maxY = Math.max(maxY, y[i] + hh[i]);
            index.put(o, i);
            long a0 = cellOf(x[i] - hw[i] - 1), a1 = cellOf(x[i] + hw[i] + 1);
            long b0 = cellOf(y[i] - hh[i] - 1), b1 = cellOf(y[i] + hh[i] + 1);
            if ((a1 - a0 + 1.0) * (b1 - b0 + 1.0) > WIDE_CELLS) {
                if (wideSize == wide.length) wide = Arrays.copyOf(wide, 2 * wideSize);
                wide[wideSize++] = i;
                return;
            }
            for (long a = a0; a <= a1; a++) for (long b = b0; b <= b1; b++) register(key(a, b), i);
        }

        /**
         * Flags every obstacle whose rectangle overlaps another one by more than 0.5 px on both axes (called with the
         * survivors only: where the reference is closer than the solver's clearance). Every pair is tested.
         */
        void markOverlappingPairs() {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (hw[i] + hw[j] - Math.abs(x[i] - x[j]) > 0.5 && hh[i] + hh[j] - Math.abs(y[i] - y[j]) > 0.5) {
                        overlapping[i] = true;
                        overlapping[j] = true;
                    }
                }
            }
        }

        /** Whether the placed node {@code n} is flagged as overlapping (see {@link Obstacles}). */
        boolean overlapping(Node n) {
            Integer i = index.get(n);
            return i != null && overlapping[i];
        }

        /** Whether a rectangle of padded half extents (w2, h2) centred at (cx, cy) overlaps no obstacle rectangle. */
        boolean isFree(double cx, double cy, double w2, double h2) {
            for (int k = 0; k < wideSize; k++) if (overlaps(wide[k], cx, cy, w2, h2)) return false;
            long a0 = cellOf(cx - w2), a1 = cellOf(cx + w2), b0 = cellOf(cy - h2), b1 = cellOf(cy + h2);
            if (scanAll(a0, a1, b0, b1)) {
                for (int i = 0; i < size; i++) if (overlaps(i, cx, cy, w2, h2)) return false;
                return true;
            }
            for (long a = a0; a <= a1; a++) {
                for (long b = b0; b <= b1; b++) {
                    int[] l = cellLists[slot(key(a, b))];
                    if (l == null) continue;
                    for (int j = 1; j <= l[0]; j++) if (overlaps(l[j], cx, cy, w2, h2)) return false;
                }
            }
            return true;
        }

        /**
         * How deeply a rectangle of padded half extents (w2, h2) centred at (cx, cy) overlaps the obstacles: over the
         * obstacle rectangles it overlaps, the largest penetration along the shallower axis (the push that would
         * separate the pair); 0 when it overlaps none.
         */
        double depth(double cx, double cy, double w2, double h2) {
            double d = 0;
            for (int k = 0; k < wideSize; k++) d = Math.max(d, depthOf(wide[k], cx, cy, w2, h2));
            long a0 = cellOf(cx - w2), a1 = cellOf(cx + w2), b0 = cellOf(cy - h2), b1 = cellOf(cy + h2);
            if (scanAll(a0, a1, b0, b1)) {
                for (int i = 0; i < size; i++) d = Math.max(d, depthOf(i, cx, cy, w2, h2));
                return d;
            }
            for (long a = a0; a <= a1; a++) {
                for (long b = b0; b <= b1; b++) {
                    int[] l = cellLists[slot(key(a, b))];
                    if (l == null) continue;
                    for (int j = 1; j <= l[0]; j++) d = Math.max(d, depthOf(l[j], cx, cy, w2, h2));
                }
            }
            return d;
        }

        /**
         * Whether an obstacle flagged as overlapping (see {@link Obstacles}) comes within {@code r} px of (cx, cy): the
         * distance from the point to its rectangle is at most r.
         */
        boolean flaggedWithin(double cx, double cy, double r) {
            for (int k = 0; k < wideSize; k++) if (flaggedNear(wide[k], cx, cy, r)) return true;
            long a0 = cellOf(cx - r), a1 = cellOf(cx + r), b0 = cellOf(cy - r), b1 = cellOf(cy + r);
            if (scanAll(a0, a1, b0, b1)) {
                for (int i = 0; i < size; i++) if (flaggedNear(i, cx, cy, r)) return true;
                return false;
            }
            for (long a = a0; a <= a1; a++) {
                for (long b = b0; b <= b1; b++) {
                    int[] l = cellLists[slot(key(a, b))];
                    if (l == null) continue;
                    for (int j = 1; j <= l[0]; j++) if (flaggedNear(l[j], cx, cy, r)) return true;
                }
            }
            return false;
        }

        private boolean flaggedNear(int i, double cx, double cy, double r) {
            if (!overlapping[i]) return false;
            double dx = Math.max(0, Math.abs(cx - x[i]) - hw[i]), dy = Math.max(0, Math.abs(cy - y[i]) - hh[i]);
            return dx * dx + dy * dy <= r * r;
        }

        /**
         * Whether a query over the cells a0..a1 x b0..b1 tests every obstacle instead of the cells: when it spans more
         * cells than {@link #WIDE_CELLS}, or more than there are obstacles (the answer is the same either way).
         */
        private boolean scanAll(long a0, long a1, long b0, long b1) {
            return (a1 - a0 + 1.0) * (b1 - b0 + 1.0) > Math.min(WIDE_CELLS, size);
        }

        /** Whether (cx, cy) lies inside the rectangle of an obstacle with index {@code >= from}. */
        boolean containsCentre(double cx, double cy, int from) {
            for (int k = 0; k < wideSize; k++) if (wide[k] >= from && overlaps(wide[k], cx, cy, 0, 0)) return true;
            int[] l = cellLists[slot(key(cellOf(cx), cellOf(cy)))];
            if (l == null) return false;
            for (int j = 1; j <= l[0]; j++) if (l[j] >= from && overlaps(l[j], cx, cy, 0, 0)) return true;
            return false;
        }

        /**
         * Rings (of spacing {@code step} around (tx, ty)) after which every sample lies outside the bounding box of all
         * obstacles grown by (w2, h2), i.e. is free: the distance to the farthest corner of that box plus one ring, at
         * most {@link MentalMapLayout#SEED_RING_GUARD}.
         */
        int ringsToLeave(double tx, double ty, double w2, double h2, double step) {
            double dx = Math.max(Math.abs(tx - (minX - w2)), Math.abs(maxX + w2 - tx));
            double dy = Math.max(Math.abs(ty - (minY - h2)), Math.abs(maxY + h2 - ty));
            return ringsFor(Math.hypot(dx, dy) + step, step);
        }

        /**
         * Last resort, reached only when {@link #ringsToLeave} hit its guard (a drawing reaching more than 50 000 px
         * from the node, at the default ideal edge length): the nearest of the four points just outside the obstacles'
         * bounding box grown by (w2, h2) level with (tx, ty). Free by construction; the next node placed there sees
         * this one in the box.
         */
        double[] besideBox(double tx, double ty, double w2, double h2) {
            double[][] c = {{minX - w2 - 1, ty}, {maxX + w2 + 1, ty}, {tx, minY - h2 - 1}, {tx, maxY + h2 + 1}};
            double[] best = c[0];
            for (double[] q : c) {
                if (Math.hypot(q[0] - tx, q[1] - ty) < Math.hypot(best[0] - tx, best[1] - ty)) best = q;
            }
            return best;
        }

        private boolean overlaps(int i, double cx, double cy, double w2, double h2) {
            return Math.abs(cx - x[i]) < w2 + hw[i] && Math.abs(cy - y[i]) < h2 + hh[i];
        }

        private double depthOf(int i, double cx, double cy, double w2, double h2) {
            double px = w2 + hw[i] - Math.abs(cx - x[i]), py = h2 + hh[i] - Math.abs(cy - y[i]);
            return px > 0 && py > 0 ? Math.min(px, py) : 0;
        }

        private long cellOf(double v) {
            return (long) Math.floor(v / cell);
        }

        private static long key(long a, long b) {
            return (a << 32) ^ (b & 0xffffffffL);
        }

        /** Slot of {@code key} in the open-addressing table: its own, or the empty one where it would go. */
        private int slot(long key) {
            int mask = cellKeys.length - 1;
            long h = key * 0x9E3779B97F4A7C15L;
            int i = (int) (h ^ (h >>> 32)) & mask;
            while (cellLists[i] != null && cellKeys[i] != key) i = (i + 1) & mask;
            return i;
        }

        private void register(long key, int obstacle) {
            int s = slot(key);
            int[] l = cellLists[s];
            if (l == null) {
                cellKeys[s] = key;
                l = new int[4];
                cellCount++;
            } else if (l[0] + 1 == l.length) {
                l = Arrays.copyOf(l, 2 * l.length);
            }
            l[++l[0]] = obstacle;
            cellLists[s] = l;
            if (2 * cellCount > cellKeys.length) {
                long[] keys = cellKeys;
                int[][] lists = cellLists;
                cellKeys = new long[2 * keys.length];
                cellLists = new int[2 * keys.length][];
                for (int k = 0; k < keys.length; k++) {
                    if (lists[k] == null) continue;
                    int t = slot(keys[k]);
                    cellKeys[t] = keys[k];
                    cellLists[t] = lists[k];
                }
            }
        }
    }

    /**
     * Whether the live graph has exactly the reference's nodes and edges (by uid; edge direction and multiplicity
     * ignored), i.e. nothing was inserted, deleted or rewired since the reference was captured. Stable mode then skips
     * the force-directed pass and only projects the drawing onto the constraints: a first run on a hand-drawn input
     * still gets its clearance, and every further run on the same graph leaves the drawing where it is.
     */
    private boolean graphUnchanged(Graph live) {
        if (!ignoreNodesOrdering.isEmpty() || live.getNumberOfNodes() != survivingRefNodes.size()) return false;
        return edgeKeys(g).equals(edgeKeys(live));
    }

    /** Undirected edge keys by node uid ({@link Snapshot#uidOf(Node)}); compared as sets only. */
    private static Set<String> edgeKeys(Graph graph) {
        Set<String> keys = new HashSet<>();
        for (org.graffiti.graph.Edge e : graph.getEdges()) {
            String a = Snapshot.uidOf(e.getSource()), b = Snapshot.uidOf(e.getTarget());
            keys.add(a.compareTo(b) <= 0 ? a + '\u0001' + b : b + '\u0001' + a);
        }
        return keys;
    }

    /**
     * Stable mode: shift the whole live drawing by minus the survivors' mean displacement from their reference
     * positions, so their centroid is back where it was. The solver has no reason to keep the drawing in place (the
     * stress is translation invariant, and the projections move whole blocks), so without this the drawing wanders
     * from step to step and the timeline has to show the union of all positions. A translation changes no relative
     * position, so order, clusters and non-overlap are unaffected. Nodes only: the engine removed the bends.
     */
    private void removeDrift(Graph live) {
        double dx = 0, dy = 0;
        int k = 0;
        for (Node n : survivingRefNodes) {
            Node l = forwardMapNodes.get(n);
            dx += AttributeHelper.getPositionX(l) - AttributeHelper.getPositionX(n);
            dy += AttributeHelper.getPositionY(l) - AttributeHelper.getPositionY(n);
            k++;
        }
        if (k == 0) return;
        dx /= k;
        dy /= k;
        for (Node l : live.getNodes()) {
            AttributeHelper.setPosition(l, AttributeHelper.getPositionX(l) - dx, AttributeHelper.getPositionY(l) - dy);
        }
    }

    /** Add a generated (not user-made) constraint to this layout's own store. */
    private void addAutomatic(Constraint c) {
        c.setAutomatic(true);
        store.addConstraint(c);
    }

    /**
     * Neighbourhood clusters: a Euclidean MST of the survivors' reference positions is pruned at its mean edge length;
     * the connected components become {@link #clusters}.
     */
    private void computeClusters() {
        // Deleted nodes stay out: one would bridge two surviving groups into one cluster and skew the mean edge length.
        List<EuclideanMST.WeightedEdge> mstEdges = EuclideanMST.computeMST(survivingRefNodes);
        double sum = 0;
        for (EuclideanMST.WeightedEdge we : mstEdges) {
            sum += we.w;
        }
        double delta = mstEdges.isEmpty() ? 100 : sum / mstEdges.size();
        mstEdges.removeIf(e -> e.w > delta);
        clusters = connectedComponentsFromEdges(survivingRefNodes, mstEdges);
    }

    private static List<List<Node>> connectedComponentsFromEdges(
            Collection<Node> allNodes,
            List<EuclideanMST.WeightedEdge> edges
    ) {
        Map<Node, List<Node>> adj = new LinkedHashMap<>();

        for (Node n : allNodes) {
            adj.put(n, new ArrayList<>());
        }

        for (EuclideanMST.WeightedEdge e : edges) {
            adj.get(e.u).add(e.v);
            adj.get(e.v).add(e.u);
        }

        Set<Node> visited = new LinkedHashSet<>();
        List<List<Node>> components = new ArrayList<>();

        for (Node start : allNodes) {
            if (visited.contains(start)) continue;

            List<Node> comp = new ArrayList<>();
            ArrayDeque<Node> queue = new ArrayDeque<>();

            visited.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                Node u = queue.poll();
                comp.add(u);

                for (Node v : adj.get(u)) {
                    if (!visited.contains(v)) {
                        visited.add(v);
                        queue.add(v);
                    }
                }
            }

            comp.sort(BY_KEY);
            components.add(comp);
        }

        // The cluster order is an input to the native layout: members and components both get a fixed order.
        components.sort(BY_FIRST);
        return components;
    }

    /**
     * Stable mode: merge the components in {@link #clusters} (singletons included) until no cluster box overlaps
     * another cluster box or a node outside it, all measured at the reference positions. libcola keeps sibling
     * clusters' boxes, and every node outside a cluster, disjoint from each cluster's box, so boxes that interleave in
     * the reference would be pushed apart by the final projection on every step; merged, a neighbourhood the reference
     * draws interleaved is kept together as one group, and the reference already meets every cluster constraint. Box of
     * a group = bounding box of its members' solver rectangles (size/2 + {@link CBLayoutAlgorithm#bboxConst} per side),
     * grown by {@link CBLayoutAlgorithm#clusterPadding} + {@link CBLayoutAlgorithm#clusterMargin} when it has two or
     * more members; two singletons never conflict (they are plain nodes). The first overlapping pair (i &lt; j) is
     * merged, repeatedly; merged lists are sorted {@link #BY_KEY}, the final groups by the key of their first member.
     */
    private void mergeClustersOverlappingInReference() {
        List<List<Node>> groups = new ArrayList<>(clusters);
        double grow = CBLayoutAlgorithm.clusterPadding + CBLayoutAlgorithm.clusterMargin;
        boolean changed = true;
        while (changed && groups.size() > 1) {
            changed = false;
            double[][] box = new double[groups.size()][];
            for (int i = 0; i < groups.size(); i++) box[i] = referenceBox(groups.get(i), groups.get(i).size() > 1 ? grow : 0);
            outer:
            for (int i = 0; i < groups.size(); i++) {
                for (int j = i + 1; j < groups.size(); j++) {
                    if (groups.get(i).size() == 1 && groups.get(j).size() == 1) continue;
                    if (box[i][0] < box[j][2] && box[j][0] < box[i][2] && box[i][1] < box[j][3] && box[j][1] < box[i][3]) {
                        List<Node> merged = new ArrayList<>(groups.get(i));
                        merged.addAll(groups.get(j));
                        merged.sort(BY_KEY);
                        groups.remove(j);
                        groups.set(i, merged);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        groups.sort(BY_FIRST);
        clusters = groups;
    }

    /** {minX, minY, maxX, maxY} of the members' solver rectangles at their reference positions, grown by {@code grow}. */
    private double[] referenceBox(List<Node> members, double grow) {
        double pad = CBLayoutAlgorithm.bboxConst;
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (Node n : members) {
            Node l = forwardMapNodes.get(n);
            double hw = AttributeHelper.getWidth(l) / 2 + pad, hh = AttributeHelper.getHeight(l) / 2 + pad;
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            x0 = Math.min(x0, x - hw); x1 = Math.max(x1, x + hw);
            y0 = Math.min(y0, y - hh); y1 = Math.max(y1, y + hh);
        }
        return new double[]{x0 - grow, y0 - grow, x1 + grow, y1 + grow};
    }

    /**
     * Stable mode, after {@link #seedNewcomers(Graph)}: the inserted nodes seeded next to the entries of
     * {@link CBLayoutAlgorithm#allCluster} of their neighbours join them, transitively; libcola keeps every node
     * outside the boxes of the clusters it does not belong to and would otherwise expel the inserted nodes from the
     * merged boxes. In passes over the inserted nodes not yet in a cluster ({@link #BY_KEY} order), each joins, of the
     * clusters that hold any of its neighbours and that its seed is next to, the one that holds most of its neighbours
     * (ties: the lowest index), counting survivors and the inserted nodes that joined in an earlier pass. Next to: its
     * padded rectangle at the seed comes within {@link #seedRadius} x ideal edge length of the cluster's box, the
     * bounding box of the members' solver rectangles (survivors at their reference positions, inserted members at their
     * seeds) grown by {@link CBLayoutAlgorithm#clusterPadding} + {@link CBLayoutAlgorithm#clusterMargin}; a node seeded
     * farther away stays out, so that the box of a small cluster does not grow over survivors outside it. The joins of
     * a pass take effect after it (they also grow the boxes; so the result does not depend on the order within a pass),
     * and the passes stop when one adds nothing. Member lists are re-sorted {@link #BY_KEY}.
     */
    private void addNewcomersToNeighbourClusters(Graph live) {
        List<List<Node>> cl = CBLayoutAlgorithm.allCluster;
        if (cl.isEmpty()) return;
        Map<Node, Integer> clusterOf = new HashMap<>();          // lookups only
        for (int i = 0; i < cl.size(); i++) for (Node n : cl.get(i)) clusterOf.put(n, i);
        List<Node> newcomers = newcomers(live);
        double pad = CBLayoutAlgorithm.bboxConst;
        double grow = CBLayoutAlgorithm.clusterPadding + CBLayoutAlgorithm.clusterMargin;
        double reach = seedRadius * CBLayoutAlgorithm.dist;
        Map<Node, Node> refOf = new HashMap<>();                         // lookups only
        for (Node n : survivingRefNodes) refOf.put(forwardMapNodes.get(n), n);
        double[][] box = new double[cl.size()][];
        for (int i = 0; i < cl.size(); i++) {
            List<Node> members = new ArrayList<>();
            for (Node l : cl.get(i)) members.add(refOf.get(l));
            box[i] = referenceBox(members, grow);
        }
        List<List<Node>> joins = new ArrayList<>();
        for (int i = 0; i < cl.size(); i++) joins.add(null);
        while (true) {
            Map<Node, Integer> joined = new LinkedHashMap<>();    // this pass, in BY_KEY order
            for (Node n : newcomers) {
                if (clusterOf.containsKey(n)) continue;
                int[] count = new int[cl.size()];
                for (Node nb : n.getNeighbors()) {
                    Integer c = clusterOf.get(nb);
                    if (c != null) count[c]++;
                }
                double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
                double w2 = AttributeHelper.getWidth(n) / 2 + pad + reach;
                double h2 = AttributeHelper.getHeight(n) / 2 + pad + reach;
                int best = -1;
                for (int i = 0; i < count.length; i++) {
                    if (count[i] == 0 || (best >= 0 && count[i] <= count[best])) continue;
                    double[] b = box[i];
                    if (x + w2 >= b[0] && x - w2 <= b[2] && y + h2 >= b[1] && y - h2 <= b[3]) best = i;
                }
                if (best >= 0) joined.put(n, best);
            }
            if (joined.isEmpty()) break;
            for (Map.Entry<Node, Integer> e : joined.entrySet()) {
                Node n = e.getKey();
                int best = e.getValue();
                clusterOf.put(n, best);
                if (joins.get(best) == null) joins.set(best, new ArrayList<>());
                joins.get(best).add(n);
                double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
                double w2 = AttributeHelper.getWidth(n) / 2 + pad + grow;
                double h2 = AttributeHelper.getHeight(n) / 2 + pad + grow;
                double[] b = box[best];
                b[0] = Math.min(b[0], x - w2); b[1] = Math.min(b[1], y - h2);
                b[2] = Math.max(b[2], x + w2); b[3] = Math.max(b[3], y + h2);
            }
        }
        for (int i = 0; i < cl.size(); i++) {
            if (joins.get(i) == null) continue;
            cl.get(i).addAll(joins.get(i));
            cl.get(i).sort(BY_KEY);
        }
    }

    /**
     * Translates the clusters of two or more reference nodes into Adaptagrams clusters of their live counterparts
     * ({@link CBLayoutAlgorithm#allCluster}).
     */
    private void addAdaptagramsClusters() {
        CBLayoutAlgorithm.allCluster = new ArrayList<>();
        for (List<Node> comp : clusters) {
            if (comp.size() > 1) {
                List<Node> cluster = new ArrayList<>();
                for (Node n : comp) {
                    cluster.add(forwardMapNodes.get(n));
                }
                cluster.sort(BY_KEY);   // live-node order is an input to the native layout too
                CBLayoutAlgorithm.allCluster.add(cluster);
            }
        }
    }
}
