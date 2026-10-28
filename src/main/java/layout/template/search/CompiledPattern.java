package layout.template.search;

import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.TemplateDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pattern template in the form the search works with: nodes and edges by index, the edges per node and per node
 * pair, and the conditions. Immutable, so a background search can read it.
 *
 * @author Niklas Gröne
 */
final class CompiledPattern {

    final String[] nodeIds;
    final List<List<Condition>> nodeConditions;
    final String[] edgeIds;
    final int[] src, dst;
    final boolean[] directed;
    final List<List<Condition>> edgeConditions;
    /** Pattern node → the edges between it and each other node (self-loops under its own index). */
    final List<Map<Integer, List<Integer>>> pairEdges;
    /** Number of edge ends per node (a self-loop counts once). */
    final int[] degree;
    final int[] selfLoops;

    CompiledPattern(TemplateDef t) {
        int n = t.nodes.size();
        nodeIds = new String[n];
        Map<String, Integer> index = new HashMap<>();
        List<List<Condition>> nc = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PatternNode p = t.nodes.get(i);
            nodeIds[i] = p.id;
            index.put(p.id, i);
            List<Condition> cs = new ArrayList<>();
            for (Condition c : p.conditions) cs.add(c.copy());
            nc.add(Collections.unmodifiableList(cs));
        }
        nodeConditions = Collections.unmodifiableList(nc);
        List<PatternEdge> valid = new ArrayList<>();
        for (PatternEdge e : t.edges) if (index.containsKey(e.source) && index.containsKey(e.target)) valid.add(e);
        int m = valid.size();
        edgeIds = new String[m];
        src = new int[m];
        dst = new int[m];
        directed = new boolean[m];
        List<List<Condition>> ec = new ArrayList<>();
        degree = new int[n];
        selfLoops = new int[n];
        List<Map<Integer, List<Integer>>> pe = new ArrayList<>();
        for (int i = 0; i < n; i++) pe.add(new HashMap<>());
        for (int i = 0; i < m; i++) {
            PatternEdge e = valid.get(i);
            edgeIds[i] = e.id;
            src[i] = index.get(e.source);
            dst[i] = index.get(e.target);
            directed[i] = e.directed;
            List<Condition> cs = new ArrayList<>();
            for (Condition c : e.conditions) cs.add(c.copy());
            ec.add(Collections.unmodifiableList(cs));
            pe.get(src[i]).computeIfAbsent(dst[i], k -> new ArrayList<>()).add(i);
            if (src[i] != dst[i]) {
                pe.get(dst[i]).computeIfAbsent(src[i], k -> new ArrayList<>()).add(i);
                degree[src[i]]++;
                degree[dst[i]]++;
            } else {
                degree[src[i]]++;
                selfLoops[src[i]]++;
            }
        }
        edgeConditions = Collections.unmodifiableList(ec);
        pairEdges = pe;
    }

    int nodeCount() {
        return nodeIds.length;
    }

    /** The pattern edges between nodes {@code a} and {@code b} (a == b: the self-loops of a). */
    List<Integer> between(int a, int b) {
        List<Integer> l = pairEdges.get(a).get(b);
        return l == null ? Collections.<Integer>emptyList() : l;
    }
}
