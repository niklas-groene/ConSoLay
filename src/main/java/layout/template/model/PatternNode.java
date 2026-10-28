package layout.template.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A node of a template's pattern graph: a stable local id (never a VANTED node id), its centre and size in the
 * template's local coordinates, how it is drawn, and the semantic conditions a graph node must meet to take its place.
 *
 * @author Niklas Gröne
 */
public final class PatternNode {

    /** Local id, e.g. "n3"; stays the same while the template is edited and saved. */
    public String id;
    /** Label shown in the editor and in match descriptions (not a search condition unless one says so). */
    public String label = "";
    /** SBGN role of the node the pattern was made from, for drawing (the search uses {@link #conditions}). */
    public String role = "";
    public double x, y;
    public double width = 40, height = 30;
    /** VANTED shape class of the original node, or "" (drawn as a rounded box). */
    public String shape = "";
    /** Fill colour "#rrggbb", or null. */
    public String fill;
    /** Conditions a graph node must meet (all of them, in "structure and semantics" searches). */
    public final List<Condition> conditions = new ArrayList<>();

    public PatternNode() { }

    public PatternNode(String id, double x, double y) {
        this.id = id;
        this.x = x;
        this.y = y;
    }

    /** The condition on {@code attribute}, or null. */
    public Condition condition(String attribute) {
        for (Condition c : conditions) if (attribute.equals(c.attribute)) return c;
        return null;
    }

    /** Replaces the condition on the same attribute (or adds it). */
    public void setCondition(Condition c) {
        conditions.removeIf(k -> k.attribute.equals(c.attribute));
        conditions.add(c);
    }

    /** Label, or role, or id: what the GUI calls the node. */
    public String displayName() {
        if (label != null && !label.trim().isEmpty()) return label.trim();
        if (role != null && !role.isEmpty()) return role + " (" + id + ")";
        return id;
    }

    public PatternNode copy() {
        PatternNode n = new PatternNode(id, x, y);
        n.label = label;
        n.role = role;
        n.width = width;
        n.height = height;
        n.shape = shape;
        n.fill = fill;
        for (Condition c : conditions) n.conditions.add(c.copy());
        return n;
    }
}
