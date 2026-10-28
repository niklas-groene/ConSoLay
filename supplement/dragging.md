# Dragging: what a drag step costs

VANTED's move tool wraps every `mouseDragged` in a transaction; the add-on solves the constraints after each of them
(`AddonTab.afterMoveTransaction`), so the constraints hold in every state of the drawing the user sees. This note gives
the measured cost of one such solve, and how the add-on keeps it low.

## Measurement

`-Dcblayout.dragTiming=true` prints one line per user solve with the time of every phase
(`CBLayoutAlgorithm.PHASES`): `rects` (solver rectangles), `decide` (`ConstraintConflicts.resolve`), `translate`
(model → cola objects), `build` (the `ConstrainedFDLayout`), `run` (force-directed, if on), `clusters`, `project`
(`makeFeasible`, with overlap removal if on), `apply` (positions back), `check` (`ConstraintCheck`), `project2` (second
projection if a constraint did not hold), `report` (conflict log, overlay).

`tools/regression` holds `headless.DragBench`: it loads a scenario, applies its constraints, and drags the five most
constrained nodes 30 steps each (3 px right, 2 px down per step), timing every solve; it prints the median and the 95th
percentile per drag step, for the force-directed run and the overlap removal off and on. Headless, so the JIT is on and
the GUI refresh is not included. The numbers measured inside VANTED come from posting the same transactions a drag makes
on the event thread.

```bash
ant regression-compile
java -cp "target/classes:target/regression-classes:libs/sbgn-ed.jar:$VANTED/target/classes:$VANTED_LIBS/*" \
     headless.DragBench --scenario trn|pd|big [--around] [--least] [--only fd0o0,fd0o1,fd1o0,fd1o1]
```

Scenarios: (a) `trn` = `supplement/example_trn/ecoli_sugar_regulation.gml` with its rule set (40 nodes, 261 constraints);
(b) `pd` = `Files/Carbohydrate Metabolism.Glycolysis, Gluconeogenesis.sbgn` after the PD algorithm (170 nodes, 521
automatic constraints); (c) `big` = `Files/central_plant_metabolism.sbgn` (1324 nodes) with the reaction building blocks of
the library applied (1000 matches, the search limit; 1922 constraints).

## Results (headless, median / 95th percentile per drag step, ms)

| Scenario | FD off, overlaps off | FD off, overlaps on | FD on, overlaps off | FD on, overlaps on |
|---|---|---|---|---|
| (a) TRN 40/261 | 7.5 / 8.8 | 8.0 / 8.8 | 53.5 / 55.5 | 334 / 354 |
| (b) PD 170/521 | 7.2 / 8.8 | 36.4 / 38.8 | 34.0 / 34.7 | 63.0 / 66.2 |
| (c) central_plant 1324/1922 | 5.1 / 6.9 | (whole graph) | (whole graph) | (whole graph) |

Where the time goes: `project` (libcola's `makeFeasible`) is almost all of it with FD off (TRN 6.1 of 7.1 ms, PD 5.2 of
7.2 ms); building the cola objects (`translate` + `build`) is 0.3 ms (TRN) to 1.3 ms (PD); `decide` is 0.3–0.4 ms, but
about 25 ms on PD with overlap removal on (`fixedOverlap` rebuilds its union-finds per constraint); `run` is the
force-directed run (46 ms TRN, 26 ms PD), 320 ms on TRN with overlaps during the run. A solve of the whole graph (c)
takes 5.1 s, of which `project` is 5.0 s.

Inside VANTED (TRN, FD off) a drag transaction takes 10.7 / 12.7 ms with overlaps off and 11.3 / 25.7 ms with overlaps on,
of which the GUI refresh is about 0.3 ms.

## How a drag step stays cheap

1. **The lists are not rebuilt during a drag.** `ConflictLog.clearUnsatisfied()` tells its listeners only if an entry
   went, and `setDeactivated` only if the decisions changed, so the constraint list and the Conflicts section are
   rebuilt only when something changed; per step only the overlay is repainted.
2. **Only the affected part is solved** (`-Dcblayout.dragSolve=component`; `all` solves the whole graph): with the
   force-directed run and the overlap removal off, a drag step solves only the constraint components of the dragged
   nodes (`CBLayoutAlgorithm.executeUserConstraintsAround`), and those of the nodes with a target, which a full solve
   puts back on their targets. No constraint links the other nodes to the dragged ones, so a projection cannot move
   them; the result equals the full solve (tests `DragSolveTest`: positions within 1e-6 px, the same decisions, on the
   TRN example; `UserSolveTest`: a target outside the dragged component). A cluster in the component (its sides
   concern every other node), FD on or overlaps on fall back to the whole graph. On (a) and (b) the components of the
   dragged nodes are the whole graph (every TRN node is in a row of a local regulator or above one; the PD skeleton
   alignments chain the map); on (c) the thousand local instances form small components, so a step takes 5 ms instead
   of 5.1 s.
3. **Optional: the dragged node stays under the cursor** (`-Dcblayout.dragPin=true`, default off): the dragged nodes get
   a pin at the cursor of the lowest rank (very low priority, automatic, youngest) for the solve of the step. If a
   constraint ranked above contradicts it, the pin is not applied and the node moves as without it; the pin is not in
   the store and leaves no entry in the conflict log. On the PD map a leaf of a star (exact x and y gaps to its process)
   stays under the cursor in 150 of 150 steps with the force-directed run on (the lock holds it during the run and the
   star follows), but in 0 of 150 with the run off, where libcola's projection satisfies the star's exact gaps before
   the pin's fixed guide; on the TRN example every node lies in a row, so the pin never holds in y. The switch therefore
   helps with the force-directed run on. Its cost is not measurable.

Every drag step runs the whole loop (decide, solve, check, show), so the state after the mouse is released equals a full
solve from the same positions.

## Open

- With overlap removal on, `ConstraintConflicts.fixedOverlap` rebuilds two union-finds over all equalities for every
  constraint it adds: 25 ms of 36 on the PD map. An incremental union-find in the model would bring it to the cost of
  the overlap-off case.
- With the force-directed run on, the run itself is the cost (26–46 ms per step, 320 ms with overlaps in the run on
  the TRN example); only fewer iterations per step would change that.
