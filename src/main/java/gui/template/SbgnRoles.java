package gui.template;

import layout.template.model.Condition;

import org.AttributeHelper;
import org.graffiti.graph.Graph;
import org.graffiti.graph.GraphElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The SBGN roles a condition can ask for, with readable names: the node and arc classes of SBGN Process Description (as
 * SBGN-ED stores them in {@code sbgn.role}) plus every role found in the open graph.
 *
 * @author Niklas Gröne
 */
final class SbgnRoles {

    private SbgnRoles() { }

    static final List<String> NODE_ROLES = Arrays.asList("MACROMOLECULE", "SIMPLECHEMICAL", "NUCLEICACIDFEATURE", "COMPLEX",
            "MULTIMERMACROMOLECULE", "MULTIMERSIMPLECHEMICAL", "MULTIMERNUCLEICACIDFEATURE", "MULTIMERCOMPLEX", "UNSPECIFIEDENTITY",
            "PERTURBINGAGENT", "SOURCESINK", "PHENOTYPE", "PROCESS", "OMITTEDPROCESS", "UNCERTAINPROCESS", "ASSOCIATION",
            "DISSOCIATION", "ANDOPERATOR", "OROPERATOR", "NOTOPERATOR", "COMPARTMENT", "SUBMAP", "TAGRIGHT", "TAGLEFT");

    static final List<String> EDGE_ROLES = Arrays.asList("CONSUMPTION", "PRODUCTION", "MODULATION", "STIMULATION", "CATALYSIS",
            "INHIBITION", "NECESSARYSTIMULATION", "LOGICARC", "EQUIVALENCEARC", "REVERSIBLE");

    private static final Map<String, String> PRETTY = new LinkedHashMap<>();

    static {
        PRETTY.put("MACROMOLECULE", "Macromolecule");
        PRETTY.put("SIMPLECHEMICAL", "Simple chemical");
        PRETTY.put("NUCLEICACIDFEATURE", "Nucleic acid feature");
        PRETTY.put("COMPLEX", "Complex");
        PRETTY.put("MULTIMERMACROMOLECULE", "Multimer macromolecule");
        PRETTY.put("MULTIMERSIMPLECHEMICAL", "Multimer simple chemical");
        PRETTY.put("MULTIMERNUCLEICACIDFEATURE", "Multimer nucleic acid feature");
        PRETTY.put("MULTIMERCOMPLEX", "Multimer complex");
        PRETTY.put("UNSPECIFIEDENTITY", "Unspecified entity");
        PRETTY.put("PERTURBINGAGENT", "Perturbing agent");
        PRETTY.put("SOURCESINK", "Source / sink");
        PRETTY.put("PHENOTYPE", "Phenotype");
        PRETTY.put("PROCESS", "Process");
        PRETTY.put("OMITTEDPROCESS", "Omitted process");
        PRETTY.put("UNCERTAINPROCESS", "Uncertain process");
        PRETTY.put("ASSOCIATION", "Association");
        PRETTY.put("DISSOCIATION", "Dissociation");
        PRETTY.put("ANDOPERATOR", "AND");
        PRETTY.put("OROPERATOR", "OR");
        PRETTY.put("NOTOPERATOR", "NOT");
        PRETTY.put("COMPARTMENT", "Compartment");
        PRETTY.put("SUBMAP", "Submap");
        PRETTY.put("TAGRIGHT", "Tag");
        PRETTY.put("TAGLEFT", "Tag");
        PRETTY.put("CONSUMPTION", "Consumption");
        PRETTY.put("PRODUCTION", "Production");
        PRETTY.put("MODULATION", "Modulation");
        PRETTY.put("STIMULATION", "Stimulation");
        PRETTY.put("CATALYSIS", "Catalysis");
        PRETTY.put("INHIBITION", "Inhibition");
        PRETTY.put("NECESSARYSTIMULATION", "Necessary stimulation");
        PRETTY.put("LOGICARC", "Logic arc");
        PRETTY.put("EQUIVALENCEARC", "Equivalence arc");
        PRETTY.put("REVERSIBLE", "Reversible");
    }

    /** Readable name of a known role, or null. */
    static String pretty(String role) {
        return role == null ? null : PRETTY.get(role.toUpperCase(Locale.ROOT));
    }

    /** The roles to offer for nodes: the known ones, then those found in {@code g}. */
    static List<String> nodeRoles(Graph g) {
        return roles(NODE_ROLES, g == null ? Collections.emptyList() : g.getNodes());
    }

    /** The roles to offer for edges: the known ones, then those found in {@code g}. */
    static List<String> edgeRoles(Graph g) {
        return roles(EDGE_ROLES, g == null ? Collections.emptyList() : g.getEdges());
    }

    private static List<String> roles(List<String> known, Collection<? extends GraphElement> found) {
        Set<String> s = new LinkedHashSet<>(known);
        for (GraphElement e : found) {
            Object o = AttributeHelper.getAttributeValue(e, "sbgn", "role", "", "", false);
            if (o != null && !String.valueOf(o).trim().isEmpty()) s.add(String.valueOf(o));
        }
        return new ArrayList<>(s);
    }

    /** "SBGN role" for {@code sbgn.role}, "Label" for {@code label}, else the path. */
    static String attributeName(String path) {
        if (Condition.SBGN_ROLE.equals(path)) return "SBGN role";
        if (Condition.LABEL.equals(path)) return "Label";
        return path;
    }
}
