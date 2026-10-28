package layout.template.apply;

import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintOrigin;
import constraints.ConstraintStore;
import constraints.Deactivation;
import constraints.Priority;
import constraints.spec.ConstraintSpecs;
import layout.template.search.MatchCheck;

import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells, without changing anything, what applying matches would do to the constraints of a graph: which of the new
 * constraints the priority resolution would not apply (and what they contradict) and which constraints already there
 * would no longer be applied; a shared node alone is never reported. It works on copies of the graph's constraints and
 * of the new ones ({@link ConstraintSpecs#copy}), so the store, the conflict log and the drawing stay as they are. It
 * also previews "Prefer" for a whole instance: which priorities it would change and what would stop being applied.
 *
 * @author Niklas Gröne
 */
public final class ConflictPreview {

    private ConflictPreview() { }

    /**
     * The check of applying all {@code requests} together on {@code g}.
     *
     * @param existing the constraints of the store (only those on {@code g} are looked at)
     * @param scope    the scope of the next user solve of {@code g}
     * @param version  the graph's structure counter, kept in the result
     */
    public static MatchCheck check(Graph g, Collection<Constraint> existing, List<TemplateApplier.Request> requests,
                                   ConstraintConflicts.Scope scope, long version) {
        Map<Constraint, Constraint> original = new IdentityHashMap<>();
        List<Constraint> copies = copiesOn(g, existing, original);
        // baseline: what is applied without the new constraints
        ConstraintConflicts.resolve(copies, scope);
        Set<Constraint> activeBefore = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Constraint c : copies) if (c.isActive()) activeBefore.add(c);

        List<Constraint> fresh = new ArrayList<>();
        Map<Constraint, List<String>> pathOf = new IdentityHashMap<>();
        for (TemplateApplier.Request q : requests) {
            try {
                Map<String, double[]> off = TemplateApplier.offsets(q.task.plan, q.nodes);
                for (TemplateApplier.Built b : TemplateApplier.instantiate(q.task.plan, q.nodes, q.edges, off)) {
                    b.constraint.setOrigin(new ConstraintOrigin("preview", q.title, q.task.template.id, q.task.template.revision,
                            b.item.path, b.item.spec.id, null));
                    fresh.add(b.constraint);
                    pathOf.put(b.constraint, b.item.path);
                }
            } catch (IllegalArgumentException e) {
                return new MatchCheck(MatchCheck.Status.BLOCKED, "Cannot be applied: " + e.getMessage(),
                        Collections.singletonList(e.getMessage()), version);
            }
        }
        List<Constraint> all = new ArrayList<>(copies);
        all.addAll(fresh);
        ConstraintConflicts.resolve(all, scope);

        List<String> details = new ArrayList<>();
        int notApplied = 0, pushedOut = 0;
        for (Constraint c : fresh) {
            Deactivation d = c.getDeactivation();
            if (d == null || !c.isEnabled()) continue;
            notApplied++;
            details.add(String.join(" → ", pathOf.get(c)) + " would not be applied: " + d.reason());
        }
        for (Constraint c : copies) {
            if (!activeBefore.contains(c) || c.isActive()) continue;
            pushedOut++;
            Deactivation d = c.getDeactivation();
            details.add("'" + describe(original.get(c)) + "' (already on the graph) would no longer be applied"
                    + (d == null ? "." : ": " + d.reason()));
        }
        if (notApplied == 0 && pushedOut == 0) {
            int n = fresh.size();
            return new MatchCheck(MatchCheck.Status.COMPATIBLE, "Compatible: all " + n + (n == 1 ? " constraint agrees" : " constraints agree")
                    + " with the constraints on the graph.", details, version);
        }
        StringBuilder sum = new StringBuilder("Conflict: ");
        if (notApplied > 0) sum.append(notApplied).append(" of ").append(fresh.size()).append(fresh.size() == 1 ? " constraint" : " constraints")
                .append(" would not be applied");
        if (pushedOut > 0) sum.append(notApplied > 0 ? "; " : "").append(pushedOut).append(pushedOut == 1 ? " existing constraint" : " existing constraints")
                .append(" would stop being applied");
        return new MatchCheck(MatchCheck.Status.CONFLICT, sum.append('.').toString(), details, version);
    }

    /**
     * Copies of the constraints of {@code existing} that lie on {@code g} (copy → original in {@code original}), made
     * oldest first, so that their new ids keep the age that breaks ranking ties.
     */
    static List<Constraint> copiesOn(Graph g, Collection<Constraint> existing, Map<Constraint, Constraint> original) {
        List<Constraint> copies = new ArrayList<>();
        List<Constraint> byAge = new ArrayList<>(existing);
        byAge.sort(Comparator.comparingLong(Constraint::getId));
        for (Constraint c : byAge) {
            if (!on(g, c) || !ConstraintSpecs.supports(c)) continue;
            try {
                Constraint k = ConstraintSpecs.copy(c);
                copies.add(k);
                original.put(k, c);
            } catch (IllegalArgumentException ignored) {
                // a constraint that cannot be copied takes no part in the preview
            }
        }
        return copies;
    }

    private static boolean on(Graph g, Constraint c) {
        if (c.getNodes().isEmpty()) return false;
        for (Node n : c.getNodes()) if (n.getGraph() != g || !g.containsNode(n)) return false;
        return true;
    }

    // ================================================================== prefer a whole instance

    /** What "Prefer this instance" would do. */
    public static final class PreferPreview {
        /** Constraints of the instance that would be applied afterwards (were not before). */
        public final List<String> gained = new ArrayList<>();
        /** Constraints whose priority would change: text "name: Normal → High". */
        public final List<String> priorityChanges = new ArrayList<>();
        /** Other constraints that would stop being applied. */
        public final List<String> lost = new ArrayList<>();
        /** Constraints of the instance that cannot be applied whatever the priorities (they contradict themselves). */
        public final List<String> impossible = new ArrayList<>();

        public boolean changesSomething() {
            return !priorityChanges.isEmpty();
        }
    }

    /** Simulates "Prefer" for every not-applied constraint of {@code inst} on copies. */
    public static PreferPreview previewPrefer(TemplateInstance inst, Collection<Constraint> existing, ConstraintConflicts.Scope scope) {
        PreferPreview p = new PreferPreview();
        Map<Constraint, Constraint> original = new IdentityHashMap<>();
        List<Constraint> copies = copiesOn(inst.graph, existing, original);
        Map<Constraint, Constraint> copyOf = new IdentityHashMap<>();
        for (Map.Entry<Constraint, Constraint> e : original.entrySet()) copyOf.put(e.getValue(), e.getKey());
        ConstraintConflicts.resolve(copies, scope);
        Map<Constraint, Priority> prio = new IdentityHashMap<>();
        Set<Constraint> activeBefore = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Constraint c : copies) {
            prio.put(c, c.getPriority());
            if (c.isActive()) activeBefore.add(c);
        }
        Set<Constraint> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Constraint c : inst.constraints()) if (copyOf.containsKey(c)) mine.add(copyOf.get(c));
        for (Constraint c : new ArrayList<>(mine)) {
            if (c.getDeactivation() != null && c.getDeactivation().contradictsItself()) {
                p.impossible.add(describe(original.get(c)));
                continue;
            }
            if (c.isEnabled() && c.getDeactivation() != null) ConstraintConflicts.prefer(c, copies, scope);
        }
        ConstraintConflicts.resolve(copies, scope);
        for (Constraint c : copies) {
            String name = describe(original.get(c));
            if (c.getPriority() != prio.get(c)) p.priorityChanges.add(name + ": " + prio.get(c).label() + " → " + c.getPriority().label());
            if (mine.contains(c) && c.isActive() && !activeBefore.contains(c)) p.gained.add(name);
            if (!mine.contains(c) && activeBefore.contains(c) && !c.isActive()) p.lost.add(name);
        }
        return p;
    }

    /** "Prefer this instance" for real: returns the constraints whose priority changed. */
    public static List<Constraint> preferInstance(TemplateInstance inst, Collection<Constraint> existing, ConstraintConflicts.Scope scope) {
        List<Constraint> all = new ArrayList<>(existing);
        Map<Constraint, Priority> before = new IdentityHashMap<>();
        for (Constraint c : all) before.put(c, c.getPriority());
        ConstraintConflicts.resolve(all, scope);
        for (Constraint c : inst.constraints()) {
            if (!all.contains(c) || !c.isEnabled()) continue;
            if (c.getDeactivation() != null && !c.getDeactivation().contradictsItself()) ConstraintConflicts.prefer(c, all, scope);
        }
        List<Constraint> changed = new ArrayList<>();
        for (Constraint c : all) if (c.getPriority() != before.get(c)) changed.add(c);
        return changed;
    }

    /** The name of a constraint in the texts (the GUI's describer, which includes a template path). */
    private static String describe(Constraint c) {
        return c == null ? "?" : ConstraintStore.describer.apply(c);
    }
}
