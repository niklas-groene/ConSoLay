package gui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * A rounded card: {@link Ui#SURFACE} background with a {@link Ui#BORDER} line, e.g. a snapshot of the mental-map
 * history or the frame of the template preview. A highlighted card (the pinned reference, for instance) has an
 * accent border and a tinted background.
 *
 * @author Niklas Gröne
 */
public class CardPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private boolean highlighted;

    /** A card with the given layout and {@code padding} px space on every side inside the border. */
    public CardPanel(LayoutManager layout, int padding) {
        super(layout);
        setOpaque(false);
        setBorder(new EmptyBorder(padding + 1, padding + 1, padding + 1, padding + 1));
    }

    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
        repaint();
    }

    /** Cards never ask for more width than they get: their content wraps or clips. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int w = getWidth(), h = getHeight();
        g2.setColor(highlighted ? Ui.ACCENT_SOFT : Ui.SURFACE);
        g2.fillRoundRect(0, 0, w - 1, h - 1, Ui.RADIUS, Ui.RADIUS);
        g2.setColor(highlighted ? Ui.ACCENT : Ui.BORDER);
        if (highlighted) {
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, Ui.RADIUS, Ui.RADIUS);
        } else {
            g2.drawRoundRect(0, 0, w - 1, h - 1, Ui.RADIUS, Ui.RADIUS);
        }
        g2.dispose();
    }
}
