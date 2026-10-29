package gui;

import gui.components.ScrollColumn;
import gui.components.Ui;
import gui.components.VerticalStack;
import gui.template.TemplatePanel;

import javax.swing.*;
import java.awt.*;

/**
 * Main tab "Templates": every operation on templates in one place. The {@link TemplatePanel} below a short
 * introduction holds the library (create a template from the selection or as a composite of other templates, edit,
 * import, choose the library folder), the search for matches, the found matches with their conflict checks, the
 * applied instances and the rule sets. Editing a template opens the template editor in its own window.
 *
 * @author Niklas Gröne
 */
public class TemplatesTab extends JPanel {

    private static final long serialVersionUID = 1L;

    public TemplatesTab() {
        super(new BorderLayout());

        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_XL));
        column.addRow(Ui.hint("Templates describe where they fit (a pattern, or a composite of other templates) and which "
                + "constraints they make there. Find where they fit, review the suggestions and their conflicts on the "
                + "graph and apply the ones you choose; build templates and rule sets in the editor.",
                Ui.COLUMN_WIDTH - 2 * Ui.PAD));
        column.addRow(new TemplatePanel(), Ui.SECTION_GAP);

        add(new ScrollColumn(column, Ui.COLUMN_WIDTH), BorderLayout.CENTER);
    }
}
