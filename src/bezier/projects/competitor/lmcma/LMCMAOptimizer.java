package bezier.projects.competitor.lmcma;

import bezier.evaluation.Problem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * LM-CMA-ES : Limited Memory CMA-ES avec IPOP restarts.
 *
 * Au lieu de stocker et mettre à jour la matrice de covariance d×d (O(d²)),
 * on maintient un buffer circulaire de m vecteurs de direction et on approxime
 * le produit C·v en O(m·d).
 *
 * m = 4 + floor(3 * log(d))
 *
 * Référence : Loshchilov 2014 "A Computationally Efficient Limited Memory CMA-ES"
 */
public class LMCMAOptimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Paramètres CMA-ES =====
    private int lambda;
    private int mu;
    private double [] weights;
    private double mueff;

    private double csig, dsig;
    private double cc;
    private double chiN;

    // ===== État évolutif =====
    private double [] mean;
    private double sigma;
    private double [] ps;     // chemin d'évolution pour σ
    private double [] pc;     // chemin d'évolution pour C (utilisé pour le buffer LM)

    private int generation;

    // ===== LM-CMA : buffer circulaire de vecteurs de direction =====
    private final int m;             // nombre de vecteurs stockés
    private double [][] dirBuffer;   // buffer circulaire [m][d] : vecteurs de direction normalisés
    private double [] dirNormSq;     // ||s_k||² pour chaque vecteur dans le buffer
    private int bufferHead;          // index d'écriture dans le buffer
    private int bufferCount;         // nombre de vecteurs effectivement stockés
    private double cLM;              // taux d'apprentissage LM (analog de cmu)

    // ===== Meilleur global =====
    private double bestFitness;
    private double [] bestX;

    // ===== Seeding =====
    private double startX, startY, endX, endY;
    private ArrayList<double []> cachedSeeds;

    // ===== IPOP restart =====
    private final int lambda0;
    private final double sigma0;
    private int restartCount;
    private int stagnationCounter;
    private int maxStagnation;
    private double prevBestGen;

    public LMCMAOptimizer (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();

        this.lambda0 = 4 + (int) (3.0 * Math.log (d));
        this.sigma0 = (ub [0] - lb [0]) / 6.0;
        this.chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));

        // Nombre de vecteurs en mémoire limitée
        this.m = 4 + (int) Math.floor (3.0 * Math.log (d));
    }

    // ================================================================
    //  INITIALISATION
    // ================================================================
    public void init ()
    {
        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;

        // --- Phase de seeding diversifié ---
        ArrayList<double []> seeds = generateDiverseSeeds ();
        seeds.add (0, initMean.clone ());

        double [] bestSeed = null;
        double bestSeedF = Double.POSITIVE_INFINITY;
        ArrayList<double []> evaluated = new ArrayList<> ();
        ArrayList<Double> fitnesses = new ArrayList<> ();

        for (double [] seed : seeds)
        {
            bounds.clampInPlace (seed);
            double f = problem.evaluate (seed);
            updateBest (seed, f);
            evaluated.add (seed);
            fitnesses.add (f);
            if (f < bestSeedF)
            {
                bestSeedF = f;
                bestSeed = seed.clone ();
            }
        }

        // Top-25 seeds
        Integer [] indices = new Integer [evaluated.size ()];
        for (int i = 0; i < indices.length; i++) indices [i] = i;
        Arrays.sort (indices, (a, b) -> Double.compare (fitnesses.get (a), fitnesses.get (b)));

        cachedSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (25, indices.length); i++)
            cachedSeeds.add (evaluated.get (indices [i]).clone ());

        // (1+1)-ES local sur top-5
        int nLocalSeeds = Math.min (5, cachedSeeds.size ());
        double [] localBestX = bestSeed.clone ();
        double localBestF = bestSeedF;
        for (int s = 0; s < nLocalSeeds; s++)
        {
            double [] result = quickLocalOptimize (cachedSeeds.get (s).clone (), 1000);
            double fResult = problem.evaluate (result);
            updateBest (result, fResult);
            if (fResult < localBestF)
            {
                localBestF = fResult;
                localBestX = result.clone ();
            }
        }

        setupLMCMAES (lambda0, localBestX, sigma0);
    }

    // ================================================================
    //  SETUP LM-CMA-ES
    // ================================================================
    private void setupLMCMAES (int newLambda, double [] startMean, double startSigma)
    {
        this.lambda = newLambda;
        this.mu = lambda / 2;
        this.sigma = startSigma;
        this.mean = startMean.clone ();

        // Poids de recombinaison
        weights = new double [mu];
        double sumW = 0;
        for (int i = 0; i < mu; i++)
        {
            weights [i] = Math.log (mu + 0.5) - Math.log (i + 1.0);
            sumW += weights [i];
        }
        for (int i = 0; i < mu; i++) weights [i] /= sumW;

        double sumW2 = 0;
        for (int i = 0; i < mu; i++) sumW2 += weights [i] * weights [i];
        mueff = 1.0 / sumW2;

        // Taux d'apprentissage
        csig = (mueff + 2.0) / (d + mueff + 5.0);
        dsig = 1.0 + 2.0 * Math.max (0, Math.sqrt ((mueff - 1.0) / (d + 1.0)) - 1.0) + csig;
        cc   = (4.0 + mueff / d) / (d + 4.0 + 2.0 * mueff / d);

        // Taux LM pour la mise à jour du buffer (analog de c_mu dans CMA-ES classique)
        cLM = Math.min (1.0, 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((d + 2.0) * (d + 2.0) + mueff));

        // État interne
        ps = new double [d];
        pc = new double [d];

        // Buffer circulaire LM
        dirBuffer = new double [m][d];
        dirNormSq = new double [m];
        bufferHead = 0;
        bufferCount = 0;

        generation = 0;
        stagnationCounter = 0;
        maxStagnation = 10 + (int) (30.0 * d / lambda);
        prevBestGen = Double.POSITIVE_INFINITY;
    }

    // ================================================================
    //  UNE GÉNÉRATION LM-CMA-ES
    // ================================================================
    public void step ()
    {
        // 1. Échantillonnage de λ offspring
        double [][] arx = new double [lambda][d];
        double [][] arz = new double [lambda][d]; // vecteurs z ~ N(0,I)
        double [] fitness = new double [lambda];

        for (int k = 0; k < lambda; k++)
        {
            // z ~ N(0, I)
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();
            arz [k] = z;

            // Appliquer l'approximation LM : y = sqrt(C) · z ≈ z + corrections directionnelles
            double [] y = applyLMTransform (z);

            for (int i = 0; i < d; i++)
                arx [k][i] = mean [i] + sigma * y [i];

            bounds.clampInPlace (arx [k]);
            fitness [k] = problem.evaluate (arx [k]);
            updateBest (arx [k], fitness [k]);
        }

        // 2. Trier par fitness croissante
        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        // 3. Nouveau mean
        double [] oldMean = mean.clone ();
        mean = new double [d];
        for (int j = 0; j < mu; j++)
        {
            int ii = idx [j];
            for (int i = 0; i < d; i++)
                mean [i] += weights [j] * arx [ii][i];
        }

        // Différence normalisée
        double [] meanDiffNorm = new double [d];
        for (int i = 0; i < d; i++)
            meanDiffNorm [i] = (mean [i] - oldMean [i]) / sigma;

        // 4. Mise à jour du chemin p_σ (CSA)
        // Pour CSA, on a besoin de C^{-1/2} · meanDiffNorm
        // En LM, on approxime C^{-1/2} · v par l'inverse de la transformation LM
        double [] invsqrtCv = applyInvLMTransform (meanDiffNorm);
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++)
            ps [i] = (1.0 - csig) * ps [i] + csigFac * invsqrtCv [i];

        double psNorm = vecNorm (ps);

        // 5. Mise à jour du chemin p_c
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++)
            pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * meanDiffNorm [i];

        // 6. Mise à jour du buffer LM : stocker pc comme nouveau vecteur de direction
        double pcNorm2 = 0;
        for (int i = 0; i < d; i++) pcNorm2 += pc [i] * pc [i];

        if (pcNorm2 > 1e-20)
        {
            for (int i = 0; i < d; i++)
                dirBuffer [bufferHead][i] = pc [i];
            dirNormSq [bufferHead] = pcNorm2;
            bufferHead = (bufferHead + 1) % m;
            if (bufferCount < m) bufferCount++;
        }

        // 7. Mise à jour de σ
        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        generation++;

        // 8. Détection de stagnation et restart IPOP
        double genBest = fitness [idx [0]];
        if (genBest < prevBestGen - 1e-12)
        {
            stagnationCounter = 0;
            prevBestGen = genBest;
        }
        else
        {
            stagnationCounter++;
        }

        if (shouldRestart ())
            restart ();
    }

    // ================================================================
    //  TRANSFORMATION LM : approximation de sqrt(C) · z
    // ================================================================
    /**
     * Approxime C·v ≈ v + Σᵢ αᵢ·(sᵢᵀv)·sᵢ
     * où αᵢ = (c_s(1-c_s))^(m-i) est un facteur d'amortissement exponentiel.
     *
     * Pour sqrt(C), on applique la moitié de la correction :
     * sqrt(C)·z ≈ z + Σᵢ (βᵢ)·(sᵢᵀz)·sᵢ / ||sᵢ||²
     * avec βᵢ = sqrt(1 + αᵢ·||sᵢ||²) - 1
     */
    private double [] applyLMTransform (double [] z)
    {
        double [] result = z.clone ();

        for (int k = 0; k < bufferCount; k++)
        {
            // Index réel dans le buffer circulaire (du plus ancien au plus récent)
            int bufIdx = (bufferHead - bufferCount + k + m) % m;

            // Facteur d'amortissement exponentiel : les vecteurs récents ont plus de poids
            double age = bufferCount - 1 - k; // 0 pour le plus récent
            double alpha = Math.pow (cLM * (1.0 - cLM), age) * (d / (cLM * mueff));

            // Projection de z sur le vecteur de direction
            double dot = 0;
            for (int i = 0; i < d; i++)
                dot += dirBuffer [bufIdx][i] * result [i];

            // Coefficient de correction pour sqrt
            double normSq = dirNormSq [bufIdx];
            if (normSq < 1e-30) continue;

            double sqrtFactor = Math.sqrt (Math.max (1.0 + alpha, 1.0)) - 1.0;
            double correction = sqrtFactor * dot / normSq;

            for (int i = 0; i < d; i++)
                result [i] += correction * dirBuffer [bufIdx][i];
        }

        return result;
    }

    /**
     * Approxime C^{-1/2} · v (pour CSA).
     * Inverse de la transformation : utilise -β au lieu de sqrt(1+α)-1.
     */
    private double [] applyInvLMTransform (double [] v)
    {
        double [] result = v.clone ();

        // Appliquer dans l'ordre inverse (du plus récent au plus ancien)
        for (int k = bufferCount - 1; k >= 0; k--)
        {
            int bufIdx = (bufferHead - bufferCount + k + m) % m;

            double age = bufferCount - 1 - k;
            double alpha = Math.pow (cLM * (1.0 - cLM), age) * (d / (cLM * mueff));

            double dot = 0;
            for (int i = 0; i < d; i++)
                dot += dirBuffer [bufIdx][i] * result [i];

            double normSq = dirNormSq [bufIdx];
            if (normSq < 1e-30) continue;

            // Inverse : 1/sqrt(1+α) - 1
            double invFactor = 1.0 / Math.sqrt (Math.max (1.0 + alpha, 1.0)) - 1.0;
            double correction = invFactor * dot / normSq;

            for (int i = 0; i < d; i++)
                result [i] += correction * dirBuffer [bufIdx][i];
        }

        return result;
    }

    // ================================================================
    //  RESTART IPOP
    // ================================================================
    private boolean shouldRestart ()
    {
        if (stagnationCounter > maxStagnation) return true;

        // Sigma trop petit
        if (sigma < 1e-12) return true;

        return false;
    }

    private void restart ()
    {
        restartCount++;
        int newLambda = lambda0 * (1 << Math.min (restartCount, 8));
        newLambda = Math.min (newLambda, 512);

        double [] newMean;
        double newSigma = sigma0;
        double choice = rng.nextDouble ();

        if (choice < 0.40 && cachedSeeds != null && !cachedSeeds.isEmpty ())
        {
            int idx = restartCount % cachedSeeds.size ();
            newMean = quickLocalOptimize (cachedSeeds.get (idx).clone (), 500);
        }
        else if (choice < 0.70 && bestX != null)
        {
            newMean = bestX.clone ();
            for (int i = 0; i < d; i++)
                newMean [i] += rng.nextGaussian () * bounds.getRange (i) * 0.15;
            bounds.clampInPlace (newMean);
            newSigma = sigma0 / 2.0;
        }
        else
        {
            double [] bSeed = problem.getRandomControlPoints1DArray ();
            bounds.clampInPlace (bSeed);
            double bF = problem.evaluate (bSeed);
            updateBest (bSeed, bF);
            for (int r = 0; r < 3; r++)
            {
                double [] s = problem.getRandomControlPoints1DArray ();
                bounds.clampInPlace (s);
                double f = problem.evaluate (s);
                updateBest (s, f);
                if (f < bF) { bF = f; bSeed = s; }
            }
            int nCP = d / 2;
            for (int r = 0; r < 4; r++)
            {
                double [] s = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    s [2 * i]     = rng.nextBoolean () ? bounds.getLb () [0] : bounds.getUb () [0];
                    s [2 * i + 1] = bounds.getLb () [1] + rng.nextDouble () * bounds.getRange (1);
                }
                bounds.clampInPlace (s);
                double f = problem.evaluate (s);
                updateBest (s, f);
                if (f < bF) { bF = f; bSeed = s; }
            }
            newMean = bSeed;
        }

        setupLMCMAES (newLambda, newMean, newSigma);
    }

    // ================================================================
    //  SEEDS (identique au CMAESOptimizer)
    // ================================================================
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY     = bounds.getLb () [1];
        double ubY     = bounds.getUb () [1];
        double centerY = (lbY + ubY) / 2.0;
        double halfY   = (ubY - lbY) / 2.0;

        double [] periods    = {0.5, 1.0, 1.5, 2.0, 2.5};
        double [] phases     = {0, Math.PI / 4, Math.PI / 2, 3 * Math.PI / 4,
                                Math.PI, 5 * Math.PI / 4, 3 * Math.PI / 2, 7 * Math.PI / 4};
        double [] amplitudes = {0.35, 0.65, 0.85, 0.95};

        for (double period : periods)
            for (double phase : phases)
                for (double amp : amplitudes)
                {
                    double [] path = new double [d];
                    for (int i = 0; i < nCP; i++)
                    {
                        double t = (double) (i + 1) / (nCP + 1);
                        path [2 * i]     = startX + t * (endX - startX);
                        path [2 * i + 1] = centerY + amp * halfY
                                * Math.sin (2 * Math.PI * period * t + phase);
                    }
                    seeds.add (path);
                }

        for (int nUp = 1; nUp <= nCP / 2; nUp++)
        {
            double [] pathA = new double [d];
            double [] pathB = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double) (i + 1) / (nCP + 1);
                boolean up = ((i / nUp) % 2 == 0);
                pathA [2 * i]     = startX + t * (endX - startX);
                pathA [2 * i + 1] = up ? (ubY - 1) : (lbY + 1);
                pathB [2 * i]     = startX + t * (endX - startX);
                pathB [2 * i + 1] = up ? (lbY + 1) : (ubY - 1);
            }
            seeds.add (pathA);
            seeds.add (pathB);
        }

        for (double yy : new double [] {lbY + 1.5, ubY - 1.5, centerY})
        {
            double [] path = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double) (i + 1) / (nCP + 1);
                path [2 * i]     = startX + t * (endX - startX);
                path [2 * i + 1] = yy;
            }
            seeds.add (path);
        }

        for (int r = 0; r < 20; r++)
            seeds.add (problem.getRandomControlPoints1DArray ());

        double lbX = bounds.getLb () [0];
        double ubX = bounds.getUb () [0];
        double [] yLevels = {lbY, lbY + 2, lbY + 5, centerY, ubY - 5, ubY - 2, ubY};

        for (double yVal : yLevels)
        {
            double [] path = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                path [2 * i]     = (i % 2 == 0) ? ubX : lbX;
                path [2 * i + 1] = yVal;
            }
            seeds.add (path);
        }

        for (double y1 : new double [] {lbY, lbY + 1, lbY + 3})
        {
            for (double y2 : new double [] {lbY, lbY + 2, lbY + 5, centerY})
            {
                double [] path = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    path [2 * i]     = (i % 2 == 0) ? ubX : lbX;
                    path [2 * i + 1] = (i < nCP / 2) ? y1 : y2;
                }
                seeds.add (path);
            }
        }

        for (double xVal : new double [] {lbX, ubX})
        {
            for (double yVal : yLevels)
            {
                double [] path = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    path [2 * i]     = xVal;
                    path [2 * i + 1] = yVal;
                }
                seeds.add (path);
            }
        }

        for (int r = 0; r < 30; r++)
        {
            double [] path = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                path [2 * i]     = rng.nextBoolean () ? lbX : ubX;
                path [2 * i + 1] = lbY + rng.nextDouble () * (ubY - lbY);
            }
            seeds.add (path);
        }

        return seeds;
    }

    // ================================================================
    //  (1+1)-ES LOCAL
    // ================================================================
    private double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x = x0.clone ();
        bounds.clampInPlace (x);
        double fx = problem.evaluate (x);
        updateBest (x, fx);

        double step = sigma0 / 2.0;
        int successes = 0;
        int window = 0;

        for (int e = 0; e < maxEvals; e++)
        {
            double [] xNew = new double [d];
            for (int i = 0; i < d; i++)
                xNew [i] = x [i] + rng.nextGaussian () * step;
            bounds.clampInPlace (xNew);

            double fNew = problem.evaluate (xNew);
            updateBest (xNew, fNew);

            if (fNew < fx)
            {
                x = xNew;
                fx = fNew;
                successes++;
            }
            window++;

            if (window >= 20)
            {
                double rate = (double) successes / window;
                if (rate > 0.2) step *= 1.3;
                else step *= 0.7;
                step = Math.max (step, 1e-10);
                step = Math.min (step, sigma0 * 2);
                successes = 0;
                window = 0;
            }
        }
        return x;
    }

    // ===== Utilitaires =====
    private double vecNorm (double [] v)
    {
        double s = 0;
        for (double vi : v) s += vi * vi;
        return Math.sqrt (s);
    }

    private void updateBest (double [] x, double f)
    {
        if (f < bestFitness)
        {
            bestFitness = f;
            bestX = x.clone ();
        }
    }

    public double [] getBestX () { return bestX; }
    public double getBestFitness () { return bestFitness; }
}
