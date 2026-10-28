package layout.template.model;

import java.util.Objects;

/**
 * A versioned reference to a template: its stable id and the revision it was used with. The name is kept for display
 * only (the template may have been renamed since).
 *
 * @author Niklas Gröne
 */
public final class TemplateRef {

    public final String id;
    public final int revision;
    public final String name;

    public TemplateRef(String id, int revision, String name) {
        this.id = id;
        this.revision = revision;
        this.name = name;
    }

    public static TemplateRef of(TemplateDef t) {
        return new TemplateRef(t.id, t.revision, t.name);
    }

    /** "id@revision": identifies one stored state of a template. */
    public String key() {
        return id + "@" + revision;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TemplateRef && ((TemplateRef) o).id.equals(id) && ((TemplateRef) o).revision == revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision);
    }

    @Override
    public String toString() {
        return (name == null ? id : name) + " (rev. " + revision + ")";
    }
}
