package layout.steps;

import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintCheck;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import constraints.Constraints;
import adaptagrams_bridge.CBLayoutAlgorithm;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;

import org.AttributeHelper;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A layout algorithm written as a list of {@link LayoutStep steps} that add constraints to the store instead of
 * setting positions. Around the steps this base class remembers the positions, decides the user's constraints
 * ({@link #resolveUserConstraints()}), marks the constraints a step adds as automatic
 * ({@link Constraint#isAutomatic()}: they rank below the user's constraints of equal priority and the guards may drop
 * them) and lists the user's constraints that do not hold afterwards in the {@link ConflictLog};
 * {@link #resetToOriginal()} undoes a run. A subclass builds its steps in its constructor with
 * {@link #addStep}, must not import GUI classes and runs on the event thread; the Algorithms page drives every
 * algorithm of the {@link StepLayoutRegistry} through this class alone.
 *
 * @author Niklas Gröne
 */
public abstract class StepLayout {

    /** The graph the layout works on. */
    public final Graph graph;
    private final String algorithmId;
    private final List<LayoutStep> steps = new ArrayList<>();
    private Map<Node, Point2D.Double> originalPositions;
    private List<Constraint> constraintsBeforeStep;
    /** Template instances the steps applied ({@link StepTemplates}); a reset takes them out again. */
    private final List<TemplateInstance> instances = new ArrayList<>();

    /**
     * @param algorithmId the id of the {@link StepAlgorithm} that made this layout (the Algorithms page shows the run
     *                    on that algorithm's panel)
     */
    protected StepLayout(String algorithmId, Graph graph) {
        this.algorithmId = algorithmId;
        this.graph = graph;
    }

    /** Id of the algorithm this layout belongs to (see {@link StepAlgorithm#id()}). */
    public String getAlgorithmId() {
        return algorithmId;
    }

    /** Name of the algorithm for messages, e.g. "SBGN PD layout". */
    public abstract String getName();

    /** Adds a step; the subclass calls this in its constructor. */
    protected final void addStep(String name, String description, Runnable action) {
        steps.add(new LayoutStep(name, description, action));
    }

    protected final void addStep(LayoutStep step) {
        steps.add(step);
    }

    protected final void clearSteps() {
        steps.clear();
    }

    public List<LayoutStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /** Runs every step in order. */
    public void runAll() {
        ConstraintStore.pin(graph);   // a window a step opens must not swap the store away from this graph
        try {
            snapshotOriginalPositions();
            beforeRun();
            for (LayoutStep s : steps) {
                beginStepCapture();
                s.action.run();
                markAutomatic(endStepCapture());
            }
            afterRun();
        } finally {
            ConstraintStore.unpin();
        }
    }

    /** Runs one step by index; returns the constraints it added (for the Algorithms page to draw). */
    public List<Constraint> runStep(int index) {
        ConstraintStore.pin(graph);
        try {
            if (originalPositions == null) snapshotOriginalPositions();
            beforeRun();
            LayoutStep s = steps.get(index);
            beginStepCapture();
            s.action.run();
            List<Constraint> created = endStepCapture();
            markAutomatic(created);
            afterStep(index, created);
            return created;
        } finally {
            ConstraintStore.unpin();
        }
    }

    /**
     * Back to the state before the first step: {@link #beforeReset()} (the subclass undoes what its steps changed in
     * the structure of the graph), then the remembered positions, no edge bends, only the user's constraints of this
     * graph in the store, the conflict log without the reports of the run, then {@link #afterReset()}.
     */
    public void resetToOriginal() {
        beforeReset();
        if (originalPositions != null) {
            for (Map.Entry<Node, Point2D.Double> e : originalPositions.entrySet()) {
                Node n = e.getKey();
                if (graph.containsNode(n)) AttributeHelper.setPosition(n, e.getValue().x, e.getValue().y);
            }
        }
        for (Edge e : graph.getEdges()) {
            try {
                AttributeHelper.removeEdgeBends(e);
            } catch (Exception ignored) { }
        }
        Constraints store = ConstraintStore.of(graph);
        for (TemplateInstance inst : instances) {
            if (store != null) TemplateApplier.remove(inst, store);
        }
        instances.clear();
        // the stores are per graph: only the constraints the steps generated on this graph go
        if (store != null) {
            store.retainUserConstraintsOf(graph);
            ConstraintStore.onListsChanged.run();
        }
        ConflictLog.clear();
        afterReset();
    }

    /** Before the steps of {@link #runAll()} and before every {@link #runStep(int)}: decides the user's constraints. */
    protected void beforeRun() {
        resolveUserConstraints();
    }

    /** After the last step of {@link #runAll()}: reports the user's constraints that do not hold. */
    protected void afterRun() {
        reportUserConstraints();
    }

    /** After a single step of {@link #runStep(int)}: reports the user's constraints that do not hold. */
    protected void afterStep(int index, List<Constraint> created) {
        reportUserConstraints();
    }

    /** First part of {@link #resetToOriginal()}: undo structural changes of the steps (nothing by default). */
    protected void beforeReset() { }

    /** Last part of {@link #resetToOriginal()}: clear caches of the run (nothing by default). */
    protected void afterReset() { }

    /** Called right after the positions were remembered, before the first step (nothing by default). */
    protected void onSnapshot() { }

    /** Phase name of this algorithm's entries in the {@link ConflictLog} (default: {@link #getName()}). */
    protected String phaseName() {
        return getName();
    }

    /**
     * Whether the step solves apply a user constraint of this kind. True by default. A user constraint that is not
     * applied keeps what the loop decided about it and is measured after every step ({@link #reportUserConstraints}).
     */
    protected boolean appliesUserConstraint(Constraint c) {
        return true;
    }

    /**
     * Whether a user constraint that the step solves apply and that does not hold after a step is reported as "not
     * satisfied" (true), or only the constraints the solves do not apply are reported (false).
     */
    protected boolean reportsAppliedUserConstraints() {
        return true;
    }

    /** The nodes the step solves move: the scope the user's constraints are decided in (all nodes by default). */
    protected Collection<Node> solvedNodes() {
        return graph.getNodes();
    }

    /**
     * The scope the user's constraints are decided in before a run: by default the {@link #solvedNodes()} without
     * clusters and overlaps. A layout whose solves hold the other nodes out of the user's clusters and remove overlaps
     * returns a scope with the rectangle padding and {@code avoidOverlaps}.
     */
    protected ConstraintConflicts.Scope resolveScope() {
        return new ConstraintConflicts.Scope(solvedNodes(), null, Double.NaN, false, false);
    }

    /**
     * Which of the user's constraints the step solves apply: contradictions among the kinds they apply are settled by
     * priority first ({@link ConstraintConflicts}), as in the Constraints tab. The other kinds keep what the tab
     * decided: the solves do not apply them, so they must not push out a constraint that is applied here. The steps'
     * own constraints are left to the algorithm's guard, whatever a drag between two steps decided about them.
     */
    protected void resolveUserConstraints() {
        if (ConstraintStore.constraints == null) return;
        List<Constraint> applied = new ArrayList<>(), automatic = new ArrayList<>();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (c.isAutomatic()) automatic.add(c);
            else if (appliesUserConstraint(c)) applied.add(c);
        }
        ConstraintConflicts.clearDeactivations(automatic);
        ConstraintConflicts.Scope scope = resolveScope();
        ConstraintConflicts.resolve(applied, scope);
        CBLayoutAlgorithm.lastUserScope = scope;   // the cluster sides the solves of the steps hold the other nodes on
        ConflictLog.setDeactivated(ConstraintStore.constraints.getAllConstraints());
    }

    /**
     * Measures every active user constraint of the graph ({@link ConstraintCheck}). One the step solves do not apply
     * (see {@link #appliesUserConstraint}) is listed as not applied by the algorithm, so it does not come as a surprise
     * when the next constraint solve (a drag, the Constraints tab) applies it again; one they apply but that does not
     * hold is listed as not satisfied (if {@link #reportsAppliedUserConstraints()}).
     */
    protected void reportUserConstraints() {
        if (ConstraintStore.constraints == null) return;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (c.isAutomatic() || !c.isActive()) continue;
            boolean applies = appliesUserConstraint(c);
            if (applies && !reportsAppliedUserConstraints()) continue;
            boolean inGraph = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) inGraph &= graph.containsNode(n);
            if (!inGraph) continue;
            String why = ConstraintCheck.violation(c, graph.getNodes(), ConstraintCheck.TOLERANCE);
            if (why == null) continue;
            if (applies) ConflictLog.addUnsatisfied(c, phaseName(), "not satisfied after the step (" + why + ")");
            else ConflictLog.addUnsatisfied(c, phaseName(), "not applied by the " + phaseName() + " layout (" + why
                    + "); the next constraint solve applies it again");
        }
    }

    /** Constraints created by a step are automatic: the guard may drop them, user constraints never. */
    protected static void markAutomatic(List<Constraint> created) {
        for (Constraint c : created) c.setAutomatic(true);
    }

    private void snapshotOriginalPositions() {
        originalPositions = new HashMap<>();
        for (Node n : graph.getNodes()) {
            originalPositions.put(n, new Point2D.Double(AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n)));
        }
        onSnapshot();
    }

    /** The remembered position of a node from before the first step, or null. */
    protected Point2D.Double originalPosition(Node n) {
        return originalPositions == null ? null : originalPositions.get(n);
    }

    /** The remembered positions from before the first step (null before the first run). */
    protected Map<Node, Point2D.Double> originalPositions() {
        return originalPositions;
    }

    private void beginStepCapture() {
        if (ConstraintStore.constraints == null) return;
        constraintsBeforeStep = new ArrayList<>(ConstraintStore.constraints.getAllConstraints());
    }

    private List<Constraint> endStepCapture() {
        if (ConstraintStore.constraints == null || constraintsBeforeStep == null) return Collections.emptyList();
        List<Constraint> after = ConstraintStore.constraints.getAllConstraints();
        List<Constraint> diff = new ArrayList<>(after);
        diff.removeAll(constraintsBeforeStep);
        constraintsBeforeStep = null;
        return diff;
    }

    /**
     * True while a step runs for a constraint the step created so far (the capture marks it automatic only when the
     * step ends); false for a constraint that was in the store before the step, among them the user's.
     */
    protected boolean isNewInStep(Constraint c) {
        return constraintsBeforeStep == null || !constraintsBeforeStep.contains(c);
    }

    /** The constraints that were in the store when the running step began (null outside a step). */
    protected List<Constraint> constraintsBeforeStep() {
        return constraintsBeforeStep;
    }

    /** Template instances a step applied (see {@link StepTemplates}); {@link #resetToOriginal()} removes them. */
    void rememberInstances(Collection<TemplateInstance> made) {
        instances.addAll(made);
    }

    /** The template instances the steps of this run applied, in order. */
    public List<TemplateInstance> appliedInstances() {
        return Collections.unmodifiableList(instances);
    }

    /**
     * A constraint of the user that a step puts back into the store (e.g. its node was removed for some steps and is
     * back). It stays the user's: the step capture would otherwise take it for a constraint the step created.
     */
    public void keepUserConstraint(Constraint c) {
        if (ConstraintStore.constraints == null) return;
        if (!ConstraintStore.constraints.getAllConstraints().contains(c)) ConstraintStore.constraints.addConstraint(c);
        if (constraintsBeforeStep != null && !constraintsBeforeStep.contains(c)) constraintsBeforeStep.add(c);
    }
}
