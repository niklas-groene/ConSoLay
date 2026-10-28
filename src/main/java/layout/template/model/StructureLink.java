package layout.template.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A structural condition between two components of a composite template: something the matched parts of the graph must
 * have in common for the composite to match. It is a search condition, not a layout wish (see {@link LayoutRelation}).
 * <p>
 * {@link Type#EDGE}: an edge of the graph joins the node of the port {@code from} with the node of the port {@code to}
 * (in that direction with {@link Direction#FORWARD}), meeting the edge conditions (e.g. an SBGN role).
 * {@link Type#SAME_NODE}: both ports are the same graph node (a shared role; without such a link components never
 * share nodes). The type {@link Type#PATH} (a path of at most {@link #maxLength} edges) is reserved in the format and
 * reported as not supported yet.
 *
 * @author Niklas Gröne
 */
public final class StructureLink {

    public enum Type { EDGE, SAME_NODE, PATH }

    public enum Direction {
        FORWARD("from → to"), ANY("either way");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public String id;
    public Type type = Type.EDGE;
    public String fromComponent, fromPort;
    public String toComponent, toPort;
    public Direction direction = Direction.FORWARD;
    /** Conditions on the joining edge (EDGE only). */
    public final List<Condition> edgeConditions = new ArrayList<>();
    /** PATH only (reserved): the most edges the path may have. */
    public int maxLength = 1;

    public StructureLink() { }

    public StructureLink(String id, String fromComponent, String fromPort, String toComponent, String toPort) {
        this.id = id;
        this.fromComponent = fromComponent;
        this.fromPort = fromPort;
        this.toComponent = toComponent;
        this.toPort = toPort;
    }

    public StructureLink copy() {
        StructureLink l = new StructureLink(id, fromComponent, fromPort, toComponent, toPort);
        l.type = type;
        l.direction = direction;
        for (Condition c : edgeConditions) l.edgeConditions.add(c.copy());
        l.maxLength = maxLength;
        return l;
    }
}
