package gui;

import constraints.Constraint;
import constraints.ConstraintStore;

/**
 * Entry points other code uses to refresh the constraint list of the "Constraints" tab ({@link ConstraintsTab}) and to
 * describe a constraint in one line. The store's refresh hook ({@code ConstraintStore.onListsChanged}) points here.
 */
public class ListModels {

    private ListModels() { }

    /** The store of constraints changed: rebuild the list, its counters and the Add button's check. */
    public static void updateAllLists() {
        // the graph carries its store (saved with GML / GraphML); written only when the JSON changed
        try {
            layout.store.GraphConstraints.sync(ConstraintStore.activeGraph(), ConstraintStore.constraints);
        } catch (RuntimeException e) {
            org.ErrorMsg.addErrorMessage(e);
        }
        ConstraintsTab.refreshList();
        gui.template.TemplatePanel.constraintsChanged();
    }

    /** Short human-readable form of a constraint: what it does, its main value and its nodes. */
    static String describe(Constraint c) {
        return ConstraintCatalog.describeWithOrigin(c) + " · " + ConstraintCatalog.nodesText(c);
    }
}
