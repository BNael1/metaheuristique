package bezier2_0.projects.competitor.optipath;

/**
 * T7 : Detection de convergence redondante des deux CMA-ES.
 * Si les deux instances convergent vers le meme point (distance faible),
 * elles sont redondantes -> restart la pire.
 * Si les deux sont dans de mauvais bassins -> restart les deux.
 */
public class PortfolioAgreementRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final double DIST_RATIO = 0.05;
    private static final double SEED_MULTIPLIER = 2.0;

    private double seedBest;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
    }

    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }
    @Override public void onRestart () { }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        double [] xs = ctx.bestXSmall;
        double [] xw = ctx.bestXWide;
        if (xs == null || xw == null) return Decision.CONTINUE;

        // Calculer distance euclidienne normalisee
        double dist = 0;
        double range = 0;
        for (int i = 0; i < xs.length; i++)
        {
            double diff = xs[i] - xw[i];
            dist += diff * diff;
            range += xs[i] * xs[i];
        }
        dist = Math.sqrt (dist);
        range = Math.sqrt (range);
        double normalizedDist = (range > 0) ? dist / range : dist;

        double badThreshold = seedBest * SEED_MULTIPLIER;

        // Cas 1 : les deux convergent au meme endroit
        if (normalizedDist < DIST_RATIO)
        {
            // Si les deux sont dans un bon bassin, pas de restart
            if (ctx.globalBestFitness < seedBest * 0.8)
                return Decision.CONTINUE;

            // Restart le pire
            if (ctx.bestFitnessSmall > ctx.bestFitnessWide)
                return Decision.RESTART_SMALL;
            else
                return Decision.RESTART_WIDE;
        }

        // Cas 2 : les deux sont dans de mauvais bassins distincts
        if (ctx.bestFitnessSmall > badThreshold && ctx.bestFitnessWide > badThreshold)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }
}
