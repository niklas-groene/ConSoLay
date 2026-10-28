package layout.mental_map;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one mental-map run applied to the live graph: the constraints it derived from the reference (the layout's own
 * {@link Constraints} store) and its neighbourhood clusters ({@link CBLayoutAlgorithm#allCluster}), copied right after
 * the solve, so the next run, which clears and refills both, cannot change them.
 *
 * <p>Information only: nothing hands these objects to a solver again, and they never enter the user's
 * {@link ConstraintStore#constraints}.
 *
 * @author Niklas Gröne
 */
public final class DerivedConstraints {

    /** No run, or a run that derives no constraints (every condition other than the mental-map layouts). */
    public static final DerivedConstraints NONE =
            new DerivedConstraints(Collections.<Constraint>emptyList(), Collections.<List<Node>>emptyList(), 0, 0);

    private final List<Constraint> constraints;
    private final List<List<Node>> clusters;
    private final double clusterPadding, clusterMargin;

    private DerivedConstraints(List<Constraint> constraints, List<List<Node>> clusters,
                               double clusterPadding, double clusterMargin) {
        this.constraints = constraints;
        this.clusters = clusters;
        this.clusterPadding = clusterPadding;
        this.clusterMargin = clusterMargin;
    }

    /** Copies {@code store} and the engine's clusters, padding and margin as the run just used them. */
    static DerivedConstraints capture(Constraints store) {
        List<List<Node>> clusters = new ArrayList<>();
        if (CBLayoutAlgorithm.allCluster != null) {
            for (List<Node> c : CBLayoutAlgorithm.allCluster) clusters.add(Collections.unmodifiableList(new ArrayList<>(c)));
        }
        return new DerivedConstraints(Collections.unmodifiableList(new ArrayList<>(store.getAllConstraints())),
                Collections.unmodifiableList(clusters), CBLayoutAlgorithm.clusterPadding, CBLayoutAlgorithm.clusterMargin);
    }

    /** The ordering (and, for the Improved method, alignment) constraints of the run. */
    public List<Constraint> getConstraints() {
        return constraints;
    }

    /** The neighbourhood clusters of the run, as live nodes. */
    public List<List<Node>> getClusters() {
        return clusters;
    }

    /** Space between a cluster's border and its members in the run. */
    public double getClusterPadding() {
        return clusterPadding;
    }

    /** Space between a cluster's border and the nodes outside in the run. */
    public double getClusterMargin() {
        return clusterMargin;
    }

    public boolean isEmpty() {
        return constraints.isEmpty() && clusters.isEmpty();
    }
}
