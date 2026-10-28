# The programming interface for layout algorithms that add constraints

A layout algorithm written for the add-on does not set positions. It is a list of steps, and each step computes a
structure, adds the constraints for it to the store and calls the solver. The constraints then pass through the same
loop as the user's constraints: they are listed, drawn, decided, checked, switched off, deleted, re-prioritised, and the
user drags nodes with them. The algorithm needs no code for any of that.

The interface lives in the package `layout.steps` (no dependency on the GUI):

| Class | Role |
|---|---|
| `layout.steps.LayoutStep` | one step: `name` (a leading "1. " is its number on the page), `description` (the tooltip), `action` (a `Runnable`) |
| `layout.steps.StepLayout` | the abstract base of an algorithm: a graph, its steps, `runAll()`, `runStep(i)`, `resetToOriginal()`, and the loop around a run (below) |
| `layout.steps.StepAlgorithm` | how the algorithm appears on the Algorithms page: `id()`, `title()`, `subtitle()`, `description()`, its `options()` (drop-down lists) and `create(graph, options)`, which makes a `StepLayout` |
| `layout.steps.StepLayoutRegistry` | `register(StepAlgorithm)`: the page shows one card per registered algorithm, in registration order; an algorithm registered after the page was built gets its card then |

The add-on's own SBGN layouts are the first implementation: `layout.sbgn.SbgnStepAlgorithm` (id `sbgn`, one option,
the language) registers `layout.sbgn.ConstraintSBGNLayout`, which extends `StepLayout` and builds the PD, ER or AF
steps in its constructor.

## What the base class does around a step

`StepLayout.runAll()` and `runStep(i)`:

1. Before the first step the positions of all nodes are remembered (`resetToOriginal()` restores them; the hook
   `onSnapshot()` lets a subclass remember more, the SBGN layout keeps the edge docking).
2. Before every run the user's constraints are decided (`resolveUserConstraints()`: contradictions among the user's
   constraints that the step solves apply are settled by priority with `constraints.ConstraintConflicts`, exactly as the
   Constraints page does; the automatic constraints of earlier steps are left to the algorithm's own guard). Which kinds
   the step solves apply is `appliesUserConstraint(c)` (all by default); the nodes they move, `solvedNodes()`.
3. The constraints in the store are captured before the step and compared after it: everything the step added is marked
   *automatic* (`Constraint.isAutomatic()`), so it ranks below the user's constraints of equal priority and the guards
   may drop it. A constraint of the user that a step puts back into the store (its node was removed for some steps) is
   excluded from this through `keepUserConstraint(c)`. During a step, `isNewInStep(c)` tells a constraint the step has
   made so far from one that was there before (the user's among them).
4. After a step (`afterStep`) and after the whole run (`afterRun`) the user's constraints of the graph are measured in
   the drawing (`constraints.ConstraintCheck`); one that does not hold is listed in the `ConflictLog` as "not satisfied
   after the step", or as "not applied by the layout" if the step solves do not apply its kind.

`resetToOriginal()`: `beforeReset()` (the subclass undoes what its steps changed in the structure of the graph), then
the remembered positions, no edge bends, only the user's constraints of the graph in the store, the conflict log
without the reports of the run, then `afterReset()`.

The Algorithms page (`gui.StepLayoutPanel`, one per registered algorithm) offers "Run all steps", "New run", "Reset",
the list of steps (click to run one), "Show constraints", and shows what a step added with a status line. The Layout
menu entry "Constraint SBGN Layout" runs the SBGN layout the same way.

## Grouping the constraints of a structure

A step that builds one structure out of several constraints (the star of a process, the sides of a process, a ring of
the skeleton) can put them into a `constraints.ConstraintGroup`. The constraint list then shows the group as one entry:
selecting it draws all of its constraints, its switch and its priority act on all of them, Delete removes all of them.
Grouping is metadata only; the solves see the members as before. The SBGN layout groups PD stars (step 4), sides
(step 3), stubs (step 5) and the rings, lenses and fans of the skeleton (step 2); AF groups its longest-path column, its
levels and its operator trees.

```java
ConstraintGroup star = new ConstraintGroup("star", "Star of " + name, "Stars");
star.addAll(constraintsOfTheStar);   // before or after they join the store
```

## Templates and rule sets from a step

A step can search and apply the templates and rule sets of the library, so that an algorithm constrains the backbone of
a diagram and templates add the recurring patterns along it (the scenario of the introduction). `layout.steps.StepTemplates`
wraps the path the Templates page takes, with the same parts:

| Call | What it does |
|---|---|
| `StepTemplates.loadRules(file)`, `loadTemplate(file)` | a rule set (`.cblrules.json`, every template embedded) or a template file |
| `StepTemplates.tasks(rules)`, `task(template)` | the search tasks: one per rule that is switched on, with the rule's priority |
| `StepTemplates.validate(task)` | the validator's problems (an ERROR or NOT_APPLICABLE blocks applying) |
| `StepTemplates.search(graph, scope, tasks, semantics, exact)` | one synchronous search among `scope` (null: the whole graph), with the limits of `TemplateLimits` (1 000 matches per run, 200 000 partial assignments); `run.limitMessage()` says when a limit stopped it |
| `StepTemplates.preview(graph, matches)` | the conflict preview of applying the matches together, decided on copies: which existing constraints they would push out, which of theirs would not be applied |
| `StepTemplates.apply(layout, run, matches, place, rank, solve)` | applies the matches in batches of 100 (`TemplateLimits.maxApplyPerStep`), one solve of the loop per batch (`solve`; null: the user solve of the add-on on the whole graph), which decides the conflicts by rank; matches already applied or whose nodes are gone are skipped with a reason; the instances join the registry, so the Templates page lists them, and a reset of the layout takes them out again |
| `StepTemplates.searchAndApply(layout, scope, tasks, semantics, exact, place, rank, solve)` | both in one call |

The constraints an instance makes carry their `ConstraintOrigin` (the list shows the instance and the path in the
template). How they rank is the `rank` argument: `Rank.AUTOMATIC` (the step's capture marks them automatic
like every constraint a step adds, so they rank below the user's constraints of equal priority and a guard may drop
them) or `Rank.TEMPLATE` (they keep the rank of a template the user applied, above automatic constraints; the base class
excludes them from the capture through `keepUserConstraint`). `AUTOMATIC` is the intended use: a constraint an algorithm
makes through a template is the algorithm's, listed with its instance and the path in the
template, and the user's constraints rank above it; `TEMPLATE` stays available for a step that applies a library the
user chose. The SBGN layouts use neither (they make their constraints directly).

## Example: the backbone as a column, a rule set along it

Two steps, compiled against the add-on (an add-on of its own would register it as shown at the end). Step 1 finds a
diameter path of the graph and makes it a column; step 2 searches every rule of a rule set among the backbone and its
neighbours and applies the matches.

```java
package my.addon;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ConstraintGroup;
import constraints.ConstraintStore;
import constraints.LinePattern;
import layout.steps.StepLayout;
import layout.steps.StepTemplates;
import layout.template.model.RuleSystem;

import org.AttributeHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two steps: the backbone of the diagram as a column, then a rule set of the library along it. The scenario of the
 * introduction of the paper: an algorithm constrains the backbone, templates add the recurring patterns along it, the
 * user finishes by hand.
 */
public final class BackboneRulesLayout extends StepLayout {

    private final RuleSystem rules;
    private final List<Node> backbone = new ArrayList<>();

    public BackboneRulesLayout(Graph graph, RuleSystem rules) {
        super(BackboneRulesAlgorithm.ID, graph);
        this.rules = rules;
        addStep("1. Backbone", "The longest shortest path of the graph becomes a column, top to bottom.", this::backbone);
        addStep("2. Rules along the backbone", "Every rule of the rule set is searched among the backbone and its neighbours "
                + "and applied; one solve per batch of 100 matches.", this::rulesAlongBackbone);
    }

    @Override
    public String getName() {
        return "Backbone and rules";
    }

    /** Step 1: a diameter path by two breadth-first searches; the path as a column with gaps of 80 px. */
    private void backbone() {
        backbone.clear();
        if (graph.getNumberOfNodes() == 0) return;
        Node start = farthest(graph.getNodes().iterator().next(), null);
        Map<Node, Node> parent = new HashMap<>();
        Node end = farthest(start, parent);
        for (Node n = end; n != null; n = parent.get(n)) backbone.add(0, n);
        if (backbone.size() < 2) return;
        LinePattern column = new LinePattern(new ArrayList<>(backbone), false, 80, false);
        new ConstraintGroup("column", "Backbone " + AttributeHelper.getLabel(start, "?") + " → " + AttributeHelper.getLabel(end, "?"), "Backbone").add(column);
        ConstraintStore.constraints.addConstraint(column);
        CBLayoutAlgorithm.executeUserConstraints(graph, true, true);   // force-directed run, overlap removal
    }

    /** Step 2: the rule set on the backbone and its neighbours, as the Templates page would apply it. */
    private void rulesAlongBackbone() {
        Set<Node> scope = new HashSet<>(backbone);
        for (Node n : backbone) scope.addAll(n.getNeighbors());
        StepTemplates.Result r = StepTemplates.searchAndApply(this, scope, StepTemplates.tasks(rules), true, false, false,
                StepTemplates.Rank.AUTOMATIC, null);
        System.out.println("[my.addon] rules along the backbone: " + r);
    }

    private Node farthest(Node from, Map<Node, Node> parent) {
        Map<Node, Integer> dist = new HashMap<>();
        Deque<Node> queue = new ArrayDeque<>(Collections.singletonList(from));
        dist.put(from, 0);
        Node far = from;
        while (!queue.isEmpty()) {
            Node u = queue.poll();
            for (Node v : u.getNeighbors()) {
                if (dist.containsKey(v)) continue;
                dist.put(v, dist.get(u) + 1);
                if (parent != null) parent.put(v, u);
                if (dist.get(v) > dist.get(far)) far = v;
                queue.add(v);
            }
        }
        return far;
    }
}
```

```java
package my.addon;

import layout.steps.StepAlgorithm;
import layout.steps.StepLayout;
import layout.steps.StepTemplates;
import layout.template.model.RuleSystem;

import org.graffiti.graph.Graph;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** The card on the Algorithms page; the rule set file is fixed here (a real add-on would offer a chooser or an option). */
public final class BackboneRulesAlgorithm implements StepAlgorithm {
    public static final String ID = "my-addon.backbone-rules";
    private final File rulesFile;

    public BackboneRulesAlgorithm(File rulesFile) {
        this.rulesFile = rulesFile;
    }

    @Override public String id() { return ID; }
    @Override public String title() { return "Backbone + rules"; }
    @Override public String subtitle() { return "A column for the backbone, a rule set along it"; }
    @Override public String description() { return "Step 1 constrains the longest path as a column; step 2 applies the rule set " + rulesFile.getName() + " along it."; }

    @Override
    public StepLayout create(Graph graph, Map<String, String> options) {
        try {
            RuleSystem rules = StepTemplates.loadRules(rulesFile);
            return new BackboneRulesLayout(graph, rules);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + rulesFile + ": " + e.getMessage(), e);
        }
    }
}
```

In the add-on's `initializeAddon()` (the `AddonAdapter`):
`StepLayoutRegistry.register(new BackboneRulesAlgorithm(new File("Transcription network.cblrules.json")));`. The
"Backbone + rules" card then appears on the Algorithms page of the Constraint-Based Layout tab with its two steps. What
the steps add is automatic: the user sees the backbone column as one entry ("Backbone a → b") and the instances of the
rules on the Templates page, can switch them off, change their priority, or drag a node and the constraints hold.

Rules for the action of a step (the add-on's invariants): run on the event thread only; add constraints to
`ConstraintStore.constraints`; never remove a constraint the user made (switch it off or let the loop decide it);
solve with `CBLayoutAlgorithm.executeUserConstraints` or, for a subset of nodes, `CBLayoutAlgorithm.solveNodes`.
