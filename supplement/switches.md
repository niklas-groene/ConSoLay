# Switches (`-D<name>=<value>` on the Java command line)

Every tuning decision of the layouts is a switch: the default is the current behaviour, the other value the previous one. A switch without a value (marked "flag") is off unless set to `true`. The descriptions are the comments in the source.

## SBGN PD (47)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.alignSides` | `off` | ConstraintSBGNLayout | -Dcblayout.alignSides=off\|drop\|rotate (default off): two skeleton neighbours that step 2 would align to the same side of one node: off aligns both, drop keeps only the nearer one, rotate aligns the other one on the perpendicular axis if that side is free. |
| `cblayout.axisLeaf` | `smart` | ConstraintSBGNLayout | -Dcblayout.axisLeaf=off\|labelpair\|noclone\|currency\|smart (default smart): which leaf takes a free axis slot (see closestTo). |
| `cblayout.bundles` | `lens` | ConstraintSBGNLayout | -Dcblayout.bundles=lens\|off (default lens): parallel processes between the same two species, off the selected rings, become a lens or fan. |
| `cblayout.compartmentPadding` | `30.0` | ConstraintSBGNLayout | -Dcblayout.compartmentPadding=px (30): padding of a restored compartment frame around its members. |
| `cblayout.compartments` | `drop` | ConstraintSBGNLayout | -Dcblayout.compartments=drop\|keep (default drop): drop removes the compartment frames and does not draw them again; keep lays the map out per compartment and re-inserts the frames around their members. |
| `cblayout.complexRigid` | `false` | ConstraintSBGNLayout | -Dcblayout.complexRigid=true\|false (default false): a complex and the glyphs inside it move as one rigid group in every solver call (see registerRigidComplexes). |
| `cblayout.cornerRule` | `species` | ConstraintSBGNLayout | -Dcblayout.cornerRule=species\|none (default species, skeletonShape=fd): step 2 moves bends from processes onto species (see applyCornerRule). |
| `cblayout.cycleGap` | `30` | ConstraintSBGNLayout | -Dcblayout.cycleGap=px (30): gap between two consecutive nodes of an embedded cycle, on top of their half extents. |
| `cblayout.debugStars` | `(flag)` | ConstraintSBGNLayout | -Dcblayout.debugStars=true\|false (default false): prints axis, occupied sides and port groups of every star |
| `cblayout.dropAlignment` | `keepUser` | ConstraintSBGNLayout | -Dcblayout.dropAlignment=keepUser\|remove (default keepUser): what {@link #dropAlignment} does with a two-node alignment the user made. {@code keepUser}: only alignments the steps made are removed; a user alignment stays, and the feasibility guard decides the contradiction with the step's new separation by rank (a user alignment of lower priority is deactivated with its reason, else the step's separation is dropped). {@code remove}: the user's alignment is removed as well. Without user constraints both values give the same output. |
| `cblayout.fanSpacing` | `150` | ConstraintSBGNLayout | -Dcblayout.fanSpacing=px (default 150): distance between neighbouring processes of a bundle fan (3+ processes). |
| `cblayout.gateInputs` | `far` | ConstraintSBGNLayout | -Dcblayout.gateInputs=far\|off (default far): a leaf input of a logic gate that modulates a process lies beyond the gate (see placeModulator). |
| `cblayout.graphSeed` | `skeleton` | ConstraintSBGNLayout | -Dcblayout.graphSeed=skeleton\|input (default skeleton): the graph FD pass starts from the skeleton FD positions (see seedGraphFromSkeleton) or from the input positions. |
| `cblayout.hubFan` | `false` | ConstraintSBGNLayout | -Dcblayout.hubFan=true\|false (default false): a process on an already taken side of a hub sits beside the process that took it (see fanBesideHub). |
| `cblayout.hubSpread` | `true` | ConstraintSBGNLayout | -Dcblayout.hubSpread=true\|false (default true): processes that share a species spread around it instead of lining up behind each other on one row through the species. |
| `cblayout.hubSpreadRev` | `false` | ConstraintSBGNLayout | -Dcblayout.hubSpreadRev=true\|false (default false): hubSpread also for reversible processes. |
| `cblayout.keepPorts` | `true` | PortHelper | -Dcblayout.keepPorts=true\|false (default true): true keeps the arcs that share a port in the input on one port in the output ({@link #planPorts}: one group per input port, the groups on two opposite sides); false derives every port from the side its arc docks on, which can move a cofactor into the other port group of a process. |
| `cblayout.lens` | `true` | ConstraintSBGNLayout | -Dcblayout.lens=true\|false (default true, skeletonShape=fd): a selected ring of two species and two processes (two reactions in parallel between the same species) becomes a lens instead of a 4-corner rectangle, where both processes would sit in corners with their ring species at 90 degrees. |
| `cblayout.lensOffset` | `40` | ConstraintSBGNLayout | -Dcblayout.lensOffset=px (default 40): distance of each lens process from the species axis. |
| `cblayout.lensRoles` | `conversion` | ConstraintSBGNLayout | -Dcblayout.lensRoles=conversion\|any (default conversion): conversion makes a two-species 4-ring a lens only if each of its two processes consumes one ring species and produces the other (reversible sides count), so no substrate arc enters beside a product; any makes every two-species 4-ring a lens. |
| `cblayout.lensStar` | `outer` | ConstraintSBGNLayout | -Dcblayout.lensStar=outer\|inner (default outer): outer puts enzyme and cofactor rows of a lens process on the side away from the partner process; inner only marks the inner side occupied. |
| `cblayout.logRemovals` | `true` | ConstraintSBGNLayout | -Dcblayout.logRemovals=true\|false (default true): alignments a step removed on purpose (hubSpread, splitRow, bundles, sideAlign, modDropAlign, hubFan) appear in the conflict log (phase "Removed"), so every constraint that goes leaves a reason. |
| `cblayout.maxCycleEdges` | `12` | CycleSelector | -Dcblayout.maxCycleEdges=n (default 12): longest cycle that gets a rectangle; longer rings are left to the force-directed layout. |
| `cblayout.mfAxis` | `pair` | ConstraintSBGNLayout | -Dcblayout.mfAxis=pair (default; any other value: the first structural substrate): a process with two or more structural substrates or products takes its axis from the most opposed substrate / product pair that already lies on opposite sides (pairs fixed by a ring and label-equal pairs first). |
| `cblayout.modDropAlign` | `false` | ConstraintSBGNLayout | -Dcblayout.modDropAlign=true\|false (default false): step 5 removes the step-2 alignment of a skeleton modulator that contradicts its stub side (as hubSpread does for species). |
| `cblayout.modRoles` | `catalysis` | ConstraintSBGNLayout | -Dcblayout.modRoles=all\|catalysis (default catalysis): all counts inhibition, modulation and unknown influence as modulation arcs too (no skeleton alignment, a stub side in step 5). |
| `cblayout.modSide` | `reserve` | ConstraintSBGNLayout | -Dcblayout.modSide=reserve\|off (default reserve): step 4 reserves a skeleton modulator's side before the cofactor side is chosen. |
| `cblayout.ringCorners` | `species` | ConstraintSBGNLayout | -Dcblayout.ringCorners=species\|entry (default species, skeletonShape=fd): species chooses rotation and run lengths of a ring rectangle for the fewest process corners (a process in a corner gets in- and outflow at 90 degrees), then the runs closest to the default split, then the smallest shift from the entry node; entry keeps the default split from the entry node. |
| `cblayout.rowCap` | `0` | ConstraintSBGNLayout | -Dcblayout.rowCap=N (default 0 = unlimited): a cofactor row holds at most N leaves; the rest go to the other perpendicular side (rowSides=both, if free) or to further rings along the axis (see pinRows). |
| `cblayout.rowFan` | `0.6` | ConstraintSBGNLayout | -Dcblayout.rowFan=f (default 0.6): the factor of rowShape=fan; the arc to the i-th row node clears the (i-1)-th by about f x pitch. |
| `cblayout.rowPins` | `fixed` | ConstraintSBGNLayout | -Dcblayout.rowPins=fixed\|soft\|distribute (default fixed): soft pins a row with minimum separations and one alignment along the axis instead of equalities; distribute adds equal spacing (see pinRowSoft). |
| `cblayout.rowPull` | `40` | ConstraintSBGNLayout | -Dcblayout.rowPull=px (default 40, 0 = off): further nodes of a diagonal cofactor row move towards the perpendicular (see pinRow). |
| `cblayout.rowRing` | `diagonal` | ConstraintSBGNLayout | -Dcblayout.rowRing=diagonal\|axis (default diagonal): with rowCap, ring r starts r ring steps further out along the axis and, with diagonal, also r ring steps further along the row side, so the rings form a 45-degree band whose arcs pass between the nearer rings; axis stacks the rings side by side. |
| `cblayout.rowShape` | `column` | ConstraintSBGNLayout | -Dcblayout.rowShape=fan\|column (default column): fan moves a row out along the axis in proportion to its length (rowFan x length), so the arcs fan out instead of running along the column (see pinRow). |
| `cblayout.rowSides` | `one` | ConstraintSBGNLayout | -Dcblayout.rowSides=both\|one (default one): with rowCap, the second chunk of a row goes to the opposite perpendicular side when no enzyme, skeleton neighbour or lens partner is there. |
| `cblayout.rowStub` | `axis` | ConstraintSBGNLayout | -Dcblayout.rowStub=side\|axis (default axis): the router stub of an arc to a row leaf points to the row's side instead of along the axis. |
| `cblayout.sideAlign` | `keep` | ConstraintSBGNLayout | -Dcblayout.sideAlign=keep\|drop\|swap (default keep): drop and swap make step 3 remove a step-2 alignment of a structural member across the process axis before adding the side separation (swap: and align the member on the axis). |
| `cblayout.sideGap` | `0` | ConstraintSBGNLayout | -Dcblayout.sideGap=px (default 0): gap added to the touching step-3 side separations. |
| `cblayout.skelSep` | `moved` | ConstraintSBGNLayout | -Dcblayout.skelSep=all: every remaining flow edge of the skeleton keeps the order the FD gave it (implies moved). |
| `cblayout.skeletonShape` | `fd` | ConstraintSBGNLayout | -Dcblayout.skeletonShape=fd\|structure\|structure-map\|topology (default fd): how step 2 directs a skeleton edge. fd reads the angle off a force-directed pass; structure builds the directions from chains of the skeleton topology ({@link SkeletonShape}); structure-map keeps the direction each chain has in the input map; topology plans them globally with regulation, feedback arcs and the later process axes ({@link TopologySkeleton}). |
| `cblayout.splitRow` | `true` | ConstraintSBGNLayout | -Dcblayout.splitRow=true\|false (default true): only the first structural member of a port group keeps its step-2 alignment on the process axis. |
| `cblayout.starGap` | `30` | ConstraintSBGNLayout | -Dcblayout.starGap=px (30): gap between the nodes of a star; equal to stubGap, so the star radius never undercuts a stub separation. |
| `cblayout.starRadius` | `100` | ConstraintSBGNLayout | -Dcblayout.starRadius=px (100): centre distance of the process star (larger only where the nodes need it). |
| `cblayout.starRowSpacing` | `80` | ConstraintSBGNLayout | -Dcblayout.starRowSpacing=px (80): pitch between the leaves of a co-factor row of a star. |
| `cblayout.stars` | `true` | ConstraintSBGNLayout | -Dcblayout.stars=true\|false (default true): false skips the stars, leaving the minimum distances of step 3 |
| `cblayout.stubGap` | `30` | ConstraintSBGNLayout | -Dcblayout.stubGap=px (30): extra gap between a process and its modulator stub node, on top of the half extents. |
| `cblayout.tagClip` | `true` | ConstraintSBGNLayout | -Dcblayout.tagClip=true\|false (default true): route bends inside a restored tag are dropped (every skeleton variant). |

## SBGN ER (39)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.erAlignOrder` | `on` | ERHyperedgeLayout | -Dcblayout.erAlignOrder=on\|off (default on): aligned pairs on a row or column also keep their order, or not. |
| `cblayout.erAlignments` | `class` | ERHyperedgeLayout | -Dcblayout.erAlignments=class\|pair (default class): one alignment per row or column, or one per aligned pair. |
| `cblayout.erAxis` | `joint` | ERLayout | -Dcblayout.erAxis=joint\|local (default joint): the axes of an arc's hyperedge nodes are chosen together (see planArc), or each from the force-directed chord between its two neighbours. |
| `cblayout.erCrossingPenalty` | `0` | ERPins | -Dcblayout.erCrossingPenalty=p (default 0 = off): libavoid's crossing penalty for ER routing. |
| `cblayout.erDebug` | `(flag)` | ERLayout | -Dcblayout.erDebug=true\|false (default false): prints the statistics of each step, each constraint group that gives way, and each U-loop. |
| `cblayout.erFit` | `edge` | ERLayout | -Dcblayout.erFit=edge\|guard (default edge): each constraint group is tested in tier order and left out if it does not fit the ones accepted before it, or all groups go to the feasibility guard at once. |
| `cblayout.erGap` | `20` | ERLayout | -Dcblayout.erGap=px (default 20): free space between two consecutive nodes of an arc, edge to edge. |
| `cblayout.erIsolated` | `START_FROM_GRAPH ? "start" : "neighbours"` | ERHyperedgeLayout | -Dcblayout.erIsolated=neighbours\|start (default start, neighbours with erStart=input): a node that has only hyperedges starts the run of step 4 between its neighbours, or where it is. |
| `cblayout.erMode` | `hyperedge` | ERHyperedgeLayout | -Dcblayout.erMode=hyperedge\|dfs\|contract\|node (default hyperedge): hyperedge and dfs select this pipeline. |
| `cblayout.erNodeAlign` | `10` | ERHyperedgeLayout | -Dcblayout.erNodeAlign=degrees (default 10, 0: never): step 3 aligns a hyperedge this close to an axis. |
| `cblayout.erNudge` | `6` | ERHyperedgeLayout | -Dcblayout.erNudge=px (default 6): distance between two parallel routes. |
| `cblayout.erOctilinearEdges` | `straight` | ERHyperedgeLayout | -Dcblayout.erOctilinearEdges=straight\|route (default straight): diagonal edges stay straight, or are routed. |
| `cblayout.erOperatorSides` | `plan` | ERHyperedgeLayout | -Dcblayout.erOperatorSides=plan\|route (default plan): step 2 or only step 5 sets an operator's output side. |
| `cblayout.erOperators` | `sides` | ERLayout | -Dcblayout.erOperators=sides\|free (default sides): a logical operator gets input and output sides, or none. |
| `cblayout.erOverlap` | `run` | ERHyperedgeLayout | -Dcblayout.erOverlap=run\|feasible (default run): runs keep nodes apart, or overlaps are removed afterwards. |
| `cblayout.erPack` | `graph` | ERHyperedgeLayout | -Dcblayout.erPack=graph\|on\|off (default graph): packs the parts of the whole map, of the graph now, or none. |
| `cblayout.erPieceAim` | `stub` | ERHyperedgeLayout | -Dcblayout.erPieceAim=stub\|node (default stub): a routed piece of an arc leaves its hyperedge node towards the end of the stub at its other end, or towards the node there. |
| `cblayout.erPinCorridor` | `10` | ERPins | -Dcblayout.erPinCorridor=px (default 10): free space a grown obstacle leaves to another glyph or a stub end. |
| `cblayout.erPinCost` | `0.5` | ERPins | -Dcblayout.erPinCost=c (default 0.5): routing cost per px a spread pin lies away from the middle of its side. |
| `cblayout.erPinInset` | `10` | ERPins | -Dcblayout.erPinInset=px (default 10): no spread pin closer than this to a corner (rounded entity corners). |
| `cblayout.erPinRoles` | `ENTITY` | ERPins | -Dcblayout.erPinRoles=ENTITY,... (default ENTITY): glyph roles that get pins. |
| `cblayout.erPinSpacing` | `20` | ERPins | -Dcblayout.erPinSpacing=px (default 20): minimum distance of two spread pins on one side (arrowhead: 15 px). |
| `cblayout.erPinSpots` | `clear` | ERHyperedgeLayout | -Dcblayout.erPinSpots=clear\|any (default clear): an edge that meets its glyph straight takes a free pin instead of a corner, the spot of another edge or the end of a straight diagonal, or it ends where it meets the glyph. |
| `cblayout.erPinStub` | `16` | ERPins | -Dcblayout.erPinStub=px (default 16, 0 = off): the obstacle of a pin glyph grows by up to this much on each side (never more than the free space towards the nearest other glyph minus {@link #CORRIDOR}) and the pins sit on the grown border, so every route leaves the glyph with a straight piece at least that long (room for the arrowhead). |
| `cblayout.erPins` | `on` | ERPins | -Dcblayout.erPins=on\|off (default on): off lets every ER edge end at the glyph centre. |
| `cblayout.erPinsDebug` | `(flag)` | ERPins | -Dcblayout.erPinsDebug=true (default false): prints the pins created on every glyph. |
| `cblayout.erPipeline` | `er` | ERLayout | -Dcblayout.erPipeline=er\|pd (default er): ER maps run through the ER steps, or through the PD steps. |
| `cblayout.erPointLength` | `0.5` | ERLayout | -Dcblayout.erPointLength=f (default 0.5): ideal length factor of a segment between two points of one arc. |
| `cblayout.erProxyLength` | `1.5` | ERLayout | -Dcblayout.erProxyLength=f (default 1.5, 0: none): ideal length factor of the edges that tie a glyph in the contracted graph to the end glyphs of the arcs it influences. |
| `cblayout.erRouting` | `orthogonal` | ERLayout | -Dcblayout.erRouting=orthogonal\|polyline (default orthogonal): every edge is routed axis-parallel along the planned sides of its hyperedge nodes, or as in PD. |
| `cblayout.erRunPatience` | `20` | ERHyperedgeLayout | -Dcblayout.erRunPatience=n (default 20, -1: libcola's convergence test): a run ends after n iterations in a row that do not lower the stress, on the best iterate it has seen. |
| `cblayout.erSectors` | `one` | ERHyperedgeLayout | -Dcblayout.erSectors=one\|nearest (default one): one aligned neighbour per direction at a node, or no limit. |
| `cblayout.erShapeBuffer` | `6` | ERHyperedgeLayout | -Dcblayout.erShapeBuffer=px (default 6): distance a route keeps from the nodes it does not end at. |
| `cblayout.erSlots` | `loops` | ERLayout | -Dcblayout.erSlots=loops\|all (default loops): only the two legs of a U, or every arc that meets a glyph side straight, may take a slot beside the centre of that side. |
| `cblayout.erStart` | `graph` | ERHyperedgeLayout | -Dcblayout.erStart=graph\|input (default graph): step 1 starts from a run on the whole map, or from the input. |
| `cblayout.erStartHyperLength` | `2` | ERHyperedgeLayout | -Dcblayout.erStartHyperLength=f (default 2): ideal length factor of a hyperedge in the start run of step 1. |
| `cblayout.erStep3Run` | `constraints` | ERHyperedgeLayout | -Dcblayout.erStep3Run=always\|constraints (default constraints): step 3 always ends with a run, or only if it added a constraint or an inserted node lies on another node. |
| `cblayout.erStub` | `20` | ERHyperedgeLayout | -Dcblayout.erStub=px (default 20, 0: none): length of the stub at which a hyperedge leaves a hyperedge node. |
| `cblayout.erStubSnap` | `keep` | ERHyperedgeLayout | -Dcblayout.erStubSnap=keep\|snap (default keep): a stub end nudged by the router stays there, or snaps back. |

## SBGN AF (29)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.afCrossingMaxArcs` | `80` | AFLayout | -Dcblayout.afCrossingMaxArcs=n (default 80): maps with more arcs are routed without {@link #CROSSING_PENALTY}. |
| `cblayout.afCrossingPenalty` | `50` | AFLayout | -Dcblayout.afCrossingPenalty=p (default 50; 0 = off): the router's penalty for a crossing of two arcs. |
| `cblayout.afFasTie` | `negative` | AFLayout | -Dcblayout.afFasTie=negative\|none (default negative): among equally small feedback arc sets the one with the most negative influences wins, or the first one found. |
| `cblayout.afFinal` | `fd` | AFLayout | -Dcblayout.afFinal=fd\|project (default fd): the final solve is a force-directed run, or only projects onto the constraints and non-overlap. |
| `cblayout.afIdealLength` | `150` | AFLayout | -Dcblayout.afIdealLength=px (default 150): ideal arc length in the solves of steps 3 to 6; a tree laid out on its own in step 1 uses CBLayoutAlgorithm.dist. |
| `cblayout.afLayerGap` | `100` | AFLayout | -Dcblayout.afLayerGap=px (default 100): free space between a unit and the level above or below it. |
| `cblayout.afLevels` | `iterative` | AFLayout | -Dcblayout.afLevels=iterative\|once (default iterative): one solve after every level, or one after all levels. |
| `cblayout.afNodePad` | `25` | AFLayout | -Dcblayout.afNodePad=px (default 25): margin around every node in the AF solves; two nodes keep twice this. |
| `cblayout.afNudge` | `5` | AFLayout | -Dcblayout.afNudge=px (default 5): distance parallel arc segments are nudged apart. |
| `cblayout.afPinSpacing` | `16` | AFLayout | -Dcblayout.afPinSpacing=px (default 16): distance of two arrowheads on one side of an activity. |
| `cblayout.afPinStub` | `16` | AFLayout | -Dcblayout.afPinStub=px (default 16): length of the straight piece in front of an arrowhead. |
| `cblayout.afPins` | `heads` | AFLayout | -Dcblayout.afPins=heads\|off (default heads): the arrowhead end of an arc gets a pin of its own on the activity's border ({@link ERPins#forActivityFlow}), the other end stays at the centre; off: both ends at the centre. |
| `cblayout.afPipeline` | `af` | AFLayout | -Dcblayout.afPipeline=af\|pd (default af): AF maps run through the steps of this class, or the PD steps. |
| `cblayout.afPortJoin` | `fan` | AFLayout | -Dcblayout.afPortJoin=fan\|merge (default fan): the arcs of an operator port stay {@link #NUDGING} apart and each reaches the port with a short 45-degree piece; merge: their last segments move onto the port's axis. |
| `cblayout.afPortLead` | `12` | AFLayout | -Dcblayout.afPortLead=px (default 12; 0 = off): an arc leaving an operator passes a point this far in front of the output port, which gives the router a line to turn on right after the port. |
| `cblayout.afRouting` | `orthogonal` | AFLayout | -Dcblayout.afRouting=orthogonal\|polyline (default orthogonal): how the arcs are routed. |
| `cblayout.afRows` | `constrain` | AFLayout | -Dcblayout.afRows=constrain\|seed (default constrain): a level's rows get order, gap and centring constraints, or are start positions only. |
| `cblayout.afRunPatience` | `20` | AFLayout | -Dcblayout.afRunPatience=n (default 20; -1: libcola's own convergence test): a force-directed run stops after n iterations in a row without lower stress and ends on the best iterate. |
| `cblayout.afShapeBuffer` | `8` | AFLayout | -Dcblayout.afShapeBuffer=px (default 8): distance an arc keeps from the nodes it passes. |
| `cblayout.afSiblingGap` | `70` | AFLayout | -Dcblayout.afSiblingGap=px (default 70): free space between two neighbours of a row. |
| `cblayout.afSpread` | `min` | AFLayout | -Dcblayout.afSpread=min\|fixed (default min): rows keep order and minimum gaps, or get exactly equal gaps. |
| `cblayout.afTreeCentre` | `free` | AFLayout | -Dcblayout.afTreeCentre=free\|row (default free): the inputs of an operator that also has an input from outside its tree form two halves beside the operator's axis, where that arc arrives; row: always one row centred on it. |
| `cblayout.afTreeGap` | `60` | AFLayout | -Dcblayout.afTreeGap=px (default 60): free space between an operator and its inputs inside a tree. |
| `cblayout.afTreePad` | `40` | AFLayout | -Dcblayout.afTreePad=px (default 40): margin around a laid-out tree in its placeholder. |
| `cblayout.afWiden` | `on` | AFLayout | -Dcblayout.afWiden=on\|off (default on): where parallel arc segments end up closer than {@link #NUDGING}, the nodes beyond that channel move away by the missing width and the arcs are routed again. |
| `cblayout.afWidenCollapsed` | `on` | AFLayout | -Dcblayout.afWidenCollapsed=on\|off (default on): arcs lying exactly on one line count as too narrow unless they share an operator port, and a bundle is widened once; off: arcs on one line never count. |
| `cblayout.topologyColumnOrder` | `crossings` | TopologySkeleton | -Dcblayout.topologyColumnOrder=crossings\|barycenter (default crossings): with columns placement, the column order is improved by estimated crossings, or kept in barycenter order. |
| `cblayout.topologyLongEdges` | `false` | TopologySkeleton | -Dcblayout.topologyLongEdges=true\|false (default false): the layer order splits long arcs at every rank they pass, or counts direct neighbours only. |
| `cblayout.topologyPlacement` | `layered` | TopologySkeleton | -Dcblayout.topologyPlacement=layered\|columns (default layered): every rank keeps its own order, or aligned lanes are packed into global columns. |

## Solver, guard, routing, user solve and dragging (17)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.bboxPad` | `15` | CBLayoutAlgorithm | -Dcblayout.bboxPad=px (default 15): padding per rectangle side handed to cola; the non-overlap gap is twice this. |
| `cblayout.clonePick` | `rarest` | ComponentTools | -Dcblayout.clonePick=rarest\|nearest (default rarest): the clone pair that breaks ties between positions is the one with the rarest label (currency metabolites such as H+ or ATP say little), or the one drawn closest. |
| `cblayout.debugSvg` | `(flag)` | CBLayoutAlgorithm | -Dcblayout.debugSvg=true\|false (default false): write intermediate layouts as SVG into the working directory. |
| `cblayout.dragPin` | `(flag)` | CBLayoutAlgorithm | -Dcblayout.dragPin=true\|false (default false): during a drag the dragged nodes get a pin at the cursor of the lowest rank, which holds wherever no higher-ranked constraint contradicts it; the pin is not in the store. |
| `cblayout.dragSolve` | `component` | CBLayoutAlgorithm | -Dcblayout.dragSolve=component\|all (default component): component: a drag step without force-directed run and overlap removal solves only the constraint components of the dragged nodes and of the nodes with a target (same result: nothing links the other nodes to them, and a full solve puts every targeted node back on its target); all: every drag step solves the whole graph. |
| `cblayout.dragTiming` | `(flag)` | CBLayoutAlgorithm | -Dcblayout.dragTiming=true\|false (default false): print the time of every phase of a user solve. |
| `cblayout.fdConverge` | `keep` | CBLayoutAlgorithm | -Dcblayout.fdConverge=keep\|reset (default keep): keep: the constrained run of executeConstraints shares the convergence test of the unconstrained run before it, which ends it after one iteration; reset: a fresh test. |
| `cblayout.fdDist` | `100` | CBLayoutAlgorithm | -Dcblayout.fdDist=px (default 100): ideal edge length of every cola run. |
| `cblayout.fdOverlap` | `feasible` | CBLayoutAlgorithm | -Dcblayout.fdOverlap=feasible\|run (default feasible): non-overlap in makeFeasible only, or also in the run. |
| `cblayout.fdTrace` | `(flag)` | CBLayoutAlgorithm | -Dcblayout.fdTrace=true\|false (default false): print the stress after every iteration of a guarded run. |
| `cblayout.feasPriority` | `age` | ConstraintFeasibility | -Dcblayout.feasPriority=class\|age (default age): class drops the automatic constraint of the lowest {@link Constraint#getRank rank} on a cycle, the youngest among equals, and also offers the alignments joining the two nodes of a self-loop; age drops the youngest automatic constraint on the cycle (never an alignment). |
| `cblayout.leafLength` | `1` | CBLayoutAlgorithm | -Dcblayout.leafLength=f (default 1): ideal length factor of an edge with a leaf end. |
| `cblayout.packComponents` | `place` | ComponentTools | -Dcblayout.packComponents=place\|off (default place): place moves the components apart; off leaves them. |
| `cblayout.portSpot` | `gap` | SbgnPortSync | -Dcblayout.portSpot=gap\|half (default gap): gap puts an inserted port {@link #PORT_GAP} outside the border whenever its segment reaches that far, and never closer than {@link #clearance}; half puts it on the first half of its segment, at least 1 px outside the border, which can lie inside the node's drawn outline. |
| `cblayout.routeDebug` | `(flag)` | SBGNEdgeRouter | -Dcblayout.routeDebug=true (default false): prints every route as libavoid returns it, before any correction. |
| `cblayout.routeJog` | `3` | SBGNEdgeRouter | -Dcblayout.routeJog=px (default 3, 0 = off): on orthogonal AF and ER routes a step shorter than this between two parallel segments is taken out ({@link #removeSmallJogs}); libavoid leaves such steps where it nudges a segment whose end is fixed (at a port, at the end of a stub). |
| `cblayout.sbgnUserTypes` | `all` | CBLayoutAlgorithm | -Dcblayout.sbgnUserTypes=all\|hvsd (default all): all: the solves of the SBGN steps apply every kind of user constraint, translated as in the user solve; hvsd: alignments, gaps and (PD) equal spacing only. |

## Mental map (10)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `mentalmap.anchorWeight` | `2e-2` | MentalMapLayout | -Dmentalmap.anchorWeight (default 2e-2, in libcola's stress units): weight of the survivors' anchors in stable mode; larger keeps survivors closer to where they were, smaller lets the force-directed pass rearrange them. |
| `mentalmap.driftCompensation` | `0.001` | MentalMapLayout | -Dmentalmap.driftCompensation (default 0.001; 0 = off): contraction of the anchor targets on every changed step against the outward drift of the force-directed pass, bounded by the {@link #reclaimDensity} floor. |
| `mentalmap.newcomerAnchor` | `0.3` | MentalMapLayout | -Dmentalmap.newcomerAnchor (default 0.3; 0 = inserted nodes are free): anchor weight of an inserted node at its seeded position, relative to {@link #anchorWeight}. |
| `mentalmap.orderGapFloor` | `5` | MentalMapLayout | -Dmentalmap.orderGapFloor (default 5 px; 0 = no floor): smallest gap {@link #orderGapFraction} may shrink an ordering pair to in stable mode; a pair whose reference gap is smaller keeps its reference gap. |
| `mentalmap.orderGapFraction` | `0.5` | MentalMapLayout | -Dmentalmap.orderGapFraction (default 0.5; 1 = the full gap): share of each pair's reference gap that an ordering constraint keeps in stable mode, see {@link #stableOrderGap}. |
| `mentalmap.reclaimDensity` | `3.5` | MentalMapLayout | -Dmentalmap.reclaimDensity (default 3.5; 0 = no floor): density below which the survivors' anchor targets are never contracted, the bounding-box area of the survivors' solver rectangles (size + 2 {@link CBLayoutAlgorithm#bboxConst}) over the summed solver-rectangle area of all live nodes. |
| `mentalmap.reclaimMin` | `0.9` | MentalMapLayout | -Dmentalmap.reclaimMin (default 0.9): smallest contraction of the survivors' anchor targets in one step when the graph shrinks, see {@link #collectAnchors(Graph, Map, Map, boolean)}. |
| `mentalmap.seedOverlapMax` | `15` | MentalMapLayout | -Dmentalmap.seedOverlapMax (default 15 px; 0 = never): largest overlap the seed search accepts for an inserted node next to an overlapping neighbourhood when no spot within {@link #seedRadius} is free. |
| `mentalmap.seedRadius` | `1.5` | MentalMapLayout | -Dmentalmap.seedRadius (default 1.5; 0 = unbounded free-spot search): reach of the seed search beyond the padded boundary of an inserted node's placed neighbours, in ideal edge lengths; it also bounds how far from its neighbours' cluster an inserted node may start and still join it. |
| `mentalmap.stableExtent` | `true` | MentalMapLayout | -Dmentalmap.stableExtent=true\|false (default true): true keeps the drawing's area per node over chained steps (reduced order gaps, anchors, seeded inserted nodes, merged clusters, an unchanged graph only projected, drift removal); false keeps the full reference gaps and runs the unanchored layout without these. |

## Templates (10)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.templates.chunkMillis` | `150` | TemplateLimits | -Dcblayout.templates.chunkMillis=n (default 150): results reach the GUI at least this often (ms)... |
| `cblayout.templates.chunkSize` | `50` | TemplateLimits | -Dcblayout.templates.chunkSize=n (default 50): ...or after this many new matches. |
| `cblayout.templates.maxApply` | `100` | TemplateLimits | -Dcblayout.templates.maxApply=n (default 100): instances made in one application step. |
| `cblayout.templates.maxAssignments` | `200000` | TemplateLimits | -Dcblayout.templates.maxAssignments=n (default 200000): complete assignments one run examines. |
| `cblayout.templates.maxMatches` | `1000` | TemplateLimits | -Dcblayout.templates.maxMatches=n (default 1000): distinct matches (node sets) one search run collects. |
| `cblayout.templates.maxParts` | `20000` | TemplateLimits | -Dcblayout.templates.maxParts=n (default 20000): occurrences of a component a composite search combines. |
| `cblayout.templates.maxShown` | `200` | TemplateLimits | -Dcblayout.templates.maxShown=n (default 200): matches drawn and listed at the same time. |
| `cblayout.templates.maxSteps` | `20000000` | TemplateLimits | -Dcblayout.templates.maxSteps=n (default 20000000): partial assignments (candidate tests) one run tries. |
| `cblayout.templates.maxVariants` | `12` | TemplateLimits | -Dcblayout.templates.maxVariants=n (default 12): assignments with different constraints kept per match. |
| `cblayout.templates.metrics` | `(unset)` | TemplateMetrics | -Dcblayout.templates.metrics=path.csv (default unset: nothing recorded): appends every record to that file. |

## Store (1)

| Switch | Default | Class | What it does |
|---|---|---|---|
| `cblayout.saveWithGraph` | `true` | GraphConstraints | -Dcblayout.saveWithGraph=true\|false (default true): true saves the store with the graph and reads it back, false writes nothing into a graph and reads nothing from it. |
