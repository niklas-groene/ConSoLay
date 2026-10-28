package layout.template.model;

import constraints.Priority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A layout rule set: several simple and composite templates bundled as named rules, each switched on or off and with a
 * priority for the constraints it makes. The file embeds every template the rules need (with their dependencies), so a
 * rule set works after loading without the library or graph it was made with. Loading or editing a rule set never
 * changes a graph: rules find matches, the user reviews them and applies the chosen ones.
 *
 * @author Niklas Gröne
 */
public final class RuleSystem {

    /** One rule: a template used under a name, with a switch and a priority. */
    public static final class Rule {
        public String id;
        public String name;
        public String description = "";
        public boolean enabled = true;
        /** Priority of the constraints this rule makes, or null to keep the template's own. */
        public Priority priority;
        public TemplateRef template;

        public Rule() { }

        public Rule(String id, String name, TemplateRef template) {
            this.id = id;
            this.name = name;
            this.template = template;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final int FORMAT_VERSION = 1;

    public String id = "rules-" + UUID.randomUUID();
    public int revision = 1;
    public String name = "Untitled rule set";
    public String description = "";
    public final List<Rule> rules = new ArrayList<>();
    /** Every template the rules use, and theirs, keyed by id@revision. */
    public final Map<String, TemplateDef> templates = new LinkedHashMap<>();
    public final Map<String, String> unknownFields = new LinkedHashMap<>();

    public String nextRuleId() {
        List<String> ids = new ArrayList<>();
        for (Rule r : rules) ids.add(r.id);
        return TemplateDef.next("rule", ids);
    }

    public TemplateDef template(TemplateRef ref) {
        return ref == null ? null : templates.get(ref.key());
    }
}
