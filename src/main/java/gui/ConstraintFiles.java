package gui;

import constraints.ConflictLog;
import constraints.ConstraintStore;
import gui.components.Ui;
import layout.store.GraphConstraints;
import layout.store.StoreCodec;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;

import org.graffiti.editor.MainFrame;
import org.graffiti.editor.MessageType;
import org.graffiti.graph.Graph;
import org.graffiti.session.EditorSession;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Save constraints…" and "Load constraints…" of the Constraints page: the store of the active graph as a companion
 * file next to the map ({@link GraphConstraints#companionOf}), for maps whose file format does not keep the graph
 * attributes (SBGN-ML). The file is read automatically when a map with a companion is opened.
 */
final class ConstraintFiles {

    private static final String SAVE_TITLE = "Save constraints", LOAD_TITLE = "Load constraints";

    private ConstraintFiles() { }

    static void save(Component parent) {
        Graph g = activeGraph(parent, SAVE_TITLE);
        if (g == null) return;
        File suggested = suggested();
        JFileChooser fc = chooser(SAVE_TITLE, suggested);
        if (suggested != null) fc.setSelectedFile(suggested);
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".json")) f = new File(f.getParentFile(), f.getName() + GraphConstraints.COMPANION_EXT);
        try {
            GraphConstraints.writeCompanion(g, ConstraintStore.constraints, f);
            MainFrame.showMessage("Constraints saved to " + f.getName() + ".", MessageType.INFO);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Could not save the constraints: " + e.getMessage(), SAVE_TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    static void load(Component parent) {
        Graph g = activeGraph(parent, LOAD_TITLE);
        if (g == null) return;
        File suggested = suggested();
        JFileChooser fc = chooser(LOAD_TITLE, suggested);
        if (suggested != null && suggested.isFile()) fc.setSelectedFile(suggested);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!ConstraintStore.constraints.getAllConstraints().isEmpty() || !InstanceRegistry.of(g).isEmpty()) {
            Object[] options = {"Replace", "Add", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent, "The map already has constraints. Replace them with the file's, "
                    + "or add the file's to them?", LOAD_TITLE, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice != 0 && choice != 1) return;
            if (choice == 0) {
                for (TemplateInstance inst : InstanceRegistry.of(g)) TemplateApplier.remove(inst, ConstraintStore.constraints);
                ConflictLog.forget(new ArrayList<>(ConstraintStore.constraints.getAllConstraints()));
                ConstraintStore.constraints.clear();
            }
        }
        try {
            GraphConstraints.markAttached(g);
            StoreCodec.Report r = GraphConstraints.readCompanion(f, g, ConstraintStore.constraints, gui.template.TemplatePanel.templateSource());
            AddonTab.afterConstraintsLoaded(g, r);
            if (!r.problems.isEmpty()) {
                StringBuilder sb = new StringBuilder("<html>").append(Ui.esc(r.summary())).append("<br><br>");
                List<String> ps = r.problems;
                for (int i = 0; i < Math.min(12, ps.size()); i++) sb.append("• ").append(Ui.esc(ps.get(i))).append("<br>");
                if (ps.size() > 12) sb.append("… and ").append(ps.size() - 12).append(" more");
                JOptionPane.showMessageDialog(parent, sb.toString(), LOAD_TITLE, JOptionPane.WARNING_MESSAGE);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Could not read the file: " + e.getMessage(), LOAD_TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /** The active graph, or null after telling the user that there is none. */
    private static Graph activeGraph(Component parent, String title) {
        Graph g = ConstraintStore.activeGraph();
        if (g == null || ConstraintStore.constraints == null) {
            JOptionPane.showMessageDialog(parent, "No active graph.", title, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return g;
    }

    /** A file chooser for constraint files that starts in the folder of {@code suggested}. */
    private static JFileChooser chooser(String title, File suggested) {
        JFileChooser fc = new JFileChooser(suggested == null ? null : suggested.getParentFile());
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter("Constraints (*" + GraphConstraints.COMPANION_EXT + ")", "json"));
        return fc;
    }

    /** The companion file of the active map, or null if the map has no file. */
    private static File suggested() {
        try {
            EditorSession s = MainFrame.getInstance().getActiveEditorSession();
            File f = AddonTab.fileOf(s);
            return f == null ? null : GraphConstraints.companionOf(f);
        } catch (Exception e) {
            return null;
        }
    }
}
