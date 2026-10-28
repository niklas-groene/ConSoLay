package gui;

import adaptagrams_bridge.CBLayoutAlgorithm;
import gui.components.Badge;
import gui.components.CardPanel;
import gui.components.CardSelector;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.SwitchToggle;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.mental_map.ComparisonLayoutRunner;
import layout.mental_map.DerivedConstraints;
import layout.mental_map.GraphBounds;
import layout.mental_map.NextState;
import layout.mental_map.Snapshot;
import layout.mental_map.SnapshotHistory;
import layout.sbgn.ConstraintSBGNLayout;

import org.AttributeHelper;
import org.BackgroundTaskStatusProviderSupportingExternalCall;
import org.ErrorMsg;
import org.graffiti.editor.GravistoService;
import org.graffiti.editor.MainFrame;
import org.graffiti.event.GraphEvent;
import org.graffiti.event.GraphListener;
import org.graffiti.event.ListenerNotFoundException;
import org.graffiti.event.TransactionEvent;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.session.Session;
import org.graffiti.session.SessionListenerExt;
import org.graffiti.plugins.modes.defaults.MegaMoveTool;

import javax.swing.Timer;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Panel "Mental Map" of the {@link AlgorithmsTab}: re-layout of the active graph relative to a pinned reference
 * snapshot with the Prototype method ({@link ComparisonLayoutRunner.Choice#PROTOTYPE}), a live re-layout toggle, and a
 * per-graph snapshot timeline for restoring, pinning, stepping and morphing between earlier layout states. The panel
 * never writes the user's constraint store; what a run applied is shown, read only, in the {@link ConstraintsTab}.
 *
 * @author Niklas Gröne
 */
public class MentalMapPanel extends JPanel implements SessionListenerExt, GraphListener {

    private static final long serialVersionUID = 1L;

    /** Wrap width of the live toggle's text beside its check mark. */
    private static final int CHECK_TEXT_WIDTH = Ui.TEXT_WIDTH - 35;
    /** Padding inside a snapshot card. */
    private static final int CARD_PADDING = Ui.GAP_M;
    /** Size of the thumbnail of a snapshot card. */
    private static final int THUMB_WIDTH = 46, THUMB_HEIGHT = 34;
    /** Wrap width of the text of a snapshot card: the card's inner width minus the thumbnail and the gap beside it. */
    private static final int CARD_TEXT_WIDTH = Ui.TEXT_WIDTH - 2 * (CARD_PADDING + 1) - THUMB_WIDTH - Ui.GAP_M;

    /** Resolution of the scrubber: its value runs from 0 (oldest snapshot) to this (newest). */
    private static final int SCRUB_MAX = 1000;
    /** Duration of a step to the neighbouring snapshot (ms); longer jumps take longer, up to {@link #STEP_MS_MAX}. */
    private static final int STEP_MS_PER_SNAPSHOT = 650, STEP_MS_MIN = 300, STEP_MS_MAX = 1400;
    /**
     * While the timeline runs without one still frame, the camera looks this far (in snapshots) ahead, so it starts to
     * make room before the graph needs it.
     */
    private static final double CAMERA_LOOKAHEAD = 0.5;
    /**
     * The display offset grows once the timeline comes closer than {@link #MIN_GAP} to VANTED's canvas origin, and then
     * puts its top-left corner at {@link #ORIGIN_MARGIN} (as VANTED's "Move Network to Top-Left", Cmd+1, does).
     */
    private static final double MIN_GAP = 10, ORIGIN_MARGIN = 50;

    /**
     * Packages and classes whose graph changes are layout output, not user edits: the SBGN pipeline, the
     * {@link StepLayoutPanel} and the cola bridge. Graph listeners run synchronously inside them, so they are on the
     * call stack of the event (see {@link #isLayoutOutput()}).
     */
    private static final String[] LAYOUT_CODE = {
            packageOf(ConstraintSBGNLayout.class),
            StepLayoutPanel.class.getName(),
            CBLayoutAlgorithm.class.getName()
    };

    /** The panel that listens; building the add-on tab again unregisters the previous one. */
    private static MentalMapPanel instance;

    /** A refresh of the Constraints tab's "Mental map" section after an edit is queued on the EDT. */
    private volatile boolean sectionRefreshQueued;

    /** Why the Constraints tab no longer shows the last run after the timeline put another state on the graph. */
    private static final String RESTORED = "A snapshot of the timeline is on the graph now, not the result of the last run.";

    /**
     * The timeline of one graph and how the live graph shows it. The snapshots keep the coordinates of the layout that
     * produced them; the live graph shows them moved by ({@link #offsetX}, {@link #offsetY}), which only grows, when
     * they reach negative coordinates. {@link #shown} holds, per node uid, the timeline position the panel last put on
     * the live graph, so that a capture of an unchanged node gets exactly that position back.
     */
    private static final class GraphTimeline {
        final SnapshotHistory history = new SnapshotHistory();
        double offsetX, offsetY;
        Map<String, Point2D> shown = Collections.emptyMap();
        /** Scrubber value while another graph is active: the state this graph shows. */
        int sliderValue = SCRUB_MAX;
        /**
         * What the last layout run applied; null before the first run, after a failed one and once the timeline
         * (restore, scrubbing) put another state on the graph.
         */
        LastRun lastRun;
        /** Why {@link #lastRun} is null, for the Constraints tab; null: no run yet. */
        String noRunReason;
        /** The file of the state the graph shows (loaded with "Load next state…" or opened); null: unknown. */
        File stateFile;
        /** Name of the state loaded last; the next layout run labels its snapshot with it. Null: none waiting. */
        String pendingState;
    }

    /**
     * What the last layout run on a graph applied: the constraints and groups the method derived from the reference
     * (see {@link DerivedConstraints}). The "Mental map" section of the {@link ConstraintsTab} shows them; nothing
     * solves them again.
     */
    static final class LastRun {
        final String method;
        final String reference;
        final DerivedConstraints derived;

        LastRun(String method, String reference, DerivedConstraints derived) {
            this.method = method;
            this.reference = reference;
            this.derived = derived;
        }
    }

    /** What the last run on {@code g} applied, or null (no run yet, it failed, or the timeline showed another state since). */
    static LastRun lastRunOf(Graph g) {
        GraphTimeline t = instance == null || g == null ? null : instance.timelines.get(g);
        return t == null ? null : t.lastRun;
    }

    /** Why {@link #lastRunOf} is null for {@code g}; null if there was no run yet. */
    static String noRunReasonOf(Graph g) {
        GraphTimeline t = instance == null || g == null ? null : instance.timelines.get(g);
        return t == null ? null : t.noRunReason;
    }

    /** {@code t}'s last run no longer describes what the live graph shows. */
    private static void forgetLastRun(GraphTimeline t, String reason) {
        if (t.lastRun == null) return;
        t.lastRun = null;
        t.noRunReason = reason;
        ConstraintsTab.mentalMapRunChanged();
    }

    /**
     * One timeline per graph; {@link #sessionClosed} drops it. The weak keys are only a fallback: a timeline with a
     * {@link GraphTimeline#lastRun} refers to live nodes, and so to its own key.
     */
    private final Map<Graph, GraphTimeline> timelines = new WeakHashMap<>();
    /** Timeline of {@link #graph}. */
    private GraphTimeline timeline = new GraphTimeline();
    /** Shortcut for {@code timeline.history}. */
    private SnapshotHistory history = timeline.history;
    private final ComparisonLayoutRunner comparisonLayouts = new ComparisonLayoutRunner();
    /** Graph of the active session; the panel is a delayed graph listener of it. */
    private Graph graph;
    private boolean sessionListenerRegistered;
    /**
     * The user has used the panel: from now on a graph that becomes active is captured, which gives its nodes their
     * {@code mentalmap.uid} attribute (see {@link Snapshot#ensureUid}); before that nothing is written into a graph.
     */
    private boolean engaged;
    /** The layout method: Prototype, and the Improved method as a placeholder that cannot be selected yet. */
    private final CardSelector<ComparisonLayoutRunner.Choice> methodSelector;
    private final StatusLabel runStatusLabel;
    /** Snapshot cards of the "History" section, newest first. */
    private final VerticalStack timelineListPanel;
    /** Section "Timeline"; its header counts the snapshots. */
    private final Section timelineSection;
    private final StatusLabel referenceStatusLabel;
    /** Shown under the reference status while nothing is pinned. */
    private final JLabel referenceHint;
    private final JCheckBox liveToggle;
    private Timer liveTimer;
    private boolean suppressLive = false;
    // Timeline scrubber / morph animation
    private final JSlider scrubSlider;
    /** "k / n" under the scrubber: the snapshot nearest to its position. */
    private final JLabel positionLabel;
    private final JButton playButton;
    private final JButton backButton;
    private final JButton nextButton;
    private Timer playTimer;
    /** Animation of a single step (Back / Next): moves the scrubber to {@link #stepTarget}. */
    private Timer stepTimer;
    private int stepTarget = -1;
    private int lastScrubIndex = -1;
    /** Snapshot count shown in the header of the "Timeline" section; it changes only with the history. */
    private int countInHeader = -1;
    private boolean scrubbing = false;
    /** The panel sets the scrubber to where the live graph already is: no morph frame. */
    private boolean movingSliderQuietly = false;
    /**
     * What the camera keeps in view while Play or a step runs: one still frame around all snapshots it passes (see
     * {@link TimelineCamera#stillFrame}), or {@code null} to follow the snapshots around the scrubber.
     */
    private Rectangle2D runContent;
    // Keep graph in view
    private final TimelineCamera camera = new TimelineCamera(this::onManualCameraChange, this::isTimelineRunning);
    private final SwitchToggle keepInViewSwitch = new SwitchToggle("Keep graph in view", true);
    private final JLabel keepInViewState = new JLabel();

    /**
     * Builds the sections "Method", "Reference", "Timeline" and "History" in a transparent column without an outer
     * border (the tab's scrolling column provides the padding) and takes over from a previous panel.
     */
    public MentalMapPanel() {
        super(new BorderLayout());
        setOpaque(false);

        // --- Method: the Prototype, and a placeholder for the planned Improved method. The other choices of
        //     ComparisonLayoutRunner are not offered here.
        methodSelector = new CardSelector<>(Ui.TEXT_WIDTH);
        methodSelector.addOption(ComparisonLayoutRunner.Choice.PROTOTYPE, "Prototype",
                "Keeps the left-right and top-bottom order and the neighbourhood groups of the reference.",
                "The mental-map method of the thesis, extended for sequences of states: hard ordering and cluster "
                        + "constraints derived from the pinned reference. A changed graph is laid out with a "
                        + "constraint-based force-directed layout; an unchanged graph is only made to satisfy the "
                        + "constraints. From step to step the nodes keep their order and stay close to where they "
                        + "were, new nodes start next to their neighbours (or in the nearest free space when there is "
                        + "no room near them), and when the graph shrinks the freed space is given back down to a "
                        + "readable density.");
        methodSelector.addPlaceholderOption(ComparisonLayoutRunner.Choice.IMPROVED, "Improved",
                "Planned successor of the Prototype. Not available yet.",
                "An improved mental-map algorithm is planned. It cannot be selected yet.", "Coming soon");
        methodSelector.setName("mentalMapMethod");
        methodSelector.getAccessibleContext().setAccessibleName("Method");

        Section methodSection = new Section("Method");
        methodSection.addRow(methodSelector);

        // --- Reference: the pinned snapshot, the actions relative to it, the live toggle and the run status.
        //     Status lines are clipped, hints wrap: nothing may widen the column.
        referenceStatusLabel = new StatusLabel(" ");
        referenceHint = Ui.hint("The first run pins the current layout.");

        JButton preserveButton = Ui.primaryButton("Run layout",
                "Run the selected method relative to the pinned reference snapshot. While nothing is pinned, "
                        + "the first click pins the current layout instead. The constraints a run applies are "
                        + "listed, read only, under Constraints › Mental map.",
                e -> runPreserve(true));

        JButton captureButton = Ui.button("Capture snapshot",
                "Store the current layout as a new snapshot and pin it as the reference.",
                e -> captureSnapshot(null));

        JButton nextStateButton = Ui.button("Load next state…",
                "Turn the graph into the state in a graph file, e.g. the network of the next time point. Nodes are "
                        + "matched by their label: nodes and edges the file no longer has are removed, new ones are "
                        + "added with their style from the file, in a block right of the drawing. The current layout "
                        + "is pinned as the reference first, so Run layout places the new state relative to it.",
                e -> loadNextState());
        nextStateButton.setName("mentalMapNextState");

        liveToggle = new JCheckBox(Ui.wrapped("Auto re-layout while editing", CHECK_TEXT_WIDTH));
        liveToggle.setOpaque(false);
        liveToggle.setToolTipText(Ui.tooltip("Run the selected layout 0.4 s after an edit of the graph. "
                + "The SBGN steps and the constraint re-solve after a move do not count as edits."));
        liveToggle.addActionListener(e -> onLiveToggled());

        runStatusLabel = new StatusLabel("Ready");

        Section referenceSection = new Section("Reference");
        referenceSection.addRow(referenceStatusLabel);
        referenceSection.addRow(referenceHint, Ui.GAP_XS);
        referenceSection.addRow(preserveButton);
        referenceSection.addRow(captureButton);
        referenceSection.addRow(nextStateButton);
        referenceSection.addRow(liveToggle);
        referenceSection.addRow(runStatusLabel);

        // --- Timeline: the scrubber morphs the graph from the oldest (left) to the newest (right) snapshot.
        scrubSlider = new JSlider(0, SCRUB_MAX, SCRUB_MAX);
        scrubSlider.setOpaque(false);
        scrubSlider.getAccessibleContext().setAccessibleName("Timeline position");
        scrubSlider.addChangeListener(e -> onScrub());

        positionLabel = mutedSmall(" ");
        positionLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel scrubber = new JPanel(new BorderLayout());
        scrubber.setOpaque(false);
        scrubber.add(scrubSlider, BorderLayout.CENTER);
        scrubber.add(scrubEnds(positionLabel), BorderLayout.SOUTH);

        playButton = Ui.button("▶ Play", "Animate the morph across the whole timeline.", e -> togglePlay());
        backButton = Ui.button("← Back", "Glide to the previous snapshot.", e -> step(-1));
        nextButton = Ui.button("Next →", "Glide to the next snapshot.", e -> step(1));
        playButton.setName("mentalMapPlay");
        backButton.setName("mentalMapBack");
        nextButton.setName("mentalMapNext");

        keepInViewSwitch.setName("keepGraphInView");
        keepInViewSwitch.setToolTipText(Ui.tooltip("<b>On</b>: while the timeline plays, steps or is dragged, and after "
                + "a restore or a layout run, the view zooms and pans smoothly just as far as needed to keep the whole "
                + "graph visible. Zooming or panning by hand while it moves the view switches it off. <b>Off</b>: the "
                + "view stays where you put it."));
        keepInViewSwitch.addActionListener(e -> onKeepInViewToggled());
        ConstraintsTab.showState(keepInViewState, true);

        timelineSection = new Section("Timeline");
        timelineSection.addRow(scrubber);
        timelineSection.addRow(playButton);
        timelineSection.addRow(Ui.row(backButton, nextButton));
        timelineSection.addRow(Ui.hint("Drag to morph from the oldest to the newest snapshot."));
        timelineSection.addRow(ConstraintsTab.switchRow("Keep graph in view", keepInViewSwitch, keepInViewState), Ui.GAP_M);
        timelineSection.addRow(Ui.hint("Zooms and pans smoothly so the whole graph stays visible. "
                + "Zooming or panning by hand while it moves the view switches it off."), Ui.GAP_XS);

        // --- History: one card per snapshot, directly in the tab's scrolling column.
        timelineListPanel = new VerticalStack();

        Section historySection = new Section("History");
        historySection.addRow(timelineListPanel);

        VerticalStack sections = new VerticalStack();
        sections.addRow(methodSection, 0);
        sections.addRow(referenceSection, Ui.SECTION_GAP);
        sections.addRow(timelineSection, Ui.SECTION_GAP);
        sections.addRow(historySection, Ui.SECTION_GAP);
        add(sections, BorderLayout.CENTER);

        refreshTimeline();

        if (instance != null) instance.dispose();
        instance = this;
        registerSessionListener();
    }

    // ---- layout helpers ----

    /** Small muted "old" and "new" under the ends of the scrubber, and the position between them. */
    private static JPanel scrubEnds(JLabel position) {
        JPanel ends = new JPanel(new BorderLayout());
        ends.setOpaque(false);
        ends.add(mutedSmall("old"), BorderLayout.WEST);
        ends.add(position, BorderLayout.CENTER);
        ends.add(mutedSmall("new"), BorderLayout.EAST);
        return ends;
    }

    private static JLabel mutedSmall(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Ui.small());
        label.setForeground(Ui.MUTED);
        return label;
    }

    private static String packageOf(Class<?> c) {
        String name = c.getName();
        return name.substring(0, name.lastIndexOf('.') + 1);
    }

    /** Name of a method in status texts and snapshot labels (the card title). */
    private static String methodName(ComparisonLayoutRunner.Choice choice) {
        switch (choice) {
            case PROTOTYPE: return "Prototype";
            case IMPROVED: return "Improved";
            default: return choice.toString();
        }
    }

    // ---- session and graph wiring ----

    /**
     * Listen to session changes of the main frame (VANTED's inspector forwards them to inspector tabs only); if the
     * main frame does not exist yet, when the panel is shown.
     */
    private void registerSessionListener() {
        MainFrame mainFrame = MainFrame.getInstance();
        if (sessionListenerRegistered || mainFrame == null || instance != this) return;
        mainFrame.addSessionListener(this);
        sessionListenerRegistered = true;
        sessionChanged(mainFrame.getActiveSession());   // a map may already be open
    }

    @Override
    public void addNotify() {
        super.addNotify();
        registerSessionListener();
    }

    /** Unregister everything: the add-on tab was built again and a new panel took over. */
    private void dispose() {
        stopTimeline();
        camera.release();
        if (liveTimer != null) liveTimer.stop();
        MainFrame mainFrame = MainFrame.getInstance();
        if (mainFrame != null) mainFrame.removeSessionListener(this);
        sessionListenerRegistered = false;
        detachGraph();
        graph = null;
    }

    private void detachGraph() {
        if (graph == null) return;
        try {
            graph.getListenerManager().removeGraphListener(this);
        } catch (ListenerNotFoundException e) {
            ErrorMsg.addErrorMessage(e);
        }
    }

    @Override
    public void sessionChanged(Session s) {
        Graph nextGraph = s == null ? null : s.getGraph();
        // VANTED may announce the same active session more than once while it builds the view: capture it only once.
        if (nextGraph == graph) return;
        stopTimeline();   // the morph, a step and a pending live run belong to the previous graph
        timeline.sliderValue = scrubSlider.getValue();
        camera.release();
        if (liveTimer != null) liveTimer.stop();
        detachGraph();
        graph = nextGraph;
        if (graph != null) {
            graph.getListenerManager().addDelayedGraphListener(this);
            timeline = timelines.computeIfAbsent(graph, g -> new GraphTimeline());
            history = timeline.history;
            if (engaged && history.isEmpty() && !isSbgnSkeleton(graph)) autoCaptureOnLoad(graph);
        } else {
            timeline = new GraphTimeline();   // holds no reference to a closed graph
            history = timeline.history;
        }
        refreshTimeline();
        showSliderValue(timeline.sliderValue);
    }

    @Override
    public void sessionClosed(Session s) {
        if (s != null && s.getGraph() != null) timelines.remove(s.getGraph());   // weak keys alone keep the last one
    }

    /** The skeleton window PD step 1 opens is a helper view of the running SBGN layout, not a map of the user. */
    private static boolean isSbgnSkeleton(Graph g) {
        Object layout = StepLayoutPanel.getActiveLayout();
        return layout instanceof ConstraintSBGNLayout && ((ConstraintSBGNLayout) layout).skeleton == g;
    }

    /**
     * A restore re-creates nodes and edges; a partial SBGN run on this map would continue on stale state, so the
     * next SBGN step starts a fresh layout.
     */
    private static void dropSbgnLayoutOf(Graph live) {
        Object layout = StepLayoutPanel.getActiveLayout();
        if (layout instanceof ConstraintSBGNLayout && ((ConstraintSBGNLayout) layout).graph == live)
            StepLayoutPanel.setActiveLayout(null);
    }

    @Override
    public void sessionDataChanged(Session s) {
    }

    public void postEdgeAdded(GraphEvent e) {
        graphEdited();
    }

    public void postEdgeRemoved(GraphEvent e) {
        graphEdited();
    }

    public void postGraphCleared(GraphEvent e) {
    }

    public void postNodeAdded(GraphEvent e) {
        graphEdited();
    }

    public void postNodeRemoved(GraphEvent e) {
        graphEdited();
    }

    public void preEdgeAdded(GraphEvent e) {
    }

    public void preEdgeRemoved(GraphEvent e) {
    }

    public void preGraphCleared(GraphEvent e) {
    }

    public void preNodeAdded(GraphEvent e) {
    }

    public void preNodeRemoved(GraphEvent e) {
    }

    public void transactionFinished(TransactionEvent e, BackgroundTaskStatusProviderSupportingExternalCall status) {
        // A transaction that changed nothing (e.g. Select All) is no edit, and neither is a drag with the move tool:
        // AddonTab re-solves the user's constraints after it, a live run would undo both.
        if (e != null && e.getChangedObjects() != null && e.getChangedObjects().isEmpty()) return;
        if (e != null && e.getSource() instanceof MegaMoveTool) return;
        graphEdited();
    }

    /**
     * The graph changed outside the panel: the live topology is no longer the one of the snapshot the scrubber last
     * restored, so the next morph frame restores again; and the live mode may re-lay out.
     */
    private void graphEdited() {
        if (!suppressLive && !scrubbing) {
            lastScrubIndex = -1;
            // The "Mental map" rows keep only constraints whose nodes are still on the graph. VANTED deletes inside a
            // transaction, so AddonTab's (delayed) preNodeRemoved, which refreshes the lists, does not come.
            if (timeline.lastRun != null && !sectionRefreshQueued) {
                sectionRefreshQueued = true;   // one refresh for a burst of edits
                SwingUtilities.invokeLater(() -> {
                    sectionRefreshQueued = false;
                    ConstraintsTab.mentalMapRunChanged();
                });
            }
        }
        scheduleLiveRelayout();
    }

    public void transactionStarted(TransactionEvent e) {
    }

    /** True if the current event comes from layout code (see {@link #LAYOUT_CODE}), not from an edit of the user. */
    private static boolean isLayoutOutput() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String c = frame.getClassName();
            if (c.equals(AddonTab.class.getName()) && frame.getMethodName().equals("afterMoveTransaction")) return true;
            for (String code : LAYOUT_CODE) {
                if (c.startsWith(code)) return true;
            }
        }
        return false;
    }

    // ---- actions ----

    /** Active live graph of the current editor session, or {@code null}. */
    private Graph activeGraph() {
        try {
            return GravistoService.getInstance().getMainFrame().getActiveEditorSession().getGraph();
        } catch (Exception e) {
            return null;
        }
    }

    /** Re-layout the live graph preserving the pinned reference snapshot. */
    private void runPreserve(boolean addToHistory) {
        Graph live = activeGraph();
        if (live == null) return;
        Snapshot ref = history.getReference();
        if (ref == null) {
            // Nothing pinned yet: capture the current layout as the first reference.
            captureSnapshot("Initial");
            return;
        }
        stopTimeline();   // a running morph would overwrite the result
        ComparisonLayoutRunner.Choice choice = methodSelector.getSelectedValue();
        // A live run follows the graph only if it was in view: the user may be working zoomed in on a detail.
        boolean follow = addToHistory || camera.shows(live, GraphBounds.of(live));
        GraphTimeline t = timeline;
        double ox = t.offsetX, oy = t.offsetY;
        suppressLive = true;
        long start = System.nanoTime();
        try {
            status("Running " + methodName(choice) + " …", StatusLabel.Kind.BUSY);
            // The layout runs in the frame of the snapshots, without the display offset: the runner first puts every
            // node at its reference position (or next to its reference neighbours), so the offset does not reach it.
            GraphBounds.translate(live, -ox, -oy);
            boolean done = false;
            try {
                t.lastRun = null;
                t.noRunReason = "The last run failed.";
                DerivedConstraints derived = comparisonLayouts.run(choice, live, ref);
                t.lastRun = new LastRun(methodName(choice), snapshotName(ref), derived);
                t.noRunReason = null;
                t.shown = positionsByUid(live);
                if (addToHistory) {
                    // Record the result, but keep the reference pinned so that repeated runs stay relative to it.
                    String label = t.pendingState == null ? "After " + methodName(choice)
                            : t.pendingState + " · " + methodName(choice);
                    t.pendingState = null;
                    history.add(Snapshot.capture(live, label), false);
                }
                growOffset(GraphBounds.union(history.bounds(), GraphBounds.of(live)));
                done = true;
            } finally {
                GraphBounds.translate(live, done ? t.offsetX : ox, done ? t.offsetY : oy);
            }
            camera.compensateShift(live, t.offsetX - ox, t.offsetY - oy);
            if (addToHistory) {
                refreshTimeline();
                showSliderAt(history.getAll().size() - 1);   // the live graph is the newest snapshot now
            }
            long millis = (System.nanoTime() - start) / 1_000_000L;
            status("Finished in " + millis + " ms", StatusLabel.Kind.SUCCESS);
        } catch (RuntimeException | LinkageError ex) {
            status("Layout failed: " + ex.getMessage(), StatusLabel.Kind.ERROR);
            JOptionPane.showMessageDialog(this,
                    "The selected layout could not be completed:\n" + ex.getMessage(),
                    "Layout error", JOptionPane.ERROR_MESSAGE);
        } finally {
            suppressLive = false;
        }
        Rectangle2D bounds = GraphBounds.of(live);
        camera.ensureScrollable(live, bounds);
        if (follow) camera.keepInView(live, bounds, TimelineCamera.Pace.JUMP, true);
        DisplayConstraints.revalidateIndication();
        ConstraintsTab.mentalMapRunChanged();
        updateReferenceStatus();
    }

    /** Node positions of {@code g} by uid. */
    private static Map<String, Point2D> positionsByUid(Graph g) {
        Map<String, Point2D> result = new HashMap<>();
        for (Node n : g.getNodes()) {
            result.put(Snapshot.uidOf(n), new Point2D.Double(AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)));
        }
        return result;
    }

    /**
     * Status line under the actions; the full text is its tooltip because the line is clipped to the tab width.
     * The kind marks a running action ({@code BUSY}), a result ({@code SUCCESS}) or a failure ({@code ERROR}).
     */
    private void status(String text, StatusLabel.Kind kind) {
        if (runStatusLabel != null) runStatusLabel.setStatus(text, kind);
    }

    /** Capture the current layout as a new snapshot (which becomes the reference). */
    private void captureSnapshot(String label) {
        engaged = true;
        Graph live = activeGraph();
        if (live == null || live.getNumberOfNodes() == 0) return;
        stopTimeline();
        GraphTimeline t = timeline;
        // A fixed name ("Snapshot n" as its card would show it now): names by place would change once the cap drops
        // the oldest snapshots, while the Constraints tab keeps naming the reference of a run.
        if (label == null) label = "Snapshot " + (history.addedCount() + 1);
        // In the frame of the timeline; nodes nobody moved since the panel put them there get exactly that position.
        // A capture moves nothing (it would bypass VANTED's undo history), so the display offset is not grown here.
        Snapshot s = history.add(Snapshot.capture(live, label, t.offsetX, t.offsetY, t.shown));
        t.shown = s.positionsByUid();
        camera.ensureScrollable(live, GraphBounds.of(live));
        refreshTimeline();
        showSliderAt(history.getAll().size() - 1);
    }

    /**
     * "Load next state…": pin the current layout as the reference (the snapshot that already holds it, or a new
     * capture), then turn the graph into the state of a chosen graph file (see {@link NextState}). The next layout run
     * labels its snapshot with the state's name. The file chooser proposes the file after the current state's file.
     */
    private void loadNextState() {
        Graph live = activeGraph();
        if (live == null) {
            status("Open a graph first", StatusLabel.Kind.ERROR);
            return;
        }
        GraphTimeline t = timeline;
        File current = t.stateFile != null ? t.stateFile : fileOf(live);
        JFileChooser chooser = new JFileChooser(current == null ? null : current.getParentFile());
        chooser.setDialogTitle("Load next state");
        File next = fileAfter(current);
        if (next != null) chooser.setSelectedFile(next);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file == null || !file.isFile()) return;

        Graph state;
        try {
            state = MainFrame.getInstance().getGraph(file);
        } catch (Exception ex) {
            state = null;
            ErrorMsg.addErrorMessage(ex);
        }
        String problem = state == null ? "The file could not be read as a graph." : NextState.problem(live, state);
        if (problem != null) {
            status("Not loaded: " + problem, StatusLabel.Kind.ERROR);
            JOptionPane.showMessageDialog(this, file.getName() + ":\n" + problem, "Load next state",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        stopTimeline();
        pinCurrentState(live, current == null ? null : stateName(current));
        suppressLive = true;
        NextState.Change change;
        try {
            change = NextState.apply(live, state);
        } finally {
            suppressLive = false;
        }
        t.stateFile = file;
        t.pendingState = stateName(file);
        lastScrubIndex = -1;   // the live topology is none of the snapshots now
        dropSbgnLayoutOf(live);
        if (t.lastRun != null) ConstraintsTab.mentalMapRunChanged();   // its rows keep only nodes still on the graph
        Rectangle2D bounds = GraphBounds.of(live);
        camera.ensureScrollable(live, bounds);
        camera.keepInView(live, bounds, TimelineCamera.Pace.JUMP, true);
        DisplayConstraints.revalidateIndication();
        status("Loaded " + t.pendingState + ": " + change + " – Run layout places them", StatusLabel.Kind.SUCCESS);
        if (liveToggle.isSelected()) restartLiveTimer();
    }

    /**
     * Pin the layout the graph shows as the reference: the newest snapshot that holds exactly this state, or a new
     * capture labelled {@code label} (null: "Snapshot n").
     */
    private void pinCurrentState(Graph live, String label) {
        engaged = true;
        GraphTimeline t = timeline;
        Snapshot now = Snapshot.capture(live, label, t.offsetX, t.offsetY, t.shown);
        List<Snapshot> all = history.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).sameState(now)) {
                history.setReference(all.get(i));
                refreshTimeline();
                return;
            }
        }
        captureSnapshot(label);
    }

    /** The file a graph was loaded from, or null. */
    private static File fileOf(Graph g) {
        String name = g.getName(true);
        if (name == null || name.isEmpty()) return null;
        File f = new File(name);
        return f.isFile() ? f : null;
    }

    /** The file after {@code f} in its folder (same extension, by name), or null. */
    private static File fileAfter(File f) {
        if (f == null || f.getParentFile() == null) return null;
        String ext = extensionOf(f.getName());
        File[] siblings = f.getParentFile().listFiles(x -> x.isFile() && !x.isHidden()
                && extensionOf(x.getName()).equalsIgnoreCase(ext));
        if (siblings == null) return null;
        Arrays.sort(siblings);
        for (File s : siblings) if (s.getName().compareTo(f.getName()) > 0) return s;
        return null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** Name of a state in snapshot labels: its file name without extension. */
    private static String stateName(File f) {
        String name = f.getName();
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    /**
     * Capture an initial reference snapshot when a graph is loaded (if it has nodes). The new timeline has no display
     * offset yet, and the loaded map is not moved: that waits for the first explicit action on it.
     */
    private void autoCaptureOnLoad(Graph loaded) {
        if (loaded == null || loaded.getNumberOfNodes() == 0) return;
        Snapshot s = history.add(Snapshot.capture(loaded, "On graph load", timeline.offsetX, timeline.offsetY, timeline.shown));
        timeline.shown = s.positionsByUid();
        refreshTimeline();
    }

    /**
     * VANTED's canvas cannot show negative coordinates, but the Prototype spreads a drawing around its centre, so a
     * map near the origin reaches them. Grows the display offset of the timeline so that {@code extent} (in the frame
     * of the timeline) starts at {@link #ORIGIN_MARGIN}, rounded up to whole units, once it came closer than
     * {@link #MIN_GAP} to the origin. The offset never shrinks, so states already shown never move back. Returns
     * whether it changed; the caller moves the live graph.
     */
    private boolean growOffset(Rectangle2D extent) {
        if (extent == null) return false;
        GraphTimeline t = timeline;
        boolean grown = false;
        // Whole units, so whole coordinates stay whole (SBGN-ED writes some glyphs of an export rounded to pixels).
        if (extent.getMinX() + t.offsetX < MIN_GAP) {
            t.offsetX = Math.ceil(ORIGIN_MARGIN - extent.getMinX());
            grown = true;
        }
        if (extent.getMinY() + t.offsetY < MIN_GAP) {
            t.offsetY = Math.ceil(ORIGIN_MARGIN - extent.getMinY());
            grown = true;
        }
        return grown;
    }

    /** {@code box} (frame of the timeline) as the live graph shows it. */
    private Rectangle2D shown(Rectangle2D box) {
        if (box == null) return null;
        return new Rectangle2D.Double(box.getX() + timeline.offsetX, box.getY() + timeline.offsetY, box.getWidth(), box.getHeight());
    }

    /**
     * Put snapshot {@code s} on the live graph: its positions, nodes and edges. AddonTab drops the constraints of the
     * removed nodes as for any deletion.
     */
    private void restoreSnapshot(Snapshot s) {
        Graph live = activeGraph();
        if (live == null) return;
        stopTimeline();
        double ox = timeline.offsetX, oy = timeline.offsetY;
        growOffset(history.bounds());   // e.g. a captured state that reached negative coordinates
        // before the restore: its node deletions refresh the lists, which then need not rebuild the old run's rows
        forgetLastRun(timeline, RESTORED);
        suppressLive = true;
        try {
            s.restore(live, timeline.offsetX, timeline.offsetY);
            timeline.shown = s.positionsByUid();
        } finally {
            suppressLive = false;
        }
        camera.compensateShift(live, timeline.offsetX - ox, timeline.offsetY - oy);
        dropSbgnLayoutOf(live);
        int index = history.getAll().indexOf(s);
        lastScrubIndex = index;   // the live graph has this snapshot's topology now; an edit resets it (graphEdited)
        if (index >= 0) showSliderAt(index);
        Rectangle2D bounds = shown(s.bounds());
        camera.ensureScrollable(live, bounds);
        camera.keepInView(live, bounds, TimelineCamera.Pace.JUMP, true);
        DisplayConstraints.revalidateIndication();
    }

    /** Live mode needs a reference taken before the first edit; switching it on counts as using the panel. */
    private void onLiveToggled() {
        if (liveToggle.isSelected()) {
            engaged = true;
            if (history.getReference() == null) captureSnapshot("Initial");
        } else if (liveTimer != null) {
            liveTimer.stop();
        }
    }

    /**
     * Debounced live re-layout while the user edits the graph (only if the toggle is on). Graph changes of the
     * panel itself, of the SBGN pipeline and of a constraint re-solve are no edits.
     */
    private void scheduleLiveRelayout() {
        if (suppressLive || scrubbing) return;
        if (liveToggle == null || !liveToggle.isSelected()) return;
        if (isLayoutOutput()) return;
        if (SwingUtilities.isEventDispatchThread()) restartLiveTimer();
        else SwingUtilities.invokeLater(this::restartLiveTimer);   // e.g. an algorithm run in the background
    }

    private void restartLiveTimer() {
        if (!liveToggle.isSelected()) return;
        if (liveTimer != null) liveTimer.stop();
        liveTimer = new Timer(400, e -> {
            if (liveToggle.isSelected()) runPreserve(false);
        });
        liveTimer.setRepeats(false);
        liveTimer.start();
    }

    /** Reference status line: the pinned snapshot, or a hint that the first run pins the current layout. */
    private void updateReferenceStatus() {
        if (referenceStatusLabel == null) return;
        Snapshot ref = history.getReference();
        if (ref == null) {
            referenceStatusLabel.setStatus("Nothing pinned yet", StatusLabel.Kind.INFO);
        } else {
            referenceStatusLabel.setStatus("Pinned: " + ref.getNodeCount() + " nodes · " + ref.ago(),
                    StatusLabel.Kind.SUCCESS);
        }
        if (referenceHint != null) referenceHint.setVisible(ref == null);
    }

    /** Rebuild the snapshot cards from the snapshot history (newest first) and update the timeline controls. */
    private void refreshTimeline() {
        if (timelineListPanel == null) return;
        lastScrubIndex = -1; // history changed → scrubber must re-sync topology on next move
        timelineListPanel.clearRows();
        List<Snapshot> all = history.getAll();
        if (all.isEmpty()) {
            timelineListPanel.addRow(Ui.hint("No snapshots yet – capture one to pin the reference."));
        } else {
            for (int i = all.size() - 1; i >= 0; i--) {
                timelineListPanel.addRow(buildSnapshotRow(all.get(i), i));
            }
        }
        updateTimelineControls();
        updateReferenceStatus();
        timelineListPanel.revalidate();
        timelineListPanel.repaint();
    }

    /**
     * Header counter of the "Timeline" section and the position under the scrubber. Scrubber, Play and the steps
     * need two snapshots or more (with fewer they do nothing), so they are disabled until then; a running play stays
     * stoppable, and a step is possible only where there is a snapshot in its direction.
     */
    private void updateTimelineControls() {
        int count = history.getAll().size();
        if (timelineSection != null && count != countInHeader) {
            timelineSection.setTrailingText(count == 1 ? "1 snapshot" : count + " snapshots");
            countInHeader = count;
        }
        boolean canMorph = count >= 2;
        if (scrubSlider != null) scrubSlider.setEnabled(canMorph);
        if (playButton != null) playButton.setEnabled(canMorph || isPlaying());
        if (backButton == null || nextButton == null) return;
        int value = stepTarget >= 0 ? sliderValue(stepTarget, count) : scrubSlider.getValue();
        backButton.setEnabled(canMorph && value > 0);
        nextButton.setEnabled(canMorph && value < SCRUB_MAX);
        positionLabel.setText(count == 0 ? " " : (int) Math.round(position(count)) + 1 + " / " + count);
    }

    // ---- timeline scrubber / morph animation ----

    /**
     * Global timeline position of the scrubber: 0 = oldest snapshot, n-1 = newest. On the (rounded) value of a
     * snapshot it is exactly that snapshot's index, so the live graph then shows exactly that snapshot.
     */
    private double position(int n) {
        if (n < 2) return Math.max(0, n - 1);
        int value = scrubSlider.getValue();
        double g = value / (double) SCRUB_MAX * (n - 1);
        int k = (int) Math.round(g);
        return sliderValue(k, n) == value ? k : g;
    }

    /** Scrubber value of snapshot {@code index} of {@code n}. */
    private static int sliderValue(int index, int n) {
        return n < 2 ? SCRUB_MAX : (int) Math.round(index * (double) SCRUB_MAX / (n - 1));
    }

    /** Puts the scrubber on snapshot {@code index} without a morph frame: the live graph is already there. */
    private void showSliderAt(int index) {
        showSliderValue(sliderValue(index, history.getAll().size()));
    }

    /** Puts the scrubber on {@code value} without a morph frame. */
    private void showSliderValue(int value) {
        movingSliderQuietly = true;
        try {
            scrubSlider.setValue(value);
        } finally {
            movingSliderQuietly = false;
        }
        updateTimelineControls();
    }

    /** Slider moved: map its value to a global timeline position, morph the graph and keep it in view. */
    private void onScrub() {
        updateTimelineControls();
        if (movingSliderQuietly) return;
        // Grabbing the scrubber takes over from a running Play or step.
        if (scrubSlider.getValueIsAdjusting() && isTimelineRunning()) {
            stopPlay();
            stopStep();
        }
        Graph live = activeGraph();
        if (live == null) return;
        List<Snapshot> s = history.getAll();
        int n = s.size();
        if (n < 2) return;
        scrubTo(live, s, position(n));
        followTimeline(live, s);
    }

    /**
     * Morph the live graph to global timeline position {@code g} (0 = oldest, n-1 = newest).
     * Shared nodes glide between the two surrounding snapshots; the topology snaps to the nearest
     * snapshot so deleted/added nodes (re)appear as the slider crosses snapshot boundaries.
     */
    private void scrubTo(Graph live, List<Snapshot> s, double g) {
        int n = s.size();
        int i = (int) Math.floor(g);
        if (i > n - 2) i = n - 2;
        if (i < 0) i = 0;
        double t = g - i;
        int nearest = (t < 0.5) ? i : i + 1;

        double ox0 = timeline.offsetX, oy0 = timeline.offsetY;
        // A captured state may reach negative coordinates: grow the offset; the restore below then rewrites every
        // node and edge with it.
        if (growOffset(GraphBounds.union(boxesInFrame(s)))) lastScrubIndex = -1;
        double ox = timeline.offsetX, oy = timeline.offsetY;
        Map<String, Point2D> shown = new HashMap<>();
        forgetLastRun(timeline, RESTORED);
        scrubbing = true;
        suppressLive = true;
        try {
            if (nearest != lastScrubIndex) {
                s.get(nearest).restore(live, ox, oy);
                dropSbgnLayoutOf(live);
                lastScrubIndex = nearest;
            }
            Map<String, Point2D> a = s.get(i).positionsByUid();
            Map<String, Point2D> b = s.get(i + 1).positionsByUid();
            for (Node l : live.getNodes()) {
                String uid = Snapshot.uidOf(l);
                Point2D pa = a.get(uid);
                Point2D pb = b.get(uid);
                Point2D p;
                if (pa != null && pb != null) {
                    // On a snapshot exactly its position: a + (b - a) * 1 is not always b in floating point.
                    p = t == 0 ? pa : t == 1 ? pb
                            : new Point2D.Double(pa.getX() + (pb.getX() - pa.getX()) * t, pa.getY() + (pb.getY() - pa.getY()) * t);
                } else {
                    p = pa != null ? pa : pb;
                }
                if (p == null) continue;
                AttributeHelper.setPosition(l, p.getX() + ox, p.getY() + oy);
                shown.put(uid, p);
            }
            timeline.shown = shown;
        } finally {
            suppressLive = false;
            scrubbing = false;
        }
        if (ox != ox0 || oy != oy0) {
            camera.compensateShift(live, ox - ox0, oy - oy0);
            if (runContent != null) {
                runContent = new Rectangle2D.Double(runContent.getX() + ox - ox0, runContent.getY() + oy - oy0,
                        runContent.getWidth(), runContent.getHeight());
            }
        }
        DisplayConstraints.revalidateIndication();
    }

    /**
     * Keep the graph in view after a morph frame. While Play or a step runs, the camera holds {@link #runContent} if
     * there is one; otherwise it keeps the snapshots around the scrubber in view (while running, looking a little
     * ahead). Zooming in is left for when the timeline rests, so the zoom does not pump during a replay.
     */
    private void followTimeline(Graph live, List<Snapshot> s) {
        int n = s.size();
        boolean running = isTimelineRunning();
        Rectangle2D content = running ? runContent : null;
        if (content == null) {
            double g = position(n);
            // Look ahead in the direction the timeline runs: forward while playing, towards the target of a step.
            int direction = isPlaying() ? 1 : isStepping() ? (int) Math.signum(stepTarget - g) : 0;
            double ahead = g + direction * CAMERA_LOOKAHEAD;
            int lo = Math.max(0, (int) Math.floor(Math.min(g, ahead)));
            int hi = Math.min(n - 1, (int) Math.ceil(Math.max(g, ahead)));
            content = GraphBounds.union(boxes(s, lo, hi));
        }
        camera.ensureScrollable(live, content);
        camera.keepInView(live, content, isPlaying() ? TimelineCamera.Pace.PLAY : TimelineCamera.Pace.STEP,
                !running && !scrubSlider.getValueIsAdjusting());
    }

    /** Extents of all snapshots in the frame of the timeline. */
    private static List<Rectangle2D> boxesInFrame(List<Snapshot> s) {
        List<Rectangle2D> result = new ArrayList<>();
        for (Snapshot snapshot : s) result.add(snapshot.bounds());
        return result;
    }

    /** Extents of the snapshots {@code lo} to {@code hi}, as the live graph shows them. */
    private List<Rectangle2D> boxes(List<Snapshot> s, int lo, int hi) {
        List<Rectangle2D> result = new ArrayList<>();
        for (int i = Math.max(0, lo); i <= hi && i < s.size(); i++) result.add(shown(s.get(i).bounds()));
        return result;
    }

    private boolean isPlaying() {
        return playTimer != null && playTimer.isRunning();
    }

    private boolean isStepping() {
        return stepTimer != null && stepTimer.isRunning();
    }

    /** Play or a step runs: the camera is busy, and zooming or panning by hand switches it off. */
    private boolean isTimelineRunning() {
        return isPlaying() || isStepping();
    }

    /** Start/stop the play animation that sweeps the scrubber across the whole timeline. */
    private void togglePlay() {
        if (isPlaying()) {
            stopPlay();
            restTimeline();
            return;
        }
        List<Snapshot> all = history.getAll();
        int n = all.size();
        if (n < 2) return;
        Graph live = activeGraph();
        if (live == null) return;
        stopStep();
        camera.takeOver(live);   // a camera change from before the replay is no interruption of it
        boolean fromStart = scrubSlider.getValue() >= scrubSlider.getMaximum();
        int from = fromStart ? 0 : (int) Math.floor(position(n));
        // One still camera for the whole replay if that keeps the graph large enough; see TimelineCamera.stillFrame.
        runContent = camera.stillFrame(live, boxes(all, from, n - 1));
        playButton.setText("‖ Pause");
        // Aim at about 1.5 s per transition (50 frames of 30 ms), and at least 80 frames for the whole replay.
        final int transitionCount = n - 1;
        final int frames = Math.max(80, transitionCount * 50);
        final int increment = Math.max(1, scrubSlider.getMaximum() / frames);
        playTimer = new Timer(30, e -> {
            int v = scrubSlider.getValue() + increment;
            if (v >= scrubSlider.getMaximum()) {
                scrubSlider.setValue(scrubSlider.getMaximum());
                stopPlay();
                status("Replay complete", StatusLabel.Kind.SUCCESS);
                restTimeline();
            } else {
                scrubSlider.setValue(v);
            }
        });
        playTimer.start();
        if (fromStart) scrubSlider.setValue(0);   // replay from the beginning; the first frame also moves the camera
        else followTimeline(live, all);
        updateTimelineControls();
    }

    /**
     * Glide to the neighbouring snapshot in {@code direction} (-1 back, +1 next). Repeated clicks during a step
     * continue from the snapshot the running step heads for.
     */
    private void step(int direction) {
        List<Snapshot> all = history.getAll();
        int n = all.size();
        Graph live = activeGraph();
        if (n < 2 || live == null) return;
        stopPlay();
        // The neighbour of the snapshot a running step heads for, or of the scrubber, by slider value: a snapshot
        // sits on a rounded value, so a position read back from the slider is off by up to half a unit.
        int value = stepTarget >= 0 ? sliderValue(stepTarget, n) : scrubSlider.getValue();
        int target = direction > 0 ? n - 1 : 0;
        for (int k = 0; k < n; k++) {
            int v = sliderValue(k, n);
            if (direction > 0 && v > value) { target = k; break; }
            if (direction < 0 && v < value) target = k;
        }
        final int from = scrubSlider.getValue(), to = sliderValue(target, n);
        stopStep();
        if (from == to) return;
        camera.takeOver(live);
        double g0 = position(n);
        runContent = camera.stillFrame(live, boxes(all, (int) Math.floor(Math.min(g0, target)), (int) Math.ceil(Math.max(g0, target))));
        stepTarget = target;
        final double duration = Math.max(STEP_MS_MIN, Math.min(STEP_MS_MAX, Math.abs(target - g0) * STEP_MS_PER_SNAPSHOT));
        final long start = System.nanoTime();
        stepTimer = new Timer(15, e -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1e6 / duration);
            double eased = 0.5 - 0.5 * Math.cos(Math.PI * t);   // ease in and out
            scrubSlider.setValue((int) Math.round(from + (to - from) * eased));
            if (t >= 1) {
                stopStep();
                restTimeline();
            }
        });
        stepTimer.start();
        followTimeline(live, all);   // the camera starts to move with the first frame
        updateTimelineControls();
    }

    /** The timeline came to rest (end of Play or of a step, Pause): a last camera update that may also zoom in. */
    private void restTimeline() {
        runContent = null;
        updateTimelineControls();
        Graph live = activeGraph();
        List<Snapshot> all = history.getAll();
        if (live != null && all.size() >= 2) followTimeline(live, all);
    }

    private void stopPlay() {
        if (playTimer != null && playTimer.isRunning()) {
            playTimer.stop();
            playButton.setText("▶ Play");
        }
        runContent = null;
        updateTimelineControls();
    }

    private void stopStep() {
        if (stepTimer != null) stepTimer.stop();
        stepTarget = -1;
        runContent = null;
    }

    /** Stops Play and a running step without a final camera update (a run, restore or capture takes over). */
    private void stopTimeline() {
        stopPlay();
        stopStep();
        updateTimelineControls();
    }

    // ---- keep graph in view ----

    private void onKeepInViewToggled() {
        boolean on = keepInViewSwitch.isSelected();
        ConstraintsTab.showState(keepInViewState, on);
        camera.setEnabled(on);
        if (!on) return;
        Graph live = activeGraph();
        if (live == null) return;
        if (isTimelineRunning()) {
            camera.keepInView(live, runContent != null ? runContent : GraphBounds.of(live), TimelineCamera.Pace.STEP, false);
        } else {
            camera.frame(live, GraphBounds.of(live));
        }
    }

    /** The user zoomed or panned while the camera was moving the view: leave the view to the user. */
    private void onManualCameraChange() {
        keepInViewSwitch.setSelected(false);   // fires no action event
        ConstraintsTab.showState(keepInViewState, false);
        camera.setEnabled(false);
        status("View moved by hand: “Keep graph in view” is off", StatusLabel.Kind.INFO);
    }

    /** Name of a snapshot on its card and elsewhere: its label, or "Snapshot n" by its place in the history. */
    private String snapshotName(Snapshot s) {
        return snapshotName(s, history.getAll().indexOf(s));
    }

    private static String snapshotName(Snapshot s, int index) {
        if (s.getLabel() != null && !s.getLabel().isEmpty()) return s.getLabel();
        return index < 0 ? "Snapshot" : "Snapshot " + (index + 1);
    }

    /**
     * One card of the history: thumbnail, name and "ago · n nodes" (and a "Reference" badge on the pinned
     * snapshot, whose card is highlighted), with "Pin" and "Restore" underneath.
     */
    private JComponent buildSnapshotRow(final Snapshot s, int index) {
        final boolean isRef = (s == history.getReference());

        // Thumbnail and text side by side, the two buttons underneath, so the card fits the width of the column.
        CardPanel card = new CardPanel(new BorderLayout(0, Ui.GAP_M), CARD_PADDING);
        card.setHighlighted(isRef);

        JLabel thumbnail = new JLabel(s.getThumbnail(THUMB_WIDTH, THUMB_HEIGHT));
        thumbnail.setVerticalAlignment(SwingConstants.TOP);

        String name = snapshotName(s, index);
        JLabel title = new JLabel(Ui.wrapped(Ui.esc(name), CARD_TEXT_WIDTH));
        title.setFont(Ui.bold());
        VerticalStack text = new VerticalStack();
        text.addRow(title);
        text.addRow(Ui.hint(Ui.esc(s.ago() + " · " + s.getNodeCount() + " nodes"), CARD_TEXT_WIDTH), Ui.GAP_XS);
        if (isRef) {
            JPanel badgeRow = new JPanel(new BorderLayout());   // keeps the badge at its own width
            badgeRow.setOpaque(false);
            badgeRow.add(new Badge("Reference"), BorderLayout.WEST);
            text.addRow(badgeRow, Ui.GAP_S);
        }
        JPanel textColumn = new JPanel(new BorderLayout());
        textColumn.setOpaque(false);
        textColumn.add(text, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout(Ui.GAP_M, 0));
        top.setOpaque(false);
        top.add(thumbnail, BorderLayout.WEST);
        top.add(textColumn, BorderLayout.CENTER);
        card.add(top, BorderLayout.CENTER);

        JButton pin = Ui.button("Pin", "Pin this snapshot as the mental-map reference", e -> {
            history.setReference(s);
            refreshTimeline();
        });
        pin.setEnabled(!isRef);

        JButton restore = Ui.button("Restore",
                "Restore this snapshot on the graph: its node positions, and the nodes and edges it had.",
                e -> restoreSnapshot(s));

        card.add(Ui.row(pin, restore), BorderLayout.SOUTH);
        return card;
    }
}
