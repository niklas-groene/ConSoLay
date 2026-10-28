package gui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Single choice as a list of cards, each with a radio mark, a title and a short description; the selected card is
 * highlighted and carries a check mark. A click or the arrow keys (and Home / End) select a card; a placeholder card
 * ({@link #addPlaceholderOption}) announces an option that is not available yet and can never be selected.
 *
 * @param <T> value of an option
 * @author Niklas Gröne
 */
public class CardSelector<T> extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Radio mark and check mark size, and the indent of the description under the title. */
    private static final int MARK = 16;
    private static final int PAD_X = 10, PAD_Y = 8;

    private final List<Card> cards = new ArrayList<>();
    private final List<Consumer<T>> listeners = new ArrayList<>();
    private final int descriptionWidth;
    private int selected = -1;

    /** @param width the column width the cards are laid out for; descriptions wrap inside it */
    public CardSelector(int width) {
        super(new GridLayout(0, 1, 0, Ui.ITEM_GAP));
        setOpaque(false);
        setFocusable(true);
        descriptionWidth = width - 2 * PAD_X - MARK - Ui.GAP_M - 4;
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int n = cards.size();
                if (n == 0) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP: case KeyEvent.VK_LEFT:
                        setSelectedIndex(selectable(selected - 1, -1)); e.consume(); break;
                    case KeyEvent.VK_DOWN: case KeyEvent.VK_RIGHT:
                        setSelectedIndex(selectable(selected + 1, 1)); e.consume(); break;
                    case KeyEvent.VK_HOME:
                        setSelectedIndex(selectable(0, 1)); e.consume(); break;
                    case KeyEvent.VK_END:
                        setSelectedIndex(selectable(n - 1, -1)); e.consume(); break;
                    default:
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    /** Adds an option; the first option added becomes the selection. */
    public void addOption(T value, String title, String description, String tooltip) {
        addCard(new Card(value, title, description, null), tooltip);
    }

    /**
     * Adds a placeholder for an option that is not available yet: shown muted with a dashed outline and the badge
     * {@code badge} (e.g. "Coming soon"), never selectable by click or keyboard, and skipped as the first selection.
     */
    public void addPlaceholderOption(T value, String title, String description, String tooltip, String badge) {
        addCard(new Card(value, title, description, badge), tooltip);
    }

    private void addCard(Card card, String tooltip) {
        if (tooltip != null) card.setToolTipText(Ui.tooltip(tooltip));
        cards.add(card);
        add(card);
        card.updateState();
        if (selected < 0) setSelectedIndex(selectable(0, 1));
        revalidate();
    }

    /** The first selectable index from {@code from} on in direction {@code step}, or the current selection. */
    private int selectable(int from, int step) {
        for (int i = from; i >= 0 && i < cards.size(); i += step) {
            if (cards.get(i).selectable) return i;
        }
        return selected;
    }

    public T getSelectedValue() {
        return selected < 0 ? null : cards.get(selected).value;
    }

    /** Selects the option at {@code index} and notifies the listeners if the selection changed; placeholders stay unselected. */
    public void setSelectedIndex(int index) {
        if (index < 0 || index >= cards.size() || index == selected || !cards.get(index).selectable) return;
        selected = index;
        for (Card c : cards) c.updateState();
        repaint();
        T value = cards.get(index).value;
        for (Consumer<T> l : new ArrayList<>(listeners)) l.accept(value);
    }

    /** Called with the new value whenever the selection changes. */
    public void addSelectionListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    /** One option. The whole card is the click target; its children have no mouse listeners of their own. */
    private final class Card extends JPanel {
        private static final long serialVersionUID = 1L;
        final T value;
        /** False for a placeholder: muted, dashed, with a badge, and never selected. */
        final boolean selectable;
        private final JLabel title;
        private final JLabel check = new JLabel();
        private boolean hover;

        /** @param badge the badge of a placeholder card, or {@code null} for a selectable option */
        Card(T value, String titleText, String description, String badge) {
            super(new BorderLayout(Ui.GAP_M, Ui.GAP_XS));
            this.value = value;
            this.selectable = badge == null;
            setOpaque(false);
            setBorder(new EmptyBorder(PAD_Y, PAD_X, PAD_Y + 1, PAD_X));
            if (selectable) setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            title = new JLabel(titleText);
            title.setFont(Ui.bold());
            title.setIcon(new RadioMark());
            title.setIconTextGap(Ui.GAP_M);
            check.setPreferredSize(new Dimension(MARK, MARK));
            JPanel top = new JPanel(new BorderLayout(Ui.GAP_S, 0));
            top.setOpaque(false);
            top.add(title, BorderLayout.CENTER);
            if (selectable) {
                top.add(check, BorderLayout.EAST);
            } else {
                JPanel badgeBox = new JPanel(new GridBagLayout());   // keeps the badge at its own height
                badgeBox.setOpaque(false);
                badgeBox.add(new Badge(badge, Ui.MUTED));
                top.add(badgeBox, BorderLayout.EAST);
            }
            add(top, BorderLayout.NORTH);

            if (description != null && !description.isEmpty()) {
                JLabel desc = new JLabel(Ui.wrapped(description, descriptionWidth));
                desc.setFont(Ui.small());
                desc.setForeground(Ui.MUTED);
                desc.setBorder(new EmptyBorder(0, MARK + Ui.GAP_M, 0, 0));
                desc.setVerticalAlignment(SwingConstants.TOP);   // all cards are as high as the tallest: space goes below
                add(desc, BorderLayout.CENTER);
            }

            if (selectable) {
                addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        CardSelector.this.requestFocusInWindow();
                        setSelectedIndex(cards.indexOf(Card.this));
                    }
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            } else {
                title.setForeground(Ui.MUTED);
            }
        }

        boolean isSelected() {
            return cards.indexOf(this) == selected;
        }

        void updateState() {
            if (!selectable) {
                getAccessibleContext().setAccessibleName(title.getText() + " (not available yet)");
                return;
            }
            boolean sel = isSelected();
            check.setIcon(sel ? new CheckMark() : null);
            title.setForeground(sel ? Ui.ACCENT_PRESSED : Ui.TEXT);
            getAccessibleContext().setAccessibleName(title.getText() + (sel ? " (selected)" : ""));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            boolean sel = isSelected();
            int w = getWidth(), h = getHeight();
            if (sel && CardSelector.this.isFocusOwner()) {
                g2.setColor(Ui.FOCUS);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, Ui.RADIUS + 2, Ui.RADIUS + 2);
            }
            if (!selectable) {
                g2.setColor(Ui.SURFACE_HOVER);
                g2.fillRoundRect(2, 2, w - 5, h - 5, Ui.RADIUS, Ui.RADIUS);
                g2.setColor(Ui.BORDER);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 3f}, 0f));
                g2.drawRoundRect(2, 2, w - 5, h - 5, Ui.RADIUS, Ui.RADIUS);
                g2.dispose();
                return;
            }
            g2.setColor(sel ? Ui.ACCENT_SOFT : hover ? Ui.SURFACE_HOVER : Ui.SURFACE);
            g2.fillRoundRect(2, 2, w - 5, h - 5, Ui.RADIUS, Ui.RADIUS);
            g2.setColor(sel ? Ui.ACCENT : hover ? Ui.BORDER_HOVER : Ui.BORDER);
            g2.setStroke(new BasicStroke(sel ? 2f : 1f));
            if (sel) g2.drawRoundRect(3, 3, w - 7, h - 7, Ui.RADIUS, Ui.RADIUS);
            else g2.drawRoundRect(2, 2, w - 5, h - 5, Ui.RADIUS, Ui.RADIUS);
            g2.dispose();
        }

        /** Empty ring, or accent ring with a dot for the selected card. */
        private final class RadioMark implements Icon {
            @Override public int getIconWidth() { return MARK; }
            @Override public int getIconHeight() { return MARK; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Ui.smooth(g);
                boolean sel = isSelected();
                g2.setColor(Ui.SURFACE);
                g2.fillOval(x + 1, y + 1, MARK - 2, MARK - 2);
                g2.setStroke(new BasicStroke(sel ? 2f : 1.3f));
                g2.setColor(!selectable ? Ui.BORDER : sel ? Ui.ACCENT : hover ? Ui.BORDER_HOVER : Ui.MUTED);
                g2.drawOval(x + 1, y + 1, MARK - 3, MARK - 3);
                if (sel) g2.fillOval(x + 5, y + 5, MARK - 10, MARK - 10);
                g2.dispose();
            }
        }
    }

    /** Accent check mark of the selected card. */
    private static final class CheckMark implements Icon {
        @Override public int getIconWidth() { return MARK; }
        @Override public int getIconHeight() { return MARK; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(Ui.ACCENT);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Double();
            p.moveTo(x + 3, y + 8.5);
            p.lineTo(x + 6.5, y + 12);
            p.lineTo(x + 13, y + 4.5);
            g2.draw(p);
            g2.dispose();
        }
    }
}
