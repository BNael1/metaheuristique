package bezier2_0.projects.competitor.optipath;

import bezier2_0.evaluation.Problem;

/**
 * Évaluation directe : chaque candidat est évalué sur la vraie fitness.
 */
public class DirectEval implements EvalWrapper
{
    private final Problem problem;
    private int evalCount = 0;

    public DirectEval (Problem problem)
    {
        this.problem = problem;
    }

    @Override
    public double [] evaluateBatch (double [][] candidates, int lambda)
    {
        double [] fitness = new double [lambda];
        for (int k = 0; k < lambda; k++)
        {
            fitness [k] = problem.evaluate (candidates [k]);
            evalCount++;
        }
        return fitness;
    }

    @Override
    public int getEvalCount () { return evalCount; }
}
