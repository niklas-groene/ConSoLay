package gui.components;

import javax.swing.*;
import java.awt.*;

/**
 * The primary action of a section: a filled accent button with white bold text. It paints itself, so it looks the
 * same in every look and feel; on the Mac it keeps the transparent margin of the native buttons so both line up.
 *
 * @author Niklas Gröne
 */
public class PrimaryButton extends JButton {

    private static final long serialVersionUID = 1L;

    /** Transparent margin around the bezel: the native Aqua buttons have one, other look and feels do not. */
    private final Insets margin;

    public PrimaryButton(String text) {
        super(text);
        margin = Ui.isAqua() ? new Insets(3, 6, 4, 6) : new Insets(0, 0, 0, 0);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setFont(Ui.bold());
        setForeground(Color.WHITE);
        setBorder(BorderFactory.createEmptyBorder(margin.top + 4, margin.left + 12, margin.bottom + 4, margin.right + 12));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        Insets in = getInsets();
        int w = Math.min(Ui.TEXT_WIDTH, fm.stringWidth(getText() == null ? "" : getText()) + in.left + in.right);
        int h = Math.max(fm.getHeight() + in.top + in.bottom, margin.top + margin.bottom + 22);
        return new Dimension(w, h);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(40, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        ButtonModel m = getModel();
        int x = margin.left, y = margin.top;
        int w = getWidth() - margin.left - margin.right, h = getHeight() - margin.top - margin.bottom;
        Color fill = !isEnabled() ? Ui.DISABLED
                : m.isPressed() && m.isArmed() ? Ui.ACCENT_PRESSED
                : m.isRollover() ? Ui.ACCENT_HOVER : Ui.ACCENT;
        boolean ringOutside = margin.left >= 2 && margin.top >= 2;
        if (isFocusOwner() && ringOutside) {
            g2.setColor(Ui.FOCUS);
            g2.setStroke(new BasicStroke(3f));
            g2.drawRoundRect(x - 1, y - 1, w + 1, h + 1, Ui.RADIUS + 2, Ui.RADIUS + 2);
        }
        g2.setColor(fill);
        g2.fillRoundRect(x, y, w, h, Ui.RADIUS, Ui.RADIUS);
        if (isFocusOwner() && !ringOutside) {
            // No transparent margin around the button (not Aqua): mark the focus inside the fill.
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(x + 2, y + 2, w - 5, h - 5, Ui.RADIUS - 2, Ui.RADIUS - 2);
        }

        String text = getText() == null ? "" : getText();
        FontMetrics fm = g2.getFontMetrics(getFont());
        int avail = w - 16;
        String shown = fm.stringWidth(text) <= avail ? text : clip(text, fm, avail);
        g2.setFont(getFont());
        g2.setColor(isEnabled() ? getForeground() : new Color(0xF4, 0xF5, 0xF7));
        int tx = x + (w - fm.stringWidth(shown)) / 2;
        int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(shown, tx, ty);
        g2.dispose();
    }

    private static String clip(String text, FontMetrics fm, int avail) {
        String dots = "…";
        for (int n = text.length() - 1; n > 0; n--) {
            String s = text.substring(0, n) + dots;
            if (fm.stringWidth(s) <= avail) return s;
        }
        return dots;
    }
}
