package bezier.projects.competitor.optipath;

/**
 * T8 : Combinaison ponderee de 4 signaux pour decision de restart.
 * Score = 0.3*(plateau) + 0.3*(seuil calibre) + 0.2*(sigma collapse) + 0.2*(temps)
 * Restart si score > 0.5. Hard ceiling a 20s dans tous les cas.
 */
public class HybridMultiSignalRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 4_000;
    private static final long HARD_CEILING_MS = 20_000;

    // Poids des signaux
    private static final double W_PLATEAU = 0.3;
    private static final double W_CALIBRATED = 0.3;
    private static final double W_SIGMA = 0.2;
    private static final double W_TIME = 0.2;

    // Seuils des signaux individuels
    private static final double MIN_RATE = 0.001;
    private static final long WINDOW_MS = 5_000;
    private static final double SEED_MULTIPLIER = 3.0;
    private static final double SIGMA_RATIO = 0.001;
    private static final long MAX_RUN_MS = 12_000;
    private static final double SATISFACTION_RATIO = 0.7;
    private static final double SCORE_THRESHOLD = 0.5;

    // Buffer circulaire pour plateau detection
    private static final int BUF_SIZE = 200;
    private final long [] timestamps = new long [BUF_SIZE];
    private final double [] fitnesses = new double [BUF_SIZE];
    private int bufHead = 0;
    private int bufCount = 0;

    private double seedBest;
    private double calibratedThreshold;
    private double initialSigmaSmall, initialSigmaWide;
    private boolean sigmaInitialized;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        calibratedThreshold = stats.bestFitness * SEED_MULTIPLIER;
        bufHead = 0;
        bufCount = 0;
        sigmaInitialized = false;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        timestamps[bufHead] = timestampMs;
        fitnesses[bufHead] = fitness;
        bufHead = (bufHead + 1) % BUF_SIZE;
        if (bufCount < BUF_SIZE) bufCount++;
    }

    @Override
    public void onRestart ()
    {
        sigmaInitialized = false;
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        // Guard : deja satisfait
        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        // Hard ceiling
        if (ctx.sinceLaunchMs > HARD_CEILING_MS)
            return Decision.RESTART_BOTH;

        // Capturer sigma initial
        if (!sigmaInitialized)
        {
            initialSigmaSmall = ctx.sigmaSmall;
            initialSigmaWide = ctx.sigmaWide;
            sigmaInitialized = true;
        }

        double score = 0;

        // Signal 1 : plateau (taux d'amelioration)
        score += W_PLATEAU * plateauSignal (ctx);

        // Signal 2 : seuil calibre
        score += W_CALIBRATED * calibratedSignal (ctx);

        // Signal 3 : sigma collapse
        score += W_SIGMA * sigmaSignal (ctx);

        // Signal 4 : temps depasse
        score += W_TIME * timeSignal (ctx);

        if (score > SCORE_THRESHOLD)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }

    private double plateauSignal (OuterRestartContext ctx)
    {
        if (bufCount == 0) return 1.0;

        long now = System.currentTimeMillis ();
        long windowStart = now - WINDOW_MS;
        double fitnessAtWindowStart = ctx.fitnessAtLaunch;

        for (int i = 0; i < bufCount; i++)
        {
            int idx = (bufHead - bufCount + i + BUF_SIZE) % BUF_SIZE;
            if (timestamps[idx] >= windowStart)
            {
                fitnessAtWindowStart = fitnesses[idx];
                break;
            }
        }

        if (fitnessAtWindowStart <= 0) return 0;
        double rate = (fitnessAtWindowStart - ctx.globalBestFitness) / fitnessAtWindowStart;
        return (rate < MIN_RATE) ? 1.0 : 0.0;
    }

    private double calibratedSignal (OuterRestartContext ctx)
    {
        return (ctx.globalBestFitness > calibratedThreshold) ? 1.0 : 0.0;
    }

    private double sigmaSignal (OuterRestartContext ctx)
    {
        if (!sigmaInitialized || initialSigmaSmall <= 0 || initialSigmaWide <= 0)
            return 0;
        boolean smallCollapsed = ctx.sigmaSmall < initialSigmaSmall * SIGMA_RATIO;
        boolean wideCollapsed = ctx.sigmaWide < initialSigmaWide * SIGMA_RATIO;
        return (smallCollapsed && wideCollapsed) ? 1.0 : 0.0;
    }

    private double timeSignal (OuterRestartContext ctx)
    {
        return (ctx.sinceLaunchMs > MAX_RUN_MS) ? 1.0 : 0.0;
    }
}
