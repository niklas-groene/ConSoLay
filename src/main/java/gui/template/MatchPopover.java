package gui.template;

import constraints.ConstraintStore;
import gui.components.PrimaryButton;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.template.apply.TemplateInstance;
import layout.template.search.Match;
import layout.template.search.MatchCheck;

import org.graffiti.plugin.view.View;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Map;

/**
 * Action panel (popover) anchored beside a match on the graph view, placed where it covers the fewest nodes and following
 * the match while zooming and panning; at most one is open. It offers the actions that fit the match's state; where
 * matches overlap, a chooser lists them first and previews each on the graph.
 *
 * @author Niklas Gröne
 */
final class MatchPopover {

    private MatchPopover() { }

    private static JComponent panel;
    private static Match anchor;
    private static TemplateInstance anchorInstance;
    private static Point chooserPoint;
    private static Timer follow;
    /** What the popover was placed for last: no need to place it again while this stays. */
    private static Rectangle placedView, placedTarget;
    private static final int WIDTH = 290;

    static boolean isOpen() {
        return panel != null;
    }

    static void hideAll() {
        if (panel != null) {
            Container p = panel.getParent();
            if (p != null) {
                p.remove(panel);
                p.repaint();
            }
        }
        panel = null;
        anchor = null;
        anchorInstance = null;
        chooserPoint = null;
        placedView = null;
        placedTarget = null;
        if (follow != null) follow.stop();
    }

    /**
     * The layered pane of the main window (not of the view's internal frame): the popover lies above the match overlay,
     * which is installed there as well.
     */
    static JLayeredPane layered() {
        View v = TemplateWorkbench.activeView();
        JComponent c = v == null ? null : v.getViewComponent();
        Window w = c == null ? null : SwingUtilities.getWindowAncestor(c);
        return w instanceof JFrame ? ((JFrame) w).getLayeredPane() : null;
    }

    /** Opens the popover of {@code m} with the actions that fit its state; nothing is applied without its button. */
    static void show(Match m) {
        hideAll();
        if (m == null) return;
        JLayeredPane lp = layered();
        if (lp == null) return;
        TemplateWorkbench wb = TemplateWorkbench.get();
        Color c = MatchOverlay.colorOf(m);
        VerticalStack body = new VerticalStack();

        body.addRow(head(MatchOverlay.symbolOf(m) + "  " + m.title()));
        body.addRow(chip(MatchOverlay.stateText(m), c), Ui.GAP_XS);

        StringBuilder info = new StringBuilder();
        info.append(m.nodes().size()).append(" nodes · ").append(m.edges().size()).append(" edges · ")
                .append(m.task.plan.size()).append(m.task.plan.size() == 1 ? " constraint" : " constraints");
        if (m.task.template.isComposite()) {
            info.append("<br>Whole composite: ");
            int k = 0;
            for (Map.Entry<String, layout.template.apply.InstancePlan.Part> e : m.task.plan.parts.entrySet()) {
                if (e.getKey().contains("/")) continue;
                if (k++ > 0) info.append(", ");
                info.append(TemplateWorkbench.esc(e.getValue().name)).append(" (").append(TemplateWorkbench.esc(e.getValue().templateName)).append(')');
            }
        }
        if (m.task.ruleName != null) info.append("<br>Rule: ").append(TemplateWorkbench.esc(m.task.ruleName));
        body.addRow(text(info.toString(), Ui.TEXT), Ui.GAP_S);
        String[] lines = m.variant().explanation.split("\n");
        StringBuilder ex = new StringBuilder();
        for (int k = 0; k < lines.length && k < 3; k++) ex.append(k > 0 ? "<br>" : "").append(TemplateWorkbench.esc(lines[k]));
        if (lines.length > 3) ex.append("<br>…");
        JLabel exl = text(ex.toString(), Ui.MUTED);
        exl.setToolTipText(Ui.tooltip(TemplateWorkbench.esc(m.variant().explanation).replace("\n", "<br>")));
        body.addRow(exl, Ui.GAP_XS);

        List<String> shared = new java.util.ArrayList<>();
        for (Match o : wb.shown(m.run.snapshot.graph)) if (o != m && m.overlaps(o)) shared.add("#" + o.number);
        if (!shared.isEmpty()) {
            body.addRow(text("Shares nodes with " + String.join(", ", shared.subList(0, Math.min(6, shared.size())))
                    + (shared.size() > 6 ? " …" : "") + ". Sharing alone is no conflict; the compatibility below tells.", Ui.MUTED), Ui.GAP_XS);
        }
        if (m.state == Match.State.INVALID) {
            body.addRow(text("No longer fits: " + TemplateWorkbench.esc(m.reason), Ui.ERROR), Ui.GAP_S);
        } else if (m.state == Match.State.STALE) {
            body.addRow(text("The graph changed here after the search. The match is checked again before it is applied.", Ui.WARNING), Ui.GAP_S);
        }
        MatchCheck check = m.check;
        if (m.state != Match.State.APPLIED && m.state != Match.State.INVALID) {
            String ct = check.status == MatchCheck.Status.NOT_CHECKED ? "Compatibility: not checked yet" : check.summary;
            JLabel cl = text(TemplateWorkbench.esc(ct), check.status == MatchCheck.Status.CONFLICT ? Ui.ERROR
                    : check.status == MatchCheck.Status.COMPATIBLE ? Ui.SUCCESS : Ui.MUTED);
            if (!check.details.isEmpty()) {
                StringBuilder d = new StringBuilder();
                for (String s : check.details) d.append("• ").append(TemplateWorkbench.esc(s)).append("<br>");
                cl.setToolTipText(Ui.tooltip(d.toString()));
                cl.setText(cl.getText().replace("</td>", " <u>Details</u></td>"));
            }
            body.addRow(cl, Ui.GAP_S);
        }

        if (m.variants().size() > 1) {
            int k = m.chosenIndex();
            JButton other = Ui.button("Other assignment (" + (k + 1) + " of " + m.variants().size()
                    + (m.moreVariants() > 0 ? "+" : "") + ")", "The same nodes in other roles: the constraints would be made differently.",
                    e -> {
                        wb.chooseVariant(m, (m.chosenIndex() + 1) % m.variants().size());
                        wb.openPopover(m);
                    });
            other.setName("otherAssignment");
            body.addRow(other, Ui.GAP_S);
        }
        int sym = m.variant().symmetricCount();
        if (sym > 1) body.addRow(text(sym + " symmetric assignments lead to this same result.", Ui.MUTED), Ui.GAP_XS);

        if (m.state == Match.State.APPLIED && m.instance instanceof TemplateInstance) {
            TemplateInstance inst = (TemplateInstance) m.instance;
            addInstanceActions(body, inst, () -> wb.openPopover(m));
        } else if (m.state != Match.State.INVALID) {
            PrimaryButton apply = Ui.primaryButton("Apply this match", "Makes its " + m.task.plan.size()
                    + " constraints on these nodes and solves the layout once (Edit › Undo reverts it).", e -> {
                        String msg = wb.apply(java.util.Collections.singletonList(m), null);
                        wb.openPopover(m);
                        TemplatePanel.status(msg);
                    });
            apply.setName("applyMatch");
            body.addRow(apply, Ui.GAP_M);
            JButton sel = Ui.button(m.selected ? "Remove from selection" : "Select for applying",
                    "Collects matches to apply together with \"Apply selected\" on the Templates page.",
                    e -> wb.setSelected(m, !m.selected));
            sel.setName("toggleSelected");
            JButton show = Ui.button("Show constraints", "Marks the constraints the match would make (nothing is added).",
                    e -> wb.showPlannedConstraints(m));
            show.setName("showPlanned");
            body.addRow(sel, Ui.GAP_S);
            body.addRow(show, Ui.GAP_XS);
        }
        anchor = m;
        open(lp, body, c, null);
    }

    /** Opens the popover of an applied instance: its state and the actions on it. */
    static void showInstance(TemplateInstance inst) {
        hideAll();
        JLayeredPane lp = layered();
        if (lp == null) return;
        TemplateWorkbench wb = TemplateWorkbench.get();
        VerticalStack body = new VerticalStack();
        body.addRow(head(inst.label()));
        body.addRow(chip(inst.isOn() ? "Applied" : "Switched off", inst.isOn() ? MatchOverlay.APPLIED : MatchOverlay.OFF), Ui.GAP_XS);
        addInstanceActions(body, inst, () -> wb.openInstancePopover(inst));
        anchorInstance = inst;
        open(lp, body, inst.isOn() ? MatchOverlay.APPLIED : MatchOverlay.OFF, null);
    }

    private static JComponent head(String titleText) {
        JLabel title = new JLabel(titleText);
        title.setFont(Ui.bold());
        title.setForeground(Ui.TEXT);
        JButton close = new JButton("×");
        close.setToolTipText("Close (Esc)");
        close.setMargin(new Insets(0, 4, 0, 4));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.getAccessibleContext().setAccessibleName("Close");
        close.addActionListener(e -> TemplateWorkbench.get().closePopover());
        JPanel head = new JPanel(new BorderLayout(Ui.GAP_S, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(close, BorderLayout.EAST);
        return head;
    }

    /** State and actions of an applied instance; {@code reopen} shows the popover again after a change. */
    private static void addInstanceActions(VerticalStack body, TemplateInstance inst, Runnable reopen) {
        TemplateWorkbench wb = TemplateWorkbench.get();
        body.addRow(text("Instance " + TemplateWorkbench.esc(inst.label()) + ": " + TemplateWorkbench.esc(inst.status(ConstraintStore.constraints).text()), Ui.TEXT), Ui.GAP_S);
        JButton onOff = Ui.button(inst.isOn() ? "Switch instance off" : "Switch instance on",
                "Switches all its constraints off or on together; their own settings are kept.", e -> {
                    wb.setInstanceOn(inst, "", !inst.isOn());
                    reopen.run();
                });
        onOff.setName("instanceOnOff");
        JButton remove = Ui.button("Remove instance", "Removes exactly the constraints this instance made (Edit › Undo brings them back).", e -> {
            wb.closePopover();
            wb.removeInstance(inst);
        });
        remove.setName("instanceRemove");
        body.addRow(onOff, Ui.GAP_S);
        body.addRow(remove, Ui.GAP_XS);
        JButton show = Ui.button("Show constraints", "Marks the instance's constraints on the graph.", e -> {
            gui.DisplayConstraints.clearMarkings();
            gui.DisplayConstraints.drawConstraints(inst.present(ConstraintStore.constraints));
        });
        body.addRow(show, Ui.GAP_XS);
    }

    /** Lists overlapping matches to choose one; moving through the list previews each on the graph. */
    static void showChooser(List<Match> ms, Point viewPoint) {
        hideAll();
        JLayeredPane lp = layered();
        View v = TemplateWorkbench.activeView();
        if (lp == null || v == null) return;
        TemplateWorkbench wb = TemplateWorkbench.get();
        VerticalStack body = new VerticalStack();
        JLabel t = new JLabel(ms.size() + " matches here: choose one");
        t.setFont(Ui.bold());
        body.addRow(t);
        DefaultListModel<Match> model = new DefaultListModel<>();
        for (Match m : ms) model.addElement(m);
        JList<Match> list = new JList<>(model);
        list.setName("matchChooser");
        list.setVisibleRowCount(Math.min(6, ms.size()));
        list.setCellRenderer((l, m, i, sel, foc) -> {
            JLabel r = new JLabel(MatchOverlay.symbolOf(m) + "  " + m.title() + " · " + MatchOverlay.stateText(m));
            r.setOpaque(true);
            r.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            r.setBackground(sel ? Ui.ACCENT_SOFT : Color.WHITE);
            r.setForeground(MatchOverlay.colorOf(m));
            return r;
        });
        list.addListSelectionListener(e -> {
            Match m = list.getSelectedValue();
            if (m != null) {
                wb.hover = m;
                MatchOverlay.repaintAll();
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && i != list.getSelectedIndex()) list.setSelectedIndex(i);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                Match m = list.getSelectedValue();
                if (m != null) wb.openPopover(m);
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Match m = list.getSelectedValue();
                if (m != null) wb.openPopover(m);
            }
        });
        body.addRow(new gui.components.NestedScrollPane(list), Ui.GAP_S);
        body.addRow(text("Move through the list to preview; click or press Enter to choose.", Ui.MUTED), Ui.GAP_XS);
        chooserPoint = SwingUtilities.convertPoint(v.getViewComponent(), viewPoint, lp);
        open(lp, body, Ui.ACCENT, chooserPoint);
        list.setSelectedIndex(0);
        list.requestFocusInWindow();
    }

    private static void open(JLayeredPane lp, JComponent body, Color accent, Point at) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new PopoverBorder(accent));
        body.setOpaque(false);
        JScrollPane sp = new gui.components.NestedScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        p.add(sp, BorderLayout.CENTER);
        p.setName("templatePopover");
        p.addMouseListener(new MouseAdapter() { });   // clicks stay in the popover
        panel = p;
        lp.add(p, JLayeredPane.POPUP_LAYER);
        place(lp, at);
        lp.revalidate();
        lp.repaint();
        if (follow == null) {
            follow = new Timer(120, e -> reposition());
            follow.setRepeats(true);
        }
        follow.restart();
        // The focus stays where it is: the graph keeps its keys (Delete, arrows), and so does a list being stepped through.
    }

    /** Keeps the popover next to its match (zoom, pan, drag); hides it if the view went away. */
    static void reposition() {
        if (panel == null) return;
        JLayeredPane lp = layered();
        if (lp == null || panel.getParent() != lp) {
            TemplateWorkbench.get().closePopover();
            return;
        }
        place(lp, chooserPoint);
    }

    private static void place(JLayeredPane lp, Point at) {
        View v = TemplateWorkbench.activeView();
        if (v == null || panel == null) return;
        JComponent comp = v.getViewComponent();
        Rectangle vis = SwingUtilities.convertRectangle(comp, comp.getVisibleRect(), lp);
        Dimension want = panel.getPreferredSize();
        if (vis.width < WIDTH + 16 || vis.height < Math.min(want.height, 360) + 16) {
            // the view is smaller than the popover: use the main window instead
            vis = new Rectangle(8, 8, lp.getWidth() - 16, lp.getHeight() - 16);
        }
        Dimension d = want;
        d.width = WIDTH;
        d.height = Math.min(d.height, Math.max(120, vis.height - 16));
        Rectangle target;
        if (anchor != null || anchorInstance != null) {
            Rectangle2D b = anchor != null ? MatchOverlay.bounds(anchor, lp) : MatchOverlay.bounds(anchorInstance, lp);
            if (b == null) return;
            target = b.getBounds();
        } else {
            target = new Rectangle(at == null ? vis.x + 20 : at.x, at == null ? vis.y + 20 : at.y, 1, 1);
        }
        if (vis.equals(placedView) && target.equals(placedTarget)) return;
        placedView = vis;
        placedTarget = target;
        int gap = 12;
        // Candidates beside the match (right, left, below, above), each kept inside the view; the one covering the
        // least of the match and of other nodes wins (in this order on ties).
        int[][] cand = {
                {target.x + target.width + gap, target.y},
                {target.x - gap - d.width, target.y},
                {target.x, target.y + target.height + gap},
                {target.x, target.y - gap - d.height}};
        List<Rectangle2D> boxes = nodeBoxes(lp);
        int x = cand[0][0], y = cand[0][1];
        double best = Double.POSITIVE_INFINITY;
        for (int[] c : cand) {
            int cx = Math.max(vis.x + 4, Math.min(c[0], vis.x + vis.width - d.width - 4));
            int cy = Math.max(vis.y + 4, Math.min(c[1], vis.y + vis.height - d.height - 4));
            Rectangle r = new Rectangle(cx, cy, d.width, d.height);
            double score = 0;
            Rectangle hit = r.intersection(target);
            if (!hit.isEmpty()) score += 20.0 * hit.width * hit.height;   // covering the match itself is worst
            for (Rectangle2D b : boxes) {
                Rectangle2D h2 = r.createIntersection(b);
                if (h2.getWidth() > 0 && h2.getHeight() > 0) score += h2.getWidth() * h2.getHeight();
            }
            score += 0.02 * (Math.abs(cx - c[0]) + Math.abs(cy - c[1]));   // moved by clamping: a little worse
            if (score < best - 1e-9) {
                best = score;
                x = cx;
                y = cy;
            }
        }
        Rectangle nb = new Rectangle(x, y, d.width, d.height);
        if (!nb.equals(panel.getBounds())) {
            panel.setBounds(nb);
            panel.revalidate();
            lp.repaint();
        }
    }

    /** Screen boxes of the active graph's nodes (for placing the popover; none for very large graphs). */
    private static List<Rectangle2D> nodeBoxes(JComponent target) {
        List<Rectangle2D> l = new java.util.ArrayList<>();
        org.graffiti.graph.Graph g = TemplateWorkbench.activeGraph();
        MatchOverlay.Geo geo = MatchOverlay.geo(target);
        if (g == null || geo == null || g.getNumberOfNodes() > 3000) return l;
        for (org.graffiti.graph.Node n : g.getNodes()) l.add(geo.box(n));
        return l;
    }

    private static JLabel text(String html, Color c) {
        JLabel l = new JLabel(Ui.wrapped(html, WIDTH - 30));
        l.setFont(Ui.small());
        l.setForeground(c);
        return l;
    }

    private static JComponent chip(String text, Color c) {
        JLabel l = new JLabel(text) {
            private static final long serialVersionUID = 1L;
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(MatchOverlay.alpha(c, 30));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(c);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(Ui.small().deriveFont(Font.BOLD));
        l.setForeground(c);
        l.setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 8));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(l);
        return row;
    }

    /** Rounded card with a coloured edge and a soft shadow. */
    private static final class PopoverBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final Color accent;

        PopoverBorder(Color accent) {
            this.accent = accent;
        }

        @Override public Insets getBorderInsets(Component c) {
            return new Insets(12, 12, 12, 12);
        }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(new Color(0, 0, 0, 30));
            g2.fill(new RoundRectangle2D.Double(x + 2, y + 3, w - 3, h - 3, 14, 14));
            g2.setColor(Color.WHITE);
            g2.fill(new RoundRectangle2D.Double(x, y, w - 3, h - 3, 14, 14));
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(1.4f));
            g2.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 4, h - 4, 14, 14));
            g2.fillRoundRect(x + 1, y + 1, 5, h - 6, 5, 5);
            g2.dispose();
        }
    }
}
