package layout.template.search;

import layout.template.apply.InstanceKeys;
import layout.template.apply.InstancePlan;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.Templates;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One search for where templates fit, run in the background on a {@link GraphSnapshot}: it never changes the graph or
 * the constraints and never calls the layout solver. Results reach the Swing thread in portions
 * ({@link Listener#delivered}); the run can be cancelled and stops at the limits of {@link TemplateLimits}, and says
 * whether it is complete, limited or cancelled. Each run has an id; a GUI keeps only the results of its newest run.
 *
 * @author Niklas Gröne
 */
public final class SearchRun {

    public enum State {
        RUNNING("Searching…"), COMPLETE("Search complete"), LIMITED("Search limited"), CANCELLED("Search cancelled"),
        FAILED("Search failed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Receives the results on the Swing thread. */
    public interface Listener {
        /** New matches, and matches that got another variant. */
        void delivered(SearchRun run, List<Match> added, List<Match> changed);

        void finished(SearchRun run);
    }

    /** Search options. */
    public static final class Options {
        /** Also check the semantic conditions ("Structure and semantics"); false: "Structure only". */
        public final boolean semantics;
        /** Exact structure within the match: no extra edges between matched nodes. */
        public final boolean exact;
        /** Distinct matches this run collects at most. */
        public final int maxMatches;

        public Options(boolean semantics, boolean exact, int maxMatches) {
            this.semantics = semantics;
            this.exact = exact;
            this.maxMatches = Math.max(1, maxMatches);
        }
    }

    private static final AtomicLong IDS = new AtomicLong();

    public final long id = IDS.incrementAndGet();
    public final GraphSnapshot snapshot;
    public final List<SearchTask> tasks;
    public final Options options;
    private final AtomicBoolean cancel = new AtomicBoolean();

    // ---- read on the Swing thread after delivery
    private volatile State state = State.RUNNING;
    private volatile String limitMessage;
    private volatile boolean limitedByMatches;
    private volatile long assignments;
    private volatile long steps;
    private volatile long durationNanos;
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private final List<Match> matches = new ArrayList<>();   // Swing thread only

    public SearchRun(GraphSnapshot snapshot, List<SearchTask> tasks, Options options) {
        this.snapshot = snapshot;
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        this.options = options;
    }

    public State state() { return state; }

    /** Why the run stopped early (limit), or null. */
    public String limitMessage() { return limitMessage; }

    public long assignments() { return assignments; }

    /** Partial assignments the run tried (candidate tests; at most {@link TemplateLimits#maxSteps}). */
    public long steps() { return steps; }

    /** True if the run stopped because it had collected {@link Options#maxMatches} matches (more may exist). */
    public boolean isLimitedByMatches() { return limitedByMatches; }

    public long durationMillis() { return durationNanos / 1_000_000; }

    public List<String> notes() {
        synchronized (notes) {
            return new ArrayList<>(notes);
        }
    }

    /** The matches delivered so far (Swing thread). */
    public List<Match> matches() {
        return Collections.unmodifiableList(matches);
    }

    public void cancel() {
        cancel.set(true);
    }

    /** Starts the run in a background thread. */
    public void start(Listener listener) {
        Thread t = new Thread(() -> runSearch(listener), "Template search " + id);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * Runs the search in the calling thread (tests, and a layout step that searches synchronously); deliveries still go
     * through the listener. On the event thread the portions and the end are delivered at once, in order, so
     * {@link #matches()} is complete when this returns; on any other thread they are posted to the event thread as
     * {@link #start} does (wait for them with {@code SwingUtilities.invokeAndWait}).
     */
    public void runNow(Listener listener) {
        runSearch(listener);
    }

    /** Runs {@code r} on the event thread: now if this is it (see {@link #runNow}), else later. */
    private static void onEventThread(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    // ================================================================== background

    private void runSearch(Listener listener) {
        long start = System.nanoTime();
        Collector collector = new Collector(listener);
        Enumerator.Budget budget = new Enumerator.Budget(TemplateLimits.maxSteps);
        State end = State.COMPLETE;
        try {
            // a note (a component cut off) limits the run but does not stop it: the later tasks are searched as well
            for (int ti = 0; ti < tasks.size() && end == State.COMPLETE; ti++) {
                SearchTask task = tasks.get(ti);
                Enumerator en = new Enumerator(snapshot, options.semantics, options.exact, cancel, budget);
                collector.task = task;
                collector.taskIndex = ti;
                boolean done = en.enumerate(task.template, task.source, collector::accept);
                notes.addAll(en.notes);
                assignments += en.assignments;
                if (!done && budget.spent() && collector.limit == null) {
                    collector.limit = "Search stopped after " + fmt(TemplateLimits.maxSteps) + " partial assignments; "
                            + collector.count + " matches found so far.";
                }
                if (!done) end = cancel.get() && collector.limit == null ? State.CANCELLED : State.LIMITED;
            }
            if (end == State.COMPLETE && !notes.isEmpty()) end = State.LIMITED;
        } catch (Throwable t) {
            end = State.FAILED;
            notes.add("The search failed: " + t);
        }
        if (end == State.LIMITED && collector.limit == null && !notes.isEmpty()) limitMessage = notes.get(0);
        else if (collector.limit != null) limitMessage = collector.limit;
        steps = budget.used();
        durationNanos = System.nanoTime() - start;
        collector.flush(true);
        State fin = end;
        onEventThread(() -> {
            state = fin;
            listener.finished(this);
        });
    }

    /** Groups assignments into matches and variants and hands them over in portions. */
    private final class Collector {
        final Listener listener;
        SearchTask task;
        int taskIndex;
        String limit;
        final Map<String, Builder> byKey = new HashMap<>();
        int count;
        long rawAssignments;
        final List<Builder> fresh = new ArrayList<>();
        final List<Object[]> newVariants = new ArrayList<>();   // {Builder, Variant}
        final List<Builder> countChanged = new ArrayList<>();
        long lastFlush = System.nanoTime();

        Collector(Listener listener) {
            this.listener = listener;
        }

        boolean accept(FlatMapping m) {
            if (++rawAssignments > TemplateLimits.maxAssignments) {
                limit = "Search stopped after " + fmt(TemplateLimits.maxAssignments) + " assignments (many symmetric ones); "
                        + count + " matches found so far.";
                return false;
            }
            String key = taskIndex + ":" + m.nodeSetKey();
            String sig = signature(m);
            Builder b = byKey.get(key);
            if (b == null) {
                if (count >= options.maxMatches) {
                    limit = fmt(options.maxMatches) + " matches found, search limited.";
                    limitedByMatches = true;
                    return false;
                }
                b = new Builder(++count, task, variant(m, sig));
                byKey.put(key, b);
                fresh.add(b);
            } else {
                Match.Variant known = b.bySignature.get(sig);
                if (known != null) {
                    b.symmetric.merge(sig, 1, Integer::sum);
                    if (!countChanged.contains(b)) countChanged.add(b);
                } else if (b.variantCount >= TemplateLimits.maxVariants) {
                    b.more++;
                    if (!countChanged.contains(b)) countChanged.add(b);
                } else {
                    Match.Variant v = variant(m, sig);
                    b.bySignature.put(sig, v);
                    b.symmetric.put(sig, 1);
                    b.variantCount++;
                    newVariants.add(new Object[]{b, v});
                }
            }
            flush(false);
            return true;
        }

        Match.Variant variant(FlatMapping m, String sig) {
            Map<String, Node> nodes = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : m.nodes.entrySet()) nodes.put(e.getKey(), snapshot.nodes[e.getValue()]);
            Map<String, Edge> edges = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : m.edges.entrySet()) if (e.getValue() >= 0) edges.put(e.getKey(), snapshot.edges[e.getValue()]);
            Map<String, Edge> links = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : m.links.entrySet()) if (e.getValue() >= 0) links.put(e.getKey(), snapshot.edges[e.getValue()]);
            return new Match.Variant(nodes, edges, links, sig, explain(task, m));
        }

        /**
         * The application an assignment leads to (see {@link InstanceKeys#application}), on graph node and edge
         * indices: assignments with equal signatures are symmetric, applying either gives the same result.
         */
        String signature(FlatMapping m) {
            return InstanceKeys.application(task.plan, id -> {
                Integer n = m.nodes.get(id);
                if (n != null) return "N" + n;
                Integer e = m.edges.get(id);
                return e != null ? "E" + e : "?" + id;
            }, q -> {
                Integer n = m.nodes.get(q);
                return n == null ? null : n.longValue();
            });
        }

        void flush(boolean last) {
            long now = System.nanoTime();
            if (!last && fresh.size() < TemplateLimits.chunkSize && (now - lastFlush) / 1_000_000 < TemplateLimits.chunkMillis) return;
            lastFlush = now;
            if (fresh.isEmpty() && newVariants.isEmpty() && countChanged.isEmpty()) return;
            List<Builder> f = new ArrayList<>(fresh);
            List<Object[]> nv = new ArrayList<>(newVariants);
            Map<Builder, Map<String, Integer>> counts = new HashMap<>();
            Map<Builder, Integer> more = new HashMap<>();
            for (Builder b : countChanged) {
                counts.put(b, new HashMap<>(b.symmetric));
                more.put(b, b.more);
            }
            fresh.clear();
            newVariants.clear();
            countChanged.clear();
            onEventThread(() -> {
                List<Match> added = new ArrayList<>(), changed = new ArrayList<>();
                for (Builder b : f) {
                    b.match = new Match(SearchRun.this, b.number, b.task, b.first);
                    matches.add(b.match);
                    added.add(b.match);
                }
                for (Object[] o : nv) {
                    Builder b = (Builder) o[0];
                    b.match.addVariant((Match.Variant) o[1]);
                    if (!added.contains(b.match) && !changed.contains(b.match)) changed.add(b.match);
                }
                for (Map.Entry<Builder, Map<String, Integer>> e : counts.entrySet()) {
                    Match mt = e.getKey().match;
                    for (Match.Variant v : mt.variants()) {
                        Integer c = e.getValue().get(v.signature);
                        if (c != null) v.symmetric = c;
                    }
                    mt.moreVariants = more.get(e.getKey());
                    if (!added.contains(mt) && !changed.contains(mt)) changed.add(mt);
                }
                listener.delivered(SearchRun.this, added, changed);
            });
        }
    }

    /** A match being collected (background) and the match handed out for it (Swing thread). */
    private static final class Builder {
        final int number;
        final SearchTask task;
        final Match.Variant first;
        final Map<String, Match.Variant> bySignature = new HashMap<>();
        final Map<String, Integer> symmetric = new HashMap<>();
        int variantCount = 1;
        int more;
        Match match;

        Builder(int number, SearchTask task, Match.Variant first) {
            this.number = number;
            this.task = task;
            this.first = first;
            bySignature.put(first.signature, first);
            symmetric.put(first.signature, 1);
        }
    }

    // ================================================================== explanations

    /** Which graph nodes and edges take which part, and why the template fits there. */
    private String explain(SearchTask task, FlatMapping m) {
        StringBuilder sb = new StringBuilder();
        TemplateDef t = task.template;
        Map<String, PatternNode> pn = Templates.flatNodes(t, task.source);
        Map<String, PatternEdge> pe = Templates.flatEdges(t, task.source);
        Map<String, List<String>> byPart = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : m.nodes.entrySet()) {
            String part = task.plan.partOfNode.getOrDefault(e.getKey(), "");
            PatternNode p = pn.get(e.getKey());
            String role = p == null ? "" : p.displayName();
            byPart.computeIfAbsent(part, k -> new ArrayList<>()).add("'" + name(e.getValue()) + "' as " + role);
        }
        for (Map.Entry<String, List<String>> e : byPart.entrySet()) {
            if (!e.getKey().isEmpty()) {
                InstancePlan.Part part = task.plan.parts.get(e.getKey());
                sb.append(String.join(" › ", task.plan.partNames(e.getKey())))
                        .append(part == null ? "" : " (" + part.templateName + ")").append(": ");
            } else {
                sb.append("Nodes: ");
            }
            sb.append(String.join(", ", e.getValue())).append('\n');
        }
        int edges = 0;
        StringBuilder es = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.edges.entrySet()) {
            if (e.getValue() < 0) continue;
            edges++;
            if (edges <= 6) {
                int ge = e.getValue();
                PatternEdge p = pe.get(e.getKey());
                if (es.length() > 0) es.append(", ");
                es.append(name(snapshot.src[ge])).append(snapshot.directed[ge] ? " → " : " – ").append(name(snapshot.dst[ge]));
                String role = snapshot.edgeValue(Condition.SBGN_ROLE, ge);
                if (!role.isEmpty()) es.append(" (").append(role).append(')');
                if (p != null && !p.directed) es.append(" [either direction]");
            }
        }
        if (edges > 0) sb.append("Edges: ").append(es).append(edges > 6 ? ", … (" + edges + " in all)" : "").append('\n');
        if (t.isComposite()) {
            for (StructureLink l : t.links) {
                Integer ge = m.links.get(l.id);
                ComponentUse a = t.component(l.fromComponent), b = t.component(l.toComponent);
                String from = portName(task, a, l.fromPort), to = portName(task, b, l.toPort);
                if (l.type == StructureLink.Type.SAME_NODE) {
                    sb.append("Shared: ").append(from).append(" = ").append(to).append('\n');
                } else if (ge != null && ge >= 0) {
                    sb.append("Link: ").append(from).append(" '").append(name(snapshot.src[ge])).append("' → ").append(to)
                            .append(" '").append(name(snapshot.dst[ge])).append("'");
                    String role = snapshot.edgeValue(Condition.SBGN_ROLE, ge);
                    if (!role.isEmpty()) sb.append(" via ").append(role);
                    sb.append('\n');
                }
            }
        }
        sb.append(options.semantics ? "Structure and semantic conditions hold." : "Structure matches (semantics not checked).");
        if (options.exact) sb.append(" Exact structure: no other edges between these nodes.");
        return sb.toString();
    }

    private static String portName(SearchTask task, ComponentUse c, String port) {
        if (c == null) return "?";
        TemplateDef inner = Templates.resolve(c, task.source);
        Port p = inner == null ? null : inner.port(port);
        return c.name + "." + (p == null ? port : p.name);
    }

    private String name(int node) {
        String l = snapshot.labels[node];
        if (l == null || l.isEmpty()) {
            String role = snapshot.role(node);
            return role.isEmpty() ? "node " + node : role;
        }
        return l.length() > 28 ? l.substring(0, 27) + "…" : l;
    }

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
