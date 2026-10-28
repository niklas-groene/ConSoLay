package layout.template.apply;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Optional timing records of the template workflow: the duration of each search (with graph size, assignments examined,
 * matches and end state) and the latency of each application (making the constraints, placing, solving, in all), each
 * appended as one line to a CSV file.
 *
 * @author Niklas Gröne
 */
public final class TemplateMetrics {

    private TemplateMetrics() { }

    /** -Dcblayout.templates.metrics=path.csv (default unset: nothing recorded): appends every record to that file. */
    private static final String FILE = System.getProperty("cblayout.templates.metrics");

    /** One search: graph nodes / edges, templates, assignments examined, matches, duration in ms, end state. */
    public static synchronized void search(String templates, int nodes, int edges, long assignments, int matches, long millis, String state) {
        record(String.format(Locale.ROOT, "search,%d,%s,%d,%d,%d,%d,%d,%s", System.currentTimeMillis(), csv(templates), nodes, edges,
                assignments, matches, millis, state));
    }

    /** One application: instances made, constraints, times in ms for making, placing, solving and in all. */
    public static synchronized void apply(int instances, int constraints, int skipped, double createMs, double placeMs, double solveMs, double totalMs) {
        record(String.format(Locale.ROOT, "apply,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f", System.currentTimeMillis(), instances, constraints,
                skipped, createMs, placeMs, solveMs, totalMs));
    }

    private static void record(String line) {
        if (FILE == null || FILE.isEmpty()) return;
        File f = new File(FILE);
        boolean header = !f.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            if (header) w.write("# search,time,templates,nodes,edges,assignments,matches,ms,state | apply,time,instances,constraints,skipped,createMs,placeMs,solveMs,totalMs\n");
            w.write(line);
            w.write('\n');
        } catch (IOException ignored) {
            // measurements must never disturb the work
        }
    }

    private static String csv(String s) {
        return '"' + (s == null ? "" : s.replace("\"", "'")) + '"';
    }
}
