package engine.de;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.ArrayList;
import java.util.Random;

/**
 * SHADE : Success-History based Adaptive Differential Evolution.
 *
 * Adapte F et CR via un historique circulaire des paramètres ayant conduit
 * à des améliorations. F échantillonné par Cauchy, CR par Gaussienne.
 *
 * Référence : Tanabe & Fukunaga (2013) "Success-History Based Parameter
 * Adaptation for Differential Evolution"
 */
public class DESHADECore implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random ();

    // Population
    private final int NP;
    private double [][] pop;
    private double [] fitness;

    // SHADE history
    private static final int H = 10;
    private final double [] MF  = new double [H];
    private final double [] MCR = new double [H];
    private int histIndex = 0;

    // Local search
    private static final int LS_INTERVAL = 5;

    // Best tracking
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double [] bestX;
    private int generationCount;
    private int totalEvals;

    public DESHADECore (Problem problem, int d, double [] lb, double [] ub)
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
        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        generationCount = 0;
        totalEvals = 0;

        for (int i = 0; i < H; i++)
        {
            MF [i]  = 0.5;
            MCR [i] = 0.5;
        }

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
            }
        }
    }

    @Override
    public void step ()
    {
        ArrayList<Double> successF     = new ArrayList<> ();
        ArrayList<Double> successCR    = new ArrayList<> ();
        ArrayList<Double> successDelta = new ArrayList<> ();

        for (int i = 0; i < NP; i++)
        {
            // Échantillonner F et CR depuis l'historique
            int r = rng.nextInt (H);
            double Fi  = sampleCauchy (MF [r], 0.1);
            double CRi = sampleGaussian (MCR [r], 0.1);
            Fi  = Math.max (0.01, Math.min (Fi, 1.0));
            CRi = Math.max (0.0, Math.min (CRi, 1.0));

            // Sélection r1, r2 ≠ i
            int r1, r2;
            do { r1 = rng.nextInt (NP); } while (r1 == i);
            do { r2 = rng.nextInt (NP); } while (r2 == i || r2 == r1);

            // Mutation current-to-best/1
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

            double fTrial = problem.evaluate (trial);
            totalEvals++;

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

                if (fTrial < bestFitness)
                {
                    bestFitness = fTrial;
                    bestX = trial.clone ();
                }
            }
        }

        // Mise à jour de l'historique SHADE
        if (!successF.isEmpty ())
        {
            double totalDelta = 0;
            for (double d : successDelta) totalDelta += d;

            // Lehmer mean pour F
            double sumF2 = 0, sumF = 0;
            for (int i = 0; i < successF.size (); i++)
            {
                double w = successDelta.get (i) / totalDelta;
                sumF2 += w * successF.get (i) * successF.get (i);
                sumF  += w * successF.get (i);
            }
            MF [histIndex] = (sumF > 0) ? sumF2 / sumF : 0.5;

            // Weighted mean pour CR
            double sumCR = 0;
            for (int i = 0; i < successCR.size (); i++)
            {
                double w = successDelta.get (i) / totalDelta;
                sumCR += w * successCR.get (i);
            }
            MCR [histIndex] = sumCR;

            histIndex = (histIndex + 1) % H;
        }

        generationCount++;

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

            if (fNew < fx) { x = xNew; fx = fNew; step *= 1.2; }
            else           { step *= 0.8; }
        }

        if (fx < bestFitness)
        {
            bestFitness = fx;
            bestX = x.clone ();
        }
    }

    private double sampleCauchy (double loc, double scale)
    {
        return loc + scale * Math.tan (Math.PI * (rng.nextDouble () - 0.5));
    }

    private double sampleGaussian (double mean, double std)
    {
        return mean + std * rng.nextGaussian ();
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
