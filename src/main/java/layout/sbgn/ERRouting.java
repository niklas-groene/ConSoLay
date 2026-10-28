package layout.sbgn;

import org.graffiti.graph.Edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an ER pipeline ({@link ERLayout}, {@link ERHyperedgeLayout}) tells the edge router ({@link SBGNEdgeRouter})
 * about the edges of one routing run.
 */
final class ERRouting {

    /**
     * Edge -> {direction at the source, direction at the target} in which the routed edge leaves that end ("up",
     * "down", "left", "right", "horizontal", "vertical"; null: any). The router hands them to libavoid. The end of a
     * stub ({@link #stubs}) lists several, joined by commas: a straight stub the way it points and then the two turns
     * ("up,right,left"), a diagonal stub its two axes ("right,down"). Every edge listed here is routed as an ER edge:
     * it ends on a pin of its glyph ({@link ERPins}) and gets no process stub.
     */
    final Map<Edge, String[]> directions = new HashMap<>();

    /** Points a routed edge has to pass (the port of a logical operator, the corners of a C-shaped edge). */
    final Map<Edge, double[][]> checkpoints = new HashMap<>();

    /**
     * Edge -> {point at the source, point at the target} (null: none): the edge runs straight from its node to that
     * point, a stub, and only the rest is routed; the direction of that end ({@link #directions}) is the one in which
     * the route leaves the stub's end.
     */
    final Map<Edge, double[][]> stubs = new HashMap<>();

    /**
     * Edge -> for the stub at {source, target} ({@link #stubs}), how far from its node's centre the end of the stub
     * has to stay at least: up to there the symbol lies on it. A stub that the router's nudge would make shorter
     * goes back to its planned end ({@link #keepNudgedStubs}).
     */
    final Map<Edge, double[]> stubReach = new HashMap<>();

    /**
     * Rectangles {left, top, right, bottom} no route may run through: the symbol at the end of a stub (its arrowhead),
     * so that no other edge passes through it. The router adds them as obstacles nothing connects to.
     */
    final List<double[]> keepOut = new ArrayList<>();

    /** Edges the router does not route: they are drawn as straight lines by the pipeline. */
    final Set<Edge> straight = new HashSet<>();

    /**
     * Px a route keeps away from the nodes it does not end at, and px between two parallel segments; 0: the
     * router's own values (2 and 4, chosen for the polylines of PD maps).
     */
    double shapeBuffer, nudging;

    /**
     * A route that turns off at the end of a stub keeps the place the router nudged it to; the stub gets that much
     * longer or shorter. Else the end goes back onto the planned end of the stub, next to the routes it was nudged
     * away from.
     */
    boolean keepNudgedStubs;

    /** Steps of a few px in a route are taken out ({@link SBGNEdgeRouter#SMALL_JOG}). */
    boolean removeSmallJogs;

    /**
     * The routed ends on a glyph keep clear of each other: an end that meets its glyph straight closer to a corner
     * than a spread pin may lie, on the spot of another such end or where a straight edge ({@link #straight}) meets
     * the glyph takes a spread pin instead, and no spread pin lies where a straight edge ends.
     */
    boolean clearStraightPins;
}
