package layout.template.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A semantic search condition on a node or an edge of a pattern: an attribute and a simple rule on its value. The
 * attribute is a path such as {@code sbgn.role} (VANTED folder and attribute) or {@code label} (the element's label).
 * {@link Op#ANY} ("any value") says explicitly that the attribute does not matter.
 *
 * @author Niklas Gröne
 */
public final class Condition {

    /** The rule on the value. */
    public enum Op {
        /** Any value, also none: the attribute is not looked at. */
        ANY("any value"),
        /** The attribute has a non-empty value. */
        EXISTS("has a value"),
        /** The value equals the (first) given value. */
        EQUALS("is"),
        /** The value is one of the given values. */
        IN_LIST("is one of");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Attribute path of the SBGN role of nodes and edges. */
    public static final String SBGN_ROLE = "sbgn.role";
    /** Attribute path of the label. */
    public static final String LABEL = "label";

    public String attribute;
    public Op op;
    public final List<String> values = new ArrayList<>();

    public Condition(String attribute, Op op, List<String> values) {
        this.attribute = attribute;
        this.op = op == null ? Op.ANY : op;
        if (values != null) this.values.addAll(values);
    }

    public static Condition equalsTo(String attribute, String value) {
        return new Condition(attribute, Op.EQUALS, Collections.singletonList(value));
    }

    public static Condition any(String attribute) {
        return new Condition(attribute, Op.ANY, null);
    }

    /** True if {@code value} (null or empty: the attribute has none) satisfies the condition. */
    public boolean test(String value) {
        String v = value == null ? "" : value;
        switch (op) {
            case ANY: return true;
            case EXISTS: return !v.isEmpty();
            case EQUALS: return !values.isEmpty() && v.equals(values.get(0));
            case IN_LIST: return values.contains(v);
            default: return false;
        }
    }

    /** E.g. "sbgn.role is macromolecule" or "label: any value". */
    public String describe() {
        String what = SBGN_ROLE.equals(attribute) ? "SBGN role" : LABEL.equals(attribute) ? "label" : attribute;
        switch (op) {
            case ANY: return what + ": any value";
            case EXISTS: return what + " has a value";
            case EQUALS: return what + " is " + (values.isEmpty() ? "''" : values.get(0));
            default: return what + " is one of " + String.join(", ", values);
        }
    }

    public Condition copy() {
        return new Condition(attribute, op, values);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Condition)) return false;
        Condition c = (Condition) o;
        return Objects.equals(attribute, c.attribute) && op == c.op && values.equals(c.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attribute, op, values);
    }

    @Override
    public String toString() {
        return describe();
    }
}
