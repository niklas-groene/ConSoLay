package gui.template;

import constraints.ConstraintStore;
import gui.components.Ui;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateInstance;
import layout.template.search.Match;
import layout.template.search.MatchCheck;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugin.view.View;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Click-through layer above the graph view that draws the matches of the newest search and the applied template
 * instances, each state with its own colour, outline and symbol (suggestion dashed blue, selected solid blue, applied
 * green, switched off dotted grey, conflict or invalid red, outdated amber). Only matches of the graph shown in the
 * active view are drawn.
 *
 * @author Niklas Gröne
 */
public final class MatchOverlay extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final Map<JFrame, MatchOverlay> INSTANCES = new IdentityHashMap<>();
    static final Integer LAYER = JLayeredPane.PALETTE_LAYER + 1;

    static final Color SUGGEST = Ui.ACCENT;
    static final Color APPLIED = Ui.SUCCESS;
    static final Color OFF = new Color(0x80, 0x86, 0x8B);
    static final Color CONFLICT = Ui.ERROR;
    static final Color STALE = Ui.WARNING;

    private static final Stroke DASHED = new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{6, 4}, 0);
    private static final Stroke DOTTED = new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{1.5f, 4}, 0);
    private static final Stroke SOLID = new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke THICK = new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke THIN = new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{3, 3}, 0);
    /** Space around the node boxes, in screen pixels. */
    private static final double PAD = 9;

    private final JFrame frame;

    private MatchOverlay(JFrame frame) {
        this.frame = frame;
        setOpaque(false);
        setFocusable(false);
    }

    /** The overlay of the active view's frame (made on demand). */
    static MatchOverlay getOrInstall() {
        View v = TemplateWorkbench.activeView();
        JComponent comp = v == null ? null : v.getViewComponent();
        Window w = comp == null ? null : SwingUtilities.getWindowAncestor(comp);
        if (!(w instanceof JFrame)) return null;
        JFrame frame = (JFrame) w;
        MatchOverlay o = INSTANCES.get(frame);
        if (o != null) return o;
        MatchOverlay overlay = new MatchOverlay(frame);
        JLayeredPane layered = frame.getLayeredPane();
        overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        layered.add(overlay, LAYER);
        ComponentAdapter resize = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
                overlay.repaint();
            }
        };
        layered.addComponentListener(resize);
        // a closed frame (e.g. a detached view) gives its overlay up, so neither keeps the other alive
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                frame.removeWindowListener(this);
                layered.removeComponentListener(resize);
                layered.remove(overlay);
                INSTANCES.remove(frame);
            }
        });
        INSTANCES.put(frame, overlay);
        return overlay;
    }

    public static void repaintAll() {
        getOrInstall();
        for (MatchOverlay o : INSTANCES.values()) o.repaint();
    }

    /** Maps graph coordinates of a view to the coordinates of a target component. */
    static final class Geo {
        final AffineTransform zoom;
        final Point offset;

        Geo(View view, JComponent comp, Component target) {
            AffineTransform z = view.getZoom();
            this.zoom = z == null ? new AffineTransform() : z;
            this.offset = SwingUtilities.convertPoint(comp, 0, 0, target);
        }

        Point2D p(double x, double y) {
            Point2D d = zoom.transform(new Point2D.Double(x, y), null);
            return new Point2D.Double(d.getX() + offset.x, d.getY() + offset.y);
        }

        Rectangle2D box(Node n) {
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            double w = AttributeHelper.getWidth(n), h = AttributeHelper.getHeight(n);
            Point2D a = p(x - w / 2, y - h / 2), b = p(x + w / 2, y + h / 2);
            return new Rectangle2D.Double(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.abs(b.getX() - a.getX()), Math.abs(b.getY() - a.getY()));
        }

        Rectangle2D bounds(Collection<Node> ns, double pad) {
            Rectangle2D r = null;
            for (Node n : ns) {
                Rectangle2D b = box(n);
                if (r == null) r = b;
                else r = r.createUnion(b);
            }
            if (r == null) return null;
            return new Rectangle2D.Double(r.getX() - pad, r.getY() - pad, r.getWidth() + 2 * pad, r.getHeight() + 2 * pad);
        }
    }

    /** Geometry for the active view in the coordinates of {@code target}, or null. */
    static Geo geo(Component target) {
        View v = TemplateWorkbench.activeView();
        if (v == null || v.getViewComponent() == null || !v.getViewComponent().isShowing()) return null;
        return new Geo(v, v.getViewComponent(), target);
    }

    /** The screen bounds (in {@code target}'s coordinates) of a match, or null. */
    static Rectangle2D bounds(Match m, Component target) {
        Geo g = geo(target);
        return g == null ? null : g.bounds(m.nodes(), PAD);
    }

    /**
     * The drawn matches under a point of the view component: those with a node box under it first (smallest hull
     * first), then those whose hull contains it.
     */
    static List<Match> hits(Point viewPoint, JComponent viewComp) {
        TemplateWorkbench wb = TemplateWorkbench.get();
        Graph g = TemplateWorkbench.activeGraph();
        List<Match> direct = new ArrayList<>(), inside = new ArrayList<>();
        Geo geo = geo(viewComp);
        if (geo == null || g == null) return direct;
        Point p = new Point(viewPoint.x + geo.offset.x, viewPoint.y + geo.offset.y);
        for (Match m : wb.shown(g)) {
            if (m.run.snapshot.graph != g) continue;
            boolean onNode = false;
            for (Node n : m.nodes()) {
                if (n.getGraph() != g) continue;
                Rectangle2D b = geo.box(n);
                if (new Rectangle2D.Double(b.getX() - 4, b.getY() - 4, b.getWidth() + 8, b.getHeight() + 8).contains(p)) onNode = true;
            }
            if (onNode) direct.add(m);
            else {
                Rectangle2D r = geo.bounds(m.nodes(), PAD);
                if (r != null && r.contains(p)) inside.add(m);
            }
        }
        java.util.Comparator<Match> byArea = java.util.Comparator.comparingDouble(m -> {
            Rectangle2D r = geo.bounds(m.nodes(), 0);
            return r == null ? 0 : r.getWidth() * r.getHeight();
        });
        direct.sort(byArea);
        inside.sort(byArea);
        return direct.isEmpty() ? inside : direct;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        TemplateWorkbench wb = TemplateWorkbench.get();
        View view = TemplateWorkbench.activeView();
        if (view == null || view.getViewComponent() == null) return;
        JComponent comp = view.getViewComponent();
        if (!comp.isShowing() || SwingUtilities.getWindowAncestor(comp) != frame) return;
        Graph graph = view.getGraph();
        if (graph == null) return;
        boolean mode = wb.isSuggestionMode();
        if (!mode && wb.focusInstance == null) return;
        Geo geo = new Geo(view, comp, this);
        Graphics2D g = Ui.smooth(g0);
        try {
            Rectangle vis = SwingUtilities.convertRectangle(comp, comp.getVisibleRect(), this);
            g.setClip(vis);
            if (mode) {
                for (TemplateInstance i : InstanceRegistry.of(graph)) paintInstance(g, geo, i, false);
                List<Match> shown = wb.shown(graph);
                for (Match m : shown) if (m.state != Match.State.APPLIED && m != wb.hover && m != wb.focus && !m.selected) paintMatch(g, geo, m, false);
                for (Match m : shown) if (m.selected && m != wb.hover && m != wb.focus) paintMatch(g, geo, m, false);
                if (wb.focus != null && wb.focus.run.snapshot.graph == graph) paintMatch(g, geo, wb.focus, true);
                if (wb.hover != null && wb.hover != wb.focus && wb.hover.run.snapshot.graph == graph) paintMatch(g, geo, wb.hover, true);
            }
            if (wb.focusInstance != null && wb.focusInstance.graph == graph) paintInstance(g, geo, wb.focusInstance, true);
        } catch (RuntimeException ignored) {
            // stale nodes: skip this paint
        } finally {
            g.dispose();
        }
    }

    /** Colour of a match's state. */
    static Color colorOf(Match m) {
        if (m.state == Match.State.INVALID) return CONFLICT;
        if (m.state == Match.State.STALE) return STALE;
        if (m.state == Match.State.APPLIED) return APPLIED;
        if (m.check.status == MatchCheck.Status.CONFLICT || m.check.status == MatchCheck.Status.BLOCKED) return CONFLICT;
        return SUGGEST;
    }

    static String symbolOf(Match m) {
        if (m.state == Match.State.INVALID) return "✕";
        if (m.state == Match.State.STALE) return "?";
        if (m.state == Match.State.APPLIED) return "●";
        if (m.check.status == MatchCheck.Status.CONFLICT || m.check.status == MatchCheck.Status.BLOCKED) return "!";
        return m.selected ? "✓" : "○";
    }

    /** Short state text, e.g. "Selected", "Conflict", "Outdated". */
    static String stateText(Match m) {
        if (m.state == Match.State.INVALID) return "No longer fits";
        if (m.state == Match.State.STALE) return "Outdated";
        if (m.state == Match.State.APPLIED) return "Applied";
        if (m.check.status == MatchCheck.Status.CONFLICT) return "Conflict";
        if (m.check.status == MatchCheck.Status.BLOCKED) return "Cannot apply";
        return m.selected ? "Selected" : "Suggestion";
    }

    private void paintMatch(Graphics2D g, Geo geo, Match m, boolean emphasised) {
        Rectangle2D r = geo.bounds(m.nodes(), PAD);
        if (r == null) return;
        Color c = colorOf(m);
        RoundRectangle2D hull = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        if (m.selected || emphasised) {
            g.setColor(alpha(c, emphasised ? 34 : 22));
            g.fill(hull);
        }
        if (emphasised) {
            g.setColor(alpha(c, 150));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Edge e : m.edges()) {
                Point2D a = geo.p(AttributeHelper.getPositionX(e.getSource()), AttributeHelper.getPositionY(e.getSource()));
                Point2D b = geo.p(AttributeHelper.getPositionX(e.getTarget()), AttributeHelper.getPositionY(e.getTarget()));
                g.draw(new Line2D.Double(a, b));
            }
            g.setStroke(new BasicStroke(2.4f));
            for (Node n : m.nodes()) {
                Rectangle2D b = geo.box(n);
                g.setColor(alpha(c, 50));
                g.fill(b);
                g.setColor(c);
                g.draw(b);
            }
            // component outlines of a composite
            Map<String, List<Node>> parts = m.nodesByPart();
            if (parts.size() > 1 || !parts.containsKey("")) {
                for (Map.Entry<String, List<Node>> e : parts.entrySet()) {
                    Rectangle2D pr = geo.bounds(e.getValue(), 4);
                    if (pr == null) continue;
                    g.setColor(alpha(c, 200));
                    g.setStroke(THIN);
                    g.draw(new RoundRectangle2D.Double(pr.getX(), pr.getY(), pr.getWidth(), pr.getHeight(), 8, 8));
                    List<String> names = m.task.plan.partNames(e.getKey());
                    badge(g, names.isEmpty() ? "" : names.get(names.size() - 1), pr.getX() + 2, pr.getMaxY() + 1, c, false);
                }
            }
        }
        g.setColor(c);
        g.setStroke(emphasised ? THICK : m.selected || m.state == Match.State.APPLIED ? SOLID : DASHED);
        g.draw(hull);
        String text = symbolOf(m) + " #" + m.number + (emphasised ? " " + m.task.title() + " · " + stateText(m) : "");
        badge(g, text, r.getX() + 6, r.getY() - 2, c, m.selected || emphasised);
    }

    private void paintInstance(Graphics2D g, Geo geo, TemplateInstance i, boolean emphasised) {
        List<Node> ns = new ArrayList<>();
        for (Node n : i.nodes.values()) if (n.getGraph() == i.graph) ns.add(n);
        Rectangle2D r = geo.bounds(ns, PAD + 3);
        if (r == null) return;
        TemplateInstance.Status s = i.status(ConstraintStore.constraints);
        Color c = !i.isOn() ? OFF : s.notApplied > 0 ? CONFLICT : APPLIED;
        RoundRectangle2D hull = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 16, 16);
        if (emphasised) {
            g.setColor(alpha(c, 28));
            g.fill(hull);
        }
        g.setColor(c);
        if (i.isOn()) {
            g.setStroke(emphasised ? THICK : SOLID);
            g.draw(hull);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(r.getX() + 3, r.getY() + 3, r.getWidth() - 6, r.getHeight() - 6, 12, 12));
        } else {
            g.setStroke(DOTTED);
            g.draw(hull);
        }
        String text = (i.isOn() ? (s.notApplied > 0 ? "! " : s.partly() ? "◐ " : "● ") : "⏻ Off · ") + i.label();
        badge(g, text, r.getMaxX() - 6, r.getMaxY() + 2, c, emphasised, true);
    }

    private static void badge(Graphics2D g, String text, double x, double y, Color c, boolean filled) {
        badge(g, text, x, y, c, filled, false);
    }

    /** A pill with {@code text}; its bottom-left at (x, y), or its top-right with {@code rightAligned}. */
    private static void badge(Graphics2D g, String text, double x, double y, Color c, boolean filled, boolean rightAligned) {
        if (text == null || text.isEmpty()) return;
        g.setFont(Ui.small().deriveFont(Font.BOLD));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 10, h = fm.getHeight() + 2;
        double bx = rightAligned ? x - w : x, by = rightAligned ? y : y - h;
        RoundRectangle2D pill = new RoundRectangle2D.Double(bx, by, w, h, h, h);
        g.setColor(filled ? c : Color.WHITE);
        g.fill(pill);
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(pill);
        g.setColor(filled ? Color.WHITE : c);
        g.drawString(text, (float) bx + 5, (float) (by + (h - fm.getHeight()) / 2.0 + fm.getAscent() + 1));
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
