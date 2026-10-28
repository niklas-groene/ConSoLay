package layout.sbgn;

import layout.steps.StepAlgorithm;
import layout.steps.StepLayout;

import org.graffiti.graph.Graph;
import org.sbgn.Language;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The SBGN layouts (PD, ER, AF) on the Algorithms page: one card with the language as its option.
 *
 * @author Niklas Gröne
 */
public final class SbgnStepAlgorithm implements StepAlgorithm {

    public static final String ID = "sbgn";
    public static final String LANGUAGE = "language";
    private static final List<String> LANGUAGES = Arrays.asList("auto", "PD", "ER", "AF");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "SBGN";
    }

    @Override
    public String subtitle() {
        return "Stepwise layout of SBGN maps";
    }

    @Override
    public String description() {
        return "Constraint-based layout for SBGN PD, ER and AF maps: run the whole pipeline or one step at a time, "
                + "and see the constraints each step adds.";
    }

    @Override
    public List<Option> options() {
        return Collections.singletonList(new Option(LANGUAGE, "Language",
                "The SBGN language of the map: Process Description (PD), Entity Relationship (ER) or Activity Flow "
                        + "(AF). <b>auto</b> detects it from the map's attributes.<br>It applies when a new run is "
                        + "prepared: by <b>New run</b>, or by <b>Run all steps</b> or a step on a map without a run.",
                LANGUAGES, "auto"));
    }

    @Override
    public StepLayout create(Graph graph, Map<String, String> options) {
        String lang = options == null ? null : options.get(LANGUAGE);
        Language language = (lang == null || "auto".equals(lang)) ? ConstraintSBGNLayout.detectLanguage(graph) : Language.valueOf(lang);
        return new ConstraintSBGNLayout(graph, language);
    }
}
