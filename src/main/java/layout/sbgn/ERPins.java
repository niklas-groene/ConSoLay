package layout.sbgn;

import org.AttributeHelper;
import org.Vector2d;
import org.adaptagrams.AvoidRectangle;
import org.adaptagrams.ConnDirFlag;
import org.adaptagrams.ConnEnd;
import org.adaptagrams.Point;
import org.adaptagrams.Polygon;
import org.adaptagrams.Router;
import org.adaptagrams.ShapeConnectionPin;
import org.adaptagrams.ShapeRef;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;

import java.util.*;

/**
 * libavoid connection pins on the sides of a glyph, so that several ER arcs (or the arrowheads of AF arcs) end at
 * different places along one side instead of all aiming at the glyph centre. An arc that meets the glyph straight
 * ends on its own pin where its line meets the side, every other arc on exclusive pins spread along the sides; after
 * routing, {@link #dock} turns the route's end into a VANTED docking, which the SBGN-ML writer exports as the arc end.
 */
final class ERPins {

    /** -Dcblayout.erPins=on|off (default on): off lets every ER edge end at the glyph centre. */
    static boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("cblayout.erPins", "on"));
    /** -Dcblayout.erPinSpacing=px (default 20): minimum distance of two spread pins on one side (arrowhead: 15 px). */
    static double SPACING = ConstraintSBGNLayout.doubleProperty("cblayout.erPinSpacing", 20);
    /** -Dcblayout.erPinInset=px (default 10): no spread pin closer than this to a corner (rounded entity corners). */
    static double INSET = ConstraintSBGNLayout.doubleProperty("cblayout.erPinInset", 10);
    /** -Dcblayout.erPinCost=c (default 0.5): routing cost per px a spread pin lies away from the middle of its side. */
    static double COST = ConstraintSBGNLayout.doubleProperty("cblayout.erPinCost", 0.5);
    /**
     * -Dcblayout.erPinStub=px (default 16, 0 = off): the obstacle of a pin glyph grows by up to this much on each side
     * (never more than the free space towards the nearest other glyph minus {@link #CORRIDOR}) and the pins sit on the
     * grown border, so every route leaves the glyph with a straight piece at least that long (room for the arrowhead).
     */
    static double STUB = ConstraintSBGNLayout.doubleProperty("cblayout.erPinStub", 16);
    /** -Dcblayout.erPinCorridor=px (default 10): free space a grown obstacle leaves to another glyph or a stub end. */
    static double CORRIDOR = ConstraintSBGNLayout.doubleProperty("cblayout.erPinCorridor", 10);
    /** -Dcblayout.erPinRoles=ENTITY,... (default ENTITY): glyph roles that get pins. */
    static Set<String> ROLES = new HashSet<>(Arrays.asList(System.getProperty("cblayout.erPinRoles", "ENTITY").split(",")));
    /** -Dcblayout.erPinsDebug=true (default false): prints the pins created on every glyph. */
    static boolean DEBUG = Boolean.getBoolean("cblayout.erPinsDebug");
    /** -Dcblayout.erCrossingPenalty=p (default 0 = off): libavoid's crossing penalty for ER routing. */
    static double CROSSING_PENALTY = ConstraintSBGNLayout.doubleProperty("cblayout.erCrossingPenalty", 0);

    static final int SPREAD_CLASS = 1;
    /** From here on one class per straight end: the pin where that arc meets the glyph side. */
    static final int STRAIGHT_CLASS = 10;
    /** Tolerance below which two centres count as lying on one row / column. */
    private static final double ALIGN_EPS = 1.5;
    private static final double FALLBACK_COST = 10000;
    /** AF: free space between two symbols on neighbouring pins. */
    private static final double SYMBOL_GAP = 3;

    private final Map<Node, ShapeRef> shapes;
    private final Router router;
    /** {@link #SPACING}, {@link #INSET}, {@link #STUB}, {@link #CORRIDOR} of this run (AF has its own). */
    private final double spacing, inset, stub, corridor;
    /** AF: the pins are for the arrowheads of activities; ER: for every end on an entity glyph. */
    private final boolean activityFlow;
    /** AF: an obstacle next to a grown one may grow as well, so each takes at most half of the room between them. */
    private static final double AF_INSET = 6, AF_CORRIDOR = 30;
    /** ER: the ends of the stubs ({@link ERRouting#stubs}), points at which routes start; no obstacle grows over them. */
    private final List<double[]> stubEnds = new ArrayList<>();
    /** AF: glyph -> how far its obstacle grew on each side in the first routing round of this map (null: ER). */
    private final Map<Node, double[]> grown;
    /** AF: glyph -> distance of two pins on it: {@link AFLayout#PIN_SPACING}, more for arcs with a wider symbol. */
    private final Map<Node, Double> spacingOf = new HashMap<>();
    /** (edge, atSource) -> pin class id; absent: the end stays a point. */
    private final Map<Edge, int[]> classes = new HashMap<>();
    /** Keeps the SWIG proxies of the pins referenced while the router uses them. */
    private final List<ShapeConnectionPin> pins = new ArrayList<>();

    /**
     * ER: pins for {@code erEdges}, the edges of {@code er} that are routed. With
     * {@link ERRouting#clearStraightPins} the ends keep clear of corners, of each other and of the straight edges, and
     * no obstacle grows over the end of a stub; else pins without regard to any of that.
     */
    ERPins(Router router, Map<Node, ShapeRef> shapes, Collection<Edge> erEdges, ERRouting er) {
        this(router, shapes, erEdges, er.directions, er.clearStraightPins ? er.straight : null,
                er.clearStraightPins ? er.stubs.values() : Collections.<double[][]>emptyList(), null);
    }

    /**
     * AF ({@link AFLayout#HEAD_PINS}): pins for the ends of {@code edges} that carry an arrowhead, on every activity
     * (not on logical operators: their arcs end at the ports). An arc from a node on the activity's own row or
     * column ends in the middle of that side; the others on pins {@link AFLayout#PIN_SPACING} apart, as many per side
     * as the activity has such arcs. The other end of an arc stays at the centre of its node. {@code grown}: how far
     * the obstacles grew in an earlier routing round of this map (filled here in the first one): the rounds in between
     * move nodes apart to make room for arcs, and an obstacle that grew again would take that room.
     */
    static ERPins forActivityFlow(Router router, Map<Node, ShapeRef> shapes, Collection<Edge> edges, Map<Node, double[]> grown) {
        return new ERPins(router, shapes, edges, new HashMap<Edge, String[]>(), Collections.<Edge>emptyList(),
                Collections.<double[][]>emptyList(), grown);
    }

    private ERPins(Router router, Map<Node, ShapeRef> shapes, Collection<Edge> erEdges, Map<Edge, String[]> directions,
                   Collection<Edge> straightEdges, Collection<double[][]> stubs, Map<Node, double[]> grown) {
        this.router = router;
        this.shapes = shapes;
        this.activityFlow = grown != null;
        this.grown = grown;
        for (double[][] ends : stubs) for (double[] end : ends) if (end != null) stubEnds.add(end);
        this.spacing = activityFlow ? AFLayout.PIN_SPACING : SPACING;
        this.inset = activityFlow ? AF_INSET : INSET;
        this.stub = activityFlow ? AFLayout.PIN_STUB : STUB;
        this.corridor = activityFlow ? AF_CORRIDOR : CORRIDOR;
        boolean clearSpots = straightEdges != null;
        // glyph -> per side, where a straight edge meets it ({along, the side it comes from}): its arrowhead lies there
        Map<Node, List<List<double[]>>> lineEnds = new HashMap<>();
        if (clearSpots) {
            for (Edge e : straightEdges) {
                if (e.getSource() == e.getTarget()) continue;
                for (Node g : new Node[]{ e.getSource(), e.getTarget() }) {
                    if (!shapes.containsKey(g)) continue;
                    double[] hit = lineHit(box(shapes.get(g)), g, g == e.getSource() ? e.getTarget() : e.getSource());
                    if (hit == null) continue;
                    List<List<double[]>> sides = lineEnds.get(g);
                    if (sides == null) {
                        sides = new ArrayList<>();
                        for (int side = 0; side < 4; side++) sides.add(new ArrayList<double[]>());
                        lineEnds.put(g, sides);
                    }
                    sides.get((int) hit[0]).add(new double[]{ hit[1], hit[2] });
                }
            }
        }
        Map<Node, List<Edge>> incident = new LinkedHashMap<>();
        for (Edge e : erEdges) {
            // a docking left from an earlier routing run would pin the end in defineConnRef
            EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
            ega.getDocking().setSource("");
            ega.getDocking().setTarget("");
            if (e.getSource() == e.getTarget()) continue;
            incident.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e);
            incident.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        int straightClass = STRAIGHT_CLASS;
        for (Map.Entry<Node, List<Edge>> en : incident.entrySet()) {
            Node g = en.getKey();
            if (!shapes.containsKey(g)) continue;
            if (activityFlow) {
                boolean head = false;
                for (Edge e : en.getValue()) head |= e.getTarget() == g;
                if (!head || AFLayout.isLogicOperator(g)) continue;
            } else if (en.getValue().size() < 2 || !ROLES.contains(ConstraintSBGNLayout.getSBGNRole(g))) continue;
            if (AttributeHelper.getWidth(g) <= 2 * inset || AttributeHelper.getHeight(g) <= 2 * inset) continue;   // no room for pins
            if (activityFlow) {
                // two pins as far apart as the widest symbol that ends here (a bar is wider than an arrowhead)
                double widest = 0;
                for (Edge e : en.getValue()) if (e.getTarget() == g) widest = Math.max(widest, ERHyperedgeLayout.symbol(e, true)[1]);
                spacingOf.put(g, Math.max(spacing, widest + SYMBOL_GAP));
            }
            double[] b = box(shapes.get(g));
            List<List<double[]>> straight = new ArrayList<>();   // per side: {along, class}
            for (int side = 0; side < 4; side++) straight.add(new ArrayList<>());
            int spread = 0;
            List<Edge> ends = en.getValue();
            if (activityFlow) {
                // several nodes can stand on the activity's column: the middle of the side is for the nearest of them
                final Vector2d centre = AttributeHelper.getPositionVec2d(g);
                ends = new ArrayList<>(ends);
                ends.sort(Comparator.comparingDouble((Edge e) -> {
                    Vector2d q = AttributeHelper.getPositionVec2d(e.getSource() == g ? e.getTarget() : e.getSource());
                    return Math.hypot(q.x - centre.x, q.y - centre.y);
                }));
            }
            for (Edge e : ends) {
                boolean atSource = e.getSource() == g;
                if (activityFlow && atSource) continue;   // no arrowhead at this end
                Node other = atSource ? e.getTarget() : e.getSource();
                String[] dirs = directions.get(e);
                double[] hit = straightHit(g, b, other, dirs == null ? null : dirs[atSource ? 1 : 0]);
                if (hit != null && clearSpots) {
                    // not on a corner (a spread pin keeps INSET from it), not where another end already is: libavoid
                    // keeps one of two pins on one point, and the edge without a pin is drawn straight through the map
                    double len = hit[0] < 2 ? b[2] - b[0] : b[3] - b[1], corner = Math.min(inset, len / 4);
                    boolean free = hit[1] >= corner && hit[1] <= len - corner;
                    // (AF: several nodes can stand on the activity's column, their arcs all meet the side in its middle)
                    for (double[] st : straight.get((int) hit[0])) free &= Math.abs(st[0] - hit[1]) >= (activityFlow ? spacingOf.get(g) / 2 : 1);
                    // ... and not where a straight edge ends: two arrowheads on one spot
                    if (lineEnds.containsKey(g)) free &= !atLineEnd(lineEnds.get(g).get((int) hit[0]), hit[1]);
                    if (!free) hit = null;
                }
                int cls;
                if (hit != null) {
                    cls = straightClass++;
                    straight.get((int) hit[0]).add(new double[]{ hit[1], cls });
                } else {
                    cls = SPREAD_CLASS;
                    spread++;
                }
                classes.computeIfAbsent(e, k -> new int[]{ -1, -1 })[atSource ? 0 : 1] = cls;
            }
            createPins(g, shapes.get(g), straight, spread, lineEnds.get(g));
        }
    }

    /**
     * {side, position along that side of box {@code b}, -1 / 0 / 1: the edge comes from lower / the same / higher
     * positions along the side} where the straight line from the centre of {@code other} to the centre of glyph
     * {@code g} enters the glyph (VANTED draws a straight edge that way); null if the centres coincide.
     */
    private static double[] lineHit(double[] b, Node g, Node other) {
        Vector2d p = AttributeHelper.getPositionVec2d(g), q = AttributeHelper.getPositionVec2d(other);
        double dx = q.x - p.x, dy = q.y - p.y, hw = (b[2] - b[0]) / 2, hh = (b[3] - b[1]) / 2;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return null;
        if (Math.abs(dx) * hh > Math.abs(dy) * hw) return new double[]{ dx > 0 ? 3 : 2, p.y + dy * hw / Math.abs(dx) - b[1], Math.signum(dy) };
        return new double[]{ dy > 0 ? 1 : 0, p.x + dx * hh / Math.abs(dy) - b[0], Math.signum(dx) };
    }

    /**
     * Whether a pin at {@code along} would lie in the arrowhead of a straight edge that meets this side
     * ({@code ends}: {along, the side it comes from}). The arrowhead lies turned on the side the edge comes from:
     * there a whole pin spacing stays free, on the other side a quarter of it.
     */
    private static boolean atLineEnd(List<double[]> ends, double along) {
        for (double[] end : ends) {
            double towards = (along - end[0]) * end[1];
            if (end[1] == 0 ? Math.abs(along - end[0]) < SPACING / 2 : towards > -SPACING / 4 && towards < SPACING) return true;
        }
        return false;
    }

    /**
     * {side, position along that side of box {@code b}} if the edge from {@code other} meets glyph {@code g} straight:
     * it leaves {@code other} towards g ({@code dirAtOther}, ERLayout's direction at a hyperedge node) and
     * {@code other} lies within g's extent across that direction. For a glyph at the other end (no direction): only
     * when it lies on g's centre row or column. Null otherwise.
     */
    private static double[] straightHit(Node g, double[] b, Node other, String dirAtOther) {
        if (dirAtOther != null && dirAtOther.indexOf(',') >= 0) {
            // several directions (ERRouting.directions): a straight stub lists the way it points first, then the two
            // turns; a diagonal stub lists two directions and never meets a glyph side straight
            String[] ways = dirAtOther.split(",");
            if (ways.length != 3) return null;
            dirAtOther = ways[0];
        }
        Vector2d p = AttributeHelper.getPositionVec2d(g), q = AttributeHelper.getPositionVec2d(other);
        if (dirAtOther == null || dirAtOther.equals("horizontal") || dirAtOther.equals("vertical")) {
            int side = alignedSide(g, other);
            if (side < 0) return null;
            return new double[]{ side, side < 2 ? p.x - b[0] : p.y - b[1] };
        }
        switch (dirAtOther) {
            case "up":    return q.y > p.y && q.x > b[0] + 1 && q.x < b[2] - 1 ? new double[]{ 1, q.x - b[0] } : null;
            case "down":  return q.y < p.y && q.x > b[0] + 1 && q.x < b[2] - 1 ? new double[]{ 0, q.x - b[0] } : null;
            case "left":  return q.x > p.x && q.y > b[1] + 1 && q.y < b[3] - 1 ? new double[]{ 3, q.y - b[1] } : null;
            default:      return q.x < p.x && q.y > b[1] + 1 && q.y < b[3] - 1 ? new double[]{ 2, q.y - b[1] } : null;
        }
    }

    /** 0 up, 1 down, 2 left, 3 right if {@code other} lies on the row / column of {@code g}; -1 otherwise. */
    static int alignedSide(Node g, Node other) {
        Vector2d p = AttributeHelper.getPositionVec2d(g), q = AttributeHelper.getPositionVec2d(other);
        double dx = q.x - p.x, dy = q.y - p.y;
        if (Math.abs(dy) < ALIGN_EPS && Math.abs(dx) >= ALIGN_EPS) return dx > 0 ? 3 : 2;
        if (Math.abs(dx) < ALIGN_EPS && Math.abs(dy) >= ALIGN_EPS) return dy > 0 ? 1 : 0;
        return -1;
    }

    private static int dirFlag(int side) {
        switch (side) {
            case 0: return ConnDirFlag.ConnDirUp;
            case 1: return ConnDirFlag.ConnDirDown;
            case 2: return ConnDirFlag.ConnDirLeft;
            default: return ConnDirFlag.ConnDirRight;
        }
    }

    /** A pin at proportional position ({@code x}, {@code y}) of the (grown) shape, leaving towards {@code side}. */
    private void pin(ShapeRef s, int cls, int side, double x, double y, double cost, boolean exclusive) {
        ShapeConnectionPin p = new ShapeConnectionPin(s, cls, x, y, true, 0.0, dirFlag(side));
        p.setExclusive(exclusive);
        if (cost > 0) p.setConnectionCost(cost);
        pins.add(p);
    }

    private static double[] box(ShapeRef s) {
        Polygon poly = s.polygon();
        double[] b = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        for (int i = 0; i < poly.size(); i++) {
            b[0] = Math.min(b[0], poly.at(i).getX());
            b[1] = Math.min(b[1], poly.at(i).getY());
            b[2] = Math.max(b[2], poly.at(i).getX());
            b[3] = Math.max(b[3], poly.at(i).getY());
        }
        return b;
    }

    /** How far the obstacle of {@code g} (box {@code b}) may grow on {@code side}: STUB, less near another glyph. */
    private double margin(Node g, double[] b, int side) {
        if (stub <= 0) return 0;
        double m = stub;
        for (Map.Entry<Node, ShapeRef> en : shapes.entrySet()) {
            if (en.getKey() == g) continue;
            double[] o = box(en.getValue());
            double gap;
            boolean band = side < 2 ? o[2] > b[0] - stub && o[0] < b[2] + stub : o[3] > b[1] - stub && o[1] < b[3] + stub;
            if (!band) continue;
            switch (side) {
                case 0: gap = b[1] - o[3]; break;
                case 1: gap = o[1] - b[3]; break;
                case 2: gap = b[0] - o[2]; break;
                default: gap = o[0] - b[2]; break;
            }
            if (gap < -1e-6) continue;   // not on that side
            m = Math.min(m, activityFlow ? (gap - corridor) / 2 : gap - corridor);
        }
        // nor over the end of a stub: a route that starts inside an obstacle is drawn straight through it
        for (double[] p : stubEnds) {
            boolean band = side < 2 ? p[0] > b[0] - stub && p[0] < b[2] + stub : p[1] > b[1] - stub && p[1] < b[3] + stub;
            double gap = side == 0 ? b[1] - p[1] : side == 1 ? p[1] - b[3] : side == 2 ? b[0] - p[0] : p[0] - b[2];
            if (band && gap >= -1e-6) m = Math.min(m, gap - corridor);
        }
        return Math.max(0, m);
    }

    private void createPins(Node g, ShapeRef s, List<List<double[]>> straight, int spreadEdges, List<List<double[]>> lineEnds) {
        double[] b = box(s);
        double w = b[2] - b[0], h = b[3] - b[1];
        double[] m = grown == null ? null : grown.get(g);
        if (m == null) {
            m = new double[4];
            for (int side = 0; side < 4; side++) m[side] = margin(g, b, side);
            if (grown != null) grown.put(g, m);
        }
        double spacing = activityFlow ? spacingOf.get(g) : this.spacing;
        double gx0 = b[0] - m[2], gy0 = b[1] - m[0], gw = w + m[2] + m[3], gh = h + m[0] + m[1];
        if (m[0] + m[1] + m[2] + m[3] > 0) router.moveShape(s, new AvoidRectangle(new Point(gx0, gy0), new Point(gx0 + gw, gy0 + gh)));
        int made = 0;
        for (int side = 0; side < 4; side++) {
            double len = side < 2 ? w : h;
            List<double[]> spots = new ArrayList<>();   // {along, cost, class, exclusive}
            for (double[] st : straight.get(side)) spots.add(new double[]{ st[0], 0, st[1], 0 });
            if (spreadEdges > 0) {
                double inset = Math.min(this.inset, len / 4);
                double usable = len - 2 * inset;
                // ER: the pins spread over the side; AF: exactly a spacing apart from the middle outwards, and no more
                // of them than there are arcs to take them (every pin is a point the router has to look at)
                int n = activityFlow ? Math.min((int) Math.floor(usable / spacing) + 1, spreadEdges + 1 + straight.get(side).size())
                                     : Math.max(1, (int) Math.floor(usable / spacing) - 1);
                if (n % 2 == 0) n--;                                   // odd: one pin in the middle
                for (int i = 0; i < n; i++) {
                    double along = activityFlow ? len / 2 + (i - (n - 1) / 2) * spacing : inset + usable * (i + 1) / (n + 1);
                    boolean nearStraight = false;
                    for (double[] st : straight.get(side)) nearStraight |= Math.abs(st[0] - along) < spacing / 2;
                    if (lineEnds != null) nearStraight |= atLineEnd(lineEnds.get(side), along);
                    if (nearStraight) continue;   // an arc meets the side straight there
                    spots.add(new double[]{ along, COST * Math.abs(along - len / 2), SPREAD_CLASS, 1 });
                    made++;
                }
                // a non-exclusive fallback, else libavoid routes an edge without a free pin to the shape centre; on a
                // point of its own: of two pins on one point libavoid keeps one, chosen by memory address, not by cost
                double fallback = len / 2;
                for (int step = 1; occupied(spots, fallback) && step < 64; step++) {
                    fallback = len / 2 + (step % 2 == 1 ? 1 : -1) * ((step + 1) / 2) * spacing / 4;
                }
                spots.add(new double[]{ Math.max(0, Math.min(len, fallback)), FALLBACK_COST, SPREAD_CLASS, 0 });
            }
            for (double[] sp : spots) {
                // position along the glyph's own side, expressed in the grown shape
                double x, y;
                switch (side) {
                    case 0: x = (b[0] + sp[0] - gx0) / gw; y = 0; break;
                    case 1: x = (b[0] + sp[0] - gx0) / gw; y = 1; break;
                    case 2: x = 0; y = (b[1] + sp[0] - gy0) / gh; break;
                    default: x = 1; y = (b[1] + sp[0] - gy0) / gh; break;
                }
                pin(s, (int) sp[2], side, x, y, sp[1], sp[3] > 0);
            }
        }
        if (DEBUG) System.out.println("[cblayout] ER pins " + AttributeHelper.getLabel(g, "") + " bounds " + w + "x" + h
                + " grown " + Arrays.toString(m) + ": " + made + " spread pins for " + spreadEdges + " edges");
    }

    /** Whether a spot of {@code spots} lies within 1 px of {@code along}. */
    private static boolean occupied(List<double[]> spots, double along) {
        for (double[] sp : spots) if (Math.abs(sp[0] - along) < 1) return true;
        return false;
    }

    /** The pin end of {@code e} at its source ({@code atSource}) or target, or null if that end is a point. */
    ConnEnd end(Edge e, boolean atSource) {
        int[] c = classes.get(e);
        if (c == null || c[atSource ? 0 : 1] < 0) return null;
        Node g = atSource ? e.getSource() : e.getTarget();
        return new ConnEnd(shapes.get(g), c[atSource ? 0 : 1]);
    }

    boolean hasPins(Edge e) {
        return classes.containsKey(e);
    }

    /**
     * Sets the docking of the pin ends of {@code e} to the route's first / last point (relative to the node size, the
     * side's own coordinate exactly +-1), so the docking branch of applyBends drops that point from the bends.
     */
    void dock(Edge e, List<Vector2d> route) {
        int[] c = classes.get(e);
        if (c == null || route.size() < 2) return;
        EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
        if (c[0] >= 0) ega.getDocking().setSource(docking(e.getSource(), route.get(0), route.get(1)));
        if (c[1] >= 0) ega.getDocking().setTarget(docking(e.getTarget(), route.get(route.size() - 1), route.get(route.size() - 2)));
    }

    /** "fx;fy" of point {@code p} on the border of {@code g}; {@code next} is the neighbouring route point (gives the side). */
    private static String docking(Node g, Vector2d p, Vector2d next) {
        double cx = AttributeHelper.getPositionX(g), cy = AttributeHelper.getPositionY(g);
        double hw = AttributeHelper.getWidth(g) / 2, hh = AttributeHelper.getHeight(g) / 2;
        double fx = hw > 1e-9 ? clamp((p.x - cx) / hw) : 0, fy = hh > 1e-9 ? clamp((p.y - cy) / hh) : 0;
        // the route leaves the pin perpendicular to its side: vertical first segment -> top / bottom side
        if (Math.abs(next.x - p.x) < 1e-6 && Math.abs(next.y - p.y) > 1e-6) fy = next.y < p.y ? -1 : 1;
        else if (Math.abs(next.y - p.y) < 1e-6 && Math.abs(next.x - p.x) > 1e-6) fx = next.x < p.x ? -1 : 1;
        return String.format(Locale.US, "%.5f;%.5f", fx, fy);
    }

    private static double clamp(double f) {
        return Math.max(-1, Math.min(1, f));
    }
}
