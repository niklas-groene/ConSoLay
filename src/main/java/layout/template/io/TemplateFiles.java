package layout.template.io;

import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.RuleSystem;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;

import org.AttributeHelper;
import org.graffiti.attributes.Attributable;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugins.ios.importers.gml.GMLReader;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reading and writing template files ({@value #TEMPLATE_EXT}) and rule-set files ({@value #RULES_EXT}), and importing
 * the GML templates of earlier versions. Writing goes to a temporary file in the same folder that then replaces the
 * target, so a failed save never leaves a half-written template. Invalid templates are not written.
 *
 * @author Niklas Gröne
 */
public final class TemplateFiles {

    public static final String TEMPLATE_EXT = ".cbltemplate.json";
    public static final String RULES_EXT = ".cblrules.json";

    private TemplateFiles() { }

    /**
     * Writes {@code t} to {@code file} (replacing it safely).
     *
     * @throws IOException with the validation problems if the template has errors, or the I/O error
     */
    public static void save(TemplateDef t, File file, TemplateSource source) throws IOException {
        List<TemplateValidator.Problem> problems = TemplateValidator.validate(t, TemplateSource.chain(TemplateSource.embeddedIn(t), source));
        if (!TemplateValidator.canSave(problems)) {
            StringBuilder sb = new StringBuilder("The template has errors and was not saved:");
            for (TemplateValidator.Problem p : problems) if (p.severity == TemplateValidator.Severity.ERROR) sb.append("\n• ").append(p);
            throw new IOException(sb.toString());
        }
        String text;
        try {
            text = Json.write(TemplateCodec.toJson(t)) + "\n";
        } catch (IllegalArgumentException e) {
            throw new IOException("The template could not be written: " + e.getMessage(), e);
        }
        writeAtomically(file, text);
    }

    /** Reads a template file; throws with a readable message for a broken or foreign file. */
    public static TemplateDef load(File file) throws IOException, TemplateCodec.FormatException {
        return TemplateCodec.fromJson(readJson(file));
    }

    public static void saveRules(RuleSystem s, File file) throws IOException {
        String text;
        try {
            text = Json.write(TemplateCodec.toJson(s)) + "\n";
        } catch (IllegalArgumentException e) {
            throw new IOException("The rule set could not be written: " + e.getMessage(), e);
        }
        writeAtomically(file, text);
    }

    public static RuleSystem loadRules(File file) throws IOException, TemplateCodec.FormatException {
        return TemplateCodec.rulesFromJson(readJson(file));
    }

    private static Object readJson(File file) throws IOException, TemplateCodec.FormatException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new TemplateCodec.FormatException("The file " + file.getName() + " is not valid JSON: " + e.getMessage());
        }
    }

    /** Writes {@code text} to a temporary file next to {@code file}, forces it to disk and moves it over {@code file}. */
    static void writeAtomically(File file, String text) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) throw new IOException("The folder " + dir + " does not exist.");
        Path tmp = new File(dir, "." + file.getName() + ".tmp-" + UUID.randomUUID()).toPath();
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
            }
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** "Chain" → "Chain.cbltemplate.json" (characters a file name must not have are replaced). */
    public static String fileName(String templateName, String ext) {
        String base = templateName == null || templateName.trim().isEmpty() ? "template" : templateName.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return base + ext;
    }

    public static boolean isTemplateFile(File f) {
        return f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(TEMPLATE_EXT);
    }

    public static boolean isGmlFile(File f) {
        return f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".gml");
    }

    /**
     * Imports a template saved as GML by earlier versions: its nodes (with position, size, label, SBGN role, shape and
     * fill colour), its edges (direction, SBGN role) and the roles as search conditions. Such files have no constraint
     * definitions; the template is marked so the editor can say so ({@link TemplateDef#importedWithoutConstraints}).
     * Positions are moved so the pattern's bounding box starts at (0, 0).
     */
    public static TemplateDef importGml(File file) throws IOException {
        Graph g;
        try (InputStream in = new FileInputStream(file)) {
            g = new GMLReader().read(in);
        } catch (Exception e) {
            throw new IOException("The GML file " + file.getName() + " could not be read: " + e.getMessage(), e);
        }
        if (g == null || g.getNodes().isEmpty()) throw new IOException("The GML file " + file.getName() + " has no nodes.");
        String name = file.getName().replaceAll("(?i)\\.gml$", "");
        TemplateDef t = fromGraph(new ArrayList<>(g.getNodes()), new ArrayList<>(g.getEdges()), name);
        t.source = "gml-import";
        t.importedWithoutConstraints = true;
        t.note = "Imported from " + file.getName() + ". GML templates have no explicit constraint definitions: "
                + "add them in the editor (or let it derive them from the arrangement).";
        return t;
    }

    /**
     * A pattern of the given nodes and the given edges between them (edges with an end outside are skipped), with
     * stable local ids in the order given, positions relative to the top-left of their bounding box and the SBGN roles
     * as conditions. Used by the GML import and by "Template from selection".
     */
    public static TemplateDef fromGraph(List<Node> nodes, List<Edge> edges, String name) {
        TemplateDef t = TemplateDef.create(name, TemplateDef.Kind.PATTERN);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (Node n : nodes) {
            minX = Math.min(minX, AttributeHelper.getPositionX(n) - AttributeHelper.getWidth(n) / 2);
            minY = Math.min(minY, AttributeHelper.getPositionY(n) - AttributeHelper.getHeight(n) / 2);
        }
        Map<Node, String> ids = new HashMap<>();
        for (Node n : nodes) {
            PatternNode p = new PatternNode(t.nextNodeId(), AttributeHelper.getPositionX(n) - minX, AttributeHelper.getPositionY(n) - minY);
            p.width = positive(AttributeHelper.getWidth(n), 40);
            p.height = positive(AttributeHelper.getHeight(n), 30);
            p.label = plainLabel(AttributeHelper.getLabel(n, ""));
            p.role = role(n);
            try {
                p.shape = AttributeHelper.getShape(n);
            } catch (Exception e) {
                p.shape = "";
            }
            try {
                Color c = AttributeHelper.getFillColor(n);
                if (c != null) p.fill = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
            } catch (Exception ignored) {
                // no fill colour
            }
            p.conditions.add(roleCondition(p.role));
            t.nodes.add(p);
            ids.put(n, p.id);
        }
        for (Edge e : edges) {
            String s = ids.get(e.getSource()), d = ids.get(e.getTarget());
            if (s == null || d == null) continue;
            PatternEdge p = new PatternEdge(t.nextEdgeId(), s, d, e.isDirected());
            p.role = role(e);
            p.conditions.add(roleCondition(p.role));
            t.edges.add(p);
        }
        return t;
    }

    /** The search condition for an SBGN role: any role if {@code role} is null or empty, else exactly this one. */
    static Condition roleCondition(String role) {
        return role == null || role.isEmpty() ? Condition.any(Condition.SBGN_ROLE)
                : Condition.equalsTo(Condition.SBGN_ROLE, role);
    }

    /** The SBGN role of a node or edge, or "". */
    public static String role(Attributable a) {
        Object o = AttributeHelper.getAttributeValue(a, "sbgn", "role", "", "", false);
        return o == null ? "" : String.valueOf(o);
    }

    public static String plainLabel(String l) {
        return l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static double positive(double v, double def) {
        return Double.isFinite(v) && v > 0 ? v : def;
    }

    /** The template files and GML files of a folder, sorted by name. */
    public static List<File> listFolder(File folder) {
        List<File> l = new ArrayList<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return l;
        for (File f : files) if (!f.isHidden() && (isTemplateFile(f) || isGmlFile(f))) l.add(f);
        l.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return l;
    }
}
