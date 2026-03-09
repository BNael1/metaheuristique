package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * Entry point : Sep-CMA-ES warm-up → Full CMA-ES + BIPOP restarts.
 *
 * Cible principale : prob1 (d=32) où le coût O(d²) de l'eigen-decomposition
 * limite le nombre de générations dans le budget de 60 s. La phase Sep
 * (O(d) par gen) apprend rapidement les échelles, puis la transition vers
 * le CMA-ES complet capture les corrélations.
 */
public class OptiPathSepWarmup extends CompetitorProject
{
    private CMAESSepWarmup optimizer;

    public OptiPathSepWarmup (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("OptiPath (Sep-CMA-ES Warmup)");
    }

    @Override
    public void initialization ()
    {
        int nCP = problem.getNControlPoints ();
        int d = 2 * nCP;

        double [] lb = new double [d], ub = new double [d];
        for (int i = 0; i < d; i++)
        {
            if (i % 2 == 0) { lb [i] = problem.getMinX (); ub [i] = problem.getMaxX (); }
            else             { lb [i] = problem.getMinY (); ub [i] = problem.getMaxY (); }
        }

        double [] initMean = new double [d];
        double sx = problem.getStartPoint ().getX ();
        double sy = problem.getStartPoint ().getY ();
        double ex = problem.getEndPoint ().getX ();
        double ey = problem.getEndPoint ().getY ();
        for (int i = 0; i < nCP; i++)
        {
            double t = (double) (i + 1) / (nCP + 1);
            initMean [2 * i]     = sx + t * (ex - sx);
            initMean [2 * i + 1] = sy + t * (ey - sy);
        }

        optimizer = new CMAESSepWarmup (problem, d, lb, ub, initMean);
        optimizer.init ();
    }

    @Override
    public void loop ()
    {
        optimizer.step ();
        double [] best = optimizer.getBestX ();
        if (best != null) problem.evaluate (best);
    }
}
