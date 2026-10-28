package layout.template.apply;

import constraints.ConstraintKind;
import constraints.spec.ConstraintSpec;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.TemplateDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Constraints from the arrangement": a small, understandable set of constraints read off the pattern's drawing, never
 * one per pair of nodes. Nodes whose centres lie on one horizontal line (within a tolerance) become a row, those on one
 * vertical line a column (both keep their order, with the smallest current gap as the least gap); an edge whose ends are
 * in no common row or column keeps the order of its ends along its main direction (one gap, at least the current
 * distance). Optionally the whole pattern becomes one rigid group; all values are fixed numbers taken from the drawing.
 *
 * @author Niklas Gröne
 */
public final class ArrangementDeriver {

    private ArrangementDeriver() { }

    public static final class Options {
        public boolean rowsAndColumns = true;
        public boolean edgeOrder = true;
        public boolean rigid = false;
        /** Centres this close count as aligned (px). */
        public double tolerance = 6;
    }

    /**
     * The derived definitions (ids and names set, not yet added to {@code t}); one that the template has already (same
     * kind on the same nodes) is left out.
     */
    public static List<ConstraintSpec> derive(TemplateDef t, Options o) {
        List<ConstraintSpec> all = deriveAll(t, o);
        List<ConstraintSpec> out = new ArrayList<>();
        for (ConstraintSpec d : all) {
            boolean known = false;
            for (ConstraintSpec e : t.constraints) known |= e.type.equals(d.type) && e.refs().equals(d.refs());
            if (!known) out.add(d);
        }
        return out;
    }

    private static List<ConstraintSpec> deriveAll(TemplateDef t, Options o) {
        List<ConstraintSpec> out = new ArrayList<>();
        Map<String, PatternNode> byId = new LinkedHashMap<>();
        for (PatternNode n : t.nodes) byId.put(n.id, n);
        int next = 0;
        for (ConstraintSpec c : t.constraints) {
            if (c.id.startsWith("k")) try { next = Math.max(next, Integer.parseInt(c.id.substring(1))); } catch (NumberFormatException ignored) { }
        }
        Set<String> together = new HashSet<>();   // "a|b": in a common row or column
        if (o.rowsAndColumns) {
            for (boolean row : new boolean[]{true, false}) {
                for (List<PatternNode> line : lines(t.nodes, row, o.tolerance)) {
                    if (line.size() < 2) continue;
                    line.sort(Comparator.comparingDouble(n -> row ? n.x : n.y));
                    double gap = Double.POSITIVE_INFINITY;
                    for (int i = 1; i < line.size(); i++) gap = Math.min(gap, row ? line.get(i).x - line.get(i - 1).x : line.get(i).y - line.get(i - 1).y);
                    gap = Math.max(0, Math.floor(gap / 5) * 5);
                    List<String> ids = new ArrayList<>();
                    for (PatternNode n : line) ids.add(n.id);
                    ConstraintSpec s = new ConstraintSpec("k" + (++next), row ? ConstraintKind.ROW : ConstraintKind.COLUMN);
                    s.name = (row ? "Row: " : "Column: ") + names(line);
                    s.role("ordered", ids).param("gap", gap).param("even", false);
                    out.add(s);
                    for (PatternNode a : line) for (PatternNode b : line) together.add(a.id + "|" + b.id);
                }
            }
        }
        if (o.edgeOrder) {
            Set<String> done = new HashSet<>();
            for (PatternEdge e : t.edges) {
                PatternNode a = byId.get(e.source), b = byId.get(e.target);
                if (a == null || b == null || a == b || together.contains(a.id + "|" + b.id)) continue;
                String key = a.id.compareTo(b.id) < 0 ? a.id + "|" + b.id : b.id + "|" + a.id;
                if (!done.add(key)) continue;
                double dx = b.x - a.x, dy = b.y - a.y;
                boolean x = Math.abs(dx) >= Math.abs(dy);
                PatternNode first = (x ? dx : dy) >= 0 ? a : b, second = first == a ? b : a;
                double d = Math.abs(x ? dx : dy);
                ConstraintSpec s = new ConstraintSpec("k" + (++next), x ? ConstraintKind.SEPARATION_X : ConstraintKind.SEPARATION_Y);
                s.name = first.displayName() + (x ? " left of " : " above ") + second.displayName();
                s.role("first", Collections.singletonList(first.id)).role("second", Collections.singletonList(second.id))
                        .param("axis", x ? "x" : "y").param("distance", Math.floor(d / 5) * 5).param("exact", false);
                out.add(s);
            }
        }
        if (o.rigid && t.nodes.size() >= 2) {
            ConstraintSpec s = new ConstraintSpec("k" + (++next), ConstraintKind.RIGID);
            s.name = "Keep the shape";
            List<String> ids = new ArrayList<>();
            double cx = 0, cy = 0;
            for (PatternNode n : t.nodes) { ids.add(n.id); cx += n.x; cy += n.y; }
            cx /= t.nodes.size();
            cy /= t.nodes.size();
            LinkedHashMap<String, double[]> off = new LinkedHashMap<>();
            for (PatternNode n : t.nodes) off.put(n.id, new double[]{n.x - cx, n.y - cy});
            s.role("nodes", ids).param("fixedPosition", false).param("offsets", off);
            out.add(s);
        }
        return out;
    }

    /** Groups of nodes whose centre y (rows) or x (columns) lie within the tolerance of each other. */
    private static List<List<PatternNode>> lines(List<PatternNode> nodes, boolean row, double tol) {
        List<PatternNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble(n -> row ? n.y : n.x));
        List<List<PatternNode>> groups = new ArrayList<>();
        List<PatternNode> cur = new ArrayList<>();
        double start = Double.NaN;
        for (PatternNode n : sorted) {
            double v = row ? n.y : n.x;
            if (cur.isEmpty() || v - start <= tol) {
                if (cur.isEmpty()) start = v;
                cur.add(n);
            } else {
                groups.add(cur);
                cur = new ArrayList<>();
                cur.add(n);
                start = v;
            }
        }
        if (!cur.isEmpty()) groups.add(cur);
        return groups;
    }

    private static String names(List<PatternNode> ns) {
        List<String> l = new ArrayList<>();
        for (PatternNode n : ns) l.add(n.displayName());
        String s = String.join(", ", l);
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }
}
