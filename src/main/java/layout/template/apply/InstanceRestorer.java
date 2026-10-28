package layout.template.apply;

import constraints.Constraint;
import constraints.ConstraintOrigin;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Re-creates applied template instances from what a saved constraint store keeps of them (see
 * {@code layout.store.StoreCodec}): the template reference, the assignment, the key and the switches. A restored
 * instance gets a fresh number and id, and its constraints get their {@link ConstraintOrigin} through {@link #attach}.
 *
 * @author Niklas Gröne
 */
public final class InstanceRestorer {

    private InstanceRestorer() { }

    /**
     * The plan of {@code ref}: built from the template if {@code source} has this revision (components resolved through
     * the source and the template's embedded dependencies), else an empty plan that only names the template.
     */
    public static InstancePlan plan(TemplateRef ref, TemplateSource source) {
        TemplateDef t = source == null ? null : source.get(ref);
        if (t != null) {
            try {
                return InstancePlan.build(t, TemplateSource.chain(source, TemplateSource.embeddedIn(t)), null);
            } catch (RuntimeException ignored) {
                // a template that cannot be planned: fall through to the stub
            }
        }
        TemplateDef stub = TemplateDef.create(ref.name == null ? ref.id : ref.name, TemplateDef.Kind.PATTERN);
        stub.id = ref.id;
        stub.revision = ref.revision;
        return InstancePlan.build(stub, TemplateSource.chain(source), null);
    }

    /** A new instance of a saved application, with the saved switches; not registered yet and without constraints. */
    public static TemplateInstance instance(TemplateRef ref, String ruleName, Graph g, Map<String, Node> nodes,
                                            Map<String, Edge> edges, Map<String, Edge> links, String key, InstancePlan plan,
                                            boolean on, Collection<String> partsOff) {
        TemplateInstance inst = new TemplateInstance(ref, ruleName, g, nodes, edges, links, key, plan);
        inst.setOn(on);
        if (partsOff != null) for (String p : partsOff) inst.setPartOn(p, false);
        return inst;
    }

    /**
     * Makes {@code c} one of the instance's constraints: its origin names the instance, the path through the template
     * and the definition, and its switch follows the instance and the part {@code part} ("" for the template itself).
     */
    public static void attach(TemplateInstance inst, Constraint c, String part, List<String> path, String specId) {
        String p = part == null ? "" : part;
        List<String> path1 = path == null || path.isEmpty() ? Collections.singletonList(inst.template.name) : path;
        c.setOrigin(new ConstraintOrigin(inst.id, inst.label(), inst.template.id, inst.template.revision, path1, specId,
                () -> inst.isPartOn(p)));
        inst.addConstraint(c);
        inst.setPart(c, p);
    }

    /** Puts a restored instance into the registry (call {@link InstanceRegistry#fire()} once after all of them). */
    public static void register(TemplateInstance inst) {
        if (InstanceRegistry.byId(inst.id) == null) InstanceRegistry.add(inst);
    }

    /** The component parts of {@code inst} that are switched off, sorted. */
    public static List<String> partsOff(TemplateInstance inst) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : inst.partSwitches().entrySet()) if (Boolean.FALSE.equals(e.getValue())) l.add(e.getKey());
        Collections.sort(l);
        return l;
    }
}
