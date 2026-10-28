package constraints;

/**
 * What a constraint does, independent of how it is stored: one value per button of the "Constraints" tab. The GUI
 * derives label, icon and settings from it; the {@link Category} groups the kinds in the palette and in the list
 * filter.
 *
 * @author Niklas Gröne
 */
public enum ConstraintKind {

    // alignment: nodes on one line (HorizontalConstraint: same y, VerticalConstraint: same x)
    ALIGN_TOP(Category.ALIGNMENT), ALIGN_MIDDLE(Category.ALIGNMENT), ALIGN_BOTTOM(Category.ALIGNMENT),
    ALIGN_LEFT(Category.ALIGNMENT), ALIGN_CENTER(Category.ALIGNMENT), ALIGN_RIGHT(Category.ALIGNMENT),

    // spacing along one axis
    SEPARATION_X(Category.SPACING), SEPARATION_Y(Category.SPACING),
    ORDER_X(Category.SPACING), ORDER_Y(Category.SPACING),
    DISTRIBUTE_X(Category.SPACING), DISTRIBUTE_Y(Category.SPACING),

    // groups and regions
    RIGID(Category.GROUPS), CLUSTER(Category.GROUPS),
    DIVIDE_X(Category.GROUPS), DIVIDE_Y(Category.GROUPS), AREA(Category.GROUPS),

    // edges
    EDGE_HORIZONTAL(Category.EDGES), EDGE_VERTICAL(Category.EDGES),

    // absolute positions
    PIN(Category.POSITION), TARGET(Category.POSITION),

    // combinations of the above
    ROW(Category.PATTERNS), COLUMN(Category.PATTERNS), GRID(Category.PATTERNS), STAR(Category.PATTERNS);

    public enum Category { ALIGNMENT, SPACING, GROUPS, EDGES, POSITION, PATTERNS }

    private final Category category;

    ConstraintKind(Category category) {
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
