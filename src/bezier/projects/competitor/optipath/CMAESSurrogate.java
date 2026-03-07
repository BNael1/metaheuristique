package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Surrogate-Assisted BIPOP-CMA-ES with RBF Pre-Screening.
 *
 * Key idea: each generation samples PRESCREENING_FACTOR * lambda candidates.
 * All candidates are ranked by the cheap SurrogateRBF model. Only the top
 * lambda are evaluated on the true (expensive) fitness function. The CMA-ES
 * update uses these true evaluations.
 *
 * This effectively multiplies the search power per generation: the
 * covariance matrix is shaped using a much larger effective search radius
 * while keeping the evaluation budget constant.
 *
 * Additionally uses BIPOP dual-regime restarts (proven best on prob4).
 *
 * The surrogate model is trained on-the-fly from all true evaluations,
 * with a bounded archive (circular buffer, most recent points kept).
 *
 * Reference: Kern et al. (2006), Loshchilov et al. (2012) "Surrogate-Assisted CMA-ES"
 */
public class CMAESSurrogate implements Optimizer
{
    // ===== External references =====
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;

    // ===== Surrogate model =====
    private SurrogateRBF surrogate;
    private static final int SURROGATE_ARCHIVE_SIZE = 300;
    private static final int PRESCREENING_FACTOR = 3;
    private static final int SURROGATE_WARMUP = 40;
    private static final double SAFETY_FRACTION = 0.3;
    private static final double MIN_SURROGATE_QUALITY = 0.15;
    private double surrogateQuality;

    // ===== CMA-ES parameters =====
    private int lambda;
    private int mu;
    private double [] weights;
    private double mueff;

    private double csig, dsig;
    private double cc;
    private double c1, cmu;
    private double chiN;

    // ===== Evolutionary state =====
    private double [] mean;
    private double sigma;
    private double [][] C;
    private double [][] B;
    private double [] diagD;
    private double [][] invsqrtC;
    private double [] ps;
    private double [] pc;

    private int generation;
    private int eigenCounter;

    // ===== Global best =====
    private double bestFitness;
    private double [] bestX;

    // ===== Seeding =====
    private double startX, startY, endX, endY;
    private ArrayList<double []> cachedSeeds;

    // ===== BIPOP restart =====
    private final int lambda0;
    private final double sigma0;
    private int restartCount;
    private int stagnationCounter;
    private int maxStagnation;
    private double prevBestGen;
    private int largeLambda;

    // ===== Constructor =====
    public CMAESSurrogate (Problem problem, int d, double [] lb, double [] ub, double [] initMean)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();

        this.lambda0 = 4 + (int) (3.0 * Math.log (d));
        this.sigma0 = (ub [0] - lb [0]) / 6.0;

        this.chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));
    }

    // ================================================================
    //  INITIALIZATION
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
        largeLambda = lambda0;

        // Initialize surrogate model
        surrogate = new SurrogateRBF (d, SURROGATE_ARCHIVE_SIZE);
        surrogateQuality = 0.5;

        // --- Diverse seeding phase ---
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
            surrogate.addPoint (seed, f);
            evaluated.add (seed);
            fitnesses.add (f);
            if (f < bestSeedF)
            {
                bestSeedF = f;
                bestSeed = seed.clone ();
            }
        }

        // Cache top-25 seeds for restarts
        Integer [] indices = new Integer [evaluated.size ()];
        for (int i = 0; i < indices.length; i++) indices [i] = i;
        Arrays.sort (indices, (a, b) -> Double.compare (fitnesses.get (a), fitnesses.get (b)));

        cachedSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (25, indices.length); i++)
            cachedSeeds.add (evaluated.get (indices [i]).clone ());

        // --- Quick local optimization on top-5 seeds ---
        int nLocalSeeds = Math.min (5, cachedSeeds.size ());
        double [] localBestX = bestSeed.clone ();
        double localBestF = bestSeedF;
        for (int s = 0; s < nLocalSeeds; s++)
        {
            double [] result = quickLocalOptimize (cachedSeeds.get (s).clone (), 1000);
            double fResult = problem.evaluate (result);
            updateBest (result, fResult);
            surrogate.addPoint (result, fResult);
            if (fResult < localBestF)
            {
                localBestF = fResult;
                localBestX = result.clone ();
            }
        }

        surrogate.updateKernelWidth ();
        setupCMAES (lambda0, localBestX, sigma0);
    }

    // ================================================================
    //  DIVERSE SEED GENERATION
    // ================================================================
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY     = bounds.getLb () [1];
        double ubY     = bounds.getUb () [1];
        double centerY = (lbY + ubY) / 2.0;
        double halfY   = (ubY - lbY) / 2.0;

        // 1. Sinusoidal paths
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

        // 3. Edge paths
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

        // 4. Random solutions
        for (int r = 0; r < 20; r++)
            seeds.add (problem.getRandomControlPoints1DArray ());

        // 5. Boundary-clustering seeds
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
    //  QUICK LOCAL OPTIMIZATION — (1+1)-ES with 1/5 success rule
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
    //  SETUP CMA-ES
    // ================================================================
    private void setupCMAES (int newLambda, double [] startMean, double startSigma)
    {
        this.lambda = newLambda;
        this.mu = lambda / 2;
        this.sigma = startSigma;
        this.mean = startMean.clone ();

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

        csig = (mueff + 2.0) / (d + mueff + 5.0);
        dsig = 1.0 + 2.0 * Math.max (0, Math.sqrt ((mueff - 1.0) / (d + 1.0)) - 1.0) + csig;
        cc   = (4.0 + mueff / d) / (d + 4.0 + 2.0 * mueff / d);
        c1   = 2.0 / ((d + 1.3) * (d + 1.3) + mueff);
        cmu  = Math.min (1.0 - c1, 2.0 * (mueff - 2.0 + 1.0 / mueff)
                / ((d + 2.0) * (d + 2.0) + mueff));

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
    //  ONE GENERATION with Surrogate Pre-Screening
    // ================================================================
    @Override
    public void step ()
    {
        boolean useSurrogate = surrogate.getSize () >= SURROGATE_WARMUP
                               && surrogateQuality > MIN_SURROGATE_QUALITY;

        double [][] arx;
        double [][] ary;
        double [] fitness;

        if (useSurrogate)
        {
            // --- SURROGATE-ASSISTED GENERATION with safety fraction ---
            int nSafe = Math.max (2, (int) (SAFETY_FRACTION * lambda));
            int nScreen = lambda - nSafe;
            int nCandidates = PRESCREENING_FACTOR * lambda;

            // Sample all candidates from CMA-ES distribution
            double [][] candX = new double [nCandidates][d];
            double [][] candY = new double [nCandidates][d];

            for (int k = 0; k < nCandidates; k++)
            {
                double [] z = new double [d];
                for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

                double [] Dz = new double [d];
                for (int i = 0; i < d; i++) Dz [i] = diagD [i] * z [i];

                for (int i = 0; i < d; i++)
                {
                    double sum = 0;
                    for (int j = 0; j < d; j++) sum += B [i][j] * Dz [j];
                    candY [k][i] = sum;
                    candX [k][i] = mean [i] + sigma * sum;
                }
                bounds.clampInPlace (candX [k]);
            }

            // Predict all using surrogate
            double [] surrogateF = new double [nCandidates];
            surrogate.predictBatch (candX, nCandidates, surrogateF);

            // Sort by surrogate fitness
            Integer [] sIdx = new Integer [nCandidates];
            for (int i = 0; i < nCandidates; i++) sIdx [i] = i;
            Arrays.sort (sIdx, (a, b) -> Double.compare (surrogateF [a], surrogateF [b]));

            // Select: top-nScreen by surrogate + nSafe random from remainder
            arx = new double [lambda][d];
            ary = new double [lambda][d];
            fitness = new double [lambda];
            double [] selectedSurrogateF = new double [lambda];

            int slot = 0;
            // Top nScreen by surrogate prediction
            for (int k = 0; k < nScreen; k++)
            {
                int ci = sIdx [k];
                for (int i = 0; i < d; i++)
                {
                    arx [slot][i] = candX [ci][i];
                    ary [slot][i] = candY [ci][i];
                }
                selectedSurrogateF [slot] = surrogateF [ci];
                slot++;
            }

            // nSafe random from unselected pool (safety / hedge)
            int remainCount = nCandidates - nScreen;
            for (int k = 0; k < nSafe; k++)
            {
                // Fisher-Yates partial shuffle to pick random from tail
                int ri = nScreen + rng.nextInt (remainCount);
                int ci = sIdx [ri];
                for (int i = 0; i < d; i++)
                {
                    arx [slot][i] = candX [ci][i];
                    ary [slot][i] = candY [ci][i];
                }
                selectedSurrogateF [slot] = surrogateF [ci];
                slot++;
                // Swap used index to consumed area
                int tmp = sIdx [ri];
                sIdx [ri] = sIdx [nScreen + remainCount - 1];
                sIdx [nScreen + remainCount - 1] = tmp;
                remainCount--;
                if (remainCount <= 0) break;
            }

            // Evaluate all lambda on true fitness
            for (int k = 0; k < lambda; k++)
            {
                fitness [k] = problem.evaluate (arx [k]);
                updateBest (arx [k], fitness [k]);
                surrogate.addPoint (arx [k], fitness [k]);
            }

            // Update surrogate quality via Kendall rank correlation
            double tau = kendallTau (selectedSurrogateF, fitness, lambda);
            surrogateQuality = 0.8 * surrogateQuality + 0.2 * Math.max (0, tau);

            // Periodically update kernel width
            if (generation % 5 == 0)
                surrogate.updateKernelWidth ();
        }
        else
        {
            // --- STANDARD GENERATION (warm-up or low-quality surrogate) ---
            arx = new double [lambda][d];
            ary = new double [lambda][d];
            fitness = new double [lambda];

            for (int k = 0; k < lambda; k++)
            {
                double [] z = new double [d];
                for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

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
                surrogate.addPoint (arx [k], fitness [k]);
            }
        }

        // --- Standard CMA-ES update from here ---

        // 2. Sort by fitness
        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        // 3. New mean
        double [] oldMean = mean.clone ();
        mean = new double [d];
        for (int j = 0; j < mu; j++)
        {
            int ii = idx [j];
            for (int i = 0; i < d; i++)
                mean [i] += weights [j] * arx [ii][i];
        }

        double [] meanDiffNorm = new double [d];
        for (int i = 0; i < d; i++)
            meanDiffNorm [i] = (mean [i] - oldMean [i]) / sigma;

        // 4. p_sigma (CSA)
        double [] invsqrtCdiff = matVecMul (invsqrtC, meanDiffNorm);
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++)
            ps [i] = (1.0 - csig) * ps [i] + csigFac * invsqrtCdiff [i];

        double psNorm = vecNorm (ps);

        // 5. h_sigma and p_c
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++)
            pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * meanDiffNorm [i];

        // 6. Covariance matrix update
        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j <= i; j++)
            {
                double rank1 = c1 * pc [i] * pc [j];

                double rankmu = 0;
                for (int k = 0; k < mu; k++)
                {
                    int ii = idx [k];
                    rankmu += weights [k] * ary [ii][i] * ary [ii][j];
                }
                rankmu *= cmu;

                C [i][j] = cOld * C [i][j] + rank1 + rankmu;
                C [j][i] = C [i][j];
            }
        }

        // 7. Update sigma
        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        // 8. Eigendecomposition
        eigenCounter++;
        if (eigenCounter >= 1)
        {
            eigenDecomposition ();
            eigenCounter = 0;
        }

        generation++;

        // 9. Stagnation detection and BIPOP restart
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
    //  STAGNATION DETECTION
    // ================================================================
    private boolean shouldRestart ()
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

    // ================================================================
    //  BIPOP RESTART
    // ================================================================
    private void restart ()
    {
        restartCount++;
        surrogate.clear ();
        surrogateQuality = 0.5;
        int newLambda;
        double newSigma;
        double [] newMean;

        if (restartCount % 2 == 1)
        {
            // --- BIPOP "large" restart: exploration ---
            largeLambda = Math.min (largeLambda * 2, 512);
            newLambda = largeLambda;
            newSigma = sigma0;

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
        }
        else
        {
            // --- BIPOP "small" restart: exploitation ---
            newLambda = lambda0;
            newSigma = sigma0 / 10.0;

            if (bestX != null)
            {
                newMean = bestX.clone ();
                for (int i = 0; i < d; i++)
                    newMean [i] += rng.nextGaussian () * bounds.getRange (i) * 0.05;
                bounds.clampInPlace (newMean);
            }
            else
            {
                newMean = initMean.clone ();
            }
        }

        setupCMAES (newLambda, newMean, newSigma);
    }

    // ================================================================
    //  EIGENDECOMPOSITION (tred2 + tql2, public domain JAMA)
    // ================================================================
    private void eigenDecomposition ()
    {
        double [][] V = new double [d][d];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < d; j++)
                V [i][j] = C [i][j];

        double [] dd = new double [d];
        double [] ee = new double [d];

        tred2 (V, dd, ee);
        tql2  (V, dd, ee);

        for (int i = 0; i < d; i++)
            diagD [i] = Math.sqrt (Math.max (dd [i], 1e-20));

        B = V;

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

    private void tred2 (double [][] V, double [] dArr, double [] e)
    {
        int n = this.d;
        for (int j = 0; j < n; j++) dArr [j] = V [n - 1][j];

        for (int i = n - 1; i > 0; i--)
        {
            double scale = 0, h = 0;
            for (int k = 0; k < i; k++) scale += Math.abs (dArr [k]);

            if (scale == 0.0)
            {
                e [i] = dArr [i - 1];
                for (int j = 0; j < i; j++) { dArr [j] = V [i - 1][j]; V [i][j] = 0; V [j][i] = 0; }
            }
            else
            {
                for (int k = 0; k < i; k++) { dArr [k] /= scale; h += dArr [k] * dArr [k]; }
                double f = dArr [i - 1];
                double g = Math.sqrt (h);
                if (f > 0) g = -g;
                e [i] = scale * g;
                h -= f * g;
                dArr [i - 1] = f - g;
                for (int j = 0; j < i; j++) e [j] = 0;

                for (int j = 0; j < i; j++)
                {
                    f = dArr [j]; V [j][i] = f;
                    g = e [j] + V [j][j] * f;
                    for (int k = j + 1; k <= i - 1; k++) { g += V [k][j] * dArr [k]; e [k] += V [k][j] * f; }
                    e [j] = g;
                }
                f = 0;
                for (int j = 0; j < i; j++) { e [j] /= h; f += e [j] * dArr [j]; }
                double hh = f / (h + h);
                for (int j = 0; j < i; j++) e [j] -= hh * dArr [j];
                for (int j = 0; j < i; j++)
                {
                    f = dArr [j]; g = e [j];
                    for (int k = j; k <= i - 1; k++) V [k][j] -= f * e [k] + g * dArr [k];
                    dArr [j] = V [i - 1][j]; V [i][j] = 0;
                }
            }
            dArr [i] = h;
        }

        for (int i = 0; i < n - 1; i++)
        {
            V [n - 1][i] = V [i][i]; V [i][i] = 1;
            double h = dArr [i + 1];
            if (h != 0)
            {
                for (int k = 0; k <= i; k++) dArr [k] = V [k][i + 1] / h;
                for (int j = 0; j <= i; j++)
                {
                    double g = 0;
                    for (int k = 0; k <= i; k++) g += V [k][i + 1] * V [k][j];
                    for (int k = 0; k <= i; k++) V [k][j] -= g * dArr [k];
                }
            }
            for (int k = 0; k <= i; k++) V [k][i + 1] = 0;
        }
        for (int j = 0; j < n; j++) { dArr [j] = V [n - 1][j]; V [n - 1][j] = 0; }
        V [n - 1][n - 1] = 1;
        e [0] = 0;
    }

    private void tql2 (double [][] V, double [] dArr, double [] e)
    {
        int n = this.d;
        for (int i = 1; i < n; i++) e [i - 1] = e [i];
        e [n - 1] = 0;

        double f = 0, tst1 = 0;
        double eps = Math.pow (2.0, -52.0);

        for (int l = 0; l < n; l++)
        {
            tst1 = Math.max (tst1, Math.abs (dArr [l]) + Math.abs (e [l]));
            int m = l;
            while (m < n) { if (Math.abs (e [m]) <= eps * tst1) break; m++; }

            if (m > l)
            {
                int iter = 0;
                do
                {
                    iter++;
                    double g = dArr [l];
                    double p = (dArr [l + 1] - g) / (2.0 * e [l]);
                    double r = Math.hypot (p, 1.0);
                    if (p < 0) r = -r;
                    dArr [l] = e [l] / (p + r);
                    dArr [l + 1] = e [l] * (p + r);
                    double dl1 = dArr [l + 1];
                    double h = g - dArr [l];
                    for (int i = l + 2; i < n; i++) dArr [i] -= h;
                    f += h;

                    p = dArr [m]; double c = 1, c2 = c, c3 = c;
                    double el1 = e [l + 1]; double s = 0, s2 = 0;
                    for (int i = m - 1; i >= l; i--)
                    {
                        c3 = c2; c2 = c; s2 = s;
                        g = c * e [i]; h = c * p;
                        r = Math.hypot (p, e [i]);
                        e [i + 1] = s * r; s = e [i] / r; c = p / r;
                        p = c * dArr [i] - s * g;
                        dArr [i + 1] = h + s * (c * g + s * dArr [i]);
                        for (int k = 0; k < n; k++)
                        {
                            h = V [k][i + 1];
                            V [k][i + 1] = s * V [k][i] + c * h;
                            V [k][i]     = c * V [k][i] - s * h;
                        }
                    }
                    p = -s * s2 * c3 * el1 * e [l] / dl1;
                    e [l] = s * p; dArr [l] = c * p;
                }
                while (Math.abs (e [l]) > eps * tst1);
            }
            dArr [l] = dArr [l] + f; e [l] = 0;
        }

        for (int i = 0; i < n - 1; i++)
        {
            int k = i; double p = dArr [i];
            for (int j = i + 1; j < n; j++) if (dArr [j] < p) { k = j; p = dArr [j]; }
            if (k != i)
            {
                dArr [k] = dArr [i]; dArr [i] = p;
                for (int j = 0; j < n; j++) { p = V [j][i]; V [j][i] = V [j][k]; V [j][k] = p; }
            }
        }
    }

    // ===== Rank correlation for surrogate quality =====

    private double kendallTau (double [] a, double [] b, int n)
    {
        int concordant = 0, discordant = 0;
        for (int i = 0; i < n; i++)
        {
            for (int j = i + 1; j < n; j++)
            {
                double da = a [i] - a [j];
                double db = b [i] - b [j];
                if (da * db > 0) concordant++;
                else if (da * db < 0) discordant++;
            }
        }
        int total = concordant + discordant;
        if (total == 0) return 0;
        return (double) (concordant - discordant) / total;
    }

    // ===== Utilities =====

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

    @Override
    public double [] getBestX () { return bestX; }
    public double getBestFitness () { return bestFitness; }
}
