package layout.sbgn;

import org.AttributeHelper;
import org.adaptagrams.Rectangle;
import org.adaptagrams.RectanglePtrs;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.*;

/**
 * Places the disconnected components of the layout graph after each cola run: cola's ConstrainedFDLayout has no force
 * between two nodes without a connecting path, so a small component would keep its input centroid and end up inside a
 * large one. Every smaller component is moved rigidly to the grid position with the fewest straight-line crossings and
 * glyph hits against the larger components already placed.
 */
public final class ComponentTools {
    /** -Dcblayout.packComponents=place|off (default place): place moves the components apart; off leaves them. */
    static final boolean PLACE = "place".equalsIgnoreCase(System.getProperty("cblayout.packComponents", "place"));
    /**
     * -Dcblayout.clonePick=rarest|nearest (default rarest): the clone pair that breaks ties between positions is the
     * one with the rarest label (currency metabolites such as H+ or ATP say little), or the one drawn closest.
     */
    static final String PICK = System.getProperty("cblayout.clonePick", "rarest").toLowerCase();
    private static final double GAP = 40;          // free margin around the fixed part in the search grid
    private static final double STEP = 20;         // grid step of the translation search
    private static final double W_CROSS = 1000;    // cost of one straight-line crossing
    private static final double W_THRU = 500;      // cost of one segment through a glyph

    private static Graph original;
    private static final List<Node[]> clonePairs = new ArrayList<>();

    private ComponentTools() { }

    /**
     * Called after the skeleton is built: remembers the layout graph and picks one clone pair (same role, label and
     * compartment) per pair of components.
     */
    static void setContext(Graph graph) {
        // Only an active placement holds the graph, so with the switch off a closed graph is not kept alive here.
        original = null;
        clonePairs.clear();
        if (!PLACE || !ConstraintSBGNLayout.fdVariant()) return;
        original = graph;
        List<List<Node>> comps = components(graph);
        Map<Node, Integer> compOf = new HashMap<>();
        for (int i = 0; i < comps.size(); i++) for (Node n : comps.get(i)) compOf.put(n, i);
        Map<String, List<Node>> byKey = new LinkedHashMap<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(graph)) {
            String k = cloneKey(n);
            if (k != null && comps.get(compOf.get(n)).size() >= 2) byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }
        Map<String, Integer> freq = new HashMap<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(graph)) {
            String k = cloneKey(n);
            if (k != null) freq.merge(k.substring(0, k.lastIndexOf('|')), 1, Integer::sum);
        }
        Map<String, Node[]> chosen = new LinkedHashMap<>();
        for (List<Node> group : byKey.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Node a = group.get(i), b = group.get(j);
                    int ca = compOf.get(a), cb = compOf.get(b);
                    if (ca == cb) continue;
                    String key = Math.min(ca, cb) + ":" + Math.max(ca, cb);
                    Node[] cur = chosen.get(key);
                    boolean better = cur == null;
                    if (!better && "rarest".equals(PICK)) {
                        int fa = freq.get(labelKey(a)), fc = freq.get(labelKey(cur[0]));
                        better = fa < fc || (fa == fc && dist(a, b) < dist(cur[0], cur[1]));
                    } else if (!better) {
                        better = dist(a, b) < dist(cur[0], cur[1]);
                    }
                    if (better) chosen.put(key, new Node[]{a, b});
                }
            }
        }
        clonePairs.addAll(chosen.values());
    }

    /** Connected components of the layout graph (constraint marker edges ignored), largest first. */
    static List<List<Node>> components(Graph g) {
        List<Node> nodes = ConstraintSBGNLayout.layoutNodes(g);
        Map<Node, Node> parent = new HashMap<>();
        for (Node n : nodes) parent.put(n, n);
        for (Edge e : ConstraintSBGNLayout.layoutEdges(g)) {
            Node a = find(parent, e.getSource()), b = find(parent, e.getTarget());
            if (a != b) parent.put(a, b);
        }
        Map<Node, List<Node>> byRoot = new LinkedHashMap<>();
        for (Node n : nodes) byRoot.computeIfAbsent(find(parent, n), k -> new ArrayList<>()).add(n);
        List<List<Node>> res = new ArrayList<>(byRoot.values());
        res.sort((x, y) -> Integer.compare(y.size(), x.size()));
        return res;
    }

    private static Node find(Map<Node, Node> p, Node x) {
        while (p.get(x) != x) { p.put(x, p.get(p.get(x))); x = p.get(x); }
        return x;
    }

    private static String labelKey(Node n) { String k = cloneKey(n); return k.substring(0, k.lastIndexOf('|')); }

    private static String cloneKey(Node n) {
        String role = ConstraintSBGNLayout.getSBGNRole(n);
        if (role == null || role.isEmpty() || role.contains("PROCESS") || role.contains("ASSOCIATION")
                || role.contains("SUBMAP") || role.contains("TAG") || role.contains("COMPARTMENT")) return null;
        String label = AttributeHelper.getLabel(n, "");
        if (label == null || label.trim().isEmpty()) return null;
        Object comp = AttributeHelper.getAttributeValue(n, "sbgn", "compartmentRef", "", "", false);
        return role + "|" + label + "|" + comp;
    }

    private static double dist(Node a, Node b) {
        return Math.hypot(AttributeHelper.getPositionX(a) - AttributeHelper.getPositionX(b),
                AttributeHelper.getPositionY(a) - AttributeHelper.getPositionY(b));
    }

    /**
     * After a cola run on the layout graph, before makeFeasible: moves whole components; ties go to the position that
     * brings a clone glyph closest to its partner clone, then to the shortest shift. Skeleton passes are left alone.
     */
    public static void afterRun(Graph g, Map<Node, Integer> index, RectanglePtrs rects) {
        if (!PLACE || g != original || !ConstraintSBGNLayout.fdVariant()) return;
        List<List<Node>> comps = new ArrayList<>();
        for (List<Node> c : components(g)) if (c.size() >= 2) comps.add(c);   // singletons are complex children / tags
        if (comps.size() < 2) return;
        place(g, comps, index, rects);
    }

    private static double[] bounds(Collection<Node> nodes, Map<Node, Integer> index, RectanglePtrs rects) {
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (Node n : nodes) {
            Integer i = index.get(n);
            if (i == null) continue;
            Rectangle r = rects.get(i);
            x0 = Math.min(x0, r.getMinX()); x1 = Math.max(x1, r.getMaxX());
            y0 = Math.min(y0, r.getMinY()); y1 = Math.max(y1, r.getMaxY());
        }
        return new double[]{x0, y0, x1, y1};
    }

    private static boolean cross(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double d1 = orient(ax, ay, bx, by, cx, cy), d2 = orient(ax, ay, bx, by, dx, dy);
        double d3 = orient(cx, cy, dx, dy, ax, ay), d4 = orient(cx, cy, dx, dy, bx, by);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double orient(double px, double py, double qx, double qy, double rx, double ry) {
        return (qx - px) * (ry - py) - (qy - py) * (rx - px);
    }

    /** Liang-Barsky: does segment a-b pass through the box {x0,y0,x1,y1}? */
    private static boolean hits(double ax, double ay, double bx, double by, double[] box) {
        double t0 = 0, t1 = 1, dx = bx - ax, dy = by - ay;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - box[0], box[2] - ax, ay - box[1], box[3] - ay};
        for (int k = 0; k < 4; k++) {
            if (Math.abs(p[k]) < 1e-12) {
                if (q[k] < 0) return false;
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) t0 = Math.max(t0, t); else t1 = Math.min(t1, t);
                if (t0 > t1) return false;
            }
        }
        return t1 - t0 > 1e-6;
    }

    private static void place(Graph g, List<List<Node>> comps, Map<Node, Integer> index, RectanglePtrs rects) {
        List<Node> fixed = new ArrayList<>(comps.get(0));
        Set<Node> fixedSet = new HashSet<>(fixed);
        List<Edge> es = new ArrayList<>();
        for (Edge e : ConstraintSBGNLayout.layoutEdges(g)) es.add(e);
        for (int ci = 1; ci < comps.size(); ci++) {
            List<Node> comp = comps.get(ci);
            Set<Node> in = new HashSet<>(comp);
            Node mine = null, partner = null;
            for (Node[] p : clonePairs) {
                if (in.contains(p[0]) && fixedSet.contains(p[1])) { mine = p[0]; partner = p[1]; break; }
                if (in.contains(p[1]) && fixedSet.contains(p[0])) { mine = p[1]; partner = p[0]; break; }
            }
            List<double[]> fSeg = new ArrayList<>(), cSeg = new ArrayList<>();
            for (Edge e : es) {
                Integer s = index.get(e.getSource()), t = index.get(e.getTarget());
                if (s == null || t == null) continue;
                double[] seg = {rects.get(s).getCentreX(), rects.get(s).getCentreY(), rects.get(t).getCentreX(), rects.get(t).getCentreY()};
                if (fixedSet.contains(e.getSource()) && fixedSet.contains(e.getTarget())) fSeg.add(seg);
                else if (in.contains(e.getSource()) && in.contains(e.getTarget())) cSeg.add(seg);
            }
            List<double[]> fBox = new ArrayList<>(), cBox = new ArrayList<>();
            for (Node n : fixed) { Rectangle r = rects.get(index.get(n)); fBox.add(new double[]{r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY()}); }
            for (Node n : comp) { Rectangle r = rects.get(index.get(n)); cBox.add(new double[]{r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY()}); }
            double[] b = bounds(comp, index, rects), all = bounds(fixed, index, rects);
            double w = b[2] - b[0], h = b[3] - b[1];
            double mx = 0, my = 0, px = 0, py = 0;
            if (mine != null) {
                mx = rects.get(index.get(mine)).getCentreX(); my = rects.get(index.get(mine)).getCentreY();
                px = rects.get(index.get(partner)).getCentreX(); py = rects.get(index.get(partner)).getCentreY();
            }
            // Staying put, then a grid around and inside the fixed part. All cost terms are non-negative, so a shift is
            // dropped once its partial cost cannot beat the best; this and the bucket grids never change the result.
            double extent = Math.max(Math.max(all[2] - all[0], all[3] - all[1]), Math.max(w, h));
            Buckets boxIdx = new Buckets(fBox, false, extent), segIdx = new Buckets(fSeg, true, extent);
            Search s = new Search(cBox, cSeg, fBox, fSeg, boxIdx, segIdx);
            double bestCost = Double.POSITIVE_INFINITY, bdx = 0, bdy = 0;
            double sx0 = all[0] - w - GAP - b[0], sx1 = all[2] + GAP - b[0];
            double sy0 = all[1] - h - GAP - b[1], sy1 = all[3] + GAP - b[1];
            {
                double d = mine != null ? Math.hypot(mx - px, my - py) : 0;
                double cost = s.cost(0, 0, d, bestCost);
                if (cost < bestCost - 1e-9) { bestCost = cost; }
            }
            for (double dx = sx0; dx <= sx1; dx += STEP) {
                for (double dy = sy0; dy <= sy1; dy += STEP) {
                    double d = mine != null ? Math.hypot(mx + dx - px, my + dy - py) : Math.hypot(dx, dy);
                    if (d >= bestCost - 1e-9) continue;
                    double cost = s.cost(dx, dy, d, bestCost);
                    if (cost < bestCost - 1e-9) { bestCost = cost; bdx = dx; bdy = dy; }
                }
            }
            for (Node n : comp) {
                Rectangle r = rects.get(index.get(n));
                r.moveCentre(r.getCentreX() + bdx, r.getCentreY() + bdy);
            }
            fixed.addAll(comp);
            fixedSet.addAll(comp);
        }
    }

    /** Cost of one shift of the component against the fixed part, or +infinity once it cannot go below {@code bestCost - 1e-9}. */
    private static final class Search {
        final List<double[]> cBox, cSeg, fBox, fSeg;
        final Buckets boxIdx, segIdx;

        Search(List<double[]> cBox, List<double[]> cSeg, List<double[]> fBox, List<double[]> fSeg, Buckets boxIdx, Buckets segIdx) {
            this.cBox = cBox; this.cSeg = cSeg; this.fBox = fBox; this.fSeg = fSeg; this.boxIdx = boxIdx; this.segIdx = segIdx;
        }

        double cost(double dx, double dy, double d, double bestCost) {
            double limit = bestCost - 1e-9;
            int ovl = 0, crs = 0, thru = 0;
            for (double[] cb : cBox) {
                double[] m = {cb[0] + dx, cb[1] + dy, cb[2] + dx, cb[3] + dy};
                int n = boxIdx.query(m[0], m[1], m[2], m[3]);
                for (int k = 0; k < n; k++) {
                    double[] fb = fBox.get(boxIdx.buf[k]);
                    if (m[0] < fb[2] && fb[0] < m[2] && m[1] < fb[3] && fb[1] < m[3]) ovl++;
                }
                n = segIdx.query(m[0], m[1], m[2], m[3]);
                for (int k = 0; k < n; k++) {
                    double[] fs = fSeg.get(segIdx.buf[k]);
                    if (hits(fs[0], fs[1], fs[2], fs[3], m)) thru++;
                }
                if (ovl * 1e6 + crs * W_CROSS + thru * W_THRU + d >= limit) return Double.POSITIVE_INFINITY;
            }
            for (double[] cs : cSeg) {
                double ax = cs[0] + dx, ay = cs[1] + dy, bx = cs[2] + dx, by = cs[3] + dy;
                double x0 = Math.min(ax, bx), y0 = Math.min(ay, by), x1 = Math.max(ax, bx), y1 = Math.max(ay, by);
                int n = segIdx.query(x0, y0, x1, y1);
                for (int k = 0; k < n; k++) {
                    double[] fs = fSeg.get(segIdx.buf[k]);
                    if (cross(ax, ay, bx, by, fs[0], fs[1], fs[2], fs[3])) crs++;
                }
                n = boxIdx.query(x0, y0, x1, y1);
                for (int k = 0; k < n; k++) {
                    if (hits(ax, ay, bx, by, fBox.get(boxIdx.buf[k]))) thru++;
                }
                if (ovl * 1e6 + crs * W_CROSS + thru * W_THRU + d >= limit) return Double.POSITIVE_INFINITY;
            }
            return ovl * 1e6 + crs * W_CROSS + thru * W_THRU + d;
        }
    }

    /**
     * Uniform bucket grid over boxes {x0, y0, x1, y1} or segments {ax, ay, bx, by} (by their bounding box).
     * A query returns every item whose padded bounding box shares a bucket with the padded query box, a
     * superset of the items an overlap, hit or crossing test can accept.
     */
    private static final class Buckets {
        private static final double PAD = 1.0;
        final double cell;
        final Map<Long, List<Integer>> map = new HashMap<>();
        final List<Integer> always = new ArrayList<>();   // items with non-finite coordinates: tested every time
        final int[] stamp, buf;
        final int size;
        int clock;

        Buckets(List<double[]> items, boolean segments, double extent) {
            size = items.size();
            cell = Math.max(80, (Double.isFinite(extent) ? extent : 0) / 256);
            stamp = new int[size];
            buf = new int[size];
            for (int i = 0; i < size; i++) {
                double[] it = items.get(i);
                double x0 = segments ? Math.min(it[0], it[2]) : it[0], x1 = segments ? Math.max(it[0], it[2]) : it[2];
                double y0 = segments ? Math.min(it[1], it[3]) : it[1], y1 = segments ? Math.max(it[1], it[3]) : it[3];
                if (!finite(x0, y0, x1, y1)) { always.add(i); continue; }
                for (int ix = idx(x0 - PAD); ix <= idx(x1 + PAD); ix++)
                    for (int iy = idx(y0 - PAD); iy <= idx(y1 + PAD); iy++)
                        map.computeIfAbsent(key(ix, iy), k -> new ArrayList<>()).add(i);
            }
        }

        private static boolean finite(double... v) {
            for (double x : v) if (!Double.isFinite(x) || Math.abs(x) > 1e9) return false;
            return true;
        }

        private int idx(double v) { return (int) Math.floor(v / cell); }

        private static long key(int ix, int iy) { return ((long) ix << 32) ^ (iy & 0xffffffffL); }

        /** Fills {@link #buf} with the candidate items for the box and returns their number. */
        int query(double x0, double y0, double x1, double y1) {
            if (!finite(x0, y0, x1, y1) || (idx(x1 + PAD) - idx(x0 - PAD) + 1L) * (idx(y1 + PAD) - idx(y0 - PAD) + 1L) > 4L * size + 64) {
                for (int i = 0; i < size; i++) buf[i] = i;
                return size;
            }
            clock++;
            int n = 0;
            for (int i : always) buf[n++] = i;
            for (int ix = idx(x0 - PAD); ix <= idx(x1 + PAD); ix++) {
                for (int iy = idx(y0 - PAD); iy <= idx(y1 + PAD); iy++) {
                    List<Integer> l = map.get(key(ix, iy));
                    if (l == null) continue;
                    for (int i : l) if (stamp[i] != clock) { stamp[i] = clock; buf[n++] = i; }
                }
            }
            return n;
        }
    }
}
