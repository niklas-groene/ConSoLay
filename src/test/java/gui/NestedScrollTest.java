package gui;

import gui.components.NestedScrollPane;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The mouse wheel over a field inside a scrolling column. Run with {@code ant test}. */
public class NestedScrollTest {

    private static final AtomicLong CLOCK = new AtomicLong(1_000_000);

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A column holding a list; {@code rows} rows of which 5 fit into the list's pane. */
    private static final class Column {
        final NestedScrollPane outer, inner;

        Column(int rows) {
            DefaultListModel<String> m = new DefaultListModel<>();
            for (int i = 0; i < rows; i++) m.addElement("row " + i);
            JList<String> list = new JList<>(m);
            list.setFixedCellHeight(20);
            list.setFixedCellWidth(100);
            list.setVisibleRowCount(5);
            inner = new NestedScrollPane(list);
            JPanel content = new JPanel(new BorderLayout());
            content.add(inner, BorderLayout.NORTH);
            content.setPreferredSize(new Dimension(150, 2000));
            outer = new NestedScrollPane(content);
            outer.setSize(200, 300);
            layout(outer);
            layout(outer);
        }

        int column() {
            return outer.getVerticalScrollBar().getValue();
        }

        int list() {
            return inner.getVerticalScrollBar().getValue();
        }
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container) layout((Container) k);
    }

    /** One wheel step down at a screen position; {@code later} ms after the one before. */
    private static void wheel(NestedScrollPane on, int later, int screenX) {
        on.dispatchEvent(new MouseWheelEvent(on, MouseEvent.MOUSE_WHEEL, CLOCK.addAndGet(later), 0, 5, 5, screenX, 5, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 1.0));
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** A field with nothing to scroll does not stop the column. */
    @Test
    public void fieldWithoutScrollBarHandsTheWheelOn() throws Exception {
        onEdt(() -> {
            Column c = new Column(3);
            wheel(c.inner, 5000, 5);
            assertTrue("the column moved", c.column() > 0);
        });
    }

    /** One continued scroll over a field takes the field to its end and then goes on with the column. */
    @Test
    public void fieldScrollsToItsEndThenTheColumnGoesOn() throws Exception {
        onEdt(() -> {
            Column c = new Column(8);
            wheel(c.inner, 5000, 5);
            assertTrue("the field moved first", c.list() > 0);
            assertEquals(0, c.column());
            for (int i = 0; i < 20; i++) wheel(c.inner, 20, 5);
            assertEquals(60, c.list());
            assertTrue("the column went on", c.column() > 0);
        });
    }

    /** A column being scrolled runs on across a field that could scroll, also over a pause of the wheel. */
    @Test
    public void columnKeepsTheWheelAcrossAField() throws Exception {
        onEdt(() -> {
            Column c = new Column(50);
            wheel(c.outer, 5000, 5);
            int moved = c.column();
            assertTrue(moved > 0);
            wheel(c.inner, 20, 5);
            assertEquals(0, c.list());
            assertTrue(c.column() > moved);
            moved = c.column();
            wheel(c.inner, 800, 5);
            assertEquals("a flick with the pointer unmoved", 0, c.list());
            assertTrue(c.column() > moved);
        });
    }

    /** After a pause with the pointer moved onto a field, the wheel means the field. */
    @Test
    public void pointerMovedOntoAFieldMeansTheField() throws Exception {
        onEdt(() -> {
            Column c = new Column(50);
            wheel(c.outer, 5000, 5);
            int moved = c.column();
            wheel(c.inner, 800, 90);
            assertTrue("the field moved", c.list() > 0);
            assertEquals(moved, c.column());
        });
    }
}
