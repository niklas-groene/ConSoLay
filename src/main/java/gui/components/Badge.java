package gui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * A small pill with a short state text, e.g. "REFERENCE" on the pinned snapshot: white bold capitals on the accent
 * colour (or on another colour given to the constructor).
 *
 * @author Niklas Gröne
 */
public class Badge extends JLabel {

    private static final long serialVersionUID = 1L;

    private final Color fill;

    public Badge(String text) {
        this(text, Ui.ACCENT);
    }

    public Badge(String text, Color fill) {
        super(text.toUpperCase());
        this.fill = fill;
        setFont(Ui.header().deriveFont(Math.max(8f, Ui.header().getSize2D() - 1f)));
        setForeground(Color.WHITE);
        setBorder(new EmptyBorder(1, 6, 1, 6));
        setHorizontalAlignment(CENTER);
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
