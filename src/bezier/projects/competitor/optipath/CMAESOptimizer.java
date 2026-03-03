package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * IPOP-CMA-ES : Covariance Matrix Adaptation Evolution Strategy
 * avec restarts à population croissante (Increasing POPulation).
 *
 * Référence : Hansen & Ostermeier (2001), Hansen (2016) "The CMA Evolution Strategy: A Tutorial".
 * Eigendecomposition basée sur les méthodes tred2/tql2 (domaine public JAMA).
 *
 * Encodage : double[] plat de taille d = 2 * nControlPoints.
 * Une génération par appel à step() : échantillonnage de λ offspring, évaluation, mise à jour.
 */
public class CMAESOptimizer implements Optimizer
{
    // ===== Références externes =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Paramètres CMA-ES (recalculés à chaque restart) =====
    private int lambda;       // taille de la population d'offspring
    private int mu;           // nb de parents sélectionnés
    private double [] weights;
    private double mueff;     // variance effective des poids

    private double csig, dsig;   // cumulation et amortissement pour σ
    private double cc;            // cumulation pour C
    private double c1, cmu;      // taux d'apprentissage pour C
    private double chiN;          // E[‖N(0,I)‖]

    // ===== État évolutif =====
    private double [] mean;       // centroïde de la distribution
    private double sigma;         // step-size global
    private double [][] C;        // matrice de covariance d×d
    private double [][] B;        // vecteurs propres de C
    private double [] diagD;      // racines carrées des valeurs propres
    private double [][] invsqrtC; // C^{-1/2}
    private double [] ps;         // chemin d'évolution pour σ
    private double [] pc;         // chemin d'évolution pour C

    private int generation;
    private int eigenCounter;

    // ===== Meilleur global =====
    private double bestFitness;
    private double [] bestX;

    // ===== Seeding & diversification =====
    private double startX, startY, endX, endY;
    private ArrayList<double []> cachedSeeds;

    // ===== IPOP restart =====
    private final int lambda0;
    private final double sigma0;
    private int restartCount;
    private int stagnationCounter;
    private int maxStagnation;
    private double prevBestGen;   // meilleur fitness de la génération précédente

    // ===== Constructeur =====
    public CMAESOptimizer (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();

        // Paramètres initiaux indépendants du restart
        this.lambda0 = 4 + (int) (3.0 * Math.log (d));
        this.sigma0 = (ub [0] - lb [0]) / 6.0;  // range/6 : bon compromis exploration/exploitation

        this.chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));
    }

    // ================================================================
    //  INITIALISATION
    // ================================================================
    @Override
    public void init ()
    {
        // Coordonnées start/end pour la génération de seeds
        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;

        // --- Phase de seeding diversifié ---
        // Génère ~80 trajectoires candidates (sinusoïdes, bords, aléatoires)
        // et démarre CMA-ES depuis la meilleure.
        ArrayList<double []> seeds = generateDiverseSeeds ();
        seeds.add (0, initMean.clone ()); // interpolation linéaire en premier

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

        // Cacher les top-25 seeds pour les restarts
        Integer [] indices = new Integer [evaluated.size ()];
        for (int i = 0; i < indices.length; i++) indices [i] = i;
        Arrays.sort (indices, (a, b) -> Double.compare (fitnesses.get (a), fitnesses.get (b)));

        cachedSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (25, indices.length); i++)
            cachedSeeds.add (evaluated.get (indices [i]).clone ());

        // --- Phase d'optimisation locale (1+1)-ES sur les top-5 seeds ---
        // Budget : 5 × 1000 évals ≈ 5000 évals (~50 ms), négligeable sur 60s.
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

        setupCMAES (lambda0, localBestX, sigma0);
    }

    // ================================================================
    //  GÉNÉRATION DE SEEDS DIVERSIFIÉS
    // ================================================================

    /**
     * Génère un ensemble varié de trajectoires candidates :
     *   - Sinusoïdes (fréquence × phase × amplitude) pour couvrir les chemins
     *     en S, arcs, zigzags, etc. Crucial pour les topologies labyrinthiques.
     *   - Chemins le long des bords (haut, bas, milieu).
     *   - Solutions purement aléatoires.
     * Coût : ~80 évaluations (< 10 ms), négligeable sur 60 s.
     */
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY     = bounds.getLb () [1];
        double ubY     = bounds.getUb () [1];
        double centerY = (lbY + ubY) / 2.0;
        double halfY   = (ubY - lbY) / 2.0;

        // 1. Chemins sinusoïdaux : diverses topologies
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

        // 2. "Block" patterns : groupes de CPs à y extrême (top/bottom)
        // Efficace pour les labyrinthes à murs avec gaps en haut / en bas.
        for (int nUp = 1; nUp <= nCP / 2; nUp++)
        {
            double [] pathA = new double [d]; // top-first
            double [] pathB = new double [d]; // bottom-first
            for (int i = 0; i < nCP; i++)
            {
                double t = (double) (i + 1) / (nCP + 1);
                int block = (i * 2) / nCP; // 0 for first half, 1 for second half...
                boolean up = ((i / nUp) % 2 == 0);
                pathA [2 * i]     = startX + t * (endX - startX);
                pathA [2 * i + 1] = up ? (ubY - 1) : (lbY + 1);
                pathB [2 * i]     = startX + t * (endX - startX);
                pathB [2 * i + 1] = up ? (lbY + 1) : (ubY - 1);
            }
            seeds.add (pathA);
            seeds.add (pathB);
        }

        // 3. Chemins le long des bords
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

        // 4. Solutions aléatoires
        for (int r = 0; r < 20; r++)
            seeds.add (problem.getRandomControlPoints1DArray ());

        // 5. Boundary-clustering seeds : CPs aux extrêmes en x (0 et max)
        // Exploite la paramétricité de Bézier : concentrer les CPs en x aux bornes
        // fait que la courbe "traverse rapidement" les zones d'obstacles en t.
        // Particulièrement efficace pour les labyrinthes multi-murs (prob4).
        double lbX  = bounds.getLb () [0];
        double ubX  = bounds.getUb () [0];
        double [] yLevels = {lbY, lbY + 2, lbY + 5, centerY, ubY - 5, ubY - 2, ubY};

        // 5a. Tous les CPs aux coins (x-boundary) avec différents y
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

        // 5b. Alternance x-boundary avec y=low
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

        // 5c. CPs tous à une même coordonnée x extrême, y varié  
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

        // 5d. Patterns mixtes aléatoires avec x aux bornes  
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
    //  OPTIMISATION LOCALE RAPIDE — (1+1)-ES avec règle du 1/5
    // ================================================================

    /**
     * Mini (1+1)-ES : perturbation gaussienne isotrope, avec adaptation du step-size
     * par la règle du 1/5 de succès. Budget limité en nombre d'évaluations.
     * @param x0 point de départ (modifié en place)
     * @param maxEvals nombre maximal d'évaluations
     * @return le meilleur point trouvé
     */
    private double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x = x0.clone ();
        bounds.clampInPlace (x);
        double fx = problem.evaluate (x);
        updateBest (x, fx);

        double step = sigma0 / 2.0; // pas initial modéré
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

            // Règle du 1/5 : adapter le step toutes les 20 itérations
            if (window >= 20)
            {
                double rate = (double) successes / window;
                if (rate > 0.2)
                    step *= 1.3;
                else
                    step *= 0.7;
                step = Math.max (step, 1e-10);
                step = Math.min (step, sigma0 * 2);
                successes = 0;
                window = 0;
            }
        }
        return x;
    }

    /**
     * (Re)configure tous les paramètres CMA-ES pour une taille de population donnée.
     */
    private void setupCMAES (int newLambda, double [] startMean, double startSigma)
    {
        this.lambda = newLambda;
        this.mu = lambda / 2;
        this.sigma = startSigma;
        this.mean = startMean.clone ();

        // --- Poids de recombinaison (log) ---
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

        // --- Taux d'apprentissage (formules Hansen) ---
        csig = (mueff + 2.0) / (d + mueff + 5.0);
        dsig = 1.0 + 2.0 * Math.max (0, Math.sqrt ((mueff - 1.0) / (d + 1.0)) - 1.0) + csig;
        cc   = (4.0 + mueff / d) / (d + 4.0 + 2.0 * mueff / d);
        c1   = 2.0 / ((d + 1.3) * (d + 1.3) + mueff);
        cmu  = Math.min (1.0 - c1, 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((d + 2.0) * (d + 2.0) + mueff));

        // --- État interne ---
        ps = new double [d];
        pc = new double [d];
        C = new double [d][d];
        B = new double [d][d];
        diagD = new double [d];
        invsqrtC = new double [d][d];

        for (int i = 0; i < d; i++)
        {
            C [i][i] = 1.0;
            B [i][i] = 1.0;
            diagD [i] = 1.0;
            invsqrtC [i][i] = 1.0;
        }

        generation = 0;
        eigenCounter = 0;
        stagnationCounter = 0;
        maxStagnation = 10 + (int) (30.0 * d / lambda);
        prevBestGen = Double.POSITIVE_INFINITY;
    }

    // ================================================================
    //  UNE GÉNÉRATION (appelée par loop())
    // ================================================================
    @Override
    public void step ()
    {
        // 1. Échantillonnage de λ offspring
        double [][] arx = new double [lambda][d];
        double [][] ary = new double [lambda][d]; // y_k = (x_k - mean_old) / σ
        double [] fitness = new double [lambda];

        for (int k = 0; k < lambda; k++)
        {
            // z ~ N(0, I)
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

            // y = B * D * z
            double [] Dz = new double [d];
            for (int i = 0; i < d; i++) Dz [i] = diagD [i] * z [i];

            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += B [i][j] * Dz [j];
                ary [k][i] = sum;
                arx [k][i] = mean [i] + sigma * sum;
            }

            bounds.clampInPlace (arx [k]);
            fitness [k] = problem.evaluate (arx [k]);
            updateBest (arx [k], fitness [k]);
        }

        // 2. Trier par fitness croissante
        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        // 3. Nouveau mean (recombinaison pondérée des μ meilleurs)
        double [] oldMean = mean.clone ();
        mean = new double [d];
        for (int j = 0; j < mu; j++)
        {
            int ii = idx [j];
            for (int i = 0; i < d; i++)
                mean [i] += weights [j] * arx [ii][i];
        }

        // Différence normalisée
        double [] meanDiffNorm = new double [d]; // (mean - oldMean) / σ
        for (int i = 0; i < d; i++)
            meanDiffNorm [i] = (mean [i] - oldMean [i]) / sigma;

        // 4. Mise à jour du chemin p_σ (CSA)
        double [] invsqrtCdiff = matVecMul (invsqrtC, meanDiffNorm);
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++)
            ps [i] = (1.0 - csig) * ps [i] + csigFac * invsqrtCdiff [i];

        double psNorm = vecNorm (ps);

        // 5. Indicateur h_σ et mise à jour du chemin p_c
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++)
            pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * meanDiffNorm [i];

        // 6. Mise à jour de la matrice de covariance C
        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j <= i; j++)
            {
                // rank-1 update
                double rank1 = c1 * pc [i] * pc [j];

                // rank-μ update
                double rankmu = 0;
                for (int k = 0; k < mu; k++)
                {
                    int ii = idx [k];
                    rankmu += weights [k] * ary [ii][i] * ary [ii][j];
                }
                rankmu *= cmu;

                C [i][j] = cOld * C [i][j] + rank1 + rankmu;
                C [j][i] = C [i][j]; // symétrie
            }
        }

        // 7. Mise à jour de σ
        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        // 8. Eigendecomposition (à chaque génération pour d ≤ 32)
        eigenCounter++;
        if (eigenCounter >= 1)
        {
            eigenDecomposition ();
            eigenCounter = 0;
        }

        generation++;

        // 9. Détection de stagnation et restart IPOP
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
    //  RESTART IPOP
    // ================================================================
    private boolean shouldRestart ()
    {
        // Stagnation trop longue
        if (stagnationCounter > maxStagnation) return true;

        // Condition number de C trop grande
        double maxD = diagD [0], minD = diagD [0];
        for (int i = 1; i < d; i++)
        {
            if (diagD [i] > maxD) maxD = diagD [i];
            if (diagD [i] < minD) minD = diagD [i];
        }
        if (minD > 0 && (maxD / minD) > 1e7) return true;

        // σ trop petit
        if (sigma * maxD < 1e-12) return true;

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
            // Stratégie A (40 %) : seed caché + optimisation locale rapide
            int idx = restartCount % cachedSeeds.size ();
            newMean = quickLocalOptimize (cachedSeeds.get (idx).clone (), 500);
        }
        else if (choice < 0.70 && bestX != null)
        {
            // Stratégie B (30 %) : perturbation autour du meilleur connu
            newMean = bestX.clone ();
            for (int i = 0; i < d; i++)
                newMean [i] += rng.nextGaussian () * bounds.getRange (i) * 0.15;
            bounds.clampInPlace (newMean);
            newSigma = sigma0 / 2.0;
        }
        else
        {
            // Stratégie C (30 %) : meilleur parmi 8 seeds (mix aléatoire + boundary-clustering)
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
            // Boundary-clustering seeds
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

        setupCMAES (newLambda, newMean, newSigma);
    }

    // ================================================================
    //  EIGENDECOMPOSITION DE C  (tred2 + tql2, source JAMA domaine public)
    // ================================================================
    private void eigenDecomposition ()
    {
        // Copier C dans V pour la décomposition
        double [][] V = new double [d][d];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < d; j++)
                V [i][j] = C [i][j];

        double [] dd = new double [d];
        double [] ee = new double [d];

        tred2 (V, dd, ee);
        tql2  (V, dd, ee);

        // Mettre à jour B, diagD, invsqrtC
        for (int i = 0; i < d; i++)
            diagD [i] = Math.sqrt (Math.max (dd [i], 1e-20));

        B = V;

        // invsqrtC = B · diag(1/diagD) · Bᵀ
        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j <= i; j++)
            {
                double sum = 0;
                for (int k = 0; k < d; k++)
                    sum += B [i][k] * (1.0 / diagD [k]) * B [j][k];
                invsqrtC [i][j] = sum;
                invsqrtC [j][i] = sum;
            }
        }
    }

    /**
     * Tridiagonalisation de Householder (JAMA, domaine public).
     * Transforme V en place ; remplit d[] (diagonal) et e[] (off-diagonal).
     */
    private void tred2 (double [][] V, double [] d, double [] e)
    {
        int n = this.d;
        for (int j = 0; j < n; j++) d [j] = V [n - 1][j];

        for (int i = n - 1; i > 0; i--)
        {
            double scale = 0, h = 0;
            for (int k = 0; k < i; k++) scale += Math.abs (d [k]);

            if (scale == 0.0)
            {
                e [i] = d [i - 1];
                for (int j = 0; j < i; j++) { d [j] = V [i - 1][j]; V [i][j] = 0; V [j][i] = 0; }
            }
            else
            {
                for (int k = 0; k < i; k++) { d [k] /= scale; h += d [k] * d [k]; }
                double f = d [i - 1];
                double g = Math.sqrt (h);
                if (f > 0) g = -g;
                e [i] = scale * g;
                h -= f * g;
                d [i - 1] = f - g;
                for (int j = 0; j < i; j++) e [j] = 0;

                for (int j = 0; j < i; j++)
                {
                    f = d [j]; V [j][i] = f;
                    g = e [j] + V [j][j] * f;
                    for (int k = j + 1; k <= i - 1; k++) { g += V [k][j] * d [k]; e [k] += V [k][j] * f; }
                    e [j] = g;
                }
                f = 0;
                for (int j = 0; j < i; j++) { e [j] /= h; f += e [j] * d [j]; }
                double hh = f / (h + h);
                for (int j = 0; j < i; j++) e [j] -= hh * d [j];
                for (int j = 0; j < i; j++)
                {
                    f = d [j]; g = e [j];
                    for (int k = j; k <= i - 1; k++) V [k][j] -= f * e [k] + g * d [k];
                    d [j] = V [i - 1][j]; V [i][j] = 0;
                }
            }
            d [i] = h;
        }

        for (int i = 0; i < n - 1; i++)
        {
            V [n - 1][i] = V [i][i]; V [i][i] = 1;
            double h = d [i + 1];
            if (h != 0)
            {
                for (int k = 0; k <= i; k++) d [k] = V [k][i + 1] / h;
                for (int j = 0; j <= i; j++)
                {
                    double g = 0;
                    for (int k = 0; k <= i; k++) g += V [k][i + 1] * V [k][j];
                    for (int k = 0; k <= i; k++) V [k][j] -= g * d [k];
                }
            }
            for (int k = 0; k <= i; k++) V [k][i + 1] = 0;
        }
        for (int j = 0; j < n; j++) { d [j] = V [n - 1][j]; V [n - 1][j] = 0; }
        V [n - 1][n - 1] = 1;
        e [0] = 0;
    }

    /**
     * Algorithme QL avec shifts implicites (JAMA, domaine public).
     * Opère sur la matrice tridiagonale (d[], e[]) et accumule les transformations dans V.
     */
    private void tql2 (double [][] V, double [] d, double [] e)
    {
        int n = this.d;
        for (int i = 1; i < n; i++) e [i - 1] = e [i];
        e [n - 1] = 0;

        double f = 0, tst1 = 0;
        double eps = Math.pow (2.0, -52.0);

        for (int l = 0; l < n; l++)
        {
            tst1 = Math.max (tst1, Math.abs (d [l]) + Math.abs (e [l]));
            int m = l;
            while (m < n) { if (Math.abs (e [m]) <= eps * tst1) break; m++; }

            if (m > l)
            {
                int iter = 0;
                do
                {
                    iter++;
                    double g = d [l];
                    double p = (d [l + 1] - g) / (2.0 * e [l]);
                    double r = Math.hypot (p, 1.0);
                    if (p < 0) r = -r;
                    d [l] = e [l] / (p + r);
                    d [l + 1] = e [l] * (p + r);
                    double dl1 = d [l + 1];
                    double h = g - d [l];
                    for (int i = l + 2; i < n; i++) d [i] -= h;
                    f += h;

                    p = d [m]; double c = 1, c2 = c, c3 = c;
                    double el1 = e [l + 1]; double s = 0, s2 = 0;
                    for (int i = m - 1; i >= l; i--)
                    {
                        c3 = c2; c2 = c; s2 = s;
                        g = c * e [i]; h = c * p;
                        r = Math.hypot (p, e [i]);
                        e [i + 1] = s * r; s = e [i] / r; c = p / r;
                        p = c * d [i] - s * g;
                        d [i + 1] = h + s * (c * g + s * d [i]);
                        for (int k = 0; k < n; k++)
                        {
                            h = V [k][i + 1];
                            V [k][i + 1] = s * V [k][i] + c * h;
                            V [k][i]     = c * V [k][i] - s * h;
                        }
                    }
                    p = -s * s2 * c3 * el1 * e [l] / dl1;
                    e [l] = s * p; d [l] = c * p;
                }
                while (Math.abs (e [l]) > eps * tst1);
            }
            d [l] = d [l] + f; e [l] = 0;
        }

        // Tri des valeurs propres par ordre croissant
        for (int i = 0; i < n - 1; i++)
        {
            int k = i; double p = d [i];
            for (int j = i + 1; j < n; j++) if (d [j] < p) { k = j; p = d [j]; }
            if (k != i)
            {
                d [k] = d [i]; d [i] = p;
                for (int j = 0; j < n; j++) { p = V [j][i]; V [j][i] = V [j][k]; V [j][k] = p; }
            }
        }
    }

    // ===== Utilitaires vectoriels / matriciels =====

    private double [] matVecMul (double [][] M, double [] v)
    {
        double [] r = new double [d];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < d; j++)
                r [i] += M [i][j] * v [j];
        return r;
    }

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

    /** Retourne le meilleur vecteur trouvé (ou null si aucun). */
    @Override
    public double [] getBestX () { return bestX; }
    /** Retourne le meilleur coût trouvé. */
    public double getBestFitness () { return bestFitness; }
}
