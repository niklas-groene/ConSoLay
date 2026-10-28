package constraints;

/**
 * How important a constraint is when constraints contradict each other: {@link ConstraintConflicts} keeps the higher
 * one and does not apply the lower one. Five levels, {@link #NORMAL} by default. Between two constraints of the same
 * priority, the user's own one wins over one of the SBGN steps, and else the one added earlier wins.
 *
 * @author Niklas Gröne
 */
public enum Priority {
    VERY_LOW("Very low"), LOW("Low"), NORMAL("Normal"), HIGH("High"), VERY_HIGH("Very high");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    /** The name the GUI shows. */
    public String label() {
        return label;
    }

    /** The next higher level, or null for {@link #VERY_HIGH}. */
    public Priority above() {
        return this == VERY_HIGH ? null : values()[ordinal() + 1];
    }

    @Override
    public String toString() {
        return label;
    }
}
