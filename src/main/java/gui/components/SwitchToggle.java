package gui.components;

import javax.swing.*;
import java.awt.*;

/**
 * An on / off switch: a rounded track with a knob, accent coloured and knob on the right while on, grey with the knob
 * on the left while off. It is a {@link JToggleButton}, so a click or Space toggles it and action / item listeners
 * work as usual; its accessible name is the text given to the constructor.
 *
 * @author Niklas Gröne
 */
public class SwitchToggle extends JToggleButton {

    private static final long serialVersionUID = 1L;

    private static final int TRACK_W = 36, TRACK_H = 20;

    public SwitchToggle(String accessibleName, boolean on) {
        super((String) null, on);
        getAccessibleContext().setAccessibleName(accessibleName);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override public Dimension getPreferredSize() { return new Dimension(TRACK_W + 6, TRACK_H + 6); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int x = (getWidth() - TRACK_W) / 2, y = (getHeight() - TRACK_H) / 2;
        boolean on = isSelected();
        if (isFocusOwner()) {
            g2.setColor(Ui.FOCUS);
            g2.setStroke(new BasicStroke(3f));
            g2.drawRoundRect(x - 2, y - 2, TRACK_W + 3, TRACK_H + 3, TRACK_H + 4, TRACK_H + 4);
        }
        Color track = !isEnabled() ? Ui.RULE : on ? (getModel().isRollover() ? Ui.ACCENT_HOVER : Ui.ACCENT)
                : (getModel().isRollover() ? Ui.BORDER_HOVER : new Color(0xB5, 0xBA, 0xC2));
        g2.setColor(track);
        g2.fillRoundRect(x, y, TRACK_W, TRACK_H, TRACK_H, TRACK_H);
        int d = TRACK_H - 4;
        int kx = on ? x + TRACK_W - d - 2 : x + 2;
        g2.setColor(new Color(0, 0, 0, 40));
        g2.fillOval(kx, y + 3, d, d);
        g2.setColor(Color.WHITE);
        g2.fillOval(kx, y + 2, d, d);
        g2.dispose();
    }
}
