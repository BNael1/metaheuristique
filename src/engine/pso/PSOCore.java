package engine.pso;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.Random;

/**
 * Particle Swarm Optimization (PSO) avec inertie décroissante linéairement
 * et mécanisme anti-stagnation.
 *
 * Paramètres :
 *   N     = 10 * d          (taille de l'essaim)
 *   w0    = 0.9             (inertie initiale)
 *   wf    = 0.4             (inertie finale)
 *   c1    = 2.0             (poids cognitif)
 *   c2    = 2.0             (poids social)
 *   vMax  = 0.2 * range(i)  (vitesse maximale par dimension)
 *
 * Stagnation : si gbest ne s'améliore pas depuis N*10 appels à step(),
 * on réinitialise 50 % des particules (position + vitesse) tout en
 * conservant gbest.
 *
 * Les positions NE sont PAS clampées — problem.evaluate() intègre déjà
 * une pénalité de frontière (×100) qui gère les sorties de bornes.
 * Seules les vitesses sont clampées dans [-vMax, +vMax].
 */
public class PSOCore implements Optimizer
{
    // ---------------------------------------------------------------
    // Constantes algorithmiques
    // ---------------------------------------------------------------
    private static final double W0       = 0.9;
    private static final double WF       = 0.4;
    private static final int    GEN_MAX  = 10_000;
    private static final double C1       = 2.0;
    private static final double C2       = 2.0;
    private static final double VMAX_RATIO = 0.2;

    // ---------------------------------------------------------------
    // État de la recherche
    // ---------------------------------------------------------------
    private final Problem      problem;
    private final int          d;
    private final BoundsChecker bounds;
    private final Random        rng = new Random ();

    /** Taille de l'essaim. */
    private final int N;

    /** Vitesse maximale par dimension. */
    private double [] vMax;

    /** Positions des particules. */
    private double [][] x;

    /** Vitesses des particules. */
    private double [][] v;

    /** Meilleure position personnelle de chaque particule. */
    private double [][] pbest;

    /** Coût de la meilleure position personnelle de chaque particule. */
    private double [] pbestFitness;

    /** Meilleure position globale (gbest). */
    private double [] gbest;

    /** Coût de gbest. */
    private double gbestFitness = Double.POSITIVE_INFINITY;

    /** Compteur de générations (appels à step()). */
    private int generationCount;

    /** Nombre total d'évaluations de problem.evaluate(). */
    private int totalEvals;

    /** Nombre de générations consécutives sans amélioration de gbest. */
    private int stagnationCounter;

    /** Seuil de stagnation : N * 10 générations sans amélioration. */
    private final int stagnationThreshold;

    // ---------------------------------------------------------------
    // Constructeur
    // ---------------------------------------------------------------

    /**
     * @param problem instance du problème Bézier
     * @param d       dimension du vecteur de décision (= 2 * nControlPoints)
     * @param lb      bornes inférieures par dimension
     * @param ub      bornes supérieures par dimension
     */
    public PSOCore (Problem problem, int d, double [] lb, double [] ub)
    {
        this.problem  = problem;
        this.d        = d;
        this.bounds   = new BoundsChecker (lb, ub);
        this.N        = 10 * d;
        this.stagnationThreshold = N * 10;
    }

    // ---------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------

    @Override
    public void init ()
    {
        // Pré-calcul des vitesses maximales par dimension
        vMax = new double [d];
        for (int j = 0; j < d; j++)
            vMax [j] = VMAX_RATIO * bounds.getRange (j);

        // Allocation des tableaux
        x            = new double [N][d];
        v            = new double [N][d];
        pbest        = new double [N][d];
        pbestFitness = new double [N];
        gbest        = null;
        gbestFitness = Double.POSITIVE_INFINITY;
        generationCount  = 0;
        totalEvals       = 0;
        stagnationCounter = 0;

        // Initialisation de chaque particule
        for (int i = 0; i < N; i++)
        {
            // Position initiale : uniforme dans [lb, ub]
            for (int j = 0; j < d; j++)
                x [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);

            // Vitesse initiale : petit bruit uniforme dans [-vMax/2, +vMax/2]
            for (int j = 0; j < d; j++)
                v [i][j] = (rng.nextDouble () - 0.5) * vMax [j];

            // Évaluation initiale
            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            // Initialisation pbest = position initiale
            pbest [i]        = x [i].clone ();
            pbestFitness [i] = fitness;

            // Mise à jour gbest
            if (fitness < gbestFitness)
            {
                gbestFitness = fitness;
                gbest        = x [i].clone ();
            }
        }
    }

    // ---------------------------------------------------------------
    // Boucle principale — UNE génération
    // ---------------------------------------------------------------

    @Override
    public void step ()
    {
        // Calcul de l'inertie décroissante linéairement
        double w = W0 - (W0 - WF) * ((double) generationCount / GEN_MAX);
        // On garde w dans [WF, W0] même si generationCount dépasse GEN_MAX
        if (w < WF) w = WF;

        // Mise à jour de chaque particule
        for (int i = 0; i < N; i++)
        {
            for (int j = 0; j < d; j++)
            {
                double r1 = rng.nextDouble ();
                double r2 = rng.nextDouble ();

                // Mise à jour de la vitesse
                v [i][j] = w * v [i][j]
                         + C1 * r1 * (pbest [i][j] - x [i][j])
                         + C2 * r2 * (gbest [j]    - x [i][j]);

                // Clamp de la vitesse dans [-vMax, +vMax]
                if (v [i][j] >  vMax [j]) v [i][j] =  vMax [j];
                if (v [i][j] < -vMax [j]) v [i][j] = -vMax [j];

                // Mise à jour de la position — PAS de clamp (pénalité gérée par evaluate)
                x [i][j] += v [i][j];
            }

            // Évaluation de la nouvelle position
            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            // Mise à jour pbest
            if (fitness < pbestFitness [i])
            {
                pbestFitness [i] = fitness;
                pbest [i]        = x [i].clone ();
            }

            // Mise à jour gbest
            if (fitness < gbestFitness)
            {
                gbestFitness = fitness;
                gbest        = x [i].clone ();
                stagnationCounter = 0;
            }
        }

        generationCount++;
        stagnationCounter++;

        // Mécanisme anti-stagnation
        if (stagnationCounter >= stagnationThreshold)
            reinitializeHalfSwarm ();
    }

    // ---------------------------------------------------------------
    // Anti-stagnation : réinitialisation de 50 % de l'essaim
    // ---------------------------------------------------------------

    /**
     * Réinitialise aléatoirement la position et la vitesse de la moitié
     * des particules (les N/2 particules aux pires pbest).
     * Le gbest est conservé intact.
     */
    private void reinitializeHalfSwarm ()
    {
        // Identifier les N/2 particules avec les pires pbestFitness
        int half = N / 2;

        // Tableau d'indices trié par pbestFitness décroissant
        Integer [] indices = new Integer [N];
        for (int i = 0; i < N; i++) indices [i] = i;
        java.util.Arrays.sort (indices,
                (a, b) -> Double.compare (pbestFitness [b], pbestFitness [a]));

        // Réinitialiser les half pires particules
        for (int k = 0; k < half; k++)
        {
            int i = indices [k];

            // Nouvelle position aléatoire dans [lb, ub]
            for (int j = 0; j < d; j++)
                x [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);

            // Nouvelle vitesse aléatoire dans [-vMax/2, +vMax/2]
            for (int j = 0; j < d; j++)
                v [i][j] = (rng.nextDouble () - 0.5) * vMax [j];

            // Réévaluation et réinitialisation pbest
            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            pbest [i]        = x [i].clone ();
            pbestFitness [i] = fitness;

            // Vérifier si la nouvelle position améliore gbest
            if (fitness < gbestFitness)
            {
                gbestFitness = fitness;
                gbest        = x [i].clone ();
            }
        }

        stagnationCounter = 0;
    }

    // ---------------------------------------------------------------
    // Accesseurs Optimizer
    // ---------------------------------------------------------------

    @Override
    public double [] getBestX ()
    {
        return gbest != null ? gbest.clone () : null;
    }

    @Override
    public double getBestFitness ()
    {
        return gbestFitness;
    }

    /** PSO n'utilise pas de mécanisme de restart externe. */
    @Override
    public boolean shouldRestart ()
    {
        return false;
    }

    @Override
    public OptimizerState getState ()
    {
        return new OptimizerState (generationCount, totalEvals, 0,
                gbestFitness, 0.0, 0.0, gbest);
    }
}
