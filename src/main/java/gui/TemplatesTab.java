package gui;

import gui.components.ScrollColumn;
import gui.components.Ui;
import gui.components.VerticalStack;
import gui.template.TemplatePanel;

import javax.swing.*;
import java.awt.*;

/**
 * Main tab "Templates": every operation on templates in one place. The {@link TemplatePanel} holds the library (create a template from the selection or as a composite of other templates, edit,
 * import, choose the library folder), the search for matches, the found matches with their conflict checks, the
 * applied instances and a summary of the rule set. Editing a template or the rule set opens a window of its own.
 *
 * @author Niklas Gröne
 */
public class TemplatesTab extends JPanel {

    private static final long serialVersionUID = 1L;

    public TemplatesTab() {
        super(new BorderLayout());

        VerticalStack column = new VerticalStack();
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_XL));
        column.addRow(new TemplatePanel());

        add(new ScrollColumn(column, Ui.COLUMN_WIDTH), BorderLayout.CENTER);
    }
}
