package gui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A grid of tiles, one of which is selected: {@link Style#TILE} tiles with a pictogram above a short label (the
 * constraint kinds) or compact {@link Style#CHIP} tiles with a label only (the categories). A click selects a tile;
 * the arrow keys move the selection while a tile has the focus.
 *
 * @param <T> value of a tile
 * @author Niklas Gröne
 */
public class TilePalette<T> extends JPanel {

    private static final long serialVersionUID = 1L;

    public enum Style { TILE, CHIP }

    private final Style style;
    private final int columns;
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Consumer<T>> listeners = new ArrayList<>();
    private int selected = -1;

    public TilePalette(Style style, int columns) {
        super(new GridLayout(0, columns, Ui.GAP_S, Ui.GAP_S));
        this.style = style;
        this.columns = columns;
        setOpaque(false);
    }

    /** Adds a tile; {@code icon} may be null (always for chips). The first tile added becomes the selection. */
    public void addTile(T value, Icon icon, String label, String tooltip) {
        Tile t = new Tile(value, icon, label);
        if (tooltip != null) t.setToolTipText(Ui.tooltip(tooltip));
        tiles.add(t);
        add(t);
        if (selected < 0) setSelectedIndex(0);
        revalidate();
    }

    /** Removes every tile (the selection becomes empty, no listener is called). */
    public void clearTiles() {
        tiles.clear();
        removeAll();
        selected = -1;
        revalidate();
        repaint();
    }

    public T getSelectedValue() { return selected < 0 ? null : tiles.get(selected).value; }

    public void setSelectedValue(T value) {
        for (int i = 0; i < tiles.size(); i++) {
            T v = tiles.get(i).value;
            if (v == null ? value == null : v.equals(value)) { setSelectedIndex(i); return; }
        }
    }

    /** Selects tile {@code index} and notifies the listeners if the selection changed. */
    public void setSelectedIndex(int index) {
        if (index < 0 || index >= tiles.size()) return;
        if (index == selected) {   // a click on the selected tile toggled it off: keep it on
            tiles.get(index).setSelected(true);
            return;
        }
        selected = index;
        for (Tile t : tiles) t.setSelected(t == tiles.get(index));
        repaint();
        T value = tiles.get(index).value;
        for (Consumer<T> l : new ArrayList<>(listeners)) l.accept(value);
    }

    public void addSelectionListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    /** One tile: a toggle button that paints itself (pictogram + label, or label only). */
    private final class Tile extends JToggleButton {
        private static final long serialVersionUID = 1L;
        final T value;
        private final Icon pictogram;
        private final String label;

        Tile(T value, Icon pictogram, String label) {
            super(label);
            this.value = value;
            this.pictogram = pictogram;
            this.label = label;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(style == Style.CHIP ? Ui.small().deriveFont(Font.BOLD) : Ui.small());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(label);
            addActionListener(e -> setSelectedIndex(tiles.indexOf(this)));
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    int i = tiles.indexOf(Tile.this), next = i;
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT: next = i - 1; break;
                        case KeyEvent.VK_RIGHT: next = i + 1; break;
                        case KeyEvent.VK_UP: next = i - columns; break;
                        case KeyEvent.VK_DOWN: next = i + columns; break;
                        default: return;
                    }
                    if (next < 0 || next >= tiles.size()) return;
                    e.consume();
                    setSelectedIndex(next);
                    tiles.get(next).requestFocusInWindow();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            if (style == Style.CHIP) return new Dimension(40, fm.getHeight() + 10);
            int lines = 1;
            // the narrowest a tile gets: the column's text width shared by the columns
            int textWidth = (Ui.TEXT_WIDTH - (columns - 1) * Ui.GAP_S) / columns - 8;
            for (Tile t : tiles) lines = Math.max(lines, Math.min(2, wrap(t.label, fm, textWidth).size()));
            return new Dimension(40, 8 + (pictogram == null ? 0 : pictogram.getIconHeight() + 4) + lines * fm.getHeight() + 4);
        }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth(), h = getHeight();
            boolean sel = isSelected(), hot = getModel().isRollover();
            int arc = style == Style.CHIP ? h - 2 : Ui.RADIUS;
            if (isFocusOwner()) {
                g2.setColor(Ui.FOCUS);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, arc + 2, arc + 2);
            }
            g2.setColor(sel ? Ui.ACCENT_SOFT : hot ? Ui.SURFACE_HOVER : Ui.SURFACE);
            g2.fillRoundRect(2, 2, w - 5, h - 5, arc, arc);
            g2.setColor(sel ? Ui.ACCENT : hot ? Ui.BORDER_HOVER : Ui.BORDER);
            g2.setStroke(new BasicStroke(sel ? 2f : 1f));
            if (sel) g2.drawRoundRect(3, 3, w - 7, h - 7, arc, arc);
            else g2.drawRoundRect(2, 2, w - 5, h - 5, arc, arc);

            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(sel ? Ui.ACCENT_PRESSED : isEnabled() ? Ui.TEXT : Ui.DISABLED);
            if (style == Style.CHIP) {
                String s = clip(label, fm, w - 12);
                g2.drawString(s, (w - fm.stringWidth(s)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            } else {
                int y = 6;
                if (pictogram != null) {
                    pictogram.paintIcon(this, g2, (w - pictogram.getIconWidth()) / 2, y);
                    y += pictogram.getIconHeight() + 4;
                }
                // the label on up to two centred lines
                List<String> lines = wrap(label, fm, w - 8);
                for (String line : lines) {
                    g2.drawString(line, (w - fm.stringWidth(line)) / 2, y + fm.getAscent());
                    y += fm.getHeight();
                }
            }
            g2.dispose();
        }
    }

    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String candidate = line.length() == 0 ? w : line + " " + w;
            if (fm.stringWidth(candidate) <= width || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(w);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        if (lines.size() > 2) {
            String second = lines.get(1);
            for (int i = 2; i < lines.size(); i++) second += " " + lines.get(i);
            lines = new ArrayList<>(lines.subList(0, 1));
            lines.add(clip(second, fm, width));
        }
        for (int i = 0; i < lines.size(); i++) lines.set(i, clip(lines.get(i), fm, width));
        return lines;
    }

    private static String clip(String text, FontMetrics fm, int avail) {
        if (fm.stringWidth(text) <= avail) return text;
        for (int n = text.length() - 1; n > 0; n--) {
            String s = text.substring(0, n) + "…";
            if (fm.stringWidth(s) <= avail) return s;
        }
        return "…";
    }
}
