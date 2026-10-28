package gui;

import constraints.Axis;
import constraints.BoundaryConstraintGraph;
import constraints.ClusterConstraintGraph;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintKind;
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
import gui.components.Ui;

import org.AttributeHelper;
import org.graffiti.editor.GravistoService;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugin.view.View;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transparent component in the frame's {@link JLayeredPane} that draws the shown constraints over the active VANTED
 * view without adding anything to the graph; it registers no mouse listeners, so clicks reach the view. Each
 * constraint is drawn in the colour of its {@link ConstraintKind.Category category}, a conflicting one in the
 * conflict colour of the {@link ConflictPanel} and a switched-off one in grey, both with a badge.
 *
 * @author Niklas Gröne
 */
public class ConstraintOverlay extends JComponent {

    private static final Map<JFrame, ConstraintOverlay> INSTANCES = new IdentityHashMap<>();

    // ---------------------------------------------------------------- colours, one per category

    /** Alignments: the line through the aligned edges or centres. */
    static final Color ALIGNMENT_COLOR = new Color(0x25, 0x63, 0xEB);   // blue
    /** Separations, orders, distributions: dimension arrows along the axis. */
    static final Color SPACING_COLOR = new Color(0x7E, 0x22, 0xCE);     // purple
    /** Rigid groups, clusters, dividers, areas: outlines around or between nodes. */
    static final Color GROUPS_COLOR = new Color(0x0D, 0x94, 0x88);      // teal
    /** Orthogonal edges: the highlighted edge segment. */
    static final Color EDGES_COLOR = new Color(0x43, 0x38, 0xCA);       // indigo
    /** Pins and targets: symbols at absolute positions. */
    static final Color POSITION_COLOR = new Color(0xC0, 0x26, 0xD3);    // magenta
    /** Rows, columns, grids, stars: guide lines of the pattern. */
    static final Color PATTERNS_COLOR = new Color(0x15, 0x5E, 0x75);    // dark cyan

    /** Opacity of automatic (pipeline) constraints, so the user's own ones stand out. */
    private static final int AUTOMATIC_ALPHA = 150;
    /** Opacity of the translucent fills of areas and clusters. */
    private static final int FILL_ALPHA = 22;
    private static final Color HALO = new Color(255, 255, 255, 225);

    // ---------------------------------------------------------------- strokes and sizes (screen pixels)

    private static final Stroke LINE_STROKE = new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke DASH_STROKE = new BasicStroke(
            2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7, 5}, 0);
    private static final Stroke GUIDE_STROKE = new BasicStroke(
            1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{6, 4}, 0);
    private static final Stroke THIN_STROKE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke HAIR_STROKE = new BasicStroke(1f);
    private static final Stroke DOT_STROKE = new BasicStroke(
            1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{1.5f, 3f}, 0);
    private static final Stroke HALO_STROKE = new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    /** Translucent band under a highlighted edge. */
    private static final Stroke HIGHLIGHT_STROKE = new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke SYMBOL_STROKE = new BasicStroke(1.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
    private static final Stroke SYMBOL_HALO_STROKE = new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    /** How far guide lines reach beyond the outermost node borders. */
    private static final double OVERHANG = 12;
    /** Distance between the node borders and a dimension line. */
    private static final double DIM_OFFSET = 16;
    /** Half length of the tick marks on lines. */
    private static final double TICK = 5;
    private static final double ARROW_LENGTH = 8;
    private static final double ARROW_HALF_WIDTH = 4;
    /** Gap between a node border and an arrow that starts or ends at it. */
    private static final double BORDER_GAP = 3;

    private static Font labelFont;

    private final List<Constraint> constraints = new ArrayList<>();
    private final JFrame frame;

    private ConstraintOverlay(JFrame frame) {
        this.frame = frame;
        setOpaque(false);
        setFocusable(false);
    }

    /** The overlay of the active view's frame, installed on first use; null if there is no active view in a frame. */
    public static ConstraintOverlay getOrInstall() {
        try {
            JComponent viewComp = activeViewComponent();
            if (viewComp == null) return null;
            Window w = SwingUtilities.getWindowAncestor(viewComp);
            if (!(w instanceof JFrame)) return null;
            JFrame frame = (JFrame) w;
            ConstraintOverlay existing = INSTANCES.get(frame);
            if (existing != null) return existing;
            ConstraintOverlay overlay = new ConstraintOverlay(frame);
            JLayeredPane layered = frame.getLayeredPane();
            overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
            layered.add(overlay, JLayeredPane.PALETTE_LAYER);
            ComponentAdapter resize = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
                    overlay.repaint();
                }
            };
            layered.addComponentListener(resize);
            // a closed frame (e.g. a detached view) gives its overlay up, so neither keeps the other alive
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    frame.removeWindowListener(this);
                    layered.removeComponentListener(resize);
                    layered.remove(overlay);
                    overlay.constraints.clear();
                    INSTANCES.remove(frame);
                }
            });
            INSTANCES.put(frame, overlay);
            return overlay;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replace the overlay's constraint set and repaint. */
    public void setConstraints(Collection<Constraint> cs) {
        constraints.clear();
        if (cs != null) constraints.addAll(cs);
        repaint();
    }

    public void clearConstraints() {
        constraints.clear();
        repaint();
    }

    /** Repaints the overlays of all frames, e.g. after a layout step moved nodes. */
    public static void repaintAll() {
        for (ConstraintOverlay o : INSTANCES.values()) {
            o.repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (constraints.isEmpty()) return;
        View view = activeView();
        JComponent viewComp = activeViewComponent();
        if (view == null || viewComp == null) return;
        if (!viewComp.isShowing()) return;
        if (SwingUtilities.getWindowAncestor(viewComp) != frame) return;

        // Only the constraints of the graph the active view shows: nodes of another graph have no place on it.
        Graph activeGraph = view.getGraph();
        List<Constraint> shown = new ArrayList<>();
        for (Constraint c : constraints) if (belongsTo(c, activeGraph)) shown.add(c);
        if (shown.isEmpty()) return;

        AffineTransform zoom = view.getZoom();
        if (zoom == null) zoom = new AffineTransform();

        Point compToOverlay = SwingUtilities.convertPoint(viewComp, 0, 0, this);
        // Clip to the visible part (the scroll pane's viewport), or lines bleed into the inspector and the scroll bars.
        Rectangle visibleInView = viewComp.getVisibleRect();
        Rectangle visibleInOverlay = SwingUtilities.convertRectangle(viewComp, visibleInView, this);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setClip(visibleInOverlay);
        paintConstraints(g2, shown, zoom, compToOverlay);
        g2.dispose();
    }

    /** True if every node of {@code c} is in {@code g}. */
    private static boolean belongsTo(Constraint c, Graph g) {
        if (c == null || g == null || c.getNodes() == null || c.getNodes().isEmpty()) return false;
        try {
            for (Node n : c.getNodes()) if (n.getGraph() != g || !g.containsNode(n)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ drawing

    /**
     * Paints {@code cs} (null entries ignored) into {@code g} without changing its state: areas and outlines first,
     * the badges last. A constraint that cannot be painted (stale nodes, missing attributes) is skipped, so this
     * method never throws.
     *
     * @param zoom   graph to view-component transform (the view's zoom), null for the identity
     * @param offset translation from view-component to {@code g} coordinates, null for none
     */
    public static void paintConstraints(Graphics2D g, Collection<? extends Constraint> cs, AffineTransform zoom,
                                        Point offset) {
        if (g == null || cs == null || cs.isEmpty()) return;
        AffineTransform z = zoom != null ? zoom : new AffineTransform();
        Point off = offset != null ? offset : new Point();
        List<Constraint> ordered = new ArrayList<>();
        for (Constraint c : cs) if (c != null) ordered.add(c);
        try {
            ordered.sort(Comparator.comparingInt(ConstraintOverlay::layer));
        } catch (Throwable ignored) {
            // keep the given order
        }

        for (Constraint c : ordered) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                prepare(g2);
                ConflictLog.Entry conflict = ConflictLog.entryFor(c);
                paintConstraint(new Canvas(g2, z, off, colorFor(c, conflict)), c);
            } catch (Throwable ignored) {
                // stale nodes or missing attributes: skip this one
            } finally {
                g2.dispose();
            }
        }
        List<Rectangle2D> obstacles = null;   // node boxes the badges avoid, collected on the first conflict
        for (Constraint c : ordered) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                ConflictLog.Entry conflict = ConflictLog.entryFor(c);
                String badge = !c.isEnabled() ? "Off" : conflict != null ? conflict.title() : null;
                if (badge != null && !c.getNodes().isEmpty()) {
                    prepare(g2);
                    Canvas cv = new Canvas(g2, z, off, colorFor(c, conflict));
                    if (obstacles == null) obstacles = nodeBoxes(cv, c.getNodes());
                    paintWarning(cv, c.getNodes(), badge, obstacles);
                }
            } catch (Throwable ignored) {
                // no badge for this one
            } finally {
                g2.dispose();
            }
        }
    }

    private static void prepare(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Paint order: areas and group outlines at the bottom, lines in between, position symbols on top. */
    private static int layer(Constraint c) {
        if (c instanceof PageBoundaryConstraintGraph) return 0;
        if (c instanceof ClusterConstraintGraph) return 1;
        if (c instanceof FixedRelativeConstraintGraph) return 2;
        if (c instanceof BoundaryConstraintGraph) return 3;
        if (c instanceof GridPattern || c instanceof LinePattern || c instanceof StarPattern) return 4;
        if (c instanceof SeparationConstraintGraph || c instanceof MultiSeparationConstraintGraph
                || c instanceof DistributionConstraintGraph) return 6;
        if (c instanceof OrthogonalEdgeConstraintGraph) return 7;
        if (c instanceof DesiredPositionConstraintGraph) return 8;
        if (c instanceof LockConstraintGraph) return 9;
        return 5;
    }

    /**
     * Grey for a constraint the user switched off; the conflict colour for a dropped, unsatisfied or not applied one;
     * else the category colour (lighter if automatic).
     */
    private static Color colorFor(Constraint c, ConflictLog.Entry conflict) {
        if (!c.isEnabled()) return withAlpha(ConflictPanel.WARNING_COLOR, AUTOMATIC_ALPHA);
        if (conflict != null) {
            return conflict.kind == ConflictLog.Kind.DROPPED ? ConflictPanel.DROPPED_COLOR
                    // translucent: what it contradicts may lie right under it (two gaps between the same nodes)
                    : conflict.kind == ConflictLog.Kind.DEACTIVATED ? withAlpha(ConflictPanel.DEACTIVATED_COLOR, AUTOMATIC_ALPHA)
                    : ConflictPanel.UNSATISFIED_COLOR;
        }
        Color base = categoryColor(c);
        return c.isAutomatic() ? withAlpha(base, AUTOMATIC_ALPHA) : base;
    }

    private static Color categoryColor(Constraint c) {
        ConstraintKind kind = null;
        try {
            kind = c.getKind();
        } catch (Throwable ignored) {
            // unknown subclass
        }
        if (kind == null) return ALIGNMENT_COLOR;
        switch (kind.getCategory()) {
            case SPACING: return SPACING_COLOR;
            case GROUPS: return GROUPS_COLOR;
            case EDGES: return EDGES_COLOR;
            case POSITION: return POSITION_COLOR;
            case PATTERNS: return PATTERNS_COLOR;
            default: return ALIGNMENT_COLOR;
        }
    }

    /** {@code c} with its opacity scaled by {@code alpha} / 255. */
    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() * alpha / 255);
    }

    private static void paintConstraint(Canvas cv, Constraint c) {
        if (c.getNodes() == null) return;
        if (c instanceof HorizontalConstraint) paintHorizontal(cv, (HorizontalConstraint) c);
        else if (c instanceof VerticalConstraint) paintVertical(cv, (VerticalConstraint) c);
        else if (c instanceof SeparationConstraintGraph) paintSeparation(cv, (SeparationConstraintGraph) c);
        else if (c instanceof MultiSeparationConstraintGraph) paintMultiSeparation(cv, (MultiSeparationConstraintGraph) c);
        else if (c instanceof DistributionConstraintGraph) paintDistribution(cv, (DistributionConstraintGraph) c);
        else if (c instanceof FixedRelativeConstraintGraph) paintRigid(cv, (FixedRelativeConstraintGraph) c);
        else if (c instanceof ClusterConstraintGraph) paintCluster(cv, (ClusterConstraintGraph) c);
        else if (c instanceof BoundaryConstraintGraph) paintBoundary(cv, (BoundaryConstraintGraph) c);
        else if (c instanceof PageBoundaryConstraintGraph) paintArea(cv, (PageBoundaryConstraintGraph) c);
        else if (c instanceof OrthogonalEdgeConstraintGraph) paintOrthogonalEdge(cv, (OrthogonalEdgeConstraintGraph) c);
        else if (c instanceof LockConstraintGraph) paintLock(cv, (LockConstraintGraph) c);
        else if (c instanceof DesiredPositionConstraintGraph) paintTargets(cv, (DesiredPositionConstraintGraph) c);
        else if (c instanceof LinePattern) paintLine(cv, (LinePattern) c);
        else if (c instanceof GridPattern) paintGrid(cv, (GridPattern) c);
        else if (c instanceof StarPattern) paintStar(cv, (StarPattern) c);
    }

    // ---------------------------------------------------------------- alignment

    /** Horizontal line along the top edges, through the centres or along the bottom edges. */
    private static void paintHorizontal(Canvas cv, HorizontalConstraint c) {
        List<Node> ns = c.getNodes();
        if (ns.isEmpty()) return;
        HorizontalConstraint.HorizontalConstraintType type = c.getType();
        double[] at = new double[ns.size()];
        double[] feature = new double[ns.size()];
        double from = Double.POSITIVE_INFINITY, to = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ns.size(); i++) {
            Node n = ns.get(i);
            at[i] = x(n);
            feature[i] = type == HorizontalConstraint.HorizontalConstraintType.TOP ? y(n) - h(n) / 2
                    : type == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? y(n) + h(n) / 2 : y(n);
            from = Math.min(from, x(n) - w(n) / 2);
            to = Math.max(to, x(n) + w(n) / 2);
        }
        boolean centres = type != HorizontalConstraint.HorizontalConstraintType.TOP
                && type != HorizontalConstraint.HorizontalConstraintType.BOTTOM;
        paintAlignment(cv, true, median(feature), from, to, at, feature, centres ? ns : null);
    }

    /** Vertical line along the left edges, through the centres or along the right edges. */
    private static void paintVertical(Canvas cv, VerticalConstraint c) {
        List<Node> ns = c.getNodes();
        if (ns.isEmpty()) return;
        VerticalConstraint.VerticalConstraintType type = c.getType();
        double[] at = new double[ns.size()];
        double[] feature = new double[ns.size()];
        double from = Double.POSITIVE_INFINITY, to = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ns.size(); i++) {
            Node n = ns.get(i);
            at[i] = y(n);
            feature[i] = type == VerticalConstraint.VerticalConstraintType.LEFT ? x(n) - w(n) / 2
                    : type == VerticalConstraint.VerticalConstraintType.RIGHT ? x(n) + w(n) / 2 : x(n);
            from = Math.min(from, y(n) - h(n) / 2);
            to = Math.max(to, y(n) + h(n) / 2);
        }
        boolean centres = type != VerticalConstraint.VerticalConstraintType.LEFT
                && type != VerticalConstraint.VerticalConstraintType.RIGHT;
        paintAlignment(cv, false, median(feature), from, to, at, feature, centres ? ns : null);
    }

    /**
     * The alignment line at {@code line} (y if {@code horizontal}, else x) from {@code from} to {@code to} along it
     * (graph coordinates), a tick at every node, and a dotted connector from a node's aligned feature to the line
     * where they differ. The callers put the line at the median of the features, so it lies on the aligned nodes even
     * if one is off. With {@code centres} (a line through the centres of these nodes) the line and the connectors
     * leave out the inside of the nodes, so they do not strike through the labels, and the gaps replace the ticks.
     */
    private static void paintAlignment(Canvas cv, boolean horizontal, double line, double from, double to,
                                       double[] at, double[] feature, List<Node> centres) {
        Point2D a = horizontal ? cv.p(from, line) : cv.p(line, from);
        Point2D b = horizontal ? cv.p(to, line) : cv.p(line, to);
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        g.setStroke(LINE_STROKE);
        Line2D axis = horizontal
                ? new Line2D.Double(a.getX() - OVERHANG, a.getY(), b.getX() + OVERHANG, b.getY())
                : new Line2D.Double(a.getX(), a.getY() - OVERHANG, b.getX(), b.getY() + OVERHANG);
        if (centres != null) {
            cv.drawOutside(axis, centres);
            // Deviations: dotted from the border of an off-centre node to the line.
            g.setStroke(DOT_STROKE);
            for (int i = 0; i < at.length; i++) {
                Point2D q = horizontal ? cv.p(at[i], line) : cv.p(line, at[i]);
                Point2D f = horizontal ? cv.p(at[i], feature[i]) : cv.p(feature[i], at[i]);
                if (f.distance(q) > 1.5) cv.drawOutside(new Line2D.Double(f, q), centres);
            }
            return;
        }
        g.draw(axis);
        for (int i = 0; i < at.length; i++) {
            Point2D q = horizontal ? cv.p(at[i], line) : cv.p(line, at[i]);
            Point2D f = horizontal ? cv.p(at[i], feature[i]) : cv.p(feature[i], at[i]);
            g.setStroke(STROKE);
            if (horizontal) g.draw(new Line2D.Double(q.getX(), q.getY() - TICK, q.getX(), q.getY() + TICK));
            else g.draw(new Line2D.Double(q.getX() - TICK, q.getY(), q.getX() + TICK, q.getY()));
            if (f.distance(q) > 1.5) {
                g.setStroke(DOT_STROKE);
                g.draw(new Line2D.Double(f, q));
                cv.dot(f, 2.5);
            }
        }
    }

    // ---------------------------------------------------------------- spacing

    /** Dimension arrow between the two centres along the axis: dashed for a minimum, solid if exact. */
    private static void paintSeparation(Canvas cv, SeparationConstraintGraph c) {
        List<Node> ns = c.getNodes();
        if (ns.size() < 2) return;
        String label = (c.isFixed() ? "= " : "\u2265 ") + fmt(c.getDistance());
        paintDimensionChain(cv, ns.subList(0, 2), Axis.of(c.getType()), c.isFixed(), true, label);
    }

    /** Arrows from each node to the next along the axis, in the order of the constraint. */
    private static void paintMultiSeparation(Canvas cv, MultiSeparationConstraintGraph c) {
        List<Node> ns = c.getNodes();
        if (ns.size() < 2) return;
        String label = (c.isExact() ? "= " : "\u2265 ") + fmt(c.getGap());
        paintDimensionChain(cv, ns, c.getAxis(), c.isExact(), false, label);
    }

    /**
     * A dimension line above (x) or right of (y) the nodes with extension lines to the node centres and an arrow
     * between each pair of consecutive centres (double-headed for a single distance, pointing to the next node for
     * an order); dashed for a minimum, solid if exact.
     */
    private static void paintDimensionChain(Canvas cv, List<Node> ns, Axis axis, boolean exact, boolean doubleHeaded,
                                            String label) {
        boolean x = axis == Axis.X;
        int k = ns.size();
        Point2D[] centre = new Point2D[k];
        Rectangle2D[] box = new Rectangle2D[k];
        double level = x ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            centre[i] = cv.centre(ns.get(i));
            box[i] = cv.box(ns.get(i));
            level = x ? Math.min(level, box[i].getMinY() - DIM_OFFSET) : Math.max(level, box[i].getMaxX() + DIM_OFFSET);
        }
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        g.setStroke(HAIR_STROKE);
        for (int i = 0; i < k; i++) {
            if (x) g.draw(new Line2D.Double(centre[i].getX(), box[i].getMinY() - BORDER_GAP, centre[i].getX(), level - TICK));
            else g.draw(new Line2D.Double(box[i].getMaxX() + BORDER_GAP, centre[i].getY(), level + TICK, centre[i].getY()));
        }
        double labelAt = Double.NaN, longest = -1;
        for (int i = 0; i + 1 < k; i++) {
            Point2D a = x ? new Point2D.Double(centre[i].getX(), level) : new Point2D.Double(level, centre[i].getY());
            Point2D b = x ? new Point2D.Double(centre[i + 1].getX(), level) : new Point2D.Double(level, centre[i + 1].getY());
            double d = a.distance(b);
            if (d > longest) {
                longest = d;
                labelAt = x ? (a.getX() + b.getX()) / 2 : (a.getY() + b.getY()) / 2;
            }
            if (d < 1) continue;
            cv.arrow(a, b, exact ? STROKE : DASH_STROKE, doubleHeaded);
        }
        // The label goes to the longest arrow, where it has the most room.
        if (x) cv.label(label, labelAt, level - 4, 0.5, 1);
        else cv.label(label, level + 6, labelAt, 0, 0.5);
    }

    /** Equal-gap ticks between consecutive centres on a line below (x) or left of (y) the nodes. */
    private static void paintDistribution(Canvas cv, DistributionConstraintGraph c) {
        List<Node> ns = c.getNodes();
        if (ns.size() < 2) return;
        boolean x = c.getType() != SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
        int k = ns.size();
        Point2D[] centre = new Point2D[k];
        Rectangle2D[] box = new Rectangle2D[k];
        double level = x ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            centre[i] = cv.centre(ns.get(i));
            box[i] = cv.box(ns.get(i));
            level = x ? Math.max(level, box[i].getMaxY() + DIM_OFFSET) : Math.min(level, box[i].getMinX() - DIM_OFFSET);
        }
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double along = x ? centre[i].getX() : centre[i].getY();
            lo = Math.min(lo, along);
            hi = Math.max(hi, along);
            g.setStroke(HAIR_STROKE);
            if (x) g.draw(new Line2D.Double(along, box[i].getMaxY() + BORDER_GAP, along, level));
            else g.draw(new Line2D.Double(box[i].getMinX() - BORDER_GAP, along, level, along));
            g.setStroke(STROKE);
            if (x) g.draw(new Line2D.Double(along, level - TICK, along, level + TICK));
            else g.draw(new Line2D.Double(level - TICK, along, level + TICK, along));
        }
        g.setStroke(STROKE);
        if (x) g.draw(new Line2D.Double(lo, level, hi, level));
        else g.draw(new Line2D.Double(level, lo, level, hi));
        // Two short slashes in the middle of every gap: the usual mark for equal segments.
        g.setStroke(THIN_STROKE);
        for (int i = 0; i + 1 < k; i++) {
            double m = x ? (centre[i].getX() + centre[i + 1].getX()) / 2 : (centre[i].getY() + centre[i + 1].getY()) / 2;
            for (int s = -1; s <= 1; s += 2) {
                double d = s * 2.5;
                if (x) g.draw(new Line2D.Double(m + d - 2.5, level + 4.5, m + d + 2.5, level - 4.5));
                else g.draw(new Line2D.Double(level - 4.5, m + d + 2.5, level + 4.5, m + d - 2.5));
            }
        }
        String label = "= " + fmt(c.getSpacing());
        double first = x ? (centre[0].getX() + centre[1].getX()) / 2 : (centre[0].getY() + centre[1].getY()) / 2;
        if (x) cv.label(label, first, level + 7, 0.5, 0);
        else cv.label(label, level - 11, first, 1, 0.5);
    }

    // ---------------------------------------------------------------- groups

    /** Solid rounded frame around the group and a padlock at its top right corner. */
    private static void paintRigid(Canvas cv, FixedRelativeConstraintGraph c) {
        Rectangle2D r = cv.bounds(c.getNodes());
        if (r == null) return;
        double pad = 7;
        RoundRectangle2D frame = new RoundRectangle2D.Double(r.getX() - pad, r.getY() - pad,
                r.getWidth() + 2 * pad, r.getHeight() + 2 * pad, 14, 14);
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        g.setStroke(STROKE);
        g.draw(frame);
        paintPadlock(cv, frame.getMaxX(), frame.getMinY());
    }

    /** Padlock on a white disc centred at (x, y). */
    private static void paintPadlock(Canvas cv, double x, double y) {
        Graphics2D g = cv.g;
        Ellipse2D disc = new Ellipse2D.Double(x - 9.5, y - 9.5, 19, 19);
        g.setColor(Color.WHITE);
        g.fill(disc);
        g.setColor(cv.color);
        g.setStroke(THIN_STROKE);
        g.draw(disc);
        g.setStroke(SYMBOL_STROKE);
        Path2D shackle = new Path2D.Double();
        shackle.moveTo(x - 3, y - 0.5);
        shackle.lineTo(x - 3, y - 2.5);
        shackle.append(new Arc2D.Double(x - 3, y - 5.5, 6, 6, 180, -180, Arc2D.OPEN), true);
        shackle.lineTo(x + 3, y - 0.5);
        g.draw(shackle);
        g.fill(new RoundRectangle2D.Double(x - 4.5, y - 1, 9, 6.5, 2, 2));
    }

    /** Dashed rounded frame around the members, enlarged by the padding, with a light fill. */
    private static void paintCluster(Canvas cv, ClusterConstraintGraph c) {
        List<Node> ns = c.getNodes();
        if (ns.isEmpty()) return;
        double pad = Math.max(0, c.getPadding());
        double[] b = graphBounds(ns);
        Rectangle2D r = cv.rect(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad);
        RoundRectangle2D frame = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 16, 16);
        Graphics2D g = cv.g;
        g.setColor(withAlpha(cv.color, FILL_ALPHA));
        g.fill(frame);
        g.setColor(cv.color);
        g.setStroke(DASH_STROKE);
        g.draw(frame);
    }

    /**
     * The dividing line in the middle of the gap between the two groups, spanning both, with short arrows at its
     * ends pointing to the side of each group.
     */
    private static void paintBoundary(Canvas cv, BoundaryConstraintGraph c) {
        List<Node> before = c.getBefore(), after = c.getAfter();
        if (before.isEmpty() || after.isEmpty()) return;
        boolean x = c.getAxis() == Axis.X;
        double[] bb = graphBounds(before);
        double[] ab = graphBounds(after);
        double[] all = graphBounds(c.getNodes());
        // x: vertical line between the right border of 'before' and the left border of 'after'; y: dual.
        double at = x ? (bb[2] + ab[0]) / 2 : (bb[3] + ab[1]) / 2;
        Point2D a = x ? cv.p(at, all[1]) : cv.p(all[0], at);
        Point2D b = x ? cv.p(at, all[3]) : cv.p(all[2], at);
        if (x) {
            a.setLocation(a.getX(), a.getY() - OVERHANG);
            b.setLocation(b.getX(), b.getY() + OVERHANG);
        } else {
            a.setLocation(a.getX() - OVERHANG, a.getY());
            b.setLocation(b.getX() + OVERHANG, b.getY());
        }
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        g.setStroke(LINE_STROKE);
        g.draw(new Line2D.Double(a, b));
        // Side markers: at both ends, one arrow into the 'before' side and one into the 'after' side.
        double len = 15;
        for (Point2D end : new Point2D[]{a, b}) {
            double sign = end == a ? 1 : -1;
            double px = x ? end.getX() : end.getX() + sign * 8;
            double py = x ? end.getY() + sign * 8 : end.getY();
            for (int side = -1; side <= 1; side += 2) {
                Point2D from = x ? new Point2D.Double(px + side * 3, py) : new Point2D.Double(px, py + side * 3);
                Point2D to = x ? new Point2D.Double(px + side * len, py) : new Point2D.Double(px, py + side * len);
                cv.arrow(from, to, THIN_STROKE, false, 7, 4);
            }
        }
    }

    /** The area as a thin frame with a light translucent fill. */
    private static void paintArea(Canvas cv, PageBoundaryConstraintGraph c) {
        Rectangle2D r = cv.rect(c.getMinX(), c.getMinY(), c.getMaxX(), c.getMaxY());
        Graphics2D g = cv.g;
        g.setColor(withAlpha(cv.color, FILL_ALPHA));
        g.fill(r);
        g.setColor(cv.color);
        g.setStroke(THIN_STROKE);
        g.draw(r);
        // Corner brackets make the frame read as a boundary rather than as a node.
        g.setStroke(LINE_STROKE);
        double l = Math.min(14, Math.min(r.getWidth(), r.getHeight()) / 3);
        double x0 = r.getMinX(), y0 = r.getMinY(), x1 = r.getMaxX(), y1 = r.getMaxY();
        Path2D corners = new Path2D.Double();
        corners.moveTo(x0, y0 + l); corners.lineTo(x0, y0); corners.lineTo(x0 + l, y0);
        corners.moveTo(x1 - l, y0); corners.lineTo(x1, y0); corners.lineTo(x1, y0 + l);
        corners.moveTo(x1, y1 - l); corners.lineTo(x1, y1); corners.lineTo(x1 - l, y1);
        corners.moveTo(x0 + l, y1); corners.lineTo(x0, y1); corners.lineTo(x0, y1 - l);
        g.draw(corners);
    }

    // ---------------------------------------------------------------- edges

    /** The segment between the two node borders, highlighted, with a right-angle marker in its middle. */
    private static void paintOrthogonalEdge(Canvas cv, OrthogonalEdgeConstraintGraph c) {
        List<Node> ns = c.getNodes();
        if (ns.size() < 2) return;
        Point2D[] seg = cv.between(ns.get(0), ns.get(1), 0);
        Graphics2D g = cv.g;
        g.setColor(withAlpha(cv.color, 60));
        g.setStroke(HIGHLIGHT_STROKE);
        g.draw(new Line2D.Double(seg[0], seg[1]));
        g.setColor(cv.color);
        g.setStroke(STROKE);
        g.draw(new Line2D.Double(seg[0], seg[1]));

        double mx = (seg[0].getX() + seg[1].getX()) / 2, my = (seg[0].getY() + seg[1].getY()) / 2;
        Path2D mark = new Path2D.Double();
        double len = 16, sq = 7;
        if (c.isHorizontal()) {
            // a perpendicular standing on the edge, with the square of the right angle
            mark.moveTo(mx, my);
            mark.lineTo(mx, my - len);
            mark.moveTo(mx + sq, my);
            mark.lineTo(mx + sq, my - sq);
            mark.lineTo(mx, my - sq);
        } else {
            mark.moveTo(mx, my);
            mark.lineTo(mx + len, my);
            mark.moveTo(mx, my - sq);
            mark.lineTo(mx + sq, my - sq);
            mark.lineTo(mx + sq, my);
        }
        g.setColor(HALO);
        g.setStroke(HALO_STROKE);
        g.draw(mark);
        g.setColor(cv.color);
        g.setStroke(THIN_STROKE);
        g.draw(mark);
    }

    // ---------------------------------------------------------------- positions

    /**
     * A pin at the top right corner of every pinned position; a node that is away from its pin gets a dashed outline
     * at the pinned position and a dashed line from the node to it.
     */
    private static void paintLock(Canvas cv, LockConstraintGraph c) {
        Graphics2D g = cv.g;
        for (Node n : c.getNodes()) {
            double[] pos = c.getPosition(n);
            if (pos == null) continue;
            double w = w(n), h = h(n);
            Rectangle2D pinned = cv.rect(pos[0] - w / 2, pos[1] - h / 2, pos[0] + w / 2, pos[1] + h / 2);
            Point2D pin = cv.p(pos[0], pos[1]);
            Point2D now = cv.centre(n);
            if (pin.distance(now) > 1.5) {
                g.setColor(cv.color);
                g.setStroke(GUIDE_STROKE);
                g.draw(new RoundRectangle2D.Double(pinned.getX(), pinned.getY(), pinned.getWidth(), pinned.getHeight(), 6, 6));
                Point2D start = exit(cv.box(n), now, pin, BORDER_GAP);
                g.setStroke(DASH_STROKE);
                g.draw(new Line2D.Double(start, pin));
                cv.dot(pin, 3);
            }
            paintPin(cv, pinned.getMaxX(), pinned.getMinY());
        }
    }

    /** Map pin whose tip is at (x, y). */
    private static void paintPin(Canvas cv, double x, double y) {
        Graphics2D g = cv.g;
        double r = 5.5, cy = y - 12;
        Area pin = new Area(new Ellipse2D.Double(x - r, cy - r, 2 * r, 2 * r));
        Path2D tail = new Path2D.Double();
        tail.moveTo(x - r * 0.82, cy + r * 0.55);
        tail.lineTo(x + r * 0.82, cy + r * 0.55);
        tail.lineTo(x, y);
        tail.closePath();
        pin.add(new Area(tail));
        g.setColor(HALO);
        g.setStroke(SYMBOL_HALO_STROKE);
        g.draw(pin);
        g.setColor(cv.color);
        g.fill(pin);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(x - 2, cy - 2, 4, 4));
    }

    /**
     * A crosshair at every target and a dashed arrow from the node to it; a node that sits on its target gets a small
     * crosshair badge at its top left corner instead, so its label stays readable.
     */
    private static void paintTargets(Canvas cv, DesiredPositionConstraintGraph c) {
        Graphics2D g = cv.g;
        for (Node n : c.getNodes()) {
            double[] t = c.getTarget(n);
            if (t == null) continue;
            Point2D target = cv.p(t[0], t[1]);
            Point2D now = cv.centre(n);
            double d = target.distance(now);
            if (d <= 3) {
                Rectangle2D box = cv.box(n);
                Ellipse2D disc = new Ellipse2D.Double(box.getMinX() - 9.5, box.getMinY() - 9.5, 19, 19);
                g.setColor(Color.WHITE);
                g.fill(disc);
                paintCrosshair(cv, new Point2D.Double(box.getMinX(), box.getMinY()), 4.5, 1.5, 8);
                continue;
            }
            double r = 6;
            if (d > r + 4) {
                Point2D start = exit(cv.box(n), now, target, BORDER_GAP);
                double ux = (target.getX() - now.getX()) / d, uy = (target.getY() - now.getY()) / d;
                Point2D end = new Point2D.Double(target.getX() - ux * (r + 3), target.getY() - uy * (r + 3));
                if (start.distance(target) > r + 3 + ARROW_LENGTH) cv.arrow(start, end, DASH_STROKE, false);
            }
            paintCrosshair(cv, target, r, 2.5, r + 5);
        }
    }

    /** Circle of radius {@code r} around {@code at} with four ticks from {@code in} to {@code out}, on a halo. */
    private static void paintCrosshair(Canvas cv, Point2D at, double r, double in, double out) {
        double x = at.getX(), y = at.getY();
        Path2D cross = new Path2D.Double();
        cross.append(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r), false);
        cross.moveTo(x - out, y);
        cross.lineTo(x - in, y);
        cross.moveTo(x + in, y);
        cross.lineTo(x + out, y);
        cross.moveTo(x, y - out);
        cross.lineTo(x, y - in);
        cross.moveTo(x, y + in);
        cross.lineTo(x, y + out);
        Graphics2D g = cv.g;
        g.setColor(HALO);
        g.setStroke(HALO_STROKE);
        g.draw(cross);
        g.setColor(cv.color);
        g.setStroke(THIN_STROKE);
        g.draw(cross);
    }

    // ---------------------------------------------------------------- patterns

    /** The guide line of the row / column through the centres and arrows from each node to the next. */
    private static void paintLine(Canvas cv, LinePattern c) {
        List<Node> ns = c.getNodes();
        if (ns.size() < 2) return;
        boolean row = c.isRow();
        double[] b = graphBounds(ns);
        double sum = 0;
        for (Node n : ns) sum += row ? y(n) : x(n);
        double guide = sum / ns.size();
        paintGuide(cv, row, guide, row ? b[0] : b[1], row ? b[2] : b[3], ns);

        Point2D labelA = null, labelB = null;
        for (int i = 0; i + 1 < ns.size(); i++) {
            Node na = ns.get(i), nb = ns.get(i + 1);
            Rectangle2D ra = cv.box(na), rb = cv.box(nb);
            Point2D ca = row ? cv.p(x(na), guide) : cv.p(guide, y(na));
            Point2D cb = row ? cv.p(x(nb), guide) : cv.p(guide, y(nb));
            double ua = row ? ca.getX() : ca.getY(), ub = row ? cb.getX() : cb.getY();
            double dir = ub >= ua ? 1 : -1;
            double s = row ? (dir > 0 ? ra.getMaxX() : ra.getMinX()) : (dir > 0 ? ra.getMaxY() : ra.getMinY());
            double e = row ? (dir > 0 ? rb.getMinX() : rb.getMaxX()) : (dir > 0 ? rb.getMinY() : rb.getMaxY());
            s += dir * BORDER_GAP;
            e -= dir * BORDER_GAP;
            if ((e - s) * dir < ARROW_LENGTH + 2) { // boxes (almost) touch: centre to centre instead
                s = ua;
                e = ub;
            }
            Point2D from = row ? new Point2D.Double(s, ca.getY()) : new Point2D.Double(ca.getX(), s);
            Point2D to = row ? new Point2D.Double(e, cb.getY()) : new Point2D.Double(cb.getX(), e);
            if (from.distance(to) < 1) continue;
            cv.arrow(from, to, STROKE, false);
            if (labelA == null || from.distance(to) > labelA.distance(labelB)) { // label at the longest arrow
                labelA = from;
                labelB = to;
            }
        }
        if (labelA != null) {
            String label = (c.isEven() ? "= " : "\u2265 ") + fmt(c.getGap());
            if (row) cv.label(label, (labelA.getX() + labelB.getX()) / 2, labelA.getY() - 6, 0.5, 1);
            else cv.label(label, labelA.getX() + 8, (labelA.getY() + labelB.getY()) / 2, 0, 0.5);
        }
    }

    /** One guide line per row and per column of the grid. */
    private static void paintGrid(Canvas cv, GridPattern c) {
        for (List<Node> r : c.getRows()) {
            if (r.isEmpty()) continue;
            double[] b = graphBounds(r);
            double sum = 0;
            for (Node n : r) sum += y(n);
            paintGuide(cv, true, sum / r.size(), b[0], b[2], r);
        }
        for (List<Node> col : c.getColumns()) {
            if (col.isEmpty()) continue;
            double[] b = graphBounds(col);
            double sum = 0;
            for (Node n : col) sum += x(n);
            paintGuide(cv, false, sum / col.size(), b[1], b[3], col);
        }
    }

    /**
     * Dashed guide line at {@code at} (y if horizontal, else x) from {@code from} to {@code to} (graph units), left
     * out inside the boxes of {@code ns}.
     */
    private static void paintGuide(Canvas cv, boolean horizontal, double at, double from, double to, List<Node> ns) {
        Point2D a = horizontal ? cv.p(from, at) : cv.p(at, from);
        Point2D b = horizontal ? cv.p(to, at) : cv.p(at, to);
        cv.g.setColor(cv.color);
        cv.g.setStroke(GUIDE_STROKE);
        cv.drawOutside(horizontal
                ? new Line2D.Double(a.getX() - OVERHANG, a.getY(), b.getX() + OVERHANG, b.getY())
                : new Line2D.Double(a.getX(), a.getY() - OVERHANG, b.getX(), b.getY() + OVERHANG), ns);
    }

    /**
     * Dashed circle of the radius around the hub, spokes from the hub to the ring nodes, and a small ring at the
     * place of every ring node that is not on it.
     */
    private static void paintStar(Canvas cv, StarPattern c) {
        Node hub = c.getHub();
        List<Node> ring = c.getRing();
        if (hub == null || ring.isEmpty()) return;
        double hx = x(hub), hy = y(hub), radius = c.getRadius();
        Graphics2D g = cv.g;
        g.setColor(cv.color);
        if (radius > 0) {
            Rectangle2D r = cv.rect(hx - radius, hy - radius, hx + radius, hy + radius);
            g.setStroke(GUIDE_STROKE);
            cv.drawOutside(new Ellipse2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight()), c.getNodes());
        }
        g.setStroke(STROKE);
        for (Node n : ring) {
            Point2D[] seg = cv.between(hub, n, BORDER_GAP);
            g.draw(new Line2D.Double(seg[0], seg[1]));
        }
        for (int i = 0; i < ring.size(); i++) {
            double[] o = c.offset(i);
            Point2D slot = cv.p(hx + o[0], hy + o[1]);
            if (slot.distance(cv.centre(ring.get(i))) > 2) {
                g.setStroke(THIN_STROKE);
                g.draw(new Ellipse2D.Double(slot.getX() - 4, slot.getY() - 4, 8, 8));
            }
        }
    }

    // ---------------------------------------------------------------- conflicts

    /**
     * Small warning badge so a conflict is visible at a glance: at the centre of the constraint's nodes, or, if the
     * badge and its label would cover a node there, below, above, right or left of the nodes, whichever spot is free
     * first, so no node label is hidden by it.
     */
    private static void paintWarning(Canvas cv, List<Node> nodes, String label, List<Rectangle2D> obstacles) {
        double sx = 0, sy = 0;
        Rectangle2D all = null;
        for (Node n : nodes) {
            sx += x(n);
            sy += y(n);
            Rectangle2D b = cv.box(n);
            if (all == null) all = b;
            else all.add(b);
        }
        Point2D c = cv.p(sx / nodes.size(), sy / nodes.size());
        double r = 9;
        double width = 2 * r + 4 + cv.labelWidth(label);   // triangle, gap, text; (x, y) is the triangle's centre
        double[][] spots = {
                {c.getX(), c.getY()},
                {all.getCenterX() - width / 2 + r, all.getMaxY() + r + 8},
                {all.getCenterX() - width / 2 + r, all.getMinY() - r - 8},
                {all.getMaxX() + r + 8, all.getCenterY()},
                {all.getMinX() - width + r - 8, all.getCenterY()}};
        double x = c.getX(), y = c.getY();
        for (double[] s : spots) {
            Rectangle2D block = new Rectangle2D.Double(s[0] - r - 2, s[1] - r - 2, width + 4, 2 * r + 4);
            boolean free = true;
            for (Rectangle2D b : obstacles) {
                if (b.intersects(block)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                x = s[0];
                y = s[1];
                break;
            }
        }
        Path2D triangle = new Path2D.Double();
        triangle.moveTo(x, y - r);
        triangle.lineTo(x - r, y + r);
        triangle.lineTo(x + r, y + r);
        triangle.closePath();
        Graphics2D g = cv.g;
        g.setStroke(LINE_STROKE);
        g.setColor(Color.WHITE);
        g.fill(triangle);
        g.setColor(cv.color);
        g.draw(triangle);
        g.setFont(labelFont().deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("!", (float) (x - fm.stringWidth("!") / 2.0), (float) (y + r - 2));
        cv.label(label, x + r + 4, y + 2, 0, 0.5);
    }

    /** Screen boxes of all nodes of the graph that {@code ns} belong to (of {@code ns} alone if that fails). */
    private static List<Rectangle2D> nodeBoxes(Canvas cv, List<Node> ns) {
        List<Rectangle2D> boxes = new ArrayList<>();
        try {
            for (Node n : ns.get(0).getGraph().getNodes()) boxes.add(cv.box(n));
        } catch (Throwable t) {
            boxes.clear();
            for (Node n : ns) boxes.add(cv.box(n));
        }
        return boxes;
    }

    // ---------------------------------------------------------------- helpers

    private static double x(Node n) { return AttributeHelper.getPositionX(n); }
    private static double y(Node n) { return AttributeHelper.getPositionY(n); }
    private static double w(Node n) { return finite(AttributeHelper.getWidth(n)); }
    private static double h(Node n) { return finite(AttributeHelper.getHeight(n)); }

    private static double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || v < 0 ? 0 : v;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2;
    }

    /** Bounding box {minX, minY, maxX, maxY} of the node borders, graph coordinates. */
    private static double[] graphBounds(Collection<Node> ns) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Node n : ns) {
            b[0] = Math.min(b[0], x(n) - w(n) / 2);
            b[1] = Math.min(b[1], y(n) - h(n) / 2);
            b[2] = Math.max(b[2], x(n) + w(n) / 2);
            b[3] = Math.max(b[3], y(n) + h(n) / 2);
        }
        return b;
    }

    /**
     * Where the ray from {@code from} (the centre of {@code box}) towards {@code to} leaves the box, plus
     * {@code gap}; {@code to} itself if it lies inside.
     */
    private static Point2D exit(Rectangle2D box, Point2D from, Point2D to, double gap) {
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return new Point2D.Double(from.getX(), from.getY());
        double tx = Math.abs(dx) < 1e-9 ? Double.POSITIVE_INFINITY : (box.getWidth() / 2) / Math.abs(dx);
        double ty = Math.abs(dy) < 1e-9 ? Double.POSITIVE_INFINITY : (box.getHeight() / 2) / Math.abs(dy);
        double t = Math.min(tx, ty) + gap / len;
        if (t >= 1) return new Point2D.Double(to.getX(), to.getY());
        return new Point2D.Double(from.getX() + t * dx, from.getY() + t * dy);
    }

    /** "120" for whole numbers, else one decimal. */
    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.format(Locale.ROOT, "%d", (long) Math.rint(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static Font labelFont() {
        if (labelFont == null) {
            Font f;
            try {
                f = Ui.font();
            } catch (Throwable t) {
                f = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
            }
            labelFont = f.deriveFont(Font.BOLD, 11f);
        }
        return labelFont;
    }

    /**
     * Graphics, transform and colour of the constraint being painted, with the drawing primitives. Graph coordinates
     * are mapped to screen pixels before drawing, so strokes, arrows and labels keep their size at every zoom level.
     */
    private static final class Canvas {
        final Graphics2D g;
        final AffineTransform zoom;
        final Point off;
        final Color color;

        Canvas(Graphics2D g, AffineTransform zoom, Point off, Color color) {
            this.g = g;
            this.zoom = zoom;
            this.off = off;
            this.color = color;
        }

        /** Graph point (x, y) in screen coordinates. */
        Point2D p(double x, double y) {
            Point2D dst = zoom.transform(new Point2D.Double(x, y), null);
            return new Point2D.Double(dst.getX() + off.x, dst.getY() + off.y);
        }

        Point2D centre(Node n) {
            return p(x(n), y(n));
        }

        /** Screen rectangle of the graph rectangle (x0, y0) - (x1, y1). */
        Rectangle2D rect(double x0, double y0, double x1, double y1) {
            Point2D a = p(x0, y0), b = p(x1, y1);
            return new Rectangle2D.Double(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                    Math.abs(b.getX() - a.getX()), Math.abs(b.getY() - a.getY()));
        }

        /** Screen rectangle of the node's box. */
        Rectangle2D box(Node n) {
            double w = w(n), h = h(n);
            return rect(x(n) - w / 2, y(n) - h / 2, x(n) + w / 2, y(n) + h / 2);
        }

        /** Screen rectangle around the boxes of the nodes, null if there are none. */
        Rectangle2D bounds(Collection<Node> ns) {
            if (ns == null || ns.isEmpty()) return null;
            double[] b = graphBounds(ns);
            return rect(b[0], b[1], b[2], b[3]);
        }

        /** Segment from the border of a's box to the border of b's box (centre to centre if the boxes overlap). */
        Point2D[] between(Node a, Node b, double gap) {
            Point2D ca = centre(a), cb = centre(b);
            Point2D s = exit(box(a), ca, cb, gap), e = exit(box(b), cb, ca, gap);
            double dx = cb.getX() - ca.getX(), dy = cb.getY() - ca.getY();
            if ((e.getX() - s.getX()) * dx + (e.getY() - s.getY()) * dy <= 0) return new Point2D[]{ca, cb};
            return new Point2D[]{s, e};
        }

        void dot(Point2D at, double r) {
            g.fill(new Ellipse2D.Double(at.getX() - r, at.getY() - r, 2 * r, 2 * r));
        }

        /** Draws {@code s} with the current stroke and colour, except inside the boxes of the nodes. */
        void drawOutside(Shape s, Collection<Node> ns) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Rectangle2D r = g2.getStroke().createStrokedShape(s).getBounds2D();
                r.setRect(r.getX() - 2, r.getY() - 2, r.getWidth() + 4, r.getHeight() + 4);
                Area keep = new Area(r);
                for (Node n : ns) {
                    Rectangle2D b = box(n);
                    if (b.intersects(r)) keep.subtract(new Area(b));
                }
                g2.clip(keep);
                g2.draw(s);
            } finally {
                g2.dispose();
            }
        }

        void arrow(Point2D from, Point2D to, Stroke stroke, boolean doubleHeaded) {
            arrow(from, to, stroke, doubleHeaded, ARROW_LENGTH, ARROW_HALF_WIDTH);
        }

        /** Line from {@code from} to {@code to} with a filled head at {@code to} (and at {@code from}). */
        void arrow(Point2D from, Point2D to, Stroke stroke, boolean doubleHeaded, double length, double halfWidth) {
            double d = from.distance(to);
            if (d < 1e-6) return;
            double ux = (to.getX() - from.getX()) / d, uy = (to.getY() - from.getY()) / d;
            // The shaft stops at the base of the heads so a thick or dashed stroke does not blunt their tips.
            double inset = Math.min(length * 0.8, d / 2);
            Point2D s = doubleHeaded ? new Point2D.Double(from.getX() + ux * inset, from.getY() + uy * inset) : from;
            Point2D e = new Point2D.Double(to.getX() - ux * inset, to.getY() - uy * inset);
            g.setColor(color);
            g.setStroke(stroke);
            g.draw(new Line2D.Double(s, e));
            head(to, ux, uy, length, halfWidth);
            if (doubleHeaded) head(from, -ux, -uy, length, halfWidth);
        }

        /** Filled arrow head with its tip at {@code tip}, pointing along (ux, uy). */
        void head(Point2D tip, double ux, double uy, double length, double halfWidth) {
            double bx = tip.getX() - ux * length, by = tip.getY() - uy * length;
            Path2D p = new Path2D.Double();
            p.moveTo(tip.getX(), tip.getY());
            p.lineTo(bx - uy * halfWidth, by + ux * halfWidth);
            p.lineTo(bx + uy * halfWidth, by - ux * halfWidth);
            p.closePath();
            g.fill(p);
        }

        /** Width in pixels of {@code text} as {@link #label} draws it. */
        double labelWidth(String text) {
            if (text == null || text.isEmpty()) return 0;
            return new TextLayout(text, labelFont(), g.getFontRenderContext()).getAdvance();
        }

        /**
         * Text with a white halo so it stays legible over nodes and edges. The text box is placed so that the point
         * (ax, ay) of it (fractions of its width and height, (0, 0) = top left) lies at (x, y).
         */
        void label(String text, double x, double y, double ax, double ay) {
            if (text == null || text.isEmpty()) return;
            TextLayout layout = new TextLayout(text, labelFont(), g.getFontRenderContext());
            double w = layout.getAdvance();
            double h = layout.getAscent() + layout.getDescent();
            double bx = x - ax * w;
            double by = y - ay * h + layout.getAscent();
            Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(bx, by));
            g.setColor(HALO);
            g.setStroke(HALO_STROKE);
            g.draw(outline);
            g.setColor(color);
            g.fill(outline);
        }
    }

    private static JComponent activeViewComponent() {
        View v = activeView();
        return (v != null) ? v.getViewComponent() : null;
    }

    private static View activeView() {
        try {
            return GravistoService.getInstance().getMainFrame().getActiveEditorSession().getActiveView();
        } catch (Throwable t) {
            return null;
        }
    }
}
