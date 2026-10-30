package gui.template;

import constraints.Constraint;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import gui.ConstraintOverlay;
import gui.components.Ui;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.TemplateDef;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pattern graph of the template editor with the tools select/move, add node and connect (keys V, N and C); the mouse
 * wheel zooms and the right mouse button (or Alt + drag) pans the view without changing stored positions. With "Keep
 * constraints while dragging" every drag step is solved with the template's own constraints, and a ghost shows where a
 * held node was pulled to and which constraints hold it. The selected constraint is drawn over the others with its
 * elements marked by role.
 *
 * @author Niklas Gröne
 */
final class PatternCanvas extends JComponent {

    private static final long serialVersionUID = 1L;

    enum Tool { SELECT, ADD_NODE, CONNECT }

    private final EditorModel model;
    private Tool tool = Tool.SELECT;
    /** Told when the tool changes (from the keys, or back to select), so the toolbar follows. */
    Runnable onToolChange = () -> { };
    /** A look inside a component: select, zoom and pan only. */
    boolean readOnly;
    boolean solveWhileDragging = true;
    boolean showAllConstraints = true;
    /** Local → screen: scale and translation (view only). */
    private double scale = 1, tx = 40, ty = 40;
    private boolean fitted;
    /** The user zoomed or panned: resizing the window no longer fits the pattern again. */
    private boolean viewChosen;

    // interaction state
    private Point press, last;
    private boolean panning, dragging, banding;
    private String pressNode;
    /** A press on a selected node of several: without a drag, the click selects that node alone. */
    private boolean collapseOnClick;
    /** The pointer left the start node while connecting, so coming back means a self-loop. */
    private boolean leftStartNode;
    private Map<String, double[]> dragStart = new HashMap<>();
    private Point2D ghost;
    private String heldText;
    private String hoverNode;
    private Point connectTo;
    private final Timer dragSolve;
    private Point pendingDrag;
    /** Hides the ghost of a held node a moment after the drag; stopped when the next gesture starts. */
    private Timer ghostTimer;

    PatternCanvas(EditorModel model) {
        this.model = model;
        setFocusable(true);
        setBackground(Color.WHITE);
        setOpaque(true);
        getAccessibleContext().setAccessibleName("Pattern graph");
        setToolTipText("");
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        dragSolve = new Timer(25, e -> dragStep());
        dragSolve.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { if (!viewChosen) fit(); }
        });
        bindKeys();
    }

    Tool tool() {
        return tool;
    }

    void setTool(Tool t) {
        if (readOnly && t != Tool.SELECT) return;
        tool = t;
        connectTo = null;
        pressNode = null;
        setCursor(t == Tool.ADD_NODE ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        onToolChange.run();
        repaint();
    }

    private void bindKeys() {
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete");
        am.put("delete", action(this::deleteSelection));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
        am.put("escape", action(() -> {
            if (tool != Tool.SELECT) setTool(Tool.SELECT);
            else if (!model.selNodes.isEmpty() || !model.selEdges.isEmpty()) {
                model.selNodes.clear();
                model.selEdges.clear();
            } else {
                model.selConstraint = null;
            }
            model.fire();
        }));
        for (Object[] k : new Object[][]{{KeyEvent.VK_V, Tool.SELECT}, {KeyEvent.VK_N, Tool.ADD_NODE}, {KeyEvent.VK_C, Tool.CONNECT}}) {
            im.put(KeyStroke.getKeyStroke((Integer) k[0], 0), "tool" + k[0]);
            am.put("tool" + k[0], action(() -> setTool((Tool) k[1])));
        }
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), "selectAll");
        am.put("selectAll", action(() -> {
            model.selNodes.addAll(model.def().nodeIds());
            model.fire();
        }));
        for (int[] k : new int[][]{{KeyEvent.VK_LEFT, -1, 0}, {KeyEvent.VK_RIGHT, 1, 0}, {KeyEvent.VK_UP, 0, -1}, {KeyEvent.VK_DOWN, 0, 1}}) {
            im.put(KeyStroke.getKeyStroke(k[0], 0), "nudge" + k[0]);
            im.put(KeyStroke.getKeyStroke(k[0], KeyEvent.SHIFT_DOWN_MASK), "nudge10" + k[0]);
            am.put("nudge" + k[0], action(() -> nudge(k[1], k[2])));
            am.put("nudge10" + k[0], action(() -> nudge(10 * k[1], 10 * k[2])));
        }
    }

    static Action action(Runnable r) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    private void nudge(int dx, int dy) {
        if (readOnly || model.selNodes.isEmpty()) return;
        model.changeMerging("nudge", "Move nodes", () -> {
            for (String id : model.selNodes) {
                PatternNode n = model.def().node(id);
                if (n != null) { n.x += dx; n.y += dy; }
            }
        });
        afterMove();
    }

    void deleteSelection() {
        if (readOnly || (model.selNodes.isEmpty() && model.selEdges.isEmpty())) return;
        if (!TemplateEditor.confirmPortDeletion(this, model, model.selNodes)) return;
        Set<String> ns = new LinkedHashSet<>(model.selNodes), es = new LinkedHashSet<>(model.selEdges);
        model.change("Delete", () -> model.deleteElements(ns, es));
    }

    // ================================================================== view transform

    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;
                @Override public javax.accessibility.AccessibleRole getAccessibleRole() { return javax.accessibility.AccessibleRole.PANEL; }
            };
        }
        return accessibleContext;
    }

    AffineTransform transform() {
        AffineTransform t = new AffineTransform();
        t.translate(tx, ty);
        t.scale(scale, scale);
        return t;
    }

    Point2D toLocal(Point p) {
        try {
            return transform().inverseTransform(new Point2D.Double(p.x, p.y), null);
        } catch (NoninvertibleTransformException e) {
            return new Point2D.Double(p.x, p.y);
        }
    }

    /** Fits the pattern into the canvas (changes only the view). */
    void fit() {
        if (getWidth() < 20) return;
        fitted = true;   // an empty pattern too: its first node appears where it was clicked, the view does not jump
        viewChosen = false;
        double[] b = model.def().bounds();
        if (b == null) return;
        AffineTransform t = PatternPainter.fit(b, new Rectangle2D.Double(0, 0, getWidth(), getHeight()), 60, 1.6);
        scale = t.getScaleX();
        tx = t.getTranslateX();
        ty = t.getTranslateY();
        repaint();
    }

    void zoom(double factor) {
        viewChosen = true;
        Point2D c = toLocal(new Point(getWidth() / 2, getHeight() / 2));
        scale = Math.max(0.1, Math.min(8, scale * factor));
        tx = getWidth() / 2.0 - c.getX() * scale;
        ty = getHeight() / 2.0 - c.getY() * scale;
        repaint();
    }

    // ================================================================== painting

    @Override
    protected void paintComponent(Graphics g0) {
        if (!fitted && getWidth() > 20) fit();
        Graphics2D g = Ui.smooth(g0);
        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            paintGrid(g);
            TemplateDef t = model.def();
            AffineTransform at = transform();
            // constraints: all of them light, the selected one on top
            List<Constraint> all = new ArrayList<>(model.constraintObjects());
            Constraint selected = model.selConstraint == null ? null : model.constraint(model.selConstraint);
            if (showAllConstraints && !all.isEmpty()) {
                Graphics2D gc = (Graphics2D) g.create();
                gc.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, selected == null ? 0.55f : 0.22f));
                List<Constraint> rest = new ArrayList<>(all);
                rest.remove(selected);
                ConstraintOverlay.paintConstraints(gc, rest, at, new Point());
                gc.dispose();
            }
            PatternPainter.Marks marks = new PatternPainter.Marks();
            marks.selectedNodes = model.selNodes;
            marks.selectedEdges = model.selEdges;
            marks.hoverNode = hoverNode;
            marks.edgeRoles = scale > 0.6;
            Map<String, String> roleLabels = new HashMap<>();
            if (model.selConstraint != null) highlight(model.def().constraint(model.selConstraint), marks, roleLabels);
            PatternPainter.paintPattern(g, t, at, marks);
            if (selected != null) ConstraintOverlay.paintConstraints(g, Collections.singletonList(selected), at, new Point());
            paintRoleLabels(g, at, roleLabels);
            paintConditionsHints(g, at);
            paintInteraction(g, at);
            if (t.nodes.isEmpty()) {
                g.setColor(Ui.MUTED);
                g.setFont(Ui.font());
                String s = "Empty pattern: choose \"Add node\" and click here.";
                g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
            }
            g.setFont(Ui.small());
            g.setColor(Ui.MUTED);
            g.drawString(hint(), 8, getHeight() - 8);
            if (isFocusOwner()) {
                g.setColor(Ui.FOCUS);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        } finally {
            g.dispose();
        }
    }

    /** What the mouse does right now. */
    private String hint() {
        if (readOnly) return "Scroll: zoom · Alt + drag: pan";
        switch (tool) {
            case ADD_NODE: return "Click: add a node · Esc: back to select";
            case CONNECT: return "Drag from one node to another: add an edge · Esc: back to select";
            default: return "Drag: move or select · Delete: remove · Esc: deselect · Scroll: zoom · Alt + drag: pan";
        }
    }

    private void paintGrid(Graphics2D g) {
        double step = 20 * scale;
        if (step < 6) return;
        g.setColor(new Color(0xEE, 0xF0, 0xF3));
        double ox = tx % step, oy = ty % step;
        for (double x = ox; x < getWidth(); x += step) for (double y = oy; y < getHeight(); y += step) g.fillRect((int) x, (int) y, 1, 1);
    }

    /** Role colours of the selected constraint's elements and their labels. */
    private void highlight(ConstraintSpec s, PatternPainter.Marks marks, Map<String, String> labels) {
        if (s == null) return;
        ConstraintSpecs.Adapter a = ConstraintSpecs.adapter(s.kind());
        if (a == null) return;
        Color[] palette = {new Color(0x25, 0x63, 0xEB), new Color(0xD9, 0x77, 0x06), new Color(0x0D, 0x94, 0x88), new Color(0x7E, 0x22, 0xCE)};
        int ri = 0;
        for (ConstraintSpecs.RoleInfo r : a.roles) {
            List<String> ids = s.role(r.name);
            Color c = palette[ri++ % palette.length];
            int k = 0;
            for (String id : ids) {
                k++;
                if (id == null) continue;
                if (r.edges) {
                    marks.edgeHighlights.put(id, c);
                    continue;
                }
                marks.nodeHighlights.put(id, c);
                String text = a.roles.size() > 1 ? shortRole(r) : "";
                if (r.ordered && ids.size() > 1) text = (text.isEmpty() ? "" : text + " ") + k;
                if (!text.isEmpty()) labels.put(id, (labels.containsKey(id) ? labels.get(id) + ", " : "") + text);
            }
        }
    }

    private static String shortRole(ConstraintSpecs.RoleInfo r) {
        switch (r.name) {
            case "first": return "1st";
            case "second": return "2nd";
            case "before": return "before";
            case "after": return "after";
            case "hub": return "centre";
            case "ring": return "ring";
            default: return r.name;
        }
    }

    private void paintRoleLabels(Graphics2D g, AffineTransform at, Map<String, String> labels) {
        g.setFont(Ui.small().deriveFont(Font.BOLD));
        FontMetrics fm = g.getFontMetrics();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            PatternNode n = model.def().node(e.getKey());
            if (n == null) continue;
            Rectangle2D b = PatternPainter.nodeShape(n, at).getBounds2D();
            int w = fm.stringWidth(e.getValue()) + 8, h = fm.getHeight();
            int x = (int) (b.getMinX() - w + 6), y = (int) (b.getMaxY() - 4);
            g.setColor(Ui.TEXT);
            g.fillRoundRect(x, y, w, h, h, h);
            g.setColor(Color.WHITE);
            g.drawString(e.getValue(), x + 4, y + fm.getAscent());
        }
    }

    /** A small "any" / "=" mark shows whether a node has semantic conditions. */
    private void paintConditionsHints(Graphics2D g, AffineTransform at) {
        if (scale < 0.5) return;
        g.setFont(Ui.small());
        for (PatternNode n : model.def().nodes) {
            int strict = 0;
            for (Condition c : n.conditions) if (c.op != Condition.Op.ANY) strict++;
            Rectangle2D b = PatternPainter.nodeShape(n, at).getBounds2D();
            g.setColor(strict == 0 ? Ui.DISABLED : Ui.MUTED);
            String s = strict == 0 ? "any" : strict == 1 ? PatternPainter.prettyRole(n.role).isEmpty() ? "1 condition" : "= " + PatternPainter.prettyRole(n.role)
                    : strict + " conditions";
            if (strict == 1 && n.condition(Condition.SBGN_ROLE) != null && n.condition(Condition.SBGN_ROLE).op == Condition.Op.IN_LIST) s = "role ∈ list";
            g.drawString(s, (float) b.getMinX(), (float) (b.getMaxY() + g.getFontMetrics().getAscent() + 1));
        }
    }

    private void paintInteraction(Graphics2D g, AffineTransform at) {
        if (banding && press != null && last != null) {
            Rectangle r = rect(press, last);
            g.setColor(MatchOverlay.alpha(Ui.ACCENT, 30));
            g.fill(r);
            g.setColor(Ui.ACCENT);
            g.draw(r);
        }
        if (tool == Tool.CONNECT && pressNode != null && connectTo != null) {
            PatternNode a = model.def().node(pressNode);
            if (a != null) {
                Point2D c = at.transform(new Point2D.Double(a.x, a.y), null);
                g.setColor(Ui.ACCENT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{6, 4}, 0));
                g.draw(new Line2D.Double(c, connectTo));
            }
        }
        if (ghost != null && pressNode != null) {
            PatternNode n = model.def().node(pressNode);
            if (n != null) {
                PatternNode gn = n.copy();
                gn.x = ghost.getX();
                gn.y = ghost.getY();
                Shape gs = PatternPainter.nodeShape(gn, at);
                Point2D real = at.transform(new Point2D.Double(n.x, n.y), null);
                Point2D wanted = at.transform(ghost, null);
                g.setColor(MatchOverlay.alpha(Ui.WARNING, 60));
                g.fill(gs);
                g.setColor(Ui.WARNING);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{4, 3}, 0));
                g.draw(gs);
                g.draw(new Line2D.Double(real, wanted));
                if (heldText != null) {
                    g.setFont(Ui.small().deriveFont(Font.BOLD));
                    FontMetrics fm = g.getFontMetrics();
                    int w = fm.stringWidth(heldText) + 10;
                    int x = (int) Math.min(getWidth() - w - 4, wanted.getX() + 12), y = (int) wanted.getY() - 22;
                    g.setColor(new Color(0xFF, 0xF4, 0xE5));
                    g.fillRoundRect(x, y, w, fm.getHeight() + 4, 8, 8);
                    g.setColor(Ui.WARNING);
                    g.drawRoundRect(x, y, w, fm.getHeight() + 4, 8, 8);
                    g.drawString(heldText, x + 5, y + 2 + fm.getAscent());
                }
            }
        }
    }

    private static Rectangle rect(Point a, Point b) {
        return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    // ================================================================== hit tests

    String nodeAt(Point p) {
        AffineTransform at = transform();
        List<PatternNode> ns = new ArrayList<>(model.def().nodes);
        Collections.reverse(ns);
        for (PatternNode n : ns) {
            Rectangle2D b = PatternPainter.nodeShape(n, at).getBounds2D();
            if (new Rectangle2D.Double(b.getX() - 3, b.getY() - 3, b.getWidth() + 6, b.getHeight() + 6).contains(p)) return n.id;
        }
        return null;
    }

    String edgeAt(Point p) {
        AffineTransform at = transform();
        for (PatternEdge e : model.def().edges) {
            PatternNode a = model.def().node(e.source), b = model.def().node(e.target);
            if (a == null || b == null) continue;
            Point2D pa = at.transform(new Point2D.Double(a.x, a.y), null), pb = at.transform(new Point2D.Double(b.x, b.y), null);
            if (a == b) {
                Rectangle2D r = PatternPainter.nodeShape(a, at).getBounds2D();
                if (p.distance(r.getMaxX(), r.getMinY()) < 14) return e.id;
            } else if (new Line2D.Double(pa, pb).ptSegDist(p) < 5) return e.id;
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        String id = nodeAt(e.getPoint());
        if (id == null) return null;
        PatternNode n = model.def().node(id);
        StringBuilder sb = new StringBuilder("<b>" + Ui.esc(n.displayName()) + "</b> (" + n.id + ")");
        for (Condition c : n.conditions) sb.append("<br>").append(Ui.esc(c.describe()));
        List<ConstraintSpec> cs = model.constraintsOf(id);
        if (!cs.isEmpty()) {
            sb.append("<br><br>In ").append(cs.size()).append(cs.size() == 1 ? " constraint:" : " constraints:");
            for (ConstraintSpec s : cs) sb.append("<br>• ").append(Ui.esc(TemplateEditor.title(s)));
        }
        return Ui.tooltip(sb.toString());
    }

    // ================================================================== mouse

    private final class Mouse extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            if (ghostTimer != null) ghostTimer.stop();
            requestFocusInWindow();
            press = e.getPoint();
            last = e.getPoint();
            dragging = false;
            banding = false;
            ghost = null;
            heldText = null;
            panning = SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) || e.isAltDown();
            if (panning) {
                viewChosen = true;
                return;
            }
            pressNode = nodeAt(press);
            collapseOnClick = false;
            leftStartNode = false;
            if (tool == Tool.ADD_NODE && pressNode == null) {
                Point2D l = toLocal(press);
                model.change("Add node", () -> {
                    TemplateDef t = model.def();
                    PatternNode n = new PatternNode(t.nextNodeId(), Math.round(l.getX()), Math.round(l.getY()));
                    n.label = "";
                    n.conditions.add(Condition.any(Condition.SBGN_ROLE));
                    t.nodes.add(n);
                    model.selNodes.clear();
                    model.selEdges.clear();
                    model.selNodes.add(n.id);
                    model.constraintFirst = false;
                });
                return;
            }
            if (tool == Tool.CONNECT) return;
            boolean toggle = e.isShiftDown() || e.isMetaDown() || e.isControlDown();
            if (pressNode != null) {
                if (toggle) {
                    if (!model.selNodes.remove(pressNode)) model.selNodes.add(pressNode);
                } else if (!model.selNodes.contains(pressNode)) {
                    model.selNodes.clear();
                    model.selEdges.clear();
                    model.selNodes.add(pressNode);
                } else {
                    collapseOnClick = model.selNodes.size() > 1;
                }
                model.constraintFirst = false;
                model.fire();
                return;
            }
            String edge = edgeAt(press);
            if (edge != null) {
                if (!toggle) {
                    model.selNodes.clear();
                    model.selEdges.clear();
                }
                model.selEdges.add(edge);
                model.constraintFirst = false;
                model.fire();
                return;
            }
            if (!toggle) {
                // the first click on the background deselects the nodes, the next one the constraint as well
                if (model.selNodes.isEmpty() && model.selEdges.isEmpty()) model.selConstraint = null;
                model.selNodes.clear();
                model.selEdges.clear();
                model.fire();
            }
            banding = true;
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (press == null) return;
            Point p = e.getPoint();
            if (panning) {
                tx += p.x - last.x;
                ty += p.y - last.y;
                last = p;
                repaint();
                return;
            }
            last = p;
            if (tool == Tool.CONNECT) {
                connectTo = p;
                hoverNode = nodeAt(p);
                if (pressNode != null && !pressNode.equals(hoverNode)) leftStartNode = true;
                repaint();
                return;
            }
            if (banding) {
                repaint();
                return;
            }
            if (pressNode == null || tool != Tool.SELECT || readOnly || !model.selNodes.contains(pressNode)) return;
            if (!dragging && p.distance(press) < 3) return;
            if (!dragging) {
                dragging = true;
                model.beginStep("Move nodes");
                dragStart.clear();
                for (String id : model.selNodes) {
                    PatternNode n = model.def().node(id);
                    if (n != null) dragStart.put(id, new double[]{n.x, n.y});
                }
            }
            pendingDrag = p;
            if (!dragSolve.isRunning()) dragSolve.start();
        }

        @Override public void mouseReleased(MouseEvent e) {
            if (press == null) return;
            if (tool == Tool.CONNECT && pressNode != null) {
                connect(pressNode, nodeAt(e.getPoint()));
            } else if (banding) {
                Rectangle r = rect(press, e.getPoint());
                AffineTransform at = transform();
                for (PatternNode n : model.def().nodes) {
                    if (r.intersects(PatternPainter.nodeShape(n, at).getBounds2D())) model.selNodes.add(n.id);
                }
                model.fire();
            } else if (dragging) {
                dragSolve.stop();
                dragStep();
                model.endStep();
                afterMove();
            } else if (collapseOnClick && !panning) {
                model.selNodes.clear();
                model.selEdges.clear();
                model.selNodes.add(pressNode);
                model.fire();
            }
            press = null;
            pressNode = dragging ? pressNode : null;
            dragging = false;
            banding = false;
            panning = false;
            connectTo = null;
            if (ghost != null) {
                // keep the ghost visible a moment so the user sees what held the node
                if (ghostTimer != null) ghostTimer.stop();
                ghostTimer = new Timer(1200, ev -> {
                    ghost = null;
                    heldText = null;
                    pressNode = null;
                    repaint();
                });
                ghostTimer.setRepeats(false);
                ghostTimer.start();
            } else {
                pressNode = null;
            }
            repaint();
        }

        @Override public void mouseMoved(MouseEvent e) {
            String h = nodeAt(e.getPoint());
            if (h == null ? hoverNode != null : !h.equals(hoverNode)) {
                hoverNode = h;
                repaint();
            }
            setCursor(h != null && tool == Tool.CONNECT ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : tool == Tool.ADD_NODE ? Cursor.getPredefinedCursor(h != null ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR)
                    : h != null && !readOnly && tool == Tool.SELECT ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            viewChosen = true;
            Point2D c = toLocal(e.getPoint());
            double f = Math.pow(1.1, -e.getPreciseWheelRotation());
            scale = Math.max(0.1, Math.min(8, scale * f));
            tx = e.getX() - c.getX() * scale;
            ty = e.getY() - c.getY() * scale;
            repaint();
        }
    }

    /** Adds the edge the connect tool was dragged along, unless that was a mere click or the edge exists already. */
    private void connect(String from, String to) {
        if (to == null) return;
        if (to.equals(from) && !leftStartNode) {
            model.message = "Drag from one node to another to connect them.";
            model.fire();
            return;
        }
        for (PatternEdge x : model.def().edges) {
            if (from.equals(x.source) && to.equals(x.target)) {
                model.selNodes.clear();
                model.selEdges.clear();
                model.selEdges.add(x.id);
                model.message = "These nodes are connected in this direction already.";
                model.fire();
                return;
            }
        }
        model.change("Add edge", () -> {
            TemplateDef t = model.def();
            PatternEdge ed = new PatternEdge(t.nextEdgeId(), from, to, true);
            ed.conditions.add(Condition.any(Condition.SBGN_ROLE));
            t.edges.add(ed);
            model.selNodes.clear();
            model.selEdges.clear();
            model.selEdges.add(ed.id);
            model.constraintFirst = false;
        });
    }

    /** One drag step: move the selected nodes by the pointer's offset, then (optionally) solve. */
    private void dragStep() {
        if (pendingDrag == null || press == null || pressNode == null) return;
        Point2D a = toLocal(press), b = toLocal(pendingDrag);
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
        for (Map.Entry<String, double[]> en : dragStart.entrySet()) {
            PatternNode n = model.def().node(en.getKey());
            if (n == null) continue;
            n.x = en.getValue()[0] + dx;
            n.y = en.getValue()[1] + dy;
        }
        double[] start = dragStart.get(pressNode);
        Point2D wanted = start == null ? null : new Point2D.Double(start[0] + dx, start[1] + dy);
        if (solveWhileDragging && !model.constraintObjects().isEmpty()) {
            model.solve(false);
            PatternNode n = model.def().node(pressNode);
            if (n != null && wanted != null && Math.hypot(n.x - wanted.getX(), n.y - wanted.getY()) > 1.5) {
                ghost = wanted;
                heldText = "Held by: " + holders(pressNode);
            } else {
                ghost = null;
                heldText = null;
            }
        } else {
            model.syncPositions();
            ghost = null;
            heldText = null;
        }
        repaint();
    }

    /** The applied constraints on node {@code id}, by title. */
    private String holders(String id) {
        List<String> l = new ArrayList<>();
        for (ConstraintSpec s : model.constraintsOf(id)) {
            Constraint c = model.constraint(s.id);
            if (c != null && c.isActive()) l.add(TemplateEditor.title(s));
        }
        if (l.isEmpty()) return "the other nodes' constraints";
        String s = String.join(", ", l.subList(0, Math.min(3, l.size())));
        return l.size() > 3 ? s + " and " + (l.size() - 3) + " more" : s;
    }

    private void afterMove() {
        if (!solveWhileDragging) model.measure();
        model.fire();
    }
}
