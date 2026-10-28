package cblayout;

import constraints.ConflictLog;
import constraints.ConstraintStore;
import gui.StepLayoutPanel;
import layout.sbgn.ConstraintSBGNLayout;

import org.graffiti.graph.Graph;
import org.graffiti.plugin.algorithm.AbstractAlgorithm;
import org.graffiti.plugin.algorithm.PreconditionException;
import org.graffiti.plugin.parameter.ObjectListParameter;
import org.graffiti.plugin.parameter.Parameter;
import org.sbgn.Language;

/**
 * Layout menu entry "Constraint SBGN Layout": runs all steps of {@link ConstraintSBGNLayout} on the active graph. Its
 * parameter pins the SBGN language; by default the language is detected from the graph's SBGN attributes.
 *
 * @author Niklas Gröne
 */
public class ConstraintSBGNLayoutAlgorithm extends AbstractAlgorithm {

    private static final String[] LANGUAGE_OPTIONS = { "auto", "PD", "ER", "AF" };
    private String selectedLanguage = "auto";

    @Override
    public String getName() {
        return "Constraint SBGN Layout";
    }

    @Override
    public String getCategory() {
        return "layout";
    }

    @Override
    public String getDescription() {
        return "<html>Constraint-based layout for SBGN PD/ER/AF maps.<br>" +
                "Open the <b>Constraint-Based Layout</b> tab in the side inspector (<b>Algorithms</b>, " +
                "<b>SBGN</b>) to run individual algorithm steps and visualize constraints.</html>";
    }

    @Override
    public boolean isLayoutAlgorithm() {
        return true;
    }

    @Override
    public Parameter[] getParameters() {
        ObjectListParameter langParam = new ObjectListParameter(
                selectedLanguage,
                "SBGN language",
                "Auto-detect from graph attributes, or force PD / ER / AF.",
                LANGUAGE_OPTIONS);
        return new Parameter[] { langParam };
    }

    @Override
    public void setParameters(Parameter[] params) {
        super.setParameters(params);
        if (params != null && params.length > 0 && params[0] instanceof ObjectListParameter) {
            Object v = ((ObjectListParameter) params[0]).getValue();
            if (v != null) selectedLanguage = v.toString();
        }
    }

    @Override
    public void check() throws PreconditionException {
        if (graph == null) throw new PreconditionException("No graph.");
        if (graph.getNumberOfNodes() == 0) throw new PreconditionException("Graph is empty.");
    }

    @Override
    public void execute() {
        // Keep only the user's constraints on this map, as the SBGN panel does: the automatic constraints of an
        // earlier run (possibly of another map) would reference nodes the solver does not know.
        ConstraintStore.of(graph).retainUserConstraintsOf(graph);
        ConflictLog.storeReplaced();
        ConstraintStore.onListsChanged.run();
        Language lang = resolveLanguage(graph);
        ConstraintSBGNLayout layout = new ConstraintSBGNLayout(graph, lang);
        StepLayoutPanel.setActiveLayout(layout);
        layout.runAll();
        StepLayoutPanel.markAllStepsDone(layout);
    }

    private Language resolveLanguage(Graph g) {
        switch (selectedLanguage) {
            case "PD": return Language.PD;
            case "ER": return Language.ER;
            case "AF": return Language.AF;
            case "auto":
            default:   return ConstraintSBGNLayout.detectLanguage(g);
        }
    }
}
