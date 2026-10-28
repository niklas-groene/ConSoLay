package cblayout;

import gui.AddonTab;

import org.graffiti.plugin.algorithm.AbstractAlgorithm;
import org.graffiti.plugin.algorithm.PreconditionException;

/**
 * Layout menu entry "Apply constraints": runs the loop of the add-on on the active graph (decide the conflicts, solve,
 * check, show), as the button of the Constraints page does. Meant for after any other VANTED layout algorithm: its
 * result is then projected onto the constraints. Edits and drags solve on their own; a layout algorithm of VANTED does
 * not trigger a solve.
 *
 * @author Niklas Gröne
 */
public class ApplyConstraintsAlgorithm extends AbstractAlgorithm {

    @Override
    public String getName() {
        return "Apply constraints";
    }

    @Override
    public String getCategory() {
        return "layout";
    }

    @Override
    public String getDescription() {
        return "<html>Decide, solve, check and show the constraints of the <b>Constraint-Based Layout</b> tab on this map.<br>"
                + "Use it after another layout algorithm, whose result is then projected onto the constraints.</html>";
    }

    @Override
    public boolean isLayoutAlgorithm() {
        return true;
    }

    @Override
    public void check() throws PreconditionException {
        if (graph == null) throw new PreconditionException("No graph.");
    }

    @Override
    public void execute() {
        AddonTab.applyConstraints();
    }
}
