package gui.template;

import constraints.Priority;
import constraints.spec.ConstraintSpec;
import gui.components.Section;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.io.TemplateLibrary;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.LayoutRelation;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Right part of the composite editor: the settings of the selected component, structure link or layout relation, or of
 * the composite template itself when nothing is selected. For a component, the settings of this use are kept apart from
 * editing the reused template (a new revision that other uses see as an update) and from making an independent copy.
 *
 * @author Niklas Gröne
 */
final class CompositeProperties extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int W = 250;

    private final EditorModel model;
    private final TemplateEditor editor;
    private final VerticalStack column = new VerticalStack();
    private String shownKey = "";
    private boolean selfChange;

    CompositeProperties(EditorModel model, TemplateEditor editor) {
        super(new BorderLayout());
        this.model = model;
        this.editor = editor;
        setOpaque(false);
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_L));
        add(new gui.components.ScrollColumn(column, W + 20), BorderLayout.CENTER);
        model.addListener(this::refresh);
        refresh();
    }

    private void edit(String name, Runnable r) {
        selfChange = true;
        try {
            model.change(name, r);
        } finally {
            selfChange = false;
        }
    }

    void refresh() {
        String key = model.selComponent + "|" + model.selLink + "|" + model.selRelation + "|" + model.def().components.size() + "|"
                + model.def().links.size() + "|" + model.def().relations.size() + "|" + model.def().ports.size() + "|" + model.undoName();
        if (selfChange && key.equals(shownKey)) return;
        shownKey = key;
        column.clearRows();
        TemplateDef t = model.def();
        if (model.selComponent != null && t.component(model.selComponent) != null) column.addRow(componentSection(t.component(model.selComponent)));
        else if (model.selLink != null && t.link(model.selLink) != null) column.addRow(linkSection(t.link(model.selLink)));
        else if (model.selRelation != null && t.relation(model.selRelation) != null) column.addRow(relationSection(t.relation(model.selRelation)));
        column.addRow(templateSection(), column.getRowCount() == 0 ? 0 : Ui.SECTION_GAP);
        column.revalidate();
        column.repaint();
    }

    /**
     * Deletes the selected component (with its links, the composite ports passed on from it and the relations left with
     * fewer than two members), the selected link or the selected relation.
     */
    static void deleteSelected(EditorModel model) {
        TemplateDef t = model.def();
        if (model.selComponent != null) {
            String id = model.selComponent;
            ComponentUse c = t.component(id);
            if (c == null) return;
            model.change("Remove component", () -> {
                int links = 0, rels = 0;
                t.components.remove(c);
                for (StructureLink l : new ArrayList<>(t.links)) {
                    if (id.equals(l.fromComponent) || id.equals(l.toComponent)) { t.links.remove(l); links++; }
                }
                for (LayoutRelation r : new ArrayList<>(t.relations)) {
                    r.components.remove(id);
                    r.ports.removeIf(p -> id.equals(p.component));
                    if ((r.type.onComponents() && r.components.size() < 2) || (!r.type.onComponents() && r.ports.size() < 2)) { t.relations.remove(r); rels++; }
                }
                t.ports.removeIf(p -> id.equals(p.component));
                model.selComponent = null;
                model.message = "Removed '" + c.name + "'" + (links + rels > 0 ? " with " + links + " links and " + rels + " layout relations" : "") + ".";
            });
        } else if (model.selLink != null) {
            String id = model.selLink;
            model.change("Remove link", () -> {
                t.links.removeIf(l -> l.id.equals(id));
                model.selLink = null;
            });
        } else if (model.selRelation != null) {
            String id = model.selRelation;
            model.change("Remove layout relation", () -> {
                t.relations.removeIf(r -> r.id.equals(id));
                model.selRelation = null;
            });
        }
    }

    private Section componentSection(ComponentUse c) {
        Section sec = new Section("Component");
        TemplateDef inner = Templates.resolve(c, model.source());
        JTextField name = new JTextField(c.name, 2);
        name.setToolTipText(Ui.tooltip("The role of this use, e.g. Source or Target. Two uses of one template need two roles."));
        commit(name, v -> {
            if (v.isEmpty()) return;
            for (ComponentUse o : model.def().components) {
                if (o != c && o.name.equals(v)) {
                    JOptionPane.showMessageDialog(this, "Another component is called '" + v + "' already; give each use its own role.",
                            "Rename component", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            edit("Rename component", () -> model.def().component(c.id).name = v);
        });
        sec.addRow(Ui.labeled("Role", name));
        sec.addRow(Ui.hint("Uses <b>" + Ui.esc(c.ref == null ? "?" : c.ref.toString()) + "</b>"
                + (inner == null ? "<br><font color='#c5221f'>This revision is not available.</font>" : ""), W), Ui.GAP_XS);
        TemplateLibrary lib = model.library;
        TemplateDef newest = lib == null || c.ref == null ? null : lib.latest(c.ref.id);
        if (newest != null && inner != null && newest.revision > inner.revision) {
            JButton upd = Ui.button("Update to rev. " + newest.revision + "…", "Uses the newer revision after checking that its ports "
                    + "and constraints still fit this composite.", e -> editor.updateComponent(c, newest));
            upd.setName("updateComponent");
            sec.addRow(upd, Ui.GAP_S);
        }
        JButton open = Ui.button("Look inside", "Shows the component's template (read only); the path above leads back.", e -> editor.openInside(c));
        open.setName("openInside");
        sec.addRow(open, Ui.GAP_S);

        sec.addRow(subheading("Settings of this use only"), Ui.GAP_M);
        JCheckBox own = new JCheckBox("Apply its own constraints", c.includeConstraints);
        own.setOpaque(false);
        own.setToolTipText(Ui.tooltip("Off: this component only takes part in the search (its structure); its layout constraints are not made."));
        own.addActionListener(e -> edit("Change component setting", () -> model.def().component(c.id).includeConstraints = own.isSelected()));
        sec.addRow(own, Ui.GAP_XS);
        Object[] prios = new Object[Priority.values().length + 1];
        prios[0] = "As in the template";
        for (int i = 0; i < Priority.values().length; i++) prios[i + 1] = Priority.values()[i];
        JComboBox<Object> prio = Ui.comboBox(prios);
        prio.setSelectedItem(c.priority == null ? prios[0] : c.priority);
        prio.addActionListener(e -> edit("Change component priority", () -> {
            Object o = prio.getSelectedItem();
            model.def().component(c.id).priority = o instanceof Priority ? (Priority) o : null;
        }));
        sec.addRow(Ui.labeled("Priority", prio), Ui.GAP_XS);
        if (inner != null && !inner.isComposite() && !inner.constraints.isEmpty()) {
            sec.addRow(Ui.hint("Its constraints in this use:", W), Ui.GAP_XS);
            for (ConstraintSpec s : inner.constraints) {
                JCheckBox on = new JCheckBox(TemplateEditor.title(s), s.enabled && !c.disabledConstraints.contains(s.id));
                on.setOpaque(false);
                on.setFont(Ui.small());
                on.setEnabled(c.includeConstraints);
                on.addActionListener(e -> edit("Switch component constraint", () -> {
                    ComponentUse k = model.def().component(c.id);
                    if (on.isSelected()) k.disabledConstraints.remove(s.id);
                    else k.disabledConstraints.add(s.id);
                }));
                sec.addRow(on, Ui.GAP_XS);
            }
        }

        sec.addRow(subheading("The reused template"), Ui.GAP_M);
        sec.addRow(Ui.hint("Changing it changes a new revision; other uses keep theirs until you update them.", W), Ui.GAP_XS);
        JButton editT = Ui.button("Edit template…", "Opens the template itself in another editor window.", e -> editor.editComponentTemplate(c));
        JButton copy = Ui.button("Independent copy", "This use gets its own copy of the template, which you can change without affecting others.",
                e -> editor.makeIndependentCopy(c));
        editT.setName("editComponentTemplate");
        copy.setName("independentCopy");
        sec.addRow(Ui.row(editT, copy), Ui.GAP_XS);
        if (inner != null && !inner.ports.isEmpty()) {
            StringBuilder sb = new StringBuilder("Ports: ");
            for (Port p : inner.ports) sb.append(sb.length() > 7 ? ", " : "").append(Ui.esc(p.name));
            sec.addRow(Ui.hint(sb.toString() + "<br>Drag from a port dot to another component's port to connect them.", W), Ui.GAP_S);
        } else if (inner != null) {
            sec.addRow(Ui.hint("<font color='#b06000'>This template has no ports yet: make ports in its editor to connect it.</font>", W), Ui.GAP_S);
        }
        JButton del = Ui.button("Remove component", "Removes this use with its links and layout relations.", e -> deleteSelected(model));
        sec.addRow(del, Ui.GAP_M);
        problems(sec, c.id);
        return sec;
    }

    private Section linkSection(StructureLink l) {
        Section sec = new Section("Structure link " + l.id);
        sec.addRow(Ui.hint("A <b>search condition</b>: the parts found for the two components must be joined like this in the graph.", W));
        JComboBox<String> type = Ui.comboBox(new String[]{"Edge between the ports", "Same node (shared role)", "Path (not supported yet)"});
        type.setSelectedIndex(l.type.ordinal());
        type.addActionListener(e -> edit("Change link type", () -> model.def().link(l.id).type = StructureLink.Type.values()[type.getSelectedIndex()]));
        sec.addRow(Ui.labeled("Kind", type), Ui.GAP_S);
        sec.addRow(portChooser("From", l.fromComponent, l.fromPort, (c, p) -> edit("Change link", () -> {
            StructureLink k = model.def().link(l.id);
            k.fromComponent = c;
            k.fromPort = p;
        })), Ui.GAP_S);
        sec.addRow(portChooser("To", l.toComponent, l.toPort, (c, p) -> edit("Change link", () -> {
            StructureLink k = model.def().link(l.id);
            k.toComponent = c;
            k.toPort = p;
        })), Ui.GAP_XS);
        if (l.type == StructureLink.Type.EDGE) {
            JComboBox<StructureLink.Direction> dir = Ui.comboBox(StructureLink.Direction.values());
            dir.setSelectedItem(l.direction);
            dir.addActionListener(e -> edit("Change direction", () -> model.def().link(l.id).direction = (StructureLink.Direction) dir.getSelectedItem()));
            sec.addRow(Ui.labeled("Direction", dir), Ui.GAP_S);
            Condition cur = null;
            for (Condition c : l.edgeConditions) if (Condition.SBGN_ROLE.equals(c.attribute)) cur = c;
            List<String> roles = new ArrayList<>();
            roles.add("(any role)");
            roles.addAll(SbgnRoles.edgeRoles(TemplateWorkbench.activeGraph()));
            JComboBox<String> role = new JComboBox<>(roles.toArray(new String[0]));
            role.setEditable(true);
            role.setSelectedItem(cur == null || cur.op == Condition.Op.ANY ? "(any role)" : String.join(", ", cur.values));
            role.setToolTipText(Ui.tooltip("The SBGN role the joining edge must have (checked in \"Structure and semantics\" searches)."));
            role.addActionListener(e -> {
                String v = String.valueOf(role.getSelectedItem()).trim();
                edit("Change link role", () -> {
                    StructureLink k = model.def().link(l.id);
                    k.edgeConditions.removeIf(c -> Condition.SBGN_ROLE.equals(c.attribute));
                    if (!v.isEmpty() && !v.startsWith("(")) {
                        List<String> vals = new ArrayList<>();
                        for (String p : v.split(",")) if (!p.trim().isEmpty()) vals.add(p.trim());
                        k.edgeConditions.add(new Condition(Condition.SBGN_ROLE, vals.size() == 1 ? Condition.Op.EQUALS : Condition.Op.IN_LIST, vals));
                    }
                });
            });
            sec.addRow(Ui.labeled("Edge role", role), Ui.GAP_XS);
        }
        JButton swap = Ui.button("Swap ends", null, e -> edit("Swap link ends", () -> {
            StructureLink k = model.def().link(l.id);
            String c = k.fromComponent, p = k.fromPort;
            k.fromComponent = k.toComponent;
            k.fromPort = k.toPort;
            k.toComponent = c;
            k.toPort = p;
        }));
        JButton del = Ui.button("Remove", null, e -> deleteSelected(model));
        sec.addRow(Ui.row(swap, del), Ui.GAP_M);
        problems(sec, l.id);
        return sec;
    }

    /** Combo boxes for a component and one of its ports; {@code onChange} receives the chosen ids. */
    private JComponent portChooser(String label, String comp, String port, java.util.function.BiConsumer<String, String> onChange) {
        TemplateDef t = model.def();
        JComboBox<ComponentUse> cc = Ui.comboBox(t.components.toArray(new ComponentUse[0]));
        cc.setSelectedItem(t.component(comp));
        JComboBox<Object> pc = Ui.comboBox(new Object[0]);
        Runnable fill = () -> {
            pc.removeAllItems();
            ComponentUse c = (ComponentUse) cc.getSelectedItem();
            TemplateDef inner = Templates.resolve(c, model.source());
            if (inner != null) for (Port p : inner.ports) pc.addItem(p);
            if (inner == null || inner.port(port) == null) {
                if (port != null && c != null && c.id.equals(comp)) pc.addItem("(deleted port " + port + ")");
            }
        };
        fill.run();
        ComponentUse c0 = t.component(comp);
        TemplateDef inner0 = Templates.resolve(c0, model.source());
        if (inner0 != null && inner0.port(port) != null) pc.setSelectedItem(inner0.port(port));
        else if (pc.getItemCount() > 0) pc.setSelectedIndex(pc.getItemCount() - 1);
        cc.addActionListener(e -> {
            fill.run();
            ComponentUse c = (ComponentUse) cc.getSelectedItem();
            Object p = pc.getSelectedItem();
            if (c != null && p instanceof Port) onChange.accept(c.id, ((Port) p).id);
        });
        pc.addActionListener(e -> {
            ComponentUse c = (ComponentUse) cc.getSelectedItem();
            Object p = pc.getSelectedItem();
            if (c != null && p instanceof Port && !(c.id.equals(comp) && ((Port) p).id.equals(port))) onChange.accept(c.id, ((Port) p).id);
        });
        VerticalStack box = new VerticalStack();
        box.addRow(Ui.labeled(label, cc));
        box.addRow(Ui.labeled("Port", pc), Ui.GAP_XS);
        return box;
    }

    private Section relationSection(LayoutRelation r) {
        Section sec = new Section("Layout relation " + r.id);
        sec.addRow(Ui.hint("A <b>layout wish</b>, applied as constraints after a match was found; it never decides whether something matches.", W));
        JComboBox<LayoutRelation.Type> type = Ui.comboBox(LayoutRelation.Type.values());
        type.setSelectedItem(r.type);
        type.addActionListener(e -> edit("Change relation type", () -> {
            LayoutRelation k = model.def().relation(r.id);
            LayoutRelation.Type nt = (LayoutRelation.Type) type.getSelectedItem();
            if (nt.onComponents() != k.type.onComponents()) {
                if (nt.onComponents() && k.components.isEmpty()) for (LayoutRelation.PortRef p : k.ports) if (!k.components.contains(p.component)) k.components.add(p.component);
            }
            k.type = nt;
        }));
        sec.addRow(Ui.labeled("Type", type), Ui.GAP_S);
        sec.addRow(Ui.hint(meaning(r), W), Ui.GAP_XS);
        if (r.type.onComponents()) {
            sec.addRow(subheading("Components, in order"), Ui.GAP_S);
            for (ComponentUse c : model.def().components) {
                int at = r.components.indexOf(c.id);
                JCheckBox box = new JCheckBox((at >= 0 ? (at + 1) + ". " : "") + c.name, at >= 0);
                box.setOpaque(false);
                box.addActionListener(e -> edit("Change relation components", () -> {
                    LayoutRelation k = model.def().relation(r.id);
                    if (box.isSelected()) { if (!k.components.contains(c.id)) k.components.add(c.id); }
                    else k.components.remove(c.id);
                }));
                JButton up = Ui.button("↑", "Earlier in the order", e -> edit("Reorder relation", () -> {
                    LayoutRelation k = model.def().relation(r.id);
                    int i = k.components.indexOf(c.id);
                    if (i > 0) java.util.Collections.swap(k.components, i, i - 1);
                }));
                up.setEnabled(at > 0);
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.add(box, BorderLayout.CENTER);
                row.add(up, BorderLayout.EAST);
                sec.addRow(row, Ui.GAP_XS);
            }
            JSpinner gap = new JSpinner(new SpinnerNumberModel(r.gap, 0, 5000, 10));
            gap.addChangeListener(e -> edit("Change distance", () -> model.def().relation(r.id).gap = ((Number) gap.getValue()).doubleValue()));
            sec.addRow(Ui.labeled("At least (px)", gap), Ui.GAP_S);
            if (r.type == LayoutRelation.Type.GRID) {
                JSpinner cols = new JSpinner(new SpinnerNumberModel(r.columns, 1, 20, 1));
                cols.addChangeListener(e -> edit("Change columns", () -> model.def().relation(r.id).columns = ((Number) cols.getValue()).intValue()));
                sec.addRow(Ui.labeled("Columns", cols), Ui.GAP_XS);
            }
        } else {
            sec.addRow(subheading("Ports to align"), Ui.GAP_S);
            ComponentCanvas names = new ComponentCanvas(model, true);
            for (LayoutRelation.PortRef p : r.ports) {
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.add(new JLabel(names.portName(p.component, p.port)), BorderLayout.CENTER);
                row.add(Ui.button("✕", "Remove", e -> edit("Remove port from relation", () ->
                        model.def().relation(r.id).ports.removeIf(k -> k.component.equals(p.component) && k.port.equals(p.port)))), BorderLayout.EAST);
                sec.addRow(row, Ui.GAP_XS);
            }
            sec.addRow(portChooser("Add", null, null, (c, p) -> edit("Add port to relation", () -> {
                LayoutRelation k = model.def().relation(r.id);
                for (LayoutRelation.PortRef x : k.ports) if (x.component.equals(c) && x.port.equals(p)) return;
                k.ports.add(new LayoutRelation.PortRef(c, p));
            })), Ui.GAP_S);
        }
        JComboBox<Priority> prio = gui.PriorityControls.comboBox("relationPriority");
        prio.setSelectedItem(r.priority);
        prio.addActionListener(e -> edit("Change relation priority", () -> model.def().relation(r.id).priority = gui.PriorityControls.selected(prio)));
        sec.addRow(Ui.labeled("Priority", prio), Ui.GAP_S);
        JCheckBox on = new JCheckBox("Active", r.enabled);
        on.setOpaque(false);
        on.addActionListener(e -> edit("Switch relation", () -> model.def().relation(r.id).enabled = on.isSelected()));
        sec.addRow(on, Ui.GAP_XS);
        sec.addRow(Ui.button("Remove", null, e -> deleteSelected(model)), Ui.GAP_M);
        problems(sec, r.id);
        return sec;
    }

    /** The geometric meaning of a relation's type, as shown to the user. */
    static String meaning(LayoutRelation r) {
        switch (r.type) {
            case LEFT_OF: return "The bounding box of all nodes of the first component ends at least the distance before the second one's begins (a vertical dividing line between them).";
            case ABOVE: return "The bounding box of all nodes of the first component ends at least the distance above the second one's (a horizontal dividing line between them).";
            case ROW: return "The components' bounding boxes from left to right in this order, each at least the distance apart.";
            case COLUMN: return "The components' bounding boxes from top to bottom in this order, each at least the distance apart.";
            case GRID: return "The components row by row in the given number of columns; the bounding boxes of neighbouring columns and rows at least the distance apart.";
            case ALIGN_PORTS_H: return "The centres of exactly these port nodes on one horizontal line.";
            default: return "The centres of exactly these port nodes on one vertical line.";
        }
    }

    private Section templateSection() {
        TemplateDef t = model.def();
        Section sec = new Section("Composite template");
        JTextField name = new JTextField(t.name, 2);
        commit(name, v -> { if (!v.isEmpty()) edit("Rename template", () -> model.def().name = v); });
        sec.addRow(Ui.labeled("Name", name));
        JTextArea desc = new JTextArea(t.description, 3, 2);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (!desc.getText().equals(model.def().description)) edit("Change description", () -> model.def().description = desc.getText());
            }
        });
        sec.addRow(new JScrollPane(desc), Ui.GAP_XS);
        sec.addRow(subheading("Its own ports (to use it as a component)"), Ui.GAP_M);
        ComponentCanvas names = new ComponentCanvas(model, true);
        for (Port p : t.ports) {
            JTextField pn = new JTextField(p.name, 2);
            commit(pn, v -> { if (!v.isEmpty()) edit("Rename port", () -> model.def().port(p.id).name = v); });
            JPanel row = new JPanel(new BorderLayout(Ui.GAP_S, 0));
            row.setOpaque(false);
            row.add(pn, BorderLayout.CENTER);
            row.add(Ui.button("✕", "Delete the port (uses of this composite show it)", e -> {
                if (!TemplateEditor.confirmPortDeletion(this, model, java.util.Collections.emptySet(), p)) return;
                edit("Delete port", () -> model.def().ports.removeIf(k -> k.id.equals(p.id)));
            }), BorderLayout.EAST);
            sec.addRow(row, Ui.GAP_XS);
            sec.addRow(Ui.hint("→ " + Ui.esc(names.portName(p.component, p.componentPort)), W), Ui.GAP_XS);
        }
        JButton add = Ui.button("Add port…", "Passes on a port of a component under a name of the composite.", e -> addPort());
        sec.addRow(add, Ui.GAP_S);
        if (model.library != null) {
            for (TemplateLibrary.Update u : model.library.updatesFor(t)) {
                JButton b = Ui.button("Update '" + u.component.name + "' to rev. " + u.newer.revision + "…", null, ev -> editor.updateComponent(u.component, u.newer));
                sec.addRow(b, Ui.GAP_XS);
            }
        }
        return sec;
    }

    private void addPort() {
        TemplateDef t = model.def();
        if (t.components.isEmpty()) return;
        JComponent chooser = portChooser("Component", t.components.get(0).id, null, (c, p) -> { });
        JTextField name = new JTextField("Port");
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.add(chooser);
        p.add(Ui.labeled("Name", name));
        if (JOptionPane.showConfirmDialog(this, p, "Add port", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        JComboBox<?>[] boxes = findCombos(chooser);
        if (boxes.length < 2) return;
        ComponentUse c = (ComponentUse) boxes[0].getSelectedItem();
        Object port = boxes[1].getSelectedItem();
        if (c == null || !(port instanceof Port) || name.getText().trim().isEmpty()) return;
        if (t.portByName(name.getText().trim()) != null) {
            JOptionPane.showMessageDialog(this, "A port with this name exists already.", "Add port", JOptionPane.WARNING_MESSAGE);
            return;
        }
        edit("Add port", () -> {
            Port np = new Port(model.def().nextPortId(), name.getText().trim(), null);
            np.component = c.id;
            np.componentPort = ((Port) port).id;
            model.def().ports.add(np);
        });
    }

    private static JComboBox<?>[] findCombos(Container c) {
        List<JComboBox<?>> l = new ArrayList<>();
        collect(c, l);
        return l.toArray(new JComboBox<?>[0]);
    }

    private static void collect(Container c, List<JComboBox<?>> l) {
        for (Component k : c.getComponents()) {
            if (k instanceof JComboBox) l.add((JComboBox<?>) k);
            else if (k instanceof Container) collect((Container) k, l);
        }
    }

    private static JLabel subheading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.small().deriveFont(Font.BOLD));
        return l;
    }

    private void problems(Section sec, String id) {
        StringBuilder sb = new StringBuilder();
        for (TemplateValidator.Problem p : model.problems()) {
            if (!id.equals(p.elementId)) continue;
            sb.append("<font color='").append(p.severity == TemplateValidator.Severity.WARNING ? "#b06000" : "#c5221f").append("'>")
                    .append(Ui.esc(p.message)).append("</font><br>");
        }
        if (sb.length() > 0) sec.addRow(Ui.hint(sb.toString(), W), Ui.GAP_S);
    }

    private static void commit(JTextField f, Consumer<String> commit) {
        final String[] before = {f.getText()};
        Runnable r = () -> {
            String v = f.getText().trim();
            if (!v.equals(before[0].trim())) {
                before[0] = v;
                commit.accept(v);
            }
        };
        f.addActionListener(e -> r.run());
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { r.run(); }
        });
    }
}
