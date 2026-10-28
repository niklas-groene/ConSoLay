package layout.mental_map;

/**
 * Weighted isotonic regression on a chain by the Pool Adjacent Violators Algorithm (PAVA), in linear time. It is the
 * exact order-preserving projection of {@link ImprovedMentalMapLayout}, which applies it per axis to the tie classes
 * after removing the minimum-gap chain by the substitution {@code eta_i = xi_i - i*g_min}.
 *
 * @author Niklas Gröne
 */
public final class Pava {

    private Pava() { }

    /**
     * The unique minimiser of {@code sum_i w_i (x_i - t_i)^2} subject to {@code x_1 <= x_2 <= ... <= x_n}.
     *
     * @param targets target values {@code t_i}
     * @param weights strictly positive weights {@code w_i} (same length as {@code targets})
     */
    public static double[] isotonic(double[] targets, double[] weights) {
        int n = targets.length;
        if (n != weights.length) {
            throw new IllegalArgumentException("targets and weights must have equal length");
        }
        double[] out = new double[n];
        if (n == 0) return out;

        // Block stack: gval = block mean, gw = block weight, gsize = #elements in the block.
        double[] gval = new double[n];
        double[] gw = new double[n];
        int[] gsize = new int[n];
        int m = 0; // number of blocks currently on the stack

        for (int i = 0; i < n; i++) {
            gval[m] = targets[i];
            gw[m] = weights[i];
            gsize[m] = 1;
            m++;
            while (m > 1 && gval[m - 2] > gval[m - 1]) {
                double nw = gw[m - 2] + gw[m - 1];
                gval[m - 2] = (gval[m - 2] * gw[m - 2] + gval[m - 1] * gw[m - 1]) / nw;
                gw[m - 2] = nw;
                gsize[m - 2] += gsize[m - 1];
                m--;
            }
        }

        // Expand the block means back to a per-element sequence.
        int idx = 0;
        for (int b = 0; b < m; b++) {
            for (int k = 0; k < gsize[b]; k++) {
                out[idx++] = gval[b];
            }
        }
        return out;
    }
}
