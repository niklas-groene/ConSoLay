package constraints;

import org.AttributeHelper;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Why a constraint is not applied: together with the constraints it names (the ones that rank above it) it would need
 * nodes at two places at once along one axis. The constraint keeps its settings and stays in the store; the next solve
 * decides again. The texts are made when the decision is made, so later edits cannot make them contradict themselves.
 *
 * @author Niklas Gröne
 */
public final class Deactivation {

    /** How many partners the texts name; the others are counted. */
    private static final int NAMED = 3;

    /** The applied constraints the contradiction runs through (empty: the constraint contradicts itself). */
    public final List<Constraint> partners;
    /** The axis of the contradiction. */
    public final Axis axis;
    /** How far apart the contradicting requirements are, in px (NaN if unknown). */
    public final double shortfall;
    private final String reason;
    private final String shortReason;

    Deactivation(Constraint self, List<Constraint> partners, Axis axis, double shortfall) {
        this(self, partners, axis, shortfall, null);
    }

    /** {@code lead}: what the constraint would do, in place of "Contradicts …" (the partners follow as "with …"). */
    Deactivation(Constraint self, List<Constraint> partners, Axis axis, double shortfall, String lead) {
        this.partners = Collections.unmodifiableList(new ArrayList<>(partners));
        this.axis = axis;
        this.shortfall = shortfall;
        this.reason = lead == null ? makeReason(self) : lead + (partners.isEmpty() ? "" : " (with " + names(self) + ")") + ".";
        this.shortReason = lead == null ? makeShortReason() : "Would fix nodes on top of each other";
    }

    /** True if nothing but its own settings keep the constraint from holding: no priority can change that. */
    public boolean contradictsItself() {
        return partners.isEmpty();
    }

    /**
     * The full explanation, e.g. "Contradicts 'Horizontal gap = 350 px' on glucose, ATP (priority High) in x: together
     * they are 230 px short."
     */
    public String reason() {
        return reason;
    }

    /** One line for the constraint list, e.g. "Contradicts Horizontal gap = 350 px". */
    public String shortReason() {
        return shortReason;
    }

    private String makeReason(Constraint self) {
        String where = axis == null ? "" : " in " + (axis == Axis.X ? "x" : "y");
        String by = Double.isNaN(shortfall) ? "" : ": together they are " + px(shortfall) + " short";
        if (partners.isEmpty()) {
            return "Cannot hold on its own" + where + (Double.isNaN(shortfall) ? "" : " (" + px(shortfall) + " short)")
                    + "; only other settings can change that.";
        }
        return "Contradicts " + names(self) + where + by + '.';
    }

    /** The partners as "'A' on n1, n2 (why it wins), 'B' … and 2 more". */
    private String names(Constraint self) {
        StringBuilder sb = new StringBuilder();
        int named = Math.min(NAMED, partners.size());
        for (int i = 0; i < named; i++) {
            if (i > 0) sb.append(i == named - 1 && named == partners.size() ? " and " : ", ");
            Constraint p = partners.get(i);
            sb.append('\'').append(ConstraintStore.describer.apply(p)).append("' on ").append(nodes(p))
                    .append(" (").append(whyWins(p, self)).append(')');
        }
        if (partners.size() > named) sb.append(" and ").append(partners.size() - named).append(" more");
        return sb.toString();
    }

    private String makeShortReason() {
        if (partners.isEmpty()) return "Cannot hold on its own";
        return partners.size() == 1 ? "Contradicts " + ConstraintStore.describer.apply(partners.get(0))
                : "Contradicts " + partners.size() + " constraints";
    }

    /** Why {@code winner} is applied and {@code loser} not: its priority, or at equal priority the tie rule. */
    static String whyWins(Constraint winner, Constraint loser) {
        if (winner.getPriority() != loser.getPriority()) return "priority " + winner.getPriority().label();
        if (!winner.isAutomatic() && loser.isAutomatic()) return "same priority, made by you";
        return "same priority, added earlier";
    }

    /** The first node labels of a constraint, e.g. "glucose, ATP" or "glucose, ATP, …". */
    private static String nodes(Constraint c) {
        StringBuilder sb = new StringBuilder();
        List<Node> ns = c.getNodes();
        for (int i = 0; i < ns.size() && i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(label(ns.get(i)));
        }
        if (ns.size() > 3) sb.append(", …");
        return sb.toString();
    }

    /** The node's label as plain text, or "unnamed node" (never an internal id). */
    public static String label(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        l = l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return l.isEmpty() ? "unnamed node" : l;
    }

    private static String px(double v) {
        return String.format(Locale.ROOT, Math.abs(v - Math.rint(v)) < 0.05 ? "%.0f px" : "%.1f px", v);
    }
}
