package layout.template.search;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A place in the graph where a template fits: a set of graph nodes with one or more ways ({@link Variant}s) to assign
 * the template's nodes to them. Assignments that lead to exactly the same constraints and placement are counted as one
 * variant (symmetric); assignments that give the nodes other roles are separate variants. A match changes nothing in
 * the graph: created by a search in the background and handed to the Swing thread, it keeps its own state for the GUI
 * there (selected for application, stale after a graph change, invalid, applied, the result of a conflict check).
 *
 * @author Niklas Gröne
 */
public final class Match {

    /** One assignment of the template to the match's nodes. */
    public static final class Variant {
        /** Qualified pattern node id → graph node. */
        public final Map<String, Node> nodes;
        public final Map<String, Edge> edges;
        /** Composite: qualified link id → the graph edge that fulfils it (absent for a shared node). */
        public final Map<String, Edge> links;
        /** Equal for assignments that make the same constraints and placement. */
        public final String signature;
        /** Readable explanation: which nodes and edges take which part, why it fits. */
        public final String explanation;
        /** How many assignments (symmetric ones) lead to this same application. */
        int symmetric = 1;

        Variant(Map<String, Node> nodes, Map<String, Edge> edges, Map<String, Edge> links, String signature,
                String explanation) {
            this.nodes = Collections.unmodifiableMap(nodes);
            this.edges = Collections.unmodifiableMap(edges);
            this.links = Collections.unmodifiableMap(links);
            this.signature = signature;
            this.explanation = explanation;
        }

        public int symmetricCount() {
            return symmetric;
        }
    }

    /** Where a match stands: found by the search, outdated by a graph change, no longer fitting, or applied. */
    public enum State {
        FOUND, STALE, INVALID, APPLIED
    }

    public final SearchRun run;
    /** 1, 2, … in the order the run found them. */
    public final int number;
    public final SearchTask task;
    private final List<Variant> variants = new ArrayList<>();
    private final Set<Node> nodes;
    /** Assignments beyond {@link TemplateLimits#maxVariants} that were not kept. */
    int moreVariants;
    private int chosen;

    // ---- state kept by the GUI (Swing thread)
    public boolean selected;
    public State state = State.FOUND;
    /** Why the match is invalid, or null. */
    public String reason;
    public MatchCheck check = MatchCheck.NOT_CHECKED;
    /** The instance made from this match, or null. */
    public Object instance;

    Match(SearchRun run, int number, SearchTask task, Variant first) {
        this.run = run;
        this.number = number;
        this.task = task;
        this.variants.add(first);
        this.nodes = Collections.unmodifiableSet(new LinkedHashSet<>(first.nodes.values()));
    }

    void addVariant(Variant v) {
        variants.add(v);
    }

    public List<Variant> variants() {
        return Collections.unmodifiableList(variants);
    }

    public Variant variant() {
        return variants.get(chosen);
    }

    public int chosenIndex() {
        return chosen;
    }

    /** Chooses another assignment (the conflict check must be made again). */
    public void choose(int index) {
        if (index < 0 || index >= variants.size() || index == chosen) return;
        chosen = index;
        check = MatchCheck.NOT_CHECKED;
    }

    public int moreVariants() {
        return moreVariants;
    }

    /** The graph nodes of the match (the same for every variant). */
    public Set<Node> nodes() {
        return nodes;
    }

    /** The graph edges of the chosen variant: the template's edges and, for composites, the joining edges. */
    public Set<Edge> edges() {
        Set<Edge> s = new LinkedHashSet<>(variant().edges.values());
        s.addAll(variant().links.values());
        return s;
    }

    /** Qualified node ids of the chosen variant by component path ("" for a simple template). */
    public Map<String, List<Node>> nodesByPart() {
        Map<String, List<Node>> m = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : variant().nodes.entrySet()) {
            String part = task.plan.partOfNode.getOrDefault(e.getKey(), "");
            m.computeIfAbsent(part, k -> new ArrayList<>()).add(e.getValue());
        }
        return m;
    }

    /** True if it shares a graph node with {@code other}. */
    public boolean overlaps(Match other) {
        if (other == this) return false;
        for (Node n : other.nodes) if (nodes.contains(n)) return true;
        return false;
    }

    /** "#12 Chain" */
    public String title() {
        return "#" + number + " " + task.title();
    }

    @Override
    public String toString() {
        return title();
    }
}
