package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintFeasibility;
import constraints.ConstraintGroup;
import constraints.ConstraintStore;
import constraints.DistributionConstraintGraph;
import constraints.HorizontalConstraint;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;
import layout.steps.LayoutStep;
import layout.steps.StepLayout;

import org.AttributeHelper;
import org.graffiti.attributes.Attributable;
import org.graffiti.event.ListenerManager;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.EdgeGraphicAttribute;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.managers.AttributeComponentManager;
import org.graffiti.plugin.view.View2D;
import org.sbgn.Language;
import java.awt.geom.Point2D;
import java.util.*;

/**
 * The constraint-based SBGN layout as a {@link StepLayout}. PD maps run the skeleton, individual, star, stub and
 * routing steps defined here; ER and AF maps run the steps of {@link ERHyperedgeLayout}, {@link ERLayout} or
 * {@link AFLayout}, which use the preparation and routing helpers of this class.
 */
public class ConstraintSBGNLayout extends StepLayout {

    public Graph skeleton;
    public Map<Node, Node> skeletonNodeLookup;
    public ArrayList<ProcessComponent> processComponents;
    public Language instanceLanguage;
    public Map<Node, Offset> childOffset = new HashMap<>();
    private final Map<Node, TagBinding> tagBindings = new HashMap<>();
    public static View2D view2D;
    public static ArrayList<Edge> reversibleRHS = new ArrayList<>();
    public static Map<Edge, String> orientationREV = new HashMap<>();
    public static Map<Edge, String> orientationProdSub = new HashMap<>();
    public static Map<Edge, String> orientationStim = new HashMap<>();
    /**
     * AF pipeline ({@link AFLayout}): edge -> {side at the source, side at the target} of a logical operator port
     * ("up", "down", "left", "right"; null where the end is no operator). The router puts a stub there.
     */
    public static Map<Edge, String[]> orientationLogic = new HashMap<>();
    /**
     * ER hyperedge pipeline ({@link ERHyperedgeLayout}): puts back what its step 1 removed; run by
     * {@link #resetToOriginal()}.
     */
    private Runnable erReset;
    /** skeletonShape=topology: the plan that owns the skeleton directions throughout the PD pipeline. */
    private TopologySkeleton topologyPlan;
    private final Map<Edge, TopologyReversibleNormalizer.State> topologyReversibleChanges = new LinkedHashMap<>();
    /** Per-edge {source, target} docking captured before step 1 removes it, so reset can undo it. */
    private final Map<Edge, String[]> originalDocking = new HashMap<>();
    /** Compartment originals this layout detached in step 1; re-added on reset. */
    private final List<Node> deletedCompartments = new ArrayList<>();
    /** Member-fitted compartment copies the last step re-inserted; dropped on reset to avoid duplicates. */
    private final List<Node> restoredCompartments = new ArrayList<>();
    /** Every compartment copy the last step made since the last reset -&gt; the original it stands for. */
    private final Map<Node, Node> originalOf = new HashMap<>();
    /** The port points {@link #afterStep} inserted into the arcs, so the next step starts without them. */
    private final Map<Edge, List<org.Vector2d>> stepPorts = new HashMap<>();

    /** Node pairs whose relative position an embedded cycle rectangle already fixes (both directions). */
    private final Map<Node, Set<Node>> cycleFixed = new HashMap<>();
    /** Which sides of a node an embedded cycle rectangle already runs along, so a chain avoids them. */
    private final Map<Node, Set<String>> cycleSides = new HashMap<>();
    /** Lens processes (-Dcblayout.lens): the side facing the partner process, kept free of enzymes and stubs. */
    private final Map<Node, String> lensInner = new HashMap<>();
    /** Axis (true = horizontal) of every rectangle edge by {@link #pairKey}, read by -Dcblayout.cornerRule (lens edges are diagonal, not listed). */
    private final Map<String, Boolean> ringAxis = new HashMap<>();
    /** Lenses embedCycle drew, by {@link #pairKey} of their species, so -Dcblayout.bundles can turn one into a fan. */
    private final Map<String, RingLens> ringLenses = new HashMap<>();

    /** The two processes of a ring lens and the constraints that draw it. */
    private static final class RingLens {
        final Node pa, pb;
        final List<Constraint> constraints;
        RingLens(Node pa, Node pb, List<Constraint> constraints) { this.pa = pa; this.pb = pb; this.constraints = constraints; }
    }
    /** -Dcblayout.cycleGap=px (30): gap between two consecutive nodes of an embedded cycle, on top of their half extents. */
    private static final double CYCLE_GAP = doubleProperty("cblayout.cycleGap", 30);

    /** Position of a glyph inside a complex, relative to the complex centre. */
    static class Offset {
        final Node complex;
        final double dx, dy; // node - complex

        Offset(Node complex, double dx, double dy) {
            this.complex = complex;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** The submap a tag lies in, the tag's offset from it, and the tag edges moved onto the submap. */
    private static class TagBinding {
        final Node tag;
        final Node submap;
        final double dx, dy; // tag - submap (absolute pixel offset)
        final List<RewiredEdge> rewired = new ArrayList<>();

        TagBinding(Node tag, Node submap, double dx, double dy) {
            this.tag = tag;
            this.submap = submap;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static class RewiredEdge {
        final Edge edge;
        final Node origSource;
        final Node origTarget;

        RewiredEdge(Edge edge, Node s, Node t) {
            this.edge = edge;
            this.origSource = s;
            this.origTarget = t;
        }
    }


    public ConstraintSBGNLayout(Graph graph, Language language) {
        super(SbgnStepAlgorithm.ID, graph);
        this.instanceLanguage = (language != null) ? language : detectLanguage(graph);
        buildSteps();
    }

    /**
     * Best-effort detection of the SBGN language from graph attributes. Falls back to PD.
     */
    public static Language detectLanguage(Graph g) {
        try {
            String role = (String) AttributeHelper.getAttributeValue(g, "sbgn", "role", "", "", false);
            if (role != null) {
                String r = role.toLowerCase();
                if (r.contains("activity") || r.contains("af")) return Language.AF;
                if (r.contains("entity") || r.contains("er")) return Language.ER;
            }
        } catch (Exception ignored) { }
        return Language.PD;
    }

    @Override
    public String getName() {
        return "SBGN " + (instanceLanguage == null ? "PD" : instanceLanguage.name()) + " layout";
    }

    @Override
    protected String phaseName() {
        return "SBGN";
    }

    /** The nodes the SBGN solves move: the scope the user's constraints are decided in. */
    @Override
    protected Collection<Node> solvedNodes() {
        return layoutNodes(graph);
    }

    /** The nodes the solves hold out of a user's cluster: no compartments, no glyphs drawn inside a complex. */
    @Override
    protected Collection<Node> checkedNodes() {
        return CBLayoutAlgorithm.userSolveNodes(graph);
    }

    /**
     * Which of the user's constraints the SBGN solves apply. With {@code -Dcblayout.sbgnUserTypes=all} every kind
     * (CBLayoutAlgorithm translates them as the user solve does); with {@code hvsd} only alignments, gaps and, in the
     * PD steps, equal spacing.
     */
    @Override
    protected boolean appliesUserConstraint(Constraint c) {
        if (CBLayoutAlgorithm.USER_TYPES_ALL) return true;
        return c instanceof HorizontalConstraint || c instanceof VerticalConstraint || c instanceof SeparationConstraintGraph
                || (appliesDistributions() && c instanceof DistributionConstraintGraph);
    }

    /**
     * sbgnUserTypes=all: the SBGN solves remove overlaps and hold the other nodes out of the user's clusters, so the
     * decision models both (the rectangle padding of the solver plus the 1 px of its non-overlap); hvsd: the base
     * scope.
     */
    @Override
    protected ConstraintConflicts.Scope resolveScope() {
        if (!CBLayoutAlgorithm.USER_TYPES_ALL) return super.resolveScope();
        return new ConstraintConflicts.Scope(layoutNodes(graph), graph, CBLayoutAlgorithm.bboxConst + 1, true, true);
    }

    /** With {@code sbgnUserTypes=hvsd} only the kinds the solves do not apply are reported after a step. */
    @Override
    protected boolean reportsAppliedUserConstraints() {
        return CBLayoutAlgorithm.USER_TYPES_ALL;
    }

    /**
     * True if the steps of this run apply equal spacing (the PD steps do; the AF and ER pipelines solve with
     * CBLayoutAlgorithm.solveNodes, which applies alignments and gaps only).
     */
    private boolean appliesDistributions() {
        Language lang = instanceLanguage != null ? instanceLanguage : Language.PD;
        return !(lang == Language.ER && ERLayout.ENABLED) && !(lang == Language.AF && AFLayout.ENABLED);
    }

    /** After the whole run: the port markers follow the arcs (SBGN-ED can save the map), then the user's constraints are measured. */
    @Override
    protected void afterRun() {
        SbgnPortSync.apply(graph);
        reportUserConstraints();
    }

    /**
     * After a single step: as after the whole run (SBGN-ED can save the map after any step). The port points this
     * inserts go again before the next step: the AF and ER solves keep the bends, and the next sync would leave them
     * behind where the nodes were.
     */
    @Override
    protected void afterStep(int index, List<Constraint> created) {
        SbgnPortSync.apply(graph, stepPorts);
        reportUserConstraints();
    }

    /** Before the steps: the port points of the last single step go ({@link #afterStep}), then as the base class. */
    @Override
    protected void beforeRun() {
        dropStepPorts();
        super.beforeRun();
    }

    /** Takes the port points {@link #afterStep} inserted out of the arcs again, where they still are. */
    private void dropStepPorts() {
        for (Map.Entry<Edge, List<org.Vector2d>> en : stepPorts.entrySet()) {
            Edge e = en.getKey();
            if (!graph.containsEdge(e)) continue;
            ArrayList<org.Vector2d> bends = AttributeHelper.getEdgeBends(e);
            List<org.Vector2d> kept = new ArrayList<>(bends);
            for (org.Vector2d port : en.getValue()) {
                for (Iterator<org.Vector2d> it = kept.iterator(); it.hasNext(); ) {
                    org.Vector2d b = it.next();
                    if (b.x == port.x && b.y == port.y) {
                        it.remove();
                        break;
                    }
                }
            }
            if (kept.size() == bends.size()) continue;
            AttributeHelper.removeEdgeBends(e);
            if (!kept.isEmpty()) AttributeHelper.addEdgeBends(e, kept);
        }
        stepPorts.clear();
    }

    /**
     * Reverses the structural changes of the steps before the base class restores the positions, so a reset after a
     * partial run leaves the graph intact: the compartment glyphs deleted in step 1 come back (without the
     * member-fitted copies of the last step), rerouted tag edges get their original endpoints, the reversible arcs
     * normalised for skeletonShape=topology and the edge docking are restored, and ER puts its removed nodes and
     * edges back.
     */
    @Override
    protected void beforeReset() {
        restoreDeletedCompartments();
        restoreRewiredTagEdges();
        tagBindings.clear();
        TopologyReversibleNormalizer.restore(topologyReversibleChanges);
        topologyReversibleChanges.clear();
        topologyPlan = null;
        restoreDocking();
        if (erReset != null) erReset.run();
    }

    /** After the base class restored positions, bends and the store: clear the orientation caches, put the port markers back. */
    @Override
    protected void afterReset() {
        clearOrientations();
        stepPorts.clear();
        SbgnPortSync.apply(graph);   // the bends are gone; put the port markers back on the straight arcs
    }

    /** Clears the stub sides and port hints the edge router reads. */
    private static void clearOrientations() {
        reversibleRHS.clear();
        orientationREV.clear();
        orientationProdSub.clear();
        orientationStim.clear();
        orientationLogic.clear();
    }

    /**
     * Re-insert the compartment glyphs that step 1 ({@link #deleteCompartmentNodes()}) detached.
     * Member-fitted copies the last step re-created are dropped first, so no compartment gets two
     * frames. The re-added glyphs keep their original geometry, styling and label because
     * {@code addNodeCopy} copies them straight from the still-detached original. The user's constraints on a
     * compartment move on to the glyph that comes back for it.
     */
    private void restoreDeletedCompartments() {
        for (Node copy : restoredCompartments) {
            try {
                if (graph.containsNode(copy)) {
                    holdUserConstraints(copy, c -> true);
                    graph.deleteNode(copy);
                }
            } catch (Exception ignored) { }
        }
        restoredCompartments.clear();
        for (Node original : deletedCompartments) {
            try {
                if (graph.containsNode(original)) continue;
                Node back = graph.addNodeCopy(original);
                releaseUserConstraints(original, back);
                for (Map.Entry<Node, Node> e : originalOf.entrySet()) {
                    if (e.getValue() == original) releaseUserConstraints(e.getKey(), back);
                }
            } catch (Exception ignored) { }
        }
        deletedCompartments.clear();
        originalOf.clear();
    }

    /** Put every tag edge that step 1 rerouted to a submap back onto its original endpoints. */
    private void restoreRewiredTagEdges() {
        for (TagBinding b : tagBindings.values()) {
            for (RewiredEdge rw : b.rewired) {
                try {
                    // setting the same end again would still move the edge in the node's edge list
                    if (rw.edge.getSource() != rw.origSource) rw.edge.setSource(rw.origSource);
                    if (rw.edge.getTarget() != rw.origTarget) rw.edge.setTarget(rw.origTarget);
                } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Step 1 runs again without a reset: what the last step 1 took out of the graph comes back first, so this one
     * starts from the structure of the map (compartment frames the last step re-inserted stay, they fit the drawing).
     */
    private void undoPreparation() {
        restoreRewiredTagEdges();
        if (restoredCompartments.isEmpty()) restoreDeletedCompartments();
    }

    /** Restore the edge docking captured by {@link #onSnapshot()} before step 1. */
    private void restoreDocking() {
        for (Map.Entry<Edge, String[]> en : originalDocking.entrySet()) {
            try {
                EdgeGraphicAttribute ega = (EdgeGraphicAttribute)
                        en.getKey().getAttribute(GraphicAttributeConstants.GRAPHICS);
                ega.getDocking().setSource(en.getValue()[0]);
                ega.getDocking().setTarget(en.getValue()[1]);
            } catch (Exception ignored) { }
        }
    }

    /** Where {@code n} stood before the first step of the run (null if it was not in the graph then). */
    Point2D.Double inputPosition(Node n) {
        return originalPosition(n);
    }

    /** Capture the docking now (still intact) so a reset can undo removeDocking(). */
    @Override
    protected void onSnapshot() {
        originalDocking.clear();
        for (Edge e : graph.getEdges()) {
            try {
                EdgeGraphicAttribute ega = (EdgeGraphicAttribute)
                        e.getAttribute(GraphicAttributeConstants.GRAPHICS);
                originalDocking.put(e, new String[]{ ega.getDocking().getSource(), ega.getDocking().getTarget() });
            } catch (Exception ignored) { }
        }
    }

    /**
     * Build the ordered step list for the current language. The closures all read
     * from {@code this} so member-state transitions (skeleton, processComponents, …)
     * are observable across steps.
     */
    private void buildSteps() {
        clearSteps();
        Language lang = (instanceLanguage != null) ? instanceLanguage : Language.PD;
        switch (lang) {
            case PD:
                buildPDSteps();
                break;
            case ER:
                // -Dcblayout.erPipeline=pd: ER maps run the PD steps
                if (ERLayout.ENABLED) buildERSteps();
                else buildPDSteps();
                break;
            case AF:
                // -Dcblayout.afPipeline=pd: AF maps run the PD steps
                if (AFLayout.ENABLED) buildAFSteps();
                else buildPDSteps();
                break;
            default:
                buildPDSteps();
        }
    }

    private void buildPDSteps() {
        addStep(new LayoutStep("1. Skeleton",
                "Prepare the graph (docking, compartments, tags, complex containment), " +
                        "build the skeleton graph and open it in a new window.",
                () -> {
                    undoPreparation();
                    removeDocking();
                    refreshView();
                    setClusterMap();
                    pdInitProcessLists();
                    rerouteTags(_tags, _subMaps);
                    createComplexContainmentMap(_complexNodes);
                    registerRigidComplexes();
                    createSkeletonMapping();
                    deleteCompartmentsAndAssign();
                    showSkeletonGraphInNewWindow();
                    ComponentTools.setContext(graph);
                }));

        addStep(new LayoutStep("2. Skeleton Constraints",
                "Generate alignment constraints based on the skeleton and apply them to the original graph.",
                () -> { addSkeletonConstraints(); enforceFeasibility("Skeleton"); CBLayoutAlgorithm.executeConstraints(graph); }));

        addStep(new LayoutStep("3. Individual Constraints",
                "Determine LHS/RHS axes of each process and create the individual separation constraints.",
                () -> { determineProcessOrientation(); addIndividualConstraints(); enforceFeasibility("Individual"); CBLayoutAlgorithm.executeConstraints(graph); }));

        addStep(new LayoutStep("4. Process Stars",
                "Place the leaves of each process on a symmetric star: main substrate / product on the axis, " +
                        "cofactors on the diagonals mirrored at the axis, enzyme on the free perpendicular side, " +
                        "all at the same centre distance (fixed separations). Skip this step to keep minimum distances only.",
                () -> { addProcessStars(); enforceFeasibility("Stars"); CBLayoutAlgorithm.executeConstraints(graph); }));

        addStep(new LayoutStep("5. Stubs",
                "Create stub constraints for stimulations / catalyses, then run the two-phase " +
                        "hierarchical compartment layout (each compartment laid out from its own members, " +
                        "boxes arranged by nesting).",
                () -> { addStubs(); enforceFeasibility("Stubs"); CBLayoutAlgorithm.layoutHierarchically(graph); }));

        addStep(new LayoutStep("6. Edge Routing",
                "Restore complex / tag positions, route the edges via Adaptagrams " +
                        "and re-insert the compartments around their laid-out members.",
                () -> {
                    applyComplexContainment();
                    restoreTagsAfterLayout();
                    SBGNEdgeRouter edgeRouting = new SBGNEdgeRouter();
                    edgeRouting.newExec(graph, false);
                    finishRouting();
                }));
    }

    /**
     * Opens the skeleton graph in a new VANTED editor window so the user can inspect the reduced structure side by
     * side with the original graph. Best effort: a failure (no editor, as in a headless run) only prints a message.
     */
    private void showSkeletonGraphInNewWindow() {
        if (skeleton == null) return;
        try {
            skeleton.setName("Skeleton");
            // a side view: when its window is active, the Constraints page keeps showing the map's store
            ConstraintStore.markSideGraph(skeleton);
            final org.graffiti.editor.MainFrame mainFrame = org.graffiti.editor.MainFrame.getInstance();
            mainFrame.showGraph(skeleton, null);
            // showGraph selects the new window in a queued call. Queue the switch back to the original map behind
            // it, so the next steps, the constraint overlay and "Save As" work on the original graph again.
            final org.graffiti.session.Session original = mainFrame.getEditorSessionForGraph(graph);
            if (original != null) javax.swing.SwingUtilities.invokeLater(() -> javax.swing.SwingUtilities.invokeLater(
                    () -> mainFrame.setActiveSession(original, original.getActiveView())));
        } catch (Throwable t) {
            System.err.println("Could not open skeleton view: " + t.getMessage());
        }
    }

    /** Entity Relationship: the arc, not the graph edge, is the unit; see {@link ERLayout}. */
    private void buildERSteps() {
        if (ERHyperedgeLayout.ENABLED) {
            final ERHyperedgeLayout hyper = new ERHyperedgeLayout(this);
            erReset = hyper::reset;
            addStep(new LayoutStep("1. Remove Hyperedges, Force-Directed",
                    "All hyperedges (modulation, stimulation and inhibition arcs; logic arcs stay) are removed, then all hyperedge nodes of " +
                            "degree 0 or 1. Force-directed run on the graph that is left, without constraints; it starts from a run over " +
                            "the whole map, so what a hyperedge joins lies together. Parts of the map that nothing joins: side by side.",
                    hyper::removeAndForceDirected));
            addStep(new LayoutStep("2. Constraints",
                    "Two neighbours get the alignment their straight line is closest to: vertical, horizontal, " +
                            "octilinear vertical or octilinear horizontal. A node has one aligned neighbour per direction, the " +
                            "inputs of a logical operator lie opposite its output, and the gaps fit together around every " +
                            "cycle. Run with these constraints.",
                    hyper::constraints));
            addStep(new LayoutStep("3. Hyperedge Nodes",
                    "The removed hyperedge nodes are inserted again, evenly on the straight line between the two ends of their " +
                            "hyperedge, and aligned with the two ends if that line is close to an axis.",
                    hyper::hyperedgeNodes));
            addStep(new LayoutStep("4. Hyperedges",
                    "The hyperedges are inserted again, each on the nodes it was attached to. Run with all constraints.",
                    hyper::hyperedges));
            addStep(new LayoutStep("5. Edge Routing",
                    "Orthogonal edge routing; an edge between two nodes on a diagonal stays a straight line. A hyperedge " +
                            "leaves a hyperedge node with a short stub at a right angle to the arc that runs through the node.",
                    hyper::route));
            return;
        }
        final ERLayout er = new ERLayout(this);
        if (ERLayout.CONTRACT) {
            addStep(new LayoutStep("1. Force-Directed",
                    "Prepare the graph (docking, compartments, tags). Take the hyperedge nodes (outcomes, ports) out: every " +
                            "arc between two glyphs becomes one edge. One force-directed pass over the glyphs.",
                    er::forceDirected));
            addStep(new LayoutStep("2. Skeleton Constraints",
                    "Every arc between two glyphs gets the axis of its force-directed angle: both glyphs on one line, in " +
                            "order, with room for the arc's nodes. Constrained force-directed pass over the glyphs.",
                    er::contractedSkeleton));
            addStep(new LayoutStep("3. Hyperedge Nodes",
                    "Put the outcomes and ports back in line with their arc's ends; every other arc at them (influences, " +
                            "logic arcs) perpendicular. Constrained force-directed pass over everything.",
                    er::hyperedgeNodes));
        } else {
            addStep(new LayoutStep("1. Force-Directed",
                    "Prepare the graph (docking, compartments, tags) and run one force-directed pass over all glyphs.",
                    er::forceDirected));
            addStep(new LayoutStep("2. Skeleton Constraints",
                    "Every hyperedge node gets an axis: its own arc runs straight through it, every other arc there " +
                            "leaves perpendicular. Constrained force-directed run.",
                    er::skeletonConstraints));
        }
        addStep(new LayoutStep((ERLayout.CONTRACT ? "4" : "3") + ". Edge Routing",
                "Restore tags, route the edges via Adaptagrams, re-insert compartments and move the drawing to the top left.",
                er::route));
    }

    /** Activity Flow: hierarchy instead of process templates, see {@link AFLayout}. */
    private void buildAFSteps() {
        final AFLayout af = new AFLayout(this);
        addStep(new LayoutStep("1. LO Trees",
                "Prepare the graph (docking, compartments, tags). Every tree of logical operators, with the inputs that " +
                        "feed only it, is laid out on its own and stands in for the next steps as one placeholder box.",
                af::collapseTrees));
        addStep(new LayoutStep("2. Feedback Arc Set",
                "Remove a minimal and stable feedback arc set (MS-FAS, per strongly connected component) so the " +
                        "influences form a DAG. Nothing moves in this step.",
                af::computeFeedbackArcs));
        addStep(new LayoutStep("3. Skeleton",
                "Align a longest path of the DAG top to bottom in one column (per component) and lay out only these nodes.",
                af::layoutSkeleton));
        addStep(new LayoutStep("4. Levels",
                "Add the nodes joined to the previous level: sources of an arc into the anchor above it, targets below, " +
                        "evenly spread around the anchor; a constrained force-directed run after every level " +
                        "(-Dcblayout.afLevels=once: one run after all levels).",
                af::addLevels));
        addStep(new LayoutStep("5. Insert LO Trees",
                "Replace the placeholders by their trees, turned towards their output, and take the feedback arcs back in.",
                af::expandTrees));
        addStep(new LayoutStep("6. LO Stubs",
                "Inputs of an operator on its input side, its output on the other side, stubs at both ports; final " +
                        "constrained force-directed run over all nodes.",
                af::addStubsAndSolve));
        addStep(new LayoutStep("7. Edge Routing",
                "Restore tags, route the edges via Adaptagrams, re-insert compartments and move the drawing to the top left.",
                af::route));
    }

    /** AF and ER step 1, the preparation PD step 1 does as well: docking, view, compartments, tags inside submaps. */
    void prepareActivityFlow() {
        undoPreparation();
        removeDocking();
        // The PD solver calls drop the bends of every edge they see; the AF solver calls see only part of the graph.
        for (Edge e : graph.getEdges()) AttributeHelper.removeEdgeBends(e);
        refreshView();
        setClusterMap();
        CBLayoutAlgorithm.rigidGroups = new ArrayList<>();
        CBLayoutAlgorithm.rigidSnap = null;
        ConflictLog.clear();
        orientationLogic.clear();
        List<Node> tags = new ArrayList<>(), subMaps = new ArrayList<>();
        for (Node n : graph.getNodes()) {
            if (getSBGNRole(n).contains("TAG")) tags.add(n);
            if (getSBGNRole(n).contains("SUBMAP")) subMaps.add(n);
        }
        rerouteTags(tags, subMaps);
        deleteCompartmentsAndAssign();
    }

    /**
     * The last ER step: what PD step 6 does after the layout, without complexes; {@code orthogonal} routing or
     * polylines, with what the pipeline planned for its edges ({@code er}).
     */
    void finishActivityFlow(boolean orthogonal, ERRouting er) {
        restoreTagsAfterLayout();
        new SBGNEdgeRouter().newExec(graph, orthogonal, er);
        finishRouting();
    }

    /**
     * AF step 7 (AFLayout): what PD step 6 does after the layout, without complexes, with {@code nudging} px between
     * parallel arc segments and {@code shapeBuffer} px between an arc and a foreign node; {@code headPins} (asked
     * before every routing): not null if the arrowheads end on pins of their own (see
     * {@link SBGNEdgeRouter#newExecActivityFlow}). After each routing {@code widen} may move nodes apart (true if the
     * arcs are to be routed again); at most {@code maxRounds} times.
     */
    void finishActivityFlow(boolean orthogonal, double nudging, double shapeBuffer, java.util.function.Supplier<Map<Node, double[]>> headPins,
                            java.util.function.BooleanSupplier widen, int maxRounds) {
        restoreTagsAfterLayout();
        new SBGNEdgeRouter().newExecActivityFlow(graph, orthogonal, nudging, shapeBuffer, headPins.get());
        for (int round = 0; widen != null && round < maxRounds && widen.getAsBoolean(); round++) {
            new SBGNEdgeRouter().newExecActivityFlow(graph, orthogonal, nudging, shapeBuffer, headPins.get());
        }
        finishRouting();
    }

    /**
     * After the routing: compartment frames (removed in step 1 so they stay out of the force-directed passes and the
     * router) come back around their members, and the drawing, which cola centres on the origin, moves to the top left.
     */
    private void finishRouting() {
        if (TAG_CLIP) clipBendsInsideTags();
        restoreCompartments();
        moveNetworkToTopLeft(50, 50);
    }

    /**
     * Sets the solver's cluster map: compartment -&gt; members, or an empty map with compartments=drop (step 5 then lays
     * the graph out flat and the last step skips the frames).
     */
    private void setClusterMap() {
        Map<Node, ArrayList<Node>> compartmentMap = resolveCompartmentMap();
        CBLayoutAlgorithm.clusterMap = DROP_COMPARTMENTS ? new HashMap<>() : compartmentMap;
    }

    /** Moves the edges of the tags inside a submap onto the submap ({@link #restoreTagsAfterLayout()} undoes it). */
    private void rerouteTags(List<Node> tags, List<Node> subMaps) {
        prepareTagsReroute(tags, subMaps);
        rerouteTagEdgesToSubmap();
    }

    private void deleteCompartmentsAndAssign() {
        deleteCompartmentNodes();
        assignNodesByNeighbors(graph);
    }

    // node lists of PD step 1
    private ArrayList<Node> _complexNodes;
    private ArrayList<Node> _tags;
    private ArrayList<Node> _subMaps;

    private void pdInitProcessLists() {
        topologyPlan = null;
        // stub sides of an earlier run (steps 3-5 only fill edges without an entry); divideGraphInProcesses below
        // collects the reversible arcs anew
        clearOrientations();
        skeletonNodeLookup = new HashMap<>();
        skeletonCopies = new HashMap<>();
        _complexNodes = new ArrayList<>();
        _tags = new ArrayList<>();
        _subMaps = new ArrayList<>();
        processComponents = new ArrayList<>();
        cycleFixed.clear();
        cycleSides.clear();
        lensInner.clear();
        ringAxis.clear();
        ringLenses.clear();
        stubSidesUsed.clear();
        starSides.clear();
        reservedModSides.clear();
        rowLeafSide.clear();
        hubLast.clear();
        CBLayoutAlgorithm.rigidGroups = new ArrayList<>();
        CBLayoutAlgorithm.rigidSnap = null;
        ConflictLog.clear();
        if (usesTopologySkeleton()) {
            for (Map.Entry<Edge, TopologyReversibleNormalizer.State> change :
                    TopologyReversibleNormalizer.normalize(graph).entrySet()) {
                topologyReversibleChanges.putIfAbsent(change.getKey(), change.getValue());
            }
        }
        divideGraphInProcesses(_subMaps, _complexNodes, _tags);
        if (usesTopologySkeleton()) {
            processComponents.sort(Comparator.comparing(p -> TopologySkeleton.key(p.processNode)));
            for (ProcessComponent p : processComponents) {
                for (List<Node> group : Arrays.asList(p.substrates, p.products, p.revNodesLHS,
                        p.revNodesRHS, p.inhibitors, p.unknown)) {
                    group.sort(Comparator.comparing(TopologySkeleton::key));
                }
            }
        }
    }

    /**
     * -Dcblayout.complexRigid=true|false (default false): without the switch the glyphs drawn inside a complex are laid
     * out as free nodes and snapped back to their input offsets in step 6, so the solver never sees them where they end
     * up. With it, every top-level complex and all its descendants form one rigid group (cola FixedRelativeConstraint)
     * and are snapped to their offsets before each solver call, so they take up their real space.
     */
    private void registerRigidComplexes() {
        CBLayoutAlgorithm.rigidGroups = new ArrayList<>();
        CBLayoutAlgorithm.rigidSnap = null;
        if (!COMPLEX_RIGID || childOffset.isEmpty()) return;
        Map<Node, List<Node>> groups = new TreeMap<>(Comparator.comparing(TopologySkeleton::key));
        List<Node> children = new ArrayList<>(childOffset.keySet());
        children.sort(Comparator.comparing(TopologySkeleton::key));
        for (Node child : children) {
            Node top = child;
            Set<Node> seen = new HashSet<>();
            while (childOffset.containsKey(top) && seen.add(top)) top = childOffset.get(top).complex;
            groups.computeIfAbsent(top, k -> new ArrayList<>(Collections.singletonList(k))).add(child);
        }
        CBLayoutAlgorithm.rigidGroups.addAll(groups.values());
        CBLayoutAlgorithm.rigidSnap = this::applyComplexContainment;
    }

    /**
     * Build the compartment map (compartment node -&gt; its member nodes).
     * <p>
     * Membership is resolved in two ways, in order of preference:
     * <ol>
     *   <li><b>Explicit:</b> the {@code sbgn/compartmentRef} attribute that SBGN-ED writes onto a node.</li>
     *   <li><b>Geometric fallback:</b> when no {@code compartmentRef} is set, a node is assigned to the compartment
     *       whose bounding box contains the node's centre. Only compartment-class glyphs act as containers, and a
     *       node that falls inside several (nested) compartments is assigned to the smallest, i.e. innermost, one.</li>
     * </ol>
     */
    private Map<Node, ArrayList<Node>> resolveCompartmentMap() {
        Map<Node, ArrayList<Node>> map = new HashMap<>();
        Map<String, Node> idToNode = new HashMap<>();
        // Only compartment-class glyphs may ever act as containers.
        List<Node> compartments = new ArrayList<>();
        for (Node n : graph.getNodes()) {
            if (isCompartment(n)) {
                compartments.add(n);
                String id = (String) AttributeHelper.getAttributeValue(n, "sbgn", "glyphid", "", "", false);
                if (id != null && !id.isEmpty()) idToNode.put(id, n);
                String label = AttributeHelper.getLabel(n, null);
                if (label != null && !label.isEmpty()) idToNode.put(label, n);
                map.computeIfAbsent(n, k -> new ArrayList<>());
            }
        }
        if (map.isEmpty()) return map;

        for (Node n : graph.getNodes()) {
            // A compartment is never a member of another compartment.
            if (isCompartment(n)) continue;

            // 1) Explicit compartmentRef wins when present.
            Node target = null;
            String compartmentRef = (String) AttributeHelper.getAttributeValue(n, "sbgn", "compartmentRef", "", "", false);
            if (compartmentRef != null && !compartmentRef.isEmpty()) {
                target = idToNode.get(compartmentRef);
            }

            // 2) Geometric fallback: innermost (smallest-area) compartment containing the node centre.
            if (target == null) {
                double smallestArea = Double.POSITIVE_INFINITY;
                for (Node c : compartments) {
                    if (compartmentContains(c, n)) {
                        double a = AttributeHelper.getWidth(c) * AttributeHelper.getHeight(c);
                        if (a < smallestArea) {
                            smallestArea = a;
                            target = c;
                        }
                    }
                }
            }

            if (target != null) {
                map.get(target).add(n);
            }
        }
        return map;
    }

    public static boolean isCompartment(Node n) {
        String role = getSBGNRole(n);
        return role != null && role.toUpperCase().contains("COMPARTMENT");
    }

    /** True if {@code node}'s centre lies within {@code compartment}'s bounding box. */
    private static boolean compartmentContains(Node compartment, Node node) {
        double cx = AttributeHelper.getPositionX(compartment);
        double cy = AttributeHelper.getPositionY(compartment);
        double cw = AttributeHelper.getWidth(compartment);
        double ch = AttributeHelper.getHeight(compartment);

        double left = cx - cw / 2.0;
        double right = cx + cw / 2.0;
        double top = cy - ch / 2.0;
        double bottom = cy + ch / 2.0;

        double nx = AttributeHelper.getPositionX(node);
        double ny = AttributeHelper.getPositionY(node);

        return nx >= left && nx <= right && ny >= top && ny <= bottom;
    }

    private void addIndividualConstraints() {
        for (ProcessComponent p : processComponents) {
            grouped(null, "sides", "Sides of " + processName(p.processNode), "Individual", () -> addIndividualConstraints(p));
        }
    }

    /** The side separations of one process (see {@link #addIndividualConstraints()}). */
    private void addIndividualConstraints(ProcessComponent p) {
        Node process = p.processNode;
        // -Dcblayout.splitRow: of two or more structural members of one port group only the first keeps its
        // step-2 alignment on the process axis; the others would share the process row with it.
        if (SPLIT_ROW && fdVariant() && p.getProdSubOrientation() != -1) {
            for (List<Node> group : Arrays.asList(p.products, p.substrates)) {
                List<Node> st = structural(free(group, process));
                for (int i = 1; i < st.size(); i++) dropContradictingAlignment(st.get(i), process, p.getProdSubOrientation() == 1);
            }
        }
        // -Dcblayout.sideAlign=drop|swap: an alignment of a structural member with the process across the axis would
        // make the guard drop the side separation below; it goes first (ring, lens and fan members keep theirs)
        if (SIDE_ALIGN != 0 && fdVariant()) {
            for (int g = 0; g < 4; g++) {
                List<Node> group = g == 0 ? p.products : g == 1 ? p.substrates : g == 2 ? p.revNodesLHS : p.revNodesRHS;
                int ori = g < 2 ? p.getProdSubOrientation() : p.getRevOrientation();
                if (ori == -1) continue;
                boolean axisHorizontal = ori == 0;
                for (Node n : free(group, process)) {
                    if (!skeletonNodeLookup.containsValue(n) || cycleFixed.containsKey(n)) continue;
                    if (dropAlignment(n, process, !axisHorizontal, "removed: across the process axis (sideAlign)") && SIDE_ALIGN == 2) {
                        List<Node> pair = new ArrayList<>(Arrays.asList(process, n));
                        ConstraintStore.constraints.addConstraint(axisHorizontal
                                ? new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE)
                                : new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
                    }
                }
            }
        }
        // a switch puts the RHS / products left of resp. above the process, the LHS / substrates on the other side
        int rev = p.getRevOrientation();
        if (rev == 0 || rev == 1) {
            addSideSeparations(p.revNodesRHS, process, p.isRevSwitch(), rev == 0);
            addSideSeparations(p.revNodesLHS, process, !p.isRevSwitch(), rev == 0);
        }
        int ori = p.getProdSubOrientation();
        if (ori == 0 || ori == 1) {
            addSideSeparations(p.products, process, p.isProdSubSwitch(), ori == 0);
            addSideSeparations(p.substrates, process, !p.isProdSubSwitch(), ori == 0);
        }
    }

    /**
     * Separates every node of {@code nodes} that no ring fixes from {@code process} along x ({@code horizontal}) or y:
     * touching distance plus sideGap, the node before the process if {@code nodeFirst}.
     */
    private void addSideSeparations(List<Node> nodes, Node process, boolean nodeFirst, boolean horizontal) {
        for (Node n : free(nodes, process)) {
            List<Node> toAdd = new ArrayList<>();
            if (nodeFirst) {
                toAdd.add(n);
                toAdd.add(process);
            } else {
                toAdd.add(process);
                toAdd.add(n);
            }
            double distance = SIDE_GAP + (int) (horizontal
                    ? AttributeHelper.getWidth(n) / 2 + AttributeHelper.getWidth(process) / 2
                    : AttributeHelper.getHeight(n) / 2 + AttributeHelper.getHeight(process) / 2);
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(toAdd, horizontal
                    ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                    : SeparationConstraintGraph.SeparationConstraintType.VERTICAL, false, distance));
        }
    }

    private void addStubs() {
        Map<Node, ConstraintGroup> stubGroups = new HashMap<>();   // the stubs of one process form one group
        for (Edge e : layoutEdges(graph)) {
            Node process = null;
            Node connector = null;
            if (getSBGNRole(e.getSource()).contains("PROCESS")) {
                process = e.getSource();
                connector = e.getTarget();
            } else if (getSBGNRole(e.getTarget()).contains("PROCESS")) {
                process = e.getTarget();
                connector = e.getSource();
            }
            if (process == null) {
                continue;
            }
            final Node pr = process, co = connector;
            stubGroups.put(process, grouped(stubGroups.get(process), "stubs", "Stubs of " + processName(process), "Stubs",
                    () -> addStub(e, pr, co)));
        }
    }

    /** The stub of one arc at its process (see {@link #addStubs()}). */
    private void addStub(Edge e, Node process, Node connector) {
        ProcessComponent p = getProcessComponent(process);
        String role = getSBGNRole(e);
        if (role.contains("CONSUMPTION") || role.contains("PRODUCTION")) {
            if (orientationProdSub.containsKey(e) || p == null) return;
            // -Dcblayout.rowStub=side: a row leaf sits beside the axis, so its stub leaves the process towards the row
            if (ROW_STUB_SIDE && rowLeafSide.containsKey(connector)) {
                orientationProdSub.put(e, rowLeafSide.get(connector));
                return;
            }
            putStubSide(orientationProdSub, e, connector, p.getProdSubOrientation(), p.isProdSubSwitch(),
                    p.products, p.substrates);
        } else if (role.contains("REVERSIBLELHS") || role.contains("REVERSIBLERHS")) {
            if (orientationREV.containsKey(e) || p == null) return;
            putStubSide(orientationREV, e, connector, p.getRevOrientation(), p.isRevSwitch(),
                    p.revNodesRHS, p.revNodesLHS);
        } else if (isModulationRole(role)) {
            placeModulator(e, process, connector, p);
        }
    }

    /**
     * Stub side of a port arc on axis {@code ori} (0 horizontal, 1 vertical, else none): an arc to a node of
     * {@code out} (products, RHS) leaves left resp. up with the switch {@code sw}, right resp. down without; one to
     * {@code in} the opposite way.
     */
    private static void putStubSide(Map<Edge, String> sides, Edge e, Node connector, int ori, boolean sw,
                                    List<Node> out, List<Node> in) {
        if (ori != 0 && ori != 1) return;
        String outSide = ori == 0 ? (sw ? "left" : "right") : (sw ? "up" : "down");
        if (out.contains(connector)) sides.put(e, outSide);
        if (in.contains(connector)) sides.put(e, opposite(outSide));
    }

    /**
     * Catalysis, stimulation and necessary stimulation: the arcs the stub step knows how to place
     * (-Dcblayout.modRoles=all: inhibition, modulation, unknown influence too).
     */
    private static boolean isModulationRole(String role) {
        if (role == null) return false;
        if (role.contains("CATALYSIS") || role.contains("STIMULATION")) return true;
        return MOD_ROLES_ALL && (role.contains("INHIBITION") || role.contains("MODULATION") || role.contains("UNKNOWNINFLUENCE"));
    }

    /** Number of modulation arcs (see {@link #isModulationRole}) incident to {@code n}. */
    private static int modulationEdgeCount(Node n) {
        int count = 0;
        for (Edge e : n.getEdges()) {
            if (isModulationRole(getSBGNRole(e))) count++;
        }
        return count;
    }

    /** Sides of each process already handed to a modulator in this run. */
    private final Map<Node, Set<String>> stubSidesUsed = new HashMap<>();

    /** Modulator leaf -> side the process star placed it on; the stub step reuses that side. */
    private final Map<Node, String> starSides = new HashMap<>();
    /** -Dcblayout.starRadius=px (100): centre distance of the process star (larger only where the nodes need it). */
    public static double STAR_RADIUS = doubleProperty("cblayout.starRadius", 100);

    /**
     * -Dcblayout.alignSides=off|drop|rotate (default off): two skeleton neighbours that step 2 would align to the same
     * side of one node: off aligns both, drop keeps only the nearer one, rotate aligns the other one on the
     * perpendicular axis if that side is free.
     */
    public static final String SIDE_POLICY = System.getProperty("cblayout.alignSides", "off").toLowerCase();

    /**
     * -Dcblayout.skeletonShape=fd|structure|structure-map|topology (default fd): how step 2 directs a skeleton edge.
     * fd reads the angle off a force-directed pass; structure builds the directions from chains of the skeleton
     * topology ({@link SkeletonShape}); structure-map keeps the direction each chain has in the input map; topology
     * plans them globally with regulation, feedback arcs and the later process axes ({@link TopologySkeleton}).
     */
    public static final String SKELETON_SHAPE = System.getProperty("cblayout.skeletonShape", "fd").toLowerCase();

    /** True while one of the structural variants of step 2 is in effect. */
    public static boolean usesStructuralSkeleton() { return SKELETON_SHAPE.startsWith("structure"); }

    public static boolean usesTopologySkeleton() { return "topology".equals(SKELETON_SHAPE); }

    /** Force-directed skeleton (skeletonShape=fd, the default); most of the -Dcblayout switches act only here. */
    public static boolean fdVariant() { return !usesStructuralSkeleton() && !usesTopologySkeleton(); }

    /** Canonical solver/router order in topology mode, including indistinguishably labelled glyphs. */
    public static List<Node> layoutNodes(Graph g) {
        List<Node> nodes = new ArrayList<>(g.getNodes());
        if (usesTopologySkeleton()) nodes.sort(Comparator.comparing(TopologySkeleton::key));
        return nodes;
    }

    /** As {@link #layoutNodes}, for the edges: in topology mode sorted by source, target, role and glyph id. */
    public static List<Edge> layoutEdges(Graph g) {
        List<Edge> edges = new ArrayList<>(g.getEdges());
        if (usesTopologySkeleton()) edges.sort(Comparator
                .comparing((Edge e) -> TopologySkeleton.key(e.getSource()))
                .thenComparing(e -> TopologySkeleton.key(e.getTarget()))
                .thenComparing(ConstraintSBGNLayout::getSBGNRole)
                .thenComparing(e -> String.valueOf(AttributeHelper.getAttributeValue(e,
                        "sbgn", "glyphid", "", "", false))));
        return edges;
    }

    /**
     * -Dcblayout.hubSpread=true|false (default true): processes that share a species spread around it instead of
     * lining up behind each other on one row through the species.
     */
    public static final boolean HUB_SPREAD = !"false".equalsIgnoreCase(System.getProperty("cblayout.hubSpread", "true"));

    /**
     * -Dcblayout.compartments=drop|keep (default drop): drop removes the compartment frames and does not draw them
     * again; keep lays the map out per compartment and re-inserts the frames around their members.
     */
    public static final boolean DROP_COMPARTMENTS =
            !"keep".equalsIgnoreCase(System.getProperty("cblayout.compartments", "drop"));
    /** -Dcblayout.starGap=px (30): gap between the nodes of a star; equal to stubGap, so the star radius never undercuts a stub separation. */
    static final double STAR_GAP = doubleProperty("cblayout.starGap", 30);
    /** -Dcblayout.starRowSpacing=px (80): pitch between the leaves of a co-factor row of a star. */
    static final double STAR_ROW_SPACING = doubleProperty("cblayout.starRowSpacing", 80);

    /** First node of the list with degree > 1 (skeleton / cycle member), else null. */
    private static Node firstStructural(List<Node> nodes) {
        for (Node n : nodes) if (n.getDegree() > 1) return n;
        return null;
    }

    /** First node of the list with degree > 1 (skeleton / cycle member), else the first node, else null. */
    private static Node structuralOrFirst(List<Node> nodes) {
        Node n = firstStructural(nodes);
        return n != null || nodes.isEmpty() ? n : nodes.get(0);
    }

    /**
     * Sides of {@code process} that carry a structural neighbour: a node an embedded cycle fixed,
     * or a skeleton neighbour (aligned by step 2 / separated by step 3), read off the current
     * positions. Plus every side already given to another modulator.
     */
    private Set<String> occupiedSides(ProcessComponent p, Node process) {
        Set<String> occupied = new HashSet<>();
        List<List<Node>> groups = new ArrayList<>();
        groups.add(p.substrates); groups.add(p.products); groups.add(p.revNodesLHS); groups.add(p.revNodesRHS);
        for (List<Node> g : groups) {
            for (Node n : g) {
                if (isCycleFixed(process, n) || skeletonNodeLookup.containsValue(n)) {
                    String side = checkOrientation(process, n);
                    if (!side.isEmpty()) occupied.add(side);
                }
            }
        }
        Set<String> used = stubSidesUsed.get(process);
        if (used != null) occupied.addAll(used);
        String inner = lensInner.get(process);
        if (inner != null) occupied.add(inner);
        return occupied;
    }

    /**
     * Choose the side of the process for a modulator: perpendicular to the substrate/product axis
     * ({@code ori} 1 = vertical axis, so left/right; 0 = horizontal axis, so up/down), the side the
     * node currently leans to first, skipping sides a structural neighbour or another modulator
     * already occupies. Falls back to a free side on the other axis, then to the current side.
     */
    private String chooseStubSide(ProcessComponent p, Node process, Node connector, int ori) {
        Set<String> occupied = occupiedSides(p, process);
        List<String> horizontal = new ArrayList<>();
        if (isToLeft(process, connector)) { horizontal.add("left"); horizontal.add("right"); }
        else { horizontal.add("right"); horizontal.add("left"); }
        List<String> vertical = new ArrayList<>();
        if (isDownwards(process, connector)) { vertical.add("up"); vertical.add("down"); }
        else { vertical.add("down"); vertical.add("up"); }
        List<String> preferred = ori == 0 ? vertical : horizontal;
        List<String> other = ori == 0 ? horizontal : vertical;
        if (topologyPlan != null && topologyPlan.owns(process, connector)) {
            // the plan already orders this arc; at a ring corner without a free perpendicular side the opposite side
            // would reverse the planned flow, so the router approaches the nearer perpendicular port
            return preferred.get(0);
        }
        for (String side : preferred) if (!occupied.contains(side)) return side;
        for (String side : other) if (!occupied.contains(side)) return side;
        return preferred.get(0);
    }

    /**
     * Stub step for one modulation arc: record the side for the router, then pin the modulator to
     * that side. Leaves and single-arc skeleton modulators get alignment + separation; a hub that
     * modulates several processes only gets the separation (an alignment per process would chain
     * the processes together). A node that is also substrate / product of the process gets nothing.
     */
    private void placeModulator(Edge e, Node process, Node connector, ProcessComponent p) {
        if (p == null) return;
        int ori = p.getProdSubOrientation();
        if (ori == -1) ori = p.getRevOrientation();
        if (p.substrates.contains(connector) || p.products.contains(connector)
                || p.revNodesLHS.contains(connector) || p.revNodesRHS.contains(connector)) {
            orientationStim.put(e, ori == 0 ? (isDownwards(process, connector) ? "up" : "down")
                                            : (isToLeft(process, connector) ? "left" : "right"));
            return;
        }
        // modSide=reserve: step 4 already gave this skeleton modulator a side and built the star around it
        String reserved = reservedModSides.containsKey(process) ? reservedModSides.get(process).get(connector) : null;
        String side = reserved != null ? reserved
                : starSides.containsKey(connector) ? starSides.get(connector) : chooseStubSide(p, process, connector, ori);
        orientationStim.put(e, side);
        stubSidesUsed.computeIfAbsent(process, k -> new HashSet<>()).add(side);
        if (topologyPlan != null && topologyPlan.owns(process, connector)) return;
        // -Dcblayout.modDropAlign: step 2 may have aligned this skeleton modulator with the process on the axis; the stub
        // separation across it would be a self-loop and the guard would drop the younger separation, leaving the
        // modulator on the axis. Take the older alignment out first.
        if (MOD_DROP_ALIGN && fdVariant() && skeletonNodeLookup.containsValue(connector)) {
            dropAlignment(connector, process, !(side.equals("left") || side.equals("right")), "removed: across the stub side (modDropAlign)");
        }

        boolean align = !skeletonNodeLookup.containsValue(connector)
                || (!usesTopologySkeleton() && modulationEdgeCount(connector) == 1);
        if (align) {
            List<Node> toAdd = new ArrayList<>();
            toAdd.add(process);
            toAdd.add(connector);
            if (side.equals("left") || side.equals("right")) {
                ConstraintStore.constraints.addConstraint(new HorizontalConstraint(toAdd, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            } else {
                ConstraintStore.constraints.addConstraint(new VerticalConstraint(toAdd, VerticalConstraint.VerticalConstraintType.MIDDLE));
            }
        }
        addStubSeparation(process, connector, side);
        // -Dcblayout.gateInputs=far: the gate sits between the process and its leaf inputs, so a logic arc does not run
        // back across the process. Non-leaf inputs keep their own constraints.
        if (GATE_INPUTS_FAR && fdVariant() && getSBGNRole(connector).contains("OPERATOR")) {
            for (Edge ge : connector.getEdges()) {
                Node in = ge.getSource() == connector ? ge.getTarget() : ge.getSource();
                if (in == process || in == connector || in.getDegree() > 1) continue;
                addStubSeparation(connector, in, side);
            }
        }
    }

    /**
     * modSide=reserve: the side perpendicular to the process axis on which the processes that produce {@code modulator}
     * lie on average (production arcs into it), or null if it has none or they lie on the axis line.
     */
    private static String producerSide(Node process, Node modulator, boolean axisVertical) {
        double sum = 0;
        int n = 0;
        for (Edge e : modulator.getEdges()) {
            if (!getSBGNRole(e).contains("PRODUCTION")) continue;
            Node q = e.getSource() == modulator ? e.getTarget() : e.getSource();
            if (q == process || q == modulator) continue;
            sum += axisVertical ? AttributeHelper.getPositionX(q) - AttributeHelper.getPositionX(process)
                    : AttributeHelper.getPositionY(q) - AttributeHelper.getPositionY(process);
            n++;
        }
        if (n == 0 || Math.abs(sum / n) < 1) return null;
        return axisVertical ? (sum > 0 ? "right" : "left") : (sum > 0 ? "down" : "up");
    }

    /**
     * Translate every node and every edge bend by the same vector so the top-left corner of the
     * drawing lands at ({@code offX}, {@code offY}). Same effect as VANTED's "Move Network to
     * Top-Left", but without an undo entry and inside the running step transaction.
     */
    private void moveNetworkToTopLeft(double offX, double offY) {
        // sbgnUserTypes=all: a user pin, area or target fixes absolute positions the solves applied; the run must not
        // shift the drawing away from them at its end (the next user solve would snap them back and move everything else)
        if (CBLayoutAlgorithm.absoluteUserConstraintsOn(graph)) {
            ConflictLog.addDropped("SBGN", Collections.<ConstraintFeasibility.Dropped>emptyList(),
                    Collections.singletonList("The drawing was not moved to the top left: a pin, area or target of yours fixes positions."));
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (Node n : graph.getNodes()) {
            minX = Math.min(minX, AttributeHelper.getPositionX(n) - AttributeHelper.getWidth(n) / 2);
            minY = Math.min(minY, AttributeHelper.getPositionY(n) - AttributeHelper.getHeight(n) / 2);
        }
        for (Edge e : graph.getEdges()) {
            for (org.Vector2d b : AttributeHelper.getEdgeBends(e)) {
                minX = Math.min(minX, b.x);
                minY = Math.min(minY, b.y);
            }
        }
        if (Double.isInfinite(minX) || Double.isInfinite(minY)) return;
        double dx = offX - minX, dy = offY - minY;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return;
        for (Node n : graph.getNodes()) {
            AttributeHelper.setPosition(n, AttributeHelper.getPositionX(n) + dx, AttributeHelper.getPositionY(n) + dy);
        }
        for (Edge e : graph.getEdges()) {
            List<org.Vector2d> bends = AttributeHelper.getEdgeBends(e);
            if (bends.isEmpty()) continue;
            List<org.Vector2d> moved = new ArrayList<>(bends.size());
            for (org.Vector2d b : bends) moved.add(new org.Vector2d(b.x + dx, b.y + dy));
            AttributeHelper.removeEdgeBends(e);
            AttributeHelper.addEdgeBends(e, moved);
        }
    }

    // ------------------------------------------------------------------ process stars

    private static int dirX(String side) { return side.equals("left") ? -1 : side.equals("right") ? 1 : 0; }
    private static int dirY(String side) { return side.equals("up") ? -1 : side.equals("down") ? 1 : 0; }
    private static String opposite(String side) {
        switch (side) { case "left": return "right"; case "right": return "left"; case "up": return "down"; default: return "up"; }
    }

    /** Substrate side of a process from the orientation fields, or null if the axis is unknown. */
    private static String substrateSide(ProcessComponent p) {
        int ori = p.getProdSubOrientation();
        boolean sw = p.isProdSubSwitch();
        if (ori == -1) { ori = p.getRevOrientation(); sw = p.isRevSwitch(); }
        if (ori == -1) return null;
        // switch = products left resp. top, so substrates on the other side
        if (ori == 0) return sw ? "right" : "left";
        return sw ? "down" : "up";
    }

    /**
     * Step 4. Symmetric neighbourhood per process: every leaf (degree 1) gets a slot on a star of
     * radius {@link #STAR_RADIUS} around the process and is pinned there with fixed separations.
     * Main substrate / product on the axis if that side is free, cofactors on the diagonals of
     * the substrate resp. product half, mirrored at the axis, on the side away from the enzyme;
     * the enzyme on the free side perpendicular to the axis. Skeleton neighbours are untouched, so
     * nothing here can close a constraint cycle: a leaf belongs to exactly one process.
     */
    private void addProcessStars() {
        // -Dcblayout.stars=true|false (default true): false skips the stars, leaving the minimum distances of step 3
        if (!Boolean.parseBoolean(System.getProperty("cblayout.stars", "true"))) return;
        for (ProcessComponent p : processComponents) {
            grouped(null, "star", "Star of " + processName(p.processNode), "Stars", () -> addProcessStar(p));
        }
    }

    /** The star of one process (see {@link #addProcessStars()}). */
    private void addProcessStar(ProcessComponent p) {
        String subSide = substrateSide(p);
        if (subSide == null) return;
        Node process = p.processNode;
        String prodSide = opposite(subSide);
        boolean axisVertical = dirX(subSide) == 0;

        List<Node> leafSubs = leaves(union(p.substrates, p.revNodesLHS));
        List<Node> leafProds = leaves(union(p.products, p.revNodesRHS));
        List<Node> leafMods = leaves(union(p.inhibitors, p.unknown));
        if (leafSubs.isEmpty() && leafProds.isEmpty() && leafMods.isEmpty()) return;

        Set<String> occupied = occupiedSides(p, process);
        // -Dcblayout.debugStars=true|false (default false): prints axis, occupied sides and port groups of every star
        if (Boolean.getBoolean("cblayout.debugStars")) {
            StringBuilder sb = new StringBuilder("[cblayout] star proc" + process.getID() + " subSide=" + subSide + " occupied=" + occupied
                    + " ori=" + p.getProdSubOrientation() + "/" + p.isProdSubSwitch() + " rev=" + p.getRevOrientation() + "/" + p.isRevSwitch() + " subs=");
            for (Node n : p.substrates) sb.append(AttributeHelper.getLabel(n, "?")).append('(').append(n.getDegree()).append(") ");
            sb.append("prods=");
            for (Node n : p.products) sb.append(AttributeHelper.getLabel(n, "?")).append('(').append(n.getDegree()).append(") ");
            System.out.println(sb);
        }

        // enzyme side: free perpendicular side, the one it leans to first
        String[] perp = axisVertical ? new String[]{"left", "right"} : new String[]{"up", "down"};
        String enzymeSide = null;
        if (!leafMods.isEmpty()) {
            Node m = leafMods.get(0);
            String lean = axisVertical ? (isToLeft(process, m) ? "left" : "right") : (isDownwards(process, m) ? "up" : "down");
            if (!occupied.contains(lean)) enzymeSide = lean;
            else if (!occupied.contains(opposite(lean))) enzymeSide = opposite(lean);
        }
        // -Dcblayout.modSide=reserve (skeletonShape=fd): a skeleton modulator reserves its side as well, but only a side
        // perpendicular to the axis (otherwise the base rule stays); placeModulator reuses it.
        Node reservedMod = null;
        if ((usesTopologySkeleton() || (MOD_RESERVE && fdVariant())) && enzymeSide == null) {
            // Regulation can arrive from an earlier pathway stage. Reserve its side before
            // placing cofactors; the stub step comes too late to move an already pinned star.
            for (Node modulator : union(p.inhibitors, p.unknown)) {
                if (!skeletonNodeLookup.containsValue(modulator)) continue;
                boolean regulatory = false;
                for (Edge e : modulator.getEdges()) {
                    if ((e.getSource() == process || e.getTarget() == process)
                            && isModulationRole(getSBGNRole(e))) { regulatory = true; break; }
                }
                if (!regulatory) continue;
                String s = chooseStubSide(p, process, modulator, axisVertical ? 1 : 0);
                if (!usesTopologySkeleton()) {
                    // a modulator produced upstream keeps the perpendicular side its producers lie on, so the
                    // production arc into it does not cross the process axis (its own lean can be a 2 px coin flip)
                    String flow = producerSide(process, modulator, axisVertical);
                    if (flow != null && !flow.equals(s)) {
                        if (occupiedSides(p, process).contains(flow)) break;   // that side is taken: base rule
                        s = flow;
                    }
                }
                if (!usesTopologySkeleton() && (dirX(s) == 0) == axisVertical) break;   // not perpendicular: base rule
                enzymeSide = s;
                if (!usesTopologySkeleton()) reservedMod = modulator;
                break;
            }
        }
        // cofactor side: away from the enzyme; without enzyme the side the first cofactor leans
        // to, but never a side a skeleton neighbour occupies (corner processes of a cycle)
        String cofactorSide;
        if (enzymeSide != null) cofactorSide = opposite(enzymeSide);
        else {
            Node any = !leafSubs.isEmpty() ? leafSubs.get(0) : !leafProds.isEmpty() ? leafProds.get(0) : null;
            cofactorSide = any == null ? perp[0]
                    : axisVertical ? (isToLeft(process, any) ? "left" : "right") : (isDownwards(process, any) ? "up" : "down");
            if (occupied.contains(cofactorSide) && !occupied.contains(opposite(cofactorSide))) cofactorSide = opposite(cofactorSide);
        }
        // lensStar=outer: enzyme and both cofactor rows of a lens process go to the side away from the partner process,
        // unless the step-3 axis runs across the lens (a row would then start on the process itself)
        String lensIn = lensInner.get(process);
        boolean lensOuter = lensIn != null && LENS_STAR_OUTER && (dirX(lensIn) == 0) != axisVertical;
        if (lensOuter) {
            cofactorSide = opposite(lensIn);
            if (enzymeSide != null) enzymeSide = opposite(lensIn);
        }
        // Precedence at one process: lensStar=outer (lens and bundle processes) > modSide=reserve > base rule. The
        // reserved modulator side is stored after the lens override, so placeModulator reuses the final side.
        if (reservedMod != null && enzymeSide != null) reservedModSides.computeIfAbsent(process, k -> new HashMap<>()).put(reservedMod, enzymeSide);

        // axis slots: the leaf closest to the axis direction, only if no skeleton neighbour is there
        // (partners for -Dcblayout.axisLeaf: the structural species of the opposite port group)
        Node axisSub = occupied.contains(subSide) ? null : closestTo(process, leafSubs, subSide, structural(union(p.products, p.revNodesRHS)));
        Node axisProd = occupied.contains(prodSide) ? null : closestTo(process, leafProds, prodSide, structural(union(p.substrates, p.revNodesLHS)));
        if (axisSub != null) pinOnAxis(process, axisSub, subSide);
        if (axisProd != null) pinOnAxis(process, axisProd, prodSide);

        // diagonal rows, mirrored at the axis. The star pins with equalities, so cola's overlap
        // removal cannot move these nodes apart later: the geometry has to be overlap-free here.
        List<Node> subRow = new ArrayList<>(leafSubs); subRow.remove(axisSub);
        List<Node> prodRow = new ArrayList<>(leafProds); prodRow.remove(axisProd);
        // offset along the axis: the two rows must clear each other across the process, and an equality here must not
        // undercut the touching distance step 3 demands for every substrate / product
        double axisOff = STAR_RADIUS / Math.sqrt(2);
        double subMax = 0, prodMax = 0;
        for (Node n : subRow) subMax = Math.max(subMax, halfExtent(n, subSide));
        for (Node n : prodRow) prodMax = Math.max(prodMax, halfExtent(n, subSide));
        if (!subRow.isEmpty() && !prodRow.isEmpty()) axisOff = Math.max(axisOff, (subMax + prodMax + STAR_GAP / 2) / 2);
        axisOff = Math.max(axisOff, halfExtent(process, subSide) + Math.max(subMax, prodMax) + 1);
        // rowCap / rowSides: the second chunk of a long row may go to the other perpendicular side when nothing is
        // there (no enzyme, skeleton neighbour or lens partner), see pinRows
        String altSide = ROW_SIDES_BOTH && enzymeSide == null && !occupied.contains(opposite(cofactorSide))
                && !opposite(cofactorSide).equals(lensIn) ? opposite(cofactorSide) : null;
        double subFar = pinRows(process, subRow, subSide, cofactorSide, altSide, axisOff, axisSub != null ? axisSub : structuralOnSide(p, process, subSide));
        double prodFar = pinRows(process, prodRow, prodSide, cofactorSide, altSide, axisOff, axisProd != null ? axisProd : structuralOnSide(p, process, prodSide));
        // rows on the enzyme side: both rows of a lens process with lensStar=outer (the base rule
        // puts the rows opposite the enzyme)
        List<Node> enzymeRows = new ArrayList<>();
        double farRow = 0;
        if (enzymeSide != null && cofactorSide.equals(enzymeSide)) {
            enzymeRows.addAll(subRow);
            enzymeRows.addAll(prodRow);
            farRow = Math.max(subFar, prodFar);
        }

        // enzyme(s): chained outwards along the enzyme side; the first one also clears the
        // corners of the axis nodes (a large enzyme complex beside a large substrate)
        if (enzymeSide != null) {
            double r = 0;
            Node prev = null;
            for (Node m : leafMods) {
                if (prev == null) {
                    r = Math.max(STAR_RADIUS, halfExtent(process, enzymeSide) + halfExtent(m, enzymeSide) + STAR_GAP);
                    if (axisSub != null) r = Math.max(r, clearCorner(m, enzymeSide, axisSub, subSide, axisRadius(process, axisSub, subSide)));
                    if (axisProd != null) r = Math.max(r, clearCorner(m, enzymeSide, axisProd, prodSide, axisRadius(process, axisProd, prodSide)));
                    if (!enzymeRows.isEmpty()) {
                        // enzyme and a cofactor row share a side: if the enzyme reaches into the row
                        // along the axis, it starts beyond the farthest row node
                        double rowPerp = 0, rowAlong = 0;
                        for (Node n : enzymeRows) { rowPerp = Math.max(rowPerp, halfExtent(n, enzymeSide)); rowAlong = Math.max(rowAlong, halfExtent(n, subSide)); }
                        if (halfExtent(m, subSide) + rowAlong + STAR_GAP / 2 > axisOff) r = Math.max(r, farRow + rowPerp + halfExtent(m, enzymeSide) + STAR_GAP);
                    }
                } else {
                    r += halfExtent(prev, enzymeSide) + halfExtent(m, enzymeSide) + STAR_GAP;
                }
                pin(process, m, dirX(enzymeSide) * r, dirY(enzymeSide) * r);
                starSides.put(m, enzymeSide);
                prev = m;
            }
        }
    }

    private static List<Node> leaves(List<Node> nodes) {
        List<Node> l = new ArrayList<>();
        for (Node n : nodes) if (n.getDegree() <= 1) l.add(n);
        return l;
    }

    private static List<Node> union(List<Node> a, List<Node> b) {
        List<Node> l = new ArrayList<>(a);
        for (Node n : b) if (!l.contains(n)) l.add(n);
        return l;
    }

    /**
     * The leaf that should sit on the axis: a macromolecule / complex before a simple chemical
     * (ATP, H2O, ... are cofactors by convention), among equals the one whose current direction
     * from the process agrees best with {@code side}.
     * <p>-Dcblayout.axisLeaf (default smart) adds a tie-break; {@code partners} are the structural species of the
     * opposite port group. {@code labelpair}: +1.5 for a leaf with the label of a partner (the species a transporter
     * moves); {@code noclone}: +1.5 for a simple chemical without clone marker (currency metabolites are the cloned
     * ones); {@code currency}: -1.5 for a leaf on the currency list. {@code smart}: a strict order instead of
     * weights, label pair &gt; not a simple chemical &gt; not on the currency list &gt; no clone marker &gt; not
     * H+ / H2O, and the direction only among leaves equal in all five.
     */
    private static Node closestTo(Node process, List<Node> nodes, String side, List<Node> partners) {
        Node best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Node n : nodes) {
            double dx = AttributeHelper.getPositionX(n) - AttributeHelper.getPositionX(process);
            double dy = AttributeHelper.getPositionY(n) - AttributeHelper.getPositionY(process);
            double len = Math.hypot(dx, dy);
            double score = len < 1e-9 ? 0 : (dx * dirX(side) + dy * dirY(side)) / len;
            boolean chemical = getSBGNRole(n).contains("SIMPLECHEMICAL");
            boolean leafRule = fdVariant();   // the axisLeaf values act only with skeletonShape=fd
            if (AXIS_LEAF_SMART && leafRule) {
                String ln = normLabel(n);
                int rank = (labelPair(n, partners) ? 16 : 0) + (chemical ? 0 : 8) + (CURRENCY.contains(ln) ? 0 : 4)
                        + (hasCloneMarker(n) ? 0 : 2) + (UBIQUITOUS.contains(ln) ? 0 : 1);
                score += 3 * rank;   // the direction term lies in [-1, 1]: a higher rank always wins
            } else {
                if (!chemical) score += 2;
                else if (AXIS_LEAF_NOCLONE && leafRule && !hasCloneMarker(n)) score += 1.5;
                if (AXIS_LEAF_LABELPAIR && leafRule && labelPair(n, partners)) score += 1.5;
                if (AXIS_LEAF_CURRENCY && leafRule && CURRENCY.contains(normLabel(n))) score -= 1.5;
            }
            if (score > bestScore) { bestScore = score; best = n; }
        }
        return best;
    }

    /** axisLeaf: the normalised label of {@code n} equals that of one of {@code partners}. */
    private static boolean labelPair(Node n, List<Node> partners) {
        String ln = normLabel(n);
        if (ln.isEmpty()) return false;
        for (Node q : partners) if (ln.equals(normLabel(q))) return true;
        return false;
    }

    /** axisLeaf: SBGN-ED stores the clone marker as a negative graphics.gradient (see org.sbgned.ValidateSBGN.hasClonemarker). */
    private static boolean hasCloneMarker(Node n) {
        try {
            Object v = AttributeHelper.getAttributeValue(n, "graphics", "gradient", 0d, 0d, false);
            return v instanceof Double && (Double) v < 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Half width for a horizontal side, half height for a vertical one. */
    private static double halfExtent(Node n, String side) {
        return dirX(side) != 0 ? AttributeHelper.getWidth(n) / 2 : AttributeHelper.getHeight(n) / 2;
    }

    /**
     * Distance along {@code side} at which {@code n} clears the corner of {@code axisNode}, which
     * sits at {@code axisDist} along the perpendicular {@code axisSide}: 0 if the two cannot meet
     * along the axis at all, else side by side plus half the star gap.
     */
    private static double clearCorner(Node n, String side, Node axisNode, String axisSide, double axisDist) {
        double gap = STAR_GAP / 2;
        if (axisDist - halfExtent(axisNode, axisSide) >= halfExtent(n, axisSide) + gap) return 0;
        return halfExtent(axisNode, side) + halfExtent(n, side) + gap;
    }

    /** Centre distance of a leaf pinned on the axis: the star radius, unless the two nodes are too large for it. */
    private static double axisRadius(Node process, Node leaf, String side) {
        return Math.max(STAR_RADIUS, halfExtent(process, side) + halfExtent(leaf, side) + STAR_GAP);
    }

    private void pinOnAxis(Node process, Node leaf, String side) {
        double r = axisRadius(process, leaf, side);
        pin(process, leaf, dirX(side) * r, dirY(side) * r);
    }

    /** The skeleton / cycle neighbour of the process that sits on {@code side} (step 3 separates it there), or null. */
    private Node structuralOnSide(ProcessComponent p, Node process, String side) {
        List<List<Node>> groups = new ArrayList<>();
        groups.add(p.substrates); groups.add(p.products); groups.add(p.revNodesLHS); groups.add(p.revNodesRHS);
        for (List<Node> g : groups) {
            for (Node n : g) {
                if ((isCycleFixed(process, n) || skeletonNodeLookup.containsValue(n)) && side.equals(checkOrientation(process, n))) return n;
            }
        }
        return null;
    }

    /**
     * -Dcblayout.rowCap: {@code row} in chunks of at most rowCap leaves. With {@code altSide} (rowSides=both and that
     * side free) the chunks alternate between the two perpendicular sides and every second chunk starts a new ring;
     * without it every chunk is a ring of its own, one node width plus the star gap further out along the axis.
     * Returns the far end of the rows on {@code rowSide} (the enzyme chain starts beyond it when it shares the side).
     */
    private double pinRows(Node process, List<Node> row, String axisSide, String rowSide, String altSide, double axisOff, Node axisNode) {
        if (row.isEmpty()) return 0;
        if (ROW_CAP <= 0 || row.size() <= ROW_CAP) return pinRow(process, row, axisSide, rowSide, axisOff, axisOff, axisNode);
        double ringStep = 0;
        for (Node n : row) ringStep = Math.max(ringStep, 2 * halfExtent(n, axisSide) + STAR_GAP);
        double far = 0;
        int chunk = 0;
        for (int start = 0; start < row.size(); start += ROW_CAP, chunk++) {
            List<Node> part = new ArrayList<>(row.subList(start, Math.min(row.size(), start + ROW_CAP)));
            boolean alt = altSide != null && chunk % 2 == 1;
            int ring = altSide != null ? chunk / 2 : chunk;
            double shift = ring * ringStep;
            double f = pinRow(process, part, axisSide, alt ? altSide : rowSide, axisOff + shift,
                    axisOff + (ROW_RING_DIAGONAL ? shift : 0), ring == 0 ? axisNode : null);
            if (!alt) far = Math.max(far, f);
        }
        return far;
    }

    /**
     * Cofactor row on the diagonal: first node at {@code axisOff} along {@code axisSide} and at
     * least {@code perpStart} along {@code rowSide} (45 degrees for the star radius), the next ones further
     * out along {@code rowSide} (away from the enzyme), all on one line parallel to the axis.
     * The first node is slid outwards along {@code rowSide} until it clears the process and the
     * node on the axis ({@code axisNode}: the main substrate / product, or a skeleton neighbour
     * that step 3 may put as close as touching), which keeps the main substrate / product at the star radius.
     * Returns the offset of the last row node along {@code rowSide} (0 for an empty row).
     */
    private double pinRow(Node process, List<Node> row, String axisSide, String rowSide, double axisOff, double perpStart, Node axisNode) {
        if (row.isEmpty()) return 0;
        Node first = row.get(0);
        double perp = perpStart;   // the base rule starts the row on the 45-degree diagonal: perpStart == axisOff
        double gap = STAR_GAP / 2;
        // positions along the row (perp is added later): the pitch, or side by side plus the star gap for large nodes
        double[] alongAt = new double[row.size()];
        double along = 0;
        for (int i = 1; i < row.size(); i++) {
            along += Math.max(STAR_ROW_SPACING, halfExtent(row.get(i - 1), rowSide) + halfExtent(row.get(i), rowSide) + STAR_GAP);
            alongAt[i] = along;
        }
        // -Dcblayout.rowShape=fan: the row moves out along the axis to rowFan * its length, so the arcs to its nodes
        // fan out with about rowFan * pitch of clearance each instead of passing through the nodes before them
        double ax0 = ROW_SHAPE_FAN && fdVariant() ? Math.max(axisOff, ROW_FAN * (perp + along)) : axisOff;
        // off the process: overlapping along the axis is fine only if the row keeps clear sideways
        if (ax0 < halfExtent(process, axisSide) + halfExtent(first, axisSide) + gap) {
            perp = Math.max(perp, halfExtent(process, rowSide) + halfExtent(first, rowSide) + gap);
        }
        // off the node on the axis
        if (axisNode != null) {
            double axisDist = axisNode.getDegree() <= 1 ? axisRadius(process, axisNode, axisSide)
                    : halfExtent(process, axisSide) + halfExtent(axisNode, axisSide);
            if (Math.abs(axisDist - ax0) < halfExtent(axisNode, axisSide) + halfExtent(first, axisSide) + gap) {
                perp = Math.max(perp, halfExtent(axisNode, rowSide) + halfExtent(first, rowSide) + gap);
            }
        }
        if (!"fixed".equals(ROW_PINS)) {
            pinRowSoft(process, row, axisSide, rowSide, ax0, perp, alongAt);
            return perp + along;
        }
        for (int i = 0; i < row.size(); i++) {
            Node n = row.get(i);
            double ax = ax0;
            // -Dcblayout.rowPull=px: the i-th further row node moves i*px back towards the perpendicular (clamped), so
            // the arc to it does not graze the node before it; not for lens processes with lensStar=outer, not with rowShape=fan
            if (i > 0 && ROW_PULL > 0 && fdVariant() && !ROW_SHAPE_FAN && !(LENS_STAR_OUTER && lensInner.containsKey(process))) {
                ax = Math.max(axisOff - i * ROW_PULL, Math.max(halfExtent(process, axisSide) + halfExtent(n, axisSide) + 1,
                        halfExtent(n, axisSide) + STAR_GAP / 4));
            }
            double ox = dirX(axisSide) * ax + dirX(rowSide) * (perp + alongAt[i]);
            double oy = dirY(axisSide) * ax + dirY(rowSide) * (perp + alongAt[i]);
            pin(process, n, ox, oy);
            rowLeafSide.put(n, rowSide);
        }
        return perp + along;
    }

    /**
     * -Dcblayout.rowPins=soft|distribute: the row as minimum separations instead of equalities, so cola's overlap removal
     * and the force-directed pass can still slide it: one alignment of all row nodes along the axis (they share the
     * offset {@code ax}), the first node at least {@code ax} along the axis and {@code perp} along the row side from
     * the process, then the pitch between neighbours; distribute adds equal spacing along the row.
     */
    private void pinRowSoft(Node process, List<Node> row, String axisSide, String rowSide, double ax, double perp, double[] alongAt) {
        boolean axisHorizontal = dirX(axisSide) != 0;
        SeparationConstraintGraph.SeparationConstraintType alongAxis = axisHorizontal
                ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL : SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
        SeparationConstraintGraph.SeparationConstraintType alongRow = axisHorizontal
                ? SeparationConstraintGraph.SeparationConstraintType.VERTICAL : SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
        if (row.size() > 1) {
            List<Node> all = new ArrayList<>(row);
            ConstraintStore.constraints.addConstraint(axisHorizontal
                    ? new VerticalConstraint(all, VerticalConstraint.VerticalConstraintType.MIDDLE)
                    : new HorizontalConstraint(all, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
        }
        addMinSeparation(process, row.get(0), axisSide, ax, alongAxis);
        addMinSeparation(process, row.get(0), rowSide, perp, alongRow);
        for (int i = 1; i < row.size(); i++) addMinSeparation(row.get(i - 1), row.get(i), rowSide, alongAt[i] - alongAt[i - 1], alongRow);
        if ("distribute".equals(ROW_PINS) && row.size() > 2) {
            ConstraintStore.constraints.addConstraint(new DistributionConstraintGraph(new ArrayList<>(row), alongRow, STAR_ROW_SPACING));
        }
        for (Node n : row) rowLeafSide.put(n, rowSide);
    }

    /** Minimum separation {@code distance} between {@code from} and {@code to}, {@code to} lying on {@code side} of {@code from}. */
    private static void addMinSeparation(Node from, Node to, String side, double distance, SeparationConstraintGraph.SeparationConstraintType type) {
        List<Node> pair = new ArrayList<>();
        if (side.equals("left") || side.equals("up")) { pair.add(to); pair.add(from); } else { pair.add(from); pair.add(to); }
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(pair, type, false, distance));
    }

    /** Pin {@code leaf} at offset (ox, oy) from the process: fixed separations, alignment where an offset is zero. */
    private void pin(Node process, Node leaf, double ox, double oy) {
        List<Node> pair;
        if (Math.abs(ox) < 1e-6) {
            pair = new ArrayList<>(); pair.add(process); pair.add(leaf);
            ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
        } else {
            pair = new ArrayList<>();
            if (ox < 0) { pair.add(leaf); pair.add(process); } else { pair.add(process); pair.add(leaf); }
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(pair,
                    SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL, true, Math.abs(ox)));
        }
        if (Math.abs(oy) < 1e-6) {
            pair = new ArrayList<>(); pair.add(process); pair.add(leaf);
            ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
        } else {
            pair = new ArrayList<>();
            if (oy < 0) { pair.add(leaf); pair.add(process); } else { pair.add(process); pair.add(leaf); }
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(pair,
                    SeparationConstraintGraph.SeparationConstraintType.VERTICAL, true, Math.abs(oy)));
        }
    }

    /** -Dcblayout.stubGap=px (30): extra gap between a process and its modulator stub node, on top of the half extents. */
    private static final double STUB_GAP = doubleProperty("cblayout.stubGap", 30);

    /**
     * Keep a modulator (catalysis / stimulation) on the side its stub was assigned to. The
     * alignment alone only says "on the process line"; without a separation the FD may push the
     * node to the opposite side and the routed stub then points the wrong way.
     * {@code side} is the value stored in {@link #orientationStim} ("left", "right", "up", "down").
     */
    private static void addStubSeparation(Node process, Node connector, String side) {
        if (side == null) return;
        switch (side) {
            case "left":
                addGapSeparation(connector, process, true, STUB_GAP);
                break;
            case "right":
                addGapSeparation(process, connector, true, STUB_GAP);
                break;
            case "up":
                addGapSeparation(connector, process, false, STUB_GAP);
                break;
            case "down":
                addGapSeparation(process, connector, false, STUB_GAP);
                break;
        }
    }

    /**
     * Minimum separation of {@code second} after {@code first} along x ({@code horizontal}) or y: their half extents
     * plus {@code gap}.
     */
    private static void addGapSeparation(Node first, Node second, boolean horizontal, double gap) {
        double distance = horizontal
                ? AttributeHelper.getWidth(first) / 2 + AttributeHelper.getWidth(second) / 2 + gap
                : AttributeHelper.getHeight(first) / 2 + AttributeHelper.getHeight(second) / 2 + gap;
        List<Node> pair = new ArrayList<>(Arrays.asList(first, second));
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(pair,
                horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                           : SeparationConstraintGraph.SeparationConstraintType.VERTICAL,
                false, distance));
    }

    /**
     * -Dcblayout.mfAxis=pair (default; any other value: the first structural substrate): a process with two or more
     * structural substrates or products takes its axis from the most opposed substrate / product pair that already
     * lies on opposite sides (pairs fixed by a ring and label-equal pairs first).
     */
    static final boolean MF_AXIS_PAIR = "pair".equalsIgnoreCase(System.getProperty("cblayout.mfAxis", "pair"));

    /**
     * -Dcblayout.graphSeed=skeleton|input (default skeleton): the graph FD pass starts from the skeleton FD positions
     * (see seedGraphFromSkeleton) or from the input positions.
     */
    static final boolean GRAPH_SEED_SKELETON = "skeleton".equalsIgnoreCase(System.getProperty("cblayout.graphSeed", "skeleton"));
    /**
     * -Dcblayout.bundles=lens|off (default lens): parallel processes between the same two species, off the selected
     * rings, become a lens or fan.
     */
    static final boolean BUNDLES_LENS = "lens".equalsIgnoreCase(System.getProperty("cblayout.bundles", "lens"));
    /** -Dcblayout.fanSpacing=px (default 150): distance between neighbouring processes of a bundle fan (3+ processes). */
    static final double FAN_SPACING = doubleProperty("cblayout.fanSpacing", 150);
    /**
     * -Dcblayout.modSide=reserve|off (default reserve): step 4 reserves a skeleton modulator's side before the cofactor
     * side is chosen.
     */
    static final boolean MOD_RESERVE = "reserve".equalsIgnoreCase(System.getProperty("cblayout.modSide", "reserve"));
    /**
     * -Dcblayout.rowPull=px (default 40, 0 = off): further nodes of a diagonal cofactor row move towards the
     * perpendicular (see pinRow).
     */
    static final double ROW_PULL = doubleProperty("cblayout.rowPull", 40);
    /**
     * -Dcblayout.splitRow=true|false (default true): only the first structural member of a port group keeps its step-2
     * alignment on the process axis.
     */
    static final boolean SPLIT_ROW = Boolean.parseBoolean(System.getProperty("cblayout.splitRow", "true"));
    /**
     * -Dcblayout.tagClip=true|false (default true): route bends inside a restored tag are dropped (every skeleton
     * variant).
     */
    static final boolean TAG_CLIP = Boolean.parseBoolean(System.getProperty("cblayout.tagClip", "true"));
    /** -Dcblayout.axisLeaf=off|labelpair|noclone|currency|smart (default smart): which leaf takes a free axis slot (see closestTo). */
    static final String AXIS_LEAF = System.getProperty("cblayout.axisLeaf", "smart").toLowerCase();
    static final boolean AXIS_LEAF_LABELPAIR = "labelpair".equals(AXIS_LEAF);
    static final boolean AXIS_LEAF_NOCLONE = "noclone".equals(AXIS_LEAF);
    static final boolean AXIS_LEAF_CURRENCY = "currency".equals(AXIS_LEAF);
    static final boolean AXIS_LEAF_SMART = "smart".equals(AXIS_LEAF);
    /** Normalised labels (see normLabel) of currency metabolites for axisLeaf=currency|smart. */
    static final Set<String> CURRENCY = new HashSet<>(Arrays.asList("atp", "adp", "amp", "gtp", "gdp", "utp", "udp", "ctp", "cdp",
            "pp", "ppi", "p", "pi", "pyrophosphate", "phosphate", "orthophosphate", "nad+", "nadh", "nadp+", "nadph", "h+", "h2o",
            "co2", "o2", "coa", "hco3", "hco3-", "nh3", "nh4+", "fad", "fadh2", "h2o2"));
    /** axisLeaf=smart, lowest tier: protons and water lose even against another currency leaf (carbonic anhydrase: HCO3- before H+). */
    static final Set<String> UBIQUITOUS = new HashSet<>(Arrays.asList("h+", "h", "proton", "h2o", "water"));
    /**
     * -Dcblayout.gateInputs=far|off (default far): a leaf input of a logic gate that modulates a process lies beyond
     * the gate (see placeModulator).
     */
    static final boolean GATE_INPUTS_FAR = "far".equalsIgnoreCase(System.getProperty("cblayout.gateInputs", "far"));

    /** modSide=reserve: the side step 4 reserved per process for a skeleton modulator, reused by placeModulator. */
    private final Map<Node, Map<Node, String>> reservedModSides = new HashMap<>();

    /**
     * -Dcblayout.rowCap=N (default 0 = unlimited): a cofactor row holds at most N leaves; the rest go to the other
     * perpendicular side (rowSides=both, if free) or to further rings along the axis (see pinRows).
     */
    public static int ROW_CAP = intProperty("cblayout.rowCap", 0);
    /**
     * -Dcblayout.rowSides=both|one (default one): with rowCap, the second chunk of a row goes to the opposite
     * perpendicular side when no enzyme, skeleton neighbour or lens partner is there.
     */
    public static boolean ROW_SIDES_BOTH = "both".equalsIgnoreCase(System.getProperty("cblayout.rowSides", "one"));
    /**
     * -Dcblayout.rowRing=diagonal|axis (default diagonal): with rowCap, ring r starts r ring steps further out along
     * the axis and, with diagonal, also r ring steps further along the row side, so the rings form a 45-degree band
     * whose arcs pass between the nearer rings; axis stacks the rings side by side.
     */
    public static boolean ROW_RING_DIAGONAL = !"axis".equalsIgnoreCase(System.getProperty("cblayout.rowRing", "diagonal"));
    /**
     * -Dcblayout.rowShape=fan|column (default column): fan moves a row out along the axis in proportion to its length
     * (rowFan x length), so the arcs fan out instead of running along the column (see pinRow).
     */
    public static boolean ROW_SHAPE_FAN = "fan".equalsIgnoreCase(System.getProperty("cblayout.rowShape", "column"));
    /**
     * -Dcblayout.rowFan=f (default 0.6): the factor of rowShape=fan; the arc to the i-th row node clears the (i-1)-th
     * by about f x pitch.
     */
    public static double ROW_FAN = doubleProperty("cblayout.rowFan", 0.6);
    /**
     * -Dcblayout.rowStub=side|axis (default axis): the router stub of an arc to a row leaf points to the row's side
     * instead of along the axis.
     */
    public static boolean ROW_STUB_SIDE = "side".equalsIgnoreCase(System.getProperty("cblayout.rowStub", "axis"));
    /**
     * -Dcblayout.rowPins=fixed|soft|distribute (default fixed): soft pins a row with minimum separations and one
     * alignment along the axis instead of equalities; distribute adds equal spacing (see pinRowSoft).
     */
    public static String ROW_PINS = System.getProperty("cblayout.rowPins", "fixed").toLowerCase();
    /**
     * -Dcblayout.sideAlign=keep|drop|swap (default keep): drop and swap make step 3 remove a step-2 alignment of a
     * structural member across the process axis before adding the side separation (swap: and align the member on the
     * axis).
     */
    public static int SIDE_ALIGN = "swap".equalsIgnoreCase(System.getProperty("cblayout.sideAlign", "keep")) ? 2
            : "drop".equalsIgnoreCase(System.getProperty("cblayout.sideAlign", "keep")) ? 1 : 0;
    /** -Dcblayout.sideGap=px (default 0): gap added to the touching step-3 side separations. */
    public static double SIDE_GAP = doubleProperty("cblayout.sideGap", 0);
    /**
     * -Dcblayout.modRoles=all|catalysis (default catalysis): all counts inhibition, modulation and unknown influence as
     * modulation arcs too (no skeleton alignment, a stub side in step 5).
     */
    public static boolean MOD_ROLES_ALL = "all".equalsIgnoreCase(System.getProperty("cblayout.modRoles", "catalysis"));
    /**
     * -Dcblayout.modDropAlign=true|false (default false): step 5 removes the step-2 alignment of a skeleton modulator
     * that contradicts its stub side (as hubSpread does for species).
     */
    public static boolean MOD_DROP_ALIGN = Boolean.parseBoolean(System.getProperty("cblayout.modDropAlign", "false"));
    /** -Dcblayout.hubSpreadRev=true|false (default false): hubSpread also for reversible processes. */
    public static boolean HUB_SPREAD_REV = Boolean.parseBoolean(System.getProperty("cblayout.hubSpreadRev", "false"));
    /**
     * -Dcblayout.hubFan=true|false (default false): a process on an already taken side of a hub sits beside the process
     * that took it (see fanBesideHub).
     */
    public static boolean HUB_FAN = Boolean.parseBoolean(System.getProperty("cblayout.hubFan", "false"));
    /**
     * -Dcblayout.complexRigid=true|false (default false): a complex and the glyphs inside it move as one rigid group in
     * every solver call (see registerRigidComplexes).
     */
    public static boolean COMPLEX_RIGID = Boolean.parseBoolean(System.getProperty("cblayout.complexRigid", "false"));
    /** -Dcblayout.skelSep=all: every remaining flow edge of the skeleton keeps the order the FD gave it (implies moved). */
    public static boolean SKEL_SEP_ALL = "all".equalsIgnoreCase(System.getProperty("cblayout.skelSep", "moved"));
    /**
     * -Dcblayout.logRemovals=true|false (default true): alignments a step removed on purpose (hubSpread, splitRow,
     * bundles, sideAlign, modDropAlign, hubFan) appear in the conflict log (phase "Removed"), so every constraint that
     * goes leaves a reason.
     */
    public static boolean LOG_REMOVALS = Boolean.parseBoolean(System.getProperty("cblayout.logRemovals", "true"));

    /** Row leaf -> the perpendicular side of its row (rowStub=side). */
    private final Map<Node, String> rowLeafSide = new HashMap<>();
    /** hubFan: hub -> side -> the last process placed on that side. */
    private static final Map<Node, Map<String, Node>> hubLast = new HashMap<>();

    /** As {@link #doubleProperty}, for an integer. */
    public static int intProperty(String key, int def) {
        String v = System.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("Ignoring -D" + key + "=" + v + " (not a number), using " + def);
            return def;
        }
    }

    /**
     * Runs {@code body} and puts every constraint it added to the store into {@code group} (a new group with this kind,
     * label and step if {@code group} is null). Grouping is metadata for the constraint list; it changes no solve.
     * Returns the group (null if the body added nothing and none was given).
     */
    private ConstraintGroup grouped(ConstraintGroup group, String kind, String label, String step, Runnable body) {
        if (ConstraintStore.constraints == null) {
            body.run();
            return group;
        }
        Set<Constraint> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(ConstraintStore.constraints.getAllConstraints());
        body.run();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (before.contains(c) || c.getGroup() != null) continue;
            if (group == null) group = new ConstraintGroup(kind, label, step);
            group.add(c);
        }
        return group;
    }

    /** The label of a node for a group name: its label, or its role and id for an unlabelled glyph (a process). */
    static String groupName(Node n) {
        String l = AttributeHelper.getLabel(n, "");
        l = l == null ? "" : l.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (!l.isEmpty()) return l;
        String role = getSBGNRole(n);
        return (role.isEmpty() ? "node" : role.toLowerCase(Locale.ROOT)) + " #" + n.getID();
    }

    /** " (a, b, c)" with the labels of the first few labelled nodes of a ring, so the group can be told from others. */
    private static String ringNames(List<Node> ring) {
        List<String> l = new ArrayList<>();
        for (Node n : ring) {
            String s = AttributeHelper.getLabel(n, "");
            s = s == null ? "" : s.replaceAll("<[^>]*>", " ").trim();
            if (!s.isEmpty()) l.add(s);
            if (l.size() == 3) break;
        }
        return l.isEmpty() ? "" : " (" + String.join(", ", l) + (ring.size() > 3 ? ", …" : "") + ")";
    }

    /** "process X" for a process: its label if it has one, else the labels of what it joins, so the user can find it. */
    private String processName(Node process) {
        String l = AttributeHelper.getLabel(process, "");
        l = l == null ? "" : l.replaceAll("<[^>]*>", " ").trim();
        if (!l.isEmpty()) return l;
        ProcessComponent p = processComponents == null ? null : getProcessComponent(process);
        if (p != null) {
            Node s = !p.substrates.isEmpty() ? p.substrates.get(0) : !p.revNodesLHS.isEmpty() ? p.revNodesLHS.get(0) : null;
            Node q = !p.products.isEmpty() ? p.products.get(0) : !p.revNodesRHS.isEmpty() ? p.revNodesRHS.get(0) : null;
            if (s != null || q != null) return (s == null ? "?" : groupName(s)) + " → " + (q == null ? "?" : groupName(q));
        }
        return groupName(process);
    }

    /** Records an alignment the pipeline removed on purpose (-Dcblayout.logRemovals). */
    static void logRemoval(Constraint c, String reason) {
        if (!LOG_REMOVALS) return;
        ConflictLog.addDropped("Removed", Collections.singletonList(new ConstraintFeasibility.Dropped(c, reason)), Collections.<String>emptyList());
    }

    private static List<Node> structural(List<Node> nodes) {
        List<Node> l = new ArrayList<>();
        for (Node n : nodes) if (n.getDegree() > 1 && !l.contains(n)) l.add(n);
        return l;
    }

    private static String normLabel(Node n) {
        String l = labelOrNull(n);
        if (l == null) return "";
        return l.replaceAll("<[^>]*>", "").replaceAll("[\\s\\-]", "").toLowerCase();
    }

    /** The label of {@code n}, or null if it has none or it cannot be read. */
    private static String labelOrNull(Node n) {
        try {
            return AttributeHelper.getLabel(n, (String) null);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** mfAxis=pair: axis from the substrate / product pair that already lies on opposite sides; true if the orientation was set. */
    private boolean chooseAxisFromOpposedPair(ProcessComponent p, Map<Node, Set<String>> hubSides) {
        Node pr = p.processNode;
        boolean rev = !p.revNodesLHS.isEmpty() || !p.revNodesRHS.isEmpty();
        if (rev ? p.getRevOrientation() != -1 : p.getProdSubOrientation() != -1) return false;
        List<Node> in = structural(union(p.substrates, p.revNodesLHS));
        List<Node> out = structural(union(p.products, p.revNodesRHS));
        if (in.isEmpty() || out.isEmpty() || (in.size() < 2 && out.size() < 2)) return false;
        Node bestIn = null;
        double best = Double.POSITIVE_INFINITY;
        double px = AttributeHelper.getPositionX(pr), py = AttributeHelper.getPositionY(pr);
        for (Node a : in) {
            for (Node b : out) {
                String sa = checkOrientation(pr, a), sb = checkOrientation(pr, b);
                if (sa.isEmpty() || !sb.equals(opposite(sa))) continue;
                // cosine of the angle a-process-b (-1 = straight through); ring-fixed and label-equal pairs first
                double ax = AttributeHelper.getPositionX(a) - px, ay = AttributeHelper.getPositionY(a) - py;
                double bx = AttributeHelper.getPositionX(b) - px, by = AttributeHelper.getPositionY(b) - py;
                double score = (ax * bx + ay * by) / Math.max(1e-9, Math.hypot(ax, ay) * Math.hypot(bx, by));
                if (isCycleFixed(pr, a)) score -= 4;
                if (isCycleFixed(pr, b)) score -= 4;
                if (normLabel(a).equals(normLabel(b))) score -= 2;
                if (score < best) { best = score; bestIn = a; }
            }
        }
        if (bestIn == null) return false;
        String side = checkOrientation(pr, bestIn);
        if (!rev || (HUB_SPREAD_REV && fdVariant())) side = hubSide(bestIn, side, pr, hubSides);
        int ori = dirX(side) != 0 ? 0 : 1;
        boolean sw = side.equals("right") || side.equals("down");
        if (rev) { p.setRevOrientation(ori); p.setRevSwitch(sw); }
        else { p.setProdSubOrientation(ori); p.setProdSubSwitch(sw); }
        return true;
    }

    private void determineProcessOrientation() {
        if (topologyPlan != null) {
            topologyPlan.applyProcessOrientations(processComponents);
            return;
        }
        // Which sides of a shared species are already taken by a process (see freeHubSide).
        Map<Node, Set<String>> hubSides = new HashMap<>();
        hubLast.clear();
        for (ProcessComponent p : processComponents) {
            if (MF_AXIS_PAIR && fdVariant() && chooseAxisFromOpposedPair(p, hubSides)) continue;
            Node process = p.processNode;
            if (p.getProdSubOrientation() == -1 && !p.substrates.isEmpty()) {
                Node n = firstStructural(p.substrates);
                if (n != null) {
                    setAxis(p, false, hubSide(n, checkOrientation(process, n), process, hubSides), false);
                } else if (!p.products.isEmpty()) {
                    Node m = structuralOrFirst(p.products);
                    setAxis(p, false, hubSide(m, checkOrientation(process, m), process, hubSides), true);
                }
            }
            if (p.getRevOrientation() == -1 && (!p.revNodesRHS.isEmpty() || !p.revNodesLHS.isEmpty())) {
                // Take the axis from a structural neighbour (degree > 1: skeleton or cycle) first;
                // a leaf cofactor says nothing about the flow direction.
                Node n = structuralOrFirst(p.revNodesRHS);
                boolean fromRHS = n != null;
                if (n == null) n = structuralOrFirst(p.revNodesLHS);
                setAxis(p, true, revHubSide(n, process, hubSides), fromRHS);
            }
        }
    }

    /**
     * Sets the substrate / product axis of {@code p}, or its reversible axis if {@code rev}, from the side of the
     * process a substrate (LHS) lies on, or a product (RHS) if {@code productSide}; any other side than left, right,
     * up and down sets nothing.
     */
    private static void setAxis(ProcessComponent p, boolean rev, String side, boolean productSide) {
        int ori;
        boolean sw;
        switch (side) {
            case "left":
                ori = 0;
                sw = false;
                break;
            case "right":
                ori = 0;
                sw = true;
                break;
            case "up":
                ori = 1;
                sw = false;
                break;
            case "down":
                ori = 1;
                sw = true;
                break;
            default:
                return;
        }
        if (productSide) sw = !sw;
        if (rev) {
            p.setRevOrientation(ori);
            p.setRevSwitch(sw);
        } else {
            p.setProdSubOrientation(ori);
            p.setProdSubSwitch(sw);
        }
    }

    /**
     * Removes the copies of the graph's leaves (fewer than two neighbours) from the skeleton, except processes,
     * associations and submaps.
     */
    private void createSkeletonOnlyLeafs() {
        ArrayList<Node> toRemove = new ArrayList<>();

        for (Node n : graph.getNodes()) {
            if (n.getNeighbors().size() < 2) {
                if (!getSBGNRole(n).contains("PROCESS") && !getSBGNRole(n).contains("ASSOCIATION") && !getSBGNRole(n).contains("SUBMAP")) {
                    toRemove.add(skeletonCopies.get(n));
                }
            }
        }

        for (Node n : toRemove) {
            if (skeleton.containsNode(n)) {
                skeleton.deleteNode(n);
            }
        }
    }

    private void addSkeletonConstraints() {

        if (!usesTopologySkeleton()) {
            CBLayoutAlgorithm.executeFD(skeleton);
            // -Dcblayout.graphSeed=skeleton: axes, corner sides and orders are read from the skeleton FD, but the solver
            // starts from the graph FD; seeding the graph FD with the skeleton FD positions keeps the two consistent.
            if (GRAPH_SEED_SKELETON && fdVariant()) seedGraphFromSkeleton();
            CBLayoutAlgorithm.executeFD(graph);
        }

        // One cycle per biconnected block of the skeleton (linear time, see CycleSelector); each
        // becomes an axis-parallel rectangle (or a lens, see embedCycle). Two blocks share at most one
        // node, so the rectangles can never contradict each other.
        List<Cycle> cycles = CycleSelector.select(skeleton,
                usesTopologySkeleton() ? Integer.MAX_VALUE : CycleSelector.MAX_CYCLE_EDGES);
        // A split and merge is an undirected cycle, but its information still flows forward.
        // Only coherent directed rings get a rectangle in the global topology plan.
        if (usesTopologySkeleton()) cycles.removeIf(c -> !TopologySkeleton.isDirectedRing(c));
        List<List<Node>> embeddedRings = new ArrayList<>();
        Set<Node> cycleSkeletonNodes = new HashSet<>();
        for (Cycle cycle : cycles) {
            if (usesTopologySkeleton() && cycle.getEdges().stream().anyMatch(e ->
                    cycleSkeletonNodes.contains(e.getSource()) || cycleSkeletonNodes.contains(e.getTarget()))) {
                continue; // shared-node rings stay in the feedback plan; a node belongs to one block
            }
            List<Node> ring = CycleSelector.ring(cycle, skeletonNodeLookup);
            boolean[] ok = { false };
            if (ring != null) grouped(null, "ring", "Ring of " + ring.size() + " nodes" + ringNames(ring), "Skeleton", () -> ok[0] = embedCycle(cycle, ring));
            if (ok[0]) {
                embeddedRings.add(ring);
                for (Edge e : cycle.getEdges()) {
                    cycleSkeletonNodes.add(e.getSource());
                    cycleSkeletonNodes.add(e.getTarget());
                }
            }
        }

        if (usesTopologySkeleton()) {
            topologyPlan = new TopologySkeleton(graph, skeleton, skeletonNodeLookup);
            topologyPlan.run(embeddedRings);
            return;
        }

        // Precedence of the ring-like shapes (skeletonShape=fd):
        //  1. CycleSelector keeps one ring per biconnected block; embedCycle draws a selected two-species 4-ring as a
        //     lens (-Dcblayout.lens) and every other ring as a rectangle.
        //  2. -Dcblayout.bundles=lens: two or more processes between the same two structural species, none on an
        //     embedded ring or lens, become a lens (two) or a fan (three or more, see embedFan). If the two species
        //     already form a lens, its processes join the group and the fan replaces the lens. Their edges leave the
        //     candidates and their nodes count as ring nodes, so the corner rule skips them.
        //  3. -Dcblayout.cornerRule=species on the remaining candidates.
        Set<Edge> bundleEdges = BUNDLES_LENS && fdVariant() ? embedBundles(cycleSkeletonNodes) : Collections.<Edge>emptySet();

        // Constraints for the skeleton structure excluding the embedded cycles
        List<Edge> candidates = new ArrayList<>();
        for (Edge edge : skeleton.getEdges()) {
            boolean skip = false;
            for (Cycle cycle : cycles) {
                if (cycle.getEdges().contains(edge)) {
                    skip = true;
                    break;
                }
            }
            if (skip || bundleEdges.contains(edge)) {
                continue;
            }
            // Catalysis / stimulation arcs are placed by the stub step on a free side of their
            // process; an alignment by the FD angle would put the modulator on the flow axis.
            if (isModulationRole(getSBGNRole(edge))) {
                continue;
            }
            candidates.add(edge);
        }
        // skeletonShape=structure*: the direction comes from the structure of the skeleton, not from the angle of a
        // force-directed pass. Same constraint types, so steps 3 to 6 do not notice the difference.
        if (usesStructuralSkeleton()) {
            boolean fromInputMap = "structure-map".equals(SKELETON_SHAPE);
            // The cycle rectangles were built on original nodes; hand their occupied sides over in
            // skeleton-node terms so a chain does not attach where the ring already runs.
            Map<Node, Set<String>> takenBySkeletonNode = new HashMap<>();
            for (Map.Entry<Node, Set<String>> e : cycleSides.entrySet()) {
                Node copy = skeletonCopies.get(e.getKey());
                if (copy != null) takenBySkeletonNode.put(copy, e.getValue());
            }
            SkeletonShape shape = new SkeletonShape(skeleton, skeletonNodeLookup, originalPositions(), fromInputMap);
            shape.run(candidates, cycleSkeletonNodes, takenBySkeletonNode);
            return;
        }

        // Shortest first: when two neighbours compete for the same side of a node, the nearer one
        // keeps the alignment and the far one is not dragged onto that row.
        if (!"off".equals(SIDE_POLICY)) {
            candidates.sort(Comparator.comparingDouble(ConstraintSBGNLayout::skeletonEdgeLength));
        }

        // axis of every candidate from its FD angle; the corner rule may flip some before they are added
        Map<Edge, Boolean> axisOf = new HashMap<>();
        for (Edge e : candidates) axisOf.put(e, horizontalOrVertical(e.getSource(), e.getTarget()));
        Map<Edge, Node[]> sepOrder = new HashMap<>();
        if (CORNER_RULE_SPECIES) applyCornerRule(candidates, cycleSkeletonNodes, axisOf, sepOrder);

        Map<Node, Set<String>> alignedSides = new HashMap<>();
        for (Edge edge : candidates) {
            Node a = edge.getSource(), b = edge.getTarget();
            boolean horizontal = axisOf.get(edge);
            boolean rotated = false;
            if (!"off".equals(SIDE_POLICY)) {
                String sideA = alignmentSide(a, b, horizontal);
                String sideB = alignmentSide(b, a, horizontal);
                Set<String> usedA = alignedSides.computeIfAbsent(a, k -> new HashSet<>());
                Set<String> usedB = alignedSides.computeIfAbsent(b, k -> new HashSet<>());
                if (usedA.contains(sideA) || usedB.contains(sideB)) {
                    boolean placed = false;
                    if ("rotate".equals(SIDE_POLICY)) {
                        // Put it on the other axis instead, e.g. above a hub whose right is taken.
                        String altA = alignmentSide(a, b, !horizontal);
                        String altB = alignmentSide(b, a, !horizontal);
                        if (!usedA.contains(altA) && !usedB.contains(altB)) {
                            horizontal = !horizontal;
                            rotated = true;
                            sideA = altA;
                            sideB = altB;
                            placed = true;
                        }
                    }
                    if (!placed) {
                        continue;
                    }
                }
                usedA.add(sideA);
                usedB.add(sideB);
            }
            ArrayList<Node> toAdd = new ArrayList<>();
            toAdd.add(skeletonNodeLookup.get(a));
            toAdd.add(skeletonNodeLookup.get(b));
            if (horizontal) {
                ConstraintStore.constraints.addConstraint(new HorizontalConstraint(toAdd, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            } else {
                ConstraintStore.constraints.addConstraint(new VerticalConstraint(toAdd, VerticalConstraint.VerticalConstraintType.MIDDLE));
            }
            // only filled with -Dcblayout.skelSep=moved; the order was taken along the axis before a rotation
            Node[] ord = rotated ? null : sepOrder.get(edge);
            if (ord != null) {
                addGapSeparation(skeletonNodeLookup.get(ord[0]), skeletonNodeLookup.get(ord[1]), horizontal, CYCLE_GAP);
            } else if (SKEL_SEP_ALL && fdVariant() && CycleSelector.isFlowEdge(edge) && a != b) {
                // -Dcblayout.skelSep=all: the alignment only fixes the axis; the order along it is what the skeleton FD
                // shows now, and without a separation the later steps can fold the chain back.
                double d = horizontal ? AttributeHelper.getPositionX(b) - AttributeHelper.getPositionX(a)
                                      : AttributeHelper.getPositionY(b) - AttributeHelper.getPositionY(a);
                if (Math.abs(d) > 1e-6) {
                    addGapSeparation(skeletonNodeLookup.get(d > 0 ? a : b), skeletonNodeLookup.get(d > 0 ? b : a), horizontal, CYCLE_GAP);
                }
            }
        }
    }

    /**
     * bundles=lens (see the precedence note in addSkeletonConstraints): groups the processes off the embedded rings by
     * their single structural substrate and product species; each group of two or more becomes a lens or fan (embedFan).
     * A group whose species already form a ring lens takes in the lens processes, and one fan replaces the lens.
     * Returns the skeleton edges the lenses took; their end nodes are added to {@code cycleSkeletonNodes}.
     */
    private Set<Edge> embedBundles(Set<Node> cycleSkeletonNodes) {
        Set<Edge> bundleEdges = new HashSet<>();
        Map<String, List<Node>> groups = new LinkedHashMap<>();
        Map<String, Node[]> pairOf = new HashMap<>();
        for (ProcessComponent p : processComponents) {
            Node pr = p.processNode;
            if (cycleFixed.containsKey(pr) || skeletonCopies.get(pr) == null) continue;
            List<Node> in = structural(union(p.substrates, p.revNodesLHS));
            List<Node> out = structural(union(p.products, p.revNodesRHS));
            if (in.size() != 1 || out.size() != 1 || in.get(0) == out.get(0)) continue;
            String key = pairKey(in.get(0), out.get(0));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(pr);
            pairOf.put(key, new Node[]{in.get(0), out.get(0)});
        }
        for (Map.Entry<String, List<Node>> g : groups.entrySet()) {
            List<Node> procs = g.getValue();
            if (procs.size() < 2) continue;
            RingLens lens = ringLenses.get(g.getKey());
            if (lens != null) {   // one fan instead of a fan next to the lens at the same offsets
                for (Constraint c : lens.constraints) {
                    ConstraintStore.constraints.removeConstraint(c);
                    logRemoval(c, "removed: the lens becomes part of a fan");
                }
                lensInner.remove(lens.pa);
                lensInner.remove(lens.pb);
                procs = new ArrayList<>(procs);
                procs.add(lens.pa);
                procs.add(lens.pb);
            }
            Node s1 = pairOf.get(g.getKey())[0], s2 = pairOf.get(g.getKey())[1];
            Set<Node> ps = new HashSet<>(procs);
            for (Edge e : skeleton.getEdges()) {
                Node a = skeletonNodeLookup.get(e.getSource()), b = skeletonNodeLookup.get(e.getTarget());
                if ((ps.contains(a) && (b == s1 || b == s2)) || (ps.contains(b) && (a == s1 || a == s2))) {
                    bundleEdges.add(e);
                    cycleSkeletonNodes.add(e.getSource());
                    cycleSkeletonNodes.add(e.getTarget());
                }
            }
            final List<Node> fanProcs = procs;
            grouped(null, "fan", "Fan " + groupName(s1) + " – " + groupName(s2), "Skeleton", () -> embedFan(s1, s2, fanProcs));
        }
        return bundleEdges;
    }

    /**
     * bundles=lens: {@code procs} (two or more) in parallel between s1 and s2. The species lie on one axis (the one
     * they lie on after the FD pass), the processes on the perpendicular through the middle, ordered by their FD
     * position across the axis, each at least {@code half} from both species along the axis. Two processes form
     * the lens of embedLens (offsets +-lensOffset). Three or more sit at offsets (i - (n-1)/2) x fanSpacing (an odd
     * middle one on the species axis) with half = 2.6 x the outer offset, so the outer processes see their species at
     * 2*atan(2.6) = 138 degrees like a lens process.
     */
    private void embedFan(Node s1, Node s2, List<Node> procs) {
        boolean horizontal = horizontalOrVertical(s1, s2);
        double c1 = horizontal ? AttributeHelper.getPositionX(s1) : AttributeHelper.getPositionY(s1);
        double c2 = horizontal ? AttributeHelper.getPositionX(s2) : AttributeHelper.getPositionY(s2);
        if (c1 > c2) { Node t = s1; s1 = s2; s2 = t; }
        final boolean hz = horizontal;
        List<Node> ps = new ArrayList<>(procs);
        ps.sort(Comparator.comparingDouble(q -> hz ? AttributeHelper.getPositionY(q) : AttributeHelper.getPositionX(q)));
        int n = ps.size();
        double spacing = n == 2 ? 2 * LENS_OFFSET : FAN_SPACING;
        double half = Math.max(Math.max(2.6 * LENS_OFFSET, STAR_RADIUS), n == 2 ? 0 : 2.6 * spacing * (n - 1) / 2);
        SeparationConstraintGraph.SeparationConstraintType along = horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                : SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
        SeparationConstraintGraph.SeparationConstraintType across = horizontal ? SeparationConstraintGraph.SeparationConstraintType.VERTICAL
                : SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
        List<Node> pair = new ArrayList<>(Arrays.asList(s1, s2));
        if (horizontal) ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
        else ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
        String fwd = horizontal ? "right" : "down", back = opposite(fwd);
        for (int i = 0; i < n; i++) {
            Node p = ps.get(i);
            if (i > 0) {   // all processes on one perpendicular line
                pair = new ArrayList<>(Arrays.asList(ps.get(i - 1), p));
                if (horizontal) ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
                else ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            }
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(s1, p)), along, false, half));
            ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(p, s2)), along, false, half));
            double off = (i - (n - 1) / 2.0) * spacing;
            if (Math.abs(off) < 1e-6) {
                pair = new ArrayList<>(Arrays.asList(s1, p));
                if (horizontal) ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
                else ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
            } else if (off < 0) {
                ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(p, s1)), across, true, -off));
            } else {
                ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(s1, p)), across, true, off));
            }
            for (Node sp : Arrays.asList(s1, s2)) {
                cycleFixed.computeIfAbsent(p, x -> new HashSet<>()).add(sp);
                cycleFixed.computeIfAbsent(sp, x -> new HashSet<>()).add(p);
            }
            cycleSides.computeIfAbsent(p, x -> new HashSet<>()).addAll(Arrays.asList(fwd, back));
            // the side facing the axis is the inner side (kept free, lensStar=outer turns the star away from it)
            if (off < -1e-6) lensInner.put(p, horizontal ? "down" : "right");
            else if (off > 1e-6) lensInner.put(p, horizontal ? "up" : "left");
        }
        cycleSides.computeIfAbsent(s1, x -> new HashSet<>()).add(fwd);
        cycleSides.computeIfAbsent(s2, x -> new HashSet<>()).add(back);
        // the fan is longer than the gap to the next process on the column: a process of an end species outside the
        // group that lies beyond the end (more along the species axis than across) is held beyond it
        for (Node end : Arrays.asList(s1, s2)) {
            boolean atFwd = end == s2;
            double ce = horizontal ? AttributeHelper.getPositionX(end) : AttributeHelper.getPositionY(end);
            double ae = horizontal ? AttributeHelper.getPositionY(end) : AttributeHelper.getPositionX(end);
            for (Node q : end.getNeighbors()) {
                if (procs.contains(q)) continue;
                ProcessComponent pq = getProcessComponent(q);
                if (pq == null || !(pq.substrates.contains(end) || pq.products.contains(end)
                        || pq.revNodesLHS.contains(end) || pq.revNodesRHS.contains(end))) continue;
                double d = (horizontal ? AttributeHelper.getPositionX(q) : AttributeHelper.getPositionY(q)) - ce;
                double a = (horizontal ? AttributeHelper.getPositionY(q) : AttributeHelper.getPositionX(q)) - ae;
                if ((atFwd ? d : -d) <= 0 || Math.abs(d) < Math.abs(a)) continue;
                if (atFwd) addGapSeparation(end, q, horizontal, CYCLE_GAP);
                else addGapSeparation(q, end, horizontal, CYCLE_GAP);
            }
        }
    }

    /** graphSeed=skeleton: graph nodes start at their skeleton FD position, a leaf 40 px from its first skeleton neighbour. */
    private void seedGraphFromSkeleton() {
        Map<Node, Integer> leafCount = new HashMap<>();
        for (Map.Entry<Node, Node> en : skeletonNodeLookup.entrySet()) {
            AttributeHelper.setPosition(en.getValue(), AttributeHelper.getPositionX(en.getKey()), AttributeHelper.getPositionY(en.getKey()));
        }
        Set<Node> inSkeleton = new HashSet<>(skeletonNodeLookup.values());
        for (Node n : layoutNodes(graph)) {
            if (inSkeleton.contains(n)) continue;
            Node anchor = null;
            for (Node nb : n.getNeighbors()) if (inSkeleton.contains(nb)) { anchor = nb; break; }
            if (anchor == null) continue;   // keeps its input position
            int i = leafCount.merge(anchor, 1, Integer::sum);
            double ang = i * 2.399963;      // golden angle: distinct directions for the leaves of one anchor
            AttributeHelper.setPosition(n, AttributeHelper.getPositionX(anchor) + 40 * Math.cos(ang), AttributeHelper.getPositionY(anchor) + 40 * Math.sin(ang));
        }
    }

    /** Distance between the two ends of a skeleton edge, in the current force-directed positions. */
    private static double skeletonEdgeLength(Edge e) {
        double dx = AttributeHelper.getPositionX(e.getTarget()) - AttributeHelper.getPositionX(e.getSource());
        double dy = AttributeHelper.getPositionY(e.getTarget()) - AttributeHelper.getPositionY(e.getSource());
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Which side of {@code from} the node {@code to} lies on, along the axis the alignment uses. */
    private static String alignmentSide(Node from, Node to, boolean horizontal) {
        if (horizontal) {
            return AttributeHelper.getPositionX(to) >= AttributeHelper.getPositionX(from) ? "right" : "left";
        }
        return AttributeHelper.getPositionY(to) >= AttributeHelper.getPositionY(from) ? "down" : "up";
    }

    /**
     * -Dcblayout.cornerRule=species|none (default species, skeletonShape=fd): step 2 moves bends from processes onto
     * species (see applyCornerRule).
     */
    static final boolean CORNER_RULE_SPECIES = "species".equalsIgnoreCase(System.getProperty("cblayout.cornerRule", "species"));
    /**
     * -Dcblayout.skelSep=moved|all|off (default moved): the two main edges of a process straightened by the corner rule
     * also get an order separation, so the solver cannot fold the process back.
     */
    public static boolean SKEL_SEP_MOVED = "moved".equalsIgnoreCase(System.getProperty("cblayout.skelSep", "moved")) || SKEL_SEP_ALL;

    /**
     * cornerRule=species: step 2 aligns every skeleton edge on its own FD angle, so a chain may bend at a process and
     * put its substrate and product at 90 degrees. For every process off the embedded rings whose main in- and
     * outflow edges disagree in axis, one of the two is flipped so the bend moves onto the neighbouring species:
     * never folding the process, never onto a species side another known edge already uses, and never cancelling a
     * species bend on a cycle (the cycle could not close any more). Among the allowed flips a moved bend beats a
     * cancelled one, then the edge whose angle was closest to 45 degrees. Flips entries of {@code axisOf}; fills
     * {@code sepOrder} with (first, second) skeleton nodes of the straightened edges.
     */
    private void applyCornerRule(List<Edge> candidates, Set<Node> ringNodes, Map<Edge, Boolean> axisOf, Map<Edge, Node[]> sepOrder) {
        Map<Node, List<Edge>> flowAt = new HashMap<>();
        for (Edge e : skeleton.getEdges()) {
            if (!CycleSelector.isFlowEdge(e) || e.getSource() == e.getTarget()) continue;
            flowAt.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e);
            flowAt.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e);
        }
        Set<Edge> cand = new HashSet<>(candidates);
        for (ProcessComponent p : processComponents) {
            Node pc = skeletonCopies.get(p.processNode);
            if (pc == null || !skeleton.containsNode(pc) || ringNodes.contains(pc)) continue;
            // main edges: first structural substrate / product with a candidate edge (list order, as step 3)
            Edge eIn = null, eOut = null;
            for (Node sp : union(p.substrates, p.revNodesLHS)) {
                eIn = candidateBetween(pc, skeletonCopies.get(sp), flowAt, cand);
                if (eIn != null) break;
            }
            for (Node sp : union(p.products, p.revNodesRHS)) {
                eOut = candidateBetween(pc, skeletonCopies.get(sp), flowAt, cand);
                if (eOut != null) break;
            }
            if (eIn == null || eOut == null || eIn == eOut || axisOf.get(eIn).equals(axisOf.get(eOut))) continue;
            Node sIn = otherEnd(eIn, pc), sOut = otherEnd(eOut, pc);
            Edge best = null;
            int bestKind = 9;
            double bestConf = 1e9;
            for (Edge e : new Edge[]{eIn, eOut}) {
                boolean newAxis = !axisOf.get(e);
                Node sp = otherEnd(e, pc);
                if (alignmentSide(pc, sIn, newAxis).equals(alignmentSide(pc, sOut, newAxis))) continue;   // would fold
                String sideAtSpecies = alignmentSide(sp, pc, newAxis);
                int known = 0;
                boolean clash = false;
                Boolean single = null;
                for (Edge f : flowAt.getOrDefault(sp, Collections.emptyList())) {
                    if (f == e) continue;
                    Boolean af = knownAxis(f, axisOf);
                    if (af == null) continue;
                    known++;
                    single = af;
                    if (af == newAxis && alignmentSide(sp, otherEnd(f, sp), af).equals(sideAtSpecies)) clash = true;
                }
                if (clash) continue;
                boolean cancel = known == 1 && single == newAxis;   // the species was a bend and becomes straight
                if (cancel && !isFlowBridge(e, flowAt)) continue;
                int kind = cancel ? 1 : 0;
                double conf = Math.abs(Math.toDegrees(Math.atan2(Math.abs(AttributeHelper.getPositionY(sp) - AttributeHelper.getPositionY(pc)),
                        Math.abs(AttributeHelper.getPositionX(sp) - AttributeHelper.getPositionX(pc)))) - 45);
                if (kind < bestKind || (kind == bestKind && conf < bestConf)) { best = e; bestKind = kind; bestConf = conf; }
            }
            if (best == null) continue;
            axisOf.put(best, !axisOf.get(best));
            if (SKEL_SEP_MOVED) {
                // the flip above excluded folding, so sIn and sOut lie on opposite sides along h
                boolean h = axisOf.get(eIn);
                double d = h ? AttributeHelper.getPositionX(sOut) - AttributeHelper.getPositionX(sIn)
                             : AttributeHelper.getPositionY(sOut) - AttributeHelper.getPositionY(sIn);
                boolean fwd = d >= 0;
                sepOrder.put(eIn, fwd ? new Node[]{sIn, pc} : new Node[]{pc, sIn});
                sepOrder.put(eOut, fwd ? new Node[]{pc, sOut} : new Node[]{sOut, pc});
            }
        }
    }

    private static Edge candidateBetween(Node pc, Node sc, Map<Node, List<Edge>> flowAt, Set<Edge> cand) {
        if (sc == null) return null;
        for (Edge e : flowAt.getOrDefault(pc, Collections.emptyList())) {
            if (cand.contains(e) && otherEnd(e, pc) == sc) return e;
        }
        return null;
    }

    private static Node otherEnd(Edge e, Node n) { return e.getSource() == n ? e.getTarget() : e.getSource(); }

    /** Axis of a skeleton flow edge if already decided: a candidate, or an edge of an embedded rectangle; else null. */
    private Boolean knownAxis(Edge f, Map<Edge, Boolean> axisOf) {
        if (axisOf.containsKey(f)) return axisOf.get(f);
        Node a = skeletonNodeLookup.get(f.getSource()), b = skeletonNodeLookup.get(f.getTarget());
        return a == null || b == null ? null : ringAxis.get(pairKey(a, b));
    }

    static String pairKey(Node a, Node b) {
        long x = a.getID(), y = b.getID();
        return Math.min(x, y) + "-" + Math.max(x, y);
    }

    /** Whether a skeleton flow edge is a bridge of the flow graph (lies on no cycle). */
    private static boolean isFlowBridge(Edge e, Map<Node, List<Edge>> flowAt) {
        Node s = e.getSource(), t = e.getTarget();
        Deque<Node> q = new ArrayDeque<>();
        Set<Node> seen = new HashSet<>();
        q.add(s);
        seen.add(s);
        while (!q.isEmpty()) {
            Node x = q.poll();
            for (Edge f : flowAt.getOrDefault(x, Collections.emptyList())) {
                if (f == e) continue;
                Node y = otherEnd(f, x);
                if (y == t) return false;
                if (seen.add(y)) q.add(y);
            }
        }
        return true;
    }

    /**
     * -Dcblayout.lens=true|false (default true, skeletonShape=fd): a selected ring of two species and two processes
     * (two reactions in parallel between the same species) becomes a lens instead of a 4-corner rectangle, where both
     * processes would sit in corners with their ring species at 90 degrees.
     */
    static final boolean LENS = Boolean.parseBoolean(System.getProperty("cblayout.lens", "true"));
    /**
     * -Dcblayout.lensStar=outer|inner (default outer): outer puts enzyme and cofactor rows of a lens process on the
     * side away from the partner process; inner only marks the inner side occupied.
     */
    static final boolean LENS_STAR_OUTER = "outer".equalsIgnoreCase(System.getProperty("cblayout.lensStar", "outer"));
    /** -Dcblayout.lensOffset=px (default 40): distance of each lens process from the species axis. */
    static final double LENS_OFFSET = doubleProperty("cblayout.lensOffset", 40);

    /** A malformed number falls back to the default: a parse error here would fail class loading and so every map. */
    public static double doubleProperty(String key, double def) {
        String v = System.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            System.err.println("Ignoring -D" + key + "=" + v + " (not a number), using " + def);
            return def;
        }
    }
    /**
     * -Dcblayout.lensRoles=conversion|any (default conversion): conversion makes a two-species 4-ring a lens only if
     * each of its two processes consumes one ring species and produces the other (reversible sides count), so no
     * substrate arc enters beside a product; any makes every two-species 4-ring a lens.
     */
    static final boolean LENS_ANY_ROLES = "any".equalsIgnoreCase(System.getProperty("cblayout.lensRoles", "conversion"));

    /** Whether {@code process} consumes one of a, b and produces the other (no component: false). */
    private boolean convertsBetween(Node process, Node a, Node b) {
        for (ProcessComponent p : processComponents) {
            if (p.processNode != process) continue;
            List<Node> in = union(p.substrates, p.revNodesLHS), out = union(p.products, p.revNodesRHS);
            return (in.contains(a) && out.contains(b)) || (in.contains(b) && out.contains(a));
        }
        return false;
    }

    /**
     * -Dcblayout.ringCorners=species|entry (default species, skeletonShape=fd): species chooses rotation and run
     * lengths of a ring rectangle for the fewest process corners (a process in a corner gets in- and outflow at 90
     * degrees), then the runs closest to the default split, then the smallest shift from the entry node; entry keeps
     * the default split from the entry node.
     */
    static final boolean RING_CORNERS_SPECIES = "species".equalsIgnoreCase(System.getProperty("cblayout.ringCorners", "species"));

    /**
     * Embed one skeleton cycle as a rectangle: {@code k} nodes on four sides with run lengths
     * nR, nD, nL, nU >= 1 (wider than tall, odd k allowed: extra nodes sit flat on a side). The
     * walk follows the SBGN flow of the ring clockwise and starts at the entry node, i.e. the ring
     * node with skeleton neighbours outside the ring and the smallest x, so the surrounding flow
     * enters at the top-left corner. Every ring edge gets an alignment along its side plus a
     * separation in walking direction; the pairs are remembered in {@link #cycleFixed} so step 3
     * does not add contradicting separations. Returns false for rings with fewer than 4 nodes.
     */
    private boolean embedCycle(Cycle cycle, List<Node> ring) {
        int k = ring.size();
        if (k < 4) return false;
        // A two-species 4-ring is a lens, not a rectangle; ringCorners only applies to the rectangles below.
        if (LENS && fdVariant() && k == 4 && CycleSelector.speciesCount(cycle) == 2) {
            for (int i = 0; i < 4; i++) {
                Node s1 = ring.get(i), pa = ring.get((i + 1) % 4), s2 = ring.get((i + 2) % 4), pb = ring.get((i + 3) % 4);
                if (!CycleSelector.isProcessNode(s1) && CycleSelector.isProcessNode(pa) && CycleSelector.isProcessNode(pb)) {
                    if (LENS_ANY_ROLES || (convertsBetween(pa, s1, s2) && convertsBetween(pb, s1, s2))) {
                        grouped(null, "lens", "Lens " + groupName(s1) + " – " + groupName(s2), "Skeleton", () -> embedLens(s1, s2, pa, pb));
                        return true;
                    }
                    break;   // a process that consumes or produces both species: the ring stays a rectangle
                }
            }
        }

        // walk with the flow (majority of substrate/product roles)
        int sense = 0;
        List<Edge> edges = cycle.getEdges();
        for (int i = 0; i < edges.size() && i < k; i++) {
            Edge e = edges.get(i);
            String role = getSBGNRole(e);
            if (usesTopologySkeleton() && role.contains("REVERSIBLE")) continue;
            Node src = skeletonNodeLookup.get(e.getSource()), tgt = skeletonNodeLookup.get(e.getTarget());
            boolean srcIsProcess = src != null && (getSBGNRole(src).contains("PROCESS") || getSBGNRole(src).contains("ASSOCIATION"));
            Node from = usesTopologySkeleton() ? skeletonNodeLookup.get(TopologySkeleton.flowSource(e))
                    : (role.contains("PRODUCTION") || role.contains("REVERSIBLERHS")) == srcIsProcess ? src : tgt;
            sense += from == ring.get(i) ? 1 : -1;
        }
        if (sense < 0) Collections.reverse(ring);

        // entry node: external skeleton neighbours first, then smallest x
        Set<Node> skeletonNodes = new HashSet<>(skeletonNodeLookup.values());
        int entry = 0;
        boolean entryExternal = false;
        double entryX = Double.POSITIVE_INFINITY;
        String entryKeyBest = null;
        for (int i = 0; i < k; i++) {
            Node n = ring.get(i);
            Node prev = ring.get((i - 1 + k) % k), next = ring.get((i + 1) % k);
            boolean external = false;
            for (Node nb : n.getNeighbors()) {
                if (nb != prev && nb != next && nb != n && skeletonNodes.contains(nb)) { external = true; break; }
            }
            // In the structural variant the entry must not come from a coordinate: those still carry
            // the force-directed pass, and with it the starting layout the variant wants to shake off.
            double x = inputX(n);
            boolean better;
            if (usesTopologySkeleton() || (usesStructuralSkeleton() && !"structure-map".equals(SKELETON_SHAPE))) {
                String ek = usesTopologySkeleton() ? TopologySkeleton.key(n) : entryKey(n);
                better = external != entryExternal ? external : (entryKeyBest == null || ek.compareTo(entryKeyBest) < 0);
                if (i == 0 || better) entryKeyBest = ek;
            } else {
                better = external != entryExternal ? external : x < entryX;
            }
            if (i == 0 || better) { entry = i; entryExternal = external; entryX = x; }
        }
        Collections.rotate(ring, -entry);

        // four runs: R, D, L, U
        int base = k / 4, rest = k % 4;
        int nR = base + (rest > 0 ? 1 : 0), nL = base + (rest > 1 ? 1 : 0), nD = base + (rest > 2 ? 1 : 0), nU = base;
        if (RING_CORNERS_SPECIES && fdVariant()) {
            // exhaustive over rotation sh and runs r, d, l, u >= 1: fewest process corners, then the
            // runs closest to the default split, then the smallest shift away from the entry node
            int bestScore = Integer.MAX_VALUE, bestS = 0, bR = nR, bD = nD, bL = nL, bU = nU;
            for (int sh = 0; sh < k; sh++) {
                for (int r = 1; r <= k - 3; r++) for (int d = 1; d <= k - r - 2; d++) for (int l = 1; l <= k - r - d - 1; l++) {
                    int u = k - r - d - l;
                    int proc = 0;
                    for (int c : new int[]{sh, sh + r, sh + r + d, sh + r + d + l}) if (CycleSelector.isProcessNode(ring.get(c % k))) proc++;
                    int dev = Math.abs(r - nR) + Math.abs(d - nD) + Math.abs(l - nL) + Math.abs(u - nU);
                    int score = proc * 10000 + dev * 100 + Math.min(sh, k - sh);
                    if (score < bestScore) { bestScore = score; bestS = sh; bR = r; bD = d; bL = l; bU = u; }
                }
            }
            Collections.rotate(ring, -bestS);
            nR = bR; nD = bD; nL = bL; nU = bU;
        }
        List<String> dirs = new ArrayList<>(k);
        for (int i = 0; i < nR; i++) dirs.add("right");
        for (int i = 0; i < nD; i++) dirs.add("down");
        for (int i = 0; i < nL; i++) dirs.add("left");
        for (int i = 0; i < nU; i++) dirs.add("up");

        for (int i = 0; i < k; i++) {
            Node a = ring.get(i), b = ring.get((i + 1) % k);
            String dir = dirs.get(i);
            List<Node> pair = new ArrayList<>();
            pair.add(a);
            pair.add(b);
            boolean horizontal = dir.equals("right") || dir.equals("left");
            ringAxis.put(pairKey(a, b), horizontal);
            if (horizontal) {
                ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
            } else {
                ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
            }
            if (dir.equals("right") || dir.equals("down")) addGapSeparation(a, b, horizontal, CYCLE_GAP);
            else addGapSeparation(b, a, horizontal, CYCLE_GAP);
            cycleFixed.computeIfAbsent(a, x -> new HashSet<>()).add(b);
            cycleFixed.computeIfAbsent(b, x -> new HashSet<>()).add(a);
            // a leaves towards dir, b is entered from the opposite side.
            cycleSides.computeIfAbsent(a, x -> new HashSet<>()).add(dir);
            cycleSides.computeIfAbsent(b, x -> new HashSet<>()).add(opposite(dir));
        }
        return true;
    }

    /**
     * Lens: both species on one axis, the two processes between them side by side, one on each side of
     * the axis at {@link #LENS_OFFSET}. Every process then has its two ring species on opposite sides
     * (2*atan(half/offset), 138 degrees for the defaults) instead of 90 degrees in a rectangle corner.
     * The axis is the one the species already lie on after the force-directed pass.
     */
    private void embedLens(Node s1, Node s2, Node pa, Node pb) {
        int constraintsBefore = ConstraintStore.constraints.getAllConstraints().size();
        boolean horizontal = horizontalOrVertical(s1, s2);
        double c1 = horizontal ? AttributeHelper.getPositionX(s1) : AttributeHelper.getPositionY(s1);
        double c2 = horizontal ? AttributeHelper.getPositionX(s2) : AttributeHelper.getPositionY(s2);
        if (c1 > c2) { Node t = s1; s1 = s2; s2 = t; }
        double qa = horizontal ? AttributeHelper.getPositionY(pa) : AttributeHelper.getPositionX(pa);
        double qb = horizontal ? AttributeHelper.getPositionY(pb) : AttributeHelper.getPositionX(pb);
        if (qa > qb) { Node t = pa; pa = pb; pb = t; }
        double o = LENS_OFFSET;
        double half = Math.max(2.6 * o, STAR_RADIUS);
        SeparationConstraintGraph.SeparationConstraintType along = horizontal ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                : SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
        SeparationConstraintGraph.SeparationConstraintType across = horizontal ? SeparationConstraintGraph.SeparationConstraintType.VERTICAL
                : SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL;
        // species on one line, processes on the perpendicular line through the middle
        List<Node> pair = new ArrayList<>(Arrays.asList(s1, s2));
        if (horizontal) ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
        else ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
        pair = new ArrayList<>(Arrays.asList(pa, pb));
        if (horizontal) ConstraintStore.constraints.addConstraint(new VerticalConstraint(pair, VerticalConstraint.VerticalConstraintType.MIDDLE));
        else ConstraintStore.constraints.addConstraint(new HorizontalConstraint(pair, HorizontalConstraint.HorizontalConstraintType.MIDDLE));
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(s1, pa)), along, false, half));
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(pa, s2)), along, false, half));
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(pa, s1)), across, true, o));
        ConstraintStore.constraints.addConstraint(new SeparationConstraintGraph(new ArrayList<>(Arrays.asList(s1, pb)), across, true, o));
        String fwd = horizontal ? "right" : "down", back = opposite(fwd);
        for (Node p : Arrays.asList(pa, pb)) {
            for (Node sp : Arrays.asList(s1, s2)) {
                cycleFixed.computeIfAbsent(p, x -> new HashSet<>()).add(sp);
                cycleFixed.computeIfAbsent(sp, x -> new HashSet<>()).add(p);
            }
            cycleSides.computeIfAbsent(p, x -> new HashSet<>()).addAll(Arrays.asList(fwd, back));
        }
        cycleSides.computeIfAbsent(s1, x -> new HashSet<>()).add(fwd);
        cycleSides.computeIfAbsent(s2, x -> new HashSet<>()).add(back);
        lensInner.put(pa, horizontal ? "down" : "right");
        lensInner.put(pb, horizontal ? "up" : "left");
        List<Constraint> all = ConstraintStore.constraints.getAllConstraints();
        ringLenses.put(pairKey(s1, s2), new RingLens(pa, pb, new ArrayList<>(all.subList(constraintsBefore, all.size()))));
    }

    /**
     * x of a node as the input map had it, so the structural variant does not depend on the
     * force-directed pass either. Falls back to the current position.
     */
    private double inputX(Node original) {
        if ("structure-map".equals(SKELETON_SHAPE) && originalPositions() != null) {
            Point2D.Double p = originalPositions().get(original);
            if (p != null) return p.x;
        }
        return AttributeHelper.getPositionX(original);
    }

    /** Identifies a node by label and role, so a choice based on it survives a reordered input file. */
    private static String entryKey(Node n) {
        String label = labelOrNull(n);
        String role = getSBGNRole(n);
        return (label == null ? "" : label.trim()) + "\u0000" + (role == null ? "" : role);
    }

    private boolean isCycleFixed(Node a, Node b) {
        Set<Node> s = cycleFixed.get(a);
        return s != null && s.contains(b);
    }

    /** The neighbours of {@code process} in {@code nodes} that no embedded cycle has already placed. */
    private List<Node> free(List<Node> nodes, Node process) {
        List<Node> l = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            if (!isCycleFixed(process, n) && (topologyPlan == null || !topologyPlan.owns(process, n))) l.add(n);
        }
        return l;
    }

    /** Drop automatic constraints that would make the set infeasible or let nodes coincide; report the rest. */
    void enforceFeasibility(String phase) {
        if (ConstraintStore.constraints == null) return;
        // Everything this step created so far is automatic; the capture only marks it after the step.
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (isNewInStep(c)) {
                c.setAutomatic(true);
                if (ConstraintFeasibility.BY_CLASS && c.getRank() == 0) c.setRank(priorityClass(c, phase));
            }
        }
        List<ConstraintFeasibility.Dropped> dropped = ConstraintFeasibility.enforce(ConstraintStore.constraints, true);
        if (!ConstraintFeasibility.lastDeactivated.isEmpty()) ConflictLog.setDeactivated(ConstraintStore.constraints.getAllConstraints());
        ConflictLog.addDropped(phase, dropped, ConstraintFeasibility.lastWarnings);
        if (!dropped.isEmpty()) {
            try { ConstraintStore.onListsChanged.run(); } catch (Exception ignored) { }
        }
    }

    /** -Dcblayout.feasPriority=class: rank of an automatic constraint by what created it (higher survives a contradiction). */
    private int priorityClass(Constraint c, String phase) {
        switch (phase) {
            case "Skeleton": {
                List<Node> ns = c.getNodes();
                if (ns.size() == 2 && isCycleFixed(ns.get(0), ns.get(1))) return 100;   // ring, lens, fan
                return c instanceof SeparationConstraintGraph ? 80 : 40;               // chain order : FD-angle alignment
            }
            case "Individual": return 60;
            case "Stars": return 90;
            case "Stubs": return 50;
            default: return 10;
        }
    }

    private ProcessComponent getProcessComponent(Node process) {
        for (ProcessComponent p : processComponents) {
            if (p.processNode == process) {
                return p;
            }
        }
        return null;
    }

    private void divideGraphInProcesses(ArrayList<Node> subMaps, ArrayList<Node> complexNodes, ArrayList<Node> tags) {
        for (Node node : graph.getNodes()) {

            if (getSBGNRole(node).contains("PROCESS") || getSBGNRole(node).contains("ASSOCIATION")) {
                processComponents.add(new ProcessComponent(node));
            }
            if (getSBGNRole(node).contains("SUBMAP")) {
                subMaps.add(node);
                processComponents.add(new ProcessComponent(node));
            }
            if (getSBGNRole(node).contains("COMPLEX")) {
                complexNodes.add(node);
            }

            if (getSBGNRole(node).contains("TAG")) {
                tags.add(node);
            }
        }
        for (Edge edge : graph.getEdges()) {
            if (getSBGNRole(edge).contains("REVERSIBLERHS") || getSBGNRole(edge).contains("REVERSIBLELHS")) {
                reversibleRHS.add(edge);
            }
        }
    }

    /** Graph node -> its copy in the skeleton (every node, also the leaves removed from the skeleton). */
    private Map<Node, Node> skeletonCopies = new HashMap<>();

    /**
     * Copy the graph into the skeleton and record which copy belongs to which node. The mapping
     * must not go through node IDs: a graph numbers its elements with its own counter, so the
     * copies only share the IDs of their originals while the graph is freshly loaded. As soon as
     * a node was deleted and re-added (compartments after a run, a reset, an edit) the IDs are
     * shifted and an ID match silently pairs wrong nodes or none at all.
     */
    private void createSkeletonMapping() {
        skeleton = new AdjListGraph(new ListenerManager());
        skeletonCopies = new HashMap<>();
        Iterator<Node> originals = graph.getNodes().iterator();
        for (org.graffiti.graph.GraphElement copy : skeleton.addGraph(graph)) {
            // addGraph returns the node copies first, one per node in graph.getNodes() order
            if (!(copy instanceof Node)) continue;
            Node original = originals.next();
            skeletonCopies.put(original, (Node) copy);
        }
        createSkeletonOnlyLeafs();
        for (Map.Entry<Node, Node> e : skeletonCopies.entrySet()) {
            if (skeleton.containsNode(e.getValue())) skeletonNodeLookup.put(e.getValue(), e.getKey());
        }
    }

    private Node pickContainingSubmap(Node tag, List<Node> submaps) {
        Node best = null;
        double bestArea = Double.POSITIVE_INFINITY;
        for (Node sm : submaps) {
            if (isContained(sm, tag)) {
                double area = AttributeHelper.getWidth(sm) * AttributeHelper.getHeight(sm);
                if (area < bestArea) {
                    bestArea = area;
                    best = sm;
                }
            }
        }
        return best;
    }

    /** Moves every tag edge onto the submap its tag lies in, so the tag is free (degree 0) during the layout. */
    private void rerouteTagEdgesToSubmap() {
        for (TagBinding b : tagBindings.values()) {
            Node tag = b.tag;
            Node submap = b.submap;

            // a copy: the endpoints change while iterating
            List<Edge> incident = new ArrayList<>(tag.getEdges());

            for (Edge e : incident) {
                Node s = e.getSource();
                Node t = e.getTarget();
                b.rewired.add(new RewiredEdge(e, s, t));
                if (s == tag) {
                    e.setSource(submap);
                }
                if (t == tag) {
                    e.setTarget(submap);
                }
            }
        }
    }

    /** Puts every tag back at its offset from the moved submap and its edges back onto their original endpoints. */
    private void restoreTagsAfterLayout() {
        for (TagBinding b : tagBindings.values()) {
            Node tag = b.tag;
            Node submap = b.submap;

            double sx = AttributeHelper.getPositionX(submap);
            double sy = AttributeHelper.getPositionY(submap);
            AttributeHelper.setPosition(tag, sx + b.dx, sy + b.dy);

            for (RewiredEdge rw : b.rewired) {
                rw.edge.setSource(rw.origSource);
                rw.edge.setTarget(rw.origTarget);
            }
        }
    }

    /**
     * -Dcblayout.tagClip=true (after routing): a route to a restored tag can end with a bend inside the tag,
     * and the exporter clips the arc at that bend, i.e. inside the tag label. Such bends are dropped, so the arc is
     * clipped at the tag border.
     */
    private void clipBendsInsideTags() {
        for (TagBinding tb : tagBindings.values()) {
            double cx = AttributeHelper.getPositionX(tb.tag), cy = AttributeHelper.getPositionY(tb.tag);
            double hw = AttributeHelper.getWidth(tb.tag) / 2 + 1, hh = AttributeHelper.getHeight(tb.tag) / 2 + 1;
            for (Edge e : tb.tag.getEdges()) {
                ArrayList<org.Vector2d> bends = AttributeHelper.getEdgeBends(e);
                ArrayList<org.Vector2d> kept = new ArrayList<>();
                for (org.Vector2d v : bends) { if (Math.abs(v.x - cx) > hw || Math.abs(v.y - cy) > hh) kept.add(v); }
                if (kept.size() != bends.size()) {
                    AttributeHelper.removeEdgeBends(e);
                    if (!kept.isEmpty()) AttributeHelper.addEdgeBends(e, kept);
                }
            }
        }
    }

    private void prepareTagsReroute(List<Node> tags, List<Node> subMaps) {
        tagBindings.clear();

        for (Node tag : tags) {
            Node submap = pickContainingSubmap(tag, subMaps);
            if (submap == null) continue; // tag not inside any submap -> leave it alone

            double tx = AttributeHelper.getPositionX(tag);
            double ty = AttributeHelper.getPositionY(tag);
            double sx = AttributeHelper.getPositionX(submap);
            double sy = AttributeHelper.getPositionY(submap);

            TagBinding binding = new TagBinding(tag, submap, tx - sx, ty - sy);
            tagBindings.put(tag, binding);
        }
    }

    private void applyComplexContainment() {
        // Restore an inner complex before its children. HashMap iteration can otherwise leave
        // a child at the old parent position, even on two runs of exactly the same input file.
        Set<Node> done = new HashSet<>(), visiting = new HashSet<>();
        List<Node> children = new ArrayList<>(childOffset.keySet());
        children.sort(Comparator.comparing(TopologySkeleton::key));
        for (Node child : children) restoreComplexChild(child, done, visiting);
    }

    private void restoreComplexChild(Node child, Set<Node> done, Set<Node> visiting) {
        if (done.contains(child) || !visiting.add(child)) return;
        Offset off = childOffset.get(child);
        if (off != null) {
            if (childOffset.containsKey(off.complex)) restoreComplexChild(off.complex, done, visiting);
            AttributeHelper.setPosition(child, AttributeHelper.getPositionX(off.complex) + off.dx,
                    AttributeHelper.getPositionY(off.complex) + off.dy);
        }
        visiting.remove(child);
        done.add(child);
    }

    /** Remembers the offset of every node drawn inside a complex from it (see {@link #applyComplexContainment()}). */
    private void createComplexContainmentMap(ArrayList<Node> complexNodes) {
        childOffset.clear();
        for (Node complexNode : complexNodes) {
            double cx = AttributeHelper.getPositionX(complexNode);
            double cy = AttributeHelper.getPositionY(complexNode);

            for (Node node : graph.getNodes()) {
                if (node == complexNode) continue;
                if (!isContained(complexNode, node)) continue;

                double nx = AttributeHelper.getPositionX(node);
                double ny = AttributeHelper.getPositionY(node);
                childOffset.put(node, new Offset(complexNode, nx - cx, ny - cy));
            }
        }
    }

    public static boolean isContained(Node complexNode, Node node) {
        double complexX = AttributeHelper.getPositionX(complexNode);
        double complexY = AttributeHelper.getPositionY(complexNode);
        double complexWidth = AttributeHelper.getWidth(complexNode);
        double complexHeight = AttributeHelper.getHeight(complexNode);

        double nodeX = AttributeHelper.getPositionX(node);
        double nodeY = AttributeHelper.getPositionY(node);
        double nodeWidth = AttributeHelper.getWidth(node);
        double nodeHeight = AttributeHelper.getHeight(node);

        // boundaries of the complex node
        double left = complexX - complexWidth / 2.0;
        double right = complexX + complexWidth / 2.0;
        double top = complexY - complexHeight / 2.0;
        double bottom = complexY + complexHeight / 2.0;

        // boundaries of the contained node
        double nodeLeft = nodeX - nodeWidth / 2.0;
        double nodeRight = nodeX + nodeWidth / 2.0;
        double nodeTop = nodeY - nodeHeight / 2.0;
        double nodeBottom = nodeY + nodeHeight / 2.0;

        // check if the node is fully inside the complex node
        return nodeLeft >= left && nodeRight <= right &&
                nodeTop >= top && nodeBottom <= bottom;
    }

    private boolean isToLeft(Node process, Node connector) {
        return AttributeHelper.getPositionX(connector) <= AttributeHelper.getPositionX(process);
    }

    /** True if {@code connector} lies above {@code process} or level with it (screen y grows downwards). */
    private boolean isDownwards(Node process, Node connector) {
        return AttributeHelper.getPositionY(connector) <= AttributeHelper.getPositionY(process);
    }

    /** The side for the reversible axis: with -Dcblayout.hubSpreadRev as hubSpread gives it, otherwise the natural side. */
    private String revHubSide(Node n, Node process, Map<Node, Set<String>> hubSides) {
        String natural = checkOrientation(process, n);
        return HUB_SPREAD_REV && fdVariant() ? hubSide(n, natural, process, hubSides) : natural;
    }

    /**
     * {@link #freeHubSide}, except for a lens process and one of its two ring species: both lens processes
     * sit on the same side of that species by design, so moving the second one would turn its axis across
     * the lens. It keeps its natural side, and the side is still marked taken for every other process.
     */
    private String hubSide(Node hub, String preferred, Node process, Map<Node, Set<String>> hubSides) {
        if (hub == null || preferred == null || preferred.isEmpty() || !lensInner.containsKey(process) || !isCycleFixed(process, hub))
            return freeHubSide(hub, preferred, process, hubSides);
        hubSides.computeIfAbsent(hub, k -> new HashSet<>()).add(opposite(preferred));
        return preferred;
    }

    /**
     * Keeps two processes that share the same species off the same side of it.
     *
     * <p>{@code preferred} says where the shared species currently lies as seen from the process, so
     * the process itself sits on the opposite side of the species. If another process already took
     * that side, the species is handed the free side closest to where this process actually lies, and
     * the answer is flipped back into "seen from the process" again. A species with only one process,
     * and a species that already has a process on all four sides, keep the natural side.
     */
    private String freeHubSide(Node hub, String preferred, Node process, Map<Node, Set<String>> hubSides) {
        if (!HUB_SPREAD || usesStructuralSkeleton() || hub == null || preferred == null || preferred.isEmpty()) return preferred;
        Set<String> used = hubSides.computeIfAbsent(hub, k -> new HashSet<>());
        String want = opposite(preferred);          // side of the species this process sits on
        if (!used.contains(want)) {
            used.add(want);
            if (HUB_FAN) hubLast.computeIfAbsent(hub, k -> new HashMap<>()).put(want, process);
            return preferred;
        }
        double dx = AttributeHelper.getPositionX(process) - AttributeHelper.getPositionX(hub);
        double dy = AttributeHelper.getPositionY(process) - AttributeHelper.getPositionY(hub);
        List<String> order = new ArrayList<>(Arrays.asList("right", "left", "down", "up"));
        // Best match to where the process already is, so the layout moves as little as possible.
        order.sort(Comparator.comparingDouble(side -> -sideScore(side, dx, dy)));
        for (String side : order) {
            if (!used.contains(side)) {
                used.add(side);
                if (HUB_FAN) hubLast.computeIfAbsent(hub, k -> new HashMap<>()).put(side, process);
                String moved = opposite(side);
                // Step 2 aligned this process with the species on the old axis. That alignment now
                // says the opposite of the new side, and since it was created first the feasibility
                // guard would drop the new separation instead. Take the alignment out.
                dropContradictingAlignment(hub, process, "left".equals(moved) || "right".equals(moved));
                return moved;
            }
        }
        if (HUB_FAN && fdVariant()) fanBesideHub(hub, want, process);
        return preferred;   // all four sides taken
    }

    /**
     * -Dcblayout.hubFan: a process that finds every side of its hub species taken keeps its natural side, but instead of
     * lining up behind the process that took that side (both aligned with the hub on the same axis) it loses its
     * alignment with the hub and gets a minimum separation beside that process, perpendicular to the axis, on the side
     * it already leans to. The next one fans out from it in turn.
     */
    private void fanBesideHub(Node hub, String side, Node process) {
        Map<String, Node> last = hubLast.get(hub);
        Node prev = last == null ? null : last.get(side);
        if (prev == null || prev == process) return;
        boolean axisHorizontal = side.equals("left") || side.equals("right");
        dropAlignment(hub, process, axisHorizontal, "removed: the process fans out beside the hub (hubFan)");
        double d = axisHorizontal ? AttributeHelper.getPositionY(process) - AttributeHelper.getPositionY(prev)
                                  : AttributeHelper.getPositionX(process) - AttributeHelper.getPositionX(prev);
        if (d >= 0) addGapSeparation(prev, process, !axisHorizontal, STAR_GAP);
        else addGapSeparation(process, prev, !axisHorizontal, STAR_GAP);
        last.put(side, process);
    }

    /**
     * Removes a two-node skeleton alignment between {@code hub} and {@code process} that contradicts
     * the axis the process was just given: on a horizontal axis the two share a row, on a vertical
     * one a column, and the alignment for the other axis cannot hold at the same time.
     */
    private void dropContradictingAlignment(Node hub, Node process, boolean wantHorizontal) {
        dropAlignment(hub, process, !wantHorizontal, "removed: contradicts the new axis (hubSpread/splitRow)");
    }

    /**
     * -Dcblayout.dropAlignment=keepUser|remove (default keepUser): what {@link #dropAlignment} does with a two-node
     * alignment the user made. {@code keepUser}: only alignments the steps made are removed; a user alignment stays,
     * and the feasibility guard decides the contradiction with the step's new separation by rank (a user alignment of
     * lower priority is deactivated with its reason, else the step's separation is dropped). {@code remove}: the
     * user's alignment is removed as well. Without user constraints both values give the same output.
     */
    static final boolean DROP_ALIGNMENT_REMOVE = "remove".equalsIgnoreCase(System.getProperty("cblayout.dropAlignment", "keepUser"));

    /**
     * Removes every two-node alignment between a and b of one kind (same row or same column) that a step made; true if
     * afterwards no such alignment is left between the two. A user's alignment (not automatic, in the store before the
     * running step began) is kept unless {@link #DROP_ALIGNMENT_REMOVE}: the guard settles it against the step's
     * constraints by rank (see there).
     */
    private boolean dropAlignment(Node a, Node b, boolean sameRow, String reason) {
        if (ConstraintStore.constraints == null) return false;
        boolean any = false, kept = false;
        for (Constraint c : new ArrayList<>(ConstraintStore.constraints.getAllConstraints())) {
            List<Node> ns = c.getNodes();
            if (ns == null || ns.size() != 2 || !ns.contains(a) || !ns.contains(b)) continue;
            if (sameRow ? c instanceof HorizontalConstraint : c instanceof VerticalConstraint) {
                boolean stepMade = c.isAutomatic() || isNewInStep(c);
                if (!stepMade && !DROP_ALIGNMENT_REMOVE) {
                    kept = true;   // the user's: the guard decides it against the step's separation by rank
                    continue;
                }
                ConstraintStore.constraints.removeConstraint(c);
                logRemoval(c, reason);
                any = true;
            }
        }
        return any && !kept;
    }

    private static double sideScore(String side, double dx, double dy) {
        switch (side) {
            case "right": return dx;
            case "left":  return -dx;
            case "down":  return dy;
            case "up":    return -dy;
            default:      return 0;
        }
    }

    /** The side of {@code process} that {@code connector} lies on along the dominant axis, "" for the same point. */
    private static String checkOrientation(Node process, Node connector) {
        if (process == null || connector == null) return "";

        double px = AttributeHelper.getPositionX(process);
        double py = AttributeHelper.getPositionY(process);
        double cx = AttributeHelper.getPositionX(connector);
        double cy = AttributeHelper.getPositionY(connector);

        final double EPS = 1e-6; // tolerance for "equal"
        double dx = cx - px;     // +dx: connector is to the right
        double dy = cy - py;     // +dy: connector is below (downwards screen Y)

        if (Math.abs(dx) < EPS && Math.abs(dy) < EPS) {
            return "";
        }

        // Pick the dominant axis; tie breaks to horizontal
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx > 0 ? "right" : "left";
        } else {
            return dy > 0 ? "down" : "up";
        }
    }

    /** True if the line between the two nodes is at most 45 degrees off the horizontal (also for the same point). */
    private static boolean horizontalOrVertical(Node n1, Node n2) {
        double x1 = AttributeHelper.getPositionX(n1);
        double y1 = AttributeHelper.getPositionY(n1);
        double x2 = AttributeHelper.getPositionX(n2);
        double y2 = AttributeHelper.getPositionY(n2);

        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx))) <= 45;
    }

    private static boolean isInAnyCluster(Node n) {
        for (ArrayList<Node> p : CBLayoutAlgorithm.clusterMap.values()) {
            if (p.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Adds every node that is in no cluster to the first cluster that holds all its neighbours. */
    public static void assignNodesByNeighbors(Graph graph) {
        for (Node n : graph.getNodes()) {
            if (isInAnyCluster(n)) {
                continue;
            }
            Collection<Node> neighbors = n.getNeighbors();
            if (neighbors.isEmpty()) {
                continue;
            }
            for (Map.Entry<Node, ArrayList<Node>> entry : CBLayoutAlgorithm.clusterMap.entrySet()) {
                ArrayList<Node> p = entry.getValue();
                boolean allNeighborsInP = true;
                for (Node m : neighbors) {
                    if (!p.contains(m)) {
                        allNeighborsInP = false;
                        break;
                    }
                }
                if (allNeighborsInP) {
                    p.add(n);
                    break;
                }
            }
        }
    }

    public static String getSBGNRole(Attributable attributable) {
        return (String) AttributeHelper.getAttributeValue(attributable, "sbgn", "role", "", "", false);
    }

    private void removeDocking()
    {
        for (Edge e : graph.getEdges())
        {
            EdgeGraphicAttribute ega = (EdgeGraphicAttribute) e.getAttribute(GraphicAttributeConstants.GRAPHICS);
            ega.getDocking().setSource("");
            ega.getDocking().setTarget("");
        }
    }

    /** A fresh view of the graph for the edge router (also after nodes were added to the graph). */
    void refreshView()
    {
        AttributeComponentManager acm = new AttributeComponentManager();
        view2D = new de.ipk_gatersleben.ag_nw.graffiti.plugins.gui.ipk_graffitiview.IPKGraffitiView();
        view2D.setAttributeComponentManager(acm);
        view2D.setGraph(graph);
    }

    private void deleteCompartmentNodes()
    {
        // Every compartment glyph goes, not only those that ended up in the cluster map: one that is
        // left behind takes part in the force-directed layout as a very large box and pushes the
        // whole drawing apart. The user's constraints on it wait outside the store until it is back.
        for (Node node : new ArrayList<>(graph.getNodes())) {
            if (!isCompartment(node)) continue;
            holdUserConstraints(node, c -> true);
            try {
                graph.deleteNode(node);
            } catch (Exception e) {
                releaseUserConstraints(node, node);
                continue;
            }
            // Remember it so resetToOriginal() can re-insert it; a frame the last step re-inserted stands for an
            // original that is remembered already.
            if (!restoredCompartments.remove(node)) deletedCompartments.add(node);
        }
    }

    /** -Dcblayout.compartmentPadding=px (30): padding of a restored compartment frame around its members. */
    private static final double COMPARTMENT_PADDING = doubleProperty("cblayout.compartmentPadding", 30.0);

    /**
     * Re-inserts the compartment glyphs that {@link #deleteCompartmentNodes()} removed before the
     * layout ran. This is the compartment counterpart to {@link #applyComplexContainment()} and
     * {@link #restoreTagsAfterLayout()}.
     * <p>
     * Each compartment is copied from its original (still-detached) node so its SBGN styling and
     * label are preserved, then sized to the bounding box of its now-positioned member nodes plus
     * {@link #COMPARTMENT_PADDING}. Compartments without resolvable members keep their original
     * position/size (the copy already carries them) so they never silently vanish. The frame is
     * pushed to the back via its z-coordinate so it sits behind its content.
     */
    private void restoreCompartments() {
        Map<Node, ArrayList<Node>> clusters = CBLayoutAlgorithm.clusterMap;
        if (clusters == null || clusters.isEmpty()) return;

        List<Node> restored = new ArrayList<>();
        for (Map.Entry<Node, ArrayList<Node>> entry : clusters.entrySet()) {
            Node original = entry.getKey();
            List<Node> members = entry.getValue();

            // addNodeCopy keeps the original SBGN compartment styling, label and (as a fallback)
            // its original bbox for compartments whose members could not be resolved.
            Node compartment = graph.addNodeCopy(original);
            restored.add(compartment);
            originalOf.put(compartment, originalOf.containsKey(original) ? originalOf.get(original) : original);
            releaseUserConstraints(original, compartment);

            // the cluster rectangle the solver computed contains exactly the members and overlaps no other one; the
            // member bounding box below is the fallback (e.g. ER/AF runs without a cluster hierarchy)
            double[] cb = (CBLayoutAlgorithm.clusterBounds != null)
                    ? CBLayoutAlgorithm.clusterBounds.get(original) : null;
            if (cb != null && cb[2] > cb[0] && cb[3] > cb[1]) {
                AttributeHelper.setPosition(compartment, (cb[0] + cb[2]) / 2.0, (cb[1] + cb[3]) / 2.0);
                AttributeHelper.setSize(compartment, cb[2] - cb[0], cb[3] - cb[1]);
                AttributeHelper.setPositionZ(compartment, -2.0);
                continue;
            }

            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            int counted = 0;
            if (members != null) {
                for (Node m : members) {
                    // complex sub-units do not size the frame: their complex is itself a member, and a sub-unit
                    // lying in another compartment than its complex would stretch the wrong frame
                    if (childOffset.containsKey(m)) continue;
                    double x = AttributeHelper.getPositionX(m);
                    double y = AttributeHelper.getPositionY(m);
                    double w = AttributeHelper.getWidth(m);
                    double h = AttributeHelper.getHeight(m);
                    minX = Math.min(minX, x - w / 2.0);
                    maxX = Math.max(maxX, x + w / 2.0);
                    minY = Math.min(minY, y - h / 2.0);
                    maxY = Math.max(maxY, y + h / 2.0);
                    counted++;
                }
            }
            if (counted > 0) {
                minX -= COMPARTMENT_PADDING;
                minY -= COMPARTMENT_PADDING;
                maxX += COMPARTMENT_PADDING;
                maxY += COMPARTMENT_PADDING;

                AttributeHelper.setPosition(compartment, (minX + maxX) / 2.0, (minY + maxY) / 2.0);
                AttributeHelper.setSize(compartment, maxX - minX, maxY - minY);
            }

            // Semantic depth hint (honoured by exporters / 3D-aware views).
            AttributeHelper.setPositionZ(compartment, -2.0);
        }

        // Track the copies so resetToOriginal() can drop them before restoring the originals.
        restoredCompartments.clear();
        restoredCompartments.addAll(restored);

        // the editor view adds every new node component on top, so re-inserted frames would cover their content
        sendCompartmentsToBack(restored);
    }

    /**
     * Moves the given compartment glyph components behind all other graph elements in every editor
     * view that displays this graph. Sorted by area so that larger (outer) compartments end up
     * furthest back, keeping nested compartments visible. Runs on the event thread and is best effort: a
     * failure only prints a message.
     */
    private void sendCompartmentsToBack(List<Node> compartments) {
        if (compartments == null || compartments.isEmpty()) return;

        // each move to the back shifts the others forward, so the last one moved ends up furthest back:
        // smallest first puts the outer compartments behind the nested ones
        List<Node> ordered = new ArrayList<>(compartments);
        ordered.sort(Comparator.comparingDouble(
                (Node n) -> AttributeHelper.getWidth(n) * AttributeHelper.getHeight(n)));

        Runnable task = () -> {
            try {
                for (org.graffiti.session.Session s : org.graffiti.editor.MainFrame.getSessions()) {
                    if (s.getGraph() != graph) continue;
                    for (org.graffiti.plugin.view.View v : s.getViews()) {
                        if (!(v instanceof java.awt.Container)) continue;
                        java.awt.Container container = (java.awt.Container) v;
                        for (Node c : ordered) {
                            org.graffiti.plugin.view.GraphElementComponent gec = v.getComponentForElement(c);
                            if (gec instanceof java.awt.Component
                                    && ((java.awt.Component) gec).getParent() == container) {
                                // z-index (count-1) == bottom of the stack == painted first == behind.
                                container.setComponentZOrder((java.awt.Component) gec, container.getComponentCount() - 1);
                            }
                        }
                        container.repaint();
                    }
                }
            } catch (Throwable t) {
                System.err.println("Could not send compartments to back: " + t.getMessage());
            }
        };

        if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
        else javax.swing.SwingUtilities.invokeLater(task);
    }

}
