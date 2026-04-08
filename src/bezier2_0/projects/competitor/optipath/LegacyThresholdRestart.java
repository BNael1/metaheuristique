package bezier2_0.projects.competitor.optipath;

/**
 * T0 : Comportement original — seuil absolu fixe a 500.
 * Sert de baseline pour comparer les nouvelles strategies.
 */
public class LegacyThresholdRestart implements OuterRestartStrategy
{
    private static final double RESTART_THRESHOLD = 500.0;
    private static final long RESTART_CHECK_MS = 5_000;

    @Override public void init (SeedingStats stats) { }
    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }
    @Override public void onRestart () { }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs > RESTART_CHECK_MS
                && ctx.globalBestFitness > RESTART_THRESHOLD)
            return Decision.RESTART_BOTH;
        return Decision.CONTINUE;
    }
}
