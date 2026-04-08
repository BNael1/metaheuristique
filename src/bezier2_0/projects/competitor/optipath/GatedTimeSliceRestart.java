package bezier2_0.projects.competitor.optipath;

/**
 * T5 : Tranches de temps avec porte de progression.
 * Restart apres MAX_RUN_MS sauf si l'amelioration est suffisante
 * (auquel cas on etend le run). Combine multi-start garanti
 * et conservation des bons runs.
 */
public class GatedTimeSliceRestart implements OuterRestartStrategy
{
    private static final long MAX_RUN_MS = 12_000;
    private static final long EXTENSION_MS = 5_000;
    private static final long MAX_TOTAL_RUN_MS = 25_000;
    private static final double GOOD_PROGRESS = 0.3;

    private long currentDeadlineMs;

    @Override
    public void init (SeedingStats stats)
    {
        currentDeadlineMs = MAX_RUN_MS;
    }

    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }

    @Override
    public void onRestart ()
    {
        currentDeadlineMs = MAX_RUN_MS;
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < currentDeadlineMs)
            return Decision.CONTINUE;

        // Verifier la progression depuis le lancement
        if (ctx.fitnessAtLaunch > 0)
        {
            double improvement = (ctx.fitnessAtLaunch - ctx.globalBestFitness)
                    / ctx.fitnessAtLaunch;
            if (improvement >= GOOD_PROGRESS && currentDeadlineMs < MAX_TOTAL_RUN_MS)
            {
                // Bonne progression, etendre le run
                currentDeadlineMs += EXTENSION_MS;
                return Decision.CONTINUE;
            }
        }

        return Decision.RESTART_BOTH;
    }
}
