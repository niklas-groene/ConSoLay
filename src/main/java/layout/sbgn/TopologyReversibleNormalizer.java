package layout.sbgn;

import org.AttributeHelper;
import org.graffiti.attributes.Attribute;
import org.graffiti.attributes.AttributeNotFoundException;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical drawing direction for the two interchangeable sides of a reversible process. */
final class TopologyReversibleNormalizer {
    private static final String LHS = "REVERSIBLELHS", RHS = "REVERSIBLERHS";

    /** What {@link #normalize} changed on one edge, for {@link #restore}. */
    static final class State {
        private final String role;
        private final String arrowhead, arrowtail;
        private final String bendOut, bendIn;
        private final Attribute consumptionLabel, productionLabel;
        private final Node source, target;

        State(Edge edge) {
            role = role(edge);
            arrowhead = AttributeHelper.getArrowhead(edge);
            arrowtail = AttributeHelper.getArrowtail(edge);
            bendOut = portMarker(edge, "BendOut");
            bendIn = portMarker(edge, "BendIn");
            consumptionLabel = copyAttribute(edge, "srcLabel");
            productionLabel = copyAttribute(edge, "tgtLabel");
            source = edge.getSource(); target = edge.getTarget();
        }
    }

    private TopologyReversibleNormalizer() { }

    /**
     * Makes the port group with the lowest arc ID (natural order, arc9 before arc10; a repeated or missing ID is
     * resolved by species identity, never by the current roles or coordinates) the RHS of its reversible process, and
     * points RHS arcs away from the process and LHS arcs towards it. Both reversible roles export as production arcs,
     * so only the drawing convention changes; returns the previous state of every changed edge.
     */
    static Map<Edge, State> normalize(Graph graph) {
        Map<Edge, State> saved = new LinkedHashMap<>();
        List<Node> processes = new ArrayList<>(graph.getNodes());
        processes.sort(Comparator.comparing(TopologySkeleton::key));
        for (Node process : processes) {
            if (!isProcess(process)) continue;
            List<Edge> lhs = new ArrayList<>(), rhs = new ArrayList<>();
            for (Edge edge : process.getEdges()) {
                Node other = other(edge, process);
                // Malformed process-to-process arcs do not define reversible port groups.
                if (other == process || isProcess(other)) continue;
                String role = role(edge);
                if (role.contains(LHS)) lhs.add(edge);
                else if (role.contains(RHS)) rhs.add(edge);
            }
            if (lhs.isEmpty() && rhs.isEmpty()) continue;
            Comparator<Edge> order = (a, b) -> compareEdges(a, b, process);
            lhs.sort(order); rhs.sort(order);
            boolean swap = !lhs.isEmpty() && (rhs.isEmpty() || compareGroups(lhs, rhs, order) < 0);
            List<Edge> edges = new ArrayList<>(lhs); edges.addAll(rhs); edges.sort(order);
            for (Edge edge : edges) {
                String oldRole = role(edge);
                String newRole = swap ? oldRole.contains(LHS)
                        ? oldRole.replace(LHS, RHS) : oldRole.replace(RHS, LHS) : oldRole;
                boolean reverse = newRole.contains(RHS)
                        ? edge.getSource() != process : edge.getTarget() != process;
                if (!reverse && oldRole.equals(newRole)) continue;
                State state = new State(edge);
                saved.put(edge, state);
                if (!oldRole.equals(newRole)) {
                    AttributeHelper.setAttribute(edge, "sbgn", "role", newRole);
                    // Cardinality is selected by role in SBGN writers, even for a role-only repair.
                    setLabelAttribute(edge, "srcLabel", state.productionLabel);
                    setLabelAttribute(edge, "tgtLabel", state.consumptionLabel);
                }
                if (reverse) {
                    String head = AttributeHelper.getArrowhead(edge), tail = AttributeHelper.getArrowtail(edge);
                    String out = portMarker(edge, "BendOut"), in = portMarker(edge, "BendIn");
                    edge.reverse();
                    // Graph.reverse swaps endpoints only. Keep the production arrow on the species.
                    AttributeHelper.setArrowhead(edge, tail);
                    AttributeHelper.setArrowtail(edge, head);
                    // The writer uses marker presence to distinguish a port from a glyph boundary.
                    // Keep that endpoint identity when the internal drawing direction changes.
                    setPortMarker(edge, "BendOut", in);
                    setPortMarker(edge, "BendIn", out);
                }
            }
        }
        return saved;
    }

    /** Undoes exactly the changes of {@link #normalize}, including a role-only change. */
    static void restore(Map<Edge, State> saved) {
        if (saved == null) return;
        for (Map.Entry<Edge, State> entry : saved.entrySet()) {
            Edge edge = entry.getKey(); State state = entry.getValue();
            if (edge.getSource() == state.target && edge.getTarget() == state.source) edge.reverse();
            else if (edge.getSource() != state.source || edge.getTarget() != state.target) {
                edge.setSource(state.source); edge.setTarget(state.target);
            }
            AttributeHelper.setAttribute(edge, "sbgn", "role", state.role);
            AttributeHelper.setArrowhead(edge, state.arrowhead);
            AttributeHelper.setArrowtail(edge, state.arrowtail);
            setPortMarker(edge, "BendOut", state.bendOut);
            setPortMarker(edge, "BendIn", state.bendIn);
            setLabelAttribute(edge, "srcLabel", state.consumptionLabel);
            setLabelAttribute(edge, "tgtLabel", state.productionLabel);
        }
    }

    private static Attribute copyAttribute(Edge edge, String name) {
        try { return (Attribute) edge.getAttribute(name).copy(); }
        catch (AttributeNotFoundException absent) { return null; }
    }

    private static void setLabelAttribute(Edge edge, String name, Attribute value) {
        AttributeHelper.deleteAttribute(edge, "", name);
        if (value != null) {
            Attribute copy = (Attribute) value.copy();
            copy.setId(name);
            edge.addAttribute(copy, "");
        }
    }

    private static String portMarker(Edge edge, String name) {
        return (String) AttributeHelper.getAttributeValue(edge, "SBGN", name, null, "", false);
    }

    private static void setPortMarker(Edge edge, String name, String value) {
        if (value == null) AttributeHelper.deleteAttribute(edge, "SBGN", name);
        else AttributeHelper.setAttribute(edge, "SBGN", name, value);
    }

    private static String role(Edge edge) { return ConstraintSBGNLayout.getSBGNRole(edge); }

    private static boolean isProcess(Node node) {
        String role = ConstraintSBGNLayout.getSBGNRole(node);
        return role.contains("PROCESS") || role.contains("ASSOCIATION") || role.contains("DISSOCIATION");
    }

    private static Node other(Edge edge, Node process) {
        return edge.getSource() == process ? edge.getTarget() : edge.getSource();
    }

    private static String id(Edge edge) {
        Object value = AttributeHelper.getAttributeValue(edge, "sbgn", "glyphid", "", "", false);
        return value == null ? "" : value.toString().trim();
    }

    private static int compareEdges(Edge a, Edge b, Node process) {
        String aid = id(a), bid = id(b);
        if (aid.isEmpty() != bid.isEmpty()) return aid.isEmpty() ? 1 : -1;
        int result = naturalCompare(aid, bid);
        return result != 0 ? result : naturalCompare(TopologySkeleton.key(other(a, process)),
                TopologySkeleton.key(other(b, process)));
    }

    private static int compareGroups(List<Edge> a, List<Edge> b, Comparator<Edge> order) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int result = order.compare(a.get(i), b.get(i));
            if (result != 0) return result;
        }
        return Integer.compare(a.size(), b.size());
    }

    /** Compare digit runs without parsing integers, so IDs of any length remain valid. */
    private static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ac = a.charAt(i), bc = b.charAt(j);
            if (digit(ac) && digit(bc)) {
                int ae = i, be = j;
                while (ae < a.length() && digit(a.charAt(ae))) ae++;
                while (be < b.length() && digit(b.charAt(be))) be++;
                int az = i, bz = j;
                while (az < ae && a.charAt(az) == '0') az++;
                while (bz < be && b.charAt(bz) == '0') bz++;
                int result = Integer.compare(ae - az, be - bz);
                if (result != 0) return result;
                while (az < ae) {
                    result = Character.compare(a.charAt(az++), b.charAt(bz++));
                    if (result != 0) return result;
                }
                i = ae; j = be;
            } else {
                if (ac != bc) return Character.compare(ac, bc);
                i++; j++;
            }
        }
        int result = Integer.compare(a.length() - i, b.length() - j);
        return result != 0 ? result : a.compareTo(b);
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
}
