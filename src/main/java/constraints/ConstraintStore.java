package constraints;

import org.graffiti.graph.Graph;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * The constraint stores, one per graph. {@link #constraints} is the store of the active graph, on which every solve,
 * list and overlay works; the stores of the other open graphs are kept until their graph is active again. The hooks
 * let the model and the layout request a GUI refresh without depending on GUI classes; AddonTab installs them.
 */
public final class ConstraintStore {

    private ConstraintStore() {}

    /** The store of the active graph (or the only store while no graph is active). Null until the add-on assigns it. */
    public static Constraints constraints;

    private static final Map<Graph, Constraints> BY_GRAPH = new WeakHashMap<>();
    private static Graph active;
    /** The graph a layout run is working on: an activation of another graph waits until the run ends ({@link #pin}). */
    private static Graph pinned;
    private static int pinDepth;
    private static boolean hasPending;
    private static Graph pending;
    /** The graph that was active when a run on a side graph began: it is active again when the run ends. */
    private static Graph sideRunFrom;
    /** Graphs shown beside their original (the PD skeleton window): activating one keeps the active graph's store. */
    private static final Set<Graph> SIDE = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Makes {@code g} the active graph: {@link #constraints} becomes its store (a new one on first use; the store that
     * existed before any graph was active is adopted by the first graph). The previous active graph's store is kept for
     * its graph. {@code null}: no active graph, an empty store. Headless code never activates a graph and assigns
     * {@link #constraints} directly.
     */
    public static synchronized void activate(Graph g) {
        if (g != null && SIDE.contains(g) && active != null) return;
        if (pinned != null && g != pinned) {
            // a layout run is in progress (PD step 1 opens the skeleton in a window of its own, which VANTED makes
            // active): the run keeps its graph's store, the switch is made when the run ends
            pending = g;
            hasPending = true;
            return;
        }
        if (g != null && g == active) return;
        if (g == null) {
            if (active != null && constraints != null) BY_GRAPH.put(active, constraints);
            active = null;
            constraints = new Constraints();
            return;
        }
        switchTo(g);
    }

    /** {@link #constraints} becomes the store of {@code g}; the previous active graph's store is kept for it. */
    private static void switchTo(Graph g) {
        if (active != null && constraints != null) BY_GRAPH.put(active, constraints);
        Constraints s = BY_GRAPH.get(g);
        if (s == null) {
            s = active == null && constraints != null ? constraints : new Constraints();
            BY_GRAPH.put(g, s);
        }
        constraints = s;
        active = g;
    }

    /**
     * The store of {@code g}: {@link #constraints} for the active graph, and for every graph while no graph is active
     * (headless runs); else the graph's own store, created on first use.
     */
    public static synchronized Constraints of(Graph g) {
        if (g == null || g == active || active == null) {
            if (constraints == null) constraints = new Constraints();   // the menu run before the tab exists
            return constraints;
        }
        Constraints s = BY_GRAPH.get(g);
        if (s == null) {
            s = new Constraints();
            BY_GRAPH.put(g, s);
        }
        return s;
    }

    /**
     * Holds the store of {@code g} active while a layout run works on it (nested calls are counted): an activation of
     * another graph during the run (a window the run opens) does not swap {@link #constraints} away from the graph whose
     * steps are adding constraints. A run on a side graph works on the side graph's own store; the graph that was active
     * before is active again when the run ends. Headless runs (no active graph) are not affected: nothing is activated.
     */
    public static synchronized void pin(Graph g) {
        if (g == null) return;
        if (pinDepth == 0) {
            if (active != null && active != g) {
                if (SIDE.contains(g)) {
                    sideRunFrom = active;
                    switchTo(g);
                } else {
                    activate(g);
                }
            }
            pinned = g;
            pending = null;
            hasPending = false;
        }
        pinDepth++;
    }

    /** Ends {@link #pin}; an activation requested during the run is made now. */
    public static synchronized void unpin() {
        if (pinDepth == 0) return;
        if (--pinDepth > 0) return;
        pinned = null;
        if (sideRunFrom != null) {
            switchTo(sideRunFrom);
            sideRunFrom = null;
        }
        if (hasPending) {
            Graph p = pending;
            pending = null;
            hasPending = false;
            activate(p);
        }
    }

    /**
     * Marks {@code g} as a side view of another graph (the skeleton a PD run shows in a window of its own): when its
     * window becomes active, {@link #constraints} stays the store of the graph the user was working on (while one is
     * active). {@link #of(Graph)} gives such a graph a store of its own, and a layout run on it works on that store
     * ({@link #pin}), so it touches nothing of the original.
     */
    public static synchronized void markSideGraph(Graph g) {
        if (g != null) SIDE.add(g);
    }

    public static synchronized boolean isSideGraph(Graph g) {
        return g != null && SIDE.contains(g);
    }

    /** The graph a layout run is pinned to, or null. */
    public static synchronized Graph pinnedGraph() {
        return pinned;
    }

    /** The graph whose store {@link #constraints} is, or null while no graph is active (headless runs). */
    public static synchronized Graph activeGraph() {
        return active;
    }

    /**
     * Forgets the stores of the graphs not in {@code open}. A store references its nodes and they their graph, so the
     * weak keys alone would never let a closed graph go. If the active graph is closed (its side view may still be
     * shown), its store goes as well and no graph is active until the next {@link #activate}.
     */
    public static synchronized void retainOnly(Collection<Graph> open) {
        if (active != null && pinned == null && !open.contains(active)) {
            BY_GRAPH.remove(active);
            active = null;
            constraints = new Constraints();
        }
        BY_GRAPH.keySet().removeIf(g -> g != active && !open.contains(g));
    }

    /** Refresh the constraint lists (AddonTab installs ListModels::updateAllLists). */
    public static Runnable onListsChanged = () -> {};

    /** Recompute the constraint overlay (AddonTab installs DisplayConstraints::revalidateIndication). */
    public static Runnable onOverlayChanged = () -> {};

    /** One-line name of a constraint for conflict reasons (AddonTab installs ConstraintCatalog::describeWithOrigin). */
    public static Function<Constraint, String> describer = c -> c.getKind().name();
}
