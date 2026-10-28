package gui.template;

import constraints.ConstraintKind;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.io.TemplateLibrary;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The template editor's model and property panels, without a window: undo after saving, ids of earlier revisions,
 * deleting nodes of grids and stars, and the role lists, conditions, sizes and component settings of the panels. Run
 * with {@code ant test}.
 */
public class TemplateEditorTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** Undo after a save keeps the saved identity, so the next save makes the next revision (also for a copy). */
    @Test
    public void undoAfterSavingKeepsTheIdentity() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(3);
            t.revision = 3;
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.change("Rename node", () -> model.def().node("n1").label = "First");
            TemplateDef saved = model.def().copy();
            saved.revision = 4;
            model.replace(saved);
            model.setClean();
            model.undo();
            assertEquals(4, model.def().revision);
            assertEquals("", model.def().node("n1").label);
            TemplateDef copy = model.def().copy();
            copy.id = "tpl-copy";
            copy.revision = 1;
            model.replace(copy);
            model.redo();
            assertEquals("tpl-copy", model.def().id);
            assertEquals(1, model.def().revision);
        });
    }

    /** Ports of an earlier revision keep their ids reserved when a later one, saved without them, is edited. */
    @Test
    public void idsOfEarlierRevisionsAreNotHandedOutAgain() throws Exception {
        onEdt(() -> {
            TemplateDef rev1 = pattern(3);
            for (int i = 1; i <= 3; i++) rev1.ports.add(new Port("p" + i, "Port " + i, "n" + i));
            TemplateDef rev2 = rev1.copy();
            rev2.revision = 2;
            rev2.ports.removeIf(p -> p.id.equals("p3"));
            rev2.lastIds.clear();
            TemplateLibrary library = new TemplateLibrary(null);
            library.addInMemory(rev1);
            library.addInMemory(rev2);
            EditorModel model = new EditorModel(rev2.copy(), library);
            assertEquals("p4", model.def().nextPortId());
        });
    }

    /** Deleting a node empties its grid cell and takes its angle out of a star, as the constraint objects do. */
    @Test
    public void deletingNodesKeepsGridCellsAndStarAngles() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(8);
            ConstraintSpec grid = new ConstraintSpec(t.nextConstraintId(), ConstraintKind.GRID);
            grid.role("cells", Arrays.asList("n1", "n2", "n3", "n4")).param("columns", 2.0).param("columnGap", 20.0)
                    .param("rowGap", 20.0);
            ConstraintSpec star = new ConstraintSpec(t.nextConstraintId(), ConstraintKind.STAR);
            star.role("hub", Collections.singletonList("n5")).role("ring", Arrays.asList("n6", "n7", "n8"))
                    .param("radius", 100.0).param("angles", new double[]{180, 0, 90});
            t.constraints.add(grid);
            t.constraints.add(star);
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.change("Delete nodes", () -> model.deleteElements(Arrays.asList("n2", "n7"), Collections.emptyList()));
            ConstraintSpec g = model.def().constraint("k1"), s = model.def().constraint("k2");
            assertEquals(Arrays.asList("n1", null, "n3", "n4"), g.role("cells"));
            assertEquals(Collections.emptyList(), ConstraintSpecs.validate(g, model.def().nodeIds(), model.def().edgeIds()));
            assertEquals(Arrays.asList("n6", "n8"), s.role("ring"));
            assertArrayEquals(new double[]{180, 90}, (double[]) s.params.get("angles"), 0);

            // removing a ring node in the properties takes its angle along as well
            model.selConstraint = "k2";
            PatternProperties pp = new PatternProperties(model);
            roleList(pp, "n6").setSelectedIndex(0);
            roleButton(roleList(pp, "n6"), "−").doClick();
            assertEquals(Collections.singletonList("n8"), model.def().constraint("k2").role("ring"));
            assertArrayEquals(new double[]{90}, (double[]) model.def().constraint("k2").params.get("angles"), 0);
        });
    }

    /** A node chosen in a role list stays chosen, so the list's buttons act on it. */
    @Test
    public void roleListButtonsActOnTheChosenNode() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(3);
            ConstraintSpec row = new ConstraintSpec(t.nextConstraintId(), ConstraintKind.ROW);
            row.role("ordered", Arrays.asList("n1", "n2", "n3")).param("gap", 40.0).param("even", false);
            t.constraints.add(row);
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selConstraint = "k1";
            PatternProperties pp = new PatternProperties(model);
            roleList(pp, "n1").setSelectedIndex(1);
            assertEquals(1, roleList(pp, "n1").getSelectedIndex());
            roleButton(roleList(pp, "n1"), "↓").doClick();
            assertEquals(Arrays.asList("n1", "n3", "n2"), model.def().constraint("k1").role("ordered"));
            assertEquals(2, roleList(pp, "n1").getSelectedIndex());
            roleButton(roleList(pp, "n1"), "−").doClick();
            assertEquals(Arrays.asList("n1", "n3"), model.def().constraint("k1").role("ordered"));
        });
    }

    /** After two removals in a row the list shows what the constraint holds, and the next removal takes what it shows. */
    @Test
    public void roleListFollowsRepeatedEdits() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(1);
            ConstraintSpec row = new ConstraintSpec(t.nextConstraintId(), ConstraintKind.ROW);
            row.role("ordered", Arrays.asList("x1", "x2", "n1")).param("gap", 40.0).param("even", false);
            t.constraints.add(row);
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selConstraint = "k1";
            PatternProperties pp = new PatternProperties(model);
            for (int i = 0; i < 2; i++) {
                roleList(pp, "n1").setSelectedIndex(0);
                roleButton(roleList(pp, "n1"), "−").doClick();
            }
            assertEquals(Collections.singletonList("n1"), model.def().constraint("k1").role("ordered"));
            assertEquals(Collections.singletonList("n1"), items(roleList(pp, "n1")));
        });
    }

    /** "is" chosen while the value is still empty changes the condition (it needs a value now) and nothing breaks. */
    @Test
    public void roleConditionWithoutValueYet() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(1);
            t.node("n1").role = "MACROMOLECULE";
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selNodes.add("n1");
            PatternProperties pp = new PatternProperties(model);
            @SuppressWarnings("unchecked") JComboBox<Object> op = (JComboBox<Object>) find(pp, JComboBox.class,
                    c -> ((JComboBox<?>) c).getItemCount() > 0 && ((JComboBox<?>) c).getItemAt(0) instanceof Condition.Op);
            op.setSelectedItem(Condition.Op.EQUALS);
            PatternNode n = model.def().node("n1");
            assertEquals(Condition.Op.EQUALS, n.condition(Condition.SBGN_ROLE).op);
            assertEquals("MACROMOLECULE", n.role);
            assertEquals("Change role condition", model.undoName());
        });
    }

    /** A node larger than the spinners' usual range (a big compartment) can be selected and shows its size. */
    @Test
    public void largeNodeCanBeSelected() throws Exception {
        onEdt(() -> {
            TemplateDef t = pattern(1);
            t.node("n1").width = 6000;
            t.node("n1").height = 0.5;
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selNodes.add("n1");
            PatternProperties pp = new PatternProperties(model);
            JSpinner w = (JSpinner) find(pp, JSpinner.class, c -> true);
            assertEquals(6000.0, ((Number) w.getValue()).doubleValue(), 0);
        });
    }

    /** Choosing another component for a link is one change: one undo takes it back. */
    @Test
    public void changingALinksComponentIsOneUndoStep() throws Exception {
        onEdt(() -> {
            TemplateDef a = pattern(1), b = pattern(1), c = pattern(1);
            for (TemplateDef p : Arrays.asList(a, b, c)) p.ports.add(new Port("p1", "Port", "n1"));
            TemplateDef t = TemplateDef.create("Composite", TemplateDef.Kind.COMPOSITE);
            for (TemplateDef p : Arrays.asList(a, b, c)) {
                t.components.add(new ComponentUse(t.nextComponentId(), "Use " + (t.components.size() + 1), p.ref()));
                t.dependencies.put(p.id + "@" + p.revision, p);
            }
            t.links.add(new StructureLink("l1", "c1", "p1", "c2", "p1"));
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selLink = "l1";
            CompositeProperties cp = new CompositeProperties(model, null);
            @SuppressWarnings("unchecked") JComboBox<Object> from = (JComboBox<Object>) find(cp, JComboBox.class,
                    k -> ((JComboBox<?>) k).getSelectedItem() == model.def().component("c1"));
            from.setSelectedItem(model.def().component("c3"));
            assertEquals("c3", model.def().link("l1").fromComponent);
            model.undo();
            assertEquals("c1", model.def().link("l1").fromComponent);
        });
    }

    /** A constraint switched off in the reused template cannot be switched on for one use. */
    @Test
    public void constraintOffInTheTemplateCannotBeSwitchedOnPerUse() throws Exception {
        onEdt(() -> {
            TemplateDef inner = pattern(2);
            ConstraintSpec s = new ConstraintSpec(inner.nextConstraintId(), ConstraintKind.ALIGN_MIDDLE);
            s.role("nodes", Arrays.asList("n1", "n2")).param("axis", "y").param("anchor", "middle");
            s.name = "Off in the template";
            s.enabled = false;
            inner.constraints.add(s);
            TemplateDef t = TemplateDef.create("Composite", TemplateDef.Kind.COMPOSITE);
            t.components.add(new ComponentUse("c1", "Use", inner.ref()));
            t.dependencies.put(inner.id + "@" + inner.revision, inner);
            EditorModel model = new EditorModel(t, new TemplateLibrary(null));
            model.selComponent = "c1";
            CompositeProperties cp = new CompositeProperties(model, null);
            JCheckBox box = (JCheckBox) find(cp, JCheckBox.class,
                    k -> "Off in the template".equals(((JCheckBox) k).getText()));
            assertFalse(box.isEnabled());
        });
    }

    // ================================================================== helpers

    /** A pattern template with {@code n} nodes n1, n2, … in a row. */
    private static TemplateDef pattern(int n) {
        TemplateDef t = TemplateDef.create("Pattern", TemplateDef.Kind.PATTERN);
        for (int i = 0; i < n; i++) t.nodes.add(new PatternNode(t.nextNodeId(), 60 * i, 0));
        return t;
    }

    /** The role list (in the panel now) whose entries include {@code id}. */
    @SuppressWarnings("unchecked")
    private static JList<String> roleList(Container root, String id) {
        return (JList<String>) find(root, JList.class, c -> items((JList<?>) c).contains(id));
    }

    private static List<Object> items(JList<?> l) {
        ListModel<?> m = l.getModel();
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < m.getSize(); i++) items.add(m.getElementAt(i));
        return items;
    }

    /** The button {@code text} of the role editor that shows {@code list}. */
    private static JButton roleButton(JList<?> list, String text) {
        for (Container c = list.getParent(); c != null; c = c.getParent()) {
            Component b = search(c, JButton.class, k -> text.equals(((AbstractButton) k).getText()));
            if (b != null) return (JButton) b;
        }
        throw new AssertionError("no button " + text);
    }

    private static Component find(Container root, Class<?> type, Predicate<Component> p) {
        Component c = search(root, type, p);
        assertNotNull("no " + type.getSimpleName() + " found", c);
        return c;
    }

    private static Component search(Container root, Class<?> type, Predicate<Component> p) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && p.test(c)) return c;
            if (c instanceof Container) {
                Component f = search((Container) c, type, p);
                if (f != null) return f;
            }
        }
        return null;
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Runs {@code b} on the event thread and rethrows what it threw there. */
    private static void onEdt(Body b) throws Exception {
        Throwable[] thrown = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                b.run();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        if (thrown[0] instanceof Exception) throw (Exception) thrown[0];
        if (thrown[0] instanceof Error) throw (Error) thrown[0];
        assertNull(thrown[0]);
    }
}
