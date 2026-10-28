package layout.sbgn;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.ArrayList;

/**
 * A process node of a PD map with its neighbours grouped by the role of the connecting arc, and the axis and side
 * that its substrates / products and its two reversible sides are placed on.
 */
public class ProcessComponent {
    Node processNode;
    ArrayList<Node> products;
    ArrayList<Node> substrates;
    ArrayList<Node> inhibitors;
    ArrayList<Node> unknown;
    ArrayList<Node> revNodesLHS;
    ArrayList<Node> revNodesRHS;
    // -1: undefined, 0: horizontal, 1: vertical
    private int revOrientation;
    // true: the LHS nodes go to the right-hand (lower) side, the RHS nodes to the left-hand (upper) side
    private boolean revSwitch;

    // -1: undefined, 0: horizontal, 1: vertical
    private int prodSubOrientation;
    // true: the products go to the left-hand (upper) side, the substrates to the right-hand (lower) side
    private boolean prodSubSwitch;

    public ProcessComponent(Node processNode)
    {
        this.processNode = processNode;
        this.products = new ArrayList<>();
        this.substrates = new ArrayList<>();
        this.inhibitors = new ArrayList<>();
        this.unknown = new ArrayList<>();
        this.revNodesLHS = new ArrayList<>();
        this.revNodesRHS = new ArrayList<>();
        this.revOrientation = -1;
        this.prodSubOrientation = -1;

        processComponent();
    }

    private void processComponent()
    {
        for (Edge e : processNode.getEdges())
        {
            String role = ConstraintSBGNLayout.getSBGNRole(e);
            Node other = e.getSource() == processNode ? e.getTarget() : e.getSource();
            if (role.contains("PRODUCTION")) products.add(other);
            else if (role.contains("CONSUMPTION")) substrates.add(other);
            else if (role.contains("CATALYSIS")) inhibitors.add(other);
            else if (role.contains("REVERSIBLELHS")) revNodesLHS.add(other);
            else if (role.contains("REVERSIBLERHS")) revNodesRHS.add(other);
            else unknown.add(other);
        }
    }

    public void setRevOrientation(int revOrientation)
    {
        this.revOrientation = revOrientation;
    }

    public int getRevOrientation(){
        return revOrientation;
    }

    public boolean isRevSwitch() {
        return revSwitch;
    }

    public void setRevSwitch(boolean revSwitch) {
        this.revSwitch = revSwitch;
    }

    public boolean isProdSubSwitch() {
        return prodSubSwitch;
    }

    public void setProdSubSwitch(boolean prodSubSwitch) {
        this.prodSubSwitch = prodSubSwitch;
    }

    public void setProdSubOrientation(int prodSubOrientation) {
        this.prodSubOrientation = prodSubOrientation;
    }

    public int getProdSubOrientation() {
        return prodSubOrientation;
    }
}
