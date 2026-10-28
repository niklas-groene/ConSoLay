package layout.template.apply;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.Priority;

import org.AttributeHelper;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One undoable change of template instances on a graph: applying (instances made), removing, switching on or off
 * (instance or component part) and "Prefer" (priorities changed). Undo and redo restore the instances, their
 * constraints in the store, the switches, the priorities, the positions of every node the change moved and the bends
 * it removed, as one step of VANTED's Edit › Undo. Searching and selecting matches make no edits.
 *
 * @author Niklas Gröne
 */
public final class InstanceEdit extends AbstractUndoableEdit {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final Graph graph;
    private final List<TemplateInstance> added = new ArrayList<>();
    /** Per applied instance, its constraints that were still in the store when the application was undone. */
    private final Map<TemplateInstance, List<Constraint>> presentAtUndo = new IdentityHashMap<>();
    private final Map<TemplateInstance, List<Constraint>> removed = new LinkedHashMap<>();
    private final Map<TemplateInstance, boolean[]> onBefore = new IdentityHashMap<>(), onAfter = new IdentityHashMap<>();
    private final Map<TemplateInstance, Map<String, Boolean>> partsBefore = new IdentityHashMap<>(), partsAfter = new IdentityHashMap<>();
    private final Map<Constraint, Priority> prioBefore = new IdentityHashMap<>(), prioAfter = new IdentityHashMap<>();
    private final Map<Node, double[]> posBefore = new IdentityHashMap<>(), posAfter = new IdentityHashMap<>();
    private final Map<Edge, List<Vector2d>> bends = new LinkedHashMap<>();
    /** Refreshes the GUI after undo / redo. */
    private final transient Runnable refresh;

    public InstanceEdit(String name, Graph graph, Runnable refresh) {
        this.name = name;
        this.graph = graph;
        this.refresh = refresh;
    }

    /** An application: the instances made, the positions before and after, the bends removed. */
    public static InstanceEdit applied(String name, Graph g, TemplateApplier.Result r, Runnable refresh) {
        InstanceEdit e = new InstanceEdit(name, g, refresh);
        e.added.addAll(r.made);
        e.positions(r.before, r.after);
        e.bends.putAll(r.bendsRemoved);
        return e;
    }

    /** Records an instance that was removed with these constraints. */
    public InstanceEdit removed(TemplateInstance i, List<Constraint> constraints) {
        removed.put(i, new ArrayList<>(constraints));
        return this;
    }

    /** Records the switches of an instance before a change (call {@link #switchesAfter} after it). */
    public InstanceEdit switchesBefore(TemplateInstance i) {
        onBefore.put(i, new boolean[]{i.isOn()});
        partsBefore.put(i, i.partSwitches());
        return this;
    }

    public InstanceEdit switchesAfter(TemplateInstance i) {
        onAfter.put(i, new boolean[]{i.isOn()});
        partsAfter.put(i, i.partSwitches());
        return this;
    }

    public InstanceEdit prioritiesBefore(List<Constraint> cs) {
        for (Constraint c : cs) prioBefore.put(c, c.getPriority());
        return this;
    }

    public InstanceEdit prioritiesAfter(List<Constraint> cs) {
        for (Constraint c : cs) prioAfter.put(c, c.getPriority());
        return this;
    }

    /** Keeps the nodes whose position differs between the two maps. */
    public InstanceEdit positions(Map<Node, double[]> before, Map<Node, double[]> after) {
        if (before == null || after == null) return this;
        for (Map.Entry<Node, double[]> e : before.entrySet()) {
            double[] a = after.get(e.getKey()), b = e.getValue();
            if (a == null) continue;
            if (Math.abs(a[0] - b[0]) > 1e-9 || Math.abs(a[1] - b[1]) > 1e-9) {
                posBefore.put(e.getKey(), b.clone());
                posAfter.put(e.getKey(), a.clone());
            }
        }
        return this;
    }

    /** True if the edit changes anything. */
    public boolean isSignificant() {
        return !added.isEmpty() || !removed.isEmpty() || !onBefore.isEmpty() || !prioBefore.isEmpty() || !posBefore.isEmpty();
    }

    @Override
    public String getPresentationName() {
        return name;
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();
        Constraints store = ConstraintStore.of(graph);   // the store of the edit's graph, whichever graph is active now
        List<Constraint> touched = new ArrayList<>();
        if (store != null) {
            for (TemplateInstance i : added) {
                List<Constraint> present = TemplateApplier.unapply(i, store);
                presentAtUndo.put(i, present);
                touched.addAll(present);
            }
            for (Map.Entry<TemplateInstance, List<Constraint>> e : removed.entrySet()) TemplateApplier.restore(e.getKey(), e.getValue(), store);
        }
        for (Map.Entry<TemplateInstance, boolean[]> e : onBefore.entrySet()) e.getKey().setOn(e.getValue()[0]);
        for (Map.Entry<TemplateInstance, Map<String, Boolean>> e : partsBefore.entrySet()) e.getKey().restorePartSwitches(e.getValue());
        for (Map.Entry<Constraint, Priority> e : prioBefore.entrySet()) e.getKey().setPriority(e.getValue());
        setPositions(posBefore);
        for (Map.Entry<Edge, List<Vector2d>> e : bends.entrySet()) {
            if (graph.containsEdge(e.getKey())) AttributeHelper.addEdgeBends(e.getKey(), e.getValue());
        }
        if (!touched.isEmpty()) ConflictLog.forget(touched);
        InstanceRegistry.fire();
        if (refresh != null) refresh.run();
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();
        Constraints store = ConstraintStore.of(graph);   // the store of the edit's graph, whichever graph is active now
        if (store != null) {
            // only what undo took away: constraints deleted in between stay deleted
            for (TemplateInstance i : added) {
                TemplateApplier.restore(i, presentAtUndo.getOrDefault(i, i.constraints()), store);
            }
            List<Constraint> gone = new ArrayList<>();
            for (TemplateInstance i : removed.keySet()) gone.addAll(TemplateApplier.unapply(i, store));
            if (!gone.isEmpty()) ConflictLog.forget(gone);
        }
        for (Map.Entry<TemplateInstance, boolean[]> e : onAfter.entrySet()) e.getKey().setOn(e.getValue()[0]);
        for (Map.Entry<TemplateInstance, Map<String, Boolean>> e : partsAfter.entrySet()) e.getKey().restorePartSwitches(e.getValue());
        for (Map.Entry<Constraint, Priority> e : prioAfter.entrySet()) e.getKey().setPriority(e.getValue());
        for (Edge e : bends.keySet()) if (graph.containsEdge(e)) AttributeHelper.removeEdgeBends(e);
        setPositions(posAfter);
        InstanceRegistry.fire();
        if (refresh != null) refresh.run();
    }

    private void setPositions(Map<Node, double[]> pos) {
        if (pos.isEmpty()) return;
        graph.getListenerManager().transactionStarted(this);
        try {
            for (Map.Entry<Node, double[]> e : pos.entrySet()) {
                if (graph.containsNode(e.getKey())) AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
        } finally {
            graph.getListenerManager().transactionFinished(this);
        }
    }
}
