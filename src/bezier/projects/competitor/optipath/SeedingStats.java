package bezier.projects.competitor.optipath;

/**
 * Statistiques collectees pendant la phase de seeding massif.
 * Utilisees par les strategies de restart adaptatif pour calibrer
 * leurs seuils en fonction du probleme.
 */
public class SeedingStats
{
    public final double bestFitness;
    public final double medianFitness;
    public final double stdDev;
    public final double percentile25;

    public SeedingStats (double bestFitness, double medianFitness,
                         double stdDev, double percentile25)
    {
        this.bestFitness = bestFitness;
        this.medianFitness = medianFitness;
        this.stdDev = stdDev;
        this.percentile25 = percentile25;
    }
}
