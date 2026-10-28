package layout.template.apply;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.Constraints;
import layout.template.model.TemplateRef;
import layout.template.model.Templates;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A template applied to one place of a graph: its own id, the template (id and the fixed revision it was made from),
 * the graph, the assignment of the template's nodes and edges, and the constraints it made (each knows it through its
 * {@link constraints.ConstraintOrigin}). An instance is switched on and off as a whole (and, for composites, per
 * component); the constraints keep their own switches, so switching the instance on again restores exactly the
 * individual decisions. Later changes of the template file do not change an instance.
 *
 * @author Niklas Gröne
 */
public final class TemplateInstance {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    public final String id;
    public final int number;
    public final TemplateRef template;
    /** Rule of a rule set that made it, or null. */
    public final String ruleName;
    public final Graph graph;
    public final Map<String, Node> nodes;
    public final Map<String, Edge> edges;
    public final Map<String, Edge> links;
    /** Identifies the application (template revision + what it makes on which nodes): re-applying is recognised. */
    public final String key;
    public final InstancePlan plan;
    private final List<Constraint> constraints = new ArrayList<>();
    private boolean on = true;
    private final Map<String, Boolean> partOn = new HashMap<>();

    TemplateInstance(TemplateRef template, String ruleName, Graph graph, Map<String, Node> nodes, Map<String, Edge> edges,
                     Map<String, Edge> links, String key, InstancePlan plan) {
        this.number = NUMBERS.incrementAndGet();
        this.id = "inst-" + number;
        this.template = template;
        this.ruleName = ruleName;
        this.graph = graph;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
        this.links = Collections.unmodifiableMap(new LinkedHashMap<>(links));
        this.key = key;
        this.plan = plan;
    }

    void addConstraint(Constraint c) {
        constraints.add(c);
    }

    /** Every constraint the instance made (also ones deleted since; see {@link #present}). */
    public List<Constraint> constraints() {
        return Collections.unmodifiableList(constraints);
    }

    /** The instance's constraints that are still in {@code store}. */
    public List<Constraint> present(Constraints store) {
        List<Constraint> l = new ArrayList<>();
        if (store == null) return l;
        List<Constraint> all = store.getAllConstraints();
        Set<Constraint> in = Collections.newSetFromMap(new IdentityHashMap<>());
        in.addAll(all);
        for (Constraint c : constraints) if (in.contains(c)) l.add(c);
        return l;
    }

    /** "Chain #7" (or "Rule 'Signalling' #7"). */
    public String label() {
        return (ruleName != null ? ruleName : template.name) + " #" + number;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    /** True if the instance and every component on the way to {@code part} are switched on. */
    public boolean isPartOn(String part) {
        if (!on) return false;
        if (part == null || part.isEmpty()) return true;
        String[] segs = part.split(Templates.SEP);
        StringBuilder k = new StringBuilder();
        for (String s : segs) {
            if (k.length() > 0) k.append(Templates.SEP);
            k.append(s);
            if (Boolean.FALSE.equals(partOn.get(k.toString()))) return false;
        }
        return true;
    }

    /** The switch of one component part itself (true unless switched off). */
    public boolean isPartSwitchedOn(String part) {
        return !Boolean.FALSE.equals(partOn.get(part));
    }

    public void setPartOn(String part, boolean on) {
        if (on) partOn.remove(part);
        else partOn.put(part, false);
    }

    /** The switches of the parts that are off (for undo). */
    Map<String, Boolean> partSwitches() {
        return new HashMap<>(partOn);
    }

    void restorePartSwitches(Map<String, Boolean> m) {
        partOn.clear();
        partOn.putAll(m);
    }

    /** Counts of the instance's constraints by state, for the status line. */
    public static final class Status {
        public int present, applied, switchedOffAlone, switchedOffByPart, notApplied, notSatisfied, deleted;
        public boolean on;

        /** E.g. "On · 6 constraints" or "Partly off: 2 of 6 switched off" or "1 not applied (conflict)". */
        public String text() {
            StringBuilder sb = new StringBuilder(on ? "On" : "Off");
            if (present == 0) return sb.append(" · no constraints left").toString();
            sb.append(" · ").append(present).append(present == 1 ? " constraint" : " constraints");
            if (on && (switchedOffAlone > 0 || switchedOffByPart > 0))
                sb.append(" · partly off (").append(switchedOffAlone + switchedOffByPart).append(')');
            if (notApplied > 0) sb.append(" · ").append(notApplied).append(" not applied (conflict)");
            if (notSatisfied > 0) sb.append(" · ").append(notSatisfied).append(" not satisfied");
            if (deleted > 0) sb.append(" · ").append(deleted).append(" deleted");
            return sb.toString();
        }

        public boolean partly() {
            return on && (switchedOffAlone > 0 || switchedOffByPart > 0 || notApplied > 0);
        }
    }

    public Status status(Constraints store) {
        Status s = new Status();
        s.on = on;
        List<Constraint> here = present(store);
        s.present = here.size();
        s.deleted = constraints.size() - here.size();
        for (Constraint c : here) {
            if (!c.isSwitchedOnItself()) s.switchedOffAlone++;
            else if (!c.isEnabled() && on) s.switchedOffByPart++;
            else if (c.getDeactivation() != null) s.notApplied++;
            else if (c.isEnabled()) {
                s.applied++;
                ConflictLog.Entry e = ConflictLog.entryFor(c);
                if (e != null && e.kind == ConflictLog.Kind.UNSATISFIED) s.notSatisfied++;
            }
        }
        return s;
    }

    /** The constraints of {@code part} and the parts below it. */
    public List<Constraint> constraintsOf(String part) {
        List<Constraint> l = new ArrayList<>();
        for (Constraint c : constraints) {
            String p = c.getOrigin() == null ? "" : partOf(c);
            if (part.isEmpty() || p.equals(part) || p.startsWith(part + Templates.SEP)) l.add(c);
        }
        return l;
    }

    private final Map<Constraint, String> partByConstraint = new IdentityHashMap<>();

    void setPart(Constraint c, String part) {
        partByConstraint.put(c, part);
    }

    /** The component path the constraint belongs to ("" for the template itself). */
    public String partOf(Constraint c) {
        String p = partByConstraint.get(c);
        return p == null ? "" : p;
    }

    @Override
    public String toString() {
        return label();
    }
}
