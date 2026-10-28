package layout.sbgn;

import adaptagrams_bridge.CBLayoutAlgorithm;
import constraints.ClusterConstraintGraph;
import constraints.ConflictLog;
import constraints.Constraint;
import constraints.ConstraintCheck;
import constraints.ConstraintStore;
import constraints.Constraints;
import constraints.FixedRelativeConstraintGraph;
import constraints.LinePattern;
import constraints.LockConstraintGraph;
import constraints.PageBoundaryConstraintGraph;

import de.ipk_gatersleben.ag_nw.graffiti.plugins.gui.ipk_graffitiview.IPKGraffitiView;
import org.AttributeHelper;
import org.adaptagrams.NativeLoader;
import org.graffiti.graph.AdjListGraph;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.managers.AttributeComponentManager;
import org.graffiti.plugin.view.View2D;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sbgn.Language;
import org.sbgn.SbgnUtil;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNHelper;
import org.sbgned.SBGNToolsTab;
import org.sbgned.sbgnml.SBGNMLReader;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The solves of the SBGN layouts apply every kind of user constraint ({@code -Dcblayout.sbgnUserTypes=all}, the
 * default): a pin, an area, a rigid group, a row or a cluster set before the run holds after it, on a PD, an ER and an
 * AF map, and is listed nowhere; constraints the steps cannot reconcile are listed as not satisfied. Needs the
 * Adaptagrams natives; run with {@code ant test}.
 */
public class SbgnUserTypesTest {

    @BeforeClass
    public static void natives() throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("com.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize", "true");
        NativeLoader.ensureLoaded();
        new SBGNToolsTab();
        assertTrue("the test needs -Dcblayout.sbgnUserTypes=all (the default)", CBLayoutAlgorithm.USER_TYPES_ALL);
    }

    static Graph load(String file) throws Exception {
        Sbgn sbgn = SbgnUtil.readFromFile(new File(file));
        Graph g = new AdjListGraph();
        SBGNMLReader.createGraph(sbgn, g);
        return g;
    }

    /** The nodes of a role, in a stable order. */
    static List<Node> ofRole(Graph g, String role) {
        List<Node> l = new ArrayList<>();
        for (Node n : g.getNodes()) if (SBGNHelper.getSBGNRole(n).toUpperCase(Locale.ROOT).equals(role)) l.add(n);
        l.sort((a, b) -> Long.compare(a.getID(), b.getID()));
        return l;
    }

    static void fresh() {
        ConstraintStore.constraints = new Constraints();
        ConflictLog.clear();
        CBLayoutAlgorithm.clusterMap = new HashMap<>();
        ConstraintSBGNLayout.view2D = null;
    }

    static void holds(Constraint c, Graph g, String what) {
        String why = ConstraintCheck.violation(c, g.getNodes(), ConstraintCheck.TOLERANCE);
        assertNull(what + " does not hold after the run: " + why, why);
        for (ConflictLog.Entry e : ConflictLog.entries()) assertTrue(what + " is listed: " + e.reason, e.constraint != c);
    }

    /** One PD run of glycolysis with the constraint {@code make} builds; it must hold afterwards and be listed nowhere. */
    static void pd(BiFunction<List<Node>, List<Node>, Constraint> make, String what) throws Exception {
        Graph g = load("Files/glycolysis.sbgn");
        fresh();
        List<Node> mm = ofRole(g, "MACROMOLECULE"), sc = ofRole(g, "SIMPLECHEMICAL");
        Constraint c = make.apply(mm, sc);
        ConstraintStore.constraints.addConstraint(c);
        new ConstraintSBGNLayout(g, Language.PD).runAll();
        holds(c, g, what);
    }

    /** A pin of the node {@code n} at ({@code x}, {@code y}). */
    static LockConstraintGraph pin(Node n, double x, double y) {
        Map<Node, double[]> at = new HashMap<>();
        at.put(n, new double[]{ x, y });
        return new LockConstraintGraph(at);
    }

    @Test
    public void pdAppliesPin() throws Exception {
        pd((mm, sc) -> pin(mm.get(0), 900, 700), "pin");
    }

    @Test
    public void pdAppliesRigidGroup() throws Exception {
        pd((mm, sc) -> {
            AttributeHelper.setPosition(mm.get(2), AttributeHelper.getPositionX(mm.get(1)) + 150, AttributeHelper.getPositionY(mm.get(1)) + 40);
            return new FixedRelativeConstraintGraph(new ArrayList<>(Arrays.asList(mm.get(1), mm.get(2))), false);
        }, "rigid group");
    }

    @Test
    public void pdAppliesRow() throws Exception {
        pd((mm, sc) -> new LinePattern(Arrays.asList(mm.get(5), mm.get(6), mm.get(7)), true, 90, false), "row");
    }

    @Test
    public void pdAppliesArea() throws Exception {
        pd((mm, sc) -> new PageBoundaryConstraintGraph(Arrays.asList(mm.get(3), mm.get(4)), new double[]{ 0, 0, 900, 700 }, 0), "area");
    }

    @Test
    public void pdAppliesCluster() throws Exception {
        pd((mm, sc) -> new ClusterConstraintGraph(Arrays.asList(mm.get(3), mm.get(4)), 10, 10), "cluster");
    }

    /**
     * Constraints that contradict each other through the automatic constraints of the steps (a pin at x = 900 and an
     * area at x ≤ 600 on nodes of one star) cannot all hold; the ones that do not are listed after the step.
     */
    @Test
    public void pdReportsWhatContradictsTheSteps() throws Exception {
        Graph g = load("Files/glycolysis.sbgn");
        fresh();
        List<Node> mm = ofRole(g, "MACROMOLECULE"), sc = ofRole(g, "SIMPLECHEMICAL");
        LockConstraintGraph pin = pin(mm.get(0), 900, 700);
        PageBoundaryConstraintGraph area = new PageBoundaryConstraintGraph(Arrays.asList(sc.get(3), sc.get(4)), new double[]{ 0, 0, 600, 400 }, 0);
        ConstraintStore.constraints.addConstraint(pin);
        ConstraintStore.constraints.addConstraint(area);
        new ConstraintSBGNLayout(g, Language.PD).runAll();
        int violated = 0;
        for (Constraint c : Arrays.asList(pin, area)) {
            String why = ConstraintCheck.violation(c, g.getNodes(), ConstraintCheck.TOLERANCE);
            if (why == null) continue;
            violated++;
            boolean listed = false;
            for (ConflictLog.Entry e : ConflictLog.entries()) listed |= e.constraint == c && e.kind == ConflictLog.Kind.UNSATISFIED;
            assertTrue(c.getKind() + " does not hold (" + why + ") but is not listed", listed);
        }
        assertTrue("the pin and the area both hold: the scenario no longer contradicts the steps", violated > 0);
    }

    @Test
    public void erAppliesPinAndRow() throws Exception {
        pinAndRow("Files/principle_of_the_pcr.sbgn", Language.ER, g -> ofRole(g, "ENTITY"));
    }

    @Test
    public void afAppliesPinAndRow() throws Exception {
        pinAndRow("Files/epidermal_growth_factor_receptor_pathway.sbgn", Language.AF, g -> {
            List<Node> act = ofRole(g, "BIOLOGICALACTIVITY");
            return act.size() < 3 ? ofRole(g, "MACROMOLECULE") : act;
        });
    }

    /**
     * One ER or AF run of the map {@code file} with a pin on the first and a row on the second and third of the nodes
     * {@code pick} returns; both must hold afterwards and be listed nowhere.
     */
    static void pinAndRow(String file, Language language, Function<Graph, List<Node>> pick) throws Exception {
        Graph g = load(file);
        fresh();
        ConstraintSBGNLayout.view2D = headlessView(g);
        List<Node> ns = pick.apply(g);
        LockConstraintGraph pin = pin(ns.get(0), 800, 600);
        LinePattern row = new LinePattern(Arrays.asList(ns.get(1), ns.get(2)), true, 120, false);
        ConstraintStore.constraints.addConstraint(pin);
        ConstraintStore.constraints.addConstraint(row);
        new ConstraintSBGNLayout(g, language).runAll();
        holds(pin, g, "pin");
        holds(row, g, "row");
    }

    /** The ER/AF edge routing reads a view (a headless IPKGraffitiView, as the regression tool makes one). */
    static View2D headlessView(Graph g) throws Exception {
        IPKGraffitiView v = new IPKGraffitiView();
        v.setAttributeComponentManager(new AttributeComponentManager());
        v.setGraph(g);
        JComponent comp = v.getViewComponent();
        comp.setSize(2000, 2000);
        comp.setBounds(new Rectangle(0, 0, 2000, 2000));
        int expected = g.getNumberOfNodes() + g.getNumberOfEdges();
        long deadline = System.currentTimeMillis() + 30_000;
        while (comp.getComponentCount() < expected && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });   // the view adds its components on the event thread
            Thread.sleep(5);
        }
        return v;
    }
}
