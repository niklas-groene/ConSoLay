package headless;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import org.AttributeHelper;
import org.LabelFrameSetting;
import org.Vector2d;
import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.graffiti.plugin.view.GraffitiViewComponent;
import org.graffiti.plugin.view.GraphElementComponent;
import org.graffiti.plugin.view.View;
import org.graffiti.plugins.attributecomponents.simplelabel.LabelComponent;
import org.graffiti.plugins.views.defaults.EdgeComponent;
import org.sbgn.ArcClazz;
import org.sbgn.GlyphClazz;
import org.sbgn.bindings.Arc;
import org.sbgn.bindings.Arc.End;
import org.sbgn.bindings.Arc.Next;
import org.sbgn.bindings.Arc.Start;
import org.sbgn.bindings.Arcgroup;
import org.sbgn.bindings.Bbox;
import org.sbgn.bindings.Glyph;
import org.sbgn.bindings.Label;
import org.sbgn.bindings.Map;
import org.sbgn.bindings.Port;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNConstants;
import org.sbgned.SBGNERGlyph;
import org.sbgned.SBGNHelper;
import org.sbgned.sbgnml.SBGNMLHelper;

/**
 * One SBGN-ML arc that SBGN-ED split into a chain of graph edges through the arc's own points (outcomes and
 * ports). Edges and points are kept in chain order, from the arc's source end to its target end.
 */
@SuppressWarnings("nls")
class MergedEdge {

    private String arcID;
    private ArrayList<Edge> edges;
    private ArrayList<Node> points;
    private Node source;
    private Node target;
    private LinkedHashMap<Node, Glyph> outcomes = new LinkedHashMap<Node, Glyph>();
    private LinkedHashMap<Node, Port> ports = new LinkedHashMap<Node, Port>();

    /** edges.get(k) runs from the arc's source (k = 0) or points.get(k - 1) to points.get(k) or the arc's target. */
    MergedEdge(String arcID, ArrayList<Edge> edges, ArrayList<Node> points, Node source, Node target) {

        this.arcID = arcID;
        this.edges = edges;
        this.points = points;
        this.source = source;
        this.target = target;

        HashSet<String> IDs = new HashSet<String>();
        for (Edge edge : edges)
            if (AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                IDs.add((String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        if (IDs.size() == 1)
            this.arcID = IDs.iterator().next();

    }

    public String getarcID() {

        return this.arcID;

    }

    void setDefaultArcID(String arcID) {

        if (this.arcID == null)
            this.arcID = arcID;

    }

    public ArrayList<Edge> getEdges() {

        return this.edges;

    }

    public ArrayList<Node> getPoints() {

        return this.points;

    }

    public Node getSource() {

        return this.source;

    }

    public Node getTarget() {

        return this.target;

    }

    /** Outcome glyphs in chain order. */
    public LinkedHashMap<Node, Glyph> getOutcomes() {

        return this.outcomes;

    }

    /** Ports in chain order. */
    public LinkedHashMap<Node, Port> getPorts() {

        return this.ports;

    }

}

/**
 * Headless variant of SBGN-ED's SBGN-ML writer for Entity Relationship maps. It joins the edges SBGN-ED split an arc
 * into back into one arc with its outcomes and ports, derives ports from the current geometry ({@link PortHelper})
 * and writes every glyph and point so that SBGN-ED's reader attaches it to the same arc again.
 */
@SuppressWarnings("nls")
public class ERWriterNoGui {

    private static HashMap<String, String> erglyph2clazz = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;
        {
            put(SBGNERGlyph.ENTITY.name(), GlyphClazz.ENTITY.getClazz());
            put(SBGNERGlyph.OUTCOME.name(), GlyphClazz.OUTCOME.getClazz());
            put(SBGNERGlyph.PERTURBINGAGENT.name(), GlyphClazz.PERTURBING_AGENT.getClazz());
            put(SBGNERGlyph.VARIABLEVALUE.name(), GlyphClazz.VARIABLE_VALUE.getClazz());
            put(SBGNERGlyph.ANDOPERATOR.name(), GlyphClazz.AND.getClazz());
            put(SBGNERGlyph.OROPERATOR.name(), GlyphClazz.OR.getClazz());
            put(SBGNERGlyph.NOTOPERATOR.name(), GlyphClazz.NOT.getClazz());
            put(SBGNERGlyph.DELAYOPERATOR.name(), GlyphClazz.DELAY.getClazz());
            put(SBGNERGlyph.PHENOTYPE.name(), GlyphClazz.PHENOTYPE.getClazz());
            put(SBGNERGlyph.ANNOTATION.name(), GlyphClazz.ANNOTATION.getClazz());
            put(SBGNERGlyph.MULTIINTERACTION.name(), GlyphClazz.INTERACTION.getClazz());
            put("IMPLICITXOR", GlyphClazz.IMPLICIT_XOR.getClazz());
        }
    };
    private static HashMap<String, String> erarc2clazz = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;
        {
            put(SBGNERGlyph.ASSIGNMENT.name(), ArcClazz.ASSIGNMENT.getClazz());
            put(SBGNERGlyph.INTERACTION.name(), ArcClazz.INTERACTION.getClazz());
            put(SBGNERGlyph.MODULATION.name(), ArcClazz.MODULATION.getClazz());
            put(SBGNERGlyph.STIMULATION.name(), ArcClazz.STIMULATION.getClazz());
            put(SBGNERGlyph.NECESSARYSTIMULATION.name(), ArcClazz.NECESSARY_STIMULATION.getClazz());
            put(SBGNERGlyph.ABSOLUTESTIMULATION.name(), ArcClazz.ABSOLUTE_STIMULATION.getClazz());
            put(SBGNERGlyph.INHIBITION.name(), ArcClazz.INHIBITION.getClazz());
            put(SBGNERGlyph.ABSOLUTEINHIBITION.name(), ArcClazz.ABSOLUTE_INHIBITION.getClazz());
            put(SBGNERGlyph.LOGICARC.name(), ArcClazz.LOGIC_ARC.getClazz());
        }
    };

    public static Sbgn getSBGN(String language, Graph graph, String[] arrParents) {

        Sbgn sbgn = new Sbgn();
        Map map = new Map();
        map.setLanguage(language);
        sbgn.getMap().add(map);
        HashMap<Node, Glyph> hmnode2glyph = new HashMap<Node, Glyph>();
        HashMap<Node, Glyph> hmnode2outcome = new HashMap<Node, Glyph>();
        HashMap<Node, Port> hmnode2port = new HashMap<Node, Port>();
        HashMap<Edge, Arc> hmedge2arc = new HashMap<Edge, Arc>();

        ArrayList<String> knownArcIDs = new ArrayList<String>();
        for (Edge edge : graph.getEdges())
            if (AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                knownArcIDs.add((String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        int arcid = 1;
        HashSet<Edge> processededges = new HashSet<Edge>();
        ArrayList<MergedEdge> mergedEdges = new ArrayList<MergedEdge>();
        for (Edge edge : graph.getEdges()) {
            if (processededges.contains(edge))
                continue;
            while (knownArcIDs.contains("arc" + arcid))
                arcid++;
            if (!isHyperedgeRole(SBGNHelper.getSBGNRole(edge)))
                continue;
            MergedEdge mergedEdge = collectChain(edge);
            // An arc without points of its own is a single edge, even when it starts or ends at another arc's
            // outcome or port; getArc writes it below.
            if (mergedEdge.getPoints().isEmpty())
                continue;
            mergedEdge.setDefaultArcID("arc" + arcid);
            processededges.addAll(mergedEdge.getEdges());
            mergedEdges.add(mergedEdge);
            arcid++;
        }

        // An outcome keeps the id it was read with; ports (SBGN-ED's reader drops their ids) and new outcomes get
        // "<arc id>.<k>" with the smallest k that no glyph, arc or other point uses.
        HashSet<String> usedIds = new HashSet<String>(knownArcIDs);
        for (Node node : graph.getNodes())
            if (!isPoint(node) && AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                usedIds.add((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        for (MergedEdge mergedEdge : mergedEdges)
            usedIds.add(mergedEdge.getarcID());
        HashMap<Node, String> pointIds = new HashMap<Node, String>();
        for (MergedEdge mergedEdge : mergedEdges)
            for (Node point : mergedEdge.getPoints())
                if (SBGNHelper.getSBGNRole(point).equals(SBGNERGlyph.OUTCOME.name()) &&
                        AttributeHelper.hasAttribute(point, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID)) {
                    String id = (String) AttributeHelper.getAttributeValue(point, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false);
                    if (id.length() > 0 && usedIds.add(id))
                        pointIds.put(point, id);
                }
        for (MergedEdge mergedEdge : mergedEdges) {
            for (Node point : mergedEdge.getPoints()) {
                String id = pointIds.get(point);
                if (id == null)
                    id = freeId(mergedEdge.getarcID(), 0, usedIds);
                if (SBGNHelper.getSBGNRole(point).equals(SBGNERGlyph.HYPEREDGENODE.name())) {
                    Vector2d pos = AttributeHelper.getPositionVec2d(point);
                    Port port = new Port();
                    port.setId(id);
                    port.setX((float) pos.x);
                    port.setY((float) pos.y);
                    mergedEdge.getPorts().put(point, port);
                    hmnode2port.put(point, port);
                } else {
                    Glyph glyph = getGlyph(point, id);
                    if (glyph != null) {
                        glyph.setId(id);   // getGlyph prefers the node's own id, which may have been taken already
                        mergedEdge.getOutcomes().put(point, glyph);
                        hmnode2outcome.put(point, glyph);
                    }
                }
            }
        }

        ArrayList<String> knownGlyphIDs = new ArrayList<String>();
        for (Node node : graph.getNodes())
            if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                knownGlyphIDs.add((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        int glyphid = 1;
        for (Node node : graph.getNodes()) {
            if (!SBGNHelper.getSBGNRole(node).equals(SBGNERGlyph.HYPEREDGENODE.name()) &&
                    !SBGNHelper.getSBGNRole(node).equals(SBGNERGlyph.OUTCOME.name())) {
                while (knownGlyphIDs.contains("glyph" + glyphid))
                    glyphid++;
                Glyph glyph = getGlyph(node, "glyph" + glyphid);
                if (glyph == null)
                    continue;
                else
                if (!AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID)) {
                    AttributeHelper.setAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "glyph" + glyphid);
                    glyphid++;
                }
                hmnode2glyph.put(node, glyph);
                map.getGlyph().add(glyph);
            }
        }

        ArrayList<Glyph> rootglyphs = new ArrayList<Glyph>();
        ArrayList<Glyph> nonrootglyphs = new ArrayList<Glyph>();
        HashMap<Glyph, ArrayList<Glyph>> hmparentchildren = SBGNMLHelper.getParentChildren(graph.getNodes(), hmnode2glyph, arrParents, rootglyphs,
                nonrootglyphs);

        // Only entities are written inside the entity whose box contains them; any other glyph there (a variable
        // value, an operator) stays at the top level, so the removal of the nested glyphs below keeps it in the map.
        HashSet<Glyph> nestedGlyphs = new HashSet<Glyph>();
        for (ArrayList<Glyph> children : hmparentchildren.values()) {
            children.removeIf(child -> !child.getClazz().equals("entity"));
            nestedGlyphs.addAll(children);
        }
        nonrootglyphs.retainAll(nestedGlyphs);
        for (Glyph rootglyph : rootglyphs)
            SBGNMLHelper.addChildGlyphs(rootglyph, hmparentchildren);

        for (MergedEdge mergedEdge : mergedEdges) {

            Glyph glyph = null;
            String docking;
            Vector2d pos;

            Arc arc = new Arc();
            arc.setId(mergedEdge.getarcID());
            ArrayList<Edge> chain = mergedEdge.getEdges();
            Edge srcEdge = chain.get(0);
            Edge tgtEdge = chain.get(chain.size() - 1);
            Node srcNode = mergedEdge.getSource();
            Node tgtNode = mergedEdge.getTarget();
            String clazz = erarc2clazz.get(SBGNHelper.getSBGNRole(srcEdge));
            if (clazz == null)
                continue;
            arc.setClazz(clazz);

            // Everything between the two ends, in chain order: the bends of every edge (turned round where the
            // edge runs against the chain) and, after every edge but the last, the point the edge leads to.
            ArrayList<Vector2d> vertices = new ArrayList<Vector2d>();
            ArrayList<Node> vertexPoint = new ArrayList<Node>();   // the point at that vertex, null for a bend
            Node current = srcNode;
            for (int k = 0; k < chain.size(); k++) {
                Edge edge = chain.get(k);
                ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(edge);
                if (edge.getSource() != current)
                    bends = SBGNMLHelper.reverseBends(bends);
                for (Vector2d bend : bends) {
                    vertices.add(bend);
                    vertexPoint.add(null);
                }
                current = (edge.getSource() == current) ? edge.getTarget() : edge.getSource();
                if (k < chain.size() - 1) {
                    vertices.add(AttributeHelper.getPositionVec2d(current));
                    vertexPoint.add(current);
                }
            }

            Vector2d srcnodepos = AttributeHelper.getPositionVec2d(srcNode);
            Vector2d tgtnodepos = AttributeHelper.getPositionVec2d(tgtNode);

            // source node
            if (hmnode2glyph.get(srcNode) != null) {
                glyph = hmnode2glyph.get(srcNode);
                map.getGlyph().remove(glyph);
            } else
            if (hmnode2outcome.get(srcNode) != null)
                glyph = hmnode2outcome.get(srcNode);
            else
            if (hmnode2port.get(srcNode) != null) {
                Port port = hmnode2port.get(srcNode);
                Start start = new Start();
                start.setX(port.getX());
                start.setY(port.getY());
                arc.setStart(start);
                arc.setSource(port);
            }
            if (glyph != null) {
                map.getGlyph().remove(glyph);
                Vector2d srcnodesize = AttributeHelper.getSize(srcNode);

                Vector2d tgtpoint;
                if (vertices.size() == 0)
                    tgtpoint = tgtnodepos;
                else
                    tgtpoint = vertices.get(0);
                docking = (String) AttributeHelper.getAttributeValue(srcEdge,
                        GraphicAttributeConstants.DOCKING_PATH, GraphicAttributeConstants.SOURCE, "", "", false);
                String bendout = (String) AttributeHelper.getAttributeValue(srcEdge, "SBGN", "BendOut", null, "", false);

                if (docking.length() > 0) {
                    pos = SBGNMLHelper.getDockingPosition(docking, srcnodepos, srcnodesize);
                    arc.setSource(glyph);
                } else
                if (bendout != null) {
                    // portPosition may consume a bend next to the node (the stub end), never a point
                    ArrayList<Vector2d> stub = new ArrayList<Vector2d>();
                    if (vertices.size() > 0 && vertexPoint.get(0) == null)
                        stub.add(vertices.get(0));
                    Port port = SBGNMLHelper.getPort(PortHelper.portPosition(srcnodepos, srcnodesize, stub, true, tgtpoint), srcnodepos, srcnodesize, glyph);
                    if (vertices.size() > 0 && vertexPoint.get(0) == null && stub.isEmpty()) {
                        vertices.remove(0);
                        vertexPoint.remove(0);
                    }
                    pos = new Vector2d(port.getX(), port.getY());
                    SBGNMLHelper.setGlyphOrientation(glyph, port, srcnodepos, srcnodesize);
                    arc.setSource(port);
                } else {
                    pos = SBGNMLHelper.getIntersectionEdgeNode(tgtpoint, srcNode, srcnodepos, srcnodesize);
                    arc.setSource(glyph);
                }
                Start start = new Start();
                start.setX((float) pos.x);
                start.setY((float) pos.y);
                arc.setStart(start);
            }
            if (hmnode2glyph.get(srcNode) != null)
                map.getGlyph().add(glyph);

            glyph = null;
            // target node
            if (hmnode2glyph.get(tgtNode) != null) {
                glyph = hmnode2glyph.get(tgtNode);
                map.getGlyph().remove(glyph);
            } else
            if (hmnode2outcome.get(tgtNode) != null)
                glyph = hmnode2outcome.get(tgtNode);
            else
            if (hmnode2port.get(tgtNode) != null) {
                Port port = hmnode2port.get(tgtNode);
                End end = new End();
                end.setX(port.getX());
                end.setY(port.getY());
                arc.setEnd(end);
                arc.setTarget(port);
            }
            if (glyph != null) {
                Vector2d tgtnodesize = AttributeHelper.getSize(tgtNode);

                Vector2d srcpoint;
                if (vertices.size() == 0)
                    srcpoint = srcnodepos;
                else
                    srcpoint = vertices.get(vertices.size() - 1);
                docking = (String) AttributeHelper.getAttributeValue(tgtEdge,
                        GraphicAttributeConstants.DOCKING_PATH, GraphicAttributeConstants.TARGET, "", "", false);
                String bendin = (String) AttributeHelper.getAttributeValue(tgtEdge, "SBGN", "BendIn", null, "", false);

                boolean atPort = false;
                if (docking.length() > 0) {
                    pos = SBGNMLHelper.getDockingPosition(docking, tgtnodepos, tgtnodesize);
                    arc.setTarget(glyph);
                } else
                if (bendin != null) {
                    // the last bend is the port itself; a point is never dropped
                    if (vertices.size() > 0 && vertexPoint.get(vertices.size() - 1) == null) {
                        vertices.remove(vertices.size() - 1);
                        vertexPoint.remove(vertexPoint.size() - 1);
                    }
                    Port port = SBGNMLHelper.getPort(bendin, tgtnodepos, tgtnodesize, glyph);
                    pos = new Vector2d(port.getX(), port.getY());
                    SBGNMLHelper.setGlyphOrientation(glyph, port, tgtnodepos, tgtnodesize);
                    arc.setTarget(port);
                    atPort = true;
                } else {
                    pos = SBGNMLHelper.getIntersectionEdgeNode(srcpoint, tgtNode, tgtnodepos, tgtnodesize);
                    arc.setTarget(glyph);
                }
                if (!atPort && clazz.equals(ArcClazz.ASSIGNMENT.getClazz()) && hmnode2glyph.get(tgtNode) != null)
                    pos = assignmentEnd(pos, vertices.size() > 0 ? vertices.get(vertices.size() - 1) : startOf(arc, srcnodepos), tgtNode);
                End end = new End();
                end.setX((float) pos.x);
                end.setY((float) pos.y);
                arc.setEnd(end);
            }
            if (hmnode2glyph.get(tgtNode) != null)
                map.getGlyph().add(glyph);

            // SBGN-ED's reader attaches an outcome or port only to the arc whose line passes through it: a point that
            // does not lie on the segment spanning its place in the chain becomes a vertex of the line.
            ArrayList<Vector2d> line = new ArrayList<Vector2d>();
            ArrayList<Node> linePoint = new ArrayList<Node>();
            line.add(startOf(arc, srcnodepos));
            linePoint.add(null);
            line.addAll(vertices);
            linePoint.addAll(vertexPoint);
            line.add(endOf(arc, tgtnodepos));
            linePoint.add(null);
            boolean[] keep = keptVertices(line, linePoint);
            for (int i = 1; i < line.size() - 1; i++)
                if (keep[i]) {
                    Next next = new Next();
                    next.setX((float) line.get(i).x);
                    next.setY((float) line.get(i).y);
                    arc.getNext().add(next);
                }
            for (Glyph outcome : mergedEdge.getOutcomes().values())
                arc.getGlyph().add(outcome);
            for (Port port : mergedEdge.getPorts().values())
                arc.getPort().add(port);

            glyph = null;
            for (int k = 0; k < chain.size(); k++) {
                Edge edge = chain.get(k);
                if (AttributeHelper.getLabel(edge, null) != null ||
                        AttributeHelper.getLabelConsumption(edge, null) != null ||
                        AttributeHelper.getLabelProduction(edge, null) != null) {
                    // the label sits on the piece of the line that belongs to this edge
                    int from = 0, to = line.size() - 1, seen = 0;
                    for (int i = 1; i < line.size() - 1; i++)
                        if (linePoint.get(i) != null) {
                            seen++;
                            if (seen == k)
                                from = i;
                            if (seen == k + 1)
                                to = i;
                        }
                    String id = freeId(mergedEdge.getarcID(), mergedEdge.getPoints().size(), usedIds);
                    glyph = getCardinalityGlyph(edge, arc, id, labelAnchor(line.subList(from, to + 1)));
                    break;
                }
            }
            if (glyph != null)
                arc.getGlyph().add(glyph);

            for (Edge edge : mergedEdge.getEdges())
                hmedge2arc.put(edge, arc);
            map.getArc().add(arc);
        }

        for (Edge edge : graph.getEdges())
            if (!processededges.contains(edge)) {
                while (usedIds.contains("arc" + arcid))
                    arcid++;
                while (knownGlyphIDs.contains("glyph" + glyphid) || usedIds.contains("glyph" + glyphid))
                    glyphid++;
                Arc arc = getArc(edge, map, hmnode2glyph, hmnode2outcome, hmnode2port, arcid, "glyph" + glyphid);
                if (arc == null)
                    continue;
                else
                if (!AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID)) {
                    AttributeHelper.setAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "arc" + arcid);
                    arcid++;
                }
                hmedge2arc.put(edge, arc);
                map.getArc().add(arc);
                if (arc.getGlyph() != null)
                    glyphid++;
            }

        for (Node node : graph.getNodes())
            if (SBGNHelper.getSBGNRole(node).equals(SBGNERGlyph.MULTIINTERACTION.name())) {
                Arcgroup arcGroup = new Arcgroup();
                arcGroup.setClazz(GlyphClazz.INTERACTION.getClazz());
                Glyph glyph = hmnode2glyph.get(node);
                map.getGlyph().remove(glyph);
                arcGroup.getGlyph().add(glyph);
                for (Edge edge : node.getEdges())
                    if (SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.INTERACTION.name())) {
                        Arc arc = hmedge2arc.get(edge);
                        if (arc != null) {
                            map.getArc().remove(arc);
                            arcGroup.getArc().add(arc);
                        }
                    }
                map.getArcgroup().add(arcGroup);
            }

        map.getGlyph().removeAll(nonrootglyphs);
        return sbgn;

    }

    /** Distance (px) at which an assignment arc ends in front of its target glyph, as in SBGN-ED's own files. */
    static final double ASSIGNMENT_GAP = 5;

    /** A point at most this far (px) from a segment of its arc's line counts as lying on it. */
    static final double ON_SEGMENT = 0.5;

    /** Arcs that can carry outcomes or ports of their own: in SBGN ER every arc but the logic arc. */
    private static final HashSet<String> HYPEREDGE_ROLES = new HashSet<String>(Arrays.asList(
            SBGNERGlyph.INTERACTION.name(), SBGNERGlyph.ASSIGNMENT.name(), SBGNERGlyph.MODULATION.name(),
            SBGNERGlyph.STIMULATION.name(), SBGNERGlyph.NECESSARYSTIMULATION.name(), SBGNERGlyph.ABSOLUTESTIMULATION.name(),
            SBGNERGlyph.INHIBITION.name(), SBGNERGlyph.ABSOLUTEINHIBITION.name()));

    private static boolean isHyperedgeRole(String role) {

        return HYPEREDGE_ROLES.contains(role);

    }

    /** An outcome or a port on an arc. */
    private static boolean isPoint(Node node) {

        String role = SBGNHelper.getSBGNRole(node);
        return role.equals(SBGNERGlyph.OUTCOME.name()) || role.equals(SBGNERGlyph.HYPEREDGENODE.name());

    }

    private static String arcIdOf(Edge edge) {

        if (!AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
            return null;
        return (String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false);

    }

    /**
     * True if both edges are pieces of the same SBGN-ML arc. SBGN-ED's reader gives every piece the arc's id; an arc
     * that starts or ends at another arc's outcome or port meets that arc's pieces there, possibly with the same class.
     * Edges without an id are compared by class only.
     */
    private static boolean sameArc(Edge a, Edge b) {

        if (!SBGNHelper.getSBGNRole(a).equals(SBGNHelper.getSBGNRole(b)))
            return false;
        String ida = arcIdOf(a), idb = arcIdOf(b);
        if (ida == null && idb == null)
            return true;
        return ida != null && ida.equals(idb);

    }

    /** The arc of {@code arcEdge} runs through the node: two of its pieces meet there. */
    private static boolean isOwnPoint(Node node, Edge arcEdge) {

        if (!isPoint(node))
            return false;
        int pieces = 0;
        for (Edge e : node.getEdges())
            if (sameArc(e, arcEdge))
                pieces++;
        return pieces >= 2;

    }

    /**
     * Follows the arc of {@code edge} through its own points in both directions. SBGN-ED splits an arc into pieces
     * that keep its direction, so walking against {@code edge} leads to the arc's source, walking along it to the target.
     */
    private static MergedEdge collectChain(Edge edge) {

        LinkedList<Edge> edges = new LinkedList<Edge>();
        LinkedList<Node> points = new LinkedList<Node>();
        HashSet<Edge> taken = new HashSet<Edge>();
        edges.add(edge);
        taken.add(edge);
        Node[] ends = new Node[2];
        for (int direction = 0; direction < 2; direction++) {
            boolean forward = direction == 0;
            Node node = forward ? edge.getTarget() : edge.getSource();
            Edge previous = edge;
            while (isOwnPoint(node, edge)) {
                Edge next = null;
                for (Edge e : node.getEdges())
                    if (e != previous && !taken.contains(e) && sameArc(e, edge)) {
                        next = e;
                        break;
                    }
                if (next == null)
                    break;
                taken.add(next);
                if (forward) {
                    points.addLast(node);
                    edges.addLast(next);
                } else {
                    points.addFirst(node);
                    edges.addFirst(next);
                }
                node = (next.getSource() == node) ? next.getTarget() : next.getSource();
                previous = next;
            }
            ends[direction] = node;
        }
        return new MergedEdge(null, new ArrayList<Edge>(edges), new ArrayList<Node>(points), ends[1], ends[0]);

    }

    /** "base.k" with the smallest k >= first that no element uses yet; the id is marked as used. */
    private static String freeId(String base, int first, HashSet<String> usedIds) {

        int k = first;
        while (usedIds.contains(base + "." + k))
            k++;
        usedIds.add(base + "." + k);
        return base + "." + k;

    }

    private static Vector2d startOf(Arc arc, Vector2d fallback) {

        return arc.getStart() != null ? new Vector2d(arc.getStart().getX(), arc.getStart().getY()) : fallback;

    }

    private static Vector2d endOf(Arc arc, Vector2d fallback) {

        return arc.getEnd() != null ? new Vector2d(arc.getEnd().getX(), arc.getEnd().getY()) : fallback;

    }

    /**
     * Which vertices of an arc's line are written: start, end and bends always, a point (linePoint != null) only if
     * the line would otherwise miss it. Every point, written or not, has to lie on the segment that spans its place
     * in the chain, since SBGN-ED's reader finds a point's arc by the line that passes through it.
     */
    private static boolean[] keptVertices(List<Vector2d> line, List<Node> linePoint) {

        boolean[] keep = new boolean[line.size()];
        Arrays.fill(keep, true);
        for (int i = 1; i < line.size() - 1; i++)
            if (linePoint.get(i) != null) {
                keep[i] = false;
                if (!pointsOnLine(line, linePoint, keep))
                    keep[i] = true;
            }
        return keep;

    }

    private static boolean pointsOnLine(List<Vector2d> line, List<Node> linePoint, boolean[] keep) {

        int previous = 0;
        for (int i = 1; i < line.size(); i++) {
            if (!keep[i])
                continue;
            for (int j = previous + 1; j < i; j++)
                if (linePoint.get(j) != null && segmentDistance(line.get(j), line.get(previous), line.get(i)) > ON_SEGMENT)
                    return false;
            previous = i;
        }
        return true;

    }

    private static double segmentDistance(Vector2d p, Vector2d a, Vector2d b) {

        return java.awt.geom.Line2D.ptSegDist(a.x, a.y, b.x, b.y, p.x, p.y);

    }

    /**
     * Where a label goes on a polyline: the middle of its longest segment (the first of equally long ones). Not on a
     * bend, and SBGN-ED's reader, which looks for the piece whose line crosses the label box, then finds a line that
     * runs through the whole box.
     */
    private static Vector2d labelAnchor(List<Vector2d> line) {

        Vector2d a = line.get(0), b = line.get(0);
        double longest = -1;
        for (int i = 1; i < line.size(); i++) {
            double d = line.get(i - 1).distance(line.get(i));
            if (d > longest) {
                longest = d;
                a = line.get(i - 1);
                b = line.get(i);
            }
        }
        return new Vector2d((a.x + b.x) / 2, (a.y + b.y) / 2);

    }

    /**
     * End point of an assignment arc at its target glyph. SBGN-ED's reader docks an assignment correctly only if its
     * end lies beside exactly one side of the target's box and more than 1 px away from it; otherwise the re-read line
     * misses the arc's outcomes and ports. Other ends are moved {@link #ASSIGNMENT_GAP} px in front of the side they
     * lie on (at a corner or inside the box: the side facing {@code from}, the vertex before the end).
     */
    private static Vector2d assignmentEnd(Vector2d end, Vector2d from, Node target) {

        if (SBGNHelper.getSBGNRole(target).equals("IMPLICITXOR"))
            return end;   // the reader does not dock there
        Vector2d c = AttributeHelper.getPositionVec2d(target);
        Vector2d size = AttributeHelper.getSize(target);
        double hw = size.x / 2, hh = size.y / 2;
        if (hw <= 0 || hh <= 0)
            return end;
        double l = c.x - hw, r = c.x + hw, t = c.y - hh, b = c.y + hh;
        boolean xIn = end.x >= l && end.x <= r, yIn = end.y >= t && end.y <= b;
        double outside = xIn ? Math.max(t - end.y, end.y - b) : Math.max(l - end.x, end.x - r);
        if (xIn != yIn && outside > 1.5)
            return end;
        double nx = (end.x - c.x) / hw, ny = (end.y - c.y) / hh;
        if (Math.abs(Math.abs(nx) - Math.abs(ny)) < 1e-6 || Math.max(Math.abs(nx), Math.abs(ny)) < 1 - 1e-6) {
            nx = (from.x - c.x) / hw;
            ny = (from.y - c.y) / hh;
        }
        if (Math.abs(nx) > Math.abs(ny)) {
            double inset = Math.min(1, hh);
            return new Vector2d(nx < 0 ? l - ASSIGNMENT_GAP : r + ASSIGNMENT_GAP, Math.min(Math.max(end.y, t + inset), b - inset));
        }
        double inset = Math.min(1, hw);
        return new Vector2d(Math.min(Math.max(end.x, l + inset), r - inset), ny < 0 ? t - ASSIGNMENT_GAP : b + ASSIGNMENT_GAP);

    }

    private static Glyph getGlyph(Node node, String glyphid) {

        ArrayList<String> logicaloperators = new ArrayList<String>(Arrays.asList(new String[] {
                SBGNERGlyph.ANDOPERATOR.name(), SBGNERGlyph.OROPERATOR.name(),
                SBGNERGlyph.NOTOPERATOR.name(), SBGNERGlyph.DELAYOPERATOR.name() }));

        String label;
        Label labelobj;
        Vector2d labelpos, labelsize;
        Bbox bbox;

        Glyph glyph = new Glyph();
        if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
            glyph.setId((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        else
            glyph.setId(glyphid);
        String clazz = erglyph2clazz.get(SBGNHelper.getSBGNRole(node));
        if (clazz == null)
            return null;
        glyph.setClazz(clazz);
        Vector2d nodepos = AttributeHelper.getPositionVec2d(node);
        Vector2d nodesize = AttributeHelper.getSize(node);
        if (clazz.equals(GlyphClazz.IMPLICIT_XOR.getClazz()))
            nodesize = new Vector2d(0, 0);
        bbox = SBGNMLHelper.getBoundingBox(nodepos, nodesize);
        glyph.setBbox(bbox);
        label = AttributeHelper.getLabel(node, null);
        String sbgnrole = SBGNHelper.getSBGNRole(node);
        if (!logicaloperators.contains(sbgnrole) && label != null) {
            label = SBGNMLHelper.adjustLabel(label);
            if (label.length() > 0) {
                labelobj = SBGNMLHelper.getLabelObj(label);
                glyph.setLabel(labelobj);
            }
        }
        char childid = 'a';
        for (int k = 1; k < 100; k++) {
            label = AttributeHelper.getLabel(k, node, null);
            if (label != null) {
                label = SBGNMLHelper.adjustLabel(label);
                labelpos = SBGNMLHelper.getBorderLabelPosition(k, node, nodepos, nodesize);
                LabelFrameSetting labelframesetting = AttributeHelper.getLabel(k, node).getLabelFrameSetting();
                if (labelpos != null &&
                        (labelframesetting.equals(LabelFrameSetting.RECTANGLE) ||
                                labelframesetting.equals(LabelFrameSetting.CAPSULE) ||
                                labelframesetting.equals(LabelFrameSetting.CIRCLE_HALF_FILLED) ||
                                labelframesetting.equals(LabelFrameSetting.PIN) ||
                                labelframesetting.equals(LabelFrameSetting.CIRCLE_FILLED))) {
                    Glyph childglyph = new Glyph();
                    if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                        childglyph.setId((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false) + childid);
                    else
                        childglyph.setId(glyphid + childid);
                    labelsize = SBGNMLHelper.getLabelSize(k, node);
                    if (labelframesetting.equals(LabelFrameSetting.CIRCLE_FILLED))
                        labelsize.x = labelsize.y;
                    bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                    childglyph.setBbox(bbox);
                    if (labelframesetting.equals(LabelFrameSetting.RECTANGLE) && label.length() > 0) {
                        childglyph.setClazz("unit of information");
                        labelobj = SBGNMLHelper.getLabelObj(label);
                        childglyph.setLabel(labelobj);
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    } else if (labelframesetting.equals(LabelFrameSetting.CAPSULE)) {
                        childglyph.setClazz("state variable");
                        if (label.length() > 0) {
                            Glyph.State state = WriterNoGui.stateOf(label);
                            childglyph.setState(state);
                        }
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    } else if (labelframesetting.equals(LabelFrameSetting.CIRCLE_HALF_FILLED)) {
                        childglyph.setClazz("existence");
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    } else if (labelframesetting.equals(LabelFrameSetting.PIN)) {
                        childglyph.setClazz("location");
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    } else if (labelframesetting.equals(LabelFrameSetting.CIRCLE_FILLED)) {
                        // the outcome of a multi-interaction
                        childglyph.setClazz("outcome");
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    }
                }
            }
        }
        return glyph;

    }

    private static Arc getArc(Edge edge, Map map, HashMap<Node, Glyph> hmnode2glyph, HashMap<Node, Glyph> hmnode2outcome,
                              HashMap<Node, Port> hmnode2port, int arcid, String glyphid) {

        Glyph glyph = null;
        String docking;
        Vector2d pos;

        Arc arc = new Arc();
        if (AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
            arc.setId((String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        else
            arc.setId("arc" + arcid);
        String clazz = erarc2clazz.get(SBGNHelper.getSBGNRole(edge));
        if (clazz == null)
            return null;
        arc.setClazz(clazz);
        ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(edge);
        Vector2d srcnodepos = AttributeHelper.getPositionVec2d(edge.getSource());
        Vector2d tgtnodepos = AttributeHelper.getPositionVec2d(edge.getTarget());

        // source node
        if (hmnode2glyph.get(edge.getSource()) != null) {
            glyph = hmnode2glyph.get(edge.getSource());
            map.getGlyph().remove(glyph);
        } else
        if (hmnode2outcome.get(edge.getSource()) != null)
            glyph = hmnode2outcome.get(edge.getSource());
        else
        if (hmnode2port.get(edge.getSource()) != null) {
            Port port = hmnode2port.get(edge.getSource());
            Start start = new Start();
            start.setX(port.getX());
            start.setY(port.getY());
            arc.setStart(start);
            arc.setSource(port);
        }
        if (glyph != null) {
            map.getGlyph().remove(glyph);
            Vector2d srcnodesize = AttributeHelper.getSize(edge.getSource());

            Vector2d tgtpoint;
            if (bends.size() == 0)
                tgtpoint = tgtnodepos;
            else
                tgtpoint = bends.get(0);
            docking = (String) AttributeHelper.getAttributeValue(edge,
                    GraphicAttributeConstants.DOCKING_PATH, GraphicAttributeConstants.SOURCE, "", "", false);
            String bendout = (String) AttributeHelper.getAttributeValue(edge, "SBGN", "BendOut", null, "", false);

            if (docking.length() > 0) {
                pos = SBGNMLHelper.getDockingPosition(docking, srcnodepos, srcnodesize);
                arc.setSource(glyph);
            } else
            if (bendout != null) {
                Port port = SBGNMLHelper.getPort(PortHelper.portPosition(srcnodepos, srcnodesize, bends, true, tgtpoint), srcnodepos, srcnodesize, glyph);
                pos = new Vector2d(port.getX(), port.getY());
                SBGNMLHelper.setGlyphOrientation(glyph, port, srcnodepos, srcnodesize);
                arc.setSource(port);
            } else {
                pos = SBGNMLHelper.getIntersectionEdgeNode(tgtpoint, edge.getSource(), srcnodepos, srcnodesize);
                arc.setSource(glyph);
            }
            Start start = new Start();
            start.setX((float) pos.x);
            start.setY((float) pos.y);
            arc.setStart(start);
        }
        if (hmnode2glyph.get(edge.getSource()) != null)
            map.getGlyph().add(glyph);

        glyph = null;
        // target node
        if (hmnode2glyph.get(edge.getTarget()) != null) {
            glyph = hmnode2glyph.get(edge.getTarget());
            map.getGlyph().remove(glyph);
        } else
        if (hmnode2outcome.get(edge.getTarget()) != null)
            glyph = hmnode2outcome.get(edge.getTarget());
        else
        if (hmnode2port.get(edge.getTarget()) != null) {
            Port port = hmnode2port.get(edge.getTarget());
            End end = new End();
            end.setX(port.getX());
            end.setY(port.getY());
            arc.setEnd(end);
            arc.setTarget(port);
        }
        if (glyph != null) {
            Vector2d tgtnodesize = AttributeHelper.getSize(edge.getTarget());

            Vector2d srcpoint;
            if (bends.size() == 0)
                srcpoint = srcnodepos;
            else
                srcpoint = bends.get(bends.size() - 1);
            docking = (String) AttributeHelper.getAttributeValue(edge,
                    GraphicAttributeConstants.DOCKING_PATH, GraphicAttributeConstants.TARGET, "", "", false);
            String bendin = (String) AttributeHelper.getAttributeValue(edge, "SBGN", "BendIn", null, "", false);

            boolean atPort = false;
            if (docking.length() > 0) {
                pos = SBGNMLHelper.getDockingPosition(docking, tgtnodepos, tgtnodesize);
                arc.setTarget(glyph);
            } else
            if (bendin != null) {
                Port port = SBGNMLHelper.getPort(PortHelper.portPosition(tgtnodepos, tgtnodesize, bends, false, srcpoint), tgtnodepos, tgtnodesize, glyph);
                pos = new Vector2d(port.getX(), port.getY());
                SBGNMLHelper.setGlyphOrientation(glyph, port, tgtnodepos, tgtnodesize);
                arc.setTarget(port);
                atPort = true;
            } else {
                pos = SBGNMLHelper.getIntersectionEdgeNode(srcpoint, edge.getTarget(), tgtnodepos, tgtnodesize);
                arc.setTarget(glyph);
            }
            if (!atPort && clazz.equals(ArcClazz.ASSIGNMENT.getClazz()) && hmnode2glyph.get(edge.getTarget()) != null)
                pos = assignmentEnd(pos, bends.size() > 0 ? bends.get(bends.size() - 1) : startOf(arc, srcnodepos), edge.getTarget());
            End end = new End();
            end.setX((float) pos.x);
            end.setY((float) pos.y);
            arc.setEnd(end);
        }
        if (hmnode2glyph.get(edge.getTarget()) != null)
            map.getGlyph().add(glyph);

        for (Vector2d bend : bends) {
            Next next = new Next();
            next.setX((float) bend.x);
            next.setY((float) bend.y);
            arc.getNext().add(next);
        }

        if ((SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.INTERACTION.name()) &&
                (AttributeHelper.getLabel(edge, null) != null ||
                        AttributeHelper.getLabelConsumption(edge, null) != null ||
                        AttributeHelper.getLabelProduction(edge, null) != null)) ||
                ((SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.MODULATION.name()) ||
                        SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.STIMULATION.name()) ||
                        SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.NECESSARYSTIMULATION.name()) ||
                        SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.ABSOLUTESTIMULATION.name()) ||
                        SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.INHIBITION.name()) ||
                        SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.ABSOLUTEINHIBITION.name())) &&
                        AttributeHelper.getLabel(edge, null) != null)) {
            ArrayList<Vector2d> line = new ArrayList<Vector2d>();
            line.add(startOf(arc, srcnodepos));
            line.addAll(bends);
            line.add(endOf(arc, tgtnodepos));
            glyph = getCardinalityGlyph(edge, arc, glyphid, labelAnchor(line));
            arc.getGlyph().add(glyph);
        }
        return arc;

    }

    /** @param anchor middle of the part of the arc's line that carries the label */
    private static Glyph getCardinalityGlyph(Edge edge, Arc arc, String glyphid, Vector2d anchor) {
        Glyph glyph = new Glyph();
        glyph.setId(glyphid);
        glyph.setClazz("cardinality");
        Label label = new Label();
        if (SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.INTERACTION.name()))
            if (AttributeHelper.getLabel(edge, null) != null)
                label.setText(AttributeHelper.getLabel(edge, ""));
            else
            if (AttributeHelper.getLabelConsumption(edge, null) != null)
                label.setText(AttributeHelper.getLabelConsumption(edge, ""));
            else
                label.setText(AttributeHelper.getLabelProduction(edge, ""));
        if (SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.MODULATION.name()) ||
                SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.STIMULATION.name()) ||
                SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.NECESSARYSTIMULATION.name()) ||
                SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.ABSOLUTESTIMULATION.name()) ||
                SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.INHIBITION.name()) ||
                SBGNHelper.getSBGNRole(edge).equals(SBGNERGlyph.ABSOLUTEINHIBITION.name()))
            label.setText(AttributeHelper.getLabel(edge, ""));
        glyph.setLabel(label);
        Bbox bbox = new Bbox();
        bbox.setX(0);
        bbox.setY(0);
        bbox.setW(0);
        bbox.setH(0);
        View view = null;
        try {
            view = MainFrame.getInstance().getActiveEditorSession().getActiveView();
        } catch (Exception e) {
            // no editor window (headless)
        }
        boolean measured = false;
        if (view != null) {
            GraphElementComponent graphElementComponent = view.getComponentForElement(edge);
            if (graphElementComponent instanceof EdgeComponent) {
                EdgeComponent edgeComponent = (EdgeComponent) graphElementComponent;
                for (GraffitiViewComponent graffitiViewComponent : edgeComponent.getAttributeComponents())
                    if (graffitiViewComponent instanceof LabelComponent) {
                        LabelComponent labelComponent = (LabelComponent) graffitiViewComponent;
                        bbox.setX(labelComponent.getX());
                        bbox.setY(labelComponent.getY());
                        float frameThickness = (float) AttributeHelper.getFrameThickNess(edge);
                        bbox.setW(labelComponent.getWidth() - frameThickness);
                        bbox.setH(labelComponent.getHeight() - frameThickness);
                        measured = true;
                    }
            }
        }
        if (!measured) {
            // Size as estimated by PDWriterNoGui, centred on the arc: SBGN-ED's reader keeps the label on the piece
            // of the arc that passes through the box.
            String text = label.getText() == null ? "" : label.getText();
            String[] lines = text.isEmpty() ? new String[] { "" } : text.split("\\r?\\n");
            int maxChars = 0;
            for (String line : lines)
                maxChars = Math.max(maxChars, line.length());
            float frameThickness = (float) AttributeHelper.getFrameThickNess(edge);
            float w = Math.max(0f, Math.max(1f, maxChars * 0.6f * 12f) - frameThickness);
            float h = Math.max(0f, lines.length * 1.2f * 12f - frameThickness);
            bbox.setX((float) anchor.x - w / 2);
            bbox.setY((float) anchor.y - h / 2);
            bbox.setW(w);
            bbox.setH(h);
        }
        glyph.setBbox(bbox);

        return glyph;
    }
}
