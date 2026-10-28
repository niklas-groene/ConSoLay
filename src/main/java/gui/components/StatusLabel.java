package gui.components;

import javax.swing.*;
import java.awt.*;

/**
 * One-line status message under the actions of a panel. It takes the row's width and never asks for more: a long
 * message is clipped with '...' and readable in full as the tooltip. The kind colours it: {@link Kind#INFO} muted,
 * {@link Kind#SUCCESS} with a green dot, {@link Kind#ERROR} red with a red dot, {@link Kind#BUSY} with a blue dot.
 *
 * @author Niklas Gröne
 */
public class StatusLabel extends JLabel {

    private static final long serialVersionUID = 1L;

    public enum Kind { INFO, BUSY, SUCCESS, ERROR }

    public StatusLabel(String text) {
        setFont(Ui.small());
        setIconTextGap(Ui.GAP_S + 2);
        setStatus(text, Kind.INFO);
    }

    /** Shows {@code text} in the style of {@code kind} ({@code null}: {@link Kind#INFO}). */
    public void setStatus(String text, Kind kind) {
        if (kind == null) kind = Kind.INFO;
        setText(text == null || text.isEmpty() ? " " : text);
        setToolTipText(text == null || text.isEmpty() ? null : Ui.tooltip(Ui.esc(text)));
        setForeground(kind == Kind.ERROR ? Ui.ERROR : kind == Kind.INFO ? Ui.MUTED : Ui.TEXT);
        setIcon(kind == Kind.INFO ? null : new Dot(kind == Kind.SUCCESS ? Ui.SUCCESS
                : kind == Kind.ERROR ? Ui.ERROR : Ui.ACCENT));
    }

    @Override public Dimension getPreferredSize() { return new Dimension(10, super.getPreferredSize().height); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    /** Filled circle in front of the text. */
    private static final class Dot implements Icon {
        private final Color color;
        Dot(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 7; }
        @Override public int getIconHeight() { return 7; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(color);
            g2.fillOval(x, y, 7, 7);
            g2.dispose();
        }
    }
}
