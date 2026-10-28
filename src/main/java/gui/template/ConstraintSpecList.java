package gui.template;

import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.Priority;
import constraints.spec.ConstraintSpec;
import gui.ConstraintCatalog;
import gui.ConstraintParamForm;
import gui.components.CardPanel;
import gui.components.PrimaryButton;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.SvgIcon;
import gui.components.TilePalette;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.apply.ArrangementDeriver;
import layout.template.model.PatternNode;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Left part of the template editor: the template's constraints (each with its state: invalid with the reason, switched
 * off, not holding in the drawing) and the ways to add some: from the catalogue of the Constraints tab (kind, settings,
 * the selected pattern nodes) or derived from the arrangement. Selecting a constraint marks its elements on the canvas;
 * "Only those of the selected nodes" finds the constraints of a node.
 *
 * @author Niklas Gröne
 */
final class ConstraintSpecList extends JPanel {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private final DefaultListModel<ConstraintSpec> listModel = new DefaultListModel<>();
    final JList<ConstraintSpec> list = new JList<>(listModel);
    private final JCheckBox onlySelected = new JCheckBox("Only those of the selected nodes");
    private final Section section = new Section("Constraints");
    private final CardPanel addCard = new CardPanel(new BorderLayout(), Ui.GAP_M);
    private final TilePalette<ConstraintKind.Category> categories = new TilePalette<>(TilePalette.Style.CHIP, 3);
    private final TilePalette<ConstraintKind> kinds = new TilePalette<>(TilePalette.Style.TILE, 3);
    private final JLabel kindTitle = new JLabel();
    private final JPanel formHolder = new JPanel(new BorderLayout());
    private ConstraintParamForm form;
    private ConstraintKind kind;
    private final Map<ConstraintKind.Category, ConstraintKind> lastKind = new EnumMap<>(ConstraintKind.Category.class);
    private final JComboBox<Priority> newPriority = gui.PriorityControls.comboBox("editorPriority");
    private final PrimaryButton addButton = new PrimaryButton("Add");
    private final StatusLabel addStatus = new StatusLabel(" ");
    private boolean refreshing;

    ConstraintSpecList(EditorModel model) {
        super(new BorderLayout());
        this.model = model;
        setOpaque(false);
        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_L));

        onlySelected.setOpaque(false);
        onlySelected.setToolTipText(Ui.tooltip("Lists only the constraints that include one of the nodes selected on the canvas."));
        onlySelected.addActionListener(e -> refresh());
        list.setName("specList");
        list.setCellRenderer(new Renderer());
        list.setFixedCellHeight(40);
        list.setFixedCellWidth(1);
        list.setVisibleRowCount(9);
        list.getAccessibleContext().setAccessibleName("Constraints of the template");
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || refreshing) return;
            ConstraintSpec s = list.getSelectedValue();
            model.selConstraint = s == null ? null : s.id;
            model.fire();
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "del");
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "del");
        list.getActionMap().put("del", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { deleteSelected(); }
        });
        section.addRow(onlySelected);
        section.addRow(new JScrollPane(list) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
        }, Ui.GAP_S);
        JButton add = Ui.button("Add…", "Adds a constraint to the nodes selected on the canvas.", e -> {
            addCard.setVisible(!addCard.isVisible());
            revalidate();
        });
        add.setName("addSpec");
        JButton del = Ui.button("Delete", "Deletes the selected constraint (Delete key).", e -> deleteSelected());
        del.setName("deleteSpec");
        JButton derive = Ui.button("Derive from arrangement…", "Creates a small set of constraints (rows, columns, the order along edges) "
                + "from the pattern's drawing; you can edit them afterwards.", e -> derive());
        derive.setName("deriveSpecs");
        section.addRow(Ui.row(add, del), Ui.GAP_S);
        section.addRow(derive, Ui.GAP_XS);
        column.addRow(section);
        column.addRow(createAddCard(), Ui.GAP_M);
        addCard.setVisible(false);
        add(new gui.components.ScrollColumn(column, 260), BorderLayout.CENTER);
        model.addListener(this::refresh);
        refresh();
    }

    private JComponent createAddCard() {
        for (ConstraintKind.Category c : ConstraintKind.Category.values()) {
            categories.addTile(c, null, ConstraintCatalog.categoryLabel(c), ConstraintCatalog.categoryTooltip(c));
        }
        categories.addSelectionListener(this::showCategory);
        kinds.addSelectionListener(this::showKind);
        kindTitle.setFont(Ui.bold());
        formHolder.setOpaque(false);
        addButton.setName("addSpecToSelection");
        addButton.addActionListener(e -> addConstraint());
        VerticalStack body = new VerticalStack();
        body.addRow(categories);
        body.addRow(kinds, Ui.GAP_M);
        body.addRow(kindTitle, Ui.GAP_M);
        body.addRow(formHolder, Ui.GAP_S);
        body.addRow(Ui.labeled("Priority", newPriority), Ui.GAP_S);
        body.addRow(addButton, Ui.GAP_M);
        body.addRow(addStatus, Ui.GAP_S);
        addCard.add(body, BorderLayout.CENTER);
        showCategory(categories.getSelectedValue());
        return addCard;
    }

    private void showCategory(ConstraintKind.Category c) {
        if (c == null) return;
        ConstraintKind last = lastKind.get(c);
        kinds.clearTiles();
        for (ConstraintKind k : ConstraintCatalog.kinds(c)) {
            ConstraintCatalog.Info info = ConstraintCatalog.info(k);
            kinds.addTile(k, SvgIcon.get(info.icon, 28), info.label, "<b>" + info.title + "</b><br>" + info.description);
        }
        if (last != null) kinds.setSelectedValue(last);
        showKind(kinds.getSelectedValue());
        revalidate();
    }

    private void showKind(ConstraintKind k) {
        if (k == null) return;
        kind = k;
        lastKind.put(k.getCategory(), k);
        ConstraintCatalog.Info info = ConstraintCatalog.info(k);
        kindTitle.setText(info.title);
        kindTitle.setIcon(SvgIcon.get(info.icon, 18));
        kindTitle.setToolTipText(Ui.tooltip(Ui.esc(info.description)));
        formHolder.removeAll();
        form = new ConstraintParamForm(info.params, null, null);
        formHolder.add(form, BorderLayout.CENTER);
        formHolder.setVisible(!form.isEmpty());
        updateAddButton();
        revalidate();
        repaint();
    }

    private ConstraintCatalog.Selection selection() {
        List<Node> ns = new ArrayList<>();
        for (String id : model.selNodes) {
            Node n = model.node(id);
            if (n != null) ns.add(n);
        }
        List<Edge> es = new ArrayList<>();
        for (String id : model.selEdges) {
            Edge e = model.edge(id);
            if (e != null) es.add(e);
        }
        return new ConstraintCatalog.Selection(model.graph(), ns, es);
    }

    private void updateAddButton() {
        if (kind == null) return;
        ConstraintCatalog.Info info = ConstraintCatalog.info(kind);
        String problem = info.problem(selection());
        addButton.setEnabled(problem == null);
        addButton.setText(problem == null ? "Add to " + info.selectionText(selection()).replace(" selected", "") : "Add");
        addStatus.setStatus(problem == null ? info.selectionText(selection()) : problem,
                problem == null ? StatusLabel.Kind.SUCCESS : StatusLabel.Kind.INFO);
    }

    private void addConstraint() {
        ConstraintCatalog.Info info = ConstraintCatalog.info(kind);
        List<Constraint> made;
        try {
            made = info.create(selection(), form == null ? null : form.values());
        } catch (IllegalArgumentException ex) {
            addStatus.setStatus(ex.getMessage(), StatusLabel.Kind.ERROR);
            return;
        }
        Priority p = gui.PriorityControls.selected(newPriority);
        for (Constraint c : made) c.setPriority(p);
        List<String> ids = new ArrayList<>();
        model.change("Add " + info.title, () -> ids.addAll(model.addConstraints(made)));
        if (!ids.isEmpty()) {
            model.selConstraint = ids.get(0);
            model.fire();
        }
        addStatus.setStatus("Added: " + info.title + ". Its values are fixed numbers now (no \"auto\").", StatusLabel.Kind.SUCCESS);
    }

    private void deleteSelected() {
        ConstraintSpec s = list.getSelectedValue();
        if (s == null) return;
        model.change("Delete constraint", () -> model.def().constraints.remove(model.def().constraint(s.id)));
        model.selConstraint = null;
        model.fire();
    }

    private void derive() {
        JCheckBox rows = new JCheckBox("Rows and columns (aligned, order kept)", true);
        JCheckBox edges = new JCheckBox("Order along edges not in a row or column", true);
        JCheckBox rigid = new JCheckBox("Keep the whole shape (rigid group)", false);
        JSpinner tol = new JSpinner(new SpinnerNumberModel(6.0, 0.0, 100.0, 1.0));
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.add(new JLabel("<html><b>Derive constraints from the arrangement</b><br>A few readable rules instead of one per pair of nodes.</html>"));
        p.add(rows);
        p.add(edges);
        p.add(rigid);
        p.add(Ui.labeled("Aligned within (px)", tol));
        if (JOptionPane.showConfirmDialog(this, p, "Derive constraints", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        ArrangementDeriver.Options o = new ArrangementDeriver.Options();
        o.rowsAndColumns = rows.isSelected();
        o.edgeOrder = edges.isSelected();
        o.rigid = rigid.isSelected();
        o.tolerance = ((Number) tol.getValue()).doubleValue();
        List<ConstraintSpec> made = ArrangementDeriver.derive(model.def(), o);
        if (made.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to derive: no nodes share a line and no edge is left.", "Derive constraints",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        model.change("Derive " + made.size() + " constraints", () -> model.def().constraints.addAll(made));
        model.message = made.size() + " constraints derived from the arrangement; edit or delete them as you like.";
        model.fire();
    }

    void refresh() {
        refreshing = true;
        try {
            listModel.clear();
            for (ConstraintSpec s : model.def().constraints) {
                if (onlySelected.isSelected() && !model.selNodes.isEmpty()) {
                    boolean in = false;
                    for (String id : model.selNodes) in |= s.refs().contains(id);
                    if (!in) continue;
                }
                listModel.addElement(s);
            }
            section.setTrailingText(String.valueOf(model.def().constraints.size()));
            if (model.selConstraint != null) {
                for (int i = 0; i < listModel.size(); i++) {
                    if (listModel.get(i).id.equals(model.selConstraint)) {
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                    }
                }
            } else list.clearSelection();
        } finally {
            refreshing = false;
        }
        updateAddButton();
        list.repaint();
    }

    /** Row: pictogram, title, participants or the reason it is invalid / not holding. */
    private final class Renderer extends JPanel implements ListCellRenderer<ConstraintSpec> {
        private static final long serialVersionUID = 1L;
        private final JLabel icon = new JLabel(), head = new JLabel(), body = new JLabel(), mark = new JLabel();

        Renderer() {
            super(new BorderLayout(Ui.GAP_M, 0));
            setBorder(new EmptyBorder(3, 6, 3, 6));
            JPanel t = new JPanel(new GridLayout(2, 1));
            t.setOpaque(false);
            head.setFont(Ui.bold().deriveFont(Ui.small().getSize2D() + 1));
            body.setFont(Ui.small());
            mark.setFont(Ui.small().deriveFont(Font.BOLD));
            t.add(head);
            t.add(body);
            add(icon, BorderLayout.WEST);
            add(t, BorderLayout.CENTER);
            add(mark, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ConstraintSpec> l, ConstraintSpec s, int index, boolean sel, boolean focus) {
            ConstraintKind k = s.kind();
            ConstraintCatalog.Info info = k == null ? null : ConstraintCatalog.info(k);
            String error = model.buildError(s.id), note = model.notes.get(s.id), unmet = model.unmet.get(s.id);
            icon.setIcon(info == null ? null : SvgIcon.get(info.icon, 22, !s.enabled || error != null));
            head.setText(TemplateEditor.title(s));
            boolean involved = false;
            for (String id : model.selNodes) involved |= s.refs().contains(id);
            head.setFont(Ui.bold().deriveFont(Ui.small().getSize2D() + 1f).deriveFont(involved ? Font.BOLD | Font.ITALIC : Font.BOLD));
            String text;
            Color c;
            if (k == null) {
                text = "Unknown type '" + s.type + "': kept, cannot be applied here";
                c = Ui.ERROR;
            } else if (error != null) {
                text = note != null && note.startsWith("Invalid") ? note : "Invalid: " + error;
                c = Ui.ERROR;
            } else if (note != null) {
                text = note;
                c = Ui.WARNING;
            } else if (unmet != null) {
                text = "Not holding in the drawing: " + unmet;
                c = Ui.WARNING;
            } else {
                List<String> names = new ArrayList<>();
                for (String id : s.refs()) {
                    PatternNode n = model.def().node(id);
                    names.add(n == null ? id : n.displayName());
                    if (names.size() >= 4) break;
                }
                text = String.join(", ", names) + (s.refs().size() > 4 ? ", …" : "");
                c = Ui.MUTED;
            }
            body.setText(text);
            mark.setText(!s.enabled ? "OFF" : s.priority != Priority.NORMAL ? s.priority.label() : error != null ? "!" : "");
            setToolTipText(Ui.tooltip("<b>" + Ui.esc(TemplateEditor.title(s)) + "</b> (" + s.id + ")<br>" + Ui.esc(text)));
            setBackground(sel ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            head.setForeground(sel ? l.getSelectionForeground() : error != null ? Ui.ERROR : !s.enabled ? Ui.MUTED : Ui.TEXT);
            body.setForeground(sel ? l.getSelectionForeground() : c);
            mark.setForeground(sel ? l.getSelectionForeground() : error != null ? Ui.ERROR : Ui.MUTED);
            setOpaque(true);
            return this;
        }
    }
}
