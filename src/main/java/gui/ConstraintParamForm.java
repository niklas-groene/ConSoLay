package gui;

import gui.components.Ui;
import gui.components.VerticalStack;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of a constraint kind as a form: a number field per number setting (with its unit and, if the setting
 * has one, an "auto" / "current" check box that computes the value from the selection) and a check box per yes / no
 * setting. Used to add a constraint and to edit one. {@link #values()} gives numbers as Double (NaN while "auto" is
 * ticked) and yes / no settings as Boolean.
 *
 * @author Niklas Gröne
 */
public final class ConstraintParamForm extends VerticalStack {

    private static final long serialVersionUID = 1L;

    /** Width of the labels in front of the number fields, so the fields line up. */
    private static final int LABEL_WIDTH = 62;

    private final List<ConstraintCatalog.Param> params;
    private final Map<String, JSpinner> spinners = new LinkedHashMap<>();
    private final Map<String, JCheckBox> autos = new LinkedHashMap<>();
    private final Map<String, JCheckBox> flags = new LinkedHashMap<>();

    /**
     * @param initial  start values by key (missing: the parameter's default, "auto" ticked if it has one)
     * @param onChange called after every change by the user (may be null)
     */
    public ConstraintParamForm(List<ConstraintCatalog.Param> params, Map<String, Object> initial, Runnable onChange) {
        this.params = params;
        for (ConstraintCatalog.Param p : params) {
            Object start = initial == null ? null : initial.get(p.key);
            if (p.type == ConstraintCatalog.ParamType.BOOLEAN) {
                JCheckBox box = new JCheckBox(p.label, start instanceof Boolean ? (Boolean) start : p.def != 0);
                box.setOpaque(false);
                box.setName(p.key);
                if (p.tooltip != null) box.setToolTipText(Ui.tooltip(p.tooltip));
                if (onChange != null) box.addActionListener(e -> onChange.run());
                flags.put(p.key, box);
                addRow(box, getRowCount() == 0 ? 0 : Ui.GAP_XS);
                continue;
            }
            double value = start instanceof Number && !Double.isNaN(((Number) start).doubleValue()) ? ((Number) start).doubleValue() : p.def;
            // The range includes the start value: a stored value outside it (a "current" distance of 6000 px, a negative
            // template gap) is shown and kept, never clamped.
            double lo = Math.min(p.min, value), hi = Math.max(p.max, value);
            SpinnerNumberModel model = p.type == ConstraintCatalog.ParamType.INTEGER
                    ? new SpinnerNumberModel((int) Math.round(value), (int) Math.floor(lo), (int) Math.ceil(hi), (int) Math.max(1, p.step))
                    : new SpinnerNumberModel(value, lo, hi, p.step);
            JSpinner spinner = new JSpinner(model) {
                private static final long serialVersionUID = 1L;
                @Override public Dimension getPreferredSize() { return new Dimension(58, super.getPreferredSize().height); }
                @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            };
            spinner.setName(p.key);
            if (p.type == ConstraintCatalog.ParamType.DECIMAL) spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0"));
            if (p.tooltip != null) spinner.setToolTipText(Ui.tooltip(p.tooltip));
            if (onChange != null) spinner.addChangeListener(e -> onChange.run());
            spinners.put(p.key, spinner);

            JPanel field = new JPanel(new BorderLayout(Ui.GAP_S, 0));
            field.setOpaque(false);
            field.add(spinner, BorderLayout.WEST);
            if (p.unit != null) {
                JLabel unit = new JLabel(p.unit);
                unit.setFont(Ui.small());
                unit.setForeground(Ui.MUTED);
                field.add(unit, BorderLayout.CENTER);
            }
            JCheckBox auto = null;
            if (p.autoLabel != null) {
                auto = new JCheckBox(p.autoLabel, !(start instanceof Number) || Double.isNaN(((Number) start).doubleValue()));
                auto.setOpaque(false);
                auto.setFont(Ui.small());
                auto.setName(p.key + ".auto");
                auto.setToolTipText(Ui.tooltip(p.tooltip));
                final JCheckBox a = auto;
                spinner.setEnabled(!a.isSelected());
                auto.addActionListener(e -> {
                    spinner.setEnabled(!a.isSelected());
                    if (onChange != null) onChange.run();
                });
                autos.put(p.key, auto);
            }
            JPanel row = Ui.labeled(p.label, field, auto);
            Component label = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.WEST);
            if (label instanceof JLabel) {
                label.setPreferredSize(new Dimension(LABEL_WIDTH, label.getPreferredSize().height));
                if (p.tooltip != null) ((JLabel) label).setToolTipText(Ui.tooltip(p.tooltip));
            }
            addRow(row, getRowCount() == 0 ? 0 : Ui.GAP_S);
        }
    }

    /** The current values by key: Double for numbers (NaN while "auto" is ticked), Boolean for yes / no settings. */
    public Map<String, Object> values() {
        Map<String, Object> v = new LinkedHashMap<>();
        for (ConstraintCatalog.Param p : params) {
            if (p.type == ConstraintCatalog.ParamType.BOOLEAN) {
                v.put(p.key, flags.get(p.key).isSelected());
            } else {
                JCheckBox auto = autos.get(p.key);
                v.put(p.key, auto != null && auto.isSelected() ? Double.NaN
                        : ((Number) spinners.get(p.key).getValue()).doubleValue());
            }
        }
        return v;
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }
}
