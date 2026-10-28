package layout.template.search;

import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.StructureLink;
import layout.template.model.Templates;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks again, on the live graph, whether a match still fits (after the graph changed): its nodes and edges are still
 * there and the template still occurs on exactly these nodes with the chosen assignment (structure, direction,
 * semantic conditions and the exact-structure option of its search). Runs a search restricted to the match's nodes,
 * so it is cheap; call it on the Swing thread.
 *
 * @author Niklas Gröne
 */
public final class MatchValidator {

    private MatchValidator() { }

    /** Null if the match still fits as chosen; else the reason. */
    public static String revalidate(Match m, Graph g) {
        Match.Variant v = m.variant();
        String gone = missing(g, v.nodes, v.edges, v.links);
        if (gone != null) return gone;
        SearchTask task = m.task;
        Set<String> np = new HashSet<>(), ep = new HashSet<>();
        for (PatternNode p : Templates.flatNodes(task.template, task.source).values()) {
            for (Condition c : p.conditions) np.add(c.attribute);
        }
        for (PatternEdge p : Templates.flatEdges(task.template, task.source).values()) {
            for (Condition c : p.conditions) ep.add(c.attribute);
        }
        for (StructureLink l : task.template.links) for (Condition c : l.edgeConditions) ep.add(c.attribute);
        GraphSnapshot snap = GraphSnapshot.capture(g, m.nodes(), np, ep, 0);
        Enumerator en = new Enumerator(snap, m.run.options.semantics, m.run.options.exact, null);
        boolean[] same = {false};
        en.enumerate(task.template, task.source, fm -> {
            for (Map.Entry<String, Node> e : v.nodes.entrySet()) {
                Integer i = fm.nodes.get(e.getKey());
                if (i == null || snap.nodes[i] != e.getValue()) return true;
            }
            same[0] = true;
            return false;
        });
        if (same[0]) return null;
        return m.run.options.semantics ? "the template no longer fits these nodes (structure or semantic conditions changed)"
                : "the template no longer fits these nodes (structure changed)";
    }

    /** Which element of an assignment is no longer on {@code g}, or null if all of them are. */
    public static String missing(Graph g, Map<String, Node> nodes, Map<String, Edge> edges, Map<String, Edge> links) {
        for (Node n : nodes.values()) if (n.getGraph() != g || !g.containsNode(n)) return "a node of the match was deleted";
        for (Edge e : edges.values()) if (e.getGraph() != g || !g.containsEdge(e)) return "an edge of the match was deleted";
        for (Edge e : links.values()) if (e.getGraph() != g || !g.containsEdge(e)) return "a joining edge was deleted";
        return null;
    }
}
