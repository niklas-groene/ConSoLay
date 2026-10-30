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
import java.util.ArrayList;
import java.util.List;

/**
 * Right part of the composite editor: the settings of the selected component, structure link or layout relation, or of
 * the composite template itself when nothing is selected. For a component, the settings of this use are kept apart from
 * editing the reused template (a new revision that other uses see as an update) and from making an independent copy.
 *
 * @author Niklas Gröne
 */
final class CompositeProperties extends PropertiesPanel {

    private static final long serialVersionUID = 1L;

    private final TemplateEditor editor;
    /** The component a port was added from last: the next port likely comes from it too. */
    private String addedFrom;

    CompositeProperties(EditorModel model, TemplateEditor editor) {
        super(model);
        this.editor = editor;
        refresh();
    }

    @Override
    String selectionKey() {
        return model.selComponent + "|" + model.selLink + "|" + model.selRelation;
    }

    @Override
    void build() {
        TemplateDef t = model.def();
        if (model.selComponent != null && t.component(model.selComponent) != null) column.addRow(componentSection(t.component(model.selComponent)));
        else if (model.selLink != null && t.link(model.selLink) != null) column.addRow(linkSection(t.link(model.selLink)));
        else if (model.selRelation != null && t.relation(model.selRelation) != null) column.addRow(relationSection(t.relation(model.selRelation)));
        column.addRow(templateSection(), column.getRowCount() == 0 ? 0 : Ui.SECTION_GAP);
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
        onText(name, v -> {
            ComponentUse now = model.def().component(c.id);
            if (v.isEmpty() || now == null) return;
            for (ComponentUse o : model.def().components) {
                if (o != now && o.name.equals(v)) {
                    model.message = "Another component is called '" + v + "' already; give each use its own role.";
                    model.fire();
                    return;
                }
            }
            tweak("Rename component", () -> now.name = v);
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
        prio.addActionListener(e -> tweak("Change component priority", () -> {
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
                // a use can only switch off: a constraint that is off in the template stays off
                on.setEnabled(c.includeConstraints && s.enabled);
                if (!s.enabled) on.setToolTipText(Ui.tooltip("Switched off in the template itself; switch it on there "
                        + "(Edit template…)."));
                on.addActionListener(e -> tweak("Switch component constraint", () -> {
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
            dir.addActionListener(e -> tweak("Change direction", () -> model.def().link(l.id).direction = (StructureLink.Direction) dir.getSelectedItem()));
            sec.addRow(Ui.labeled("Direction", dir), Ui.GAP_S);
            Condition cur = null;
            for (Condition c : l.edgeConditions) if (Condition.SBGN_ROLE.equals(c.attribute)) cur = c;
            List<String> roles = new ArrayList<>();
            roles.add("(any role)");
            roles.addAll(SbgnRoles.edgeRoles(TemplateWorkbench.activeGraph()));
            JComboBox<String> role = new JComboBox<>(roles.toArray(new String[0]));
            role.setEditable(true);
            String shown = cur == null || cur.op == Condition.Op.ANY ? "(any role)" : String.join(", ", cur.values);
            role.setSelectedItem(shown);
            role.setToolTipText(Ui.tooltip("The SBGN role the joining edge must have (checked in \"Structure and semantics\" searches)."));
            final String[] last = {shown};
            role.addActionListener(e -> {
                String v = String.valueOf(role.getSelectedItem()).trim();
                if (v.equals(last[0])) return;   // an editable box reports one choice twice
                last[0] = v;
                tweak("Change link role", () -> {
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
        return new PortChoice(label, comp, port, onChange).box;
    }

    /** A component and one of its ports, chosen in two combo boxes. */
    private final class PortChoice {
        final VerticalStack box = new VerticalStack();
        private final JComboBox<ComponentUse> cc;
        private final JComboBox<Object> pc = Ui.comboBox(new Object[0]);
        private boolean filling;

        PortChoice(String label, String comp, String port, java.util.function.BiConsumer<String, String> onChange) {
            TemplateDef t = model.def();
            cc = Ui.comboBox(t.components.toArray(new ComponentUse[0]));
            cc.setSelectedItem(t.component(comp));
            fill(comp, port);
            TemplateDef inner0 = Templates.resolve(t.component(comp), model.source());
            if (inner0 != null && inner0.port(port) != null) pc.setSelectedItem(inner0.port(port));
            else if (pc.getItemCount() > 0) pc.setSelectedIndex(pc.getItemCount() - 1);
            cc.addActionListener(e -> {
                fill(comp, port);
                String[] p = picked();
                if (onChange != null && p != null) onChange.accept(p[0], p[1]);
            });
            pc.addActionListener(e -> {
                if (filling) return;
                String[] p = picked();
                if (onChange != null && p != null && !(p[0].equals(comp) && p[1].equals(port))) onChange.accept(p[0], p[1]);
            });
            box.addRow(Ui.labeled(label, cc));
            box.addRow(Ui.labeled("Port", pc), Ui.GAP_XS);
        }

        private void fill(String comp, String port) {
            filling = true;   // the first item added selects itself: that is no choice of the user's
            try {
                pc.removeAllItems();
                ComponentUse c = (ComponentUse) cc.getSelectedItem();
                TemplateDef inner = Templates.resolve(c, model.source());
                if (inner != null) for (Port p : inner.ports) pc.addItem(p);
                if (inner == null || inner.port(port) == null) {
                    if (port != null && c != null && c.id.equals(comp)) pc.addItem("(deleted port " + port + ")");
                }
            } finally {
                filling = false;
            }
        }

        /** Component id and port id chosen now, or null if no real port is chosen. */
        String[] picked() {
            ComponentUse c = (ComponentUse) cc.getSelectedItem();
            Object p = pc.getSelectedItem();
            return c != null && p instanceof Port ? new String[]{c.id, ((Port) p).id} : null;
        }
    }

    private Section relationSection(LayoutRelation r) {
        Section sec = new Section("Layout relation " + r.id);
        sec.addRow(Ui.hint("A <b>layout wish</b>, applied as constraints after a match was found; it never decides whether something matches.", W));
        JComboBox<LayoutRelation.Type> type = Ui.comboBox(LayoutRelation.Type.values());
        type.setSelectedItem(r.type);
        type.addActionListener(e -> {
            LayoutRelation.Type nt = (LayoutRelation.Type) type.getSelectedItem();
            if (nt != model.def().relation(r.id).type) edit("Change relation type", () -> setRelationType(model.def().relation(r.id), nt));
        });
        sec.addRow(Ui.labeled("Type", type), Ui.GAP_S);
        sec.addRow(Ui.hint(meaning(r), W), Ui.GAP_XS);
        if (r.type.onComponents()) {
            if (isPair(r.type)) pairEditor(sec, r);
            else orderedEditor(sec, r);
            sec.addRow(Ui.labeled("At least (px)", spinner(r.gap, 0, 5000, 10, "gap " + r.id, "Change distance",
                    v -> model.def().relation(r.id).gap = v)), Ui.GAP_S);
            if (r.type == LayoutRelation.Type.GRID) {
                sec.addRow(Ui.labeled("Columns", spinner(r.columns, 1, 20, 1, "columns " + r.id, "Change columns",
                        v -> model.def().relation(r.id).columns = (int) v)), Ui.GAP_XS);
            }
        } else {
            sec.addRow(subheading("Ports to align"), Ui.GAP_S);
            for (LayoutRelation.PortRef p : r.ports) {
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.add(new JLabel(model.portName(p.component, p.port)), BorderLayout.CENTER);
                row.add(Ui.button("✕", "Remove", e -> edit("Remove port from relation", () ->
                        model.def().relation(r.id).ports.removeIf(k -> k.component.equals(p.component) && k.port.equals(p.port)))), BorderLayout.EAST);
                sec.addRow(row, Ui.GAP_XS);
            }
            sec.addRow(portAdder(r), Ui.GAP_S);
        }
        JComboBox<Priority> prio = gui.PriorityControls.comboBox("relationPriority");
        prio.setSelectedItem(r.priority);
        prio.addActionListener(e -> tweak("Change relation priority", () -> model.def().relation(r.id).priority = gui.PriorityControls.selected(prio)));
        sec.addRow(Ui.labeled("Priority", prio), Ui.GAP_S);
        JCheckBox on = new JCheckBox("Active", r.enabled);
        on.setOpaque(false);
        on.addActionListener(e -> tweak("Switch relation", () -> model.def().relation(r.id).enabled = on.isSelected()));
        sec.addRow(on, Ui.GAP_XS);
        sec.addRow(Ui.button("Remove", null, e -> deleteSelected(model)), Ui.GAP_M);
        problems(sec, r.id);
        return sec;
    }

    private static boolean isPair(LayoutRelation.Type t) {
        return t == LayoutRelation.Type.LEFT_OF || t == LayoutRelation.Type.ABOVE;
    }

    /** Another type for {@code k}: its members carry over as far as they fit, so it stays valid where it can. */
    private void setRelationType(LayoutRelation k, LayoutRelation.Type nt) {
        if (nt.onComponents()) {
            if (k.components.isEmpty()) for (LayoutRelation.PortRef p : k.ports) if (!k.components.contains(p.component)) k.components.add(p.component);
            for (ComponentUse c : model.def().components) {
                if (k.components.size() >= 2) break;
                if (!k.components.contains(c.id)) k.components.add(c.id);
            }
            while (isPair(nt) && k.components.size() > 2) k.components.remove(k.components.size() - 1);
        } else if (k.ports.isEmpty()) {
            for (String id : k.components) {
                String p = model.firstPort(id);
                if (p != null) k.ports.add(new LayoutRelation.PortRef(id, p));
            }
        }
        k.type = nt;
    }

    /** "Left of" and "above" take exactly two components: one box each, and a button to swap them. */
    private void pairEditor(Section sec, LayoutRelation r) {
        boolean leftOf = r.type == LayoutRelation.Type.LEFT_OF;
        sec.addRow(pairMember(leftOf ? "Left" : "Upper", r, 0), Ui.GAP_S);
        sec.addRow(pairMember(leftOf ? "Right" : "Lower", r, 1), Ui.GAP_XS);
        JButton swap = Ui.button("Swap", "Exchanges the two components.", e -> edit("Swap relation components", () ->
                java.util.Collections.reverse(model.def().relation(r.id).components)));
        swap.setName("swapRelation");
        swap.setEnabled(r.components.size() == 2);
        sec.addRow(swap, Ui.GAP_XS);
    }

    private JComponent pairMember(String label, LayoutRelation r, int index) {
        JComboBox<ComponentUse> box = Ui.comboBox(model.def().components.toArray(new ComponentUse[0]));
        box.setSelectedItem(index < r.components.size() ? model.def().component(r.components.get(index)) : null);
        box.addActionListener(e -> {
            ComponentUse c = (ComponentUse) box.getSelectedItem();
            if (c != null) edit("Change relation components", () -> setPairMember(model.def().relation(r.id), index, c.id));
        });
        return Ui.labeled(label, box);
    }

    /** Puts {@code id} at {@code index} of a pair; choosing the other member's component swaps the two. */
    private static void setPairMember(LayoutRelation k, int index, String id) {
        String a = !k.components.isEmpty() ? k.components.get(0) : null, b = k.components.size() > 1 ? k.components.get(1) : null;
        if (index == 0) {
            if (id.equals(b)) b = a;
            a = id;
        } else {
            if (id.equals(a)) a = b;
            b = id;
        }
        k.components.clear();
        if (a != null) k.components.add(a);
        if (b != null) k.components.add(b);
    }

    /** A row, column or grid: the members in their order, then the other components to add. */
    private void orderedEditor(Section sec, LayoutRelation r) {
        sec.addRow(subheading("Components, in order"), Ui.GAP_S);
        List<ComponentUse> rows = new ArrayList<>();
        for (String id : r.components) if (model.def().component(id) != null) rows.add(model.def().component(id));
        for (ComponentUse c : model.def().components) if (!rows.contains(c)) rows.add(c);
        for (ComponentUse c : rows) {
            int at = r.components.indexOf(c.id);
            JCheckBox box = new JCheckBox((at >= 0 ? (at + 1) + ". " : "") + c.name, at >= 0);
            box.setOpaque(false);
            box.addActionListener(e -> edit("Change relation components", () -> {
                LayoutRelation k = model.def().relation(r.id);
                if (box.isSelected()) { if (!k.components.contains(c.id)) k.components.add(c.id); }
                else k.components.remove(c.id);
            }));
            JButton up = Ui.button("↑", "Earlier in the order", e -> moveMember(r, c.id, -1));
            JButton down = Ui.button("↓", "Later in the order", e -> moveMember(r, c.id, 1));
            up.setEnabled(at > 0);
            down.setEnabled(at >= 0 && at < r.components.size() - 1);
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(box, BorderLayout.CENTER);
            row.add(Ui.row(up, down), BorderLayout.EAST);
            sec.addRow(row, Ui.GAP_XS);
        }
    }

    private void moveMember(LayoutRelation r, String id, int d) {
        edit("Reorder relation", () -> {
            LayoutRelation k = model.def().relation(r.id);
            int i = k.components.indexOf(id);
            if (i >= 0 && i + d >= 0 && i + d < k.components.size()) java.util.Collections.swap(k.components, i, i + d);
        });
    }

    /** Component and port to add to a port relation, added only with its button. */
    private JComponent portAdder(LayoutRelation r) {
        TemplateDef t = model.def();
        String first = addedFrom != null && model.firstPort(addedFrom) != null ? addedFrom : null;
        for (ComponentUse c : t.components) {
            if (first == null && model.firstPort(c.id) != null) first = c.id;
        }
        if (first == null) return Ui.hint("<font color='#b06000'>No component has ports yet: make ports in their templates.</font>", W);
        PortChoice choice = new PortChoice("Add", first, null, null);
        JButton add = Ui.button("Add port", "Adds the chosen port to this relation.", e -> {
            String[] picked = choice.picked();
            if (picked == null) return;
            addedFrom = picked[0];
            LayoutRelation k = model.def().relation(r.id);
            for (LayoutRelation.PortRef x : k.ports) {
                if (x.component.equals(picked[0]) && x.port.equals(picked[1])) {
                    model.message = model.portName(picked[0], picked[1]) + " is in this relation already.";
                    model.fire();
                    return;
                }
            }
            edit("Add port to relation", () -> k.ports.add(new LayoutRelation.PortRef(picked[0], picked[1])));
        });
        add.setName("addRelationPort");
        VerticalStack box = new VerticalStack();
        box.addRow(choice.box);
        box.addRow(add, Ui.GAP_XS);
        return box;
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
        onText(name, v -> { if (!v.isEmpty()) tweak("Rename template", () -> model.def().name = v); });
        sec.addRow(Ui.labeled("Name", name));
        sec.addRow(descriptionField(t.description, v -> tweak("Change description", () -> model.def().description = v)), Ui.GAP_XS);
        sec.addRow(subheading("Its own ports (to use it as a component)"), Ui.GAP_M);
        for (Port p : t.ports) {
            JTextField pn = new JTextField(p.name, 2);
            onText(pn, v -> {
                Port now = model.def().port(p.id);
                if (!v.isEmpty() && now != null) tweak("Rename port", () -> now.name = v);
            });
            JPanel row = new JPanel(new BorderLayout(Ui.GAP_S, 0));
            row.setOpaque(false);
            row.add(pn, BorderLayout.CENTER);
            row.add(Ui.button("✕", "Delete the port (uses of this composite show it)", e -> {
                if (!TemplateEditor.confirmPortDeletion(this, model, java.util.Collections.emptySet(), p)) return;
                edit("Delete port", () -> model.def().ports.removeIf(k -> k.id.equals(p.id)));
            }), BorderLayout.EAST);
            sec.addRow(row, Ui.GAP_XS);
            sec.addRow(Ui.hint("→ " + Ui.esc(model.portName(p.component, p.componentPort)), W), Ui.GAP_XS);
        }
        JButton add = Ui.button("Add port…", "Passes on a port of a component under a name of the composite.", e -> addPort());
        add.setName("addCompositePort");
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
        String first = model.selComponent != null && model.firstPort(model.selComponent) != null ? model.selComponent : null;
        for (ComponentUse c : t.components) {
            if (first == null && model.firstPort(c.id) != null) first = c.id;
        }
        if (first == null) {
            model.message = t.components.isEmpty() ? "Insert a component first." : "No component has ports yet: make ports in their templates.";
            model.fire();
            return;
        }
        PortChoice choice = new PortChoice("Component", first, null, null);
        JTextField name = new JTextField(2);
        name.setToolTipText(Ui.tooltip("Empty: the name of the chosen port."));
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.add(choice.box);
        p.add(Ui.labeled("Name", name));
        if (JOptionPane.showConfirmDialog(this, p, "Add port", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String[] picked = choice.picked();
        if (picked == null) return;
        TemplateDef inner = Templates.resolve(t.component(picked[0]), model.source());
        String wanted = name.getText().trim().isEmpty() && inner != null ? inner.port(picked[1]).name : name.getText().trim();
        if (t.portByName(wanted) != null) {
            JOptionPane.showMessageDialog(this, "A port named '" + wanted + "' exists already.", "Add port", JOptionPane.WARNING_MESSAGE);
            return;
        }
        edit("Add port", () -> {
            Port np = new Port(model.def().nextPortId(), wanted, null);
            np.component = picked[0];
            np.componentPort = picked[1];
            model.def().ports.add(np);
        });
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
}
