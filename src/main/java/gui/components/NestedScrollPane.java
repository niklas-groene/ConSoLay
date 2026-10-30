package gui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;

/**
 * A scroll pane that can lie inside another one without trapping the mouse wheel. A plain {@link JScrollPane} takes every
 * wheel event under the pointer, even with nothing to scroll, so the column around it stops as soon as a list or text
 * field comes under the pointer. Here a field scrolls only while it can move and then hands the wheel on, and a column
 * being scrolled keeps it: it runs on across its fields, also over several flicks of the wheel while the pointer stays
 * where it is. So one continued scroll always gets through the whole column.
 *
 * @author Niklas Gröne
 */
public class NestedScrollPane extends JScrollPane {

    private static final long serialVersionUID = 1L;
    /** Within this pause (ms) scrolling goes on in the pane around, wherever the pointer is. */
    private static final long GESTURE_PAUSE = 300;
    /** Within this pause (ms) it goes on as well if the pointer has not moved. */
    private static final long FLICK_PAUSE = 1200;

    /** The pane that scrolled last, with the time and pointer position of that (there is one mouse wheel). */
    private static JScrollPane lastPane;
    private static long lastWheel;
    private static Point lastPointer;

    public NestedScrollPane() {
        super();
    }

    public NestedScrollPane(Component view) {
        super(view);
    }

    public NestedScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
        super(view, vsbPolicy, hsbPolicy);
    }

    @Override
    protected void processMouseWheelEvent(MouseWheelEvent e) {
        JScrollPane outer = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        JScrollPane target = outer == null || (!aroundIsScrolling(e) && canScroll(e)) ? this : outer;
        lastPane = target;
        lastWheel = e.getWhen();
        lastPointer = e.getLocationOnScreen();
        if (target == this) super.processMouseWheelEvent(e);
        else target.dispatchEvent(forwarded(e, target));
    }

    /** True while a pane around this one is being scrolled: the pointer merely passes over this field. */
    private boolean aroundIsScrolling(MouseWheelEvent e) {
        if (lastPane == null || !lastPane.isAncestorOf(this)) return false;
        long pause = e.getWhen() - lastWheel;
        return pause < GESTURE_PAUSE || (pause < FLICK_PAUSE && lastPointer != null && lastPointer.distance(e.getLocationOnScreen()) < 4);
    }

    private boolean canScroll(MouseWheelEvent e) {
        JScrollBar bar = e.isShiftDown() ? getHorizontalScrollBar() : getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) return false;
        return e.getPreciseWheelRotation() < 0 ? bar.getValue() > bar.getMinimum() : bar.getValue() + bar.getVisibleAmount() < bar.getMaximum();
    }

    /** The event for {@code target}, with the wheel's exact amount (a trackpad's small steps have a rotation of 0). */
    private MouseWheelEvent forwarded(MouseWheelEvent e, Component target) {
        Point p = SwingUtilities.convertPoint(this, e.getPoint(), target);
        Point s = e.getLocationOnScreen();
        return new MouseWheelEvent(target, e.getID(), e.getWhen(), e.getModifiersEx() | e.getModifiers(), p.x, p.y, s.x, s.y, e.getClickCount(),
                e.isPopupTrigger(), e.getScrollType(), e.getScrollAmount(), e.getWheelRotation(), e.getPreciseWheelRotation());
    }
}
