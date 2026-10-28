package layout.template.model;

/**
 * A named connection point of a template, e.g. "Input", "Output", "Centre". In a pattern template it stands for one
 * pattern node ({@link #node}); in a composite template it passes on a port of one of its components
 * ({@link #component} + {@link #componentPort}), so the composite can be used as a component itself. References use
 * the stable {@link #id}; renaming a port changes only {@link #name}.
 *
 * @author Niklas Gröne
 */
public final class Port {

    public String id;
    public String name;
    /** Pattern template: local id of the node. */
    public String node;
    /** Composite template: the component whose port this one passes on, and that port's id. */
    public String component;
    public String componentPort;

    public Port() { }

    public Port(String id, String name, String node) {
        this.id = id;
        this.name = name;
        this.node = node;
    }

    public Port copy() {
        Port p = new Port(id, name, node);
        p.component = component;
        p.componentPort = componentPort;
        return p;
    }

    @Override
    public String toString() {
        return name;
    }
}
