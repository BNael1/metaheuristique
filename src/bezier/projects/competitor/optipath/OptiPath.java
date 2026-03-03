package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * Classe principale du projet — étend CompetitorProject.
 *
 * Algorithme retenu : IPOP-CMA-ES (Covariance Matrix Adaptation Evolution Strategy
 * avec restarts à population croissante). Sélectionné après comparaison avec DE et GA.
 */
public class OptiPath extends CompetitorProject
{
    private Optimizer optimizer;

    public OptiPath (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("OptiPath (IPOP-CMA-ES)");
    }

    // ================================================================
    //  initialization() — appelée UNE SEULE FOIS au début des 60 s
    // ================================================================
    @Override
    public void initialization ()
    {
        int nCP = problem.getNControlPoints ();
        int d   = 2 * nCP;

        // Bornes : indices pairs = X, indices impairs = Y
        double [] lb = new double [d];
        double [] ub = new double [d];
        for (int i = 0; i < d; i++)
        {
            if (i % 2 == 0)
            {
                lb [i] = problem.getMinX ();
                ub [i] = problem.getMaxX ();
            }
            else
            {
                lb [i] = problem.getMinY ();
                ub [i] = problem.getMaxY ();
            }
        }

        // Point de départ : interpolation linéaire start → end
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

        // IPOP-CMA-ES
        optimizer = new CMAESOptimizer (problem, d, lb, ub, initMean);
        optimizer.init ();
    }

    // ================================================================
    //  loop() — rappelée en boucle pendant le reste des 60 s
    // ================================================================
    @Override
    public void loop ()
    {
        optimizer.step ();
    }

    /** Expose l'optimiseur pour diagnostic (BenchmarkRunner). */
    public Optimizer getOptimizer () { return optimizer; }
}
