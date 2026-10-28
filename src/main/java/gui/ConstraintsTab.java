package gui;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.ConstraintStore;
import constraints.Deactivation;
import constraints.Priority;
import layout.store.GraphConstraints;
import gui.components.CardPanel;
import gui.components.PrimaryButton;
import gui.components.ScrollColumn;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.SvgIcon;
import gui.components.SwitchToggle;
import gui.components.TilePalette;
import gui.components.Ui;
import gui.components.VerticalStack;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.selection.Selection;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main tab "Constraints": the solver switches, the palette and settings card that add a constraint to the selected
 * nodes, the constraints of the active graph, what the last Mental Map run applied (read only) and the conflicts.
 * After adding, and after every change, the constraints are solved on the active graph
 * ({@link AddonTab#executeUserConstraints()}).
 *
 * @author Niklas Gröne
 */
public class ConstraintsTab extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Wrap width of texts inside a settings card (the column minus the card's padding and border). */
    static final int CARD_TEXT_WIDTH = Ui.TEXT_WIDTH - 2 * (Ui.GAP_M + 1) - 4;

    private static ConstraintsTab instance;

    /** The force-directed mode of the constraint solve (off by default). */
    private static boolean forceDirected;

    /** True: the constraints are solved in a constrained force-directed run along the edges; false: only projected. */
    public static boolean isForceDirected() {
        return forceDirected;
    }

    public static void setForceDirected(boolean on) {
        forceDirected = on;
        if (instance != null) instance.showSolver();
    }

    /** Node overlap removal in the constraint solves (off by default: it compares every pair of nodes). */
    private static boolean avoidOverlaps;

    /**
     * True: the constraint solves (this tab, drags, templates) also push overlapping nodes apart; false: no non-overlap
     * constraint is made. The SBGN and Mental Map layouts keep their own overlap removal either way.
     */
    public static boolean isAvoidOverlaps() {
        return avoidOverlaps;
    }

    public static void setAvoidOverlaps(boolean on) {
        avoidOverlaps = on;
        if (instance != null) instance.showSolver();
    }

    /** Refreshes the constraint list and the counters (the store changed). */
    static void refreshList() {
        if (instance != null) instance.refreshAll();
    }

    /** A Mental Map run (or the timeline) changed what the "Mental map" section shows. */
    static void mentalMapRunChanged() {
        if (instance != null) instance.mentalMapSection.refresh();
    }

    /** The selection of the active graph changed: update what the Add button would act on. */
    static void graphSelectionChanged() {
        if (instance != null) instance.updateSelection();
    }

    private final SwitchToggle forceSwitch = new SwitchToggle("Force-directed", false);
    private final JLabel forceState = new JLabel();
    private final JLabel forceHint = Ui.hint(" ");
    private final SwitchToggle overlapSwitch = new SwitchToggle("Avoid overlaps", false);
    private final JLabel overlapState = new JLabel();
    private final JLabel overlapHint = Ui.hint(" ");

    private final TilePalette<ConstraintKind.Category> categories = new TilePalette<>(TilePalette.Style.CHIP, 3);
    private final TilePalette<ConstraintKind> kinds = new TilePalette<>(TilePalette.Style.TILE, 3);
    private final Map<ConstraintKind.Category, ConstraintKind> lastKind = new EnumMap<>(ConstraintKind.Category.class);
    private final Map<ConstraintKind, Map<String, Object>> lastValues = new EnumMap<>(ConstraintKind.class);

    private final JLabel kindTitle = new JLabel();
    private final JLabel kindDescription = Ui.hint(" ", CARD_TEXT_WIDTH);
    private final JLabel kindAdaptagrams = Ui.hint(" ", CARD_TEXT_WIDTH);
    private final JPanel formHolder = new JPanel(new BorderLayout());
    private ConstraintParamForm form;
    private ConstraintKind currentKind;
    private final Section addAdvanced = Section.collapsible("Advanced", false);
    private final JComboBox<Priority> newPriority = PriorityControls.comboBox("newPriority");
    private final StatusLabel selectionStatus = new StatusLabel(" ");
    private final PrimaryButton addButton = new PrimaryButton("Add");
    private final StatusLabel addStatus = new StatusLabel(" ");
    /** Under the status: whether what was added is applied, or what it pushed out; follows every later change. */
    private final JLabel addNote = Ui.hint(" ");
    /** The constraints of the last Add, and the ones applied just before it (for {@link #addNote}). */
    private List<Constraint> lastAdded = Collections.emptyList();
    private Set<Constraint> appliedBeforeAdd = Collections.emptySet();

    private final ConstraintListSection listSection = new ConstraintListSection();
    private final MentalMapConstraintSection mentalMapSection = new MentalMapConstraintSection();
    private final Section conflictSection = Section.collapsible("Conflicts", false);
    private final ConflictPanel conflictPanel = new ConflictPanel();

    public ConstraintsTab() {
        super(new BorderLayout());
        instance = this;

        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_XL));
        column.addRow(createSolverSection());
        column.addRow(createAddSection(), Ui.SECTION_GAP);
        column.addRow(listSection, Ui.SECTION_GAP);
        column.addRow(mentalMapSection, Ui.SECTION_GAP);
        column.addRow(createConflictSection(), Ui.SECTION_GAP);
        add(new ScrollColumn(column, Ui.COLUMN_WIDTH), BorderLayout.CENTER);
        // The two constraint lists draw their selection on the graph; one draws at a time.
        listSection.beforeDrawing = mentalMapSection::clearSelection;
        mentalMapSection.beforeDrawing = listSection::clearSelection;

        showSolver();
        ConflictLog.addListener(this::updateConflictCount);
        updateConflictCount();
        updateSelection();
    }

    // ------------------------------------------------------------------ solver

    private Section createSolverSection() {
        Section section = new Section("Solver");
        forceSwitch.setName("forceDirected");
        forceSwitch.setToolTipText(Ui.tooltip("<b>On</b>: the constraints are solved in a force-directed layout: edges pull "
                + "like springs, so the drawing may change a lot. <b>Off</b>: nodes move only as far as the constraints "
                + "(and, if it is on, the overlap removal) need."));
        forceSwitch.addActionListener(e -> {
            setForceDirected(forceSwitch.isSelected());
            // Re-solve right away only if the active graph has constraints: without any, a force-directed run would
            // re-lay out the whole drawing just because the mode was chosen.
            if (hasConstraintsOnActiveGraph()) AddonTab.executeUserConstraints();
        });
        overlapSwitch.setName("avoidOverlaps");
        overlapSwitch.setToolTipText(Ui.tooltip("<b>On</b>: every constraint solve (this tab, dragging, templates) also pushes "
                + "overlapping nodes apart. It compares every pair of nodes, so it gets slow on large maps. <b>Off</b>: nodes "
                + "may overlap and no time is spent on it; clusters still keep other nodes out. The SBGN and Mental Map "
                + "layouts keep their own overlap removal either way."));
        overlapSwitch.addActionListener(e -> {
            setAvoidOverlaps(overlapSwitch.isSelected());
            if (hasConstraintsOnActiveGraph()) AddonTab.executeUserConstraints();
        });

        JButton resolve = Ui.button("Apply constraints", "Run the loop on the active graph now: decide the conflicts, "
                + "solve, check and show. Use it after a VANTED layout algorithm (Layout menu) to project its result onto "
                + "the constraints; also in the Layout menu as \"Apply constraints\".",
                e -> AddonTab.applyConstraints());
        resolve.setName("resolve");

        section.addRow(switchRow("Force-directed", forceSwitch, forceState));
        section.addRow(forceHint, Ui.GAP_XS);
        section.addRow(switchRow("Avoid overlaps", overlapSwitch, overlapState), Ui.GAP_M);
        section.addRow(overlapHint, Ui.GAP_XS);
        JButton save = Ui.button("Save constraints\u2026", "Writes the constraints of the active graph into a file next to its map "
                + "(<map>" + GraphConstraints.COMPANION_EXT + "). A map saved as GML or GraphML carries its constraints inside the file; "
                + "SBGN-ML does not, so save them here: the file is read with the map.",
                e -> ConstraintFiles.save(this));
        save.setName("saveConstraints");
        JButton load = Ui.button("Load constraints\u2026", "Reads a constraints file into the active graph: the constraints are "
                + "added and decided, no node moves until \"Apply constraints\".",
                e -> ConstraintFiles.load(this));
        load.setName("loadConstraints");
        JPanel files = new JPanel(new GridLayout(1, 2, Ui.GAP_S, 0));
        files.setOpaque(false);
        files.add(save);
        files.add(load);

        section.addRow(resolve);
        section.addRow(files, Ui.GAP_S);
        return section;
    }

    /** Bold name on the left; state text and switch on the right. */
    public static JPanel switchRow(String text, SwitchToggle toggle, JLabel state) {
        JLabel name = new JLabel(text);
        name.setFont(Ui.bold());
        name.setLabelFor(toggle);
        state.setFont(Ui.small().deriveFont(Font.BOLD));

        JPanel right = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        right.setOpaque(false);
        right.add(state, BorderLayout.WEST);
        right.add(toggle, BorderLayout.EAST);
        JPanel row = new JPanel(new BorderLayout(Ui.GAP_M, 0));
        row.setOpaque(false);
        row.add(name, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** The state text of a switch row. */
    public static void showState(JLabel state, boolean on) {
        state.setText(on ? "On" : "Off");
        state.setForeground(on ? Ui.ACCENT : Ui.MUTED);
    }

    /** Switches, state texts and hints follow {@link #forceDirected} and {@link #avoidOverlaps}. */
    private void showSolver() {
        if (forceSwitch.isSelected() != forceDirected) forceSwitch.setSelected(forceDirected);
        showState(forceState, forceDirected);
        forceHint.setText(Ui.wrapped(forceDirected
                ? "Edges pull like springs while the constraints are solved; the drawing may change a lot."
                : avoidOverlaps ? "Nodes move only as far as the constraints and the overlap removal need."
                : "Nodes move only as far as the constraints need.", Ui.TEXT_WIDTH));
        if (overlapSwitch.isSelected() != avoidOverlaps) overlapSwitch.setSelected(avoidOverlaps);
        showState(overlapState, avoidOverlaps);
        overlapHint.setText(Ui.wrapped(avoidOverlaps
                ? "Overlapping nodes are pushed apart. Slow on large maps: every pair of nodes is compared."
                : "Nodes may overlap; no time is spent on it. SBGN and Mental Map keep their own overlap removal.",
                Ui.TEXT_WIDTH));
    }

    // ------------------------------------------------------------------ add

    private Section createAddSection() {
        Section section = new Section("Add constraint");
        for (ConstraintKind.Category c : ConstraintKind.Category.values()) {
            categories.addTile(c, null, ConstraintCatalog.categoryLabel(c), ConstraintCatalog.categoryTooltip(c));
        }
        categories.setName("categories");
        kinds.setName("kinds");
        categories.addSelectionListener(this::showCategory);
        kinds.addSelectionListener(this::showKind);

        kindTitle.setFont(Ui.bold());
        formHolder.setOpaque(false);
        addButton.setName("add");
        addButton.addActionListener(e -> addConstraint());

        CardPanel card = new CardPanel(new BorderLayout(), Ui.GAP_M);
        VerticalStack body = new VerticalStack();
        body.addRow(kindTitle);
        body.addRow(kindDescription, Ui.GAP_XS);
        body.addRow(kindAdaptagrams, Ui.GAP_XS);
        body.addRow(formHolder, Ui.GAP_M);
        body.addRow(createAddAdvanced(), Ui.GAP_M);
        body.addRow(selectionStatus, Ui.GAP_M);
        body.addRow(addButton, Ui.GAP_S);
        card.add(body, BorderLayout.CENTER);

        section.addRow(categories);
        section.addRow(kinds, Ui.GAP_M);
        section.addRow(card, Ui.GAP_M);
        section.addRow(addStatus, Ui.GAP_S);
        section.addRow(addNote, Ui.GAP_XS);
        addStatus.setVisible(false);
        addNote.setName("addNote");
        addNote.setVisible(false);
        showCategory(categories.getSelectedValue());
        return section;
    }

    /** Collapsed "Advanced" part of the settings card: the priority the new constraints get. */
    private Section createAddAdvanced() {
        newPriority.setToolTipText(Ui.tooltip(PriorityControls.TOOLTIP));
        newPriority.addActionListener(e -> addAdvanced.setTrailingText(PriorityControls.selected(newPriority).label()));
        addAdvanced.setName("addAdvanced");
        addAdvanced.addRow(Ui.labeled("Priority", newPriority));
        addAdvanced.addRow(Ui.hint(PriorityControls.RULE, CARD_TEXT_WIDTH), Ui.GAP_XS);
        addAdvanced.setTrailingText(Priority.NORMAL.label());
        return addAdvanced;
    }

    private void showCategory(ConstraintKind.Category category) {
        if (category == null) return;
        ConstraintKind last = lastKind.get(category);   // read first: refilling the tiles announces the first kind
        kinds.clearTiles();
        for (ConstraintKind k : ConstraintCatalog.kinds(category)) {
            ConstraintCatalog.Info info = ConstraintCatalog.info(k);
            kinds.addTile(k, SvgIcon.get(info.icon, 32), info.label, "<b>" + info.title + "</b><br>" + info.description);
        }
        if (last != null) kinds.setSelectedValue(last);
        showKind(kinds.getSelectedValue());
        revalidate();
        repaint();
    }

    private void showKind(ConstraintKind kind) {
        if (kind == null) return;
        if (form != null) lastValues.put(currentKind, form.values());
        lastKind.put(kind.getCategory(), kind);
        ConstraintCatalog.Info info = ConstraintCatalog.info(kind);
        kindTitle.setText(info.title);
        kindTitle.setIcon(SvgIcon.get(info.icon, 20));
        kindTitle.setIconTextGap(Ui.GAP_M);
        kindDescription.setText(Ui.wrapped(Ui.esc(info.description), CARD_TEXT_WIDTH));
        kindAdaptagrams.setText(Ui.wrapped("Adaptagrams: " + Ui.esc(info.adaptagrams), CARD_TEXT_WIDTH));
        formHolder.removeAll();
        form = new ConstraintParamForm(info.params, lastValues.get(kind), null);
        formHolder.add(form, BorderLayout.CENTER);
        formHolder.setVisible(!form.isEmpty());
        currentKind = kind;
        updateSelection();
        revalidate();
        repaint();
    }

    /** What the Add button would act on: the selection of the active graph, checked against the chosen kind. */
    private void updateSelection() {
        if (currentKind == null) return;
        ConstraintCatalog.Info info = ConstraintCatalog.info(currentKind);
        ConstraintCatalog.Selection sel = currentSelection();
        String problem = info.problem(sel);
        if (problem == null) {
            selectionStatus.setStatus(info.selectionText(sel), StatusLabel.Kind.SUCCESS);
            addButton.setEnabled(true);
            int n = info.needsEdges() ? sel.edgesOrEdgesBetweenNodes().size() : sel.nodes.size();
            addButton.setText(info.needsEdges() ? "Add to " + n + (n == 1 ? " edge" : " edges")
                    : "Add to " + n + (n == 1 ? " node" : " nodes"));
        } else {
            selectionStatus.setStatus(problem, StatusLabel.Kind.ERROR);
            addButton.setEnabled(false);
            addButton.setText("Add");
        }
        addButton.repaint();
    }

    /** The active graph's selection, or an empty one. */
    private static ConstraintCatalog.Selection currentSelection() {
        EditorSession session = activeSession();
        if (session == null || session.getGraph() == null) return new ConstraintCatalog.Selection(null, Collections.emptyList(), Collections.emptyList());
        Graph g = session.getGraph();
        List<Node> nodes = GraphHelper.getSelectedNodes(session);
        List<Edge> edges = new ArrayList<>();
        Selection s = session.getSelectionModel() == null ? null : session.getSelectionModel().getActiveSelection();
        if (s != null) edges.addAll(s.getEdges());
        return new ConstraintCatalog.Selection(g, nodes, edges);
    }

    private void addConstraint() {
        if (currentKind == null || ConstraintStore.constraints == null) return;
        ConstraintCatalog.Info info = ConstraintCatalog.info(currentKind);
        List<Constraint> made;
        try {
            made = info.create(currentSelection(), form == null ? null : form.values());
        } catch (IllegalArgumentException ex) {
            addStatus.setStatus(ex.getMessage(), StatusLabel.Kind.ERROR);
            addStatus.setVisible(true);
            updateSelection();
            return;
        }
        Priority priority = PriorityControls.selected(newPriority);
        Set<Constraint> applied = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) if (c.isActive()) applied.add(c);
        for (Constraint c : made) {
            c.setPriority(priority);
            ConstraintStore.constraints.addConstraint(c);
        }
        ListModels.updateAllLists();
        listSection.select(made);   // shows them on the graph
        AddonTab.executeUserConstraints();
        addStatus.setStatus("Added: " + info.title + (made.size() > 1 ? " (" + made.size() + "×)" : "") + ".",
                StatusLabel.Kind.SUCCESS);
        addStatus.setVisible(true);
        lastAdded = made;
        appliedBeforeAdd = applied;
        refreshAll();   // the note under the status and the editor card show the new state
        lastValues.put(currentKind, form == null ? null : form.values());
    }

    // ------------------------------------------------------------------ list and conflicts

    private Section createConflictSection() {
        conflictSection.addRow(conflictPanel);
        return conflictSection;
    }

    private void updateConflictCount() {
        int n = ConflictLog.entries().size();
        conflictSection.setTrailingText(n == 0 ? "none" : String.valueOf(n));
    }

    /** True if a constraint of the store that is switched on has all its nodes in the active graph. */
    private static boolean hasConstraintsOnActiveGraph() {
        EditorSession session = activeSession();
        if (session == null || session.getGraph() == null || ConstraintStore.constraints == null) return false;
        Graph g = session.getGraph();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (c.isEnabled() && ConstraintListSection.belongsTo(c, g)) return true;
        }
        return false;
    }

    /** The active editor session of VANTED, or null. */
    static EditorSession activeSession() {
        return MainFrame.getInstance() == null ? null : MainFrame.getInstance().getActiveEditorSession();
    }

    private void refreshAll() {
        listSection.refresh();
        mentalMapSection.refresh();
        updateConflictCount();
        updateSelection();
        updateAddNote();
        conflictPanel.updateButtons();   // "Prefer this" depends on the active graph
    }

    /**
     * The note under the Add status, from the current state: a constraint just added that is not applied says why (it
     * contradicts constraints ranked above it); one that took the place of constraints applied before says how many.
     * Later changes (priority, Prefer this, delete) update it, so it never describes an old state.
     */
    private void updateAddNote() {
        String text = null;
        if (!lastAdded.isEmpty() && ConstraintStore.constraints != null) {
            List<Constraint> all = ConstraintStore.constraints.getAllConstraints();
            Constraint off = null;
            int present = 0, notApplied = 0;
            for (Constraint c : lastAdded) {
                if (!all.contains(c)) continue;
                present++;
                if (c.isEnabled() && c.getDeactivation() != null) {
                    notApplied++;
                    if (off == null) off = c;
                }
            }
            if (off != null) {
                text = (present == 1 ? "Not applied. " : notApplied + " of the " + present + " added constraints are not applied. ")
                        + off.getDeactivation().reason();
            } else {
                int pushedOut = 0;
                for (Constraint c : all) {
                    Deactivation d = c.getDeactivation();
                    if (d != null && appliedBeforeAdd.contains(c) && !Collections.disjoint(d.partners, lastAdded)) pushedOut++;
                }
                if (pushedOut > 0) text = pushedOut + (pushedOut == 1 ? " constraint added before is" : " constraints added before are")
                        + " no longer applied: " + (pushedOut == 1 ? "it contradicts" : "they contradict") + " the new one and rank"
                        + (pushedOut == 1 ? "s" : "") + " below it.";
            }
        }
        addNote.setVisible(text != null);
        if (text != null) {
            addNote.setText(Ui.wrapped(Ui.esc(text), Ui.TEXT_WIDTH));
            addNote.setForeground(Ui.WARNING);
        }
    }
}
