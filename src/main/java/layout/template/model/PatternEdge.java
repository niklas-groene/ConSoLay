package layout.template.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An edge of a template's pattern graph: stable local id, its two end nodes (local ids), whether its direction counts,
 * and the semantic conditions a graph edge must meet. Parallel edges and self-loops are allowed; the search maps each
 * pattern edge to its own graph edge.
 *
 * @author Niklas Gröne
 */
public final class PatternEdge {

    public String id;
    public String source;
    public String target;
    /** True: a graph edge must run from the source's node to the target's node; false: either way. */
    public boolean directed = true;
    /** SBGN role of the original edge, for drawing. */
    public String role = "";
    public final List<Condition> conditions = new ArrayList<>();

    public PatternEdge() { }

    public PatternEdge(String id, String source, String target, boolean directed) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.directed = directed;
    }

    public Condition condition(String attribute) {
        for (Condition c : conditions) if (attribute.equals(c.attribute)) return c;
        return null;
    }

    public void setCondition(Condition c) {
        conditions.removeIf(k -> k.attribute.equals(c.attribute));
        conditions.add(c);
    }

    public PatternEdge copy() {
        PatternEdge e = new PatternEdge(id, source, target, directed);
        e.role = role;
        for (Condition c : conditions) e.conditions.add(c.copy());
        return e;
    }
}
