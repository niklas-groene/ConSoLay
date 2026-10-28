/*************************************************************************************
 * The Exemplary Add-on is (c) 2008-2011 Plant Bioinformatics Group, IPK Gatersleben,
 * http://bioinformatics.ipk-gatersleben.de
 * The source code for this project, which is developed by our group, is
 * available under the GPL license v2.0 available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html. By using this
 * Add-on and VANTED you need to accept the terms and conditions of this
 * license, the below stated disclaimer of warranties and the licenses of
 * the used libraries. For further details see license.txt in the root
 * folder of this project.
 ************************************************************************************/
package adaptagrams_bridge;

import constraints.ConflictLog;
import constraints.ConstraintConflicts;
import constraints.ConstraintCheck;
import constraints.Deactivation;
import constraints.ClusterConstraintGraph;
import constraints.LockConstraintGraph;
import constraints.Priority;
import constraints.Constraint;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.DistributionConstraintGraph;
import constraints.HorizontalConstraint;
import constraints.SeparationConstraintGraph;
import constraints.VerticalConstraint;
import layout.sbgn.ComponentTools;
import layout.sbgn.ConstraintSBGNLayout;

import org.AttributeHelper;
import org.ErrorMsg;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.adaptagrams.CompoundConstraintPtrs;
import org.adaptagrams.RectanglePtrs;
import org.adaptagrams.ConstrainedFDLayout;
import org.adaptagrams.RootCluster;
import org.adaptagrams.Unsigneds;
import org.adaptagrams.Rectangle;
import org.adaptagrams.ColaEdges;
import org.adaptagrams.ColaEdge;
import org.adaptagrams.RectangularCluster;
import org.adaptagrams.AlignmentConstraint;
import org.adaptagrams.SeparationConstraint;
import org.adaptagrams.UnsatisfiableConstraintInfo;
import org.adaptagrams.UnsatisfiableConstraintInfoPtrs;
import org.adaptagrams.DistributionConstraint;
import org.adaptagrams.Doubles;
import org.adaptagrams.FixedRelativeConstraint;
import org.adaptagrams.TestConvergence;
import org.adaptagrams.ColaDesiredPositions;
import org.adaptagrams.DesiredPosition;
import org.adaptagrams.PreIteration;
import org.adaptagrams.SWIGTYPE_p_std__valarrayT_double_t;
import java.util.*;

/**
 * Bridge to the Adaptagrams library (libcola): turns the nodes, edges and constraints of a VANTED graph into cola
 * rectangles and constraint objects, runs the constraint-based layout and writes the positions back. Serves the SBGN
 * pipeline steps, the user-constraint solves of the Constraints tab and the mental-map layout.
 *
 * @author Niklas Gröne
 */
public class CBLayoutAlgorithm {
    static RectanglePtrs rectPtrVector;
    static ConstrainedFDLayout constraintFDLayout;
    static CompoundConstraintPtrs constraints;
    public static Map<Node,ArrayList<Node>> clusterMap;
    static RootCluster rootCluster;
    /** -Dcblayout.fdDist=px (default 100): ideal edge length of every cola run. */
    public static int dist = ConstraintSBGNLayout.intProperty("cblayout.fdDist", 100);
    /** -Dcblayout.bboxPad=px (default 15): padding per rectangle side handed to cola; the non-overlap gap is twice this. */
    public static double bboxConst = ConstraintSBGNLayout.doubleProperty("cblayout.bboxPad", 15);

    /** -Dcblayout.leafLength=f (default 1): ideal length factor of an edge with a leaf end. */
    public static double LEAF_LENGTH = ConstraintSBGNLayout.doubleProperty("cblayout.leafLength", 1);
    /**
     * -Dcblayout.fdConverge=keep|reset (default keep): keep: the constrained run of executeConstraints shares the
     * convergence test of the unconstrained run before it, which ends it after one iteration; reset: a fresh test.
     */
    public static boolean FD_CONVERGE_RESET = "reset".equalsIgnoreCase(System.getProperty("cblayout.fdConverge", "keep"));
    /** -Dcblayout.fdOverlap=feasible|run (default feasible): non-overlap in makeFeasible only, or also in the run. */
    public static boolean FD_OVERLAP_RUN = "run".equalsIgnoreCase(System.getProperty("cblayout.fdOverlap", "feasible"));
    /** -Dcblayout.fdTrace=true|false (default false): print the stress after every iteration of a guarded run. */
    public static boolean FD_TRACE = Boolean.getBoolean("cblayout.fdTrace");
    /**
     * -Dcblayout.sbgnUserTypes=all|hvsd (default all): all: the solves of the SBGN steps apply every kind of user
     * constraint, translated as in the user solve; hvsd: alignments, gaps and (PD) equal spacing only.
     */
    public static final boolean USER_TYPES_ALL = !"hvsd".equalsIgnoreCase(System.getProperty("cblayout.sbgnUserTypes", "all"));
    /**
     * sbgnUserTypes=all: the scope in which {@link layout.steps.StepLayout} decided the user's constraints before the
     * run; the SBGN solves keep the other nodes on the cluster sides chosen there.
     */
    public static ConstraintConflicts.Scope lastUserScope;
    /** The translator of the last SBGN solve and its native objects, kept alive while cola may use them. */
    private static UserConstraintTranslator sbgnExtras;
    private static PreIteration sbgnExtrasPre;
    private static TestConvergence sbgnExtrasConvergence;
    /**
     * Groups (a complex and the glyphs inside it) kept rigid with -Dcblayout.complexRigid=true; filled by
     * ConstraintSBGNLayout.registerRigidComplexes.
     */
    public static List<List<Node>> rigidGroups = new ArrayList<>();
    /** Moves the members of the rigid groups to their offsets before a solver call. */
    public static Runnable rigidSnap;
    private static TestConvergence convergence;          // kept alive while cola references it
    private static TestConvergence solveGuard;           // the guard of the last solveNodes run, kept alive likewise
    private static CompoundConstraintPtrs rigidOnly;     // executeFD: the rigid groups are its only constraints

    // Mental-map layout: read only by executeMentalMap, executeFDAnchored(Weighted) and, in mentalMapMode, executeFD.
    /**
     * Node clusters of the mental-map layout (each inner list is one cluster), used by
     * {@link #executeMentalMap(Graph, Constraints, boolean)}. Unlike {@link #clusterMap} they are not compartments.
     */
    public static List<List<Node>> allCluster = new ArrayList<>();
    /**
     * Whether {@link #executeMentalMap(Graph, Constraints, boolean)} switches on the solver's non-overlap for its final
     * projection (default true). makeFeasible() runs either way, since it also projects onto the cluster hierarchy.
     */
    public static boolean mentalMapFinalOverlapPass = true;
    /** Adaptagrams cluster margin and padding of the clusters of {@link #executeMentalMap(Graph, Constraints, boolean)}. */
    public static double clusterMargin = 5;
    public static double clusterPadding = 15;
    /**
     * Mental-map mode (default false): {@link #executeFD(Graph)} runs the mental-map variant (all nodes in stable order,
     * all edges of the graph, no rigid groups, no component packing, no conflict reports).
     */
    public static boolean mentalMapMode = false;
    /**
     * Whether the warning that libcola never called the convergence director back has been given. Without director
     * support in the native library the guarded runs fall back to libcola's own test.
     */
    private static boolean directorWarningPrinted = false;
    /**
     * Strong references to the native objects the mental-map solves hand to Adaptagrams, held until
     * {@link #clearNativeKeepAlive()}: SWIG proxies free their native memory in a finalizer while libcola may still use
     * it, which would make repeated solves depend on GC timing.
     */
    private static final List<Object> nativeKeepAlive = new ArrayList<>();

    private static void keepAlive(Object... objects) {
        for (Object o : objects) if (o != null) nativeKeepAlive.add(o);
    }

    /** Release the keep-alive references of the mental-map solves. Call once per sequence, never mid-sequence. */
    public static void clearNativeKeepAlive() {
        nativeKeepAlive.clear();
    }

    /**
     * The graph's nodes sorted by label (else by id), the stable node key of the mental-map layout. The position becomes
     * the solver variable index, by which the solver breaks ties; {@code Graph.getNodes()} has no fixed order.
     */
    private static List<Node> stableNodeOrder(Graph g) {
        List<Node> nodes = new ArrayList<>(g.getNodes());
        nodes.sort(Comparator.comparing(n -> {
            String l = AttributeHelper.getLabel(n, "");
            return (l == null || l.isEmpty()) ? ("id#" + n.getID()) : l;
        }));
        return nodes;
    }

    /** Builds the layout object; the plain 3-argument constructor unless a switch needs edge lengths or a convergence test. */
    private static ConstrainedFDLayout newLayout(RectanglePtrs rects, ColaEdges edges, List<Edge> graphEdges) {
        if (LEAF_LENGTH == 1 && !FD_CONVERGE_RESET) return new ConstrainedFDLayout(rects, edges, dist);
        Doubles lengths = new Doubles();
        if (LEAF_LENGTH != 1) {
            for (Edge e : graphEdges) {
                boolean leaf = e.getSource().getDegree() <= 1 || e.getTarget().getDegree() <= 1;
                lengths.add(leaf ? LEAF_LENGTH : 1.0);
            }
        }
        if (!FD_CONVERGE_RESET) return new ConstrainedFDLayout(rects, edges, dist, lengths);
        convergence = new TestConvergence(1e-4, 100);
        return new ConstrainedFDLayout(rects, edges, dist, lengths, convergence);
    }

    /** Whether the registered rigid groups belong to {@code g} (they are stale for another graph). */
    private static boolean rigidGroupsIn(Graph g) {
        if (rigidSnap == null || rigidGroups.isEmpty() || g == null) return false;
        for (List<Node> group : rigidGroups) for (Node n : group) return g.containsNode(n);
        return false;
    }

    /** One FixedRelativeConstraint per rigid group with at least two members in the current node index. */
    private static List<FixedRelativeConstraint> rigidConstraints() {
        List<FixedRelativeConstraint> l = new ArrayList<>();
        for (List<Node> group : rigidGroups) {
            Unsigneds ids = new Unsigneds();
            for (Node n : group) {
                Integer i = nodeIndex.get(n);
                if (i != null) ids.add((long) i);
            }
            if (ids.size() >= 2) l.add(new FixedRelativeConstraint(rectPtrVector, ids, false));
        }
        return l;
    }

    /** Equal spacing: one guideline per node and a cola DistributionConstraint over consecutive pairs. */
    private static void addDistribution(DistributionConstraintGraph c) {
        int dim = c.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                ? org.adaptagrams.Dim.XDIM : org.adaptagrams.Dim.YDIM;
        List<AlignmentConstraint> guides = new ArrayList<>();
        for (Node n : c.getNodes()) {
            Integer i = nodeIndex.get(n);
            if (i == null) continue;
            AlignmentConstraint a = new AlignmentConstraint(dim);
            a.addShape(i, 0);
            constraints.add(a);
            guides.add(a);
        }
        if (guides.size() < 2) return;
        DistributionConstraint d = new DistributionConstraint(dim);
        for (int i = 1; i < guides.size(); i++) d.addAlignmentPair(guides.get(i - 1), guides.get(i));
        d.setSeparation(c.getSpacing());
        constraints.add(d);
    }

    /** Solver variable index of every node of the current solve. */
    static Map<Node, Integer> nodeIndex = new HashMap<>();

    /** -Dcblayout.debugSvg=true|false (default false): write intermediate layouts as SVG into the working directory. */
    private static final boolean DEBUG_SVG = Boolean.getBoolean("cblayout.debugSvg");

    /** Best-effort SVG dump of the current layout instance, gated by {@link #DEBUG_SVG}. */
    private static void dumpSvg(String name) {
        if (!DEBUG_SVG || constraintFDLayout == null) return;
        try { constraintFDLayout.outputInstanceToSVG(name); } catch (Exception ignored) { }
    }

    private static void dumpSvg() {
        if (!DEBUG_SVG || constraintFDLayout == null) return;
        try { constraintFDLayout.outputInstanceToSVG(); } catch (Exception ignored) { }
    }

    /**
     * Compartment node &rarr; cluster bounds {minX,minY,maxX,maxY} computed by Adaptagrams in the last run.
     * {@link ConstraintSBGNLayout} sizes the compartment frames to exactly these non-overlapping regions.
     */
    public static Map<Node, double[]> clusterBounds = new HashMap<>();

    /** Receives cola's per-dimension reports of constraints it could not satisfy (kept alive while cola holds the pointer). */
    private static UnsatisfiableConstraintInfoPtrs unsatX, unsatY;

    private static void armUnsatisfiable(ConstrainedFDLayout fd) {
        if (FD_OVERLAP_RUN) {
            // With non-overlap in run(), cola also reports its temporary NonOverlapConstraints; reading their type
            // after the run dereferences freed memory and crashes the JVM, so there are no solver reports then.
            unsatX = null;
            unsatY = null;
            return;
        }
        unsatX = new UnsatisfiableConstraintInfoPtrs();
        unsatY = new UnsatisfiableConstraintInfoPtrs();
        try { fd.setUnsatisfiableConstraintInfo(unsatX, unsatY); } catch (Throwable ignored) { }
    }

    /**
     * Map cola's unsatisfiable-constraint reports (variable indices + gap + equality flag) back to
     * the registered {@link Constraint} objects and record them in {@link ConflictLog}. Reports about
     * solver-generated constraints (non-overlap, clusters) carry other compound types and are skipped.
     */
    private static void collectUnsatisfied(String phase, Map<Node, Integer> index) {
        if (unsatX == null || unsatY == null || ConstraintStore.constraints == null) return;
        Map<Integer, Node> inverse = new HashMap<>();
        for (Map.Entry<Node, Integer> e : index.entrySet()) inverse.put(e.getValue(), e.getKey());
        Set<String> seen = new HashSet<>();
        for (int dim = 0; dim < 2; dim++) {
            UnsatisfiableConstraintInfoPtrs infos = dim == 0 ? unsatX : unsatY;
            for (int i = 0; i < infos.size(); i++) {
                UnsatisfiableConstraintInfo info = infos.get(i);
                String cc = String.valueOf(info.getCc());
                boolean equality = info.getEquality();
                if (!cc.startsWith("SeparationConstraint") && !cc.startsWith("AlignmentConstraint")) continue;
                Node a = inverse.get((int) info.getLeftVarIndex());
                Node b = inverse.get((int) info.getRightVarIndex());
                if (a == null || b == null) continue;
                if (!seen.add(dim + ":" + a.getID() + ":" + b.getID() + ":" + equality)) continue;
                for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
                    if (!c.isActive() || !c.getNodes().contains(a) || !c.getNodes().contains(b)) continue;
                    boolean match;
                    if (equality) {
                        match = dim == 0 ? c instanceof VerticalConstraint : c instanceof HorizontalConstraint;
                    } else {
                        match = c instanceof SeparationConstraintGraph
                                && ((SeparationConstraintGraph) c).getType() == (dim == 0
                                    ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                                    : SeparationConstraintGraph.SeparationConstraintType.VERTICAL)
                                && Math.abs(((SeparationConstraintGraph) c).getDistance() - info.getSeparation()) < 1e-6;
                    }
                    if (match) {
                        ConflictLog.addUnsatisfied(c, phase, "the solver could not satisfy it (" + (dim == 0 ? "x" : "y")
                                + "); it contradicts other constraints");
                    }
                }
            }
        }
    }

    /**
     * Builds the Adaptagrams cluster hierarchy from {@link #clusterMap} (compartment &rarr; member nodes), with the
     * nesting of the compartments. Only members in {@link #nodeIndex} are added and empty leaf compartments are
     * skipped; {@code outClusterByComp} receives the cluster of every compartment, to read its bounds after the layout.
     */
    private static RootCluster buildClusterHierarchy(Map<Node, RectangularCluster> outClusterByComp) {
        RootCluster root = new RootCluster();
        if (clusterMap == null || clusterMap.isEmpty()) return root;

        List<Node> comps = new ArrayList<>(clusterMap.keySet());

        // One cluster per compartment with its direct members; every node is in its innermost compartment only
        // (ConstraintSBGNLayout.resolveCompartmentMap), so each node lands in exactly one cluster.
        Map<Node, RectangularCluster> byComp = new HashMap<>();
        Map<Node, Integer> directCount = new HashMap<>();
        for (Node comp : comps) {
            RectangularCluster rc = new RectangularCluster();
            rc.setMargin(5);
            rc.setPadding(15);
            int added = 0;
            for (Node child : clusterMap.get(comp)) {
                Integer ix = nodeIndex.get(child);
                if (ix == null) continue;
                rc.addChildNode(ix);
                added++;
            }
            byComp.put(comp, rc);
            directCount.put(comp, added);
        }

        // Nested compartments (e.g. plastid in membrane in cytosol) become nested clusters; a flat hierarchy would
        // force them side by side.
        Map<Node, Node> parent = new HashMap<>();
        for (Node c : comps) parent.put(c, innermostContainingCompartment(c, comps));

        // A compartment is live if it has direct members or a live descendant: empty leaf compartments are dropped,
        // empty intermediate ones that wrap live ones stay.
        Map<Node, Boolean> live = new HashMap<>();
        for (Node c : comps) {
            if (directCount.get(c) > 0) {
                for (Node x = c; x != null; x = parent.get(x)) live.put(x, true);
            }
        }

        for (Node c : comps) {
            if (!Boolean.TRUE.equals(live.get(c))) continue;
            Node p = parent.get(c);
            while (p != null && !Boolean.TRUE.equals(live.get(p))) p = parent.get(p);   // nearest live ancestor
            if (p != null) byComp.get(p).addChildCluster(byComp.get(c));
            else root.addChildCluster(byComp.get(c));
            outClusterByComp.put(c, byComp.get(c));
        }
        return root;
    }

    /**
     * Smallest other compartment whose bounding box contains {@code comp}'s, or {@code null}. Compartment glyphs keep
     * their geometry during the layout, so this reproduces the nesting of the drawing.
     */
    private static Node innermostContainingCompartment(Node comp, List<Node> compartments) {
        double w = AttributeHelper.getWidth(comp), h = AttributeHelper.getHeight(comp);
        double x = AttributeHelper.getPositionX(comp), y = AttributeHelper.getPositionY(comp);
        double left = x - w / 2, right = x + w / 2, top = y - h / 2, bottom = y + h / 2;
        double myArea = w * h;
        Node best = null;
        double bestArea = Double.POSITIVE_INFINITY;
        for (Node p : compartments) {
            if (p == comp) continue;
            double pw = AttributeHelper.getWidth(p), ph = AttributeHelper.getHeight(p);
            double pArea = pw * ph;
            if (pArea <= myArea) continue; // a parent must be strictly larger
            double px = AttributeHelper.getPositionX(p), py = AttributeHelper.getPositionY(p);
            boolean contains = (px - pw / 2) <= left + 1 && (py - ph / 2) <= top + 1
                    && right <= (px + pw / 2) + 1 && bottom <= (py + ph / 2) + 1;
            if (contains && pArea < bestArea) { bestArea = pArea; best = p; }
        }
        return best;
    }

    /** Read back the cluster rectangles adaptagrams computed after a run (see {@link #clusterBounds}). */
    private static void captureClusterBounds(Map<Node, RectangularCluster> clusterByComp) {
        clusterBounds = new HashMap<>();
        for (Map.Entry<Node, RectangularCluster> e : clusterByComp.entrySet()) {
            try {
                Rectangle b = e.getValue().getBounds();
                if (b != null) {
                    clusterBounds.put(e.getKey(),
                            new double[]{ b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY() });
                }
            } catch (Exception ignored) { }
        }
    }

    /** Fills {@link #nodeIndex} and {@link #rectPtrVector} with the rectangles of {@code nodes}, in this order. */
    private static void addRectangles(List<Node> nodes) {
        int lastID = 0;
        nodeIndex = new HashMap<>();
        rectPtrVector = new RectanglePtrs();
        for (Node n : nodes) {
            nodeIndex.put(n, lastID);
            rectPtrVector.add(defineRectangle(n));
            lastID++;
        }
    }

    /** The cola edges of {@code graphEdges}, whose ends must be in {@link #nodeIndex}; the edge bends are removed. */
    private static ColaEdges colaEdges(Collection<Edge> graphEdges) {
        ColaEdges edges = new ColaEdges();
        for (Edge e : graphEdges) {
            AttributeHelper.removeEdgeBends(e);
            long a = nodeIndex.get(e.getSource());
            long b = nodeIndex.get(e.getTarget());
            edges.add(new ColaEdge(a, b));
        }
        return edges;
    }

    /** Moves {@code nodes} to the centres of their rectangles. */
    private static void writePositions(Collection<Node> nodes) {
        for (Node node : nodes) {
            try {
                Rectangle r = rectPtrVector.get(nodeIndex.get(node));
                AttributeHelper.setPosition(node, r.getCentreX(), r.getCentreY());
            } catch (NullPointerException ignored) {
                // a node without a rectangle keeps its position
            }
        }
    }

    /**
     * Start of an SBGN solve over the layout nodes and edges of {@code g}: rectangles, layout object and solver
     * reports. Returns the cola edges.
     */
    private static ColaEdges startSbgnSolve(Graph g) {
        ConflictLog.clearUnsatisfied();
        if (rigidGroupsIn(g)) rigidSnap.run();
        addRectangles(ConstraintSBGNLayout.layoutNodes(g));
        List<Edge> graphEdges = ConstraintSBGNLayout.layoutEdges(g);
        ColaEdges edges = colaEdges(graphEdges);
        constraintFDLayout = newLayout(rectPtrVector, edges, graphEdges);
        armUnsatisfiable(constraintFDLayout);
        return edges;
    }

    /**
     * The constraints of an SBGN solve: the compartment clusters ({@link #rootCluster}), the store's active alignments,
     * separations and equal spacings and the rigid groups ({@link #constraints}), and with sbgnUserTypes=all the
     * user's other kinds. Returns the translator of those other kinds, or null if there are none.
     */
    private static UserConstraintTranslator addSbgnConstraints(Graph g, ColaEdges edges,
                                                               Map<Node, RectangularCluster> clusterByComp) {
        rootCluster = buildClusterHierarchy(clusterByComp);
        constraints = new CompoundConstraintPtrs();
        for (HorizontalConstraint c : ConstraintStore.constraints.getHorizontalConstraints()) {
            if (!c.isActive()) continue;   // switched off, or contradicts a constraint of higher priority
            AlignmentConstraint alignmentConstraint = null;
            if (c.getType() == HorizontalConstraint.HorizontalConstraintType.TOP) {
                alignmentConstraint = createAlignTop(c.getNodes());
            } else if (c.getType() == HorizontalConstraint.HorizontalConstraintType.MIDDLE) {
                alignmentConstraint = createAlignMiddle(c.getNodes());
            } else if (c.getType() == HorizontalConstraint.HorizontalConstraintType.BOTTOM) {
                alignmentConstraint = createAlignBottom(c.getNodes());
            }
            constraints.add(alignmentConstraint);
        }
        for (VerticalConstraint c : ConstraintStore.constraints.getVerticalConstraints()) {
            if (!c.isActive()) continue;
            AlignmentConstraint alignmentConstraint = null;
            if (c.getType() == VerticalConstraint.VerticalConstraintType.LEFT) {
                alignmentConstraint = createAlignLeft(c.getNodes());
            } else if (c.getType() == VerticalConstraint.VerticalConstraintType.MIDDLE) {
                alignmentConstraint = createAlignCenter(c.getNodes());
            } else if (c.getType() == VerticalConstraint.VerticalConstraintType.RIGHT) {
                alignmentConstraint = createAlignRight(c.getNodes());
            }
            constraints.add(alignmentConstraint);
        }
        for (SeparationConstraintGraph c : ConstraintStore.constraints.getSeparationConstraints()) {
            if (!c.isActive()) continue;
            SeparationConstraint separationConstraint = null;
            if (c.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL) {
                separationConstraint = createSeparationX(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1),
                        c.isFixed());
            } else if (c.getType() == SeparationConstraintGraph.SeparationConstraintType.VERTICAL) {
                separationConstraint = createSeparationY(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1),
                        c.isFixed());
            }
            constraints.add(separationConstraint);
        }
        for (DistributionConstraintGraph c : ConstraintStore.constraints.getDistributionConstraints()) {
            if (c.isActive()) addDistribution(c);
        }
        if (rigidGroupsIn(g)) for (FixedRelativeConstraint c : rigidConstraints()) constraints.add(c);
        UserConstraintTranslator extras = sbgnUserExtras(nodeIndex, rectPtrVector, true, false);
        if (extras != null) {
            constraintFDLayout = withLocks(constraintFDLayout, extras, rectPtrVector, edges, null, null);
            armUnsatisfiable(constraintFDLayout);
        }
        return extras;
    }

    /**
     * End of an SBGN solve with constraints: records the solver's reports, writes the positions and projects once more
     * if a translated user constraint does not hold.
     */
    private static void finishSbgnSolve(Graph g, UserConstraintTranslator extras) {
        collectUnsatisfied("Solver", nodeIndex);
        Runnable write = () -> writePositions(ConstraintSBGNLayout.layoutNodes(g));
        write.run();
        projectAgainIfExtrasUnmet(extras, constraintFDLayout, write);
    }

    /**
     * Constraint-based layout of an SBGN step: a force-directed run with the store's active constraints, then the
     * projection onto the compartment clusters, the constraints and non-overlap.
     */
    public static void execute(Graph g) {
        ColaEdges edges = startSbgnSolve(g);
        Map<Node, RectangularCluster> clusterByComp = new HashMap<>();
        UserConstraintTranslator extras = addSbgnConstraints(g, edges, clusterByComp);
        try {
            constraintFDLayout.setConstraints(withExtrasForRun(constraints, extras));
            if (extras != null && !extras.desired.isEmpty()) constraintFDLayout.setDesiredPositions(extras.desired);
            // The clusters take part in the final projection only: a run with clusters forces every node into its box
            // and destroys the connectivity-based structure.
            if (FD_OVERLAP_RUN) constraintFDLayout.setAvoidNodeOverlaps(true);   // -Dcblayout.fdOverlap=run
            constraintFDLayout.run();
            if (clusterByComp.size() > 0) {
                constraintFDLayout.setClusterHierarchy(rootCluster);
            }
            ComponentTools.afterRun(g, nodeIndex, rectPtrVector);   // -Dcblayout.packComponents=place
            if (extras != null && extras.separateFinalList) {
                constraintFDLayout.setConstraints(withExtrasForProjection(constraints, extras));
            }
            constraintFDLayout.setAvoidNodeOverlaps(true);
            constraintFDLayout.makeFeasible();
            captureClusterBounds(clusterByComp);
            dumpSvg("Clusters");
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        dumpSvg();
        finishSbgnSolve(g, extras);
    }

    /**
     * Mental-map preserving constraint-based layout: like {@link #execute(Graph)}, but with the clusters of
     * {@link #allCluster} and the alignments and separations of {@code store} (null counts as empty). Nothing is
     * reported to {@link ConflictLog}, which belongs to the user's store.
     */
    public static void executeMentalMap(Graph g, Constraints store) {
        executeMentalMap(g, store, false);
    }

    /**
     * {@code explicitNonOverlap}: the store already holds order-consistent non-overlap constraints, so libcola's own
     * non-overlap stays off (it could contradict the block order).
     */
    public static void executeMentalMap(Graph g, Constraints store, boolean explicitNonOverlap) {
        executeMentalMap(g, store, explicitNonOverlap, null, null, true);
    }

    /**
     * Convergence test that ends a force-directed run on the best iterate it has seen. libcola's step size
     * {@code g'g / g'Hg} is unbounded where the p-stress Hessian is indefinite (pairs closer than their ideal distance),
     * and its default test stops on such a blown-up iterate.
     *
     * <p>The test compares the stress libcola passes after every iteration ({@code computeStress()}, anchor terms
     * included), keeps the rectangles of the iterate with the lowest stress and stops once the stress has not decreased
     * for more than {@code patience} iterations in a row (or at a relative gain below {@code tolerance}, or after
     * {@code maxIterations}); {@link #finish()} restores the best iterate. Every iterate is a projection onto the
     * constraints, so the restored drawing is as feasible as the rejected one, and {@code makeFeasible()} afterwards
     * starts from the rectangles, not from libcola's internal coordinates. The test is a SWIG director and needs a
     * native library built with director support.
     */
    private static final class MonotoneConvergence extends TestConvergence {
        private final RectanglePtrs rects;
        private final double tol;
        private final long maxIter;
        private double[] bestX, bestY;
        private double bestStress = Double.MAX_VALUE;
        private boolean restore;
        /** Director calls so far; 0 after a run() on two or more nodes means the director is not connected. */
        private long iterations;
        /** Iterations in a row that may fail to lower the stress before the run stops (0: it stops at the first). */
        private final int patience;
        private int worse;

        MonotoneConvergence(RectanglePtrs rects, double tolerance, long maxIterations) {
            this(rects, tolerance, maxIterations, 0);
        }

        MonotoneConvergence(RectanglePtrs rects, double tolerance, long maxIterations, int patience) {
            super(tolerance, maxIterations);
            this.rects = rects;
            this.tol = tolerance;
            this.maxIter = maxIterations;
            this.patience = patience;
        }

        /** Record the start drawing and its stress ({@code computeStress()} with the anchors already set). */
        void start(double initialStress) {
            bestStress = initialStress;
            snapshot();
        }

        @Override
        public boolean ColaTestConvergenceOperator(double stress, SWIGTYPE_p_std__valarrayT_double_t x,
                                                   SWIGTYPE_p_std__valarrayT_double_t y) {
            iterations++;
            if (FD_TRACE) System.out.println("[cblayout] fd iteration " + iterations + " stress " + stress + (stress < bestStress ? "" : " (not lower)"));
            if (!(stress < bestStress)) {          // not better (or NaN): keep the best iterate, and stop unless patient
                restore = true;
                return ++worse > patience || iterations >= maxIter;
            }
            double gain = (bestStress - stress) / (stress + 1e-10);
            bestStress = stress;
            snapshot();
            restore = false;
            worse = 0;
            return gain < tol || iterations >= maxIter;
        }

        /** After run(): put the best iterate back into the rectangles if the last step was rejected. */
        void finish() {
            if (!restore || bestX == null) return;
            for (int i = 0; i < bestX.length; i++) rects.get(i).moveCentre(bestX[i], bestY[i]);
        }

        private void snapshot() {
            int n = (int) rects.size();
            if (bestX == null) { bestX = new double[n]; bestY = new double[n]; }
            for (int i = 0; i < n; i++) {
                Rectangle r = rects.get(i);
                bestX[i] = r.getCentreX();
                bestY[i] = r.getCentreY();
            }
        }
    }

    /**
     * Extra makeFeasible passes of the anchored mental-map run while solver rectangles still overlap. makeFeasible adds
     * the constraints one at a time and can stop short of a point that meets them all; projecting again from where it
     * stopped mostly reaches it (as in executeUserConstraints).
     */
    private static final int FEASIBLE_RETRIES = 3;

    /** Pairs of solver rectangles (padded by {@link #bboxConst}) overlapping by more than 0.5 px on both axes. */
    private static int overlappingPairs(RectanglePtrs rects) {
        int n = (int) rects.size();
        double[] x0 = new double[n], x1 = new double[n], y0 = new double[n], y1 = new double[n];
        for (int i = 0; i < n; i++) {
            Rectangle r = rects.get(i);
            x0[i] = r.getMinX(); x1[i] = r.getMaxX(); y0[i] = r.getMinY(); y1[i] = r.getMaxY();
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.min(x1[i], x1[j]) - Math.max(x0[i], x0[j]) > 0.5
                        && Math.min(y1[i], y1[j]) - Math.max(y0[i], y0[j]) > 0.5) count++;
            }
        }
        return count;
    }

    /**
     * After a guarded {@code run()}: reports once per JVM that libcola never called the convergence director back.
     * {@code run()} tests convergence at least once, so zero calls on two or more nodes mean that the native library has
     * no director support and the run was unguarded. The result is not changed.
     */
    private static void warnIfDirectorInactive(MonotoneConvergence monotone) {
        if (directorWarningPrinted || monotone.iterations > 0 || rectPtrVector.size() < 2) return;
        directorWarningPrinted = true;
        System.err.println("[CBLayout] TestConvergence director inactive - the guarded force-directed run is unguarded");
    }

    /**
     * {@link #executeMentalMap(Graph, Constraints, boolean)} with weak anchors, used by the stable mode of the mental-map
     * layout. Every node with an anchor and a positive weight gets a libcola desired position during the force-directed
     * run (stress term {@code w/2 * |p - anchor|^2}); the projection afterwards ignores the anchors, so an anchor never
     * outweighs a hard constraint. An anchored run also ends on its best iterate ({@link MonotoneConvergence}) and
     * repeats makeFeasible() up to {@link #FEASIBLE_RETRIES} times while solver rectangles still overlap.
     *
     * @param anchors   desired centre per node, or null for the unanchored run of the 3-argument overload
     * @param weights   anchor weight per node, in libcola's stress units (a pair's stress is normalised by the squared
     *                  ideal distance: a weight of 1e-4 pulls a node 100 px from its anchor as hard as one edge stretched
     *                  to twice {@link #dist})
     * @param runForces false skips the force-directed run: the drawing is only projected onto the constraints, clusters
     *                  and non-overlap, so a drawing that already meets them does not move
     */
    public static void executeMentalMap(Graph g, Constraints store, boolean explicitNonOverlap,
                                        Map<Node, java.awt.geom.Point2D> anchors, Map<Node, Double> weights,
                                        boolean runForces) {
        addRectangles(stableNodeOrder(g));
        ColaEdges edges = colaEdges(g.getEdges());

        MonotoneConvergence monotone = anchors != null ? new MonotoneConvergence(rectPtrVector, 1e-4, 100) : null;
        Doubles standardLengths = monotone != null ? new Doubles() : null;
        constraintFDLayout = monotone == null
                ? new ConstrainedFDLayout(rectPtrVector, edges, dist)
                : new ConstrainedFDLayout(rectPtrVector, edges, dist, standardLengths, monotone);
        // Keep the native inputs reachable for as long as the layout can touch them.
        keepAlive(rectPtrVector, edges, constraintFDLayout, standardLengths, monotone);

        rootCluster = new RootCluster();
        boolean hasClusters = false;
        if (allCluster != null) {
            for (List<Node> cluster : allCluster) {
                RectangularCluster rc = new RectangularCluster();
                rc.setMargin(clusterMargin);
                rc.setPadding(clusterPadding);
                int added = 0;
                for (Node child : cluster) {
                    Integer childIx = nodeIndex.get(child);
                    if (childIx == null) continue;
                    rc.addChildNode(childIx);
                    added++;
                }
                if (added > 0) {
                    rootCluster.addChildCluster(rc);
                    hasClusters = true;
                }
            }
        }

        constraints = new CompoundConstraintPtrs();
        if (store != null) {
            for (HorizontalConstraint c : store.getHorizontalConstraints()) {
                AlignmentConstraint alignmentConstraint = null;
                if (c.getType() == HorizontalConstraint.HorizontalConstraintType.TOP)
                    alignmentConstraint = createAlignTop(c.getNodes());
                else if (c.getType() == HorizontalConstraint.HorizontalConstraintType.MIDDLE)
                    alignmentConstraint = createAlignMiddle(c.getNodes());
                else if (c.getType() == HorizontalConstraint.HorizontalConstraintType.BOTTOM)
                    alignmentConstraint = createAlignBottom(c.getNodes());
                if (alignmentConstraint != null) constraints.add(alignmentConstraint);
            }

            for (VerticalConstraint c : store.getVerticalConstraints()) {
                AlignmentConstraint alignmentConstraint = null;
                if (c.getType() == VerticalConstraint.VerticalConstraintType.LEFT)
                    alignmentConstraint = createAlignLeft(c.getNodes());
                else if (c.getType() == VerticalConstraint.VerticalConstraintType.MIDDLE)
                    alignmentConstraint = createAlignCenter(c.getNodes());
                else if (c.getType() == VerticalConstraint.VerticalConstraintType.RIGHT)
                    alignmentConstraint = createAlignRight(c.getNodes());
                if (alignmentConstraint != null) constraints.add(alignmentConstraint);
            }

            for (SeparationConstraintGraph c : store.getSeparationConstraints()) {
                SeparationConstraint separationConstraint = null;
                if (c.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL) {
                    separationConstraint = createSeparationX(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1), c.isFixed());
                } else if (c.getType() == SeparationConstraintGraph.SeparationConstraintType.VERTICAL) {
                    separationConstraint = createSeparationY(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1), c.isFixed());
                }
                if (separationConstraint != null) constraints.add(separationConstraint);
            }
        }

        // Anchors in the solver's variable order, so the vector handed to libcola does not depend on the map's order.
        ColaDesiredPositions des = null;
        if (anchors != null && weights != null && !anchors.isEmpty()) {
            des = new ColaDesiredPositions();
            for (Node n : stableNodeOrder(g)) {
                java.awt.geom.Point2D a = anchors.get(n);
                Double w = weights.get(n);
                if (a == null || w == null || !(w > 0)) continue;   // no anchor or no weight: fully free
                DesiredPosition dp = new DesiredPosition();
                dp.setId(nodeIndex.get(n).longValue());
                dp.setX(a.getX());
                dp.setY(a.getY());
                dp.setWeight(w);
                des.add(dp);
            }
            // libcola only borrows the native vector; it must outlive run().
            keepAlive(des);
        }

        try {
            constraintFDLayout.setConstraints(constraints);
            if (des != null && des.size() > 0) constraintFDLayout.setDesiredPositions(des);
            // As in execute(Graph): the clusters are attached for the final projection only.
            if (runForces) {
                if (monotone != null) monotone.start(constraintFDLayout.computeStress());
                constraintFDLayout.run();
                if (monotone != null) {
                    monotone.finish();
                    warnIfDirectorInactive(monotone);
                }
            }
            if (hasClusters) {
                constraintFDLayout.setClusterHierarchy(rootCluster);
            }
            // makeFeasible() always runs: it is the only call after setClusterHierarchy(), so it is what projects the
            // drawing onto the clusters. Only the non-overlap is optional.
            if (mentalMapFinalOverlapPass && !explicitNonOverlap) {
                constraintFDLayout.setAvoidNodeOverlaps(true);
            }
            constraintFDLayout.makeFeasible();
            for (int pass = 0; anchors != null && pass < FEASIBLE_RETRIES
                    && mentalMapFinalOverlapPass && !explicitNonOverlap && overlappingPairs(rectPtrVector) > 0; pass++) {
                constraintFDLayout.makeFeasible();
            }
            dumpSvg("MentalMap");
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        writePositions(g.getNodes());
    }

    /** {@link #execute(Graph)} with an unconstrained force-directed run as warm start before the constrained run. */
    public static void executeConstraints(Graph g) {
        ColaEdges edges = startSbgnSolve(g);
        Map<Node, RectangularCluster> clusterByComp = new HashMap<>();
        UserConstraintTranslator extras = addSbgnConstraints(g, edges, clusterByComp);
        try {
            constraintFDLayout.run();
            constraintFDLayout.setConstraints(withExtrasForRun(constraints, extras));
            if (extras != null && !extras.desired.isEmpty()) constraintFDLayout.setDesiredPositions(extras.desired);
            if (FD_CONVERGE_RESET && convergence != null) convergence.reset();   // -Dcblayout.fdConverge=reset
            if (FD_OVERLAP_RUN) constraintFDLayout.setAvoidNodeOverlaps(true);   // -Dcblayout.fdOverlap=run
            constraintFDLayout.run();
            if (clusterByComp.size() > 0) {
                constraintFDLayout.setClusterHierarchy(rootCluster);
            }
            ComponentTools.afterRun(g, nodeIndex, rectPtrVector);   // -Dcblayout.packComponents=place
            if (extras != null && extras.separateFinalList) {
                constraintFDLayout.setConstraints(withExtrasForProjection(constraints, extras));
            }
            constraintFDLayout.setAvoidNodeOverlaps(true);
            constraintFDLayout.makeFeasible();
            captureClusterBounds(clusterByComp);
            dumpSvg("Constraints");
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        finishSbgnSolve(g, extras);
    }

    /**
     * Force-directed step of the SBGN pipeline: an unconstrained run (only the rigid groups are kept), then the
     * components are packed. With {@link #mentalMapMode} the mental-map variant instead.
     */
    public static void executeFD(Graph g) {
        if (mentalMapMode) { executeFDMentalMap(g); return; }
        startSbgnSolve(g);
        if (rigidGroupsIn(g)) {
            List<FixedRelativeConstraint> rigid = rigidConstraints();
            if (!rigid.isEmpty()) {
                rigidOnly = new CompoundConstraintPtrs();
                for (FixedRelativeConstraint c : rigid) rigidOnly.add(c);
                constraintFDLayout.setConstraints(rigidOnly);
            }
        }
        try {
            constraintFDLayout.run();
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        dumpSvg("FD");
        // cola exerts no force between components: move them apart here (-Dcblayout.packComponents=place)
        ComponentTools.afterRun(g, nodeIndex, rectPtrVector);
        collectUnsatisfied("Solver", nodeIndex);
        writePositions(ConstraintSBGNLayout.layoutNodes(g));
    }

    /**
     * {@link #executeFD(Graph)} with {@link #mentalMapMode}: the unconstrained force-directed run of the mental-map
     * layout over all nodes in stable order and all edges, without rigid groups, component packing or conflict reports.
     */
    private static void executeFDMentalMap(Graph g) {
        addRectangles(stableNodeOrder(g));
        ColaEdges edges = colaEdges(g.getEdges());
        constraintFDLayout = new ConstrainedFDLayout(rectPtrVector, edges, dist);
        // Keep the native inputs reachable for as long as the layout can touch them.
        keepAlive(rectPtrVector, edges, constraintFDLayout);
        try {
            constraintFDLayout.run();
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        dumpSvg("FD");
        writePositions(g.getNodes());
    }

    /**
     * Anchored force-directed layout after Brandes &amp; Mader (2012, "APP"): the mental-map {@link #executeFD(Graph)}
     * in which every node listed in {@code anchors} gets a libcola desired position, i.e. the stress gains
     * {@code lambda * ||p_v - anchor_v||^2}. The warm start is the current drawing; nodes without an anchor move freely.
     *
     * @param lambda anchor weight; 0 approaches free FD, large values effectively pin
     */
    public static void executeFDAnchored(Graph g, Map<Node, java.awt.geom.Point2D> anchors, double lambda) {
        Map<Node, Double> w = new HashMap<>();
        for (Node n : anchors.keySet()) w.put(n, lambda);
        executeFDAnchoredWeighted(g, anchors, w);
    }

    /**
     * {@link #executeFDAnchored(Graph, Map, double)} with a weight per node, used by the pinning baseline (after
     * Frishman and Tal); nodes with a missing or non-positive weight move freely.
     */
    public static void executeFDAnchoredWeighted(Graph g, Map<Node, java.awt.geom.Point2D> anchors,
                                                 Map<Node, Double> weights) {
        addRectangles(stableNodeOrder(g));
        ColaEdges edges = colaEdges(g.getEdges());

        ColaDesiredPositions des = new ColaDesiredPositions();
        for (Map.Entry<Node, java.awt.geom.Point2D> a : anchors.entrySet()) {
            Integer idx = nodeIndex.get(a.getKey());
            if (idx == null) continue;                 // anchor for a node not in g
            Double lw = weights.get(a.getKey());
            if (lw == null || lw <= 0) continue;
            DesiredPosition dp = new DesiredPosition();
            dp.setId(idx.longValue());
            dp.setX(a.getValue().getX());
            dp.setY(a.getValue().getY());
            dp.setWeight(lw.doubleValue());
            des.add(dp);
        }

        constraintFDLayout = new ConstrainedFDLayout(rectPtrVector, edges, dist);
        // Keep the native inputs reachable for as long as the layout can touch them.
        keepAlive(rectPtrVector, edges, constraintFDLayout);
        try {
            constraintFDLayout.setDesiredPositions(des);
            constraintFDLayout.run();
            // No overlap removal: the anchored baselines have no non-overlap component.
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        dumpSvg("BRANDES_APP");
        writePositions(g.getNodes());
        // Keep the desired-position vector reachable until after run(): the SWIG proxy owns the native vector that
        // libcola only borrows through setDesiredPositions.
        des.size();
    }

    // Two-phase compartment layout: bottom-up, each compartment's interior is laid out from its own members and its
    // child compartments as solid boxes, then the boxes are arranged, so no foreign node can land inside a box.

    /**
     * Compartment layout in two phases (see above); positions are composed level by level and the frames recorded in
     * {@link #clusterBounds}. Without compartments it is {@link #execute(Graph)}.
     */
    public static void layoutHierarchically(Graph g) {
        ConflictLog.clearUnsatisfied();
        if (clusterMap == null || clusterMap.isEmpty()) { execute(g); return; }

        List<Node> comps = new ArrayList<>(clusterMap.keySet());

        // node -> its innermost compartment (absent => free node, outside every compartment)
        Map<Node, Node> compOf = new HashMap<>();
        for (Map.Entry<Node, ArrayList<Node>> e : clusterMap.entrySet())
            for (Node m : e.getValue()) compOf.put(m, e.getKey());

        // raw geometric nesting, then keep only "live" compartments (those with members in their subtree)
        Map<Node, Node> rawParent = new HashMap<>();
        for (Node c : comps) rawParent.put(c, innermostContainingCompartment(c, comps));
        Map<Node, Boolean> live = new HashMap<>();
        for (Node c : comps) {
            List<Node> mem = clusterMap.get(c);
            if (mem != null && !mem.isEmpty())
                for (Node x = c; x != null; x = rawParent.get(x)) live.put(x, Boolean.TRUE);
        }
        // parent = nearest LIVE ancestor (collapses empty intermediate compartments)
        Map<Node, Node> parent = new HashMap<>();
        for (Node c : comps) {
            if (!Boolean.TRUE.equals(live.get(c))) continue;
            Node p = rawParent.get(c);
            while (p != null && !Boolean.TRUE.equals(live.get(p))) p = rawParent.get(p);
            parent.put(c, p);
        }
        Map<Node, List<Node>> children = new HashMap<>();
        List<Node> roots = new ArrayList<>();
        for (Node c : parent.keySet()) {
            Node p = parent.get(c);
            if (p == null) roots.add(c);
            else children.computeIfAbsent(p, k -> new ArrayList<>()).add(c);
        }

        Map<Node, double[]> boxSize = new HashMap<>();
        Map<Node, double[]> localCenter = new HashMap<>();
        Map<Node, Map<Node, double[]>> localMemberPos = new HashMap<>();
        Map<Node, Map<Node, double[]>> localChildPos = new HashMap<>();

        // lay out compartments deepest-first so a child box exists before its parent needs it
        List<Node> order = new ArrayList<>(parent.keySet());
        order.sort((a, b) -> Integer.compare(depth(b, parent), depth(a, parent)));
        for (Node c : order) {
            List<Node> members = new ArrayList<>();
            for (Node m : clusterMap.get(c)) if (g.containsNode(m)) members.add(m);
            List<Node> childComps = children.getOrDefault(c, Collections.<Node>emptyList());
            Map<Node, double[]> mPos = new HashMap<>(), cPos = new HashMap<>();
            double[] bbox = layoutLevel(g, members, childComps, c, compOf, parent, boxSize, mPos, cPos);
            localMemberPos.put(c, mPos);
            localChildPos.put(c, cPos);
            boxSize.put(c, new double[]{ bbox[2] - bbox[0], bbox[3] - bbox[1] });
            localCenter.put(c, new double[]{ (bbox[0] + bbox[2]) / 2.0, (bbox[1] + bbox[3]) / 2.0 });
        }

        // top level: free nodes + root compartment boxes (top-level coordinates ARE absolute)
        List<Node> freeNodes = new ArrayList<>();
        for (Node n : ConstraintSBGNLayout.layoutNodes(g)) if (!compOf.containsKey(n)) freeNodes.add(n);
        Map<Node, double[]> topMemberPos = new HashMap<>(), topChildPos = new HashMap<>();
        layoutLevel(g, freeNodes, roots, null, compOf, parent, boxSize, topMemberPos, topChildPos);

        // compose absolute positions: free nodes are already absolute; place each root box and recurse
        Map<Node, double[]> abs = new HashMap<>(topMemberPos);
        clusterBounds = new HashMap<>();
        for (Node r : roots) {
            double[] c = topChildPos.get(r);
            if (c != null) placeCompartment(r, c[0], c[1], children, boxSize, localCenter,
                    localMemberPos, localChildPos, abs);
        }
        for (Map.Entry<Node, double[]> e : abs.entrySet()) {
            try { AttributeHelper.setPosition(e.getKey(), e.getValue()[0], e.getValue()[1]); }
            catch (Exception ignored) { }
        }
    }

    /** Translate compartment {@code c}'s local layout so its bbox centre lands at (absCx,absCy), then
     *  recurse into its child compartments. Records the absolute frame in {@link #clusterBounds}. */
    private static void placeCompartment(Node c, double absCx, double absCy,
            Map<Node, List<Node>> children, Map<Node, double[]> boxSize, Map<Node, double[]> localCenter,
            Map<Node, Map<Node, double[]>> localMemberPos, Map<Node, Map<Node, double[]>> localChildPos,
            Map<Node, double[]> abs) {
        double[] lc = localCenter.get(c);
        if (lc == null) return;
        double dx = absCx - lc[0], dy = absCy - lc[1];
        for (Map.Entry<Node, double[]> e : localMemberPos.get(c).entrySet())
            abs.put(e.getKey(), new double[]{ e.getValue()[0] + dx, e.getValue()[1] + dy });
        for (Map.Entry<Node, double[]> e : localChildPos.get(c).entrySet())
            placeCompartment(e.getKey(), e.getValue()[0] + dx, e.getValue()[1] + dy,
                    children, boxSize, localCenter, localMemberPos, localChildPos, abs);
        double[] sz = boxSize.get(c);
        clusterBounds.put(c, new double[]{ absCx - sz[0] / 2.0, absCy - sz[1] / 2.0,
                absCx + sz[0] / 2.0, absCy + sz[1] / 2.0 });
    }

    private static int depth(Node c, Map<Node, Node> parent) {
        int d = 0;
        for (Node x = parent.get(c); x != null; x = parent.get(x)) d++;
        return d;
    }

    /** Centroid of all nodes in {@code comp}'s subtree at their current positions (the global seed),
     *  used to seed the child box so it nests where its content already lies. */
    private static double[] subtreeCentroid(Graph g, Node comp, Map<Node, Node> compOf, Map<Node, Node> parent) {
        double sx = 0, sy = 0; int n = 0;
        for (Node u : ConstraintSBGNLayout.layoutNodes(g)) {
            for (Node x = compOf.get(u); x != null; x = parent.get(x)) {
                if (x == comp) { sx += AttributeHelper.getPositionX(u); sy += AttributeHelper.getPositionY(u); n++; break; }
            }
        }
        if (n == 0) return new double[]{ AttributeHelper.getPositionX(comp), AttributeHelper.getPositionY(comp) };
        return new double[]{ sx / n, sy / n };
    }

    /**
     * Lay out one flat level: {@code members} (real nodes) plus {@code childComps} (each as a solid box
     * sized to {@code boxSize[child]}). Fills {@code outMemberPos}/{@code outChildPos} with the resulting
     * local positions and returns the level bounding box {minX,minY,maxX,maxY}. {@code levelComp} is the
     * compartment being laid out, or {@code null} for the top level.
     */
    private static double[] layoutLevel(Graph g, List<Node> members, List<Node> childComps, Node levelComp,
            Map<Node, Node> compOf, Map<Node, Node> parent, Map<Node, double[]> boxSize,
            Map<Node, double[]> outMemberPos, Map<Node, double[]> outChildPos) {

        RectanglePtrs rects = new RectanglePtrs();
        Map<Node, Integer> memberIndex = new HashMap<>();
        Map<Node, Integer> childIdx = new HashMap<>();
        int idx = 0;
        for (Node m : members) { memberIndex.put(m, idx++); rects.add(defineRectangle(m)); }
        for (Node c : childComps) {
            childIdx.put(c, idx++);
            double[] sz = boxSize.get(c);
            double w = (sz != null) ? sz[0] : AttributeHelper.getWidth(c);
            double h = (sz != null) ? sz[1] : AttributeHelper.getHeight(c);
            // Seed the child box at the actual centroid of its content in the (structured) global
            // layout, so it nests where the pathway already is instead of shoving members aside.
            double[] ctr = subtreeCentroid(g, c, compOf, parent);
            rects.add(new Rectangle(ctr[0] - w / 2, ctr[0] + w / 2, ctr[1] - h / 2, ctr[1] + h / 2));
        }

        // edges between units at this level (cross-level edges collapse onto the child box)
        ColaEdges edges = new ColaEdges();
        for (Edge e : ConstraintSBGNLayout.layoutEdges(g)) {
            int a = unitIndex(e.getSource(), levelComp, compOf, parent, memberIndex, childIdx);
            int b = unitIndex(e.getTarget(), levelComp, compOf, parent, memberIndex, childIdx);
            if (a >= 0 && b >= 0 && a != b) edges.add(new ColaEdge(a, b));
        }

        ConstrainedFDLayout fd = new ConstrainedFDLayout(rects, edges, dist);
        armUnsatisfiable(fd);

        // The store's constraints whose nodes are all direct members of this level. The create* helpers read the
        // static nodeIndex, so it points at this level's member index from here on.
        nodeIndex = memberIndex;
        CompoundConstraintPtrs ccs = new CompoundConstraintPtrs();
        if (ConstraintStore.constraints != null) {
            for (HorizontalConstraint c : ConstraintStore.constraints.getHorizontalConstraints()) {
                if (!c.isActive()) continue;
                if (!memberIndex.keySet().containsAll(c.getNodes())) continue;
                ccs.add(alignmentOf(c));
            }
            for (VerticalConstraint c : ConstraintStore.constraints.getVerticalConstraints()) {
                if (!c.isActive()) continue;
                if (!memberIndex.keySet().containsAll(c.getNodes())) continue;
                ccs.add(alignmentOf(c));
            }
            for (SeparationConstraintGraph c : ConstraintStore.constraints.getSeparationConstraints()) {
                if (!c.isActive()) continue;
                if (c.getNodes().size() != 2 || !memberIndex.keySet().containsAll(c.getNodes())) continue;
                ccs.add(separationOf(c));
            }
        }

        // sbgnUserTypes=all: the user's other kinds whose nodes are all on this level (nothing changes without them)
        UserConstraintTranslator extras = sbgnUserExtras(memberIndex, rects, false, false);
        try {
            CompoundConstraintPtrs all = withExtrasForProjection(ccs, extras);
            if (all.size() > 0) fd.setConstraints(all);
            fd.setAvoidNodeOverlaps(true);
            // A projection of the global seed with minimal movement instead of a new force-directed run, which would
            // scramble the structure inside the compartment.
            fd.makeFeasible();
            collectUnsatisfied("Solver", memberIndex);
            if (extras != null) {   // as the user solve: project again if a user constraint does not hold yet
                boolean unmet = false;
                for (Constraint c : extras.translated) {
                    for (Node n : c.getNodes()) {   // the check reads attributes: give it the rectangles' positions
                        Rectangle r = rects.get(memberIndex.get(n));
                        AttributeHelper.setPosition(n, r.getCentreX(), r.getCentreY());
                    }
                    if (ConstraintCheck.violation(c, memberIndex.keySet(), ConstraintCheck.TOLERANCE) != null) unmet = true;
                }
                if (unmet) fd.makeFeasible();
            }
        } catch (Exception ex) {
            ErrorMsg.addErrorMessage(ex);
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Node, Integer> en : memberIndex.entrySet()) {
            Rectangle r = rects.get(en.getValue());
            outMemberPos.put(en.getKey(), new double[]{ r.getCentreX(), r.getCentreY() });
            minX = Math.min(minX, r.getMinX()); maxX = Math.max(maxX, r.getMaxX());
            minY = Math.min(minY, r.getMinY()); maxY = Math.max(maxY, r.getMaxY());
        }
        for (Map.Entry<Node, Integer> en : childIdx.entrySet()) {
            Rectangle r = rects.get(en.getValue());
            outChildPos.put(en.getKey(), new double[]{ r.getCentreX(), r.getCentreY() });
            minX = Math.min(minX, r.getMinX()); maxX = Math.max(maxX, r.getMaxX());
            minY = Math.min(minY, r.getMinY()); maxY = Math.max(maxY, r.getMaxY());
        }
        if (minX == Double.POSITIVE_INFINITY) { minX = minY = 0; maxX = maxY = 1; } // empty-level guard
        return new double[]{ minX, minY, maxX, maxY };
    }

    /** Adaptagrams unit index that node {@code u} maps to at {@code levelComp}'s level, or -1 if {@code u}
     *  is not represented on this level (i.e. its compartment lies outside {@code levelComp}'s subtree). */
    private static int unitIndex(Node u, Node levelComp, Map<Node, Node> compOf, Map<Node, Node> parent,
            Map<Node, Integer> memberIndex, Map<Node, Integer> childIdx) {
        Node c = compOf.get(u);
        if (levelComp == null) {                              // top level
            if (c == null) return memberIndex.getOrDefault(u, -1); // free node
            Node r = c; while (parent.get(r) != null) r = parent.get(r);
            return childIdx.getOrDefault(r, -1);              // root compartment box
        }
        if (c == levelComp) return memberIndex.getOrDefault(u, -1); // direct member
        if (c == null) return -1;
        Node child = c, p = parent.get(c);
        while (p != null) {
            if (p == levelComp) return childIdx.getOrDefault(child, -1); // child box on the path
            child = p; p = parent.get(p);
        }
        return -1;
    }

    /**
     * The user solve of the store's constraints on {@code g}, after every change of a constraint, template or drag step:
     * {@link ConstraintConflicts} settles contradictions by priority (a constraint that is not applied gets its reason
     * in the {@link ConflictLog}), then cola projects the drawing onto the applied constraints. Compartments (as free
     * rectangles the non-overlap would push their members out of them) and the glyphs inside a complex (they follow
     * their complex) are not solved; edge routes are left alone.
     *
     * @param withEdges     a constrained force-directed run along the edges before the projection
     * @param avoidOverlaps the projection also pushes overlapping nodes apart (libcola's non-overlap)
     */
    public static void executeUserConstraints(Graph g, boolean withEdges, boolean avoidOverlaps) {
        if (g == null || ConstraintStore.constraints == null) return;
        solveUserConstraints(g, ConstraintStore.constraints.getAllConstraints(), withEdges, avoidOverlaps, true);
    }

    // ------------------------------------------------------------------ phases of a user solve

    /** -Dcblayout.dragTiming=true|false (default false): print the time of every phase of a user solve. */
    public static final boolean DRAG_TIMING = Boolean.getBoolean("cblayout.dragTiming");
    /** The phases of {@link #solveUserConstraints}, in order; {@link #phaseNanos} holds the time of each for the last solve. */
    public static final String[] PHASES = { "rects", "decide", "translate", "build", "run", "clusters", "project", "apply", "check", "project2", "report" };
    /** Nanoseconds per phase of the last user solve (filled always; printed with dragTiming). */
    public static final long[] phaseNanos = new long[PHASES.length];
    /** Nanoseconds of the whole last user solve. */
    public static long solveNanos;
    private static long phaseStart;

    private static void phase(int i) {
        long now = System.nanoTime();
        phaseNanos[i] = now - phaseStart;
        phaseStart = now;
    }

    /**
     * The user solve of {@link #executeUserConstraints(Graph, boolean, boolean)} for an explicit set of constraints, as
     * the template editor solves its pattern graph without touching the store.
     *
     * @param report record the results in the {@link ConflictLog} and refresh the overlay; false leaves both alone
     * @return the constraints of {@code cs} that do not hold after the solve, with the reason
     */
    public static Map<Constraint, String> solveUserConstraints(Graph g, Collection<? extends Constraint> cs,
                                                               boolean withEdges, boolean avoidOverlaps, boolean report) {
        return solveUserConstraints(g, cs, withEdges, avoidOverlaps, report, null);
    }

    /**
     * -Dcblayout.dragSolve=component|all (default component): component: a drag step without force-directed run and
     * overlap removal solves only the constraint components of the dragged nodes (same result, nothing links the other
     * nodes to them); all: every drag step solves the whole graph.
     */
    public static final boolean DRAG_SOLVE_COMPONENT = !"all".equalsIgnoreCase(System.getProperty("cblayout.dragSolve", "component"));

    /**
     * -Dcblayout.dragPin=true|false (default false): during a drag the dragged nodes get a pin at the cursor of the
     * lowest rank, which holds wherever no higher-ranked constraint contradicts it; the pin is not in the store.
     */
    public static final boolean DRAG_PIN = Boolean.getBoolean("cblayout.dragPin");

    /**
     * The user solve after a drag step of {@code moved}. With the force-directed run and the overlap removal off only
     * the constraint components of the moved nodes are solved (a cluster among them makes it the whole graph), else the
     * whole graph as {@link #executeUserConstraints}.
     */
    public static void executeUserConstraintsAround(Graph g, Collection<Node> moved, boolean withEdges, boolean avoidOverlaps) {
        if (g == null || ConstraintStore.constraints == null) return;
        Collection<Constraint> all = ConstraintStore.constraints.getAllConstraints();
        if (DRAG_PIN && moved != null && !moved.isEmpty()) {
            Set<Node> solvable = userSolveNodes(g);
            Map<Node, double[]> at = new LinkedHashMap<>();
            for (Node n : moved) if (solvable.contains(n)) at.put(n, new double[]{ AttributeHelper.getPositionX(n), AttributeHelper.getPositionY(n) });
            if (!at.isEmpty()) {
                LockConstraintGraph pin = new LockConstraintGraph(at);
                pin.setPriority(Priority.VERY_LOW);
                pin.setAutomatic(true);   // the lowest rank: below every constraint of the user and of the steps
                List<Constraint> withPin = new ArrayList<>(all);
                withPin.add(pin);
                all = withPin;
            }
        }
        boolean anyCluster = false;   // the sides of a cluster concern every node, so the whole graph is solved
        for (Constraint c : all) anyCluster |= c instanceof ClusterConstraintGraph && c.isEnabled();
        if (!DRAG_SOLVE_COMPONENT || withEdges || avoidOverlaps || moved == null || moved.isEmpty() || anyCluster) {
            solveUserConstraints(g, all, withEdges, avoidOverlaps, true);
            return;
        }
        Set<Node> solvable = userSolveNodes(g);
        Map<Node, Node> parent = new HashMap<>();
        for (Constraint c : all) {
            if (!c.isEnabled()) continue;
            Node first = null;
            for (Node n : c.getNodes()) {
                if (!solvable.contains(n)) continue;
                if (first == null) first = n;
                else union(parent, first, n);
            }
        }
        Set<Node> roots = new HashSet<>();
        for (Node n : moved) if (solvable.contains(n)) roots.add(find(parent, n));
        if (roots.isEmpty()) {
            solveUserConstraints(g, all, withEdges, avoidOverlaps, true);
            return;
        }
        List<Node> component = new ArrayList<>();
        for (Node n : solvable) if (roots.contains(find(parent, n))) component.add(n);
        if (component.size() == solvable.size()) {   // the whole graph anyway
            solveUserConstraints(g, all, withEdges, avoidOverlaps, true);
            return;
        }
        Set<Node> inside = new HashSet<>(component);
        List<Constraint> cs = new ArrayList<>();
        for (Constraint c : all) {
            boolean in = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) in &= inside.contains(n);
            if (!in) continue;
            if (c instanceof ClusterConstraintGraph && c.isEnabled()) {   // its sides concern every other node
                solveUserConstraints(g, all, withEdges, avoidOverlaps, true);
                return;
            }
            cs.add(c);
        }
        solveUserConstraints(g, cs, withEdges, avoidOverlaps, true, component);
    }

    private static Node find(Map<Node, Node> parent, Node n) {
        Node r = n;
        while (parent.containsKey(r)) r = parent.get(r);
        while (n != r) {   // path compression
            Node next = parent.get(n);
            parent.put(n, r);
            n = next;
        }
        return r;
    }

    private static void union(Map<Node, Node> parent, Node a, Node b) {
        Node ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(rb, ra);
    }

    /**
     * {@link #solveUserConstraints(Graph, Collection, boolean, boolean, boolean)} over the nodes {@code only} (null:
     * every node the user solve moves); the decisions and conflict entries of constraints outside them stay as they are.
     */
    public static Map<Constraint, String> solveUserConstraints(Graph g, Collection<? extends Constraint> cs,
                                                               boolean withEdges, boolean avoidOverlaps, boolean report,
                                                               Collection<Node> only) {
        Map<Constraint, String> result = new LinkedHashMap<>();
        if (g == null || cs == null) return result;
        long t0 = System.nanoTime();
        phaseStart = t0;
        java.util.Arrays.fill(phaseNanos, 0L);
        if (report) {
            if (only == null) ConflictLog.clearUnsatisfied();
            else ConflictLog.clearUnsatisfied(cs);
        }
        Map<Node, Node> complexOf = complexChildren(g);
        nodeIndex = new HashMap<>();
        rectPtrVector = new RectanglePtrs();
        List<Node> solved = new ArrayList<>();
        Set<Node> onlySet = only == null ? null : new HashSet<>(only);
        for (Node n : ConstraintSBGNLayout.layoutNodes(g)) {
            if (ConstraintSBGNLayout.isCompartment(n) || complexOf.containsKey(n)) continue;
            if (onlySet != null && !onlySet.contains(n)) continue;
            nodeIndex.put(n, nodeIndex.size());
            rectPtrVector.add(defineRectangle(n));
            solved.add(n);
        }
        ColaEdges edges = new ColaEdges();
        if (withEdges) {
            for (Edge e : ConstraintSBGNLayout.layoutEdges(g)) {
                Integer a = nodeIndex.get(e.getSource()), b = nodeIndex.get(e.getTarget());
                if (a != null && b != null && !a.equals(b)) edges.add(new ColaEdge(a.longValue(), b.longValue()));
            }
        }

        phase(0);
        // Which constraints are applied: contradictions are settled by priority before cola sees them.
        ConstraintConflicts.Scope scope = new ConstraintConflicts.Scope(solved, only == null ? g : null, rectPad(avoidOverlaps), withEdges, avoidOverlaps);
        ConstraintConflicts.resolve(cs, scope);
        if (report) {
            if (only == null) ConflictLog.setDeactivated(cs);
            else ConflictLog.updateDeactivated(cs);
            // a constraint on a node the solve does not move (a compartment, a glyph inside a complex) is not solved:
            // report it instead of leaving it silently where it is
            if (only == null) {
                for (Constraint c : cs) {
                    if (c.isAutomatic() || !c.isEnabled() || c.getNodes().isEmpty()) continue;
                    Node outside = null;
                    boolean inGraph = true;
                    for (Node n : c.getNodes()) {
                        if (!g.containsNode(n)) { inGraph = false; break; }
                        if (!nodeIndex.containsKey(n) && outside == null) outside = n;
                    }
                    if (!inGraph || outside == null) continue;
                    ConflictLog.addUnsatisfied(c, "Manual", "not solved: '" + Deactivation.label(outside) + "' is "
                            + (ConstraintSBGNLayout.isCompartment(outside) ? "a compartment" : "inside a complex")
                            + ", which the solve does not move");
                }
            }
        }
        phase(1);

        // The applied constraints for cola: alignments and separations first, then every other type in store order.
        UserConstraintTranslator tr = new UserConstraintTranslator(nodeIndex, rectPtrVector, withEdges);
        List<Constraint> ordered = new ArrayList<>();
        for (Constraint c : cs) if (c instanceof HorizontalConstraint) ordered.add(c);
        for (Constraint c : cs) if (c instanceof VerticalConstraint) ordered.add(c);
        for (Constraint c : cs) if (c instanceof SeparationConstraintGraph) ordered.add(c);
        for (Constraint c : cs) {
            if (!(c instanceof HorizontalConstraint || c instanceof VerticalConstraint || c instanceof SeparationConstraintGraph))
                ordered.add(c);
        }
        ordered.removeIf(c -> !c.isActive());   // before prepare(): a pin that is not applied must not move its node
        // Higher priority last: makeFeasible places the constraints it was given last first. The sort is stable.
        ordered.sort(Comparator.comparing(Constraint::getPriority));
        tr.prepare(ordered);
        for (Constraint c : ordered) tr.add(c);
        CompoundConstraintPtrs ccs = tr.runConstraints;
        constraints = ccs;
        userSolve = tr;   // cola keeps pointers to these objects while the layout lives
        phase(2);

        if (tr.locks.isEmpty()) {
            constraintFDLayout = new ConstrainedFDLayout(rectPtrVector, edges, dist);
        } else {
            userPreIteration = new PreIteration(tr.locks);
            userConvergence = new TestConvergence();   // cola calls it in run(); the proxy must outlive the layout
            constraintFDLayout = new ConstrainedFDLayout(rectPtrVector, edges, dist, new Doubles(), userConvergence, userPreIteration);
        }
        armUnsatisfiable(constraintFDLayout);
        phase(3);
        try {
            if (ccs.size() > 0) constraintFDLayout.setConstraints(ccs);
            if (withEdges && !tr.desired.isEmpty()) constraintFDLayout.setDesiredPositions(tr.desired);
            if (withEdges) constraintFDLayout.run();
            phase(4);
            // A user cluster is dividing lines on the sides the resolution chose, so it ranks by its priority; libcola's
            // clusters keep other nodes out only through the non-overlap and add their containment unchecked.
            if (tr.hasClusters) tr.excludeFromClusters(scope.clusterSides, scope.rectPad);
            // The projection: libcola's makeFeasible skips some types, their stand-ins replace them (see the translator).
            if (tr.separateFinalList) constraintFDLayout.setConstraints(tr.finalConstraints);
            phase(5);
            if (avoidOverlaps) constraintFDLayout.setAvoidNodeOverlaps(true);
            constraintFDLayout.makeFeasible();
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        phase(6);
        if (report) collectUnsatisfied("Manual", tr);
        applyRectangles(complexOf);
        phase(7);
        // makeFeasible adds the constraints one at a time and can stop short of a point that meets them all; projecting
        // once more from where it stopped mostly reaches it.
        Map<Constraint, String> unmetNow = unmet(tr);
        phase(8);
        if (!unmetNow.isEmpty()) {
            try {
                constraintFDLayout.makeFeasible();
                applyRectangles(complexOf);
            } catch (Exception e) {
                ErrorMsg.addErrorMessage(e);
            }
            unmetNow = unmet(tr);
        }
        phase(9);
        // makeFeasible drops the parts of constraints it cannot satisfy without a report: list the ones that do not hold.
        for (Map.Entry<Constraint, String> en : unmetNow.entrySet()) {
            if (report) ConflictLog.addUnsatisfied(en.getKey(), "Manual", "not satisfied: " + en.getValue());
            result.put(en.getKey(), en.getValue());
        }
        if (report) ConstraintStore.onOverlayChanged.run();
        phase(10);
        solveNanos = System.nanoTime() - t0;
        if (DRAG_TIMING) {
            StringBuilder sb = new StringBuilder("[cblayout] user solve ").append(String.format(java.util.Locale.ROOT, "%.2f ms", solveNanos / 1e6))
                    .append(" (").append(solved.size()).append(" nodes, ").append(ordered.size()).append(" constraints applied)");
            for (int i = 0; i < PHASES.length; i++) sb.append(' ').append(PHASES[i]).append('=').append(String.format(java.util.Locale.ROOT, "%.2f", phaseNanos[i] / 1e6));
            System.out.println(sb);
        }
        return result;
    }

    /** The user's constraints of the solve that do not hold in the drawing, with the reason. */
    private static Map<Constraint, String> unmet(UserConstraintTranslator tr) {
        Map<Constraint, String> l = new LinkedHashMap<>();
        for (Constraint c : tr.translated) {
            if (c.isAutomatic()) continue;
            String why = ConstraintCheck.violation(c, nodeIndex.keySet(), ConstraintCheck.TOLERANCE);
            if (why != null) l.put(c, why);
        }
        return l;
    }

    /** Moves the solved nodes to their rectangles; the glyphs inside a complex move with it. */
    private static void applyRectangles(Map<Node, Node> complexOf) {
        Map<Node, double[]> shift = new HashMap<>();
        for (Map.Entry<Node, Integer> en : nodeIndex.entrySet()) {
            Rectangle r = rectPtrVector.get(en.getValue());
            Node n = en.getKey();
            double dx = r.getCentreX() - AttributeHelper.getPositionX(n), dy = r.getCentreY() - AttributeHelper.getPositionY(n);
            if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) continue;   // no attribute event for a node that did not move
            AttributeHelper.setPosition(n, r.getCentreX(), r.getCentreY());
            shift.put(n, new double[]{dx, dy});
        }
        for (Map.Entry<Node, Node> en : complexOf.entrySet()) {
            double[] d = shift.get(en.getValue());
            if (d == null) continue;
            Node child = en.getKey();
            AttributeHelper.setPosition(child, AttributeHelper.getPositionX(child) + d[0], AttributeHelper.getPositionY(child) + d[1]);
        }
    }

    /** Keeps the cola objects of the last user-constraint solve alive (the layout holds pointers to them). */
    private static UserConstraintTranslator userSolve;
    private static PreIteration userPreIteration;
    private static TestConvergence userConvergence;

    /**
     * Solver reports of the last run, traced back to the model constraints by the native pointer of the cola object
     * (the report carries it). Only the automatic constraints are reported this way; the user's constraints are
     * measured in the result instead, since the final projection may still satisfy what the run could not.
     */
    private static void collectUnsatisfied(String phase, UserConstraintTranslator tr) {
        if (unsatX == null || unsatY == null) return;
        Set<Constraint> reported = new HashSet<>();
        for (int dim = 0; dim < 2; dim++) {
            UnsatisfiableConstraintInfoPtrs infos = dim == 0 ? unsatX : unsatY;
            for (int i = 0; i < infos.size(); i++) {
                Constraint c = tr.ownerOf(infos.get(i).getCc());
                if (c == null || !c.isAutomatic() || !reported.add(c)) continue;
                ConflictLog.addUnsatisfied(c, phase, "the solver could not satisfy it (" + (dim == 0 ? "x" : "y")
                        + "); it contradicts other constraints");
            }
        }
    }

    /**
     * One solver call over {@code nodes} only, every other node left out as if it were not in the graph; used by the
     * ER and AF pipelines and by step algorithms that solve a subset of nodes. Of {@code cs} only the constraints whose
     * nodes are all listed are handed to cola. {@code lengths}: an ideal length factor per edge, parallel to
     * {@code edges} (null: all 1); a skipped duplicate pair keeps the factor of its first occurrence.
     */
    public static void solveNodes(List<Node> nodes, Map<Node, double[]> boxes, List<Node[]> edges, List<Double> lengths,
                           Collection<Constraint> cs, boolean fd) {
        solveNodes(nodes, boxes, edges, lengths, cs, fd, -1);
    }

    /**
     * {@link #solveNodes(List, Map, List, List, Collection, boolean)} with a run guarded by {@link MonotoneConvergence}
     * ({@code patience >= 0}): it goes on for up to {@code patience} iterations in a row that do not lower the stress
     * and ends on the best iterate. {@code patience < 0}: libcola's own test, which stops on the first such iterate.
     */
    public static void solveNodes(List<Node> nodes, Map<Node, double[]> boxes, List<Node[]> edges, List<Double> lengths,
                           Collection<Constraint> cs, boolean fd, int patience) {
        solveNodes(nodes, boxes, edges, lengths, cs, fd, patience, false);
    }

    /**
     * {@link #solveNodes(List, Map, List, List, Collection, boolean, int)}; with {@code overlapsInRun} the
     * force-directed run itself keeps the nodes from overlapping instead of only the projection afterwards. There are no
     * solver reports then (see {@link #armUnsatisfiable}).
     */
    public static void solveNodes(List<Node> nodes, Map<Node, double[]> boxes, List<Node[]> edges, List<Double> lengths,
                           Collection<Constraint> cs, boolean fd, int patience, boolean overlapsInRun) {
        ConflictLog.clearUnsatisfied();
        nodeIndex = new HashMap<>();
        rectPtrVector = new RectanglePtrs();
        for (Node n : nodes) {
            if (nodeIndex.containsKey(n)) continue;
            nodeIndex.put(n, nodeIndex.size());
            double[] box = boxes == null ? null : boxes.get(n);
            if (box == null) {
                rectPtrVector.add(defineRectangle(n));
            } else {
                double x = AttributeHelper.getPositionX(n), y = AttributeHelper.getPositionY(n);
                rectPtrVector.add(new Rectangle(x - box[0] / 2 - bboxConst, x + box[0] / 2 + bboxConst,
                        y - box[1] / 2 - bboxConst, y + box[1] / 2 + bboxConst));
            }
        }
        ColaEdges colaEdges = new ColaEdges();
        Doubles colaLengths = new Doubles();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < edges.size(); i++) {
            Node[] e = edges.get(i);
            Integer a = nodeIndex.get(e[0]), b = nodeIndex.get(e[1]);
            if (a == null || b == null || a.equals(b)) continue;
            if (!seen.add((long) Math.min(a, b) << 32 | Math.max(a, b))) continue;
            colaEdges.add(new ColaEdge(a, b));
            colaLengths.add(lengths == null ? 1.0 : lengths.get(i));
        }
        Set<Node> known = nodeIndex.keySet();
        CompoundConstraintPtrs ccs = new CompoundConstraintPtrs();
        for (Constraint c : cs) {
            if (!c.isActive() || !known.containsAll(c.getNodes())) continue;
            if (c instanceof HorizontalConstraint) {
                ccs.add(alignmentOf((HorizontalConstraint) c));
            } else if (c instanceof VerticalConstraint) {
                ccs.add(alignmentOf((VerticalConstraint) c));
            } else if (c instanceof SeparationConstraintGraph && c.getNodes().size() == 2) {
                ccs.add(separationOf((SeparationConstraintGraph) c));
            }
        }
        constraints = ccs;
        // sbgnUserTypes=all: the user's other kinds whose nodes are all in the solve, equal spacing included (these
        // solves have no translation of their own)
        UserConstraintTranslator extras = sbgnUserExtras(nodeIndex, rectPtrVector, fd && colaEdges.size() > 0, true);
        MonotoneConvergence guard = patience >= 0 ? new MonotoneConvergence(rectPtrVector, 1e-4, 100, patience) : null;
        solveGuard = guard;   // cola calls it during run(): it has to outlive the layout
        if (guard != null) {
            constraintFDLayout = new ConstrainedFDLayout(rectPtrVector, colaEdges, dist, lengths == null ? new Doubles() : colaLengths, guard);
        } else {
            constraintFDLayout = lengths == null ? new ConstrainedFDLayout(rectPtrVector, colaEdges, dist)
                                                 : new ConstrainedFDLayout(rectPtrVector, colaEdges, dist, colaLengths);
        }
        if (extras != null) constraintFDLayout = withLocks(constraintFDLayout, extras, rectPtrVector, colaEdges, lengths == null ? null : colaLengths, guard);
        if (overlapsInRun) {
            unsatX = null;   // as with -Dcblayout.fdOverlap=run: cola would report its own temporary constraints
            unsatY = null;
        } else {
            armUnsatisfiable(constraintFDLayout);
        }
        try {
            CompoundConstraintPtrs forRun = withExtrasForRun(ccs, extras);
            if (forRun.size() > 0) constraintFDLayout.setConstraints(forRun);
            if (extras != null && !extras.desired.isEmpty()) constraintFDLayout.setDesiredPositions(extras.desired);
            if (fd && colaEdges.size() > 0) {
                if (overlapsInRun) constraintFDLayout.setAvoidNodeOverlaps(true);
                constraintFDLayout.run();
                if (guard != null) {
                    guard.finish();
                    warnIfDirectorInactive(guard);
                }
            }
            if (extras != null && extras.separateFinalList) constraintFDLayout.setConstraints(withExtrasForProjection(ccs, extras));
            constraintFDLayout.setAvoidNodeOverlaps(true);
            constraintFDLayout.makeFeasible();
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
        }
        collectUnsatisfied("Solver", nodeIndex);
        Runnable write = () -> {
            for (Map.Entry<Node, Integer> en : nodeIndex.entrySet()) {
                Rectangle r = rectPtrVector.get(en.getValue());
                AttributeHelper.setPosition(en.getKey(), r.getCentreX(), r.getCentreY());
            }
        };
        write.run();
        projectAgainIfExtrasUnmet(extras, constraintFDLayout, write);
    }

    /** The scope in which {@link #executeUserConstraints} resolves the constraints of {@code g} ("Prefer this" uses it). */
    public static ConstraintConflicts.Scope userScope(Graph g, boolean withEdges, boolean avoidOverlaps) {
        return new ConstraintConflicts.Scope(userSolveNodes(g), g, rectPad(avoidOverlaps), withEdges, avoidOverlaps);
    }

    /**
     * How much larger than its node a rectangle counts per side when constraints keep nodes apart: the padding
     * ({@link #bboxConst}), and with overlap removal on also the 1 px border libcola's non-overlap adds (makeFeasible's
     * default), so the lines around a cluster leave its non-overlap nothing to push.
     */
    private static double rectPad(boolean avoidOverlaps) {
        return bboxConst + (avoidOverlaps ? 1 : 0);
    }

    // ------------------------------------------------------------------ sbgnUserTypes=all: the user's other kinds in the SBGN solves

    /**
     * {@link #USER_TYPES_ALL}: the user's active constraints of the kinds the SBGN solves do not translate themselves,
     * translated as in the user solve over {@code index} and {@code rects}, or null if there are none (the solve then
     * stays unchanged). {@code withDistributions}: equal spacing is among them (the ER/AF solves have no translation of
     * their own). Pinned and rigid nodes are moved first; the cluster sides come from {@link #lastUserScope}.
     */
    private static UserConstraintTranslator sbgnUserExtras(Map<Node, Integer> index, RectanglePtrs rects, boolean forceDirected,
                                                           boolean withDistributions) {
        sbgnExtras = null;
        if (!USER_TYPES_ALL || ConstraintStore.constraints == null) return null;
        List<Constraint> extra = new ArrayList<>();
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (c.isAutomatic() || !c.isActive()) continue;
            if (c instanceof HorizontalConstraint || c instanceof VerticalConstraint || c instanceof SeparationConstraintGraph) continue;
            if (!withDistributions && c instanceof DistributionConstraintGraph) continue;
            boolean known = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) known &= index.containsKey(n);
            if (known) extra.add(c);
        }
        if (extra.isEmpty()) return null;
        extra.sort(Comparator.comparing(Constraint::getPriority));   // as in the user solve: higher priority last
        UserConstraintTranslator tr = new UserConstraintTranslator(index, rects, forceDirected);
        tr.prepare(extra);
        for (Constraint c : extra) tr.add(c);
        if (tr.hasClusters && lastUserScope != null) tr.excludeFromClusters(lastUserScope.clusterSides, bboxConst + 1);
        sbgnExtras = tr;
        return tr;
    }

    /**
     * sbgnUserTypes=all: whether an active user constraint with absolute positions (a pin, an area, a target) lies in
     * {@code g}; the steps then skip what would move the drawing after the last solve (packing, the move to the origin).
     */
    public static boolean absoluteUserConstraintsOn(Graph g) {
        if (!USER_TYPES_ALL || ConstraintStore.constraints == null || g == null) return false;
        for (Constraint c : ConstraintStore.constraints.getAllConstraints()) {
            if (c.isAutomatic() || !c.isActive()) continue;
            if (!(c instanceof constraints.LockConstraintGraph || c instanceof constraints.PageBoundaryConstraintGraph
                    || c instanceof constraints.DesiredPositionConstraintGraph)) continue;
            boolean inGraph = !c.getNodes().isEmpty();
            for (Node n : c.getNodes()) inGraph &= g.containsNode(n);
            if (inGraph) return true;
        }
        return false;
    }

    /**
     * sbgnUserTypes=all, after the projection of an SBGN solve whose positions have been written: projects once more,
     * as the user solve does, if a translated user constraint does not hold, and then writes the positions again.
     */
    private static void projectAgainIfExtrasUnmet(UserConstraintTranslator extras, ConstrainedFDLayout fd, Runnable writePositions) {
        if (extras == null) return;
        boolean unmet = false;
        for (Constraint c : extras.translated) {
            if (ConstraintCheck.violation(c, nodeIndex.keySet(), ConstraintCheck.TOLERANCE) != null) { unmet = true; break; }
        }
        if (!unmet) return;
        try {
            fd.makeFeasible();
        } catch (Exception e) {
            ErrorMsg.addErrorMessage(e);
            return;
        }
        writePositions.run();
    }

    /** The constraints of {@code base} plus the extras' list for the run (null extras: {@code base} itself). */
    private static CompoundConstraintPtrs withExtrasForRun(CompoundConstraintPtrs base, UserConstraintTranslator extras) {
        if (extras == null) return base;
        CompoundConstraintPtrs l = new CompoundConstraintPtrs();
        for (int i = 0; i < base.size(); i++) l.add(base.get(i));
        for (int i = 0; i < extras.runConstraints.size(); i++) l.add(extras.runConstraints.get(i));
        return l;
    }

    /** The constraints of {@code base} plus the extras' list for the final projection (the stand-ins of makeFeasible). */
    private static CompoundConstraintPtrs withExtrasForProjection(CompoundConstraintPtrs base, UserConstraintTranslator extras) {
        if (extras == null) return base;
        CompoundConstraintPtrs l = new CompoundConstraintPtrs();
        for (int i = 0; i < base.size(); i++) l.add(base.get(i));
        CompoundConstraintPtrs e = extras.separateFinalList ? extras.finalConstraints : extras.runConstraints;
        for (int i = 0; i < e.size(); i++) l.add(e.get(i));
        return l;
    }

    /**
     * The layout object of an SBGN solve with the user's pins as locks ({@code PreIteration}) when the extras have
     * any, else {@code plain}. A layout with locks needs the full constructor; {@code lengths} and {@code done} are
     * what the plain one used (null: the defaults, as the three-argument constructor takes them).
     */
    private static ConstrainedFDLayout withLocks(ConstrainedFDLayout plain, UserConstraintTranslator extras, RectanglePtrs rects,
                                                 ColaEdges edges, Doubles lengths, TestConvergence done) {
        if (extras == null || extras.locks.isEmpty()) return plain;
        sbgnExtrasPre = new PreIteration(extras.locks);
        sbgnExtrasConvergence = done != null ? done : new TestConvergence();
        return new ConstrainedFDLayout(rects, edges, dist, lengths != null ? lengths : new Doubles(), sbgnExtrasConvergence, sbgnExtrasPre);
    }

    /**
     * The nodes {@link #executeUserConstraints} moves: every layout node except compartments and the glyphs drawn
     * inside a complex (they follow their complex). A user constraint on any other node is not solved.
     */
    public static Set<Node> userSolveNodes(Graph g) {
        Set<Node> nodes = new LinkedHashSet<>();
        if (g == null) return nodes;
        Map<Node, Node> complexOf = complexChildren(g);
        for (Node n : ConstraintSBGNLayout.layoutNodes(g)) {
            if (ConstraintSBGNLayout.isCompartment(n) || complexOf.containsKey(n)) continue;
            nodes.add(n);
        }
        return nodes;
    }

    /**
     * Glyph drawn inside a complex -> the outermost complex that contains it (the one the solver moves). A complex
     * inside another complex is a child as well; compartments never are.
     */
    static Map<Node, Node> complexChildren(Graph g) {
        List<Node> complexes = new ArrayList<>();
        for (Node n : g.getNodes()) {
            String role = ConstraintSBGNLayout.getSBGNRole(n);
            if (role != null && role.toUpperCase().contains("COMPLEX")) complexes.add(n);
        }
        Map<Node, Node> complexOf = new HashMap<>();
        if (complexes.isEmpty()) return complexOf;
        for (Node n : g.getNodes()) {
            if (ConstraintSBGNLayout.isCompartment(n)) continue;
            Node outer = null;
            for (Node c : complexes) {
                if (c == n || !ConstraintSBGNLayout.isContained(c, n)) continue;
                if (outer == null || AttributeHelper.getWidth(c) * AttributeHelper.getHeight(c)
                        > AttributeHelper.getWidth(outer) * AttributeHelper.getHeight(outer)) outer = c;
            }
            if (outer != null) complexOf.put(n, outer);
        }
        return complexOf;
    }

    /** The cola alignment of {@code c}: top or bottom edges, else centres, on one line. */
    private static AlignmentConstraint alignmentOf(HorizontalConstraint c) {
        if (c.getType() == HorizontalConstraint.HorizontalConstraintType.TOP) return createAlignTop(c.getNodes());
        if (c.getType() == HorizontalConstraint.HorizontalConstraintType.BOTTOM) return createAlignBottom(c.getNodes());
        return createAlignMiddle(c.getNodes());
    }

    /** The cola alignment of {@code c}: right or left edges, else centres, on one line. */
    private static AlignmentConstraint alignmentOf(VerticalConstraint c) {
        if (c.getType() == VerticalConstraint.VerticalConstraintType.RIGHT) return createAlignRight(c.getNodes());
        if (c.getType() == VerticalConstraint.VerticalConstraintType.LEFT) return createAlignLeft(c.getNodes());
        return createAlignCenter(c.getNodes());
    }

    /** The cola separation of the two nodes of {@code c}. */
    private static SeparationConstraint separationOf(SeparationConstraintGraph c) {
        if (c.getType() == SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL)
            return createSeparationX(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1), c.isFixed());
        return createSeparationY(c.getDistance(), c.getNodes().get(0), c.getNodes().get(1), c.isFixed());
    }

    // cola's AlignmentConstraint: guideline + offset == node centre. Top edges on the line: offset = +height / 2,
    // bottom edges: -height / 2; left edges (x): +width / 2, right edges: -width / 2.

    /** Same y for the top edges. */
    static AlignmentConstraint createAlignTop(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.YDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), AttributeHelper.getHeight(n) / 2);
        return alignmentConstraint;
    }

    /** Same y for the centres. */
    static AlignmentConstraint createAlignMiddle(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.YDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), 0);
        return alignmentConstraint;
    }

    /** Same y for the bottom edges. */
    static AlignmentConstraint createAlignBottom(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.YDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), -AttributeHelper.getHeight(n) / 2);
        return alignmentConstraint;
    }

    /** Same x for the left edges. */
    static AlignmentConstraint createAlignLeft(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.XDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), AttributeHelper.getWidth(n) / 2);
        return alignmentConstraint;
    }

    /** Same x for the centres. */
    static AlignmentConstraint createAlignCenter(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.XDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), 0);
        return alignmentConstraint;
    }

    /** Same x for the right edges. */
    static AlignmentConstraint createAlignRight(List<Node> nodes) {
        AlignmentConstraint alignmentConstraint = new AlignmentConstraint(org.adaptagrams.Dim.XDIM);
        for (Node n : nodes) alignmentConstraint.addShape(nodeIndex.get(n), -AttributeHelper.getWidth(n) / 2);
        return alignmentConstraint;
    }

    /** {@code rightNode}'s centre at least (exactly, if {@code fixed}) {@code distance} right of {@code leftNode}'s. */
    public static SeparationConstraint createSeparationX(double distance, Node leftNode, Node rightNode, boolean fixed) {
        return new SeparationConstraint(org.adaptagrams.Dim.XDIM, nodeIndex.get(leftNode), nodeIndex.get(rightNode), distance, fixed);
    }

    /** {@code bottomNode}'s centre at least (exactly, if {@code fixed}) {@code distance} below {@code topNode}'s. */
    public static SeparationConstraint createSeparationY(double distance, Node topNode, Node bottomNode, boolean fixed) {
        return new SeparationConstraint(org.adaptagrams.Dim.YDIM, nodeIndex.get(topNode), nodeIndex.get(bottomNode), distance, fixed);
    }

    /** The solver rectangle of {@code n}: its bounding box, {@link #bboxConst} larger per side. */
    private static Rectangle defineRectangle(Node n) {
        Vector2d pos  = AttributeHelper.getPositionVec2d(n);
        Vector2d size = AttributeHelper.getSize(n);
        double left   = pos.x - size.x/2 - bboxConst;
        double right  = pos.x + size.x/2 + bboxConst;
        double top    = pos.y - size.y/2 - bboxConst;
        double bottom = pos.y + size.y/2 + bboxConst;
        return new Rectangle(left, right, top, bottom);
    }
}
