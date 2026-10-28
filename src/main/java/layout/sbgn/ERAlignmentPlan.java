package layout.sbgn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of the ER layout ({@link ERHyperedgeLayout}) that need only numbers, with nodes as indices into the
 * caller's arrays: which hyperedge nodes step 1 removes, which axis the line between two neighbours is closest to,
 * which of these alignments fit together, and whether a straight line runs through a node. An alignment is kept as
 * equalities between coordinates (vertical: the same x, horizontal: the same y, diagonal: exactly g further in x and
 * in y, since the solver has no diagonal), so on every cycle of the graph their gaps have to add up to zero;
 * {@link Model} rejects one that contradicts the known differences or puts two nodes on top of each other.
 */
final class ERAlignmentPlan {

    private ERAlignmentPlan() { }

    /** Two known coordinate differences count as equal below this. */
    static final double EPS = 1e-6;
    /** {@link Candidate#allowed}: any of the eight directions. */
    static final int ANY = 0xFF;
    /** The four straight directions, as bits. */
    static final int STRAIGHT = 0x55;

    // ------------------------------------------------------------------ directions

    /** The eight directions of an alignment, clockwise on screen (y grows downwards), 45 degrees apart. */
    enum Direction {
        E(1, 0), SE(1, 1), S(0, 1), SW(-1, 1), W(-1, 0), NW(-1, -1), N(0, -1), NE(1, -1);

        final int dx, dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        Direction opposite() { return turned(4); }

        /** This direction turned clockwise by {@code eighths} times 45 degrees. */
        Direction turned(int eighths) { return values()[((ordinal() + eighths) % 8 + 8) % 8]; }

        boolean diagonal() { return dx != 0 && dy != 0; }

        int bit() { return 1 << ordinal(); }

        /** This direction and its two neighbours: the three directions on one side of a node, as bits. */
        int side() { return bit() | turned(1).bit() | turned(-1).bit(); }

        Alignment alignment() { return Alignment.values()[ordinal() % 4]; }

        /** Degrees between this direction and the vector (dx, dy): 0 to 180. */
        double deviation(double dx, double dy) {
            double turn = Math.toDegrees(Math.atan2(dy, dx)) - 45.0 * ordinal();
            return Math.abs((turn % 360 + 540) % 360 - 180);
        }
    }

    /** The four alignments; each is the axis of two opposite directions. */
    enum Alignment {
        /** The same y. */
        HORIZONTAL,
        /** The x axis turned 45 degrees clockwise, on screen "\". */
        OCT_HORIZONTAL,
        /** The same x. */
        VERTICAL,
        /** The y axis turned 45 degrees clockwise, on screen "/". */
        OCT_VERTICAL
    }

    /**
     * The directions a pair with the vector (dx, dy) from its first to its second node may take, best first; at most
     * two, out of {@code allowed} (bits of {@link Direction}). With a free choice ({@link #ANY}) these are the axis the
     * vector is closest to (a straight one on a tie) and the axis on its other side, the next sector. A straight
     * direction is left out if the second node does not lie on that side at all: an alignment alone does not take it
     * there, the two equal gaps of a diagonal do.
     */
    static List<Direction> candidates(double dx, double dy, int allowed) {
        List<Direction> options = new ArrayList<>(2);
        if (dx == 0 && dy == 0) return options;
        Direction[] all = Direction.values();
        Arrays.sort(all, (a, b) -> {
            double c = a.deviation(dx, dy) - b.deviation(dx, dy);
            if (Math.abs(c) > 1e-9) return c < 0 ? -1 : 1;
            return a.diagonal() != b.diagonal() ? (a.diagonal() ? 1 : -1) : Integer.compare(a.ordinal(), b.ordinal());
        });
        for (Direction d : all) {
            double deviation = d.deviation(dx, dy);
            if (allowed == ANY && deviation > 45 + 1e-9) break;
            if ((allowed & d.bit()) == 0 || (!d.diagonal() && deviation >= 90)) continue;
            options.add(d);
            if (options.size() == 2) break;
        }
        return options;
    }

    /** The direction of {@code allowed} (bits) closest to the vector (dx, dy), a straight one on a tie; null without a vector or a direction. */
    static Direction closest(double dx, double dy, int allowed) {
        Direction best = null;
        if (dx == 0 && dy == 0) return null;
        for (Direction d : Direction.values()) {
            if ((allowed & d.bit()) == 0) continue;
            if (best == null) { best = d; continue; }
            double c = d.deviation(dx, dy) - best.deviation(dx, dy);
            if (c < -1e-9 || (Math.abs(c) <= 1e-9 && !d.diagonal() && best.diagonal())) best = d;
        }
        return best;
    }

    /** How far two directions are apart, in eighths of a turn: 0 to 4. */
    static int steps(Direction a, Direction b) {
        int d = Math.abs(a.ordinal() - b.ordinal());
        return Math.min(d, 8 - d);
    }

    /**
     * Step 5: the directions the stub may take with which a hyperedge leaves a hyperedge node, best first. {@code a}
     * and {@code b}: the directions in which the two pieces of the node's own arc leave it. The stub stands at a right
     * angle to an arc that runs straight through the node; where the arc bends in the node it lies on the outer side
     * of the bend, at least a right angle away from both pieces. Best is the direction that points most along
     * (dx, dy), towards the other end of the hyperedge; a straight one on a tie.
     */
    static List<Direction> stubDirections(Direction a, Direction b, double dx, double dy) {
        List<Direction> options = new ArrayList<>();
        for (Direction d : Direction.values()) if (steps(d, a) >= 2 && steps(d, b) >= 2) options.add(d);
        options.sort((p, q) -> {
            double c = (q.dx * dx + q.dy * dy) / Math.hypot(q.dx, q.dy) - (p.dx * dx + p.dy * dy) / Math.hypot(p.dx, p.dy);
            if (Math.abs(c) > 1e-9) return c < 0 ? -1 : 1;
            return p.diagonal() != q.diagonal() ? (p.diagonal() ? 1 : -1) : Integer.compare(p.ordinal(), q.ordinal());
        });
        return options;
    }

    // ------------------------------------------------------------------ known coordinate differences

    /**
     * One axis: which nodes have a known difference of their coordinates (alignments and exact gaps join them), and
     * that difference. A union-find with offsets; joins can be taken back in reverse order.
     */
    static final class Offsets {
        private final int[] parent, size;
        /** coordinate(node) - coordinate(parent). */
        private final double[] toParent;
        private final Deque<Integer> joined = new ArrayDeque<>();

        Offsets(int n) {
            parent = new int[n];
            size = new int[n];
            toParent = new double[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                size[i] = 1;
            }
        }

        int root(int i) {
            while (parent[i] != i) i = parent[i];
            return i;
        }

        private double toRoot(int i) {
            double sum = 0;
            while (parent[i] != i) {
                sum += toParent[i];
                i = parent[i];
            }
            return sum;
        }

        /** coordinate(b) - coordinate(a); NaN if the two are not joined. */
        double difference(int a, int b) {
            return root(a) == root(b) ? toRoot(b) - toRoot(a) : Double.NaN;
        }

        /** coordinate(b) - coordinate(a) = d. The two must not be joined yet. */
        void join(int a, int b, double d) {
            int ra = root(a), rb = root(b);
            double oa = toRoot(a), ob = toRoot(b);
            if (size[ra] < size[rb]) {
                parent[ra] = rb;
                toParent[ra] = ob - d - oa;
                size[rb] += size[ra];
                joined.push(ra);
            } else {
                parent[rb] = ra;
                toParent[rb] = oa + d - ob;
                size[ra] += size[rb];
                joined.push(rb);
            }
        }

        int mark() { return joined.size(); }

        void undo(int mark) {
            while (joined.size() > mark) {
                int r = joined.pop();
                size[parent[r]] -= size[r];
                parent[r] = r;
                toParent[r] = 0;
            }
        }
    }

    /**
     * Minimum distances along one axis, on top of its known differences: node {@code to[i]} lies at least
     * {@code room[i]} behind node {@code from[i]}. They contradict each other if around a circle of them the rooms
     * add up to more than the known differences leave (a chain of nodes on one column whose two ends are held closer
     * together by exact gaps elsewhere than the chain is long). Returns the youngest of the distances on such a
     * circle (the largest index), -1 if all of them fit.
     */
    static int contradictingOrder(Offsets axis, int[] from, int[] to, double[] room) {
        int n = from.length;
        // coordinate(node) = place(root(node)) + offset(node), so a distance is place(b) - place(a) >= weight
        Map<Integer, Integer> places = new HashMap<>();
        int[] a = new int[n], b = new int[n];
        double[] weight = new double[n];
        for (int i = 0; i < n; i++) {
            int ra = axis.root(from[i]), rb = axis.root(to[i]);
            if (!places.containsKey(ra)) places.put(ra, places.size());
            if (!places.containsKey(rb)) places.put(rb, places.size());
            a[i] = places.get(ra);
            b[i] = places.get(rb);
            weight[i] = room[i] - axis.difference(rb, to[i]) + axis.difference(ra, from[i]);
        }
        // longest paths (Bellman-Ford): a place that still moves after as many rounds as there are places lies behind
        // a circle with a positive sum
        int k = places.size(), moved = -1;
        double[] far = new double[k];
        int[] via = new int[k];
        Arrays.fill(via, -1);
        for (int round = 0; round <= k; round++) {
            moved = -1;
            for (int i = 0; i < n; i++) {
                if (far[a[i]] + weight[i] > far[b[i]] + EPS) {
                    far[b[i]] = far[a[i]] + weight[i];
                    via[b[i]] = i;
                    moved = b[i];
                }
            }
            if (moved < 0) return -1;
        }
        int on = moved;
        for (int i = 0; i < k && via[on] >= 0; i++) on = a[via[on]];   // back onto the circle
        int youngest = -1;
        for (int at = on, steps = 0; via[at] >= 0 && steps <= k; steps++) {
            youngest = Math.max(youngest, via[at]);
            at = a[via[at]];
            if (at == on) break;
        }
        return youngest;
    }

    /** What is known about the places of the nodes relative to each other, on both axes, and how large they are. */
    static final class Model {
        final Offsets x, y;
        private final double[] w, h;
        private final double clearance;

        /** {@code clearance}: the free space the solver keeps between two nodes, per axis (centre to centre: half sizes plus this). */
        Model(double[] w, double[] h, double clearance) {
            this.w = w;
            this.h = h;
            this.clearance = clearance;
            x = new Offsets(w.length);
            y = new Offsets(w.length);
        }

        /** coordinate(b) - coordinate(a) = d on the x axis (or the y axis); false, and nothing changes, if that contradicts what is known. */
        boolean fix(boolean onX, int a, int b, double d) {
            Offsets axis = onX ? x : y;
            double known = axis.difference(a, b);
            if (!Double.isNaN(known)) return Math.abs(known - d) <= EPS;
            axis.join(a, b, d);
            return true;
        }

        /**
         * Puts {@code b} in direction {@code d} of {@code a}: straight, both on one line (the same y for E and W, the
         * same x for N and S); diagonal, {@code b} exactly {@code gap} further in x and in y. Returns which of the
         * equalities were new (bit 1: x, bit 2: y; the others followed from what was known), or -1 if the placement
         * contradicts what is known. Take the joins back with {@link #undo} in that case.
         */
        int place(int a, int b, Direction d, double gap) {
            int joins = 0;
            if (d.dy == 0 || d.diagonal()) {
                boolean fresh = Double.isNaN(y.difference(a, b));
                if (!fix(false, a, b, d.dy * gap)) return -1;
                if (fresh) joins |= 2;
            }
            if (d.dx == 0 || d.diagonal()) {
                boolean fresh = Double.isNaN(x.difference(a, b));
                if (!fix(true, a, b, d.dx * gap)) return -1;
                if (fresh) joins |= 1;
            }
            return joins;
        }

        int[] mark() { return new int[]{ x.mark(), y.mark() }; }

        void undo(int[] mark) {
            x.undo(mark[0]);
            y.undo(mark[1]);
        }

        /** The centre distance two nodes need on one diagonal, in x and in y, not to overlap. */
        double diagonalRoom(int a, int b) {
            return Math.min(w[a] + w[b], h[a] + h[b]) / 2 + clearance;
        }

        /** The pairs of nodes whose place relative to each other is known on both axes and whose boxes (with the clearance) overlap. */
        int overlaps() {
            Map<Long, List<Integer>> groups = new HashMap<>();
            for (int i = 0; i < w.length; i++) {
                groups.computeIfAbsent(((long) x.root(i) << 32) | y.root(i), k -> new ArrayList<>()).add(i);
            }
            int count = 0;
            for (List<Integer> group : groups.values()) {
                for (int i = 0; i < group.size(); i++) {
                    for (int j = i + 1; j < group.size(); j++) {
                        int p = group.get(i), q = group.get(j);
                        if (Math.abs(x.difference(p, q)) < (w[p] + w[q]) / 2 + clearance - EPS
                                && Math.abs(y.difference(p, q)) < (h[p] + h[q]) / 2 + clearance - EPS) count++;
                    }
                }
            }
            return count;
        }
    }

    // ------------------------------------------------------------------ alignments

    /** What {@link #alignChain} did: per piece of the chain the equalities that were new (bit 1: x, bit 2: y), and the gap of a diagonal piece. */
    static final class Chain {
        final int[] joins;
        final double gap;

        Chain(int[] joins, double gap) {
            this.joins = joins;
            this.gap = gap;
        }
    }

    /**
     * Puts the nodes of {@code chain} on one line in direction {@code d}, in their order: the first and the last are
     * the two ends, the others lie between them. Straight: all on one row (column). Diagonal: every node the same gap
     * further in x and in y than the one before, {@code measured} in total if the two ends are free, else what is known
     * between them; never less than the nodes need. Returns null, and nothing changes, if the direction is taken at an
     * end ({@code taken}: bits of the directions in which a node has an aligned neighbour; null: not checked), if the
     * line contradicts what is known, or if two nodes would lie on top of each other.
     */
    static Chain alignChain(Model m, int[] taken, int[] chain, Direction d, double measured) {
        int first = chain[0], last = chain[chain.length - 1], pieces = chain.length - 1;
        if (taken != null && ((taken[first] & d.bit()) != 0 || (taken[last] & d.opposite().bit()) != 0)) return null;
        double kx = m.x.difference(first, last), ky = m.y.difference(first, last);
        // what is known between the two ends already has to point this way
        if (d.dx != 0 && !Double.isNaN(kx) && kx * d.dx <= 0) return null;
        if (d.dy != 0 && !Double.isNaN(ky) && ky * d.dy <= 0) return null;
        double gap = 0;
        if (d.diagonal()) {
            double room = 0;
            for (int i = 0; i < pieces; i++) room = Math.max(room, m.diagonalRoom(chain[i], chain[i + 1]));
            double total = !Double.isNaN(kx) ? Math.abs(kx) : !Double.isNaN(ky) ? Math.abs(ky) : Math.max(measured, pieces * room);
            gap = total / pieces;
        }
        int[] mark = m.mark();
        int before = m.overlaps();
        int[] joins = new int[pieces];
        boolean fits = true, any = false;
        for (int i = 0; i < pieces && fits; i++) {
            joins[i] = m.place(chain[i], chain[i + 1], d, gap);
            fits = joins[i] >= 0;
            any |= joins[i] > 0;
        }
        if (fits && any && m.overlaps() > before) fits = false;
        if (!fits) {
            m.undo(mark);
            return null;
        }
        if (taken != null) {
            taken[first] |= d.bit();
            taken[last] |= d.opposite().bit();
            for (int i = 1; i < pieces; i++) taken[chain[i]] |= d.bit() | d.opposite().bit();
        }
        return new Chain(joins, gap);
    }

    /** Two neighbours (the two ends of an edge) and, after {@link #plan}, their alignment. */
    static final class Candidate {
        final int u, w;
        /** The directions from u to w the pair may take, as bits; {@link #ANY}: the choice is free. */
        final int allowed;
        /** From u to w; null: the pair got no alignment. */
        Direction direction;
        /** A diagonal: the distance of the two in x and in y. */
        double gap;
        /** The equalities the pair adds itself (bit 1: x, bit 2: y); the others follow from earlier alignments. */
        int joins;
        /** Degrees between the pair's line and its closest allowed axis. */
        double deviation;
        /** True if the pair did not get its closest axis but the next one. */
        boolean second;
        private List<Direction> options;

        Candidate(int u, int w, int allowed) {
            this.u = u;
            this.w = w;
            this.allowed = allowed;
        }
    }

    /**
     * Step 2: gives every pair of {@code candidates} the alignment its line is closest to, the pairs closest to an axis
     * first. A pair whose axis does not fit what the earlier pairs fixed (see {@link #alignChain}), or, with
     * {@code onePerDirection}, whose direction is taken at one of its nodes ({@code taken}, updated), tries the next
     * sector after all pairs had their first choice; if that does not fit either it stays without alignment. Returns
     * the pairs that got an alignment, in the order they got it.
     */
    static List<Candidate> plan(Model m, double[] x, double[] y, int[] taken, List<Candidate> candidates, boolean onePerDirection) {
        for (Candidate c : candidates) {
            double dx = x[c.w] - x[c.u], dy = y[c.w] - y[c.u];
            c.options = candidates(dx, dy, c.allowed);
            c.deviation = c.options.isEmpty() ? Double.POSITIVE_INFINITY : c.options.get(0).deviation(dx, dy);
        }
        List<Candidate> order = new ArrayList<>(candidates);
        order.sort((a, b) -> Double.compare(a.deviation, b.deviation));   // stable: equal deviations keep the given order
        List<Candidate> accepted = new ArrayList<>();
        for (int choice = 0; choice < (onePerDirection ? 2 : 1); choice++) {
            for (Candidate c : order) {
                if (c.direction != null || c.options.size() <= choice) continue;
                Direction d = c.options.get(choice);
                double dx = x[c.w] - x[c.u], dy = y[c.w] - y[c.u];
                // the distance the two have now: along a diagonal next to their line the mean of their x and y distance
                double measured = d.deviation(dx, dy) <= 45 + 1e-9 ? (Math.abs(dx) + Math.abs(dy)) / 2 : Math.hypot(dx, dy) / Math.sqrt(2);
                Chain chain = alignChain(m, onePerDirection ? taken : null, new int[]{ c.u, c.w }, d, measured);
                if (chain == null) continue;
                c.direction = d;
                c.gap = chain.gap;
                c.joins = chain.joins[0];
                c.second = choice > 0;
                accepted.add(c);
            }
        }
        return accepted;
    }

    // ------------------------------------------------------------------ step 1 and step 5

    /**
     * Step 1: the nodes that go when every hyperedge node ({@code point}) of degree 0 or 1 is taken out together with
     * its edge, again and again until none is left; in the order they go. {@code edges}: one {node, node} per edge.
     */
    static int[] peel(int n, int[][] edges, boolean[] point) {
        int[] degree = new int[n];
        List<List<Integer>> at = new ArrayList<>();
        for (int i = 0; i < n; i++) at.add(new ArrayList<>());
        for (int e = 0; e < edges.length; e++) {
            degree[edges[e][0]]++;
            degree[edges[e][1]]++;
            at.get(edges[e][0]).add(e);
            if (edges[e][1] != edges[e][0]) at.get(edges[e][1]).add(e);
        }
        boolean[] gone = new boolean[n], edgeGone = new boolean[edges.length];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (point[i] && degree[i] <= 1) queue.add(i);
        List<Integer> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (gone[i]) continue;
            gone[i] = true;
            order.add(i);
            for (int e : at.get(i)) {
                if (edgeGone[e]) continue;
                edgeGone[e] = true;
                int other = edges[e][0] == i ? edges[e][1] : edges[e][0];
                if (other == i) continue;
                degree[other]--;
                if (point[other] && !gone[other] && degree[other] <= 1) queue.add(other);
            }
        }
        int[] result = new int[order.size()];
        for (int i = 0; i < result.length; i++) result[i] = order.get(i);
        return result;
    }

    /** True if the straight line from (x1, y1) to (x2, y2) runs through the box around (cx, cy), w wide and h high, grown by margin on every side. */
    static boolean segmentHitsBox(double x1, double y1, double x2, double y2, double cx, double cy, double w, double h, double margin) {
        double dx = x2 - x1, dy = y2 - y1;
        double[] p = { -dx, dx, -dy, dy };
        double[] q = { x1 - (cx - w / 2 - margin), cx + w / 2 + margin - x1, y1 - (cy - h / 2 - margin), cy + h / 2 + margin - y1 };
        double enter = 0, leave = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return false;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) enter = Math.max(enter, t);
                else leave = Math.min(leave, t);
                if (enter > leave) return false;
            }
        }
        return true;
    }
}
