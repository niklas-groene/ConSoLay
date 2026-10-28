package layout.steps;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import constraints.Priority;
import layout.template.apply.ConflictPreview;
import layout.template.apply.InstanceKeys;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;
import layout.template.io.TemplateCodec;
import layout.template.io.TemplateFiles;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.RuleSystem;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;
import layout.template.model.Templates;
import layout.template.search.GraphSnapshot;
import layout.template.search.Match;
import layout.template.search.MatchCheck;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;
import layout.template.search.TemplateLimits;

import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Templates and rule sets of the library for a layout step: a step searches a template or a rule set on its graph (or
 * on a part of it) and applies the matches synchronously on the event thread, as the Templates page does, with the same
 * limits ({@link TemplateLimits}), the same conflict decision and instances the Templates page lists; Undo does not
 * know them, Reset of the layout removes them ({@link StepLayout#resetToOriginal()}). The constraints an instance makes
 * carry their {@link constraints.ConstraintOrigin} and rank as {@link Rank} says.
 *
 * @author Niklas Gröne
 */
public final class StepTemplates {

    private StepTemplates() { }

    /**
     * How the constraints a step makes through a template rank in the loop: AUTOMATIC like every constraint a step adds
     * (below the user's constraints of equal priority), TEMPLATE like a template the user applied.
     */
    public enum Rank { AUTOMATIC, TEMPLATE }

    /** What one application did. */
    public static final class Result {
        /** The search; {@link SearchRun#matches()} holds every match, {@link SearchRun#limitMessage()} why it stopped early. */
        public final SearchRun run;
        public final List<TemplateInstance> made = new ArrayList<>();
        public int constraints;
        /** Requests left out, with the reason (already applied, no longer fits, …). */
        public final List<String> skipped = new ArrayList<>();
        /** How many batches (solves) the application took ({@link TemplateLimits#maxApplyPerStep} matches each). */
        public int batches;

        Result(SearchRun run) {
            this.run = run;
        }

        @Override
        public String toString() {
            return run.matches().size() + " matches, " + made.size() + " instances, " + constraints + " constraints in " + batches
                    + (batches == 1 ? " batch" : " batches") + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped")
                    + (run.limitMessage() == null ? "" : "; " + run.limitMessage());
        }
    }

    /** The search tasks of a rule set: one per rule that is switched on, with the rule's priority. */
    public static List<SearchTask> tasks(RuleSystem rules) {
        List<SearchTask> l = new ArrayList<>();
        TemplateSource source = TemplateSource.of(rules);
        for (RuleSystem.Rule r : rules.rules) {
            if (!r.enabled) continue;
            TemplateDef t = source.get(r.template);
            if (t == null) throw new IllegalArgumentException("Rule '" + r.name + "': its template " + r.template + " is not in the rule set.");
            l.add(new SearchTask(t, source, r.name, r.priority));
        }
        return l;
    }

    /** One search task for a template (its dependencies embedded in it, as a saved template has them). */
    public static SearchTask task(TemplateDef t) {
        return new SearchTask(t, TemplateSource.embeddedIn(t), null, null);
    }

    /** One search task for a template whose dependencies come from {@code source} (a library), with a priority for its constraints. */
    public static SearchTask task(TemplateDef t, TemplateSource source, Priority priority) {
        return new SearchTask(t, source, null, priority);
    }

    /** Loads a rule set file ({@code .cblrules.json}). */
    public static RuleSystem loadRules(File file) throws IOException {
        try {
            return TemplateFiles.loadRules(file);
        } catch (TemplateCodec.FormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Loads a template file ({@code .cbltemplate.json}). */
    public static TemplateDef loadTemplate(File file) throws IOException {
        try {
            return TemplateFiles.load(file);
        } catch (TemplateCodec.FormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The problems the validator finds with a task's template (an ERROR or NOT_APPLICABLE blocks applying). */
    public static List<TemplateValidator.Problem> validate(SearchTask task) {
        return TemplateValidator.validate(task.template, task.source);
    }

    /**
     * Searches {@code tasks} on {@code g}, among {@code scope} (null: the whole graph), synchronously. {@code semantics}:
     * the conditions on roles and attributes count (false: structure only); {@code exact}: no further edges among the
     * assigned nodes. The run collects at most {@link TemplateLimits#maxMatchesPerRun} matches and stops after
     * {@link TemplateLimits#maxAssignments} complete or {@link TemplateLimits#maxSteps} partial assignments;
     * {@link SearchRun#limitMessage()} says so.
     */
    public static SearchRun search(Graph g, Collection<Node> scope, List<SearchTask> tasks, boolean semantics, boolean exact) {
        return search(g, scope, tasks, semantics, exact, TemplateLimits.maxMatchesPerRun);
    }

    public static SearchRun search(Graph g, Collection<Node> scope, List<SearchTask> tasks, boolean semantics, boolean exact, int maxMatches) {
        Set<String> np = new HashSet<>(), ep = new HashSet<>();
        for (SearchTask t : tasks) {
            for (PatternNode n : Templates.flatNodes(t.template, t.source).values()) for (Condition c : n.conditions) np.add(c.attribute);
            for (PatternEdge e : Templates.flatEdges(t.template, t.source).values()) for (Condition c : e.conditions) ep.add(c.attribute);
            for (TemplateDef d : Templates.closure(t.template, t.source))
                for (StructureLink l : d.links) for (Condition c : l.edgeConditions) ep.add(c.attribute);
        }
        GraphSnapshot snap = GraphSnapshot.capture(g, scope, np, ep, System.nanoTime());
        SearchRun run = new SearchRun(snap, tasks, new SearchRun.Options(semantics, exact, maxMatches));
        run.runNow(new SearchRun.Listener() {
            @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) { }
            @Override public void finished(SearchRun r) { }
        });
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(() -> { });   // the matches are delivered on the event thread
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return run;
    }

    /**
     * The conflict preview of applying {@code matches} (their chosen variants) together: which existing constraints
     * they would push out and which of theirs would not be applied, decided on copies as the Templates page shows it
     * before applying; nothing changes.
     */
    public static MatchCheck preview(Graph g, List<Match> matches) {
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : matches) reqs.add(request(m));
        ConstraintConflicts.Scope scope = CBLayoutAlgorithm.userScope(g, false, false);
        return ConflictPreview.check(g, ConstraintStore.constraints == null ? new ArrayList<>() : ConstraintStore.constraints.getAllConstraints(),
                reqs, scope, 0);
    }

    /**
     * Applies {@code matches} (their chosen variants) on the graph of {@code layout} in batches of
     * {@link TemplateLimits#maxApplyPerStep}, each batch one solve of the loop ({@code solve}; null: the user solve of
     * the add-on on the whole graph, projection only), which decides the conflicts by rank. A match that is already
     * applied, or whose nodes are gone, is skipped with a reason. The instances join the Templates page's registry.
     *
     * @param place place the matched nodes like the template before the solve (the Templates page's option)
     * @param rank  how the new constraints rank (see {@link Rank})
     */
    public static Result apply(StepLayout layout, SearchRun run, List<Match> matches, boolean place, Rank rank, Runnable solve) {
        Graph g = layout.graph;
        Result r = new Result(run);
        if (ConstraintStore.constraints == null) throw new IllegalStateException("The constraint store is not ready.");
        Runnable doSolve = solve != null ? solve : () -> CBLayoutAlgorithm.executeUserConstraints(g, false, false);
        List<Match> pending = new ArrayList<>();
        for (Match m : matches) {
            if (m.run.snapshot.graph != g) continue;
            if (InstanceRegistry.find(g, key(m)) != null) {
                r.skipped.add(m.title() + ": already applied");
                continue;
            }
            pending.add(m);
        }
        for (int from = 0; from < pending.size(); from += TemplateLimits.maxApplyPerStep) {
            List<TemplateApplier.Request> reqs = new ArrayList<>();
            for (Match m : pending.subList(from, Math.min(pending.size(), from + TemplateLimits.maxApplyPerStep)))
                reqs.add(request(m));
            TemplateApplier.Result res = TemplateApplier.apply(g, reqs, ConstraintStore.constraints, place, doSolve);
            r.batches++;
            r.made.addAll(res.made);
            r.constraints += res.constraints;
            r.skipped.addAll(res.skipped);
            if (rank == Rank.TEMPLATE) {
                for (TemplateInstance inst : res.made) for (Constraint c : inst.constraints()) layout.keepUserConstraint(c);
            }
            layout.rememberInstances(res.made);
            for (TemplateInstance inst : res.made) {
                for (Match m : pending) {
                    if (m.instance == null && key(m).equals(inst.key)) {
                        m.instance = inst;
                        m.state = Match.State.APPLIED;
                    }
                }
            }
        }
        return r;
    }

    /** {@link #search} and {@link #apply} of every match in one call; the usual case of a step. */
    public static Result searchAndApply(StepLayout layout, Collection<Node> scope, List<SearchTask> tasks, boolean semantics,
                                        boolean exact, boolean place, Rank rank, Runnable solve) {
        SearchRun run = search(layout.graph, scope, tasks, semantics, exact);
        return apply(layout, run, run.matches(), place, rank, solve);
    }

    private static String key(Match m) {
        return InstanceKeys.key(m.task.plan, m.variant().nodes, m.variant().edges);
    }

    static TemplateApplier.Request request(Match m) {
        Match.Variant v = m.variant();
        return new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title());
    }
}
