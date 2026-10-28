package constraints.spec;

import constraints.ConstraintKind;
import constraints.Priority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stored constraint definition, independent of any graph: its kind, its elements by the local ids of a pattern
 * ("n3", "e1") in named {@link #roles}, and its values in {@link #params}. Templates and copies keep constraints in
 * this form; a definition of a type this version does not know keeps its JSON text in {@link #raw}.
 *
 * @author Niklas Gröne
 */
public final class ConstraintSpec {

    /** Local id, unique in its template, e.g. "k4". */
    public String id;
    /** The {@link ConstraintKind} name, or the type name of an unknown definition. */
    public String type;
    /** Optional name the user gave it (null: the kind's title is shown). */
    public String name;
    /** Role → the element ids in order; an empty grid cell is a null entry. */
    public final LinkedHashMap<String, List<String>> roles = new LinkedHashMap<>();
    /**
     * Parameter name → value: a {@link Double}, {@link Boolean}, {@link String}, rectangle ({@code double[4]}), list of
     * numbers ({@code double[]}) or value per element ({@code Map<String, double[]>}); positions are in local coordinates.
     */
    public final LinkedHashMap<String, Object> params = new LinkedHashMap<>();
    public Priority priority = Priority.NORMAL;
    /** The definition's own switch: false = made switched off. */
    public boolean enabled = true;
    /** Unknown type: the definition's JSON as read (written back unchanged); else null. */
    public String raw;

    public ConstraintSpec() { }

    public ConstraintSpec(String id, ConstraintKind kind) {
        this.id = id;
        this.type = kind.name();
    }

    /** The kind, or null for a type this version does not know. */
    public ConstraintKind kind() {
        if (type == null) return null;
        try {
            return ConstraintKind.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<String> role(String role) {
        List<String> l = roles.get(role);
        return l == null ? Collections.<String>emptyList() : l;
    }

    public ConstraintSpec role(String role, List<String> ids) {
        roles.put(role, new ArrayList<>(ids));
        return this;
    }

    public ConstraintSpec param(String key, Object value) {
        params.put(key, value);
        return this;
    }

    /** A number parameter, or {@code def} if it is missing or no number. */
    public double num(String key, double def) {
        Object o = params.get(key);
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    public boolean bool(String key, boolean def) {
        Object o = params.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    /** A rectangle parameter {minX, minY, maxX, maxY}, or null. */
    public double[] rect(String key) {
        Object o = params.get(key);
        return o instanceof double[] && ((double[]) o).length == 4 ? (double[]) o : null;
    }

    /** A per-element parameter (element id → {x, y}), or an empty map. */
    @SuppressWarnings("unchecked")
    public Map<String, double[]> points(String key) {
        Object o = params.get(key);
        return o instanceof Map ? (Map<String, double[]>) o : Collections.<String, double[]>emptyMap();
    }

    /** Every element id the definition refers to (roles and the keys of per-element parameters), in order. */
    public Set<String> refs() {
        Set<String> s = new LinkedHashSet<>();
        for (List<String> l : roles.values()) for (String id : l) if (id != null) s.add(id);
        for (Object o : params.values()) {
            if (o instanceof Map) for (Object k : ((Map<?, ?>) o).keySet()) s.add(String.valueOf(k));
        }
        return s;
    }

    /** Replaces the element id {@code from} by {@code to} everywhere (roles and per-element parameters). */
    public void renameRef(String from, String to) {
        for (List<String> l : roles.values()) for (int i = 0; i < l.size(); i++) if (from.equals(l.get(i))) l.set(i, to);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, double[]> m = (Map<String, double[]>) e.getValue();
            if (!m.containsKey(from)) continue;
            LinkedHashMap<String, double[]> n = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> p : m.entrySet()) n.put(p.getKey().equals(from) ? to : p.getKey(), p.getValue());
            e.setValue(n);
        }
    }

    /** A deep copy: no array, list or map is shared with this definition. */
    public ConstraintSpec copy() {
        ConstraintSpec c = new ConstraintSpec();
        c.id = id;
        c.type = type;
        c.name = name;
        for (Map.Entry<String, List<String>> e : roles.entrySet()) c.roles.put(e.getKey(), new ArrayList<>(e.getValue()));
        for (Map.Entry<String, Object> e : params.entrySet()) c.params.put(e.getKey(), copyValue(e.getValue()));
        c.priority = priority;
        c.enabled = enabled;
        c.raw = raw;
        return c;
    }

    static Object copyValue(Object v) {
        if (v instanceof double[]) return ((double[]) v).clone();
        if (v instanceof Map) {
            LinkedHashMap<String, double[]> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                Object p = e.getValue();
                m.put(String.valueOf(e.getKey()), p instanceof double[] ? ((double[]) p).clone() : null);
            }
            return m;
        }
        if (v instanceof List) return new ArrayList<>((List<?>) v);
        return v;   // Double, Boolean, String: immutable
    }

    @Override
    public String toString() {
        return id + ":" + type + roles + params.keySet();
    }
}
