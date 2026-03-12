package engine.ga;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.Random;

/**
 * GA réel-codé : SBX (Simulated Binary Crossover) + mutation polynomiale
 * + sélection par tournoi binaire + élitisme.
 */
public class GACore implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random ();

    // Population
    private static final int NP = 100;
    private double [][] pop;
    private double [] fitness;

    // Paramètres
    private static final double ETA_C   = 20.0;
    private static final double ETA_M   = 20.0;
    private static final double P_CROSS = 0.9;
    private final double pMut;

    // Élitisme
    private double [] eliteX;
    private double eliteFitness;

    // Best tracking
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double [] bestX;
    private int generationCount;
    private int totalEvals;

    public GACore (Problem problem, int d, double [] lb, double [] ub)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.pMut = 1.0 / d;
    }

    @Override
    public void init ()
    {
        pop = new double [NP][d];
        fitness = new double [NP];
        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        eliteFitness = Double.POSITIVE_INFINITY;
        generationCount = 0;
        totalEvals = 0;

        for (int i = 0; i < NP; i++)
        {
            for (int j = 0; j < d; j++)
                pop [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);
            fitness [i] = problem.evaluate (pop [i]);
            totalEvals++;

            if (fitness [i] < bestFitness)
            {
                bestFitness = fitness [i];
                bestX = pop [i].clone ();
                eliteFitness = fitness [i];
                eliteX = pop [i].clone ();
            }
        }
    }

    @Override
    public void step ()
    {
        double [][] newPop = new double [NP][d];
        double [] newFitness = new double [NP];

        for (int i = 0; i < NP; i += 2)
        {
            // Tournoi binaire
            int p1 = tournament ();
            int p2 = tournament ();

            double [] c1, c2;
            if (rng.nextDouble () < P_CROSS)
            {
                double [][] children = sbxCrossover (pop [p1], pop [p2]);
                c1 = children [0];
                c2 = children [1];
            }
            else
            {
                c1 = pop [p1].clone ();
                c2 = pop [p2].clone ();
            }

            polynomialMutation (c1);
            polynomialMutation (c2);
            bounds.clampInPlace (c1);
            bounds.clampInPlace (c2);

            newPop [i] = c1;
            newFitness [i] = problem.evaluate (c1);
            totalEvals++;
            updateBest (c1, newFitness [i]);

            if (i + 1 < NP)
            {
                newPop [i + 1] = c2;
                newFitness [i + 1] = problem.evaluate (c2);
                totalEvals++;
                updateBest (c2, newFitness [i + 1]);
            }
        }

        // Élitisme : remplacer le pire
        int worstIdx = 0;
        for (int i = 1; i < NP; i++)
            if (newFitness [i] > newFitness [worstIdx]) worstIdx = i;

        if (eliteFitness < newFitness [worstIdx])
        {
            newPop [worstIdx] = eliteX.clone ();
            newFitness [worstIdx] = eliteFitness;
        }

        pop = newPop;
        fitness = newFitness;

        // Mettre à jour l'élite
        for (int i = 0; i < NP; i++)
        {
            if (fitness [i] < eliteFitness)
            {
                eliteFitness = fitness [i];
                eliteX = pop [i].clone ();
            }
        }

        generationCount++;
    }

    private int tournament ()
    {
        int a = rng.nextInt (NP);
        int b = rng.nextInt (NP);
        return (fitness [a] <= fitness [b]) ? a : b;
    }

    private double [][] sbxCrossover (double [] p1, double [] p2)
    {
        double [] c1 = new double [d];
        double [] c2 = new double [d];

        for (int j = 0; j < d; j++)
        {
            if (rng.nextDouble () < 0.5)
            {
                if (Math.abs (p1 [j] - p2 [j]) > 1e-14)
                {
                    double y1 = Math.min (p1 [j], p2 [j]);
                    double y2 = Math.max (p1 [j], p2 [j]);
                    double lb = bounds.getLb () [j];
                    double ub = bounds.getUb () [j];

                    double beta1 = 1.0 + 2.0 * (y1 - lb) / (y2 - y1);
                    double beta2 = 1.0 + 2.0 * (ub - y2) / (y2 - y1);

                    double alpha1 = 2.0 - Math.pow (beta1, -(ETA_C + 1));
                    double alpha2 = 2.0 - Math.pow (beta2, -(ETA_C + 1));

                    double u = rng.nextDouble ();
                    double betaq1 = (u <= 1.0 / alpha1)
                            ? Math.pow (u * alpha1, 1.0 / (ETA_C + 1))
                            : Math.pow (1.0 / (2.0 - u * alpha1), 1.0 / (ETA_C + 1));

                    u = rng.nextDouble ();
                    double betaq2 = (u <= 1.0 / alpha2)
                            ? Math.pow (u * alpha2, 1.0 / (ETA_C + 1))
                            : Math.pow (1.0 / (2.0 - u * alpha2), 1.0 / (ETA_C + 1));

                    c1 [j] = 0.5 * ((y1 + y2) - betaq1 * (y2 - y1));
                    c2 [j] = 0.5 * ((y1 + y2) + betaq2 * (y2 - y1));
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
        return new double [][] {c1, c2};
    }

    private void polynomialMutation (double [] x)
    {
        for (int j = 0; j < d; j++)
        {
            if (rng.nextDouble () < pMut)
            {
                double lb = bounds.getLb () [j];
                double ub = bounds.getUb () [j];
                double delta;

                double u = rng.nextDouble ();
                if (u < 0.5)
                    delta = Math.pow (2.0 * u, 1.0 / (ETA_M + 1)) - 1.0;
                else
                    delta = 1.0 - Math.pow (2.0 * (1.0 - u), 1.0 / (ETA_M + 1));

                x [j] += delta * (ub - lb);
            }
        }
    }

    private void updateBest (double [] x, double f)
    {
        if (f < bestFitness)
        {
            bestFitness = f;
            bestX = x.clone ();
        }
    }

    @Override
    public double [] getBestX ()       { return bestX; }
    @Override
    public double getBestFitness ()    { return bestFitness; }
    @Override
    public boolean shouldRestart ()    { return false; }

    @Override
    public OptimizerState getState ()
    {
        return new OptimizerState (generationCount, totalEvals, 0,
                bestFitness, 0, 0, bestX);
    }
}
