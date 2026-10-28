package headless;

import adaptagrams_bridge.CBLayoutAlgorithm;
import de.ipk_gatersleben.ag_nw.graffiti.plugins.gui.ipk_graffitiview.IPKGraffitiView;
import org.AttributeHelper;
import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;
import org.graffiti.attributes.Attribute;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.CoordinateAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.graphics.LabelAttribute;
import org.graffiti.managers.AttributeComponentManager;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Headless SVG / PNG export of a VANTED graph: paints the IPKGraffitiView (node shapes and edges)
 * through Batik and adds the node labels by hand, because the Swing label components are not
 * laid out without a real window.
 *
 * Coordinates: the view component clips children to its own bounds, so a graph with negative
 * coordinates is first shifted into the positive quadrant (uniform translation, the layout is
 * unaffected). The SVG viewBox is then set to the graph's bounding box plus a border.
 */
public final class HeadlessSvgExporter {

    public static final String FONT_FAMILY = "Helvetica, Arial, sans-serif";

    public static final class Bounds {
        public final double x, y, width, height;
        Bounds(double x, double y, double width, double height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
        public double maxX() { return x + width; }
        public double maxY() { return y + height; }
    }

    private HeadlessSvgExporter() { }

    /** Bounding box over node rectangles and edge bends. */
    public static Bounds bounds(Graph graph) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Node n : graph.getNodes()) {
            if (AttributeHelper.isHiddenGraphElement(n)) continue;
            double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
            double w = Math.max(1, AttributeHelper.getWidth(n)), h = Math.max(1, AttributeHelper.getHeight(n));
            minX = Math.min(minX, x - w / 2); maxX = Math.max(maxX, x + w / 2);
            minY = Math.min(minY, y - h / 2); maxY = Math.max(maxY, y + h / 2);
        }
        for (Edge e : graph.getEdges()) {
            if (AttributeHelper.isHiddenGraphElement(e)) continue;
            for (Object o : HeadlessCenterLayout.bendValues(e)) {
                if (!(o instanceof CoordinateAttribute)) continue;
                CoordinateAttribute c = (CoordinateAttribute) o;
                minX = Math.min(minX, c.getX()); maxX = Math.max(maxX, c.getX());
                minY = Math.min(minY, c.getY()); maxY = Math.max(maxY, c.getY());
            }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)) return new Bounds(0, 0, 1, 1);
        return new Bounds(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    /**
     * Ensures every element lies at or beyond (border, border) — the Swing view clips its children to
     * its own bounds, so negative coordinates would be cut off. Returns the bounds and the shift that
     * was applied, so a caller that must not disturb the graph can undo it.
     */
    private static Bounds ensurePositive(Graph graph, int border, double[] shiftOut) {
        Bounds b = bounds(graph);
        if (b.x < border || b.y < border) {
            double dx = border - b.x, dy = border - b.y;
            HeadlessCenterLayout.moveGraphTopLeft(graph, border, border);
            if (shiftOut != null) { shiftOut[0] = dx; shiftOut[1] = dy; }
            b = bounds(graph);
        }
        return b;
    }

    public static Bounds exportSvg(Graph graph, File outFile, int border) throws Exception {
        return exportSvg(graph, outFile, border, false);
    }

    /**
     * @param restorePositions move the graph back after drawing. Use it for graphs the pipeline still
     *        reads afterwards (the skeleton), so the export cannot influence the layout.
     */
    public static Bounds exportSvg(Graph graph, File outFile, int border, boolean restorePositions) throws Exception {
        double[] shift = new double[2];
        Bounds b = ensurePositive(graph, border, shift);
        int w = (int) Math.ceil(b.width) + 2 * border;
        int h = (int) Math.ceil(b.height) + 2 * border;
        IPKGraffitiView view = openView(graph, b, border);
        try {
            JComponent comp = view.getViewComponent();
            DOMImplementation dom = GenericDOMImplementation.getDOMImplementation();
            Document doc = dom.createDocument("http://www.w3.org/2000/svg", "svg", null);
            SVGGraphics2D svg = new SVGGraphics2D(doc);
            svg.setSVGCanvasSize(new Dimension(w, h));
            // Paint in absolute graph coordinates; the viewBox crops to the graph.
            svg.setPaint(Color.WHITE);
            svg.fillRect((int) Math.floor(b.x) - border, (int) Math.floor(b.y) - border, w + 1, h + 1);
            paintOnEdt(() -> { comp.print(svg); drawNodeLabels(graph, svg); });
            Element root = svg.getRoot();
            root.setAttributeNS(null, "viewBox",
                    fmt(b.x - border) + " " + fmt(b.y - border) + " " + w + " " + h);
            root.setAttributeNS(null, "width", String.valueOf(w));
            root.setAttributeNS(null, "height", String.valueOf(h));
            StringWriter sw = new StringWriter();
            svg.stream(root, sw, false, false);
            String text = sw.toString()
                    .replace("font-family=\"'Dialog'\"", "font-family=\"" + FONT_FAMILY + "\"")
                    .replace("font-family=\"'SansSerif'\"", "font-family=\"" + FONT_FAMILY + "\"")
                    .replace("font-family=\"'Helvetica'\"", "font-family=\"" + FONT_FAMILY + "\"");
            Files.write(outFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } finally {
            closeQuietly(view);
            if (restorePositions) HeadlessCenterLayout.translate(graph, -shift[0], -shift[1]);
        }
        return b;
    }

    /** PNG raster of the graph; the longer side is capped at maxDim pixels. */
    public static Bounds exportPng(Graph graph, File outFile, int border, int maxDim) throws Exception {
        Bounds b = ensurePositive(graph, border, null);
        double w = Math.ceil(b.width) + 2 * border, h = Math.ceil(b.height) + 2 * border;
        double scale = Math.min(1.0, maxDim / Math.max(w, h));
        // Render at 2x when the graph is small so labels stay crisp on high-dpi screens.
        if (Math.max(w, h) * 2 <= maxDim) scale = 2.0;
        int pw = (int) Math.ceil(w * scale), ph = (int) Math.ceil(h * scale);
        IPKGraffitiView view = openView(graph, b, border);
        try {
            JComponent comp = view.getViewComponent();
            BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, pw, ph);
                g.scale(scale, scale);
                g.translate(border - b.x, border - b.y);
                paintOnEdt(() -> { comp.print(g); drawNodeLabels(graph, g); });
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "png", outFile);
        } finally {
            closeQuietly(view);
        }
        return b;
    }

    /**
     * A view of the graph with all element components created (waits for the event thread).
     * The ER/AF pipelines need one in {@code ConstraintSBGNLayout.view2D} for the edge router;
     * only the PD pipeline creates it itself.
     */
    public static IPKGraffitiView createView(Graph graph) {
        return openView(graph, bounds(graph), 0);
    }

    private static IPKGraffitiView openView(Graph graph, Bounds b, int border) {
        IPKGraffitiView view = new IPKGraffitiView();
        view.setAttributeComponentManager(new AttributeComponentManager());
        view.setGraph(graph);
        JComponent comp = view.getViewComponent();
        int cw = (int) Math.ceil(b.maxX()) + border, ch = (int) Math.ceil(b.maxY()) + border;
        comp.setPreferredSize(new Dimension(cw, ch));
        comp.setSize(cw, ch);
        comp.setBounds(new Rectangle(0, 0, cw, ch));
        RepaintManager.currentManager(comp).setDoubleBufferingEnabled(false);
        // GraffitiView adds the node / edge components on the Swing event thread: wait until they are there,
        // otherwise a fast pipeline step is exported before the view has anything to paint.
        int expected = graph.getNumberOfNodes() + graph.getNumberOfEdges();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            flushEdt();
            if (comp.getComponentCount() >= expected || System.currentTimeMillis() > deadline) break;
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (comp.getComponentCount() < expected) {
            System.err.println("HeadlessSvgExporter: view has " + comp.getComponentCount()
                    + " components for " + expected + " graph elements");
        }
        return view;
    }

    /** Runs an empty task on the event thread, i.e. waits for everything queued before it. */
    private static void flushEdt() {
        if (SwingUtilities.isEventDispatchThread()) return;
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception ignore) { }
    }

    /** Paints on the event thread, like Swing itself would. */
    private static void paintOnEdt(Runnable painter) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) { painter.run(); return; }
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { painter.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    private static void closeQuietly(IPKGraffitiView view) {
        try { view.close(); } catch (Throwable ignore) { }
    }

    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /** Draws every node label centred on the node (compartments: bold, top-left inside). */
    private static void drawNodeLabels(Graph graph, Graphics2D g) {
        final AffineTransform baseTx = g.getTransform();
        final FontRenderContext frc = g.getFontRenderContext();
        Font defaultFont = new Font("Helvetica", Font.PLAIN, 12);
        for (Node n : graph.getNodes()) {
            if (AttributeHelper.isHiddenGraphElement(n)) continue;
            String text = null;
            try { text = AttributeHelper.getLabel(n, (String) null); } catch (Throwable ignore) { }
            if (text == null || text.isEmpty()) continue;
            String plain = HtmlPlainText.htmlToPlainText(text);
            if (plain == null || plain.trim().isEmpty()) continue;
            String[] lines = plain.split("\n");

            double cx = AttributeHelper.getPositionX(n), cy = AttributeHelper.getPositionY(n);
            Font f = defaultFont;
            try {
                Attribute la = n.getAttribute(GraphicAttributeConstants.LABEL_ATTRIBUTE_PATH);
                if (la instanceof LabelAttribute) {
                    int size = ((LabelAttribute) la).getFontSize();
                    if (size > 0) f = new Font("Helvetica", Font.PLAIN, size);
                }
            } catch (Throwable ignore) { }
            Color col = null;
            try { col = AttributeHelper.getLabelColor(n); } catch (Throwable ignore) { }
            if (col == null || col.equals(Color.WHITE)) col = Color.BLACK;

            double offX = 0, offY = 0;
            boolean compartment = CBLayoutAlgorithm.clusterMap != null && CBLayoutAlgorithm.clusterMap.containsKey(n);
            if (!compartment) {
                try {
                    String role = (String) AttributeHelper.getAttributeValue(n, "sbgn", "role", "", "", false);
                    compartment = "COMPARTMENT".equalsIgnoreCase(role);
                } catch (Throwable ignore) { }
            }
            if (compartment) {
                offY = -AttributeHelper.getHeight(n) / 2 + 14;
                f = new Font("Helvetica", Font.BOLD, 16);
            }
            try {
                Attribute la = n.getAttribute(GraphicAttributeConstants.LABEL_ATTRIBUTE_PATH);
                if (la instanceof LabelAttribute && ((LabelAttribute) la).getLabelOffset() != null) {
                    offX += ((LabelAttribute) la).getLabelOffset().x;
                    offY += ((LabelAttribute) la).getLabelOffset().y;
                }
            } catch (Exception ignore) { }

            g.setTransform(baseTx);
            Font oldF = g.getFont();
            Paint oldP = g.getPaint();
            try {
                g.setFont(f);
                g.setPaint(col);
                float lineHeight = f.getLineMetrics("Ag", frc).getHeight();
                float blockH = lineHeight * lines.length;
                float y = (float) (cy + offY - blockH / 2f + lineHeight * 0.8f);
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        Rectangle2D r = f.getStringBounds(line, frc);
                        float x = (float) (cx + offX - r.getWidth() / 2.0);
                        g.drawString(line, x, y);
                    }
                    y += lineHeight;
                }
            } finally {
                g.setFont(oldF);
                g.setPaint(oldP);
            }
        }
    }
}
