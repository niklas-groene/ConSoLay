package layout.mental_map;

import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link NextState#apply} matches the edges between two labels one by one: parallel and antiparallel arcs of the state
 * all arrive, surplus live edges go, and a live edge in the other direction still stands for a state edge. Needs no
 * natives; run with {@code ant test}.
 */
public class NextStateTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A graph with the nodes {@code labels} and an edge per "S>T" in {@code edges}. */
    private static Graph graph(String labels, String... edges) {
        Graph g = new AdjListGraph();
        int x = 0;
        for (String l : labels.split(",")) {
            Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(x += 100, 100));
            AttributeHelper.setLabel(n, l);
        }
        for (String e : edges) {
            String[] ends = e.split(">");
            g.addEdge(byLabel(g, ends[0]), byLabel(g, ends[1]), true,
                    AttributeHelper.getDefaultGraphicsAttributeForEdge(Color.BLACK, Color.BLACK, true));
        }
        return g;
    }

    private static Node byLabel(Graph g, String label) {
        for (Node n : g.getNodes()) if (label.equals(AttributeHelper.getLabel(n, ""))) return n;
        throw new IllegalArgumentException(label);
    }

    private static List<String> edges(Graph g) {
        List<String> result = new ArrayList<>();
        for (Edge e : g.getEdges()) {
            result.add(AttributeHelper.getLabel(e.getSource(), "") + ">" + AttributeHelper.getLabel(e.getTarget(), ""));
        }
        Collections.sort(result);
        return result;
    }

    @Test
    public void antiparallelArcsBothArrive() {
        Graph live = graph("A,B");
        NextState.Change change = NextState.apply(live, graph("A,B", "A>B", "B>A"));
        assertEquals("[A>B, B>A]", edges(live).toString());
        assertEquals(2, change.edgesAdded);
        assertEquals(0, change.edgesRemoved);
    }

    @Test
    public void parallelArcsCountOneByOne() {
        Graph live = graph("A,B", "A>B");
        NextState.Change change = NextState.apply(live, graph("A,B", "A>B", "A>B", "A>B"));
        assertEquals("[A>B, A>B, A>B]", edges(live).toString());
        assertEquals(2, change.edgesAdded);
    }

    @Test
    public void surplusEdgesGoThoseInTheStatesDirectionStay() {
        Graph live = graph("A,B", "A>B", "B>A", "A>B");
        NextState.Change change = NextState.apply(live, graph("A,B", "B>A"));
        assertEquals("[B>A]", edges(live).toString());
        assertEquals(2, change.edgesRemoved);
        assertEquals(0, change.edgesAdded);
    }

    @Test
    public void anEdgeInTheOtherDirectionStillMatches() {
        Graph live = graph("A,B,C", "B>A", "B>C");
        NextState.Change change = NextState.apply(live, graph("A,B", "A>B"));
        assertEquals("[B>A]", edges(live).toString());
        assertEquals(0, change.edgesAdded);
        assertEquals(1, change.edgesRemoved);
        assertEquals(1, change.nodesRemoved);
    }
}
