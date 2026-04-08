package bezier2_0.projects.competitor.optipath;

/**
 * T9 : detection anticipee de plateau via dynamique des gains relatifs.
 *
 * Le signal n'utilise jamais de seuil absolu de fitness :
 * - pente robuste des gains positifs (Theil-Sen)
 * - ratio energie haute frequence / basse frequence
 * - progression relative totale sur fenetre
 */
public class SpectralPlateauRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final long WINDOW_MS = 6_000;
    private static final long CHECK_MS = 1_200;
    private static final double SATISFACTION_RATIO = 0.72;

    private static final int BUF_SIZE = 256;
    private final long [] ts = new long [BUF_SIZE];
    private final double [] fit = new double [BUF_SIZE];
    private int head = 0;
    private int count = 0;

    private double seedBest;
    private long lastCheck;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        head = 0;
        count = 0;
        lastCheck = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        ts [head] = timestampMs;
        fit [head] = fitness;
        head = (head + 1) % BUF_SIZE;
        if (count < BUF_SIZE) count++;
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS || count < 8)
            return Decision.CONTINUE;

        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        long now = System.currentTimeMillis ();
        if (lastCheck != 0 && now - lastCheck < CHECK_MS)
            return Decision.CONTINUE;
        lastCheck = now;

        int startIdx = findStartIndex (now - WINDOW_MS);
        if (startIdx < 0) return Decision.CONTINUE;

        int n = countFrom (startIdx);
        if (n < 6) return Decision.CONTINUE;

        double [] gains = new double [n - 1];
        double [] times = new double [n - 1];
        double firstFitness = getFitnessAt (startIdx);
        double lastFitness = getFitnessAtOffset (startIdx, n - 1);

        for (int i = 1; i < n; i++)
        {
            double fPrev = getFitnessAtOffset (startIdx, i - 1);
            double fCur = getFitnessAtOffset (startIdx, i);
            long tPrev = getTimestampAtOffset (startIdx, i - 1);
            long tCur = getTimestampAtOffset (startIdx, i);

            double rel = (fPrev - fCur) / (Math.abs (fPrev) + 1e-12);
            gains [i - 1] = Math.max (rel, 0.0);
            times [i - 1] = (tCur - tPrev) * 1e-3;
        }

        double slope = robustSlopePerSecond (gains, times);
        double medianGain = median (gains);
        double totalProgress = (firstFitness - lastFitness) / (Math.abs (firstFitness) + 1e-12);

        double lowEnergy = 0.0;
        double highEnergy = 0.0;
        for (int i = 0; i < gains.length; i++)
        {
            lowEnergy += gains [i] * gains [i];
            if (i > 0)
            {
                double d = gains [i] - gains [i - 1];
                highEnergy += d * d;
            }
        }
        double ratioHF = highEnergy / (lowEnergy + 1e-12);

        boolean flatSlope = slope < 0.05 * (medianGain + 1e-12);
        boolean weakProgress = totalProgress < 0.004;
        boolean lowExploitDynamics = ratioHF < 0.10;

        if (flatSlope && weakProgress && lowExploitDynamics)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }

    @Override
    public void onRestart ()
    {
        lastCheck = 0;
    }

    private int findStartIndex (long windowStart)
    {
        for (int i = 0; i < count; i++)
        {
            int idx = (head - count + i + BUF_SIZE) % BUF_SIZE;
            if (ts [idx] >= windowStart)
                return idx;
        }
        return -1;
    }

    private int countFrom (int idx)
    {
        int oldest = (head - count + BUF_SIZE) % BUF_SIZE;
        int diff = idx - oldest;
        if (diff < 0) diff += BUF_SIZE;
        return count - diff;
    }

    private double getFitnessAt (int idx)
    {
        return fit [idx];
    }

    private double getFitnessAtOffset (int idx, int offset)
    {
        return fit [(idx + offset) % BUF_SIZE];
    }

    private long getTimestampAtOffset (int idx, int offset)
    {
        return ts [(idx + offset) % BUF_SIZE];
    }

    private double robustSlopePerSecond (double [] y, double [] dt)
    {
        int n = y.length;
        if (n < 3) return 0.0;

        double [] t = new double [n];
        double acc = 0.0;
        for (int i = 0; i < n; i++)
        {
            acc += Math.max (dt [i], 1e-3);
            t [i] = acc;
        }

        int m = n * (n - 1) / 2;
        double [] slopes = new double [m];
        int k = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
            {
                double den = t [j] - t [i];
                if (den <= 1e-9) continue;
                slopes [k++] = (y [j] - y [i]) / den;
            }

        if (k == 0) return 0.0;
        double [] used = new double [k];
        System.arraycopy (slopes, 0, used, 0, k);
        return median (used);
    }

    private double median (double [] arr)
    {
        if (arr.length == 0) return 0.0;
        double [] copy = arr.clone ();
        java.util.Arrays.sort (copy);
        int m = copy.length / 2;
        if ((copy.length & 1) == 1) return copy [m];
        return 0.5 * (copy [m - 1] + copy [m]);
    }
}