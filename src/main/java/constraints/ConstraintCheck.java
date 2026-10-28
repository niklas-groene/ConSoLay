package constraints;

import org.AttributeHelper;
import org.graffiti.graph.Node;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Measures in the current drawing whether a constraint holds. The layout reports the user constraints that do not
 * hold after a solve (the solver drops conflicting parts silently in its final projection), and the tests use the
 * same measure. Soft constraints ({@link DesiredPositionConstraintGraph}) always hold.
 *
 * @author Niklas Gröne
 */
public final class ConstraintCheck {

    /** Default tolerance in px: the solver's final projection works with one px borders. */
    public static final double TOLERANCE = 1.0;

    private ConstraintCheck() { }

    /**
     * Why {@code c} does not hold in the current drawing, or {@code null} if it holds within {@code tol} px.
     * {@code others} are the nodes the solve moved (for a cluster: the nodes that must stay outside).
     */
    public static String violation(Constraint c, Collection<Node> others, double tol) {
        List<Node> ns = c.getNodes();
        if (c instanceof HorizontalConstraint) {
            HorizontalConstraint.HorizontalConstraintType t = ((HorizontalConstraint) c).getType();
            return spread(ns, n -> t == HorizontalConstraint.HorizontalConstraintType.TOP ? top(n)
                    : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? bottom(n) : y(n), tol, "y");
        }
        if (c instanceof VerticalConstraint) {
            VerticalConstraint.VerticalConstraintType t = ((VerticalConstraint) c).getType();
            return spread(ns, n -> t == VerticalConstraint.VerticalConstraintType.LEFT ? left(n)
                    : t == VerticalConstraint.VerticalConstraintType.RIGHT ? right(n) : x(n), tol, "x");
        }
        if (c instanceof SeparationConstraintGraph) {
            SeparationConstraintGraph s = (SeparationConstraintGraph) c;
            if (ns.size() != 2) return null;
            Axis a = Axis.of(s.getType());
            double d = pos(ns.get(1), a) - pos(ns.get(0), a);
            return gap(d, s.getDistance(), s.isFixed(), tol, a);
        }
        if (c instanceof DistributionConstraintGraph) {
            DistributionConstraintGraph d = (DistributionConstraintGraph) c;
            return consecutive(ns, Axis.of(d.getType()), d.getSpacing(), true, tol);
        }
        if (c instanceof MultiSeparationConstraintGraph) {
            MultiSeparationConstraintGraph m = (MultiSeparationConstraintGraph) c;
            return consecutive(ns, m.getAxis(), m.getGap(), m.isExact(), tol);
        }
        if (c instanceof FixedRelativeConstraintGraph) {
            FixedRelativeConstraintGraph f = (FixedRelativeConstraintGraph) c;
            double[] mean = meanOffset(f, ns);
            double cx = 0, cy = 0;
            for (Node n : ns) { cx += x(n); cy += y(n); }
            cx /= ns.size();
            cy /= ns.size();
            for (Node n : ns) {
                double[] o = f.getOffset(n);
                if (o == null) continue;
                double dx = x(n) - cx - (o[0] - mean[0]), dy = y(n) - cy - (o[1] - mean[1]);
                if (Math.abs(dx) > tol || Math.abs(dy) > tol)
                    return "group lost its shape: " + label(n) + " is " + px(Math.hypot(dx, dy)) + " off";
            }
            return null;
        }
        if (c instanceof BoundaryConstraintGraph) {
            BoundaryConstraintGraph b = (BoundaryConstraintGraph) c;
            Axis a = b.getAxis();
            double lowSide = Double.NEGATIVE_INFINITY, highSide = Double.POSITIVE_INFINITY;
            for (Node n : b.getBefore()) lowSide = Math.max(lowSide, pos(n, a) + half(n, a) + b.getGap());
            for (Node n : b.getAfter()) highSide = Math.min(highSide, pos(n, a) - half(n, a) - b.getGap());
            if (lowSide > highSide + tol) return "no line fits between the groups (" + px(lowSide - highSide) + " short)";
            return null;
        }
        if (c instanceof PageBoundaryConstraintGraph) {
            PageBoundaryConstraintGraph p = (PageBoundaryConstraintGraph) c;
            for (Node n : ns) {
                if (left(n) < p.getMinX() - tol || right(n) > p.getMaxX() + tol
                        || top(n) < p.getMinY() - tol || bottom(n) > p.getMaxY() + tol)
                    return label(n) + " is outside the area";
            }
            return null;
        }
        if (c instanceof OrthogonalEdgeConstraintGraph) {
            OrthogonalEdgeConstraintGraph o = (OrthogonalEdgeConstraintGraph) c;
            double d = o.isHorizontal() ? y(ns.get(1)) - y(ns.get(0)) : x(ns.get(1)) - x(ns.get(0));
            return Math.abs(d) > tol ? "edge is " + px(Math.abs(d)) + " off " + (o.isHorizontal() ? "horizontal" : "vertical") : null;
        }
        if (c instanceof ClusterConstraintGraph) {
            ClusterConstraintGraph cl = (ClusterConstraintGraph) c;
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (Node n : ns) {
                minX = Math.min(minX, left(n)); maxX = Math.max(maxX, right(n));
                minY = Math.min(minY, top(n)); maxY = Math.max(maxY, bottom(n));
            }
            Set<Node> members = new HashSet<>(ns);
            if (others == null) return null;
            for (Node n : others) {
                if (members.contains(n)) continue;
                // An outside node must not reach into the member box (tolerance for rounding).
                if (right(n) > minX + tol && left(n) < maxX - tol && bottom(n) > minY + tol && top(n) < maxY - tol)
                    return label(n) + " is inside the cluster";
            }
            return null;
        }
        if (c instanceof LockConstraintGraph) {
            LockConstraintGraph l = (LockConstraintGraph) c;
            for (Node n : ns) {
                double[] p = l.getPosition(n);
                if (p == null) continue;
                double d = Math.hypot(x(n) - p[0], y(n) - p[1]);
                if (d > tol) return label(n) + " moved " + px(d) + " from its pin";
            }
            return null;
        }
        if (c instanceof LinePattern) {
            LinePattern l = (LinePattern) c;
            String s = spread(ns, l.isRow() ? ConstraintCheck::y : ConstraintCheck::x, tol, l.isRow() ? "y" : "x");
            return s != null ? s : consecutive(ns, l.getAxis(), l.getGap(), l.isEven(), tol);
        }
        if (c instanceof GridPattern) {
            GridPattern g = (GridPattern) c;
            for (List<Node> row : g.getRows()) {
                String s = spread(row, ConstraintCheck::y, tol, "y");
                if (s != null) return "row: " + s;
            }
            List<List<Node>> cols = g.getColumns();
            for (List<Node> col : cols) {
                String s = spread(col, ConstraintCheck::x, tol, "x");
                if (s != null) return "column: " + s;
            }
            for (int i = 1; i < cols.size(); i++) {
                double d = x(cols.get(i).get(0)) - x(cols.get(i - 1).get(0));
                if (d < g.getColumnGap() - tol) return "columns " + i + " and " + (i + 1) + " only " + px(d) + " apart";
            }
            List<List<Node>> rows = g.getRows();
            for (int i = 1; i < rows.size(); i++) {
                double d = y(rows.get(i).get(0)) - y(rows.get(i - 1).get(0));
                if (d < g.getRowGap() - tol) return "rows " + i + " and " + (i + 1) + " only " + px(d) + " apart";
            }
            return null;
        }
        if (c instanceof StarPattern) {
            StarPattern s = (StarPattern) c;
            Node hub = s.getHub();
            for (int i = 0; i < s.getRing().size(); i++) {
                Node n = s.getRing().get(i);
                double[] o = s.offset(i);
                double d = Math.hypot(x(n) - x(hub) - o[0], y(n) - y(hub) - o[1]);
                if (d > tol) return label(n) + " is " + px(d) + " off its place on the ring";
            }
            return null;
        }
        return null;   // soft or unknown constraints
    }

    // ---------------------------------------------------------------- helpers

    private interface Coord { double of(Node n); }

    private static String spread(List<Node> ns, Coord coord, double tol, String axis) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Node n : ns) { double v = coord.of(n); min = Math.min(min, v); max = Math.max(max, v); }
        return max - min > tol ? "not aligned: " + axis + " differs by " + px(max - min) : null;
    }

    private static String consecutive(List<Node> ns, Axis a, double gap, boolean exact, double tol) {
        for (int i = 1; i < ns.size(); i++) {
            String s = gap(pos(ns.get(i), a) - pos(ns.get(i - 1), a), gap, exact, tol, a);
            if (s != null) return label(ns.get(i - 1)) + " → " + label(ns.get(i)) + ": " + s;
        }
        return null;
    }

    private static String gap(double d, double wanted, boolean exact, double tol, Axis a) {
        String axis = a == Axis.X ? "x" : "y";
        if (exact && Math.abs(d - wanted) > tol) return axis + " distance " + px(d) + " instead of " + px(wanted);
        if (!exact && d < wanted - tol) return axis + " distance " + px(d) + " below " + px(wanted);
        return null;
    }

    /** Mean offset of the remaining members (the stored offsets are relative to the centroid at creation). */
    private static double[] meanOffset(FixedRelativeConstraintGraph f, List<Node> ns) {
        double mx = 0, my = 0;
        int k = 0;
        for (Node n : ns) {
            double[] o = f.getOffset(n);
            if (o == null) continue;
            mx += o[0];
            my += o[1];
            k++;
        }
        return k == 0 ? new double[]{0, 0} : new double[]{mx / k, my / k};
    }

    static double x(Node n) { return AttributeHelper.getPositionX(n); }
    static double y(Node n) { return AttributeHelper.getPositionY(n); }
    static double left(Node n) { return x(n) - AttributeHelper.getWidth(n) / 2; }
    static double right(Node n) { return x(n) + AttributeHelper.getWidth(n) / 2; }
    static double top(Node n) { return y(n) - AttributeHelper.getHeight(n) / 2; }
    static double bottom(Node n) { return y(n) + AttributeHelper.getHeight(n) / 2; }
    static double pos(Node n, Axis a) { return a == Axis.X ? x(n) : y(n); }
    static double half(Node n, Axis a) { return (a == Axis.X ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n)) / 2; }

    private static String px(double v) {
        return String.format(Locale.ROOT, "%.1f px", v);
    }

    private static String label(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        l = l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return l.isEmpty() ? "node #" + n.getID() : "'" + l + "'";
    }
}
