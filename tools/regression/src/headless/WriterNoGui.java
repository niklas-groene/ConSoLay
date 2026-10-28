package headless;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import org.AttributeHelper;
import org.ErrorMsg;
import org.Vector2d;
import org.graffiti.graph.Graph;
import org.graffiti.graph.Node;
import org.sbgn.Language;
import org.sbgn.bindings.Arc;
import org.sbgn.bindings.Arcgroup;
import org.sbgn.bindings.Glyph;
import org.sbgn.bindings.ObjectFactory;
import org.sbgn.bindings.Port;
import org.sbgn.bindings.Sbgn;
import org.sbgned.SBGNAFGlyph;
import org.sbgned.SBGNAFTab;
import org.sbgned.SBGNERGlyph;
import org.sbgned.SBGNERTab;
import org.sbgned.SBGNHelper;
import org.sbgned.SBGNPDGlyph;
import org.sbgned.SBGNPDTab;

/**
 * Writes an SBGN map (PD, ER or AF) of a graph as SBGN-ML without the VANTED GUI, using the language's headless
 * writer, and refuses a document whose arcs reference glyphs or ports that are not in it.
 */
public class WriterNoGui {

    /** -Dheadless.skipValidation=true|false (default false): true writes the document even when references dangle. */
    static final boolean SKIP_VALIDATION = Boolean.getBoolean("headless.skipValidation");

    /** Names every arc whose source/target references a glyph or port without an id (JAXB would only NPE). */
    static String validate(Sbgn sbgn) {
        StringBuilder sb = new StringBuilder();
        for (org.sbgn.bindings.Map map : sbgn.getMap()) {
            Set<String> knownPorts = new HashSet<>();
            Set<Object> knownGlyphs = new HashSet<>();
            ArrayDeque<Glyph> stack = new ArrayDeque<>(map.getGlyph());
            collectGlyphs(stack, knownGlyphs, knownPorts);
            // ER/AF arcs carry their own ports and child glyphs (outcomes, cardinalities) which other arcs may reference.
            List<Arc> allArcs = new ArrayList<>(map.getArc());
            for (Arcgroup group : map.getArcgroup()) {
                allArcs.addAll(group.getArc());
                stack.addAll(group.getGlyph());
            }
            for (Arc arc : allArcs) {
                for (Port p : arc.getPort()) if (p.getId() != null) knownPorts.add(p.getId());
                stack.addAll(arc.getGlyph());
            }
            collectGlyphs(stack, knownGlyphs, knownPorts);
            for (Arc arc : allArcs) {
                for (String end : new String[] { "source", "target" }) {
                    Object ref = end.equals("source") ? arc.getSource() : arc.getTarget();
                    String problem = null;
                    if (ref == null) problem = "null";
                    else if (ref instanceof Port) {
                        Port p = (Port) ref;
                        if (p.getId() == null) problem = "port without id at (" + p.getX() + "," + p.getY() + ")";
                        else if (!knownPorts.contains(p.getId())) problem = "port " + p.getId() + " not attached to any glyph";
                    } else if (ref instanceof Glyph) {
                        Glyph gg = (Glyph) ref;
                        if (gg.getId() == null) problem = "glyph without id (" + gg.getClazz() + ")";
                        else if (!knownGlyphs.contains(gg)) problem = "glyph " + gg.getId() + " not in map";
                    }
                    if (problem != null) sb.append("arc ").append(arc.getId()).append(" (").append(arc.getClazz()).append(") ").append(end).append(": ").append(problem).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Empties {@code stack}, recording every glyph on it, their child glyphs and the ids of their ports. */
    private static void collectGlyphs(ArrayDeque<Glyph> stack, Set<Object> knownGlyphs, Set<String> knownPorts) {
        while (!stack.isEmpty()) {
            Glyph gl = stack.pop();
            knownGlyphs.add(gl);
            for (Port p : gl.getPort()) if (p.getId() != null) knownPorts.add(p.getId());
            stack.addAll(gl.getGlyph());
        }
    }

    public void write(OutputStream stream, Graph g) throws IOException {
        try {
            // Built from Sbgn (ObjectFactory adds the other bindings), JAXB declares the libsbgn namespace first; built
            // from the package, the order would follow ObjectFactory.getDeclaredMethods(), which has no fixed order.
            JAXBContext jaxbContext = JAXBContext.newInstance(Sbgn.class, ObjectFactory.class);
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);
            Sbgn sbgn;
            if (SBGNHelper.getSBGNRole(g).equals(SBGNPDTab.getMapName())) {
                sbgn = PDWriterNoGui.getSBGN(Language.PD.getName(), g, new String[]{SBGNPDGlyph.COMPLEX.name(), SBGNPDGlyph.SUBMAP.name()});
            } else if (SBGNHelper.getSBGNRole(g).equals(SBGNERTab.getMapName())) {
                sbgn = ERWriterNoGui.getSBGN(Language.ER.getName(), g, new String[]{SBGNERGlyph.ENTITY.name()});
            } else if (SBGNHelper.getSBGNRole(g).equals(SBGNAFTab.getMapName())) {
                sbgn = AFWriterNoGui.getSBGN(Language.AF.getName(), g, new String[]{SBGNAFGlyph.SUBMAP.name()});
            } else {
                throw new IOException("No SBGN map!");
            }
            String problems = validate(sbgn);
            if (!problems.isEmpty() && !SKIP_VALIDATION) throw new IOException("SBGN export: dangling references\n" + problems);
            marshaller.marshal(sbgn, stream);
        } catch (JAXBException jaxbException) {
            ErrorMsg.addErrorMessage(jaxbException);
        } finally {
            stream.close();
        }
    }

    /** The graph's nodes from top to bottom, then left to right: a deterministic order without an editor view. */
    static ArrayList<Node> nodesInReadingOrder(Graph graph) {
        ArrayList<Node> nodes = new ArrayList<>(graph.getNodes());
        nodes.sort((n1, n2) -> {
            Vector2d p1 = AttributeHelper.getPositionVec2d(n1);
            Vector2d p2 = AttributeHelper.getPositionVec2d(n2);
            int cmpY = Double.compare(p1.y, p2.y);
            return (cmpY != 0) ? cmpY : Double.compare(p1.x, p2.x);
        });
        return nodes;
    }

    /** The state of a state variable from its label: "value@variable", or the value alone. */
    static Glyph.State stateOf(String label) {
        Glyph.State state = new Glyph.State();
        String[] labelarr = label.split("@");
        if (labelarr.length == 2) {
            if (labelarr[0].length() > 0) {
                state.setValue(labelarr[0]);
            }
            if (labelarr[1].length() > 0) {
                state.setVariable(labelarr[1]);
            }
        } else {
            state.setValue(label);
        }
        return state;
    }
}
