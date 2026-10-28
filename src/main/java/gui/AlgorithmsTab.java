package gui;

import gui.components.CardSelector;
import gui.components.ScrollColumn;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.steps.StepAlgorithm;
import layout.steps.StepLayoutRegistry;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main tab "Algorithms": a {@link CardSelector} with the layout algorithms of the add-on (the step algorithms of the
 * {@link StepLayoutRegistry}, the Mental Map and the Templates) above the panel of the selected algorithm. Every panel
 * is built once and keeps its state while another algorithm is shown.
 *
 * @author Niklas Gröne
 */
public class AlgorithmsTab extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Ids of the two panels that are not step algorithms (those use their registry id). */
    public static final String MENTAL_MAP = "mental-map", TEMPLATES = "templates";

    private final CardSelector<String> selector = new CardSelector<>(Ui.COLUMN_WIDTH - 2 * Ui.PAD);
    private final Map<String, JComponent> panels = new LinkedHashMap<>();
    private final VerticalStack content = new VerticalStack();
    private final ScrollColumn scroll;

    public AlgorithmsTab() {
        super(new BorderLayout());

        for (StepAlgorithm a : StepLayoutRegistry.all()) addStepAlgorithm(a);
        selector.addOption(MENTAL_MAP, "Mental Map", "Keep a reference layout",
                "Re-layout the edited graph relative to a pinned reference snapshot, so the user's mental map is "
                        + "preserved, and travel through the snapshot history.");
        selector.addOption(TEMPLATES, "Templates", "Find and apply layout rules",
                "Templates describe where they fit (a pattern, or a composite of other templates) and which constraints "
                        + "they make there. Find where they fit, review the suggestions on the graph and apply the ones "
                        + "you choose; build templates and rule sets in the editor.");
        selector.getAccessibleContext().setAccessibleName("Algorithm");

        panels.put(MENTAL_MAP, new MentalMapPanel());
        panels.put(TEMPLATES, new gui.template.TemplatePanel());

        // All panels stay in the tree (listeners, timers and the template preview keep working); the stack skips
        // the invisible ones, so the column is only as high as the selected panel.
        for (JComponent panel : panels.values()) content.addRow(panel, 0);

        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_XL));
        column.addRow(selector);
        column.addRow(content, Ui.SECTION_GAP);

        scroll = new ScrollColumn(column, Ui.COLUMN_WIDTH);
        add(scroll, BorderLayout.CENTER);

        selector.addSelectionListener(this::show);
        show(selector.getSelectedValue());
        // An add-on that registers its algorithm after this tab was built gets its card now.
        StepLayoutRegistry.addListener(() -> SwingUtilities.invokeLater(this::addNewAlgorithms));
    }

    private void addStepAlgorithm(StepAlgorithm a) {
        if (panels.containsKey(a.id())) return;
        selector.addOption(a.id(), a.title(), a.subtitle(), a.description());
        StepLayoutPanel panel = new StepLayoutPanel(a);
        panels.put(a.id(), panel);
        panel.setVisible(false);
    }

    private void addNewAlgorithms() {
        boolean added = false;
        for (StepAlgorithm a : StepLayoutRegistry.all()) {
            if (panels.containsKey(a.id())) continue;
            addStepAlgorithm(a);
            content.addRow(panels.get(a.id()), 0);
            added = true;
        }
        if (added) show(selector.getSelectedValue());
    }

    private void show(String algorithm) {
        for (Map.Entry<String, JComponent> e : panels.entrySet()) e.getValue().setVisible(e.getKey().equals(algorithm));
        revalidate();
        repaint();
        SwingUtilities.invokeLater(scroll::scrollToTop);
    }
}
