package layout.template.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where templates referenced by id and revision come from: a library folder, the dependencies embedded in a composite
 * or rule-set file, or both ({@link #chain}).
 *
 * @author Niklas Gröne
 */
public interface TemplateSource {

    /** The template in exactly this revision, or null. */
    TemplateDef get(TemplateRef ref);

    /** The newest revision known of the template with this id, or null. */
    TemplateDef latest(String id);

    /** Tries the sources in order. */
    static TemplateSource chain(TemplateSource... sources) {
        List<TemplateSource> l = new ArrayList<>();
        for (TemplateSource s : sources) if (s != null) l.add(s);
        return new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) {
                for (TemplateSource s : l) {
                    TemplateDef t = s.get(ref);
                    if (t != null) return t;
                }
                return null;
            }

            @Override public TemplateDef latest(String id) {
                TemplateDef best = null;
                for (TemplateSource s : l) {
                    TemplateDef t = s.latest(id);
                    if (t != null && (best == null || t.revision > best.revision)) best = t;
                }
                return best;
            }
        };
    }

    /** The dependencies embedded in {@code t} (and, recursively, in them). */
    static TemplateSource embeddedIn(TemplateDef t) {
        return new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) {
                return find(t, ref.key());
            }

            private TemplateDef find(TemplateDef in, String key) {
                if (in == null) return null;
                if (key.equals(in.id + "@" + in.revision)) return in;
                TemplateDef d = in.dependencies.get(key);
                if (d != null) return d;
                for (TemplateDef dep : in.dependencies.values()) {
                    TemplateDef f = find(dep, key);
                    if (f != null) return f;
                }
                return null;
            }

            @Override public TemplateDef latest(String id) {
                return Templates.newest(Templates.closure(t, this), id);
            }
        };
    }

    /** The templates of a rule set. */
    static TemplateSource of(RuleSystem rules) {
        return new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) {
                return rules.template(ref);
            }

            @Override public TemplateDef latest(String id) {
                return Templates.newest(rules.templates.values(), id);
            }
        };
    }
}
