package gui;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Axis;
import constraints.BoundaryConstraintGraph;
import constraints.ClusterConstraintGraph;
import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.ConstraintOrigin;
import constraints.ConstraintStore;
import constraints.DesiredPositionConstraintGraph;
import constraints.DistributionConstraintGraph;
import constraints.FixedRelativeConstraintGraph;
import constraints.GridPattern;
import constraints.HorizontalConstraint;
import constraints.LinePattern;
import constraints.LockConstraintGraph;
import constraints.MultiSeparationConstraintGraph;
import constraints.OrthogonalEdgeConstraintGraph;
import constraints.PageBoundaryConstraintGraph;
import constraints.SeparationConstraintGraph;
import constraints.StarPattern;
import constraints.VerticalConstraint;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Everything the "Constraints" tab shows about a {@link ConstraintKind}: pictogram, labels, description, the
 * Adaptagrams types behind it, the settings offered when adding it, the rule for the selection it needs, how it is
 * made from the selection and which of its values can be edited later. The texts for the constraint list
 * ({@link #describe(Constraint)}, {@link #nodesText(Constraint)}) live here as well.
 *
 * @author Niklas Gröne
 */
public final class ConstraintCatalog {

    private ConstraintCatalog() { }

    // ================================================================== data types

    /** How a setting is entered: a number, a number shown with one decimal, a whole number or a check box. */
    public enum ParamType { NUMBER, DECIMAL, INTEGER, BOOLEAN }

    /**
     * A setting of a kind. A number with {@link #autoLabel} has a check box next to it ("auto", "current"): while it
     * is ticked the value is computed from the selection and reported as {@link Double#NaN}.
     */
    public static final class Param {
        public final String key, label, tooltip, unit, autoLabel;
        public final ParamType type;
        public final double def, min, max, step;

        Param(String key, String label, ParamType type, double def, double min, double max, double step,
              String unit, String autoLabel, String tooltip) {
            this.key = key; this.label = label; this.type = type; this.def = def; this.min = min; this.max = max;
            this.step = step; this.unit = unit; this.autoLabel = autoLabel; this.tooltip = tooltip;
        }
    }

    /** A value of an existing constraint the editor can change. */
    public static final class Editable {
        public final Param param;
        public final Function<Constraint, Object> get;
        public final BiConsumer<Constraint, Object> set;

        Editable(Param param, Function<Constraint, Object> get, BiConsumer<Constraint, Object> set) {
            this.param = param; this.get = get; this.set = set;
        }
    }

    /** The nodes and edges a constraint is made from: the selection of the active graph, as far as it can be solved. */
    public static final class Selection {
        public final Graph graph;
        /** Selected nodes the solver moves, in selection order. */
        public final List<Node> nodes;
        /** Selected edges whose two ends the solver moves. */
        public final List<Edge> edges;
        /** Selected nodes the solver does not move (compartments, glyphs inside a complex). */
        public final int ignored;

        public Selection(Graph graph, List<Node> selectedNodes, List<Edge> selectedEdges) {
            this.graph = graph;
            Set<Node> solvable = CBLayoutAlgorithm.userSolveNodes(graph);
            List<Node> ns = new ArrayList<>();
            int skipped = 0;
            for (Node n : selectedNodes) {
                if (solvable.contains(n)) ns.add(n);
                else skipped++;
            }
            List<Edge> es = new ArrayList<>();
            for (Edge e : selectedEdges) {
                if (solvable.contains(e.getSource()) && solvable.contains(e.getTarget()) && e.getSource() != e.getTarget()) es.add(e);
            }
            this.nodes = Collections.unmodifiableList(ns);
            this.edges = Collections.unmodifiableList(es);
            this.ignored = skipped;
        }

        /** Selected edges, or else the edges between the selected nodes. */
        public List<Edge> edgesOrEdgesBetweenNodes() {
            if (!edges.isEmpty()) return edges;
            Set<Node> set = new HashSet<>(nodes);
            List<Edge> l = new ArrayList<>();
            for (Node n : nodes) {
                for (Edge e : n.getEdges()) {
                    if (e.getSource() == n && set.contains(e.getTarget()) && e.getTarget() != n) l.add(e);
                }
            }
            return l;
        }
    }

    /** Makes the constraints of a kind from a selection; throws IllegalArgumentException with a message on a misfit. */
    interface Creator {
        List<Constraint> create(Selection sel, Map<String, Object> values);
    }

    /** What the tab knows about one kind. */
    public static final class Info {
        public final ConstraintKind kind;
        /** Name of the pictogram in /cblayout/icons. */
        public final String icon;
        /** Short label on the tile. */
        public final String label;
        /** Heading of the settings card and of list entries. */
        public final String title;
        /** One or two sentences: what the constraint does. */
        public final String description;
        /** The Adaptagrams types it is made of. */
        public final String adaptagrams;
        /** Fewest nodes (0: needs edges instead), and most nodes (0: no limit). */
        public final int minNodes, maxNodes;
        public final List<Param> params;
        public final List<Editable> editables;
        final Creator creator;

        Info(ConstraintKind kind, String label, String title, String description, String adaptagrams,
             int minNodes, int maxNodes, List<Param> params, List<Editable> editables, Creator creator) {
            this.kind = kind;
            this.icon = kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
            this.label = label; this.title = title; this.description = description; this.adaptagrams = adaptagrams;
            this.minNodes = minNodes; this.maxNodes = maxNodes;
            this.params = params; this.editables = editables; this.creator = creator;
        }

        /**
         * The values of {@code c} the editor offers: the kind's editables and, for a pin or a target on a single node,
         * its position.
         */
        public List<Editable> editablesFor(Constraint c) {
            List<Editable> l = new ArrayList<>(editables);
            if (c.getNodes().size() == 1 && (c instanceof LockConstraintGraph || c instanceof DesiredPositionConstraintGraph)) {
                Node n = c.getNodes().get(0);
                String what = c instanceof LockConstraintGraph ? "pin" : "target";
                for (Axis a : Axis.values()) {
                    Param p = new Param(a == Axis.X ? "x" : "y", a == Axis.X ? "X" : "Y", ParamType.NUMBER, 0, -1e6, 1e6, 10, "px", null,
                            "Position of the " + what + " (the node's centre).");
                    int i = a == Axis.X ? 0 : 1;
                    l.add(new Editable(p, k -> position(k, n)[i], (k, v) -> {
                        double[] pos = position(k, n).clone();
                        pos[i] = (Double) v;
                        if (k instanceof LockConstraintGraph) ((LockConstraintGraph) k).setPosition(n, pos[0], pos[1]);
                        else ((DesiredPositionConstraintGraph) k).setTarget(n, pos[0], pos[1]);
                    }));
                }
            }
            return l;
        }

        private static double[] position(Constraint c, Node n) {
            double[] p = c instanceof LockConstraintGraph ? ((LockConstraintGraph) c).getPosition(n)
                    : ((DesiredPositionConstraintGraph) c).getTarget(n);
            return p == null ? new double[]{0, 0} : p;
        }

        /** True if the kind works on edges (the selected ones, or those between the selected nodes). */
        public boolean needsEdges() {
            return minNodes == 0;
        }

        /** Why the selection does not fit, or null if it does. */
        public String problem(Selection sel) {
            if (sel == null || sel.graph == null) return "Open a graph first.";
            if (needsEdges()) {
                return sel.edgesOrEdgesBetweenNodes().isEmpty()
                        ? "Select edges, or nodes with edges between them." : null;
            }
            int n = sel.nodes.size();
            if (maxNodes > 0 && maxNodes == minNodes && n != minNodes)
                return "Select exactly " + minNodes + " nodes (" + n + " selected).";
            if (n < minNodes)
                return "Select at least " + minNodes + (minNodes == 1 ? " node" : " nodes") + " (" + n + " selected).";
            if (kind == ConstraintKind.CLUSTER) {
                for (ClusterConstraintGraph c : ConstraintStore.constraints == null ? Collections.<ClusterConstraintGraph>emptyList()
                        : ConstraintStore.constraints.getConstraints(ClusterConstraintGraph.class)) {
                    for (Node node : sel.nodes) {
                        if (c.getNodes().contains(node)) return label(node) + " is already in a cluster.";
                    }
                }
            }
            return null;
        }

        /** What the selection amounts to, e.g. "4 nodes selected" or "2 edges". */
        public String selectionText(Selection sel) {
            if (sel == null || sel.graph == null) return "No graph";
            String s;
            if (needsEdges()) {
                int e = sel.edgesOrEdgesBetweenNodes().size();
                s = e + (e == 1 ? " edge" : " edges") + (sel.edges.isEmpty() ? " between the selected nodes" : " selected");
            } else {
                int n = sel.nodes.size();
                s = n + (n == 1 ? " node" : " nodes") + " selected";
            }
            if (sel.ignored > 0) s += " (" + sel.ignored + " ignored)";
            return s;
        }

        /** Makes the constraints; throws IllegalArgumentException with a message if the selection does not fit. */
        public List<Constraint> create(Selection sel, Map<String, Object> values) {
            String p = problem(sel);
            if (p != null) throw new IllegalArgumentException(p);
            return creator.create(sel, values);
        }
    }

    // ================================================================== the catalogue

    private static final Map<ConstraintKind, Info> INFOS = new EnumMap<>(ConstraintKind.class);

    public static Info info(ConstraintKind kind) {
        return INFOS.get(kind);
    }

    public static Info info(Constraint c) {
        return INFOS.get(c.getKind());
    }

    /** The kinds of a category, in palette order. */
    public static List<ConstraintKind> kinds(ConstraintKind.Category category) {
        List<ConstraintKind> l = new ArrayList<>();
        for (ConstraintKind k : ConstraintKind.values()) if (k.getCategory() == category) l.add(k);
        return l;
    }

    public static String categoryLabel(ConstraintKind.Category c) {
        switch (c) {
            case ALIGNMENT: return "Align";
            case SPACING: return "Spacing";
            case GROUPS: return "Groups";
            case EDGES: return "Edges";
            case POSITION: return "Position";
            default: return "Patterns";
        }
    }

    public static String categoryTooltip(ConstraintKind.Category c) {
        switch (c) {
            case ALIGNMENT: return "Put nodes on a common horizontal or vertical line.";
            case SPACING: return "Gaps, order and equal spacing along one axis.";
            case GROUPS: return "Keep groups rigid, together, apart, or inside an area.";
            case EDGES: return "Make edges horizontal or vertical.";
            case POSITION: return "Pin nodes or pull them towards a position.";
            default: return "Rows, columns, grids and stars: common arrangements built from the basic constraints.";
        }
    }

    private static Param number(String key, String label, double def, double min, double max, String autoLabel, String tip) {
        return new Param(key, label, ParamType.NUMBER, def, min, max, 5, "px", autoLabel, tip);
    }

    /** {@code p} without its "auto" box, for editing the value of an existing constraint. */
    private static Param withoutAuto(Param p, String tip) {
        return new Param(p.key, p.label, p.type, p.def, p.min, p.max, p.step, p.unit, null, tip);
    }

    private static Param bool(String key, String label, boolean def, String tip) {
        return new Param(key, label, ParamType.BOOLEAN, def ? 1 : 0, 0, 1, 1, null, null, tip);
    }

    private static List<Param> params(Param... p) {
        return Arrays.asList(p);
    }

    private static List<Editable> editables(Editable... e) {
        return Arrays.asList(e);
    }

    private static <C extends Constraint> Editable edit(Param p, Class<C> type, Function<C, Object> get, BiConsumer<C, Object> set) {
        return new Editable(p, c -> get.apply(type.cast(c)), (c, v) -> set.accept(type.cast(c), v));
    }

    private static void put(Info i) {
        INFOS.put(i.kind, i);
    }

    static {
        // ---- alignment
        String alignY = "AlignmentConstraint (y)", alignX = "AlignmentConstraint (x)";
        put(new Info(ConstraintKind.ALIGN_TOP, "Top", "Align top edges",
                "The top edges of the nodes on one horizontal line.", alignY, 2, 0, params(), editables(),
                (s, v) -> one(new HorizontalConstraint(s.nodes, HorizontalConstraint.HorizontalConstraintType.TOP))));
        put(new Info(ConstraintKind.ALIGN_MIDDLE, "Middle", "Align centres horizontally",
                "The centres of the nodes on one horizontal line.", alignY, 2, 0, params(), editables(),
                (s, v) -> one(new HorizontalConstraint(s.nodes, HorizontalConstraint.HorizontalConstraintType.MIDDLE))));
        put(new Info(ConstraintKind.ALIGN_BOTTOM, "Bottom", "Align bottom edges",
                "The bottom edges of the nodes on one horizontal line.", alignY, 2, 0, params(), editables(),
                (s, v) -> one(new HorizontalConstraint(s.nodes, HorizontalConstraint.HorizontalConstraintType.BOTTOM))));
        put(new Info(ConstraintKind.ALIGN_LEFT, "Left", "Align left edges",
                "The left edges of the nodes on one vertical line.", alignX, 2, 0, params(), editables(),
                (s, v) -> one(new VerticalConstraint(s.nodes, VerticalConstraint.VerticalConstraintType.LEFT))));
        put(new Info(ConstraintKind.ALIGN_CENTER, "Center", "Align centres vertically",
                "The centres of the nodes on one vertical line.", alignX, 2, 0, params(), editables(),
                (s, v) -> one(new VerticalConstraint(s.nodes, VerticalConstraint.VerticalConstraintType.MIDDLE))));
        put(new Info(ConstraintKind.ALIGN_RIGHT, "Right", "Align right edges",
                "The right edges of the nodes on one vertical line.", alignX, 2, 0, params(), editables(),
                (s, v) -> one(new VerticalConstraint(s.nodes, VerticalConstraint.VerticalConstraintType.RIGHT))));

        // ---- spacing
        for (Axis a : Axis.values()) {
            boolean x = a == Axis.X;
            Param distance = number("distance", "Distance", 100, 0, 5000, "current",
                    "Distance between the two centres. <b>current</b>: the distance they have now.");
            Param exact = bool("exact", "Exact distance", false, "Keep exactly this distance instead of at least it.");
            put(new Info(x ? ConstraintKind.SEPARATION_X : ConstraintKind.SEPARATION_Y, x ? "Gap X" : "Gap Y",
                    x ? "Horizontal gap" : "Vertical gap",
                    x ? "The right node stays at least the distance right of the left one (between their centres)."
                      : "The lower node stays at least the distance below the upper one (between their centres).",
                    "SeparationConstraint (" + (x ? "x" : "y") + ")", 2, 2, params(distance, exact),
                    editables(edit(withoutAuto(distance, "Distance between the centres."),
                                    SeparationConstraintGraph.class, SeparationConstraintGraph::getDistance, (c, val) -> c.setDistance((Double) val)),
                            edit(exact, SeparationConstraintGraph.class, SeparationConstraintGraph::isFixed, (c, val) -> c.setFixed((Boolean) val))),
                    (s, v) -> {
                        List<Node> l = sorted(s.nodes, a);
                        double current = pos(l.get(1), a) - pos(l.get(0), a);
                        return one(new SeparationConstraintGraph(l, a.toSeparationType(), bool(v, "exact"), num(v, "distance", current)));
                    }));

            Param gap = number("gap", "Gap", 80, 0, 5000, "auto",
                    "Least distance between consecutive centres. <b>auto</b>: the largest node size plus 20 px.");
            Param exactGaps = bool("exact", "Exact gaps", false, "All gaps exactly this size instead of at least it.");
            put(new Info(x ? ConstraintKind.ORDER_X : ConstraintKind.ORDER_Y, x ? "Order X" : "Order Y",
                    x ? "Keep left-to-right order" : "Keep top-to-bottom order",
                    "The nodes keep their current order " + (x ? "from left to right" : "from top to bottom")
                            + ", consecutive centres at least the gap apart.",
                    "MultiSeparationConstraint (" + (x ? "x" : "y") + ")", 2, 0, params(gap, exactGaps),
                    editables(edit(withoutAuto(gap, "Least distance between consecutive centres."),
                                    MultiSeparationConstraintGraph.class, MultiSeparationConstraintGraph::getGap, (c, val) -> c.setGap((Double) val)),
                            edit(exactGaps, MultiSeparationConstraintGraph.class, MultiSeparationConstraintGraph::isExact, (c, val) -> c.setExact((Boolean) val))),
                    (s, v) -> {
                        List<Node> l = sorted(s.nodes, a);
                        return one(new MultiSeparationConstraintGraph(l, a, num(v, "gap", maxExtent(l, a) + 20), bool(v, "exact")));
                    }));

            Param spacing = number("spacing", "Spacing", 100, 0, 5000, "auto",
                    "Distance between consecutive centres. <b>auto</b>: their current mean distance, at least the largest node size plus 10 px.");
            put(new Info(x ? ConstraintKind.DISTRIBUTE_X : ConstraintKind.DISTRIBUTE_Y, x ? "Distribute X" : "Distribute Y",
                    x ? "Distribute horizontally" : "Distribute vertically",
                    "Equal " + (x ? "horizontal" : "vertical") + " spacing between consecutive centres, in the current order.",
                    "DistributionConstraint (" + (x ? "x" : "y") + ")", 3, 0, params(spacing),
                    editables(edit(withoutAuto(spacing, "Distance between consecutive centres."),
                            DistributionConstraintGraph.class, DistributionConstraintGraph::getSpacing, (c, val) -> c.setSpacing((Double) val))),
                    (s, v) -> {
                        List<Node> l = sorted(s.nodes, a);
                        double mean = (pos(l.get(l.size() - 1), a) - pos(l.get(0), a)) / (l.size() - 1);
                        double auto = Math.max(mean, maxExtent(l, a) + 10);
                        return one(new DistributionConstraintGraph(l, a.toSeparationType(), num(v, "spacing", auto)));
                    }));
        }

        // ---- groups
        Param fixedPos = bool("fixedPosition", "Also resist moving", false,
                "The group also keeps its place as a whole as far as possible.");
        put(new Info(ConstraintKind.RIGID, "Rigid", "Rigid group",
                "The nodes keep their current arrangement and move only as a whole.", "FixedRelativeConstraint", 2, 0,
                params(fixedPos),
                editables(edit(fixedPos, FixedRelativeConstraintGraph.class, FixedRelativeConstraintGraph::isFixedPosition,
                        (c, val) -> c.setFixedPosition((Boolean) val))),
                (s, v) -> one(new FixedRelativeConstraintGraph(s.nodes, bool(v, "fixedPosition")))));

        Param padding = number("padding", "Padding", 15, 0, 500, null, "Space between the cluster's border and its nodes.");
        Param margin = number("margin", "Margin", 10, 0, 500, null, "Space between the cluster's border and the nodes outside.");
        put(new Info(ConstraintKind.CLUSTER, "Cluster", "Cluster",
                "The nodes stay together in a box that no other node enters.", "BoundaryConstraint per occupied side", 1, 0,
                params(padding, margin),
                editables(edit(padding, ClusterConstraintGraph.class, ClusterConstraintGraph::getPadding, (c, val) -> c.setPadding((Double) val)),
                        edit(margin, ClusterConstraintGraph.class, ClusterConstraintGraph::getMargin, (c, val) -> c.setMargin((Double) val))),
                (s, v) -> one(new ClusterConstraintGraph(s.nodes, num(v, "padding", 15), num(v, "margin", 10)))));

        for (Axis a : Axis.values()) {
            boolean x = a == Axis.X;
            Param lineGap = number("gap", "Distance", 20, 0, 1000, null, "Least space between the line and the nodes' borders.");
            put(new Info(x ? ConstraintKind.DIVIDE_X : ConstraintKind.DIVIDE_Y, x ? "Divide X" : "Divide Y",
                    x ? "Divide left | right" : "Divide top / bottom",
                    x ? "A vertical line keeps the nodes on its left apart from those on its right. The selection is split at its widest horizontal gap."
                      : "A horizontal line keeps the nodes above it apart from those below it. The selection is split at its widest vertical gap.",
                    "BoundaryConstraint (" + (x ? "x" : "y") + ")", 2, 0, params(lineGap),
                    editables(edit(lineGap, BoundaryConstraintGraph.class, BoundaryConstraintGraph::getGap, (c, val) -> c.setGap((Double) val))),
                    (s, v) -> {
                        List<Node> l = sorted(s.nodes, a);
                        int cut = 1;
                        double widest = Double.NEGATIVE_INFINITY;
                        for (int i = 1; i < l.size(); i++) {
                            double g = pos(l.get(i), a) - pos(l.get(i - 1), a);
                            if (g > widest) { widest = g; cut = i; }
                        }
                        return one(new BoundaryConstraintGraph(new ArrayList<>(l.subList(0, cut)), new ArrayList<>(l.subList(cut, l.size())),
                                a, num(v, "gap", 20)));
                    }));
        }

        Param areaMargin = number("margin", "Margin", 40, 0, 5000, null, "Room around the nodes' current bounding box.");
        put(new Info(ConstraintKind.AREA, "Area", "Keep inside an area",
                "The nodes stay inside the rectangle around their current positions, enlarged by the margin.",
                "fixed AlignmentConstraint + SeparationConstraint (PageBoundaryConstraints in the force-directed run)",
                1, 0, params(areaMargin),
                editables(edit(areaMargin, PageBoundaryConstraintGraph.class, PageBoundaryConstraintGraph::getMargin, (c, val) -> c.setMargin((Double) val))),
                (s, v) -> one(new PageBoundaryConstraintGraph(s.nodes, bbox(s.nodes), num(v, "margin", 40)))));

        // ---- edges
        for (boolean horizontal : new boolean[]{true, false}) {
            put(new Info(horizontal ? ConstraintKind.EDGE_HORIZONTAL : ConstraintKind.EDGE_VERTICAL,
                    horizontal ? "Horizontal" : "Vertical", horizontal ? "Horizontal edge" : "Vertical edge",
                    "The selected edges (or the edges between the selected nodes) become " + (horizontal ? "horizontal: both ends at one height."
                            : "vertical: both ends at one x position."),
                    "AlignmentConstraint (" + (horizontal ? "y" : "x") + "; OrthogonalEdgeConstraint in the force-directed run)", 0, 0, params(), editables(),
                    (s, v) -> {
                        List<Constraint> l = new ArrayList<>();
                        for (Edge e : s.edgesOrEdgesBetweenNodes()) l.add(new OrthogonalEdgeConstraintGraph(e, horizontal));
                        return l;
                    }));
        }

        // ---- position
        put(new Info(ConstraintKind.PIN, "Pin", "Pin in place",
                "The nodes stay exactly where they are now (for a single node, edit the position in the list).", "Lock (+ fixed guidelines)", 1, 0, params(), editables(),
                (s, v) -> one(new LockConstraintGraph(positions(s.nodes)))));
        Param weight = new Param("weight", "Strength", ParamType.DECIMAL, 1, 0.1, 50, 0.5, null, null,
                "How strongly the nodes are pulled against the edges in a force-directed run; without it the nodes are put "
                        + "on their targets and only other constraints hold them away.");
        put(new Info(ConstraintKind.TARGET, "Target", "Pull towards position",
                "The nodes are pulled softly towards their target, which is where they are now (for a single node, edit it in the list). "
                        + "Unlike a pin, edges and other constraints can hold them away.",
                "DesiredPosition in the force-directed run; otherwise a move onto the point", 1, 0, params(weight),
                editables(edit(weight, DesiredPositionConstraintGraph.class, DesiredPositionConstraintGraph::getWeight, (c, val) -> c.setWeight((Double) val))),
                (s, v) -> one(new DesiredPositionConstraintGraph(positions(s.nodes), num(v, "weight", 1)))));

        // ---- patterns
        for (boolean row : new boolean[]{true, false}) {
            Axis a = row ? Axis.X : Axis.Y;
            Param gap = number("gap", "Gap", 80, 0, 5000, "auto",
                    "Least distance between consecutive centres. <b>auto</b>: the largest node size plus 20 px.");
            Param even = bool("even", "Equal spacing", false, "All gaps exactly this size.");
            put(new Info(row ? ConstraintKind.ROW : ConstraintKind.COLUMN, row ? "Row" : "Column", row ? "Row" : "Column",
                    row ? "The centres on one horizontal line, the current left-to-right order kept."
                        : "The centres on one vertical line, the current top-to-bottom order kept.",
                    "AlignmentConstraint + MultiSeparationConstraint", 2, 0, params(gap, even),
                    editables(edit(withoutAuto(gap, "Least distance between consecutive centres."),
                                    LinePattern.class, LinePattern::getGap, (c, val) -> c.setGap((Double) val)),
                            edit(even, LinePattern.class, LinePattern::isEven, (c, val) -> c.setEven((Boolean) val))),
                    (s, v) -> {
                        List<Node> l = sorted(s.nodes, a);
                        return one(new LinePattern(l, row, num(v, "gap", maxExtent(l, a) + 20), bool(v, "even")));
                    }));
        }

        Param columns = new Param("columns", "Columns", ParamType.INTEGER, 3, 1, 50, 1, null, "auto",
                "Nodes per row. <b>auto</b>: a square grid.");
        Param colGap = number("columnGap", "Col. gap", 100, 0, 5000, "auto",
                "Least distance between neighbouring column centres. <b>auto</b>: the widest node plus 30 px.");
        Param rowGap = number("rowGap", "Row gap", 80, 0, 5000, "auto",
                "Least distance between neighbouring row centres. <b>auto</b>: the tallest node plus 30 px.");
        put(new Info(ConstraintKind.GRID, "Grid", "Grid",
                "The nodes are sorted into rows (top to bottom) and columns (left to right); every row and column stays aligned and in order.",
                "AlignmentConstraint + MultiSeparationConstraint", 3, 0, params(columns, colGap, rowGap),
                editables(edit(withoutAuto(colGap, "Least distance between neighbouring column centres."),
                                GridPattern.class, GridPattern::getColumnGap, (c, val) -> c.setColumnGap((Double) val)),
                        edit(withoutAuto(rowGap, "Least distance between neighbouring row centres."),
                                GridPattern.class, GridPattern::getRowGap, (c, val) -> c.setRowGap((Double) val))),
                (s, v) -> {
                    int n = s.nodes.size();
                    int cols = (int) Math.round(num(v, "columns", Math.ceil(Math.sqrt(n))));
                    cols = Math.max(1, Math.min(n, cols));
                    List<Node> byY = sorted(s.nodes, Axis.Y);
                    List<List<Node>> rows = new ArrayList<>();
                    for (int i = 0; i < n; i += cols) rows.add(sorted(byY.subList(i, Math.min(n, i + cols)), Axis.X));
                    return one(new GridPattern(rows, num(v, "columnGap", maxExtent(s.nodes, Axis.X) + 30),
                            num(v, "rowGap", maxExtent(s.nodes, Axis.Y) + 30)));
                }));

        Param radius = number("radius", "Radius", 120, 10, 5000, "auto",
                "Distance of the ring from the centre node. <b>auto</b>: large enough for the nodes to fit around it.");
        put(new Info(ConstraintKind.STAR, "Star", "Star",
                "The most connected node in the middle, the others evenly on a circle around it, their order around it kept.",
                "SeparationConstraint (exact, x and y)", 3, 0, params(radius),
                editables(edit(withoutAuto(radius, "Distance of the ring from the centre node."),
                        StarPattern.class, StarPattern::getRadius, (c, val) -> c.setRadius((Double) val))),
                (s, v) -> {
                    Node hub = hubOf(s.nodes);
                    List<Node> ring = new ArrayList<>(s.nodes);
                    ring.remove(hub);
                    double hx = AttributeHelper.getPositionX(hub), hy = AttributeHelper.getPositionY(hub);
                    ring.sort(Comparator.comparingDouble(n -> Math.atan2(AttributeHelper.getPositionY(n) - hy, AttributeHelper.getPositionX(n) - hx)));
                    double start = Math.atan2(AttributeHelper.getPositionY(ring.get(0)) - hy, AttributeHelper.getPositionX(ring.get(0)) - hx);
                    double ext = 0;
                    for (Node n : ring) ext = Math.max(ext, Math.max(AttributeHelper.getWidth(n), AttributeHelper.getHeight(n)));
                    double hubExt = Math.max(AttributeHelper.getWidth(hub), AttributeHelper.getHeight(hub));
                    double auto = Math.max(hubExt / 2 + ext / 2 + 40, ring.size() * (ext + 25) / (2 * Math.PI));
                    return one(new StarPattern(hub, ring, num(v, "radius", auto), start));
                }));
    }

    // ================================================================== texts for the list

    /** Heading of a list entry: the kind's title plus its main value, e.g. "Horizontal gap ≥ 120 px". */
    public static String describe(Constraint c) {
        Info i = info(c);
        String t = i == null ? c.getClass().getSimpleName() : i.title;
        if (c instanceof SeparationConstraintGraph) {
            SeparationConstraintGraph s = (SeparationConstraintGraph) c;
            return t + (s.isFixed() ? " = " : " ≥ ") + px(s.getDistance());
        }
        if (c instanceof MultiSeparationConstraintGraph) {
            MultiSeparationConstraintGraph m = (MultiSeparationConstraintGraph) c;
            return t + (m.isExact() ? ", gaps = " : ", gaps ≥ ") + px(m.getGap());
        }
        if (c instanceof DistributionConstraintGraph) return t + " = " + px(((DistributionConstraintGraph) c).getSpacing());
        if (c instanceof LinePattern) {
            LinePattern l = (LinePattern) c;
            return t + (l.isEven() ? ", gaps = " : ", gaps ≥ ") + px(l.getGap());
        }
        if (c instanceof GridPattern) {
            GridPattern g = (GridPattern) c;
            return t + " " + g.getRows().size() + " × " + g.getColumnCount();
        }
        if (c instanceof StarPattern) return t + ", radius " + px(((StarPattern) c).getRadius());
        if (c instanceof BoundaryConstraintGraph) {
            BoundaryConstraintGraph b = (BoundaryConstraintGraph) c;
            return t + " (" + b.getBefore().size() + " | " + b.getAfter().size() + ")";
        }
        if (c instanceof ClusterConstraintGraph) return t + ", padding " + px(((ClusterConstraintGraph) c).getPadding());
        if (c instanceof PageBoundaryConstraintGraph) return t + ", margin " + px(((PageBoundaryConstraintGraph) c).getMargin());
        if (c instanceof DesiredPositionConstraintGraph) {
            return t + String.format(Locale.ROOT, ", strength %.1f", ((DesiredPositionConstraintGraph) c).getWeight());
        }
        if (c instanceof FixedRelativeConstraintGraph && ((FixedRelativeConstraintGraph) c).isFixedPosition()) return t + ", held in place";
        return t;
    }

    /**
     * {@link #describe} with the origin of a template's constraint in front, e.g. "Rule set → Source → Row, gaps ≥ 80 px
     * (Rule set #3)": conflict messages name the hierarchy a constraint comes from.
     */
    public static String describeWithOrigin(Constraint c) {
        ConstraintOrigin o = c.getOrigin();
        if (o == null) return describe(c);
        List<String> p = o.getPath();
        String head = p.size() > 1 ? String.join(" → ", p.subList(0, p.size() - 1)) + " → " : "";
        return head + describe(c) + " (" + o.getInstanceLabel() + ")";
    }

    /** "4 nodes: glucose, ATP, ADP, …" (labels without markup). */
    public static String nodesText(Constraint c) {
        List<Node> ns = c.getNodes();
        StringBuilder sb = new StringBuilder();
        sb.append(ns.size()).append(ns.size() == 1 ? " node: " : " nodes: ");
        for (int i = 0; i < ns.size() && i < 4; i++) {
            if (i > 0) sb.append(", ");
            sb.append(plainLabel(ns.get(i)));
        }
        if (ns.size() > 4) sb.append(", …");
        return sb.toString();
    }

    /** A node's label without HTML markup, or "#id" if it has none. */
    public static String plainLabel(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        l = l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return l.isEmpty() ? "#" + n.getID() : l;
    }

    private static String label(Node n) {
        return "'" + plainLabel(n) + "'";
    }

    public static String px(double v) {
        return number(v) + " px";
    }

    /** {@code v} as a whole number, or with one decimal if it is not close to one. */
    static String number(double v) {
        return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ================================================================== helpers for the creators

    private static List<Constraint> one(Constraint c) {
        List<Constraint> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    /** The value of a number setting; NaN (its "auto" box ticked) or missing gives {@code auto}. */
    private static double num(Map<String, Object> v, String key, double auto) {
        Object o = v == null ? null : v.get(key);
        if (!(o instanceof Number)) return auto;
        double d = ((Number) o).doubleValue();
        return Double.isNaN(d) ? auto : d;
    }

    private static boolean bool(Map<String, Object> v, String key) {
        Object o = v == null ? null : v.get(key);
        return o instanceof Boolean && (Boolean) o;
    }

    private static double pos(Node n, Axis a) {
        return a == Axis.X ? AttributeHelper.getPositionX(n) : AttributeHelper.getPositionY(n);
    }

    /** The nodes sorted by their centre along {@code a} (ties by the other coordinate). */
    private static List<Node> sorted(List<Node> nodes, Axis a) {
        List<Node> l = new ArrayList<>(nodes);
        Axis other = a == Axis.X ? Axis.Y : Axis.X;
        l.sort(Comparator.<Node>comparingDouble(n -> pos(n, a)).thenComparingDouble(n -> pos(n, other)));
        return l;
    }

    /** The largest node size along {@code a}. */
    private static double maxExtent(List<Node> nodes, Axis a) {
        double m = 0;
        for (Node n : nodes) m = Math.max(m, a == Axis.X ? AttributeHelper.getWidth(n) : AttributeHelper.getHeight(n));
        return m;
    }

    /** Bounding box {minX, minY, maxX, maxY} of the node borders. */
    private static double[] bbox(List<Node> nodes) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Node n : nodes) {
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            double hw = AttributeHelper.getWidth(n) / 2, hh = AttributeHelper.getHeight(n) / 2;
            b[0] = Math.min(b[0], x - hw); b[1] = Math.min(b[1], y - hh);
            b[2] = Math.max(b[2], x + hw); b[3] = Math.max(b[3], y + hh);
        }
        return b;
    }

    private static Map<Node, double[]> positions(List<Node> nodes) {
        Map<Node, double[]> m = new LinkedHashMap<>();
        for (Node n : nodes) m.put(n, new double[]{AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)});
        return m;
    }

    /** The node with the most edges to the other nodes; ties go to the one closest to their centroid. */
    private static Node hubOf(List<Node> nodes) {
        Set<Node> set = new HashSet<>(nodes);
        double cx = 0, cy = 0;
        for (Node n : nodes) { cx += AttributeHelper.getPositionX(n); cy += AttributeHelper.getPositionY(n); }
        cx /= nodes.size();
        cy /= nodes.size();
        Node best = null;
        int bestDeg = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Node n : nodes) {
            int deg = 0;
            for (Node m : n.getNeighbors()) if (m != n && set.contains(m)) deg++;
            double d = Math.hypot(AttributeHelper.getPositionX(n) - cx, AttributeHelper.getPositionY(n) - cy);
            if (deg > bestDeg || (deg == bestDeg && d < bestDist)) { best = n; bestDeg = deg; bestDist = d; }
        }
        return best;
    }
}
