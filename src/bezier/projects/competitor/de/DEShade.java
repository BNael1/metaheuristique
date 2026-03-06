package bezier.projects.competitor.de;

import bezier.evaluation.Problem;
import bezier.projects.competitor.optipath.BoundsChecker;
import bezier.projects.competitor.optipath.Optimizer;
import java.util.ArrayList;
import java.util.Random;

/**
 * SHADE (Success-History based Adaptive Differential Evolution).
 * Remplace jDE par un historique circulaire de taille H pour adapter F et CR.
 *
 * Mecanisme DE : current-to-best/1/bin (identique a DEOptimizer).
 * Adaptation : F ~ Cauchy(M_F[r], 0.1), CR ~ N(M_CR[r], 0.1).
 * Mise a jour historique : moyenne de Lehmer pour F, moyenne ponderee pour CR.
 */
public class DEShade implements Optimizer
{
    // ===== References =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Hyperparametres =====
    private static final int POP_SIZE_FACTOR = 10;
    private static final int H = 10;                // taille de l'historique SHADE
    private static final int LS_INTERVAL = 5;
    private static final int LS_MAX_EVALS = 100;
    private static final double LS_INIT_STEP = 0.5;

    // ===== Population =====
    private int NP;
    private double [][] pop;
    private double [] fitness;

    // ===== Historique SHADE =====
    private double [] MF;
    private double [] MCR;
    private int histIndex;

    // ===== Meilleur individu =====
    private int bestIdx;
    private double bestFitness;
    private double [] bestX;

    private int generationCount;

    // ===== Constructeur =====
    public DEShade (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
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

        // Initialiser l'historique SHADE
        MF  = new double [H];
        MCR = new double [H];
        for (int i = 0; i < H; i++)
        {
            MF [i]  = 0.5;
            MCR [i] = 0.5;
        }
        histIndex = 0;

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        bestIdx = 0;
        generationCount = 0;

        // Initialiser la population aleatoirement
        for (int i = 0; i < NP; i++)
        {
            pop [i] = problem.getRandomControlPoints1DArray ();
            fitness [i] = problem.evaluate (pop [i]);
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
        ArrayList<Double> successF     = new ArrayList<> ();
        ArrayList<Double> successCR    = new ArrayList<> ();
        ArrayList<Double> successDelta = new ArrayList<> ();

        for (int i = 0; i < NP; i++)
        {
            // SHADE : echantillonner F et CR depuis l'historique
            int r = rng.nextInt (H);
            double Fi  = sampleCauchy (MF [r], 0.1);
            double CRi = sampleGaussian (MCR [r], 0.1);

            // Selection de 2 indices distincts != i
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

            // Selection greedy
            double fTrial = problem.evaluate (trial);
            if (fTrial <= fitness [i])
            {
                double delta = fitness [i] - fTrial;
                if (delta > 0)
                {
                    successF.add (Fi);
                    successCR.add (CRi);
                    successDelta.add (delta);
                }
                pop [i] = trial;
                fitness [i] = fTrial;
                updateBest (i);
            }
        }

        // --- Mise a jour de l'historique SHADE ---
        if (!successF.isEmpty ())
        {
            // Moyenne de Lehmer pour F : sum(F^2) / sum(F)
            double sumF = 0, sumF2 = 0;
            for (double f : successF) { sumF += f; sumF2 += f * f; }
            MF [histIndex] = sumF2 / sumF;

            // Moyenne ponderee pour CR (ponderee par l'amelioration de fitness)
            double sumDelta = 0;
            for (double dv : successDelta) sumDelta += dv;
            double wCR = 0;
            for (int i = 0; i < successCR.size (); i++)
            {
                double w = successDelta.get (i) / sumDelta;
                wCR += w * successCR.get (i);
            }
            MCR [histIndex] = wCR;

            histIndex = (histIndex + 1) % H;
        }

        // Recherche locale periodique
        generationCount++;
        if (generationCount % LS_INTERVAL == 0)
            localSearch ();
    }

    // ================================================================
    //  ECHANTILLONNAGE SHADE
    // ================================================================

    /** Cauchy(loc, scale) tronque a (0, 1]. */
    private double sampleCauchy (double loc, double scale)
    {
        double f;
        do
        {
            f = loc + scale * Math.tan (Math.PI * (rng.nextDouble () - 0.5));
        }
        while (f <= 0);
        return Math.min (f, 1.0);
    }

    /** N(mean, std) clampe a [0, 1]. */
    private double sampleGaussian (double mean, double std)
    {
        double cr = mean + std * rng.nextGaussian ();
        return Math.max (0, Math.min (1, cr));
    }

    // ================================================================
    //  RECHERCHE LOCALE (identique a DEOptimizer)
    // ================================================================
    private void localSearch ()
    {
        double [] x = bestX.clone ();
        double fx = bestFitness;
        double step = LS_INIT_STEP;

        for (int eval = 0; eval < LS_MAX_EVALS; eval++)
        {
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
                step *= 0.95;
                if (step < 1e-10) break;
            }
        }

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
