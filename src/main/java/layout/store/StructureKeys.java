package layout.store;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A key per node that names its place in the graph's structure: its label and, refined round by round, the keys of the
 * nodes its edges lead to and come from (colour refinement). It depends neither on positions nor on the order of the
 * nodes, so the same map opened again gives every node the same key, also where the map file keeps no id of its own and
 * labels repeat (the empty labels of SBGN processes, ATP and ADP of every reaction). Nodes the structure cannot tell
 * apart share a key.
 *
 * @author Niklas Gröne
 */
final class StructureKeys {

    /** Rounds at most; refining stops earlier once a round splits no class of nodes any more. */
    static final int MAX_ROUNDS = 12;

    private StructureKeys() { }

    /** The key of every node of {@code g}. */
    static Map<Node, String> of(Graph g) {
        List<Node> nodes = new ArrayList<>(g.getNodes());
        Map<Node, List<Node>> out = new IdentityHashMap<>(), in = new IdentityHashMap<>();
        for (Node n : nodes) {
            out.put(n, new ArrayList<>());
            in.put(n, new ArrayList<>());
        }
        for (Edge e : g.getEdges()) {
            List<Node> o = out.get(e.getSource()), i = in.get(e.getTarget());
            if (o == null || i == null) continue;
            o.add(e.getTarget());
            i.add(e.getSource());
        }
        Map<Node, String> key = new IdentityHashMap<>();
        for (Node n : nodes) key.put(n, hash("l:" + label(n)));
        int classes = classes(key);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<Node, String> next = new IdentityHashMap<>();
            for (Node n : nodes) {
                next.put(n, hash(key.get(n) + "|o:" + joined(out.get(n), key) + "|i:" + joined(in.get(n), key)));
            }
            key = next;
            int c = classes(key);
            if (c == classes) break;   // stable: every later round only renames the classes
            classes = c;
        }
        return key;
    }

    /** The nodes of {@code g} by key, for the keys exactly one node has. */
    static Map<String, Node> unique(Map<Node, String> keys) {
        Map<String, Node> byKey = new HashMap<>();
        java.util.Set<String> shared = new HashSet<>();
        for (Map.Entry<Node, String> e : keys.entrySet()) {
            if (byKey.put(e.getValue(), e.getKey()) != null) shared.add(e.getValue());
        }
        byKey.keySet().removeAll(shared);
        return byKey;
    }

    private static String label(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        return l == null ? "" : l.trim();
    }

    private static String joined(List<Node> ns, Map<Node, String> key) {
        List<String> l = new ArrayList<>(ns.size());
        for (Node n : ns) l.add(key.get(n));
        Collections.sort(l);
        return String.join(",", l);
    }

    private static int classes(Map<Node, String> key) {
        return new HashSet<>(key.values()).size();
    }

    /** 64 bits of SHA-1 as 16 hex digits. */
    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
