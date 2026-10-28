package constraints;

import org.AttributeHelper;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A warning about user constraints that contradict each other goes when one of them is deleted; other warnings stay
 * until the next run. Runs without VANTED's GUI.
 */
public class ConflictLogTest {

    private static int warnings() {
        int k = 0;
        for (ConflictLog.Entry e : ConflictLog.entries()) if (e.kind == ConflictLog.Kind.WARNING) k++;
        return k;
    }

    @Test
    public void contradictionWarningGoesWithItsConstraint() {
        Graph g = new AdjListGraph();
        Node a = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(0, 0));
        Node b = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(100, 0));
        SeparationConstraintGraph right = new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(a, b)),
                SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL, false, 50);
        SeparationConstraintGraph left = new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(b, a)),
                SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL, false, 50);
        Constraints store = new Constraints();
        store.addConstraint(right);
        store.addConstraint(left);
        ConstraintStore.constraints = store;
        ConflictLog.clear();
        List<ConstraintFeasibility.Dropped> dropped = ConstraintFeasibility.enforce(store, true);
        ConflictLog.addDropped("SBGN", dropped, ConstraintFeasibility.lastWarnings);
        ConflictLog.addDropped("SBGN", Collections.<ConstraintFeasibility.Dropped>emptyList(),
                Collections.singletonList("Components were not packed"));
        assertEquals(2, warnings());

        store.removeConstraint(left);
        ConflictLog.forget(Collections.singletonList(left));
        assertEquals("only the run's note is left", 1, warnings());
        ConflictLog.clear();
    }
}
