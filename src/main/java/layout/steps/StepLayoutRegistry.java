package layout.steps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The layout algorithms the Algorithms page offers, in the order they were registered. An add-on registers its
 * algorithm once at initialisation ({@code StepLayoutRegistry.register(new MyAlgorithm())}); registering an id again
 * replaces the earlier entry. Listeners (the page) are told on the calling thread.
 *
 * @author Niklas Gröne
 */
public final class StepLayoutRegistry {

    private static final List<StepAlgorithm> ALGORITHMS = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private StepLayoutRegistry() { }

    public static synchronized void register(StepAlgorithm a) {
        if (a == null || a.id() == null) throw new IllegalArgumentException("algorithm without id");
        ALGORITHMS.removeIf(x -> x.id().equals(a.id()));
        ALGORITHMS.add(a);
        fire();
    }

    public static synchronized void unregister(String id) {
        if (ALGORITHMS.removeIf(x -> x.id().equals(id))) fire();
    }

    public static synchronized List<StepAlgorithm> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALGORITHMS));
    }

    public static synchronized StepAlgorithm byId(String id) {
        for (StepAlgorithm a : ALGORITHMS) if (a.id().equals(id)) return a;
        return null;
    }

    public static synchronized void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    private static void fire() {
        for (Runnable r : new ArrayList<>(LISTENERS)) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the others
            }
        }
    }
}
