package layout.template.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether the constraints a match would make agree with the constraints already on the graph (and with each other),
 * as far as the priority resolution can tell without changing anything. {@link Status#NOT_CHECKED} until a check ran.
 * A shared node alone is never a conflict: only requirements that cannot hold together are.
 *
 * @author Niklas Gröne
 */
public final class MatchCheck {

    public enum Status {
        NOT_CHECKED("Not checked yet"), COMPATIBLE("Compatible"), CONFLICT("Conflict"), BLOCKED("Cannot be applied");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final MatchCheck NOT_CHECKED = new MatchCheck(Status.NOT_CHECKED, "Not checked yet.", Collections.emptyList(), -1);

    public final Status status;
    /** One line, e.g. "2 of 6 constraints would not apply: they contradict 'Horizontal gap' (priority High)". */
    public final String summary;
    /** One sentence per contradiction or pushed-out constraint. */
    public final List<String> details;
    /** Structure counter of the graph the check was made for. */
    public final long version;

    public MatchCheck(Status status, String summary, List<String> details, long version) {
        this.status = status;
        this.summary = summary;
        this.details = Collections.unmodifiableList(new ArrayList<>(details));
        this.version = version;
    }
}
