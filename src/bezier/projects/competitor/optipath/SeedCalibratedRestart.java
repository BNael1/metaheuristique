package bezier.projects.competitor.optipath;

/**
 * T2 : Seuil calibre automatiquement par le seeding.
 * threshold = seedBestFitness * MULTIPLIER.
 * S'adapte a l'echelle du probleme sans parametre manuel.
 */
public class SeedCalibratedRestart implements OuterRestartStrategy
{
    private static final long CHECK_MS = 5_000;
    private final double multiplier;
    private double threshold;

    public SeedCalibratedRestart ()
    {
        this (3.0);
    }

    public SeedCalibratedRestart (double multiplier)
    {
        this.multiplier = multiplier;
    }

    @Override
    public void init (SeedingStats stats)
    {
        threshold = stats.bestFitness * multiplier;
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
