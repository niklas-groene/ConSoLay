package gui.components;

import javax.swing.*;
import java.awt.*;

/**
 * Vertical scroll pane for a column of the inspector tab. The content always takes the viewport's width (there is
 * no horizontal scrolling), and the pane asks for a fixed width no matter what it holds. VANTED sizes the inspector
 * from the tabs' preferred width and pins that as the minimum, so a long constraint text or hint must not widen it.
 */
public class ScrollColumn extends NestedScrollPane {

    private static final long serialVersionUID = 1L;
    /** Pixels per step of the mouse wheel. */
    private static final int WHEEL_STEP = 24;

    public ScrollColumn(JComponent content, int preferredWidth) {
        super(new Column(content, preferredWidth), VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
        getVerticalScrollBar().setUnitIncrement(WHEEL_STEP);
        setBorder(BorderFactory.createEmptyBorder());
        setViewportBorder(null);
    }

    /** Scrolls back to the top, e.g. after the content was exchanged. */
    public void scrollToTop() {
        getViewport().setViewPosition(new Point(0, 0));
    }

    /** The view: reports the fixed width as its own and follows the viewport's width. */
    private static final class Column extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        private final int preferredWidth;

        Column(JComponent content, int preferredWidth) {
            super(new BorderLayout());
            this.preferredWidth = preferredWidth;
            add(content, BorderLayout.NORTH);   // a short content stays at the top, the column fills the rest
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(preferredWidth, getPreferredSize().height);
        }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return WHEEL_STEP; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(16, (orientation == SwingConstants.VERTICAL ? r.height : r.width) - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        /** Fill a viewport that is higher than the content, so the column's background reaches the bottom. */
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
