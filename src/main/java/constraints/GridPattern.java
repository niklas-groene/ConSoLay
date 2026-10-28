package constraints;

import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid: the nodes of every row share their centre y and keep their order left to right, the nodes of every column
 * share their centre x and keep their order top to bottom, neighbouring columns at least {@code columnGap} and
 * neighbouring rows at least {@code rowGap} apart between their centres. The cells are filled row by row; the last
 * row may be shorter.
 *
 * @author Niklas Gröne
 */
public class GridPattern extends Constraint {

    /** The cells row by row; a deleted node leaves a null, so the other nodes keep their columns. */
    private final List<List<Node>> cells = new ArrayList<>();
    private double columnGap;
    private double rowGap;

    /** @param rows the rows from top to bottom, each from left to right */
    public GridPattern(List<List<Node>> rows, double columnGap, double rowGap) {
        super(flatten(rows));
        for (List<Node> r : rows) this.cells.add(new ArrayList<>(r));
        this.columnGap = columnGap;
        this.rowGap = rowGap;
    }

    /** The nodes of the cells; an empty cell (null, as a template may store it) holds none. */
    private static List<Node> flatten(List<List<Node>> rows) {
        List<Node> l = new ArrayList<>();
        for (List<Node> r : rows) for (Node n : r) if (n != null) l.add(n);
        return l;
    }

    /** The cells row by row as made, empty cells as null (copies of the rows). */
    public List<List<Node>> getCells() {
        List<List<Node>> l = new ArrayList<>();
        for (List<Node> r : cells) l.add(new ArrayList<>(r));
        return l;
    }

    /** The non-empty rows from top to bottom, each with its nodes from left to right. */
    public List<List<Node>> getRows() {
        List<List<Node>> rows = new ArrayList<>();
        for (List<Node> r : cells) {
            List<Node> row = new ArrayList<>();
            for (Node n : r) if (n != null) row.add(n);
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    /** The non-empty columns from left to right, each with its nodes from top to bottom. */
    public List<List<Node>> getColumns() {
        int width = 0;
        for (List<Node> r : cells) width = Math.max(width, r.size());
        List<List<Node>> cols = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            List<Node> col = new ArrayList<>();
            for (List<Node> r : cells) if (c < r.size() && r.get(c) != null) col.add(r.get(c));
            if (!col.isEmpty()) cols.add(col);
        }
        return cols;
    }

    public int getColumnCount() {
        return getColumns().size();
    }

    public double getColumnGap() { return columnGap; }
    public void setColumnGap(double columnGap) { this.columnGap = columnGap; }
    public double getRowGap() { return rowGap; }
    public void setRowGap(double rowGap) { this.rowGap = rowGap; }

    /** A deleted node leaves an empty cell; the others keep their rows and columns. */
    @Override
    public boolean removeNode(Node n) {
        for (List<Node> r : cells) {
            int i = r.indexOf(n);
            if (i >= 0) r.set(i, null);
        }
        return super.removeNode(n);
    }

    @Override
    public ConstraintKind getKind() {
        return ConstraintKind.GRID;
    }
}
