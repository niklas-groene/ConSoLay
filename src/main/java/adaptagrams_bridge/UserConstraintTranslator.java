package adaptagrams_bridge;

import constraints.Axis;
import constraints.BoundaryConstraintGraph;
import constraints.ClusterConstraintGraph;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.DesiredPositionConstraintGraph;
import constraints.DistributionConstraintGraph;
import constraints.FixedRelativeConstraintGraph;
import constraints.GridPattern;
import constraints.HorizontalConstraint;
import constraints.LinePattern;
import constraints.LockConstraintGraph;
import constraints.MultiSeparationConstraintGraph;
import constraints.OrthogonalEdgeConstraintGraph;
import constraints.PageBoundaryConstraintGraph;
import constraints.SeparationConstraintGraph;
import constraints.StarPattern;
import constraints.VerticalConstraint;

import org.AttributeHelper;
import org.adaptagrams.AlignmentConstraint;
import org.adaptagrams.BoundaryConstraint;
import org.adaptagrams.ColaDesiredPositions;
import org.adaptagrams.ColaLocks;
import org.adaptagrams.CompoundConstraint;
import org.adaptagrams.CompoundConstraintPtrs;
import org.adaptagrams.DesiredPosition;
import org.adaptagrams.Dim;
import org.adaptagrams.DistributionConstraint;
import org.adaptagrams.FixedRelativeConstraint;
import org.adaptagrams.Lock;
import org.adaptagrams.MultiSeparationConstraint;
import org.adaptagrams.OrthogonalEdgeConstraint;
import org.adaptagrams.PageBoundaryConstraints;
import org.adaptagrams.Rectangle;
import org.adaptagrams.RectanglePtrs;
import org.adaptagrams.SeparationConstraint;
import org.adaptagrams.Unsigneds;
import org.graffiti.graph.Node;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns model constraints into cola objects for one solve of {@link CBLayoutAlgorithm#executeUserConstraints}: an
 * optional force-directed {@code run()}, then the projection {@code makeFeasible()}, which skips page boundaries and
 * orthogonal edges and ignores locks and desired positions. Those types get their native translation in
 * {@link #runConstraints} for the run and an equivalent of alignments and separations in {@link #finalConstraints} for
 * the projection; every other type is in both lists, and clusters become dividing lines for the projection only
 * ({@link #excludeFromClusters}). Every cola object is registered with its model constraint ({@link #owner}) so that
 * solver reports can be traced back by pointer.
 *
 * @author Niklas Gröne
 */
final class UserConstraintTranslator {

    private final Map<Node, Integer> index;
    private final RectanglePtrs rects;
    private final boolean forceDirected;

    /** Constraints handed to cola for the force-directed run. */
    final CompoundConstraintPtrs runConstraints = new CompoundConstraintPtrs();
    /** Constraints handed to cola for the final projection. */
    final CompoundConstraintPtrs finalConstraints = new CompoundConstraintPtrs();
    /** True if the two lists differ, i.e. makeFeasible needs its own list. */
    boolean separateFinalList;
    /** Locks for the run (a PreIteration is only needed if not empty). */
    final ColaLocks locks = new ColaLocks();
    /** Desired positions for the run. */
    final ColaDesiredPositions desired = new ColaDesiredPositions();
    /** True if there are user clusters: {@link #excludeFromClusters} makes their lines. */
    boolean hasClusters;
    /** The user clusters, for {@link #excludeFromClusters}. */
    private final List<ClusterConstraintGraph> clusters = new ArrayList<>();
    /** Model constraints that were translated (all their nodes are in the solve). */
    final List<Constraint> translated = new ArrayList<>();
    /** Native pointer of every cola object → the model constraint it belongs to. */
    private final Map<Long, Constraint> owner = new HashMap<>();

    UserConstraintTranslator(Map<Node, Integer> index, RectanglePtrs rects, boolean forceDirected) {
        this.index = index;
        this.rects = rects;
        this.forceDirected = forceDirected;
    }

    /** The nodes of the solve. */
    Collection<Node> nodes() {
        return index.keySet();
    }

    /** The model constraint a cola constraint (e.g. from a solver report) was made for, or null. */
    Constraint ownerOf(CompoundConstraint cc) {
        return cc == null ? null : owner.get(pointer(cc));
    }

    // ------------------------------------------------------------------ first pass: positions

    /**
     * Moves the rectangles before any constraint reads them. A rigid group is put back into its shape, anchored on a
     * pinned member if it has one, else (without a force-directed run) on its members' targets, else on its current
     * centroid. Then, without a run, the other nodes with a target are put on it (the run would pull them there), and
     * pinned nodes go onto their pins. A node with several targets takes the one of the highest
     * {@link ConstraintConflicts#RANK rank}, as the conflict decision does.
     */
    void prepare(Collection<Constraint> cs) {
        Map<Node, double[]> pins = new HashMap<>(), targets = new HashMap<>();
        List<Constraint> wishes = new ArrayList<>();
        for (Constraint c : cs) {
            if (!known(c)) continue;
            if (c instanceof LockConstraintGraph) {
                for (Node n : c.getNodes()) pins.put(n, ((LockConstraintGraph) c).getPosition(n));
            } else if (c instanceof DesiredPositionConstraintGraph) {
                wishes.add(c);
            }
        }
        wishes.sort(ConstraintConflicts.RANK);
        for (Constraint c : wishes) {
            for (Node n : c.getNodes()) targets.putIfAbsent(n, ((DesiredPositionConstraintGraph) c).getTarget(n));
        }
        Set<Node> placed = new HashSet<>();
        for (Constraint c : cs) {
            if (known(c) && c instanceof FixedRelativeConstraintGraph) {
                placeRigid((FixedRelativeConstraintGraph) c, pins, forceDirected ? null : targets, placed);
            }
        }
        if (!forceDirected) {
            // placeRigid has taken a group member's target into account; moving the member alone would bend the shape
            for (Map.Entry<Node, double[]> e : targets.entrySet()) {
                if (!placed.contains(e.getKey())) moveTo(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<Node, double[]> e : pins.entrySet()) moveTo(e.getKey(), e.getValue());
    }

    /**
     * Puts the members of a rigid group into their stored arrangement: the shape (offsets from the group centroid at
     * creation) is moved so that a pinned member sits on its pin, or else the targeted members' mean sits on their
     * targets' mean, or else the shape keeps the group's current centroid. The members put into the shape are added to
     * {@code placed}.
     */
    private void placeRigid(FixedRelativeConstraintGraph f, Map<Node, double[]> pins, Map<Node, double[]> targets,
                            Set<Node> placed) {
        double cx = 0, cy = 0, mx = 0, my = 0;
        int k = 0;
        for (Node n : f.getNodes()) {
            double[] o = f.getOffset(n);
            if (o == null) continue;
            Rectangle r = rects.get(index.get(n));
            cx += r.getCentreX();
            cy += r.getCentreY();
            mx += o[0];
            my += o[1];
            k++;
        }
        if (k < 2) return;
        cx /= k; cy /= k; mx /= k; my /= k;
        // the shape's origin (the stored centroid) ends up at (ox, oy)
        double ox = cx, oy = cy;
        boolean anchored = false;
        for (Node n : f.getNodes()) {
            double[] o = f.getOffset(n), p = pins.get(n);
            if (o == null || p == null) continue;
            ox = p[0] - (o[0] - mx);
            oy = p[1] - (o[1] - my);
            anchored = true;
            break;
        }
        if (!anchored && targets != null) {
            double tx = 0, ty = 0, sx = 0, sy = 0;
            int t = 0;
            for (Node n : f.getNodes()) {
                double[] o = f.getOffset(n), p = targets.get(n);
                if (o == null || p == null) continue;
                tx += p[0]; ty += p[1]; sx += o[0] - mx; sy += o[1] - my;
                t++;
            }
            if (t > 0) { ox = (tx - sx) / t; oy = (ty - sy) / t; }
        }
        for (Node n : f.getNodes()) {
            double[] o = f.getOffset(n);
            if (o == null) continue;
            rects.get(index.get(n)).moveCentre(ox + o[0] - mx, oy + o[1] - my);
            placed.add(n);
        }
    }

    private void moveTo(Node n, double[] p) {
        if (p != null) rects.get(index.get(n)).moveCentre(p[0], p[1]);
    }

    // ------------------------------------------------------------------ second pass: constraints

    /** Translates {@code c} if all its nodes take part in the solve; returns whether it did. */
    boolean add(Constraint c) {
        if (!known(c)) return false;
        translated.add(c);
        if (c instanceof HorizontalConstraint) {
            // guideline + offset == centre: top edges +height / 2, bottom edges -height / 2 (as CBLayoutAlgorithm.createAlign*)
            HorizontalConstraint.HorizontalConstraintType t = ((HorizontalConstraint) c).getType();
            double sign = t == HorizontalConstraint.HorizontalConstraintType.TOP ? 1 : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? -1 : 0;
            both(alignment(c.getNodes(), Dim.YDIM, sign), c);
        } else if (c instanceof VerticalConstraint) {
            VerticalConstraint.VerticalConstraintType t = ((VerticalConstraint) c).getType();
            double sign = t == VerticalConstraint.VerticalConstraintType.LEFT ? 1 : t == VerticalConstraint.VerticalConstraintType.RIGHT ? -1 : 0;
            both(alignment(c.getNodes(), Dim.XDIM, sign), c);
        } else if (c instanceof SeparationConstraintGraph) {
            SeparationConstraintGraph s = (SeparationConstraintGraph) c;
            if (c.getNodes().size() != 2) { translated.remove(c); return false; }
            both(s.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                    ? CBLayoutAlgorithm.createSeparationX(s.getDistance(), c.getNodes().get(0), c.getNodes().get(1), s.isFixed())
                    : CBLayoutAlgorithm.createSeparationY(s.getDistance(), c.getNodes().get(0), c.getNodes().get(1), s.isFixed()), c);
        } else if (c instanceof DistributionConstraintGraph) {
            DistributionConstraintGraph d = (DistributionConstraintGraph) c;
            int dim = dim(Axis.of(d.getType()));
            List<AlignmentConstraint> guides = guides(c.getNodes(), dim, c);
            DistributionConstraint dc = new DistributionConstraint(dim);
            for (int i = 1; i < guides.size(); i++) dc.addAlignmentPair(guides.get(i - 1), guides.get(i));
            dc.setSeparation(d.getSpacing());   // libcola does not initialise it
            both(dc, c);
        } else if (c instanceof MultiSeparationConstraintGraph) {
            MultiSeparationConstraintGraph m = (MultiSeparationConstraintGraph) c;
            both(order(guides(c.getNodes(), dim(m.getAxis()), c), dim(m.getAxis()), m.getGap(), m.isExact()), c);
        } else if (c instanceof FixedRelativeConstraintGraph) {
            Unsigneds ids = new Unsigneds();
            for (Node n : c.getNodes()) ids.add((long) index.get(n));
            if (ids.size() < 2) { translated.remove(c); return false; }   // cola asserts at least two
            both(new FixedRelativeConstraint(rects, ids, ((FixedRelativeConstraintGraph) c).isFixedPosition()), c);
        } else if (c instanceof BoundaryConstraintGraph) {
            addBoundary((BoundaryConstraintGraph) c);
        } else if (c instanceof PageBoundaryConstraintGraph) {
            addArea((PageBoundaryConstraintGraph) c);
        } else if (c instanceof OrthogonalEdgeConstraintGraph) {
            OrthogonalEdgeConstraintGraph o = (OrthogonalEdgeConstraintGraph) c;
            int dim = o.isHorizontal() ? Dim.YDIM : Dim.XDIM;   // same y: horizontal edge
            long a = index.get(c.getNodes().get(0)), b = index.get(c.getNodes().get(1));
            runOnly(new OrthogonalEdgeConstraint(dim, a, b), c);
            AlignmentConstraint line = new AlignmentConstraint(dim, meanCentre(c.getNodes(), dim));   // makeFeasible skips the native one
            line.addShape(a, 0);
            line.addShape(b, 0);
            finalOnly(line, c);
        } else if (c instanceof ClusterConstraintGraph) {
            // Not a libcola cluster: its containment is added unchecked and can break other constraints, and it keeps
            // other nodes out only through the non-overlap. The lines of excludeFromClusters do both.
            hasClusters = true;
            clusters.add((ClusterConstraintGraph) c);
        } else if (c instanceof LockConstraintGraph) {
            LockConstraintGraph l = (LockConstraintGraph) c;
            for (Node n : c.getNodes()) {
                double[] p = l.getPosition(n);
                if (p == null) continue;
                locks.add(new Lock(index.get(n), p[0], p[1]));
                finalOnly(fixedGuide(n, Dim.XDIM, p[0]), c);   // makeFeasible ignores locks
                finalOnly(fixedGuide(n, Dim.YDIM, p[1]), c);
            }
        } else if (c instanceof DesiredPositionConstraintGraph) {
            DesiredPositionConstraintGraph d = (DesiredPositionConstraintGraph) c;
            if (forceDirected) {
                for (Node n : c.getNodes()) {
                    double[] p = d.getTarget(n);
                    if (p == null) continue;
                    DesiredPosition dp = new DesiredPosition();
                    dp.setId(index.get(n));
                    dp.setX(p[0]);
                    dp.setY(p[1]);
                    // cola's stress terms scale with 1 / (ideal edge length)^2: without this even 0.1 pins the node
                    dp.setWeight(d.getWeight() / ((double) CBLayoutAlgorithm.dist * CBLayoutAlgorithm.dist));
                    desired.add(dp);
                }
            }   // without a run, prepare() put the nodes on their targets
        } else if (c instanceof LinePattern) {
            LinePattern l = (LinePattern) c;
            // the line: same centre y (row) / x (column); the order along it: one guideline per node
            line(c.getNodes(), l.isRow() ? Dim.YDIM : Dim.XDIM, c);
            int dim = dim(l.getAxis());
            both(order(guides(c.getNodes(), dim, c), dim, l.getGap(), l.isEven()), c);
        } else if (c instanceof GridPattern) {
            GridPattern g = (GridPattern) c;
            List<AlignmentConstraint> rowLines = new ArrayList<>(), columnLines = new ArrayList<>();
            for (List<Node> row : g.getRows()) rowLines.add(line(row, Dim.YDIM, c));
            for (List<Node> col : g.getColumns()) columnLines.add(line(col, Dim.XDIM, c));
            if (columnLines.size() > 1) both(order(columnLines, Dim.XDIM, g.getColumnGap(), false), c);
            if (rowLines.size() > 1) both(order(rowLines, Dim.YDIM, g.getRowGap(), false), c);
        } else if (c instanceof StarPattern) {
            StarPattern s = (StarPattern) c;
            Node hub = s.getHub();
            for (int i = 0; i < s.getRing().size(); i++) {
                Node leaf = s.getRing().get(i);
                double[] o = s.offset(i);
                both(o[0] >= 0 ? CBLayoutAlgorithm.createSeparationX(o[0], hub, leaf, true)
                        : CBLayoutAlgorithm.createSeparationX(-o[0], leaf, hub, true), c);
                both(o[1] >= 0 ? CBLayoutAlgorithm.createSeparationY(o[1], hub, leaf, true)
                        : CBLayoutAlgorithm.createSeparationY(-o[1], leaf, hub, true), c);
            }
        } else {
            translated.remove(c);
            return false;
        }
        return true;
    }

    /**
     * Keeps the other nodes out of the user clusters. libcola does that only through its non-overlap constraints, which
     * rank below every constraint and exist only with non-overlap on; here every node that is not a member stays on the
     * side of the cluster {@link ConstraintConflicts} chose for it ({@code sides}, which fit the constraints ranked above
     * the cluster): per side a dividing line has the members (rectangle plus padding) on the one side and those nodes
     * (rectangle plus margin) on the other, the distances libcola keeps. Linear in the number of nodes, no node pairs.
     * For the projection only, like the cluster hierarchy.
     */
    void excludeFromClusters(Map<ClusterConstraintGraph, Map<Node, ConstraintConflicts.Side>> sides, double rectPad) {
        for (ClusterConstraintGraph cl : clusters) {
            Map<Node, ConstraintConflicts.Side> side = sides.get(cl);
            if (side == null || side.isEmpty()) continue;
            double pad = cl.getPadding(), margin = cl.getMargin();
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (Node n : cl.getNodes()) {
                minX = Math.min(minX, centre(n, Dim.XDIM) - padded(n, Dim.XDIM, rectPad) - pad);
                maxX = Math.max(maxX, centre(n, Dim.XDIM) + padded(n, Dim.XDIM, rectPad) + pad);
                minY = Math.min(minY, centre(n, Dim.YDIM) - padded(n, Dim.YDIM, rectPad) - pad);
                maxY = Math.max(maxY, centre(n, Dim.YDIM) + padded(n, Dim.YDIM, rectPad) + pad);
            }
            for (ConstraintConflicts.Side s : ConstraintConflicts.Side.values()) {
                List<Node> outside = new ArrayList<>();
                for (Map.Entry<Node, ConstraintConflicts.Side> e : side.entrySet()) {
                    if (e.getValue() == s && index.containsKey(e.getKey())) outside.add(e.getKey());
                }
                if (outside.isEmpty()) continue;
                int dim = s == ConstraintConflicts.Side.LEFT || s == ConstraintConflicts.Side.RIGHT ? Dim.XDIM : Dim.YDIM;
                boolean outsideFirst = s == ConstraintConflicts.Side.LEFT || s == ConstraintConflicts.Side.ABOVE;
                BoundaryConstraint line = new BoundaryConstraint(dim);   // negative offset: before the line
                for (Node n : outside) {
                    double off = padded(n, dim, rectPad) + margin;
                    line.addShape(index.get(n), outsideFirst ? -off : off);
                }
                for (Node n : cl.getNodes()) {
                    double off = padded(n, dim, rectPad) + pad;
                    line.addShape(index.get(n), outsideFirst ? off : -off);
                }
                line.setPosition(s == ConstraintConflicts.Side.LEFT ? minX : s == ConstraintConflicts.Side.RIGHT ? maxX
                        : s == ConstraintConflicts.Side.ABOVE ? minY : maxY);
                finalOnly(line, cl);
            }
        }
    }

    /** Half the size of the node's rectangle, {@code rectPad} larger per side (the value the resolution used). */
    private static double padded(Node n, int dim, double rectPad) {
        return (dim == Dim.XDIM ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n)) / 2 + rectPad;
    }

    /** A vertical (x) or horizontal (y) dividing line; each node keeps its half size plus the gap from it. */
    private void addBoundary(BoundaryConstraintGraph b) {
        Axis a = b.getAxis();
        int dim = dim(a);
        BoundaryConstraint bc = new BoundaryConstraint(dim);
        double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
        for (Node n : b.getBefore()) {
            double off = half(n, a) + b.getGap();
            bc.addShape(index.get(n), -off);   // negative: centre + off <= line
            low = Math.max(low, centre(n, dim) + off);
        }
        for (Node n : b.getAfter()) {
            double off = half(n, a) + b.getGap();
            bc.addShape(index.get(n), off);    // positive: line + off <= centre
            high = Math.min(high, centre(n, dim) - off);
        }
        if (!Double.isInfinite(low) && !Double.isInfinite(high)) bc.setPosition((low + high) / 2);
        both(bc, b);
    }

    /**
     * The native PageBoundaryConstraints for the run; for the projection four fixed guidelines (the walls) and, per
     * node, a guideline through its centre kept half its size inside the walls.
     */
    private void addArea(PageBoundaryConstraintGraph p) {
        PageBoundaryConstraints pb = new PageBoundaryConstraints(p.getMinX(), p.getMaxX(), p.getMinY(), p.getMaxY(), 100);
        for (Node n : p.getNodes()) pb.addShape(index.get(n), AttributeHelper.getWidth(n) / 2, AttributeHelper.getHeight(n) / 2);
        runOnly(pb, p);

        AlignmentConstraint left = wall(Dim.XDIM, p.getMinX(), p), right = wall(Dim.XDIM, p.getMaxX(), p);
        AlignmentConstraint top = wall(Dim.YDIM, p.getMinY(), p), bottom = wall(Dim.YDIM, p.getMaxY(), p);
        for (Node n : p.getNodes()) {
            AlignmentConstraint gx = guide(n, Dim.XDIM), gy = guide(n, Dim.YDIM);
            finalOnly(gx, p);
            finalOnly(gy, p);
            double hw = AttributeHelper.getWidth(n) / 2, hh = AttributeHelper.getHeight(n) / 2;
            finalOnly(new SeparationConstraint(Dim.XDIM, left, gx, hw), p);
            finalOnly(new SeparationConstraint(Dim.XDIM, gx, right, hw), p);
            finalOnly(new SeparationConstraint(Dim.YDIM, top, gy, hh), p);
            finalOnly(new SeparationConstraint(Dim.YDIM, gy, bottom, hh), p);
        }
    }

    private AlignmentConstraint wall(int dim, double pos, Constraint c) {
        AlignmentConstraint w = new AlignmentConstraint(dim, pos);
        w.fixPos(pos);
        finalOnly(w, c);
        return w;
    }

    // ------------------------------------------------------------------ building blocks

    /**
     * A guideline through the centre of {@code n}. Every guideline starts where its nodes are: its variable's desired
     * value is this position (0 would pull it, and with it the nodes, towards the origin).
     */
    private AlignmentConstraint guide(Node n, int dim) {
        AlignmentConstraint a = new AlignmentConstraint(dim, centre(n, dim));
        a.addShape(index.get(n), 0);
        return a;
    }

    /**
     * An alignment of {@code nodes}: each node's centre lies {@code sign} × half its size beyond the guideline (+1: top
     * or left edges on the line, -1: bottom or right edges, 0: centres). The guideline starts at the nodes' mean line.
     */
    private AlignmentConstraint alignment(List<Node> nodes, int dim, double sign) {
        double sum = 0;
        double[] offsets = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            offsets[i] = sign * (dim == Dim.XDIM ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n)) / 2;
            sum += centre(n, dim) - offsets[i];
        }
        AlignmentConstraint a = new AlignmentConstraint(dim, sum / nodes.size());
        for (int i = 0; i < nodes.size(); i++) a.addShape(index.get(nodes.get(i)), offsets[i]);
        return a;
    }

    private double meanCentre(List<Node> nodes, int dim) {
        double sum = 0;
        for (Node n : nodes) sum += centre(n, dim);
        return nodes.isEmpty() ? 0 : sum / nodes.size();
    }

    /** One guideline per node, in list order, registered for both phases. */
    private List<AlignmentConstraint> guides(List<Node> nodes, int dim, Constraint c) {
        List<AlignmentConstraint> l = new ArrayList<>();
        for (Node n : nodes) {
            AlignmentConstraint a = guide(n, dim);
            both(a, c);
            l.add(a);
        }
        return l;
    }

    /** One guideline through the centres of all {@code nodes}, registered for both phases. */
    private AlignmentConstraint line(List<Node> nodes, int dim, Constraint c) {
        AlignmentConstraint a = new AlignmentConstraint(dim, meanCentre(nodes, dim));
        for (Node n : nodes) a.addShape(index.get(n), 0);
        both(a, c);
        return a;
    }

    /** Consecutive guidelines at least (exactly, if {@code exact}) {@code gap} apart. */
    private static MultiSeparationConstraint order(List<AlignmentConstraint> guides, int dim, double gap, boolean exact) {
        MultiSeparationConstraint m = new MultiSeparationConstraint(dim, gap, exact);
        for (int i = 1; i < guides.size(); i++) m.addAlignmentPair(guides.get(i - 1), guides.get(i));
        return m;
    }

    /** A guideline through the centre of {@code n}, fixed at {@code pos}. */
    private AlignmentConstraint fixedGuide(Node n, int dim, double pos) {
        AlignmentConstraint a = new AlignmentConstraint(dim, pos);
        a.addShape(index.get(n), 0);
        a.fixPos(pos);
        return a;
    }

    private void both(CompoundConstraint cc, Constraint c) {
        runConstraints.add(cc);
        finalConstraints.add(cc);
        owner.put(pointer(cc), c);
    }

    private void runOnly(CompoundConstraint cc, Constraint c) {
        runConstraints.add(cc);
        separateFinalList = true;
        owner.put(pointer(cc), c);
    }

    private void finalOnly(CompoundConstraint cc, Constraint c) {
        finalConstraints.add(cc);
        separateFinalList = true;
        owner.put(pointer(cc), c);
    }

    private boolean known(Constraint c) {
        if (c == null || c.getNodes() == null || c.getNodes().isEmpty()) return false;
        for (Node n : c.getNodes()) if (!index.containsKey(n)) return false;
        return true;
    }

    private static int dim(Axis a) {
        return a == Axis.X ? Dim.XDIM : Dim.YDIM;
    }

    private static double half(Node n, Axis a) {
        return (a == Axis.X ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n)) / 2;
    }

    private double centre(Node n, int dim) {
        Rectangle r = rects.get(index.get(n));
        return dim == Dim.XDIM ? r.getCentreX() : r.getCentreY();
    }

    // ------------------------------------------------------------------ native pointers

    private static final Method GET_C_PTR;

    static {
        Method m = null;
        try {
            m = CompoundConstraint.class.getDeclaredMethod("getCPtr", CompoundConstraint.class);
            m.setAccessible(true);
        } catch (Exception ignored) {
            // pointer() then returns 0: the solver's reports are not traced back to their constraints
        }
        GET_C_PTR = m;
    }

    /** The native address of {@code cc} as cola's reports carry it (never dereferenced). */
    static long pointer(CompoundConstraint cc) {
        try {
            return GET_C_PTR == null ? 0 : (Long) GET_C_PTR.invoke(null, cc);
        } catch (Exception e) {
            return 0;
        }
    }
}
