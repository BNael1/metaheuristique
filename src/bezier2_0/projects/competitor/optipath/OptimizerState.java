package bezier2_0.projects.competitor.optipath;

/**
 * État observable d'un optimiseur, utilisé par la GUI et le monitoring.
 */
public class OptimizerState
{
    public final int generation;
    public final int evaluations;
    public final int restarts;
    public final double bestFitness;
    public final double sigma;
    public final double conditionNumber;
    public final double [] bestX;

    public OptimizerState (int generation, int evaluations, int restarts,
                           double bestFitness, double sigma,
                           double conditionNumber, double [] bestX)
    {
        this.generation = generation;
        this.evaluations = evaluations;
        this.restarts = restarts;
        this.bestFitness = bestFitness;
        this.sigma = sigma;
        this.conditionNumber = conditionNumber;
        this.bestX = bestX != null ? bestX.clone () : null;
    }
}
