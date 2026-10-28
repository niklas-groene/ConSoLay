package layout.template.search;

import constraints.Priority;
import layout.template.apply.InstancePlan;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;

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
        this.source = TemplateSource.chain(source, TemplateSource.embeddedIn(template));
        this.plan = InstancePlan.build(template, this.source, rulePriority);
        this.ruleName = ruleName;
    }

    /** "Chain" or "Rule 'Signalling' (Chain)". */
    public String title() {
        return ruleName == null ? template.name : ruleName + " (" + template.name + ")";
    }
}
