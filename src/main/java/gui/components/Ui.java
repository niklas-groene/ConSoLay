package gui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

/**
 * Design tokens (spacing, widths, colours, fonts) and small component factories shared by every panel of the add-on
 * tab. VANTED pins the inspector's minimum width to the preferred width of its tabs, so no component may ask for more
 * than {@link #COLUMN_WIDTH}: long texts wrap ({@link #wrapped(String, int)}) or are clipped ({@link StatusLabel}).
 *
 * @author Niklas Gröne
 */
public final class Ui {

    private Ui() { }

    // ---------------------------------------------------------------- spacing (px)

    public static final int GAP_XS = 2;
    public static final int GAP_S = 4;
    public static final int GAP_M = 8;
    public static final int GAP_L = 12;
    public static final int GAP_XL = 16;

    /** Horizontal padding of a tab column. */
    public static final int PAD = 10;
    /** Gap between two rows (controls) of a section. */
    public static final int ITEM_GAP = 6;
    /** Gap between two sections. */
    public static final int SECTION_GAP = 18;

    // ---------------------------------------------------------------- widths (px)

    /** Upper bound of the add-on tab's preferred width. */
    public static final int TAB_WIDTH = 280;
    /** Preferred content width of a scrolling column (the tab minus scrollbar, borders and tab insets). */
    public static final int COLUMN_WIDTH = 240;
    /** Wrap width of a text that spans a column (the column minus its padding). */
    public static final int TEXT_WIDTH = COLUMN_WIDTH - 2 * PAD;

    // ---------------------------------------------------------------- colours

    /** Selection, primary actions, focus. White text on it has a contrast of 5:1. */
    public static final Color ACCENT = new Color(0x25, 0x63, 0xEB);
    public static final Color ACCENT_HOVER = new Color(0x1D, 0x4E, 0xD8);
    public static final Color ACCENT_PRESSED = new Color(0x1E, 0x40, 0xAF);
    /** Background of a selected card. */
    public static final Color ACCENT_SOFT = new Color(0xEA, 0xF1, 0xFE);
    /** Focus ring. */
    public static final Color FOCUS = new Color(0x25, 0x63, 0xEB, 0x70);

    /** Background of cards (algorithm cards, snapshot rows, preview frame). */
    public static final Color SURFACE = Color.WHITE;
    public static final Color SURFACE_HOVER = new Color(0xF6, 0xF8, 0xFB);
    /** Hover background of flat list rows. */
    public static final Color ROW_HOVER = new Color(0xE1, 0xE7, 0xF0);
    public static final Color BORDER = new Color(0xC9, 0xCD, 0xD3);
    public static final Color BORDER_HOVER = new Color(0x9A, 0xA3, 0xAE);
    /** Rule of the section headers. */
    public static final Color RULE = new Color(0xD3, 0xD6, 0xDB);

    public static final Color TEXT = new Color(0x20, 0x21, 0x24);
    /** Secondary text: hints, descriptions, counters. */
    public static final Color MUTED = new Color(0x5F, 0x63, 0x68);
    /** Section headers. */
    public static final Color HEADER = new Color(0x3C, 0x40, 0x43);
    public static final Color DISABLED = new Color(0xA0, 0xA4, 0xAA);

    public static final Color SUCCESS = new Color(0x1E, 0x8E, 0x3E);
    public static final Color ERROR = new Color(0xC5, 0x22, 0x1F);
    public static final Color WARNING = new Color(0xB0, 0x60, 0x00);

    /** Corner radius of cards and buttons. */
    public static final int RADIUS = 8;

    // ---------------------------------------------------------------- fonts

    /**
     * The look and feel's label font (13 px Lucida Grande on the Mac), always plain: Metal's label font is bold,
     * which would flatten the hierarchy of plain, bold and header texts.
     */
    public static Font font() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f.deriveFont(Font.PLAIN) : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    public static Font bold() {
        return font().deriveFont(Font.BOLD);
    }

    /** Hints, descriptions, counters: two points smaller than the label font. */
    public static Font small() {
        Font f = font();
        return f.deriveFont(Math.max(9f, f.getSize2D() - 2f));
    }

    /** Section headers: small bold capitals with a little letter spacing. */
    public static Font header() {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.TRACKING, 0.06f);
        return small().deriveFont(Font.BOLD).deriveFont(attributes);
    }

    // ---------------------------------------------------------------- text

    /**
     * HTML for a label or check box text that wraps at {@code width} px. Swing's CSS {@code width} on a block inflates
     * the preferred width; a table with a width attribute keeps it exact.
     */
    public static String wrapped(String html, int width) {
        return "<html><table width=" + width + " cellpadding=0 cellspacing=0><tr><td>" + html + "</td></tr></table></html>";
    }

    /** Escapes text for use inside {@link #wrapped(String, int)}. */
    public static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A tooltip that wraps at 260 px. */
    public static String tooltip(String html) {
        return html == null ? null : wrapped(html, 260);
    }

    // ---------------------------------------------------------------- factories

    /** A muted, small hint that wraps at the column's text width. */
    public static JLabel hint(String html) {
        return hint(html, TEXT_WIDTH);
    }

    /** A muted, small hint that wraps at {@code width} px. */
    public static JLabel hint(String html, int width) {
        JLabel label = new JLabel(wrapped(html, width));
        label.setFont(small());
        label.setForeground(MUTED);
        return label;
    }

    /**
     * A plain look-and-feel button. It never asks for more than {@link #TEXT_WIDTH}: in a row it takes the row's width
     * anyway, and a text that does not fit is clipped with '...' by the look and feel.
     */
    public static JButton button(String text, String tooltip, ActionListener action) {
        return withAction(new JButton(text) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.min(d.width, TEXT_WIDTH), d.height);
            }
            @Override public Dimension getMinimumSize() {
                Dimension d = super.getMinimumSize();
                return new Dimension(Math.min(d.width, TEXT_WIDTH), d.height);
            }
        }, tooltip, action);
    }

    /** The primary action of a section (at most one per section). */
    public static PrimaryButton primaryButton(String text, String tooltip, ActionListener action) {
        return withAction(new PrimaryButton(text), tooltip, action);
    }

    private static <B extends AbstractButton> B withAction(B button, String tooltip, ActionListener action) {
        if (tooltip != null) button.setToolTipText(tooltip(tooltip));
        if (action != null) button.addActionListener(action);
        return button;
    }

    /** Components side by side with equal widths, e.g. two buttons. */
    public static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new GridLayout(1, components.length, GAP_S, 0));
        row.setOpaque(false);
        for (JComponent c : components) row.add(c);
        return row;
    }

    /** A label on the left and a control that takes the rest of the row. */
    public static JPanel labeled(String label, JComponent control) {
        return labeled(label, control, null);
    }

    /** A label on the left, a control that takes the rest of the row and an optional component on the right. */
    public static JPanel labeled(String label, JComponent control, JComponent trailing) {
        JPanel row = new JPanel(new BorderLayout(GAP_M, 0));
        row.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setLabelFor(control);
        row.add(l, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        if (trailing != null) row.add(trailing, BorderLayout.EAST);
        return row;
    }

    /** An empty border with the column padding: {@link #PAD} left and right, {@code top} and {@code bottom} as given. */
    public static EmptyBorder columnPadding(int top, int bottom) {
        return new EmptyBorder(top, PAD, bottom, PAD);
    }

    /** A combo box that never widens the column: it takes the row's width and clips long entries. */
    public static <E> JComboBox<E> comboBox(ComboBoxModel<E> model) {
        return new JComboBox<E>(model) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };
    }

    /** {@link #comboBox(ComboBoxModel)} over a fixed list of items. */
    public static <E> JComboBox<E> comboBox(E[] items) {
        return comboBox(new DefaultComboBoxModel<>(items));
    }

    /** True for the Mac look and feel, whose buttons keep a transparent margin around their bezel. */
    public static boolean isAqua() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        return laf != null && "Aqua".equals(laf.getID());
    }

    /** Anti-aliased graphics for custom painting; dispose it after use. */
    public static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }
}
