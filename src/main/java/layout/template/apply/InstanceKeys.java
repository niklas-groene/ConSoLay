package layout.template.apply;

import constraints.spec.ConstraintSpecs;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The key that identifies an application on a graph: the template's id and revision, the constraints it makes on which
 * graph nodes (canonical form, so symmetric assignments that make the same constraints get the same key) and where it
 * places them. Applying the same template revision to the same place again gives the same key and is recognised as a
 * duplicate.
 *
 * @author Niklas Gröne
 */
public final class InstanceKeys {

    private InstanceKeys() { }

    public static String key(InstancePlan plan, Map<String, Node> nodes, Map<String, Edge> edges) {
        Function<String, String> ref = id -> {
            Node n = nodes.get(id);
            if (n != null) return "N" + n.getID();
            Edge e = edges.get(id);
            return e != null ? "E" + e.getID() : "?" + id;
        };
        return plan.template.id + "@" + plan.template.revision + "|" + application(plan, ref, q -> {
            Node n = nodes.get(q);
            return n == null ? null : n.getID();
        });
    }

    /**
     * What applying {@code plan} under an assignment leads to, as text: the canonical form of every constraint
     * ({@code ref} names an assigned element) and the local offset of every node from the centroid of its pattern part,
     * by the number {@code node} gives the assigned graph node (null: not assigned).
     */
    public static String application(InstancePlan plan, Function<String, String> ref, Function<String, Long> node) {
        List<String> parts = new ArrayList<>();
        for (InstancePlan.Item i : plan.items) parts.add(ConstraintSpecs.canonical(i.spec, ref));
        Collections.sort(parts);
        TreeMap<String, String> place = new TreeMap<>();
        for (Map.Entry<String, List<String>> part : plan.patternParts.entrySet()) {
            double cx = 0, cy = 0;
            List<Long> targets = new ArrayList<>();
            for (String q : part.getValue()) {
                double[] p = plan.positions.get(q);
                cx += p[0];
                cy += p[1];
                Long n = node.apply(q);
                targets.add(n == null ? -1L : n);
            }
            int k = Math.max(1, part.getValue().size());
            cx /= k;
            cy /= k;
            Collections.sort(targets);
            for (String q : part.getValue()) {
                double[] p = plan.positions.get(q);
                Long n = node.apply(q);
                place.put("N" + (n == null ? -1 : n) + "@" + targets, String.format(Locale.ROOT, "%.2f,%.2f", p[0] - cx, p[1] - cy));
            }
        }
        return String.join(";", parts) + "#" + place;
    }
}
