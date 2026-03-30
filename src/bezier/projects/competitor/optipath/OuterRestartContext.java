package bezier.projects.competitor.optipath;

/**
 * Contexte passe a la strategie de restart externe pour prendre
 * la decision de relancer les CMA-ES.
 */
public class OuterRestartContext
{
    public final long nowMs;
    public final long dtMs;
    public final double globalBestFitness;
    public final double fitnessAtLaunch;
    public final long sinceLaunchMs;
    public final long elapsedTotalMs;
    public final long totalBudgetMs;
    public final int restartCount;
    public final int genSmall;
    public final int genWide;
    public final int evalSmall;
    public final int evalWide;
    public final int deltaEvalSmall;
    public final int deltaEvalWide;
    public final double deltaBestSmall;
    public final double deltaBestWide;
    public final double sigmaSmall;
    public final double sigmaWide;
    public final double [] bestXSmall;
    public final double [] bestXWide;
    public final double bestFitnessSmall;
    public final double bestFitnessWide;
    public final SeedingStats seedStats;
    public final double domainDiag;
    public final double startX;
    public final double startY;
    public final double endX;
    public final double endY;
    public final double [] lbWide;
    public final double [] ubWide;

    public OuterRestartContext (long nowMs, long dtMs,
                                double globalBestFitness, double fitnessAtLaunch,
                                long sinceLaunchMs, long elapsedTotalMs,
                                long totalBudgetMs, int restartCount,
                                int genSmall, int genWide,
                                int evalSmall, int evalWide,
                                int deltaEvalSmall, int deltaEvalWide,
                                double deltaBestSmall, double deltaBestWide,
                                double sigmaSmall, double sigmaWide,
                                double [] bestXSmall, double [] bestXWide,
                                double bestFitnessSmall, double bestFitnessWide,
                                SeedingStats seedStats,
                                double domainDiag,
                                double startX, double startY, double endX, double endY,
                                double [] lbWide, double [] ubWide)
    {
        this.nowMs = nowMs;
        this.dtMs = dtMs;
        this.globalBestFitness = globalBestFitness;
        this.fitnessAtLaunch = fitnessAtLaunch;
        this.sinceLaunchMs = sinceLaunchMs;
        this.elapsedTotalMs = elapsedTotalMs;
        this.totalBudgetMs = totalBudgetMs;
        this.restartCount = restartCount;
        this.genSmall = genSmall;
        this.genWide = genWide;
        this.evalSmall = evalSmall;
        this.evalWide = evalWide;
        this.deltaEvalSmall = deltaEvalSmall;
        this.deltaEvalWide = deltaEvalWide;
        this.deltaBestSmall = deltaBestSmall;
        this.deltaBestWide = deltaBestWide;
        this.sigmaSmall = sigmaSmall;
        this.sigmaWide = sigmaWide;
        this.bestXSmall = bestXSmall;
        this.bestXWide = bestXWide;
        this.bestFitnessSmall = bestFitnessSmall;
        this.bestFitnessWide = bestFitnessWide;
        this.seedStats = seedStats;
        this.domainDiag = domainDiag;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.lbWide = lbWide;
        this.ubWide = ubWide;
    }
}
