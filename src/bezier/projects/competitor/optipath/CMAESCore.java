package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * CMA-ES composable : le coeur de l'algorithme avec injection de stratégies.
 *
 * Remplace les ~9 fichiers dupliqués (CMAESBipop, CMAESActive, CMAESSurrogate, etc.)
 * par une seule implémentation paramétrable.
 *
 * Points d'injection :
 *   - RestartStrategy   : IPOP, BIPOP, GridMapElites, SHADE-sigma, etc.
 *   - EvalWrapper        : Direct, Surrogate pre-screening, etc.
 *   - CovarianceUpdate   : Standard, Active (poids négatifs), etc.
 *   - ConstraintHandler   : Tri standard, Stochastic Ranking, etc.
 *   - SamplingStrategy    : Standard, Mirror sampling, etc.
 *
 * Référence : Hansen & Ostermeier (2001), Hansen (2016) "The CMA Evolution Strategy: A Tutorial".
 */
public class CMAESCore implements Optimizer
{
    // ===== Références externes =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;
    private final AlgorithmParameters params;

    // ===== Stratégies injectées =====
    private final RestartStrategy restartStrategy;
    private final EvalWrapper evalWrapper;
    private final CovarianceUpdate covUpdate;
    private final ConstraintHandler constraintHandler;
    private final SamplingStrategy sampling;

    // ===== Paramètres CMA-ES (recalculés à chaque restart) =====
    private int lambda;
    private int mu;
    private double [] weights;
    private double mueff;

    private double csig, dsig;
    private double cc;
    private double c1, cmu;
    private double chiN;

    // ===== État évolutif =====
    private double [] mean;
    private double sigma;
    private double [][] C;
    private double [][] B;
    private double [] diagD;
    private double [] diagC;   // diagonale seule (mode séparable)
    private double [][] invsqrtC;
    private double [] ps;
    private double [] pc;

    private int generation;
    private int totalEvaluations;
    private int eigenCounter;

    // ===== Meilleur global =====
    private double bestFitness;
    private double [] bestX;

    // ===== Seeding & diversification =====
    private double startX, startY, endX, endY;
    private ArrayList<double []> cachedSeeds;

    // ===== IPOP / restart =====
    private final int lambda0;
    private double sigma0;
    private int restartCount;
    private int stagnationCounter;
    private int maxStagnation;
    private double prevBestGen;

    // ===== Buffers pré-alloués (réduit GC pressure) =====
    private double [] meanBuf;        // d
    private double [] diffBuf;        // d
    private double [] tmpVec;         // d
    private Integer [] idxBuf;        // lambda
    private double [][] eigenVBuf;    // d × d
    private double [] eigenDdBuf;     // d
    private double [] eigenEeBuf;     // d

    // ===== Constructeur =====
    public CMAESCore (Problem problem, int d, double [] lb, double [] ub,
                      double [] initMean,
                      RestartStrategy restartStrategy,
                      EvalWrapper evalWrapper,
                      CovarianceUpdate covUpdate,
                      ConstraintHandler constraintHandler,
                      SamplingStrategy sampling,
                      AlgorithmParameters params)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();
        this.params = params != null ? params : new AlgorithmParameters();

        this.restartStrategy = restartStrategy;
        this.evalWrapper = evalWrapper;
        this.covUpdate = covUpdate;
        this.constraintHandler = constraintHandler;
        this.sampling = sampling;

        this.lambda0 = 4 + (int) (3.0 * Math.log (d));
        double originalWidth = (ub [0] - lb [0]) - 2.0 * this.params.getMargin();
        this.sigma0 = originalWidth / 6.0;
        this.chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));
    }

    // ================================================================
    //  INITIALISATION
    // ================================================================
    @Override
    public void init ()
    {
        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;
        totalEvaluations = 0;

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
            double f = evaluateCandidate (seed);
            evaluated.add (seed);
            fitnesses.add (f);
            if (f < bestSeedF)
            {
                bestSeedF = f;
                bestSeed = seed.clone ();
            }
        }

        // Cache top-25 seeds pour les restarts
        Integer [] indices = new Integer [evaluated.size ()];
        for (int i = 0; i < indices.length; i++) indices [i] = i;
        Arrays.sort (indices, (a, b) -> Double.compare (fitnesses.get (a), fitnesses.get (b)));

        cachedSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (25, indices.length); i++)
            cachedSeeds.add (evaluated.get (indices [i]).clone ());

        // --- Optimisation locale (1+1)-ES sur top-5 seeds ---
        int nLocalSeeds = Math.min (5, cachedSeeds.size ());
        double [] localBestX = bestSeed.clone ();
        double localBestF = bestSeedF;
        for (int s = 0; s < nLocalSeeds; s++)
        {
            double [] result = quickLocalOptimize (cachedSeeds.get (s).clone (), 1000);
            double fResult = evaluateCandidate (result);
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
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY     = bounds.getLb () [1];
        double ubY     = bounds.getUb () [1];
        double centerY = (lbY + ubY) / 2.0;
        double halfY   = (ubY - lbY) / 2.0;

        // 1. Sinusoïdes (uniquement si ASTAR_PATH ou CENTER_LINE)
        if (params.getInitStrategy() == AlgorithmParameters.InitStrategy.ASTAR_PATH || params.getInitStrategy() == AlgorithmParameters.InitStrategy.CENTER_LINE) {
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
        }

        // 2. Block patterns
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

        // 3. Chemins bords
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

        // 4. Aléatoires
        int nRandom = (params.getInitStrategy() == AlgorithmParameters.InitStrategy.RANDOM) ? 50 : 20;
        for (int r = 0; r < nRandom; r++) {
            double[] randSeed = new double[d];
            for (int i = 0; i < d; i++) {
                randSeed[i] = bounds.getLb()[i] + Math.random() * (bounds.getUb()[i] - bounds.getLb()[i]);
            }
            seeds.add (randSeed);
        }

        // 5. Boundary-clustering
        double lbX  = bounds.getLb () [0];
        double ubX  = bounds.getUb () [0];
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

        for (double xVal : new double [] {lbX, ubX})
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
    //  OPTIMISATION LOCALE RAPIDE — (1+1)-ES règle du 1/5
    // ================================================================
    double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x = x0.clone ();
        bounds.clampInPlace (x);
        double fx = evaluateCandidate (x);

        double step = sigma0 / 2.0;
        int successes = 0;
        int window = 0;

        for (int e = 0; e < maxEvals; e++)
        {
            double [] xNew = new double [d];
            for (int i = 0; i < d; i++)
                xNew [i] = x [i] + rng.nextGaussian () * step;
            bounds.clampInPlace (xNew);

            double fNew = evaluateCandidate (xNew);

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
                else            step *= 0.7;
                step = Math.max (step, 1e-10);
                step = Math.min (step, sigma0 * 2);
                successes = 0;
                window = 0;
            }
        }
        return x;
    }

    // ================================================================
    //  CONFIGURATION CMA-ES
    // ================================================================
    private void setupCMAES (int newLambda, double [] startMean, double startSigma)
    {
        this.lambda = newLambda;
        this.mu = lambda / 2;
        this.sigma = startSigma;
        this.mean = startMean.clone ();

        // Poids de recombinaison (log)
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
        c1   = 2.0 / ((d + 1.3) * (d + 1.3) + mueff);
        cmu  = Math.min (1.0 - c1, 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((d + 2.0) * (d + 2.0) + mueff));

        // État interne
        ps = new double [d];
        pc = new double [d];
        diagD = new double [d];

        // Buffers pré-alloués (réutilisés à chaque step)
        meanBuf = new double [d];
        diffBuf = new double [d];
        tmpVec = new double [d];
        idxBuf = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idxBuf [i] = i;
        eigenVBuf = new double [d][d];
        eigenDdBuf = new double [d];
        eigenEeBuf = new double [d];

        covUpdate.prepareRestart ();

        boolean sepMode = (covUpdate.getMode () == CovarianceUpdate.Mode.SEPARABLE);

        if (sepMode)
        {
            // Sep mode : pas besoin de C/B/invsqrtC, juste diagC/diagD
            diagC = new double [d];
            Arrays.fill (diagC, 1.0);
            Arrays.fill (diagD, 1.0);
            C = null; B = null; invsqrtC = null;
            covUpdate.onRestartSep (diagC);
        }
        else
        {
            diagC = null;
            C = new double [d][d];
            B = new double [d][d];
            invsqrtC = new double [d][d];

            for (int i = 0; i < d; i++)
            {
                C [i][i] = 1.0;
                B [i][i] = 1.0;
                diagD [i] = 1.0;
                invsqrtC [i][i] = 1.0;
            }

            // Callback de restart pour la covariance
            covUpdate.onRestart (C);
        }

        generation = 0;
        eigenCounter = 0;
        stagnationCounter = 0;
        maxStagnation = 10 + (int) (30.0 * d / lambda);
        prevBestGen = Double.POSITIVE_INFINITY;
    }

    // ================================================================
    //  UNE GÉNÉRATION
    // ================================================================
    @Override
    public void step ()
    {
        boolean sepMode = (covUpdate.getMode () == CovarianceUpdate.Mode.SEPARABLE);

        if (sepMode)
            stepSep ();
        else
        {
            // Vérifier si on vient de passer de Sep → Full (transition)
            if (C == null)
                transitionToFull ();
            stepFull ();
        }
    }

    /**
     * Transition Sep → Full : construit C à partir de la diagonale apprise.
     */
    private void transitionToFull ()
    {
        C = new double [d][d];
        B = new double [d][d];
        invsqrtC = new double [d][d];

        if (covUpdate instanceof SeparableWarmupCovariance)
        {
            ((SeparableWarmupCovariance) covUpdate).buildTransitionC (
                    C, diagC, diagD, invsqrtC, B);
        }
        else
        {
            // Fallback : identité
            for (int i = 0; i < d; i++)
            {
                C [i][i] = 1.0;
                B [i][i] = 1.0;
                diagD [i] = 1.0;
                invsqrtC [i][i] = 1.0;
            }
        }

        // Reset partiel des chemins d'évolution
        for (int i = 0; i < d; i++) { ps [i] *= 0.5; pc [i] *= 0.5; }

        diagC = null;
        eigenDecomposition ();
    }

    /**
     * Génération en mode séparable : O(d) — pas de rotation B, pas d'eigendécomposition.
     */
    private void stepSep ()
    {
        double [][] arx = new double [lambda][d];
        double [] fitness = new double [lambda];

        // Échantillonnage diagonal : x = mean + sigma * diagD * z
        for (int k = 0; k < lambda; k++)
        {
            for (int i = 0; i < d; i++)
                arx [k][i] = mean [i] + sigma * diagD [i] * rng.nextGaussian ();
            bounds.clampInPlace (arx [k]);
        }

        // Évaluation
        fitness = evalWrapper.evaluateBatch (arx, lambda);
        totalEvaluations += lambda;

        for (int k = 0; k < lambda; k++)
        {
            double oldBest = bestFitness;
            updateBest (arx [k], fitness [k]);
            restartStrategy.onEvaluation (arx [k], fitness [k]);
            if (bestFitness < oldBest) restartStrategy.onImprovement (sigma);
        }

        // Tri
        for (int i = 0; i < lambda; i++) idxBuf [i] = i;
        constraintHandler.sortPopulation (idxBuf, fitness, arx);

        // Nouveau mean
        System.arraycopy (mean, 0, meanBuf, 0, d);
        Arrays.fill (mean, 0.0);
        for (int j = 0; j < mu; j++)
        {
            int ii = idxBuf [j];
            for (int i = 0; i < d; i++) mean [i] += weights [j] * arx [ii][i];
        }

        for (int i = 0; i < d; i++) diffBuf [i] = (mean [i] - meanBuf [i]) / sigma;

        // p_σ  (CSA) — en mode Sep, invsqrtC = diag(1/diagD)
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++)
            ps [i] = (1.0 - csig) * ps [i] + csigFac * (diffBuf [i] / diagD [i]);

        double psNorm = vecNorm (ps);

        // h_σ et p_c
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++)
            pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * diffBuf [i];

        // Mise à jour diagonale via covUpdate
        covUpdate.updateDiagonal (diagC, diagD, pc, arx, meanBuf, sigma,
                idxBuf, weights, mu, c1, cmu, hsig, cc);

        // σ
        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        generation++;

        // Stagnation
        double genBest = fitness [idxBuf [0]];
        if (genBest < prevBestGen - 1e-12)
        {
            stagnationCounter = 0;
            prevBestGen = genBest;
        }
        else stagnationCounter++;

        if (shouldRestart ()) restart ();
    }

    /**
     * Génération Full CMA-ES : O(d²) — avec rotation B et eigendécomposition.
     */
    private void stepFull ()
    {
        // 1. Échantillonnage via la stratégie injectée
        SampleResult sr = sampling.sample (mean, sigma, B, diagD, lambda, d, rng);
        double [][] arx = sr.arx;
        double [][] ary = sr.ary;

        // Clamper
        for (int k = 0; k < lambda; k++)
            bounds.clampInPlace (arx [k]);

        // 2. Évaluation via le wrapper (direct ou surrogate)
        double [] fitness = evalWrapper.evaluateBatch (arx, lambda);
        totalEvaluations += lambda;

        // Mettre à jour le meilleur global et notifier les stratégies
        for (int k = 0; k < lambda; k++)
        {
            double oldBest = bestFitness;
            updateBest (arx [k], fitness [k]);
            restartStrategy.onEvaluation (arx [k], fitness [k]);
            if (bestFitness < oldBest)
                restartStrategy.onImprovement (sigma);
        }

        // 3. Trier via le ConstraintHandler
        for (int i = 0; i < lambda; i++) idxBuf [i] = i;
        constraintHandler.sortPopulation (idxBuf, fitness, arx);

        // 4. Nouveau mean (utilise meanBuf comme oldMean, diffBuf comme meanDiffNorm)
        System.arraycopy (mean, 0, meanBuf, 0, d);
        Arrays.fill (mean, 0.0);
        for (int j = 0; j < mu; j++)
        {
            int ii = idxBuf [j];
            for (int i = 0; i < d; i++)
                mean [i] += weights [j] * arx [ii][i];
        }

        for (int i = 0; i < d; i++)
            diffBuf [i] = (mean [i] - meanBuf [i]) / sigma;

        // 5. Mise à jour p_σ (CSA)
        matVecMulInPlace (invsqrtC, diffBuf, tmpVec);
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++)
            ps [i] = (1.0 - csig) * ps [i] + csigFac * tmpVec [i];

        double psNorm = vecNorm (ps);

        // 6. h_σ et p_c
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++)
            pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * diffBuf [i];

        // 7. Mise à jour de C via la stratégie injectée
        covUpdate.updateCovariance (C, pc, ary, idxBuf, weights, mu, c1, cmu, hsig, cc);

        // 8. Mise à jour de σ
        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        // 9. Eigendecomposition (throttled : tous les eigenFreq steps)
        eigenCounter++;
        int eigenFreq = Math.max (1, (int) (1.0 / ((c1 + cmu) * d) / 10.0));
        if (eigenCounter >= eigenFreq)
        {
            eigenDecomposition ();
            eigenCounter = 0;
        }

        generation++;

        // 10. Détection de stagnation et restart
        double genBest = fitness [idxBuf [0]];
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
    //  RESTART
    // ================================================================
    @Override
    public boolean shouldRestart ()
    {
        if (stagnationCounter > maxStagnation) return true;

        double maxD = diagD [0], minD = diagD [0];
        for (int i = 1; i < d; i++)
        {
            if (diagD [i] > maxD) maxD = diagD [i];
            if (diagD [i] < minD) minD = diagD [i];
        }
        if (minD > 0 && (maxD / minD) > 1e7) return true;
        if (sigma * maxD < 1e-12) return true;

        return false;
    }

    private void restart ()
    {
        restartCount++;

        RestartContext ctx = new RestartContext (d, lambda0, sigma0, restartCount,
                bestX, bestFitness, bounds, cachedSeeds, sigma);
        RestartConfig cfg = restartStrategy.nextRestart (ctx);

        // Trouver le mean pour le restart
        double [] newMean = cfg.mean;
        if (newMean == null)
            newMean = getDefaultRestartMean ();

        evalWrapper.onRestart ();
        setupCMAES (cfg.lambda, newMean, cfg.sigma);
    }

    /**
     * Mean par défaut : mix de cached seeds, perturbation du best, et aléatoire.
     */
    private double [] getDefaultRestartMean ()
    {
        double choice = rng.nextDouble ();
        if (choice < 0.40 && cachedSeeds != null && !cachedSeeds.isEmpty ())
        {
            int idx = restartCount % cachedSeeds.size ();
            return quickLocalOptimize (cachedSeeds.get (idx).clone (), 500);
        }
        else if (choice < 0.70 && bestX != null)
        {
            double [] newMean = bestX.clone ();
            for (int i = 0; i < d; i++)
                newMean [i] += rng.nextGaussian () * bounds.getRange (i) * 0.15;
            bounds.clampInPlace (newMean);
            return newMean;
        }
        else
        {
            double [] bSeed = problem.getRandomControlPoints1DArray ();
            bounds.clampInPlace (bSeed);
            double bF = evaluateCandidate (bSeed);
            for (int r = 0; r < 3; r++)
            {
                double [] s = problem.getRandomControlPoints1DArray ();
                bounds.clampInPlace (s);
                double f = evaluateCandidate (s);
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
                double f = evaluateCandidate (s);
                if (f < bF) { bF = f; bSeed = s; }
            }
            return bSeed;
        }
    }

    // ================================================================
    //  EIGENDECOMPOSITION DE C (tred2 + tql2, JAMA domaine public)
    // ================================================================
    private void eigenDecomposition ()
    {
        // Réutilise les buffers pré-alloués
        for (int i = 0; i < d; i++)
            System.arraycopy (C [i], 0, eigenVBuf [i], 0, d);

        Arrays.fill (eigenDdBuf, 0.0);
        Arrays.fill (eigenEeBuf, 0.0);

        tred2 (eigenVBuf, eigenDdBuf, eigenEeBuf);
        tql2  (eigenVBuf, eigenDdBuf, eigenEeBuf);

        for (int i = 0; i < d; i++)
            diagD [i] = Math.sqrt (Math.max (eigenDdBuf [i], 1e-20));

        B = eigenVBuf;

        for (int i = 0; i < d; i++)
            for (int j = 0; j <= i; j++)
            {
                double sum = 0;
                for (int k = 0; k < d; k++)
                    sum += B [i][k] * (1.0 / diagD [k]) * B [j][k];
                invsqrtC [i][j] = sum;
                invsqrtC [j][i] = sum;
            }
    }

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

    // ===== Utilitaires =====

    private double evaluateCandidate (double [] x)
    {
        double [] [] one = new double [] [] { x };
        double [] f = evalWrapper.evaluateBatch (one, 1);
        totalEvaluations++;
        double fx = f [0];
        updateBest (x, fx);
        return fx;
    }

    /** Multiplie M × v et stocke le résultat dans out (pas d'allocation). */
    private void matVecMulInPlace (double [][] M, double [] v, double [] out)
    {
        for (int i = 0; i < d; i++)
        {
            double sum = 0;
            for (int j = 0; j < d; j++) sum += M [i][j] * v [j];
            out [i] = sum;
        }
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

    // ===== Accesseurs =====

    @Override
    public double [] getBestX ()       { return bestX; }
    @Override
    public double getBestFitness ()    { return bestFitness; }

    @Override
    public OptimizerState getState ()
    {
        double maxD = 1, minD = 1;
        if (diagD != null)
        {
            maxD = diagD [0]; minD = diagD [0];
            for (int i = 1; i < d; i++)
            {
                if (diagD [i] > maxD) maxD = diagD [i];
                if (diagD [i] < minD) minD = diagD [i];
            }
        }
        double cond = (minD > 0) ? (maxD / minD) * (maxD / minD) : 1;
        return new OptimizerState (generation, totalEvaluations, restartCount,
                bestFitness, sigma, cond, bestX);
    }

    // ===== Accesseurs internes pour les stratégies =====
    public Problem getProblem ()       { return problem; }
    public int getD ()                 { return d; }
    public BoundsChecker getBounds ()  { return bounds; }
    public Random getRng ()            { return rng; }
    public double getSigma0 ()         { return sigma0; }
    public void overrideSigma0 (double s) { this.sigma0 = s; }
    public int getLambda0 ()           { return lambda0; }
    public ArrayList<double []> getCachedSeeds () { return cachedSeeds; }
}
