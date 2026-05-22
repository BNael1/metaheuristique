package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

/**
 * Builder pour construire un CMAESCore composable avec les stratégies requises.
 */
public class CMAESBuilder
{
    private final Problem problem;
    private RestartStrategy restart;
    private EvalWrapper evaluator;
    private CovarianceUpdate covariance;
    private ConstraintHandler constraints;
    private SamplingStrategy sampling;
    private AlgorithmParameters parameters;
    private double [] customLb;
    private double [] customUb;
    private double [] customInitMean;

    public CMAESBuilder (Problem problem)
    {
        this.problem = problem;
    }

    public CMAESBuilder restart (RestartStrategy restart)
    {
        this.restart = restart;
        return this;
    }

    public CMAESBuilder evaluator (EvalWrapper evaluator)
    {
        this.evaluator = evaluator;
        return this;
    }

    public CMAESBuilder covariance (CovarianceUpdate covariance)
    {
        this.covariance = covariance;
        return this;
    }

    public CMAESBuilder constraints (ConstraintHandler constraints)
    {
        this.constraints = constraints;
        return this;
    }

    public CMAESBuilder sampling (SamplingStrategy sampling)
    {
        this.sampling = sampling;
        return this;
    }

    public CMAESBuilder parameters (AlgorithmParameters parameters)
    {
        this.parameters = parameters;
        return this;
    }

    /**
     * Override the default initial mean (center line start→end) with a custom vector.
     * Useful for seeding CMA-ES from a solution found by another algorithm.
     */
    public CMAESBuilder initMean (double [] initMean)
    {
        this.customInitMean = initMean != null ? initMean.clone () : null;
        return this;
    }

    /**
     * Override the default bounds (problem rectangle) with custom bounds.
     * Arrays are cloned to avoid external mutation.
     */
    public CMAESBuilder bounds (double [] lb, double [] ub)
    {
        this.customLb = lb != null ? lb.clone () : null;
        this.customUb = ub != null ? ub.clone () : null;
        return this;
    }

    /**
     * Construit le CMAESCore avec les stratégies configurées.
     * Les valeurs non spécifiées utilisent les défauts (standard).
     */
    public Optimizer build ()
    {
        int nCP = problem.getNControlPoints ();
        int d = 2 * nCP;
        double [] lb;
        double [] ub;
        if (customLb != null && customUb != null)
        {
            lb = customLb.clone ();
            ub = customUb.clone ();
        }
        else
        {
            lb = new double [d];
            ub = new double [d];
            for (int j = 0; j < d; j++)
            {
                lb [j] = (j % 2 == 0) ? problem.getMinX () : problem.getMinY ();
                ub [j] = (j % 2 == 0) ? problem.getMaxX () : problem.getMaxY ();
            }
        }

        double [] initMean;
        if (customInitMean != null && customInitMean.length == d)
        {
            initMean = customInitMean.clone ();
        }
        else
        {
            initMean = new double [d];
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
        }

        // Défauts
        if (restart == null)     restart     = new IPOPRestart ();
        if (covariance == null)  covariance  = new StandardCovariance ();
        if (constraints == null) constraints = new StandardConstraint ();
        if (parameters == null)  parameters  = new AlgorithmParameters ();

        if (evaluator == null)
            throw new IllegalStateException ("EvalWrapper requis pour CMAESCore");
        if (sampling == null)
            throw new IllegalStateException ("SamplingStrategy requis pour CMAESCore");

        return new CMAESCore (problem, d, lb, ub, initMean,
                restart, evaluator, covariance, constraints, sampling, parameters);
    }

    // ================================================================
    //  PRESETS : équivalents des anciens fichiers
    // ================================================================

    /** Équivalent de CMAESOptimizer (IPOP standard). */
    public static CMAESBuilder ipop (Problem problem)
    {
        return new CMAESBuilder (problem)
                .restart (new IPOPRestart ());
    }


}
