package layout.mental_map;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered timeline of layout {@link Snapshot}s plus the pinned reference of the mental-map layouts. The user can pin
 * any snapshot of the timeline; the timeline keeps {@link #MAX_SIZE} snapshots: the pinned one and the newest others.
 *
 * @author Niklas Gröne
 */
public class SnapshotHistory {

    private static final int MAX_SIZE = 30;

    private final List<Snapshot> snapshots = new ArrayList<>();
    private Snapshot reference;
    /** Snapshots added so far, including those the cap dropped since. */
    private int added;

    /** Append a snapshot and make it the new reference. */
    public Snapshot add(Snapshot s) {
        return add(s, true);
    }

    /**
     * Append a snapshot. When {@code makeReference} is true (or nothing is pinned yet) it becomes the pinned reference;
     * otherwise the current reference is kept, so that repeated layout runs keep preserving the same reference.
     */
    public Snapshot add(Snapshot s, boolean makeReference) {
        return add(s, makeReference, null);
    }

    /**
     * {@link #add(Snapshot, boolean)}; when the history is full, the oldest snapshot that is neither pinned nor
     * {@code keep} (e.g. the one about to be restored; may be null) makes room.
     */
    public Snapshot add(Snapshot s, boolean makeReference, Snapshot keep) {
        if (s == null) return null;
        snapshots.add(s);
        added++;
        if (makeReference || reference == null) {
            reference = s;
        }
        while (snapshots.size() > MAX_SIZE) {
            int oldest = 0;
            while (oldest < snapshots.size() - 1 && (snapshots.get(oldest) == reference || snapshots.get(oldest) == keep)) oldest++;
            snapshots.remove(oldest);
        }
        return s;
    }

    public List<Snapshot> getAll() {
        return Collections.unmodifiableList(snapshots);
    }

    public Snapshot getReference() {
        return reference;
    }

    public void setReference(Snapshot s) {
        if (s != null && snapshots.contains(s)) reference = s;
    }

    /** Union of the extents of all snapshots in graph coordinates, or {@code null} while there is none. */
    public Rectangle2D bounds() {
        Rectangle2D result = null;
        for (Snapshot s : snapshots) result = GraphBounds.union(result, s.bounds());
        return result;
    }

    public boolean isEmpty() {
        return snapshots.isEmpty();
    }

    /** How many snapshots were added so far; unlike the size, it does not shrink when the cap drops the oldest. */
    public int addedCount() {
        return added;
    }
}
