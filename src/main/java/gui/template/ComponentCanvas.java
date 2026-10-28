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
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Canvas of a composite template: every component is a box with its role, the template it uses and a preview; its ports
 * are dots on the box sides. Structure links (search conditions) are solid lines between ports, made by dragging from
 * port to port; layout relations (applied as constraints) are dashed violet lines between the components.
 *
 * @author Niklas Gröne
 */
final class ComponentCanvas extends JComponent {

    private static final long serialVersionUID = 1L;
    static final double BOX_W = 190, BOX_H = 130;
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
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete");
        getActionMap().put("delete", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(ActionEvent e) { if (!readOnly) CompositeProperties.deleteSelected(model); }
        });
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

    private String[] portAt(Point p) {
        Point2D l = local(p);
        for (ComponentUse c : model.def().components) {
            TemplateDef inner = Templates.resolve(c, model.source());
            if (inner == null) continue;
            for (Port port : inner.ports) {
                if (portPoint(c, port.id).distance(l) * scale < 9) return new String[]{c.id, port.id};
            }
        }
        return null;
    }

    private ComponentUse compAt(Point p) {
        Point2D l = local(p);
        List<ComponentUse> cs = new ArrayList<>(model.def().components);
        for (int i = cs.size() - 1; i >= 0; i--) if (box(cs.get(i)).contains(l)) return cs.get(i);
        return null;
    }

    private String linkAt(Point p) {
        Point2D l = local(p);
        for (StructureLink s : model.def().links) {
            ComponentUse a = model.def().component(s.fromComponent), b = model.def().component(s.toComponent);
            if (a == null || b == null) continue;
            if (new Line2D.Double(portPoint(a, s.fromPort), portPoint(b, s.toPort)).ptSegDist(l) * scale < 6) return s.id;
        }
        return null;
    }

    private String relationAt(Point p) {
        Point2D l = local(p);
        for (LayoutRelation r : model.def().relations) {
            List<Point2D> pts = relationPoints(r);
            for (int i = 0; i + 1 < pts.size(); i++) if (new Line2D.Double(pts.get(i), pts.get(i + 1)).ptSegDist(l) * scale < 6) return r.id;
        }
        return null;
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
                    gl.draw(new Line2D.Double(portPoint(a, fromPort), local(linkTo)));
                }
            }
            gl.dispose();
            paintLegend(g);
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
            Rectangle2D pr = new Rectangle2D.Double(b.getX() + 22, b.getY() + 34, b.getWidth() - 44, b.getHeight() - 42);
            PatternPainter.paintPreview(g, inner, model.source(), pr);
            for (Port p : inner.ports) {
                Point2D pt = portPoint(c, p.id);
                boolean left = pt.getX() <= b.getX() + 1;
                g.setColor(PatternPainter.PORT);
                g.fill(new java.awt.geom.Ellipse2D.Double(pt.getX() - 5, pt.getY() - 5, 10, 10));
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(pt.getX() - 2, pt.getY() - 2, 4, 4));
                g.setFont(Ui.small().deriveFont(Font.BOLD));
                FontMetrics fm = g.getFontMetrics();
                String name = p.name;
                float x = left ? (float) pt.getX() - fm.stringWidth(name) - 8 : (float) pt.getX() + 8;
                g.setColor(new Color(255, 255, 255, 220));
                g.fillRect((int) x - 2, (int) pt.getY() - fm.getAscent() + 3, fm.stringWidth(name) + 4, fm.getHeight() - 2);
                g.setColor(PatternPainter.PORT);
                g.drawString(name, x, (float) pt.getY() + 4);
            }
        }
    }

    private void paintLink(Graphics2D g, StructureLink s) {
        ComponentUse a = model.def().component(s.fromComponent), b = model.def().component(s.toComponent);
        if (a == null || b == null) return;
        Point2D p = portPoint(a, s.fromPort), q = portPoint(b, s.toPort);
        boolean sel = s.id.equals(model.selLink);
        Color c = hasProblem(s.id) ? Ui.ERROR : sel ? Ui.ACCENT : STRUCTURE;
        g.setColor(c);
        g.setStroke(new BasicStroke(sel ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = curve(p, q);
        g.draw(path);
        if (s.type == StructureLink.Type.SAME_NODE) {
            g.setStroke(new BasicStroke(1f));
            g.draw(curve(new Point2D.Double(p.getX(), p.getY() + 4), new Point2D.Double(q.getX(), q.getY() + 4)));
        } else if (s.direction == StructureLink.Direction.FORWARD) {
            Point2D before = new Point2D.Double(q.getX() + (p.getX() < q.getX() ? -14 : 14), q.getY());
            PatternPainter.arrowHead(g, before, q, 2f);
        }
        String role = "";
        for (layout.template.model.Condition k : s.edgeConditions) {
            if (k.op == layout.template.model.Condition.Op.EQUALS) role = PatternPainter.prettyRole(k.values.get(0));
            else if (k.op == layout.template.model.Condition.Op.IN_LIST) role = String.join("/", k.values);
        }
        String text = s.type == StructureLink.Type.SAME_NODE ? "same node" : s.type == StructureLink.Type.PATH ? "path (not supported)"
                : "edge" + (role.isEmpty() ? "" : " · " + role) + (s.direction == StructureLink.Direction.ANY ? " · either way" : "");
        label(g, text, (p.getX() + q.getX()) / 2, (p.getY() + q.getY()) / 2 - 10, c, true);
    }

    private boolean hasProblem(String elementId) {
        for (layout.template.model.TemplateValidator.Problem p : model.problems()) if (elementId.equals(p.elementId)) return true;
        return false;
    }

    private static Path2D curve(Point2D p, Point2D q) {
        Path2D path = new Path2D.Double();
        path.moveTo(p.getX(), p.getY());
        double dx = Math.max(40, Math.abs(q.getX() - p.getX()) / 2);
        double s1 = p.getX() <= q.getX() ? 1 : -1;
        path.curveTo(p.getX() + s1 * dx, p.getY(), q.getX() - s1 * dx, q.getY(), q.getX(), q.getY());
        return path;
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
        Point2D m = pts.get(0);
        Point2D n = pts.get(1);
        String text = r.describe(id -> {
            ComponentUse u = model.def().component(id);
            return u == null ? "?" : u.name;
        });
        if (r.type.onComponents()) {
            g.setStroke(new BasicStroke(1f));
            for (String id : r.components) {
                ComponentUse u = model.def().component(id);
                if (u == null) continue;
                g.draw(new Line2D.Double(box(u).getCenterX(), box(u).getMaxY(), box(u).getCenterX(), pts.get(0).getY()));
            }
        }
        label(g, "Layout: " + text + (r.enabled ? "" : " (off)"), (m.getX() + n.getX()) / 2, (m.getY() + n.getY()) / 2 + 12, c, false);
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
        if (port != null) return Ui.tooltip("Port <b>" + Ui.esc(portName(port[0], port[1])) + "</b>" + (readOnly ? "" : "<br>Drag to another port to connect them (structure)."));
        ComponentUse c = compAt(e.getPoint());
        if (c != null) return Ui.tooltip("<b>" + Ui.esc(c.name) + "</b>: " + Ui.esc(c.ref == null ? "?" : c.ref.toString()) + "<br>Double-click to look inside.");
        return null;
    }

    String portName(String comp, String port) {
        ComponentUse c = model.def().component(comp);
        TemplateDef inner = Templates.resolve(c, model.source());
        Port p = inner == null ? null : inner.port(port);
        return (c == null ? "?" : c.name) + "." + (p == null ? port + " (missing)" : p.name);
    }

    private final class Mouse extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            press = e.getPoint();
            last = press;
            moved = false;
            panning = SwingUtilities.isRightMouseButton(e) || e.isAltDown();
            if (panning) return;
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
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (press == null) return;
            Point p = e.getPoint();
            if (panning) {
                tx += p.x - last.x;
                ty += p.y - last.y;
                last = p;
                repaint();
                return;
            }
            last = p;
            if (fromPort != null) {
                linkTo = p;
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
                String[] to = portAt(e.getPoint());
                String fc = fromComp, fp = fromPort;
                if (to != null && !to[0].equals(fc)) {
                    model.change("Connect ports", () -> {
                        StructureLink l = new StructureLink(model.def().nextLinkId(), fc, fp, to[0], to[1]);
                        model.def().links.add(l);
                        model.selLink = l.id;
                        model.selComponent = null;
                        model.selRelation = null;
                    });
                } else if (to != null) {
                    model.message = "Links join two different components.";
                    model.fire();
                }
            }
            if (moved) model.endStep();
            press = null;
            dragComp = null;
            fromPort = null;
            fromComp = null;
            linkTo = null;
            panning = false;
            moved = false;
            repaint();
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            Point2D c = local(e.getPoint());
            scale = Math.max(0.2, Math.min(4, scale * Math.pow(1.1, -e.getPreciseWheelRotation())));
            tx = e.getX() - c.getX() * scale;
            ty = e.getY() - c.getY() * scale;
            repaint();
        }
    }

    private void select(String comp, String link, String relation) {
        model.selComponent = comp;
        model.selLink = link;
        model.selRelation = relation;
        model.fire();
    }

    /** Position for a new component box, right of all others. */
    static double[] freePlace(TemplateDef t) {
        double x = 40, y = 60;
        for (ComponentUse c : t.components) x = Math.max(x, c.x + BOX_W + 170);
        return new double[]{x, y};
    }
}
