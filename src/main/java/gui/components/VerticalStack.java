package gui.components;

import javax.swing.*;
import java.awt.*;

/**
 * A transparent column of rows: every row takes the full width, rows are separated by the gap given when they are
 * added (by default {@link Ui#ITEM_GAP}), and the column is only as high as its rows.
 *
 * @author Niklas Gröne
 */
public class VerticalStack extends JPanel {

    private static final long serialVersionUID = 1L;

    private final GridBagConstraints gc = new GridBagConstraints();
    private int rows;

    public VerticalStack() {
        super(new GridBagLayout());
        setOpaque(false);
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
    }

    /** Adds a row {@link Ui#ITEM_GAP} below the previous one (the first row without a gap). */
    public <T extends Component> T addRow(T row) {
        return addRow(row, rows == 0 ? 0 : Ui.ITEM_GAP);
    }

    /** Adds a row {@code gapAbove} px below the previous one. */
    public <T extends Component> T addRow(T row, int gapAbove) {
        gc.gridy = rows++;
        gc.insets = new Insets(gapAbove, 0, 0, 0);
        add(row, gc);
        return row;
    }

    /** Removes every row. */
    public void clearRows() {
        removeAll();
        rows = 0;
    }

    /** Number of rows added since the last {@link #clearRows()}. */
    public int getRowCount() {
        return rows;
    }
}
