package layout.template.search;

import constraints.Priority;
import layout.template.apply.InstancePlan;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One template to search for in a run: the template in a fixed revision, where its components come from, the plan of
 * what applying it makes and, for a rule of a rule set, the rule's name (its priority goes into the plan).
 *
 * @author Niklas Gröne
 */
public final class SearchTask {

    public final TemplateDef template;
    public final TemplateSource source;
    public final InstancePlan plan;
    /** Name of the rule (rule sets), or null. */
    public final String ruleName;

    public SearchTask(TemplateDef template, TemplateSource source, String ruleName, Priority rulePriority) {
        this.template = template;
        this.source = taken(template, TemplateSource.chain(source, TemplateSource.embeddedIn(template)));
        this.plan = InstancePlan.build(template, this.source, rulePriority);
        this.ruleName = ruleName;
    }

    /**
     * The templates {@code t} uses, taken from {@code source} now: a run reads them in the background, where a library
     * that is read again meanwhile must not be touched.
     */
    private static TemplateSource taken(TemplateDef t, TemplateSource source) {
        Map<String, TemplateDef> byKey = new LinkedHashMap<>();
        for (TemplateDef d : Templates.closure(t, source)) byKey.put(d.id + "@" + d.revision, d);
        return new TemplateSource() {
            @Override public TemplateDef get(TemplateRef ref) {
                return ref == null ? null : byKey.get(ref.key());
            }

            @Override public TemplateDef latest(String id) {
                TemplateDef best = null;
                for (TemplateDef d : byKey.values()) {
                    if (d.id.equals(id) && (best == null || d.revision > best.revision)) best = d;
                }
                return best;
            }
        };
    }

    /** "Chain" or "Rule 'Signalling' (Chain)". */
    public String title() {
        return ruleName == null ? template.name : ruleName + " (" + template.name + ")";
    }
}
