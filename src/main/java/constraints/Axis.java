package constraints;

/**
 * The axis a constraint acts along: {@link #X} (left to right) or {@link #Y} (top to bottom).
 * {@link SeparationConstraintGraph.SeparationConstraintType} names the same axes (HORIZONTAL = x, VERTICAL = y).
 *
 * @author Niklas Gröne
 */
public enum Axis {
    X, Y;

    public static Axis of(SeparationConstraintGraph.SeparationConstraintType type) {
        return type == SeparationConstraintGraph.SeparationConstraintType.VERTICAL ? Y : X;
    }

    public SeparationConstraintGraph.SeparationConstraintType toSeparationType() {
        return this == X ? SeparationConstraintGraph.SeparationConstraintType.HORIZONTAL
                : SeparationConstraintGraph.SeparationConstraintType.VERTICAL;
    }
}
