package bezier.projects.competitor.optipath;

/**
 * T3 : Seuil base sur le 25e percentile des seeds.
 * Si apres optimisation CMA-ES on est encore pire que 75% des seeds
 * aleatoires, on est clairement dans un mauvais bassin -> restart.
 */
public class PercentileThresholdRestart implements OuterRestartStrategy
{
    private static final long CHECK_MS = 5_000;
    private double threshold;

    @Override
    public void init (SeedingStats stats)
    {
        threshold = stats.percentile25;
    }

    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }
    @Override public void onRestart () { }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs > CHECK_MS && ctx.globalBestFitness > threshold)
            return Decision.RESTART_BOTH;
        return Decision.CONTINUE;
    }
}
