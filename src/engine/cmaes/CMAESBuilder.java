package engine.cmaes;

import engine.core.Optimizer;
import bezier.evaluation.Problem;
import engine.constraints.ConstraintHandler;
import engine.constraints.StandardConstraint;
import engine.covariance.ActiveCovariance;
import engine.covariance.CovarianceUpdate;
import engine.covariance.SeparableWarmupCovariance;
import engine.covariance.StandardCovariance;
import engine.surrogate.DirectEval;
import engine.surrogate.EvalWrapper;
import engine.surrogate.SurrogatePrescreen;
import engine.restart.*;
import engine.sampling.MirrorSampling;
import engine.sampling.SamplingStrategy;
import engine.sampling.StandardSampling;
import engine.core.AlgorithmParameters;

/**
 * Builder pour construire un CMAESCore composable avec les features souhaitées.
 *
 * Exemples d'utilisation :
 * <pre>
 *   // Équivalent de l'ancien CMAESOptimizer (IPOP standard)
 *   Optimizer opt = CMAESBuilder.ipop(problem).build();
 *
 *   // Équivalent de CMAESBipop
 *   Optimizer opt = CMAESBuilder.bipop(problem).build();
 *
 *   // Combinaison libre : Active + Surrogate + BIPOP
 *   Optimizer opt = new CMAESBuilder(problem)
 *       .restart(new BIPOPRestart())
 *       .evaluator(new SurrogatePrescreen(problem, d))
 *       .covariance(new ActiveCovariance(true))
 *       .sampling(new MirrorSampling())
 *       .build();
 * </pre>
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

        // Défauts
        if (restart == null)     restart     = new IPOPRestart ();
        if (evaluator == null)   evaluator   = new DirectEval (problem);
        if (covariance == null)  covariance  = new StandardCovariance ();
        if (constraints == null) constraints = new StandardConstraint ();
        if (sampling == null)    sampling    = new StandardSampling ();
        if (parameters == null)  parameters  = new AlgorithmParameters ();

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

    /** Équivalent de CMAESBipop. */
    public static CMAESBuilder bipop (Problem problem)
    {
        return new CMAESBuilder (problem)
                .restart (new BIPOPRestart ());
    }

    /** Équivalent de CMAESActive (active + mirror + covariance memory). */
    public static CMAESBuilder active (Problem problem)
    {
        return new CMAESBuilder (problem)
                .restart (new IPOPRestart ())
                .covariance (new ActiveCovariance (true))
                .sampling (new MirrorSampling ());
    }

    /** Équivalent de CMAESSurrogate (BIPOP + surrogate RBF). */
    public static CMAESBuilder surrogate (Problem problem)
    {
        int d = 2 * problem.getNControlPoints ();
        return new CMAESBuilder (problem)
                .restart (new BIPOPRestart ())
                .evaluator (new SurrogatePrescreen (problem, d));
    }

    /** Équivalent de CMAESBipopAdaptif (adaptive BIPOP + SHADE sigma). */
    public static CMAESBuilder adaptiveBipop (Problem problem)
    {
        int d = 2 * problem.getNControlPoints ();
        return new CMAESBuilder (problem)
                .restart (new AdaptiveBIPOP (d));
    }

    /** Équivalent de CMAESGridBipop (BIPOP + MAP-Elites QD). */
    public static CMAESBuilder gridBipop (Problem problem)
    {
        return new CMAESBuilder (problem)
                .restart (new GridMapElitesRestart ());
    }

    /**
     * Équivalent de CMAESStochRank (BIPOP + stochastic ranking).
     * @param feasibilityThreshold seuil de faisabilité
     */
    public static CMAESBuilder stochRank (Problem problem, double feasibilityThreshold)
    {
        return new CMAESBuilder (problem)
                .restart (new BIPOPRestart ())
                .constraints (new engine.constraints.StochasticRanking (feasibilityThreshold));
    }

    /**
     * Combinaison maximale : Active + Surrogate + BIPOP adaptatif.
     * Nouvelle combinaison impossible avec l'ancienne architecture !
     */
    public static CMAESBuilder full (Problem problem)
    {
        int d = 2 * problem.getNControlPoints ();
        return new CMAESBuilder (problem)
                .restart (new AdaptiveBIPOP (d))
                .evaluator (new SurrogatePrescreen (problem, d))
                .covariance (new ActiveCovariance (true))
                .sampling (new MirrorSampling ());
    }

    /**
     * Équivalent de CMAESSepWarmup : Sep-CMA-ES warmup (300 gens)
     * puis transition vers Full CMA-ES. BIPOP restarts, toujours
     * en mode Sep au démarrage de chaque run.
     */
    public static CMAESBuilder sepWarmup (Problem problem)
    {
        return new CMAESBuilder (problem)
                .restart (new BIPOPRestart ())
                .covariance (new SeparableWarmupCovariance (new StandardCovariance ()));
    }
}
