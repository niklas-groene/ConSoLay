package gui.template;

import gui.components.ScrollColumn;
import gui.components.Ui;
import gui.components.VerticalStack;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Base of the editor's property panels. The panel shows the selection and is built anew after every change of the
 * model, so what it shows is never out of date. Only a value typed or stepped in one of its own controls leaves it
 * standing ({@link #tweak}): rebuilding there would take the focus from the control being used and swallow a click on
 * its neighbour. Text that was typed but not confirmed yet is committed before the panel changes.
 *
 * @author Niklas Gröne
 */
abstract class PropertiesPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    static final int W = 250;

    final EditorModel model;
    final VerticalStack column = new VerticalStack();
    private final ScrollColumn scroll;
    /** Commits what the controls hold but the model does not know yet. */
    private final List<Runnable> pending = new ArrayList<>();
    private boolean keepControls;
    private String shownSelection = "";

    PropertiesPanel(EditorModel model) {
        super(new BorderLayout());
        this.model = model;
        setOpaque(false);
        column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_L));
        scroll = new ScrollColumn(column, W + 20);
        add(scroll, BorderLayout.CENTER);
        model.addListener(this::refresh);
    }

    /** Adds the sections for the current selection to {@link #column}. */
    abstract void build();

    /** Names the current selection: when it changes, the panel starts at its top again. */
    abstract String selectionKey();

    /** After a {@link #tweak}: updates the texts that depend on the value (the controls stay). */
    void controlsKept() { }

    final void refresh() {
        if (keepControls) {
            controlsKept();
            return;
        }
        commitPending();
        pending.clear();
        column.clearRows();
        build();
        column.revalidate();
        column.repaint();
        String selection = selectionKey();
        if (!selection.equals(shownSelection)) {
            shownSelection = selection;
            scroll.scrollToTop();
        }
    }

    /** Gives the model what was typed but not confirmed yet (before saving, undoing, closing or rebuilding). */
    final void commitPending() {
        for (Runnable r : new ArrayList<>(pending)) r.run();
    }

    /** A change that alters what the panel shows: the panel is built anew. */
    final void edit(String name, Runnable r) {
        model.change(name, r);
    }

    /** A value changed in a control of this panel: the controls stay as they are. */
    final void tweak(String name, Runnable r) {
        keepControls = true;
        try {
            model.change(name, r);
        } finally {
            keepControls = false;
        }
    }

    /** {@link #tweak} for a value stepped repeatedly: the steps of one control make one undo step. */
    final void tweakMerging(String key, String name, Runnable r) {
        keepControls = true;
        try {
            model.changeMerging(key, name, r);
        } finally {
            keepControls = false;
        }
    }

    /** Commits the trimmed text on Enter, on leaving the field and before the panel changes; only when it changed. */
    final void onText(JTextField f, Consumer<String> commit) {
        Runnable r = commitOnChange(f, commit);
        f.addActionListener(e -> r.run());
    }

    /** Like {@link #onText(JTextField, Consumer)} for a text area (Enter is a line break there). */
    final void onText(JTextArea a, Consumer<String> commit) {
        commitOnChange(a, commit);
    }

    private Runnable commitOnChange(JTextComponent c, Consumer<String> commit) {
        final String[] before = {c.getText().trim()};
        Runnable r = () -> {
            String v = c.getText().trim();
            if (!v.equals(before[0])) {
                before[0] = v;
                commit.accept(v);
            }
        };
        c.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { r.run(); }
        });
        pending.add(r);
        return r;
    }

    /** A description field: three lines high however tight the column is, committed like {@link #onText}. */
    final JComponent descriptionField(String text, Consumer<String> commit) {
        JTextArea a = new JTextArea(text, 3, 2);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        onText(a, commit);
        JScrollPane sp = new gui.components.NestedScrollPane(a);
        sp.setMinimumSize(sp.getPreferredSize());
        return sp;
    }

    /** A number spinner; the range widens to a value outside it (from a graph node or a file), so it can show it. */
    final JSpinner spinner(double value, double min, double max, double step, String key, String undoName, DoubleConsumer set) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, Math.min(min, value), Math.max(max, value), step));
        s.addChangeListener(e -> {
            double v = ((Number) s.getValue()).doubleValue();
            tweakMerging(key, undoName, () -> set.accept(v));
        });
        JFormattedTextField text = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
        pending.add(() -> {
            try {
                // only what was typed: parsing the shown text back would round an exact value
                if (!text.getText().equals(text.getFormatter().valueToString(s.getValue()))) s.commitEdit();
            } catch (ParseException ignored) {
                // not a number: the spinner keeps its last value
            }
        });
        return s;
    }

    static JLabel subheading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.small().deriveFont(Font.BOLD));
        return l;
    }
}
