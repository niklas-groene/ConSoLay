package layout.template.io;

import constraints.Priority;
import constraints.spec.ConstraintSpec;
import constraints.spec.ConstraintSpecs;
import layout.template.model.ComponentUse;
import layout.template.model.Condition;
import layout.template.model.LayoutRelation;
import layout.template.model.PatternEdge;
import layout.template.model.PatternNode;
import layout.template.model.Port;
import layout.template.model.RuleSystem;
import layout.template.model.StructureLink;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Template and rule-set objects ⇄ the JSON structure of the files ({@code .cbltemplate.json}, {@code .cblrules.json}).
 * The format is versioned ({@code "formatVersion"}); a file of a newer major version is refused with a clear message.
 * Fields this version does not know are kept and written back; constraint definitions of unknown types are kept
 * verbatim (see {@link ConstraintSpec#raw}).
 *
 * @author Niklas Gröne
 */
public final class TemplateCodec {

    public static final String TEMPLATE_FORMAT = "cbl-template";
    public static final String RULES_FORMAT = "cbl-rules";

    private TemplateCodec() { }

    /** A file that cannot be read as a template or rule set, with a message for the user. */
    public static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }
    }

    private static final Set<String> TEMPLATE_FIELDS = new HashSet<>(Arrays.asList("format", "formatVersion", "id", "revision",
            "name", "description", "kind", "source", "note", "importedWithoutConstraints", "coordinates", "nodes", "edges",
            "ports", "constraints", "components", "links", "relations", "dependencies", "lastIds"));

    public static Map<String, Object> toJson(TemplateDef t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", TEMPLATE_FORMAT);
        m.put("formatVersion", (double) TemplateDef.FORMAT_VERSION);
        m.put("id", t.id);
        m.put("revision", (double) t.revision);
        m.put("name", t.name);
        m.put("description", t.description == null ? "" : t.description);
        m.put("kind", t.kind.name().toLowerCase(Locale.ROOT));
        m.put("source", t.source);
        if (t.note != null) m.put("note", t.note);
        if (t.importedWithoutConstraints) m.put("importedWithoutConstraints", true);
        m.put("coordinates", TemplateDef.COORDINATES);
        List<Object> nodes = new ArrayList<>();
        for (PatternNode n : t.nodes) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", n.id);
            o.put("label", n.label == null ? "" : n.label);
            o.put("role", n.role == null ? "" : n.role);
            o.put("x", n.x);
            o.put("y", n.y);
            o.put("width", n.width);
            o.put("height", n.height);
            if (n.shape != null && !n.shape.isEmpty()) o.put("shape", n.shape);
            if (n.fill != null) o.put("fill", n.fill);
            o.put("conditions", conditions(n.conditions));
            nodes.add(o);
        }
        m.put("nodes", nodes);
        List<Object> edges = new ArrayList<>();
        for (PatternEdge e : t.edges) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", e.id);
            o.put("source", e.source);
            o.put("target", e.target);
            o.put("directed", e.directed);
            o.put("role", e.role == null ? "" : e.role);
            o.put("conditions", conditions(e.conditions));
            edges.add(o);
        }
        m.put("edges", edges);
        List<Object> ports = new ArrayList<>();
        for (Port p : t.ports) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", p.id);
            o.put("name", p.name);
            if (p.node != null) o.put("node", p.node);
            if (p.component != null) {
                o.put("component", p.component);
                o.put("componentPort", p.componentPort);
            }
            ports.add(o);
        }
        m.put("ports", ports);
        List<Object> cs = new ArrayList<>();
        for (ConstraintSpec c : t.constraints) cs.add(constraint(c));
        m.put("constraints", cs);
        if (t.isComposite() || !t.components.isEmpty()) {
            List<Object> comps = new ArrayList<>();
            for (ComponentUse c : t.components) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("template", ref(c.ref));
                o.put("x", c.x);
                o.put("y", c.y);
                o.put("includeConstraints", c.includeConstraints);
                o.put("priority", c.priority == null ? null : c.priority.name());
                o.put("disabledConstraints", new ArrayList<Object>(c.disabledConstraints));
                comps.add(o);
            }
            m.put("components", comps);
            List<Object> links = new ArrayList<>();
            for (StructureLink l : t.links) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", l.id);
                o.put("type", l.type.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                o.put("from", portRef(l.fromComponent, l.fromPort));
                o.put("to", portRef(l.toComponent, l.toPort));
                o.put("direction", l.direction.name().toLowerCase(Locale.ROOT));
                o.put("edgeConditions", conditions(l.edgeConditions));
                if (l.type == StructureLink.Type.PATH) o.put("maxLength", (double) l.maxLength);
                links.add(o);
            }
            m.put("links", links);
            List<Object> rels = new ArrayList<>();
            for (LayoutRelation r : t.relations) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", r.id);
                o.put("type", r.type.name());
                o.put("components", new ArrayList<Object>(r.components));
                List<Object> ps = new ArrayList<>();
                for (LayoutRelation.PortRef p : r.ports) ps.add(portRef(p.component, p.port));
                o.put("ports", ps);
                o.put("gap", r.gap);
                if (r.type == LayoutRelation.Type.GRID) o.put("columns", (double) r.columns);
                o.put("priority", r.priority.name());
                o.put("enabled", r.enabled);
                rels.add(o);
            }
            m.put("relations", rels);
            List<Object> deps = new ArrayList<>();
            for (TemplateDef d : t.dependencies.values()) deps.add(toJson(d));
            m.put("dependencies", deps);
        }
        if (!t.lastIds.isEmpty()) {
            Map<String, Object> ids = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : t.lastIds.entrySet()) ids.put(e.getKey(), (double) e.getValue());
            m.put("lastIds", ids);
        }
        putUnknown(m, t.unknownFields);
        return m;
    }

    private static Map<String, Object> portRef(String component, String port) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("component", component);
        o.put("port", port);
        return o;
    }

    private static Map<String, Object> ref(TemplateRef r) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (r == null) return o;
        o.put("id", r.id);
        o.put("revision", (double) r.revision);
        o.put("name", r.name);
        return o;
    }

    private static List<Object> conditions(List<Condition> cs) {
        List<Object> l = new ArrayList<>();
        for (Condition c : cs) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("attribute", c.attribute);
            o.put("op", opName(c.op));
            o.put("values", new ArrayList<Object>(c.values));
            l.add(o);
        }
        return l;
    }

    private static String opName(Condition.Op op) {
        switch (op) {
            case ANY: return "any";
            case EXISTS: return "exists";
            case EQUALS: return "equals";
            default: return "in";
        }
    }

    /** One constraint definition; an unknown type is written as it was read. */
    public static Map<String, Object> constraint(ConstraintSpec c) {
        if (c.raw != null) {
            try {
                Object o = Json.parse(c.raw);
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                    return m;
                }
            } catch (Json.ParseException ignored) {
                // fall through: write what is known
            }
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", c.id);
        o.put("type", c.type);
        if (c.name != null) o.put("name", c.name);
        o.put("priority", c.priority.name());
        o.put("enabled", c.enabled);
        Map<String, Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : c.roles.entrySet()) roles.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        o.put("roles", roles);
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : c.params.entrySet()) params.put(e.getKey(), paramValue(e.getKey(), e.getValue()));
        o.put("params", params);
        return o;
    }

    private static Object paramValue(String key, Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Value '" + key + "' is not a finite number; it cannot be saved.");
            return d;
        }
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            return m;
        }
        return v;
    }

    /** Reads a template; unknown fields are kept, a newer major format or a wrong file is refused. */
    public static TemplateDef fromJson(Object json) throws FormatException {
        Map<String, Object> m = object(json, "The file");
        String format = str(m, "format", null);
        if (format == null) throw new FormatException("This is not a template file (no \"format\" field).");
        if (RULES_FORMAT.equals(format)) throw new FormatException("This is a rule-set file, not a single template. Open it as a rule set.");
        if (!TEMPLATE_FORMAT.equals(format)) throw new FormatException("This is not a template file (format \"" + format + "\").");
        checkVersion(m, TemplateDef.FORMAT_VERSION, "template");
        TemplateDef t = new TemplateDef();
        t.id = str(m, "id", null);
        if (t.id == null || t.id.trim().isEmpty()) throw new FormatException("The template has no id.");
        t.revision = (int) num(m, "revision", 1);
        t.name = str(m, "name", "Untitled template");
        t.description = str(m, "description", "");
        String kind = str(m, "kind", "pattern");
        if ("composite".equals(kind)) t.kind = TemplateDef.Kind.COMPOSITE;
        else if ("pattern".equals(kind)) t.kind = TemplateDef.Kind.PATTERN;
        else throw new FormatException("Unknown template kind \"" + kind + "\".");
        t.source = str(m, "source", "file");
        t.note = str(m, "note", null);
        t.importedWithoutConstraints = bool(m, "importedWithoutConstraints", false);
        for (Object o : list(m, "nodes")) {
            Map<String, Object> n = object(o, "A node");
            PatternNode p = new PatternNode(req(n, "id", "A node"), num(n, "x", 0), num(n, "y", 0));
            p.label = str(n, "label", "");
            p.role = str(n, "role", "");
            p.width = num(n, "width", 40);
            p.height = num(n, "height", 30);
            p.shape = str(n, "shape", "");
            p.fill = str(n, "fill", null);
            p.conditions.addAll(conditions(n.get("conditions"), "Node " + p.id));
            t.nodes.add(p);
        }
        for (Object o : list(m, "edges")) {
            Map<String, Object> e = object(o, "An edge");
            PatternEdge p = new PatternEdge(req(e, "id", "An edge"), req(e, "source", "An edge"), req(e, "target", "An edge"),
                    bool(e, "directed", true));
            p.role = str(e, "role", "");
            p.conditions.addAll(conditions(e.get("conditions"), "Edge " + p.id));
            t.edges.add(p);
        }
        for (Object o : list(m, "ports")) {
            Map<String, Object> p = object(o, "A port");
            Port port = new Port(req(p, "id", "A port"), str(p, "name", ""), str(p, "node", null));
            port.component = str(p, "component", null);
            port.componentPort = str(p, "componentPort", null);
            t.ports.add(port);
        }
        for (Object o : list(m, "constraints")) t.constraints.add(constraint(o));
        for (Object o : list(m, "components")) {
            Map<String, Object> c = object(o, "A component");
            ComponentUse u = new ComponentUse(req(c, "id", "A component"), str(c, "name", ""), templateRef(c.get("template")));
            u.x = num(c, "x", 0);
            u.y = num(c, "y", 0);
            u.includeConstraints = bool(c, "includeConstraints", true);
            u.priority = priority(c.get("priority"), null);
            for (Object k : list(c, "disabledConstraints")) if (k instanceof String) u.disabledConstraints.add((String) k);
            t.components.add(u);
        }
        for (Object o : list(m, "links")) {
            Map<String, Object> l = object(o, "A link");
            Map<String, Object> from = object(l.get("from"), "A link's start");
            Map<String, Object> to = object(l.get("to"), "A link's end");
            StructureLink s = new StructureLink(req(l, "id", "A link"), str(from, "component", null), str(from, "port", null),
                    str(to, "component", null), str(to, "port", null));
            String type = str(l, "type", "edge");
            if ("edge".equals(type)) s.type = StructureLink.Type.EDGE;
            else if ("same-node".equals(type)) s.type = StructureLink.Type.SAME_NODE;
            else if ("path".equals(type)) s.type = StructureLink.Type.PATH;
            else throw new FormatException("Link " + s.id + " has the unknown type \"" + type + "\".");
            s.direction = "any".equals(str(l, "direction", "forward")) ? StructureLink.Direction.ANY : StructureLink.Direction.FORWARD;
            s.edgeConditions.addAll(conditions(l.get("edgeConditions"), "Link " + s.id));
            s.maxLength = (int) num(l, "maxLength", 1);
            t.links.add(s);
        }
        for (Object o : list(m, "relations")) {
            Map<String, Object> r = object(o, "A layout relation");
            LayoutRelation rel = new LayoutRelation(req(r, "id", "A layout relation"), LayoutRelation.Type.LEFT_OF);
            String type = str(r, "type", "LEFT_OF");
            try {
                rel.type = LayoutRelation.Type.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new FormatException("Layout relation " + rel.id + " has the unknown type \"" + type + "\".");
            }
            for (Object c : list(r, "components")) if (c instanceof String) rel.components.add((String) c);
            for (Object p : list(r, "ports")) {
                Map<String, Object> pr = object(p, "A port of a relation");
                rel.ports.add(new LayoutRelation.PortRef(str(pr, "component", null), str(pr, "port", null)));
            }
            rel.gap = num(r, "gap", 40);
            rel.columns = (int) num(r, "columns", 2);
            rel.priority = priority(r.get("priority"), Priority.NORMAL);
            rel.enabled = bool(r, "enabled", true);
            t.relations.add(rel);
        }
        for (Object o : list(m, "dependencies")) {
            TemplateDef d = fromJson(o);
            t.dependencies.put(d.id + "@" + d.revision, d);
        }
        if (m.get("lastIds") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("lastIds")).entrySet()) {
                Object n = e.getValue();
                if (n instanceof Double) t.lastIds.put(String.valueOf(e.getKey()), ((Double) n).intValue());
            }
        }
        keepUnknown(m, TEMPLATE_FIELDS, t.unknownFields);
        return t;
    }

    /**
     * One constraint definition; a type this version does not know keeps its JSON text and is read leniently: what does
     * not fit this version's value forms is left out here (the text keeps it), the element ids are still known.
     */
    public static ConstraintSpec constraint(Object o) throws FormatException {
        Map<String, Object> c = object(o, "A constraint");
        ConstraintSpec s = new ConstraintSpec();
        s.id = req(c, "id", "A constraint");
        s.type = req(c, "type", "Constraint " + s.id);
        boolean known = ConstraintSpecs.isKnown(s.type);
        if (!known) s.raw = Json.write(c);
        s.name = str(c, "name", null);
        try {
            s.priority = priority(c.get("priority"), Priority.NORMAL);
        } catch (FormatException e) {
            if (known) throw e;
        }
        s.enabled = bool(c, "enabled", true);
        Object roles = c.get("roles");
        if (roles != null && (known || roles instanceof Map)) {
            for (Map.Entry<String, Object> e : object(roles, "Constraint " + s.id + " roles").entrySet()) {
                List<String> ids = new ArrayList<>();
                if (!(e.getValue() instanceof List)) {
                    if (!known) continue;
                    throw new FormatException("Constraint " + s.id + ": role \"" + e.getKey() + "\" must be a list.");
                }
                for (Object id : (List<?>) e.getValue()) {
                    if (id != null && !(id instanceof String)) {
                        if (!known) continue;
                        throw new FormatException("Constraint " + s.id + ": role \"" + e.getKey() + "\" must list ids.");
                    }
                    ids.add((String) id);
                }
                s.roles.put(e.getKey(), ids);
            }
        }
        Object params = c.get("params");
        if (params != null && (known || params instanceof Map)) {
            for (Map.Entry<String, Object> e : object(params, "Constraint " + s.id + " params").entrySet()) {
                try {
                    s.params.put(e.getKey(), param(e.getValue(), s.id, e.getKey()));
                } catch (FormatException ex) {
                    if (known) throw ex;
                }
            }
        }
        return s;
    }

    private static Object param(Object v, String id, String key) throws FormatException {
        if (v == null || v instanceof Double || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            double[] d = new double[l.size()];
            for (int i = 0; i < d.length; i++) {
                if (!(l.get(i) instanceof Double)) throw new FormatException("Constraint " + id + ": value \"" + key + "\" must list numbers.");
                d[i] = (Double) l.get(i);
            }
            return d;
        }
        if (v instanceof Map) {
            LinkedHashMap<String, double[]> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                Object p = param(e.getValue(), id, key);
                if (!(p instanceof double[])) throw new FormatException("Constraint " + id + ": value \"" + key + "\" must give numbers per node.");
                m.put(String.valueOf(e.getKey()), (double[]) p);
            }
            return m;
        }
        throw new FormatException("Constraint " + id + ": value \"" + key + "\" has an unsupported form.");
    }

    private static List<Condition> conditions(Object o, String where) throws FormatException {
        List<Condition> l = new ArrayList<>();
        if (o == null) return l;
        if (!(o instanceof List)) throw new FormatException(where + ": \"conditions\" must be a list.");
        for (Object c : (List<?>) o) {
            Map<String, Object> m = object(c, where + " condition");
            String op = str(m, "op", "any");
            Condition.Op cop;
            switch (op) {
                case "any": cop = Condition.Op.ANY; break;
                case "exists": cop = Condition.Op.EXISTS; break;
                case "equals": cop = Condition.Op.EQUALS; break;
                case "in": cop = Condition.Op.IN_LIST; break;
                default: throw new FormatException(where + ": unknown condition \"" + op + "\".");
            }
            List<String> values = new ArrayList<>();
            for (Object v : list(m, "values")) values.add(String.valueOf(v));
            l.add(new Condition(str(m, "attribute", ""), cop, values));
        }
        return l;
    }

    static TemplateRef templateRef(Object o) throws FormatException {
        Map<String, Object> m = object(o, "A template reference");
        String id = str(m, "id", null);
        if (id == null) throw new FormatException("A template reference has no id.");
        return new TemplateRef(id, (int) num(m, "revision", 1), str(m, "name", id));
    }

    public static Map<String, Object> toJson(RuleSystem s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", RULES_FORMAT);
        m.put("formatVersion", (double) RuleSystem.FORMAT_VERSION);
        m.put("id", s.id);
        m.put("revision", (double) s.revision);
        m.put("name", s.name);
        m.put("description", s.description == null ? "" : s.description);
        List<Object> rules = new ArrayList<>();
        for (RuleSystem.Rule r : s.rules) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("description", r.description == null ? "" : r.description);
            o.put("enabled", r.enabled);
            o.put("priority", r.priority == null ? null : r.priority.name());
            o.put("template", ref(r.template));
            rules.add(o);
        }
        m.put("rules", rules);
        List<Object> ts = new ArrayList<>();
        for (TemplateDef t : s.templates.values()) ts.add(toJson(t));
        m.put("templates", ts);
        putUnknown(m, s.unknownFields);
        return m;
    }

    private static final Set<String> RULE_FIELDS = new HashSet<>(Arrays.asList("format", "formatVersion", "id", "revision",
            "name", "description", "rules", "templates"));

    public static RuleSystem rulesFromJson(Object json) throws FormatException {
        Map<String, Object> m = object(json, "The file");
        String format = str(m, "format", null);
        if (TEMPLATE_FORMAT.equals(format)) throw new FormatException("This is a single template, not a rule set.");
        if (!RULES_FORMAT.equals(format)) throw new FormatException("This is not a rule-set file.");
        checkVersion(m, RuleSystem.FORMAT_VERSION, "rule set");
        RuleSystem s = new RuleSystem();
        s.id = str(m, "id", s.id);
        s.revision = (int) num(m, "revision", 1);
        s.name = str(m, "name", "Untitled rule set");
        s.description = str(m, "description", "");
        for (Object o : list(m, "rules")) {
            Map<String, Object> r = object(o, "A rule");
            RuleSystem.Rule rule = new RuleSystem.Rule(req(r, "id", "A rule"), str(r, "name", ""), templateRef(r.get("template")));
            rule.description = str(r, "description", "");
            rule.enabled = bool(r, "enabled", true);
            rule.priority = priority(r.get("priority"), null);
            s.rules.add(rule);
        }
        for (Object o : list(m, "templates")) {
            TemplateDef t = fromJson(o);
            s.templates.put(t.id + "@" + t.revision, t);
        }
        keepUnknown(m, RULE_FIELDS, s.unknownFields);
        return s;
    }

    private static void checkVersion(Map<String, Object> m, int supported, String what) throws FormatException {
        int version = (int) num(m, "formatVersion", 1);
        if (version > supported) {
            throw new FormatException("The " + what + " was saved by a newer version of the add-on (format " + version
                    + "); this version reads format " + supported + ". Update the add-on to open it.");
        }
    }

    /** Writes the fields kept from reading back into {@code m}, as JSON values where they parse. */
    private static void putUnknown(Map<String, Object> m, Map<String, String> unknownFields) {
        for (Map.Entry<String, String> e : unknownFields.entrySet()) {
            try {
                m.put(e.getKey(), Json.parse(e.getValue()));
            } catch (Json.ParseException ex) {
                m.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Keeps the fields of {@code m} that are not {@code known} as JSON text, so that saving writes them back. */
    private static void keepUnknown(Map<String, Object> m, Set<String> known, Map<String, String> unknownFields) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!known.contains(e.getKey())) unknownFields.put(e.getKey(), Json.write(e.getValue()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o, String what) throws FormatException {
        if (!(o instanceof Map)) throw new FormatException(what + " must be an object.");
        return (Map<String, Object>) o;
    }

    private static List<?> list(Map<String, Object> m, String key) throws FormatException {
        Object o = m.get(key);
        if (o == null) return new ArrayList<>();
        if (!(o instanceof List)) throw new FormatException("\"" + key + "\" must be a list.");
        return (List<?>) o;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object o = m.get(key);
        return o instanceof String ? (String) o : def;
    }

    private static String req(Map<String, Object> m, String key, String what) throws FormatException {
        Object o = m.get(key);
        if (!(o instanceof String) || ((String) o).isEmpty()) throw new FormatException(what + " has no \"" + key + "\".");
        return (String) o;
    }

    private static double num(Map<String, Object> m, String key, double def) {
        Object o = m.get(key);
        return o instanceof Double ? (Double) o : def;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object o = m.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    private static Priority priority(Object o, Priority def) throws FormatException {
        if (o == null) return def;
        try {
            return Priority.valueOf(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            throw new FormatException("Unknown priority \"" + o + "\".");
        }
    }
}
