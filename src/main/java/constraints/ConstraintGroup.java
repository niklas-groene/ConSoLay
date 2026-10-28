package constraints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The constraints that make up one structure a layout step built (the star, sides or stubs of a process, a ring, lens
 * or fan of the skeleton, an AF level or operator tree); the constraint list shows a group as one entry whose switch
 * and priority act on every member. Grouping is metadata only: the solves see the members as single constraints. A
 * template instance ({@link ConstraintOrigin}) is the other kind of group; a constraint has at most one of the two.
 *
 * @author Niklas Gröne
 */
public final class ConstraintGroup {

    /** What the structure is: "star", "sides", "stubs", "ring", "lens", "fan", "row", "column", "level", "tree". */
    public final String kind;
    /** Shown in the list, e.g. "Star of hexokinase process". */
    public final String label;
    /** The step that made it (the phase name of the ConflictLog), e.g. "Stars". */
    public final String step;
    private final List<Constraint> members = new ArrayList<>();

    public ConstraintGroup(String kind, String label, String step) {
        this.kind = kind;
        this.label = label;
        this.step = step;
    }

    /** Puts {@code c} into this group (taking it out of its previous group). */
    public void add(Constraint c) {
        if (c == null) return;
        ConstraintGroup old = c.getGroup();
        if (old == this) return;
        if (old != null) old.members.remove(c);
        members.add(c);
        c.setGroup(this);
    }

    public void addAll(Collection<? extends Constraint> cs) {
        for (Constraint c : cs) add(c);
    }

    /** Every member ever added, in order (also members no longer in the store). */
    public List<Constraint> members() {
        return Collections.unmodifiableList(members);
    }

    @Override
    public String toString() {
        return label + " (" + members.size() + ")";
    }
}
