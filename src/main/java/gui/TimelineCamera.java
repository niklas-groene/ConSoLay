package gui;

import layout.mental_map.GraphBounds;

import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Graph;
import org.graffiti.plugin.view.View;
import org.graffiti.session.EditorSession;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * The "Keep graph in view" camera of the {@link MentalMapPanel}: eases zoom and scroll position of the active view so
 * that a box of the graph stays visible while the timeline plays, steps, scrubs, restores or runs a layout, which
 * VANTED does not do for nodes moved outside of transactions. It acts only when the box nears the border of the
 * viewport or no longer fits, zooms in only at rest and never beyond the user's zoom, and a manual zoom or pan while it
 * is busy switches it off; all methods run on the event dispatch thread.
 *
 * @author Niklas Gröne
 */
final class TimelineCamera {

    /** How quickly the camera reaches a new target: the smoothing time of the spring. */
    enum Pace {
        /** The graph jumped (a layout run, a restore): catch up quickly. */
        JUMP(0.14),
        /** Single steps, scrubbing by hand. */
        STEP(0.22),
        /** Playback: slower, so zoom changes are calm. */
        PLAY(0.35);

        final double seconds;

        Pace(double seconds) {
            this.seconds = seconds;
        }
    }

    /** Margin around the graph when the camera re-frames it (px). */
    private static final int MARGIN = 36;
    /**
     * The camera acts once the graph comes closer than this to the border of the viewport (px); it re-frames with the
     * larger {@link #MARGIN}, so the band in between keeps small changes from triggering it again.
     */
    private static final int SAFE_INSET = 6;
    /** A zoom-out fits the graph slightly smaller, so it can grow a little further before the next one. */
    private static final double ZOOM_OUT_HEADROOM = 0.94;
    /**
     * At rest, zoom back in once the graph covers less than this share of the viewport in both directions; while the
     * timeline runs only below {@link #TINY} (e.g. after the window grew), so the zoom does not pump.
     */
    private static final double TOO_SMALL = 0.5, TINY = 0.25;
    /** Switching the camera on frames a small graph up to this zoom at most. */
    private static final double MAX_AUTO_ZOOM = 1.0;
    private static final double MIN_ZOOM = 0.02, MAX_ZOOM = 40;
    /** One still frame for a whole playback is used if it shows the largest state at least this large (see {@link #stillFrame}). */
    private static final double STILL_FRAME_RATIO = 0.6;
    /** The canvas grows in steps of this size, so a moving graph does not resize it on every frame (px). */
    private static final int CANVAS_SLACK = 100;
    /** Timer period of a camera move (ms). */
    private static final int TICK_MS = 15;
    /** Longest time step of the spring: after a stall of the event thread the camera slows down instead of jumping (s). */
    private static final double MAX_DT = 0.05;

    /** Zoom and centre (graph coordinates) of the camera. */
    private static final class Frame {
        final double scale, cx, cy;

        Frame(double scale, double cx, double cy) {
            this.scale = scale;
            this.cx = cx;
            this.cy = cy;
        }

        Frame shifted(double dx, double dy) {
            return new Frame(scale, cx + dx, cy + dy);
        }
    }

    private final Runnable onManualChange;
    private final BooleanSupplier timelineRunning;
    private boolean enabled = true;

    /** The view the camera works on and its viewport; looked up again on every call (views can be switched or detached). */
    private View view;
    private JViewport viewport;
    private final ChangeListener viewportListener = e -> onViewportChanged();

    /** Current camera (in the middle of a move) and where it is heading. */
    private double scale = 1, cx, cy;
    private Frame target = new Frame(1, 0, 0);
    /** The box that has to stay visible, as last requested; a move keeps it inside while it fits. */
    private Rectangle2D content;
    /** The zoom the user chose: the camera zooms back in at rest, but never beyond it. */
    private double preferredScale = 1;
    /**
     * Per view the camera left: its {@link #preferredScale} and the zoom transform the camera had set, so that coming
     * back to a view whose zoom nobody changed since restores the user's zoom rather than adopting the camera's own.
     */
    private final Map<View, Object[]> leftViews = new WeakHashMap<>();
    /** Spring velocities of log(scale) and of the centre. */
    private double vScale, vx, vy;
    private double smoothTime = Pace.STEP.seconds;
    private Timer animator;
    private long lastTick;

    /** The zoom transform and scroll position the camera set last, to recognise changes by others. */
    private AffineTransform lastZoom;
    private Point lastPos;
    /** True while the camera itself changes zoom, canvas or scroll position. */
    private boolean writing;

    /**
     * @param onManualChange  called when the user zoomed or panned while the camera was busy; the camera is still
     *                        enabled then, the owner switches it off
     * @param timelineRunning whether the timeline plays or steps, i.e. whether the camera is busy even between moves
     */
    TimelineCamera(Runnable onManualChange, BooleanSupplier timelineRunning) {
        this.onManualChange = onManualChange;
        this.timelineRunning = timelineRunning;
    }

    /** Switches following on or off; off stops a running move where it is. */
    void setEnabled(boolean on) {
        enabled = on;
        if (!on) stopMove();
    }

    /** Forgets the view: another graph became active or the panel is disposed. */
    void release() {
        stopMove();
        if (view != null) leftViews.put(view, new Object[]{lastZoom, preferredScale});
        if (viewport != null) viewport.removeChangeListener(viewportListener);
        view = null;
        viewport = null;
        content = null;
    }

    // ------------------------------------------------------------------ following

    /**
     * Keeps {@code box} (graph coordinates) visible in the active view of {@code g}, see the class comment.
     * {@code atRest} allows zooming back in to a graph that became small; it is false while the timeline plays or the
     * slider is dragged, so the zoom does not pump. Does nothing while the camera is off.
     */
    void keepInView(Graph g, Rectangle2D box, Pace pace, boolean atRest) {
        keepInView(g, box, pace, atRest, false);
    }

    private void keepInView(Graph g, Rectangle2D box, Pace pace, boolean atRest, boolean fitSmall) {
        if (!enabled || box == null || !bind(g, true)) return;
        content = (Rectangle2D) box.clone();
        Frame next = plan(box, atRest, fitSmall);
        if (next == null) {
            if (atRest && !moving()) shrinkCanvas(box);
            return;
        }
        target = next;
        smoothTime = pace.seconds;
        if (animator == null) animator = new Timer(TICK_MS, e -> tick());
        if (!animator.isRunning()) {
            lastTick = System.nanoTime();
            animator.start();
        }
    }

    /**
     * Frames {@code box} when the camera is switched on: like {@link #keepInView} at rest, but a graph that does not
     * fill the view is always zoomed in to fit, up to {@link #MAX_AUTO_ZOOM} even beyond the zoom the user had.
     */
    void frame(Graph g, Rectangle2D box) {
        if (!enabled || box == null || !bind(g, true)) return;
        preferredScale = Math.max(preferredScale, Math.min(fit(box, viewport.getExtentSize(), MARGIN), MAX_AUTO_ZOOM));
        keepInView(g, box, Pace.STEP, true, true);
    }

    /**
     * Before the timeline starts to run: takes over the current camera of the active view of {@code g}, including a
     * change by others since the last move. Such a change is older than the run, so it does not count as an
     * interruption of it; once the run is going, one does.
     */
    void takeOver(Graph g) {
        bind(g, false);
    }

    /**
     * The union of {@code boxes} if one still frame around all of them is good enough for a whole playback: it must
     * show the largest single state at least {@link #STILL_FRAME_RATIO} as large as a frame of its own would. A still
     * camera keeps the orientation best; otherwise {@code null}, and the camera follows the states one by one.
     */
    Rectangle2D stillFrame(Graph g, List<Rectangle2D> boxes) {
        Rectangle2D union = GraphBounds.union(boxes);
        JViewport vp = viewportOf(activeView(g));
        if (union == null || vp == null) return union;
        Dimension ext = vp.getExtentSize();
        double smallestFit = Double.POSITIVE_INFINITY;
        for (Rectangle2D box : boxes) {
            if (box != null) smallestFit = Math.min(smallestFit, fit(box, ext, MARGIN));
        }
        return fit(union, ext, MARGIN) >= STILL_FRAME_RATIO * smallestFit ? union : null;
    }

    /** True if {@code box} lies completely inside the visible part of the active view of {@code g}. */
    boolean shows(Graph g, Rectangle2D box) {
        View v = activeView(g);
        JViewport vp = viewportOf(v);
        if (vp == null || box == null) return false;
        double s = scaleOf(v);
        Rectangle r = vp.getViewRect();
        return box.getMinX() * s >= r.x - 1 && box.getMaxX() * s <= r.x + r.width + 1
                && box.getMinY() * s >= r.y - 1 && box.getMaxY() * s <= r.y + r.height + 1;
    }

    /**
     * Grows the canvas of the active view of {@code g} so that {@code box} can be scrolled to, also while the camera
     * is off: nodes moved outside a transaction lie beyond VANTED's canvas otherwise.
     */
    void ensureScrollable(Graph g, Rectangle2D box) {
        View v = activeView(g);
        if (v == null || box == null) return;
        double s = scaleOf(v);
        growCanvas(v, (int) Math.ceil(box.getMaxX() * s), (int) Math.ceil(box.getMaxY() * s));
    }

    /**
     * The drawing of {@code g} was moved by ({@code dx}, {@code dy}) graph units (out of negative coordinates):
     * scroll along at once, so the picture stays where it was. Also while the camera is off.
     */
    void compensateShift(Graph g, double dx, double dy) {
        View v = activeView(g);
        JViewport vp = viewportOf(v);
        if (vp == null || (dx == 0 && dy == 0)) return;
        boolean ours = v == view && vp == viewport;
        writing = true;
        try {
            double s = scaleOf(v);
            Point p = vp.getViewPosition();
            Dimension ext = vp.getExtentSize();
            Point moved = new Point(Math.max(0, (int) Math.round(p.x + dx * s)), Math.max(0, (int) Math.round(p.y + dy * s)));
            growCanvas(v, moved.x + ext.width, moved.y + ext.height);
            vp.setViewPosition(moved);
            if (ours) {
                lastPos = vp.getViewPosition();
                cx += dx;
                cy += dy;
                target = target.shifted(dx, dy);
                if (content != null) content.setRect(content.getX() + dx, content.getY() + dy, content.getWidth(), content.getHeight());
            }
        } finally {
            writing = false;
        }
        ConstraintOverlay.repaintAll();
    }

    // ------------------------------------------------------------------ planning

    /**
     * The frame that shows {@code b}, starting from where the camera is heading, or {@code null} if it may stay.
     */
    private Frame plan(Rectangle2D b, boolean atRest, boolean fitSmall) {
        Dimension ext = viewport.getExtentSize();
        double w = ext.width, h = ext.height;
        if (w < 40 || h < 40) return null;
        double margin = Math.min(MARGIN, Math.min(w, h) / 8);
        double s = target.scale;
        double ns = s;
        double small = fitSmall ? 1 : atRest ? TOO_SMALL : TINY;
        if (s > fit(b, ext, SAFE_INSET)) {
            ns = fit(b, ext, margin) * ZOOM_OUT_HEADROOM;                   // does not fit any more: zoom out
        } else if (s < preferredScale
                && Math.max(b.getWidth(), 1) * s < small * w && Math.max(b.getHeight(), 1) * s < small * h) {
            ns = Math.max(s, Math.min(fit(b, ext, margin), preferredScale));   // became small: zoom back in
        }
        ns = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, ns));
        boolean zoom = Math.abs(ns - s) > s * 1e-3;
        if (!zoom) ns = s;
        // After a zoom the frame is new anyway: bring the graph inside the margin. Without one, only a graph that left
        // the safe area makes the camera pan.
        double inset = zoom ? margin : SAFE_INSET;
        double ncx = axis(target.cx, b.getMinX(), b.getMaxX(), w / (2 * ns), inset, margin, ns);
        double ncy = axis(target.cy, b.getMinY(), b.getMaxY(), h / (2 * ns), inset, margin, ns);
        if (!zoom && Math.abs(ncx - target.cx) * ns < 0.5 && Math.abs(ncy - target.cy) * ns < 0.5) return null;
        return new Frame(ns, ncx, ncy);
    }

    /**
     * The centre on one axis: {@code c} stays if the content [{@code lo}, {@code hi}] lies at least {@code inset} px
     * inside the visible half-extent {@code half} around it; otherwise the least pan that gives the content
     * {@code margin} px, or the content's middle if it is too wide for that. The visible part never starts before 0,
     * where VANTED's canvas begins.
     */
    private static double axis(double c, double lo, double hi, double half, double inset, double margin, double s) {
        double result = c;
        if (lo - inset / s < c - half || hi + inset / s > c + half) {
            double min = hi + margin / s - half, max = lo - margin / s + half;
            result = min <= max ? Math.max(min, Math.min(max, c)) : (lo + hi) / 2;
        }
        return Math.max(result, half);
    }

    /** The zoom at which {@code b} fits into a viewport of size {@code ext} with {@code margin} px on every side. */
    private static double fit(Rectangle2D b, Dimension ext, double margin) {
        return Math.min((ext.width - 2 * margin) / Math.max(b.getWidth(), 1), (ext.height - 2 * margin) / Math.max(b.getHeight(), 1));
    }

    // ------------------------------------------------------------------ moving

    private boolean moving() {
        return animator != null && animator.isRunning();
    }

    private void stopMove() {
        if (animator != null) animator.stop();
        vScale = vx = vy = 0;
    }

    /** One step of the spring towards {@link #target}; stops once it has arrived. */
    private void tick() {
        if (view == null || activeView(view.getGraph()) != view || viewportOf(view) != viewport) {
            stopMove();
            return;
        }
        long now = System.nanoTime();
        double dt = Math.max(0.001, Math.min(MAX_DT, (now - lastTick) / 1e9));
        lastTick = now;

        double ls = Math.log(scale), lt = Math.log(target.scale);
        double[] r = smoothDamp(ls, lt, vScale, dt);
        ls = r[0];
        vScale = r[1];
        r = smoothDamp(cx, target.cx, vx, dt);
        cx = r[0];
        vx = r[1];
        r = smoothDamp(cy, target.cy, vy, dt);
        cy = r[0];
        vy = r[1];
        scale = Math.exp(ls);

        boolean arrived = Math.abs(ls - lt) < 1e-3
                && Math.abs(cx - target.cx) * scale < 0.5 && Math.abs(cy - target.cy) * scale < 0.5;
        if (arrived) {
            scale = target.scale;
            cx = target.cx;
            cy = target.cy;
        } else if (content != null) {
            // Zoom and centre ease on their own curves, so the visible edge could cut into a box that fits at the
            // zoom of this frame: hold the centre where the box stays inside.
            Dimension ext = viewport.getExtentSize();
            cx = holdInside(cx, content.getMinX(), content.getMaxX(), ext.width / (2 * scale), SAFE_INSET / scale);
            cy = holdInside(cy, content.getMinY(), content.getMaxY(), ext.height / (2 * scale), SAFE_INSET / scale);
        }
        apply();
        if (arrived) stopMove();
    }

    /** {@code c} moved just far enough that [{@code lo}, {@code hi}] lies {@code inset} inside the half-extent; unchanged if it cannot. */
    private static double holdInside(double c, double lo, double hi, double half, double inset) {
        double min = hi + inset - half, max = lo - inset + half;
        if (min > max) return c;
        return Math.max(Math.max(min, Math.min(max, c)), half);
    }

    /**
     * Critically damped spring (the "SmoothDamp" of Game Programming Gems 4, ch. 1.10): {@code {position, velocity}}
     * after {@code dt} seconds, without overshooting the target.
     */
    private double[] smoothDamp(double current, double goal, double velocity, double dt) {
        double omega = 2 / smoothTime, x = omega * dt;
        double decay = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
        double change = current - goal;
        double temp = (velocity + omega * change) * dt;
        double v = (velocity - omega * temp) * decay;
        double out = goal + (change + temp) * decay;
        if ((goal - current > 0) == (out > goal)) {
            out = goal;
            v = 0;
        }
        return new double[]{out, v};
    }

    /** Sets zoom, canvas and scroll position of the view to the current camera; in this order, so nothing is clamped. */
    private void apply() {
        writing = true;
        try {
            double s = scaleOf(view);
            if (Math.abs(scale - s) > s * 5e-4) {
                lastZoom = AffineTransform.getScaleInstance(scale, scale);
                view.zoomChanged(lastZoom);   // VANTED also fits the canvas to the drawing here
                s = scale;
            }
            Dimension ext = viewport.getExtentSize();
            Point p = new Point(Math.max(0, (int) Math.round(cx * s - ext.width / 2.0)),
                    Math.max(0, (int) Math.round(cy * s - ext.height / 2.0)));
            growCanvas(view, p.x + ext.width, p.y + ext.height);
            if (!p.equals(viewport.getViewPosition())) viewport.setViewPosition(p);
            lastPos = viewport.getViewPosition();
        } finally {
            writing = false;
        }
        ConstraintOverlay.repaintAll();
    }

    /**
     * At rest: give back canvas that the graph no longer needs (VANTED's canvas only grows between zoom changes). The
     * same zoom is set again, which makes VANTED fit the canvas to the drawing; the scroll position stays.
     */
    private void shrinkCanvas(Rectangle2D box) {
        JComponent c = view.getViewComponent();
        double s = scaleOf(view);
        Point p = viewport.getViewPosition();
        Dimension ext = viewport.getExtentSize();
        int needW = (int) Math.max(box.getMaxX() * s, p.x + ext.width), needH = (int) Math.max(box.getMaxY() * s, p.y + ext.height);
        if (c.getWidth() < needW + 4 * CANVAS_SLACK && c.getHeight() < needH + 4 * CANVAS_SLACK) return;
        writing = true;
        try {
            lastZoom = AffineTransform.getScaleInstance(s, s);
            view.zoomChanged(lastZoom);
            growCanvas(view, p.x + ext.width, p.y + ext.height);
            viewport.setViewPosition(p);
            lastPos = viewport.getViewPosition();
        } finally {
            writing = false;
        }
    }

    /** Grows (never shrinks) the canvas of {@code v} to at least {@code w} x {@code h} px, with some slack. */
    private static void growCanvas(View v, int w, int h) {
        JComponent c = v.getViewComponent();
        Dimension d = c.getPreferredSize();
        if (w <= d.width && h <= d.height) return;
        Dimension grown = new Dimension(w > d.width ? w + CANVAS_SLACK : d.width, h > d.height ? h + CANVAS_SLACK : d.height);
        c.setPreferredSize(grown);
        c.setSize(grown);
    }

    // ------------------------------------------------------------------ the view and changes by others

    /**
     * Makes the active view of {@code g} the camera's view. A new view starts from its current camera (and the user's
     * zoom remembered for it, if nobody zoomed it since the camera left it), and so does the same view when someone
     * else moved it; if {@code busyCounts}, that is a manual change when the camera was busy.
     */
    private boolean bind(Graph g, boolean busyCounts) {
        View v = activeView(g);
        JViewport vp = viewportOf(v);
        if (vp == null) return false;
        if (v != view || vp != viewport) {
            release();
            view = v;
            viewport = vp;
            viewport.addChangeListener(viewportListener);
            Object[] left = leftViews.remove(v);
            adoptCamera();
            if (left != null && left[0] == view.getZoom()) preferredScale = (Double) left[1];
        } else if (movedByOthers()) {
            boolean busy = busyCounts && (moving() || timelineRunning.getAsBoolean());
            stopMove();
            adoptCamera();
            if (busy && enabled) onManualChange.run();
        } else if (!moving()) {
            sync();
        }
        return enabled;
    }

    /** The camera state is the view's current zoom and scroll position. */
    private void sync() {
        scale = scaleOf(view);
        Rectangle r = viewport.getViewRect();
        cx = (r.x + r.width / 2.0) / scale;
        cy = (r.y + r.height / 2.0) / scale;
        target = new Frame(scale, cx, cy);
        vScale = vx = vy = 0;
        lastZoom = view.getZoom();
        lastPos = viewport.getViewPosition();
    }

    /** Takes over a camera set by the user (or a new view): its zoom is the one to come back to. */
    private void adoptCamera() {
        sync();
        preferredScale = scale;
    }

    /**
     * True if zoom or scroll position are not the ones the camera set: someone zoomed, or scrolled to a position that
     * is no clamp of the camera's own. VANTED sends no zoom events, but every zoom path installs a new transform, so a
     * foreign zoom shows as a different transform object. A smaller canvas or a larger window clamps the position, and
     * the scroll pane does that one axis at a time, so each axis may be either unchanged or clamped.
     */
    private boolean movedByOthers() {
        if (view.getZoom() != lastZoom) return true;
        Point p = viewport.getViewPosition();
        if (lastPos == null || p.equals(lastPos)) return false;
        Dimension size = viewport.getViewSize(), ext = viewport.getExtentSize();
        int clampedX = Math.max(0, Math.min(lastPos.x, size.width - ext.width));
        int clampedY = Math.max(0, Math.min(lastPos.y, size.height - ext.height));
        if ((p.x == lastPos.x || p.x == clampedX) && (p.y == lastPos.y || p.y == clampedY)) {
            lastPos = p;
            return false;
        }
        return true;
    }

    private void onViewportChanged() {
        if (writing || view == null || !movedByOthers()) return;
        boolean busy = moving() || timelineRunning.getAsBoolean();
        stopMove();
        adoptCamera();
        if (busy && enabled) onManualChange.run();
    }

    /** The active view of the active editor session if it shows {@code g}, or {@code null}. */
    private static View activeView(Graph g) {
        MainFrame mainFrame = MainFrame.getInstance();
        if (mainFrame == null || g == null) return null;
        EditorSession session = mainFrame.getActiveEditorSession();
        if (session == null || session.getGraph() != g) return null;
        View v = session.getActiveView();
        return v != null && v.getGraph() == g ? v : null;
    }

    private static JViewport viewportOf(View v) {
        JComponent c = v == null ? null : v.getViewComponent();
        return c != null && c.getParent() instanceof JViewport ? (JViewport) c.getParent() : null;
    }

    private static double scaleOf(View v) {
        AffineTransform zoom = v.getZoom();
        double s = zoom == null ? 1 : zoom.getScaleX();
        return s > 0 ? s : 1;
    }
}
