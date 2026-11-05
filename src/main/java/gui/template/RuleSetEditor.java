package gui.template;

import constraints.Priority;
import gui.components.NestedScrollPane;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The rule set editor, in a window of its own that the Templates page opens (the page shows the rule set in short).
 * On the left it lists every template of the library, the examples and composites too; on the right the rules of the
 * rule set and the settings of the chosen one. A template becomes a rule with a double-click, Enter or "Add as rule"
 * (several at once, too). Templates made with "New" here become rules when they are saved, and when a template is
 * changed from here, every rule that uses it takes the saved revision. Each rule is switched on or off and has a
 * priority for the constraints it makes; the rule set is saved as one file that carries every template it needs.
 * Creating, loading or editing a rule set never changes the graph: "Find matches for active rules" searches, and the
 * found matches are reviewed and applied like any others.
 *
 * @author Niklas Gröne
 */
final class RuleSetEditor extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String KEEP = "As in the template";
    /** The key under which this editor follows the saves of a template editor window. */
    private static final String FOLLOW = "ruleSet";
    /** Width of the check box column of the rule list. */
    private static final int BOX_COLUMN = 26;
    /** Width of the preview in a list row. */
    private static final int THUMB = 42;
    /** Width of the wrapped texts of the rules column. */
    private static final int TEXT = 380;

    private final TemplatePanel panel;
    private RuleSystem rules = new RuleSystem();
    private File file;
    /** Changed since it was made, opened or saved. */
    private boolean dirty;

    private final JTextField nameField = new JTextField(18);

    // ---- the library
    private final JTextField filter = new HintField("Filter by name");
    private final DefaultListModel<TemplatePanel.Item> libraryModel = new DefaultListModel<>();
    private final JList<TemplatePanel.Item> libraryList = new JList<TemplatePanel.Item>(libraryModel) {
        private static final long serialVersionUID = 1L;
        @Override public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
            return Ui.tooltip(TemplatePanel.describe(libraryModel.get(i)));
        }
    };
    private final JLabel noTemplates = Ui.hint(" ");

    // ---- the rules
    private final Section rulesSection = new Section("Rules");
    private final DefaultListModel<RuleSystem.Rule> ruleModel = new DefaultListModel<>();
    private final JList<RuleSystem.Rule> ruleList = new JList<>(ruleModel);
    private final JLabel noRules = Ui.hint("No rules yet: double-click a template on the left, or choose templates there "
            + "and click Add as rule.", TEXT);
    private JScrollPane ruleScroll;
    private JComponent ruleButtons;
    private final JButton up = Ui.button("Up", "Moves the rule up (the list order is the order of the search).", e -> move(-1));
    private final JButton down = Ui.button("Down", "Moves the rule down.", e -> move(1));
    private final JButton remove = Ui.button("Remove", "Removes the rule from the rule set (its template stays in the library).",
            e -> removeRule());

    // ---- the chosen rule
    private final Section ruleSection = new Section("Selected rule");
    /** The settings of the chosen rule (hidden while none is chosen). */
    private final VerticalStack details = new VerticalStack();
    private final Preview preview = new Preview();
    private final JTextField ruleName = new JTextField(2);
    private final JCheckBox ruleOn = new JCheckBox("Active");
    private final JComboBox<Object> rulePriority;
    private final JLabel ruleInfo = Ui.hint(" ", TEXT);
    private final JButton editTemplate = Ui.button("Edit template…", "Opens the rule's template in the template editor. "
            + "When you save it, every rule of this set that uses the template takes the saved revision.", e -> editRuleTemplate());
    private final JButton update = Ui.button("Use newest revision", "Uses the newest revision of the rule's template in the "
            + "library (checked first).", e -> updateTemplate());
    private final JLabel chooseRule = Ui.hint("Choose a rule to change its name, switch, priority or template.", TEXT);
    private boolean updating;
    /** The rule the fields show; their edits go to it, even when they are committed after the list moved on. */
    private RuleSystem.Rule shown;

    private final StatusLabel status = new StatusLabel(" ");
    /** The active rules the last search left out and why ("A (template missing)"), or null. */
    private String skipped;
    /** Told about every change of the rule set (its name, its rules, a new or opened one): the panel's summary follows. */
    Runnable onChanged = () -> { };

    RuleSetEditor(TemplatePanel panel) {
        super(new BorderLayout());
        this.panel = panel;
        setBackground(Color.WHITE);
        Object[] prios = new Object[Priority.values().length + 1];
        prios[0] = KEEP;
        for (int i = 0; i < Priority.values().length; i++) prios[i + 1] = Priority.values()[i];
        rulePriority = Ui.comboBox(prios);

        add(header(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        JComponent left = libraryColumn();
        left.setPreferredSize(new Dimension(340, 10));
        left.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.RULE),
                new EmptyBorder(Ui.GAP_L, Ui.GAP_XL, Ui.GAP_L, Ui.GAP_L)));
        body.add(left, BorderLayout.WEST);
        body.add(rulesColumn(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        load();
    }

    // ================================================================== layout

    /** The name of the rule set and the buttons for whole rule sets. */
    private JComponent header() {
        JPanel bar = new JPanel(new BorderLayout(Ui.GAP_L, 0));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.RULE),
                new EmptyBorder(Ui.GAP_L, Ui.GAP_XL, Ui.GAP_L, Ui.GAP_XL)));
        nameField.setName("ruleSetName");
        nameField.setFont(Ui.bold());
        onCommit(nameField, () -> {
            String n = nameField.getText().trim();
            if (n.equals(rules.name)) return;
            rules.name = n;
            changed();
        });
        bar.add(Ui.labeled("Rule set", nameField), BorderLayout.CENTER);
        JButton neu = Ui.button("New", "Starts an empty rule set.", e -> newRuleSet());
        neu.setName("newRuleSet");
        JButton open = Ui.button("Open…", "Opens a rule set file (" + TemplateFiles.RULES_EXT + ").", e -> open());
        open.setName("openRules");
        JButton save = Ui.button("Save…", "Saves the rule set with every template it needs; asks where, proposing the file "
                + "it was opened from or saved to last.", e -> save());
        save.setName("saveRules");
        bar.add(Ui.flow(Ui.GAP_S, neu, open, save), BorderLayout.EAST);
        return bar;
    }

    /** Every template of the library: filter, list, add, new, edit. */
    private JComponent libraryColumn() {
        JPanel col = new JPanel(new BorderLayout(0, Ui.GAP_M));
        col.setOpaque(false);
        VerticalStack top = new VerticalStack();
        Section s = new Section("Templates");
        top.addRow(s);
        filter.setName("templateFilter");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { listTemplates(); }
            @Override public void removeUpdate(DocumentEvent e) { listTemplates(); }
            @Override public void changedUpdate(DocumentEvent e) { listTemplates(); }
        });
        top.addRow(filter, 0);
        col.add(top, BorderLayout.NORTH);

        libraryList.setName("ruleTemplates");
        libraryList.setCellRenderer(panel.itemRenderer(this::useBadge));
        libraryList.setFixedCellHeight(46);
        libraryList.setFixedCellWidth(1);
        libraryList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);   // several become rules at once
        libraryList.getAccessibleContext().setAccessibleName("Templates of the library");
        ToolTipManager.sharedInstance().registerComponent(libraryList);
        libraryList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int i = libraryList.locationToIndex(e.getPoint());
                if (i >= 0 && libraryList.getCellBounds(i, i).contains(e.getPoint())) addRules(Collections.singletonList(libraryModel.get(i)));
            }
        });
        libraryList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "addRule");
        libraryList.getActionMap().put("addRule", PatternCanvas.action(() -> addRules(libraryList.getSelectedValuesList())));
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(new NestedScrollPane(libraryList), BorderLayout.CENTER);
        noTemplates.setVisible(false);
        center.add(noTemplates, BorderLayout.SOUTH);
        col.add(center, BorderLayout.CENTER);

        PrimaryButton add = Ui.primaryButton("Add as rule →", "Adds the chosen templates as rules (double-click or Enter "
                + "works too). The rule set keeps a copy of each, so it works without this library.",
                e -> addRules(libraryList.getSelectedValuesList()));
        add.setName("addRule");
        JButton newButton = Ui.button("New ▾", "Make a template here: blank, from the nodes selected in the graph, a composite "
                + "of other templates, or from a file. When you save it, it is put into the library and added as a rule.", null);
        newButton.setName("ruleNewTemplate");
        JPopupMenu menu = panel.newMenu(e -> follow(e, null, true));
        newButton.addActionListener(e -> menu.show(newButton, 0, newButton.getHeight()));
        JButton edit = Ui.button("Edit…", "Opens the chosen template in the template editor. When you save it, the rules of "
                + "this set that use it take the saved revision.", e -> editChosen());
        edit.setName("ruleEditTemplate");
        VerticalStack bottom = new VerticalStack();
        bottom.addRow(add, 0);
        bottom.addRow(Ui.row(newButton, edit), Ui.GAP_S);
        col.add(bottom, BorderLayout.SOUTH);
        return col;
    }

    /** The rules and the settings of the chosen one. */
    private JComponent rulesColumn() {
        VerticalStack col = new VerticalStack();
        col.setBorder(new EmptyBorder(Ui.GAP_L, Ui.GAP_L, Ui.GAP_L, Ui.GAP_XL));

        ruleList.setName("ruleList");
        ruleList.setCellRenderer(new RuleRenderer());
        ruleList.setFixedCellHeight(44);
        ruleList.setFixedCellWidth(1);
        ruleList.setVisibleRowCount(6);
        ruleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);   // the settings and the buttons act on one rule
        ruleList.getAccessibleContext().setAccessibleName("Rules");
        ruleList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showRule(); });
        ruleList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = ruleList.locationToIndex(e.getPoint());
                Rectangle cell = i < 0 ? null : ruleList.getCellBounds(i, i);
                if (cell == null || !cell.contains(e.getPoint()) || !SwingUtilities.isLeftMouseButton(e)) return;
                if (e.getX() - cell.x < BOX_COLUMN) toggle(ruleModel.get(i));
            }
        });
        for (int key : new int[]{KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE}) {
            ruleList.getInputMap().put(KeyStroke.getKeyStroke(key, 0), "removeRule");
        }
        ruleList.getActionMap().put("removeRule", PatternCanvas.action(this::removeRule));
        ruleList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleRule");
        ruleList.getActionMap().put("toggleRule", PatternCanvas.action(() -> {
            if (ruleList.getSelectedValue() != null) toggle(ruleList.getSelectedValue());
        }));
        ruleScroll = new NestedScrollPane(ruleList) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
        };
        rulesSection.addRow(noRules);
        rulesSection.addRow(ruleScroll, 0);
        up.setName("ruleUp");
        down.setName("ruleDown");
        remove.setName("removeRule");
        ruleButtons = rulesSection.addRow(Ui.flow(Ui.GAP_S, up, down, remove), Ui.GAP_S);
        col.addRow(rulesSection, 0);

        ruleOn.setOpaque(false);
        ruleOn.setName("ruleActive");
        ruleOn.setToolTipText(Ui.tooltip("Only active rules search for matches (the check box in the list switches, too)."));
        ruleOn.addActionListener(e -> edit(r -> r.enabled = ruleOn.isSelected()));
        ruleName.setName("ruleName");
        onCommit(ruleName, () -> {
            String n = ruleName.getText().trim();
            if (shown != null && !n.equals(shown.name)) edit(r -> r.name = n);
        });
        rulePriority.setName("rulePriority");
        rulePriority.setToolTipText(Ui.tooltip("Priority of every constraint this rule makes (it decides conflicts), or the template's own."));
        rulePriority.addActionListener(e -> {
            Object o = rulePriority.getSelectedItem();
            Priority p = o instanceof Priority ? (Priority) o : null;
            if (shown != null && p != shown.priority) edit(r -> r.priority = p);
        });
        editTemplate.setName("editRuleTemplate");
        update.setName("updateRuleTemplate");
        details.addRow(preview, 0);
        details.addRow(Ui.labeled("Name", ruleName), Ui.GAP_M);
        details.addRow(Ui.labeled("Priority", rulePriority), Ui.GAP_S);
        details.addRow(ruleOn, Ui.GAP_XS);
        details.addRow(ruleInfo, Ui.GAP_S);
        details.addRow(Ui.flow(Ui.GAP_S, editTemplate, update), Ui.GAP_S);
        ruleSection.addRow(chooseRule);
        ruleSection.addRow(details, 0);
        col.addRow(ruleSection, Ui.SECTION_GAP);
        col.setOpaque(false);
        gui.components.ScrollColumn scroll = new gui.components.ScrollColumn(col, 360);
        scroll.getViewport().getView().setBackground(Color.WHITE);   // one surface with the library column
        return scroll;
    }

    /** The search with the active rules and the status line. */
    private JComponent footer() {
        JPanel bar = new JPanel(new BorderLayout(Ui.GAP_L, 0));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.RULE),
                new EmptyBorder(Ui.GAP_M, Ui.GAP_XL, Ui.GAP_M, Ui.GAP_XL)));
        PrimaryButton find = new PrimaryButton("Find matches for active rules") {
            private static final long serialVersionUID = 1L;
            /** The whole text: the footer has room (a primary button is as wide as the tab's text at most). */
            @Override public Dimension getPreferredSize() {
                Insets in = getInsets();
                return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + in.left + in.right, super.getPreferredSize().height);
            }
        };
        find.setToolTipText(Ui.tooltip("Searches the active graph for every active rule; the matches appear in the Matches "
                + "list of the Templates page and on the graph. Nothing is applied yet."));
        find.addActionListener(e -> find());
        find.setName("findRuleMatches");
        bar.add(find, BorderLayout.WEST);
        status.setName("ruleSetStatus");
        bar.add(status, BorderLayout.CENTER);
        return bar;
    }

    /** Runs {@code commit} on Enter and when {@code f} loses the focus. */
    private static void onCommit(JTextField f, Runnable commit) {
        f.addActionListener(e -> commit.run());
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commit.run(); }
        });
    }

    // ================================================================== state shown

    /** The window title: the name of the rule set, marked while it has unsaved changes. */
    String title() {
        return "Rule set: " + displayName() + (dirty ? " (not saved)" : "");
    }

    private String displayName() {
        return rules.name == null || rules.name.trim().isEmpty() ? "Untitled rule set" : rules.name.trim();
    }

    /** The rule set in a line: its name and how many of its rules are active. */
    String summary() {
        int active = 0;
        for (RuleSystem.Rule r : rules.rules) if (r.enabled) active++;
        return rules.rules.isEmpty() ? displayName() + ": no rules yet. Edit the rule set to add templates of the library as rules."
                : displayName() + ": " + active + " of " + Ui.count(rules.rules.size(), "rule") + " active.";
    }

    /** The templates of the rules: the library first, then the copies the rule set carries. */
    private TemplateSource source() {
        return TemplateSource.chain(panel.library(), TemplateSource.of(rules));
    }

    /** The template of a rule, or null if neither the library nor the rule set has it. */
    private TemplateDef templateOf(RuleSystem.Rule r) {
        return r.template == null ? null : source().get(r.template);
    }

    /** The library was read anew (a template saved, a folder chosen): the list and the rule infos follow. */
    void libraryChanged() {
        listTemplates();
        refresh();
    }

    void refresh() {
        ruleList.repaint();
        libraryList.repaint();
        showRule();
    }

    /** Something of the rule set changed: marks it unsaved and tells the panel. */
    private void changed() {
        dirty = true;
        ruleList.repaint();
        libraryList.repaint();
        rulesSection.setTrailingText(rules.rules.isEmpty() ? null : activeCount() + " of " + rules.rules.size() + " active");
        onChanged.run();
    }

    private int activeCount() {
        int n = 0;
        for (RuleSystem.Rule r : rules.rules) if (r.enabled) n++;
        return n;
    }

    /** Lists the library's templates that match the filter, keeping the chosen ones. */
    private void listTemplates() {
        Set<String> keep = new HashSet<>();
        for (TemplatePanel.Item it : libraryList.getSelectedValuesList()) if (it.template != null) keep.add(it.template.id);
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        libraryModel.clear();
        List<TemplatePanel.Item> all = panel.libraryItems();
        for (TemplatePanel.Item it : all) {
            if (f.isEmpty() || matches(it, f)) libraryModel.addElement(it);
        }
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < libraryModel.size(); i++) {
            TemplateDef t = libraryModel.get(i).template;
            if (t != null && keep.contains(t.id)) sel.add(i);
        }
        int[] idx = new int[sel.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = sel.get(i);
        libraryList.setSelectedIndices(idx);
        noTemplates.setVisible(libraryModel.isEmpty());
        noTemplates.setText(Ui.wrapped(all.isEmpty() ? "The library is empty: make a template with New."
                : "No template's name contains \"" + Ui.esc(filter.getText().trim()) + "\".", 300));
    }

    private static boolean matches(TemplatePanel.Item it, String f) {
        if (it.toString().toLowerCase(Locale.ROOT).contains(f)) return true;
        return it.template != null && it.template.description != null && it.template.description.toLowerCase(Locale.ROOT).contains(f);
    }

    /** The badge of a library entry that rules of this set use, or null. */
    private String useBadge(TemplatePanel.Item it) {
        if (it.template == null) return null;
        int n = usersOf(it.template.id).size();
        return n == 0 ? null : n == 1 ? "✓ rule" : "✓ " + n + " rules";
    }

    /** The rules that use the template with this id (in any revision). */
    private List<RuleSystem.Rule> usersOf(String id) {
        List<RuleSystem.Rule> l = new ArrayList<>();
        if (id == null) return l;
        for (RuleSystem.Rule r : rules.rules) if (r.template != null && id.equals(r.template.id)) l.add(r);
        return l;
    }

    /** Shows the whole rule set anew (made, opened), choosing its first rule. */
    private void load() {
        nameField.setText(rules.name);
        ruleModel.clear();
        for (RuleSystem.Rule r : rules.rules) ruleModel.addElement(r);
        if (!ruleModel.isEmpty()) ruleList.setSelectedIndex(0);
        showRules();
        showRule();
        rulesSection.setTrailingText(rules.rules.isEmpty() ? null : activeCount() + " of " + rules.rules.size() + " active");
        libraryList.repaint();
        onChanged.run();
    }

    /** Without rules the list gives way to a hint. */
    private void showRules() {
        boolean any = !ruleModel.isEmpty();
        noRules.setVisible(!any);
        ruleScroll.setVisible(any);
        ruleButtons.setVisible(any);
        ruleList.setVisibleRowCount(Math.max(3, Math.min(6, ruleModel.size())));
        int i = ruleList.getSelectedIndex();
        up.setEnabled(i > 0);
        down.setEnabled(i >= 0 && i < ruleModel.size() - 1);
        remove.setEnabled(i >= 0);
        revalidate();
    }

    private void showRule() {
        RuleSystem.Rule r = ruleList.getSelectedValue();
        shown = r;
        showRules();
        chooseRule.setVisible(r == null);
        details.setVisible(r != null);
        ruleSection.setVisible(!ruleModel.isEmpty());
        if (r == null) {
            update.setVisible(false);
            revalidate();
            repaint();
            return;
        }
        updating = true;
        try {
            ruleName.setText(r.name);
            ruleOn.setSelected(r.enabled);
            rulePriority.setSelectedItem(r.priority == null ? KEEP : r.priority);
        } finally {
            updating = false;
        }
        TemplateDef t = templateOf(r);
        preview.show(t);
        TemplateLibrary lib = panel.library();
        TemplateDef newest = lib == null || r.template == null ? null : lib.latest(r.template.id);
        StringBuilder sb = new StringBuilder("<b>").append(Ui.esc(t != null ? t.name : r.template == null ? "?" : r.template.name))
                .append("</b> · rev. ").append(r.template == null ? "?" : String.valueOf(r.template.revision));
        if (t != null) {
            sb.append(" · ").append(t.isComposite() ? "composite of " + Ui.count(t.components.size(), "part")
                    : Ui.count(t.nodes.size(), "node") + ", " + Ui.count(t.constraints.size(), "constraint"));
            for (TemplateValidator.Problem p : TemplateValidator.validate(t, TemplateSource.chain(source(), TemplateSource.embeddedIn(t)))) {
                String color = p.severity == TemplateValidator.Severity.WARNING ? "#b06000" : "#c5221f";
                sb.append("<br><font color='").append(color).append("'>").append(Ui.esc(p.toString())).append("</font>");
            }
        } else {
            sb.append("<br><font color='#c5221f'>Not available: the rule cannot find matches.</font>");
        }
        boolean newer = newest != null && t != null && newest.revision > t.revision;
        if (newer) sb.append("<br><font color='#2563eb'>Revision ").append(newest.revision).append(" is in the library.</font>");
        ruleInfo.setText(Ui.wrapped(sb.toString(), TEXT));
        update.setText(newer ? "Use revision " + newest.revision : "Use newest revision");
        update.setVisible(newer);
        editTemplate.setEnabled(t != null);
        revalidate();
        repaint();
    }

    // ================================================================== editing rules

    private void edit(Consumer<RuleSystem.Rule> e) {
        if (updating || shown == null) return;
        e.accept(shown);
        changed();
    }

    private void toggle(RuleSystem.Rule r) {
        r.enabled = !r.enabled;
        if (r == shown) {
            updating = true;
            ruleOn.setSelected(r.enabled);
            updating = false;
        }
        changed();
        status.setStatus("Rule '" + r.name + "' is " + (r.enabled ? "active." : "off."), StatusLabel.Kind.INFO);
    }

    /** Adds the templates of these library entries as rules, in their order; chooses the last one added. */
    void addRules(List<TemplatePanel.Item> items) {
        List<String> added = new ArrayList<>(), unreadable = new ArrayList<>();
        RuleSystem.Rule last = null;
        for (TemplatePanel.Item it : items) {
            if (it.template == null) {
                unreadable.add(it.toString());
                continue;
            }
            last = addRule(it.template);
            added.add(last.name);
        }
        if (last == null) {
            status.setStatus(unreadable.isEmpty() ? "Choose one or more templates on the left first."
                    : "Cannot be read, not added: " + String.join(", ", unreadable), StatusLabel.Kind.ERROR);
            return;
        }
        ruleList.setSelectedValue(last, true);
        ruleList.requestFocusInWindow();
        String text = added.size() == 1 ? "Added rule '" + added.get(0) + "'." : "Added " + added.size() + " rules: " + String.join(", ", added) + ".";
        if (!unreadable.isEmpty()) text += " Cannot be read: " + String.join(", ", unreadable) + ".";
        status.setStatus(text, unreadable.isEmpty() ? StatusLabel.Kind.SUCCESS : StatusLabel.Kind.ERROR);
    }

    /** Adds {@code t} as a rule at the end of the list, named after it (numbered if that name is taken). */
    private RuleSystem.Rule addRule(TemplateDef t) {
        RuleSystem.Rule r = new RuleSystem.Rule(rules.nextRuleId(), freeName(t.name), t.ref());
        rules.rules.add(r);
        embed(t, TemplateSource.chain(panel.library(), TemplateSource.embeddedIn(t)));
        ruleModel.addElement(r);
        changed();
        return r;
    }

    private String freeName(String base) {
        Set<String> names = new HashSet<>();
        for (RuleSystem.Rule r : rules.rules) names.add(r.name);
        if (!names.contains(base)) return base;
        for (int k = 2; ; k++) if (!names.contains(base + " " + k)) return base + " " + k;
    }

    private void removeRule() {
        RuleSystem.Rule r = ruleList.getSelectedValue();
        if (r == null) return;
        int i = ruleList.getSelectedIndex();
        rules.rules.remove(r);
        ruleModel.removeElement(r);
        if (!ruleModel.isEmpty()) ruleList.setSelectedIndex(Math.min(i, ruleModel.size() - 1));
        showRule();
        changed();
        status.setStatus("Removed rule '" + r.name + "'.", StatusLabel.Kind.INFO);
    }

    private void move(int d) {
        int i = ruleList.getSelectedIndex();
        int j = i + d;
        if (i < 0 || j < 0 || j >= rules.rules.size()) return;
        RuleSystem.Rule r = rules.rules.remove(i);
        rules.rules.add(j, r);
        ruleModel.remove(i);
        ruleModel.add(j, r);
        ruleList.setSelectedIndex(j);
        ruleList.ensureIndexIsVisible(j);
        changed();
    }

    /** Uses the library's newest revision of the rule's template, after checking it. */
    private void updateTemplate() {
        RuleSystem.Rule r = ruleList.getSelectedValue();
        if (r == null || r.template == null || panel.library() == null) return;
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
        changed();
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

    // ================================================================== editing templates

    /** Opens the template chosen on the left in the template editor; the rules that use it follow its saves. */
    private void editChosen() {
        TemplatePanel.Item it = libraryList.getSelectedValue();
        if (it == null || it.template == null) {
            status.setStatus(it == null ? "Choose a template on the left first." : "This file cannot be read.", StatusLabel.Kind.ERROR);
            return;
        }
        follow(panel.edit(it, null), it.template.id, false);
    }

    /** Opens the template of the chosen rule in the template editor; the rules that use it follow its saves. */
    private void editRuleTemplate() {
        RuleSystem.Rule r = ruleList.getSelectedValue();
        TemplateDef t = r == null ? null : templateOf(r);
        if (t == null) return;
        TemplatePanel.Item it = panel.item(t.id);
        TemplateEditor e;
        if (it != null && it.template != null && it.template.revision >= t.revision) {
            // the library's revision (an example is edited as a copy); a newer one than the rule's is the one to change
            String note = it.template.revision > t.revision ? "The rule uses revision " + t.revision + "; this is revision "
                    + it.template.revision + " of the library." : null;
            e = panel.edit(it, note);
        } else {
            e = panel.editRevision(t, "This revision came with the rule set" + (it == null ? "; the library does not have this template."
                    : "; the library has an older one.") + " Saving puts the changed template into the library.");
        }
        follow(e, t.id, false);
    }

    /**
     * The rules of this set follow the saves of template editor {@code e}: after each save with Save (not "Save as
     * copy") every rule that uses the template {@code id} (in any revision) uses the saved revision. Saving an example
     * or an import for the first time gives it an id of its own: the rules follow that id from then on. With
     * {@code add} (a template made here), the first save adds it as a rule if no rule uses it.
     */
    private void follow(TemplateEditor e, String id, boolean add) {
        if (e == null) return;
        String[] watched = {id};
        boolean[] mayAdd = {add};
        e.follow(FOLLOW, saved -> {
            List<RuleSystem.Rule> users = usersOf(watched[0]);
            for (RuleSystem.Rule r : usersOf(saved.id)) if (!users.contains(r)) users.add(r);
            watched[0] = saved.id;
            if (users.isEmpty()) {
                if (mayAdd[0]) {
                    mayAdd[0] = false;
                    RuleSystem.Rule r = addRule(saved);
                    ruleList.setSelectedValue(r, true);
                    status.setStatus("Saved '" + saved.name + "' and added it as rule '" + r.name + "'.", StatusLabel.Kind.SUCCESS);
                }
                return;
            }
            mayAdd[0] = false;
            boolean moved = false;
            for (RuleSystem.Rule r : users) {
                if (saved.ref().equals(r.template)) continue;
                r.template = saved.ref();
                moved = true;
            }
            if (!moved) return;
            embed(saved, TemplateSource.chain(panel.library(), TemplateSource.embeddedIn(saved)));
            changed();
            showRule();
            status.setStatus((users.size() == 1 ? "Rule '" + users.get(0).name + "' uses" : users.size() + " rules use") + " '"
                    + saved.name + "' revision " + saved.revision + " now.", StatusLabel.Kind.SUCCESS);
        });
    }

    // ================================================================== search

    /** Searches the active graph for every active rule; returns null if the search started, else why not. */
    String find() {
        List<SearchTask> tasks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (RuleSystem.Rule r : rules.rules) {
            if (!r.enabled) continue;
            TemplateDef t = templateOf(r);
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
        this.skipped = skipped.isEmpty() ? null : String.join(", ", skipped);
        if (tasks.isEmpty()) {
            String why = rules.rules.isEmpty() ? "The rule set has no rules yet." : "No active rule can search" + (skipped.isEmpty() ? "." : ": " + String.join(", ", skipped));
            status.setStatus(why, StatusLabel.Kind.ERROR);
            return why;
        }
        String why = panel.startSearch(tasks);
        if (why != null) {
            status.setStatus("Not searched: " + why, StatusLabel.Kind.ERROR);
            return why;
        }
        status.setStatus("Searching " + Ui.count(tasks.size(), "rule") + "; the matches appear on the graph and on the Templates page"
                + (skipped.isEmpty() ? "." : ". Skipped: " + String.join(", ", skipped)),
                skipped.isEmpty() ? StatusLabel.Kind.INFO : StatusLabel.Kind.ERROR);
        return null;
    }

    /** The active rules the last search left out, with the reason, or null if it searched for all of them. */
    String skipped() {
        return skipped;
    }

    // ================================================================== files

    /** True if the rule set may be replaced: it has no unsaved rules, or the user lets them go. */
    private boolean mayDiscard(String action) {
        if (!dirty || rules.rules.isEmpty()) return true;
        return JOptionPane.showConfirmDialog(this, "'" + displayName() + "' has changes that are not saved. " + action + " anyway?",
                "Rule set", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void newRuleSet() {
        if (!mayDiscard("Start a new rule set")) return;
        rules = new RuleSystem();
        file = null;
        dirty = false;
        load();
        status.setStatus("New rule set: add templates from the left.", StatusLabel.Kind.INFO);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void open() {
        if (!mayDiscard("Open another rule set")) return;
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : panel.library() == null ? null : panel.library().getFolder());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            rules = TemplateFiles.loadRules(fc.getSelectedFile());
            file = fc.getSelectedFile();
            dirty = false;
            load();
            status.setStatus("Opened '" + displayName() + "' (" + Ui.count(rules.rules.size(), "rule") + "). The graph is not changed.", StatusLabel.Kind.SUCCESS);
        } catch (IOException | TemplateCodec.FormatException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Open rule set", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        rules.name = nameField.getText().trim().isEmpty() ? "Untitled rule set" : nameField.getText().trim();
        // always asks (the button says so), proposing the file of the rule set: saving under another name is possible too
        JFileChooser fc = new JFileChooser(file != null ? file.getParentFile() : panel.library() == null ? null : panel.library().getFolder());
        fc.setSelectedFile(file != null ? file : new File(fc.getCurrentDirectory(), TemplateFiles.fileName(rules.name, TemplateFiles.RULES_EXT)));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = fc.getSelectedFile();
        if (!target.getName().endsWith(TemplateFiles.RULES_EXT)) target = new File(target.getParentFile(), target.getName() + TemplateFiles.RULES_EXT);
        if (target.exists() && !target.equals(file) && JOptionPane.showConfirmDialog(this, target.getName() + " exists. Replace it?",
                "Save rule set", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        keepNeededTemplates();
        try {
            TemplateFiles.saveRules(rules, target);
            file = target;
            dirty = false;
            onChanged.run();
            status.setStatus("Saved " + target.getName() + " with " + Ui.count(rules.templates.size(), "template") + ".", StatusLabel.Kind.SUCCESS);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save rule set", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Before saving: the rule set carries exactly the templates its rules use and theirs, so revisions left behind by
     * changed or removed rules do not travel along. A rule whose template is nowhere keeps its entry (there is none).
     */
    private void keepNeededTemplates() {
        Set<String> keep = new HashSet<>();
        for (RuleSystem.Rule r : rules.rules) {
            TemplateDef t = templateOf(r);
            if (t == null) continue;
            TemplateSource s = TemplateSource.chain(source(), TemplateSource.embeddedIn(t));
            embed(t, s);
            for (TemplateDef d : Templates.closure(t, s)) keep.add(d.id + "@" + d.revision);
        }
        rules.templates.keySet().retainAll(keep);
    }

    // ================================================================== rendering

    /** A text field that shows a muted hint while it is empty. */
    private static final class HintField extends JTextField {
        private static final long serialVersionUID = 1L;
        private final String hint;

        HintField(String hint) {
            super(2);
            this.hint = hint;
            getAccessibleContext().setAccessibleDescription(hint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty()) return;
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(Ui.MUTED);
            g2.setFont(getFont());
            Insets in = getInsets();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(hint, in.left + 2, in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** The template of the chosen rule, drawn as in the library but larger. */
    private final class Preview extends JComponent {
        private static final long serialVersionUID = 1L;
        private TemplateDef t;

        Preview() {
            setPreferredSize(new Dimension(40, 110));
        }

        void show(TemplateDef t) {
            this.t = t;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            Rectangle2D r = new Rectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1);
            g2.setColor(Color.WHITE);
            g2.fill(r);
            g2.setColor(Ui.BORDER);
            g2.draw(r);
            if (t != null) {
                PatternPainter.paintPreview(g2, t, TemplateSource.chain(source(), TemplateSource.embeddedIn(t)),
                        new Rectangle2D.Double(8, 8, getWidth() - 16, getHeight() - 16));
            } else {
                g2.setColor(Ui.MUTED);
                g2.setFont(Ui.small());
                String s = "Template not available";
                g2.drawString(s, (getWidth() - g2.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
            }
            g2.dispose();
        }
    }

    /** Rule row: on/off box, preview, name, template and priority. */
    private final class RuleRenderer extends JPanel implements ListCellRenderer<RuleSystem.Rule> {
        private static final long serialVersionUID = 1L;
        private final JCheckBox box = new JCheckBox();
        private final JLabel head = new JLabel(), body = new JLabel();
        private TemplateDef t;

        RuleRenderer() {
            super(new BorderLayout(Ui.GAP_S, 0));
            setBorder(new EmptyBorder(2, 2, 2, 6));
            box.setOpaque(false);
            box.setBorder(new EmptyBorder(0, 0, 0, 0));
            box.setPreferredSize(new Dimension(BOX_COLUMN - 2, box.getPreferredSize().height));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            text.setBorder(new EmptyBorder(0, THUMB + Ui.GAP_M, 0, 0));
            head.setFont(Ui.bold());
            body.setFont(Ui.small());
            text.add(head);
            text.add(body);
            add(box, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends RuleSystem.Rule> l, RuleSystem.Rule r, int index, boolean selected, boolean focus) {
            t = templateOf(r);
            box.setSelected(r.enabled);
            head.setText(r.name == null || r.name.isEmpty() ? "(no name)" : r.name);
            List<String> b = new ArrayList<>();
            boolean bad = false, newer = false;
            if (t == null) {
                b.add("template missing");
                bad = true;
            } else {
                b.add(t.name + " · rev. " + t.revision);
                if (!TemplateValidator.canApply(TemplateValidator.validate(t, TemplateSource.chain(source(), TemplateSource.embeddedIn(t))))) {
                    b.add("has errors");
                    bad = true;
                }
                TemplateDef newest = panel.library() == null ? null : panel.library().latest(t.id);
                newer = newest != null && newest.revision > t.revision;
                if (newer) b.add("rev. " + newest.revision + " available");
            }
            b.add(r.priority == null ? "template priority" : r.priority.label());
            body.setText(String.join(" · ", b));
            setBackground(selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            head.setForeground(selected ? l.getSelectionForeground() : r.enabled ? Ui.TEXT : Ui.MUTED);
            body.setForeground(selected ? l.getSelectionForeground() : bad ? Ui.ERROR : newer ? Ui.ACCENT : Ui.MUTED);
            setOpaque(true);
            setToolTipText(Ui.tooltip(Ui.esc(r.name) + (t == null ? "" : "<br>" + Ui.esc(t.description == null ? "" : t.description))));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = Ui.smooth(g);
            Rectangle2D r = new Rectangle2D.Double(BOX_COLUMN + 2, 4, THUMB, getHeight() - 8);
            g2.setColor(Color.WHITE);
            g2.fill(r);
            g2.setColor(Ui.BORDER);
            g2.draw(r);
            if (t != null) PatternPainter.paintPreview(g2, t, TemplateSource.chain(source(), TemplateSource.embeddedIn(t)), r);
            g2.dispose();
        }
    }
}
