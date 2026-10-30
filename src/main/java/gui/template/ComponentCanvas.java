package gui.template;

import gui.components.Ui;
import layout.template.model.ComponentUse;
import layout.template.model.LayoutRelation;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.Templates;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Canvas of a composite template: every component is a box with its role, the template it uses and a preview; its ports
 * are dots on the box sides. Structure links (search conditions) are solid lines between ports, made by dragging from
 * port to port (dropping anywhere on a box takes its nearest port); layout relations (applied as constraints) are dashed
 * violet lines between the components. Dragging the background or the mouse wheel moves and zooms the view only.
 *
 * @author Niklas Gröne
 */
final class ComponentCanvas extends JComponent {

    private static final long serialVersionUID = 1L;
    static final double BOX_W = 210, BOX_H = 130;
    /** Reach of a port dot, screen px. */
    private static final double PORT_REACH = 11;
    static final Color STRUCTURE = new Color(0x33, 0x41, 0x55);
    static final Color LAYOUT = new Color(0x7E, 0x22, 0xCE);

    private final EditorModel model;
    private final boolean readOnly;
    private double scale = 1, tx = 20, ty = 20;
    private Point press, last;
    private String dragComp, fromPort, fromComp;
    private Point linkTo;
    private boolean panning, moved;
    private double[] dragStart;
    /** The port a link being dragged would end at now: component id, port id. */
    private String[] linkTarget;
    private boolean fitted, viewChosen;
    private int knownComponents;
    /** Called with the component that was double-clicked. */
    Consumer<ComponentUse> onOpen = c -> { };

    ComponentCanvas(EditorModel model, boolean readOnly) {
        this.model = model;
        this.readOnly = readOnly;
        setFocusable(true);
        setOpaque(true);
        setBackground(new Color(0xFA, 0xFA, 0xFB));
        getAccessibleContext().setAccessibleName("Components of the composite template");
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        setToolTipText("");
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { if (!viewChosen) fit(); }
        });
        knownComponents = model.def().components.size();
        model.addListener(() -> {
            // a new component may lie outside the view: show all of them
            int n = model.def().components.size();
            if (n > knownComponents) fit();
            knownComponents = n;
        });
        bindKeys();
    }

    private void bindKeys() {
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete");
        am.put("delete", PatternCanvas.action(() -> { if (!readOnly) CompositeProperties.deleteSelected(model); }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
        am.put("escape", PatternCanvas.action(() -> {
            fromPort = null;
            fromComp = null;
            linkTo = null;
            linkTarget = null;
            select(null, null, null);
            repaint();
        }));
        for (int[] k : new int[][]{{KeyEvent.VK_LEFT, -1, 0}, {KeyEvent.VK_RIGHT, 1, 0}, {KeyEvent.VK_UP, 0, -1}, {KeyEvent.VK_DOWN, 0, 1}}) {
            im.put(KeyStroke.getKeyStroke(k[0], 0), "nudge" + k[0]);
            im.put(KeyStroke.getKeyStroke(k[0], KeyEvent.SHIFT_DOWN_MASK), "nudge10" + k[0]);
            am.put("nudge" + k[0], PatternCanvas.action(() -> nudge(k[1], k[2])));
            am.put("nudge10" + k[0], PatternCanvas.action(() -> nudge(10 * k[1], 10 * k[2])));
        }
    }

    private void nudge(int dx, int dy) {
        ComponentUse c = model.selComponent == null ? null : model.def().component(model.selComponent);
        if (readOnly || c == null) return;
        model.changeMerging("nudge", "Move component", () -> {
            c.x += dx;
            c.y += dy;
        });
    }

    /** Fits all components with their relations into the canvas (changes only the view). */
    void fit() {
        if (getWidth() < 20) return;
        fitted = true;
        viewChosen = false;
        double[] b = extent();
        if (b == null) return;
        AffineTransform t = PatternPainter.fit(b, new Rectangle2D.Double(0, 0, getWidth(), getHeight()), 36, 1.2);
        scale = t.getScaleX();
        tx = t.getTranslateX();
        ty = t.getTranslateY();
        repaint();
    }

    void zoom(double factor) {
        viewChosen = true;
        Point2D c = local(new Point(getWidth() / 2, getHeight() / 2));
        scale = Math.max(0.2, Math.min(4, scale * factor));
        tx = getWidth() / 2.0 - c.getX() * scale;
        ty = getHeight() / 2.0 - c.getY() * scale;
        repaint();
    }

    private double[] extent() {
        if (model.def().components.isEmpty()) return null;
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (ComponentUse c : model.def().components) {
            b[0] = Math.min(b[0], c.x);
            b[1] = Math.min(b[1], c.y);
            b[2] = Math.max(b[2], c.x + BOX_W);
            b[3] = Math.max(b[3], c.y + BOX_H);
        }
        for (LayoutRelation r : model.def().relations) {
            for (Point2D p : relationPoints(r)) b[3] = Math.max(b[3], p.getY() + 24);
        }
        return b;
    }

    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;
                @Override public javax.accessibility.AccessibleRole getAccessibleRole() { return javax.accessibility.AccessibleRole.PANEL; }
            };
        }
        return accessibleContext;
    }

    AffineTransform transform() {
        AffineTransform t = new AffineTransform();
        t.translate(tx, ty);
        t.scale(scale, scale);
        return t;
    }

    private Point2D local(Point p) {
        try {
            return transform().inverseTransform(new Point2D.Double(p.x, p.y), null);
        } catch (NoninvertibleTransformException e) {
            return new Point2D.Double(p.x, p.y);
        }
    }

    static Rectangle2D box(ComponentUse c) {
        return new Rectangle2D.Double(c.x, c.y, BOX_W, BOX_H);
    }

    /** Local position of a port dot: the first half of the ports on the box's left side, the rest on its right. */
    Point2D portPoint(ComponentUse c, String portId) {
        TemplateDef inner = Templates.resolve(c, model.source());
        Rectangle2D b = box(c);
        if (inner == null) return new Point2D.Double(b.getCenterX(), b.getMaxY());
        List<Port> ports = inner.ports;
        int i = 0;
        for (int k = 0; k < ports.size(); k++) if (ports.get(k).id.equals(portId)) i = k;
        int left = (ports.size() + 1) / 2, right = ports.size() - left;
        boolean onLeft = i < left;
        int idx = onLeft ? i : i - left, count = onLeft ? left : right;
        double y = b.getY() + 34 + (b.getHeight() - 44) * (idx + 0.5) / Math.max(1, count);
        return new Point2D.Double(onLeft ? b.getX() : b.getMaxX(), y);
    }

    private boolean onLeft(ComponentUse c, String portId) {
        return portPoint(c, portId).getX() <= box(c).getX() + 1;
    }

    private String[] portAt(Point p) {
        Point2D l = local(p);
        String[] best = null;
        double reach = PORT_REACH;
        for (ComponentUse c : model.def().components) {
            TemplateDef inner = Templates.resolve(c, model.source());
            if (inner == null) continue;
            for (Port port : inner.ports) {
                double d = portPoint(c, port.id).distance(l) * scale;
                if (d < reach) {
                    reach = d;
                    best = new String[]{c.id, port.id};
                }
            }
        }
        return best;
    }

    /** Where a link dropped at {@code p} ends: the port there, or the nearest port of the box there; never on {@code fromComp}. */
    private String[] dropTarget(Point p) {
        String[] at = portAt(p);
        if (at != null && !at[0].equals(fromComp)) return at;
        ComponentUse c = compAt(p);
        if (c == null || c.id.equals(fromComp)) return null;
        TemplateDef inner = Templates.resolve(c, model.source());
        if (inner == null) return null;
        Point2D l = local(p);
        String[] best = null;
        double near = Double.POSITIVE_INFINITY;
        for (Port port : inner.ports) {
            double d = portPoint(c, port.id).distance(l);
            if (d < near) {
                near = d;
                best = new String[]{c.id, port.id};
            }
        }
        return best;
    }

    private ComponentUse compAt(Point p) {
        Point2D l = local(p);
        List<ComponentUse> cs = new ArrayList<>(model.def().components);
        for (int i = cs.size() - 1; i >= 0; i--) if (box(cs.get(i)).contains(l)) return cs.get(i);
        return null;
    }

    /** The curve of a link as drawn, or null if a component is gone. */
    private Path2D linkCurve(StructureLink s) {
        ComponentUse a = model.def().component(s.fromComponent), b = model.def().component(s.toComponent);
        if (a == null || b == null) return null;
        return curve(portPoint(a, s.fromPort), onLeft(a, s.fromPort), portPoint(b, s.toPort), onLeft(b, s.toPort));
    }

    private String linkAt(Point p) {
        Point2D l = local(p);
        for (StructureLink s : model.def().links) {
            Path2D path = linkCurve(s);
            if (path == null) continue;
            double[] c = new double[6];
            double px = 0, py = 0;
            for (PathIterator it = new FlatteningPathIterator(path.getPathIterator(null), 1); !it.isDone(); it.next()) {
                boolean line = it.currentSegment(c) == PathIterator.SEG_LINETO;
                if (line && Line2D.ptSegDist(px, py, c[0], c[1], l.getX(), l.getY()) * scale < 6) return s.id;
                px = c[0];
                py = c[1];
            }
            if (labelBounds(linkText(s), middle(path), true).contains(l)) return s.id;
        }
        return null;
    }

    private String relationAt(Point p) {
        Point2D l = local(p);
        for (LayoutRelation r : model.def().relations) {
            List<Point2D> pts = relationPoints(r);
            for (int i = 0; i + 1 < pts.size(); i++) if (new Line2D.Double(pts.get(i), pts.get(i + 1)).ptSegDist(l) * scale < 6) return r.id;
            if (pts.size() >= 2 && labelBounds(relationText(r), relationLabelAt(pts), false).contains(l)) return r.id;
        }
        return null;
    }

    private Rectangle2D labelBounds(String text, Point2D at, boolean bold) {
        FontMetrics fm = getFontMetrics(bold ? Ui.small().deriveFont(Font.BOLD) : Ui.small());
        int w = fm.stringWidth(text) + 8, h = fm.getHeight();
        return new Rectangle2D.Double(at.getX() - w / 2.0, at.getY() - h / 2.0, w, h);
    }

    private List<Point2D> relationPoints(LayoutRelation r) {
        List<Point2D> pts = new ArrayList<>();
        if (r.type.onComponents()) {
            // one lane per relation below the boxes, so the dashed lines never cross a component
            int lane = Math.max(0, model.def().relations.indexOf(r));
            double bottom = 0;
            for (String id : r.components) {
                ComponentUse c = model.def().component(id);
                if (c != null) bottom = Math.max(bottom, box(c).getMaxY());
            }
            for (String id : r.components) {
                ComponentUse c = model.def().component(id);
                if (c != null) pts.add(new Point2D.Double(box(c).getCenterX(), bottom + 16 + 22 * lane));
            }
        } else {
            for (LayoutRelation.PortRef pr : r.ports) {
                ComponentUse c = model.def().component(pr.component);
                if (c != null) pts.add(portPoint(c, pr.port));
            }
        }
        return pts;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (!fitted && getWidth() > 20) fit();
        Graphics2D g = Ui.smooth(g0);
        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            Graphics2D gl = (Graphics2D) g.create();
            gl.transform(transform());
            TemplateDef t = model.def();
            for (ComponentUse c : t.components) paintComponentBox(gl, c);
            for (LayoutRelation r : t.relations) paintRelation(gl, r);
            for (StructureLink s : t.links) paintLink(gl, s);
            if (fromPort != null && linkTo != null) {
                ComponentUse a = t.component(fromComp);
                if (a != null) {
                    gl.setColor(STRUCTURE);
                    gl.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{5, 4}, 0));
                    ComponentUse to = linkTarget == null ? null : t.component(linkTarget[0]);
                    gl.draw(new Line2D.Double(portPoint(a, fromPort), to != null ? portPoint(to, linkTarget[1]) : local(linkTo)));
                }
            }
            gl.dispose();
            paintLegend(g);
            g.setFont(Ui.small());
            g.setColor(Ui.MUTED);
            g.drawString(readOnly ? "Double-click: look inside · Scroll: zoom · Drag: pan"
                    : "Drag a box: move · Drag from a port: connect · Double-click: look inside · Scroll: zoom", 8, getHeight() - 8);
            if (t.components.isEmpty()) {
                g.setColor(Ui.MUTED);
                g.setFont(Ui.font());
                String s = readOnly ? "No components." : "Insert building blocks from the library on the left.";
                g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
            }
            if (isFocusOwner()) {
                g.setColor(Ui.FOCUS);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintComponentBox(Graphics2D g, ComponentUse c) {
        Rectangle2D b = box(c);
        boolean sel = c.id.equals(model.selComponent);
        TemplateDef inner = Templates.resolve(c, model.source());
        boolean problem = false;
        for (layout.template.model.TemplateValidator.Problem p : model.problems()) {
            if (c.id.equals(p.elementId) && p.severity != layout.template.model.TemplateValidator.Severity.WARNING) problem = true;
        }
        RoundRectangle2D rr = new RoundRectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight(), 12, 12);
        g.setColor(new Color(0, 0, 0, 25));
        g.fill(new RoundRectangle2D.Double(b.getX() + 2, b.getY() + 3, b.getWidth(), b.getHeight(), 12, 12));
        g.setColor(Color.WHITE);
        g.fill(rr);
        g.setColor(problem ? Ui.ERROR : sel ? Ui.ACCENT : Ui.BORDER);
        g.setStroke(new BasicStroke(sel ? 2.4f : 1.2f));
        g.draw(rr);
        g.setFont(Ui.bold());
        g.setColor(Ui.TEXT);
        g.drawString(PatternPainter.clip(c.name, g.getFontMetrics(), b.getWidth() - 12), (float) b.getX() + 8, (float) b.getY() + 16);
        g.setFont(Ui.small());
        g.setColor(problem ? Ui.ERROR : Ui.MUTED);
        String sub = inner == null ? "missing: " + (c.ref == null ? "?" : c.ref) : inner.name + " · rev. " + inner.revision
                + (inner.isComposite() ? " · composite" : "");
        if (model.library != null && c.ref != null) {
            TemplateDef newest = model.library.latest(c.ref.id);
            if (newest != null && newest.revision > c.ref.revision) sub += " · update";
        }
        g.drawString(PatternPainter.clip(sub, g.getFontMetrics(), b.getWidth() - 12), (float) b.getX() + 8, (float) b.getY() + 29);
        if (inner != null) {
            // the port names lie inside the box beside the dots, the preview between them
            Font portFont = Ui.small().deriveFont(Font.BOLD);
            g.setFont(portFont);
            FontMetrics fm = g.getFontMetrics();
            double maxLabel = 64, leftW = 0, rightW = 0;
            for (Port p : inner.ports) {
                double w = Math.min(maxLabel, fm.stringWidth(p.name));
                if (onLeft(c, p.id)) leftW = Math.max(leftW, w);
                else rightW = Math.max(rightW, w);
            }
            double px = b.getX() + (leftW > 0 ? leftW + 12 : 8), pw = b.getMaxX() - (rightW > 0 ? rightW + 12 : 8) - px;
            PatternPainter.paintPreview(g, inner, model.source(), new Rectangle2D.Double(px, b.getY() + 34, pw, b.getHeight() - 42));
            g.setFont(portFont);
            for (Port p : inner.ports) {
                Point2D pt = portPoint(c, p.id);
                boolean left = onLeft(c, p.id);
                boolean target = linkTarget != null && c.id.equals(linkTarget[0]) && p.id.equals(linkTarget[1]);
                boolean open = fromPort != null && !c.id.equals(fromComp);
                double r = target ? 7 : 5;
                if (open) {
                    g.setColor(MatchOverlay.alpha(Ui.ACCENT, target ? 90 : 40));
                    g.fill(new java.awt.geom.Ellipse2D.Double(pt.getX() - 10, pt.getY() - 10, 20, 20));
                }
                g.setColor(target ? Ui.ACCENT : PatternPainter.PORT);
                g.fill(new java.awt.geom.Ellipse2D.Double(pt.getX() - r, pt.getY() - r, 2 * r, 2 * r));
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(pt.getX() - 2, pt.getY() - 2, 4, 4));
                String name = PatternPainter.clip(p.name, fm, maxLabel);
                float x = left ? (float) pt.getX() + 8 : (float) pt.getX() - fm.stringWidth(name) - 8;
                g.setColor(new Color(255, 255, 255, 220));
                g.fillRect((int) x - 2, (int) pt.getY() - fm.getAscent() + 3, fm.stringWidth(name) + 4, fm.getHeight() - 2);
                g.setColor(target ? Ui.ACCENT : PatternPainter.PORT);
                g.drawString(name, x, (float) pt.getY() + 4);
            }
        }
    }

    private void paintLink(Graphics2D g, StructureLink s) {
        ComponentUse a = model.def().component(s.fromComponent), b = model.def().component(s.toComponent);
        if (a == null || b == null) return;
        Point2D p = portPoint(a, s.fromPort), q = portPoint(b, s.toPort);
        boolean pLeft = onLeft(a, s.fromPort), qLeft = onLeft(b, s.toPort);
        boolean sel = s.id.equals(model.selLink);
        Color c = hasProblem(s.id) ? Ui.ERROR : sel ? Ui.ACCENT : STRUCTURE;
        g.setColor(c);
        g.setStroke(new BasicStroke(sel ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = curve(p, pLeft, q, qLeft);
        g.draw(path);
        if (s.type == StructureLink.Type.SAME_NODE) {
            g.setStroke(new BasicStroke(1f));
            g.draw(curve(new Point2D.Double(p.getX(), p.getY() + 4), pLeft, new Point2D.Double(q.getX(), q.getY() + 4), qLeft));
        } else if (s.direction == StructureLink.Direction.FORWARD) {
            Point2D before = new Point2D.Double(q.getX() + (qLeft ? -14 : 14), q.getY());
            PatternPainter.arrowHead(g, before, q, 2f);
        }
        Point2D m = middle(path);
        label(g, linkText(s), m.getX(), m.getY(), c, true);
    }

    private static String linkText(StructureLink s) {
        String role = "";
        for (layout.template.model.Condition k : s.edgeConditions) {
            if (k.op == layout.template.model.Condition.Op.EQUALS) role = PatternPainter.prettyRole(k.values.get(0));
            else if (k.op == layout.template.model.Condition.Op.IN_LIST) role = String.join("/", k.values);
        }
        return s.type == StructureLink.Type.SAME_NODE ? "same node" : s.type == StructureLink.Type.PATH ? "path (not supported)"
                : "edge" + (role.isEmpty() ? "" : " · " + role) + (s.direction == StructureLink.Direction.ANY ? " · either way" : "");
    }

    private boolean hasProblem(String elementId) {
        for (layout.template.model.TemplateValidator.Problem p : model.problems()) if (elementId.equals(p.elementId)) return true;
        return false;
    }

    /** A curve leaving each port away from its box. */
    private static Path2D curve(Point2D p, boolean pLeft, Point2D q, boolean qLeft) {
        Path2D path = new Path2D.Double();
        path.moveTo(p.getX(), p.getY());
        double dx = Math.max(40, Math.abs(q.getX() - p.getX()) / 2);
        path.curveTo(p.getX() + (pLeft ? -dx : dx), p.getY(), q.getX() + (qLeft ? -dx : dx), q.getY(), q.getX(), q.getY());
        return path;
    }

    /** The middle of a curve made by {@link #curve}. */
    private static Point2D middle(Path2D curve) {
        double[][] c = new double[2][6];
        PathIterator it = curve.getPathIterator(null);
        it.currentSegment(c[0]);
        it.next();
        it.currentSegment(c[1]);
        return new Point2D.Double((c[0][0] + 3 * c[1][0] + 3 * c[1][2] + c[1][4]) / 8, (c[0][1] + 3 * c[1][1] + 3 * c[1][3] + c[1][5]) / 8);
    }

    private String relationText(LayoutRelation r) {
        return "Layout: " + r.describe(id -> {
            ComponentUse u = model.def().component(id);
            return u == null ? "?" : u.name;
        }) + (r.enabled ? "" : " (off)");
    }

    private static Point2D relationLabelAt(List<Point2D> pts) {
        Point2D m = pts.get(0), n = pts.get(1);
        return new Point2D.Double((m.getX() + n.getX()) / 2, (m.getY() + n.getY()) / 2 + 12);
    }

    private void paintRelation(Graphics2D g, LayoutRelation r) {
        List<Point2D> pts = relationPoints(r);
        if (pts.size() < 2) return;
        boolean sel = r.id.equals(model.selRelation);
        Color c = hasProblem(r.id) ? Ui.ERROR : !r.enabled ? Ui.DISABLED : sel ? Ui.ACCENT : LAYOUT;
        g.setColor(c);
        g.setStroke(new BasicStroke(sel ? 2.8f : 1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{7, 5}, 0));
        for (int i = 0; i + 1 < pts.size(); i++) {
            g.draw(new Line2D.Double(pts.get(i), pts.get(i + 1)));
            if (r.type != LayoutRelation.Type.ALIGN_PORTS_H && r.type != LayoutRelation.Type.ALIGN_PORTS_V) {
                Point2D a = pts.get(i), b = pts.get(i + 1);
                Point2D tip = new Point2D.Double(a.getX() + (b.getX() - a.getX()) * 0.62, a.getY() + (b.getY() - a.getY()) * 0.62);
                PatternPainter.arrowHead(g, a, tip, 1.5f);
            }
        }
        if (r.type.onComponents()) {
            g.setStroke(new BasicStroke(1f));
            for (String id : r.components) {
                ComponentUse u = model.def().component(id);
                if (u == null) continue;
                g.draw(new Line2D.Double(box(u).getCenterX(), box(u).getMaxY(), box(u).getCenterX(), pts.get(0).getY()));
            }
        }
        Point2D at = relationLabelAt(pts);
        label(g, relationText(r), at.getX(), at.getY(), c, false);
    }

    private static void label(Graphics2D g, String text, double x, double y, Color c, boolean bold) {
        g.setFont(bold ? Ui.small().deriveFont(Font.BOLD) : Ui.small());
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 8, h = fm.getHeight();
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect((int) (x - w / 2.0), (int) (y - h / 2.0), w, h, 8, 8);
        g.setColor(c);
        g.drawRoundRect((int) (x - w / 2.0), (int) (y - h / 2.0), w, h, 8, 8);
        g.drawString(text, (float) (x - w / 2.0 + 4), (float) (y - h / 2.0 + fm.getAscent()));
    }

    private void paintLegend(Graphics2D g) {
        g.setFont(Ui.small());
        int w = 60 + Math.max(g.getFontMetrics().stringWidth("Structure: must be connected (search)"),
                g.getFontMetrics().stringWidth("Layout: how they should lie (applied)"));
        int x = getWidth() - w - 10, y = 10;
        if (x < 10) return;
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(x, y, w, 52, 10, 10);
        g.setColor(Ui.BORDER);
        g.drawRoundRect(x, y, w, 52, 10, 10);
        g.setStroke(new BasicStroke(2f));
        g.setColor(STRUCTURE);
        g.drawLine(x + 10, y + 17, x + 40, y + 17);
        PatternPainter.arrowHead(g, new Point(x + 30, y + 17), new Point(x + 42, y + 17), 1.5f);
        g.drawString("Structure: must be connected (search)", x + 50, y + 21);
        g.setColor(LAYOUT);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{7, 5}, 0));
        g.drawLine(x + 10, y + 38, x + 40, y + 38);
        g.drawString("Layout: how they should lie (applied)", x + 50, y + 42);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        String[] port = portAt(e.getPoint());
        if (port != null) return Ui.tooltip("Port <b>" + Ui.esc(model.portName(port[0], port[1])) + "</b>" + (readOnly ? "" : "<br>Drag to another component to connect them (structure)."));
        ComponentUse c = compAt(e.getPoint());
        if (c != null) return Ui.tooltip("<b>" + Ui.esc(c.name) + "</b>: " + Ui.esc(c.ref == null ? "?" : c.ref.toString()) + "<br>Double-click to look inside.");
        return null;
    }

    private final class Mouse extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            press = e.getPoint();
            last = press;
            moved = false;
            panning = SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) || e.isAltDown();
            if (panning) {
                viewChosen = true;
                return;
            }
            String[] port = readOnly ? null : portAt(press);
            if (port != null) {
                fromComp = port[0];
                fromPort = port[1];
                return;
            }
            ComponentUse c = compAt(press);
            if (c != null) {
                select(c.id, null, null);
                if (e.getClickCount() == 2) {
                    onOpen.accept(c);
                    press = null;
                    return;
                }
                if (!readOnly) {
                    dragComp = c.id;
                    dragStart = new double[]{c.x, c.y};
                }
                return;
            }
            String l = linkAt(press);
            if (l != null) {
                select(null, l, null);
                return;
            }
            String r = relationAt(press);
            select(null, null, r);
            if (r == null) panning = true;   // the background moves the view
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (press == null) return;
            Point p = e.getPoint();
            if (panning) {
                viewChosen = true;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                tx += p.x - last.x;
                ty += p.y - last.y;
                last = p;
                repaint();
                return;
            }
            last = p;
            if (fromPort != null) {
                if (p.distance(press) >= 3) moved = true;
                linkTo = p;
                linkTarget = dropTarget(p);
                repaint();
                return;
            }
            if (dragComp != null) {
                if (!moved && p.distance(press) < 3) return;
                if (!moved) model.beginStep("Move component");
                moved = true;
                ComponentUse c = model.def().component(dragComp);
                if (c != null) {
                    c.x = Math.round(dragStart[0] + (p.x - press.x) / scale);
                    c.y = Math.round(dragStart[1] + (p.y - press.y) / scale);
                }
                repaint();
            }
        }

        @Override public void mouseReleased(MouseEvent e) {
            if (fromPort != null) {
                finishLink(e.getPoint());
            } else if (moved) {
                model.endStep();
            }
            press = null;
            dragComp = null;
            fromPort = null;
            fromComp = null;
            linkTo = null;
            linkTarget = null;
            panning = false;
            moved = false;
            setCursor(Cursor.getDefaultCursor());
            repaint();
        }

        @Override public void mouseMoved(MouseEvent e) {
            setCursor(!readOnly && portAt(e.getPoint()) != null ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                    : compAt(e.getPoint()) != null && !readOnly ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    : linkAt(e.getPoint()) != null || relationAt(e.getPoint()) != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            viewChosen = true;
            Point2D c = local(e.getPoint());
            scale = Math.max(0.2, Math.min(4, scale * Math.pow(1.1, -e.getPreciseWheelRotation())));
            tx = e.getX() - c.getX() * scale;
            ty = e.getY() - c.getY() * scale;
            repaint();
        }
    }

    /** A link dragged from a port ends here: a click selects the component, a drop on another component connects. */
    private void finishLink(Point at) {
        String fc = fromComp, fp = fromPort;
        if (!moved) {
            select(fc, null, null);
            return;
        }
        String[] to = dropTarget(at);
        if (to == null) {
            model.message = compAt(at) != null && !fc.equals(compAt(at).id) ? "This component has no ports yet: make ports in its template."
                    : "Links join two different components: drop it on a port of another one.";
            model.fire();
            return;
        }
        for (StructureLink x : model.def().links) {
            boolean same = fc.equals(x.fromComponent) && fp.equals(x.fromPort) && to[0].equals(x.toComponent) && to[1].equals(x.toPort);
            boolean back = fc.equals(x.toComponent) && fp.equals(x.toPort) && to[0].equals(x.fromComponent) && to[1].equals(x.fromPort);
            if (same || back) {
                model.message = "These ports are connected already.";
                select(null, x.id, null);
                return;
            }
        }
        model.change("Connect ports", () -> {
            StructureLink l = new StructureLink(model.def().nextLinkId(), fc, fp, to[0], to[1]);
            model.def().links.add(l);
            model.selLink = l.id;
            model.selComponent = null;
            model.selRelation = null;
        });
    }

    private void select(String comp, String link, String relation) {
        model.selComponent = comp;
        model.selLink = link;
        model.selRelation = relation;
        model.fire();
    }

    /** Position for a new component box: the first free place, two boxes per row, so the view can stay large. */
    static double[] freePlace(TemplateDef t) {
        for (int i = 0; ; i++) {
            double x = 40 + (i % 2) * (BOX_W + 110), y = 40 + (i / 2) * (BOX_H + 90);
            Rectangle2D place = new Rectangle2D.Double(x - 40, y - 40, BOX_W + 80, BOX_H + 80);
            boolean free = true;
            for (ComponentUse c : t.components) free &= !box(c).intersects(place);
            if (free) return new double[]{x, y};
        }
    }
}
