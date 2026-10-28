package layout.template.model;

import constraints.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A layout wish between components of a composite template: how the matched parts should lie, applied as constraints
 * after a match was found (the current drawing never decides whether something matches). The geometric meaning is
 * fixed per type:
 * <ul>
 *   <li>{@link Type#LEFT_OF}, {@link Type#ABOVE}: the bounding box of all nodes of the first component lies left of
 *   (above) the bounding box of the second one, at least {@link #gap} px apart; made as a dividing line between the two
 *   node sets.</li>
 *   <li>{@link Type#ROW}, {@link Type#COLUMN}, {@link Type#GRID}: the components' bounding boxes in this order from left
 *   to right, top to bottom, or row by row in {@link #columns} columns, each at least {@link #gap} px apart.</li>
 *   <li>{@link Type#ALIGN_PORTS_H}, {@link Type#ALIGN_PORTS_V}: the centres of the given port nodes on one horizontal
 *   (vertical) line.</li>
 * </ul>
 *
 * @author Niklas Gröne
 */
public final class LayoutRelation {

    public enum Type {
        LEFT_OF("left of"), ABOVE("above"), ROW("in a row"), COLUMN("in a column"), GRID("in a grid"),
        ALIGN_PORTS_H("ports on one horizontal line"), ALIGN_PORTS_V("ports on one vertical line");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** True for the types between whole components (bounding boxes), false for those on port nodes. */
        public boolean onComponents() {
            return this != ALIGN_PORTS_H && this != ALIGN_PORTS_V;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A port of a component: component id and port id. */
    public static final class PortRef {
        public final String component;
        public final String port;

        public PortRef(String component, String port) {
            this.component = component;
            this.port = port;
        }
    }

    /** One dividing line of a relation between components (see {@link Templates#divides}). */
    public static final class Divide {
        /** Names the line in its relation: "0", "1", … in order; in a grid "c0", … (columns) and "r0", … (rows). */
        public final String key;
        /** True: a vertical line (before = left of it), false: a horizontal one (before = above it). */
        public final boolean vertical;
        /** Qualified node ids on either side. */
        public final List<String> before, after;

        Divide(String key, boolean vertical, List<String> before, List<String> after) {
            this.key = key;
            this.vertical = vertical;
            this.before = before;
            this.after = after;
        }
    }

    public String id;
    public Type type = Type.LEFT_OF;
    /** The components in order (types on components). */
    public final List<String> components = new ArrayList<>();
    /** The port nodes (alignment types). */
    public final List<PortRef> ports = new ArrayList<>();
    /** Least distance between the bounding boxes, px. */
    public double gap = 40;
    /** GRID: components per row. */
    public int columns = 2;
    public Priority priority = Priority.NORMAL;
    public boolean enabled = true;

    public LayoutRelation() { }

    public LayoutRelation(String id, Type type) {
        this.id = id;
        this.type = type;
    }

    public LayoutRelation copy() {
        LayoutRelation r = new LayoutRelation(id, type);
        r.components.addAll(components);
        for (PortRef p : ports) r.ports.add(new PortRef(p.component, p.port));
        r.gap = gap;
        r.columns = columns;
        r.priority = priority;
        r.enabled = enabled;
        return r;
    }

    /** E.g. "Source left of Target, ≥ 40 px apart". */
    public String describe(java.util.function.Function<String, String> componentName) {
        List<String> names = new ArrayList<>();
        for (String c : components) names.add(componentName.apply(c));
        String gapText = String.format(Locale.ROOT, "%.0f px", gap);
        switch (type) {
            case LEFT_OF: return names.size() == 2 ? names.get(0) + " left of " + names.get(1) + ", ≥ " + gapText + " apart" : "Left of";
            case ABOVE: return names.size() == 2 ? names.get(0) + " above " + names.get(1) + ", ≥ " + gapText + " apart" : "Above";
            case ROW: return "Row: " + String.join(", ", names) + " (≥ " + gapText + ")";
            case COLUMN: return "Column: " + String.join(", ", names) + " (≥ " + gapText + ")";
            case GRID: return "Grid of " + columns + " columns: " + String.join(", ", names);
            case ALIGN_PORTS_H: return "Ports on one horizontal line (" + ports.size() + ")";
            default: return "Ports on one vertical line (" + ports.size() + ")";
        }
    }
}
