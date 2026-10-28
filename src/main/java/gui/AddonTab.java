/*************************************************************************************
 * The Exemplary Add-on is (c) 2008-2011 Plant Bioinformatics Group, IPK Gatersleben,
 * http://bioinformatics.ipk-gatersleben.de
 * The source code for this project, which is developed by our group, is
 * available under the GPL license v2.0 available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html. By using this
 * Add-on and VANTED you need to accept the terms and conditions of this
 * license, the below stated disclaimer of warranties and the licenses of
 * the used libraries. For further details see license.txt in the root
 * folder of this project.
 ************************************************************************************/
package gui;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintCheck;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.OrthogonalEdgeConstraintGraph;
import gui.components.Ui;
import layout.store.GraphConstraints;
import layout.store.StoreCodec;
import layout.template.apply.InstanceRegistry;
import layout.template.model.TemplateSource;

import org.BackgroundTaskStatusProviderSupportingExternalCall;
import org.ErrorMsg;
import org.adaptagrams.NativeLoader;
import org.graffiti.editor.GravistoService;
import org.graffiti.editor.MainFrame;
import org.graffiti.editor.MessageType;
import org.graffiti.event.GraphEvent;
import org.graffiti.event.GraphListener;
import org.graffiti.event.ListenerNotFoundException;
import org.graffiti.event.TransactionEvent;
import org.graffiti.graph.Graph;
import org.graffiti.plugin.inspector.InspectorTab;
import org.graffiti.plugin.view.View;
import org.graffiti.plugin.view.ViewListener;
import org.graffiti.plugins.modes.defaults.MegaMoveTool;
import org.graffiti.selection.SelectionEvent;
import org.graffiti.selection.SelectionListener;
import org.graffiti.session.EditorSession;
import org.graffiti.session.Session;
import org.graffiti.session.SessionListener;
import org.vanted.plugins.layout.adaptagrams.AdaptagramsLibrary;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The add-on's inspector tab "Constraint-Based Layout" with two main tabs: "Algorithms" ({@link AlgorithmsTab}: SBGN,
 * Mental Map, Templates) and "Constraints" (the user's constraints, their lists and conflicts). The tab loads the
 * Adaptagrams natives, installs the refreshes of the {@link ConstraintStore}, and re-solves the user's constraints
 * after a drag or a deleted node.
 */
public class AddonTab extends InspectorTab implements ViewListener, SessionListener, GraphListener, SelectionListener {

    private static final long serialVersionUID = 1L;

    private Graph graph;

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.min(d.width, Ui.TAB_WIDTH), d.height);
    }

    public AddonTab() {
        // The Adaptagrams natives bundled in the add-on JAR must be loaded before any org.adaptagrams class is touched;
        // VANTED's own Adaptagrams library in its plugins folder is the fallback.
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            String errorMessage = AdaptagramsLibrary.loadLibrary();
            if (!errorMessage.isEmpty()) ErrorMsg.addErrorMessage(errorMessage);
        }
        // the store and the GUI refreshes that non-GUI code triggers through it
        ConstraintStore.constraints = new Constraints();
        ConstraintStore.onListsChanged = ListModels::updateAllLists;
        ConstraintStore.onOverlayChanged = DisplayConstraints::revalidateIndication;
        ConstraintStore.describer = ConstraintCatalog::describeWithOrigin;

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("Algorithms", new AlgorithmsTab());
        mainTabs.addTab("Constraints", new ConstraintsTab());

        setLayout(new BorderLayout());
        add(mainTabs, BorderLayout.CENTER);

        validate();
    }

    @Override
    public String getName() {
        return getTitle();
    }

    /** VANTED does not show an inspector tab without a title. */
    @Override
    public String getTitle() {
        return "Constraint-Based Layout";
    }

    /** VANTED asks every tab on each view change whether it stays visible; this one always does. */
    @Override
    public boolean visibleForView(View v) {
        return true;
    }

    public void viewChanged(View newView) {
        gui.template.TemplateWorkbench.get().activeViewChanged();
    }

    public void sessionChanged(Session s) {
        // A layout run that opens a window on another graph (the PD skeleton) makes VANTED fire this mid-run: the run
        // keeps its graph, store and listeners until its own switch back to the map or the user's next click.
        Graph pinned = ConstraintStore.pinnedGraph();
        if (pinned != null && (s == null || s.getGraph() != pinned)) return;
        if (s != null) {
            if (graph != null) {
                try {
                    graph.getListenerManager().removeGraphListener(this);
                } catch (ListenerNotFoundException e) {
                    ErrorMsg.addErrorMessage(e);
                }
            }
            graph = s.getGraph();
            graph.getListenerManager().addDelayedGraphListener(this);
        } else {
            graph = null; // free sessions, views, or graphs to avoid memory leaks
        }
        // One store per graph: the active graph's store becomes ConstraintStore.constraints, those of closed graphs go.
        Graph before = ConstraintStore.activeGraph();
        ConstraintStore.activate(graph);
        forgetClosedStores();
        if (ConstraintStore.activeGraph() != before) ConflictLog.graphChanged();
        else ConflictLog.storeReplaced();
        if (graph != null && !ConstraintStore.isSideGraph(graph)) attachSavedConstraints(s);
        ConstraintsTab.graphSelectionChanged();
        ListModels.updateAllLists();
        gui.template.TemplateWorkbench.get().activeViewChanged();   // matches and instances belong to their graph
    }

    /** Drops the stores of graphs whose sessions are closed. */
    private static void forgetClosedStores() {
        List<Graph> open = new ArrayList<>();
        try {
            for (Session x : MainFrame.getSessions()) if (x.getGraph() != null) open.add(x.getGraph());
        } catch (Exception ignored) {
            return;   // no main frame (headless): keep everything
        }
        ConstraintStore.retainOnly(open);
        InstanceRegistry.retainOnly(open);
    }

    /**
     * The first time a graph's session is active: reads the constraints the graph carries ({@link GraphConstraints}),
     * or else the companion file next to its map file, and decides them without moving a node.
     */
    private void attachSavedConstraints(Session s) {
        if (!GraphConstraints.markAttached(graph)) return;
        boolean wasModified = graph.isModified();
        try {
            TemplateSource templates = gui.template.TemplatePanel.templateSource();
            StoreCodec.Report r = GraphConstraints.loadSaved(graph, ConstraintStore.constraints, templates);
            if (r == null && s instanceof EditorSession) {
                File f = fileOf((EditorSession) s);
                File companion = f == null ? null : GraphConstraints.companionOf(f);
                if (companion != null && companion.isFile())
                    r = GraphConstraints.readCompanion(companion, graph, ConstraintStore.constraints, templates);
            }
            if (r != null) {
                afterConstraintsLoaded(graph, r);
                graph.setModified(wasModified);   // giving the elements their stable ids is no change of the map
            }
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
    }

    /** The file of the session's graph, or null (not saved yet, or loaded from a URL). */
    static File fileOf(EditorSession s) {
        String name = s == null ? null : s.getFileNameFull();
        if (name == null || name.isEmpty()) return null;
        try {
            if (name.startsWith("file:")) {
                File f = new File(new java.net.URI(name));
                return f.isFile() ? f : null;
            }
        } catch (Exception ignored) {
            // not a URI
        }
        File f = new File(name);
        return f.isFile() ? f : null;
    }

    /**
     * Decides constraints just loaded into the active graph's store and lists those that do not hold in the drawing
     * under "Loaded"; the drawing stays as it is until "Apply constraints".
     */
    static void afterConstraintsLoaded(Graph g, StoreCodec.Report r) {
        if (g == null || ConstraintStore.constraints == null) return;
        List<Constraint> all = ConstraintStore.constraints.getAllConstraints();
        ConstraintConflicts.Scope scope = CBLayoutAlgorithm.userScope(g, ConstraintsTab.isForceDirected(), ConstraintsTab.isAvoidOverlaps());
        if (scope != null) ConstraintConflicts.resolve(all, scope);
        ConflictLog.setDeactivated(all);
        for (Constraint c : r.added) {
            if (c.isAutomatic() || !c.isActive()) continue;
            String why = ConstraintCheck.violation(c, g.getNodes(), ConstraintCheck.TOLERANCE);
            if (why != null) ConflictLog.addUnsatisfied(c, "Loaded", "does not hold in the loaded drawing (" + why + "); \"Apply constraints\" solves it");
        }
        ListModels.updateAllLists();
        DisplayConstraints.revalidateIndication();
        String msg = r.summary();
        if (!r.problems.isEmpty()) msg += " " + r.problems.get(0) + (r.problems.size() > 1 ? " (and " + (r.problems.size() - 1) + " more)" : "");
        MainFrame.showMessage(msg, r.problems.isEmpty() ? MessageType.INFO : MessageType.ERROR);
    }

    /**
     * VANTED fires this after File › Save and Save As. SBGN-ML keeps no foreign attributes, so the constraints of a map
     * saved as SBGN-ML go into the companion file next to it.
     */
    public void sessionDataChanged(Session s) {
        if (!GraphConstraints.ENABLED || !(s instanceof EditorSession) || s.getGraph() == null) return;
        Graph g = s.getGraph();
        if (ConstraintStore.isSideGraph(g)) return;
        File f = fileOf((EditorSession) s);
        if (f == null || !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".sbgn")) return;
        Constraints store = ConstraintStore.of(g);
        File companion = GraphConstraints.companionOf(f);
        if (store.getAllConstraints().isEmpty() && InstanceRegistry.of(g).isEmpty() && !companion.isFile()) return;
        try {
            GraphConstraints.writeCompanion(g, store, companion);
        } catch (IOException e) {
            ErrorMsg.addErrorMessage(e);
        }
    }

    public void postEdgeAdded(GraphEvent e) { }

    public void postEdgeRemoved(GraphEvent e) { }

    public void postGraphCleared(GraphEvent e) { }

    public void postNodeAdded(GraphEvent e) { }

    public void postNodeRemoved(GraphEvent e) { }

    public void preEdgeAdded(GraphEvent e) { }

    public void preEdgeRemoved(GraphEvent e) {
        // A deleted edge takes its horizontal / vertical edge constraints along.
        if (ConstraintStore.constraints == null || e == null || e.getEdge() == null) return;
        List<Constraint> removed = new ArrayList<>();
        for (OrthogonalEdgeConstraintGraph c : ConstraintStore.constraints.getConstraints(OrthogonalEdgeConstraintGraph.class)) {
            if (c.getEdge() == e.getEdge()) {
                ConstraintStore.constraints.removeConstraint(c);
                removed.add(c);
            }
        }
        if (!removed.isEmpty()) {
            DisplayConstraints.clearMarkings();
            ListModels.updateAllLists();
            afterConstraintsRemoved(removed);
        }
    }

    /**
     * Constraints went with a deleted node or edge, or lost a node: forgets the removed ones' conflicts and, if a
     * constraint is not applied now (its reason may be gone), solves again once the deletion is done.
     */
    private static void afterConstraintsRemoved(List<Constraint> removed) {
        boolean freed = ConflictLog.forget(removed);
        boolean undecided = false;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) undecided |= c.getDeactivation() != null;
        if (freed || undecided) SwingUtilities.invokeLater(() -> {
            executeUserConstraints();
            ListModels.updateAllLists();
        });
    }

    /** The scope the next constraint solve of the active graph resolves in (null without an active graph). */
    public static ConstraintConflicts.Scope activeScope() {
        Graph g;
        try {
            g = GravistoService.getInstance().getMainFrame().getActiveEditorSession().getGraph();
        } catch (Exception e) {
            return null;
        }
        return g == null ? null : CBLayoutAlgorithm.userScope(g, ConstraintsTab.isForceDirected(), ConstraintsTab.isAvoidOverlaps());
    }

    public void preGraphCleared(GraphEvent e) { }

    public void preNodeAdded(GraphEvent e) { }

    public void preNodeRemoved(GraphEvent e) {
        // A deleted node leaves its constraints; the rest of an alignment stays aligned.
        if (ConstraintStore.constraints == null || e == null || e.getNode() == null) return;
        boolean touched = false;   // a constraint that only loses the node changes as well
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) touched |= c.getNodes().contains(e.getNode());
        List<Constraint> removed = ConstraintStore.constraints.removeNode(e.getNode());
        if (!removed.isEmpty()) DisplayConstraints.clearMarkings();
        ListModels.updateAllLists();
        if (touched) afterConstraintsRemoved(removed);
    }

    public void transactionFinished(TransactionEvent e, BackgroundTaskStatusProviderSupportingExternalCall status) {
        dropDeletedElements();
        afterMoveTransaction(graph, e);
    }

    /**
     * VANTED deletes nodes and edges (Delete key, Cut, undo) inside a transaction, and a delayed listener does not get the
     * pre-remove events of a transaction. After every transaction the constraints therefore let go of the nodes and edges
     * that are no longer in the graph, as preNodeRemoved and preEdgeRemoved do for single deletions.
     */
    private void dropDeletedElements() {
        Constraints store = ConstraintStore.constraints;
        if (graph == null || store == null || ConstraintStore.activeGraph() != graph) return;
        if (ConstraintStore.pinnedGraph() != null) return;   // a layout run removes and re-inserts elements itself
        Set<org.graffiti.graph.Node> nodes = new HashSet<>(graph.getNodes());
        Set<org.graffiti.graph.Edge> edges = null;
        List<Constraint> removed = new ArrayList<>();
        for (OrthogonalEdgeConstraintGraph c : store.getConstraints(OrthogonalEdgeConstraintGraph.class)) {
            if (c.getEdge() == null) continue;
            if (edges == null) edges = new HashSet<>(graph.getEdges());
            if (!edges.contains(c.getEdge())) {
                store.removeConstraint(c);
                removed.add(c);
            }
        }
        Set<org.graffiti.graph.Node> gone = new LinkedHashSet<>();
        for (Constraint c : store.getAllConstraints()) {
            for (org.graffiti.graph.Node n : c.getNodes()) if (!nodes.contains(n)) gone.add(n);
        }
        for (org.graffiti.graph.Node n : gone) removed.addAll(store.removeNode(n));
        if (removed.isEmpty() && gone.isEmpty()) return;
        DisplayConstraints.clearMarkings();
        ListModels.updateAllLists();
        afterConstraintsRemoved(removed);
    }

    /**
     * Re-solves the active graph after the user added or changed a constraint or applied a template, with the
     * force-directed and overlap switches of the Constraints tab.
     */
    public static void executeUserConstraints() {
        Graph g;
        try {
            g = GravistoService.getInstance().getMainFrame().getActiveEditorSession().getGraph();
        } catch (Exception e) {
            return;
        }
        CBLayoutAlgorithm.executeUserConstraints(g, ConstraintsTab.isForceDirected(), ConstraintsTab.isAvoidOverlaps());
    }

    /**
     * "Apply constraints" (button of the Constraints tab and Layout menu entry): decides, solves, checks and shows the
     * constraints of the active graph, e.g. to project the result of a VANTED layout algorithm onto them.
     */
    public static void applyConstraints() {
        executeUserConstraints();
        ListModels.updateAllLists();
        DisplayConstraints.revalidateIndication();
    }

    /** Guards against re-entering the move re-solve (its position updates fire events of their own). */
    private static boolean resolvingMove = false;

    /**
     * Re-solves the constraints after each step of an interactive move (VANTED's move tool wraps every drag step in a
     * transaction), so they hold while the user drags. Other transactions (file loading, undo, layout algorithms such
     * as VANTED's NoOverlap in SBGN step 3) are ignored: a solve there would discard the routed edges.
     */
    static void afterMoveTransaction(Graph g, TransactionEvent e) {
        if (g == null || e == null || !(e.getSource() instanceof MegaMoveTool)) return;
        if (ConstraintStore.constraints == null || !hasEnabledConstraint()) return;   // nothing to hold: no solve at all
        if (resolvingMove) return;
        resolvingMove = true;
        try {
            // the move tool moves the selected nodes: a solve of their constraint components is enough
            List<org.graffiti.graph.Node> moved = null;
            try {
                moved = de.ipk_gatersleben.ag_nw.graffiti.GraphHelper.getSelectedNodes(MainFrame.getInstance().getActiveEditorSession());
            } catch (Exception ignored) { }
            CBLayoutAlgorithm.executeUserConstraintsAround(g, moved, ConstraintsTab.isForceDirected(), ConstraintsTab.isAvoidOverlaps());
        } catch (Exception ex) {
            ErrorMsg.addErrorMessage(ex);
        } finally {
            resolvingMove = false;
        }
    }

    /** True if the store holds a constraint the user has not switched off. */
    private static boolean hasEnabledConstraint() {
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) if (c.isEnabled()) return true;
        return false;
    }

    public void transactionStarted(TransactionEvent e) { }

    public void selectionChanged(SelectionEvent e) {
        ConstraintsTab.graphSelectionChanged();
    }

    public void selectionListChanged(SelectionEvent e) {
        ConstraintsTab.graphSelectionChanged();
    }

    @Override
    public boolean isSelectionListener() {
        return true;
    }
}
