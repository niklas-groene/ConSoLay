package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.*;
import org.adaptagrams.AvoidCheckpoints;
import org.adaptagrams.AvoidRectangle;
import org.adaptagrams.Checkpoint;
import org.adaptagrams.ConnDirFlag;
import org.adaptagrams.ConnEnd;
import org.adaptagrams.ConnRef;
import org.adaptagrams.Point;
import org.adaptagrams.ShapeRef;
import org.adaptagrams.ConnType;
import org.adaptagrams.Router;
import org.adaptagrams.RouterFlag;
import org.adaptagrams.RoutingOption;
import org.adaptagrams.RoutingParameter;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.DockingAttribute;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.plugin.view.View;
import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.vanted.plugins.layout.adaptagrams.edgerouting.AdaptagramsRouting;

/**
 * Edge router of the SBGN layout pipeline (PD step 6, AF/ER final step). Derived from VANTED's
 * EdgeRoutingAlgorithm (Tobias Czauderna), but routes synchronously on an explicit view and adds the SBGN
 * specifics: ER pins and outcome directions, process / catalysis / stimulation stubs, AF logic-operator
 * stubs. The libavoid helpers are VANTED's {@link AdaptagramsRouting}.
 */
public class SBGNEdgeRouter {

    /** Orthogonal routing; false: polylines. */
    private boolean orthogonal;
    private double shapeBufferDistance = 2.0; // distance between nodes and edges
    private double nudgingDistance = 4; // distance edge segments are nudged apart
    private boolean nudgeOrthogonalSegments = true; // the first and the last segments of edges are nudged apart
    private boolean processStubs = true; // stub at the process end of an arc (PD); off for AF
    /** ER: directions, checkpoints and straight edges of this run (null for PD and AF). */
    private ERRouting er;
    /** AF, and ER if the pipeline asks for it: steps shorter than {@link #SMALL_JOG} are taken out of the routes. */
    private boolean removeSmallJogs;
    /** AF: not null if the arrowheads end on pins (see {@link #newExecActivityFlow}). */
    private Map<Node, double[]> headPins;

    /**
     * -Dcblayout.routeJog=px (default 3, 0 = off): on orthogonal AF and ER routes a step shorter than this between two
     * parallel segments is taken out ({@link #removeSmallJogs}); libavoid leaves such steps where it nudges a segment
     * whose end is fixed (at a port, at the end of a stub).
     */
    static double SMALL_JOG = ConstraintSBGNLayout.doubleProperty("cblayout.routeJog", 3);
    /** -Dcblayout.routeDebug=true (default false): prints every route as libavoid returns it, before any correction. */
    static boolean DEBUG = Boolean.getBoolean("cblayout.routeDebug");

    /**
     * AF (AFLayout): routes with {@code nudging} px between parallel segments instead of the default 4, and without
     * the 20 px process stub towards an aligned neighbour: an AF map has no process nodes, and on an orthogonal route
     * that stub turns the last segment into a diagonal. {@code shapeBuffer}: px an arc keeps away from foreign nodes.
     * {@code headPins}: not null if the arrowheads end on pins of their own ({@link AFLayout#HEAD_PINS}); the map
     * keeps what {@link ERPins#forActivityFlow} needs from one routing round of a map to the next.
     */
    public void newExecActivityFlow(Graph graph, boolean orthogonal, double nudging, double shapeBuffer, Map<Node, double[]> headPins) {
        this.headPins = headPins;
        this.nudgingDistance = nudging;
        this.shapeBufferDistance = shapeBuffer;
        this.processStubs = false;
        this.removeSmallJogs = true;
        newExec(graph, orthogonal);
    }

    /** ER ({@link ERLayout}, {@link ERHyperedgeLayout}): routes with what the pipeline planned for its edges. */
    void newExec(Graph graph, boolean orthogonal, ERRouting er) {
        this.er = er;
        if (er != null && er.shapeBuffer > 0) this.shapeBufferDistance = er.shapeBuffer;
        if (er != null && er.nudging > 0) this.nudgingDistance = er.nudging;
        this.removeSmallJogs = er != null && er.removeSmallJogs;
        newExec(graph, orthogonal);
    }

    /** Routes every edge of {@code graph}, orthogonally or as polylines (PD; the AF and ER variants end here). */
    public void newExec(Graph graph, boolean orthogonal) {
        this.orthogonal = orthogonal;
        this.nudgeOrthogonalSegments = orthogonal;
        execute(graph);
    }

    /** Routes every edge of {@code graph} on {@link ConstraintSBGNLayout#view2D} with the settings of this router. */
    public void execute(Graph graph) {
        View view = ConstraintSBGNLayout.view2D;
        Router router = new Router(orthogonal ? RouterFlag.OrthogonalRouting : RouterFlag.PolyLineRouting);
        int routingType = orthogonal ? ConnType.ConnType_Orthogonal : ConnType.ConnType_PolyLine;
        router.setRoutingParameter(RoutingParameter.shapeBufferDistance, this.shapeBufferDistance);
        router.setRoutingPenalty(RoutingParameter.segmentPenalty);
        router.setRoutingOption(RoutingOption.nudgeOrthogonalSegmentsConnectedToShapes,
                this.nudgeOrthogonalSegments);
        router.setRoutingParameter(RoutingParameter.idealNudgingDistance, this.nudgingDistance);

        Collection<Node> visibleNodes = GraphHelper.getVisibleNodes(ConstraintSBGNLayout.layoutNodes(graph));
        HashMap<Node, ShapeRef> shapeRefs = new HashMap<>();
        for (Node node : visibleNodes) {
            if (CBLayoutAlgorithm.clusterMap != null && CBLayoutAlgorithm.clusterMap.containsKey(node)) {
                continue;
            }
            if (node.getDegree() > 0) {
                shapeRefs.put(node, AdaptagramsRouting.defineShapeRef(router, view, node));
            }
        }
        // ER: the symbols at the ends of stubs are obstacles, so no other edge runs through an arrowhead
        if (er != null) {
            for (double[] r : er.keepOut) new ShapeRef(router, new AvoidRectangle(new Point(r[0], r[1]), new Point(r[2], r[3])));
        }
        // ER (ERLayout): arcs end on pins spread along the glyph sides instead of the glyph centre
        ERPins erPins = null;
        if (orthogonal && er != null && !er.directions.isEmpty()) {
            if (ERPins.ENABLED) {
                List<Edge> erEdges = new ArrayList<>();
                for (Edge e : ConstraintSBGNLayout.layoutEdges(graph)) if (er.directions.containsKey(e)) erEdges.add(e);
                erPins = new ERPins(router, shapeRefs, erEdges, er);
            }
            if (ERPins.CROSSING_PENALTY > 0) router.setRoutingParameter(RoutingParameter.crossingPenalty, ERPins.CROSSING_PENALTY);
            // with pins, only the inputs of a logical operator share an end point, and they are meant to merge there
            router.setRoutingOption(RoutingOption.nudgeSharedPathsWithCommonEndPoint, false);
        }
        // AF (AFLayout): a penalty for crossings, and the arrowheads end on pins of their own
        if (orthogonal && er == null && !this.processStubs) {
            if (AFLayout.CROSSING_PENALTY > 0 && ConstraintSBGNLayout.layoutEdges(graph).size() <= AFLayout.CROSSING_MAX_ARCS) {
                router.setRoutingParameter(RoutingParameter.crossingPenalty, AFLayout.CROSSING_PENALTY);
            }
            if (this.headPins != null) {
                erPins = ERPins.forActivityFlow(router, shapeRefs, ConstraintSBGNLayout.layoutEdges(graph), this.headPins);
            } else {
                // the dockings of an earlier round with pins would fix the ends where those pins were
                for (Edge e : ConstraintSBGNLayout.layoutEdges(graph)) {
                    DockingAttribute docking = ((EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS)).getDocking();
                    if (docking.getSource() != null && !docking.getSource().isEmpty()) docking.setSource("");
                    if (docking.getTarget() != null && !docking.getTarget().isEmpty()) docking.setTarget("");
                }
            }
        }

        HashMap<Edge, ConnRef> hmConnRefs = new HashMap<>();
        for (Edge edge : ConstraintSBGNLayout.layoutEdges(graph)) {
            if (er != null && er.straight.contains(edge)) continue;   // ER: drawn as a straight line, takes no pin and no channel
            String[] erDirs = er == null ? null : er.directions.get(edge);   // ER: the side an outcome or port is left on
            // AF, orthogonal: the router itself leaves and enters an operator on its port side (no stub afterwards)
            String[] logicSides = erDirs == null && orthogonal ? ConstraintSBGNLayout.orientationLogic.get(edge) : null;
            if (logicSides != null) erDirs = logicSides;
            ConnRef connRef = erDirs == null ? AdaptagramsRouting.defineConnRef(router, view, edge, routingType)
                    : AdaptagramsRouting.defineConnRef(router, view, edge, connDir(erDirs[0]), connDir(erDirs[1]), routingType);
            if (erPins != null && erPins.hasPins(edge)) {
                ConnEnd src = erPins.end(edge, true), tgt = erPins.end(edge, false);
                if (src != null) connRef.setSourceEndpoint(src);
                if (tgt != null) connRef.setDestEndpoint(tgt);
            }
            double[][] erStubs = er == null ? null : er.stubs.get(edge);
            if (erStubs != null) {
                // ER: the edge runs straight from its node to the end of a stub; the route starts (ends) there
                if (erStubs[0] != null) connRef.setSourceEndpoint(new ConnEnd(new Point(erStubs[0][0], erStubs[0][1]), connDir(erDirs[0])));
                if (erStubs[1] != null) connRef.setDestEndpoint(new ConnEnd(new Point(erStubs[1][0], erStubs[1][1]), connDir(erDirs[1])));
            }
            if (logicSides != null) {
                // ... and ends at the operator's port, where the arcs of one side meet (SBGN-ML ends them there)
                if (logicSides[0] != null) connRef.setSourceEndpoint(new ConnEnd(logicPort(edge.getSource(), logicSides[0]), connDir(logicSides[0])));
                if (logicSides[1] != null) connRef.setDestEndpoint(new ConnEnd(logicPort(edge.getTarget(), logicSides[1]), connDir(logicSides[1])));
                if (AFLayout.PORT_LEAD > 0 && logicSides[0] != null) {
                    // a point to turn at in front of the output port (see AFLayout.PORT_LEAD)
                    AvoidCheckpoints lead = new AvoidCheckpoints();
                    lead.add(new Checkpoint(logicPort(edge.getSource(), logicSides[0], LOGIC_PORT_DISTANCE + AFLayout.PORT_LEAD)));
                    connRef.setRoutingCheckpoints(lead);
                }
            }
            double[][] erPass = er == null ? null : er.checkpoints.get(edge);
            if (erPass != null) {
                AvoidCheckpoints cps = new AvoidCheckpoints();
                for (double[] cp : erPass) cps.add(new Checkpoint(new Point(cp[0], cp[1])));
                connRef.setRoutingCheckpoints(cps);
            }
            hmConnRefs.put(edge, connRef);
        }

        router.processTransaction();

        applyBends(ConstraintSBGNLayout.layoutEdges(graph), hmConnRefs, view, erPins, er, this.processStubs,
                orthogonal, this.removeSmallJogs && orthogonal ? SMALL_JOG : 0);

        hmConnRefs.clear();
        router.delete();
    }

    /**
     * libavoid direction flag for "up", "down", "left", "right", "horizontal" (left or right), "vertical", or several
     * of these joined by commas (any of them); any direction for null.
     */
    private static int connDir(String side) {
        if (side == null) return ConnDirFlag.ConnDirAll;
        if (side.indexOf(',') >= 0) {
            int flags = 0;
            for (String one : side.split(",")) flags |= connDir(one);
            return flags;
        }
        switch (side) {
            case "up": return ConnDirFlag.ConnDirUp;
            case "down": return ConnDirFlag.ConnDirDown;
            case "left": return ConnDirFlag.ConnDirLeft;
            case "right": return ConnDirFlag.ConnDirRight;
            case "horizontal": return ConnDirFlag.ConnDirLeft | ConnDirFlag.ConnDirRight;
            case "vertical": return ConnDirFlag.ConnDirUp | ConnDirFlag.ConnDirDown;
            default: return ConnDirFlag.ConnDirAll;
        }
    }

    /** Distance of a logical operator's port from its border (as SBGN-ED writes it). */
    private static final double LOGIC_PORT_DISTANCE = 10;

    /** The port of {@code operator} on {@code side}. */
    private static Point logicPort(Node operator, String side) {
        return logicPort(operator, side, LOGIC_PORT_DISTANCE);
    }

    /** The point {@code distance} px outside the border of {@code operator} on the axis of its port on {@code side}. */
    private static Point logicPort(Node operator, String side, double distance) {
        Vector2d c = AttributeHelper.getPositionVec2d(operator);
        double d = ("left".equals(side) || "right".equals(side) ? AttributeHelper.getWidth(operator) : AttributeHelper.getHeight(operator)) / 2
                + distance;
        switch (side) {
            case "up": return new Point(c.x, c.y - d);
            case "down": return new Point(c.x, c.y + d);
            case "left": return new Point(c.x - d, c.y);
            default: return new Point(c.x + d, c.y);
        }
    }

    /**
     * AF, orthogonal: libavoid nudges the arcs meeting at an operator port apart, so they would reach the port
     * sideways. Each reaches it with a short diagonal piece ({@link AFLayout#PORT_FAN}); or the last segment of each
     * is moved back onto the port's axis: the arcs of one port merge in front of it.
     */
    private static void snapToLogicPorts(ArrayList<Vector2d> bends, Node src, Node tgt, String[] sides) {
        if (AFLayout.PORT_FAN) {
            if (bends.size() < 2) return;
            if (sides[1] != null) fanToLogicPort(bends, false, logicPort(tgt, sides[1]), sides[1]);
            if (sides[0] != null) fanToLogicPort(bends, true, logicPort(src, sides[0]), sides[0]);
            return;
        }
        // a straight arc between two ports is left alone: moving one end onto its port would tilt it
        if (bends.size() < 2 || (bends.size() == 2 && sides[0] != null && sides[1] != null)) return;
        if (sides[0] != null) snapToLogicPort(bends.get(0), bends.get(1), logicPort(src, sides[0]), sides[0]);
        if (sides[1] != null) snapToLogicPort(bends.get(bends.size() - 1), bends.get(bends.size() - 2), logicPort(tgt, sides[1]), sides[1]);
    }

    /**
     * The segment at the port stays where the router nudged it; its end moves back by its distance from the port's
     * axis (at most the segment's length; half of it if that segment is the whole route, which then may get such a
     * piece at both ends), and a 45-degree piece leads from there into the port.
     */
    private static void fanToLogicPort(ArrayList<Vector2d> bends, boolean atSource, Point port, String side) {
        Vector2d end = bends.get(atSource ? 0 : bends.size() - 1), next = bends.get(atSource ? 1 : bends.size() - 2);
        boolean vertical = "up".equals(side) || "down".equals(side);
        // only a segment that leaves the port along its side; anything else is left as routed
        if (vertical ? Math.abs(end.x - next.x) > 0.001 : Math.abs(end.y - next.y) > 0.001) return;
        double off = vertical ? end.x - port.getX() : end.y - port.getY();
        if (Math.abs(off) < 0.01) {
            end.x = port.getX();
            end.y = port.getY();
            return;
        }
        double length = vertical ? Math.abs(next.y - end.y) : Math.abs(next.x - end.x);
        double back = Math.min(Math.abs(off), Math.max(0, length / (bends.size() == 2 ? 2 : 1) - 1));
        if (vertical) end.y = port.getY() + Math.signum(next.y - port.getY()) * back;
        else end.x = port.getX() + Math.signum(next.x - port.getX()) * back;
        Vector2d at = new Vector2d(port.getX(), port.getY());
        if (atSource) bends.add(0, at);
        else bends.add(at);
    }

    private static void snapToLogicPort(Vector2d end, Vector2d next, Point port, String side) {
        boolean vertical = "up".equals(side) || "down".equals(side);
        // only a segment that leaves the port along its side; anything else is left as routed
        if (vertical ? Math.abs(end.x - next.x) > 0.001 : Math.abs(end.y - next.y) > 0.001) return;
        end.x = port.getX();
        end.y = port.getY();
        if (vertical) next.x = end.x;
        else next.y = end.y;
    }

    /** {@link ERRouting#keepNudgedStubs}: a stub may get this much shorter (px) than planned, not more (and see {@link ERRouting#stubReach}). */
    private static final double STUB_LOSS = 6;

    /**
     * ER: libavoid nudges the segment at the end of a route sideways, also where the route starts at the end of a stub,
     * and the stub then is a little off its direction. The route's end goes back onto the stub's end, and the bend
     * next to it moves along, so the segment between them stays parallel to its axis. {@code keep}: where the route
     * turns off at the end of a straight stub, the nudge only makes the stub longer or shorter, and it stays: it is
     * what keeps this route apart from the ones beside it (back on the planned end they ran 1 px apart).
     * {@code reach}: how far from its node's centre the end of the stub at {source, target} has to stay at least.
     */
    private static void snapToStubs(ArrayList<Vector2d> bends, double[][] stubs, double[] reach, Node src, Node tgt, boolean keep) {
        if (bends.size() < 2) return;
        for (int k = 0; k < 2; k++) {
            if (stubs[k] == null) continue;
            Vector2d end = bends.get(k == 0 ? 0 : bends.size() - 1), next = bends.get(k == 0 ? 1 : bends.size() - 2);
            if (keep) {
                Vector2d c = AttributeHelper.getPositionVec2d(k == 0 ? src : tgt);
                double sx = stubs[k][0] - c.x, sy = stubs[k][1] - c.y;   // the stub as planned, from the node's centre
                boolean turnsVertical = Math.abs(end.x - next.x) < 0.001 && Math.abs(end.y - next.y) > 0.001;
                boolean turnsHorizontal = Math.abs(end.y - next.y) < 0.001 && Math.abs(end.x - next.x) > 0.001;
                double least = reach == null ? 0 : reach[k];
                if (Math.abs(sy) < 0.001 && turnsVertical && (end.x - c.x) * sx > 0 && Math.abs(end.x - c.x) >= Math.max(Math.abs(sx) - STUB_LOSS, least)) {
                    end.y = stubs[k][1];
                    continue;
                }
                if (Math.abs(sx) < 0.001 && turnsHorizontal && (end.y - c.y) * sy > 0 && Math.abs(end.y - c.y) >= Math.max(Math.abs(sy) - STUB_LOSS, least)) {
                    end.x = stubs[k][0];
                    continue;
                }
            }
            if (Math.abs(end.y - next.y) < 0.001) next.y = stubs[k][1];
            else if (Math.abs(end.x - next.x) < 0.001) next.x = stubs[k][0];
            end.x = stubs[k][0];
            end.y = stubs[k][1];
        }
    }

    /**
     * Takes the steps shorter than {@code max} px out of an orthogonal route: a short segment between two parallel
     * ones that go on in the same direction. The shorter of the two moves onto the line of the other; never the
     * first or last segment of the route (its end is fixed at the node, pin, port or stub) and never a segment next
     * to a diagonal one. Points on top of each other and points in the middle of a straight piece go as well.
     * {@code freeStart}, {@code freeEnd}: the route starts (ends) at a point of its own (the end of a stub, a port)
     * and not on a node: there a first (last) segment that is such a step goes too, the next segment moves onto the
     * end point.
     */
    static void removeSmallJogs(ArrayList<Vector2d> bends, double max, boolean freeStart, boolean freeEnd) {
        if (max <= 0) return;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int end = 0; end < 2 && !changed && bends.size() >= 4; end++) {
                if (end == 0 ? !freeStart : !freeEnd) continue;
                int n = bends.size() - 1;
                Vector2d b = bends.get(end == 0 ? 0 : n), c = bends.get(end == 0 ? 1 : n - 1), d = bends.get(end == 0 ? 2 : n - 2),
                        after = bends.get(end == 0 ? 3 : n - 3);
                boolean stepX = Math.abs(b.y - c.y) < 0.001 && Math.abs(b.x - c.x) < max && Math.abs(c.x - d.x) < 0.001 && Math.abs(d.y - after.y) < 0.001;
                boolean stepY = Math.abs(b.x - c.x) < 0.001 && Math.abs(b.y - c.y) < max && Math.abs(c.y - d.y) < 0.001 && Math.abs(d.x - after.x) < 0.001;
                if (almostEqual(b, c) || almostEqual(c, d) || (!stepX && !stepY)) continue;
                if (stepX) d.x = b.x;
                else d.y = b.y;
                bends.remove(c);
                changed = true;
            }
            for (int i = 1; i + 1 < bends.size() && !changed; i++) {
                Vector2d a = bends.get(i - 1), b = bends.get(i), c = bends.get(i + 1);
                boolean spare = almostEqual(a, b) || almostEqual(b, c)
                        || (Math.abs(a.x - b.x) < 0.001 && Math.abs(b.x - c.x) < 0.001 && (b.y - a.y) * (c.y - b.y) > 0)
                        || (Math.abs(a.y - b.y) < 0.001 && Math.abs(b.y - c.y) < 0.001 && (b.x - a.x) * (c.x - b.x) > 0);
                if (spare) {
                    bends.remove(i);
                    changed = true;
                }
            }
            for (int i = 1; i + 2 < bends.size() && !changed; i++) {
                Vector2d a = bends.get(i - 1), b = bends.get(i), c = bends.get(i + 1), d = bends.get(i + 2);
                // b - c is the step: horizontal between two vertical segments, or vertical between two horizontal ones
                boolean stepX = Math.abs(b.y - c.y) < 0.001 && Math.abs(a.x - b.x) < 0.001 && Math.abs(c.x - d.x) < 0.001
                        && Math.abs(b.x - c.x) < max && (b.y - a.y) * (d.y - c.y) > 0;
                boolean stepY = Math.abs(b.x - c.x) < 0.001 && Math.abs(a.y - b.y) < 0.001 && Math.abs(c.y - d.y) < 0.001
                        && Math.abs(b.y - c.y) < max && (b.x - a.x) * (d.x - c.x) > 0;
                if (!stepX && !stepY) continue;
                // a segment may move sideways if the one before (after) it is at a right angle to it
                Vector2d before = i >= 2 ? bends.get(i - 2) : null, after = i + 3 < bends.size() ? bends.get(i + 3) : null;
                boolean firstFree = before != null && (stepX ? Math.abs(before.y - a.y) < 0.001 : Math.abs(before.x - a.x) < 0.001);
                boolean lastFree = after != null && (stepX ? Math.abs(after.y - d.y) < 0.001 : Math.abs(after.x - d.x) < 0.001);
                if (!firstFree && !lastFree) continue;
                double first = Math.abs(a.x - b.x) + Math.abs(a.y - b.y), last = Math.abs(c.x - d.x) + Math.abs(c.y - d.y);
                boolean moveFirst = firstFree && (!lastFree || first <= last);
                if (stepX) {
                    if (moveFirst) a.x = c.x;
                    else d.x = b.x;
                } else {
                    if (moveFirst) a.y = c.y;
                    else d.y = b.y;
                }
                bends.remove(i + 1);
                bends.remove(i);
                changed = true;
            }
        }
    }

    /** Names of the sides {@link ERPins#alignedSide} returns, by index. */
    private static final String[] SIDE_NAMES = {"up", "down", "left", "right"};

    private static boolean almostEqual(Vector2d a, Vector2d b) {
        return Math.abs(a.x - b.x) < 0.001 && Math.abs(a.y - b.y) < 0.001;
    }

    /** Inserts {@code stub} after the first (before the last) point of the route unless its neighbour lies there. */
    private static void insertStub(ArrayList<Vector2d> bends, boolean atSource, Vector2d stub) {
        if (atSource) {
            if (bends.size() == 1 || !almostEqual(stub, bends.get(1))) bends.add(1, stub);
        } else {
            int last = bends.size() - 1;
            if (last == 0 || !almostEqual(stub, bends.get(last - 1))) bends.add(last, stub);
        }
    }

    /** AF: a straight piece in front of each operator port, {side at source, side at target} (null = no stub). */
    private static void insertLogicStubs(ArrayList<Vector2d> bends, Node src, Node tgt, String[] sides) {
        if (bends.isEmpty()) {
            bends.add(AttributeHelper.getPositionVec2d(src));
            bends.add(AttributeHelper.getPositionVec2d(tgt));
        }
        if (sides[0] != null) insertStub(bends, true, logicStub(bends.get(0), src, sides[0]));
        if (sides[1] != null) insertStub(bends, false, logicStub(bends.get(bends.size() - 1), tgt, sides[1]));
    }

    /** The point in front of the port of {@code operator} on {@code side}, 15 px outside its border. */
    private static Vector2d logicStub(Vector2d centre, Node operator, String side) {
        double half = ("left".equals(side) || "right".equals(side) ? AttributeHelper.getWidth(operator) : AttributeHelper.getHeight(operator)) / 2;
        double d = half + 15;
        switch (side) {
            case "up": return new Vector2d(centre.x, centre.y - d);
            case "down": return new Vector2d(centre.x, centre.y + d);
            case "left": return new Vector2d(centre.x - d, centre.y);
            default: return new Vector2d(centre.x + d, centre.y);
        }
    }

    private static void applyBends(final Collection<Edge> edges,
                                   final HashMap<Edge, ConnRef> hmConnRefs,
                                   final View view,
                                   final ERPins erPins,
                                   final ERRouting er,
                                   final boolean processStubs,
                                   final boolean orthogonal,
                                   final double smallJog) {

        for (Edge edge : edges) {

            ConnRef connRef = hmConnRefs.get(edge);
            if (connRef == null) {
                continue;
            }

            ArrayList<Vector2d> edgeBends = AdaptagramsRouting.getEdgeBends(connRef);
            if (DEBUG) {
                StringBuilder route = new StringBuilder("[cblayout] route " + AttributeHelper.getLabel(edge.getSource(), "?") + " -> " + AttributeHelper.getLabel(edge.getTarget(), "?") + ":");
                for (Vector2d b : edgeBends) route.append(String.format(Locale.US, " (%.1f,%.1f)", b.x, b.y));
                System.out.println(route);
            }
            Node srcNode = edge.getSource();
            Node tgtNode = edge.getTarget();
            boolean processAtSource = ConstraintSBGNLayout.getSBGNRole(srcNode).contains("PROCESS");
            Node processNode = processAtSource ? srcNode : tgtNode;
            Node orientationNode = processAtSource ? tgtNode : srcNode;
            String orientation = null;
            double stubDistance = 20.0;   // length of the straight stub at the process

            String[] logicSides = ConstraintSBGNLayout.orientationLogic.get(edge);
            // AF: an end on a pin becomes a docking there, as in ER
            if (er == null && erPins != null) erPins.dock(edge, edgeBends);
            if (er != null && er.directions.containsKey(edge)) {
                // ER (ERLayout): libavoid already left each outcome or port in its direction; no process stub
                processNode = null;
                double[][] stubs = er.stubs.get(edge);
                if (stubs != null) snapToStubs(edgeBends, stubs, er.stubReach.get(edge), srcNode, tgtNode, er.keepNudgedStubs);
                removeSmallJogs(edgeBends, smallJog, stubs != null && stubs[0] != null, stubs != null && stubs[1] != null);
                // an end on a glyph pin becomes a docking there; the docking branch below drops the pin point
                if (erPins != null) erPins.dock(edge, edgeBends);
            } else if (logicSides != null) {
                // AF logical operator (AFLayout): stubs at the operator ports, possibly at both ends; the process
                // stub below does not apply.
                if (!orthogonal) insertLogicStubs(edgeBends, srcNode, tgtNode, logicSides);
                else snapToLogicPorts(edgeBends, srcNode, tgtNode, logicSides);
                if (orthogonal) removeSmallJogs(edgeBends, smallJog, logicSides[0] != null, logicSides[1] != null);
                processNode = null;
            } else if (!processStubs) {
                removeSmallJogs(edgeBends, smallJog, false, false);
                processNode = null;
            } else if (ConstraintSBGNLayout.reversibleRHS.contains(edge)) {
                orientation = ConstraintSBGNLayout.orientationREV.get(edge);
            } else if (ConstraintSBGNLayout.getSBGNRole(edge).contains("CONSUMPTION") || ConstraintSBGNLayout.getSBGNRole(edge).contains("PRODUCTION")) {
                orientation = ConstraintSBGNLayout.orientationProdSub.get(edge);
            } else if (ConstraintSBGNLayout.getSBGNRole(edge).contains("NECESSARYSTIMULATION")) {
                stubDistance = 40.;
                orientation = ConstraintSBGNLayout.orientationStim.get(edge);
            } else if (ConstraintSBGNLayout.getSBGNRole(edge).contains("CATALYSIS"))
            {
                stubDistance = 30.;
                orientation = ConstraintSBGNLayout.orientationStim.get(edge);
            } else if (ConstraintSBGNLayout.MOD_ROLES_ALL && ConstraintSBGNLayout.orientationStim.containsKey(edge)) {
                // -Dcblayout.modRoles=all: stimulation, inhibition, modulation and unknown influence get a stub like catalysis
                stubDistance = 30.;
                orientation = ConstraintSBGNLayout.orientationStim.get(edge);
            }
            // A constraint may have put the neighbour onto the process' row or column. Then the stub runs along that
            // line: one axis per process cannot express a corner process whose LHS sits above and whose RHS sits left.
            if (processNode != null) {
                int aligned = ERPins.alignedSide(processNode, orientationNode);
                if (aligned >= 0) orientation = SIDE_NAMES[aligned];
            }

            if (edgeBends.isEmpty()) {
                Vector2d srcPos = AttributeHelper.getPositionVec2d(srcNode);
                Vector2d tgtPos = AttributeHelper.getPositionVec2d(tgtNode);
                edgeBends.add(srcPos);
                edgeBends.add(tgtPos);
            }

            if (processNode != null && orientation != null) {
                Vector2d base = edgeBends.get(processNode == srcNode ? 0 : edgeBends.size() - 1);
                double x = base.x;
                double y = base.y;
                switch (orientation.toLowerCase()) {
                    case "up":
                        y -= stubDistance;
                        break;
                    case "down":
                        y += stubDistance;
                        break;
                    case "left":
                        x -= stubDistance;
                        break;
                    case "right":
                        x += stubDistance;
                        break;
                    default:
                        break;
                }
                insertStub(edgeBends, processNode == srcNode, new Vector2d(x, y));
            }

            Collection<Vector2d> edgeBendsToRemove = new ArrayList<Vector2d>();
            for (int k = 1; k < edgeBends.size() - 1; k++) {
                Vector2d bend_pre_k = edgeBends.get(k - 1);
                Vector2d bend_k = edgeBends.get(k);
                Vector2d bend_post_k = edgeBends.get(k + 1);

                if ((Math.abs(bend_pre_k.x - bend_k.x) < 0.0001 &&
                        Math.abs(bend_post_k.x - bend_k.x) < 0.0001) ||
                        (Math.abs(bend_pre_k.y - bend_k.y) < 0.0001 &&
                                Math.abs(bend_post_k.y - bend_k.y) < 0.0001)) {
                    edgeBendsToRemove.add(bend_k);
                }
            }
            edgeBends.removeAll(edgeBendsToRemove);

            AttributeHelper.removeEdgeBends(edge);

            EdgeGraphicAttribute edgeGraphicAttribute =
                    (EdgeGraphicAttribute) edge.getAttribute(GraphicAttributeConstants.GRAPHICS);
            DockingAttribute dockingAttribute = edgeGraphicAttribute.getDocking();

            String srcDocking = dockingAttribute.getSource();
            if (srcDocking != null && srcDocking.indexOf(";") > 0) {
                for (int k = 1; k < edgeBends.size(); k++) {
                    double deltaX = Math.abs(edgeBends.get(0).x - edgeBends.get(k).x);
                    double deltaY = Math.abs(edgeBends.get(0).y - edgeBends.get(k).y);
                    if ((deltaX < 0.0001 && deltaY < 1.0001) ||
                            (deltaX < 1.0001 && deltaY < 0.0001)) {
                        edgeBends.remove(k);
                        k--;
                    } else {
                        break;
                    }
                }
                edgeBends.remove(0);
            } else {
                AdaptagramsRouting.addBendPointForNodePosition(edgeBends, 0, 0,
                        AttributeHelper.getPositionVec2d(srcNode));
                if (edgeBends.size() > 2) {
                    AdaptagramsRouting.fixSourceBendPoints(edgeBends, view, srcNode);
                } else {
                    edgeBends.remove(0);
                }
            }

            String tgtDocking = dockingAttribute.getTarget();
            if (tgtDocking != null && tgtDocking.indexOf(";") > 0) {
                for (int k = edgeBends.size() - 2; k >= 0; k--) {
                    double deltaX = Math.abs(edgeBends.get(edgeBends.size() - 1).x - edgeBends.get(k).x);
                    double deltaY = Math.abs(edgeBends.get(edgeBends.size() - 1).y - edgeBends.get(k).y);
                    if ((deltaX < 0.0001 && deltaY < 1.0001) ||
                            (deltaX < 1.0001 && deltaY < 0.0001)) {
                        edgeBends.remove(k);
                    } else {
                        break;
                    }
                }
                edgeBends.remove(edgeBends.size() - 1);
            } else {
                AdaptagramsRouting.addBendPointForNodePosition(edgeBends,
                        edgeBends.size() - 1, edgeBends.size(),
                        AttributeHelper.getPositionVec2d(tgtNode));
                if (edgeBends.size() > 1) {
                    AdaptagramsRouting.fixTargetBendPoints(edgeBends, view, tgtNode);
                } else {
                    edgeBends.remove(edgeBends.size() - 1);
                }
            }

            if (!edgeBends.isEmpty()) {
                AttributeHelper.addEdgeBends(edge, edgeBends);
                AttributeHelper.setEdgeBendStyle(edge, GraphicAttributeConstants.POLYLINE_CLASSNAME);
            } else {
                // removeEdgeBends() fires no attribute event, so the view would keep the previous polyline; switching
                // to a straight line is a real attribute change and redraws the edge.
                AttributeHelper.setEdgeBendStyle(edge, GraphicAttributeConstants.STRAIGHTLINE_CLASSNAME);
            }
        }
    }
}
