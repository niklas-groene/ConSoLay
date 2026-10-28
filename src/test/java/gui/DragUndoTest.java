package gui;

import org.AttributeHelper;
import org.graffiti.attributes.Attribute;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.undo.ChangeAttributesEdit;
import org.junit.Test;

import javax.swing.undo.UndoManager;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The undo step of a drag with constraints takes over the move tool's edit of the dragged node, so one undo puts back
 * the dragged node and the nodes the drag's solves moved, and one redo moves both again. Runs without VANTED's GUI.
 */
public class DragUndoTest {

    private static Node node(Graph g, double x, double y) {
        Node n = g.addNode(AttributeHelper.getDefaultGraphicsAttributeForNode(x, y));
        AttributeHelper.setSize(n, 40, 40);
        return n;
    }

    private static void at(Node n, double x, double y) {
        assertEquals(x, AttributeHelper.getPositionX(n), 1e-9);
        assertEquals(y, AttributeHelper.getPositionY(n), 1e-9);
    }

    @Test
    public void oneUndoRestoresTheDraggedNodeAndItsPartners() {
        System.setProperty("java.awt.headless", "true");
        Graph g = new AdjListGraph();
        Node dragged = node(g, 0, 0), partner = node(g, 200, 0);
        UndoManager undo = new UndoManager();
        // the move tool's edit, posted when the drag starts: the dragged node's coordinate before the drag
        Map<Attribute, Object> before = new HashMap<>();
        Attribute coordinate = dragged.getAttribute(GraphicAttributeConstants.COORD_PATH);
        before.put(coordinate, ((Attribute) coordinate.copy()).getValue());
        undo.addEdit(new ChangeAttributesEdit(g, before, new HashMap<>()));

        // two drag steps: the tool moves the dragged node, the solve moves its partner along
        AttributeHelper.setPosition(dragged, 0, 50);
        AddonTab.DragEdit edit = new AddonTab.DragEdit(g, new HashMap<>());
        edit.moved(partner, 200, 0);
        AttributeHelper.setPosition(partner, 200, 50);
        undo.addEdit(edit);
        AttributeHelper.setPosition(dragged, 0, 100);
        edit.moved(partner, 200, 50);
        AttributeHelper.setPosition(partner, 200, 100);

        undo.undo();
        at(dragged, 0, 0);
        at(partner, 200, 0);
        assertFalse("the drag is one undo step", undo.canUndo());
        undo.redo();
        at(dragged, 0, 100);
        at(partner, 200, 100);
    }
}
