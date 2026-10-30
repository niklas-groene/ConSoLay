package gui;

import constraints.ConflictLog;
import constraints.Constraint;
import gui.components.StatusLabel;
import gui.components.Ui;

import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Node;
import org.graffiti.selection.Selection;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Section "Conflicts" of the {@link ConstraintsTab}: every constraint the layout dropped, the solver could not satisfy,
 * the check found not holding or a higher priority left out, with the reason. Its actions show a constraint on the
 * canvas, select its nodes, restore a dropped one, prefer one that is not applied, or delete it.
 */
public class ConflictPanel extends JPanel {

    static final Color DROPPED_COLOR = new Color(0xff, 0x7f, 0x0e);      // orange
    static final Color UNSATISFIED_COLOR = new Color(0xd6, 0x27, 0x28);  // red
    static final Color WARNING_COLOR = new Color(0x7f, 0x7f, 0x7f);      // grey
    static final Color DEACTIVATED_COLOR = Ui.WARNING;                   // amber

    /** The marker colour of an entry kind (list, overlay and this panel). */
    static Color colorOf(ConflictLog.Kind kind) {
        switch (kind) {
            case DROPPED: return DROPPED_COLOR;
            case UNSATISFIED: return UNSATISFIED_COLOR;
            case DEACTIVATED: return DEACTIVATED_COLOR;
            default: return WARNING_COLOR;
        }
    }

    private final DefaultListModel<ConflictLog.Entry> model = new DefaultListModel<>();
    private final JList<ConflictLog.Entry> list = new JList<>(model);
    private final StatusLabel summary = new StatusLabel(" ");
    private final JButton showButton = Ui.button("Show", "Draw the constraint on the graph (with the constraints it contradicts).", null);
    private final JButton selectButton = Ui.button("Select nodes", "Select the constraint's nodes in the graph.", null);
    private final JButton restoreButton = Ui.button("Restore", "Put a dropped constraint back as your own constraint.", null);
    private final JButton discardButton = Ui.button("Delete", "Delete the constraint (or forget the warning).", null);

    public ConflictPanel() {
        super(new BorderLayout(0, Ui.ITEM_GAP));
        setOpaque(false);
        list.setCellRenderer(new EntryRenderer());
        list.setFixedCellWidth(1);   // the list takes the visible width; an empty JList would ask for 256 px
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) onSelect(); });

        list.setVisibleRowCount(4);
        JPanel buttons = new JPanel(new GridLayout(2, 2, Ui.GAP_S, Ui.GAP_S));
        buttons.setOpaque(false);
        buttons.add(showButton);
        buttons.add(selectButton);
        buttons.add(restoreButton);
        buttons.add(discardButton);
        showButton.addActionListener(e -> showSelected());
        selectButton.addActionListener(e -> selectNodes());
        restoreButton.setName("conflictRestore");
        restoreButton.addActionListener(e -> restoreOrPrefer());
        discardButton.addActionListener(e -> {
            ConflictLog.Entry en = list.getSelectedValue();
            if (en == null) return;
            if (ConflictLog.discard(en)) {   // it kept another constraint from being applied: solve again
                AddonTab.executeUserConstraints();
                ListModels.updateAllLists();
            }
        });

        add(summary, BorderLayout.NORTH);
        add(new gui.components.NestedScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        }, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        ConflictLog.addListener(this::refresh);
        refresh();
    }

    /** True while {@link #refresh()} rebuilds the list: the selection it restores must not redraw the markings. */
    private boolean refreshing;

    private void refresh() {
        ConflictLog.Entry selected = list.getSelectedValue();
        refreshing = true;
        try {
            model.clear();
            List<ConflictLog.Entry> entries = ConflictLog.entries();
            int dropped = 0, unsatisfied = 0, deactivated = 0, warnings = 0;
            for (ConflictLog.Entry e : entries) {
                model.addElement(e);
                if (e.kind == ConflictLog.Kind.DROPPED) dropped++;
                else if (e.kind == ConflictLog.Kind.UNSATISFIED) unsatisfied++;
                else if (e.kind == ConflictLog.Kind.DEACTIVATED) deactivated++;
                else warnings++;
            }
            List<String> parts = new ArrayList<>();
            if (deactivated > 0) parts.add(deactivated + " not applied");
            if (unsatisfied > 0) parts.add(unsatisfied + " not satisfied");
            if (dropped > 0) parts.add(dropped + " dropped");
            if (warnings > 0) parts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
            if (entries.isEmpty()) summary.setStatus("No conflicts.", StatusLabel.Kind.SUCCESS);
            else summary.setStatus(String.join(", ", parts), deactivated == entries.size() ? StatusLabel.Kind.INFO : StatusLabel.Kind.ERROR);
            // Entries are made anew on every decision: keep the one about the same constraint selected.
            if (selected != null) {
                for (ConflictLog.Entry e : entries) {
                    if (e == selected || (e.constraint != null && e.constraint == selected.constraint && e.kind == selected.kind)) {
                        list.setSelectedValue(e, true);
                        break;
                    }
                }
            }
        } finally {
            refreshing = false;
        }
        updateButtons();
        // The constraint lists are live models without change events: repaint them so the warning markers appear
        // right after a layout step.
        try { ListModels.updateAllLists(); } catch (Throwable ignored) { }
        try { ConstraintOverlay.repaintAll(); } catch (Throwable ignored) { }
    }

    void updateButtons() {
        ConflictLog.Entry e = list.getSelectedValue();
        boolean has = e != null && e.constraint != null;
        showButton.setEnabled(has);
        selectButton.setEnabled(has);
        boolean prefer = e != null && e.kind == ConflictLog.Kind.DEACTIVATED;
        restoreButton.setText(prefer ? "Prefer this" : "Restore");
        restoreButton.setToolTipText(Ui.tooltip(prefer
                ? "Raise its priority above the constraints it contradicts, so that it is applied instead of them "
                        + "(only for the active graph, and not for a constraint that contradicts itself)."
                : "Put a dropped constraint back as your own constraint."));
        restoreButton.setEnabled(has && (e.kind == ConflictLog.Kind.DROPPED || (prefer && canPrefer(e.constraint))));
        discardButton.setEnabled(e != null && e.kind != ConflictLog.Kind.DROPPED);
    }

    /** Restores a dropped constraint or prefers one that is not applied, then solves the active graph again. */
    private void restoreOrPrefer() {
        ConflictLog.Entry en = list.getSelectedValue();
        if (en == null || en.constraint == null) return;
        if (en.kind == ConflictLog.Kind.DROPPED) {
            ConflictLog.restore(en);
            if (!inActiveGraph(en.constraint)) return;
            AddonTab.executeUserConstraints();
            ListModels.updateAllLists();
            DisplayConstraints.revalidateIndication();
        } else if (en.kind == ConflictLog.Kind.DEACTIVATED && canPrefer(en.constraint)) {
            ConstraintListSection.prefer(en.constraint);
        }
    }

    /** Prefer works on the active graph (it solves that one) and not for a constraint that contradicts itself. */
    private static boolean canPrefer(Constraint c) {
        if (c == null || c.getDeactivation() == null || c.getDeactivation().contradictsItself()) return false;
        return inActiveGraph(c);
    }

    /** True if every node of {@code c} is in the graph of the active editor session. */
    private static boolean inActiveGraph(Constraint c) {
        EditorSession session = MainFrame.getInstance() == null ? null : MainFrame.getInstance().getActiveEditorSession();
        if (session == null || session.getGraph() == null) return false;
        for (Node n : c.getNodes()) if (!session.getGraph().containsNode(n)) return false;
        return true;
    }

    /** Draws the entry's constraint and, if it is not applied, the constraints it contradicts (under it). */
    private static void draw(ConflictLog.Entry e) {
        DisplayConstraints.clearMarkings();
        for (Constraint p : e.partners) DisplayConstraints.drawConstraint(p);
        DisplayConstraints.drawConstraint(e.constraint);
    }

    /** The user picked an entry: enable its actions and draw its constraint. */
    private void onSelect() {
        updateButtons();
        ConflictLog.Entry e = list.getSelectedValue();
        if (e != null && e.constraint != null) draw(e);
    }

    private void showSelected() {
        ConflictLog.Entry e = list.getSelectedValue();
        if (e == null || e.constraint == null) return;
        draw(e);
    }

    /** Select the constraint's nodes in the active VANTED editor session. */
    private void selectNodes() {
        ConflictLog.Entry e = list.getSelectedValue();
        if (e == null || e.constraint == null) return;
        try {
            EditorSession session = MainFrame.getInstance().getActiveEditorSession();
            if (session == null) return;
            // Only nodes of the graph shown in the active view can be selected there.
            for (Node n : e.constraint.getNodes()) if (n.getGraph() != session.getGraph()) return;
            de.ipk_gatersleben.ag_nw.graffiti.GraphHelper.selectNodes(true, e.constraint.getNodes());
        } catch (Throwable t) {
            try {
                EditorSession session = MainFrame.getInstance().getActiveEditorSession();
                Selection sel = new Selection("conflict");
                for (Node n : e.constraint.getNodes()) sel.add(n);
                session.getSelectionModel().setActiveSelection(sel);
                session.getSelectionModel().selectionChanged();
            } catch (Throwable ignored) { }
        }
    }

    /** Two-line cell: coloured kind marker + constraint, reason underneath. */
    private static final class EntryRenderer extends JPanel implements ListCellRenderer<ConflictLog.Entry> {
        /** Text wraps at this width instead of widening the list (cell insets, scrollbar and tab borders come on top). */
        private static final int TEXT_WIDTH = Ui.COLUMN_WIDTH - 50;
        private final JLabel head = new JLabel();
        private final JLabel body = new JLabel();

        EntryRenderer() {
            super(new BorderLayout());
            setBorder(new EmptyBorder(3, 6, 3, 6));
            body.setFont(body.getFont().deriveFont(Font.PLAIN, 11f));
            body.setForeground(Color.DARK_GRAY);
            add(head, BorderLayout.NORTH);
            add(body, BorderLayout.SOUTH);
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ConflictLog.Entry> l, ConflictLog.Entry e, int index,
                                                      boolean selected, boolean focus) {
            Color marker = colorOf(e.kind);
            String what = e.constraint == null ? "" : " " + Ui.esc(ListModels.describe(e.constraint));
            head.setText(Ui.wrapped("<span style='color:rgb(" + marker.getRed() + "," + marker.getGreen() + ","
                    + marker.getBlue() + ")'>●</span> <b>" + e.title() + "</b> [" + Ui.esc(e.phase) + "]" + what, TEXT_WIDTH));
            body.setText(Ui.wrapped(Ui.esc(e.reason), TEXT_WIDTH));
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            head.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            body.setForeground(selected ? l.getSelectionForeground() : Color.DARK_GRAY);
            return this;
        }
    }
}
