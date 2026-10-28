package gui.components;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A pictogram from {@code /cblayout/icons/<name>.svg}, parsed once with Batik and painted as a vector graphic, so it
 * is sharp at any size and on HiDPI screens. A file that is missing or cannot be parsed is logged once and painted as
 * a neutral outline. Create and paint on the event dispatch thread.
 *
 * @author Niklas Gröne
 */
public final class SvgIcon implements Icon {

    private static final String FOLDER = "/cblayout/icons/";
    private static final float DISABLED_ALPHA = 0.4f;

    /** Parsed pictograms by name; {@code null} values mark a file that could not be loaded. */
    private static final Map<String, Picture> PICTURES = new HashMap<>();
    private static final Map<String, SvgIcon> ICONS = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();
    /** False once loading failed for want of Batik classes; later pictograms then skip Batik silently. */
    private static boolean batikAvailable = true;

    private final String name;
    private final int size;
    private final boolean disabled;
    private final Picture picture;

    /** The last raster of the disabled variant and the device scale it was made for. */
    private BufferedImage disabledImage;
    private double disabledScaleX;
    private double disabledScaleY;

    private SvgIcon(String name, int size, boolean disabled, Picture picture) {
        this.name = name;
        this.size = size;
        this.disabled = disabled;
        this.picture = picture;
    }

    /** The pictogram {@code name} (file name without ".svg", e.g. "align-top") at {@code size} x {@code size} px. */
    public static SvgIcon get(String name, int size) {
        return get(name, size, false);
    }

    /**
     * As {@link #get(String, int)}; a disabled icon is painted grey at 40 % opacity, composited as a whole so that
     * overlapping shapes do not shine through one another.
     */
    public static SvgIcon get(String name, int size, boolean disabled) {
        String key = name + '@' + size + (disabled ? "-" : "+");
        SvgIcon icon = ICONS.get(key);
        if (icon == null) {
            icon = new SvgIcon(name, size, disabled, picture(name));
            ICONS.put(key, icon);
        }
        return icon;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = Ui.smooth(g);
        try {
            g2.translate(x, y);
            if (picture == null) {
                paintFallback(g2);
            } else if (disabled) {
                paintDisabled(g2);
            } else {
                paintVector(g2);
            }
        } catch (RuntimeException | LinkageError e) {
            report(name, "cannot be painted", e);
            paintFallback(g2);
        } finally {
            g2.dispose();
        }
    }

    /** Paints the shape tree scaled to {@link #size}, centred if the pictogram is not square. */
    private void paintVector(Graphics2D g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            double scale = Math.min(size / picture.width, size / picture.height);
            g2.translate((size - picture.width * scale) / 2, (size - picture.height * scale) / 2);
            g2.scale(scale, scale);
            picture.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints the grey, translucent variant: the vector is drawn into an image with one pixel per device pixel, the
     * image is made grey and translucent, and it is drawn back at the device resolution.
     */
    private void paintDisabled(Graphics2D g2) {
        AffineTransform t = g2.getTransform();
        double sx = Math.max(1e-3, Math.hypot(t.getScaleX(), t.getShearY()));
        double sy = Math.max(1e-3, Math.hypot(t.getShearX(), t.getScaleY()));
        if (disabledImage == null || sx != disabledScaleX || sy != disabledScaleY) {
            disabledImage = renderDisabled(sx, sy);
            disabledScaleX = sx;
            disabledScaleY = sy;
        }
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.scale(1 / sx, 1 / sy);
            g.drawImage(disabledImage, 0, 0, null);
        } finally {
            g.dispose();
        }
    }

    private BufferedImage renderDisabled(double sx, double sy) {
        int w = Math.max(1, (int) Math.ceil(size * sx));
        int h = Math.max(1, (int) Math.ceil(size * sy));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(sx, sy);
            paintVector(g);
        } finally {
            g.dispose();
        }
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int alpha = Math.round((p >>> 24) * DISABLED_ALPHA);
            int grey = ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
            pixels[i] = alpha << 24 | grey << 16 | grey << 8 | grey;
        }
        return image;
    }

    /** A neutral rounded square outline in place of a pictogram that could not be loaded. */
    private void paintFallback(Graphics2D g) {
        float unit = size / 32f;
        float stroke = 1.5f * unit;
        float inset = 4 * unit + stroke / 2;
        g.setColor(disabled ? Ui.DISABLED : Ui.MUTED);
        g.setStroke(new BasicStroke(stroke));
        g.draw(new RoundRectangle2D.Float(inset, inset, size - 2 * inset, size - 2 * inset, 6 * unit, 6 * unit));
    }

    private static Picture picture(String name) {
        if (PICTURES.containsKey(name)) {
            return PICTURES.get(name);
        }
        Picture picture = null;
        URL url = SvgIcon.class.getResource(FOLDER + name + ".svg");
        if (url == null) {
            report(name, "is missing", null);
        } else if (batikAvailable) {
            try {
                picture = Batik.load(url);
            } catch (LinkageError e) {
                // Batik (or its XML parser) is not on the class path: say so once, not once per pictogram
                batikAvailable = false;
                report("*", "cannot be read, Batik is not available", e);
            } catch (Exception e) {
                report(name, "cannot be read", e);
            }
        }
        PICTURES.put(name, picture);
        return picture;
    }

    private static void report(String name, String problem, Throwable cause) {
        if (REPORTED.add(name)) {
            System.err.println("[SvgIcon] Pictogram " + FOLDER + name + ".svg " + problem
                    + (cause != null ? ": " + cause : "") + "; painting an outline instead.");
        }
    }

    /** A parsed pictogram: its shape tree and the size of the document it is drawn in (px). */
    private static final class Picture {

        /** A Batik {@code GraphicsNode}, kept as an Object so that this class loads without Batik. */
        final Object node;
        final double width;
        final double height;

        Picture(Object node, double width, double height) {
            this.node = node;
            this.width = width;
            this.height = height;
        }

        void paint(Graphics2D g) {
            Batik.paint(node, g);
        }
    }

    /**
     * Every reference to Batik lives here, so that a missing Batik only fails (and is reported) when the first
     * pictogram is loaded, not when {@link SvgIcon} itself is loaded.
     */
    private static final class Batik {

        private static SAXSVGDocumentFactory factory;

        static Picture load(URL url) throws Exception {
            if (factory == null) {
                factory = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName());
            }
            Document document;
            try (InputStream in = url.openStream()) {
                document = factory.createDocument(url.toString(), in);
            }
            UserAgent agent = new UserAgentAdapter();
            BridgeContext context = new BridgeContext(agent, new DocumentLoader(agent));
            try {
                context.setDynamicState(BridgeContext.STATIC);
                // the root node maps the viewBox onto the document's width x height (px)
                GraphicsNode node = new GVTBuilder().build(context, document);
                Dimension2D size = context.getDocumentSize();
                if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
                    throw new IllegalStateException("the document has no size");
                }
                return new Picture(node, size.getWidth(), size.getHeight());
            } finally {
                context.dispose();
            }
        }

        static void paint(Object node, Graphics2D g) {
            ((GraphicsNode) node).paint(g);
        }
    }
}
