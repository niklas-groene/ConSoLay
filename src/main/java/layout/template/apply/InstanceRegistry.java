package layout.template.apply;

import org.graffiti.graph.Graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The applied template instances per graph. Knows an instance by id and by its application key, and tells listeners
 * (the GUI) about changes.
 *
 * @author Niklas Gröne
 */
public final class InstanceRegistry {

    private static final Map<Graph, List<TemplateInstance>> BY_GRAPH = new WeakHashMap<>();
    private static final Map<String, TemplateInstance> BY_ID = new HashMap<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private InstanceRegistry() { }

    /** The instances on {@code g}, oldest first. */
    public static synchronized List<TemplateInstance> of(Graph g) {
        List<TemplateInstance> l = g == null ? null : BY_GRAPH.get(g);
        return l == null ? Collections.<TemplateInstance>emptyList() : new ArrayList<>(l);
    }

    /** The instance on {@code g} with this application key, or null. */
    public static synchronized TemplateInstance find(Graph g, String key) {
        for (TemplateInstance i : of(g)) if (i.key.equals(key)) return i;
        return null;
    }

    public static synchronized TemplateInstance byId(String id) {
        return BY_ID.get(id);
    }

    static synchronized void add(TemplateInstance i) {
        BY_GRAPH.computeIfAbsent(i.graph, k -> new ArrayList<>()).add(i);
        BY_ID.put(i.id, i);
    }

    static synchronized void remove(TemplateInstance i) {
        List<TemplateInstance> l = BY_GRAPH.get(i.graph);
        if (l != null) l.remove(i);
        BY_ID.remove(i.id);
    }

    /** Forgets the instances of graphs not in {@code open} (closed sessions), so a closed graph can be collected. */
    public static synchronized void retainOnly(Collection<Graph> open) {
        BY_GRAPH.keySet().removeIf(g -> !open.contains(g));
        BY_ID.values().removeIf(i -> !open.contains(i.graph));
    }

    public static synchronized void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void fire() {
        List<Runnable> copy;
        synchronized (InstanceRegistry.class) { copy = new ArrayList<>(LISTENERS); }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the others
            }
        }
    }
}
