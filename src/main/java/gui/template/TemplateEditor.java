package gui.template;

import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import gui.ConstraintCatalog;
import gui.components.PrimaryButton;
import gui.components.Ui;
import layout.template.io.TemplateFiles;
import layout.template.io.TemplateLibrary;
import layout.template.model.ComponentUse;
import layout.template.model.LayoutRelation;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The template editor: a separate, non-modal window that works on its own copy of a template with its own selection,
 * constraint context and undo history, so the main graph, its store, selection and layout are never touched. Pattern
 * templates show constraint list, pattern graph and properties; composites show building blocks, component graph and
 * properties, with a path at the top to look into components and back. Saving validates the template first and
 * writes a new revision; "Save as copy" makes a new template.
 *
 * @author Niklas Gröne
 */
final class TemplateEditor extends JFrame {

    private static final long serialVersionUID = 1L;

    private final EditorModel model;
    private File file;
    private final Consumer<TemplateDef> onSaved;
    private final PrimaryButton saveButton = new PrimaryButton("Save");
    private final JButton undoButton = Ui.button("Undo", null, e -> undo());
    private final JButton redoButton = Ui.button("Redo", null, e -> redo());
    private final JLabel problemsLabel = new JLabel(" ");
    private final JLabel messageLabel = new JLabel(" ");
    private final JPanel banner = new JPanel(new BorderLayout());
    private final JLabel bannerText = new JLabel();
    private final JPanel crumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final JPanel rightCards = new JPanel(new CardLayout());
    private PatternCanvas patternCanvas;
    private ComponentCanvas componentCanvas;
    /** Components looked into, from the edited template down (read-only views). */
    private final List<ComponentUse> path = new ArrayList<>();
    private final List<TemplateDef> pathTemplates = new ArrayList<>();
    /** The canvas and info panel of the read-only look inside shown now (null: none), replaced on each navigation. */
    private JComponent viewCanvas, viewInfo;

    private TemplateEditor(TemplateDef def, File file, TemplateLibrary library, String bannerMessage, Consumer<TemplateDef> onSaved) {
        super("Template: " + def.name);
        this.file = file;
        this.onSaved = onSaved;
        this.model = new EditorModel(def, library);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
        });
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);
        root.add(north(bannerMessage), BorderLayout.NORTH);
        JComponent left;
        if (def.isComposite()) {
            componentCanvas = new ComponentCanvas(model, false);
            componentCanvas.onOpen = this::openInside;
            centerCards.add(componentCanvas, "edit");
            left = new CompositeSidebar(model, this);
            rightCards.add(new CompositeProperties(model, this), "edit");
        } else {
            patternCanvas = new PatternCanvas(model);
            patternCanvas.setName("patternCanvas");
            JPanel center = new JPanel(new BorderLayout());
            center.add(canvasToolbar(), BorderLayout.NORTH);
            center.add(patternCanvas, BorderLayout.CENTER);
            centerCards.add(center, "edit");
            left = new ConstraintSpecList(model);
            rightCards.add(new PatternProperties(model), "edit");
        }
        // fixed side columns, the canvas takes the rest of the (resizable) window
        JPanel body = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(290, 10));
        rightCards.setPreferredSize(new Dimension(310, 10));
        left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.RULE));
        rightCards.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.RULE));
        body.add(left, BorderLayout.WEST);
        body.add(centerCards, BorderLayout.CENTER);
        body.add(rightCards, BorderLayout.EAST);
        root.add(body, BorderLayout.CENTER);
        root.add(south(), BorderLayout.SOUTH);
        setContentPane(root);
        bindKeys(root);
        model.addListener(this::refresh);
        refresh();
        setSize(1180, 760);
        setMinimumSize(new Dimension(820, 520));
        setLocationByPlatform(true);
    }

    /** Opens an editor window for {@code def} (a copy is edited). */
    static TemplateEditor open(TemplateDef def, File file, TemplateLibrary library, String banner, Consumer<TemplateDef> onSaved) {
        TemplateEditor e = new TemplateEditor(def.copy(), file, library, banner, onSaved);
        e.setVisible(true);
        return e;
    }

    // ================================================================== layout

    private JComponent north(String bannerMessage) {
        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(Color.WHITE);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP_M, Ui.GAP_S));
        bar.setOpaque(false);
        saveButton.setName("saveTemplate");
        saveButton.setToolTipText(Ui.tooltip("Checks the template and saves it as a new revision (⌘S)."));
        saveButton.addActionListener(e -> save(false));
        JButton copy = Ui.button("Save as copy…", "Saves the template as a new, independent template.", e -> save(true));
        copy.setName("saveCopy");
        undoButton.setName("editorUndo");
        redoButton.setName("editorRedo");
        bar.add(saveButton);
        bar.add(copy);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(undoButton);
        bar.add(redoButton);
        crumbs.setOpaque(false);
        bar.add(Box.createHorizontalStrut(Ui.GAP_L));
        bar.add(crumbs);
        north.add(bar, BorderLayout.NORTH);
        banner.setBackground(new Color(0xFF, 0xF8, 0xE1));
        banner.setBorder(new EmptyBorder(6, 10, 6, 10));
        bannerText.setFont(Ui.small());
        banner.add(bannerText, BorderLayout.CENTER);
        JButton close = new JButton("×");
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setToolTipText("Hide this note");
        close.addActionListener(e -> {
            banner.setVisible(false);
            revalidate();
        });
        banner.add(close, BorderLayout.EAST);
        setBanner(bannerMessage);
        north.add(banner, BorderLayout.SOUTH);
        return north;
    }

    void setBanner(String text) {
        banner.setVisible(text != null && !text.isEmpty());
        bannerText.setText(text == null ? "" : "<html>" + Ui.esc(text) + "</html>");
    }

    private JComponent canvasToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP_S, Ui.GAP_XS));
        bar.setBackground(new Color(0xF6, 0xF8, 0xFB));
        JPanel bar2 = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP_S, Ui.GAP_XS));
        bar2.setBackground(new Color(0xF6, 0xF8, 0xFB));
        JPanel both = new JPanel(new GridLayout(2, 1));
        both.add(bar);
        both.add(bar2);
        ButtonGroup g = new ButtonGroup();
        JToggleButton select = new JToggleButton("Select / move", true), add = new JToggleButton("Add node"), connect = new JToggleButton("Connect");
        select.setToolTipText(Ui.tooltip("Click to select, drag to move; drag on the background to select several. Delete removes."));
        add.setToolTipText(Ui.tooltip("Click on the canvas to add a node (Esc ends)."));
        connect.setToolTipText(Ui.tooltip("Drag from one node to another to add an edge."));
        select.setName("toolSelect");
        add.setName("toolAddNode");
        connect.setName("toolConnect");
        for (JToggleButton b : new JToggleButton[]{select, add, connect}) {
            g.add(b);
            bar.add(b);
        }
        select.addActionListener(e -> patternCanvas.tool = PatternCanvas.Tool.SELECT);
        add.addActionListener(e -> patternCanvas.tool = PatternCanvas.Tool.ADD_NODE);
        connect.addActionListener(e -> patternCanvas.tool = PatternCanvas.Tool.CONNECT);
        model.addListener(() -> {
            if (patternCanvas.tool == PatternCanvas.Tool.SELECT && !select.isSelected()) select.setSelected(true);
        });
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        JButton fit = Ui.button("Fit", "Fits the pattern into the view (the stored positions stay as they are).", e -> patternCanvas.fit());
        JButton zin = Ui.button("+", "Zoom in (or the mouse wheel)", e -> patternCanvas.zoom(1.25));
        JButton zout = Ui.button("−", "Zoom out", e -> patternCanvas.zoom(0.8));
        bar.add(fit);
        bar.add(zin);
        bar.add(zout);
        JCheckBox keep = new JCheckBox("Keep constraints while dragging", true);
        keep.setOpaque(false);
        keep.setName("keepConstraints");
        keep.setToolTipText(Ui.tooltip("On: each drag step is solved with the template's constraints; what holds a node back is shown. "
                + "Off: nodes move freely and constraints that stop holding are marked. Dragging never changes a constraint's values."));
        keep.addActionListener(e -> patternCanvas.solveWhileDragging = keep.isSelected());
        JCheckBox all = new JCheckBox("Show all constraints", true);
        all.setOpaque(false);
        all.addActionListener(e -> {
            patternCanvas.showAllConstraints = all.isSelected();
            patternCanvas.repaint();
        });
        JButton solve = Ui.button("Apply constraints", "Solves the template's constraints once on the pattern (like a solve in the graph).", e -> {
            model.beginStep("Apply constraints");
            java.util.Map<String, String> unmet = model.solve(false);
            model.message = unmet.isEmpty() ? "All constraints hold." : unmet.size() + " constraints do not hold (see the list).";
            model.endStep();
        });
        bar2.add(keep);
        bar2.add(all);
        bar2.add(solve);
        return both;
    }

    private JComponent south() {
        JPanel s = new JPanel(new BorderLayout(Ui.GAP_L, 0));
        s.setBorder(new EmptyBorder(5, 10, 5, 10));
        s.setBackground(new Color(0xF6, 0xF8, 0xFB));
        problemsLabel.setFont(Ui.small().deriveFont(Font.BOLD));
        problemsLabel.setName("problems");
        problemsLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        problemsLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showProblems(); }
        });
        messageLabel.setFont(Ui.small());
        messageLabel.setForeground(Ui.MUTED);
        messageLabel.setName("editorMessage");
        s.add(problemsLabel, BorderLayout.WEST);
        s.add(messageLabel, BorderLayout.CENTER);
        return s;
    }

    private void bindKeys(JComponent root) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "save", () -> save(false));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "undo", this::undo);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask | KeyEvent.SHIFT_DOWN_MASK), "redo", this::redo);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Y, mask), "redo2", this::redo);
    }

    private static void bind(JComponent c, KeyStroke k, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, name);
        c.getActionMap().put(name, PatternCanvas.action(r));
    }

    // ================================================================== refresh

    private void refresh() {
        TemplateDef t = model.def();
        setTitle((model.isDirty() ? "• " : "") + "Template: " + t.name + " (rev. " + t.revision + ")" + (t.isComposite() ? " — composite" : ""));
        undoButton.setEnabled(model.canUndo());
        redoButton.setEnabled(model.canRedo());
        undoButton.setToolTipText(Ui.tooltip(model.canUndo() ? "Undo: " + model.undoName() + " (⌘Z)" : "Nothing to undo"));
        redoButton.setToolTipText(Ui.tooltip(model.canRedo() ? "Redo: " + model.redoName() + " (⇧⌘Z)" : "Nothing to redo"));
        int err = 0, na = 0, warn = 0;
        for (TemplateValidator.Problem p : model.problems()) {
            if (p.severity == TemplateValidator.Severity.ERROR) err++;
            else if (p.severity == TemplateValidator.Severity.NOT_APPLICABLE) na++;
            else warn++;
        }
        if (err + na + warn == 0) {
            problemsLabel.setText("✓ No problems");
            problemsLabel.setForeground(Ui.SUCCESS);
        } else {
            List<String> parts = new ArrayList<>();
            if (err > 0) parts.add(err + (err == 1 ? " error" : " errors"));
            if (na > 0) parts.add(na + " not applicable");
            if (warn > 0) parts.add(warn + (warn == 1 ? " warning" : " warnings"));
            problemsLabel.setText((err > 0 ? "✕ " : "! ") + String.join(" · ", parts) + " — show");
            problemsLabel.setForeground(err > 0 ? Ui.ERROR : Ui.WARNING);
        }
        messageLabel.setText(model.message == null ? " " : model.message);
        refreshCrumbs();
        if (patternCanvas != null) patternCanvas.repaint();
        if (componentCanvas != null) componentCanvas.repaint();
    }

    private void showProblems() {
        StringBuilder sb = new StringBuilder("<html>");
        if (model.problems().isEmpty()) sb.append("No problems.");
        for (TemplateValidator.Problem p : model.problems()) {
            String c = p.severity == TemplateValidator.Severity.ERROR ? "#c5221f" : p.severity == TemplateValidator.Severity.NOT_APPLICABLE ? "#b06000" : "#5f6368";
            sb.append("<font color='").append(c).append("'>").append(p.severity == TemplateValidator.Severity.ERROR ? "Error" :
                    p.severity == TemplateValidator.Severity.NOT_APPLICABLE ? "Cannot be applied" : "Note").append("</font>: ")
                    .append(Ui.esc(p.toString())).append("<br>");
        }
        sb.append("</html>");
        JOptionPane.showMessageDialog(this, new JLabel(sb.toString()), "Problems of the template", JOptionPane.PLAIN_MESSAGE);
        for (TemplateValidator.Problem p : model.problems()) {
            if (p.elementId == null) continue;
            if (model.def().constraint(p.elementId) != null) model.selConstraint = p.elementId;
            else if (model.def().component(p.elementId) != null) model.selComponent = p.elementId;
            else if (model.def().link(p.elementId) != null) model.selLink = p.elementId;
            else if (model.def().relation(p.elementId) != null) model.selRelation = p.elementId;
            else continue;
            model.fire();
            break;
        }
    }

    private void undo() {
        model.undo();
        model.message = "Undone.";
        model.fire();
    }

    private void redo() {
        model.redo();
        model.message = "Redone.";
        model.fire();
    }

    // ================================================================== saving

    /** Saves (a new revision, or a copy); returns false if it was not saved. */
    boolean save(boolean asCopy) {
        TemplateDef t = model.def().copy();
        if (asCopy) {
            String name = JOptionPane.showInputDialog(this, "Name of the copy:", t.name + " copy");
            if (name == null || name.trim().isEmpty()) return false;
            t.id = TemplateDef.newId();
            t.revision = 1;
            t.name = name.trim();
            t.source = "copy";
        }
        if (t.isComposite()) TemplateLibrary.embedDependencies(t, model.source());
        TemplateSource src = TemplateSource.chain(model.library, TemplateSource.embeddedIn(t));
        List<TemplateValidator.Problem> ps = TemplateValidator.validate(t, src);
        if (!TemplateValidator.canSave(ps)) {
            StringBuilder sb = new StringBuilder("<html><b>The template cannot be saved yet:</b><br>");
            for (TemplateValidator.Problem p : ps) if (p.severity == TemplateValidator.Severity.ERROR) sb.append("• ").append(Ui.esc(p.toString())).append("<br>");
            sb.append("<br>Fix or delete these elements (the list shows them in red).</html>");
            JOptionPane.showMessageDialog(this, new JLabel(sb.toString()), "Save template", JOptionPane.WARNING_MESSAGE);
            showProblems();
            return false;
        }
        File target = asCopy ? null : file;
        if (target == null) {
            File dir = model.library == null ? null : model.library.getFolder();
            JFileChooser fc = new JFileChooser(dir);
            fc.setSelectedFile(new File(dir == null ? fc.getCurrentDirectory() : dir, TemplateFiles.fileName(t.name, TemplateFiles.TEMPLATE_EXT)));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            target = fc.getSelectedFile();
            if (!target.getName().toLowerCase(Locale.ROOT).endsWith(TemplateFiles.TEMPLATE_EXT)) {
                target = new File(target.getParentFile(), target.getName().replaceAll("(?i)\\.json$", "") + TemplateFiles.TEMPLATE_EXT);
            }
            if (target.exists() && JOptionPane.showConfirmDialog(this, target.getName() + " exists. Replace it?", "Save template",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false;
            TemplateDef known = model.library == null ? null : model.library.latest(t.id);
            if (model.isDirty() && known != null) t.revision = Math.max(t.revision, known.revision) + 1;   // a changed known template
        } else if (model.isDirty()) {
            t.revision = model.def().revision + 1;
        }
        try {
            TemplateFiles.save(t, target, model.library);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save template", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        file = target;
        model.replace(t);
        model.setClean();
        boolean notApplicable = !TemplateValidator.canApply(ps);
        model.message = "Saved " + target.getName() + " (revision " + t.revision + ")." + (notApplicable
                ? " It contains parts this version cannot apply; they were kept unchanged." : "");
        model.fire();
        if (onSaved != null) onSaved.accept(t.copy());
        return true;
    }

    private void close() {
        if (model.isDirty()) {
            int a = JOptionPane.showConfirmDialog(this, "Save the changes to '" + model.def().name + "'?", "Close template editor",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (a == JOptionPane.CANCEL_OPTION || a == JOptionPane.CLOSED_OPTION) return;
            if (a == JOptionPane.YES_OPTION && !save(false)) return;
        }
        dispose();
    }

    // ================================================================== composites: navigation and components

    /** Shows a component's template read-only; the path at the top leads back. */
    void openInside(ComponentUse c) {
        TemplateDef current = pathTemplates.isEmpty() ? model.def() : pathTemplates.get(pathTemplates.size() - 1);
        TemplateDef inner = Templates.resolve(c, sourceWith(current));
        if (inner == null) {
            model.message = "The template of '" + c.name + "' is not available.";
            model.fire();
            return;
        }
        path.add(c);
        pathTemplates.add(inner);
        showLevel();
    }

    private void showLevel() {
        CardLayout cl = (CardLayout) centerCards.getLayout(), cr = (CardLayout) rightCards.getLayout();
        if (viewCanvas != null) centerCards.remove(viewCanvas);
        if (viewInfo != null) rightCards.remove(viewInfo);
        viewCanvas = null;
        viewInfo = null;
        if (path.isEmpty()) {
            cl.show(centerCards, "edit");
            cr.show(rightCards, "edit");
            refreshCrumbs();
            return;
        }
        TemplateDef inner = pathTemplates.get(pathTemplates.size() - 1);
        EditorModel view = new EditorModel(inner.copy(), model.library);
        JComponent canvas;
        if (inner.isComposite()) {
            ComponentCanvas cc = new ComponentCanvas(view, true);
            cc.onOpen = this::openInside;
            canvas = cc;
        } else {
            PatternCanvas pc = new PatternCanvas(view);
            pc.readOnly = true;
            canvas = pc;
        }
        viewCanvas = canvas;
        centerCards.add(canvas, "view");
        cl.show(centerCards, "view");
        JPanel info = new JPanel(new BorderLayout());
        info.setBorder(new EmptyBorder(12, 12, 12, 12));
        info.setOpaque(false);
        ComponentUse c = path.get(path.size() - 1);
        JPanel box = new JPanel(new GridLayout(0, 1, 0, 6));
        box.setOpaque(false);
        box.add(new JLabel("<html><b>" + Ui.esc(c.name) + "</b> uses " + Ui.esc(inner.name) + " (rev. " + inner.revision + ").<br>"
                + "This is a read-only look inside.<br>To change this use, go back and select it; to change the template itself, "
                + "use Edit template.</html>"));
        JButton back = Ui.button("Back to " + (path.size() == 1 ? model.def().name : path.get(path.size() - 2).name), null, e -> {
            path.remove(path.size() - 1);
            pathTemplates.remove(pathTemplates.size() - 1);
            showLevel();
        });
        back.setName("navigateBack");
        box.add(back);
        if (path.size() == 1) box.add(Ui.button("Edit template…", null, e -> editComponentTemplate(c)));
        info.add(box, BorderLayout.NORTH);
        viewInfo = info;
        rightCards.add(info, "view");
        cr.show(rightCards, "view");
        refreshCrumbs();
    }

    private void refreshCrumbs() {
        crumbs.removeAll();
        JButton root = link(model.def().name, () -> {
            path.clear();
            pathTemplates.clear();
            showLevel();
        });
        root.setFont(path.isEmpty() ? Ui.bold() : Ui.font());
        crumbs.add(root);
        for (int i = 0; i < path.size(); i++) {
            final int depth = i + 1;
            JLabel sep = new JLabel("›");
            sep.setForeground(Ui.MUTED);
            crumbs.add(sep);
            JButton b = link(path.get(i).name + " (" + pathTemplates.get(i).name + ")", () -> {
                while (path.size() > depth) {
                    path.remove(path.size() - 1);
                    pathTemplates.remove(pathTemplates.size() - 1);
                }
                showLevel();
            });
            b.setFont(i == path.size() - 1 ? Ui.bold() : Ui.font());
            crumbs.add(b);
        }
        crumbs.revalidate();
        crumbs.repaint();
    }

    private static JButton link(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setForeground(Ui.ACCENT);
        b.setMargin(new Insets(0, 2, 0, 2));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> r.run());
        return b;
    }

    /** Adds {@code t} as a new component (after checking that no endless nesting arises). */
    void insertComponent(TemplateDef t) {
        TemplateSource src = sourceWith(t);
        String why = Templates.whyNotComponent(model.def(), t, src);
        if (why != null) {
            JOptionPane.showMessageDialog(this, why, "Insert component", JOptionPane.WARNING_MESSAGE);
            return;
        }
        model.change("Insert " + t.name, () -> {
            TemplateDef d = model.def();
            String name = t.name;
            int k = 2;
            while (true) {
                boolean taken = false;
                for (ComponentUse c : d.components) taken |= c.name.equals(name);
                if (!taken) break;
                name = t.name + " " + k++;
            }
            ComponentUse c = new ComponentUse(d.nextComponentId(), name, t.ref());
            double[] at = ComponentCanvas.freePlace(d);
            c.x = at[0];
            c.y = at[1];
            d.components.add(c);
            embedClosure(d, t, src);
            model.selComponent = c.id;
            model.selLink = null;
            model.selRelation = null;
            model.message = "Inserted '" + name + "'. Give it a role name, connect its ports and add layout relations."
                    + (t.ports.isEmpty() ? " (This template has no ports yet.)" : "");
        });
    }

    /** The editor's template source, followed by the dependencies embedded in {@code t}. */
    private TemplateSource sourceWith(TemplateDef t) {
        return TemplateSource.chain(model.source(), TemplateSource.embeddedIn(t));
    }

    /** Puts a copy of every template in the closure of {@code t} (without nested dependencies) into {@code into}. */
    private static void embedClosure(TemplateDef into, TemplateDef t, TemplateSource src) {
        for (TemplateDef dep : Templates.closure(t, src)) {
            TemplateDef cp = dep.copy();
            cp.dependencies.clear();
            into.dependencies.put(cp.id + "@" + cp.revision, cp);
        }
    }

    /** Uses the newer revision for component {@code c}, after showing what it changes. */
    void updateComponent(ComponentUse c, TemplateDef newer) {
        TemplateDef trial = model.def().copy();
        trial.component(c.id).ref = newer.ref();
        trial.dependencies.put(newer.id + "@" + newer.revision, newer.copy());
        List<TemplateValidator.Problem> ps = TemplateValidator.validate(trial, sourceWith(newer));
        StringBuilder sb = new StringBuilder("<html><b>Update '" + Ui.esc(c.name) + "' to " + Ui.esc(newer.name) + " rev. " + newer.revision + "</b><br><br>");
        int bad = 0;
        for (TemplateValidator.Problem p : ps) {
            if (p.severity == TemplateValidator.Severity.WARNING) continue;
            bad++;
            sb.append("<font color='#c5221f'>• ").append(Ui.esc(p.toString())).append("</font><br>");
        }
        if (bad == 0) sb.append("<font color='#1e8e3e'>✓ Its ports and constraints still fit this composite.</font><br>");
        else sb.append("<br>After the update these rules are marked invalid until you fix them.<br>");
        sb.append("</html>");
        if (JOptionPane.showConfirmDialog(this, new JLabel(sb.toString()), "Update component", JOptionPane.OK_CANCEL_OPTION,
                bad == 0 ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        model.change("Update " + c.name, () -> {
            model.def().component(c.id).ref = newer.ref();
            embedClosure(model.def(), newer, sourceWith(newer));
            model.message = "'" + c.name + "' uses revision " + newer.revision + " now.";
        });
    }

    /** Opens the component's template itself in another editor (changes become a new revision). */
    void editComponentTemplate(ComponentUse c) {
        TemplateDef inner = Templates.resolve(c, model.source());
        TemplateDef newest = model.library == null || c.ref == null ? null : model.library.latest(c.ref.id);
        TemplateDef t = newest != null && inner != null && newest.revision > inner.revision ? newest : inner;
        if (t == null) return;
        TemplateLibrary.Entry entry = model.library == null ? null : model.library.find(t.id);
        List<String> uses = model.library == null ? new ArrayList<>() : model.library.usesOf(t.id);
        String banner = "You are changing the reused template '" + t.name + "'. Saving makes a new revision; '" + model.def().name
                + "' and every other use keep their revision until they are updated" + (uses.isEmpty() ? "." : " (" + uses.size() + " uses: "
                + String.join("; ", uses.subList(0, Math.min(3, uses.size()))) + (uses.size() > 3 ? "; …" : "") + ").");
        open(t, entry == null || TemplateFiles.isGmlFile(entry.file) ? null : entry.file, model.library, banner, saved -> {
            if (onSaved != null) onSaved.accept(saved);
            if (model.library != null) model.library.reload();
            model.rebuild();
            model.fire();
        });
    }

    /** Gives this use its own copy of the template (stored inside the composite). */
    void makeIndependentCopy(ComponentUse c) {
        TemplateDef inner = Templates.resolve(c, model.source());
        if (inner == null) return;
        model.change("Make independent copy", () -> {
            TemplateDef copy = inner.copy();
            copy.id = TemplateDef.newId();
            copy.revision = 1;
            copy.name = inner.name + " (" + c.name + ")";
            copy.source = "copy";
            copy.dependencies.clear();
            model.def().dependencies.put(copy.id + "@" + copy.revision, copy);
            model.def().component(c.id).ref = copy.ref();
            model.message = "'" + c.name + "' now uses its own copy '" + copy.name + "'; other uses of '" + inner.name
                    + "' are unchanged. Edit it with Edit template.";
        });
    }

    // ================================================================== shared helpers

    /** Title of a constraint definition: its name, or its kind with the main value. */
    static String title(ConstraintSpec s) {
        if (s.name != null && !s.name.trim().isEmpty()) return s.name.trim();
        if (s.kind() == null) return s.type;
        String t = ConstraintCatalog.info(s.kind()) != null ? ConstraintCatalog.info(s.kind()).title : ConstraintSpecs.title(s.kind());
        switch (s.kind()) {
            case SEPARATION_X:
            case SEPARATION_Y:
                return t + (s.bool("exact", false) ? " = " : " ≥ ") + ConstraintCatalog.px(s.num("distance", 0));
            case ORDER_X:
            case ORDER_Y:
                return t + (s.bool("exact", false) ? ", gaps = " : ", gaps ≥ ") + ConstraintCatalog.px(s.num("gap", 0));
            case ROW:
            case COLUMN:
                return t + (s.bool("even", false) ? ", gaps = " : ", gaps ≥ ") + ConstraintCatalog.px(s.num("gap", 0));
            case DISTRIBUTE_X:
            case DISTRIBUTE_Y:
                return t + " = " + ConstraintCatalog.px(s.num("spacing", 0));
            case STAR:
                return t + ", radius " + ConstraintCatalog.px(s.num("radius", 0));
            case DIVIDE_X:
            case DIVIDE_Y:
                return t + " (" + s.role("before").size() + " | " + s.role("after").size() + ")";
            default:
                return t;
        }
    }

    /** Asks before deleting ports (of the given nodes, or the given port) that composites of the library use. */
    static boolean confirmPortDeletion(Component parent, EditorModel model, Collection<String> nodeIds, Port... explicit) {
        List<Port> ports = new ArrayList<>(model.portsOn(nodeIds));
        Collections.addAll(ports, explicit);
        if (ports.isEmpty() || model.library == null) return true;
        List<String> uses = new ArrayList<>();
        for (TemplateDef t : model.library.templates()) {
            for (ComponentUse c : t.components) {
                if (c.ref == null || !c.ref.id.equals(model.def().id)) continue;
                for (Port p : ports) {
                    for (StructureLink l : t.links) {
                        if ((c.id.equals(l.fromComponent) && p.id.equals(l.fromPort)) || (c.id.equals(l.toComponent) && p.id.equals(l.toPort)))
                            uses.add(t.name + " › " + c.name + ": link " + l.id + " uses '" + p.name + "'");
                    }
                    for (LayoutRelation r : t.relations) {
                        for (LayoutRelation.PortRef pr : r.ports) {
                            if (c.id.equals(pr.component) && p.id.equals(pr.port)) uses.add(t.name + " › " + c.name + ": layout relation " + r.id + " uses '" + p.name + "'");
                        }
                    }
                    for (Port cp : t.ports) {
                        if (c.id.equals(cp.component) && p.id.equals(cp.componentPort)) uses.add(t.name + " passes on '" + p.name + "' as '" + cp.name + "'");
                    }
                }
            }
        }
        if (uses.isEmpty()) return true;
        StringBuilder sb = new StringBuilder("<html><b>These ports are used:</b><br>");
        for (String u : uses.subList(0, Math.min(10, uses.size()))) sb.append("• ").append(Ui.esc(u)).append("<br>");
        if (uses.size() > 10) sb.append("… and ").append(uses.size() - 10).append(" more<br>");
        sb.append("<br>After deleting, those rules are invalid (when updated to the new revision) until another port is chosen.<br>Delete anyway?</html>");
        return JOptionPane.showConfirmDialog(parent, new JLabel(sb.toString()), "Delete port", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
