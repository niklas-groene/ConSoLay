package gui.template;

import gui.components.Ui;
import layout.template.model.ComponentUse;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Draws a template's pattern graph (and, for composites, its components as small boxes) with Java2D: node shapes after
 * their SBGN role, edges with arrow heads, labels and port tags. Used by the editor canvas, the library previews and
 * the component boxes. Coordinates go through a transform from the template's local coordinates to the screen; the
 * template itself is never changed (fitting a preview into a box changes only the transform).
 *
 * @author Niklas Gröne
 */
public final class PatternPainter {

    private PatternPainter() { }

    static final Color NODE_BORDER = new Color(0x4B, 0x55, 0x63);
    static final Color EDGE = new Color(0x6B, 0x72, 0x80);
    static final Color SELECTED = Ui.ACCENT;
    static final Color PORT = new Color(0xB4, 0x53, 0x09);

    /** What to emphasise while painting. */
    public static final class Marks {
        public Set<String> selectedNodes = Collections.emptySet();
        public Set<String> selectedEdges = Collections.emptySet();
        /** Node id → highlight colour (e.g. the elements of the selected constraint). */
        public Map<String, Color> nodeHighlights = new HashMap<>();
        public Map<String, Color> edgeHighlights = new HashMap<>();
        public String hoverNode;
        public boolean labels = true;
        public boolean ports = true;
        public boolean edgeRoles = false;
        /** Scale of line widths and fonts (1 = screen pixels). */
        public float stroke = 1f;
    }

    /** The transform that fits the local box {@code b} (minX, minY, maxX, maxY) into {@code r} with a margin. */
    public static AffineTransform fit(double[] b, Rectangle2D r, double margin, double maxScale) {
        AffineTransform t = new AffineTransform();
        if (b == null) return t;
        double w = Math.max(1, b[2] - b[0]), h = Math.max(1, b[3] - b[1]);
        double s = Math.min((r.getWidth() - 2 * margin) / w, (r.getHeight() - 2 * margin) / h);
        s = Math.max(0.02, Math.min(maxScale, s));
        double ox = r.getX() + (r.getWidth() - w * s) / 2 - b[0] * s;
        double oy = r.getY() + (r.getHeight() - h * s) / 2 - b[1] * s;
        t.translate(ox, oy);
        t.scale(s, s);
        return t;
    }

    /** Paints the pattern of {@code t} (nodes and edges) through {@code at}. */
    public static void paintPattern(Graphics2D g0, TemplateDef t, AffineTransform at, Marks marks) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Map<String, PatternNode> byId = new HashMap<>();
            for (PatternNode n : t.nodes) byId.put(n.id, n);
            Map<String, Integer> parallel = new HashMap<>();
            for (PatternEdge e : t.edges) {
                PatternNode a = byId.get(e.source), b = byId.get(e.target);
                if (a == null || b == null) continue;
                String pairKey = e.source.compareTo(e.target) < 0 ? e.source + "|" + e.target : e.target + "|" + e.source;
                int k = parallel.merge(pairKey, 1, Integer::sum) - 1;
                Color c = marks.edgeHighlights.getOrDefault(e.id, marks.selectedEdges.contains(e.id) ? SELECTED : EDGE);
                boolean strong = marks.edgeHighlights.containsKey(e.id) || marks.selectedEdges.contains(e.id);
                paintEdge(g, a, b, e, at, c, strong ? 2.6f * marks.stroke : 1.4f * marks.stroke, k, marks.edgeRoles);
            }
            for (PatternNode n : t.nodes) {
                Color hl = marks.nodeHighlights.get(n.id);
                boolean sel = marks.selectedNodes.contains(n.id);
                paintNode(g, n, at, hl, sel, n.id.equals(marks.hoverNode), marks);
                if (marks.ports) {
                    List<Port> ports = t.portsOf(n.id);
                    if (!ports.isEmpty()) paintPortTag(g, n, at, ports, marks.stroke);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Screen shape of a node (its box through {@code at}). */
    public static Shape nodeShape(PatternNode n, AffineTransform at) {
        Point2D c = at.transform(new Point2D.Double(n.x, n.y), null);
        double s = Math.sqrt(Math.abs(at.getDeterminant()));
        double w = Math.max(6, n.width * s), h = Math.max(6, n.height * s);
        double x = c.getX() - w / 2, y = c.getY() - h / 2;
        String role = n.role == null ? "" : n.role.toUpperCase(Locale.ROOT);
        String shape = n.shape == null ? "" : n.shape.toLowerCase(Locale.ROOT);
        if (role.contains("SIMPLECHEMICAL") || role.equals("UNSPECIFIEDENTITY") || role.contains("ASSOCIATION")
                || role.contains("SOURCESINK") || role.contains("OPERATOR") || shape.contains("circle") || shape.contains("ellipse")) {
            return new Ellipse2D.Double(x, y, w, h);
        }
        if (role.contains("PHENOTYPE")) {
            Path2D p = new Path2D.Double();
            double d = Math.min(w / 4, h / 2);
            p.moveTo(x + d, y); p.lineTo(x + w - d, y); p.lineTo(x + w, y + h / 2); p.lineTo(x + w - d, y + h);
            p.lineTo(x + d, y + h); p.lineTo(x, y + h / 2); p.closePath();
            return p;
        }
        if (role.contains("PROCESS")) return new Rectangle2D.Double(x, y, w, h);
        double arc = Math.min(w, h) * (role.contains("COMPLEX") ? 0.25 : 0.45);
        return new RoundRectangle2D.Double(x, y, w, h, arc, arc);
    }

    static void paintNode(Graphics2D g, PatternNode n, AffineTransform at, Color highlight, boolean selected, boolean hover, Marks marks) {
        Shape s = nodeShape(n, at);
        Color fill = parse(n.fill, Color.WHITE);
        if (highlight != null) {
            g.setColor(new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), 60));
            g.setStroke(new BasicStroke(8f * marks.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
        g.setColor(fill);
        g.fill(s);
        g.setColor(selected ? SELECTED : highlight != null ? highlight : hover ? Ui.BORDER_HOVER : NODE_BORDER);
        g.setStroke(new BasicStroke((selected || highlight != null ? 2.4f : 1.2f) * marks.stroke));
        g.draw(s);
        String role = n.role == null ? "" : n.role.toUpperCase(Locale.ROOT);
        if (role.contains("DISSOCIATION")) {
            Rectangle2D b = s.getBounds2D();
            g.draw(new Ellipse2D.Double(b.getCenterX() - b.getWidth() / 4, b.getCenterY() - b.getHeight() / 4, b.getWidth() / 2, b.getHeight() / 2));
        }
        if (role.contains("ASSOCIATION") && !role.contains("DIS")) {
            g.setColor(NODE_BORDER);
            g.fill(s);
        }
        if (!marks.labels) return;
        String label = n.label == null || n.label.trim().isEmpty() ? prettyRole(n.role) : n.label.trim();
        boolean unnamed = label.isEmpty();   // its id then, as the lists show it, so the node can be told apart
        if (unnamed) label = n.id;
        Rectangle2D b = s.getBounds2D();
        float size = (float) Math.max(8, Math.min(12, b.getHeight() * 0.42));
        if (b.getWidth() < 18) return;
        Font f = Ui.font().deriveFont(size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String text = clip(label, fm, b.getWidth() - 4);
        g.setColor(unnamed ? Ui.DISABLED : Ui.TEXT);
        g.drawString(text, (float) (b.getCenterX() - fm.stringWidth(text) / 2.0), (float) (b.getCenterY() + fm.getAscent() / 2.0 - 1));
    }

    static void paintEdge(Graphics2D g, PatternNode a, PatternNode b, PatternEdge e, AffineTransform at, Color c, float width,
                          int parallelIndex, boolean roleLabel) {
        Shape sa = nodeShape(a, at), sb = nodeShape(b, at);
        Rectangle2D ra = sa.getBounds2D(), rb = sb.getBounds2D();
        g.setColor(c);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (a == b) {
            double r = Math.max(8, Math.min(ra.getWidth(), ra.getHeight()) * 0.45);
            Ellipse2D loop = new Ellipse2D.Double(ra.getMaxX() - r * 0.6, ra.getMinY() - r * 1.2 - parallelIndex * 6, r * 1.6, r * 1.6);
            g.draw(loop);
            return;
        }
        Point2D ca = new Point2D.Double(ra.getCenterX(), ra.getCenterY()), cb = new Point2D.Double(rb.getCenterX(), rb.getCenterY());
        double dx = cb.getX() - ca.getX(), dy = cb.getY() - ca.getY(), d = Math.hypot(dx, dy);
        if (d < 1e-6) return;
        double nx = -dy / d, ny = dx / d;
        double bend = parallelIndex == 0 ? 0 : ((parallelIndex + 1) / 2) * 12.0 * (parallelIndex % 2 == 0 ? -1 : 1);
        Point2D s = boxExit(ra, ca, cb), t = boxExit(rb, cb, ca);
        if (bend == 0) {
            g.draw(new Line2D.Double(s, t));
            if (e.directed) arrowHead(g, s, t, width);
        } else {
            Point2D mid = new Point2D.Double((s.getX() + t.getX()) / 2 + nx * bend, (s.getY() + t.getY()) / 2 + ny * bend);
            Path2D p = new Path2D.Double();
            p.moveTo(s.getX(), s.getY());
            p.quadTo(mid.getX(), mid.getY(), t.getX(), t.getY());
            g.draw(p);
            if (e.directed) arrowHead(g, mid, t, width);
        }
        if (roleLabel && e.role != null && !e.role.isEmpty() && d > 60) {
            g.setFont(Ui.small());
            String text = prettyRole(e.role);
            FontMetrics fm = g.getFontMetrics();
            float mx = (float) ((s.getX() + t.getX()) / 2 + nx * (bend + 9)), my = (float) ((s.getY() + t.getY()) / 2 + ny * (bend + 9));
            g.setColor(new Color(255, 255, 255, 200));
            g.fillRect((int) (mx - fm.stringWidth(text) / 2.0) - 2, (int) (my - fm.getAscent()) + 1, fm.stringWidth(text) + 4, fm.getHeight() - 2);
            g.setColor(Ui.MUTED);
            g.drawString(text, mx - fm.stringWidth(text) / 2f, my);
        }
    }

    static void arrowHead(Graphics2D g, Point2D from, Point2D tip, float width) {
        double d = from.distance(tip);
        if (d < 1e-6) return;
        double ux = (tip.getX() - from.getX()) / d, uy = (tip.getY() - from.getY()) / d;
        double len = 7 + width * 1.5, half = 3.5 + width;
        double bx = tip.getX() - ux * len, by = tip.getY() - uy * len;
        Path2D p = new Path2D.Double();
        p.moveTo(tip.getX(), tip.getY());
        p.lineTo(bx - uy * half, by + ux * half);
        p.lineTo(bx + uy * half, by - ux * half);
        p.closePath();
        g.fill(p);
    }

    /** Where the ray from {@code c} towards {@code to} leaves the box {@code r}. */
    static Point2D boxExit(Rectangle2D r, Point2D c, Point2D to) {
        double dx = to.getX() - c.getX(), dy = to.getY() - c.getY();
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return c;
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (r.getWidth() / 2) / Math.abs(dx);
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (r.getHeight() / 2) / Math.abs(dy);
        double t = Math.min(tx, ty);
        return new Point2D.Double(c.getX() + dx * t, c.getY() + dy * t);
    }

    static void paintPortTag(Graphics2D g, PatternNode n, AffineTransform at, List<Port> ports, float stroke) {
        Rectangle2D b = nodeShape(n, at).getBounds2D();
        StringBuilder sb = new StringBuilder();
        for (Port p : ports) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name);
        }
        g.setFont(Ui.small().deriveFont(Font.BOLD));
        FontMetrics fm = g.getFontMetrics();
        String text = "⚑ " + sb;
        int w = fm.stringWidth(text) + 8, h = fm.getHeight();
        int x = (int) (b.getMaxX() - 4), y = (int) (b.getMinY() - h + 2);
        g.setColor(new Color(0xFF, 0xF4, 0xE5));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(PORT);
        g.setStroke(new BasicStroke(1f * stroke));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.drawString(text, x + 4, y + fm.getAscent());
    }

    /** A compact preview of {@code t} in {@code r}: the pattern, or for a composite its components as boxes. */
    public static void paintPreview(Graphics2D g0, TemplateDef t, TemplateSource source, Rectangle2D r) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.clip(r);
            if (t == null) return;
            if (!t.isComposite()) {
                Marks m = new Marks();
                m.labels = r.getHeight() > 60;
                m.ports = false;
                m.stroke = 0.8f;
                paintPattern(g, t, fit(t.bounds(), r, 4, 1.0), m);
                return;
            }
            int n = Math.max(1, t.components.size());
            int cols = (int) Math.ceil(Math.sqrt(n)), rows = (int) Math.ceil(n / (double) cols);
            double cw = r.getWidth() / cols, ch = r.getHeight() / rows;
            int i = 0;
            for (ComponentUse c : t.components) {
                double x = r.getX() + (i % cols) * cw, y = r.getY() + (i / cols) * ch;
                Rectangle2D box = new Rectangle2D.Double(x + 2, y + 2, cw - 4, ch - 4);
                g.setColor(new Color(0xF3, 0xF4, 0xF6));
                g.fill(new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight(), 6, 6));
                g.setColor(Ui.BORDER);
                g.draw(new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight(), 6, 6));
                TemplateDef inner = Templates.resolve(c, source);
                if (inner != null && !inner.isComposite()) {
                    Marks m = new Marks();
                    m.labels = false;
                    m.ports = false;
                    m.stroke = 0.6f;
                    paintPattern(g, inner, fit(inner.bounds(), box, 3, 1.0), m);
                }
                i++;
            }
        } finally {
            g.dispose();
        }
    }

    /** "MACROMOLECULE" → "Macromolecule", "SIMPLECHEMICAL" → "Simple chemical" (known SBGN names). */
    public static String prettyRole(String role) {
        if (role == null || role.isEmpty()) return "";
        String r = SbgnRoles.pretty(role);
        return r != null ? r : role;
    }

    static Color parse(String hex, Color def) {
        if (hex == null) return def;
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String clip(String s, FontMetrics fm, double width) {
        if (fm.stringWidth(s) <= width) return s;
        for (int k = s.length() - 1; k > 0; k--) {
            String c = s.substring(0, k) + "…";
            if (fm.stringWidth(c) <= width) return c;
        }
        return "";
    }
}
