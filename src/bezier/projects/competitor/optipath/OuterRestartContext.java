package bezier.projects.competitor.optipath;

/**
 * Contexte passe a la strategie de restart externe pour prendre
 * la decision de relancer les CMA-ES.
 */
public class OuterRestartContext
{
    public final double globalBestFitness;
    public final double fitnessAtLaunch;
    public final long sinceLaunchMs;
    public final long elapsedTotalMs;
    public final long totalBudgetMs;
    public final int restartCount;
    public final double sigmaSmall;
    public final double sigmaWide;
    public final double [] bestXSmall;
    public final double [] bestXWide;
    public final double bestFitnessSmall;
    public final double bestFitnessWide;
    public final SeedingStats seedStats;

    public OuterRestartContext (double globalBestFitness, double fitnessAtLaunch,
                                long sinceLaunchMs, long elapsedTotalMs,
                                long totalBudgetMs, int restartCount,
                                double sigmaSmall, double sigmaWide,
                                double [] bestXSmall, double [] bestXWide,
                                double bestFitnessSmall, double bestFitnessWide,
                                SeedingStats seedStats)
    {
        this.globalBestFitness = globalBestFitness;
        this.fitnessAtLaunch = fitnessAtLaunch;
        this.sinceLaunchMs = sinceLaunchMs;
        this.elapsedTotalMs = elapsedTotalMs;
        this.totalBudgetMs = totalBudgetMs;
        this.restartCount = restartCount;
        this.sigmaSmall = sigmaSmall;
        this.sigmaWide = sigmaWide;
        this.bestXSmall = bestXSmall;
        this.bestXWide = bestXWide;
        this.bestFitnessSmall = bestFitnessSmall;
        this.bestFitnessWide = bestFitnessWide;
        this.seedStats = seedStats;
    }
}
