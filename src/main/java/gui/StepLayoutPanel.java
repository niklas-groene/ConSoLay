package gui;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintStore;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.StepList;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.sbgn.ConstraintSBGNLayout;
import layout.steps.LayoutStep;
import layout.steps.StepAlgorithm;
import layout.steps.StepLayout;

import org.AttributeHelper;
import org.graffiti.editor.GravistoService;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel of one registered {@link StepAlgorithm} in the "Algorithms" tab ({@link AlgorithmsTab}): chooses the
 * algorithm's options, runs its {@link StepLayout} at once or step by step, and shows the constraints the steps add.
 * The {@link MentalMapPanel} treats graph changes as layout output while code of this class is on the call stack, so
 * every action that runs steps, resets the layout or removes bends stays in this class.
 *
 * @author Niklas Gröne
 */
public class StepLayoutPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Every panel that exists, one per registered algorithm (the add-on tab is built once). */
    private static final List<StepLayoutPanel> instances = new ArrayList<>();

    /** The step layout the steps belong to: set by a panel and by the Layout menu entry (ConstraintSBGNLayoutAlgorithm). */
    private static StepLayout activeLayout;

    public static void setActiveLayout(StepLayout layout) {
        activeLayout = layout;
        refresh();
    }

    public static StepLayout getActiveLayout() {
        return activeLayout;
    }

    private final StepAlgorithm algorithm;
    private final Map<String, JComboBox<String>> optionBoxes = new LinkedHashMap<>();
    private final StatusLabel statusLabel;
    private final Section stepsSection;
    private final StepList stepList;
    private final JLabel stepsHint;
    private final JCheckBox showConstraintsBox;
    private final JButton runAllButton;
    private final JButton resetButton;
    private final JButton newLayoutButton;
    private final JButton removeBendsButton;

    /** Indices of steps that have already executed in the current layout. */
    private final List<Integer> executedSteps = new ArrayList<>();

    /**
     * The layout this panel set up itself (New run, or a run / step on a map without a layout). The panel resets its
     * step list right after setting it up, so the deferred {@link #refresh()} must not reset it again: it would run
     * after the steps and mark them as not executed. That refresh clears it again.
     */
    private StepLayout preparedLayout;

    public StepLayoutPanel(StepAlgorithm algorithm) {
        super(new BorderLayout());
        this.algorithm = algorithm;
        instances.add(this);
        setOpaque(false);

        // ---- Run: the algorithm's options, run all, new run / reset, status ----
        List<JPanel> optionRows = new ArrayList<>();
        for (StepAlgorithm.Option o : algorithm.options()) {
            JComboBox<String> box = Ui.comboBox(o.values.toArray(new String[0]));
            box.setName(o.key);
            if (o.defaultValue != null) box.setSelectedItem(o.defaultValue);
            optionBoxes.put(o.key, box);
            JPanel row = Ui.labeled(o.label, box);
            if (o.tooltip != null) {
                String tip = Ui.tooltip(o.tooltip);
                for (Component c : row.getComponents()) {
                    if (c instanceof JComponent) ((JComponent) c).setToolTipText(tip);
                }
            }
            optionRows.add(row);
        }

        runAllButton = Ui.primaryButton("Run all steps",
                "Run every step of the " + algorithm.title() + " layout on the active map, one after the other. If the map has no run yet, "
                        + "a new one is prepared first.",
                this::onRunAll);
        runAllButton.setName("runAll");

        newLayoutButton = Ui.button("New run",
                "Prepare a fresh run on the active map" + (optionRows.isEmpty() ? "" : " with the selected options") + ", to execute the steps one at a "
                        + "time. The constraints of an earlier run are dropped; your own constraints on this map stay.",
                this::onNewLayout);
        newLayoutButton.setName("newRun");

        resetButton = Ui.button("Reset",
                "Undo the run: restore the node positions from before the first step, remove the edge bends and the "
                        + "constraints the steps added, and hide the constraint markings.",
                this::onReset);
        resetButton.setName("reset");

        statusLabel = new StatusLabel("Ready.");

        Section runSection = new Section("Run");
        for (JPanel row : optionRows) runSection.addRow(row);
        runSection.addRow(runAllButton);
        runSection.addRow(Ui.row(newLayoutButton, resetButton));
        runSection.addRow(statusLabel);

        // ---- Steps: one row per step of the active layout ----
        stepList = new StepList("The steps appear once a run is prepared (New run) or started (Run all steps).");
        stepList.setOnRun(this::onRunStep);
        stepsHint = Ui.hint("Click a step to run it; hover for details.");

        stepsSection = new Section("Steps");
        stepsSection.addRow(stepList);
        stepsSection.addRow(stepsHint, Ui.GAP_S);

        // ---- View: constraint markings, straight edges ----
        showConstraintsBox = new JCheckBox("Show constraints", true);
        showConstraintsBox.setName("showConstraints");
        showConstraintsBox.setOpaque(false);
        showConstraintsBox.setToolTipText(Ui.tooltip("Draw all registered constraints over the graph. While it is on, "
                + "a run or a step also draws the constraints it adds."));
        showConstraintsBox.addActionListener(this::onToggleShowConstraints);

        removeBendsButton = Ui.button("Straight edges",
                "Delete every edge bend and clear the docking of every edge of the active map: all edges become "
                        + "straight centre-to-centre lines.",
                this::onRemoveBends);
        removeBendsButton.setName("removeBends");

        Section viewSection = new Section("View");
        viewSection.addRow(showConstraintsBox);
        viewSection.addRow(removeBendsButton);

        VerticalStack sections = new VerticalStack();
        sections.addRow(runSection, 0);
        sections.addRow(stepsSection, Ui.SECTION_GAP);
        sections.addRow(viewSection, Ui.SECTION_GAP);
        add(sections, BorderLayout.CENTER);

        rebuildStepButtons();
    }

    /**
     * Every panel shows the steps of the new active layout as not executed, except the panel that prepared it itself
     * (see {@link #preparedLayout}).
     */
    static void refresh() {
        final StepLayout layout = activeLayout;
        for (StepLayoutPanel instance : new ArrayList<>(instances)) {
            SwingUtilities.invokeLater(() -> {
                // A layout the panel set up itself was already reset by the panel (and may have run since). Only this
                // one refresh concerns it, so drop the reference: it would otherwise keep a replaced layout (and its
                // graph) alive.
                if (layout != null && layout == instance.preparedLayout) {
                    instance.preparedLayout = null;
                    return;
                }
                instance.executedSteps.clear();
                instance.rebuildStepButtons();
                if (instance.ownLayout() == null) instance.status("No run active.");
                else instance.status("Run ready (" + instance.stepCount() + " steps).");
            });
        }
    }

    /**
     * Marks every step of {@code layout} as executed, e.g. after the Layout menu entry ran the whole pipeline. Does
     * nothing if no panel shows it or {@code layout} is no longer the active layout.
     */
    public static void markAllStepsDone(StepLayout layout) {
        SwingUtilities.invokeLater(() -> {
            for (StepLayoutPanel panel : new ArrayList<>(instances)) {
                if (layout == null || layout != activeLayout || panel.ownLayout() != layout) continue;
                panel.showAllStepsDone();
            }
        });
    }

    /** The active layout if it belongs to this panel's algorithm, else null. */
    private StepLayout ownLayout() {
        StepLayout l = activeLayout;
        return l != null && algorithm.id().equals(l.getAlgorithmId()) ? l : null;
    }

    /** The chosen value per option. */
    private Map<String, String> chosenOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JComboBox<String>> e : optionBoxes.entrySet()) m.put(e.getKey(), (String) e.getValue().getSelectedItem());
        return m;
    }

    // --- handlers ---

    private void onNewLayout(ActionEvent e) {
        if (!setupLayoutForActiveGraph(true)) return;
        status("New run ready (" + stepCount() + " steps).");
    }

    private void onRunAll(ActionEvent e) {
        if (!setupLayoutForActiveGraph(false)) return;
        StepLayout layout = ownLayout();
        try {
            layout.runAll();
            showAllStepsDone();
            if (showConstraintsBox.isSelected()) {
                DisplayConstraints.clearMarkings();
                DisplayConstraints.displayAllConstraints();
            }
            redrawActiveView();
        } catch (Throwable t) {
            status("Run all failed: " + message(t), StatusLabel.Kind.ERROR);
        }
    }

    private void onReset(ActionEvent e) {
        StepLayout layout = ownLayout();
        if (layout == null) return;
        try {
            layout.resetToOriginal();
            executedSteps.clear();
            rebuildStepButtons();
            DisplayConstraints.clearMarkings();
            status("Reset to the original layout.", StatusLabel.Kind.SUCCESS);
            redrawActiveView();
        } catch (Throwable t) {
            status("Reset failed: " + message(t), StatusLabel.Kind.ERROR);
        }
    }

    /**
     * Binds a layout of this panel's algorithm to the active graph. Unless {@code force}, the active layout stays if it
     * belongs to that graph (or to its skeleton window); otherwise the markings and the constraints of earlier runs are
     * dropped and a new layout is created. Returns false if no graph is active or the layout cannot be created.
     */
    private boolean setupLayoutForActiveGraph(boolean force) {
        Graph current = activeGraph();
        if (current == null) {
            status("No active graph.", StatusLabel.Kind.ERROR);
            return false;
        }
        StepLayout existing = ownLayout();
        if (!force && existing != null && (existing.graph == current || isSkeletonOf(existing, current))) {
            return true;
        }
        DisplayConstraints.clearMarkings();
        ConstraintStore.of(current).retainUserConstraintsOf(current);
        ConflictLog.storeReplaced();
        ListModels.updateAllLists();
        StepLayout layout;
        try {
            layout = algorithm.create(current, chosenOptions());
        } catch (Throwable t) {
            status("Could not initialize the " + algorithm.title() + " layout: " + message(t), StatusLabel.Kind.ERROR);
            return false;
        }
        preparedLayout = layout;
        setActiveLayout(layout);
        executedSteps.clear();
        rebuildStepButtons();
        return true;
    }

    /**
     * PD step 1 opens the skeleton in its own window. If that window is active, the steps still belong to the
     * layout of the original map (a fresh layout for the skeleton would also drop the user's constraints).
     */
    private static boolean isSkeletonOf(StepLayout layout, Graph current) {
        return layout instanceof ConstraintSBGNLayout && ((ConstraintSBGNLayout) layout).skeleton == current;
    }

    private void onRemoveBends(ActionEvent e) {
        Graph g = activeGraph();
        if (g == null) {
            status("No active graph.", StatusLabel.Kind.ERROR);
            return;
        }
        int[] n = removeAllBends(g);
        status(n[0] + (n[0] == 1 ? " bend" : " bends") + " and " + n[1] + (n[1] == 1 ? " docking" : " dockings")
                + " removed.", StatusLabel.Kind.SUCCESS);
        redrawActiveView();
    }

    /**
     * Deletes the bends of every edge of {@code g} and clears its docking (the SBGN-ED port / side an arc was attached
     * to, as step 1 does before the layout), so every edge becomes a straight centre-to-centre line. Returns
     * {edges that had bends, edges that had a docking}. removeEdgeBends() fires no attribute event (an empty bend
     * map), so the view would keep the old polylines until the next full rebuild; switching the shape to a straight
     * line is a real attribute change and makes the view redraw the edge now.
     */
    static int[] removeAllBends(Graph g) {
        int bends = 0, dockings = 0;
        for (Edge edge : g.getEdges()) {
            if (!AttributeHelper.getEdgeBends(edge).isEmpty()) bends++;
            AttributeHelper.removeEdgeBends(edge);
            try {
                EdgeGraphicAttribute ega = (EdgeGraphicAttribute) edge.getAttribute(GraphicAttributeConstants.GRAPHICS);
                String src = ega.getDocking().getSource(), tgt = ega.getDocking().getTarget();
                if ((src != null && !src.isEmpty()) || (tgt != null && !tgt.isEmpty())) dockings++;
                ega.getDocking().setSource("");
                ega.getDocking().setTarget("");
            } catch (Exception ignored) { }
            AttributeHelper.setEdgeBendStyle(edge, GraphicAttributeConstants.STRAIGHTLINE_CLASSNAME);
        }
        return new int[]{ bends, dockings };
    }

    private void onToggleShowConstraints(ActionEvent e) {
        if (showConstraintsBox.isSelected()) {
            DisplayConstraints.displayAllConstraints();
            status("Constraints shown.");
        } else {
            DisplayConstraints.clearMarkings();
            status("Constraints hidden.");
        }
        redrawActiveView();
    }

    private void onRunStep(int index) {
        if (!setupLayoutForActiveGraph(false)) return;
        StepLayout layout = ownLayout();
        try {
            List<Constraint> gone = ConstraintStore.constraints == null ? new ArrayList<>()
                    : new ArrayList<>(ConstraintStore.constraints.getAllConstraints());
            List<Constraint> diff = layout.runStep(index);
            // What the step took out of the store (step 1 of a second run drops the constraints of the first) is not
            // marked any more.
            if (ConstraintStore.constraints != null) gone.removeAll(ConstraintStore.constraints.getAllConstraints());
            DisplayConstraints.hideConstraints(gone);
            if (!executedSteps.contains(index)) executedSteps.add(index);
            rebuildStepButtons();
            int n = (diff != null) ? diff.size() : 0;
            status("Step " + (index + 1) + " done" + (n > 0 ? ": +" + n + " constraints." : "."), StatusLabel.Kind.SUCCESS);
            if (showConstraintsBox.isSelected() && diff != null) {
                for (Constraint c : diff) {
                    if (c != null) {
                        DisplayConstraints.drawConstraint(c);
                    }
                }
            }
            // The step moved nodes, so the markings drawn before it are stale.
            DisplayConstraints.revalidateIndication();
            redrawActiveView();
        } catch (Throwable t) {
            String stepName = stepName(index);
            status("Step failed" + (stepName.isEmpty() ? "" : " (" + stepName + ")") + ": " + message(t),
                    StatusLabel.Kind.ERROR);
        }
    }

    // --- step list build ---

    /**
     * Rebuilds the step list from the active layout: executed steps are done, the first step not executed yet is the
     * next one. Also updates the "k / n done" counter, the hint under the list and whether Reset is enabled.
     */
    private void rebuildStepButtons() {
        StepLayout layout = ownLayout();
        List<StepList.Step> steps = new ArrayList<>();
        int done = 0;
        if (layout != null) {
            List<LayoutStep> layoutSteps = layout.getSteps();
            int next = -1;
            for (int i = 0; i < layoutSteps.size(); i++) {
                if (!executedSteps.contains(i)) {
                    next = i;
                    break;
                }
            }
            for (int i = 0; i < layoutSteps.size(); i++) {
                LayoutStep step = layoutSteps.get(i);
                StepList.State state = executedSteps.contains(i) ? StepList.State.DONE
                        : i == next ? StepList.State.NEXT : StepList.State.PENDING;
                if (state == StepList.State.DONE) done++;
                steps.add(new StepList.Step(step == null ? "" : text(step.name),
                        step == null ? null : step.description, state));
            }
        }
        stepList.setSteps(steps);
        stepsHint.setVisible(!steps.isEmpty());
        stepsSection.setTrailingText(layout == null ? null : done + " / " + steps.size() + " done");
        resetButton.setEnabled(layout != null);
        revalidate();
        repaint();
    }

    /** Marks every step of the active layout as executed. */
    private void showAllStepsDone() {
        executedSteps.clear();
        int count = stepCount();
        for (int i = 0; i < count; i++) executedSteps.add(i);
        rebuildStepButtons();
        status("Run complete (" + count + " steps).", StatusLabel.Kind.SUCCESS);
    }

    /** Status line under the actions; the full text is its tooltip because the line is clipped to the tab width. */
    private void status(String text) {
        status(text, StatusLabel.Kind.INFO);
    }

    private void status(String text, StatusLabel.Kind kind) {
        statusLabel.setStatus(text, kind);
    }

    // --- layout helpers ---

    private int stepCount() {
        StepLayout layout = ownLayout();
        return layout == null ? 0 : layout.getSteps().size();
    }

    /** Name of step {@code index} of the active layout, "" if there is none. */
    private String stepName(int index) {
        StepLayout layout = ownLayout();
        if (layout == null) return "";
        List<LayoutStep> steps = layout.getSteps();
        if (index < 0 || index >= steps.size() || steps.get(index) == null) return "";
        return text(steps.get(index).name);
    }

    private static String text(String s) {
        return s != null ? s : "";
    }

    /** The message of {@code t}, or its class name if it has none (e.g. a NullPointerException). */
    private static String message(Throwable t) {
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
    }

    private static Graph activeGraph() {
        try {
            return GravistoService.getInstance().getMainFrame().getActiveEditorSession().getGraph();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void redrawActiveView() {
        try {
            GravistoService.getInstance().getMainFrame().getActiveEditorSession()
                    .getActiveView().getViewComponent().repaint();
        } catch (Throwable ignored) { }
    }
}
