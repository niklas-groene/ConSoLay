package headless;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import org.AlignmentSetting;
import org.AttributeHelper;
import org.LabelFrameSetting;
import org.Vector2d;
import org.graffiti.editor.MainFrame;
import org.graffiti.graph.Edge;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.graffiti.plugin.view.GraffitiViewComponent;
import org.graffiti.plugin.view.GraphElementComponent;
import org.graffiti.plugin.view.View;
import org.graffiti.plugins.attributecomponents.simplelabel.LabelComponent;
import org.graffiti.plugins.views.defaults.EdgeComponent;
import org.sbgn.GlyphClazz;
import org.sbgn.bindings.Arc;
import org.sbgn.bindings.Bbox;
import org.sbgn.bindings.Glyph;
import org.sbgn.bindings.Label;
import org.sbgn.bindings.Map;
import org.sbgn.bindings.Port;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNHelper;
import org.sbgned.SBGNPDGlyph;
import org.sbgned.sbgnml.SBGNMLHelper;

/**
 * Headless variant of SBGN-ED's SBGN-ML writer for Process Description maps: nodes in a deterministic order, ports
 * derived from the current geometry ({@link PortHelper}) and estimated label boxes where no view can measure them.
 */
public class PDWriterNoGui {

    private static HashMap<String, String> pdglyph2clazz = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;

        {
            this.put("UNSPECIFIEDENTITY", "unspecified entity");
            this.put("SIMPLECHEMICAL", "simple chemical");
            this.put("MACROMOLECULE", "macromolecule");
            this.put("NUCLEICACIDFEATURE", "nucleic acid feature");
            this.put("MULTIMERSIMPLECHEMICAL", "simple chemical multimer");
            this.put("MULTIMERMACROMOLECULE", "macromolecule multimer");
            this.put("MULTIMERNUCLEICACIDFEATURE", "nucleic acid feature multimer");
            this.put("SOURCESINK", "source and sink");
            this.put("PERTURBINGAGENT", "perturbing agent");
            this.put("PHENOTYPE", "phenotype");
            this.put("PROCESS", "process");
            this.put("OMITTEDPROCESS", "omitted process");
            this.put("UNCERTAINPROCESS", "uncertain process");
            this.put("ASSOCIATION", "association");
            this.put("DISSOCIATION", "dissociation");
            this.put("COMPLEX", "complex");
            this.put("MULTIMERCOMPLEX", "complex multimer");
            this.put("COMPARTMENT", "compartment");
            this.put("SUBMAP", "submap");
            this.put("TAGRIGHT", "tag");
            this.put("TAGLEFT", "tag");
            this.put("TAGDOWN", "tag");
            this.put("TAGUP", "tag");
            this.put("ANDOPERATOR", "and");
            this.put("OROPERATOR", "or");
            this.put("NOTOPERATOR", "not");
            this.put("PRODUCTION", "production");
            this.put("CONSUMPTION", "consumption");
            this.put("MODULATION", "modulation");
            this.put("STIMULATION", "stimulation");
            this.put("CATALYSIS", "catalysis");
            this.put("INHIBITION", "inhibition");
            this.put("NECESSARYSTIMULATION", "necessary stimulation");
            this.put("LOGICARC", "logic arc");
            this.put("EQUIVALENCEARC", "equivalence arc");
            this.put("REVERSIBLELHS", "production");
            this.put("REVERSIBLERHS", "production");
        }
    };

    public static Sbgn getSBGN(String language, Graph graph, String[] arrParents) {
        ArrayList<String> epns = new ArrayList(Arrays.asList(SBGNPDGlyph.SIMPLECHEMICAL.name(), SBGNPDGlyph.MACROMOLECULE.name(), SBGNPDGlyph.NUCLEICACIDFEATURE.name(), SBGNPDGlyph.MULTIMERSIMPLECHEMICAL.name(), SBGNPDGlyph.MULTIMERMACROMOLECULE.name(), SBGNPDGlyph.MULTIMERNUCLEICACIDFEATURE.name(), SBGNPDGlyph.COMPLEX.name(), SBGNPDGlyph.MULTIMERCOMPLEX.name(), SBGNPDGlyph.UNSPECIFIEDENTITY.name(), SBGNPDGlyph.SOURCESINK.name(), SBGNPDGlyph.PERTURBINGAGENT.name()));
        Sbgn sbgn = new Sbgn();
        Map map = new Map();
        map.setLanguage(language);
        sbgn.getMap().add(map);
        ArrayList<Node> nodes = WriterNoGui.nodesInReadingOrder(graph);
        HashMap<Node, Glyph> hmnode2glyph = new HashMap();
        ArrayList<String> knownGlyphIDs = new ArrayList();

        for(Node node : nodes) {
            if (AttributeHelper.hasAttribute(node, "sbgn", "glyphid")) {
                knownGlyphIDs.add((String)AttributeHelper.getAttributeValue(node, "sbgn", "glyphid", "", "", false));
            }
        }

        int glyphid = 1;
        int viewID = -nodes.size();

        for (Node node : nodes) {
            while (knownGlyphIDs.contains("glyph" + glyphid)) {
                ++glyphid;
            }

            Glyph glyph = getGlyph(node, glyphid);
            if (glyph != null) {
                if (!AttributeHelper.hasAttribute(node, "sbgn", "glyphid")) {
                    AttributeHelper.setAttribute(node, "sbgn", "glyphid", "glyph" + glyphid);
                    ++glyphid;
                }

                if (glyph.getClazz().equals(GlyphClazz.COMPARTMENT.getClazz())) {
                    glyph.setCompartmentOrder(new Float(viewID));
                    ++viewID;
                }

                hmnode2glyph.put(node, glyph);
                map.getGlyph().add(glyph);
            }
        }

        SBGNMLHelper.setCompartmentRefs(nodes, hmnode2glyph, epns);
        ArrayList<Glyph> rootglyphs = new ArrayList();
        ArrayList<Glyph> nonrootglyphs = new ArrayList();
        HashMap<Glyph, ArrayList<Glyph>> hmparentchildren = SBGNMLHelper.getParentChildren(graph.getNodes(), hmnode2glyph, arrParents, rootglyphs, nonrootglyphs);

        for(Glyph rootglyph : rootglyphs) {
            SBGNMLHelper.addChildGlyphs(rootglyph, hmparentchildren);
        }

        ArrayList<String> knownArcIDs = new ArrayList();

        for(Edge edge : graph.getEdges()) {
            if (AttributeHelper.hasAttribute(edge, "sbgn", "glyphid")) {
                knownArcIDs.add((String)AttributeHelper.getAttributeValue(edge, "sbgn", "glyphid", "", "", false));
            }
        }

        int arcid = 1;
        PortHelper.planPorts(graph);   // -Dcblayout.keepPorts=true: keep the input port groups

        for (Edge edge : graph.getEdges()) {
            while (knownArcIDs.contains("arc" + arcid)) {
                ++arcid;
            }

            while(knownGlyphIDs.contains("glyph" + glyphid)) {
                ++glyphid;
            }

            Arc arc = getArc(edge, map, hmnode2glyph, arcid, glyphid);
            if (arc != null) {
                if (!AttributeHelper.hasAttribute(edge, "sbgn", "glyphid")) {
                    AttributeHelper.setAttribute(edge, "sbgn", "glyphid", "arc" + arcid);
                    ++arcid;
                }

                map.getArc().add(arc);
                if (arc.getGlyph() != null) {
                    ++glyphid;
                }
            }
        }

        map.getGlyph().removeAll(nonrootglyphs);

        return sbgn;
    }

    private static Glyph getGlyph(Node node, int glyphid) {
        ArrayList<String> processnodes = new ArrayList(Arrays.asList(SBGNPDGlyph.PROCESS.name(), SBGNPDGlyph.OMITTEDPROCESS.name(), SBGNPDGlyph.UNCERTAINPROCESS.name()));
        ArrayList<String> logicaloperators = new ArrayList(Arrays.asList(SBGNPDGlyph.ANDOPERATOR.name(), SBGNPDGlyph.OROPERATOR.name(), SBGNPDGlyph.NOTOPERATOR.name()));
        Glyph glyph = new Glyph();
        if (AttributeHelper.hasAttribute(node, "sbgn", "glyphid")) {
            glyph.setId((String)AttributeHelper.getAttributeValue(node, "sbgn", "glyphid", "", "", false));
        } else {
            glyph.setId("glyph" + glyphid);
        }

        String clazz = pdglyph2clazz.get(SBGNHelper.getSBGNRole(node));
        if (clazz == null) {
            return null;
        } else {
            glyph.setClazz(clazz);
            if (SBGNHelper.getSBGNRole(node).contains("TAG")) {
                String orientation = SBGNHelper.getSBGNRole(node).replace("TAG", "");
                glyph.setOrientation(orientation.toLowerCase());
            }

            Vector2d nodepos = AttributeHelper.getPositionVec2d(node);
            Vector2d nodesize = AttributeHelper.getSize(node);
            Bbox bbox = SBGNMLHelper.getBoundingBox(nodepos, nodesize);
            glyph.setBbox(bbox);
            String label = AttributeHelper.getLabel(node, (String)null);
            String sbgnrole = SBGNHelper.getSBGNRole(node);
            if (!processnodes.contains(sbgnrole) && !logicaloperators.contains(sbgnrole) && !sbgnrole.equals(SBGNPDGlyph.SOURCESINK.name()) && label != null) {
                label = SBGNMLHelper.adjustLabel(label);
                if (label.length() > 0) {
                    Label labelobj = SBGNMLHelper.getLabelObj(label);
                    if (clazz.equals("compartment") || clazz.contains("complex")) {
                        Vector2d labelsize = SBGNMLHelper.getLabelSize(-1, node);
                        Vector2d labelpos = SBGNMLHelper.getInnerLabelPosition(-1, node, nodepos, nodesize, labelsize);
                        if (labelpos != null) {
                            bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                            labelobj.setBbox(bbox);
                        }
                    }

                    glyph.setLabel(labelobj);
                }
            }

            double d = (Double)AttributeHelper.getAttributeValue(node, "graphics", "gradient", new Double(0.0), new Double(0.0), false);
            if (d >= -1.0 && d < 0.0) {
                Glyph.Clone clone = new Glyph.Clone();
                glyph.setClone(clone);
            }

            char childid = 'a';

            for(int k = 1; k < 100; ++k) {
                label = AttributeHelper.getLabel(k, node, (String)null);
                if (label != null) {
                    label = SBGNMLHelper.adjustLabel(label);
                    if (label.length() > 0 && d >= -1.0 && d < 0.0 && AttributeHelper.getLabelAlignment(k, node).equals(AlignmentSetting.INSIDEBOTTOM)) {
                        Label labelobj = SBGNMLHelper.getLabelObj(label);
                        Vector2d labelsize = SBGNMLHelper.getLabelSize(k, node);
                        Vector2d labelpos = SBGNMLHelper.getInnerLabelPosition(k, node, nodepos, nodesize, labelsize);
                        bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                        labelobj.setBbox(bbox);
                        glyph.getClone().setLabel(labelobj);
                    } else {
                        Vector2d labelpos = SBGNMLHelper.getBorderLabelPosition(k, node, nodepos, nodesize);
                        LabelFrameSetting labelframesetting = AttributeHelper.getLabel(k, node).getLabelFrameSetting();
                        if (labelpos != null && (labelframesetting.equals(LabelFrameSetting.RECTANGLE) || labelframesetting.equals(LabelFrameSetting.ELLIPSE))) {
                            Glyph childglyph = new Glyph();
                            if (AttributeHelper.hasAttribute(node, "sbgn", "glyphid")) {
                                childglyph.setId((String)AttributeHelper.getAttributeValue(node, "sbgn", "glyphid", "", "", false) + childid);
                            } else {
                                childglyph.setId("glyph" + glyphid + childid);
                            }

                            Vector2d labelsize = SBGNMLHelper.getLabelSize(k, node);
                            bbox = SBGNMLHelper.getBoundingBox(labelpos, labelsize);
                            childglyph.setBbox(bbox);
                            if (labelframesetting.equals(LabelFrameSetting.RECTANGLE) && label.length() > 0) {
                                childglyph.setClazz("unit of information");
                                Label labelobj = SBGNMLHelper.getLabelObj(label);
                                childglyph.setLabel(labelobj);
                                glyph.getGlyph().add(childglyph);
                                ++childid;
                            } else if (labelframesetting.equals(LabelFrameSetting.ELLIPSE)) {
                                childglyph.setClazz("state variable");
                                if (label.length() > 0) {
                                    Glyph.State state = WriterNoGui.stateOf(label);
                                    childglyph.setState(state);
                                }

                                glyph.getGlyph().add(childglyph);
                                ++childid;
                            }
                        }
                    }
                }
            }

            return glyph;
        }
    }

    private static Arc getArc(Edge edge, Map map, HashMap<Node, Glyph> hmnode2glyph, int arcid, int glyphid) {
        if (hmnode2glyph.get(edge.getSource()) != null && hmnode2glyph.get(edge.getTarget()) != null) {
            Arc arc = new Arc();
            if (AttributeHelper.hasAttribute(edge, "sbgn", "glyphid")) {
                arc.setId((String)AttributeHelper.getAttributeValue(edge, "sbgn", "glyphid", "", "", false));
            } else {
                arc.setId("arc" + arcid);
            }

            String clazz = pdglyph2clazz.get(SBGNHelper.getSBGNRole(edge));
            if (clazz == null) {
                return null;
            } else {
                arc.setClazz(clazz);
                ArrayList<Vector2d> bends = AttributeHelper.getEdgeBends(edge);
                Vector2d srcnodepos = AttributeHelper.getPositionVec2d(edge.getSource());
                Vector2d tgtnodepos = AttributeHelper.getPositionVec2d(edge.getTarget());
                Glyph glyph = hmnode2glyph.get(edge.getSource());
                map.getGlyph().remove(glyph);
                Vector2d srcnodesize = AttributeHelper.getSize(edge.getSource());
                Vector2d tgtpoint;
                if (bends.size() == 0) {
                    tgtpoint = tgtnodepos;
                } else {
                    tgtpoint = bends.get(0);
                }

                String docking = (String)AttributeHelper.getAttributeValue(edge, "graphics.docking", "source", "", "", false);
                String bendout = (String)AttributeHelper.getAttributeValue(edge, "SBGN", "BendOut", null, "", false);
                Vector2d pos;
                if (docking.length() > 0) {
                    pos = SBGNMLHelper.getDockingPosition(docking, srcnodepos, srcnodesize);
                    arc.setSource(glyph);
                } else if (bendout != null) {
                    Port port = SBGNMLHelper.getPort(PortHelper.portPosition(edge, srcnodepos, srcnodesize, bends, true, tgtnodepos), srcnodepos, srcnodesize, glyph);
                    pos = new Vector2d(port.getX(), port.getY());
                    SBGNMLHelper.setGlyphOrientation(glyph, port, srcnodepos, srcnodesize);
                    arc.setSource(port);
                } else {
                    pos = SBGNMLHelper.getIntersectionEdgeNode(tgtpoint, edge.getSource(), srcnodepos, srcnodesize);
                    arc.setSource(glyph);
                }

                Arc.Start start = new Arc.Start();
                start.setX((float)pos.x);
                start.setY((float)pos.y);
                arc.setStart(start);
                map.getGlyph().add(glyph);
                glyph = hmnode2glyph.get(edge.getTarget());
                map.getGlyph().remove(glyph);
                Vector2d tgtnodesize = AttributeHelper.getSize(edge.getTarget());
                Vector2d srcpoint;
                if (bends.size() == 0) {
                    srcpoint = srcnodepos;
                } else {
                    srcpoint = bends.get(bends.size() - 1);
                }

                docking = (String)AttributeHelper.getAttributeValue(edge, "graphics.docking", "target", "", "", false);
                String bendin = (String)AttributeHelper.getAttributeValue(edge, "SBGN", "BendIn", null, "", false);
                if (docking.length() > 0) {
                    pos = SBGNMLHelper.getDockingPosition(docking, tgtnodepos, tgtnodesize);
                    arc.setTarget(glyph);
                } else if (bendin != null) {
                    try {
                        Port port = SBGNMLHelper.getPort(PortHelper.portPosition(edge, tgtnodepos, tgtnodesize, bends, false, srcnodepos), tgtnodepos, tgtnodesize, glyph);
                        pos = new Vector2d(port.getX(), port.getY());
                        SBGNMLHelper.setGlyphOrientation(glyph, port, tgtnodepos, tgtnodesize);
                        arc.setTarget(port);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                } else {
                    pos = SBGNMLHelper.getIntersectionEdgeNode(srcpoint, edge.getTarget(), tgtnodepos, tgtnodesize);
                    arc.setTarget(glyph);
                }

                Arc.End end = new Arc.End();
                end.setX((float)pos.x);
                end.setY((float)pos.y);
                arc.setEnd(end);
                map.getGlyph().add(glyph);
                if (SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.REVERSIBLELHS.name())) {
                    Object dummyObj = arc.getSource();
                    arc.setSource(arc.getTarget());
                    arc.setTarget(dummyObj);
                    Arc.Start dummyStart = new Arc.Start();
                    dummyStart.setX(arc.getStart().getX());
                    dummyStart.setY(arc.getStart().getY());
                    arc.getStart().setX(arc.getEnd().getX());
                    arc.getStart().setY(arc.getEnd().getY());
                    arc.getEnd().setX(dummyStart.getX());
                    arc.getEnd().setY(dummyStart.getY());
                    bends = SBGNMLHelper.reverseBends(bends);
                }

                for(Vector2d bend : bends) {
                    Arc.Next next = new Arc.Next();
                    next.setX((float)bend.x);
                    next.setY((float)bend.y);
                    arc.getNext().add(next);
                }

                if ((SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.CONSUMPTION.name()) || SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.REVERSIBLELHS.name())) && AttributeHelper.getLabelConsumption(edge, (String)null) != null || (SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.PRODUCTION.name()) || SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.REVERSIBLERHS.name())) && AttributeHelper.getLabelProduction(edge, (String)null) != null) {
                    glyph = getCardinalityGlyph(edge, arc, glyphid);
                    arc.getGlyph().add(glyph);
                }

                return arc;
            }
        } else {
            return null;
        }
    }

    private static Glyph getCardinalityGlyph(Edge edge, Arc arc, int glyphid) {
        Glyph glyph = new Glyph();
        glyph.setId("glyph" + glyphid);
        glyph.setClazz("cardinality");
        Label label = new Label();
        if (!SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.CONSUMPTION.name()) && !SBGNHelper.getSBGNRole(edge).equals(SBGNPDGlyph.REVERSIBLELHS.name())) {
            label.setText(AttributeHelper.getLabelProduction(edge, (String)null));
        } else {
            label.setText(AttributeHelper.getLabelConsumption(edge, (String)null));
        }

        glyph.setLabel(label);
        Bbox bbox = new Bbox();
        bbox.setX(0.0F);
        bbox.setY(0.0F);
        bbox.setW(0.0F);
        bbox.setH(0.0F);
        View view = null;
        boolean bboxFilledFromGUI = false;
        if (MainFrame.getInstance() != null && MainFrame.getInstance().getActiveEditorSession() != null) {
            view = MainFrame.getInstance().getActiveEditorSession().getActiveView();
        }
        if (view != null) {
            GraphElementComponent graphElementComponent = view.getComponentForElement(edge);
            if (graphElementComponent instanceof EdgeComponent) {
                EdgeComponent edgeComponent = (EdgeComponent) graphElementComponent;
                for (GraffitiViewComponent graffitiViewComponent : edgeComponent.getAttributeComponents()) {
                    if (graffitiViewComponent instanceof LabelComponent) {
                        LabelComponent labelComponent = (LabelComponent) graffitiViewComponent;
                        bbox.setX((float) labelComponent.getX());
                        bbox.setY((float) labelComponent.getY());
                        float frameThickness = (float) AttributeHelper.getFrameThickNess(edge);
                        bbox.setW((float) labelComponent.getWidth() - frameThickness);
                        bbox.setH((float) labelComponent.getHeight() - frameThickness);
                        bboxFilledFromGUI = true;
                        break;
                    }
                }
            }
        }
        if (!bboxFilledFromGUI) {
            estimateEdgeLabelBBox(edge, bbox);
        }

        glyph.setBbox(bbox);
        return glyph;
    }

    /**
     * Estimates an edge label's box where no label component can be measured: 12 pt text, 0.6 em per character,
     * 1.2 em per line, with its corner at the midpoint between the two end nodes.
     */
    private static void estimateEdgeLabelBBox(Edge edge, Bbox bbox) {
        String text = AttributeHelper.getLabel(edge, "");
        float fontSize = 12f;
        float avgCharWidth = 0.6f * fontSize;
        float lineHeight = 1.2f * fontSize;

        String[] lines = text.isEmpty() ? new String[]{""} : text.split("\\r?\\n");
        int maxChars = 0;
        for (String line : lines) {
            if (line.length() > maxChars) maxChars = line.length();
        }

        float width = Math.max(1f, maxChars * avgCharWidth);
        float height = Math.max(lineHeight, lines.length * lineHeight);

        float frameThickness = (float) AttributeHelper.getFrameThickNess(edge);
        bbox.setW(Math.max(0f, width - frameThickness));
        bbox.setH(Math.max(0f, height - frameThickness));

        Node s = edge.getSource();
        Node t = edge.getTarget();
        org.Vector2d ps = AttributeHelper.getPositionVec2d(s);
        org.Vector2d pt = AttributeHelper.getPositionVec2d(t);
        float midX = (float) ((ps.x + pt.x) / 2.0);
        float midY = (float) ((ps.y + pt.y) / 2.0);
        bbox.setX(midX);
        bbox.setY(midY);
    }
}
