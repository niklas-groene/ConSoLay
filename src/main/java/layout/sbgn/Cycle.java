package layout.sbgn;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.ArrayList;

/** A cycle of the skeleton selected by {@link CycleSelector}: its edges in ring order and the node it starts at. */
public class Cycle {
    private final Node startingPoint;
    private final ArrayList<Edge> edges;

    public Cycle(Node startingPoint, ArrayList<Edge> edges)
    {
        this.startingPoint = startingPoint;
        this.edges = edges;
    }

    public ArrayList<Edge> getEdges() {
        return edges;
    }

    public Node getStartingPoint() {
        return startingPoint;
    }
}
