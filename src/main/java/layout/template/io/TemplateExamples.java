package layout.template.io;

import constraints.ConstraintKind;
import constraints.spec.ConstraintSpec;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.LayoutRelation;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prepared building blocks, always available in the library: SBGN reaction patterns with named ports and a few generic
 * shapes, and three composites made of them. They let users combine blocks, connect ports and set layout relations
 * without first building basic templates themselves. They have fixed ids ("example:…"), so composites that use them
 * keep working.
 *
 * @author Niklas Gröne
 */
public final class TemplateExamples {

    private TemplateExamples() { }

    public static List<TemplateDef> all() {
        List<TemplateDef> l = new ArrayList<>();
        TemplateDef reaction = reaction(), catalysed = catalysed();
        l.add(reaction);
        l.add(catalysed);
        l.add(cofactors());
        l.add(hub());
        l.add(chain());
        l.add(pair());
        TemplateDef series = reactionSeries(reaction);
        l.add(series);
        l.add(pathwayChain(series, reaction));
        l.add(catalysedFeeds(catalysed, reaction));
        return l;
    }

    private static TemplateDef base(String id, String name, String description) {
        TemplateDef t = new TemplateDef();
        t.id = "example:" + id;
        t.revision = 1;
        t.name = name;
        t.description = description;
        t.source = "example";
        return t;
    }

    private static PatternNode node(TemplateDef t, String label, String role, double x, double y, double w, double h) {
        PatternNode n = new PatternNode(t.nextNodeId(), x, y);
        n.label = label;
        n.role = role == null ? "" : role;
        n.width = w;
        n.height = h;
        n.conditions.add(TemplateFiles.roleCondition(role));
        t.nodes.add(n);
        return n;
    }

    private static void edge(TemplateDef t, PatternNode a, PatternNode b, String role) {
        PatternEdge e = new PatternEdge(t.nextEdgeId(), a.id, b.id, true);
        e.role = role == null ? "" : role;
        e.conditions.add(TemplateFiles.roleCondition(role));
        t.edges.add(e);
    }

    private static ConstraintSpec spec(TemplateDef t, ConstraintKind k, String name) {
        ConstraintSpec s = new ConstraintSpec(t.nextConstraintId(), k);
        s.name = name;
        t.constraints.add(s);
        return s;
    }

    private static void port(TemplateDef t, String name, PatternNode n) {
        t.ports.add(new Port(t.nextPortId(), name, n.id));
    }

    private static void component(TemplateDef t, String id, String name, TemplateDef used, double x, double y) {
        ComponentUse c = new ComponentUse(id, name, used.ref());
        c.x = x;
        c.y = y;
        t.components.add(c);
    }

    /** A port of the composite {@code t} that passes on the port {@code componentPort} of {@code component}. */
    private static void passPort(TemplateDef t, String id, String name, String component, String componentPort) {
        Port p = new Port(id, name, null);
        p.component = component;
        p.componentPort = componentPort;
        t.ports.add(p);
    }

    private static void embed(TemplateDef t, TemplateDef dependency) {
        t.dependencies.put(dependency.id + "@" + dependency.revision, dependency.copy());
    }

    /** Substrate → process → product in a row. */
    static TemplateDef reaction() {
        TemplateDef t = base("reaction", "Reaction", "A substrate consumed by a process that makes a product, laid out as a row.");
        PatternNode s = node(t, "Substrate", "SIMPLECHEMICAL", 30, 20, 60, 40);
        PatternNode p = node(t, "Process", "PROCESS", 130, 20, 20, 20);
        PatternNode q = node(t, "Product", "SIMPLECHEMICAL", 230, 20, 60, 40);
        edge(t, s, p, "CONSUMPTION");
        edge(t, p, q, "PRODUCTION");
        spec(t, ConstraintKind.ROW, "Reaction row").role("ordered", Arrays.asList(s.id, p.id, q.id)).param("gap", 80.0).param("even", false);
        port(t, "Input", s);
        port(t, "Process", p);
        port(t, "Output", q);
        return t;
    }

    /** A reaction with its catalyst above the process. */
    static TemplateDef catalysed() {
        TemplateDef t = base("catalysed-reaction", "Catalysed reaction", "A reaction whose process is catalysed by a macromolecule drawn above it.");
        PatternNode s = node(t, "Substrate", "SIMPLECHEMICAL", 30, 90, 60, 40);
        PatternNode p = node(t, "Process", "PROCESS", 130, 90, 20, 20);
        PatternNode q = node(t, "Product", "SIMPLECHEMICAL", 230, 90, 60, 40);
        PatternNode e = node(t, "Enzyme", "MACROMOLECULE", 130, 20, 80, 40);
        edge(t, s, p, "CONSUMPTION");
        edge(t, p, q, "PRODUCTION");
        edge(t, e, p, "CATALYSIS");
        spec(t, ConstraintKind.ROW, "Reaction row").role("ordered", Arrays.asList(s.id, p.id, q.id)).param("gap", 80.0).param("even", false);
        spec(t, ConstraintKind.ALIGN_CENTER, "Enzyme over process").role("nodes", Arrays.asList(e.id, p.id)).param("axis", "x").param("anchor", "centre");
        spec(t, ConstraintKind.SEPARATION_Y, "Enzyme above").role("first", Arrays.asList(e.id)).role("second", Arrays.asList(p.id))
                .param("axis", "y").param("distance", 70.0).param("exact", false);
        port(t, "Input", s);
        port(t, "Process", p);
        port(t, "Output", q);
        port(t, "Enzyme", e);
        return t;
    }

    /**
     * A reaction with a co-factor on each side (the PD convention of step 4 of the PD algorithm): the main substrate and
     * product on the axis through the process, the co-factors on the diagonals above it, mirrored at the axis.
     */
    static TemplateDef cofactors() {
        TemplateDef t = base("reaction-cofactors", "Reaction with co-factors", "A process that consumes a main substrate "
                + "and a co-factor and produces a main product and a co-factor: substrate and product on the axis, the "
                + "co-factors on the diagonals above the process, mirrored at the axis (a star).");
        double r = 100, d = r / Math.sqrt(2);
        PatternNode s = node(t, "Substrate", "SIMPLECHEMICAL", 30, 130, 60, 40);
        PatternNode p = node(t, "Process", "PROCESS", 30 + r, 130, 20, 20);
        PatternNode q = node(t, "Product", "SIMPLECHEMICAL", 30 + 2 * r, 130, 60, 40);
        PatternNode ci = node(t, "Co-factor in", "SIMPLECHEMICAL", 30 + r - d, 130 - d, 60, 40);
        PatternNode co = node(t, "Co-factor out", "SIMPLECHEMICAL", 30 + r + d, 130 - d, 60, 40);
        edge(t, s, p, "CONSUMPTION");
        edge(t, ci, p, "CONSUMPTION");
        edge(t, p, q, "PRODUCTION");
        edge(t, p, co, "PRODUCTION");
        // angles in degrees, 0 = right, clockwise on screen: substrate left, co-factor in upper left, product right,
        // co-factor out upper right
        spec(t, ConstraintKind.STAR, "Reaction star").role("hub", Arrays.asList(p.id)).role("ring", Arrays.asList(s.id, ci.id, q.id, co.id))
                .param("radius", r).param("startAngle", Math.PI).param("angles", new double[]{ 180, 225, 0, 315 });
        port(t, "Input", s);
        port(t, "Process", p);
        port(t, "Output", q);
        port(t, "Co-factor in", ci);
        port(t, "Co-factor out", co);
        return t;
    }

    /** A node with three neighbours around it. */
    static TemplateDef hub() {
        TemplateDef t = base("hub", "Hub with three neighbours", "Any node with edges to three others, laid out as a star.");
        PatternNode h = node(t, "Centre", null, 100, 100, 50, 40);
        PatternNode a = node(t, "A", null, 20, 100, 40, 30), b = node(t, "B", null, 180, 100, 40, 30), c = node(t, "C", null, 100, 180, 40, 30);
        edge(t, h, a, null);
        edge(t, h, b, null);
        edge(t, h, c, null);
        spec(t, ConstraintKind.STAR, "Star").role("hub", Arrays.asList(h.id)).role("ring", Arrays.asList(b.id, c.id, a.id))
                .param("radius", 90.0).param("startAngle", 0.0);
        port(t, "Centre", h);
        return t;
    }

    /** Three nodes in a directed chain, in a row. */
    static TemplateDef chain() {
        TemplateDef t = base("chain", "Chain of three", "Any three nodes joined A → B → C, laid out left to right.");
        PatternNode a = node(t, "A", null, 20, 20, 40, 30), b = node(t, "B", null, 110, 20, 40, 30), c = node(t, "C", null, 200, 20, 40, 30);
        edge(t, a, b, null);
        edge(t, b, c, null);
        spec(t, ConstraintKind.ROW, "Row").role("ordered", Arrays.asList(a.id, b.id, c.id)).param("gap", 70.0).param("even", true);
        port(t, "First", a);
        port(t, "Last", c);
        return t;
    }

    /** Two nodes joined by an edge, side by side. */
    static TemplateDef pair() {
        TemplateDef t = base("pair", "Connected pair", "Any two nodes joined A → B, A left of B on one line.");
        PatternNode a = node(t, "A", null, 20, 20, 40, 30), b = node(t, "B", null, 110, 20, 40, 30);
        edge(t, a, b, null);
        spec(t, ConstraintKind.ALIGN_MIDDLE, "Same height").role("nodes", Arrays.asList(a.id, b.id)).param("axis", "y").param("anchor", "middle");
        spec(t, ConstraintKind.SEPARATION_X, "A left of B").role("first", Arrays.asList(a.id)).role("second", Arrays.asList(b.id))
                .param("axis", "x").param("distance", 80.0).param("exact", false);
        port(t, "A", a);
        port(t, "B", b);
        return t;
    }

    /** Two reactions where the first one's product is the second one's substrate (a shared node). */
    static TemplateDef reactionSeries(TemplateDef reaction) {
        TemplateDef t = base("reaction-series", "Two reactions in series", "The product of the upstream reaction is the "
                + "substrate of the downstream one (a shared node); upstream left of downstream.");
        t.kind = TemplateDef.Kind.COMPOSITE;
        component(t, "c1", "Upstream", reaction, 40, 60);
        component(t, "c2", "Downstream", reaction, 300, 60);
        StructureLink l = new StructureLink("l1", "c1", "p3", "c2", "p1");
        l.type = StructureLink.Type.SAME_NODE;
        t.links.add(l);
        passPort(t, "p1", "Input", "c1", "p1");
        passPort(t, "p2", "Output", "c2", "p3");
        LayoutRelation align = new LayoutRelation("r1", LayoutRelation.Type.ALIGN_PORTS_H);
        align.ports.add(new LayoutRelation.PortRef("c1", "p2"));
        align.ports.add(new LayoutRelation.PortRef("c2", "p2"));
        t.relations.add(align);
        embed(t, reaction);
        return t;
    }

    /**
     * Two series of reactions, the second starting where the first ends, the second below the first: the pathway runs
     * top-down. A composite of two uses of "Two reactions in series" joined by a shared node and a COLUMN relation.
     */
    static TemplateDef pathwayChain(TemplateDef series, TemplateDef reaction) {
        TemplateDef t = base("pathway-chain", "Pathway chain", "Four reactions in a row: two series of two reactions, "
                + "the product of the upper series is the substrate of the lower one (a shared node); the lower series "
                + "lies below the upper one, so the pathway runs from top to bottom.");
        t.kind = TemplateDef.Kind.COMPOSITE;
        component(t, "c1", "Upper", series, 40, 40);
        component(t, "c2", "Lower", series, 40, 200);
        StructureLink l = new StructureLink("l1", "c1", "p2", "c2", "p1");   // the Output of the upper series is the Input of the lower one
        l.type = StructureLink.Type.SAME_NODE;
        t.links.add(l);
        passPort(t, "p1", "Input", "c1", "p1");
        passPort(t, "p2", "Output", "c2", "p2");
        LayoutRelation column = new LayoutRelation("r1", LayoutRelation.Type.COLUMN);
        column.components.add("c1");
        column.components.add("c2");
        column.gap = 60;
        t.relations.add(column);
        embed(t, series);
        embed(t, reaction);
        return t;
    }

    /** A catalysed reaction whose product is consumed by another reaction's process (a directed edge). */
    static TemplateDef catalysedFeeds(TemplateDef catalysed, TemplateDef reaction) {
        TemplateDef t = base("catalysed-feeds", "Catalysed reaction feeds reaction", "A catalysed reaction and a reaction; "
                + "an edge (consumption) runs from the first one's product to the second one's process. The catalysed "
                + "reaction lies left of the other one.");
        t.kind = TemplateDef.Kind.COMPOSITE;
        component(t, "c1", "Catalysed", catalysed, 40, 60);
        component(t, "c2", "Next", reaction, 320, 60);
        StructureLink l = new StructureLink("l1", "c1", "p3", "c2", "p2");
        l.edgeConditions.add(Condition.equalsTo(Condition.SBGN_ROLE, "CONSUMPTION"));
        t.links.add(l);
        LayoutRelation r = new LayoutRelation("r1", LayoutRelation.Type.LEFT_OF);
        r.components.add("c1");
        r.components.add("c2");
        r.gap = 60;
        t.relations.add(r);
        embed(t, catalysed);
        embed(t, reaction);
        return t;
    }
}
