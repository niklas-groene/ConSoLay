package gui.template;

import constraints.Constraint;
import constraints.ConstraintConflicts;
import constraints.ConstraintStore;
import gui.AddonTab;
import gui.DisplayConstraints;
import gui.ListModels;
import gui.components.Ui;
import layout.template.apply.ConflictPreview;
import layout.template.apply.InstanceEdit;
import layout.template.apply.InstanceKeys;
import layout.store.GraphConstraints;
import layout.template.apply.InstanceRegistry;
import layout.template.apply.TemplateApplier;
import layout.template.apply.TemplateInstance;
import layout.template.apply.TemplateMetrics;
import layout.template.model.Condition;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.StructureLink;
import layout.template.model.Templates;
import layout.template.search.GraphSnapshot;
import layout.template.search.Match;
import layout.template.search.MatchCheck;
import layout.template.search.MatchValidator;
import layout.template.search.SearchRun;
import layout.template.search.SearchTask;
import layout.template.search.TemplateLimits;

import de.ipk_gatersleben.ag_nw.graffiti.GraphHelper;
import org.BackgroundTaskStatusProviderSupportingExternalCall;
import org.graffiti.attributes.Attributable;
import org.graffiti.attributes.Attribute;
import org.graffiti.editor.MainFrame;
import org.graffiti.event.AttributeEvent;
import org.graffiti.event.AttributeListener;
import org.graffiti.event.GraphEvent;
import org.graffiti.event.GraphListener;
import org.graffiti.event.TransactionEvent;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.GraphElement;
import org.graffiti.graph.Node;
import org.graffiti.plugin.view.View;
import org.graffiti.session.EditorSession;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Coordinates the template workflow on the graphs (Swing thread only): per graph the newest search run and its matches,
 * which of them are selected for applying, their staleness after graph changes, the conflict checks, the application
 * (one undoable edit per step) and the actions on applied instances. The panel, the graph overlay and the match
 * popover all read and change the state here; {@link #addListener listeners} hear about every change.
 * <p>
 * Everything belongs to one graph: a view of another graph never shows it, and a search's late results never
 * overwrite a newer run's.
 *
 * @author Niklas Gröne
 */
public final class TemplateWorkbench {

    private static TemplateWorkbench instance;

    public static TemplateWorkbench get() {
        if (instance == null) instance = new TemplateWorkbench();
        return instance;
    }

    /** What the workbench keeps for one graph. */
    static final class GraphState {
        long version;
        SearchRun run;
        /** The graph changed after the run started: new matches may exist. */
        boolean runOutdated;
        int page;
        Watch watch;
        /** Last message of a search or application, for the status line. */
        String message;
    }

    private final Map<Graph, GraphState> states = new WeakHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean suggestionMode;
    /** Place matched nodes like the template when applying. */
    private boolean placeNodes = true;
    Match hover;
    Match focus;
    /** Matches offered in the chooser for overlapping matches (null: none). */
    List<Match> chooser;
    /** Instance emphasised on the graph (from the instance list), or null. */
    TemplateInstance focusInstance;
    private boolean firing, fireQueued;
    /** The scope option of the last search (for "Find more"). */
    boolean lastWithinSelection;

    private TemplateWorkbench() {
        InstanceRegistry.addListener(this::fireLater);
    }

    // ================================================================== graphs and listeners

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    /** Tells the listeners (panel, overlay) about a change; coalesced when called while firing. */
    public void fire() {
        if (firing) {
            fireLater();
            return;
        }
        firing = true;
        try {
            for (Runnable r : new ArrayList<>(listeners)) {
                try {
                    r.run();
                } catch (RuntimeException ignored) {
                    // one broken listener must not stop the others
                }
            }
            MatchOverlay.repaintAll();
        } finally {
            firing = false;
        }
    }

    void fireLater() {
        if (fireQueued) return;
        fireQueued = true;
        SwingUtilities.invokeLater(() -> {
            fireQueued = false;
            fire();
        });
    }

    static EditorSession activeSession() {
        try {
            return MainFrame.getInstance() == null ? null : MainFrame.getInstance().getActiveEditorSession();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Graph activeGraph() {
        EditorSession s = activeSession();
        return s == null ? null : s.getGraph();
    }

    static View activeView() {
        EditorSession s = activeSession();
        return s == null ? null : s.getActiveView();
    }

    GraphState state(Graph g) {
        if (g == null) return null;
        GraphState s = states.get(g);
        if (s == null) {
            s = new GraphState();
            s.watch = new Watch(s);
            try {
                g.getListenerManager().addAllTimeGraphListener(s.watch);
                g.getListenerManager().addAllTimeAttributeListener(s.watch);
            } catch (Exception ignored) {
                // no change tracking: matches are revalidated before applying anyway
            }
            states.put(g, s);
        }
        return s;
    }

    /** The newest run on {@code g}, or null. */
    public SearchRun run(Graph g) {
        GraphState s = g == null ? null : states.get(g);
        return s == null ? null : s.run;
    }

    /** All matches of the newest run on {@code g}. */
    public List<Match> matches(Graph g) {
        SearchRun r = run(g);
        return r == null ? Collections.emptyList() : r.matches();
    }

    /** The matches shown now (one page of at most {@link TemplateLimits#maxShown}). */
    public List<Match> shown(Graph g) {
        List<Match> all = matches(g);
        GraphState s = states.get(g);
        int page = s == null ? 0 : s.page;
        int from = Math.min(all.size(), page * TemplateLimits.maxShown), to = Math.min(all.size(), from + TemplateLimits.maxShown);
        return all.subList(from, to);
    }

    public int page(Graph g) {
        GraphState s = states.get(g);
        return s == null ? 0 : s.page;
    }

    public int pageCount(Graph g) {
        int n = matches(g).size();
        return Math.max(1, (n + TemplateLimits.maxShown - 1) / TemplateLimits.maxShown);
    }

    public void setPage(Graph g, int page) {
        GraphState s = state(g);
        if (s == null) return;
        s.page = Math.max(0, Math.min(pageCount(g) - 1, page));
        closePopover();
        fire();
    }

    public boolean isRunOutdated(Graph g) {
        GraphState s = states.get(g);
        return s != null && s.runOutdated;
    }

    public String message(Graph g) {
        GraphState s = states.get(g);
        return s == null ? null : s.message;
    }

    public long version(Graph g) {
        GraphState s = states.get(g);
        return s == null ? 0 : s.version;
    }

    public boolean isSuggestionMode() {
        return suggestionMode;
    }

    /** Suggestion mode: matches are drawn on the graph and react to hover and click; Escape leaves it. */
    public void setSuggestionMode(boolean on) {
        if (on == suggestionMode) return;
        suggestionMode = on;
        if (on) MatchInteraction.get().attach();
        else {
            MatchInteraction.get().detach();
            hover = null;
            closePopover();
        }
        fire();
    }

    public void setPlaceNodes(boolean on) {
        placeNodes = on;
    }

    /** The active view shows another graph now: drop what belongs to the old one. */
    public void activeViewChanged() {
        hover = null;
        closePopover();
        focusInstance = null;
        if (suggestionMode) MatchInteraction.get().attach();
        fire();
    }

    // ================================================================== search

    /**
     * Starts a search on the active graph for {@code tasks} (cancelling a running one). {@code withinSelection}: only
     * the selected nodes may take part in a match (a restriction of where to search, not a choice of matches).
     *
     * @return null if started, else why not
     */
    public String search(List<SearchTask> tasks, boolean semantics, boolean exact, boolean withinSelection) {
        return search(tasks, semantics, exact, withinSelection, TemplateLimits.maxMatchesPerRun);
    }

    /** {@link #search(List, boolean, boolean, boolean)} collecting at most {@code maxMatches} matches. */
    public String search(List<SearchTask> tasks, boolean semantics, boolean exact, boolean withinSelection, int maxMatches) {
        Graph g = activeGraph();
        if (g == null) return "Open a graph first.";
        if (tasks.isEmpty()) return "Choose a template first.";
        Collection<Node> scope = null;
        if (withinSelection) {
            EditorSession s = activeSession();
            List<Node> sel = s == null ? Collections.emptyList() : GraphHelper.getSelectedNodes(s);
            if (sel.isEmpty()) return "Select the nodes to search among, or search the whole graph.";
            scope = sel;
        }
        GraphState st = state(g);
        if (st.run != null && st.run.state() == SearchRun.State.RUNNING) st.run.cancel();
        Set<String> np = new HashSet<>(), ep = new HashSet<>();
        for (SearchTask t : tasks) {
            for (PatternNode n : Templates.flatNodes(t.template, t.source).values()) for (Condition c : n.conditions) np.add(c.attribute);
            for (PatternEdge e : Templates.flatEdges(t.template, t.source).values()) for (Condition c : e.conditions) ep.add(c.attribute);
            for (layout.template.model.TemplateDef d : Templates.closure(t.template, t.source))
                for (StructureLink l : d.links) for (Condition c : l.edgeConditions) ep.add(c.attribute);
        }
        GraphSnapshot snap = GraphSnapshot.capture(g, scope, np, ep, st.version);
        SearchRun run = new SearchRun(snap, tasks, new SearchRun.Options(semantics, exact, maxMatches));
        lastWithinSelection = withinSelection;
        st.run = run;
        st.runOutdated = false;
        st.page = 0;
        st.message = null;
        hover = null;
        closePopover();
        run.start(new SearchRun.Listener() {
            @Override public void delivered(SearchRun r, List<Match> added, List<Match> changed) {
                if (st.run != r) return;   // an older run: its late results are dropped
                for (Match m : added) markIfApplied(g, m);
                fire();
            }

            @Override public void finished(SearchRun r) {
                StringBuilder names = new StringBuilder();
                for (SearchTask t : r.tasks) names.append(names.length() > 0 ? "; " : "").append(t.title());
                TemplateMetrics.search(names.toString(), snap.nodeCount(), snap.edges.length, r.assignments(), r.matches().size(),
                        r.durationMillis(), r.state().name());
                if (st.run != r) return;
                fire();
            }
        });
        fire();
        return null;
    }

    public void cancelSearch() {
        SearchRun r = run(activeGraph());
        if (r != null) r.cancel();
    }

    /** A match that an existing instance already covers (same template revision and application) counts as applied. */
    private static void markIfApplied(Graph g, Match m) {
        TemplateInstance i = InstanceRegistry.find(g, InstanceKeys.key(m.task.plan, m.variant().nodes, m.variant().edges));
        if (i != null) {
            m.state = Match.State.APPLIED;
            m.instance = i;
        }
    }

    // ================================================================== selection of matches

    public void setSelected(Match m, boolean on) {
        if (m.state == Match.State.APPLIED || m.state == Match.State.INVALID) on = false;
        m.selected = on;
        fire();
    }

    /** Selects every found match that can be applied (all of the run, not only the shown page). */
    public void selectAllFound(Graph g) {
        for (Match m : matches(g)) m.selected = m.state == Match.State.FOUND || m.state == Match.State.STALE;
        fire();
    }

    public void clearSelected(Graph g) {
        for (Match m : matches(g)) m.selected = false;
        fire();
    }

    public List<Match> selected(Graph g) {
        List<Match> l = new ArrayList<>();
        for (Match m : matches(g)) if (m.selected) l.add(m);
        return l;
    }

    /** Chooses another assignment of {@code m} (its conflict check is made again later). */
    public void chooseVariant(Match m, int index) {
        m.choose(index);
        Graph g = m.run.snapshot.graph;
        if (m.state == Match.State.APPLIED) m.state = Match.State.FOUND;
        markIfApplied(g, m);
        fire();
    }

    // ================================================================== checks

    static TemplateApplier.Request request(Match m) {
        Match.Variant v = m.variant();
        return new TemplateApplier.Request(m.task, v.nodes, v.edges, v.links, m.title());
    }

    /** The conflict check of {@code m} (made now if it is missing or the graph changed since). */
    public MatchCheck check(Match m) {
        Graph g = m.run.snapshot.graph;
        long version = version(g);
        if (m.check.status != MatchCheck.Status.NOT_CHECKED && m.check.version == version) return m.check;
        if (ConstraintStore.constraints == null || g != activeGraph()) return m.check;
        if (m.state == Match.State.INVALID) return m.check;
        ConstraintConflicts.Scope scope = AddonTab.activeScope();
        if (scope == null) return m.check;
        m.check = ConflictPreview.check(g, ConstraintStore.constraints.getAllConstraints(),
                Collections.singletonList(request(m)), scope, version);
        return m.check;
    }

    /** Checks several matches together (conflicts among them count too); changes nothing. */
    public MatchCheck checkTogether(List<Match> ms) {
        Graph g = activeGraph();
        ConstraintConflicts.Scope scope = AddonTab.activeScope();
        if (g == null || scope == null || ConstraintStore.constraints == null) return MatchCheck.NOT_CHECKED;
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        for (Match m : ms) if (m.state != Match.State.APPLIED && m.state != Match.State.INVALID) reqs.add(request(m));
        return ConflictPreview.check(g, ConstraintStore.constraints.getAllConstraints(), reqs, scope, version(g));
    }

    /** Constraints a set of matches would make (for the counts before applying). */
    public static int constraintCount(List<Match> ms) {
        int n = 0;
        for (Match m : ms) if (m.state != Match.State.APPLIED && m.state != Match.State.INVALID) n += m.task.plan.size();
        return n;
    }

    // ================================================================== apply

    /**
     * Applies the matches on the active graph as one step: at most {@link TemplateLimits#maxApplyPerStep}; outdated ones
     * are checked again first (those that no longer fit are left out and marked); applied and invalid ones are skipped.
     * Posts one undoable edit.
     *
     * @return a message for the status line
     */
    public String apply(List<Match> ms, String what) {
        Graph g = activeGraph();
        if (g == null) return "Open a graph first.";
        if (ConstraintStore.constraints == null) return "The constraint store is not ready.";
        List<Match> todo = new ArrayList<>();
        int skippedApplied = 0, invalid = 0;
        List<String> reasons = new ArrayList<>();
        for (Match m : ms) {
            if (m.run.snapshot.graph != g) continue;
            if (m.state == Match.State.APPLIED) { skippedApplied++; continue; }
            if (m.state == Match.State.INVALID) { invalid++; continue; }
            if (todo.size() >= TemplateLimits.maxApplyPerStep) break;
            if (m.state == Match.State.STALE) {
                String why = MatchValidator.revalidate(m, g);
                if (why != null) {
                    m.state = Match.State.INVALID;
                    m.reason = why;
                    m.selected = false;
                    invalid++;
                    reasons.add(m.title() + ": " + why);
                    continue;
                }
                m.state = Match.State.FOUND;
            }
            todo.add(m);
        }
        int left = 0;
        for (Match m : ms) if (m.state == Match.State.FOUND && m.selected && !todo.contains(m)) left++;
        if (todo.isEmpty()) {
            fire();
            return "Nothing to apply" + (skippedApplied > 0 ? ": " + skippedApplied + " already applied" : "")
                    + (invalid > 0 ? ", " + invalid + " no longer fit" : "") + ".";
        }
        List<TemplateApplier.Request> reqs = new ArrayList<>();
        Map<TemplateApplier.Request, Match> byReq = new IdentityHashMap<>();
        for (Match m : todo) {
            TemplateApplier.Request q = request(m);
            reqs.add(q);
            byReq.put(q, m);
        }
        closePopover();
        TemplateApplier.Result r = TemplateApplier.apply(g, reqs, ConstraintStore.constraints, placeNodes, AddonTab::executeUserConstraints);
        for (TemplateInstance inst : r.made) {
            for (Match m : todo) {
                if (m.instance == null && InstanceKeys.key(m.task.plan, m.variant().nodes, m.variant().edges).equals(inst.key)) {
                    m.instance = inst;
                    m.state = Match.State.APPLIED;
                    m.selected = false;
                }
            }
        }
        for (Map.Entry<TemplateApplier.Request, TemplateInstance> e : r.duplicates.entrySet()) {
            Match m = byReq.get(e.getKey());
            if (m != null) {
                m.state = Match.State.APPLIED;
                m.instance = e.getValue();
                m.selected = false;
            }
        }
        // other matches of the run that the new instances cover are applied as well (symmetric duplicates)
        for (Match m : matches(g)) if (m.state == Match.State.FOUND) markIfApplied(g, m);
        InstanceEdit edit = InstanceEdit.applied(r.made.size() == 1 ? "Apply template" : "Apply " + r.made.size() + " templates", g, r,
                this::afterUndo);
        if (edit.isSignificant()) postEdit(edit);
        TemplateMetrics.apply(r.made.size(), r.constraints, r.skipped.size(), r.createNanos / 1e6, r.placeNanos / 1e6,
                r.solveNanos / 1e6, r.totalNanos / 1e6);
        refreshConstraintViews();
        int notApplied = 0;
        for (TemplateInstance i : r.made) notApplied += i.status(ConstraintStore.constraints).notApplied;
        StringBuilder msg = new StringBuilder();
        msg.append(what == null ? "Applied " : what + ": applied ").append(r.made.size()).append(r.made.size() == 1 ? " match" : " matches")
                .append(" (").append(r.constraints).append(r.constraints == 1 ? " constraint" : " constraints").append(")");
        if (notApplied > 0) msg.append("; ").append(notApplied).append(" not applied because of conflicts");
        if (!r.skipped.isEmpty() || skippedApplied > 0) msg.append("; ").append(r.skipped.size() + skippedApplied).append(" skipped (already applied)");
        if (invalid > 0) msg.append("; ").append(invalid).append(" no longer fit");
        if (left > 0) msg.append("; ").append(left).append(" selected left for the next step (limit ").append(TemplateLimits.maxApplyPerStep).append(" per step)");
        msg.append(String.format(Locale.ROOT, ". %.0f ms. Undo with Edit › Undo.", r.totalNanos / 1e6));
        state(g).message = msg.toString();
        fire();
        return msg.toString();
    }

    private void postEdit(InstanceEdit edit) {
        try {
            MainFrame.getInstance().getUndoSupport().postEdit(edit);
        } catch (RuntimeException ignored) {
            // no undo support (tests)
        }
    }

    private void afterUndo() {
        Graph g = activeGraph();
        if (g != null) {
            for (Match m : matches(g)) {
                if (m.instance instanceof TemplateInstance && InstanceRegistry.byId(((TemplateInstance) m.instance).id) == null) {
                    m.instance = null;
                    m.state = Match.State.FOUND;
                }
                if (m.state == Match.State.FOUND) markIfApplied(g, m);
            }
        }
        refreshConstraintViews();
        fire();
    }

    static void refreshConstraintViews() {
        ListModels.updateAllLists();
        DisplayConstraints.revalidateIndication();
    }

    // ================================================================== instances

    /** Switches an instance (part "" = the whole instance) on or off, solves again, undoable. */
    public void setInstanceOn(TemplateInstance inst, String part, boolean on) {
        Graph g = inst.graph;
        Map<Node, double[]> before = TemplateApplier.positions(g);
        InstanceEdit e = new InstanceEdit(on ? "Switch template instance on" : "Switch template instance off", g, this::afterUndo)
                .switchesBefore(inst);
        List<Constraint> changed = TemplateApplier.setOn(inst, part, on);
        e.switchesAfter(inst);
        if (!changed.isEmpty() && g == activeGraph()) AddonTab.executeUserConstraints();
        e.positions(before, TemplateApplier.positions(g));
        postEdit(e);
        refreshConstraintViews();
        fire();
    }

    /** Removes an instance and exactly its own constraints, solves again, undoable. */
    public void removeInstance(TemplateInstance inst) {
        Graph g = inst.graph;
        Map<Node, double[]> before = TemplateApplier.positions(g);
        List<Constraint> gone = TemplateApplier.remove(inst, ConstraintStore.constraints);
        InstanceEdit e = new InstanceEdit("Remove template instance", g, this::afterUndo).removed(inst, gone);
        if (g == activeGraph()) AddonTab.executeUserConstraints();
        e.positions(before, TemplateApplier.positions(g));
        postEdit(e);
        for (Match m : matches(g)) {
            if (m.instance == inst) {
                m.instance = null;
                m.state = Match.State.FOUND;
            }
        }
        if (focusInstance == inst) focusInstance = null;
        refreshConstraintViews();
        fire();
    }

    /**
     * "Prefer this instance": shows what would change (priorities raised and lowered, what would stop being applied)
     * and does it after confirmation; undoable.
     */
    public void preferInstance(TemplateInstance inst, Component parent) {
        ConstraintConflicts.Scope scope = AddonTab.activeScope();
        if (scope == null || inst.graph != activeGraph()) return;
        List<Constraint> all = ConstraintStore.constraints.getAllConstraints();
        ConflictPreview.PreferPreview p = ConflictPreview.previewPrefer(inst, all, scope);
        if (!p.changesSomething()) {
            JOptionPane.showMessageDialog(parent, p.impossible.isEmpty()
                    ? "Every constraint of " + inst.label() + " is applied already; nothing to prefer."
                    : "The constraints of " + inst.label() + " that are not applied contradict themselves; no priority can change that.",
                    "Prefer this instance", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder("<html><b>Prefer " + Ui.esc(inst.label()) + "</b>: its constraints win over those they contradict.<br><br>");
        sb.append("Priorities that change:<br>");
        for (String s : limit(p.priorityChanges)) sb.append("• ").append(Ui.esc(s)).append("<br>");
        if (!p.gained.isEmpty()) sb.append("<br>Applied afterwards: ").append(p.gained.size()).append(" of its constraints.<br>");
        if (!p.lost.isEmpty()) {
            sb.append("<br><font color='#b06000'>No longer applied:</font><br>");
            for (String s : limit(p.lost)) sb.append("• ").append(Ui.esc(s)).append("<br>");
        }
        if (!p.impossible.isEmpty()) sb.append("<br>Cannot be applied in any case: ").append(p.impossible.size()).append("<br>");
        sb.append("</html>");
        int ok = JOptionPane.showConfirmDialog(parent, sb.toString(), "Prefer this instance", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        Graph g = inst.graph;
        Map<Node, double[]> before = TemplateApplier.positions(g);
        InstanceEdit e = new InstanceEdit("Prefer template instance", g, this::afterUndo).prioritiesBefore(new ArrayList<>(all));
        ConflictPreview.preferInstance(inst, all, scope);
        e.prioritiesAfter(new ArrayList<>(all));
        AddonTab.executeUserConstraints();
        e.positions(before, TemplateApplier.positions(g));
        postEdit(e);
        refreshConstraintViews();
        fire();
    }

    private static List<String> limit(List<String> l) {
        if (l.size() <= 8) return l;
        List<String> k = new ArrayList<>(l.subList(0, 8));
        k.add("… and " + (l.size() - 8) + " more");
        return k;
    }

    static String esc(String s) {
        return Ui.esc(s);
    }

    // ================================================================== popover

    /** Opens the action popover of {@code m} (closing any other). */
    public void openPopover(Match m) {
        chooser = null;
        focus = m;
        if (m != null) check(m);
        MatchPopover.show(m);
        fire();
    }

    /** Opens the compact chooser for overlapping matches at a point of the view. */
    void openChooser(List<Match> ms, Point viewPoint) {
        focus = null;
        chooser = ms;
        MatchPopover.showChooser(ms, viewPoint);
        fire();
    }

    public void closePopover() {
        boolean was = focus != null || chooser != null;
        focus = null;
        chooser = null;
        MatchPopover.hideAll();
        if (was) MatchOverlay.repaintAll();
    }

    /** Shows the constraints the match would make as markings on the graph (nothing is added). */
    public void showPlannedConstraints(Match m) {
        try {
            Map<String, double[]> off = TemplateApplier.offsets(m.task.plan, m.variant().nodes);
            List<Constraint> preview = new ArrayList<>();
            for (TemplateApplier.Built b : TemplateApplier.instantiate(m.task.plan, m.variant().nodes, m.variant().edges, off)) {
                preview.add(b.constraint);
            }
            DisplayConstraints.clearMarkings();
            DisplayConstraints.drawConstraints(preview);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(null, "The constraints cannot be shown: " + e.getMessage());
        }
    }

    // ================================================================== change tracking

    /** Marks matches outdated or invalid when the structure or the searched attributes of the graph change. */
    final class Watch implements GraphListener, AttributeListener {
        final GraphState state;

        Watch(GraphState state) {
            this.state = state;
        }

        private void changed(GraphElement el, boolean removed, boolean structural) {
            Runnable r = () -> {
                state.version++;
                if (structural) state.runOutdated = true;
                SearchRun run = state.run;
                if (run == null) return;
                boolean any = false;
                for (Match m : run.matches()) {
                    if (m.state == Match.State.APPLIED || m.state == Match.State.INVALID) continue;
                    if (touches(m, el)) {
                        if (removed) {
                            m.state = Match.State.INVALID;
                            m.reason = el instanceof Node ? "a node of the match was deleted" : "an edge of the match was deleted";
                            m.selected = false;
                        } else {
                            m.state = Match.State.STALE;
                        }
                        m.check = MatchCheck.NOT_CHECKED;
                        any = true;
                    }
                }
                if (any || structural) fireLater();
            };
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeLater(r);
        }

        private boolean touches(Match m, GraphElement el) {
            if (el instanceof Node) return m.nodes().contains(el);
            if (el instanceof Edge) {
                Edge e = (Edge) el;
                return m.edges().contains(e) || (m.nodes().contains(e.getSource()) && m.nodes().contains(e.getTarget()));
            }
            return false;
        }

        @Override public void postEdgeAdded(GraphEvent e) { if (e.getEdge() != null) changed(e.getEdge(), false, true); }
        @Override public void postEdgeRemoved(GraphEvent e) { }
        @Override public void postGraphCleared(GraphEvent e) { changed(null, false, true); }
        @Override public void postNodeAdded(GraphEvent e) { changed(null, false, true); }
        @Override public void postNodeRemoved(GraphEvent e) { }
        @Override public void preEdgeAdded(GraphEvent e) { }
        @Override public void preEdgeRemoved(GraphEvent e) { if (e.getEdge() != null) changed(e.getEdge(), true, true); }
        @Override public void preGraphCleared(GraphEvent e) { }
        @Override public void preNodeAdded(GraphEvent e) { }
        @Override public void preNodeRemoved(GraphEvent e) { if (e.getNode() != null) changed(e.getNode(), true, true); }

        private void attribute(AttributeEvent e) {
            Attribute a = e.getAttribute();
            String path = a == null ? e.getPath() : a.getPath();
            if (path == null) return;
            String p = path.startsWith(".") ? path.substring(1) : path;
            if (p.isEmpty() || p.startsWith("graphics")) return;   // positions, sizes, colours, bends: never searched
            // the add-on's own stable ids and saved store are bookkeeping, not part of the map
            if (p.equals(GraphConstraints.PATH) || p.startsWith(GraphConstraints.PATH + ".")) return;
            Attributable owner = a == null ? e.getAttributeable() : a.getAttributable();
            if (owner instanceof GraphElement) changed((GraphElement) owner, false, false);
        }

        @Override public void postAttributeAdded(AttributeEvent e) { attribute(e); }
        @Override public void postAttributeChanged(AttributeEvent e) { attribute(e); }
        @Override public void postAttributeRemoved(AttributeEvent e) { attribute(e); }
        @Override public void preAttributeAdded(AttributeEvent e) { }
        @Override public void preAttributeChanged(AttributeEvent e) { }
        @Override public void preAttributeRemoved(AttributeEvent e) { }
        @Override public void transactionFinished(TransactionEvent e, BackgroundTaskStatusProviderSupportingExternalCall status) { }
        @Override public void transactionStarted(TransactionEvent e) { }
    }
}
