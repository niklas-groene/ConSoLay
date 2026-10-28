package gui.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checklist of the steps of an algorithm: one flat button per step with a badge (a green check once it has run, an
 * accent ring with its number for the next step, a grey ring for the others); a click or Space runs the step. Without
 * steps the list shows a placeholder text.
 *
 * @author Niklas Gröne
 */
public class StepList extends JPanel {

    private static final long serialVersionUID = 1L;

    public enum State { DONE, NEXT, PENDING }

    /** One step: its title ("1. Skeleton" or "Skeleton"), a description for the tooltip and its state. */
    public static final class Step {
        final String title;
        final String description;
        final State state;

        public Step(String title, String description, State state) {
            this.title = title == null ? "" : title;
            this.description = description;
            this.state = state == null ? State.PENDING : state;
        }
    }

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)\\.\\s*(.*)$");
    private static final int BADGE = 18;
    private static final int ROW_HEIGHT = 28;

    private final VerticalStack rows = new VerticalStack();
    private final JLabel placeholder;
    private IntConsumer onRun = i -> { };

    public StepList(String placeholderText) {
        super(new BorderLayout());
        setOpaque(false);
        placeholder = Ui.hint(placeholderText);
        placeholder.setBorder(new EmptyBorder(Ui.GAP_XS, 0, Ui.GAP_XS, 0));
        add(rows, BorderLayout.CENTER);
        setSteps(null);
    }

    /** Called with the index of a step when the user runs it. */
    public void setOnRun(IntConsumer onRun) {
        this.onRun = onRun == null ? i -> { } : onRun;
    }

    /** Replaces the rows; {@code null} or an empty list shows the placeholder. */
    public void setSteps(List<Step> steps) {
        // A step that ran from the keyboard rebuilds the list: keep the focus on the same row.
        int focused = -1;
        for (int i = 0; i < rows.getComponentCount(); i++) {
            if (rows.getComponent(i).isFocusOwner()) focused = i;
        }
        rows.clearRows();
        if (steps == null || steps.isEmpty()) {
            rows.addRow(placeholder, 0);
        } else {
            for (int i = 0; i < steps.size(); i++) rows.addRow(new Row(i, steps.get(i)), i == 0 ? 0 : Ui.GAP_XS);
        }
        rows.revalidate();
        rows.repaint();
        if (focused >= 0 && focused < rows.getComponentCount()) rows.getComponent(focused).requestFocusInWindow();
    }

    /** A step as a flat button: badge, title, "Run ▸" on hover. */
    private final class Row extends JButton {
        private static final long serialVersionUID = 1L;
        private final State state;
        private final String number;
        private final String label;

        Row(int index, Step step) {
            state = step.state;
            Matcher m = NUMBERED.matcher(step.title);
            number = m.matches() ? m.group(1) : String.valueOf(index + 1);
            label = m.matches() ? m.group(2) : step.title;
            setText(label);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(state == State.NEXT ? Ui.bold() : Ui.font());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            String stateText = state == State.DONE ? "Done – click to run it again." : "Click to run this step.";
            setToolTipText(Ui.tooltip("<b>" + Ui.esc(step.title) + "</b><br>"
                    + (step.description == null ? "" : Ui.esc(step.description) + "<br>")
                    + "<i>" + stateText + "</i>"));
            getAccessibleContext().setAccessibleName(step.title + (state == State.DONE ? " (done)" : ""));
            addActionListener(e -> onRun.accept(index));
        }

        @Override public Dimension getPreferredSize() { return new Dimension(40, ROW_HEIGHT); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth(), h = getHeight();
            ButtonModel m = getModel();
            boolean hot = m.isRollover() || isFocusOwner();
            if (m.isPressed() && m.isArmed()) {
                g2.setColor(Ui.ROW_HOVER.darker());
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            } else if (hot) {
                g2.setColor(Ui.ROW_HOVER);
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            }
            if (isFocusOwner()) {
                g2.setColor(Ui.FOCUS);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, 6, 6);
            }

            // badge
            int bx = 5, by = (h - BADGE) / 2;
            Font small = Ui.small().deriveFont(Font.BOLD);
            if (state == State.DONE) {
                g2.setColor(Ui.SUCCESS);
                g2.fillOval(bx, by, BADGE, BADGE);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Double();
                p.moveTo(bx + 4.5, by + 9.5);
                p.lineTo(bx + 7.8, by + 12.8);
                p.lineTo(bx + 13.5, by + 6);
                g2.draw(p);
            } else {
                boolean next = state == State.NEXT;
                g2.setColor(next ? Ui.ACCENT : Ui.SURFACE);
                g2.fillOval(bx, by, BADGE, BADGE);
                if (!next) {
                    g2.setColor(Ui.BORDER_HOVER);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawOval(bx, by, BADGE - 1, BADGE - 1);
                }
                g2.setFont(small);
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(next ? Color.WHITE : Ui.MUTED);
                g2.drawString(number, bx + (BADGE - fm.stringWidth(number)) / 2f,
                        by + (BADGE - fm.getHeight()) / 2f + fm.getAscent());
            }

            // "Run ▸" on hover / focus
            int textRight = w - 6;
            if (hot && isEnabled()) {
                String run = "Run ▸";
                g2.setFont(small);
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(Ui.ACCENT);
                int rx = w - 8 - fm.stringWidth(run);
                g2.drawString(run, rx, (h - fm.getHeight()) / 2 + fm.getAscent());
                textRight = rx - 6;
            }

            // title, clipped with an ellipsis if it does not fit
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = bx + BADGE + Ui.GAP_M;
            String shown = clip(label, fm, textRight - tx);
            g2.setColor(!isEnabled() ? Ui.DISABLED : state == State.PENDING ? Ui.TEXT : state == State.NEXT ? Ui.ACCENT_PRESSED : Ui.TEXT);
            g2.drawString(shown, tx, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
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
