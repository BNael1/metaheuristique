package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * Projet competiteur utilisant CMAESBipopAdaptif :
 * BIPOP conditionnel (IPOP si d>16) + SHADE adapte pour sigma au restart.
 */
public class OptiPathBipopAdaptif extends CompetitorProject
{
    private final CMAESBipopAdaptif optimizer;

    public OptiPathBipopAdaptif (Problem problem) throws InvalidProjectException
    {
        super (problem);

        int nCP = problem.getNControlPoints ();
        int d   = 2 * nCP;
        double [] lb = new double [d], ub = new double [d];
        for (int j = 0; j < d; j++)
        {
            lb [j] = (j % 2 == 0) ? problem.getMinX () : problem.getMinY ();
            ub [j] = (j % 2 == 0) ? problem.getMaxX () : problem.getMaxY ();
        }
        double [] initMean = new double [d];
        double sx = problem.getStartPoint ().getX ();
        double sy = problem.getStartPoint ().getY ();
        double ex = problem.getEndPoint ().getX ();
        double ey = problem.getEndPoint ().getY ();
        for (int k = 0; k < nCP; k++)
        {
            double t = (double) (k + 1) / (nCP + 1);
            initMean [2 * k]     = sx + t * (ex - sx);
            initMean [2 * k + 1] = sy + t * (ey - sy);
        }

        optimizer = new CMAESBipopAdaptif (problem, d, lb, ub, initMean);
    }

    @Override
    public void initialization ()
    {
        optimizer.init ();
    }

    @Override
    public void loop ()
    {
        optimizer.step ();
    }

    public Optimizer getOptimizer () { return optimizer; }
}
