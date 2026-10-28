package gui.template;

import gui.components.Section;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.model.ComponentUse;
import layout.template.model.LayoutRelation;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Left part of the composite editor: the building blocks of the library (insert one as a component), the structural
 * links and the layout relations of the composite. Every step of the workflow has a visible control here as well
 * (dragging between ports on the canvas is a shortcut for "Connect ports…").
 *
 * @author Niklas Gröne
 */
final class CompositeSidebar extends JPanel {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private final TemplateEditor editor;
    private final DefaultListModel<TemplateDef> blocks = new DefaultListModel<>();
    private final JList<TemplateDef> blockList = new JList<>(blocks);
    private final DefaultListModel<StructureLink> links = new DefaultListModel<>();
    private final JList<StructureLink> linkList = new JList<>(links);
    private final DefaultListModel<LayoutRelation> relations = new DefaultListModel<>();
    private final JList<LayoutRelation> relationList = new JList<>(relations);
    private boolean refreshing;

    CompositeSidebar(EditorModel model, TemplateEditor editor) {
        super(new BorderLayout());
        this.model = model;
        this.editor = editor;
        setOpaque(false);
        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_L));

        Section lib = new Section("Building blocks");
        blockList.setName("buildingBlocks");
        blockList.setFixedCellHeight(42);
        blockList.setFixedCellWidth(1);
        blockList.setVisibleRowCount(6);
        blockList.setCellRenderer(new BlockRenderer());
        blockList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) insert(); }
        });
        lib.addRow(narrowScroll(blockList));
        JButton insert = Ui.button("Insert as component", "Adds the chosen template as a new component (double-click works too).", e -> insert());
        insert.setName("insertComponent");
        lib.addRow(insert, Ui.GAP_S);
        column.addRow(lib);

        Section ls = new Section("Structure (search conditions)");
        linkList.setName("linkList");
        linkList.setVisibleRowCount(4);
        linkList.setFixedCellWidth(1);
        linkList.setCellRenderer((l, k, i, sel, f) -> row(l, linkText(k), sel, ComponentCanvas.STRUCTURE));
        linkList.addListSelectionListener(e -> {
            if (refreshing || e.getValueIsAdjusting() || linkList.getSelectedValue() == null) return;
            model.selLink = linkList.getSelectedValue().id;
            model.selComponent = null;
            model.selRelation = null;
            model.fire();
        });
        ls.addRow(narrowScroll(linkList));
        JButton connect = Ui.button("Connect ports…", "Adds a structural link: an edge (or a shared node) between a port of one "
                + "component and a port of another. Shortcut: drag from port to port on the canvas.", e -> connect());
        connect.setName("connectPorts");
        ls.addRow(connect, Ui.GAP_S);
        column.addRow(ls, Ui.SECTION_GAP);

        Section rs = new Section("Layout (applied as constraints)");
        relationList.setName("relationList");
        relationList.setVisibleRowCount(4);
        relationList.setFixedCellWidth(1);
        relationList.setCellRenderer((l, r, i, sel, f) -> row(l, r.describe(this::compName) + (r.enabled ? "" : " (off)"), sel, ComponentCanvas.LAYOUT));
        relationList.addListSelectionListener(e -> {
            if (refreshing || e.getValueIsAdjusting() || relationList.getSelectedValue() == null) return;
            model.selRelation = relationList.getSelectedValue().id;
            model.selComponent = null;
            model.selLink = null;
            model.fire();
        });
        rs.addRow(narrowScroll(relationList));
        JButton add = Ui.button("Add layout relation…", "Left of, above, a row, column or grid of components, or ports on one line.", null);
        add.setName("addRelation");
        add.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            for (LayoutRelation.Type t : LayoutRelation.Type.values()) {
                JMenuItem it = new JMenuItem(t.label());
                it.setToolTipText(Ui.tooltip(CompositeProperties.meaning(new LayoutRelation("x", t))));
                it.addActionListener(ev -> addRelation(t));
                menu.add(it);
            }
            menu.show(add, 0, add.getHeight());
        });
        rs.addRow(add, Ui.GAP_S);
        rs.addRow(Ui.hint("Relations between components use their bounding boxes; port relations use exactly the named port nodes."), Ui.GAP_XS);
        column.addRow(rs, Ui.SECTION_GAP);
        add(new gui.components.ScrollColumn(column, 260), BorderLayout.CENTER);
        model.addListener(this::refresh);
        refresh();
    }

    /** A scroll pane that never widens the column: it takes the row's width. */
    private static JScrollPane narrowScroll(JList<?> list) {
        return new JScrollPane(list) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
        };
    }

    private String compName(String id) {
        ComponentUse c = model.def().component(id);
        return c == null ? "?" : c.name;
    }

    private String linkText(StructureLink l) {
        ComponentCanvas names = new ComponentCanvas(model, true);
        return names.portName(l.fromComponent, l.fromPort) + (l.type == StructureLink.Type.SAME_NODE ? " = " : " → ")
                + names.portName(l.toComponent, l.toPort);
    }

    private static JLabel row(JList<?> l, String text, boolean sel, Color c) {
        JLabel r = new JLabel(text);
        r.setFont(Ui.small());
        r.setOpaque(true);
        r.setBorder(new EmptyBorder(3, 6, 3, 6));
        r.setBackground(sel ? l.getSelectionBackground() : Color.WHITE);
        r.setForeground(sel ? l.getSelectionForeground() : c);
        return r;
    }

    void refresh() {
        refreshing = true;
        try {
            TemplateDef keep = blockList.getSelectedValue();
            blocks.clear();
            if (model.library != null) for (TemplateDef t : model.library.templates()) if (!t.id.equals(model.def().id)) blocks.addElement(t);
            if (keep != null) blockList.setSelectedValue(keep, false);
            links.clear();
            for (StructureLink l : model.def().links) links.addElement(l);
            relations.clear();
            for (LayoutRelation r : model.def().relations) relations.addElement(r);
            if (model.selLink != null) {
                for (int i = 0; i < links.size(); i++) if (links.get(i).id.equals(model.selLink)) linkList.setSelectedIndex(i);
            } else linkList.clearSelection();
            if (model.selRelation != null) {
                for (int i = 0; i < relations.size(); i++) if (relations.get(i).id.equals(model.selRelation)) relationList.setSelectedIndex(i);
            } else relationList.clearSelection();
        } finally {
            refreshing = false;
        }
    }

    private void insert() {
        TemplateDef t = blockList.getSelectedValue();
        if (t == null) {
            model.message = "Choose a building block first.";
            model.fire();
            return;
        }
        editor.insertComponent(t);
    }

    private void connect() {
        TemplateDef t = model.def();
        if (t.components.size() < 2) {
            JOptionPane.showMessageDialog(this, "Insert at least two components first.", "Connect ports", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String a = t.components.get(0).id, b = t.components.get(1).id;
        model.change("Connect ports", () -> {
            StructureLink l = new StructureLink(model.def().nextLinkId(), a, firstPort(a), b, firstPort(b));
            model.def().links.add(l);
            model.selLink = l.id;
            model.selComponent = null;
            model.selRelation = null;
            model.message = "Link added: choose its ports, kind and edge role on the right.";
        });
    }

    private String firstPort(String comp) {
        TemplateDef inner = layout.template.model.Templates.resolve(model.def().component(comp), model.source());
        return inner == null || inner.ports.isEmpty() ? null : inner.ports.get(0).id;
    }

    private void addRelation(LayoutRelation.Type type) {
        TemplateDef t = model.def();
        if (t.components.size() < 2) {
            JOptionPane.showMessageDialog(this, "Insert at least two components first.", "Add layout relation", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        model.change("Add layout relation", () -> {
            LayoutRelation r = new LayoutRelation(model.def().nextRelationId(), type);
            List<String> ids = new ArrayList<>();
            if (model.selComponent != null) ids.add(model.selComponent);
            for (ComponentUse c : model.def().components) if (!ids.contains(c.id)) ids.add(c.id);
            if (type.onComponents()) {
                int n = type == LayoutRelation.Type.LEFT_OF || type == LayoutRelation.Type.ABOVE ? 2 : ids.size();
                r.components.addAll(ids.subList(0, Math.min(n, ids.size())));
            } else {
                for (String id : ids.subList(0, 2)) {
                    String p = firstPort(id);
                    if (p != null) r.ports.add(new LayoutRelation.PortRef(id, p));
                }
            }
            model.def().relations.add(r);
            model.selRelation = r.id;
            model.selComponent = null;
            model.selLink = null;
            model.message = "Layout relation added: check its components and distance on the right.";
        });
    }

    /** Building block row: preview, name, kind. */
    private final class BlockRenderer extends JPanel implements ListCellRenderer<TemplateDef> {
        private static final long serialVersionUID = 1L;
        private TemplateDef t;
        private final JLabel name = new JLabel(), sub = new JLabel();

        BlockRenderer() {
            super(new GridLayout(2, 1));
            setBorder(new EmptyBorder(3, 50, 3, 4));
            name.setFont(Ui.bold());
            sub.setFont(Ui.small());
            add(name);
            add(sub);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends TemplateDef> l, TemplateDef t, int i, boolean sel, boolean f) {
            this.t = t;
            name.setText(t.name);
            sub.setText((t.isComposite() ? "Composite" : t.nodes.size() + " nodes") + " · " + t.ports.size() + " ports"
                    + ("example".equals(t.source) ? " · Example" : ""));
            setBackground(sel ? l.getSelectionBackground() : i % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            name.setForeground(sel ? l.getSelectionForeground() : Ui.TEXT);
            sub.setForeground(sel ? l.getSelectionForeground() : Ui.MUTED);
            setOpaque(true);
            setToolTipText(Ui.tooltip(Ui.esc(t.description)));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = Ui.smooth(g);
            Rectangle2D r = new Rectangle2D.Double(4, 4, 40, getHeight() - 8);
            g2.setColor(Color.WHITE);
            g2.fill(r);
            g2.setColor(Ui.BORDER);
            g2.draw(r);
            if (t != null) PatternPainter.paintPreview(g2, t, TemplateSource.chain(model.library, TemplateSource.embeddedIn(t)), r);
            g2.dispose();
        }
    }
}
