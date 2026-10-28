package gui;

import constraints.Priority;
import gui.components.Ui;

import javax.swing.*;

/**
 * The priority control of the "Advanced" parts of the {@link ConstraintsTab} (settings card and list editor), and the
 * texts that explain what a priority does.
 *
 * @author Niklas Gröne
 */
public final class PriorityControls {

    private PriorityControls() { }

    /** The rule in one hint: which constraint is applied when two contradict each other. */
    public static final String RULE = "When constraints contradict each other, the higher priority is applied; at equal "
            + "priority yours before the SBGN steps', then the older one.";

    public static final String TOOLTIP = "<b>Priority</b>: decides which constraint is applied when constraints contradict "
            + "each other. The higher priority wins; at equal priority your own constraint wins over one of the SBGN "
            + "steps, else the one added earlier. A constraint that loses keeps its settings and is applied again as "
            + "soon as the contradiction is gone.";

    /** A combo box with the five levels, "Normal" selected. */
    public static JComboBox<Priority> comboBox(String name) {
        JComboBox<Priority> box = Ui.comboBox(Priority.values());
        box.setSelectedItem(Priority.NORMAL);
        box.setName(name);
        return box;
    }

    public static Priority selected(JComboBox<Priority> box) {
        Object o = box.getSelectedItem();
        return o instanceof Priority ? (Priority) o : Priority.NORMAL;
    }
}
