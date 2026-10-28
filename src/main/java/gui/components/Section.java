package gui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;

/**
 * A titled group of controls: a header (small bold capitals, a rule that fills the row, and an optional counter on
 * the right) above a {@link VerticalStack} of rows. A collapsible section shows a chevron in its header; clicking the
 * header (or Space / Enter while it has the focus) opens or closes it.
 *
 * @author Niklas Gröne
 */
public class Section extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Header header;
    private final VerticalStack body = new VerticalStack();
    private final boolean collapsible;
    private boolean expanded = true;

    /** A section that is always open. */
    public Section(String title) {
        this(title, false, true);
    }

    /** A section that the user can open and close; {@code expanded} is its initial state. */
    public static Section collapsible(String title, boolean expanded) {
        return new Section(title, true, expanded);
    }

    /** For subclasses; {@code expanded} is the initial state of a collapsible section. */
    protected Section(String title, boolean collapsible, boolean expanded) {
        super(new BorderLayout(0, Ui.GAP_M));
        setOpaque(false);
        this.collapsible = collapsible;
        header = new Header(title);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        setExpanded(expanded);
    }

    /** Adds a row to the section, {@link Ui#ITEM_GAP} below the previous one. */
    public <T extends Component> T addRow(T row) {
        return body.addRow(row);
    }

    /** Adds a row to the section, {@code gapAbove} px below the previous one. */
    public <T extends Component> T addRow(T row, int gapAbove) {
        return body.addRow(row, gapAbove);
    }

    /** A muted counter or state text at the right end of the header; {@code null} removes it. */
    public void setTrailingText(String text) {
        if (text == null) {
            header.setTrailing(null);
            return;
        }
        JLabel label = new JLabel(text);
        label.setFont(Ui.small());
        label.setForeground(Ui.MUTED);
        header.setTrailing(label);
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded || !collapsible;
        body.setVisible(this.expanded);
        ((BorderLayout) getLayout()).setVgap(this.expanded ? Ui.GAP_M : 0);
        header.repaint();
        revalidate();
        repaint();
    }

    /** Header row: chevron (collapsible only), title, rule, trailing component. */
    private final class Header extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JLabel title;
        private final JPanel end = new JPanel(new BorderLayout(Ui.GAP_M, 0));
        private JComponent trailing;
        private boolean hover;

        Header(String text) {
            super(new BorderLayout(Ui.GAP_M, 0));
            setOpaque(false);
            title = new JLabel(text.toUpperCase());
            title.setFont(Ui.header());
            title.setForeground(Ui.HEADER);
            if (collapsible) {
                title.setIcon(new Chevron());
                title.setIconTextGap(Ui.GAP_S);
            }
            end.setOpaque(false);
            end.add(new Rule(), BorderLayout.CENTER);
            add(title, BorderLayout.WEST);
            add(end, BorderLayout.CENTER);
            // room for the focus ring of a collapsible header, so it does not cross the trailing text; every header
            // gets it, so the counters of all sections end at the same x
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
            if (collapsible) {
                setFocusable(true);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Click to show or hide");
                addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) toggle(); }
                    @Override public void mouseEntered(MouseEvent e) { hover = true; title.setForeground(Ui.ACCENT); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; title.setForeground(Ui.HEADER); }
                });
                addKeyListener(new KeyAdapter() {
                    @Override public void keyPressed(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) toggle();
                    }
                });
                addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent e) { repaint(); }
                    @Override public void focusLost(FocusEvent e) { repaint(); }
                });
            }
        }

        private void toggle() {
            requestFocusInWindow();
            setExpanded(!expanded);
        }

        void setTrailing(JComponent c) {
            if (trailing != null) end.remove(trailing);
            trailing = c;
            if (c != null) end.add(c, BorderLayout.EAST);
            end.revalidate();
            end.repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (collapsible && isFocusOwner()) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(Ui.FOCUS);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                g2.dispose();
            }
        }

        /** Small triangle pointing right (closed) or down (open). */
        private final class Chevron implements Icon {
            @Override public int getIconWidth() { return 9; }
            @Override public int getIconHeight() { return 9; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(hover ? Ui.ACCENT : Ui.HEADER);
                Path2D p = new Path2D.Double();
                if (expanded) {
                    p.moveTo(x + 1, y + 2.5); p.lineTo(x + 8, y + 2.5); p.lineTo(x + 4.5, y + 7);
                } else {
                    p.moveTo(x + 2.5, y + 1); p.lineTo(x + 7, y + 4.5); p.lineTo(x + 2.5, y + 8);
                }
                p.closePath();
                g2.fill(p);
                g2.dispose();
            }
        }
    }

    /** One-pixel rule, vertically centred. */
    private static final class Rule extends JComponent {
        private static final long serialVersionUID = 1L;
        @Override public Dimension getPreferredSize() { return new Dimension(10, 1); }
        @Override public Dimension getMinimumSize() { return new Dimension(0, 1); }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(Ui.RULE);
            int y = getHeight() / 2;
            g.fillRect(0, y, getWidth(), 1);
        }
    }
}
