package gui;

import constraints.ConflictLog;
import gui.components.Ui;

import java.awt.Color;

/** The marker colours of the conflict kinds, shared by the constraint list and the overlay. */
final class ConflictColors {

    static final Color DROPPED = new Color(0xff, 0x7f, 0x0e);      // orange
    static final Color UNSATISFIED = new Color(0xd6, 0x27, 0x28);  // red
    static final Color WARNING = new Color(0x7f, 0x7f, 0x7f);      // grey
    static final Color DEACTIVATED = Ui.WARNING;                   // amber

    private ConflictColors() { }

    /** The colour of an entry kind. */
    static Color of(ConflictLog.Kind kind) {
        switch (kind) {
            case DROPPED: return DROPPED;
            case UNSATISFIED: return UNSATISFIED;
            case DEACTIVATED: return DEACTIVATED;
            default: return WARNING;
        }
    }
}
