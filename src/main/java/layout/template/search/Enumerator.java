package layout.template.search;

import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.Templates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enumerates the assignments of a template on a snapshot: a pattern template through the {@link PatternMatcher}, a
 * composite by joining the occurrences of its components (a template used twice is searched once). The join takes the
 * components in the order of most links and fewest occurrences and draws each next one from an index of its occurrences
 * by the graph node of the linked port (edge links: the neighbours across edges of the right direction and role), so
 * only combinations that satisfy a link are formed. Components use different graph nodes unless a
 * {@link StructureLink.Type#SAME_NODE} link lets two ports share one, and every link edge is a separate graph edge.
 *
 * @author Niklas Gröne
 */
final class Enumerator {

    interface Sink {
        /** Return false to stop. */
        boolean accept(FlatMapping m);
    }

    private final GraphSnapshot g;
    private final boolean semantics;
    private final boolean exact;
    private final AtomicBoolean cancel;
    /** Notes for the user (components cut off at the limit, …). */
    final List<String> notes = new ArrayList<>();
    /** Occurrences per template (id@revision), shared by uses of the same template. */
    private final Map<String, List<FlatMapping>> partCache = new HashMap<>();
    long assignments;

    Enumerator(GraphSnapshot g, boolean semantics, boolean exact, AtomicBoolean cancel) {
        this.g = g;
        this.semantics = semantics;
        this.exact = exact;
        this.cancel = cancel;
    }

    /** Runs through every assignment of {@code t}; returns false if stopped (by the sink or cancelled). */
    boolean enumerate(TemplateDef t, TemplateSource source, Sink sink) {
        return enumerate(t, source, sink, 0);
    }

    private boolean enumerate(TemplateDef t, TemplateSource source, Sink sink, int depth) {
        if (depth > Templates.MAX_DEPTH) return true;
        if (!t.isComposite()) return enumeratePattern(t, sink);
        return new Join(t, source, depth).run(sink);
    }

    private boolean enumeratePattern(TemplateDef t, Sink sink) {
        CompiledPattern cp = new CompiledPattern(t);
        PatternMatcher pm = new PatternMatcher(cp, g, semantics, exact, cancel);
        Map<String, Integer> portIndex = new LinkedHashMap<>();
        Map<String, Integer> nodeIndex = new HashMap<>();
        for (int i = 0; i < cp.nodeIds.length; i++) nodeIndex.put(cp.nodeIds[i], i);
        for (Port p : t.ports) if (p.node != null && nodeIndex.containsKey(p.node)) portIndex.put(p.id, nodeIndex.get(p.node));
        return pm.run((nodeMap, edgeMap) -> {
            assignments++;
            Map<String, Integer> nodes = new LinkedHashMap<>(), edges = new LinkedHashMap<>(), ports = new LinkedHashMap<>();
            for (int i = 0; i < nodeMap.length; i++) nodes.put(cp.nodeIds[i], nodeMap[i]);
            for (int i = 0; i < edgeMap.length; i++) edges.put(cp.edgeIds[i], edgeMap[i]);
            for (Map.Entry<String, Integer> e : portIndex.entrySet()) ports.put(e.getKey(), nodeMap[e.getValue()]);
            return sink.accept(new FlatMapping(nodes, edges, new LinkedHashMap<>(), ports));
        });
    }

    /** The occurrences of {@code t} (at most {@link TemplateLimits#maxPartsPerComponent}). */
    private List<FlatMapping> parts(TemplateDef t, TemplateSource source, String useName, int depth) {
        String key = t.id + "@" + t.revision;
        List<FlatMapping> cached = partCache.get(key);
        if (cached != null) return cached;
        List<FlatMapping> l = new ArrayList<>();
        int limit = TemplateLimits.maxPartsPerComponent;
        boolean[] cut = {false};
        enumerate(t, source, m -> {
            if (l.size() >= limit) {
                cut[0] = true;
                return false;
            }
            l.add(m);
            return true;
        }, depth + 1);
        if (cut[0]) notes.add("'" + useName + "' (" + t.name + ") occurs more than " + limit + " times; only the first "
                + limit + " occurrences were combined.");
        partCache.put(key, l);
        return l;
    }

    /** The join of a composite's components. */
    private final class Join {
        final TemplateDef t;
        final List<ComponentUse> comps = new ArrayList<>();
        final Map<String, List<FlatMapping>> parts = new HashMap<>();
        ComponentUse[] order;
        /** The link used to generate candidates of order[k] (null: all occurrences). */
        StructureLink[] driver;
        final Map<String, FlatMapping> chosen = new HashMap<>();
        /** Graph node → components that use it. */
        final Map<Integer, List<String>> owner = new HashMap<>();
        final Map<String, Integer> linkEdges = new LinkedHashMap<>();
        final Set<Integer> usedLinkEdges = new HashSet<>();
        final Map<String, Map<Integer, List<FlatMapping>>> portIndexCache = new HashMap<>();
        boolean stopped;

        Join(TemplateDef t, TemplateSource source, int depth) {
            this.t = t;
            for (ComponentUse c : t.components) {
                TemplateDef inner = Templates.resolve(c, source);
                if (inner == null) continue;
                comps.add(c);
                parts.put(c.id, parts(inner, TemplateSource.chain(source, TemplateSource.embeddedIn(inner)), c.name, depth));
            }
        }

        boolean run(Sink sink) {
            if (comps.isEmpty() || comps.size() != t.components.size()) return true;
            for (ComponentUse c : comps) if (parts.get(c.id).isEmpty()) return true;
            planOrder();
            assign(0, sink);
            return !stopped;
        }

        private List<StructureLink> linksBetween(String a, String b) {
            List<StructureLink> l = new ArrayList<>();
            for (StructureLink s : t.links) {
                if ((a.equals(s.fromComponent) && b.equals(s.toComponent)) || (b.equals(s.fromComponent) && a.equals(s.toComponent))) l.add(s);
            }
            return l;
        }

        private void planOrder() {
            int n = comps.size();
            order = new ComponentUse[n];
            driver = new StructureLink[n];
            Set<String> placed = new HashSet<>();
            for (int k = 0; k < n; k++) {
                ComponentUse best = null;
                int bestLinks = -1;
                for (ComponentUse c : comps) {
                    if (placed.contains(c.id)) continue;
                    int links = 0;
                    for (String p : placed) links += linksBetween(c.id, p).size();
                    if (best == null || links > bestLinks || (links == bestLinks && parts.get(c.id).size() < parts.get(best.id).size())) {
                        best = c;
                        bestLinks = links;
                    }
                }
                order[k] = best;
                for (String p : placed) {
                    for (StructureLink l : linksBetween(best.id, p)) {
                        if (l.type == StructureLink.Type.PATH) continue;
                        if (driver[k] == null || l.type == StructureLink.Type.SAME_NODE) driver[k] = l;
                    }
                }
                placed.add(best.id);
            }
        }

        private void assign(int k, Sink sink) {
            if (stopped) return;
            if (cancel != null && cancel.get()) {
                stopped = true;
                return;
            }
            if (k == order.length) {
                if (!sink.accept(flatten())) stopped = true;
                return;
            }
            ComponentUse c = order[k];
            for (FlatMapping cand : candidates(k)) {
                if (!fits(c, cand)) continue;
                List<String> newLinks = new ArrayList<>();
                if (!links(c, cand, newLinks)) {
                    for (String id : newLinks) usedLinkEdges.remove(linkEdges.remove(id));
                    continue;
                }
                take(c, cand);
                assign(k + 1, sink);
                release(c, cand);
                for (String id : newLinks) {
                    Integer e = linkEdges.remove(id);
                    if (e != null && e >= 0) usedLinkEdges.remove(e);
                }
                if (stopped) return;
            }
        }

        /** The occurrences of order[k] that its driving link allows (all, without one). */
        private List<FlatMapping> candidates(int k) {
            ComponentUse c = order[k];
            StructureLink l = driver[k];
            if (l == null) return parts.get(c.id);
            boolean cIsTo = c.id.equals(l.toComponent);
            String otherComp = cIsTo ? l.fromComponent : l.toComponent;
            String otherPort = cIsTo ? l.fromPort : l.toPort;
            String myPort = cIsTo ? l.toPort : l.fromPort;
            Integer x = chosen.get(otherComp).ports.get(otherPort);
            if (x == null) return new ArrayList<>();
            Map<Integer, List<FlatMapping>> index = portIndex(c.id, myPort);
            if (l.type == StructureLink.Type.SAME_NODE) {
                List<FlatMapping> m = index.get(x);
                return m == null ? new ArrayList<>() : m;
            }
            List<FlatMapping> l2 = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (int e : g.incident[x]) {
                int y = g.other(e, x);
                if (!seen.add(y) || !edgeFitsLink(l, e, cIsTo ? x : y, cIsTo ? y : x)) continue;
                List<FlatMapping> m = index.get(y);
                if (m != null) l2.addAll(m);
            }
            return l2;
        }

        private Map<Integer, List<FlatMapping>> portIndex(String comp, String port) {
            return portIndexCache.computeIfAbsent(comp + "#" + port, k -> {
                Map<Integer, List<FlatMapping>> m = new HashMap<>();
                for (FlatMapping f : parts.get(comp)) {
                    Integer v = f.ports.get(port);
                    if (v != null) m.computeIfAbsent(v, z -> new ArrayList<>()).add(f);
                }
                return m;
            });
        }

        /** Does graph edge {@code e} fulfil link {@code l} from node {@code from} (the link's from port) to {@code to}? */
        private boolean edgeFitsLink(StructureLink l, int e, int from, int to) {
            boolean ends = (g.src[e] == from && g.dst[e] == to) || (g.src[e] == to && g.dst[e] == from);
            if (!ends) return false;
            if (l.direction == StructureLink.Direction.FORWARD && g.directed[e] && !(g.src[e] == from && g.dst[e] == to)) return false;
            if (semantics) {
                for (Condition c : l.edgeConditions) if (!c.test(g.edgeValue(c.attribute, e))) return false;
            }
            return true;
        }

        /** No node of {@code cand} is used by an assigned component, except where a shared-node link allows it. */
        private boolean fits(ComponentUse c, FlatMapping cand) {
            for (int v : new HashSet<>(cand.nodes.values())) {
                List<String> owners = owner.get(v);
                if (owners == null) continue;
                for (String o : owners) if (!sharedAllowed(c.id, cand, o, v)) return false;
            }
            return true;
        }

        private boolean sharedAllowed(String comp, FlatMapping cand, String other, int v) {
            for (StructureLink l : linksBetween(comp, other)) {
                if (l.type != StructureLink.Type.SAME_NODE) continue;
                String myPort = comp.equals(l.fromComponent) ? l.fromPort : l.toPort;
                String otherPort = comp.equals(l.fromComponent) ? l.toPort : l.fromPort;
                Integer a = cand.ports.get(myPort), b = chosen.get(other).ports.get(otherPort);
                if (a != null && b != null && a == v && b == v) return true;
            }
            return false;
        }

        /** Checks every link between {@code c} and the assigned components; records the link edges. */
        private boolean links(ComponentUse c, FlatMapping cand, List<String> added) {
            for (StructureLink l : t.links) {
                boolean mineFrom = c.id.equals(l.fromComponent), mineTo = c.id.equals(l.toComponent);
                if (!mineFrom && !mineTo) continue;
                String other = mineFrom ? l.toComponent : l.fromComponent;
                if (!chosen.containsKey(other)) continue;
                if (l.type == StructureLink.Type.PATH) return false;
                Integer from = (mineFrom ? cand : chosen.get(other)).ports.get(l.fromPort);
                Integer to = (mineTo ? cand : chosen.get(other)).ports.get(l.toPort);
                if (from == null || to == null) return false;
                if (l.type == StructureLink.Type.SAME_NODE) {
                    if (!from.equals(to)) return false;
                    linkEdges.put(l.id, -1);
                    added.add(l.id);
                    continue;
                }
                int found = -1;
                for (int e : g.incident[from]) {
                    if (usedLinkEdges.contains(e)) continue;
                    if (edgeFitsLink(l, e, from, to)) {
                        found = e;
                        break;
                    }
                }
                if (found < 0) return false;
                linkEdges.put(l.id, found);
                usedLinkEdges.add(found);
                added.add(l.id);
            }
            return true;
        }

        private void take(ComponentUse c, FlatMapping cand) {
            chosen.put(c.id, cand);
            for (int v : new HashSet<>(cand.nodes.values())) owner.computeIfAbsent(v, z -> new ArrayList<>()).add(c.id);
        }

        private void release(ComponentUse c, FlatMapping cand) {
            chosen.remove(c.id);
            for (int v : new HashSet<>(cand.nodes.values())) {
                List<String> o = owner.get(v);
                if (o != null) {
                    o.remove(c.id);
                    if (o.isEmpty()) owner.remove(v);
                }
            }
        }

        private FlatMapping flatten() {
            assignments++;
            Map<String, Integer> nodes = new LinkedHashMap<>(), edges = new LinkedHashMap<>(), links = new LinkedHashMap<>(),
                    ports = new LinkedHashMap<>();
            for (ComponentUse c : t.components) {
                FlatMapping f = chosen.get(c.id);
                for (Map.Entry<String, Integer> e : f.nodes.entrySet()) nodes.put(c.id + Templates.SEP + e.getKey(), e.getValue());
                for (Map.Entry<String, Integer> e : f.edges.entrySet()) edges.put(c.id + Templates.SEP + e.getKey(), e.getValue());
                for (Map.Entry<String, Integer> e : f.links.entrySet()) links.put(c.id + Templates.SEP + e.getKey(), e.getValue());
            }
            links.putAll(linkEdges);
            for (Port p : t.ports) {
                FlatMapping f = chosen.get(p.component);
                Integer v = f == null ? null : f.ports.get(p.componentPort);
                if (v != null) ports.put(p.id, v);
            }
            return new FlatMapping(nodes, edges, links, ports);
        }
    }
}
