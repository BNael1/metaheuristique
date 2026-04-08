package bezier2_0.projects.competitor.optipath;

/**
 * T6 : Detection d'effondrement du sigma CMA-ES.
 * Si les deux CMA-ES ont un sigma tres petit par rapport a l'initial
 * ET le fitness est encore mauvais, la recherche est terminee dans
 * un mauvais bassin -> restart.
 */
public class SigmaCollapseRestart implements OuterRestartStrategy
{
    private static final long CHECK_MS = 3_000;
    private static final double SIGMA_RATIO = 0.001;
    private static final double SATISFACTION_RATIO = 0.8;

    private double seedBest;
    private double initialSigmaSmall, initialSigmaWide;
    private boolean sigmaInitialized;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        sigmaInitialized = false;
    }

    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }
    @Override public void onRestart ()
    {
        sigmaInitialized = false;
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < CHECK_MS)
            return Decision.CONTINUE;

        // Capturer le sigma initial au premier check
        if (!sigmaInitialized)
        {
            initialSigmaSmall = ctx.sigmaSmall;
            initialSigmaWide = ctx.sigmaWide;
            sigmaInitialized = true;
            return Decision.CONTINUE;
        }

        // Guard : deja satisfait
        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        boolean smallCollapsed = ctx.sigmaSmall < initialSigmaSmall * SIGMA_RATIO;
        boolean wideCollapsed = ctx.sigmaWide < initialSigmaWide * SIGMA_RATIO;

        if (smallCollapsed && wideCollapsed)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }
}
