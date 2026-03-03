package bezier.projects.competitor.ga;

import bezier.evaluation.Problem;
import bezier.projects.competitor.optipath.BoundsChecker;
import bezier.projects.competitor.optipath.Optimizer;
import java.util.Arrays;
import java.util.Random;

/**
 * Algorithme Génétique réel-codé avec :
 *   - croisement SBX (Simulated Binary Crossover) borné,
 *   - mutation polynomiale bornée,
 *   - sélection par tournoi binaire,
 *   - élitisme (le meilleur individu survit toujours).
 *
 * Référence : Deb & Agrawal (1995), Deb & Beyer (2001).
 *
 * Encodage : double[] plat de taille d = 2 * nControlPoints.
 * Une génération par appel à step().
 */
public class GAOptimizer implements Optimizer
{
    // ===== Références =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Hyperparamètres =====
    private static final int POP_SIZE = 100;
    private static final double ETA_C = 20.0;    // indice de distribution SBX
    private static final double ETA_M = 20.0;    // indice de distribution mutation polynomiale
    private static final double P_CROSS = 0.9;   // probabilité de croisement
    // p_mut = 1.0 / d  (calculé dynamiquement)

    // ===== Population =====
    private int NP;
    private double [][] pop;
    private double [] fitness;
    private double [] eliteX;
    private double eliteFitness;
    private double pMut;

    // ===== Constructeur =====
    public GAOptimizer (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
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
        NP = POP_SIZE;
        if (NP % 2 != 0) NP++; // assurer parité pour le croisement
        pMut = 1.0 / d;

        pop = new double [NP][d];
        fitness = new double [NP];
        eliteFitness = Double.POSITIVE_INFINITY;
        eliteX = null;

        // Initialisation aléatoire
        for (int i = 0; i < NP; i++)
        {
            pop [i] = problem.getRandomControlPoints1DArray ();
            fitness [i] = problem.evaluate (pop [i]);
            updateElite (pop [i], fitness [i]);
        }

        // Injecter la solution "ligne droite"
        System.arraycopy (initMean, 0, pop [0], 0, d);
        bounds.clampInPlace (pop [0]);
        fitness [0] = problem.evaluate (pop [0]);
        updateElite (pop [0], fitness [0]);
    }

    @Override
    public void step ()
    {
        // 1. Sélection par tournoi binaire
        double [][] parents = new double [NP][d];
        for (int i = 0; i < NP; i++)
        {
            int a = rng.nextInt (NP);
            int b = rng.nextInt (NP);
            parents [i] = (fitness [a] <= fitness [b]) ? pop [a].clone () : pop [b].clone ();
        }

        // 2. Croisement SBX borné + 3. Mutation polynomiale
        double [][] offspring = new double [NP][d];
        double [] offFitness = new double [NP];

        for (int i = 0; i < NP; i += 2)
        {
            double [] p1 = parents [i];
            double [] p2 = parents [i + 1];

            double [] c1 = new double [d];
            double [] c2 = new double [d];

            if (rng.nextDouble () < P_CROSS)
                sbxCrossover (p1, p2, c1, c2);
            else
            {
                System.arraycopy (p1, 0, c1, 0, d);
                System.arraycopy (p2, 0, c2, 0, d);
            }

            polynomialMutation (c1);
            polynomialMutation (c2);

            bounds.clampInPlace (c1);
            bounds.clampInPlace (c2);

            offspring [i]     = c1;
            offspring [i + 1] = c2;
        }

        // 4. Évaluation des offspring
        for (int i = 0; i < NP; i++)
        {
            offFitness [i] = problem.evaluate (offspring [i]);
            updateElite (offspring [i], offFitness [i]);
        }

        // 5. Remplacement avec élitisme
        pop = offspring;
        fitness = offFitness;

        // Trouver le pire et le remplacer par l'élite si nécessaire
        int worstIdx = 0;
        for (int i = 1; i < NP; i++)
            if (fitness [i] > fitness [worstIdx]) worstIdx = i;

        if (eliteFitness < fitness [worstIdx])
        {
            pop [worstIdx] = eliteX.clone ();
            fitness [worstIdx] = eliteFitness;
        }

        // Mettre à jour l'élite de cette génération
        for (int i = 0; i < NP; i++)
            updateElite (pop [i], fitness [i]);
    }

    // ====================================================================
    //  SBX — Simulated Binary Crossover (borné, Deb & Agrawal)
    // ====================================================================
    private void sbxCrossover (double [] p1, double [] p2, double [] c1, double [] c2)
    {
        double [] lb = bounds.getLb ();
        double [] ub = bounds.getUb ();

        for (int j = 0; j < d; j++)
        {
            if (rng.nextDouble () <= 0.5)
            {
                if (Math.abs (p1 [j] - p2 [j]) > 1e-14)
                {
                    double y1, y2;
                    if (p1 [j] < p2 [j]) { y1 = p1 [j]; y2 = p2 [j]; }
                    else                  { y1 = p2 [j]; y2 = p1 [j]; }

                    double diff = y2 - y1;

                    // --- Bêta borné inférieur ---
                    double betaL = 1.0 + 2.0 * (y1 - lb [j]) / diff;
                    double alphaL = 2.0 - Math.pow (betaL, -(ETA_C + 1.0));

                    // --- Bêta borné supérieur ---
                    double betaU = 1.0 + 2.0 * (ub [j] - y2) / diff;
                    double alphaU = 2.0 - Math.pow (betaU, -(ETA_C + 1.0));

                    double u1 = rng.nextDouble ();
                    double betaq1 = computeBetaq (u1, alphaL);

                    double u2 = rng.nextDouble ();
                    double betaq2 = computeBetaq (u2, alphaU);

                    c1 [j] = 0.5 * ((y1 + y2) - betaq1 * diff);
                    c2 [j] = 0.5 * ((y1 + y2) + betaq2 * diff);
                }
                else
                {
                    c1 [j] = p1 [j];
                    c2 [j] = p2 [j];
                }
            }
            else
            {
                c1 [j] = p1 [j];
                c2 [j] = p2 [j];
            }
        }
    }

    private double computeBetaq (double u, double alpha)
    {
        if (u <= 1.0 / alpha)
            return Math.pow (u * alpha, 1.0 / (ETA_C + 1.0));
        else
            return Math.pow (1.0 / (2.0 - u * alpha), 1.0 / (ETA_C + 1.0));
    }

    // ====================================================================
    //  MUTATION POLYNOMIALE BORNÉE (Deb & Goyal 1996)
    // ====================================================================
    private void polynomialMutation (double [] x)
    {
        double [] lb = bounds.getLb ();
        double [] ub = bounds.getUb ();

        for (int j = 0; j < d; j++)
        {
            if (rng.nextDouble () < pMut)
            {
                double y = x [j];
                double delta1 = (y - lb [j]) / (ub [j] - lb [j]);
                double delta2 = (ub [j] - y) / (ub [j] - lb [j]);
                double u = rng.nextDouble ();
                double deltaq;

                if (u < 0.5)
                {
                    double xy = 1.0 - delta1;
                    double val = 2.0 * u + (1.0 - 2.0 * u) * Math.pow (xy, ETA_M + 1.0);
                    deltaq = Math.pow (val, 1.0 / (ETA_M + 1.0)) - 1.0;
                }
                else
                {
                    double xy = 1.0 - delta2;
                    double val = 2.0 * (1.0 - u) + 2.0 * (u - 0.5) * Math.pow (xy, ETA_M + 1.0);
                    deltaq = 1.0 - Math.pow (val, 1.0 / (ETA_M + 1.0));
                }

                x [j] = y + deltaq * (ub [j] - lb [j]);
            }
        }
    }

    private void updateElite (double [] x, double f)
    {
        if (f < eliteFitness)
        {
            eliteFitness = f;
            eliteX = x.clone ();
        }
    }

    @Override
    public double [] getBestX () { return eliteX; }
}
