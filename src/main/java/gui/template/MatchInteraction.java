package gui.template;

import gui.components.Ui;
import layout.template.apply.TemplateInstance;
import layout.template.search.Match;
import layout.template.search.MatchCheck;

import org.graffiti.plugin.view.View;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * Mouse and keyboard handling of the suggestion mode on the graph view: hovering a match highlights it and shows a short
 * card, a click (not a drag) opens its action popover or, where matches overlap, a chooser, a click on an applied
 * instance opens its actions, and Escape closes the popover or leaves the mode. The listeners only observe, so VANTED's
 * own tools still receive every event.
 *
 * @author Niklas Gröne
 */
final class MatchInteraction extends MouseAdapter {

    private static MatchInteraction instance;
    private static final int CLICK_SLOP = 4;
    private static final String ESC_KEY = "cblayout.templates.escape";
    private static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    static MatchInteraction get() {
        if (instance == null) instance = new MatchInteraction();
        return instance;
    }

    private JComponent attached;
    private JRootPane root;
    /** What Escape was bound to in the root pane's own key map before (put back on detach), or null. */
    private Object rootEscape;
    private Point pressed;
    private boolean dragging, extending;
    private final Timer hoverTimer;
    private JComponent card;
    private Point lastPoint;

    private MatchInteraction() {
        hoverTimer = new Timer(380, e -> showCard());
        hoverTimer.setRepeats(false);
    }

    /** Listens on the active view (moving from a previously attached one). */
    void attach() {
        View v = TemplateWorkbench.activeView();
        JComponent c = v == null ? null : v.getViewComponent();
        if (c == attached) return;
        detach();
        if (c == null) return;
        attached = c;
        c.addMouseListener(this);
        c.addMouseMotionListener(this);
        root = SwingUtilities.getRootPane(c);
        if (root != null) {
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            KeyStroke[] own = im.keys();   // this map's own bindings, not those of its parents
            if (own != null) for (KeyStroke k : own) if (ESCAPE.equals(k)) rootEscape = im.get(k);
            im.put(ESCAPE, ESC_KEY);
            root.getActionMap().put(ESC_KEY, new AbstractAction() {
                private static final long serialVersionUID = 1L;
                @Override public void actionPerformed(ActionEvent e) { escape(); }
            });
        }
    }

    void detach() {
        hideCard();
        if (attached != null) {
            attached.removeMouseListener(this);
            attached.removeMouseMotionListener(this);
        }
        if (root != null) {
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            if (rootEscape != null) im.put(ESCAPE, rootEscape);
            else im.remove(ESCAPE);
            root.getActionMap().remove(ESC_KEY);
        }
        attached = null;
        root = null;
        rootEscape = null;
    }

    void escape() {
        TemplateWorkbench wb = TemplateWorkbench.get();
        if (wb.focus != null || wb.chooser != null || MatchPopover.isOpen()) wb.closePopover();
        else wb.setSuggestionMode(false);
        wb.fire();
    }

    private boolean ours(MouseEvent e) {
        View v = TemplateWorkbench.activeView();
        return v != null && e.getComponent() == v.getViewComponent() && TemplateWorkbench.get().isSuggestionMode();
    }

    /**
     * The event's position on the view component. VANTED's view hands its listeners graph coordinates (it wraps every
     * listener and undoes the zoom), while the overlay, the popover and the click tolerance work in view coordinates.
     */
    static Point viewPoint(MouseEvent e, AffineTransform zoom) {
        if (zoom == null) return e.getPoint();
        Point2D p = zoom.transform(new Point2D.Double(e.getX(), e.getY()), null);
        return new Point((int) Math.round(p.getX()), (int) Math.round(p.getY()));
    }

    private static Point viewPoint(MouseEvent e) {
        View v = TemplateWorkbench.activeView();
        return viewPoint(e, v == null ? null : v.getZoom());
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (!ours(e)) return;
        TemplateWorkbench wb = TemplateWorkbench.get();
        lastPoint = viewPoint(e);
        if (wb.chooser != null) return;   // the chooser previews its own rows
        List<Match> hits = MatchOverlay.hits(lastPoint, (JComponent) e.getComponent());
        Match h = hits.isEmpty() ? null : hits.get(0);
        if (h != wb.hover) {
            wb.hover = h;
            hideCard();
            if (h != null && h != wb.focus) hoverTimer.restart();
            else hoverTimer.stop();
            MatchOverlay.repaintAll();
        }
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (!ours(e)) return;
        hoverTimer.stop();
        hideCard();
        TemplateWorkbench wb = TemplateWorkbench.get();
        if (wb.hover != null && wb.chooser == null) {
            wb.hover = null;
            MatchOverlay.repaintAll();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (!ours(e) || !SwingUtilities.isLeftMouseButton(e)) return;
        pressed = viewPoint(e);
        dragging = false;
        // Shift or Ctrl extend the node selection of the graph: that is no choice of a match
        extending = e.isShiftDown() || e.isControlDown() || e.isMetaDown();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (!ours(e) || pressed == null) return;
        if (viewPoint(e).distance(pressed) > CLICK_SLOP) {
            dragging = true;
            hideCard();
            MatchOverlay.repaintAll();   // the outlines follow the nodes being moved
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!ours(e) || pressed == null || !SwingUtilities.isLeftMouseButton(e)) return;
        Point at = viewPoint(e);
        boolean click = !dragging && at.distance(pressed) <= CLICK_SLOP;
        pressed = null;
        if (!click) {
            // a drag moved nodes: the outlines and the popover follow
            MatchOverlay.repaintAll();
            MatchPopover.reposition();
            return;
        }
        if (extending) return;
        hideCard();
        TemplateWorkbench wb = TemplateWorkbench.get();
        JComponent view = (JComponent) e.getComponent();
        List<Match> hits = MatchOverlay.hits(at, view);
        if (hits.size() == 1) wb.openPopover(hits.get(0));
        else if (hits.size() > 1) wb.openChooser(hits, at);
        else {
            TemplateInstance inst = MatchOverlay.instanceAt(at, view);
            if (inst != null) wb.openInstancePopover(inst);
            else wb.closePopover();
        }
    }

    private void showCard() {
        TemplateWorkbench wb = TemplateWorkbench.get();
        Match m = wb.hover;
        if (m == null || m == wb.focus || attached == null || lastPoint == null || wb.chooser != null) return;
        JLayeredPane lp = MatchPopover.layered();
        if (lp == null) return;
        hideCard();
        MatchCheck c = m.check;
        String html = "<html><b>" + TemplateWorkbench.esc(m.title()) + "</b> · " + MatchOverlay.stateText(m) + "<br>"
                + m.nodes().size() + " nodes · " + m.task.plan.size() + " constraints"
                + (m.variants().size() > 1 ? " · " + m.variants().size() + " assignments" : "") + "<br>"
                + "<font color='#5f6368'>" + TemplateWorkbench.esc(firstLine(m.variant().explanation)) + "</font><br>"
                + (c.status == MatchCheck.Status.NOT_CHECKED ? "<font color='#5f6368'>Not checked yet</font>"
                : TemplateWorkbench.esc(c.status.label())) + "<br><i>Click for actions.</i></html>";
        JLabel l = new JLabel(html);
        l.setFont(Ui.small());
        l.setOpaque(true);
        l.setBackground(new Color(255, 255, 255, 245));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(MatchOverlay.colorOf(m)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        Dimension d = l.getPreferredSize();
        d.width = Math.min(d.width, 320);
        Point p = SwingUtilities.convertPoint(attached, lastPoint, lp);
        Rectangle vis = SwingUtilities.convertRectangle(attached, attached.getVisibleRect(), lp);
        int x = Math.min(p.x + 14, vis.x + vis.width - d.width - 4), y = p.y + 18;
        if (y + d.height > vis.y + vis.height) y = p.y - d.height - 8;
        l.setBounds(Math.max(vis.x + 4, x), Math.max(vis.y + 4, y), d.width, d.height);
        lp.add(l, JLayeredPane.POPUP_LAYER);
        card = l;
        lp.repaint();
    }

    void hideCard() {
        hoverTimer.stop();
        if (card != null) {
            Container p = card.getParent();
            if (p != null) {
                p.remove(card);
                p.repaint();
            }
        }
        card = null;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 90 ? l.substring(0, 89) + "…" : l;
    }
}
