package constraints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Where a constraint came from when a template made it: the applied template instance, the template (id and the fixed
 * revision it was applied with) and the path through a composite template down to the constraint, e.g.
 * "Rule set → Source → Align centres". The constraint list and the conflict texts show the path; the switch decides
 * together with the constraint's own switch whether it takes part in the solves ({@link Constraint#isEnabled()}).
 *
 * @author Niklas Gröne
 */
public final class ConstraintOrigin {

    private final String instanceId;
    private final String instanceLabel;
    private final String templateId;
    private final int templateRevision;
    private final List<String> path;
    private final String specId;
    private final BooleanSupplier switchedOn;

    /**
     * @param instanceId       id of the applied instance (unique among the instances)
     * @param instanceLabel    short name of the instance for the GUI, e.g. "Chain #3"
     * @param templateId       stable id of the template
     * @param templateRevision the revision the instance was made from
     * @param path             names from the template down to the constraint (at least the template's name)
     * @param specId           local id of the constraint definition in the template that made it
     * @param switchedOn       true while the instance and every part above the constraint are switched on (null: always)
     */
    public ConstraintOrigin(String instanceId, String instanceLabel, String templateId, int templateRevision,
                            List<String> path, String specId, BooleanSupplier switchedOn) {
        this.instanceId = instanceId;
        this.instanceLabel = instanceLabel;
        this.templateId = templateId;
        this.templateRevision = templateRevision;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.specId = specId;
        this.switchedOn = switchedOn;
    }

    public String getInstanceId() { return instanceId; }

    public String getInstanceLabel() { return instanceLabel; }

    public String getTemplateId() { return templateId; }

    public int getTemplateRevision() { return templateRevision; }

    /** The names from the template down to the constraint, e.g. [Rule set, Source, Align centres]. */
    public List<String> getPath() { return path; }

    /** Local id of the constraint definition in its template. */
    public String getSpecId() { return specId; }

    /** False while the instance, or a part of it that holds the constraint, is switched off. */
    public boolean isSwitchedOn() {
        return switchedOn == null || switchedOn.getAsBoolean();
    }

    /** The path as one line, e.g. "Rule set → Source → Align centres". */
    public String pathText() {
        return String.join(" → ", path);
    }

    @Override
    public String toString() {
        return instanceLabel + ": " + pathText();
    }
}
