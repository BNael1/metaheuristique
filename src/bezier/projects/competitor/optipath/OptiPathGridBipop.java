package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * Entry point pour Grid-BIPOP-CMA-ES (Quality-Diversity restarts).
 * Force l'exploration de routes différentes sur les labyrinthes.
 */
public class OptiPathGridBipop extends CompetitorProject
{
    private Optimizer optimizer;

    public OptiPathGridBipop (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("OptiPath (Grid-BIPOP-CMA-ES)");
    }

    @Override
    public void initialization ()
    {
        int nCP = problem.getNControlPoints ();
        int d   = 2 * nCP;

        double [] lb = new double [d];
        double [] ub = new double [d];
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

        optimizer = new CMAESGridBipop (problem, d, lb, ub, initMean);
        optimizer.init ();
    }

    @Override
    public void loop ()
    {
        optimizer.step ();
    }
}
