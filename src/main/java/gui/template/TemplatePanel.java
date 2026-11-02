package gui.template;

import constraints.ConstraintStore;
import gui.ConstraintCatalog;
import gui.DisplayConstraints;
import gui.ConstraintsTab;
import gui.components.PrimaryButton;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.SwitchToggle;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateCapture;
import layout.template.apply.TemplateInstance;
import layout.template.io.TemplateExamples;
import layout.template.io.TemplateFiles;
import layout.template.io.TemplateLibrary;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.search.Match;
import layout.template.search.MatchCheck;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;
import layout.template.search.TemplateLimits;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Content of the main tab "Templates" ({@link gui.TemplatesTab}): template library, search for matches, found matches, applied instances
 * and rule set. Finding matches never changes the graph; only the apply buttons (here or in a match's popover) change
 * the layout, as one undoable step.
 *
 * @author Niklas Gröne
 */
public class TemplatePanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Preferences PREFS = Preferences.userNodeForPackage(TemplatePanel.class);
    /** Width of the check box column of the match list. */
    private static final int BOX_COLUMN = 28;
    private static TemplatePanel instance;

    /** Shows a message in the status line of the panel (popover actions report here). */
    static void status(String message) {
        if (instance != null && message != null) instance.searchStatus.setStatus(message, StatusLabel.Kind.SUCCESS);
    }

    /** The constraints of the store changed (deleted, switched, solved): the instance states and match checks follow. */
    public static void constraintsChanged() {
        TemplateWorkbench.storeChanged();
        if (instance != null) instance.instanceList.repaint();
    }

    // ---- library
    private TemplateLibrary library;
    private final DefaultListModel<Item> libraryModel = new DefaultListModel<>();
    private final JList<Item> libraryList = new JList<Item>(libraryModel) {
        private static final long serialVersionUID = 1L;
        @Override public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
            return Ui.tooltip(describe(libraryModel.get(i)));
        }
    };
    /** Problems of the chosen template and updates of its components; hidden while there are none. */
    private final JLabel templateInfo = Ui.hint(" ");
    /** The library folder, under the list. */
    private final JLabel folderLabel = new JLabel(" ");

    // ---- search
    private final JCheckBox structureOnly = new JCheckBox("Structure only (no conditions)");
    private final JCheckBox withinSelection = new JCheckBox("Only among the selected nodes");
    private final JCheckBox exact = new JCheckBox("Exact structure within a match");
    private final JCheckBox place = new JCheckBox("Place matched nodes like the template", true);
    private final Section options = Section.collapsible("Options", false);
    private final PrimaryButton findButton = new PrimaryButton("Find matches");
    private final StatusLabel searchStatus = new StatusLabel(" ");
    /** After a search: the graph changed since, or the search stopped at its limit; with the links that act on it. */
    private final JLabel notice = Ui.hint(" ");
    private final JButton searchAgain = Ui.link("Search again", "The same search anew (the same template or rules, options "
            + "and nodes).", e -> searchAgain());
    private final JButton findMore = Ui.link("Find more", null, e -> findMore());
    private final JPanel noticeLinks = Ui.flow(Ui.GAP_M, searchAgain, findMore);

    // ---- matches
    private final Section matchesSection = new Section("Matches");
    private final DefaultListModel<Match> matchModel = new DefaultListModel<>();
    /** A press in a row's check box column selects the match for applying: it does not open it. */
    private boolean pressOnBox;
    private final JList<Match> matchList = new JList<Match>(matchModel) {
        private static final long serialVersionUID = 1L;
        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) pressOnBox = onCheckBox(e.getPoint());
            super.processMouseEvent(e);
            // only the selection this press makes is one for applying: the arrow keys open matches again afterwards
            if (e.getID() == MouseEvent.MOUSE_RELEASED) pressOnBox = false;
        }
    };
    private JScrollPane matchScroll;
    private final JLabel noMatches = Ui.hint("No matches yet: choose a template in the library and find where it fits.");
    private final JLabel pageLabel = new JLabel(" ");
    private final JButton prevPage = Ui.button("◀", "Previous matches", null), nextPage = Ui.button("▶", "Next matches", null);
    private JPanel pager;
    /** "3 of 12 selected" with the links that select all or none, and the one that drops the results. */
    private final JLabel selectedLabel = new JLabel(" ");
    private final JButton selectAll = Ui.link("All", "Selects every match of the search for applying (not only the shown ones).", null);
    private final JButton clearSel = Ui.link("None", "Deselects all matches.", null);
    private final JButton clearResults = Ui.link("Clear results", "Drops the matches of this search from the list and from "
            + "the graph; the applied instances stay.", null);
    private JPanel selectionRow;
    private final PrimaryButton applySelected = new PrimaryButton("Apply selected");
    private boolean updatingMatches;

    // ---- instances
    private final Section instanceSection = new Section("Applied instances");
    private final DefaultListModel<TemplateInstance> instanceModel = new DefaultListModel<>();
    private final JList<TemplateInstance> instanceList = new JList<>(instanceModel);
    private JScrollPane instanceScroll;
    private final VerticalStack partsBox = new VerticalStack();
    private final JLabel instanceInfo = Ui.hint(" ");
    private boolean updatingInstances;

    // ---- rule set: edited in a window of its own, used from here
    private final RuleSetSection ruleSection;
    private JFrame ruleWindow;
    private final JLabel ruleSummary = Ui.hint(" ");
    /** What the last search with the rules left out (shown only then). */
    private final StatusLabel ruleStatus = new StatusLabel(" ");

    /** One entry of the library list. */
    static final class Item {
        final TemplateDef template;
        final File file;
        final String error;
        final boolean example;

        Item(TemplateDef template, File file, String error, boolean example) {
            this.template = template;
            this.file = file;
            this.error = error;
            this.example = example;
        }

        @Override
        public String toString() {
            return template != null ? template.name : file == null ? "?" : file.getName();
        }
    }

    public TemplatePanel() {
        super(new BorderLayout());
        instance = this;
        setOpaque(false);
        ruleSection = new RuleSetSection(this);
        VerticalStack sections = new VerticalStack();
        sections.addRow(createLibrarySection(), 0);
        sections.addRow(createSearchSection(), Ui.SECTION_GAP);
        sections.addRow(createMatchesSection(), Ui.SECTION_GAP);
        sections.addRow(createInstanceSection(), Ui.SECTION_GAP);
        sections.addRow(createRuleSection(), Ui.SECTION_GAP);
        ruleSection.onChanged = this::showRuleSummary;
        add(sections, BorderLayout.CENTER);
        String folder = PREFS.get("folder", null);
        setFolder(folder == null ? null : new File(folder));
        TemplateWorkbench.get().addListener(this::refresh);
        InstanceRegistry.addListener(() -> SwingUtilities.invokeLater(this::refreshInstances));
        refresh();
    }

    // ================================================================== library

    private Section createLibrarySection() {
        Section s = new Section("Library");
        libraryList.setName("templateLibrary");
        libraryList.setCellRenderer(new ItemRenderer());
        libraryList.setFixedCellHeight(46);
        libraryList.setFixedCellWidth(1);
        libraryList.setVisibleRowCount(5);
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) templateSelected(); });
        libraryList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) editSelected(); }
        });
        libraryList.getAccessibleContext().setAccessibleName("Templates");
        ToolTipManager.sharedInstance().registerComponent(libraryList);
        s.addRow(scroll(libraryList));
        s.addRow(templateInfo, Ui.GAP_XS);

        // New: the three ways to make a template, in one menu
        JButton newButton = Ui.button("New ▾", "Make a template: from the nodes selected in the graph, as a composite of "
                + "other templates, or from a file.", null);
        newButton.setName("newTemplate");
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("From selection", "newFromSelection", "A new template from the selected nodes, their edges and the "
                + "constraints that lie completely inside the selection. Opens the editor.", this::newFromSelection));
        menu.add(menuItem("Composite of templates", "newComposite", "A template made of other templates: connect their ports "
                + "and add layout relations.", this::newComposite));
        menu.add(menuItem("Import file…", "importTemplate", "Adds a template file (" + TemplateFiles.TEMPLATE_EXT
                + " or old .gml) from anywhere.", this::importFile));
        newButton.addActionListener(e -> menu.show(newButton, 0, newButton.getHeight()));
        JButton edit = Ui.button("Edit…", "Opens the chosen template in the editor (double-click works too).", e -> editSelected());
        edit.setName("editTemplate");
        s.addRow(Ui.row(newButton, edit), Ui.GAP_S);

        folderLabel.setFont(Ui.small());
        folderLabel.setForeground(Ui.MUTED);
        JButton change = Ui.link("Change…", "Choose the folder that holds your template files (" + TemplateFiles.TEMPLATE_EXT
                + "; old GML templates are imported).", e -> chooseFolder());
        change.setName("chooseFolder");
        JPanel folderRow = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        folderRow.setOpaque(false);
        folderRow.add(folderLabel, BorderLayout.CENTER);
        folderRow.add(change, BorderLayout.EAST);
        s.addRow(folderRow, Ui.GAP_S);
        return s;
    }

    private static JMenuItem menuItem(String text, String name, String tooltip, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setName(name);
        item.setToolTipText(Ui.tooltip(tooltip));
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Name, description and size of a library entry (its tooltip). */
    private static String describe(Item it) {
        if (it.template == null) return "<b>" + Ui.esc(it.toString()) + "</b><br>" + Ui.esc(it.error);
        TemplateDef t = it.template;
        StringBuilder sb = new StringBuilder("<b>").append(Ui.esc(t.name)).append("</b>");
        if (t.description != null && !t.description.isEmpty()) sb.append("<br>").append(Ui.esc(t.description));
        sb.append("<br>").append(t.isComposite() ? Ui.count(t.components.size(), "component") + ", " + Ui.count(t.links.size(), "link")
                + ", " + Ui.count(t.relations.size(), "layout relation")
                : Ui.count(t.nodes.size(), "node") + ", " + Ui.count(t.edges.size(), "edge") + ", " + Ui.count(t.constraints.size(), "constraint"));
        if (!t.ports.isEmpty()) sb.append(", ").append(Ui.count(t.ports.size(), "port"));
        if (it.file != null) sb.append("<br><font color='#5f6368'>").append(Ui.esc(it.file.getName())).append("</font>");
        return sb.toString();
    }

    TemplateLibrary library() {
        return library;
    }

    /** The template source for restoring saved instances (null before the Templates page exists). */
    public static TemplateSource templateSource() {
        return instance == null ? null : instance.library;
    }

    /** The library, followed by the dependencies embedded in {@code t}. */
    private TemplateSource sourceFor(TemplateDef t) {
        return TemplateSource.chain(library, TemplateSource.embeddedIn(t));
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (library != null && library.getFolder() != null) fc.setSelectedFile(library.getFolder());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        setFolder(fc.getSelectedFile());
    }

    void setFolder(File folder) {
        if (folder != null && !folder.isDirectory()) folder = null;
        library = new TemplateLibrary(folder);
        for (TemplateDef t : TemplateExamples.all()) library.addInMemory(t);
        folderLabel.setText(folder == null ? "No folder: only the examples are listed." : "Folder: " + folder.getName());
        folderLabel.setToolTipText(folder == null ? "Choose a folder to keep your own templates." : Ui.tooltip(Ui.esc(folder.getAbsolutePath())));
        if (folder != null) PREFS.put("folder", folder.getAbsolutePath());
        reloadLibrary(null);
    }

    /** Lists the library again and selects the template with {@code selectId} (or keeps the selection). */
    void reloadLibrary(String selectId) {
        Item sel = libraryList.getSelectedValue();
        String keep = selectId != null ? selectId : sel != null && sel.template != null ? sel.template.id : null;
        library.reload();
        libraryModel.clear();
        for (TemplateLibrary.Entry e : library.entries()) libraryModel.addElement(new Item(e.template, e.file, e.error, false));
        Set<String> examples = new HashSet<>();
        for (TemplateDef t : TemplateExamples.all()) examples.add(t.id);
        // saved elsewhere than the library folder (or no folder chosen): listed while the session lasts
        for (TemplateDef t : library.templates()) {
            if (library.find(t.id) == null && !examples.contains(t.id) && library.latest(t.id) == t) {
                libraryModel.addElement(new Item(t, null, null, false));
            }
        }
        for (TemplateDef t : TemplateExamples.all()) libraryModel.addElement(new Item(t, null, null, true));
        int idx = 0;
        for (int i = 0; i < libraryModel.size(); i++) {
            TemplateDef t = libraryModel.get(i).template;
            if (t != null && t.id.equals(keep)) idx = i;
        }
        if (!libraryModel.isEmpty()) libraryList.setSelectedIndex(idx);
        ruleSection.libraryChanged();
    }

    TemplateDef selectedTemplate() {
        Item i = libraryList.getSelectedValue();
        return i == null ? null : i.template;
    }

    /** Under the list: the problems of the chosen template and the updates of its components, if any. */
    private void templateSelected() {
        Item i = libraryList.getSelectedValue();
        StringBuilder sb = new StringBuilder();
        if (i != null && i.template == null) {
            sb.append("<font color='#c5221f'>").append(Ui.esc(i.error)).append("</font>");
        } else if (i != null) {
            TemplateDef t = i.template;
            for (TemplateValidator.Problem p : TemplateValidator.validate(t, sourceFor(t))) {
                String color = p.severity == TemplateValidator.Severity.WARNING ? "#b06000" : "#c5221f";
                sb.append(sb.length() > 0 ? "<br>" : "").append("<font color='").append(color).append("'>").append(Ui.esc(p.toString())).append("</font>");
            }
            if (t.isComposite()) {
                for (TemplateLibrary.Update u : library.updatesFor(t)) {
                    sb.append(sb.length() > 0 ? "<br>" : "").append("<font color='#2563eb'>'").append(Ui.esc(u.component.name)).append("' uses ")
                            .append(Ui.esc(u.component.ref.toString())).append("; rev. ").append(u.newer.revision)
                            .append(" is available (update in the editor).</font>");
                }
            }
        }
        templateInfo.setVisible(sb.length() > 0);
        templateInfo.setText(sb.length() == 0 ? " " : Ui.wrapped(sb.toString(), Ui.TEXT_WIDTH));
    }

    private void editSelected() {
        Item i = libraryList.getSelectedValue();
        if (i == null || i.template == null) {
            JOptionPane.showMessageDialog(this, "Choose a template in the list first.", "Edit template", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String banner = i.example ? "This is a prepared example. Saving stores your own copy in the library folder." : null;
        File file = i.example || (i.file != null && TemplateFiles.isGmlFile(i.file)) ? null : i.file;
        TemplateDef t = i.template.copy();
        if (i.file != null && TemplateFiles.isGmlFile(i.file)) {
            t.id = TemplateDef.newId();   // a GML import becomes a template of its own on saving
            banner = t.note;
        }
        if (i.example) {
            t.id = TemplateDef.newId();
            t.revision = 1;
            t.source = "editor";
        }
        // An example or a GML file is edited as a copy under a new id: its window has a key of its own (choosing the
        // entry again finds it, editing the original from a composite does not). A template of the library (also one
        // held in memory only) is a stored revision, the copy is new.
        boolean gml = i.file != null && TemplateFiles.isGmlFile(i.file);
        boolean copy = i.example || gml;
        TemplateEditor.open(t, copy ? "copy:" + i.template.id : i.template.id, !copy, file, library, banner, this::saved);
    }

    private void newFromSelection() {
        EditorSession s = TemplateWorkbench.activeSession();
        if (s == null || s.getGraph() == null) {
            JOptionPane.showMessageDialog(this, "Open a graph and select the nodes of the new template first.", "New template",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Node> sel = new ArrayList<>(GraphHelper.getSelectedNodes(s));
        if (sel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select the nodes of the new template in the graph first.", "New template",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        TemplateCapture.Result r = TemplateCapture.capture(s.getGraph(), sel, ConstraintStore.constraints, ConstraintCatalog::describe,
                "Template from selection");
        StringBuilder banner = new StringBuilder();
        banner.append(Ui.count(sel.size(), "node")).append(", ").append(Ui.count(r.template.edges.size(), "edge")).append(" and ")
                .append(Ui.count(r.taken, "constraint")).append(" taken over.");
        if (!r.notTaken.isEmpty()) {
            banner.append(" Not taken over (").append(r.notTaken.size()).append("): ");
            for (int k = 0; k < r.notTaken.size() && k < 6; k++) banner.append(k > 0 ? "; " : "").append(r.notTaken.get(k));
            if (r.notTaken.size() > 6) banner.append("; …");
        }
        if (r.taken == 0) banner.append(" Add constraints, or derive them from the arrangement.");
        TemplateEditor.open(r.template, null, library, banner.toString(), this::saved);
    }

    private void newComposite() {
        TemplateDef t = TemplateDef.create("New composite", TemplateDef.Kind.COMPOSITE);
        TemplateEditor.open(t, null, library, "Insert templates of the library as components, connect their ports (structure) "
                + "and add layout relations.", this::saved);
    }

    private void importFile() {
        JFileChooser fc = new JFileChooser(library.getFolder());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        String key = "import:" + f.getAbsolutePath();   // known by the file until it is saved
        if (TemplateEditor.toFront(key)) return;          // imported already and not saved yet: that window
        TemplateDef t;
        boolean gml = TemplateFiles.isGmlFile(f);
        try {
            t = gml ? TemplateFiles.importGml(f) : TemplateFiles.load(f);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Import template", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String banner = gml ? t.note : "Imported from " + f.getName() + ". Save to add it to the library folder.";
        File file = null;
        boolean stored = !gml;
        // The same template (same id) in the library: saving the file as it is would list two templates with one id.
        TemplateDef known = gml ? null : library.latest(t.id);
        if (known != null) {
            boolean newer = t.revision > known.revision;
            String asNew = "Import as a new template", update = "Use as revision " + t.revision;
            Object[] options = newer ? new Object[]{update, asNew, "Cancel"} : new Object[]{asNew, "Cancel"};
            String message = "<html>The library has this template already: <b>" + Ui.esc(known.name) + "</b>, revision "
                    + known.revision + ".<br>The file holds revision " + t.revision + (newer ? ", a newer one." : ".") + "</html>";
            int a = JOptionPane.showOptionDialog(this, message, "Import template", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (a < 0 || "Cancel".equals(options[a])) return;
            if (update.equals(options[a])) {
                TemplateLibrary.Entry entry = library.find(t.id);
                file = entry == null || TemplateFiles.isGmlFile(entry.file) ? null : entry.file;
                banner = "Revision " + t.revision + " of '" + known.name + "' from " + f.getName()
                        + ". Saving puts it into the library in place of revision " + known.revision + ".";
            } else {
                t.id = TemplateDef.newId();
                t.revision = 1;
                t.source = "copy";
                stored = false;
                banner = "Imported from " + f.getName() + " as a template of its own (the library has the original). "
                        + "Save to add it to the library folder.";
            }
        }
        TemplateEditor.open(t, key, stored, file, library, banner, this::saved);
    }

    /** The editor saved a template: list it and select it. */
    void saved(TemplateDef t) {
        library.reload();
        if (library.find(t.id) == null) library.addInMemory(t);   // saved outside the folder
        reloadLibrary(t.id);
    }

    // ================================================================== search

    private Section createSearchSection() {
        Section s = new Section("Find matches");
        findButton.setName("findMatches");
        findButton.setToolTipText(Ui.tooltip("Searches the active graph for the template chosen in the library; nothing is "
                + "applied yet. Limits: " + fmt(TemplateLimits.maxMatchesPerRun) + " matches per search, " + TemplateLimits.maxShown
                + " shown at once, " + TemplateLimits.maxApplyPerStep + " applied per step."));
        findButton.addActionListener(e -> {
            SearchRun r = TemplateWorkbench.get().run(TemplateWorkbench.activeGraph());
            if (r != null && r.state() == SearchRun.State.RUNNING) TemplateWorkbench.get().cancelSearch();
            else find();
        });
        s.addRow(findButton);
        searchStatus.setName("searchStatus");
        s.addRow(searchStatus, Ui.GAP_S);
        notice.setVisible(false);
        noticeLinks.setVisible(false);
        findMore.setName("findMore");
        s.addRow(notice, Ui.GAP_XS);
        s.addRow(noticeLinks, Ui.GAP_XS);

        // the options: the default (structure and conditions, the whole graph) needs none of them
        for (AbstractButton b : new AbstractButton[]{structureOnly, withinSelection, exact, place}) {
            b.setOpaque(false);
            b.addActionListener(e -> showOptionsSummary());
        }
        structureOnly.setName("modeStructure");
        structureOnly.setToolTipText(Ui.tooltip("On: only the structure counts, i.e. which nodes are joined by which edges, and "
                + "in which direction. Off: nodes and edges must also meet the template's conditions (e.g. SBGN roles)."));
        withinSelection.setName("withinSelection");
        withinSelection.setToolTipText(Ui.tooltip("Restricts <b>where</b> to search: a match may use only nodes selected in the graph. "
                + "(Choosing matches to apply is done on the found matches.)"));
        exact.setName("exactStructure");
        exact.setToolTipText(Ui.tooltip("Stricter: between the matched nodes the graph may have no other edges than the template's."));
        place.setToolTipText(Ui.tooltip("When applying, the matched nodes first take the template's arrangement (moved to where they are), "
                + "then the constraints are solved. Off: only the constraints act."));
        place.addActionListener(e -> TemplateWorkbench.get().setPlaceNodes(place.isSelected()));
        options.setName("searchOptions");
        options.addRow(structureOnly);
        options.addRow(withinSelection, Ui.GAP_XS);
        options.addRow(exact, Ui.GAP_XS);
        options.addRow(place, Ui.GAP_XS);
        s.addRow(options, Ui.GAP_M);
        showOptionsSummary();
        return s;
    }

    /** The options that differ from the default, in the header of the closed "Options". */
    private void showOptionsSummary() {
        List<String> on = new ArrayList<>();
        if (structureOnly.isSelected()) on.add("structure only");
        if (withinSelection.isSelected()) on.add("selection");
        if (exact.isSelected()) on.add("exact");
        if (!place.isSelected()) on.add("no placing");
        options.setTrailingText(on.isEmpty() ? null : String.join(" · ", on));
    }

    /** Searches the active graph for the chosen template. */
    void find() {
        TemplateDef t = selectedTemplate();
        if (t == null) {
            searchStatus.setStatus("Choose a template in the library first.", StatusLabel.Kind.ERROR);
            return;
        }
        List<TemplateValidator.Problem> ps = TemplateValidator.validate(t, sourceFor(t));
        if (!TemplateValidator.canApply(ps)) {
            for (TemplateValidator.Problem p : ps) {
                if (p.severity != TemplateValidator.Severity.WARNING) {
                    searchStatus.setStatus("Cannot search: " + p, StatusLabel.Kind.ERROR);
                    searchStatus.setToolTipText(Ui.tooltip(Ui.esc(p.toString())));
                    return;
                }
            }
        }
        startSearch(Collections.singletonList(new SearchTask(t, library, null, null)));
    }

    /** The search stopped at its match limit: search again, collecting twice as many. */
    private void findMore() {
        SearchRun r = TemplateWorkbench.get().run(TemplateWorkbench.activeGraph());
        if (r != null) repeat(r, r.options.maxMatches * 2);
    }

    /**
     * The graph changed after the search: the same search anew (the template or the rules of the rule set, the options
     * and the nodes it searched among), not one for the template chosen in the library now.
     */
    private void searchAgain() {
        SearchRun r = TemplateWorkbench.get().run(TemplateWorkbench.activeGraph());
        if (r == null) find();
        else if (repeat(r, r.options.maxMatches)) TemplateWorkbench.get().setSuggestionMode(true);   // as a new search
    }

    /** Runs the search of {@code r} again on the active graph, collecting at most {@code maxMatches} matches; true if it started. */
    private boolean repeat(SearchRun r, int maxMatches) {
        Graph g = TemplateWorkbench.activeGraph();
        if (g == null) return false;
        List<Node> scope = r.snapshot.scope();   // the same nodes as the run it repeats
        if (scope != null) {
            scope.removeIf(n -> !g.containsNode(n));
            if (scope.isEmpty()) {
                searchStatus.setStatus("The nodes searched among are deleted: select nodes and find matches anew.", StatusLabel.Kind.ERROR);
                return false;
            }
        }
        String why = TemplateWorkbench.get().search(r.tasks, r.options.semantics, r.options.exact, scope, maxMatches);
        if (why != null) searchStatus.setStatus(why, StatusLabel.Kind.ERROR);
        return why == null;
    }

    /**
     * Starts a search for these tasks with the chosen options (also used by the rule set). Returns null if it started,
     * else why not (the status line shows it as well).
     */
    String startSearch(List<SearchTask> tasks) {
        String why = TemplateWorkbench.get().search(tasks, !structureOnly.isSelected(), exact.isSelected(), withinSelection.isSelected());
        if (why != null) {
            searchStatus.setStatus(why, StatusLabel.Kind.ERROR);
            return why;
        }
        // the matches are reviewed on the graph: the suggestion mode starts with the search
        TemplateWorkbench.get().setSuggestionMode(true);
        return null;
    }

    // ================================================================== matches

    private Section createMatchesSection() {
        Section s = matchesSection;
        matchList.setName("matchList");
        matchList.setCellRenderer(new MatchRenderer());
        matchList.setFixedCellHeight(38);
        matchList.setFixedCellWidth(1);
        matchList.setVisibleRowCount(1);
        matchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matchList.getAccessibleContext().setAccessibleName("Matches");
        matchList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingMatches || pressOnBox) return;
            showChosenMatch();
        });
        matchList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = matchList.locationToIndex(e.getPoint());
                Rectangle cell = i < 0 ? null : matchList.getCellBounds(i, i);
                if (cell == null || !cell.contains(e.getPoint()) || !SwingUtilities.isLeftMouseButton(e)) return;
                if (onCheckBox(e.getPoint())) {
                    Match m = matchModel.get(i);
                    TemplateWorkbench.get().setSelected(m, !m.selected);
                } else if (TemplateWorkbench.get().focus != matchModel.get(i)) {
                    // the row was chosen before and its popover closed since: no selection event comes
                    showChosenMatch();
                }
            }
            @Override public void mousePressed(MouseEvent e) { matchMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { matchMenu(e); }
        });
        matchList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleSelected");
        matchList.getActionMap().put("toggleSelected", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Match m = matchList.getSelectedValue();
                if (m != null) TemplateWorkbench.get().setSelected(m, !m.selected);
            }
        });
        matchList.setToolTipText(Ui.tooltip("Tick the box (or press Space) to select a match for applying; click the row to "
                + "see it on the graph with its actions."));
        s.addRow(noMatches);
        matchScroll = scroll(matchList);
        s.addRow(matchScroll, Ui.GAP_S);

        prevPage.addActionListener(e -> page(-1));
        nextPage.addActionListener(e -> page(1));
        pageLabel.setFont(Ui.small());
        pageLabel.setForeground(Ui.MUTED);
        pager = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        pager.setOpaque(false);
        pager.add(pageLabel, BorderLayout.CENTER);
        pager.add(Ui.row(prevPage, nextPage), BorderLayout.EAST);
        s.addRow(pager, Ui.GAP_XS);

        selectAll.setName("selectAllFound");
        clearSel.setName("clearSelection");
        clearResults.setName("clearResults");
        selectAll.addActionListener(e -> TemplateWorkbench.get().selectAllFound(TemplateWorkbench.activeGraph()));
        clearSel.addActionListener(e -> TemplateWorkbench.get().clearSelected(TemplateWorkbench.activeGraph()));
        clearResults.addActionListener(e -> TemplateWorkbench.get().clearResults(TemplateWorkbench.activeGraph()));
        selectedLabel.setFont(Ui.small());
        selectedLabel.setForeground(Ui.MUTED);
        selectedLabel.setName("selectionInfo");
        JLabel dot = new JLabel("·");
        dot.setFont(Ui.small());
        dot.setForeground(Ui.MUTED);
        selectionRow = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        selectionRow.setOpaque(false);
        selectionRow.add(Ui.flow(Ui.GAP_S, selectedLabel, selectAll, dot, clearSel), BorderLayout.CENTER);
        selectionRow.add(clearResults, BorderLayout.EAST);
        s.addRow(selectionRow, Ui.GAP_S);

        applySelected.setName("applySelected");
        applySelected.addActionListener(e -> applySelected());
        s.addRow(applySelected, Ui.GAP_S);
        return s;
    }

    /** The context menu of a match: select it for applying (or not), show it on the graph. */
    private void matchMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int i = matchList.locationToIndex(e.getPoint());
        if (i < 0 || !matchList.getCellBounds(i, i).contains(e.getPoint())) return;
        Match m = matchModel.get(i);
        JPopupMenu menu = new JPopupMenu();
        boolean selectable = m.state != Match.State.APPLIED && m.state != Match.State.INVALID;
        menu.add(menuItem(m.selected ? "Remove from selection" : "Select for applying", "toggleSelected", "Collects matches to apply "
                + "together with \"Apply selected\".", () -> TemplateWorkbench.get().setSelected(m, !m.selected))).setEnabled(selectable);
        menu.add(menuItem("Show on the graph", "showMatch", "Its outline and actions beside it on the graph.", () -> {
            matchList.setSelectedValue(m, true);
            showChosenMatch();
        }));
        menu.show(matchList, e.getX(), e.getY());
    }

    /** The match chosen in the list: its popover beside it on the graph, scrolled into view. */
    private void showChosenMatch() {
        Match m = matchList.getSelectedValue();
        if (m == null) return;
        TemplateWorkbench wb = TemplateWorkbench.get();
        if (!wb.isSuggestionMode()) wb.setSuggestionMode(true);
        wb.reveal(m);
        wb.openPopover(m);
    }

    private void page(int d) {
        Graph g = TemplateWorkbench.activeGraph();
        TemplateWorkbench.get().setPage(g, TemplateWorkbench.get().page(g) + d);
    }

    private void applySelected() {
        TemplateWorkbench wb = TemplateWorkbench.get();
        Graph g = TemplateWorkbench.activeGraph();
        List<Match> sel = wb.selected(g);
        if (sel.isEmpty()) return;
        MatchCheck together = wb.checkTogether(sel);
        if (together.status == MatchCheck.Status.CONFLICT || together.status == MatchCheck.Status.BLOCKED) {
            StringBuilder sb = new StringBuilder("<html><b>" + Ui.count(sel.size(), "selected match", "selected matches") + " · "
                    + Ui.count(TemplateWorkbench.constraintCount(sel), "constraint") + "</b><br><br>" + Ui.esc(together.summary) + "<br>");
            for (int k = 0; k < together.details.size() && k < 6; k++) sb.append("• ").append(Ui.esc(together.details.get(k))).append("<br>");
            sb.append("<br>Apply anyway? Constraints that lose keep their settings and are listed as not applied.</html>");
            if (JOptionPane.showConfirmDialog(this, sb.toString(), "Apply selected matches", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        }
        String msg = wb.apply(sel, null);
        searchStatus.setStatus(msg, StatusLabel.Kind.SUCCESS);
    }

    // ================================================================== instances

    private Section createInstanceSection() {
        Section s = instanceSection;
        instanceList.setName("instanceList");
        instanceList.setCellRenderer(new InstanceRenderer());
        instanceList.setFixedCellHeight(38);
        instanceList.setFixedCellWidth(1);
        instanceList.setVisibleRowCount(4);
        instanceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);   // the actions act on one instance
        instanceList.getAccessibleContext().setAccessibleName("Applied template instances");
        instanceList.setToolTipText(Ui.tooltip("Click an instance to see it on the graph with its actions: switch it off or on, "
                + "remove it, show its constraints, prefer it."));
        instanceList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingInstances) return;
            TemplateInstance i = instanceList.getSelectedValue();
            TemplateWorkbench.get().listInstance = i;
            TemplateWorkbench.get().focusInstance = i;
            updateInstanceActions();
            if (i != null) showInstance(i);
            else MatchOverlay.repaintAll();
        });
        instanceList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                // the row chosen before, whose popover was closed or replaced since: no selection event comes
                int k = instanceList.locationToIndex(e.getPoint());
                if (k < 0 || !instanceList.getCellBounds(k, k).contains(e.getPoint())) return;
                TemplateInstance i = instanceModel.get(k);
                if (!MatchPopover.isOpenFor(i)) showInstance(i);
            }
        });
        instanceScroll = scroll(instanceList);
        s.addRow(instanceScroll);
        s.addRow(instanceInfo, Ui.GAP_XS);
        s.addRow(partsBox, Ui.GAP_S);
        return s;
    }

    /** The actions of an applied instance: in its popover beside it on the graph, which is scrolled into view. */
    private void showInstance(TemplateInstance i) {
        TemplateWorkbench wb = TemplateWorkbench.get();
        if (i.graph != TemplateWorkbench.activeGraph()) return;
        wb.revealInstance(i);
        wb.openInstancePopover(i);
    }

    private void refreshInstances() {
        Graph g = TemplateWorkbench.activeGraph();
        TemplateInstance sel = instanceList.getSelectedValue();
        updatingInstances = true;   // listing them anew chooses nothing
        try {
            instanceModel.clear();
            for (TemplateInstance i : InstanceRegistry.of(g)) instanceModel.addElement(i);
            if (sel != null && instanceModel.contains(sel)) instanceList.setSelectedValue(sel, false);
        } finally {
            updatingInstances = false;
        }
        TemplateWorkbench wb = TemplateWorkbench.get();
        wb.listInstance = instanceList.getSelectedValue();
        if (wb.focusInstance != null && wb.focusInstance.graph == g && !instanceModel.contains(wb.focusInstance)) wb.focusInstance = null;
        instanceSection.setTrailingText(String.valueOf(instanceModel.size()));
        instanceList.setVisibleRowCount(Math.max(1, Math.min(5, instanceModel.size())));
        updateInstanceActions();
        instanceList.repaint();
    }

    private void updateInstanceActions() {
        TemplateInstance i = instanceList.getSelectedValue();
        boolean any = i != null;
        partsBox.clearRows();
        instanceScroll.setVisible(!instanceModel.isEmpty());
        if (!any) {
            instanceInfo.setText(Ui.wrapped(instanceModel.isEmpty() ? "No template applied to this graph yet."
                    : "Click an instance for its actions on the graph.", Ui.TEXT_WIDTH));
        } else {
            TemplateInstance.Status st = i.status(ConstraintStore.constraints);
            instanceInfo.setText(Ui.wrapped("<b>" + Ui.esc(i.label()) + "</b> · " + Ui.esc(i.template.toString()) + "<br>" + Ui.esc(st.text()), Ui.TEXT_WIDTH));
            // component parts of a composite, each with its own switch
            for (layout.template.apply.InstancePlan.Part p : i.plan.parts.values()) {
                int n = i.constraintsOf(p.key).size();
                JCheckBox box = new JCheckBox(indent(p.key) + p.name + " (" + p.templateName + ", " + n + ")", i.isPartSwitchedOn(p.key));
                box.setOpaque(false);
                box.setFont(Ui.small());
                box.setToolTipText(Ui.tooltip("Switches the constraints of this component on or off; the rest of the instance stays as it is."));
                box.addActionListener(e -> TemplateWorkbench.get().setInstanceOn(i, p.key, box.isSelected()));
                partsBox.addRow(box, partsBox.getRowCount() == 0 ? 0 : Ui.GAP_XS);
            }
        }
        partsBox.setVisible(partsBox.getRowCount() > 0);
        revalidate();
        repaint();
    }

    private static String indent(String key) {
        int depth = key.split("/").length - 1;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < depth; k++) sb.append("   ");
        return sb.toString();
    }

    // ================================================================== rule set

    /** The rule set in short: its name and rules, the window that edits it and the search for its active rules. */
    private Section createRuleSection() {
        Section s = new Section("Rule set");
        JButton edit = Ui.button("Edit rule set…", "Opens the rule set in a window of its own: bundle templates as rules, "
                + "each switched on or off with a priority; open and save rule sets.", e -> openRuleSet());
        edit.setName("editRuleSet");
        JButton find = Ui.button("Find with rules", "Searches the active graph for every active rule of the rule set; the "
                + "matches appear above and on the graph. Nothing is applied yet.", e -> {
                    String why = ruleSection.find();
                    if (why != null) searchStatus.setStatus(why, StatusLabel.Kind.ERROR);
                    String skipped = why == null ? ruleSection.skipped() : null;
                    ruleStatus.setStatus(skipped == null ? null : "Skipped: " + skipped, StatusLabel.Kind.ERROR);
                    ruleStatus.setVisible(skipped != null);
                });
        find.setName("findRuleMatchesHere");
        s.addRow(ruleSummary);
        s.addRow(Ui.row(edit, find), Ui.GAP_S);
        ruleStatus.setVisible(false);
        s.addRow(ruleStatus, Ui.GAP_XS);
        showRuleSummary();
        return s;
    }

    private void showRuleSummary() {
        ruleSummary.setText(Ui.wrapped(Ui.esc(ruleSection.summary()), Ui.TEXT_WIDTH));
        ruleStatus.setVisible(false);   // about the rules as they were
    }

    private void openRuleSet() {
        if (ruleWindow == null) {
            ruleWindow = new JFrame("Rule set");
            VerticalStack column = new VerticalStack();
            column.setBorder(Ui.columnPadding(Ui.GAP_L, Ui.GAP_XL));
            column.addRow(ruleSection);
            ruleWindow.setContentPane(new gui.components.ScrollColumn(column, Ui.COLUMN_WIDTH));
            ruleWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            ruleWindow.setSize(Ui.TAB_WIDTH + 30, 640);
            ruleWindow.setLocationRelativeTo(this);
        }
        ruleWindow.setVisible(true);
        ruleWindow.toFront();
    }

    // ================================================================== refresh

    /** Follows the workbench: search state, matches, selection, instances. */
    void refresh() {
        TemplateWorkbench wb = TemplateWorkbench.get();
        Graph g = TemplateWorkbench.activeGraph();
        SearchRun run = wb.run(g);
        boolean running = run != null && run.state() == SearchRun.State.RUNNING;
        findButton.setText(running ? "Cancel search" : "Find matches");
        findButton.repaint();
        if (g == null) searchStatus.setStatus("Open a graph to search it.", StatusLabel.Kind.INFO);
        else if (run == null) searchStatus.setStatus(wb.message(g) != null ? wb.message(g) : "Choose a template and find where it fits.", StatusLabel.Kind.INFO);
        else {
            int n = run.matches().size();
            String text;
            StatusLabel.Kind kind;
            switch (run.state()) {
                case RUNNING:
                    text = "Searching… " + fmt(n) + (n == 1 ? " match" : " matches") + " so far";
                    kind = StatusLabel.Kind.BUSY;
                    break;
                case COMPLETE:
                    text = "Search complete: " + fmt(n) + (n == 1 ? " match" : " matches") + " (" + run.durationMillis() + " ms)";
                    kind = StatusLabel.Kind.SUCCESS;
                    break;
                case LIMITED:
                    text = run.limitMessage() != null ? run.limitMessage() : fmt(n) + " matches found, search limited.";
                    kind = StatusLabel.Kind.ERROR;
                    break;
                case CANCELLED:
                    text = "Search cancelled: " + fmt(n) + " found so far (incomplete).";
                    kind = StatusLabel.Kind.ERROR;
                    break;
                default:
                    text = "Search failed: " + String.join("; ", run.notes());
                    kind = StatusLabel.Kind.ERROR;
            }
            if (wb.message(g) != null && !running) {
                text = wb.message(g);
                kind = StatusLabel.Kind.SUCCESS;
            }
            searchStatus.setStatus(text, kind);
            searchStatus.setToolTipText(Ui.tooltip(Ui.esc(text) + (run.notes().isEmpty() ? "" : "<br>" + Ui.esc(String.join(" ", run.notes())))
                    + "<br>Assignments examined: " + fmt(run.assignments())));
        }
        // one notice under the status: the graph changed since the search, or the search stopped at its limit
        boolean more = run != null && !running && run.state() == SearchRun.State.LIMITED && run.isLimitedByMatches();
        boolean out = g != null && wb.isRunOutdated(g) && run != null && !running;
        String say = out ? "The graph changed after the search: new matches may exist, changed ones are marked outdated."
                : more ? "The search stopped at its limit: the graph may hold more matches." : null;
        notice.setVisible(say != null);
        if (say != null) notice.setText(Ui.wrapped("<font color='#b06000'>" + say + "</font>", Ui.TEXT_WIDTH));
        searchAgain.setVisible(out);
        findMore.setVisible(more);
        noticeLinks.setVisible(out || more);
        if (more) {
            findMore.setText("Find more (up to " + fmt(run.options.maxMatches * 2L) + ")");
            findMore.setToolTipText(Ui.tooltip("Searches again and collects up to " + fmt(run.options.maxMatches * 2L)
                    + " matches. The matches applied so far stay applied."));
        }

        updatingMatches = true;
        try {
            Match keep = matchList.getSelectedValue();
            List<Match> shown = wb.shown(g);
            if (!sameList(shown)) {
                matchModel.clear();
                for (Match m : shown) matchModel.addElement(m);
            }
            if (wb.focus != null && matchModel.contains(wb.focus)) matchList.setSelectedValue(wb.focus, true);
            else if (keep != null && matchModel.contains(keep) && matchList.isFocusOwner()) matchList.setSelectedValue(keep, false);
            else matchList.clearSelection();
        } finally {
            updatingMatches = false;
        }
        List<Match> all = wb.matches(g);
        boolean found = !all.isEmpty();
        // without matches the section is one line; the list and its actions come with the first match
        noMatches.setVisible(!found);
        noMatches.setText(Ui.wrapped(run == null ? "No matches yet: choose a template in the library and find where it fits."
                : running ? "Searching…" : "The search found no match.", Ui.TEXT_WIDTH));
        matchScroll.setVisible(found);
        selectionRow.setVisible(found);
        applySelected.setVisible(found);
        matchList.setVisibleRowCount(Math.max(1, Math.min(7, matchModel.size())));
        int page = wb.page(g), pages = wb.pageCount(g);
        int from = page * TemplateLimits.maxShown;
        matchesSection.setTrailingText(!found ? null : pages > 1 ? (from + 1) + "–" + (from + matchModel.size()) + " of " + fmt(all.size())
                : fmt(all.size()));
        pager.setVisible(found && pages > 1);
        pageLabel.setText("Page " + (page + 1) + " of " + pages);
        prevPage.setEnabled(page > 0);
        nextPage.setEnabled(page < pages - 1);
        List<Match> sel = wb.selected(g);
        int constraints = TemplateWorkbench.constraintCount(sel);
        int applicable = 0, applied = 0;
        for (Match m : all) {
            if (selectable(m)) applicable++;
            else if (m.state == Match.State.APPLIED) applied++;
        }
        selectedLabel.setText(applicable > 0 ? sel.size() + " of " + fmt(applicable) + " selected:"
                : applied == all.size() ? "All applied." : "None left to apply.");
        selectAll.setEnabled(applicable > 0 && sel.size() < applicable);
        clearSel.setEnabled(!sel.isEmpty());
        applySelected.setText(sel.isEmpty() ? "Apply selected" : "Apply " + fmt(sel.size()) + " selected");
        applySelected.setToolTipText(Ui.tooltip(sel.isEmpty() ? "Tick matches in the list (or All) to apply them together."
                : "Makes " + Ui.count(constraints, "constraint") + " on the graph and solves the layout once (Edit › Undo reverts it)."
                + (sel.size() > TemplateLimits.maxApplyPerStep ? " The first " + TemplateLimits.maxApplyPerStep
                + " are applied in this step, the others stay selected for the next." : "")));
        applySelected.setEnabled(!sel.isEmpty() && !running);
        clearResults.setEnabled(!running);
        matchList.repaint();
        refreshInstances();
        ruleSection.refresh();
    }

    private boolean sameList(List<Match> shown) {
        if (shown.size() != matchModel.size()) return false;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i) != matchModel.get(i)) return false;
        return true;
    }

    // ================================================================== rendering

    private static JScrollPane scroll(JList<?> list) {
        return new gui.components.NestedScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };
    }

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Background of a list row: the selection colour, else alternating stripes. */
    private static Color rowBackground(JList<?> l, int index, boolean selected) {
        return selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER;
    }

    /** Library entry: a preview, the name and badges. */
    private final class ItemRenderer extends JPanel implements ListCellRenderer<Item> {
        private static final long serialVersionUID = 1L;
        private Item item;
        private final JLabel name = new JLabel(), badges = new JLabel();

        ItemRenderer() {
            super(new BorderLayout(Ui.GAP_M, 0));
            setBorder(new EmptyBorder(3, 52, 3, 6));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            name.setFont(Ui.bold());
            badges.setFont(Ui.small());
            text.add(name);
            text.add(badges);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item it, int index, boolean selected, boolean focus) {
            item = it;
            name.setText(it.toString());
            List<String> b = new ArrayList<>();
            if (it.template == null) b.add("cannot be read");
            else {
                if (it.example) b.add("Example");
                if (it.template.isComposite()) b.add("Composite · " + Ui.count(it.template.components.size(), "part"));
                if (it.file != null && TemplateFiles.isGmlFile(it.file)) b.add("GML · no constraints yet");
                else if (!it.example) b.add("rev. " + it.template.revision);
                if (!it.example && it.file == null) b.add("not in the library folder");
                if (it.template.isComposite() && library != null && !library.updatesFor(it.template).isEmpty()) b.add("update available");
            }
            badges.setText(String.join(" · ", b));
            setBackground(rowBackground(l, index, selected));
            name.setForeground(selected ? l.getSelectionForeground() : it.template == null ? Ui.ERROR : Ui.TEXT);
            badges.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            setOpaque(true);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Rectangle2D r = new Rectangle2D.Double(4, 4, 42, getHeight() - 8);
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(Color.WHITE);
            g2.fill(r);
            g2.setColor(Ui.BORDER);
            g2.draw(r);
            if (item != null && item.template != null) {
                PatternPainter.paintPreview(g2, item.template, sourceFor(item.template), r);
            }
            g2.dispose();
        }
    }

    /** Match row: selection box, symbol and number, template, nodes, state. */
    private static final class MatchRenderer extends JPanel implements ListCellRenderer<Match> {
        private static final long serialVersionUID = 1L;
        /** A real check box, so the column that selects for applying is seen as one; its width is {@link #BOX_COLUMN}. */
        private final JCheckBox box = new JCheckBox();
        private final JLabel head = new JLabel(), body = new JLabel();

        MatchRenderer() {
            super(new BorderLayout(Ui.GAP_S, 0));
            setBorder(new EmptyBorder(2, 2, 2, 4));
            box.setOpaque(false);
            box.setBorder(new EmptyBorder(0, 0, 0, 0));
            box.setPreferredSize(new Dimension(BOX_COLUMN - 2 - Ui.GAP_S, box.getPreferredSize().height));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            head.setFont(Ui.bold().deriveFont(Ui.small().getSize2D() + 1));
            body.setFont(Ui.small());
            text.add(head);
            text.add(body);
            add(box, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Match> l, Match m, int index, boolean selected, boolean focus) {
            boolean selectable = selectable(m);
            box.setEnabled(selectable);
            box.setSelected(selectable && m.selected);
            Color c = MatchOverlay.colorOf(m);
            // the check box shows whether a suggestion is selected: the symbol and the state text only show the others
            boolean plain = MatchOverlay.isSuggestion(m);
            head.setText(plain ? m.title() : MatchOverlay.symbolOf(m) + " " + m.title());
            List<String> labels = new ArrayList<>();
            for (Node n : m.nodes()) {
                String s = ConstraintCatalog.plainLabel(n);
                labels.add(s);
                if (labels.size() >= 3) break;
            }
            body.setText((plain ? "Suggestion" : MatchOverlay.stateText(m)) + " · " + String.join(", ", labels) + (m.nodes().size() > 3 ? ", …" : "")
                    + (m.variants().size() > 1 ? " · " + m.variants().size() + " assignments" : ""));
            setBackground(rowBackground(l, index, selected));
            head.setForeground(selected ? l.getSelectionForeground() : c);
            body.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            setOpaque(true);
            return this;
        }
    }

    /** A match that can be selected for applying: its check box is enabled. */
    static boolean selectable(Match m) {
        return m.state != Match.State.APPLIED && m.state != Match.State.INVALID;
    }

    /** True if {@code p} (in the match list) lies on the enabled check box of a row. */
    private boolean onCheckBox(Point p) {
        int i = matchList.locationToIndex(p);
        Rectangle cell = i < 0 ? null : matchList.getCellBounds(i, i);
        return cell != null && cell.contains(p) && p.x - cell.x < BOX_COLUMN && selectable(matchModel.get(i));
    }

    /** Instance row: on/off symbol, label, state. */
    private static final class InstanceRenderer extends JPanel implements ListCellRenderer<TemplateInstance> {
        private static final long serialVersionUID = 1L;
        private final JLabel head = new JLabel(), body = new JLabel();

        InstanceRenderer() {
            super(new GridLayout(2, 1));
            setBorder(new EmptyBorder(2, 6, 2, 6));
            head.setFont(Ui.bold().deriveFont(Ui.small().getSize2D() + 1));
            body.setFont(Ui.small());
            add(head);
            add(body);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends TemplateInstance> l, TemplateInstance i, int index,
                                                      boolean selected, boolean focus) {
            TemplateInstance.Status s = i.status(ConstraintStore.constraints);
            head.setText((i.isOn() ? s.notApplied > 0 ? "! " : s.partly() ? "◐ " : "● " : "⏻ ") + i.label());
            body.setText(s.text() + " · rev. " + i.template.revision);
            setBackground(rowBackground(l, index, selected));
            head.setForeground(selected ? l.getSelectionForeground() : !i.isOn() ? MatchOverlay.OFF : s.notApplied > 0 ? Ui.ERROR : Ui.SUCCESS);
            body.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            setOpaque(true);
            return this;
        }
    }
}
