package layout.template.model;

import constraints.Priority;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One named use of another template inside a composite template, e.g. the chain "Source" and the chain "Target" (the
 * same template twice, in two roles). The settings here belong to this use only; they never change the reused template
 * or its other uses.
 *
 * @author Niklas Gröne
 */
public final class ComponentUse {

    /** Local id in the composite, e.g. "c2". */
    public String id;
    /** The role of this use, e.g. "Source". */
    public String name;
    /** The template used, with the revision it is used in. */
    public TemplateRef ref;
    /** Position of the component's box in the component editor. */
    public double x, y;
    /** Setting of this use: apply the component's own layout constraints (false: only its structure counts). */
    public boolean includeConstraints = true;
    /** Setting of this use: priority for all of its constraints, or null to keep theirs. */
    public Priority priority;
    /** Setting of this use: local ids of the component's constraints that start switched off. */
    public final Set<String> disabledConstraints = new LinkedHashSet<>();

    public ComponentUse() { }

    public ComponentUse(String id, String name, TemplateRef ref) {
        this.id = id;
        this.name = name;
        this.ref = ref;
    }

    public ComponentUse copy() {
        ComponentUse c = new ComponentUse(id, name, ref);
        c.x = x;
        c.y = y;
        c.includeConstraints = includeConstraints;
        c.priority = priority;
        c.disabledConstraints.addAll(disabledConstraints);
        return c;
    }

    @Override
    public String toString() {
        return name;
    }
}
