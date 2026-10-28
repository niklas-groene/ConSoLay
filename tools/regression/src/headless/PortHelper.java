package headless;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.sbgn.bindings.Arc;
import org.sbgn.bindings.Glyph;
import org.sbgn.bindings.Port;
import org.sbgn.bindings.Sbgn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Port positions for the SBGN-ML writers. SBGN-ED stores the port coordinates read from the input
 * file in the edge attributes SBGN.BendOut / SBGN.BendIn; after a layout those coordinates are
 * stale and {@code SBGNMLHelper.getPort} then cannot assign a side (and leaves the port id null).
 * This helper derives the port from the current geometry instead: on the node axis that points
 * towards the adjacent bend (or the other end of the arc), {@link #PORT_GAP} px outside the border.
 */
final class PortHelper {

    /** Distance between node border and port, as in SBGN-ED's own files. */
    static final double PORT_GAP = 10;

    /**
     * -Dcblayout.keepPorts=true|false (default true): true keeps the arcs that share a port in the input on one port in
     * the output ({@link #planPorts}: one group per input port, the groups on two opposite sides); false derives every
     * port from the side its arc docks on, which can move a cofactor into the other port group of a process.
     */
    static final boolean KEEP_PORTS = Boolean.parseBoolean(System.getProperty("cblayout.keepPorts", "true"));

    private static final double[] SX = { -1, 1, 0, 0 }, SY = { 0, 0, -1, 1 };   // left, right, up, down
    private static final int[] OPP = { 1, 0, 3, 2 };

    /** Planned port position ("x;y") per edge: [source end, target end]; filled by planPorts. */
    private static final Map<Edge, String[]> PLAN = new IdentityHashMap<>();

    /**
     * Input arc id -> (glyph id -> id of that glyph's port the arc references); filled by readInputPorts. The graph
     * keeps only the arc's start / end point (BendOut / BendIn), which does not identify the port: two ports can
     * share coordinates, or an arc can be drawn to the glyph centre.
     */
    private static final Map<String, Map<String, String>> INPUT_PORTS = new HashMap<>();

    private PortHelper() { }

    /**
     * @param nodePos   centre of the node the port belongs to
     * @param nodeSize  width / height of that node
     * @param bends     the arc's bend points (may be modified: a stub-end bend next to the node is consumed)
     * @param atSource  true for the arc's source end, false for the target end
     * @param otherEnd  centre of the node at the other end (used when there are no bends)
     * @return "x;y" as expected by {@code SBGNMLHelper.getPort}
     */
    static String portPosition(Vector2d nodePos, Vector2d nodeSize, ArrayList<Vector2d> bends, boolean atSource, Vector2d otherEnd) {
        Vector2d adjacent = null;
        if (bends != null && !bends.isEmpty()) adjacent = atSource ? bends.get(0) : bends.get(bends.size() - 1);
        Vector2d toward = adjacent != null ? adjacent : otherEnd;
        double dx = toward == null ? 1 : toward.x - nodePos.x;
        double dy = toward == null ? 0 : toward.y - nodePos.y;
        double halfW = Math.max(1, nodeSize.x / 2), halfH = Math.max(1, nodeSize.y / 2);
        double px, py;
        if (Math.abs(dx) >= Math.abs(dy)) {
            px = nodePos.x + (dx < 0 ? -1 : 1) * (halfW + PORT_GAP);
            py = nodePos.y;
        } else {
            px = nodePos.x;
            py = nodePos.y + (dy < 0 ? -1 : 1) * (halfH + PORT_GAP);
        }
        // A bend right next to the node is the end of a stub, i.e. the port itself: consume it so the
        // arc does not get a redundant "next" point (this is what SBGN-ED's reader/writer pair does).
        if (adjacent != null && bends != null) {
            double dist = Math.hypot(adjacent.x - nodePos.x, adjacent.y - nodePos.y);
            if (dist <= Math.max(halfW, halfH) + PORT_GAP + 5) {
                if (atSource) bends.remove(0); else bends.remove(bends.size() - 1);
            }
        }
        return String.format(Locale.ROOT, "%.3f;%.3f", px, py);
    }

    /** As above, but returns the position planned by {@link #planPorts} for this arc end if there is one. */
    static String portPosition(Edge edge, Vector2d nodePos, Vector2d nodeSize, ArrayList<Vector2d> bends, boolean atSource, Vector2d otherEnd) {
        String geometric = portPosition(nodePos, nodeSize, bends, atSource, otherEnd);   // same bend handling either way
        String[] planned = PLAN.get(edge);
        String p = planned == null ? null : planned[atSource ? 0 : 1];
        return p != null ? p : geometric;
    }

    /**
     * Remembers the port every input arc end references, for {@link #planPorts} (no-op with
     * -Dcblayout.keepPorts=false). Call it once per file, before the graph is built.
     */
    static void readInputPorts(Sbgn sbgn) {
        INPUT_PORTS.clear();
        if (!KEEP_PORTS || sbgn == null || sbgn.getMap() == null || sbgn.getMap().isEmpty()) return;
        org.sbgn.bindings.Map map = sbgn.getMap().get(0);   // the map SBGNMLReader reads
        Map<String, String> portOwner = new HashMap<>();
        for (Glyph g : map.getGlyph()) collectPorts(g, portOwner);
        for (Arc arc : map.getArc()) {
            if (arc.getId() == null) continue;
            for (Object end : new Object[] { arc.getSource(), arc.getTarget() }) {
                String owner = end instanceof Port ? portOwner.get(((Port) end).getId()) : null;
                if (owner != null) INPUT_PORTS.computeIfAbsent(arc.getId(), k -> new HashMap<>()).put(owner, ((Port) end).getId());
            }
        }
    }

    private static void collectPorts(Glyph glyph, Map<String, String> portOwner) {
        for (Port p : glyph.getPort()) portOwner.put(p.getId(), glyph.getId());
        for (Glyph child : glyph.getGlyph()) collectPorts(child, portOwner);
    }

    private static final class End {
        final Edge edge; final boolean atSource; final double dx, dy;
        End(Edge edge, boolean atSource, double dx, double dy) {
            this.edge = edge; this.atSource = atSource; this.dx = dx; this.dy = dy;
        }
    }

    /** Plans the ports of one PD export (no-op with -Dcblayout.keepPorts=false). */
    static void planPorts(Graph graph) {
        PLAN.clear();
        if (!KEEP_PORTS) return;
        for (Node node : graph.getNodes()) {
            String glyphId = (String) AttributeHelper.getAttributeValue(node, "sbgn", "glyphid", null, "", false);
            if (glyphId == null) continue;
            Vector2d pos = AttributeHelper.getPositionVec2d(node), size = AttributeHelper.getSize(node);
            double halfW = Math.max(1, size.x / 2), halfH = Math.max(1, size.y / 2);
            Map<String, List<End>> byPort = new LinkedHashMap<>();   // input port id -> arc ends, in edge order
            for (Edge e : node.getEdges()) {
                if (e.getSource() == e.getTarget()) continue;
                boolean atSource = e.getSource() == node;
                String docking = (String) AttributeHelper.getAttributeValue(e, "graphics.docking", atSource ? "source" : "target", "", "", false);
                if (docking.length() > 0) continue;   // the writer uses the docking there, not a port
                // the writer puts a port only where the reader found one (BendOut / BendIn set)
                if (AttributeHelper.getAttributeValue(e, "SBGN", atSource ? "BendOut" : "BendIn", null, "", false) == null) continue;
                Map<String, String> ports = INPUT_PORTS.get((String) AttributeHelper.getAttributeValue(e, "sbgn", "glyphid", null, "", false));
                String port = ports == null ? null : ports.get(glyphId);
                if (port == null) continue;
                // docking direction exactly as portPosition sees it: adjacent bend, else the other node
                ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(e);
                Vector2d toward = bends != null && !bends.isEmpty() ? (atSource ? bends.get(0) : bends.get(bends.size() - 1))
                        : AttributeHelper.getPositionVec2d(atSource ? e.getTarget() : e.getSource());
                byPort.computeIfAbsent(port, k -> new ArrayList<>()).add(new End(e, atSource, toward.x - pos.x, toward.y - pos.y));
            }
            if (byPort.isEmpty()) continue;
            if (byPort.size() > 2) {
                System.err.println("WARN keepPorts: " + glyphId + " has " + byPort.size() + " input ports; ports taken from the geometry");
                continue;
            }
            List<List<End>> groups = new ArrayList<>(byPort.values());
            // count[g][s]: arcs of group g docking on side s; proj breaks ties by how squarely they point there
            int[][] count = new int[groups.size()][4];
            double[][] proj = new double[groups.size()][4];
            for (int g = 0; g < groups.size(); g++) {
                for (End end : groups.get(g)) {
                    double len = Math.hypot(end.dx, end.dy);
                    double ux = len == 0 ? 1 : end.dx / len, uy = len == 0 ? 0 : end.dy / len;
                    int side = Math.abs(ux) >= Math.abs(uy) ? (ux < 0 ? 0 : 1) : (uy < 0 ? 2 : 3);
                    count[g][side]++;
                    for (int s = 0; s < 4; s++) proj[g][s] += ux * SX[s] + uy * SY[s];
                }
            }
            int best = 0, bestCount = -1;
            double bestProj = 0;
            for (int s = 0; s < 4; s++) {
                int c = count[0][s] + (groups.size() == 2 ? count[1][OPP[s]] : 0);
                double p = proj[0][s] + (groups.size() == 2 ? proj[1][OPP[s]] : 0);
                if (c > bestCount || (c == bestCount && p > bestProj + 1e-9)) { best = s; bestCount = c; bestProj = p; }
            }
            for (int g = 0; g < groups.size(); g++) {
                int s = g == 0 ? best : OPP[best];
                String p = String.format(Locale.ROOT, "%.3f;%.3f", pos.x + SX[s] * (halfW + PORT_GAP), pos.y + SY[s] * (halfH + PORT_GAP));
                for (End end : groups.get(g)) PLAN.computeIfAbsent(end.edge, k -> new String[2])[end.atSource ? 0 : 1] = p;
            }
        }
    }
}
