package layout.template.io;

import layout.template.model.ComponentUse;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The templates of a folder: every {@code .cbltemplate.json} file (the newest revision of one template each), the old
 * GML templates (imported on the fly, marked as such), and the revisions embedded in composite templates. It resolves
 * versioned references, finds the composites that use a template and the components that could be updated to a newer
 * revision.
 *
 * @author Niklas Gröne
 */
public final class TemplateLibrary implements TemplateSource {

    /** One file of the folder. */
    public static final class Entry {
        public final File file;
        /** The template, or null if the file could not be read. */
        public final TemplateDef template;
        /** Why it could not be read, or null. */
        public final String error;

        Entry(File file, TemplateDef template, String error) {
            this.file = file;
            this.template = template;
            this.error = error;
        }

        @Override
        public String toString() {
            return template != null ? template.name : file.getName();
        }
    }

    private final File folder;
    private final List<Entry> entries = new ArrayList<>();
    /** Templates held in memory only (examples, templates opened from elsewhere), keyed by id@revision. */
    private final Map<String, TemplateDef> extra = new LinkedHashMap<>();

    public TemplateLibrary(File folder) {
        this.folder = folder;
        reload();
    }

    public File getFolder() {
        return folder;
    }

    /** Reads the folder again. */
    public void reload() {
        entries.clear();
        for (File f : TemplateFiles.listFolder(folder)) {
            try {
                TemplateDef t = TemplateFiles.isGmlFile(f) ? TemplateFiles.importGml(f) : TemplateFiles.load(f);
                if (TemplateFiles.isGmlFile(f)) t.id = "gml:" + f.getName();   // stable while the file keeps its name
                entries.add(new Entry(f, t, null));
            } catch (IOException | TemplateCodec.FormatException e) {
                entries.add(new Entry(f, null, e.getMessage()));
            } catch (RuntimeException e) {
                entries.add(new Entry(f, null, "Could not read " + f.getName() + ": " + e));
            }
        }
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** The readable templates of the folder and the ones added in memory. */
    public List<TemplateDef> templates() {
        List<TemplateDef> l = new ArrayList<>();
        for (Entry e : entries) if (e.template != null) l.add(e.template);
        for (TemplateDef t : extra.values()) if (find(t.id) == null) l.add(t);
        return l;
    }

    /** Adds a template that has no file in the folder (it is found by id and revision). */
    public void addInMemory(TemplateDef t) {
        extra.put(t.id + "@" + t.revision, t);
    }

    /** The file entry of the template with this id, or null. */
    public Entry find(String id) {
        for (Entry e : entries) if (e.template != null && e.template.id.equals(id)) return e;
        return null;
    }

    @Override
    public TemplateDef get(TemplateRef ref) {
        if (ref == null) return null;
        for (Entry e : entries) {
            if (e.template != null && e.template.id.equals(ref.id) && e.template.revision == ref.revision) return e.template;
        }
        TemplateDef x = extra.get(ref.key());
        if (x != null) return x;
        // an older revision embedded in a composite of the folder
        for (Entry e : entries) {
            if (e.template == null) continue;
            TemplateDef d = TemplateSource.embeddedIn(e.template).get(ref);
            if (d != null) return d;
        }
        return null;
    }

    @Override
    public TemplateDef latest(String id) {
        TemplateDef best = null;
        for (Entry e : entries) {
            if (e.template != null && e.template.id.equals(id) && (best == null || e.template.revision > best.revision)) best = e.template;
        }
        for (TemplateDef t : extra.values()) if (t.id.equals(id) && (best == null || t.revision > best.revision)) best = t;
        return best;
    }

    /** A component that uses an older revision than the library has. */
    public static final class Update {
        public final ComponentUse component;
        public final TemplateDef newer;

        Update(ComponentUse component, TemplateDef newer) {
            this.component = component;
            this.newer = newer;
        }
    }

    /** The components of {@code composite} whose template has a newer revision in the library. */
    public List<Update> updatesFor(TemplateDef composite) {
        List<Update> l = new ArrayList<>();
        for (ComponentUse c : composite.components) {
            if (c.ref == null) continue;
            TemplateDef newest = latest(c.ref.id);
            if (newest != null && newest.revision > c.ref.revision) l.add(new Update(c, newest));
        }
        return l;
    }

    /** Every use of the template {@code id} in the library's composites: "Composite › Component (rev. n)". */
    public List<String> usesOf(String id) {
        List<String> l = new ArrayList<>();
        for (TemplateDef t : templates()) {
            for (ComponentUse c : t.components) {
                if (c.ref != null && c.ref.id.equals(id)) l.add(t.name + " › " + c.name + " (uses rev. " + c.ref.revision + ")");
            }
        }
        return l;
    }

    /**
     * Puts every template {@code t} uses (all levels, the exact revisions) into its {@link TemplateDef#dependencies}, so
     * the file can be used on its own. Nested dependency maps are flattened into the top level.
     */
    public static void embedDependencies(TemplateDef t, TemplateSource source) {
        TemplateSource s = TemplateSource.chain(source, TemplateSource.embeddedIn(t));
        Map<String, TemplateDef> deps = new LinkedHashMap<>();
        for (TemplateDef d : Templates.closure(t, s)) {
            if (d == t || (d.id.equals(t.id) && d.revision == t.revision)) continue;
            TemplateDef copy = d.copy();
            copy.dependencies.clear();
            deps.put(copy.id + "@" + copy.revision, copy);
        }
        t.dependencies.clear();
        t.dependencies.putAll(deps);
    }
}
