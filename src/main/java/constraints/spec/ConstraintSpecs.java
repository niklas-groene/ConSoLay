package constraints.spec;

import constraints.Axis;
import constraints.BoundaryConstraintGraph;
import constraints.ClusterConstraintGraph;
import constraints.Constraint;
import constraints.ConstraintKind;
import constraints.Deactivation;
import constraints.DesiredPositionConstraintGraph;
import constraints.DistributionConstraintGraph;
import constraints.FixedRelativeConstraintGraph;
import constraints.GridPattern;
import constraints.HorizontalConstraint;
import constraints.LinePattern;
import constraints.LockConstraintGraph;
import constraints.MultiSeparationConstraintGraph;
import constraints.OrthogonalEdgeConstraintGraph;
import constraints.PageBoundaryConstraintGraph;
import constraints.Priority;
import constraints.SeparationConstraintGraph;
import constraints.StarPattern;
import constraints.VerticalConstraint;

import org.graffiti.graph.Edge;
import org.graffiti.graph.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Registry of one adapter per {@link ConstraintKind} that converts between constraints and stored definitions
 * ({@link #toSpec}, {@link #create}), validates definitions ({@link #validate}) and copies constraints without shared
 * state ({@link #copy}). The adapters read the values a constraint stores itself, never the current drawing; positions
 * are converted between graph and local coordinates by the {@link Naming} and the {@link Binding}.
 *
 * @author Niklas Gröne
 */
public final class ConstraintSpecs {

    private ConstraintSpecs() { }

    /** How the elements of a graph are named in a definition, and graph → local coordinates. */
    public interface Naming {
        /** Local id of the node, or null if it has none (it lies outside what is being stored). */
        String node(Node n);

        /** Local id of the edge, or null. */
        String edge(Edge e);

        /** Graph position → local position. */
        double[] toLocal(double x, double y);
    }

    /** Which node or edge of a graph each local id stands for, and local → graph coordinates. */
    public interface Binding {
        /** The node the id is assigned to, or null. */
        Node node(String id);

        /** The edge the id is assigned to, or null. */
        Edge edge(String id);

        /** Local position → graph position. */
        double[] toTarget(double x, double y);
    }

    /** Position and size of the pattern's elements, for values derived from the arrangement. */
    public interface Geometry {
        /** Local centre {x, y} of the node, or null. */
        double[] position(String nodeId);

        /** {width, height} of the node, or null. */
        double[] size(String nodeId);
    }

    /** A part a definition's elements play. */
    public static final class RoleInfo {
        public final String name;
        /** Label for the editor, e.g. "Left or upper node". */
        public final String label;
        public final int min, max;
        /** True if the order of the elements matters. */
        public final boolean ordered;
        /** True if the role holds edges instead of nodes. */
        public final boolean edges;
        /** True if the role may be left out entirely (another role takes its place). */
        public final boolean optional;

        RoleInfo(String name, String label, int min, int max, boolean ordered, boolean edges, boolean optional) {
            this.name = name; this.label = label; this.min = min; this.max = max;
            this.ordered = ordered; this.edges = edges; this.optional = optional;
        }
    }

    public enum ParamType { NUMBER, INTEGER, BOOLEAN, WORD, RECT, POINTS, NUMBERS }

    /** A value a definition keeps. */
    public static final class ParamInfo {
        public final String key;
        public final ParamType type;
        public final boolean required;
        /** Least value of a number (NaN: any finite value). */
        public final double min;
        /** WORD: the allowed words (null: any). */
        public final List<String> words;

        ParamInfo(String key, ParamType type, boolean required, double min, List<String> words) {
            this.key = key; this.type = type; this.required = required; this.min = min; this.words = words;
        }
    }

    private static RoleInfo nodes(String name, String label, int min, int max, boolean ordered) {
        return new RoleInfo(name, label, min, max, ordered, false, false);
    }

    private static ParamInfo number(String key, double min) {
        return new ParamInfo(key, ParamType.NUMBER, true, min, null);
    }

    private static ParamInfo flag(String key) {
        return new ParamInfo(key, ParamType.BOOLEAN, true, Double.NaN, null);
    }

    private static ParamInfo word(String key, String... allowed) {
        return new ParamInfo(key, ParamType.WORD, true, Double.NaN, Arrays.asList(allowed));
    }

    private static ParamInfo points(String key) {
        return new ParamInfo(key, ParamType.POINTS, true, Double.NaN, null);
    }

    /** Everything the registry knows about one kind. */
    public abstract static class Adapter {
        public final ConstraintKind kind;
        public final List<RoleInfo> roles;
        public final List<ParamInfo> params;

        Adapter(ConstraintKind kind, List<RoleInfo> roles, List<ParamInfo> params) {
            this.kind = kind;
            this.roles = Collections.unmodifiableList(roles);
            this.params = Collections.unmodifiableList(params);
        }

        /** Writes the roles and values of {@code c} into {@code s}. */
        abstract void write(Constraint c, ConstraintSpec s, Names n);

        /** Makes the constraint; the resolver throws IllegalArgumentException for an element that is not assigned. */
        abstract Constraint build(ConstraintSpec s, Resolver r);

        /** Checks beyond the generic ones (roles, counts, values). */
        void checkMore(ConstraintSpec s, List<String> problems) { }

        /** Fills values derived from the arrangement that are missing for the current elements. */
        void fillDerived(ConstraintSpec s, Geometry g) { }

        public RoleInfo role(String name) {
            for (RoleInfo r : roles) if (r.name.equals(name)) return r;
            return null;
        }
    }

    private static final Map<ConstraintKind, Adapter> ADAPTERS = new EnumMap<>(ConstraintKind.class);

    public static Adapter adapter(ConstraintKind kind) {
        return kind == null ? null : ADAPTERS.get(kind);
    }

    /** True if this version can apply and edit definitions of {@code type}. */
    public static boolean isKnown(String type) {
        try {
            return type != null && ADAPTERS.containsKey(ConstraintKind.valueOf(type));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void register(Adapter a) {
        ADAPTERS.put(a.kind, a);
    }

    static {
        for (HorizontalConstraint.HorizontalConstraintType t : HorizontalConstraint.HorizontalConstraintType.values()) {
            ConstraintKind k = t == HorizontalConstraint.HorizontalConstraintType.TOP ? ConstraintKind.ALIGN_TOP
                    : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? ConstraintKind.ALIGN_BOTTOM : ConstraintKind.ALIGN_MIDDLE;
            String anchor = t == HorizontalConstraint.HorizontalConstraintType.TOP ? "top"
                    : t == HorizontalConstraint.HorizontalConstraintType.BOTTOM ? "bottom" : "middle";
            register(new Adapter(k, list(nodes("nodes", "Aligned nodes", 2, 0, false)), list(word("axis", "y"), word("anchor", anchor))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    s.role("nodes", n.nodes(c.getNodes())).param("axis", "y").param("anchor", anchor);
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new HorizontalConstraint(r.nodes("nodes"), t);
                }
            });
        }
        for (VerticalConstraint.VerticalConstraintType t : VerticalConstraint.VerticalConstraintType.values()) {
            ConstraintKind k = t == VerticalConstraint.VerticalConstraintType.LEFT ? ConstraintKind.ALIGN_LEFT
                    : t == VerticalConstraint.VerticalConstraintType.RIGHT ? ConstraintKind.ALIGN_RIGHT : ConstraintKind.ALIGN_CENTER;
            String anchor = t == VerticalConstraint.VerticalConstraintType.LEFT ? "left"
                    : t == VerticalConstraint.VerticalConstraintType.RIGHT ? "right" : "centre";
            register(new Adapter(k, list(nodes("nodes", "Aligned nodes", 2, 0, false)), list(word("axis", "x"), word("anchor", anchor))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    s.role("nodes", n.nodes(c.getNodes())).param("axis", "x").param("anchor", anchor);
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new VerticalConstraint(r.nodes("nodes"), t);
                }
            });
        }

        for (Axis a : Axis.values()) {
            boolean x = a == Axis.X;
            String ax = x ? "x" : "y";
            register(new Adapter(x ? ConstraintKind.SEPARATION_X : ConstraintKind.SEPARATION_Y,
                    list(nodes("first", x ? "Left node" : "Upper node", 1, 1, true), nodes("second", x ? "Right node" : "Lower node", 1, 1, true)),
                    list(word("axis", ax), number("distance", Double.NaN), flag("exact"))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    SeparationConstraintGraph g = (SeparationConstraintGraph) c;
                    s.role("first", n.nodes(c.getNodes().subList(0, 1))).role("second", n.nodes(c.getNodes().subList(1, 2)))
                            .param("axis", ax).param("distance", g.getDistance()).param("exact", g.isFixed());
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new SeparationConstraintGraph(Arrays.asList(r.node("first"), r.node("second")), a.toSeparationType(),
                            s.bool("exact", false), s.num("distance", 0));
                }
            });
            register(new Adapter(x ? ConstraintKind.ORDER_X : ConstraintKind.ORDER_Y,
                    list(nodes("ordered", x ? "Left to right" : "Top to bottom", 2, 0, true)),
                    list(word("axis", ax), number("gap", 0), flag("exact"))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    MultiSeparationConstraintGraph m = (MultiSeparationConstraintGraph) c;
                    s.role("ordered", n.nodes(c.getNodes())).param("axis", ax).param("gap", m.getGap()).param("exact", m.isExact());
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new MultiSeparationConstraintGraph(r.nodes("ordered"), a, s.num("gap", 0), s.bool("exact", false));
                }
            });
            register(new Adapter(x ? ConstraintKind.DISTRIBUTE_X : ConstraintKind.DISTRIBUTE_Y,
                    list(nodes("ordered", x ? "Left to right" : "Top to bottom", 2, 0, true)),
                    list(word("axis", ax), number("spacing", 0))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    s.role("ordered", n.nodes(c.getNodes())).param("axis", ax).param("spacing", ((DistributionConstraintGraph) c).getSpacing());
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new DistributionConstraintGraph(r.nodes("ordered"), a.toSeparationType(), s.num("spacing", 0));
                }
            });
            register(new Adapter(x ? ConstraintKind.DIVIDE_X : ConstraintKind.DIVIDE_Y,
                    list(nodes("before", x ? "Left of the line" : "Above the line", 1, 0, false),
                            nodes("after", x ? "Right of the line" : "Below the line", 1, 0, false)),
                    list(word("axis", ax), number("gap", 0))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    BoundaryConstraintGraph b = (BoundaryConstraintGraph) c;
                    s.role("before", n.nodes(b.getBefore())).role("after", n.nodes(b.getAfter())).param("axis", ax).param("gap", b.getGap());
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new BoundaryConstraintGraph(r.nodes("before"), r.nodes("after"), a, s.num("gap", 0));
                }
            });
        }

        register(new Adapter(ConstraintKind.RIGID, list(nodes("nodes", "Members", 2, 0, false)),
                list(flag("fixedPosition"), points("offsets"))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                FixedRelativeConstraintGraph f = (FixedRelativeConstraintGraph) c;
                LinkedHashMap<String, double[]> offsets = new LinkedHashMap<>();
                for (Node m : c.getNodes()) {
                    double[] o = f.getOffset(m);
                    offsets.put(n.node(m), o == null ? new double[]{0, 0} : new double[]{o[0], o[1]});
                }
                s.role("nodes", n.nodes(c.getNodes())).param("fixedPosition", f.isFixedPosition()).param("offsets", offsets);
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                List<Node> ns = r.nodes("nodes");
                return new FixedRelativeConstraintGraph(ns, s.bool("fixedPosition", false), r.points("offsets"));
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                requirePointsFor(s, "offsets", s.role("nodes"), "offset in the group", p);
            }
            @Override void fillDerived(ConstraintSpec s, Geometry g) {
                List<String> members = s.role("nodes");
                Map<String, double[]> old = s.points("offsets");
                LinkedHashMap<String, double[]> now = new LinkedHashMap<>();
                // The reference point of the kept offsets in the current arrangement: centroid of those members minus
                // the mean of their offsets; a new member gets its offset from it, so the old shape stays.
                double cx = 0, cy = 0, ox = 0, oy = 0;
                int kept = 0;
                for (String id : members) {
                    double[] o = old.get(id), p = g.position(id);
                    if (o == null || p == null) continue;
                    cx += p[0]; cy += p[1]; ox += o[0]; oy += o[1]; kept++;
                }
                double rx, ry;
                if (kept > 0) {
                    rx = cx / kept - ox / kept;
                    ry = cy / kept - oy / kept;
                } else {
                    double[] c = centroid(members, g);
                    rx = c[0];
                    ry = c[1];
                }
                for (String id : members) {
                    double[] o = old.get(id), p = g.position(id);
                    if (o != null) now.put(id, o.clone());
                    else if (p != null) now.put(id, new double[]{p[0] - rx, p[1] - ry});
                }
                s.params.put("offsets", now);
            }
        });
        register(new Adapter(ConstraintKind.CLUSTER, list(nodes("nodes", "Members", 1, 0, false)),
                list(number("padding", 0), number("margin", 0))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                ClusterConstraintGraph k = (ClusterConstraintGraph) c;
                s.role("nodes", n.nodes(c.getNodes())).param("padding", k.getPadding()).param("margin", k.getMargin());
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                return new ClusterConstraintGraph(r.nodes("nodes"), s.num("padding", 15), s.num("margin", 10));
            }
        });
        register(new Adapter(ConstraintKind.AREA, list(nodes("nodes", "Kept inside", 1, 0, false)),
                list(new ParamInfo("bounds", ParamType.RECT, true, Double.NaN, null), number("margin", 0))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                PageBoundaryConstraintGraph p = (PageBoundaryConstraintGraph) c;
                double[] b = p.getBase();
                double[] lo = n.toLocal(b[0], b[1]), hi = n.toLocal(b[2], b[3]);
                s.role("nodes", n.nodes(c.getNodes())).param("bounds", new double[]{lo[0], lo[1], hi[0], hi[1]})
                        .param("margin", p.getMargin());
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                double[] b = s.rect("bounds");
                double[] lo = r.binding.toTarget(b[0], b[1]), hi = r.binding.toTarget(b[2], b[3]);
                return new PageBoundaryConstraintGraph(r.nodes("nodes"), new double[]{lo[0], lo[1], hi[0], hi[1]}, s.num("margin", 40));
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                double[] b = s.rect("bounds");
                if (b != null && (b[2] < b[0] || b[3] < b[1])) p.add("The area's bounds are inverted (max < min).");
            }
            @Override void fillDerived(ConstraintSpec s, Geometry g) {
                if (s.rect("bounds") != null) return;
                double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
                for (String id : s.role("nodes")) {
                    double[] p = g.position(id), z = g.size(id);
                    if (p == null) continue;
                    double hw = z == null ? 0 : z[0] / 2, hh = z == null ? 0 : z[1] / 2;
                    b[0] = Math.min(b[0], p[0] - hw); b[1] = Math.min(b[1], p[1] - hh);
                    b[2] = Math.max(b[2], p[0] + hw); b[3] = Math.max(b[3], p[1] + hh);
                }
                if (b[0] <= b[2]) s.params.put("bounds", b);
            }
        });

        for (boolean horizontal : new boolean[]{true, false}) {
            String dir = horizontal ? "horizontal" : "vertical";
            register(new Adapter(horizontal ? ConstraintKind.EDGE_HORIZONTAL : ConstraintKind.EDGE_VERTICAL,
                    list(new RoleInfo("edge", "Edge", 1, 1, false, true, true),
                            new RoleInfo("ends", "End nodes (without an edge)", 2, 2, false, false, true)),
                    list(word("direction", dir))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    OrthogonalEdgeConstraintGraph o = (OrthogonalEdgeConstraintGraph) c;
                    if (o.getEdge() != null) s.role("edge", Collections.singletonList(n.edge(o.getEdge())));
                    else s.role("ends", n.nodes(c.getNodes()));
                    s.param("direction", dir);
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    if (!s.role("edge").isEmpty()) return new OrthogonalEdgeConstraintGraph(r.edge("edge"), horizontal);
                    List<Node> ends = r.nodes("ends");
                    return new OrthogonalEdgeConstraintGraph(ends.get(0), ends.get(1), horizontal);
                }
                @Override void checkMore(ConstraintSpec s, List<String> p) {
                    boolean e = !s.role("edge").isEmpty(), n = !s.role("ends").isEmpty();
                    if (e == n) p.add("Needs either one edge or two end nodes.");
                }
            });
        }

        register(new Adapter(ConstraintKind.PIN, list(nodes("nodes", "Pinned nodes", 1, 0, false)),
                list(points("positions"))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                LockConstraintGraph l = (LockConstraintGraph) c;
                LinkedHashMap<String, double[]> pos = new LinkedHashMap<>();
                for (Node m : c.getNodes()) {
                    double[] p = l.getPosition(m);
                    if (p != null) pos.put(n.node(m), n.toLocal(p[0], p[1]));
                }
                s.role("nodes", n.nodes(c.getNodes())).param("positions", pos);
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                return new LockConstraintGraph(r.orderedPoints("nodes", "positions"));
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                requirePointsFor(s, "positions", s.role("nodes"), "pin position", p);
            }
            @Override void fillDerived(ConstraintSpec s, Geometry g) {
                fillPositions(s, "positions", g);
            }
        });
        register(new Adapter(ConstraintKind.TARGET, list(nodes("nodes", "Pulled nodes", 1, 0, false)),
                list(points("targets"), number("weight", 0))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                DesiredPositionConstraintGraph d = (DesiredPositionConstraintGraph) c;
                LinkedHashMap<String, double[]> pos = new LinkedHashMap<>();
                for (Node m : c.getNodes()) {
                    double[] p = d.getTarget(m);
                    if (p != null) pos.put(n.node(m), n.toLocal(p[0], p[1]));
                }
                s.role("nodes", n.nodes(c.getNodes())).param("targets", pos).param("weight", d.getWeight());
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                return new DesiredPositionConstraintGraph(r.orderedPoints("nodes", "targets"), s.num("weight", 1));
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                requirePointsFor(s, "targets", s.role("nodes"), "target position", p);
                if (s.num("weight", 1) <= 0) p.add("The strength must be above 0.");
            }
            @Override void fillDerived(ConstraintSpec s, Geometry g) {
                fillPositions(s, "targets", g);
            }
        });

        for (boolean row : new boolean[]{true, false}) {
            register(new Adapter(row ? ConstraintKind.ROW : ConstraintKind.COLUMN,
                    list(nodes("ordered", row ? "Left to right" : "Top to bottom", 2, 0, true)),
                    list(number("gap", 0), flag("even"))) {
                @Override void write(Constraint c, ConstraintSpec s, Names n) {
                    LinePattern l = (LinePattern) c;
                    s.role("ordered", n.nodes(c.getNodes())).param("gap", l.getGap()).param("even", l.isEven());
                }
                @Override Constraint build(ConstraintSpec s, Resolver r) {
                    return new LinePattern(r.nodes("ordered"), row, s.num("gap", 80), s.bool("even", false));
                }
            });
        }
        register(new Adapter(ConstraintKind.GRID,
                list(new RoleInfo("cells", "Cells, row by row (empty cells allowed)", 2, 0, true, false, false)),
                list(new ParamInfo("columns", ParamType.INTEGER, true, 1, null), number("columnGap", 0), number("rowGap", 0))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                GridPattern g = (GridPattern) c;
                List<List<Node>> cells = g.getCells();
                int cols = 0;
                for (List<Node> r : cells) cols = Math.max(cols, r.size());
                List<String> flat = new ArrayList<>();
                for (List<Node> r : cells) {
                    for (int i = 0; i < cols; i++) {
                        Node m = i < r.size() ? r.get(i) : null;
                        flat.add(m == null ? null : n.node(m));
                    }
                }
                s.role("cells", flat).param("columns", (double) cols).param("columnGap", g.getColumnGap()).param("rowGap", g.getRowGap());
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                int cols = (int) Math.round(s.num("columns", 1));
                List<Node> flat = r.nodesWithGaps("cells");
                List<List<Node>> rows = new ArrayList<>();
                for (int i = 0; i < flat.size(); i += cols) rows.add(new ArrayList<>(flat.subList(i, Math.min(flat.size(), i + cols))));
                return new GridPattern(rows, s.num("columnGap", 100), s.num("rowGap", 80));
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                int cols = (int) Math.round(s.num("columns", 0));
                if (cols >= 1 && s.role("cells").size() % cols != 0)
                    p.add("The cells do not fill whole rows of " + cols + " columns (use empty cells).");
            }
        });
        // "angles": optional, one angle per ring node in degrees (0 = right, clockwise on screen), else even spacing
        register(new Adapter(ConstraintKind.STAR,
                list(nodes("hub", "Centre", 1, 1, true), nodes("ring", "Around it, in order", 1, 0, true)),
                list(number("radius", 0), number("startAngle", Double.NaN), new ParamInfo("angles", ParamType.NUMBERS, false, Double.NaN, null))) {
            @Override void write(Constraint c, ConstraintSpec s, Names n) {
                StarPattern st = (StarPattern) c;
                s.role("hub", n.nodes(Collections.singletonList(st.getHub()))).role("ring", n.nodes(st.getRing()))
                        .param("radius", st.getRadius()).param("startAngle", st.getStartAngle());
                double[] a = st.getAngles();
                int k = 0;
                while (a != null && k < a.length && k < st.getRing().size() && Double.isFinite(a[k])) k++;
                if (k > 0) {
                    double[] deg = new double[k];
                    for (int i = 0; i < k; i++) deg[i] = Math.toDegrees(a[i]);
                    s.param("angles", deg);
                }
            }
            @Override Constraint build(ConstraintSpec s, Resolver r) {
                Object a = s.params.get("angles");
                double[] rad = null;
                if (a instanceof double[]) {
                    rad = new double[((double[]) a).length];
                    for (int i = 0; i < rad.length; i++) rad[i] = Math.toRadians(((double[]) a)[i]);
                }
                return new StarPattern(r.node("hub"), r.nodes("ring"), s.num("radius", 120), s.num("startAngle", 0), rad);
            }
            @Override void checkMore(ConstraintSpec s, List<String> p) {
                if (s.num("radius", 1) <= 0) p.add("The radius must be above 0.");
                Object a = s.params.get("angles");
                if (a instanceof double[] && ((double[]) a).length > s.role("ring").size())
                    p.add("More angles (" + ((double[]) a).length + ") than ring nodes (" + s.role("ring").size() + ").");
            }
        });
    }

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /**
     * The stored definition of {@code c}: its kind, its elements by the local ids of {@code naming}, its values (positions
     * in local coordinates), priority and its own switch. The id and name are left to the caller.
     *
     * @throws IllegalArgumentException if a node or edge of {@code c} has no local id (it lies outside), or the type is
     *                                  unknown
     */
    public static ConstraintSpec toSpec(Constraint c, Naming naming) {
        Adapter a = adapter(c.getKind());
        if (a == null) throw new IllegalArgumentException("Unsupported constraint type " + c.getClass().getSimpleName() + ".");
        ConstraintSpec s = new ConstraintSpec();
        s.type = a.kind.name();
        a.write(c, s, new Names(naming));
        s.priority = c.getPriority();
        s.enabled = c.isSwitchedOnItself();
        return s;
    }

    /**
     * A new constraint from {@code s} on the elements {@code binding} assigns (positions converted to graph
     * coordinates), with the definition's priority and switch. No origin is set.
     *
     * @throws IllegalArgumentException with a readable reason if the type is unknown, an element is not assigned or a
     *                                  value is missing or invalid
     */
    public static Constraint create(ConstraintSpec s, Binding binding) {
        Adapter a = adapter(s.kind());
        if (a == null) throw new IllegalArgumentException("Unknown constraint type '" + s.type + "': this version cannot apply it.");
        List<String> problems = validateValues(s, a);
        if (problems.isEmpty()) a.checkMore(s, problems);
        if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0));
        Constraint c = a.build(s, new Resolver(s, binding));
        c.setPriority(s.priority == null ? Priority.NORMAL : s.priority);
        c.setEnabled(s.enabled);
        return c;
    }

    /**
     * A copy of {@code c} on the mapped elements ({@code nodes} / {@code edges} give the element to use for each; the
     * identity keeps them), with its own lists, maps and values, the same priority, switch, automatic flag and origin.
     * Nothing mutable is shared with {@code c}; conflict decisions are not copied (the next resolution decides).
     */
    public static Constraint copy(Constraint c, Function<Node, Node> nodes, Function<Edge, Edge> edges) {
        Map<Node, String> nodeIds = new IdentityHashMap<>();
        Map<String, Node> byNodeId = new HashMap<>();
        Map<Edge, String> edgeIds = new IdentityHashMap<>();
        Map<String, Edge> byEdgeId = new HashMap<>();
        ConstraintSpec s = toSpec(c, new Naming() {
            @Override public String node(Node n) {
                return nodeIds.computeIfAbsent(n, k -> {
                    String id = "n" + nodeIds.size();
                    byNodeId.put(id, nodes.apply(k));
                    return id;
                });
            }
            @Override public String edge(Edge e) {
                return edgeIds.computeIfAbsent(e, k -> {
                    String id = "e" + edgeIds.size();
                    byEdgeId.put(id, edges.apply(k));
                    return id;
                });
            }
            @Override public double[] toLocal(double x, double y) { return new double[]{x, y}; }
        });
        Constraint k = create(s, new Binding() {
            @Override public Node node(String id) { return byNodeId.get(id); }
            @Override public Edge edge(String id) { return byEdgeId.get(id); }
            @Override public double[] toTarget(double x, double y) { return new double[]{x, y}; }
        });
        k.setAutomatic(c.isAutomatic());
        k.setRank(c.getRank());
        k.setOrigin(c.getOrigin());
        return k;
    }

    /** {@link #copy(Constraint, Function, Function)} onto the same elements. */
    public static Constraint copy(Constraint c) {
        return copy(c, Function.identity(), Function.identity());
    }

    /** True if {@link #toSpec} can store {@code c}. */
    public static boolean supports(Constraint c) {
        return adapter(c.getKind()) != null;
    }

    /**
     * Why {@code s} cannot be applied as it is, as readable sentences (empty: it is valid): unknown type, elements
     * missing from the pattern ({@code nodeIds}, {@code edgeIds}), too few or too many elements in a role, an element
     * twice, missing or out-of-range values.
     */
    public static List<String> validate(ConstraintSpec s, Collection<String> nodeIds, Collection<String> edgeIds) {
        List<String> p = new ArrayList<>();
        Adapter a = adapter(s.kind());
        if (a == null) {
            p.add("Unknown constraint type '" + s.type + "': made by a newer version; it can be kept but not applied or edited.");
            return p;
        }
        Set<String> nodesKnown = new HashSet<>(nodeIds), edgesKnown = new HashSet<>(edgeIds);
        Set<String> seen = new HashSet<>();
        for (String role : s.roles.keySet()) {
            if (a.role(role) == null) p.add("Unknown part '" + role + "' for " + title(a.kind) + ".");
        }
        for (RoleInfo r : a.roles) {
            List<String> ids = s.role(r.name);
            if (ids.isEmpty() && r.optional) continue;
            int count = 0;
            for (String id : ids) if (id != null) count++;
            if (count < r.min) p.add(r.label + ": needs at least " + r.min + (r.edges ? (r.min == 1 ? " edge" : " edges") : (r.min == 1 ? " node" : " nodes")) + " (has " + count + ").");
            if (r.max > 0 && count > r.max) p.add(r.label + ": allows at most " + r.max + " (has " + count + ").");
            for (String id : ids) {
                if (id == null) continue;
                boolean known = r.edges ? edgesKnown.contains(id) : nodesKnown.contains(id);
                if (!known) p.add(r.label + ": refers to '" + id + "', which is not in the pattern (deleted?).");
                if (!r.edges && !seen.add(id)) p.add("'" + id + "' takes part twice.");
            }
        }
        p.addAll(validateValues(s, a));
        a.checkMore(s, p);
        return p;
    }

    /** The value checks of {@link #validate}: required values present, numbers finite and in range, words allowed. */
    private static List<String> validateValues(ConstraintSpec s, Adapter a) {
        List<String> p = new ArrayList<>();
        for (ParamInfo i : a.params) {
            Object v = s.params.get(i.key);
            if (v == null) {
                if (i.required) p.add("Missing value '" + i.key + "'.");
                continue;
            }
            switch (i.type) {
                case NUMBER:
                case INTEGER:
                    if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) {
                        p.add("Value '" + i.key + "' must be a finite number.");
                    } else {
                        double d = ((Number) v).doubleValue();
                        if (!Double.isNaN(i.min) && d < i.min) p.add("Value '" + i.key + "' must be at least " + fmt(i.min) + " (is " + fmt(d) + ").");
                        if (i.type == ParamType.INTEGER && Math.abs(d - Math.rint(d)) > 1e-9) p.add("Value '" + i.key + "' must be a whole number.");
                    }
                    break;
                case BOOLEAN:
                    if (!(v instanceof Boolean)) p.add("Value '" + i.key + "' must be true or false.");
                    break;
                case WORD:
                    if (!(v instanceof String) || (i.words != null && !i.words.contains(v)))
                        p.add("Value '" + i.key + "' must be " + (i.words == null ? "a word" : "one of " + i.words) + " (is " + v + ").");
                    break;
                case RECT:
                    if (!(v instanceof double[]) || ((double[]) v).length != 4 || !finite((double[]) v))
                        p.add("Value '" + i.key + "' must be four finite numbers.");
                    break;
                case NUMBERS:
                    if (!(v instanceof double[]) || !finite((double[]) v))
                        p.add("Value '" + i.key + "' must be a list of finite numbers.");
                    break;
                case POINTS:
                    if (!(v instanceof Map)) {
                        p.add("Value '" + i.key + "' must give a position per node.");
                    } else {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                            Object q = e.getValue();
                            if (!(q instanceof double[]) || ((double[]) q).length != 2 || !finite((double[]) q))
                                p.add("Value '" + i.key + "' of '" + e.getKey() + "' must be two finite numbers.");
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    private static boolean finite(double[] v) {
        for (double d : v) if (!Double.isFinite(d)) return false;
        return true;
    }

    private static void requirePointsFor(ConstraintSpec s, String key, List<String> ids, String what, List<String> p) {
        Map<String, double[]> m = s.points(key);
        for (String id : ids) if (id != null && !m.containsKey(id)) p.add("'" + id + "' has no " + what + ".");
        for (String id : m.keySet()) if (!ids.contains(id)) p.add("A " + what + " is stored for '" + id + "', which does not take part.");
    }

    /**
     * Completes the values of {@code s} that come from the arrangement and are missing for its current elements (a new
     * rigid-group member's offset, a newly pinned node's position, an area's bounds) from {@code g}, and drops the values
     * of elements that no longer take part; values that are there are kept, so moving nodes never changes a definition.
     */
    public static void fillDerived(ConstraintSpec s, Geometry g) {
        Adapter a = adapter(s.kind());
        if (a == null) return;
        a.fillDerived(s, g);
        for (ParamInfo i : a.params) {
            if (i.type != ParamType.POINTS || !(s.params.get(i.key) instanceof Map)) continue;
            Set<String> refs = new HashSet<>();
            for (List<String> l : s.roles.values()) refs.addAll(l);
            s.points(i.key).keySet().retainAll(refs);
        }
    }

    private static void fillPositions(ConstraintSpec s, String key, Geometry g) {
        LinkedHashMap<String, double[]> now = new LinkedHashMap<>();
        Map<String, double[]> old = s.points(key);
        for (String id : s.role("nodes")) {
            double[] o = old.get(id), p = g.position(id);
            if (o != null) now.put(id, o.clone());
            else if (p != null) now.put(id, p.clone());
        }
        s.params.put(key, now);
    }

    private static double[] centroid(List<String> ids, Geometry g) {
        double x = 0, y = 0;
        int k = 0;
        for (String id : ids) {
            double[] p = g.position(id);
            if (p == null) continue;
            x += p[0]; y += p[1]; k++;
        }
        return k == 0 ? new double[]{0, 0} : new double[]{x / k, y / k};
    }

    /**
     * A text that is equal for two definitions exactly when they make the same constraint on the same elements:
     * element ids go through {@code ref} (e.g. to the matched graph nodes), roles whose order does not matter are
     * sorted, numbers are rounded to 1e-3 px. Used to recognise symmetric matches that lead to the same application and
     * to find duplicates.
     */
    public static String canonical(ConstraintSpec s, Function<String, String> ref) {
        StringBuilder sb = new StringBuilder(s.type).append('|').append(s.priority).append('|').append(s.enabled);
        Adapter a = adapter(s.kind());
        if (a == null) return sb.append('|').append(s.raw).toString();
        for (RoleInfo r : a.roles) {
            List<String> mapped = new ArrayList<>();
            for (String id : s.role(r.name)) mapped.add(id == null ? "-" : ref.apply(id));
            if (!r.ordered) Collections.sort(mapped);
            sb.append('|').append(r.name).append('=').append(mapped);
        }
        for (ParamInfo i : a.params) {
            Object v = s.params.get(i.key);
            sb.append('|').append(i.key).append('=');
            if (v instanceof Number) sb.append(fmt3(((Number) v).doubleValue()));
            else if (v instanceof double[]) for (double d : (double[]) v) sb.append(fmt3(d)).append(',');
            else if (v instanceof Map) {
                List<String> entries = new ArrayList<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    Object q = e.getValue();   // validate() reports a malformed value; the key just marks it
                    String at = q instanceof double[] && ((double[]) q).length == 2
                            ? fmt3(((double[]) q)[0]) + "," + fmt3(((double[]) q)[1]) : "?";
                    entries.add(ref.apply(String.valueOf(e.getKey())) + ":" + at);
                }
                Collections.sort(entries);
                sb.append(entries);
            } else sb.append(v);
        }
        return sb.toString();
    }

    private static String fmt3(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String fmt(double d) {
        return Math.abs(d - Math.rint(d)) < 1e-9 ? String.valueOf((long) Math.rint(d)) : String.format(Locale.ROOT, "%.2f", d);
    }

    /** The name of a kind as the "Constraints" tab shows it, e.g. "Align top edges" or "Horizontal gap". */
    public static String title(ConstraintKind k) {
        switch (k) {
            case ALIGN_TOP: return "Align top edges";
            case ALIGN_MIDDLE: return "Align centres horizontally";
            case ALIGN_BOTTOM: return "Align bottom edges";
            case ALIGN_LEFT: return "Align left edges";
            case ALIGN_CENTER: return "Align centres vertically";
            case ALIGN_RIGHT: return "Align right edges";
            case SEPARATION_X: return "Horizontal gap";
            case SEPARATION_Y: return "Vertical gap";
            case ORDER_X: return "Keep left-to-right order";
            case ORDER_Y: return "Keep top-to-bottom order";
            case DISTRIBUTE_X: return "Distribute horizontally";
            case DISTRIBUTE_Y: return "Distribute vertically";
            case RIGID: return "Rigid group";
            case CLUSTER: return "Cluster";
            case DIVIDE_X: return "Divide left | right";
            case DIVIDE_Y: return "Divide top / bottom";
            case AREA: return "Keep inside an area";
            case EDGE_HORIZONTAL: return "Horizontal edge";
            case EDGE_VERTICAL: return "Vertical edge";
            case PIN: return "Pin in place";
            case TARGET: return "Pull towards position";
            case ROW: return "Row";
            case COLUMN: return "Column";
            case GRID: return "Grid";
            default: return "Star";
        }
    }

    /** Local ids through a {@link Naming}; throws for an element without one. */
    static final class Names {
        final Naming naming;

        Names(Naming naming) {
            this.naming = naming;
        }

        List<String> nodes(Collection<Node> ns) {
            List<String> l = new ArrayList<>();
            for (Node n : ns) l.add(node(n));
            return l;
        }

        String node(Node n) {
            String id = n == null ? null : naming.node(n);
            if (id == null) {
                String label = n == null ? "?" : Deactivation.label(n);
                throw new IllegalArgumentException("Refers to node '" + label + "', which lies outside.");
            }
            return id;
        }

        String edge(Edge e) {
            String id = e == null ? null : naming.edge(e);
            if (id == null) throw new IllegalArgumentException("Refers to an edge that lies outside.");
            return id;
        }

        double[] toLocal(double x, double y) {
            return naming.toLocal(x, y);
        }
    }

    /** Elements by local id through a {@link Binding}; throws for an id that is not assigned. */
    static final class Resolver {
        final ConstraintSpec spec;
        final Binding binding;

        Resolver(ConstraintSpec spec, Binding binding) {
            this.spec = spec;
            this.binding = binding;
        }

        List<Node> nodes(String role) {
            List<Node> l = new ArrayList<>();
            for (String id : spec.role(role)) {
                if (id == null) continue;
                l.add(resolve(id));
            }
            return l;
        }

        /** The nodes of a role with its empty cells kept as null. */
        List<Node> nodesWithGaps(String role) {
            List<Node> l = new ArrayList<>();
            for (String id : spec.role(role)) l.add(id == null ? null : resolve(id));
            return l;
        }

        Node node(String role) {
            List<Node> l = nodes(role);
            if (l.size() != 1) throw new IllegalArgumentException("'" + role + "' needs exactly one node.");
            return l.get(0);
        }

        Edge edge(String role) {
            List<String> ids = spec.role(role);
            if (ids.size() != 1) throw new IllegalArgumentException("'" + role + "' needs exactly one edge.");
            Edge e = binding.edge(ids.get(0));
            if (e == null) throw new IllegalArgumentException("Edge '" + ids.get(0) + "' is not assigned.");
            return e;
        }

        private Node resolve(String id) {
            Node n = binding.node(id);
            if (n == null) throw new IllegalArgumentException("Node '" + id + "' is not assigned.");
            return n;
        }

        /** A per-element value keyed by the assigned node, copied as it is (not converted to graph coordinates). */
        Map<Node, double[]> points(String key) {
            Map<Node, double[]> m = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : spec.points(key).entrySet()) {
                double[] p = e.getValue();
                m.put(resolve(e.getKey()), p.clone());
            }
            return m;
        }

        /** Per-element positions in the order of the role (graph coordinates). */
        Map<Node, double[]> orderedPoints(String role, String key) {
            Map<Node, double[]> m = new LinkedHashMap<>();
            Map<String, double[]> pts = spec.points(key);
            for (String id : spec.role(role)) {
                double[] p = pts.get(id);
                if (p == null) throw new IllegalArgumentException("'" + id + "' has no position.");
                m.put(resolve(id), binding.toTarget(p[0], p[1]));
            }
            return m;
        }
    }
}
