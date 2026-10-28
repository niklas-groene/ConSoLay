package layout.template.search;

/**
 * The limits of the template workflow: how many matches one search run collects, how many are drawn and listed at the
 * same time and how many instances one application step makes, plus the guards of the search itself. Each is set at
 * start-up with {@code -Dcblayout.templates.<name>=<n>}; values below 1 count as 1, unreadable ones as the default.
 *
 * @author Niklas Gröne
 */
public final class TemplateLimits {

    private TemplateLimits() { }

    /** -Dcblayout.templates.maxMatches=n (default 1000): distinct matches (node sets) one search run collects. */
    public static int maxMatchesPerRun = intProperty("maxMatches", 1000);
    /** -Dcblayout.templates.maxShown=n (default 200): matches drawn and listed at the same time. */
    public static int maxShown = intProperty("maxShown", 200);
    /** -Dcblayout.templates.maxApply=n (default 100): instances made in one application step. */
    public static int maxApplyPerStep = intProperty("maxApply", 100);
    /** -Dcblayout.templates.maxAssignments=n (default 200000): complete assignments one run examines. */
    public static int maxAssignments = intProperty("maxAssignments", 200_000);
    /** -Dcblayout.templates.maxVariants=n (default 12): assignments with different constraints kept per match. */
    public static int maxVariants = intProperty("maxVariants", 12);
    /** -Dcblayout.templates.maxParts=n (default 20000): occurrences of a component a composite search combines. */
    public static int maxPartsPerComponent = intProperty("maxParts", 20_000);
    /** -Dcblayout.templates.chunkMillis=n (default 150): results reach the GUI at least this often (ms)... */
    public static int chunkMillis = intProperty("chunkMillis", 150);
    /** -Dcblayout.templates.chunkSize=n (default 50): ...or after this many new matches. */
    public static int chunkSize = intProperty("chunkSize", 50);

    private static int intProperty(String name, int def) {
        try {
            return Math.max(1, Integer.parseInt(System.getProperty("cblayout.templates." + name, String.valueOf(def))));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
