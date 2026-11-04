package layout.mental_map;

import constraints.ClusterConstraintGraph;
import constraints.Constraint;
import constraints.ConstraintGroup;
import constraints.ConstraintKind;
import constraints.Constraints;
import constraints.spec.ConstraintSpecs;

import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The constraints a Mental Map run applied, as constraints of the graph's store: the left-to-right and the
 * top-to-bottom order of the reference (one group each), any other kind the method made (one group per kind) and every
 * neighbourhood group as a cluster. They are made by an algorithm ({@link Constraint#isAutomatic()}), so they rank below
 * the user's constraints of the same priority, and the solves of drags and "Apply constraints" hold them like the
 * constraints of the SBGN steps. Their groups carry the step {@link #STEP}, which marks them (also once saved with the
 * graph): the next run replaces them.
 *
 * @author Niklas Gröne
 */
public final class MentalMapConstraints {

    /** The step of their groups. */
    public static final String STEP = "Mental Map";

    private MentalMapConstraints() { }

    /** True for a constraint of a Mental Map run. */
    public static boolean isMentalMap(Constraint c) {
        ConstraintGroup g = c == null ? null : c.getGroup();
        return g != null && STEP.equals(g.step);
    }

    /** The constraints of a Mental Map run in {@code store}, in store order. */
    public static List<Constraint> in(Constraints store) {
        List<Constraint> l = new ArrayList<>();
        if (store != null) for (Constraint c : store.getAllConstraints()) if (isMentalMap(c)) l.add(c);
        return l;
    }

    /**
     * The store's constraints of what {@code d} applied to {@code live}: copies, grouped as in the class comment, with the
     * constraints whose nodes are all on {@code live}.
     */
    public static List<Constraint> fromRun(DerivedConstraints d, Graph live) {
        List<Constraint> made = new ArrayList<>();
        if (d == null || live == null) return made;
        ConstraintGroup xs = new ConstraintGroup("order-x", "Order left → right", STEP);
        ConstraintGroup ys = new ConstraintGroup("order-y", "Order top → bottom", STEP);
        Map<ConstraintKind, ConstraintGroup> other = new EnumMap<>(ConstraintKind.class);
        for (Constraint c : d.getConstraints()) {
            if (!onGraph(c.getNodes(), live) || !ConstraintSpecs.supports(c)) continue;
            Constraint k = ConstraintSpecs.copy(c);
            ConstraintGroup g = c.getKind() == ConstraintKind.SEPARATION_X ? xs
                    : c.getKind() == ConstraintKind.SEPARATION_Y ? ys
                    : other.computeIfAbsent(c.getKind(), kind -> new ConstraintGroup("kind", ConstraintSpecs.title(kind), STEP));
            add(made, k, g);
        }
        int i = 0;
        for (List<Node> members : d.getClusters()) {
            i++;
            if (members.size() < 2 || !onGraph(members, live)) continue;
            ClusterConstraintGraph c = new ClusterConstraintGraph(new ArrayList<>(members), d.getClusterPadding(), d.getClusterMargin());
            add(made, c, new ConstraintGroup("group", "Group " + i, STEP));
        }
        return made;
    }

    private static void add(List<Constraint> made, Constraint c, ConstraintGroup g) {
        c.setAutomatic(true);
        g.add(c);
        made.add(c);
    }

    /**
     * Copies of {@code cs} on the nodes {@code nodes} gives (null: the constraint is left out), with their priority,
     * switch and values, in groups of their own with the same names: nothing is shared with {@code cs}.
     */
    public static List<Constraint> copies(Collection<? extends Constraint> cs, Function<Node, Node> nodes) {
        List<Constraint> l = new ArrayList<>();
        Map<ConstraintGroup, ConstraintGroup> groups = new IdentityHashMap<>();
        for (Constraint c : cs) {
            boolean all = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) all &= nodes.apply(n) != null;
            if (!all || !ConstraintSpecs.supports(c)) continue;
            Constraint k;
            try {
                k = ConstraintSpecs.copy(c, nodes, Function.identity());
            } catch (RuntimeException e) {
                continue;   // e.g. a node twice after the mapping
            }
            ConstraintGroup g = c.getGroup();
            if (g != null) groups.computeIfAbsent(g, x -> new ConstraintGroup(x.kind, x.label, x.step)).add(k);
            l.add(k);
        }
        return l;
    }

    /** Takes the constraints of a Mental Map run out of {@code store}; returns them. */
    public static List<Constraint> removeFrom(Constraints store) {
        List<Constraint> gone = in(store);
        for (Constraint c : gone) store.removeConstraint(c);
        return gone;
    }

    private static boolean onGraph(Collection<Node> ns, Graph g) {
        if (ns == null || ns.isEmpty()) return false;
        for (Node n : ns) if (!g.containsNode(n)) return false;
        return true;
    }
}
