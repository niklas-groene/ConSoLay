package constraints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

/**
 * Central record of constraints the layout could not honour: constraints the pre-check
 * ({@link ConstraintFeasibility}) dropped, constraints the solver reported as unsatisfiable,
 * constraints the user solve measured as not holding, constraints not applied because they contradict constraints of
 * a higher rank ({@link ConstraintConflicts}), and warnings about contradicting user constraints. The GUI
 * (ConflictPanel, the constraint list, ConstraintOverlay) reads it; the layout writes it.
 */
public final class ConflictLog {

    /**
     * DROPPED: an automatic constraint the SBGN guard removed; UNSATISFIED: applied but not holding; DEACTIVATED: kept
     * in the store but not applied, it contradicts constraints that rank above it; WARNING: a note without a constraint.
     */
    public enum Kind { DROPPED, UNSATISFIED, DEACTIVATED, WARNING }

    public static final class Entry {
        public final Kind kind;
        public final Constraint constraint;   // null for warnings
        public final String phase;
        public final String reason;
        /** DEACTIVATED: the applied constraints it contradicts; WARNING: the user constraints it names, if known. */
        public final List<Constraint> partners;

        Entry(Kind kind, Constraint constraint, String phase, String reason) {
            this(kind, constraint, phase, reason, Collections.<Constraint>emptyList());
        }

        Entry(Kind kind, Constraint constraint, String phase, String reason, List<Constraint> partners) {
            this.kind = kind;
            this.constraint = constraint;
            this.phase = phase;
            this.reason = reason;
            this.partners = partners;
        }

        public String title() {
            switch (kind) {
                case DROPPED: return "Dropped";
                case UNSATISFIED: return "Not satisfied";
                case DEACTIVATED: return "Not applied";
                default: return "Warning";
            }
        }
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static final List<Runnable> listeners = new ArrayList<>();

    private ConflictLog() { }

    public static synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public static synchronized Entry entryFor(Constraint c) {
        for (Entry e : entries) if (e.constraint == c) return e;
        return null;
    }

    /**
     * Forget the reports of earlier runs (new layout run or reset). The deactivations stay: they belong to the current
     * constraints, not to a run; only those naming a constraint that is gone are dropped.
     */
    public static void clear() {
        synchronized (ConflictLog.class) { entries.removeIf(e -> e.kind != Kind.DEACTIVATED); }
        syncDeactivated();
        fire();
    }

    /**
     * The store was replaced (an SBGN run keeps only the user's constraints): deactivations naming a constraint that is
     * gone are dropped, and the entries follow the store.
     */
    public static void storeReplaced() {
        syncDeactivated();
        fire();
    }

    /**
     * Another graph became active: the entries belong to the previous graph's constraints and runs, so only those of
     * constraints in the now active store stay.
     */
    public static void graphChanged() {
        synchronized (ConflictLog.class) {
            entries.removeIf(e -> e.constraint == null || !inStore(e.constraint));
        }
        storeReplaced();
    }

    /**
     * Replaces the DEACTIVATED entries by one per constraint of {@code cs} that carries a {@link Deactivation} (after
     * {@link ConstraintConflicts#resolve}).
     */
    public static void setDeactivated(Collection<? extends Constraint> cs) {
        if (replaceDeactivated(cs)) fire();
    }

    /**
     * Clears the deactivations naming a deleted constraint and lists the rest, and drops the warnings naming a deleted
     * constraint; true if a deactivation was cleared.
     */
    private static boolean syncDeactivated() {
        List<Constraint> all = ConstraintStore.constraints == null ? Collections.<Constraint>emptyList()
                : ConstraintStore.constraints.getAllConstraints();
        boolean freed = ConstraintConflicts.forgetStale(all);
        Set<Constraint> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(all);
        synchronized (ConflictLog.class) {
            entries.removeIf(e -> e.kind == Kind.WARNING && !present.containsAll(e.partners));
        }
        replaceDeactivated(all);
        return freed;
    }

    /** True for a constraint of the store; a constraint a solve made up for itself (the pin of a drag) leaves no entry. */
    private static boolean inStore(Constraint c) {
        return ConstraintStore.constraints == null || ConstraintStore.constraints.getAllConstraints().contains(c);
    }

    private static boolean replaceDeactivated(Collection<? extends Constraint> cs) {
        List<Entry> now = new ArrayList<>();
        addDeactivations(cs, now);
        synchronized (ConflictLog.class) {
            return swapDeactivated(deactivatedEntries(), now);
        }
    }

    /** Adds a DEACTIVATED entry for each switched-on constraint of {@code cs} in the store that has a deactivation. */
    private static void addDeactivations(Collection<? extends Constraint> cs, List<Entry> to) {
        for (Constraint c : cs) {
            Deactivation d = c.getDeactivation();
            if (d != null && c.isEnabled() && inStore(c)) to.add(new Entry(Kind.DEACTIVATED, c, "Priority", d.reason(), d.partners));
        }
    }

    /** The current DEACTIVATED entries; the caller holds the lock. */
    private static List<Entry> deactivatedEntries() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : entries) if (e.kind == Kind.DEACTIVATED) l.add(e);
        return l;
    }

    /** Replaces the DEACTIVATED entries {@code before} by {@code now}; false if nothing changed. Caller holds the lock. */
    private static boolean swapDeactivated(List<Entry> before, List<Entry> now) {
        if (sameDeactivations(before, now)) return false;   // a drag step re-solves often; no refresh if nothing changed
        entries.removeIf(e -> e.kind == Kind.DEACTIVATED);
        entries.addAll(now);
        return true;
    }

    private static boolean sameDeactivations(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).constraint != b.get(i).constraint || !a.get(i).reason.equals(b.get(i).reason)) return false;
        }
        return true;
    }

    /**
     * Forget the solver reports of the previous run; the next run re-evaluates every constraint. The listeners (the
     * list, the Conflicts section, the overlay) are told only if an entry went, since a drag step solves many times a
     * second.
     */
    public static void clearUnsatisfied() {
        boolean removed;
        synchronized (ConflictLog.class) { removed = entries.removeIf(e -> e.kind == Kind.UNSATISFIED); }
        if (removed) fire();
    }

    /** {@link #clearUnsatisfied()} for the constraints of {@code cs} only (a solve of a part of the graph). */
    public static void clearUnsatisfied(Collection<? extends Constraint> cs) {
        Set<Constraint> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(cs);
        boolean removed;
        synchronized (ConflictLog.class) { removed = entries.removeIf(e -> e.kind == Kind.UNSATISFIED && set.contains(e.constraint)); }
        if (removed) fire();
    }

    /**
     * {@link #setDeactivated} for the constraints of {@code cs} only: their DEACTIVATED entries follow their
     * {@link Deactivation}, the entries of every other constraint stay (a solve of a part of the graph).
     */
    public static void updateDeactivated(Collection<? extends Constraint> cs) {
        Set<Constraint> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(cs);
        List<Entry> now = new ArrayList<>();
        synchronized (ConflictLog.class) {
            List<Entry> before = deactivatedEntries();
            for (Entry e : before) if (!set.contains(e.constraint)) now.add(e);
            addDeactivations(cs, now);
            if (!swapDeactivated(before, now)) return;
        }
        fire();
    }

    /**
     * Records dropped constraints and warnings. A warning of the last {@link ConstraintFeasibility} check keeps the
     * constraints it names ({@link ConstraintFeasibility#lastWarningConstraints}) and goes when one of them is deleted;
     * any other warning stays until {@link #clear()}.
     */
    public static void addDropped(String phase, List<ConstraintFeasibility.Dropped> dropped, List<String> warnings) {
        if (dropped.isEmpty() && warnings.isEmpty()) return;
        synchronized (ConflictLog.class) {
            for (ConstraintFeasibility.Dropped d : dropped) entries.add(new Entry(Kind.DROPPED, d.constraint, phase, d.reason));
            for (String w : warnings) {
                boolean known = false;
                for (Entry e : entries) if (e.kind == Kind.WARNING && e.reason.equals(w)) known = true;
                List<Constraint> named = ConstraintFeasibility.lastWarningConstraints.get(w);
                if (!known) entries.add(new Entry(Kind.WARNING, null, phase, w,
                        named == null ? Collections.<Constraint>emptyList() : new ArrayList<>(named)));
            }
        }
        fire();
    }

    public static void addUnsatisfied(Constraint c, String phase, String reason) {
        synchronized (ConflictLog.class) {
            for (Entry e : entries) if (e.kind == Kind.UNSATISFIED && e.constraint == c) return;
            entries.add(new Entry(Kind.UNSATISFIED, c, phase, reason));
        }
        fire();
    }

    /** Put a dropped constraint back as a user constraint (it then wins over automatic ones next time). */
    public static void restore(Entry e) {
        if (e.constraint == null || ConstraintStore.constraints == null) return;
        synchronized (ConflictLog.class) { entries.remove(e); }
        e.constraint.setAutomatic(false);
        e.constraint.setEnabled(true);
        if (!ConstraintStore.constraints.getAllConstraints().contains(e.constraint)) ConstraintStore.constraints.addConstraint(e.constraint);
        fire();
        try { ConstraintStore.onListsChanged.run(); } catch (Throwable ignored) { }
    }

    /**
     * Remove an unsatisfied or not applied constraint from the registered set (or just forget a warning). Returns true if
     * a constraint that was not applied because of it is free now: a solve should decide again.
     */
    public static boolean discard(Entry e) {
        synchronized (ConflictLog.class) { entries.remove(e); }
        if (e.constraint != null && ConstraintStore.constraints != null) ConstraintStore.constraints.removeConstraint(e.constraint);
        boolean freed = syncDeactivated();
        fire();
        try { ConstraintStore.onListsChanged.run(); } catch (Throwable ignored) { }
        return freed;
    }

    /**
     * Forget the entries about these constraints (they were deleted). Returns true if a constraint that was not applied
     * because of them is free now: a solve should decide again.
     */
    public static boolean forget(Collection<? extends Constraint> cs) {
        synchronized (ConflictLog.class) {
            entries.removeIf(e -> (e.constraint != null && cs.contains(e.constraint))
                    || (e.kind == Kind.WARNING && !Collections.disjoint(e.partners, cs)));
        }
        boolean freed = syncDeactivated();
        fire();
        return freed;
    }

    public static synchronized void addListener(Runnable r) {
        listeners.add(r);
    }

    private static void fire() {
        List<Runnable> copy;
        synchronized (ConflictLog.class) { copy = new ArrayList<>(listeners); }
        if (copy.isEmpty()) return;
        Runnable all = () -> { for (Runnable r : copy) { try { r.run(); } catch (Throwable ignored) { } } };
        if (SwingUtilities.isEventDispatchThread()) all.run();
        else SwingUtilities.invokeLater(all);
    }
}
