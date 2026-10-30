package gui;

import constraints.ClusterConstraintGraph;
import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.SeparationConstraintGraph;
import gui.components.Section;
import gui.components.SvgIcon;
import gui.components.Ui;
import layout.mental_map.DerivedConstraints;
import layout.mental_map.Snapshot;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Section "Mental map" of the {@link ConstraintsTab}: the constraints the last Mental Map run derived for the active
 * graph, read only, grouped into the left-to-right order, the top-to-bottom order, any other kind and one row per
 * neighbourhood group. They never enter the user's store ({@link MentalMapPanel} solves them itself); selecting rows
 * draws their constraints on the graph.
 *
 * @author Niklas Gröne
 */
final class MentalMapConstraintSection extends Section {

    private static final long serialVersionUID = 1L;

    private static final String READ_ONLY = "Only the Mental Map layout applies these; they cannot be changed here.";

    private static final String ORDER_GAP = "order, at least by the gap shown (the Prototype keeps half of each gap of "
            + "the reference, but not less than 5 px, and a smaller gap as it is; its thesis version keeps the "
            + "whole gap).";

    /** One row: a group of derived constraints of the same meaning. */
    private static final class Row {
        /** Identity across runs (the selection keeps it). */
        final String key;
        /** Pictogram. */
        final ConstraintKind kind;
        final String title, detail, tooltip;
        /** The Adaptagrams types the layout makes of the members. */
        final String adaptagrams;
        final List<Constraint> members;

        Row(String key, ConstraintKind kind, String title, String detail, String tooltip, ConstraintKind solvedAs,
            List<Constraint> members) {
            this.key = key;
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.tooltip = tooltip;
            ConstraintCatalog.Info info = ConstraintCatalog.info(solvedAs);
            this.adaptagrams = info == null ? null : info.adaptagrams;
            this.members = members;
        }

        @Override public String toString() {   // type-ahead of the list
            return title;
        }
    }

    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<Row>(model) {
        private static final long serialVersionUID = 1L;
        @Override public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
            Row r = model.get(i);
            return Ui.tooltip("<b>" + Ui.esc(r.title) + "</b><br>" + Ui.esc(r.detail) + "<br>" + Ui.esc(r.tooltip)
                    + (r.adaptagrams == null ? "" : "<br><i>Adaptagrams: " + Ui.esc(r.adaptagrams) + "</i>")
                    + "<br>" + Ui.esc(READ_ONLY));
        }
    };
    private final JLabel source = Ui.hint(" ");
    private final JButton showAllButton, selectNodesButton;
    private final JPanel actions;
    private final JScrollPane scroll;

    /** Graph and run the rows show; a new run or another graph rebuilds them. */
    private Graph shownGraph;
    private MentalMapPanel.LastRun shownRun;
    /** The groups of {@link #shownRun} as clusters to draw, made once per run so the markings keep their identity. */
    private List<Constraint> groupShapes = Collections.emptyList();
    private boolean refreshing;
    /** {@link DisplayConstraints#generation()} right after this section drew (its selection or Show all); -1: none. */
    private long drawnGeneration = -1;
    /** The markings are this section's Show all (a new run then shows all of its constraints). */
    private boolean showingAll;

    /** Runs before this section draws on the graph, so the other list gives up its selection (and its markings). */
    Runnable beforeDrawing = () -> { };

    MentalMapConstraintSection() {
        super("Mental map", true, false);
        setName("mentalMapSection");
        list.setName("mentalMapConstraintList");
        list.setCellRenderer(new Renderer());
        list.setFixedCellWidth(1);   // the list takes the visible width; long texts are clipped
        list.setFixedCellHeight(40);
        list.setVisibleRowCount(3);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(list);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) onSelection(); });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selectNodes();
            }
        });
        // Nothing here can be deleted; Delete / Backspace are consumed anyway, so VANTED's own Delete binding does not
        // delete the selected graph elements while the list has the focus.
        Action none = new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { }
        };
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "readOnly");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "readOnly");
        list.getActionMap().put("readOnly", none);
        list.getAccessibleContext().setAccessibleName("Mental map constraints");

        scroll = new gui.components.NestedScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };

        showAllButton = Ui.button("Show all", "Draw every constraint of the last run on the graph.", e -> showAll());
        selectNodesButton = Ui.button("Select nodes", "Select the nodes of the selected rows in the graph (or double-click a row).",
                e -> selectNodes());
        showAllButton.setName("mentalMapShowAll");
        selectNodesButton.setName("mentalMapSelectNodes");
        source.setName("mentalMapSource");
        actions = Ui.row(showAllButton, selectNodesButton);

        addRow(source);
        addRow(scroll);
        addRow(actions);
        refresh();
    }

    /**
     * Rebuilds the rows from the last run on the active graph. The selection survives by row key while the graph
     * stays the same; if the run changed, the markings show the new run's constraints of the selected rows.
     */
    void refresh() {
        Graph g = activeGraph();
        MentalMapPanel.LastRun run = g == null ? null : MentalMapPanel.lastRunOf(g);
        Set<String> selected = new HashSet<>();
        for (Row r : list.getSelectedValuesList()) selected.add(r.key);
        boolean otherGraph = g != shownGraph;
        boolean newRun = run != shownRun;
        if (otherGraph) selected.clear();   // a row of another graph means other nodes
        if (newRun) groupShapes = run == null ? Collections.<Constraint>emptyList() : groupShapes(run.derived);
        shownGraph = g;
        shownRun = run;

        refreshing = true;
        try {
            model.clear();
            if (run != null) for (Row r : rows(run.derived, g)) model.addElement(r);
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) if (selected.contains(model.get(i).key)) keep.add(i);
            int[] ix = new int[keep.size()];
            for (int i = 0; i < ix.length; i++) ix[i] = keep.get(i);
            list.setSelectedIndices(ix);
            list.setVisibleRowCount(Math.max(2, Math.min(6, model.size())));
        } finally {
            refreshing = false;
        }
        showSummary(g, run);
        // Its markings would show constraints of an older run or of another graph. Only its own: once another list or
        // panel (e.g. Conflicts) drew, the markings are that one's.
        if ((newRun || otherGraph) && ownsMarkings()) {
            if (showingAll && !otherGraph && run != null && !model.isEmpty()) showAll();
            else drawSelection();
        }
        updateActions();
        list.repaint();
    }

    /**
     * Header counter and the line above the list. The counter is one short number, as in the other sections (the
     * constraints and groups the rows show, "n of m" once deleted nodes took some away); the line has the breakdown.
     */
    private void showSummary(Graph g, MentalMapPanel.LastRun run) {
        boolean rows = !model.isEmpty();
        scroll.setVisible(rows);
        actions.setVisible(rows);
        String text;
        if (run == null) {
            setTrailingText("none");
            String reason = g == null ? null : MentalMapPanel.noRunReasonOf(g);
            text = g == null ? "Open a graph and run the layout under Algorithms › Mental Map to see the constraints "
                    + "it derives from the reference."
                    : (reason == null ? "No Mental Map run on this graph yet." : reason)
                    + " Run the layout under Algorithms › Mental Map to see the constraints it derives from the reference.";
        } else {
            int gaps = 0, other = 0;
            for (Constraint c : run.derived.getConstraints()) {
                if (c instanceof SeparationConstraintGraph) gaps++;
                else other++;
            }
            int groups = run.derived.getClusters().size();
            int total = gaps + other + groups, shown = 0;
            for (int i = 0; i < model.size(); i++) shown += model.get(i).members.size();
            setTrailingText(shown == total ? String.valueOf(total) : shown + " of " + total);
            List<String> parts = new ArrayList<>();
            if (gaps > 0) parts.add(gaps + (gaps == 1 ? " gap" : " gaps"));
            if (other > 0) parts.add(other + " other");
            if (groups > 0) parts.add(groups + (groups == 1 ? " group" : " groups"));
            String from = "From the last " + run.method + " run on this graph (reference “" + run.reference + "”)";
            text = run.derived.isEmpty() ? from + ". It derived no constraints."
                    : !rows ? from + ": " + String.join(", ", parts) + ". None of them is on the graph any more."
                    : from + ": " + String.join(", ", parts) + ". Read only: no other solve applies them."
                    + (shown == total ? "" : " The rest belonged to nodes deleted since (a node brought back by Undo "
                            + "counts as new); the next run derives them anew.");
        }
        source.setText(Ui.wrapped(Ui.esc(text), Ui.TEXT_WIDTH));
        revalidate();
    }

    /** The rows of {@code d}, only with the constraints whose nodes are all still in {@code g}. */
    private List<Row> rows(DerivedConstraints d, Graph g) {
        List<Row> rows = new ArrayList<>();
        if (g == null) return rows;
        Set<Node> nodes = new HashSet<>(g.getNodes());
        List<Constraint> xs = new ArrayList<>(), ys = new ArrayList<>();
        Map<ConstraintKind, List<Constraint>> other = new EnumMap<>(ConstraintKind.class);
        for (Constraint c : d.getConstraints()) {
            if (!in(c, nodes)) continue;
            if (c.getKind() == ConstraintKind.SEPARATION_X) xs.add(c);
            else if (c.getKind() == ConstraintKind.SEPARATION_Y) ys.add(c);
            else other.computeIfAbsent(c.getKind(), k -> new ArrayList<>()).add(c);
        }
        if (!xs.isEmpty()) rows.add(new Row("order-x", ConstraintKind.ORDER_X, "Order left → right", chainText(xs),
                "Keeps the left-to-right order of the reference: every node stays right of the node before it in that "
                        + ORDER_GAP,
                ConstraintKind.SEPARATION_X, xs));
        if (!ys.isEmpty()) rows.add(new Row("order-y", ConstraintKind.ORDER_Y, "Order top → bottom", chainText(ys),
                "Keeps the top-to-bottom order of the reference: every node stays below the node before it in that "
                        + ORDER_GAP,
                ConstraintKind.SEPARATION_Y, ys));
        for (Map.Entry<ConstraintKind, List<Constraint>> e : other.entrySet()) {
            ConstraintCatalog.Info info = ConstraintCatalog.info(e.getKey());
            List<Constraint> cs = e.getValue();
            String title = (info == null ? e.getKey().name() : info.title) + (cs.size() > 1 ? " (" + cs.size() + "×)" : "");
            rows.add(new Row("kind-" + e.getKey().name(), e.getKey(), title,
                    nodeCount(cs) + " nodes · " + cs.size() + (cs.size() == 1 ? " constraint" : " constraints"),
                    info == null ? "" : info.description, e.getKey(), cs));
        }
        for (int i = 0; i < groupShapes.size(); i++) {
            Constraint shape = groupShapes.get(i);
            if (!in(shape, nodes)) continue;
            rows.add(new Row(groupKey(shape), ConstraintKind.CLUSTER, "Group " + (i + 1), ConstraintCatalog.nodesText(shape),
                    "Neighbourhood group: nodes that were close together in the reference (the Prototype also merges "
                            + "groups whose boxes overlap there, and a new node joins the group that holds most of its "
                            + "grouped neighbours): the layout keeps them together in one box (padding "
                            + ConstraintCatalog.px(d.getClusterPadding()) + ", margin "
                            + ConstraintCatalog.px(d.getClusterMargin()) + ").", ConstraintKind.CLUSTER,
                    Collections.singletonList(shape)));
        }
        return rows;
    }

    /** A group's key is its members, so a selected group stays selected only while a new run keeps it the same. */
    private static String groupKey(Constraint shape) {
        List<String> ids = new ArrayList<>();
        for (Node n : shape.getNodes()) {
            String uid = Snapshot.uidOf(n);
            ids.add(uid.isEmpty() ? "#" + n.getID() : uid);
        }
        Collections.sort(ids);
        return "group-" + String.join(",", ids);
    }

    /** The groups of a run as clusters for the overlay; never handed to a solver. */
    private static List<Constraint> groupShapes(DerivedConstraints d) {
        List<Constraint> shapes = new ArrayList<>();
        for (List<Node> members : d.getClusters()) {
            ClusterConstraintGraph shape = new ClusterConstraintGraph(new ArrayList<>(members), d.getClusterPadding(),
                    d.getClusterMargin());
            shape.setAutomatic(true);   // drawn lighter, like every constraint the user did not make
            shapes.add(shape);
        }
        return shapes;
    }

    /** "49 nodes · 48 gaps ≥ 3–240 px". */
    private static String chainText(List<Constraint> seps) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Constraint c : seps) {
            double d = ((SeparationConstraintGraph) c).getDistance();
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        String range = Math.abs(max - min) < 0.05 ? ConstraintCatalog.px(min)
                : ConstraintCatalog.number(min) + "–" + ConstraintCatalog.px(max);
        return nodeCount(seps) + " nodes · " + seps.size() + (seps.size() == 1 ? " gap ≥ " : " gaps ≥ ") + range;
    }

    private static int nodeCount(List<Constraint> cs) {
        Set<Node> ns = new LinkedHashSet<>();
        for (Constraint c : cs) ns.addAll(c.getNodes());
        return ns.size();
    }

    private static boolean in(Constraint c, Set<Node> nodes) {
        if (c.getNodes() == null || c.getNodes().isEmpty()) return false;
        for (Node n : c.getNodes()) if (!nodes.contains(n)) return false;
        return true;
    }

    private static Graph activeGraph() {
        EditorSession session = MainFrame.getInstance() == null ? null : MainFrame.getInstance().getActiveEditorSession();
        return session == null ? null : session.getGraph();
    }

    /** Drops the selection without touching the markings (the other list is about to draw its own). */
    void clearSelection() {
        refreshing = true;
        try {
            list.clearSelection();
        } finally {
            refreshing = false;
        }
        drawnGeneration = -1;
        showingAll = false;
        updateActions();
    }

    /** The markings on the graph are still the ones this section drew (no other list or panel drew since). */
    private boolean ownsMarkings() {
        return drawnGeneration == DisplayConstraints.generation();
    }

    private void onSelection() {
        showingAll = false;
        if (!list.isSelectionEmpty()) beforeDrawing.run();
        drawSelection();
        updateActions();
    }

    /** The markings show the constraints of the selected rows. */
    private void drawSelection() {
        DisplayConstraints.clearMarkings();
        List<Constraint> cs = new ArrayList<>();
        for (Row r : list.getSelectedValuesList()) cs.addAll(r.members);
        if (!cs.isEmpty()) DisplayConstraints.drawConstraints(cs);
        drawnGeneration = cs.isEmpty() ? -1 : DisplayConstraints.generation();
    }

    private void showAll() {
        beforeDrawing.run();
        DisplayConstraints.clearMarkings();
        List<Constraint> cs = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) cs.addAll(model.get(i).members);
        DisplayConstraints.drawConstraints(cs);
        drawnGeneration = cs.isEmpty() ? -1 : DisplayConstraints.generation();
        showingAll = !cs.isEmpty();
    }

    private void selectNodes() {
        List<Row> sel = list.getSelectedValuesList();
        Graph g = activeGraph();
        if (sel.isEmpty() || g == null) return;
        Set<Node> nodes = new LinkedHashSet<>();
        for (Row r : sel) for (Constraint c : r.members) for (Node n : c.getNodes()) if (n.getGraph() == g) nodes.add(n);
        GraphHelper.selectNodes(true, new ArrayList<>(nodes));
    }

    private void updateActions() {
        selectNodesButton.setEnabled(!list.isSelectionEmpty());
        showAllButton.setEnabled(!model.isEmpty());
    }

    /** Two-line cell: pictogram, title, and what the row holds. */
    private static final class Renderer extends JPanel implements ListCellRenderer<Row> {
        private static final long serialVersionUID = 1L;
        private final JLabel icon = new JLabel();
        private final JLabel head = new JLabel();
        private final JLabel body = new JLabel();

        Renderer() {
            super(new BorderLayout(Ui.GAP_M, 0));
            setBorder(new EmptyBorder(3, 6, 3, 6));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            head.setFont(Ui.font().deriveFont(Font.BOLD, Ui.small().getSize2D() + 1));
            body.setFont(Ui.small());
            text.add(head);
            text.add(body);
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> l, Row r, int index, boolean selected, boolean focus) {
            ConstraintCatalog.Info info = ConstraintCatalog.info(r.kind);
            icon.setIcon(info == null ? null : SvgIcon.get(info.icon, 24));
            head.setText(r.title);
            body.setText(r.detail);
            getAccessibleContext().setAccessibleName(r.title + ", " + r.detail);
            setBackground(selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            head.setForeground(selected ? l.getSelectionForeground() : Ui.TEXT);
            body.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            setOpaque(true);
            return this;
        }
    }
}
