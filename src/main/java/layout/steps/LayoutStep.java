package layout.steps;

/**
 * One step of a {@link StepLayout}: a name (the Algorithms page shows it; a leading "1. " is taken as its number), a
 * description (the tooltip) and the action. The action computes a structure, adds constraints for it to the store
 * ({@code constraints.ConstraintStore.constraints}) and calls the solver; what it adds is captured by the layout and
 * marked automatic when the step ends.
 *
 * @author Niklas Gröne
 */
public final class LayoutStep {
    public final String name;
    public final String description;
    public final Runnable action;

    public LayoutStep(String name, String description, Runnable action) {
        this.name = name;
        this.description = description;
        this.action = action;
    }

    @Override
    public String toString() {
        return name;
    }
}
