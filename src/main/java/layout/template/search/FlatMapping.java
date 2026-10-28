package layout.template.search;

import java.util.Collections;
import java.util.Map;
import java.util.TreeSet;

/**
 * One complete assignment of a template to a graph snapshot, by index: qualified pattern node id → graph node,
 * qualified pattern edge id → graph edge, and for composites the graph edge that fulfils each structural link
 * (qualified link id → edge, -1 for a shared node) and the graph node of each of the template's ports.
 *
 * @author Niklas Gröne
 */
final class FlatMapping {

    final Map<String, Integer> nodes;
    final Map<String, Integer> edges;
    final Map<String, Integer> links;
    final Map<String, Integer> ports;

    FlatMapping(Map<String, Integer> nodes, Map<String, Integer> edges, Map<String, Integer> links, Map<String, Integer> ports) {
        this.nodes = Collections.unmodifiableMap(nodes);
        this.edges = Collections.unmodifiableMap(edges);
        this.links = Collections.unmodifiableMap(links);
        this.ports = Collections.unmodifiableMap(ports);
    }

    /** The graph nodes used, sorted: two assignments with the same key cover the same nodes. */
    String nodeSetKey() {
        return new TreeSet<>(nodes.values()).toString();
    }
}
