package layout.template.apply;

import constraints.Constraint;
import constraints.Constraints;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.io.TemplateFiles;
import layout.template.model.TemplateDef;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * "Template from selection": a new pattern template from selected nodes of a graph, with the edges between them and
 * the existing constraints whose elements all lie in the selection. Constraints that reach outside are not cut down;
 * they are listed with the reason instead, and so are constraints the SBGN steps made.
 *
 * @author Niklas Gröne
 */
public final class TemplateCapture {

    private TemplateCapture() { }

    /** The new template and what was left out. */
    public static final class Result {
        public final TemplateDef template;
        public final int taken;
        /** One line per constraint not taken over, with the reason. */
        public final List<String> notTaken = new ArrayList<>();

        Result(TemplateDef template, int taken) {
            this.template = template;
            this.taken = taken;
        }
    }

    /**
     * @param nodes the selected nodes (in selection order; their order gives the local ids)
     * @param store the constraints of the graph (may be null)
     * @param describe names a constraint for the report
     */
    public static Result capture(Graph g, List<Node> nodes, Constraints store, Function<Constraint, String> describe,
                                 String name) {
        Set<Node> sel = new HashSet<>(nodes);
        List<Edge> edges = new ArrayList<>();
        for (Edge e : g.getEdges()) if (sel.contains(e.getSource()) && sel.contains(e.getTarget())) edges.add(e);
        TemplateDef t = TemplateFiles.fromGraph(nodes, edges, name);
        t.source = "selection";
        // local ids in the order of fromGraph (nodes in order, edges in order)
        Map<Node, String> nodeIds = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) nodeIds.put(nodes.get(i), t.nodes.get(i).id);
        Map<Edge, String> edgeIds = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) edgeIds.put(edges.get(i), t.edges.get(i).id);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (Node n : nodes) {
            minX = Math.min(minX, AttributeHelper.getPositionX(n) - AttributeHelper.getWidth(n) / 2);
            minY = Math.min(minY, AttributeHelper.getPositionY(n) - AttributeHelper.getHeight(n) / 2);
        }
        final double ox = minX, oy = minY;
        ConstraintSpecs.Naming naming = new ConstraintSpecs.Naming() {
            @Override public String node(Node n) { return nodeIds.get(n); }
            @Override public String edge(Edge e) { return edgeIds.get(e); }
            @Override public double[] toLocal(double x, double y) { return new double[]{x - ox, y - oy}; }
        };
        int taken = 0;
        List<String> left = new ArrayList<>();
        if (store != null) {
            for (Constraint c : store.getAllConstraints()) {
                boolean anyIn = false, allIn = true;
                for (Node n : c.getNodes()) {
                    if (sel.contains(n)) anyIn = true;
                    else allIn = false;
                }
                if (!anyIn) continue;
                String what = describe == null ? c.getKind().name() : describe.apply(c);
                if (!allIn) {
                    Node outside = null;
                    for (Node n : c.getNodes()) if (!sel.contains(n)) { outside = n; break; }
                    left.add("'" + what + "': refers to '" + TemplateFiles.plainLabel(AttributeHelper.getLabel(outside, "")) + "' outside the selection");
                    continue;
                }
                if (c.isAutomatic()) {
                    left.add("'" + what + "': made by an SBGN layout step, not by you");
                    continue;
                }
                if (!ConstraintSpecs.supports(c)) {
                    left.add("'" + what + "': this type cannot be stored in a template");
                    continue;
                }
                try {
                    ConstraintSpec s = ConstraintSpecs.toSpec(c, naming);
                    s.id = t.nextConstraintId();
                    t.constraints.add(s);
                    taken++;
                } catch (IllegalArgumentException e) {
                    left.add("'" + what + "': " + e.getMessage());
                }
            }
        }
        Result r = new Result(t, taken);
        r.notTaken.addAll(left);
        return r;
    }
}
