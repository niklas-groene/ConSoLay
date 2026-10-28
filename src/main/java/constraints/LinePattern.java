package constraints;

import org.graffiti.graph.Node;

import java.util.List;

/**
 * A row or a column: the node centres on one line (an alignment) and the nodes in the order of the list along it,
 * consecutive centres at least {@code gap} apart (a multi-separation), or exactly {@code gap} apart with
 * {@code even}.
 *
 * @author Niklas Gröne
 */
public class LinePattern extends Constraint {

    private final boolean row;
    private double gap;
    private boolean even;

    /**
     * @param ordered the nodes from left to right (row) or top to bottom (column)
     * @param row     true: a horizontal row, false: a vertical column
     */
    public LinePattern(List<Node> ordered, boolean row, double gap, boolean even) {
        super(ordered);
        this.row = row;
        this.gap = gap;
        this.even = even;
    }

    public boolean isRow() { return row; }
    /** The axis the nodes are ordered along: x for a row, y for a column. */
    public Axis getAxis() { return row ? Axis.X : Axis.Y; }
    public double getGap() { return gap; }
    public void setGap(double gap) { this.gap = gap; }
    public boolean isEven() { return even; }
    public void setEven(boolean even) { this.even = even; }

    @Override
    public ConstraintKind getKind() {
        return row ? ConstraintKind.ROW : ConstraintKind.COLUMN;
    }
}
