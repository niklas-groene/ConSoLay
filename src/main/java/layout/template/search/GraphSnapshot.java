package layout.template.search;

import layout.template.model.Condition;

import org.AttributeHelper;
import org.graffiti.attributes.Attributable;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable copy of what a search needs from a graph: its structure (nodes, directed or undirected edges, parallel
 * edges and self-loops as they are) and the attribute values the searched templates ask about. Made on the Swing thread
 * ({@link #capture}); the search then runs on it in the background and never touches the live graph. The node and edge
 * objects are kept only to name the result; no method of them is called off the Swing thread.
 *
 * @author Niklas Gröne
 */
public final class GraphSnapshot {

    /** The graph it was made from (identity only). */
    public final Graph graph;
    /** Number of structural changes of the graph when it was made (see {@link #capture}). */
    public final long version;
    public final Node[] nodes;
    public final Edge[] edges;
    /** Edge i runs from node {@code src[i]} to node {@code dst[i]}. */
    public final int[] src, dst;
    public final boolean[] directed;
    /** Per node: indices of the edges that touch it (a self-loop once). */
    public final int[][] incident;
    /** Per node: its label (plain text). */
    public final String[] labels;
    /** Attribute path → value per node / per edge ("" if the element has none). */
    final Map<String, String[]> nodeAttrs = new HashMap<>();
    final Map<String, String[]> edgeAttrs = new HashMap<>();
    /** Nodes a search may use (null: all). */
    public final boolean[] allowed;

    private GraphSnapshot(Graph g, long version, List<Node> ns, List<Edge> es, Set<String> nodePaths, Set<String> edgePaths,
                          Collection<Node> scope) {
        this.graph = g;
        this.version = version;
        this.nodes = ns.toArray(new Node[0]);
        Map<Node, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < nodes.length; i++) index.put(nodes[i], i);
        List<Edge> kept = new ArrayList<>();
        for (Edge e : es) if (index.containsKey(e.getSource()) && index.containsKey(e.getTarget())) kept.add(e);
        this.edges = kept.toArray(new Edge[0]);
        int n = nodes.length, m = edges.length;
        src = new int[m];
        dst = new int[m];
        directed = new boolean[m];
        List<List<Integer>> inc = new ArrayList<>();
        for (int i = 0; i < n; i++) inc.add(new ArrayList<>());
        for (int i = 0; i < m; i++) {
            src[i] = index.get(edges[i].getSource());
            dst[i] = index.get(edges[i].getTarget());
            directed[i] = edges[i].isDirected();
            inc.get(src[i]).add(i);
            if (dst[i] != src[i]) inc.get(dst[i]).add(i);
        }
        incident = new int[n][];
        for (int i = 0; i < n; i++) {
            List<Integer> l = inc.get(i);
            incident[i] = new int[l.size()];
            for (int k = 0; k < l.size(); k++) incident[i][k] = l.get(k);
        }
        labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = plain(AttributeHelper.getLabel(nodes[i], ""));
        for (String path : nodePaths) {
            String[] v = new String[n];
            for (int i = 0; i < n; i++) v[i] = read(nodes[i], path);
            nodeAttrs.put(path, v);
        }
        for (String path : edgePaths) {
            String[] v = new String[m];
            for (int i = 0; i < m; i++) v[i] = read(edges[i], path);
            edgeAttrs.put(path, v);
        }
        if (scope == null) {
            allowed = null;
        } else {
            allowed = new boolean[n];
            for (Node s : scope) {
                Integer i = index.get(s);
                if (i != null) allowed[i] = true;
            }
        }
    }

    /**
     * Captures {@code g} (on the Swing thread). {@code nodePaths} / {@code edgePaths}: the attributes the searched
     * templates' conditions read (the SBGN role is always captured); {@code scope}: the nodes a match may use, or null
     * for the whole graph; {@code version}: the caller's structure counter, to recognise stale results.
     */
    public static GraphSnapshot capture(Graph g, Collection<Node> scope, Set<String> nodePaths, Set<String> edgePaths, long version) {
        Set<String> np = new LinkedHashSet<>(nodePaths), ep = new LinkedHashSet<>(edgePaths);
        np.add(Condition.SBGN_ROLE);
        ep.add(Condition.SBGN_ROLE);
        return new GraphSnapshot(g, version, new ArrayList<>(g.getNodes()), new ArrayList<>(g.getEdges()), np, ep, scope);
    }

    public int nodeCount() {
        return nodes.length;
    }

    public boolean isAllowed(int node) {
        return allowed == null || allowed[node];
    }

    /** The nodes a search may use, as given to {@link #capture} (null: the whole graph). */
    public List<Node> scope() {
        if (allowed == null) return null;
        List<Node> l = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) if (allowed[i]) l.add(nodes[i]);
        return l;
    }

    /** The value of {@code path} at node {@code i} ("" if none; label and SBGN role always known). */
    public String nodeValue(String path, int i) {
        if (Condition.LABEL.equals(path)) return labels[i];
        String[] v = nodeAttrs.get(path);
        return v == null ? "" : v[i];
    }

    public String edgeValue(String path, int i) {
        String[] v = edgeAttrs.get(path);
        return v == null ? "" : v[i];
    }

    public String role(int node) {
        return nodeValue(Condition.SBGN_ROLE, node);
    }

    /** The node at the other end of edge {@code e} seen from {@code from}. */
    public int other(int e, int from) {
        return src[e] == from ? dst[e] : src[e];
    }

    /** Reads an attribute path: "label", or "folder.name" (VANTED attribute folder and name). */
    static String read(Attributable a, String path) {
        try {
            if (Condition.LABEL.equals(path)) return plain(AttributeHelper.getLabel(a, ""));
            int dot = path.lastIndexOf('.');
            String folder = dot < 0 ? "" : path.substring(0, dot), name = dot < 0 ? path : path.substring(dot + 1);
            Object o = AttributeHelper.getAttributeValue(a, folder, name, null, "", false);
            return o == null ? "" : String.valueOf(o);
        } catch (Exception e) {
            return "";
        }
    }

    static String plain(String l) {
        return l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }
}
