package bezier.projects.competitor.optipath;

/**
 * T10 : restart base sur collapse topologique en espace parametrique.
 *
 * On suit une projection 2D (centroides x/y des points de controle),
 * puis l'entropie d'occupation de grille + taux de nouveaute recent.
 */
public class OccupancyEntropyRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final long CHECK_MS = 1_000;
    private static final long NOVELTY_WINDOW_MS = 6_000;
    private static final double SATISFACTION_RATIO = 0.72;
    private static final int GRID = 12;

    private final int [] gridCounts = new int [GRID * GRID];
    private int visits = 0;

    private static final int RECENT_BUF = 256;
    private final int [] recentCells = new int [RECENT_BUF];
    private final long [] recentTs = new long [RECENT_BUF];
    private final double [] recentFitness = new double [RECENT_BUF];
    private int recentHead = 0;
    private int recentCount = 0;

    private double minPX, maxPX, minPY, maxPY;
    private double seedBest;
    private long lastCheck;
    private double lastEntropy;

    @Override
    public void init (SeedingStats stats)
    {
        java.util.Arrays.fill (gridCounts, 0);
        visits = 0;
        recentHead = 0;
        recentCount = 0;
        minPX = Double.POSITIVE_INFINITY;
        maxPX = Double.NEGATIVE_INFINITY;
        minPY = Double.POSITIVE_INFINITY;
        maxPY = Double.NEGATIVE_INFINITY;
        seedBest = stats.bestFitness;
        lastCheck = 0;
        lastEntropy = 0.0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        // Pas utilise : la strategie lit directement depuis le contexte.
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        long now = System.currentTimeMillis ();
        if (lastCheck != 0 && now - lastCheck < CHECK_MS)
            return Decision.CONTINUE;
        lastCheck = now;

        double [] x = chooseBestVector (ctx);
        if (x == null || x.length < 2)
            return Decision.CONTINUE;

        double [] p = project2D (x);
        updateRanges (p [0], p [1]);
        int cell = cellId (p [0], p [1]);

        gridCounts [cell]++;
        visits++;
        pushRecent (cell, now, ctx.globalBestFitness);

        double entropy = normalizedEntropy ();
        double dEntropy = entropy - lastEntropy;
        lastEntropy = entropy;

        double noveltyRate = noveltyRate (now - NOVELTY_WINDOW_MS);
        double progress = progressRate (now - NOVELTY_WINDOW_MS, ctx.globalBestFitness);

        boolean entropyFrozen = dEntropy < 0.010;
        boolean noveltyCollapsed = noveltyRate < 0.10;
        boolean weakProgress = progress < 0.003;

        if (entropyFrozen && noveltyCollapsed && weakProgress)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }

    @Override
    public void onRestart ()
    {
        lastCheck = 0;
    }

    private double [] chooseBestVector (OuterRestartContext ctx)
    {
        if (ctx.bestXSmall == null) return ctx.bestXWide;
        if (ctx.bestXWide == null) return ctx.bestXSmall;
        return (ctx.bestFitnessSmall <= ctx.bestFitnessWide) ? ctx.bestXSmall : ctx.bestXWide;
    }

    private double [] project2D (double [] x)
    {
        double sx = 0.0;
        double sy = 0.0;
        int n = x.length / 2;
        if (n == 0) return new double[]{x [0], (x.length > 1 ? x [1] : 0.0)};
        for (int i = 0; i < n; i++)
        {
            sx += x [2 * i];
            sy += x [2 * i + 1];
        }
        return new double[]{sx / n, sy / n};
    }

    private void updateRanges (double px, double py)
    {
        minPX = Math.min (minPX, px);
        maxPX = Math.max (maxPX, px);
        minPY = Math.min (minPY, py);
        maxPY = Math.max (maxPY, py);
    }

    private int cellId (double px, double py)
    {
        double nx = (maxPX > minPX) ? (px - minPX) / (maxPX - minPX) : 0.5;
        double ny = (maxPY > minPY) ? (py - minPY) / (maxPY - minPY) : 0.5;
        int ix = clampCell ((int) Math.floor (nx * GRID));
        int iy = clampCell ((int) Math.floor (ny * GRID));
        return iy * GRID + ix;
    }

    private int clampCell (int i)
    {
        if (i < 0) return 0;
        if (i >= GRID) return GRID - 1;
        return i;
    }

    private void pushRecent (int cell, long tsMs, double fit)
    {
        recentCells [recentHead] = cell;
        recentTs [recentHead] = tsMs;
        recentFitness [recentHead] = fit;
        recentHead = (recentHead + 1) % RECENT_BUF;
        if (recentCount < RECENT_BUF) recentCount++;
    }

    private double noveltyRate (long sinceMs)
    {
        if (recentCount == 0) return 1.0;
        boolean [] seen = new boolean [GRID * GRID];
        int total = 0;
        int unique = 0;
        for (int i = 0; i < recentCount; i++)
        {
            int idx = (recentHead - 1 - i + RECENT_BUF) % RECENT_BUF;
            if (recentTs [idx] < sinceMs) break;
            total++;
            int c = recentCells [idx];
            if (!seen [c])
            {
                seen [c] = true;
                unique++;
            }
        }
        if (total == 0) return 1.0;
        return (double) unique / total;
    }

    private double progressRate (long sinceMs, double fallback)
    {
        if (recentCount == 0) return 0.0;
        double first = fallback;
        double last = fallback;
        boolean found = false;
        for (int i = recentCount - 1; i >= 0; i--)
        {
            int idx = (recentHead - 1 - i + RECENT_BUF) % RECENT_BUF;
            if (recentTs [idx] >= sinceMs)
            {
                first = recentFitness [idx];
                found = true;
                break;
            }
        }
        if (!found) return 0.0;
        last = recentFitness [(recentHead - 1 + RECENT_BUF) % RECENT_BUF];
        return (first - last) / (Math.abs (first) + 1e-12);
    }

    private double normalizedEntropy ()
    {
        if (visits <= 1) return 0.0;
        double h = 0.0;
        for (int c : gridCounts)
        {
            if (c <= 0) continue;
            double p = (double) c / visits;
            h -= p * Math.log (p + 1e-12);
        }
        return h / Math.log (GRID * GRID);
    }
}