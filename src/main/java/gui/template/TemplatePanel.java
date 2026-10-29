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
    private final JTextField folderField = new JTextField(2);
    private final DefaultListModel<Item> libraryModel = new DefaultListModel<>();
    private final JList<Item> libraryList = new JList<>(libraryModel);
    private final JLabel templateInfo = Ui.hint(" ");

    // ---- search
    private final JRadioButton semanticsMode = new JRadioButton("Structure and semantics", true);
    private final JRadioButton structureMode = new JRadioButton("Structure only");
    private final JCheckBox withinSelection = new JCheckBox("Only among the selected nodes");
    private final JCheckBox exact = new JCheckBox("Exact structure within a match");
    private final JCheckBox place = new JCheckBox("Place matched nodes like the template", true);
    private final PrimaryButton findButton = new PrimaryButton("Find matches");
    private final StatusLabel searchStatus = new StatusLabel(" ");
    private final JLabel outdated = Ui.hint(" ");
    private final JButton searchAgain = Ui.button("Search again", "The graph changed after the search: search anew.", e -> find());
    private final JButton findMore = Ui.button("Find more", null, e -> findMore());

    // ---- matches
    private final SwitchToggle showSwitch = new SwitchToggle("Show matches on the graph", false);
    private final JLabel showState = new JLabel();
    private final Section matchesSection = new Section("Matches");
    private final DefaultListModel<Match> matchModel = new DefaultListModel<>();
    private final JList<Match> matchList = new JList<>(matchModel);
    private final JLabel pageLabel = Ui.hint(" ");
    private final JButton prevPage = Ui.button("◀", "Previous matches", null), nextPage = Ui.button("▶", "Next matches", null);
    private final JButton selectAll = Ui.button("Select all", "Selects every match of the search for applying (not only the shown ones).", null);
    private final JButton clearSel = Ui.button("Clear", "Deselects all matches.", null);
    private final JLabel selectionInfo = Ui.hint(" ");
    private final PrimaryButton applySelected = new PrimaryButton("Apply selected");
    private final JButton applyAll = Ui.button("Apply all found…", null, null);
    private boolean updatingMatches;

    // ---- instances
    private final Section instanceSection = new Section("Applied instances");
    private final DefaultListModel<TemplateInstance> instanceModel = new DefaultListModel<>();
    private final JList<TemplateInstance> instanceList = new JList<>(instanceModel);
    private final JButton instOnOff = Ui.button("Switch off", "Switches all constraints of the instance off or on together; their own settings are kept.", null);
    private final JButton instRemove = Ui.button("Remove", "Removes exactly the constraints of this instance (Edit › Undo brings them back).", null);
    private final JButton instShow = Ui.button("Constraints", "Marks the instance's constraints on the graph.", null);
    private final JButton instPrefer = Ui.button("Prefer…", "Raises the priorities of its constraints that are not applied so they win; shows first what changes.", null);
    private final VerticalStack partsBox = new VerticalStack();
    private final JLabel instanceInfo = Ui.hint(" ");

    private final RuleSetSection ruleSection;

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
        sections.addRow(ruleSection, Ui.SECTION_GAP);
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
        folderField.setEditable(false);
        s.addRow(Ui.labeled("Folder", folderField, Ui.button("Choose…", "Choose the folder that holds your template files "
                + "(" + TemplateFiles.TEMPLATE_EXT + "; old GML templates are imported).", e -> chooseFolder())));
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
        s.addRow(scroll(libraryList));
        s.addRow(templateInfo, Ui.GAP_XS);
        JButton fromSel = Ui.button("From selection", "A new template from the selected nodes, their edges and the constraints "
                + "that lie completely inside the selection. Opens the editor.", e -> newFromSelection());
        fromSel.setName("newFromSelection");
        JButton edit = Ui.button("Edit…", "Opens the chosen template in the editor (double-click works too).", e -> editSelected());
        edit.setName("editTemplate");
        JButton composite = Ui.button("Composite…", "A template made of other templates: connect their ports and add layout relations.",
                e -> newComposite());
        composite.setName("newComposite");
        JButton imp = Ui.button("Import…", "Adds a template file (" + TemplateFiles.TEMPLATE_EXT + " or old .gml) from anywhere.", e -> importFile());
        s.addRow(Ui.row(fromSel, edit), Ui.GAP_S);
        s.addRow(Ui.row(composite, imp), Ui.GAP_XS);
        return s;
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
        folderField.setText(folder == null ? "" : folder.getAbsolutePath());
        folderField.setToolTipText(folder == null ? "No folder chosen: only the prepared examples are listed." : Ui.tooltip(Ui.esc(folder.getAbsolutePath())));
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

    private void templateSelected() {
        Item i = libraryList.getSelectedValue();
        if (i == null) {
            templateInfo.setText(" ");
            return;
        }
        if (i.template == null) {
            templateInfo.setText(Ui.wrapped("<font color='#c5221f'>" + Ui.esc(i.error) + "</font>", Ui.TEXT_WIDTH));
            return;
        }
        TemplateDef t = i.template;
        StringBuilder sb = new StringBuilder();
        if (t.description != null && !t.description.isEmpty()) sb.append(Ui.esc(t.description)).append("<br>");
        sb.append(t.isComposite() ? t.components.size() + " components, " + t.links.size() + " links, " + t.relations.size() + " layout relations"
                : t.nodes.size() + " nodes, " + t.edges.size() + " edges, " + t.constraints.size() + " constraints");
        if (!t.ports.isEmpty()) sb.append(", ").append(t.ports.size()).append(" ports");
        List<TemplateValidator.Problem> ps = TemplateValidator.validate(t, sourceFor(t));
        for (TemplateValidator.Problem p : ps) {
            String color = p.severity == TemplateValidator.Severity.WARNING ? "#b06000" : "#c5221f";
            sb.append("<br><font color='").append(color).append("'>").append(Ui.esc(p.toString())).append("</font>");
        }
        if (t.isComposite()) {
            for (TemplateLibrary.Update u : library.updatesFor(t)) {
                sb.append("<br><font color='#2563eb'>'").append(Ui.esc(u.component.name)).append("' uses ").append(Ui.esc(u.component.ref.toString()))
                        .append("; rev. ").append(u.newer.revision).append(" is available (update in the editor).</font>");
            }
        }
        templateInfo.setText(Ui.wrapped(sb.toString(), Ui.TEXT_WIDTH));
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
        TemplateEditor.open(t, file, library, banner, this::saved);
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
        banner.append(sel.size()).append(" nodes, ").append(r.template.edges.size()).append(" edges and ").append(r.taken)
                .append(r.taken == 1 ? " constraint" : " constraints").append(" taken over.");
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
        TemplateEditor.open(t, null, library, "Insert building blocks from the library, connect their ports (structure) "
                + "and add layout relations.", this::saved);
    }

    private void importFile() {
        JFileChooser fc = new JFileChooser(library.getFolder());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            TemplateDef t = TemplateFiles.isGmlFile(f) ? TemplateFiles.importGml(f) : TemplateFiles.load(f);
            TemplateEditor.open(t, null, library, TemplateFiles.isGmlFile(f) ? t.note
                    : "Imported from " + f.getName() + ". Save to add it to the library folder.", this::saved);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Import template", JOptionPane.ERROR_MESSAGE);
        }
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
        ButtonGroup g = new ButtonGroup();
        g.add(semanticsMode);
        g.add(structureMode);
        for (AbstractButton b : new AbstractButton[]{semanticsMode, structureMode, withinSelection, exact, place}) b.setOpaque(false);
        semanticsMode.setName("modeSemantics");
        structureMode.setName("modeStructure");
        semanticsMode.setToolTipText(Ui.tooltip("Nodes and edges must have the structure <b>and</b> meet the template's conditions (e.g. SBGN roles)."));
        structureMode.setToolTipText(Ui.tooltip("Only the structure counts: which nodes are joined by which edges, and in which direction."));
        withinSelection.setName("withinSelection");
        withinSelection.setToolTipText(Ui.tooltip("Restricts <b>where</b> to search: a match may use only nodes selected in the graph. "
                + "(Choosing matches to apply is done on the found matches.)"));
        exact.setName("exactStructure");
        exact.setToolTipText(Ui.tooltip("Stricter: between the matched nodes the graph may have no other edges than the template's."));
        place.setToolTipText(Ui.tooltip("When applying, the matched nodes first take the template's arrangement (moved to where they are), "
                + "then the constraints are solved. Off: only the constraints act."));
        place.addActionListener(e -> TemplateWorkbench.get().setPlaceNodes(place.isSelected()));
        s.addRow(semanticsMode);
        s.addRow(structureMode, Ui.GAP_XS);
        s.addRow(withinSelection, Ui.GAP_S);
        Section adv = Section.collapsible("Advanced", false);
        adv.addRow(exact);
        adv.addRow(place, Ui.GAP_XS);
        adv.addRow(Ui.hint("Limits: " + fmt(TemplateLimits.maxMatchesPerRun) + " matches per search, " + TemplateLimits.maxShown
                + " shown at once, " + TemplateLimits.maxApplyPerStep + " applied per step."), Ui.GAP_S);
        s.addRow(adv, Ui.GAP_S);
        findButton.setName("findMatches");
        findButton.addActionListener(e -> {
            SearchRun r = TemplateWorkbench.get().run(TemplateWorkbench.activeGraph());
            if (r != null && r.state() == SearchRun.State.RUNNING) TemplateWorkbench.get().cancelSearch();
            else find();
        });
        s.addRow(findButton, Ui.GAP_M);
        searchStatus.setName("searchStatus");
        s.addRow(searchStatus, Ui.GAP_S);
        outdated.setVisible(false);
        searchAgain.setVisible(false);
        s.addRow(outdated, Ui.GAP_XS);
        s.addRow(searchAgain, Ui.GAP_XS);
        findMore.setName("findMore");
        findMore.setVisible(false);
        s.addRow(findMore, Ui.GAP_XS);
        return s;
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
        Graph g = TemplateWorkbench.activeGraph();
        SearchRun r = TemplateWorkbench.get().run(g);
        if (r == null) return;
        int more = r.options.maxMatches * 2;
        List<Node> scope = r.snapshot.scope();   // the same nodes as the run it extends
        if (scope != null) {
            scope.removeIf(n -> !g.containsNode(n));
            if (scope.isEmpty()) {
                searchStatus.setStatus("The nodes searched among are deleted; search again.", StatusLabel.Kind.ERROR);
                return;
            }
        }
        String why = TemplateWorkbench.get().search(r.tasks, r.options.semantics, r.options.exact, scope, more);
        if (why != null) searchStatus.setStatus(why, StatusLabel.Kind.ERROR);
    }

    /** Starts a search for these tasks with the chosen options (also used by the rule set). */
    void startSearch(List<SearchTask> tasks) {
        String why = TemplateWorkbench.get().search(tasks, semanticsMode.isSelected(), exact.isSelected(), withinSelection.isSelected());
        if (why != null) {
            searchStatus.setStatus(why, StatusLabel.Kind.ERROR);
            return;
        }
        // the matches are reviewed on the graph: the suggestion mode starts with the search
        TemplateWorkbench.get().setSuggestionMode(true);
    }

    // ================================================================== matches

    private Section createMatchesSection() {
        Section s = matchesSection;
        showSwitch.setName("suggestionMode");
        showSwitch.setToolTipText(Ui.tooltip("<b>On</b>: the matches are drawn on the graph; hover shows them, a click opens their actions. "
                + "Selecting and moving nodes still works. <b>Esc</b> turns it off."));
        showSwitch.addActionListener(e -> TemplateWorkbench.get().setSuggestionMode(showSwitch.isSelected()));
        s.addRow(ConstraintsTab.switchRow("Show on graph", showSwitch, showState));
        matchList.setName("matchList");
        matchList.setCellRenderer(new MatchRenderer());
        matchList.setFixedCellHeight(38);
        matchList.setFixedCellWidth(1);
        matchList.setVisibleRowCount(1);
        matchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matchList.getAccessibleContext().setAccessibleName("Matches");
        matchList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingMatches) return;
            Match m = matchList.getSelectedValue();
            if (m == null) return;
            TemplateWorkbench wb = TemplateWorkbench.get();
            if (!wb.isSuggestionMode()) wb.setSuggestionMode(true);
            wb.openPopover(m);
        });
        matchList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = matchList.locationToIndex(e.getPoint());
                if (i < 0) return;
                Rectangle cell = matchList.getCellBounds(i, i);
                if (cell != null && e.getX() - cell.x < 24) {   // the check box column
                    Match m = matchModel.get(i);
                    TemplateWorkbench.get().setSelected(m, !m.selected);
                }
            }
        });
        matchList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleSelected");
        matchList.getActionMap().put("toggleSelected", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Match m = matchList.getSelectedValue();
                if (m != null) TemplateWorkbench.get().setSelected(m, !m.selected);
            }
        });
        matchList.setToolTipText(Ui.tooltip("Click the box or press Space to select a match for applying; choosing a row shows it on the graph."));
        s.addRow(scroll(matchList), Ui.GAP_S);
        prevPage.addActionListener(e -> page(-1));
        nextPage.addActionListener(e -> page(1));
        JPanel pager = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        pager.setOpaque(false);
        pager.add(pageLabel, BorderLayout.CENTER);
        pager.add(Ui.row(prevPage, nextPage), BorderLayout.EAST);
        s.addRow(pager, Ui.GAP_XS);
        selectAll.setName("selectAllFound");
        clearSel.setName("clearSelection");
        selectAll.addActionListener(e -> TemplateWorkbench.get().selectAllFound(TemplateWorkbench.activeGraph()));
        clearSel.addActionListener(e -> TemplateWorkbench.get().clearSelected(TemplateWorkbench.activeGraph()));
        s.addRow(Ui.row(selectAll, clearSel), Ui.GAP_S);
        selectionInfo.setName("selectionInfo");
        s.addRow(selectionInfo, Ui.GAP_S);
        applySelected.setName("applySelected");
        applySelected.addActionListener(e -> applySelected());
        s.addRow(applySelected, Ui.GAP_S);
        applyAll.setName("applyAllFound");
        applyAll.addActionListener(e -> applyAllFound());
        s.addRow(applyAll, Ui.GAP_XS);
        return s;
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
            StringBuilder sb = new StringBuilder("<html><b>" + sel.size() + " selected matches · " + TemplateWorkbench.constraintCount(sel)
                    + " constraints</b><br><br>" + Ui.esc(together.summary) + "<br>");
            for (int k = 0; k < together.details.size() && k < 6; k++) sb.append("• ").append(Ui.esc(together.details.get(k))).append("<br>");
            sb.append("<br>Apply anyway? Constraints that lose keep their settings and are listed as not applied.</html>");
            if (JOptionPane.showConfirmDialog(this, sb.toString(), "Apply selected matches", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        }
        String msg = wb.apply(sel, null);
        searchStatus.setStatus(msg, StatusLabel.Kind.SUCCESS);
    }

    private void applyAllFound() {
        TemplateWorkbench wb = TemplateWorkbench.get();
        Graph g = TemplateWorkbench.activeGraph();
        SearchRun run = wb.run(g);
        if (run == null) return;
        List<Match> all = wb.matches(g);
        int applicable = 0, applied = 0, invalid = 0, stale = 0;
        for (Match m : all) {
            if (m.state == Match.State.APPLIED) applied++;
            else if (m.state == Match.State.INVALID) invalid++;
            else {
                applicable++;
                if (m.state == Match.State.STALE) stale++;
            }
        }
        int now = Math.min(applicable, TemplateLimits.maxApplyPerStep);
        List<Match> todo = new ArrayList<>();
        for (Match m : all) if ((m.state == Match.State.FOUND || m.state == Match.State.STALE) && todo.size() < now) todo.add(m);
        StringBuilder sb = new StringBuilder("<html><b>Apply all found matches</b><br><br>");
        sb.append(fmt(all.size())).append(" found").append(run.state() == SearchRun.State.COMPLETE ? " (search complete)"
                : " — " + Ui.esc(run.state().label()).toLowerCase(Locale.ROOT) + ": there may be more in the graph").append(".<br>");
        sb.append("This step applies <b>").append(now).append("</b>").append(applicable > now ? " (limit " + TemplateLimits.maxApplyPerStep
                + " per step; " + (applicable - now) + " stay for the next step)" : "").append(": about ")
                .append(TemplateWorkbench.constraintCount(todo)).append(" constraints.<br>");
        if (applied > 0) sb.append(applied).append(" already applied are skipped.<br>");
        if (invalid > 0) sb.append(invalid).append(" no longer fit and are skipped.<br>");
        if (stale > 0) sb.append(Math.min(stale, now)).append(" outdated ones are checked again first.<br>");
        sb.append("<br>The layout is solved once for all of them; Edit › Undo reverts the step.</html>");
        if (now == 0) {
            JOptionPane.showMessageDialog(this, "Nothing left to apply: every found match is applied or no longer fits.",
                    "Apply all found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, sb.toString(), "Apply all found", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        String msg = wb.apply(todo, "All found");
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
        instanceList.getAccessibleContext().setAccessibleName("Applied template instances");
        instanceList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            TemplateInstance i = instanceList.getSelectedValue();
            TemplateWorkbench.get().focusInstance = i;
            updateInstanceActions();
            MatchOverlay.repaintAll();
        });
        s.addRow(scroll(instanceList));
        s.addRow(instanceInfo, Ui.GAP_XS);
        instOnOff.setName("instanceOnOff");
        instRemove.setName("instanceRemove");
        instPrefer.setName("instancePrefer");
        instOnOff.addActionListener(e -> {
            TemplateInstance i = instanceList.getSelectedValue();
            if (i != null) TemplateWorkbench.get().setInstanceOn(i, "", !i.isOn());
        });
        instRemove.addActionListener(e -> {
            TemplateInstance i = instanceList.getSelectedValue();
            if (i != null) TemplateWorkbench.get().removeInstance(i);
        });
        instShow.addActionListener(e -> {
            TemplateInstance i = instanceList.getSelectedValue();
            if (i == null) return;
            DisplayConstraints.clearMarkings();
            DisplayConstraints.drawConstraints(i.present(ConstraintStore.constraints));
        });
        instPrefer.addActionListener(e -> {
            TemplateInstance i = instanceList.getSelectedValue();
            if (i != null) TemplateWorkbench.get().preferInstance(i, this);
        });
        s.addRow(Ui.row(instOnOff, instRemove), Ui.GAP_S);
        s.addRow(Ui.row(instShow, instPrefer), Ui.GAP_XS);
        s.addRow(partsBox, Ui.GAP_S);
        return s;
    }

    private void refreshInstances() {
        Graph g = TemplateWorkbench.activeGraph();
        TemplateInstance sel = instanceList.getSelectedValue();
        instanceModel.clear();
        for (TemplateInstance i : InstanceRegistry.of(g)) instanceModel.addElement(i);
        if (sel != null && instanceModel.contains(sel)) instanceList.setSelectedValue(sel, false);
        instanceSection.setTrailingText(String.valueOf(instanceModel.size()));
        instanceList.setVisibleRowCount(Math.max(1, Math.min(5, instanceModel.size())));
        updateInstanceActions();
        instanceList.repaint();
    }

    private void updateInstanceActions() {
        TemplateInstance i = instanceList.getSelectedValue();
        boolean any = i != null;
        instOnOff.setEnabled(any);
        instRemove.setEnabled(any);
        instShow.setEnabled(any);
        instPrefer.setEnabled(any && i.status(ConstraintStore.constraints).notApplied > 0);
        instOnOff.setText(any && !i.isOn() ? "Switch on" : "Switch off");
        partsBox.clearRows();
        if (!any) {
            instanceInfo.setText(Ui.wrapped(instanceModel.isEmpty() ? "No template applied to this graph yet." : "Choose an instance to see and change it.", Ui.TEXT_WIDTH));
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
        boolean more = run != null && !running && run.state() == SearchRun.State.LIMITED && run.isLimitedByMatches();
        findMore.setVisible(more);
        if (more) {
            findMore.setText("Find more (up to " + fmt(run.options.maxMatches * 2L) + ")");
            findMore.setToolTipText(Ui.tooltip("Searches again and collects up to " + fmt(run.options.maxMatches * 2L)
                    + " matches. The matches applied so far stay applied."));
        }
        boolean out = g != null && wb.isRunOutdated(g) && run != null && !running;
        outdated.setVisible(out);
        searchAgain.setVisible(out);
        if (out) outdated.setText(Ui.wrapped("<font color='#b06000'>The graph changed after the search: new matches may exist, "
                + "changed ones are marked outdated.</font>", Ui.TEXT_WIDTH));

        if (showSwitch.isSelected() != wb.isSuggestionMode()) showSwitch.setSelected(wb.isSuggestionMode());
        ConstraintsTab.showState(showState, wb.isSuggestionMode());

        updatingMatches = true;
        try {
            Match keep = matchList.getSelectedValue();
            List<Match> shown = wb.shown(g);
            if (!sameList(shown)) {
                matchModel.clear();
                for (Match m : shown) matchModel.addElement(m);
            }
            if (wb.focus != null && matchModel.contains(wb.focus)) matchList.setSelectedValue(wb.focus, true);
            else if (keep != null && matchModel.contains(keep)) matchList.setSelectedValue(keep, false);
            else matchList.clearSelection();
        } finally {
            updatingMatches = false;
        }
        List<Match> all = wb.matches(g);
        matchList.setVisibleRowCount(Math.max(1, Math.min(7, matchModel.size())));
        matchesSection.setTrailingText(all.isEmpty() ? null : fmt(all.size()));
        int page = wb.page(g), pages = wb.pageCount(g);
        int from = page * TemplateLimits.maxShown;
        pageLabel.setText(all.isEmpty() ? "No matches yet."
                : pages > 1 ? (from + 1) + "–" + (from + matchModel.size()) + " of " + fmt(all.size())
                : "All " + fmt(all.size()) + " shown");
        prevPage.setEnabled(page > 0);
        nextPage.setEnabled(page < pages - 1);
        prevPage.setVisible(pages > 1);
        nextPage.setVisible(pages > 1);
        List<Match> sel = wb.selected(g);
        int constraints = TemplateWorkbench.constraintCount(sel);
        selectionInfo.setText(Ui.wrapped(sel.isEmpty() ? "No match selected for applying." : sel.size() + (sel.size() == 1 ? " match" : " matches")
                + " selected → " + constraints + (constraints == 1 ? " constraint" : " constraints")
                + (sel.size() > TemplateLimits.maxApplyPerStep ? " (the first " + TemplateLimits.maxApplyPerStep + " are applied per step)" : ""), Ui.TEXT_WIDTH));
        applySelected.setText(sel.isEmpty() ? "Apply selected" : "Apply selected (" + sel.size() + ")");
        applySelected.setEnabled(!sel.isEmpty() && !running);
        int applicable = 0;
        for (Match m : all) if (m.state == Match.State.FOUND || m.state == Match.State.STALE) applicable++;
        boolean complete = run != null && run.state() == SearchRun.State.COMPLETE;
        applyAll.setText(applicable == 0 ? "Apply all found…" : "Apply all found (" + fmt(applicable) + (complete ? "" : ", search incomplete") + ")…");
        applyAll.setToolTipText(Ui.tooltip("Applies every found match that is not applied yet, at most " + TemplateLimits.maxApplyPerStep
                + " per step; shows the numbers first." + (complete ? "" : " The search stopped early: this means all <b>found</b> matches, not all in the graph.")));
        applyAll.setEnabled(applicable > 0 && !running);
        selectAll.setEnabled(applicable > 0);
        clearSel.setEnabled(!sel.isEmpty());
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
        return new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
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
                if (it.template.isComposite()) b.add("Composite · " + it.template.components.size() + " parts");
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
        private final JLabel box = new JLabel(), head = new JLabel(), body = new JLabel();

        MatchRenderer() {
            super(new BorderLayout(Ui.GAP_S, 0));
            setBorder(new EmptyBorder(2, 4, 2, 4));
            box.setFont(Ui.font().deriveFont(15f));
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
            boolean selectable = m.state != Match.State.APPLIED && m.state != Match.State.INVALID;
            box.setText(!selectable ? " " : m.selected ? "☑" : "☐");
            Color c = MatchOverlay.colorOf(m);
            head.setText(MatchOverlay.symbolOf(m) + " " + m.title());
            List<String> labels = new ArrayList<>();
            for (Node n : m.nodes()) {
                String s = ConstraintCatalog.plainLabel(n);
                labels.add(s);
                if (labels.size() >= 3) break;
            }
            body.setText(MatchOverlay.stateText(m) + " · " + String.join(", ", labels) + (m.nodes().size() > 3 ? ", …" : "")
                    + (m.variants().size() > 1 ? " · " + m.variants().size() + " assignments" : ""));
            setBackground(rowBackground(l, index, selected));
            head.setForeground(selected ? l.getSelectionForeground() : c);
            body.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            box.setForeground(selected ? l.getSelectionForeground() : Ui.ACCENT);
            setOpaque(true);
            return this;
        }
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
