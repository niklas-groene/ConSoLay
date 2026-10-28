package layout.steps;

import org.graffiti.graph.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A layout algorithm another VANTED add-on (or this one) offers on the Algorithms page: how it is called there and how
 * a {@link StepLayout} of it is made for a graph. Register it in the {@link StepLayoutRegistry} when the add-on
 * initialises; the page then shows a card with its title, the options as drop-down lists, the buttons Run all steps,
 * New run and Reset, and the list of steps of the current run.
 *
 * @author Niklas Gröne
 */
public interface StepAlgorithm {

    /** Stable id, e.g. "sbgn". */
    String id();

    /** Title of the card, e.g. "SBGN". */
    String title();

    /** One line under the title, e.g. "Stepwise layout of SBGN maps". */
    String subtitle();

    /** Tooltip of the card. */
    String description();

    /** Options shown as drop-down lists above the buttons (empty if none). */
    default List<Option> options() {
        return Collections.emptyList();
    }

    /**
     * A new layout of {@code graph}; {@code options} holds the chosen value per option key (the default where the user
     * chose nothing).
     */
    StepLayout create(Graph graph, Map<String, String> options);

    /** A choice among fixed words, e.g. the SBGN language. */
    final class Option {
        public final String key, label, tooltip, defaultValue;
        public final List<String> values;

        public Option(String key, String label, String tooltip, List<String> values, String defaultValue) {
            this.key = key;
            this.label = label;
            this.tooltip = tooltip;
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
            this.defaultValue = defaultValue;
        }
    }
}
