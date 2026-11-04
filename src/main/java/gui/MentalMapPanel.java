package gui;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import gui.components.PrimaryButton;
import gui.components.Section;
import gui.components.StatusLabel;
import gui.components.Ui;
import gui.components.VerticalStack;
import layout.mental_map.ComparisonLayoutRunner;
import layout.mental_map.DerivedConstraints;
import layout.mental_map.GraphBounds;
import layout.mental_map.MentalMapConstraints;
import layout.mental_map.NextState;
import layout.mental_map.Snapshot;
import layout.mental_map.SnapshotHistory;
import layout.sbgn.ConstraintSBGNLayout;
import layout.sbgn.SbgnPortSync;

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
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
    /** Size of the thumbnail of a row of the snapshot list. */
    private static final int THUMB_WIDTH = 40, THUMB_HEIGHT = 30;

    /** Resolution of the scrubber: its value runs from 0 (oldest snapshot) to this (newest). */
    private static final int SCRUB_MAX = 1000;
    /** Duration of a step to the neighbouring snapshot (ms); longer jumps take longer, up to {@link #STEP_MS_MAX}. */
    private static final int STEP_MS_PER_SNAPSHOT = 650, STEP_MS_MIN = 300, STEP_MS_MAX = 1400;
    /** Duration of Play per transition between two snapshots (ms), and at least for the whole timeline. */
    private static final int PLAY_MS_PER_TRANSITION = 1500, PLAY_MS_MIN = 2400;
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
         * The Mental Map constraints of the state each snapshot holds ({@link MentalMapConstraints}), as copies: putting
         * the snapshot on the graph puts them into the graph's store again, with the changes made to them meanwhile.
         */
        final Map<Snapshot, List<Constraint>> poolStates = new WeakHashMap<>();
        /** The snapshot whose state the Mental Map constraints in the store belong to; null: none (a live run, an edit). */
        Snapshot poolOwner;
        /** The file of the state the graph shows (loaded with "Load next state…" or opened); null: unknown. */
        File stateFile;
        /** Name of the state loaded last; the next layout run labels its snapshot with it. Null: none waiting. */
        String pendingState;
        /** The {@link #stateFile} of each snapshot, where known. */
        final Map<Snapshot, File> stateFiles = new WeakHashMap<>();
        /**
         * The snapshots that hold a loaded state no layout run has placed yet, with the name of the state: putting one
         * on the graph again makes it the {@link #pendingState} again.
         */
        final Map<Snapshot, String> pendingStates = new WeakHashMap<>();
        /** The graph shows a frame of the scrubber that nothing changed since (it may lie between two snapshots). */
        boolean showsFrame;

        /** Adds {@code s} to the history (see {@link SnapshotHistory#add(Snapshot, boolean)}) with the state file. */
        Snapshot add(Snapshot s, boolean makeReference) {
            return add(s, makeReference, null);
        }

        /** {@link #add(Snapshot, boolean)}; the cap of the history does not drop {@code keep} to make room. */
        Snapshot add(Snapshot s, boolean makeReference, Snapshot keep) {
            history.add(s, makeReference, keep);
            if (stateFile != null) stateFiles.put(s, stateFile);
            return s;
        }

        /**
         * Snapshot {@code s} was put on the graph: its state file is the graph's now, and the loaded state that waits
         * for a run is the one {@code s} holds, if any.
         */
        void restored(Snapshot s) {
            stateFile = stateFiles.get(s);
            pendingState = pendingStates.get(s);
            showsFrame = false;
        }
    }

    /**
     * One timeline per graph; {@link #sessionClosed} drops it. The weak keys are only a fallback: a timeline with
     * {@link GraphTimeline#poolStates} refers to live nodes, and so to its own key.
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
    /**
     * The layout method. Only the Prototype can run; the planned Improved method ({@link
     * ComparisonLayoutRunner.Choice#IMPROVED}) gets a choice next to the run button once it exists.
     */
    private final ComparisonLayoutRunner.Choice method = ComparisonLayoutRunner.Choice.PROTOTYPE;
    /** The primary action: makes the current layout the reference while there is none, then runs the layout. */
    private final PrimaryButton runButton;
    /** Makes the current layout the reference; shown once there is one (before, the primary action does it). */
    private final JButton setReferenceButton;
    private final StatusLabel runStatusLabel;
    /** Section "Timeline": scrubber, playback and the snapshots; shown from two snapshots on. */
    private final Section timelineSection;
    /** The snapshots, newest first; choosing one puts it on the graph. */
    private final DefaultListModel<Snapshot> snapshotModel = new DefaultListModel<>();
    /** The row pressed last was chosen already: a click on it puts it on the graph again (no selection event comes). */
    private boolean pressedChosenRow;
    private final JList<Snapshot> snapshotList = new JList<Snapshot>(snapshotModel) {
        private static final long serialVersionUID = 1L;
        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                int i = locationToIndex(e.getPoint());
                pressedChosenRow = i >= 0 && isSelectedIndex(i);
            }
            super.processMouseEvent(e);
        }
        @Override public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
            Snapshot s = snapshotModel.get(i);
            return Ui.tooltip("<b>" + Ui.esc(snapshotName(s)) + "</b>" + (s == history.getReference() ? " (reference)" : "")
                    + "<br>" + Ui.esc(s.ago() + " · " + Ui.count(s.getNodeCount(), "node"))
                    + "<br>Click to put it on the graph; right-click to make it the reference.");
        }
    };
    /** Makes the snapshot chosen in the list the reference. */
    private final JButton listReferenceButton;
    /** The scrubber and the row of steps: shown from two snapshots on (with one there is nothing to morph). */
    private JComponent scrubberRow, stepRow;
    /** The panel moves the list's selection itself (it follows the scrubber): that puts nothing on the graph. */
    private boolean syncingList;
    private final JCheckBox liveToggle;
    private Timer liveTimer;
    private boolean suppressLive = false;
    /** The live toggle's state for graph events of other threads, which may not ask the toggle itself. */
    private volatile boolean liveOn;
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
    private final JCheckBox keepInViewBox = new JCheckBox("Keep the graph in view", true);
    /** Keeps the "… ago" texts of the snapshot list and of the run button's tooltip current. */
    private final Timer agoTimer = new Timer(30_000, e -> {
        snapshotList.repaint();
        updateReferenceStatus();
    });

    /**
     * Builds the sections "Layout" and "Timeline" in a transparent column without an outer border (the tab's scrolling
     * column provides the padding) and takes over from a previous panel. The column shows what can be done next: at
     * first a single button that sets the reference; the timeline appears with the second snapshot.
     */
    public MentalMapPanel() {
        super(new BorderLayout());
        setOpaque(false);

        // --- Layout: the primary action, the other ways to set the reference or the state, the live toggle and the
        //     status. Status lines are clipped, hints wrap: nothing may widen the column.
        runButton = Ui.primaryButton("Set current layout as reference", null, e -> runPreserve(true));
        runButton.setName("mentalMapRun");

        setReferenceButton = Ui.button("Set current layout as reference",
                "Keep the current layout as a new snapshot and make it the reference the next runs keep.",
                e -> captureSnapshot(null));
        setReferenceButton.setName("mentalMapSetReference");

        JButton nextStateButton = Ui.button("Load next state…",
                "Turn the graph into the state in a graph file, e.g. the network of the next time point. Nodes are "
                        + "matched by their label: nodes and edges the file no longer has are removed, new ones are "
                        + "added with their style from the file, in a block right of the drawing. The current layout "
                        + "becomes the reference first, so Run layout places the new state relative to it.",
                e -> loadNextState());
        nextStateButton.setName("mentalMapNextState");

        liveToggle = new JCheckBox(Ui.wrapped("Run the layout after each edit", CHECK_TEXT_WIDTH));
        liveToggle.setOpaque(false);
        liveToggle.setName("mentalMapLive");
        liveToggle.setToolTipText(Ui.tooltip("Run the layout 0.4 s after an edit of the graph. "
                + "Moving nodes, the SBGN steps and the constraint re-solve after a move do not count as edits."));
        liveToggle.addActionListener(e -> onLiveToggled());

        runStatusLabel = new StatusLabel(" ");

        Section layoutSection = new Section("Layout");
        layoutSection.addRow(runButton);
        layoutSection.addRow(setReferenceButton);
        layoutSection.addRow(nextStateButton);
        layoutSection.addRow(liveToggle);
        layoutSection.addRow(runStatusLabel);

        // --- Timeline: the scrubber morphs the graph from the oldest (left) to the newest (right) snapshot; the list
        //     below holds the snapshots, newest first.
        scrubSlider = new JSlider(0, SCRUB_MAX, SCRUB_MAX);
        scrubSlider.setOpaque(false);
        scrubSlider.getAccessibleContext().setAccessibleName("Timeline position");
        scrubSlider.setToolTipText(Ui.tooltip("Drag to morph the graph from the oldest to the newest snapshot."));
        scrubSlider.addChangeListener(e -> onScrub());

        positionLabel = mutedSmall(" ");
        positionLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel scrubber = new JPanel(new BorderLayout());
        scrubber.setOpaque(false);
        scrubber.add(scrubSlider, BorderLayout.CENTER);
        scrubber.add(scrubEnds(positionLabel), BorderLayout.SOUTH);

        backButton = Ui.button("←", "Glide to the previous snapshot.", e -> step(-1));
        playButton = Ui.button("▶ Play", "Animate the morph from the scrubber's position to the newest snapshot; "
                + "from the oldest one when the scrubber is at the end.", e -> togglePlay());
        nextButton = Ui.button("→", "Glide to the next snapshot.", e -> step(1));
        playButton.setName("mentalMapPlay");
        backButton.setName("mentalMapBack");
        nextButton.setName("mentalMapNext");

        keepInViewBox.setName("keepGraphInView");
        keepInViewBox.setOpaque(false);
        keepInViewBox.setToolTipText(Ui.tooltip("While the timeline plays, steps or is dragged, and after a restore or "
                + "a layout run, the view zooms and pans smoothly just as far as needed to keep the whole graph visible. "
                + "Zooming or panning by hand while it moves the view turns this off."));
        keepInViewBox.addActionListener(e -> onKeepInViewToggled());

        snapshotList.setName("snapshotList");
        snapshotList.setCellRenderer(new SnapshotRenderer());
        snapshotList.setFixedCellHeight(THUMB_HEIGHT + 8);
        snapshotList.setFixedCellWidth(1);   // the list takes the visible width; long names are clipped
        snapshotList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        snapshotList.getAccessibleContext().setAccessibleName("Snapshots");
        ToolTipManager.sharedInstance().registerComponent(snapshotList);
        snapshotList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateListButtons();
            if (syncingList) return;
            Snapshot s = snapshotList.getSelectedValue();
            // later: putting the snapshot on the graph rebuilds the list
            if (s != null) SwingUtilities.invokeLater(() -> { if (history.getAll().contains(s)) restoreSnapshot(s); });
        });
        snapshotList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showSnapshotMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { showSnapshotMenu(e); }
            @Override public void mouseClicked(MouseEvent e) {
                // the chosen row once more (e.g. after an edit): no selection event comes
                Snapshot s = snapshotList.getSelectedValue();
                if (SwingUtilities.isLeftMouseButton(e) && pressedChosenRow && s != null) restoreSnapshot(s);
            }
        });
        JScrollPane snapshotScroll = new gui.components.NestedScrollPane(snapshotList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;
            @Override public Dimension getPreferredSize() { return new Dimension(40, super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };

        listReferenceButton = Ui.button("Set as reference", "Make the snapshot chosen in the list the reference the next "
                + "runs keep (right-clicking a snapshot does the same).", e -> setReference(snapshotList.getSelectedValue()));
        listReferenceButton.setName("mentalMapListReference");

        timelineSection = new Section("Timeline");
        scrubberRow = timelineSection.addRow(scrubber);
        stepRow = timelineSection.addRow(Ui.row(backButton, playButton, nextButton));
        timelineSection.addRow(keepInViewBox);
        timelineSection.addRow(snapshotScroll, Ui.GAP_M);
        timelineSection.addRow(listReferenceButton, Ui.GAP_S);

        VerticalStack sections = new VerticalStack();
        sections.addRow(layoutSection, 0);
        sections.addRow(timelineSection, Ui.SECTION_GAP);
        add(sections, BorderLayout.CENTER);

        refreshTimeline();
        agoTimer.start();

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
        agoTimer.stop();
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
        if (s == null || s.getGraph() == null) return;
        timelines.remove(s.getGraph());   // weak keys alone keep the last one
        comparisonLayouts.forget(s.getGraph());
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
        graphEdited();
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
        if (!SwingUtilities.isEventDispatchThread()) {
            // e.g. an algorithm run in the background: the panel's state belongs to the event thread, but only the
            // stack of this thread shows whether the change is layout output. The change belongs to the graph the panel
            // listens to now; if another graph is active by the time the event thread gets to it, it is not this one's.
            Graph edited = graph;
            boolean layoutOutput = liveOn && isLayoutOutput();   // the stack walk only matters for a live run
            SwingUtilities.invokeLater(() -> {
                if (edited == graph) graphEdited(layoutOutput);
            });
            return;
        }
        graphEdited(false);
    }

    /** {@link #graphEdited()} on the event thread; {@code layoutOutput}: the change came from layout code. */
    private void graphEdited(boolean layoutOutput) {
        if (!suppressLive && !scrubbing) {
            lastScrubIndex = -1;
            timeline.showsFrame = false;
        }
        if (!layoutOutput) scheduleLiveRelayout();
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
        if (live == null) {
            status("Open a graph first", StatusLabel.Kind.ERROR);
            return;
        }
        Snapshot ref = history.getReference();
        if (ref == null) {
            // Nothing pinned yet: capture the current layout as the first reference.
            captureSnapshot("Initial");
            return;
        }
        stopTimeline();   // a running morph would overwrite the result
        ComparisonLayoutRunner.Choice choice = method;
        // A live run follows the graph only if it was in view: the user may be working zoomed in on a detail.
        boolean follow = addToHistory || camera.shows(live, GraphBounds.of(live));
        GraphTimeline t = timeline;
        double ox = t.offsetX, oy = t.offsetY;
        suppressLive = true;
        long start = System.nanoTime();
        try {
            status("Running " + methodName(choice) + " …", StatusLabel.Kind.BUSY);
            // the run blocks the event thread: paint the status now, or it is replaced before anyone sees it
            if (addToHistory) runStatusLabel.paintImmediately(0, 0, runStatusLabel.getWidth(), runStatusLabel.getHeight());
            // The layout runs in the frame of the snapshots, without the display offset: the runner first puts every
            // node at its reference position (or next to its reference neighbours), so the offset does not reach it.
            GraphBounds.translate(live, -ox, -oy);
            boolean done = false;
            try {
                DerivedConstraints derived = comparisonLayouts.run(choice, live, ref);
                // The run drops every bend; SBGN-ED's port markers need their bend for an export as SBGN-ML.
                SbgnPortSync.apply(live);
                // what the run applied replaces the previous run's constraints in the graph's store
                setPoolConstraints(t, live, MentalMapConstraints.fromRun(derived, live));
                t.shown = positionsByUid(live);
                t.showsFrame = false;
                if (addToHistory) {
                    // Record the result, but keep the reference pinned so that repeated runs stay relative to it.
                    String label = t.pendingState == null ? "After " + methodName(choice)
                            : t.pendingState + " · " + methodName(choice);
                    t.pendingState = null;
                    Snapshot result = Snapshot.capture(live, label);
                    // a run that left the graph as the newest snapshot has it (e.g. Run layout clicked twice) adds
                    // no step without any change to the timeline
                    List<Snapshot> all = history.getAll();
                    if (all.isEmpty() || !all.get(all.size() - 1).sameState(result)) t.add(result, false);
                    recordPoolState(t, live, history.getAll().get(history.getAll().size() - 1));
                }
                growOffset(GraphBounds.union(history.bounds(), GraphBounds.of(live)));
                done = true;
            } finally {
                GraphBounds.translate(live, done ? t.offsetX : ox, done ? t.offsetY : oy);
            }
            camera.compensateShift(live, t.offsetX - ox, t.offsetY - oy);
            AddonTab.decideConstraints(live);   // the run's constraints are in the store now
            if (addToHistory) {
                refreshTimeline();
                showSliderAt(history.getAll().size() - 1);   // the live graph is the newest snapshot now
            }
            long millis = (System.nanoTime() - start) / 1_000_000L;
            status("Finished in " + millis + " ms", StatusLabel.Kind.SUCCESS);
        } catch (RuntimeException | LinkageError ex) {
            String why = ex.getMessage() == null || ex.getMessage().isEmpty() ? ex.getClass().getSimpleName() : ex.getMessage();
            status("Layout failed: " + why, StatusLabel.Kind.ERROR);
            JOptionPane.showMessageDialog(this,
                    "The selected layout could not be completed:\n" + why,
                    "Layout error", JOptionPane.ERROR_MESSAGE);
        } finally {
            suppressLive = false;
        }
        Rectangle2D bounds = GraphBounds.of(live);
        camera.ensureScrollable(live, bounds);
        if (follow) camera.keepInView(live, bounds, TimelineCamera.Pace.JUMP, true);
        DisplayConstraints.revalidateIndication();
        updateReferenceStatus();
    }

    // ---- Mental Map constraints in the graph's store ----

    /**
     * {@code made} replaces the Mental Map constraints in the store of {@code live} (a run): those there go, after the
     * snapshot they belong to took over their current state. Nothing moves; the caller decides the conflicts anew once the
     * graph is back in its display frame ({@link AddonTab#decideConstraints}).
     */
    private static void setPoolConstraints(GraphTimeline t, Graph live, List<Constraint> made) {
        Constraints store = ConstraintStore.of(live);
        List<Constraint> before = MentalMapConstraints.in(store);
        if (t.poolOwner != null) t.poolStates.put(t.poolOwner, MentalMapConstraints.copies(before, n -> n));
        MentalMapConstraints.removeFrom(store);
        for (Constraint c : made) store.addConstraint(c);
        t.poolOwner = null;
        if (!before.isEmpty()) ConflictLog.forget(before);
    }

    /** Snapshot {@code s} holds the state of {@code live} now: it keeps a copy of the Mental Map constraints in its store. */
    private static void recordPoolState(GraphTimeline t, Graph live, Snapshot s) {
        if (s == null) return;
        t.poolStates.put(s, MentalMapConstraints.copies(MentalMapConstraints.in(ConstraintStore.of(live)), n -> n));
        t.poolOwner = s;
    }

    /**
     * Before the timeline puts another state on {@code live}: the snapshot the Mental Map constraints in the store belong
     * to takes over their current state (switches, priorities and deletions included), and they leave the store.
     */
    private static void stashPoolState(GraphTimeline t, Graph live) {
        Constraints store = ConstraintStore.of(live);
        List<Constraint> current = MentalMapConstraints.in(store);
        if (t.poolOwner != null) t.poolStates.put(t.poolOwner, MentalMapConstraints.copies(current, n -> n));
        MentalMapConstraints.removeFrom(store);
        t.poolOwner = null;
        if (!current.isEmpty()) ConflictLog.forget(current);
    }

    /** After snapshot {@code s} was put on {@code live}: its Mental Map constraints go into the store, on the live nodes. */
    private static void unstashPoolState(GraphTimeline t, Graph live, Snapshot s) {
        List<Constraint> saved = t.poolStates.get(s);
        t.poolOwner = s;
        List<Constraint> back = Collections.emptyList();
        if (saved != null && !saved.isEmpty()) {
            Map<String, Node> byUid = new HashMap<>();
            for (Node n : live.getNodes()) byUid.put(Snapshot.uidOf(n), n);
            back = MentalMapConstraints.copies(saved, n -> byUid.get(Snapshot.uidOf(n)));
            Constraints store = ConstraintStore.of(live);
            for (Constraint c : back) store.addConstraint(c);
        }
        poolChanged(live, back);
    }

    /** The Mental Map constraints in the store of {@code live} changed: decide the conflicts anew and show the lists. */
    private static void poolChanged(Graph live, List<Constraint> touched) {
        if (touched.isEmpty() && MentalMapConstraints.in(ConstraintStore.of(live)).isEmpty()) return;
        AddonTab.decideConstraints(live);
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
        if (live == null || live.getNumberOfNodes() == 0) {
            status(live == null ? "Open a graph first" : "The graph has no nodes to capture", StatusLabel.Kind.ERROR);
            return;
        }
        stopTimeline();
        GraphTimeline t = timeline;
        // A fixed name ("Snapshot n" as its card would show it now): names by place would change once the cap drops
        // the oldest snapshots, while the Constraints tab keeps naming the reference of a run.
        if (label == null) label = "Snapshot " + (history.addedCount() + 1);
        // In the frame of the timeline; nodes nobody moved since the panel put them there get exactly that position.
        // A capture moves nothing (it would bypass VANTED's undo history), so the display offset is not grown here.
        Snapshot s = t.add(captureUnmodified(live, label, t), true);
        recordPoolState(t, live, s);
        t.shown = s.positionsByUid();
        status("“" + label + "” is the reference now", StatusLabel.Kind.SUCCESS);
        camera.ensureScrollable(live, GraphBounds.of(live));
        refreshTimeline();
        showSliderAt(history.getAll().size() - 1);
    }

    /**
     * "Load next state…": pin the current layout as the reference (the snapshot that already holds it, or a new
     * capture), then turn the graph into the state of a chosen graph file (see {@link NextState}). The next layout run
     * labels its snapshot with the state's name. On an empty graph the state is captured and pinned as it is. The file
     * chooser proposes the file after the current state's file.
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
        // An empty graph has no layout to place the state relative to: the state becomes the first reference.
        boolean first = live.getNumberOfNodes() == 0;
        if (!first) pinCurrentState(live, current == null ? null : stateName(current));
        suppressLive = true;
        NextState.Change change;
        try {
            change = NextState.apply(live, state, NextState.uidsByLabel(history.getAll()));
        } finally {
            suppressLive = false;
        }
        t.stateFile = file;
        t.pendingState = first ? null : stateName(file);
        t.showsFrame = false;
        lastScrubIndex = -1;   // the live topology is none of the snapshots now
        if (first) captureSnapshot(stateName(file));
        else t.poolOwner = null;   // the Mental Map constraints left in the store belong to the new state, no snapshot
        dropSbgnLayoutOf(live);
        Rectangle2D bounds = GraphBounds.of(live);
        camera.ensureScrollable(live, bounds);
        camera.keepInView(live, bounds, TimelineCamera.Pace.JUMP, true);
        DisplayConstraints.revalidateIndication();
        if (first) {
            status("Loaded " + stateName(file) + " as the first state: " + change, StatusLabel.Kind.SUCCESS);
            return;
        }
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

    /** The file after {@code f} in its folder (same extension, by name with numbers in order: T2 before T10), or null. */
    private static File fileAfter(File f) {
        if (f == null || f.getParentFile() == null) return null;
        String ext = extensionOf(f.getName());
        File[] siblings = f.getParentFile().listFiles(x -> x.isFile() && !x.isHidden()
                && extensionOf(x.getName()).equalsIgnoreCase(ext));
        if (siblings == null) return null;
        Arrays.sort(siblings, (a, b) -> compareNames(a.getName(), b.getName()));
        for (File s : siblings) if (compareNames(s.getName(), f.getName()) > 0) return s;
        return null;
    }

    /**
     * Compares file names with every run of digits taken as a number, so that "T2" comes before "T10" (leading zeros
     * do not count: "T02" and "T2" differ only by the final tie break on the plain names).
     */
    static int compareNames(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = stripZeros(a.substring(si, i)), nb = stripZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        int rest = (a.length() - i) - (b.length() - j);
        return rest != 0 ? rest : a.compareTo(b);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
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
        Snapshot s = history.add(captureUnmodified(loaded, "On graph load", timeline));
        recordPoolState(timeline, loaded, s);
        timeline.shown = s.positionsByUid();
        refreshTimeline();
    }

    /**
     * Captures {@code g} in the frame of {@code t}. The uids a capture gives the nodes ({@link Snapshot#ensureUid}) are
     * no change of the map: a graph the user only looked at or captured does not ask to be saved afterwards.
     */
    private static Snapshot captureUnmodified(Graph g, String label, GraphTimeline t) {
        boolean wasModified = g.isModified();
        try {
            return Snapshot.capture(g, label, t.offsetX, t.offsetY, t.shown);
        } finally {
            g.setModified(wasModified);
        }
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
        keepLiveState(s);   // the snapshot being restored stays in the history, however full it is
        double ox = timeline.offsetX, oy = timeline.offsetY;
        growOffset(history.bounds());   // e.g. a captured state that reached negative coordinates
        // the Mental Map constraints of the state shown go first, so that the restore's deletions leave them as they are
        stashPoolState(timeline, live);
        suppressLive = true;
        try {
            s.restore(live, timeline.offsetX, timeline.offsetY);
            timeline.shown = s.positionsByUid();
            timeline.restored(s);
        } finally {
            suppressLive = false;
        }
        unstashPoolState(timeline, live, s);
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

    /**
     * Before the timeline puts a snapshot on the graph: the state the graph shows becomes the newest snapshot (the
     * reference stays pinned) unless a snapshot holds it or it is an unchanged frame of the scrubber, so that edits,
     * the result of a live run and a loaded state are not lost. The cap of the history does not drop {@code keep} (the
     * snapshot about to be restored; may be null) to make room. Returns whether a snapshot was added.
     */
    private boolean keepLiveState(Snapshot keep) {
        Graph live = activeGraph();
        GraphTimeline t = timeline;
        if (live == null || live.getNumberOfNodes() == 0 || history.isEmpty()) return false;
        if (t.showsFrame && showsTimelineFrame(live, t)) return false;
        // named for what it is: the edited state, kept so that moving through the timeline loses nothing
        String label = t.pendingState != null ? t.pendingState : "Edited state " + (history.addedCount() + 1);
        Snapshot now = Snapshot.capture(live, label, t.offsetX, t.offsetY, t.shown);
        for (Snapshot s : history.getAll()) {
            if (s.sameState(now)) return false;
        }
        t.add(now, false, keep);
        recordPoolState(t, live, now);
        // a loaded state that no run has placed yet: putting this snapshot on the graph again lets the next run name it
        if (t.pendingState != null) t.pendingStates.put(now, t.pendingState);
        t.shown = now.positionsByUid();
        refreshTimeline();
        showSliderAt(history.getAll().size() - 1);
        return true;
    }

    /** Every node of {@code live} stands where the timeline last put it ({@link GraphTimeline#shown}). */
    private static boolean showsTimelineFrame(Graph live, GraphTimeline t) {
        if (live.getNumberOfNodes() != t.shown.size()) return false;
        for (Node n : live.getNodes()) {
            Point2D p = t.shown.get(Snapshot.uidOf(n));
            if (p == null || AttributeHelper.getPositionX(n) != p.getX() + t.offsetX
                    || AttributeHelper.getPositionY(n) != p.getY() + t.offsetY) return false;
        }
        return true;
    }

    /** Live mode needs a reference taken before the first edit; switching it on counts as using the panel. */
    private void onLiveToggled() {
        liveOn = liveToggle.isSelected();
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
        restartLiveTimer();
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

    /**
     * The controls that depend on the reference: the primary button sets it while there is none and runs the layout
     * relative to it afterwards; "Set current layout as reference" appears once there is one.
     */
    private void updateReferenceStatus() {
        if (runButton == null) return;
        Snapshot ref = history.getReference();
        runButton.setText(ref == null ? "Set current layout as reference" : "Run layout");
        runButton.setToolTipText(Ui.tooltip(ref == null
                ? "Keep the current layout as the reference: the runs after it keep its left-right and top-bottom order "
                        + "and its neighbourhood groups."
                : "Lay the graph out relative to the reference <b>" + Ui.esc(snapshotName(ref)) + "</b> ("
                        + Ui.esc(Ui.count(ref.getNodeCount(), "node") + ", " + ref.ago()) + ") with the Prototype, the "
                        + "mental-map method of the thesis extended for sequences of states: hard ordering and cluster "
                        + "constraints derived from the reference. A changed graph is laid out with a constraint-based "
                        + "force-directed layout; an unchanged graph is only made to satisfy the constraints. New nodes "
                        + "start next to their neighbours, and when the graph shrinks the freed space is given back. The "
                        + "constraints a run applies are listed, read only, under Constraints › Mental map."));
        runButton.repaint();
        setReferenceButton.setVisible(ref != null);
        updateListButtons();
    }

    /**
     * Rebuild the snapshot list from the history (newest first) and update the timeline controls. The timeline shows
     * from two snapshots on: with fewer there is nothing to move between.
     */
    private void refreshTimeline() {
        if (runButton == null) return;
        lastScrubIndex = -1; // history changed → scrubber must re-sync topology on next move
        List<Snapshot> all = history.getAll();
        syncingList = true;
        try {
            snapshotModel.clear();
            for (int i = all.size() - 1; i >= 0; i--) snapshotModel.addElement(all.get(i));
        } finally {
            syncingList = false;
        }
        snapshotList.setVisibleRowCount(Math.max(1, Math.min(6, all.size())));
        // from the first snapshot on: the list puts it back on the graph; morphing needs a second one
        boolean morph = all.size() >= 2;
        timelineSection.setVisible(!all.isEmpty());
        scrubberRow.setVisible(morph);
        stepRow.setVisible(morph);
        keepInViewBox.setVisible(morph);
        listReferenceButton.setVisible(morph);
        updateTimelineControls();
        updateReferenceStatus();
        revalidate();
        repaint();
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
        syncListSelection(count);
    }

    /** The list chooses the snapshot the scrubber is on or nearest to; that puts nothing on the graph. */
    private void syncListSelection(int n) {
        if (n == 0 || snapshotModel.size() != n) return;
        int row = n - 1 - (int) Math.round(position(n));   // the list is newest first
        if (row < 0 || row >= n || snapshotList.getSelectedIndex() == row) return;
        syncingList = true;
        try {
            snapshotList.setSelectedIndex(row);
            snapshotList.ensureIndexIsVisible(row);
        } finally {
            syncingList = false;
        }
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
        if (!isTimelineRunning()) {
            // The scrubber was moved by hand: an edited state becomes the newest snapshot first (which puts the
            // scrubber on it). The position the user chose keeps its place among the snapshots there were before.
            int value = scrubSlider.getValue(), before = history.getAll().size();
            if (keepLiveState(null) && before >= 2) {
                int after = history.getAll().size(), dropped = before + 1 - after;   // the cap may drop the oldest
                double g = Math.max(0, value / (double) SCRUB_MAX * (before - 1) - dropped);
                showSliderValue((int) Math.round(g / (after - 1) * SCRUB_MAX));
            }
        }
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
        scrubbing = true;
        suppressLive = true;
        try {
            if (nearest != lastScrubIndex) {
                // the topology is that of the nearest snapshot, and so are the Mental Map constraints in the store
                stashPoolState(timeline, live);
                s.get(nearest).restore(live, ox, oy);
                timeline.restored(s.get(nearest));
                unstashPoolState(timeline, live, s.get(nearest));
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
            timeline.showsFrame = true;
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
        keepLiveState(null);
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
        // By elapsed time, as a step: the timer coalesces late ticks, and whole slider units per tick round the speed.
        final int startValue = fromStart ? 0 : scrubSlider.getValue();
        final double duration = Math.max(PLAY_MS_MIN, (n - 1) * PLAY_MS_PER_TRANSITION)
                * (SCRUB_MAX - startValue) / (double) SCRUB_MAX;
        final long start = System.nanoTime();
        playTimer = new Timer(30, e -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1e6 / duration);
            int v = startValue + (int) Math.round((SCRUB_MAX - startValue) * t);
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
        keepLiveState(null);
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
        boolean on = keepInViewBox.isSelected();
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
        keepInViewBox.setSelected(false);   // fires no action event
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

    /** Makes {@code s} the reference the next runs keep. */
    private void setReference(Snapshot s) {
        if (s == null || !history.getAll().contains(s)) return;
        history.setReference(s);
        refreshTimeline();
        status("“" + snapshotName(s) + "” is the reference now", StatusLabel.Kind.SUCCESS);
    }

    /** "Set as reference" acts on the snapshot chosen in the list, unless it is the reference already. */
    private void updateListButtons() {
        if (listReferenceButton == null) return;
        Snapshot s = snapshotList.getSelectedValue();
        listReferenceButton.setEnabled(s != null && s != history.getReference());
    }

    /** The context menu of a snapshot: put it on the graph, make it the reference. */
    private void showSnapshotMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int i = snapshotList.locationToIndex(e.getPoint());
        if (i < 0 || !snapshotList.getCellBounds(i, i).contains(e.getPoint())) return;
        Snapshot s = snapshotModel.get(i);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem restore = new JMenuItem("Put on the graph");
        restore.addActionListener(a -> restoreSnapshot(s));
        JMenuItem reference = new JMenuItem("Set as reference");
        reference.setEnabled(s != history.getReference());
        reference.addActionListener(a -> setReference(s));
        menu.add(restore);
        menu.add(reference);
        menu.show(snapshotList, e.getX(), e.getY());
    }

    /** A row of the snapshot list: thumbnail, name, "ago · n nodes", and "Reference" on the reference. */
    private final class SnapshotRenderer extends JPanel implements ListCellRenderer<Snapshot> {
        private static final long serialVersionUID = 1L;
        private final JLabel thumbnail = new JLabel(), name = new JLabel(), meta = new JLabel(), mark = new JLabel();

        SnapshotRenderer() {
            super(new BorderLayout(Ui.GAP_M, 0));
            setBorder(new EmptyBorder(3, 4, 3, 6));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            name.setFont(Ui.bold().deriveFont(Ui.small().getSize2D() + 1));
            meta.setFont(Ui.small());
            mark.setFont(Ui.small().deriveFont(Font.BOLD));
            text.add(name);
            text.add(meta);
            add(thumbnail, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(mark, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Snapshot> l, Snapshot s, int index, boolean selected, boolean focus) {
            boolean ref = s == history.getReference();
            thumbnail.setIcon(s.getThumbnail(THUMB_WIDTH, THUMB_HEIGHT));
            name.setText(snapshotName(s));
            meta.setText(s.ago() + " · " + Ui.count(s.getNodeCount(), "node"));
            mark.setText(ref ? "Reference" : "");
            setBackground(selected ? l.getSelectionBackground() : index % 2 == 0 ? l.getBackground() : Ui.SURFACE_HOVER);
            name.setForeground(selected ? l.getSelectionForeground() : Ui.TEXT);
            meta.setForeground(selected ? l.getSelectionForeground() : Ui.MUTED);
            mark.setForeground(selected ? l.getSelectionForeground() : Ui.ACCENT);
            getAccessibleContext().setAccessibleName(name.getText() + (ref ? ", reference" : "") + ", " + meta.getText());
            setOpaque(true);
            return this;
        }
    }
}
