package gui;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintGroup;
import constraints.ConstraintKind;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.Deactivation;
import constraints.Priority;
import gui.components.CardPanel;
import gui.components.Section;
import gui.components.SvgIcon;
import gui.components.SwitchToggle;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.store.GraphConstraints;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Section "Constraints" of the {@link ConstraintsTab}: the constraints of the active graph in one filterable list, the
 * actions on the selected entries and, for a single entry, a card to edit its values, switch and priority. A constraint
 * that is not applied stays in the list, greyed, with the reason; with "Group by structure" the constraints a layout
 * step made for one {@link ConstraintGroup} form one entry, on which drawing, switch, priority and Delete act together.
 *
 * @author Niklas Gröne
 */
final class ConstraintListSection extends Section {

    private static final long serialVersionUID = 1L;

    /** Filters of the "Show" box, in order. */
    private static final Map<String, Predicate<Constraint>> FILTERS = new LinkedHashMap<>();

    static {
        FILTERS.put("All constraints", c -> true);
        FILTERS.put("Made by you", c -> !c.isAutomatic());
        FILTERS.put("From templates", c -> c.getOrigin() != null);
        FILTERS.put("From the SBGN steps", Constraint::isAutomatic);
        FILTERS.put("With conflicts", c -> ConflictLog.entryFor(c) != null);
        FILTERS.put("Not applied", c -> c.isEnabled() && !c.isActive());
        for (ConstraintKind.Category cat : ConstraintKind.Category.values()) {
            FILTERS.put(ConstraintCatalog.categoryLabel(cat), c -> c.getKind().getCategory() == cat);
        }
    }

    private final DefaultListModel<Constraint> model = new DefaultListModel<>();
    private final JList<Constraint> list = new JList<Constraint>(model) {
        private static final long serialVersionUID = 1L;
        @Override public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
            Constraint c = model.get(i);
            ConstraintGroup group = groupOf(c);
            if (group != null) {
                List<Constraint> ms = membersOf(c);
                StringBuilder sb = new StringBuilder("<b>" + Ui.esc(group.label) + "</b><br>" + ms.size() + " constraints of step "
                        + Ui.esc(group.step) + "; the switch, the priority and Delete act on all of them.");
                int k = 0;
                for (Constraint m : ms) {
                    if (k++ == 8) { sb.append("<br>…"); break; }
                    sb.append("<br>• ").append(Ui.esc(ConstraintCatalog.describe(m))).append(": ").append(Ui.esc(ConstraintCatalog.nodesText(m)));
                }
                return Ui.tooltip(sb.toString());
            }
            ConflictLog.Entry conflict = ConflictLog.entryFor(c);
            ConstraintCatalog.Info info = ConstraintCatalog.info(c);
            return Ui.tooltip("<b>" + Ui.esc(ConstraintCatalog.describe(c)) + "</b><br>" + Ui.esc(ConstraintCatalog.nodesText(c))
                    + (info == null ? "" : "<br><i>Adaptagrams: " + Ui.esc(info.adaptagrams) + "</i>")
                    + (c.isAutomatic() ? "<br>Made by an SBGN step." : "")
                    + (c.getOrigin() == null ? "" : "<br>From template instance <b>" + Ui.esc(c.getOrigin().getInstanceLabel()) + "</b>: "
                            + Ui.esc(c.getOrigin().pathText()) + " (rev. " + c.getOrigin().getTemplateRevision() + ")"
                            + (c.getOrigin().isSwitchedOn() ? "" : "<br>Its instance (or component) is switched off."))
                    + "<br>Priority: " + c.getPriority().label()
                    + (c.isEnabled() ? "" : "<br>Switched off: no solve applies it.")
                    + (conflict == null ? "" : "<br><font color='" + (conflict.kind == ConflictLog.Kind.DEACTIVATED ? "#b06000" : "#c5221f")
                            + "'>" + Ui.esc(conflict.title() + ": " + conflict.reason) + "</font>"));
        }
    };
    private final JComboBox<String> filter = Ui.comboBox(FILTERS.keySet().toArray(new String[0]));
    /** On: the constraints of one structure of a layout step are one entry (see the class comment). */
    private final JCheckBox groupBox = new JCheckBox("Group by structure", true);
    /** The entry that stands for a group → the group's present members (the entry itself first); empty when not grouping. */
    private final Map<Constraint, List<Constraint>> represented = new IdentityHashMap<>();
    private final JButton showAllButton, selectNodesButton, deleteButton, deleteAllButton;
    private final CardPanel editorCard = new CardPanel(new BorderLayout(0, Ui.GAP_M), Ui.GAP_M);
    private final JLabel editorTitle = new JLabel();
    private final VerticalStack editorBody = new VerticalStack();
    /** Why the constraint is not applied (hidden while it is). */
    private final JLabel editorState = Ui.hint(" ", ConstraintsTab.CARD_TEXT_WIDTH);
    private final JPanel editorForm = new JPanel(new BorderLayout());
    /** Collapsed part of the card: on / off, priority, "Prefer this". Made once, so it stays open or closed. */
    private final Section editorAdvanced = Section.collapsible("Advanced", false);
    private final SwitchToggle activeSwitch = new SwitchToggle("Enabled", true);
    private final JLabel activeState = new JLabel();
    private final JComboBox<Priority> priorityBox = PriorityControls.comboBox("constraintPriority");
    private final JButton preferButton = Ui.button("Prefer this",
            "Raise its priority above the constraints it contradicts (if even the highest is not enough, lower theirs), "
                    + "so that it is applied instead of them.", null);
    /** True while the card shows a constraint's state: the controls' listeners must not write it back. */
    private boolean updatingEditor;
    private final Timer resolveTimer;
    /** The constraint whose value edit {@link #resolveTimer} solves. */
    private Constraint resolveEdited;
    private final JLabel emptyHint = Ui.hint(" ");
    /** The constraint the editor card is bound to, or null. */
    private Constraint editing;
    private boolean refreshing;
    /** Runs before this list draws on the graph, so the other list gives up its selection. */
    Runnable beforeDrawing = () -> { };

    ConstraintListSection() {
        super("Constraints");
        list.setName("constraintList");
        list.setCellRenderer(new Renderer());
        list.setFixedCellWidth(1);   // the list takes the visible width; long texts are clipped
        list.setFixedCellHeight(40);
        list.setVisibleRowCount(6);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(list);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) onSelection(); });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selectNodes();
            }
        });
        // Delete / Backspace delete the selected constraints; the key is consumed, so VANTED's own Delete binding
        // (delete the selected graph elements) does not fire as well.
        Action delete = new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(ActionEvent e) { deleteSelected(); }
        };
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteConstraints");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteConstraints");
        list.getActionMap().put("deleteConstraints", delete);
        list.getAccessibleContext().setAccessibleName("Constraints");
        filter.setName("constraintFilter");
        filter.addActionListener(e -> refresh());
        groupBox.setName("groupByStructure");
        groupBox.setOpaque(false);
        groupBox.setFont(Ui.small());
        groupBox.setToolTipText(Ui.tooltip("<b>On</b>: the constraints a layout step made for one structure (the star of a "
                + "process, its sides, its stubs, a ring, ...) are one entry; its switch, priority and Delete act on all of "
                + "them. <b>Off</b>: every constraint is its own entry."));
        groupBox.addActionListener(e -> refresh());

        JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };

        showAllButton = Ui.button("Show all", "Draw every constraint of the list on the graph.", e -> showAll());
        selectNodesButton = Ui.button("Select nodes", "Select the nodes of the selected constraints in the graph (or double-click an entry).",
                e -> selectNodes());
        deleteButton = Ui.button("Delete", "Delete the selected constraints (Delete key).", e -> deleteSelected());
        deleteAllButton = Ui.button("Delete shown…", "Delete every constraint the list shows now.", e -> deleteShown());
        showAllButton.setName("showAll");
        selectNodesButton.setName("selectNodes");
        deleteButton.setName("delete");
        deleteAllButton.setName("deleteShown");

        editorTitle.setFont(Ui.bold());
        editorForm.setOpaque(false);
        editorState.setName("constraintState");
        editorBody.addRow(editorState);
        editorBody.addRow(editorForm, Ui.GAP_S);
        editorBody.addRow(createEditorAdvanced(), Ui.GAP_M);
        editorCard.add(editorTitle, BorderLayout.NORTH);
        editorCard.add(editorBody, BorderLayout.CENTER);
        editorCard.setVisible(false);

        addRow(Ui.labeled("Show", filter));
        addRow(groupBox, Ui.GAP_XS);
        addRow(emptyHint);
        addRow(scroll);
        addRow(Ui.row(showAllButton, selectNodesButton));
        addRow(Ui.row(deleteButton, deleteAllButton), Ui.GAP_S);
        addRow(editorCard, Ui.GAP_M);

        resolveTimer = new Timer(200, e -> resolveEdited());
        resolveTimer.setRepeats(false);
        refresh();
    }

    /** The "Advanced" part of the editor card. */
    private Section createEditorAdvanced() {
        activeSwitch.setName("constraintActive");
        activeSwitch.setToolTipText(Ui.tooltip("<b>On</b>: the constraint takes part in the solves (it is applied unless it "
                + "contradicts constraints of a higher rank). <b>Off</b>: it stays in the list with its settings, but no solve "
                + "applies it and it cannot take the place of another constraint."));
        activeSwitch.addActionListener(e -> {
            if (updatingEditor || editing == null) return;
            for (Constraint m : membersOf(editing)) m.setEnabled(activeSwitch.isSelected());
            afterStateChange();
        });
        priorityBox.setToolTipText(Ui.tooltip(PriorityControls.TOOLTIP));
        priorityBox.addActionListener(e -> {
            if (updatingEditor || editing == null) return;
            Priority p = PriorityControls.selected(priorityBox);
            boolean same = true;
            for (Constraint m : membersOf(editing)) same &= p == m.getPriority();
            if (same) return;
            for (Constraint m : membersOf(editing)) m.setPriority(p);
            afterStateChange();
        });
        preferButton.setName("preferConstraint");
        preferButton.addActionListener(e -> {
            if (editing == null) return;
            Constraint c = editing;
            prefer(c);
            keepShowing(c);
        });
        editorAdvanced.setName("editorAdvanced");
        editorAdvanced.addRow(ConstraintsTab.switchRow("Enabled", activeSwitch, activeState));
        editorAdvanced.addRow(Ui.labeled("Priority", priorityBox), Ui.GAP_S);
        editorAdvanced.addRow(Ui.hint(PriorityControls.RULE, ConstraintsTab.CARD_TEXT_WIDTH), Ui.GAP_XS);
        editorAdvanced.addRow(preferButton, Ui.GAP_S);
        return editorAdvanced;
    }

    /** On / off or the priority changed: solve again (it decides anew what is applied), then show the result. */
    private void afterStateChange() {
        Constraint c = editing;
        AddonTab.executeUserConstraints();
        ListModels.updateAllLists();
        DisplayConstraints.revalidateIndication();
        keepShowing(c);
    }

    /** The card stays on the constraint it changed, even if the filter no longer shows it ("Not applied"). */
    private void keepShowing(Constraint c) {
        if (c == null || ConstraintStore.constraints == null || !ConstraintStore.constraints.getAllConstraints().contains(c)) return;
        if (editing != c || !model.contains(c)) select(Collections.singletonList(c));
    }

    /**
     * "Prefer this" for a constraint of the active graph: priorities so that it ranks above what it contradicts
     * ({@link ConstraintConflicts#prefer}), then a solve applies it.
     */
    static void prefer(Constraint c) {
        ConstraintConflicts.Scope scope = AddonTab.activeScope();
        if (scope == null || ConstraintStore.constraints == null) return;
        ConstraintConflicts.prefer(c, ConstraintStore.constraints.getAllConstraints(), scope);
        AddonTab.executeUserConstraints();
        ListModels.updateAllLists();
        DisplayConstraints.revalidateIndication();
    }

    // ------------------------------------------------------------------ model

    /**
     * Rebuilds the list from the store: the constraints of the active graph that pass the filter. The selection
     * survives by identity; if the edited or a selected constraint is gone (deleted, filtered out, another graph),
     * the editor and the markings follow.
     */
    void refresh() {
        Set<Constraint> selected = new HashSet<>(list.getSelectedValuesList());
        Predicate<Constraint> f = FILTERS.get((String) filter.getSelectedItem());
        Graph active = activeGraph();
        refreshing = true;
        boolean lostSelection;
        try {
            model.clear();
            represented.clear();
            int total = 0;
            if (ConstraintStore.constraints != null && active != null) {
                boolean grouping = groupBox.isSelected();
                // the members of every group of this graph, in store order (the first one stands for the group)
                Map<ConstraintGroup, List<Constraint>> members = new LinkedHashMap<>();
                if (grouping) {
                    for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
                        if (c.getGroup() != null && belongsTo(c, active)) members.computeIfAbsent(c.getGroup(), k -> new ArrayList<>()).add(c);
                    }
                }
                for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
                    if (!belongsTo(c, active)) continue;
                    total++;
                    ConstraintGroup g = grouping ? c.getGroup() : null;
                    if (g != null) {
                        List<Constraint> ms = members.get(g);
                        if (ms == null || ms.get(0) != c) continue;   // not the first member: shown through the first
                        boolean shown = false;
                        for (Constraint m : ms) shown |= f == null || f.test(m) || m == editing;
                        if (!shown) continue;
                        represented.put(c, ms);
                        model.addElement(c);
                        continue;
                    }
                    // the constraint being edited stays, so a change that takes it out of the filter keeps its card
                    if (f == null || f.test(c) || c == editing) model.addElement(c);
                }
            }
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) if (selected.contains(model.get(i))) keep.add(i);
            int[] ix = new int[keep.size()];
            for (int i = 0; i < ix.length; i++) ix[i] = keep.get(i);
            list.setSelectedIndices(ix);
            lostSelection = keep.size() < selected.size() || (editing != null && !model.contains(editing));
            setTrailingText(represented.isEmpty() ? (model.size() == total ? String.valueOf(total) : model.size() + " of " + total)
                    : model.size() + " entries · " + total);
            list.setVisibleRowCount(Math.max(2, Math.min(6, model.size())));
            emptyHint.setVisible(model.isEmpty());
            emptyHint.setText(Ui.wrapped(total == 0 ? "No constraints yet: choose one above and add it to selected nodes."
                    : "No constraint matches the filter.", Ui.TEXT_WIDTH));
        } finally {
            refreshing = false;
        }
        if (lostSelection) onSelection();   // editor and markings follow the remaining selection
        else updateEditorState();            // a solve may have changed whether it is applied
        updateActions();
        list.repaint();
    }

    private static Graph activeGraph() {
        EditorSession session = ConstraintsTab.activeSession();
        return session == null ? null : session.getGraph();
    }

    /** True if every node of {@code c} is in {@code g}. */
    static boolean belongsTo(Constraint c, Graph g) {
        if (c.getNodes() == null || c.getNodes().isEmpty()) return false;
        for (Node n : c.getNodes()) if (!g.containsNode(n)) return false;
        return true;
    }

    /** The constraints an entry stands for: the members of its group, or the constraint itself. */
    private List<Constraint> membersOf(Constraint entry) {
        List<Constraint> ms = represented.get(entry);
        return ms == null ? Collections.singletonList(entry) : ms;
    }

    /** The group an entry stands for, or null. */
    private ConstraintGroup groupOf(Constraint entry) {
        return represented.containsKey(entry) ? entry.getGroup() : null;
    }

    /** The entry that shows {@code c}: itself, or the first member of its group while grouping. */
    private Constraint entryFor(Constraint c) {
        if (c == null || model.contains(c)) return c;
        for (Map.Entry<Constraint, List<Constraint>> e : represented.entrySet()) if (e.getValue().contains(c)) return e.getKey();
        return c;
    }

    /** Selects these constraints in the list (showing all entries if the filter hides one). */
    void select(List<Constraint> cs) {
        for (Constraint c : cs) {
            if (!model.contains(entryFor(c))) {
                filter.setSelectedIndex(0);
                break;
            }
        }
        List<Integer> ix = new ArrayList<>();
        for (Constraint c : cs) {
            int i = model.indexOf(entryFor(c));
            if (i >= 0 && !ix.contains(i)) ix.add(i);
        }
        int[] a = new int[ix.size()];
        for (int i = 0; i < a.length; i++) a[i] = ix.get(i);
        list.setSelectedIndices(a);
        if (a.length > 0) list.ensureIndexIsVisible(a[0]);
    }

    // ------------------------------------------------------------------ actions

    private void onSelection() {
        List<Constraint> sel = list.getSelectedValuesList();
        if (!sel.isEmpty()) beforeDrawing.run();
        DisplayConstraints.clearMarkings();
        for (Constraint c : sel) for (Constraint m : membersOf(c)) DisplayConstraints.drawConstraint(m);
        updateActions();
        buildEditor(sel.size() == 1 ? sel.get(0) : null);
    }

    /** Drops the selection (the other list is about to draw); the editor card closes. */
    void clearSelection() {
        list.clearSelection();
    }

    private void updateActions() {
        boolean any = !list.getSelectedValuesList().isEmpty();
        selectNodesButton.setEnabled(any);
        deleteButton.setEnabled(any);
        showAllButton.setEnabled(model.size() > 0);
        deleteAllButton.setEnabled(model.size() > 0);
    }

    private void showAll() {
        beforeDrawing.run();
        DisplayConstraints.clearMarkings();
        for (int i = 0; i < model.size(); i++) for (Constraint m : membersOf(model.get(i))) DisplayConstraints.drawConstraint(m);
    }

    private void selectNodes() {
        List<Constraint> sel = list.getSelectedValuesList();
        if (sel.isEmpty()) return;
        EditorSession session = ConstraintsTab.activeSession();
        if (session == null) return;
        List<Node> nodes = new ArrayList<>();
        for (Constraint c : sel) {
            for (Constraint m : membersOf(c)) for (Node n : m.getNodes()) if (n.getGraph() == session.getGraph() && !nodes.contains(n)) nodes.add(n);
        }
        GraphHelper.selectNodes(true, nodes);
    }

    private void deleteSelected() {
        List<Constraint> sel = new ArrayList<>();
        for (Constraint c : list.getSelectedValuesList()) sel.addAll(membersOf(c));
        if (sel.isEmpty() || ConstraintStore.constraints == null) return;
        for (Constraint c : sel) ConstraintStore.constraints.removeConstraint(c);
        afterDelete(ConflictLog.forget(sel));
    }

    private void deleteShown() {
        if (ConstraintStore.constraints == null || model.isEmpty()) return;
        List<Constraint> gone = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) gone.addAll(membersOf(model.get(i)));
        int n = gone.size();
        int ok = JOptionPane.showConfirmDialog(this, "Delete " + n + (n == 1 ? " constraint?" : " constraints?"),
                "Delete constraints", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (Constraint c : gone) ConstraintStore.constraints.removeConstraint(c);
        afterDelete(ConflictLog.forget(gone));
    }

    /**
     * {@code freed}: a deleted constraint was what kept others from being applied; solve again, so they are applied
     * right away (without that, they would wait for the next change).
     */
    private void afterDelete(boolean freed) {
        list.clearSelection();
        DisplayConstraints.clearMarkings();
        ListModels.updateAllLists();
        if (freed) {
            AddonTab.executeUserConstraints();
            ListModels.updateAllLists();
        }
    }

    // ------------------------------------------------------------------ editor

    /**
     * The editor card for one constraint (hidden for none or several): why it is not applied, its values (if its kind
     * has editable ones) and the "Advanced" part.
     */
    private void buildEditor(Constraint c) {
        editorForm.removeAll();
        editing = c;
        if (c == null) {
            editorCard.setVisible(false);
            revalidate();
            return;
        }
        ConstraintCatalog.Info info = ConstraintCatalog.info(c);
        ConstraintGroup group = groupOf(c);
        // a group's card has no values: its switch and priority act on all members (Advanced)
        List<ConstraintCatalog.Editable> editables = info == null || group != null ? new ArrayList<>() : info.editablesFor(c);
        editorTitle.setText("Edit: " + (group != null ? group.label : info == null ? ConstraintCatalog.describe(c) : info.title));
        editorForm.setVisible(!editables.isEmpty());
        updateEditorState();
        editorCard.setVisible(true);
        if (editables.isEmpty()) {
            revalidate();
            repaint();
            return;
        }
        List<ConstraintCatalog.Param> params = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConstraintCatalog.Editable e : editables) {
            params.add(e.param);
            values.put(e.param.key, e.get.apply(c));
        }
        final ConstraintParamForm[] form = new ConstraintParamForm[1];
        final Map<String, Object> written = new LinkedHashMap<>(values);
        form[0] = new ConstraintParamForm(params, values, () -> {
            // write back only what the user changed, so an untouched value is never rounded or replaced
            Map<String, Object> v = form[0].values();
            for (ConstraintCatalog.Editable e : editables) {
                Object value = v.get(e.param.key);
                if (value == null || (value instanceof Double && Double.isNaN((Double) value))) continue;
                Object before = written.get(e.param.key);
                if (before instanceof Number && value instanceof Number
                        && Math.abs(((Number) before).doubleValue() - ((Number) value).doubleValue()) < 1e-9) continue;
                if (value.equals(before)) continue;
                e.set.accept(c, value);
                written.put(e.param.key, value);
            }
            list.repaint();
            DisplayConstraints.revalidateIndication();
            if (resolveEdited != null && graphOf(resolveEdited) != graphOf(c)) resolveEdited();
            resolveEdited = c;
            resolveTimer.restart();
        });
        editorForm.add(form[0], BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /**
     * The solve after a value edit, of the edited constraint's graph: with its own store if the user has gone to
     * another graph meanwhile (its decisions then show once it is active again).
     */
    private void resolveEdited() {
        resolveTimer.stop();
        Constraint c = resolveEdited;
        resolveEdited = null;
        Graph g = graphOf(c);
        if (g == null) return;
        if (g == ConstraintStore.activeGraph()) {
            CBLayoutAlgorithm.executeUserConstraints(g, ConstraintsTab.isForceDirected(),
                    ConstraintsTab.isAvoidOverlaps());
            ListModels.updateAllLists();   // the edited values reach the store saved with the graph
            return;
        }
        Constraints store = ConstraintStore.of(g);
        if (!store.getAllConstraints().contains(c)) return;   // deleted, or its graph was closed
        CBLayoutAlgorithm.solveUserConstraints(g, store.getAllConstraints(), ConstraintsTab.isForceDirected(),
                ConstraintsTab.isAvoidOverlaps(), false);
        GraphConstraints.sync(g, store);
    }

    private static Graph graphOf(Constraint c) {
        return c == null || c.getNodes() == null || c.getNodes().isEmpty() ? null : c.getNodes().get(0).getGraph();
    }

    /** The card follows the constraint's state: the reason it is not applied, on / off, priority, "Prefer this". */
    private void updateEditorState() {
        Constraint c = editing;
        if (c == null) return;
        ConstraintGroup group = groupOf(c);
        if (group != null) {
            updateGroupEditorState(group, membersOf(c));
            return;
        }
        updatingEditor = true;
        try {
            if (activeSwitch.isSelected() != c.isSwitchedOnItself()) activeSwitch.setSelected(c.isSwitchedOnItself());
            ConstraintsTab.showState(activeState, c.isEnabled());
            if (priorityBox.getSelectedItem() != c.getPriority()) priorityBox.setSelectedItem(c.getPriority());
        } finally {
            updatingEditor = false;
        }
        Deactivation d = c.getDeactivation();
        String state = c.isSwitchedOnItself() && !c.isEnabled() ? "Its template instance " + (c.getOrigin() == null ? "" : "'" + c.getOrigin().getInstanceLabel() + "' ")
                + "(or a component of it) is switched off; switch it on on the Templates page. Its own switch stays as it is."
                : !c.isEnabled() ? "Switched off: no solve applies it until you switch it on again under Advanced."
                : d == null ? null
                : d.contradictsItself() ? "Not applied. " + d.reason()
                : "Not applied. " + d.reason() + " It keeps its settings; to apply it, raise its priority or use Prefer this "
                        + "under Advanced.";
        editorState.setVisible(state != null);
        if (state != null) {
            editorState.setText(Ui.wrapped(Ui.esc(state), ConstraintsTab.CARD_TEXT_WIDTH));
            editorState.setForeground(c.isEnabled() ? Ui.WARNING : Ui.MUTED);
        }
        preferButton.setVisible(c.isEnabled() && d != null && !d.contradictsItself());
        editorAdvanced.setTrailingText(c.getPriority().label() + (c.isEnabled() ? "" : " · off"));
        editorCard.revalidate();
    }

    /** The card of a group entry: the switch and the priority of all members, and how many are applied. */
    private void updateGroupEditorState(ConstraintGroup group, List<Constraint> ms) {
        int on = 0, notApplied = 0, conflicts = 0;
        Priority shared = null;
        boolean mixed = false;
        for (Constraint m : ms) {
            if (m.isSwitchedOnItself()) on++;
            if (m.isEnabled() && m.getDeactivation() != null) notApplied++;
            ConflictLog.Entry e = ConflictLog.entryFor(m);
            if (e != null && e.kind != ConflictLog.Kind.DEACTIVATED) conflicts++;
            if (shared == null) shared = m.getPriority();
            else if (shared != m.getPriority()) mixed = true;
        }
        updatingEditor = true;
        try {
            boolean allOn = on == ms.size();
            if (activeSwitch.isSelected() != allOn) activeSwitch.setSelected(allOn);
            ConstraintsTab.showState(activeState, allOn);
            if (on > 0 && !allOn) activeState.setText(on + " of " + ms.size() + " on");
            if (!mixed && shared != null && priorityBox.getSelectedItem() != shared) priorityBox.setSelectedItem(shared);
        } finally {
            updatingEditor = false;
        }
        StringBuilder sb = new StringBuilder(ms.size() + (ms.size() == 1 ? " constraint" : " constraints") + " of step " + group.step + ".");
        if (on < ms.size()) sb.append(' ').append(ms.size() - on).append(" switched off.");
        if (notApplied > 0) sb.append(' ').append(notApplied).append(" not applied (conflict).");
        if (conflicts > 0) sb.append(' ').append(conflicts).append(" dropped or not satisfied.");
        if (mixed) sb.append(" Priorities differ; choosing one sets it for all.");
        sb.append(" The switch and the priority act on all of them.");
        editorState.setVisible(true);
        editorState.setText(Ui.wrapped(Ui.esc(sb.toString()), ConstraintsTab.CARD_TEXT_WIDTH));
        editorState.setForeground(notApplied > 0 || conflicts > 0 ? Ui.WARNING : Ui.MUTED);
        preferButton.setVisible(false);
        editorAdvanced.setTrailingText((mixed ? "mixed" : shared == null ? "" : shared.label()) + (on == ms.size() ? "" : on == 0 ? " · off" : " · partly off"));
        editorCard.revalidate();
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Two-line cell: pictogram, description and nodes, a marker for conflicts, a priority other than "Normal" and
     * constraints of the SBGN steps. A constraint that is not applied is greyed and shows why instead of its nodes.
     */
    private final class Renderer extends JPanel implements ListCellRenderer<Constraint> {
        private static final long serialVersionUID = 1L;
        private final JLabel icon = new JLabel();
        private final JLabel head = new JLabel();
        private final JLabel body = new JLabel();
        private final JLabel mark = new JLabel();

        Renderer() {
            super(new BorderLayout(Ui.GAP_M, 0));
            setBorder(new EmptyBorder(3, 6, 3, 6));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            head.setFont(Ui.font().deriveFont(Font.BOLD, Ui.small().getSize2D() + 1));
            body.setFont(Ui.small());
            text.add(head);
            text.add(body);
            mark.setFont(Ui.small().deriveFont(Font.BOLD));
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(mark, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Constraint> l, Constraint c, int index,
                                                      boolean selected, boolean focus) {
            ConstraintGroup group = groupOf(c);
            if (group != null) return groupCell(l, c, group, membersOf(c), index, selected);
            ConstraintCatalog.Info info = ConstraintCatalog.info(c);
            boolean off = !c.isEnabled();
            Deactivation notApplied = off ? null : c.getDeactivation();
            icon.setIcon(info == null ? null : SvgIcon.get(info.icon, 24, (off || notApplied != null) && !selected));
            head.setText(ConstraintCatalog.describe(c));
            String from = c.getOrigin() == null ? "" : c.getOrigin().getInstanceLabel() + " · ";
            body.setText(off ? (c.isSwitchedOnItself() ? "Instance off · " : "Switched off · ") + from + ConstraintCatalog.nodesText(c)
                    : notApplied != null ? from + notApplied.shortReason() : from + ConstraintCatalog.nodesText(c));
            ConflictLog.Entry conflict = ConflictLog.entryFor(c);
            if (off) {
                mark.setText("OFF");
                mark.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            } else if (conflict != null) {
                mark.setText("●");
                mark.setForeground(selected ? l.getSelectionForeground() : ConflictPanel.colorOf(conflict.kind));
            } else if (c.getPriority() != Priority.NORMAL) {
                // a priority other than the default, so it can be seen without opening the card
                mark.setText(c.getPriority().label());
                mark.setForeground(selected ? l.getSelectionForeground()
                        : c.getPriority().compareTo(Priority.NORMAL) > 0 ? Ui.ACCENT : Ui.MUTED);
            } else if (c.isAutomatic()) {
                mark.setText("SBGN");
                mark.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            } else {
                mark.setText("");
            }
            setBackground(selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            head.setForeground(selected ? l.getSelectionForeground() : off || notApplied != null ? Ui.MUTED
                    : conflict != null && conflict.kind != ConflictLog.Kind.WARNING ? Ui.ERROR : Ui.TEXT);
            body.setForeground(selected ? l.getSelectionForeground() : notApplied != null ? Ui.WARNING : Ui.MUTED);
            setOpaque(true);
            return this;
        }

        /** One entry for a structure: its label, how many constraints it holds and how many of them are not applied or off. */
        private Component groupCell(JList<? extends Constraint> l, Constraint first, ConstraintGroup group, List<Constraint> ms,
                                    int index, boolean selected) {
            int on = 0, notApplied = 0, conflicts = 0;
            for (Constraint m : ms) {
                if (m.isEnabled()) on++;
                if (m.isEnabled() && m.getDeactivation() != null) notApplied++;
                ConflictLog.Entry e = ConflictLog.entryFor(m);
                if (e != null && e.kind != ConflictLog.Kind.DEACTIVATED) conflicts++;
            }
            boolean allOff = on == 0;
            ConstraintCatalog.Info info = ConstraintCatalog.info(first);
            icon.setIcon(info == null ? null : SvgIcon.get(info.icon, 24, allOff && !selected));
            head.setText(group.label);
            StringBuilder sb = new StringBuilder(ms.size() + (ms.size() == 1 ? " constraint" : " constraints"));
            if (allOff) sb.append(" · switched off");
            else if (on < ms.size()) sb.append(" · ").append(ms.size() - on).append(" off");
            if (notApplied > 0) sb.append(" · ").append(notApplied).append(" not applied");
            if (conflicts > 0) sb.append(" · ").append(conflicts).append(" with conflicts");
            body.setText(sb.toString());
            mark.setText(allOff ? "OFF" : conflicts > 0 || notApplied > 0 ? "●" : "SBGN");
            mark.setForeground(selected ? l.getSelectionForeground() : allOff ? Ui.MUTED : conflicts > 0 ? ConflictPanel.UNSATISFIED_COLOR
                    : notApplied > 0 ? ConflictPanel.DEACTIVATED_COLOR : Ui.MUTED);
            setBackground(selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            head.setForeground(selected ? l.getSelectionForeground() : allOff ? Ui.MUTED : Ui.TEXT);
            body.setForeground(selected ? l.getSelectionForeground() : notApplied > 0 || conflicts > 0 ? Ui.WARNING : Ui.MUTED);
            setOpaque(true);
            return this;
        }
    }
}
