package engine.surrogate;

/**
 * Lightweight RBF (Radial Basis Function) surrogate model for pre-screening.
 *
 * Maintains a bounded archive of (x, f) training points. Uses a cubic RBF
 * kernel to predict fitness via inverse-distance weighted interpolation.
 * This is a simple but effective model that avoids solving linear systems
 * (which would be O(n^3) and too expensive for an online surrogate).
 *
 * The model is used to cheaply rank candidate solutions; only the most
 * promising candidates are then evaluated on the true (expensive) fitness.
 *
 * Reference: Kern et al. (2006) "Surrogate-Assisted CMA-ES"
 */
public class SurrogateRBF
{
    private final int d;
    private final int maxSize;
    private double [][] xs;
    private double [] fs;
    private int size;
    private int insertIdx;

    // Kernel width scaling factor
    private double kernelWidth;

    /**
     * @param d        dimension of the search space
     * @param maxSize  maximum number of training points in the archive
     */
    public SurrogateRBF (int d, int maxSize)
    {
        this.d = d;
        this.maxSize = maxSize;
        this.xs = new double [maxSize][d];
        this.fs = new double [maxSize];
        this.size = 0;
        this.insertIdx = 0;
        this.kernelWidth = 1.0;
    }

    /**
     * Add a training point to the archive (circular buffer).
     */
    public void addPoint (double [] x, double f)
    {
        System.arraycopy (x, 0, xs [insertIdx], 0, d);
        fs [insertIdx] = f;
        insertIdx = (insertIdx + 1) % maxSize;
        if (size < maxSize) size++;
    }

    /**
     * Add multiple training points at once.
     */
    public void addPoints (double [][] xArr, double [] fArr, int n)
    {
        for (int i = 0; i < n; i++)
            addPoint (xArr [i], fArr [i]);
    }

    /**
     * Update the kernel width based on current archive statistics.
     * Called periodically (e.g., at each generation) to adapt the model.
     * Uses average nearest-neighbor distance as bandwidth.
     */
    public void updateKernelWidth ()
    {
        if (size < 2)
        {
            kernelWidth = 1.0;
            return;
        }

        // Sample a few points and compute average nearest-neighbor distance
        int nSamples = Math.min (size, 20);
        double totalMinDist = 0;
        int count = 0;

        for (int i = 0; i < nSamples; i++)
        {
            double minDist = Double.POSITIVE_INFINITY;
            for (int j = 0; j < size; j++)
            {
                if (i == j) continue;
                double dist = squaredDist (xs [i], xs [j]);
                if (dist < minDist) minDist = dist;
            }
            if (minDist < Double.POSITIVE_INFINITY)
            {
                totalMinDist += Math.sqrt (minDist);
                count++;
            }
        }

        if (count > 0)
            kernelWidth = Math.max (totalMinDist / count, 1e-10);
        else
            kernelWidth = 1.0;
    }

    /**
     * Predict fitness for a query point using inverse-distance weighted
     * interpolation with a cubic RBF kernel.
     *
     * f_hat(x) = sum_i( w_i * f_i ) / sum_i( w_i )
     * where w_i = 1 / (||x - x_i||^3 + eps)
     *
     * This is O(n*d) per query, fast enough for pre-screening.
     *
     * @param x  query point
     * @return   predicted fitness
     */
    public double predict (double [] x)
    {
        if (size == 0) return 0;

        double sumWf = 0;
        double sumW = 0;
        double h3 = kernelWidth * kernelWidth * kernelWidth;

        for (int i = 0; i < size; i++)
        {
            double r2 = squaredDist (x, xs [i]);
            double r = Math.sqrt (r2);
            // Cubic inverse kernel: w = 1 / (r^3 + h^3)
            double w = 1.0 / (r * r2 + h3 + 1e-30);
            sumWf += w * fs [i];
            sumW += w;
        }

        return sumWf / sumW;
    }

    /**
     * Predict fitness for multiple query points.
     *
     * @param queries  array of query points
     * @param n        number of queries
     * @param results  output array for predicted fitness values
     */
    public void predictBatch (double [][] queries, int n, double [] results)
    {
        for (int q = 0; q < n; q++)
            results [q] = predict (queries [q]);
    }

    /**
     * Get the number of training points currently in the archive.
     */
    public int getSize () { return size; }

    /**
     * Clear the archive. Used at restart.
     */
    public void clear ()
    {
        size = 0;
        insertIdx = 0;
    }

    // ===== Private =====

    private double squaredDist (double [] a, double [] b)
    {
        double s = 0;
        for (int i = 0; i < d; i++)
        {
            double diff = a [i] - b [i];
            s += diff * diff;
        }
        return s;
    }
}
