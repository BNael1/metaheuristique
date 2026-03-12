package engine.de;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.Random;

/**
 * DE/current-to-best/1/bin avec auto-adaptation jDE des paramètres F et CR.
 * Recherche locale périodique (1+1)-ES.
 */
public class DECore implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random ();

    // Population
    private final int NP;
    private double [][] pop;
    private double [] fitness;

    // jDE auto-adaptation
    private double [] indF;
    private double [] indCR;
    private static final double F_INIT  = 0.7;
    private static final double CR_INIT = 0.9;
    private static final double TAU_F   = 0.1;
    private static final double TAU_CR  = 0.1;

    // Local search
    private static final int LS_INTERVAL = 5;

    // Best tracking
    private int bestIdx;
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double [] bestX;
    private int generationCount;
    private int totalEvals;

    public DECore (Problem problem, int d, double [] lb, double [] ub)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.NP = 10 * d;
    }

    @Override
    public void init ()
    {
        pop = new double [NP][d];
        fitness = new double [NP];
        indF  = new double [NP];
        indCR = new double [NP];
        bestIdx = 0;
        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        generationCount = 0;
        totalEvals = 0;

        for (int i = 0; i < NP; i++)
        {
            for (int j = 0; j < d; j++)
                pop [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);

            fitness [i] = problem.evaluate (pop [i]);
            totalEvals++;
            indF [i] = F_INIT;
            indCR [i] = CR_INIT;

            if (fitness [i] < bestFitness)
            {
                bestFitness = fitness [i];
                bestIdx = i;
                bestX = pop [i].clone ();
            }
        }
    }

    @Override
    public void step ()
    {
        for (int i = 0; i < NP; i++)
        {
            // jDE auto-adaptation
            double Fi  = indF [i];
            double CRi = indCR [i];
            if (rng.nextDouble () < TAU_F)  Fi  = 0.1 + 0.9 * rng.nextDouble ();
            if (rng.nextDouble () < TAU_CR) CRi = rng.nextDouble ();

            // Sélection r1, r2 ≠ i
            int r1, r2;
            do { r1 = rng.nextInt (NP); } while (r1 == i);
            do { r2 = rng.nextInt (NP); } while (r2 == i || r2 == r1);

            // Mutation current-to-best/1/bin
            int jRand = rng.nextInt (d);
            double [] trial = new double [d];
            for (int j = 0; j < d; j++)
            {
                if (rng.nextDouble () < CRi || j == jRand)
                    trial [j] = pop [i][j] + Fi * (bestX [j] - pop [i][j])
                               + Fi * (pop [r1][j] - pop [r2][j]);
                else
                    trial [j] = pop [i][j];
            }
            bounds.clampInPlace (trial);

            // Sélection greedy
            double fTrial = problem.evaluate (trial);
            totalEvals++;

            if (fTrial <= fitness [i])
            {
                pop [i] = trial;
                fitness [i] = fTrial;
                indF [i] = Fi;
                indCR [i] = CRi;

                if (fTrial < bestFitness)
                {
                    bestFitness = fTrial;
                    bestIdx = i;
                    bestX = trial.clone ();
                }
            }
        }

        generationCount++;

        // Recherche locale périodique
        if (generationCount % LS_INTERVAL == 0)
            localSearch ();
    }

    private void localSearch ()
    {
        double step = bounds.getRange (0) / 20.0;
        double [] x = bestX.clone ();
        double fx = bestFitness;

        for (int e = 0; e < 50; e++)
        {
            double [] xNew = new double [d];
            for (int i = 0; i < d; i++)
                xNew [i] = x [i] + rng.nextGaussian () * step;
            bounds.clampInPlace (xNew);

            double fNew = problem.evaluate (xNew);
            totalEvals++;

            if (fNew < fx)
            {
                x = xNew;
                fx = fNew;
                step *= 1.2;
            }
            else
            {
                step *= 0.8;
            }
        }

        if (fx < bestFitness)
        {
            bestFitness = fx;
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
