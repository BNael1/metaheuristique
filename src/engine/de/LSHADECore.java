package engine.de;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * L-SHADE : Linear Success-History based Adaptive Differential Evolution.
 *
 * Améliorations par rapport à SHADE :
 * 1. LPSR (Linear Population Size Reduction) : La taille de la population diminue linéairement
 *    de N_init à N_min au fil des évaluations.
 * 2. Archive externe (A) : Utilisée pour la mutation current-to-pbest/1.
 *    v = x + F * (pbest - x) + F * (r1 - r2_tilde)
 *    où r2_tilde est pris dans P U A.
 *
 * Référence : Ryoji Tanabe and Alex Fukunaga, "Improving the search performance of SHADE using linear population size reduction", CEC 2014.
 */
public class LSHADECore implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random ();

    // Population
    private int NP;
    private final int initNP;
    private final int minNP = 4;
    private double [][] pop;
    private double [] fitness;

    // Archive externe
    private final ArrayList<double []> archive;
    private final int archiveSize;

    // SHADE history
    private static final int H = 6; // Taille historique recommandée pour L-SHADE
    private final double [] MF  = new double [H];
    private final double [] MCR = new double [H];
    private int histIndex = 0;

    // Best tracking
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double [] bestX;
    private int generationCount;
    private int totalEvals;
    private final int maxEvals;

    public LSHADECore (Problem problem, int d, double [] lb, double [] ub, int maxEvals)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        
        // L-SHADE utilise typiquement une population initiale plus grande (ex: 18*d)
        // car elle va être réduite.
        this.initNP = 18 * d; 
        this.NP = initNP;
        this.maxEvals = maxEvals;

        this.archive = new ArrayList<> ();
        this.archiveSize = (int) (2.6 * initNP); // Taille archive typique
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
        archive.clear ();
        histIndex = 0;

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

        // Tri des indices pour p-best (les meilleurs en premier)
        Integer [] sortedIndices = new Integer [NP];
        for (int i = 0; i < NP; i++) sortedIndices [i] = i;
        Arrays.sort (sortedIndices, (a, b) -> Double.compare (fitness [a], fitness [b]));

        double [][] newPop = new double [NP][d];
        double [] newFitness = new double [NP];

        for (int i = 0; i < NP; i++)
        {
            // Échantillonner F et CR depuis l'historique
            int r = rng.nextInt (H);
            double Fi  = sampleCauchy (MF [r], 0.1);
            double CRi = sampleGaussian (MCR [r], 0.1);
            Fi  = Math.max (0.01, Math.min (Fi, 1.0)); // Si Fi > 1, cap à 1.
            CRi = Math.max (0.0, Math.min (CRi, 1.0)); // Si CRi < 0, cap à 0.

            // --- Mutation current-to-pbest/1 ---
            // pbest : un des top p% (p = 0.11 typique pour L-SHADE)
            int pBestIndex = sortedIndices [rng.nextInt (Math.max (2, (int) (NP * 0.11)))];
            double [] xPBest = pop [pBestIndex];

            // r1 : différent de i
            int r1;
            do { r1 = rng.nextInt (NP); } while (r1 == i);
            double [] xR1 = pop [r1];

            // r2 : différent de i et r1, pris dans P U A
            int r2;
            double [] xR2;
            int unionSize = NP + archive.size ();
            do {
                int idx = rng.nextInt (unionSize);
                if (idx < NP) {
                    r2 = idx;
                    xR2 = pop [r2];
                } else {
                    r2 = -1; // Marqueur archive
                    xR2 = archive.get (idx - NP);
                }
            } while (r2 == i || r2 == r1);

            // Création du mutant/trial
            int jRand = rng.nextInt (d);
            double [] trial = new double [d];
            for (int j = 0; j < d; j++)
            {
                if (rng.nextDouble () < CRi || j == jRand)
                    trial [j] = pop [i][j] + Fi * (xPBest [j] - pop [i][j]) + Fi * (xR1 [j] - xR2 [j]);
                else
                    trial [j] = pop [i][j];
            }
            bounds.clampInPlace (trial);

            // Évaluation
            double fTrial = problem.evaluate (trial);
            totalEvals++;

            // Sélection
            if (fTrial <= fitness [i])
            {
                newPop [i] = trial;
                newFitness [i] = fTrial;

                if (fTrial < fitness [i]) // Strictement meilleur pour l'historique
                {
                    successF.add (Fi);
                    successCR.add (CRi);
                    successDelta.add (fitness [i] - fTrial);
                    
                    // Ajout du parent à l'archive
                    addToArchive(pop[i].clone());
                }

                if (fTrial < bestFitness)
                {
                    bestFitness = fTrial;
                    bestX = trial.clone ();
                }
            }
            else
            {
                newPop [i] = pop [i];
                newFitness [i] = fitness [i];
            }
        }

        pop = newPop;
        fitness = newFitness;

        // Mise à jour de l'historique SHADE
        updateMemory(successF, successCR, successDelta);

        // --- LPSR : Réduction linéaire de la population ---
        int nextNP = (int) Math.round (((double) (minNP - initNP) / maxEvals) * totalEvals + initNP);
        if (nextNP < minNP) nextNP = minNP;

        if (nextNP < NP)
        {
            reducePopulation (nextNP);
            NP = nextNP;
        }

        generationCount++;
    }

    private void addToArchive(double[] ind) {
        if (archive.size() < archiveSize) {
            archive.add(ind);
        } else {
            // Remplacer aléatoirement
            archive.set(rng.nextInt(archiveSize), ind);
        }
    }

    private void reducePopulation(int nextNP) {
        // Trier la population actuelle pour garder les meilleurs
        Integer [] idx = new Integer [NP];
        for (int i = 0; i < NP; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        double [][] reducedPop = new double [nextNP][d];
        double [] reducedFitness = new double [nextNP];

        for (int i = 0; i < nextNP; i++) {
            reducedPop[i] = pop[idx[i]];
            reducedFitness[i] = fitness[idx[i]];
        }
        
        // IMPORTANT : Ajuster la taille de l'archive aussi selon la formule L-SHADE
        // |A| = round(NP * Arc_rate) ? Non, dans CEC2014, l'archive a une taille fixe par rapport à initNP 
        // ou elle diminue ?
        // Dans le papier original LPSR : "The archive size |A| is also updated dynamically... |A_t| = round(A_init * (NP_t / NP_init))" ??
        // En fait, souvent on garde l'archive fixe ou on la réduit.
        // Simplification : on garde l'archive telle quelle, ou on la cap si elle dépasse trop la taille de pop ?
        // Dans L-SHADE standard, l'archive diminue aussi.
        int currentArchiveCap = (int) (2.6 * nextNP); // Garder la proportion
        if (archive.size() > currentArchiveCap) {
             while (archive.size() > currentArchiveCap) {
                 archive.remove(rng.nextInt(archive.size()));
             }
        }

        pop = reducedPop;
        fitness = reducedFitness;
    }

    private void updateMemory(ArrayList<Double> sF, ArrayList<Double> sCR, ArrayList<Double> sDelta) {
        if (!sF.isEmpty ())
        {
            double totalDelta = 0;
            for (double d : sDelta) totalDelta += d;

            // Lehmer mean pour F (WL)
            double sumF2 = 0, sumF = 0;
            for (int i = 0; i < sF.size (); i++)
            {
                double w = sDelta.get (i) / totalDelta;
                sumF2 += w * sF.get (i) * sF.get (i);
                sumF  += w * sF.get (i);
            }
            MF [histIndex] = (sumF > 0) ? sumF2 / sumF : 0.5;

            // Weighted mean pour CR (WA)
            double sumCR = 0;
            // Catch cas spécial où MCR = -1 (terminal value) si on voulait implémenter 'terminal',
            // mais ici on reste simple.
            for (int i = 0; i < sCR.size (); i++)
            {
                double w = sDelta.get (i) / totalDelta;
                sumCR += w * sCR.get (i);
            }
            MCR [histIndex] = sumCR; // Si < 0 ou > 1 géré au sampling

            histIndex = (histIndex + 1) % H;
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
