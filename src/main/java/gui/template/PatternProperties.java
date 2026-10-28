package gui.template;

import constraints.Constraint;
import constraints.Priority;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import gui.ConstraintCatalog;
import gui.ConstraintParamForm;
import gui.PriorityControls;
import gui.components.ScrollColumn;
import gui.components.Section;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.TemplateDef;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Right part of the pattern editor, showing the properties of the selection: for a constraint its name, state,
 * priority, values and elements per role; for a node its label, search conditions, size and ports; for an edge its
 * direction and role condition. The template's name and description are always shown below.
 *
 * @author Niklas Gröne
 */
final class PatternProperties extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int W = 250;
    private static final int ROW_H = 18;

    private final EditorModel model;
    private final VerticalStack column = new VerticalStack();
    private String shownKey = "";
    /** Set while this panel changes the model, so the rebuild does not replace the control being used. */
    private boolean selfChange;

    PatternProperties(EditorModel model) {
        super(new BorderLayout());
        this.model = model;
        setOpaque(false);
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_L));
        add(new ScrollColumn(column, W + 20), BorderLayout.CENTER);
        model.addListener(this::refresh);
        refresh();
    }

    /** Rebuilds when the selection or the structure changed (not for the panel's own value edits). */
    void refresh() {
        String key = model.selConstraint + "|" + model.selNodes + "|" + model.selEdges + "|" + model.def().nodes.size() + "|"
                + model.def().constraints.size() + "|" + model.def().ports.size() + "|" + model.canUndo() + model.undoName();
        if (selfChange && key.equals(shownKey)) return;
        shownKey = key;
        column.clearRows();
        ConstraintSpec s = model.selConstraint == null ? null : model.def().constraint(model.selConstraint);
        if (s != null) column.addRow(constraintSection(s));
        if (model.selNodes.size() == 1) {
            column.addRow(nodeSection(model.def().node(model.selNodes.iterator().next())), sectionGap());
        } else if (model.selNodes.size() > 1) {
            column.addRow(multiSection(), sectionGap());
        }
        if (model.selEdges.size() == 1) column.addRow(edgeSection(model.def().edge(model.selEdges.iterator().next())), sectionGap());
        column.addRow(templateSection(), sectionGap());
        column.revalidate();
        column.repaint();
    }

    private int sectionGap() {
        return column.getRowCount() == 0 ? 0 : Ui.SECTION_GAP;
    }

    private void edit(String name, Runnable r) {
        selfChange = true;
        try {
            model.change(name, r);
        } finally {
            selfChange = false;
        }
    }

    // ================================================================== constraint

    private Section constraintSection(ConstraintSpec s) {
        Section sec = new Section("Constraint " + s.id);
        JLabel title = new JLabel(TemplateEditor.title(s));
        title.setFont(Ui.bold());
        sec.addRow(title);
        if (s.kind() == null) {
            sec.addRow(Ui.hint("Type '" + Ui.esc(s.type) + "' of a newer version: it is kept unchanged when saving, but it cannot be "
                    + "edited or applied with this version.", W), Ui.GAP_XS);
            return sec;
        }
        JTextField name = new JTextField(s.name == null ? "" : s.name, 2);
        name.setToolTipText(Ui.tooltip("Your name for the constraint (shown in lists and conflict messages); empty: its kind."));
        commitOnFocus(name, v -> edit("Rename constraint", () -> model.def().constraint(s.id).name = v.isEmpty() ? null : v));
        sec.addRow(Ui.labeled("Name", name), Ui.GAP_S);
        JCheckBox on = new JCheckBox("Active", s.enabled);
        on.setOpaque(false);
        on.setToolTipText(Ui.tooltip("Off: the constraint is kept in the template but starts switched off in every instance."));
        on.addActionListener(e -> edit(on.isSelected() ? "Switch constraint on" : "Switch constraint off", () -> model.def().constraint(s.id).enabled = on.isSelected()));
        sec.addRow(on, Ui.GAP_XS);
        JComboBox<Priority> prio = PriorityControls.comboBox("specPriority");
        prio.setSelectedItem(s.priority);
        prio.setToolTipText(Ui.tooltip(PriorityControls.TOOLTIP));
        prio.addActionListener(e -> edit("Change priority", () -> model.def().constraint(s.id).priority = PriorityControls.selected(prio)));
        sec.addRow(Ui.labeled("Priority", prio), Ui.GAP_XS);

        Constraint c = model.constraint(s.id);
        ConstraintCatalog.Info info = ConstraintCatalog.info(s.kind());
        if (c != null && info != null) {
            List<ConstraintCatalog.Editable> editables = info.editablesFor(c);
            if (!editables.isEmpty()) {
                List<ConstraintCatalog.Param> params = new ArrayList<>();
                Map<String, Object> values = new LinkedHashMap<>();
                for (ConstraintCatalog.Editable ed : editables) {
                    params.add(ed.param);
                    values.put(ed.param.key, ed.get.apply(c));
                }
                final ConstraintParamForm[] form = new ConstraintParamForm[1];
                form[0] = new ConstraintParamForm(params, values, () -> {
                    Map<String, Object> v = form[0].values();
                    edit("Change " + info.title, () -> {
                        Constraint k = model.constraint(s.id);
                        if (k == null) return;
                        for (ConstraintCatalog.Editable ed : info.editablesFor(k)) {
                            Object val = v.get(ed.param.key);
                            if (val == null || (val instanceof Double && Double.isNaN((Double) val))) continue;
                            ed.set.accept(k, val);
                        }
                        model.writeBack(s.id, k);
                    });
                });
                sec.addRow(form[0], Ui.GAP_S);
            }
        }
        ConstraintSpecs.Adapter a = ConstraintSpecs.adapter(s.kind());
        for (ConstraintSpecs.RoleInfo r : a.roles) {
            if (r.optional && s.role(r.name).isEmpty() && !s.roles.isEmpty() && otherOptionalUsed(a, s, r)) continue;
            sec.addRow(roleEditor(s, r), Ui.GAP_M);
        }
        List<String> problems = ConstraintSpecs.validate(s, model.def().nodeIds(), model.def().edgeIds());
        String note = model.notes.get(s.id), unmet = model.unmet.get(s.id);
        StringBuilder sb = new StringBuilder();
        if (note != null) sb.append(Ui.esc(note)).append("<br>");
        for (String p : problems) sb.append("<font color='#c5221f'>").append(Ui.esc(p)).append("</font><br>");
        if (problems.isEmpty() && unmet != null) sb.append("<font color='#b06000'>Does not hold in the drawing: ").append(Ui.esc(unmet)).append("</font>");
        if (sb.length() > 0) sec.addRow(Ui.hint(sb.toString(), W), Ui.GAP_S);
        if (info != null) sec.addRow(Ui.hint(Ui.esc(info.description), W), Ui.GAP_S);
        return sec;
    }

    private static boolean otherOptionalUsed(ConstraintSpecs.Adapter a, ConstraintSpec s, ConstraintSpecs.RoleInfo r) {
        for (ConstraintSpecs.RoleInfo o : a.roles) if (o != r && o.optional && !s.role(o.name).isEmpty()) return true;
        return false;
    }

    /** The elements of one role in order, with add / remove / move. */
    private JComponent roleEditor(ConstraintSpec s, ConstraintSpecs.RoleInfo r) {
        VerticalStack box = new VerticalStack();
        box.addRow(subheading(r.label + (r.ordered ? " (in order)" : "")));
        DefaultListModel<String> m = new DefaultListModel<>();
        List<String> ids = s.role(r.name);
        for (String id : ids) m.addElement(id);
        JList<String> list = new JList<>(m);
        list.setVisibleRowCount(Math.max(3, Math.min(6, ids.size())));
        list.setFixedCellWidth(1);
        list.setFixedCellHeight(ROW_H);
        list.getAccessibleContext().setAccessibleName(r.label);
        list.setCellRenderer((jl, id, i, sel, f) -> {
            String text;
            if (id == null) text = "(empty cell)";
            else if (r.edges) {
                PatternEdge e = model.def().edge(id);
                text = e == null ? id + " — missing" : edgeText(e);
            } else {
                PatternNode n = model.def().node(id);
                text = n == null ? id + " — deleted" : n.displayName();
            }
            JLabel c = new JLabel((r.ordered ? (i + 1) + ". " : "• ") + text);
            c.setFont(Ui.small());
            c.setOpaque(true);
            c.setBackground(sel ? jl.getSelectionBackground() : Color.WHITE);
            c.setForeground(sel ? jl.getSelectionForeground() : id != null && !r.edges && model.def().node(id) == null ? Ui.ERROR : Ui.TEXT);
            return c;
        });
        list.addListSelectionListener(e -> {
            String id = list.getSelectedValue();
            if (id != null && !r.edges && model.def().node(id) != null) {
                model.selNodes.clear();
                model.selNodes.add(id);
                // The constraint stays selected; the node is only highlighted.
                shownKey = "";
                selfChange = true;
                model.fire();
                selfChange = false;
            }
        });
        int rows = Math.max(2, Math.min(6, ids.size()));
        box.addRow(new JScrollPane(list) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, rows * ROW_H + 6); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        }, Ui.GAP_XS);
        JButton add = Ui.button("+ Add", r.edges ? "Adds the edge selected on the canvas."
                : "Adds the nodes selected on the canvas (in selection order).", e -> edit("Add elements", () -> {
                    ConstraintSpec k = model.def().constraint(s.id);
                    List<String> cur = new ArrayList<>(k.role(r.name));
                    for (String id : r.edges ? model.selEdges : model.selNodes) if (!cur.contains(id)) cur.add(id);
                    k.role(r.name, cur);
                    ConstraintSpecs.fillDerived(k, model.geometry());
                }));
        JButton remove = Ui.button("−", "Removes the chosen element from this constraint.", e -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            edit("Remove element", () -> {
                ConstraintSpec k = model.def().constraint(s.id);
                List<String> cur = new ArrayList<>(k.role(r.name));
                cur.remove(i);
                k.role(r.name, cur);
                ConstraintSpecs.fillDerived(k, model.geometry());
            });
        });
        JButton up = Ui.button("↑", "Moves the chosen element earlier.", e -> move(s, r, list.getSelectedIndex(), -1));
        JButton down = Ui.button("↓", "Moves the chosen element later.", e -> move(s, r, list.getSelectedIndex(), 1));
        up.setEnabled(r.ordered);
        down.setEnabled(r.ordered);
        box.addRow(Ui.row(add, remove, up, down), Ui.GAP_XS);
        return box;
    }

    private void move(ConstraintSpec s, ConstraintSpecs.RoleInfo r, int i, int d) {
        List<String> cur = new ArrayList<>(s.role(r.name));
        int j = i + d;
        if (i < 0 || j < 0 || j >= cur.size()) return;
        edit("Reorder elements", () -> {
            ConstraintSpec k = model.def().constraint(s.id);
            List<String> l = new ArrayList<>(k.role(r.name));
            Collections.swap(l, i, j);
            k.role(r.name, l);
        });
    }

    private String edgeText(PatternEdge e) {
        PatternNode a = model.def().node(e.source), b = model.def().node(e.target);
        return (a == null ? e.source : a.displayName()) + (e.directed ? " → " : " – ") + (b == null ? e.target : b.displayName());
    }

    // ================================================================== node

    private Section nodeSection(PatternNode n) {
        Section sec = new Section("Node " + n.id);
        JTextField label = new JTextField(n.label, 2);
        label.setToolTipText(Ui.tooltip("Shown in the editor and in match descriptions; searching uses it only through a condition."));
        commitOnFocus(label, v -> edit("Rename node", () -> model.def().node(n.id).label = v));
        sec.addRow(Ui.labeled("Label", label));
        sec.addRow(conditionEditor("Semantic role", n.condition(Condition.SBGN_ROLE), SbgnRoles.nodeRoles(TemplateWorkbench.activeGraph()),
                c -> edit("Change role condition", () -> {
                    PatternNode k = model.def().node(n.id);
                    k.setCondition(c);
                    if (c.op == Condition.Op.EQUALS) k.role = c.values.get(0);
                })), Ui.GAP_S);
        for (Condition c : n.conditions) {
            if (Condition.SBGN_ROLE.equals(c.attribute)) continue;
            sec.addRow(extraCondition(c, cond -> edit("Change condition", () -> model.def().node(n.id).setCondition(cond)),
                    () -> edit("Remove condition", () -> model.def().node(n.id).conditions.removeIf(k -> k.attribute.equals(c.attribute)))), Ui.GAP_S);
        }
        JButton addCond = Ui.button("Add condition…", "A further search condition, e.g. on the label or another attribute.", e -> {
            String path = (String) JOptionPane.showInputDialog(this, "Attribute (label, or folder.name such as sbgn.compartment):",
                    "Add condition", JOptionPane.PLAIN_MESSAGE, null, null, "label");
            if (path == null || path.trim().isEmpty()) return;
            edit("Add condition", () -> model.def().node(n.id).setCondition(new Condition(path.trim(), Condition.Op.EXISTS, null)));
        });
        sec.addRow(addCond, Ui.GAP_XS);
        JSpinner w = new JSpinner(new SpinnerNumberModel(n.width, 1, 5000, 5)), h = new JSpinner(new SpinnerNumberModel(n.height, 1, 5000, 5));
        w.addChangeListener(e -> edit("Resize node", () -> model.def().node(n.id).width = ((Number) w.getValue()).doubleValue()));
        h.addChangeListener(e -> edit("Resize node", () -> model.def().node(n.id).height = ((Number) h.getValue()).doubleValue()));
        sec.addRow(Ui.labeled("Size", Ui.row(w, h)), Ui.GAP_S);

        sec.addRow(subheading("Ports (named connection points)"), Ui.GAP_M);
        for (Port p : model.def().portsOf(n.id)) {
            JTextField pn = new JTextField(p.name, 2);
            pn.setToolTipText(Ui.tooltip("Renaming keeps every use working (uses refer to the port's id " + p.id + ")."));
            commitOnFocus(pn, v -> {
                if (v.isEmpty()) return;
                edit("Rename port", () -> model.def().port(p.id).name = v);
            });
            JButton del = Ui.button("Delete", "Deletes the port; composites that use it show the uses as invalid.", e -> {
                if (!TemplateEditor.confirmPortDeletion(this, model, Collections.singleton(n.id), p)) return;
                edit("Delete port", () -> model.def().ports.removeIf(k -> k.id.equals(p.id)));
            });
            JPanel row = new JPanel(new BorderLayout(Ui.GAP_S, 0));
            row.setOpaque(false);
            row.add(new JLabel("⚑"), BorderLayout.WEST);
            row.add(pn, BorderLayout.CENTER);
            row.add(del, BorderLayout.EAST);
            sec.addRow(row, Ui.GAP_XS);
        }
        JButton makePort = Ui.button("Make port…", "Names this node as a connection point (e.g. Input, Output, Centre) for composite templates.", e -> {
            String name = JOptionPane.showInputDialog(this, "Name of the port (e.g. Input, Output, Centre):", suggestPortName(n));
            if (name == null || name.trim().isEmpty()) return;
            if (model.def().portByName(name.trim()) != null) {
                JOptionPane.showMessageDialog(this, "A port named '" + name.trim() + "' exists already.", "Make port", JOptionPane.WARNING_MESSAGE);
                return;
            }
            edit("Make port", () -> model.def().ports.add(new Port(model.def().nextPortId(), name.trim(), n.id)));
        });
        makePort.setName("makePort");
        sec.addRow(makePort, Ui.GAP_XS);

        List<ConstraintSpec> cs = model.constraintsOf(n.id);
        sec.addRow(subheading(cs.isEmpty() ? "In no constraint."
                : "In " + cs.size() + (cs.size() == 1 ? " constraint:" : " constraints:")), Ui.GAP_M);
        for (ConstraintSpec s : cs) {
            JButton b = Ui.button(TemplateEditor.title(s), "Select this constraint.", e -> {
                model.selConstraint = s.id;
                model.fire();
            });
            b.setHorizontalAlignment(SwingConstants.LEFT);
            sec.addRow(b, Ui.GAP_XS);
        }
        JButton del = Ui.button("Delete node", "Deletes the node and its edges; constraints lose it (and show it).", e -> {
            if (!TemplateEditor.confirmPortDeletion(this, model, Collections.singleton(n.id))) return;
            model.change("Delete node", () -> model.deleteElements(Collections.singleton(n.id), Collections.emptyList()));
        });
        sec.addRow(del, Ui.GAP_M);
        return sec;
    }

    private static String suggestPortName(PatternNode n) {
        return n.label != null && !n.label.trim().isEmpty() ? n.label.trim() : "Port";
    }

    private Section multiSection() {
        Section sec = new Section(model.selNodes.size() + " nodes");
        sec.addRow(Ui.hint("Add a constraint to them on the left, or drag them together. Delete removes them.", W));
        JButton del = Ui.button("Delete nodes", null, e -> {
            if (!TemplateEditor.confirmPortDeletion(this, model, model.selNodes)) return;
            List<String> ids = new ArrayList<>(model.selNodes);
            model.change("Delete nodes", () -> model.deleteElements(ids, Collections.emptyList()));
        });
        sec.addRow(del, Ui.GAP_S);
        return sec;
    }

    // ================================================================== edge

    private Section edgeSection(PatternEdge e) {
        Section sec = new Section("Edge " + e.id);
        sec.addRow(new JLabel(edgeText(e)));
        JCheckBox dir = new JCheckBox("Direction counts", e.directed);
        dir.setOpaque(false);
        dir.setToolTipText(Ui.tooltip("On: a graph edge must run the same way. Off: either way fits."));
        dir.addActionListener(ev -> edit("Change direction", () -> model.def().edge(e.id).directed = dir.isSelected()));
        JButton rev = Ui.button("Reverse", "Swaps the edge's start and end.", ev -> edit("Reverse edge", () -> {
            PatternEdge k = model.def().edge(e.id);
            String t = k.source;
            k.source = k.target;
            k.target = t;
        }));
        sec.addRow(Ui.row(dir, rev), Ui.GAP_S);
        sec.addRow(conditionEditor("Semantic role", e.condition(Condition.SBGN_ROLE), SbgnRoles.edgeRoles(TemplateWorkbench.activeGraph()),
                c -> edit("Change edge role", () -> {
                    PatternEdge k = model.def().edge(e.id);
                    k.setCondition(c);
                    if (c.op == Condition.Op.EQUALS) k.role = c.values.get(0);
                })), Ui.GAP_S);
        JButton del = Ui.button("Delete edge", null, ev -> model.change("Delete edge",
                () -> model.deleteElements(Collections.emptyList(), Collections.singleton(e.id))));
        sec.addRow(del, Ui.GAP_M);
        return sec;
    }

    // ================================================================== template

    private Section templateSection() {
        TemplateDef t = model.def();
        Section sec = new Section("Template");
        JTextField name = new JTextField(t.name, 2);
        commitOnFocus(name, v -> { if (!v.isEmpty()) edit("Rename template", () -> model.def().name = v); });
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
        sec.addRow(Ui.hint("Revision " + t.revision + " · " + t.nodes.size() + " nodes · " + t.edges.size() + " edges · "
                + t.constraints.size() + " constraints · " + t.ports.size() + " ports<br><font color='#9aa0a6'>id " + Ui.esc(t.id)
                + "</font><br>Positions are local (origin = top left of the pattern); fitting the view never changes them.", W), Ui.GAP_S);
        return sec;
    }

    // ================================================================== helpers

    private static JLabel subheading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.small().deriveFont(Font.BOLD));
        return l;
    }

    /** A condition row: rule (any value / is / is one of / has a value) and the value(s). */
    private JComponent conditionEditor(String label, Condition current, List<String> proposals, Consumer<Condition> onChange) {
        Condition c = current == null ? Condition.any(Condition.SBGN_ROLE) : current;
        VerticalStack box = new VerticalStack();
        box.addRow(subheading(label));
        JComboBox<Condition.Op> op = Ui.comboBox(Condition.Op.values());
        op.setSelectedItem(c.op);
        op.setToolTipText(Ui.tooltip("<b>any value</b>: not looked at · <b>is</b>: equal to the value · <b>is one of</b>: one of the "
                + "values (comma separated) · <b>has a value</b>: any non-empty value"));
        JComboBox<String> value = new JComboBox<>(proposals.toArray(new String[0]));
        value.setEditable(true);
        value.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean s, boolean f) {
                String p = v == null ? null : SbgnRoles.pretty(String.valueOf(v));
                return super.getListCellRendererComponent(list, p == null ? v : p + " (" + v + ")", i, s, f);
            }
        });
        value.setSelectedItem(String.join(", ", c.values));
        Runnable commit = () -> {
            Condition.Op o = (Condition.Op) op.getSelectedItem();
            String v = value.getEditor().getItem() == null ? "" : String.valueOf(value.getEditor().getItem()).trim();
            List<String> vals = new ArrayList<>();
            if (o == Condition.Op.IN_LIST) for (String p : v.split(",")) { if (!p.trim().isEmpty()) vals.add(p.trim()); }
            else if (o == Condition.Op.EQUALS && !v.isEmpty()) vals.add(v);
            Condition n = new Condition(c.attribute, o, vals);
            if (!n.equals(current)) onChange.accept(n);
        };
        op.addActionListener(e -> {
            value.setEnabled(op.getSelectedItem() == Condition.Op.EQUALS || op.getSelectedItem() == Condition.Op.IN_LIST);
            commit.run();
        });
        value.setEnabled(c.op == Condition.Op.EQUALS || c.op == Condition.Op.IN_LIST);
        value.addActionListener(e -> { if ("comboBoxEdited".equals(e.getActionCommand()) || "comboBoxChanged".equals(e.getActionCommand())) commit.run(); });
        box.addRow(Ui.row(op, value), Ui.GAP_XS);
        return box;
    }

    private JComponent extraCondition(Condition c, Consumer<Condition> onChange, Runnable remove) {
        JPanel p = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        p.setOpaque(false);
        JComponent editor = conditionEditor(SbgnRoles.attributeName(c.attribute), c, Collections.emptyList(), onChange);
        p.add(editor, BorderLayout.CENTER);
        p.add(Ui.button("✕", "Removes this condition.", e -> remove.run()), BorderLayout.EAST);
        return p;
    }

    private static void commitOnFocus(JTextField f, Consumer<String> commit) {
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
