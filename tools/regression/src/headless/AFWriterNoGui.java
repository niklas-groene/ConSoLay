package headless;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import org.AttributeHelper;
import org.LabelFrameSetting;
import org.Vector2d;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.graphics.GraphicAttributeConstants;
import org.sbgn.ArcClazz;
import org.sbgn.GlyphClazz;
import org.sbgn.bindings.Arc;
import org.sbgn.bindings.Arc.End;
import org.sbgn.bindings.Arc.Next;
import org.sbgn.bindings.Arc.Start;
import org.sbgn.bindings.Bbox;
import org.sbgn.bindings.Glyph;
import org.sbgn.bindings.Glyph.Entity;
import org.sbgn.bindings.Label;
import org.sbgn.bindings.Map;
import org.sbgn.bindings.Port;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNAFGlyph;
import org.sbgned.SBGNConstants;
import org.sbgned.SBGNHelper;
import org.sbgned.sbgnml.SBGNMLHelper;

/**
 * Headless variant of SBGN-ED's SBGN-ML writer for Activity Flow maps: nodes in a deterministic order and ports derived
 * from the current geometry ({@link PortHelper}).
 */
@SuppressWarnings("nls")
public class AFWriterNoGui {

    private static HashMap<String, String> afglyph2clazz = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;
        {
            put(SBGNAFGlyph.BIOLOGICALACTIVITY.name(), GlyphClazz.BIOLOGICAL_ACTIVITY.getClazz());
            put(SBGNAFGlyph.PHENOTYPE.name(), GlyphClazz.PHENOTYPE.getClazz());
            put(SBGNAFGlyph.PERTURBATION.name(), GlyphClazz.PERTURBATION.getClazz());
            put(SBGNAFGlyph.COMPARTMENT.name(), GlyphClazz.COMPARTMENT.getClazz());
            put(SBGNAFGlyph.SUBMAP.name(), GlyphClazz.SUBMAP.getClazz());
            put(SBGNAFGlyph.TAGRIGHT.name(), GlyphClazz.TAG.getClazz());
            put(SBGNAFGlyph.TAGLEFT.name(), GlyphClazz.TAG.getClazz());
            put(SBGNAFGlyph.TAGDOWN.name(), GlyphClazz.TAG.getClazz());
            put(SBGNAFGlyph.TAGUP.name(), GlyphClazz.TAG.getClazz());
            put(SBGNAFGlyph.ANDOPERATOR.name(), GlyphClazz.AND.getClazz());
            put(SBGNAFGlyph.OROPERATOR.name(), GlyphClazz.OR.getClazz());
            put(SBGNAFGlyph.NOTOPERATOR.name(), GlyphClazz.NOT.getClazz());
            put(SBGNAFGlyph.DELAY.name(), GlyphClazz.DELAY.getClazz());
            put(SBGNAFGlyph.POSITIVEINFLUENCE.name(), ArcClazz.POSITIVE_INFLUENCE.getClazz());
            put(SBGNAFGlyph.NEGATIVEINFLUENCE.name(), ArcClazz.NEGATIVE_INFLUENCE.getClazz());
            put(SBGNAFGlyph.UNKNOWNINFLUENCE.name(), ArcClazz.UNKNOWN_INFLUENCE.getClazz());
            put(SBGNAFGlyph.NECESSARYSTIMULATION.name(), ArcClazz.NECESSARY_STIMULATION.getClazz());
            put(SBGNAFGlyph.LOGICARC.name(), ArcClazz.LOGIC_ARC.getClazz());
            put(SBGNAFGlyph.EQUIVALENCEARC.name(), ArcClazz.EQUIVALENCE_ARC.getClazz());
        }
    };

    public static Sbgn getSBGN(String language, Graph graph, String[] arrParents) {

        ArrayList<String> activityNodes = new ArrayList<String>(Arrays.asList(new String[] {
                SBGNAFGlyph.BIOLOGICALACTIVITY.name(), SBGNAFGlyph.PHENOTYPE.name()}));

        Sbgn sbgn = new Sbgn();
        Map map = new Map();
        map.setLanguage(language);
        sbgn.getMap().add(map);
        ArrayList<Node> nodes = WriterNoGui.nodesInReadingOrder(graph);
        HashMap<Node, Glyph> hmnode2glyph = new HashMap<Node, Glyph>();
        ArrayList<String> knownGlyphIDs = new ArrayList<String>();
        for (Node node : nodes)
            if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                knownGlyphIDs.add((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        int glyphid = 1;
        int viewID = -nodes.size();
        for (Node node : nodes) {
            while (knownGlyphIDs.contains("glyph" + glyphid))
                glyphid++;
            Glyph glyph = getGlyph(node, glyphid);
            if (glyph == null)
                continue;
            else
            if (!AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID)) {
                AttributeHelper.setAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "glyph" + glyphid);
                glyphid++;
            }
            if (glyph.getClazz().equals(GlyphClazz.COMPARTMENT.getClazz())) {
                glyph.setCompartmentOrder(new Float(viewID));
                viewID++;
            }
            hmnode2glyph.put(node, glyph);
            map.getGlyph().add(glyph);
        }
        SBGNMLHelper.setCompartmentRefs(nodes, hmnode2glyph, activityNodes);
        ArrayList<Glyph> rootglyphs = new ArrayList<Glyph>();
        ArrayList<Glyph> nonrootglyphs = new ArrayList<Glyph>();
        HashMap<Glyph, ArrayList<Glyph>> hmparentchildren = SBGNMLHelper.getParentChildren(graph.getNodes(), hmnode2glyph, arrParents, rootglyphs, nonrootglyphs);
        for (Glyph rootglyph : rootglyphs)
            SBGNMLHelper.addChildGlyphs(rootglyph, hmparentchildren);
        ArrayList<String> knownArcIDs = new ArrayList<String>();
        for (Edge edge : graph.getEdges())
            if (AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                knownArcIDs.add((String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        int arcid = 1;
        for (Edge edge : graph.getEdges()) {
            while (knownArcIDs.contains("arc" + arcid))
                arcid++;
            Arc arc = getArc(edge, map, hmnode2glyph, arcid);
            if (arc == null)
                continue;
            else
            if (!AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID)) {
                AttributeHelper.setAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "arc" + arcid);
                arcid++;
            }
            map.getArc().add(arc);
        }
        map.getGlyph().removeAll(nonrootglyphs);

        return sbgn;

    }

    private static Glyph getGlyph(Node node, int glyphid) {

        ArrayList<String> logicaloperators = new ArrayList<String>(Arrays.asList(new String[] {
                SBGNAFGlyph.ANDOPERATOR.name(), SBGNAFGlyph.OROPERATOR.name(),
                SBGNAFGlyph.NOTOPERATOR.name(), SBGNAFGlyph.DELAY.name() }));
        HashMap<LabelFrameSetting, String> hmFrame2Entity = new HashMap<LabelFrameSetting, String>() {
            private static final long serialVersionUID = 1L;
            {
                put(LabelFrameSetting.CIRCLE, GlyphClazz.SIMPLE_CHEMICAL.getClazz());
                put(LabelFrameSetting.RECTANGLE_ROUNDED, GlyphClazz.MACROMOLECULE.getClazz());
                put(LabelFrameSetting.RECTANGLE_BOTTOM_ROUND, GlyphClazz.NUCLEIC_ACID_FEATURE.getClazz());
                put(LabelFrameSetting.RECTANGLE_CORNER_CUT, GlyphClazz.COMPLEX.getClazz());
                put(LabelFrameSetting.ELLIPSE, GlyphClazz.UNSPECIFIED_ENTITY.getClazz());
                put(LabelFrameSetting.HEXAGON, GlyphClazz.PERTURBATION.getClazz());
            }
        };

        String label;
        Label labelobj;
        Vector2d labelpos, labelsize;
        Bbox bbox;

        Glyph glyph = new Glyph();
        if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
            glyph.setId((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        else
            glyph.setId("glyph" + glyphid);
        String clazz = afglyph2clazz.get(SBGNHelper.getSBGNRole(node));
        if (clazz == null)
            return null;
        glyph.setClazz(clazz);
        if (SBGNHelper.getSBGNRole(node).contains("TAG")) {
            String orientation = SBGNHelper.getSBGNRole(node).replace("TAG", "");
            glyph.setOrientation(orientation.toLowerCase());
        }
        Vector2d nodepos = AttributeHelper.getPositionVec2d(node);
        Vector2d nodesize = AttributeHelper.getSize(node);
        bbox = SBGNMLHelper.getBoundingBox(nodepos, nodesize);
        glyph.setBbox(bbox);
        label = AttributeHelper.getLabel(node, null);
        String sbgnrole = SBGNHelper.getSBGNRole(node);
        if (!logicaloperators.contains(sbgnrole) && label != null) {
            label = SBGNMLHelper.adjustLabel(label);
            if (label.length() > 0) {
                labelobj = SBGNMLHelper.getLabelObj(label);
                if (clazz.equals(GlyphClazz.COMPARTMENT.getClazz())) {
                    labelsize = SBGNMLHelper.getLabelSize(-1, node);
                    labelpos = SBGNMLHelper.getInnerLabelPosition(-1, node, nodepos, nodesize, labelsize);
                    if (labelpos != null) {
                        bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                        labelobj.setBbox(bbox);
                    }
                }
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
                if (labelpos != null) {
                    Glyph childglyph = new Glyph();
                    if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                        childglyph.setId((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false) + childid);
                    else
                        childglyph.setId("glyph" + glyphid + childid);
                    labelsize = SBGNMLHelper.getLabelSize(k, node);
                    if (labelframesetting.equals(LabelFrameSetting.CIRCLE))
                        labelsize.x = labelsize.y;
                    bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                    childglyph.setBbox(bbox);
                    childglyph.setClazz("unit of information");
                    if (label.length() > 0) {
                        labelobj = SBGNMLHelper.getLabelObj(label);
                        childglyph.setLabel(labelobj);
                    }
                    if (clazz.equals(GlyphClazz.COMPARTMENT.getClazz()) &&
                            labelframesetting.equals(LabelFrameSetting.RECTANGLE) && label.length() > 0) {
                        glyph.getGlyph().add(childglyph);
                        childid++;
                    }
                    if (clazz.equals(GlyphClazz.BIOLOGICAL_ACTIVITY.getClazz()) &&
                            hmFrame2Entity.keySet().contains(labelframesetting)) {
                        // an activity with a hexagon unit of information is written as a perturbation
                        if(labelframesetting.equals(LabelFrameSetting.HEXAGON)){
                            if (AttributeHelper.hasAttribute(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
                                glyph.setId((String) AttributeHelper.getAttributeValue(node, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
                            else
                                glyph.setId("glyph" + glyphid);

                            if(glyph.getLabel() != null){
                                if(childglyph.getLabel() == null)
                                    childglyph.setLabel(SBGNMLHelper.getLabelObj(""));
                            }
                            else{
                                if(childglyph.getLabel() == null){
                                    childglyph.setLabel(SBGNMLHelper.getLabelObj(""));
                                    glyph.setLabel(SBGNMLHelper.getLabelObj(""));
                                }
                                else{
                                    glyph.setLabel(SBGNMLHelper.getLabelObj(""));
                                }
                            }

                            glyph = setLabel(childglyph, glyph);
                            glyph.setClazz("perturbation");
                            glyphid ++;

                        }
                        else{
                            Entity entity = new Entity();
                            entity.setName(hmFrame2Entity.get(labelframesetting));

                            childglyph.setEntity(entity);
                            glyph.getGlyph().add(childglyph);
                            childid++;
                        }
                    }
                }
            }
        }
        return glyph;

    }

    /** Joins the labels of the activity and its unit of information: {@code <html><center>label<br>[unit label]}. */
    private static Glyph setLabel(Glyph childglyph, Glyph glyph){
        if(childglyph.getLabel().getText().length()>0){
            if(glyph.getLabel().getText().startsWith("<html><center>") || glyph.getLabel().getText().startsWith("<html> <center>")){
                Label labelperturb = SBGNMLHelper.getLabelObj(glyph.getLabel().getText()+"<br>["+childglyph.getLabel().getText()+"]");
                glyph.setLabel(labelperturb);
            }else{
                Label labelperturb = SBGNMLHelper.getLabelObj("<html><center>"+glyph.getLabel().getText()+"<br>["+childglyph.getLabel().getText()+"]");
                glyph.setLabel(labelperturb);

            }
        }
        return glyph;
    }

    private static Arc getArc(Edge edge, Map map, HashMap<Node, Glyph> hmnode2glyph, int arcid) {

        Glyph glyph;
        String docking;
        Vector2d pos;

        if (hmnode2glyph.get(edge.getSource()) == null || hmnode2glyph.get(edge.getTarget()) == null)
            return null;
        Arc arc = new Arc();
        if (AttributeHelper.hasAttribute(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID))
            arc.setId((String) AttributeHelper.getAttributeValue(edge, SBGNConstants.SBGN_PATH, SBGNConstants.SBGN_ID, "", "", false));
        else
            arc.setId("arc" + arcid);
        String clazz = afglyph2clazz.get(SBGNHelper.getSBGNRole(edge));
        if (clazz == null)
            return null;
        arc.setClazz(clazz);
        ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(edge);
        Vector2d srcnodepos = AttributeHelper.getPositionVec2d(edge.getSource());
        Vector2d tgtnodepos = AttributeHelper.getPositionVec2d(edge.getTarget());

        // source node
        glyph = hmnode2glyph.get(edge.getSource());
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
        map.getGlyph().add(glyph);

        // target node
        glyph = hmnode2glyph.get(edge.getTarget());
        map.getGlyph().remove(glyph);
        Vector2d tgtnodesize = AttributeHelper.getSize(edge.getTarget());

        Vector2d srcpoint;
        if (bends.size() == 0)
            srcpoint = srcnodepos;
        else
            srcpoint = bends.get(bends.size() - 1);
        docking = (String) AttributeHelper.getAttributeValue(edge,
                GraphicAttributeConstants.DOCKING_PATH, GraphicAttributeConstants.TARGET, "", "", false);
        String bendin = (String) AttributeHelper.getAttributeValue(edge, "SBGN", "BendIn", null, "", false);

        if (docking.length() > 0) {
            pos = SBGNMLHelper.getDockingPosition(docking, tgtnodepos, tgtnodesize);
            arc.setTarget(glyph);
        } else
        if (bendin != null) {
            Port port = SBGNMLHelper.getPort(PortHelper.portPosition(tgtnodepos, tgtnodesize, bends, false, srcpoint), tgtnodepos, tgtnodesize, glyph);
            pos = new Vector2d(port.getX(), port.getY());
            SBGNMLHelper.setGlyphOrientation(glyph, port, tgtnodepos, tgtnodesize);
            arc.setTarget(port);
        } else {
            pos = SBGNMLHelper.getIntersectionEdgeNode(srcpoint, edge.getTarget(), tgtnodepos, tgtnodesize);
            arc.setTarget(glyph);
        }
        End end = new End();
        end.setX((float) pos.x);
        end.setY((float) pos.y);
        arc.setEnd(end);
        map.getGlyph().add(glyph);

        for (Vector2d bend : bends) {
            Next next = new Next();
            next.setX((float) bend.x);
            next.setY((float) bend.y);
            arc.getNext().add(next);
        }

        return arc;

    }

}
