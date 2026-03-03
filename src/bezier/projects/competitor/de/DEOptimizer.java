package bezier.projects.competitor.de;

import bezier.evaluation.Problem;
import bezier.projects.competitor.optipath.BoundsChecker;
import bezier.projects.competitor.optipath.Optimizer;
import java.util.Arrays;
import java.util.Random;

/**
 * Differential Evolution (DE/current-to-best/1/bin) avec variante auto-adaptative jDE
 * et recherche locale périodique (hill-climbing gaussien).
 *
 * Encodage : double[] plat de taille d = 2 * nControlPoints.
 * Une génération par appel à step() : NP essais + sélection, plus recherche locale optionnelle.
 */
public class DEOptimizer implements Optimizer
{
    // ===== Références =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Hyperparamètres =====
    private static final int POP_SIZE_FACTOR = 10;        // NP = POP_SIZE_FACTOR * d
    private static final double F_INIT = 0.7;             // facteur de mutation initial
    private static final double CR_INIT = 0.9;            // taux de croisement initial
    private static final double TAU_F = 0.1;              // proba d'adaptation de F (jDE)
    private static final double TAU_CR = 0.1;             // proba d'adaptation de CR (jDE)
    private static final int LS_INTERVAL = 5;             // recherche locale toutes les N générations
    private static final int LS_MAX_EVALS = 100;          // nb max d'évaluations par recherche locale
    private static final double LS_INIT_STEP = 0.5;       // pas initial de la recherche locale

    // ===== Population =====
    private int NP;
    private double [][] pop;
    private double [] fitness;
    private double [] indF;    // F par individu (jDE)
    private double [] indCR;   // CR par individu (jDE)

    // ===== Meilleur individu =====
    private int bestIdx;
    private double bestFitness;
    private double [] bestX;

    private int generationCount;

    // ===== Constructeur =====
    public DEOptimizer (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();
    }

    @Override
    public void init ()
    {
        NP = Math.max (20, POP_SIZE_FACTOR * d);
        pop = new double [NP][d];
        fitness = new double [NP];
        indF = new double [NP];
        indCR = new double [NP];

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        bestIdx = 0;
        generationCount = 0;

        // Initialiser la population aléatoirement
        for (int i = 0; i < NP; i++)
        {
            pop [i] = problem.getRandomControlPoints1DArray ();
            fitness [i] = problem.evaluate (pop [i]);
            indF [i] = F_INIT;
            indCR [i] = CR_INIT;
            updateBest (i);
        }

        // Injecter la solution "ligne droite" comme individu 0
        System.arraycopy (initMean, 0, pop [0], 0, d);
        bounds.clampInPlace (pop [0]);
        fitness [0] = problem.evaluate (pop [0]);
        updateBest (0);
    }

    @Override
    public void step ()
    {
        // ~~~ 1. Mutation + croisement + sélection (1 génération) ~~~
        for (int i = 0; i < NP; i++)
        {
            // jDE : auto-adaptation de F et CR
            double Fi = indF [i];
            double CRi = indCR [i];
            if (rng.nextDouble () < TAU_F)  Fi = 0.1 + 0.9 * rng.nextDouble ();
            if (rng.nextDouble () < TAU_CR) CRi = rng.nextDouble ();

            // Sélection de 2 indices distincts ≠ i
            int r1, r2;
            do { r1 = rng.nextInt (NP); } while (r1 == i);
            do { r2 = rng.nextInt (NP); } while (r2 == i || r2 == r1);

            // Mutation : current-to-best/1
            double [] trial = new double [d];
            int jRand = rng.nextInt (d);
            for (int j = 0; j < d; j++)
            {
                if (rng.nextDouble () < CRi || j == jRand)
                {
                    trial [j] = pop [i][j]
                              + Fi * (bestX [j] - pop [i][j])
                              + Fi * (pop [r1][j] - pop [r2][j]);
                }
                else
                {
                    trial [j] = pop [i][j];
                }
            }
            bounds.clampInPlace (trial);

            // Sélection greedy
            double fTrial = problem.evaluate (trial);
            if (fTrial <= fitness [i])
            {
                pop [i] = trial;
                fitness [i] = fTrial;
                indF [i] = Fi;
                indCR [i] = CRi;
                updateBest (i);
            }
        }

        // ~~~ 2. Recherche locale périodique sur le meilleur ~~~
        generationCount++;
        if (generationCount % LS_INTERVAL == 0)
        {
            localSearch ();
        }
    }

    /**
     * Recherche locale : hill-climbing avec perturbation gaussienne, appliqué au meilleur individu.
     * Budget limité à LS_MAX_EVALS évaluations.
     */
    private void localSearch ()
    {
        double [] x = bestX.clone ();
        double fx = bestFitness;
        double step = LS_INIT_STEP;

        for (int eval = 0; eval < LS_MAX_EVALS; eval++)
        {
            // Perturbation gaussienne
            double [] xNew = new double [d];
            for (int j = 0; j < d; j++)
                xNew [j] = x [j] + rng.nextGaussian () * step;
            bounds.clampInPlace (xNew);

            double fNew = problem.evaluate (xNew);
            if (fNew < fx)
            {
                x = xNew;
                fx = fNew;
            }
            else
            {
                step *= 0.95; // réduire le pas si pas d'amélioration
                if (step < 1e-10) break;
            }
        }

        // Remettre le résultat dans la population
        if (fx < bestFitness)
        {
            bestX = x.clone ();
            bestFitness = fx;
            pop [bestIdx] = x.clone ();
            fitness [bestIdx] = fx;
        }
    }

    private void updateBest (int i)
    {
        if (fitness [i] < bestFitness)
        {
            bestFitness = fitness [i];
            bestX = pop [i].clone ();
            bestIdx = i;
        }
    }

    @Override
    public double [] getBestX () { return bestX; }
}
