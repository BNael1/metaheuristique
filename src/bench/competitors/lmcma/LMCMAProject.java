package bench.competitors.lmcma;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * LM-CMA-ES : Limited Memory CMA-ES.
 * Remplace la mise à jour O(d²) de la matrice de covariance par une
 * approximation O(m·d) basée sur m vecteurs de direction en mémoire limitée.
 *
 * Référence : Loshchilov 2014 "A Computationally Efficient Limited Memory CMA-ES"
 */
public class LMCMAProject extends CompetitorProject
{
    private LMCMAOptimizer optimizer;
    private final double margin;

    public LMCMAProject (Problem problem) throws InvalidProjectException
    {
        this (problem, 0.0);
    }

    public LMCMAProject (Problem problem, double margin) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("LM-CMA-ES");
        this.margin = margin;
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
            if (i % 2 == 0)
            {
                lb [i] = problem.getMinX () - margin;
                ub [i] = problem.getMaxX () + margin;
            }
            else
            {
                lb [i] = problem.getMinY () - margin;
                ub [i] = problem.getMaxY () + margin;
            }
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

        optimizer = new LMCMAOptimizer (problem, d, lb, ub, initMean);
        optimizer.init ();
    }

    @Override
    public void loop ()
    {
        optimizer.step ();
    }
}
