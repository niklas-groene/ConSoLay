package gui;

import constraints.Constraint;
import constraints.ConstraintStore;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Facade for the constraint markings: keeps the set of constraints to show and hands it to the
 * {@link ConstraintOverlay}, which paints them on top of the active VANTED view without changing the graph.
 *
 * @author Niklas Gröne
 */
public class DisplayConstraints {

    /** Constraints currently selected for visualization. Insertion order preserved. */
    private static final Set<Constraint> active = new LinkedHashSet<>();
    /** Counts every change of the markings, so a drawer can tell whether they are still the ones it drew. */
    private static long generation;

    /** Changes with every change of the markings (whoever makes it). */
    public static long generation() {
        return generation;
    }

    /** Show every currently registered constraint. */
    public static void displayAllConstraints() {
        if (ConstraintStore.constraints == null) return;
        active.clear();
        active.addAll(ConstraintStore.constraints.getAllConstraints());
        push();
    }

    /** Add a single constraint to the visible set (and repaint). */
    public static void drawConstraint(Constraint c) {
        addConstraint(c);
        push();
    }

    /** Add several constraints to the visible set and repaint once. */
    public static void drawConstraints(Collection<? extends Constraint> cs) {
        for (Constraint c : cs) addConstraint(c);
        push();
    }

    /** Take constraints out of the visible set (and repaint if one was shown), e.g. those a step removed from the store. */
    public static void hideConstraints(Collection<? extends Constraint> cs) {
        if (active.removeAll(cs)) push();
    }

    /** Hide all visible markings and clear the visible set. */
    public static void clearMarkings() {
        generation++;
        active.clear();
        ConstraintOverlay overlay = ConstraintOverlay.getOrInstall();
        if (overlay != null) overlay.clearConstraints();
    }

    /** Repaint the overlay so node positions stay in sync after a layout step. */
    public static void revalidateIndication() {
        ConstraintOverlay.repaintAll();
    }

    private static void addConstraint(Constraint c) {
        if (c != null) active.add(c);
    }

    private static void push() {
        generation++;
        ConstraintOverlay overlay = ConstraintOverlay.getOrInstall();
        if (overlay != null) overlay.setConstraints(active);
    }
}
