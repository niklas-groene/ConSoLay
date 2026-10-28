package gui.template;

import constraints.Priority;
import gui.components.PrimaryButton;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.io.TemplateCodec;
import layout.template.io.TemplateFiles;
import layout.template.io.TemplateLibrary;
import layout.template.model.RuleSystem;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;
import layout.template.search.SearchTask;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Section "Rule set" of the Templates panel: several templates bundled as named rules, each switched on or off, with a
 * priority for the constraints it makes, saved as one file that carries every template it needs. Creating, loading or
 * editing a rule set never changes the graph: "Find matches for active rules" searches, and the found matches are
 * reviewed and applied like any others.
 *
 * @author Niklas Gröne
 */
final class RuleSetSection extends Section {

    private static final long serialVersionUID = 1L;
    private static final String KEEP = "As in the template";

    private final TemplatePanel panel;
    private RuleSystem rules = new RuleSystem();
    private File file;
    private final JTextField nameField = new JTextField(2);
    private final DefaultListModel<RuleSystem.Rule> model = new DefaultListModel<>();
    private final JList<RuleSystem.Rule> list = new JList<>(model);
    private final JTextField ruleName = new JTextField(2);
    private final JCheckBox ruleOn = new JCheckBox("Active");
    private final JComboBox<Object> rulePriority;
    private final JLabel ruleInfo = Ui.hint(" ");
    private final JButton update = Ui.button("Update template", "Uses the newest revision of the rule's template (checked first).",
            e -> updateTemplate());
    private final VerticalStack editor = new VerticalStack();
    private final StatusLabel status = new StatusLabel(" ");
    private boolean updating;

    RuleSetSection(TemplatePanel panel) {
        super("Rule set", true, false);
        this.panel = panel;
        Object[] prios = new Object[Priority.values().length + 1];
        prios[0] = KEEP;
        for (int i = 0; i < Priority.values().length; i++) prios[i + 1] = Priority.values()[i];
        rulePriority = Ui.comboBox(prios);

        nameField.setText(rules.name);
        onCommit(nameField, () -> rules.name = nameField.getText().trim());
        addRow(Ui.labeled("Name", nameField));
        addRow(Ui.hint("Bundle templates as rules; find the matches of all active rules at once, review them and apply the ones you want."), Ui.GAP_XS);
        list.setName("ruleList");
        list.setCellRenderer((l, r, i, sel, foc) -> {
            JLabel c = new JLabel((r.enabled ? "☑ " : "☐ ") + r.name + "  ·  " + (r.priority == null ? "" : r.priority.label() + " · ")
                    + (r.template == null ? "?" : r.template.name));
            c.setOpaque(true);
            c.setBorder(new EmptyBorder(3, 6, 3, 6));
            c.setBackground(sel ? l.getSelectionBackground() : Color.WHITE);
            c.setForeground(sel ? l.getSelectionForeground() : r.enabled ? Ui.TEXT : Ui.MUTED);
            return c;
        });
        list.setVisibleRowCount(4);
        list.setFixedCellWidth(1);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showRule(); });
        list.getAccessibleContext().setAccessibleName("Rules");
        addRow(new JScrollPane(list) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
        }, Ui.GAP_S);
        JButton add = Ui.button("Add chosen template", "Adds the template chosen in the library as a new rule.", e -> addRule());
        add.setName("addRule");
        JButton remove = Ui.button("Remove", "Removes the rule (not its template).", e -> removeRule());
        JButton up = Ui.button("Up", "Moves the rule up (the list order is the order of the search).", e -> move(-1));
        JButton down = Ui.button("Down", "Moves the rule down.", e -> move(1));
        addRow(Ui.row(add, remove), Ui.GAP_S);
        addRow(Ui.row(up, down), Ui.GAP_XS);

        ruleOn.setOpaque(false);
        ruleOn.addActionListener(e -> edit(r -> r.enabled = ruleOn.isSelected()));
        onCommit(ruleName, () -> edit(r -> r.name = ruleName.getText().trim()));
        rulePriority.setToolTipText(Ui.tooltip("Priority of every constraint this rule makes (it decides conflicts), or the template's own."));
        rulePriority.addActionListener(e -> edit(r -> {
            Object o = rulePriority.getSelectedItem();
            r.priority = o instanceof Priority ? (Priority) o : null;
        }));
        editor.addRow(Ui.labeled("Rule", ruleName));
        editor.addRow(ruleOn, Ui.GAP_XS);
        editor.addRow(Ui.labeled("Priority", rulePriority), Ui.GAP_XS);
        editor.addRow(ruleInfo, Ui.GAP_XS);
        editor.addRow(update, Ui.GAP_XS);
        editor.setVisible(false);
        addRow(editor, Ui.GAP_S);

        PrimaryButton find = Ui.primaryButton("Find matches for active rules", "Searches the active graph for every active rule; "
                + "the matches appear in the Matches list and on the graph. Nothing is applied yet.", e -> find());
        find.setName("findRuleMatches");
        addRow(find, Ui.GAP_M);
        addRow(status, Ui.GAP_S);
        JButton neu = Ui.button("New", "Starts an empty rule set.", e -> {
            rules = new RuleSystem();
            file = null;
            load();
        });
        JButton open = Ui.button("Open…", "Opens a rule set file (" + TemplateFiles.RULES_EXT + ").", e -> open());
        JButton save = Ui.button("Save…", "Saves the rule set with every template it needs.", e -> save());
        save.setName("saveRules");
        addRow(Ui.row(neu, open, save), Ui.GAP_S);
    }

    /** Runs {@code commit} on Enter and when {@code f} loses the focus. */
    private static void onCommit(JTextField f, Runnable commit) {
        f.addActionListener(e -> commit.run());
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commit.run(); }
        });
    }

    private void edit(Consumer<RuleSystem.Rule> e) {
        if (updating) return;
        RuleSystem.Rule r = list.getSelectedValue();
        if (r == null) return;
        e.accept(r);
        list.repaint();
    }

    private TemplateSource source() {
        return TemplateSource.chain(panel.library(), TemplateSource.of(rules));
    }

    void libraryChanged() {
        showRule();
    }

    void refresh() {
        list.repaint();
    }

    private void load() {
        nameField.setText(rules.name);
        model.clear();
        for (RuleSystem.Rule r : rules.rules) model.addElement(r);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        showRule();
    }

    private void showRule() {
        RuleSystem.Rule r = list.getSelectedValue();
        editor.setVisible(r != null);
        if (r == null) return;
        updating = true;
        try {
            ruleName.setText(r.name);
            ruleOn.setSelected(r.enabled);
            rulePriority.setSelectedItem(r.priority == null ? KEEP : r.priority);
        } finally {
            updating = false;
        }
        TemplateDef t = source().get(r.template);
        TemplateLibrary lib = panel.library();
        TemplateDef newest = lib == null || r.template == null ? null : lib.latest(r.template.id);
        StringBuilder sb = new StringBuilder("Template: ").append(Ui.esc(r.template == null ? "?" : r.template.toString()));
        if (t == null) sb.append("<br><font color='#c5221f'>Not available: the rule cannot find matches.</font>");
        boolean newer = newest != null && t != null && newest.revision > t.revision;
        if (newer) sb.append("<br><font color='#2563eb'>Revision ").append(newest.revision).append(" is in the library.</font>");
        ruleInfo.setText(Ui.wrapped(sb.toString(), Ui.TEXT_WIDTH));
        update.setVisible(newer);
        revalidate();
    }

    private void addRule() {
        TemplateDef t = panel.selectedTemplate();
        if (t == null) {
            status.setStatus("Choose a template in the library first.", StatusLabel.Kind.ERROR);
            return;
        }
        RuleSystem.Rule r = new RuleSystem.Rule(rules.nextRuleId(), t.name, t.ref());
        rules.rules.add(r);
        embed(t, TemplateSource.chain(panel.library(), TemplateSource.embeddedIn(t)));
        model.addElement(r);
        list.setSelectedValue(r, true);
        status.setStatus("Added rule '" + r.name + "'.", StatusLabel.Kind.SUCCESS);
    }

    private void removeRule() {
        RuleSystem.Rule r = list.getSelectedValue();
        if (r == null) return;
        rules.rules.remove(r);
        model.removeElement(r);
        showRule();
    }

    private void move(int d) {
        int i = list.getSelectedIndex();
        int j = i + d;
        if (i < 0 || j < 0 || j >= rules.rules.size()) return;
        RuleSystem.Rule r = rules.rules.remove(i);
        rules.rules.add(j, r);
        load();
        list.setSelectedIndex(j);
    }

    /** Uses the library's newest revision of the rule's template, after checking it. */
    private void updateTemplate() {
        RuleSystem.Rule r = list.getSelectedValue();
        if (r == null || panel.library() == null) return;
        TemplateDef newest = panel.library().latest(r.template.id);
        if (newest == null) return;
        TemplateSource s = TemplateSource.chain(panel.library(), TemplateSource.embeddedIn(newest));
        List<TemplateValidator.Problem> ps = TemplateValidator.validate(newest, s);
        if (!TemplateValidator.canApply(ps)) {
            StringBuilder sb = new StringBuilder("Revision " + newest.revision + " cannot be used:\n");
            for (TemplateValidator.Problem p : ps) if (p.severity != TemplateValidator.Severity.WARNING) sb.append("• ").append(p).append('\n');
            JOptionPane.showMessageDialog(this, sb.toString(), "Update template", JOptionPane.WARNING_MESSAGE);
            return;
        }
        r.template = TemplateRef.of(newest);
        embed(newest, s);
        showRule();
        list.repaint();
        status.setStatus("Rule '" + r.name + "' uses revision " + newest.revision + " now.", StatusLabel.Kind.SUCCESS);
    }

    /** Stores a copy of {@code t} and of every template it uses in the rule set, keyed by id@revision. */
    private void embed(TemplateDef t, TemplateSource s) {
        for (TemplateDef d : Templates.closure(t, s)) {
            TemplateDef c = d.copy();
            c.dependencies.clear();
            rules.templates.put(c.id + "@" + c.revision, c);
        }
    }

    private void find() {
        List<SearchTask> tasks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RuleSystem.Rule r : rules.rules) {
            if (!r.enabled) continue;
            TemplateDef t = source().get(r.template);
            if (t == null) {
                skipped.add(r.name + " (template missing)");
                continue;
            }
            TemplateSource s = TemplateSource.chain(source(), TemplateSource.embeddedIn(t));
            if (!TemplateValidator.canApply(TemplateValidator.validate(t, s))) {
                skipped.add(r.name + " (template has errors)");
                continue;
            }
            tasks.add(new SearchTask(t, s, r.name, r.priority));
        }
        if (tasks.isEmpty()) {
            status.setStatus(rules.rules.isEmpty() ? "Add rules first." : "No active rule can search" + (skipped.isEmpty() ? "." : ": " + String.join(", ", skipped)),
                    StatusLabel.Kind.ERROR);
            return;
        }
        panel.startSearch(tasks);
        status.setStatus("Searching " + tasks.size() + (tasks.size() == 1 ? " rule" : " rules") + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped)),
                skipped.isEmpty() ? StatusLabel.Kind.INFO : StatusLabel.Kind.ERROR);
    }

    private void open() {
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : panel.library() == null ? null : panel.library().getFolder());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            rules = TemplateFiles.loadRules(fc.getSelectedFile());
            file = fc.getSelectedFile();
            load();
            status.setStatus("Opened '" + rules.name + "' (" + rules.rules.size() + " rules). The graph is not changed.", StatusLabel.Kind.SUCCESS);
        } catch (IOException | TemplateCodec.FormatException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Open rule set", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        rules.name = nameField.getText().trim().isEmpty() ? "Untitled rule set" : nameField.getText().trim();
        File target = file;
        if (target == null) {
            JFileChooser fc = new JFileChooser(panel.library() == null ? null : panel.library().getFolder());
            fc.setSelectedFile(new File(fc.getCurrentDirectory(), TemplateFiles.fileName(rules.name, TemplateFiles.RULES_EXT)));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            target = fc.getSelectedFile();
            if (!target.getName().endsWith(TemplateFiles.RULES_EXT)) target = new File(target.getParentFile(), target.getName() + TemplateFiles.RULES_EXT);
        }
        try {
            TemplateFiles.saveRules(rules, target);
            file = target;
            status.setStatus("Saved " + target.getName() + " with " + rules.templates.size() + " templates.", StatusLabel.Kind.SUCCESS);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save rule set", JOptionPane.ERROR_MESSAGE);
        }
    }
}
