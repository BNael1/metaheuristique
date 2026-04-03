package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Algorithme final : Portfolio 2xCMA-ES + restart adaptatif.
 *
 * Structure :
 *   1. Seeding massif (~500 seeds) + optim locale sur top-10
 *   2. Portfolio CMA-ES small (margin=5) + CMA-ES wide (margin=15)
 *   3. Restart externe multi-signaux (dynamique + frustration + optional geo)
 *      sans seuil absolu de fitness.
 */
public class OptiPath extends CompetitorProject
{
    // ================================================================
    //  HYPERPARAMETRES
    // ================================================================
    private double marginSmall;
    private double marginWide;
    private static final long TOTAL_TIME_MS = 58_000;
    private static final long RESTART_CHECK_EVERY_MS = 200;
    private static final long RESCUE_START_MS = 8_000;
    private static final long RESCUE_PERIOD_MS = 1_500;
    private static final int RESCUE_BURST_TRIES = 28;
    private static final int FEASIBILITY_SAMPLES = 128;

    /** Fraction du temps pour le seeding initial. */
    private static final double SEED_RATIO = 0.10;

    /** Strategie de restart externe (remplace le seuil fixe 500). */
    private final OuterRestartStrategy restartStrategy;

    // ================================================================
    //  ETAT
    // ================================================================
    private int d, nCP;
    private int nObs;
    private double [] obsX, obsY, obsR;
    private double [] lbSmall, ubSmall, lbWide, ubWide;
    private Optimizer cmaesSmall, cmaesWide;
    private long startTime;
    private boolean cmaesReady;
    private double globalBestFitness;
    private Random rng;
    private boolean stepSmallNext;

    /** Timestamp du dernier lancement/restart CMA-ES. */
    private long lastRestartTime;

    /** Compteur de restarts pour diversifier les seeds. */
    private int restartCount;

    /** Fitness au moment du dernier lancement CMA-ES. */
    private double fitnessAtLaunch;

    /** Sigma initial des CMA-ES (pour detection effondrement). */
    private double initialSigmaSmall, initialSigmaWide;
    private double domainDiag;

    /** Cadence de checks et deltas entre checks pour la strategie externe. */
    private long lastRestartCheckMs;
    private long lastCtxSnapshotMs;
    private double lastBestSmall, lastBestWide;
    private int lastEvalSmall, lastEvalWide;

    /** Stats du seeding pour les strategies adaptatives. */
    private SeedingStats seedStats;

    /** Meilleur vecteur solution global (pour restart best-ever). */
    private double [] globalBestX;
    /** Meilleur vecteur faisable observe (garde-fou anti-catastrophe). */
    private double [] bestFeasibleX;
    private double bestFeasibleFitness;
    /** Parametres de micro-affinage local du meilleur faisable. */
    private double refineSigma;
    private long lastRefineMs;
    /** Timestamp du dernier gain global et du dernier burst de rescue. */
    private long lastGlobalImproveMs;
    private long lastRescueBurstMs;

    /** Waypoints detectes par l'analyse obstacle-aware (start, gaps, end). */
    private double [][] detectedWaypoints;
    /** Ratio longueur_waypoints / distance_directe (1.0 = ligne droite). */
    private double pathComplexity;
    /** Seed A* garde comme fallback garanti pour les restarts. */
    private double [] aStarSeed;

    // Seeding : top seeds + optim locale
    private ArrayList<double []> topSeeds;
    private int localOptIdx;
    private double [] localCurrent;
    private double localCurrentF;
    private double localStep;
    private int localEvals;
    private static final int LOCAL_BUDGET_PER_SEED = 200;

    // ================================================================
    //  CONSTRUCTEURS
    // ================================================================

    public OptiPath (Problem problem) throws InvalidProjectException
    {
        this (problem, 5.0, 15.0, new TimePhasedFocusRestart ());
    }

    public OptiPath (Problem problem, OuterRestartStrategy strategy)
            throws InvalidProjectException
    {
        this (problem, 5.0, 15.0, strategy);
    }

    OptiPath (Problem problem, double marginSmall, double marginWide,
              OuterRestartStrategy strategy)
            throws InvalidProjectException
    {
        super (problem);
        addAuthor ("BENSAADI");
        addAuthor ("RAHALI");
        setMethodName ("OptiPath (Portfolio CMA-ES)");
        this.marginSmall = marginSmall;
        this.marginWide = marginWide;
        this.restartStrategy = strategy;
    }

    // ================================================================
    //  initialization()
    // ================================================================
    @Override
    public void initialization ()
    {
        startTime = System.currentTimeMillis ();
        cmaesReady = false;
        rng = new Random ();
        globalBestFitness = Double.POSITIVE_INFINITY;
        bestFeasibleFitness = Double.POSITIVE_INFINITY;
        bestFeasibleX = null;
        stepSmallNext = true;
        restartCount = 0;
        lastGlobalImproveMs = startTime;
        lastRescueBurstMs = 0L;
        lastRefineMs = 0L;
        detectedWaypoints = null;
        pathComplexity = 1.0;
        aStarSeed = null;

        nCP = problem.getNControlPoints ();
        d = 2 * nCP;
        nObs = problem.getNObstacles ();
        obsX = new double [nObs];
        obsY = new double [nObs];
        obsR = new double [nObs];
        for (int i = 0; i < nObs; i++)
        {
            bezier.evaluation.Obstacle o = problem.getObstacle (i);
            obsX[i] = o.getX ();
            obsY[i] = o.getY ();
            obsR[i] = o.getRadius ();
        }
        // Adapter les marges : réduire pour petits domaines, garder >=5/15 sinon
        double domSize = Math.max (problem.getMaxX () - problem.getMinX (),
                                    problem.getMaxY () - problem.getMinY ());
        if (domSize < 20.0)
        {
            marginSmall = Math.max (1.0, domSize * 0.20);
            marginWide  = Math.max (2.0, domSize * 0.40);
        }
        else
        {
            marginSmall = 5.0;
            marginWide  = 15.0;
        }
        lbSmall = buildLb (marginSmall);
        ubSmall = buildUb (marginSmall);
        lbWide  = buildLb (marginWide);
        ubWide  = buildUb (marginWide);
        domainDiag = Math.hypot (problem.getMaxX () - problem.getMinX (),
                                 problem.getMaxY () - problem.getMinY ());
        if (domainDiag <= 0.0) domainDiag = 1.0;
        refineSigma = domainDiag * 0.01;
        lastRestartCheckMs = 0L;
        lastCtxSnapshotMs = 0L;
        lastBestSmall = Double.POSITIVE_INFINITY;
        lastBestWide = Double.POSITIVE_INFINITY;
        lastEvalSmall = 0;
        lastEvalWide = 0;

        massiveSeed ();

        localOptIdx = 0;
        if (!topSeeds.isEmpty ())
        {
            localCurrent = topSeeds.get (0).clone ();
            localCurrentF = evaluateAndTrack (localCurrent);
            localStep = (ubWide [0] - lbWide [0]) / 20.0;
            localEvals = 0;
        }
    }

    // ================================================================
    //  Seeding massif
    // ================================================================
    private void massiveSeed ()
    {
        double sx = problem.getStartPoint ().getX (), sy = problem.getStartPoint ().getY ();
        double ex = problem.getEndPoint ().getX (),   ey = problem.getEndPoint ().getY ();

        ArrayList<double []> allSeeds = new ArrayList<> ();
        ArrayList<Double> allFitness = new ArrayList<> ();

        // Fast-path : sans obstacles, le chemin optimal est la ligne droite
        if (nObs == 0)
        {
            double [] straight = defaultMean ();
            addSeed (allSeeds, allFitness, straight);
            for (int r = 0; r < 15; r++)
            {
                double [] p = straight.clone ();
                for (int i = 0; i < d; i++)
                    p[i] += rng.nextGaussian () * domainDiag * (0.005 + 0.01 * r);
                addSeed (allSeeds, allFitness, p);
            }
            topSeeds = new ArrayList<> ();
            topSeeds.add (straight);
            globalBestX = straight.clone ();
            seedStats = new SeedingStats (allFitness.get (0), allFitness.get (0), 0.0, allFitness.get (0));
            restartStrategy.init (seedStats);
            return;
        }

        double [] xVals = {lbWide[0], lbWide[0]+2, problem.getMinX (), problem.getMinX ()+2,
                           (problem.getMinX ()+problem.getMaxX ())/2,
                           problem.getMaxX ()-2, problem.getMaxX (), ubWide[0]-2, ubWide[0]};
        double [] yVals = {lbWide[1], lbWide[1]+2, problem.getMinY (), problem.getMinY ()+2,
                           (problem.getMinY ()+problem.getMaxY ())/2,
                           problem.getMaxY ()-2, problem.getMaxY (), ubWide[1]-2, ubWide[1]};

        // 1. Grille x constant, y constant
        for (double xv : xVals)
            for (double yv : yVals)
                addSeed (allSeeds, allFitness, makePath (xv, yv));

        // 2. Alternance X extreme
        for (double y1 : yVals)
            for (double y2 : yVals)
            {
                if (y1 == y2) continue;
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                { p[2*i] = (i%2==0) ? ubWide[0] : lbWide[0]; p[2*i+1] = (i<nCP/2) ? y1 : y2; }
                addSeed (allSeeds, allFitness, p);
            }

        // 3. Lignes a differentes hauteurs
        for (double yy : yVals)
        {
            double [] p = new double [d];
            for (int i = 0; i < nCP; i++)
            { double t=(double)(i+1)/(nCP+1); p[2*i]=sx+t*(ex-sx); p[2*i+1]=yy; }
            addSeed (allSeeds, allFitness, p);
        }

        // 4. Sinusoides
        double halfY = (ubWide[1]-lbWide[1]) / 2.0;
        double centerY = (lbWide[1]+ubWide[1]) / 2.0;
        for (double per : new double[]{0.5,1.0,1.5,2.0})
            for (double ph : new double[]{0, Math.PI/2, Math.PI, 3*Math.PI/2})
                for (double amp : new double[]{0.3, 0.6, 0.9})
                {
                    double [] p = new double [d];
                    for (int i=0;i<nCP;i++)
                    { double t=(double)(i+1)/(nCP+1); p[2*i]=sx+t*(ex-sx); p[2*i+1]=centerY+amp*halfY*Math.sin(2*Math.PI*per*t+ph); }
                    addSeed (allSeeds, allFitness, p);
                }

        // 5. Aleatoires
        for (int r = 0; r < 80; r++)
        {
            double [] p = new double [d];
            for (int i=0;i<d;i++) p[i] = lbWide[i] + rng.nextDouble()*(ubWide[i]-lbWide[i]);
            addSeed (allSeeds, allFitness, p);
        }

        // 6. Zigzags : alternance haut/bas le long du chemin start→end
        for (double yH : yVals)
            for (double yL : yVals)
            {
                if (Math.abs (yH - yL) < 4.0) continue;
                for (int flip = 0; flip < 2; flip++)
                {
                    double [] p = new double [d];
                    for (int i = 0; i < nCP; i++)
                    {
                        double t = (double)(i+1) / (nCP+1);
                        p[2*i] = sx + t * (ex - sx);
                        p[2*i+1] = ((i % 2 == flip) ? yH : yL);
                    }
                    addSeed (allSeeds, allFitness, p);
                }
            }

        // 7. Obstacle-aware : detection de murs et routage par les gaps
        addObstacleAwareSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        // 7b. Detour seeds : contournement d'obstacles bloquant le chemin direct
        addDetourSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        // 8. Seed deterministe via A* sur grille (garde-fou anti-catastrophe)
        addGridPlannerSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        // Trier et garder top-20
        Integer [] idx = new Integer [allSeeds.size ()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort (idx, (a,b) -> Double.compare (allFitness.get(a), allFitness.get(b)));

        topSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (20, idx.length); i++)
            topSeeds.add (allSeeds.get (idx[i]).clone ());

        // Initialiser globalBestX avec le meilleur seed pour que le best-ever
        // restart fonctionne meme si CMA-ES ne s'ameliore jamais
        if (!topSeeds.isEmpty ())
            globalBestX = topSeeds.get (0).clone ();

        // Calculer SeedingStats pour les strategies adaptatives
        int n = allFitness.size ();
        double [] sorted = new double [n];
        for (int i = 0; i < n; i++) sorted[i] = allFitness.get (idx[i]);
        double best = sorted[0];
        double median = sorted[n / 2];
        double p25 = sorted[n / 4];
        double mean = 0;
        for (double v : sorted) mean += v;
        mean /= n;
        double variance = 0;
        for (double v : sorted) variance += (v - mean) * (v - mean);
        variance /= n;
        seedStats = new SeedingStats (best, median, Math.sqrt (variance), p25);
        restartStrategy.init (seedStats);
    }

    private void addSeed (ArrayList<double []> seeds, ArrayList<Double> fits, double [] p)
    {
        clamp (p);
        double f = evaluateAndTrack (p);
        seeds.add (p);
        fits.add (f);
    }

    // ================================================================
    //  loop()
    // ================================================================
    @Override
    public void loop ()
    {
        long elapsed = System.currentTimeMillis () - startTime;
        double ratio = (double) elapsed / TOTAL_TIME_MS;

        if (!cmaesReady && ratio < SEED_RATIO)
        {
            doLocalOptStep ();
            return;
        }

        if (!cmaesReady)
            launchBothCMAES ();

        maybeCheckExternalRestart ();
        maybeRescueFeasibility ();

        // Alterner small/wide
        if (stepSmallNext) cmaesSmall.step ();
        else cmaesWide.step ();
        stepSmallNext = !stepSmallNext;
        maybeRefineElite ();

        // Tracker le meilleur (CMA-ES met a jour problem en interne)
        double prev = globalBestFitness;
        globalBestFitness = Math.min (globalBestFitness, problem.getBestEvaluation ());
        if (globalBestFitness < prev)
        {
            lastGlobalImproveMs = System.currentTimeMillis ();
            restartStrategy.onFitnessUpdate (globalBestFitness, System.currentTimeMillis ());
            // Sauvegarder le vecteur solution du meilleur resultat
            double bs = cmaesSmall.getBestFitness (), bw = cmaesWide.getBestFitness ();
            double [] src = (bs <= bw) ? cmaesSmall.getBestX () : cmaesWide.getBestX ();
            if (src != null)
            {
                globalBestX = src.clone ();
                updateFeasible (src, Math.min (bs, bw));
            }
        }
    }

    private void maybeCheckExternalRestart ()
    {
        long now = System.currentTimeMillis ();
        if (lastRestartCheckMs != 0L && (now - lastRestartCheckMs) < RESTART_CHECK_EVERY_MS)
            return;

        // Filet de securite : si apres 30s le score est encore tres mauvais,
        // forcer un restart depuis le seed A*
        long elapsed = now - startTime;
        if (aStarSeed != null && elapsed > 30_000L
                && bestFeasibleFitness > 2.0 * domainDiag)
        {
            globalBestX = aStarSeed.clone ();
            restartCount++;
            restartStrategy.onRestart ();
            launchBothCMAES ();
            return;
        }

        OptimizerState sState = cmaesSmall.getState ();
        OptimizerState wState = cmaesWide.getState ();

        double bestSmall = cmaesSmall.getBestFitness ();
        double bestWide  = cmaesWide.getBestFitness ();
        updateFeasible (cmaesSmall.getBestX (), bestSmall);
        updateFeasible (cmaesWide.getBestX (), bestWide);
        int evalSmall = sState.evaluations;
        int evalWide  = wState.evaluations;
        int genSmall = sState.generation;
        int genWide  = wState.generation;

        long dtMs = (lastCtxSnapshotMs == 0L) ? Math.max (1L, now - lastRestartTime)
                                              : Math.max (1L, now - lastCtxSnapshotMs);
        double deltaBestSmall = Double.isFinite (lastBestSmall)
                ? Math.max (0.0, lastBestSmall - bestSmall) : 0.0;
        double deltaBestWide = Double.isFinite (lastBestWide)
                ? Math.max (0.0, lastBestWide - bestWide) : 0.0;
        int deltaEvalSmall = (lastCtxSnapshotMs == 0L) ? 0 : Math.max (0, evalSmall - lastEvalSmall);
        int deltaEvalWide  = (lastCtxSnapshotMs == 0L) ? 0 : Math.max (0, evalWide - lastEvalWide);

        long sinceLaunch = now - lastRestartTime;
        long elapsedTotal = now - startTime;

        OuterRestartContext ctx = new OuterRestartContext (
                now, dtMs,
                globalBestFitness, fitnessAtLaunch,
                sinceLaunch, elapsedTotal, TOTAL_TIME_MS, restartCount,
                genSmall, genWide,
                evalSmall, evalWide,
                deltaEvalSmall, deltaEvalWide,
                deltaBestSmall, deltaBestWide,
                sState.sigma, wState.sigma,
                cmaesSmall.getBestX (), cmaesWide.getBestX (),
                bestSmall, bestWide,
                seedStats,
                domainDiag,
                problem.getStartPoint ().getX (), problem.getStartPoint ().getY (),
                problem.getEndPoint ().getX (), problem.getEndPoint ().getY (),
                lbWide, ubWide);

        lastRestartCheckMs = now;
        lastCtxSnapshotMs = now;
        lastBestSmall = bestSmall;
        lastBestWide = bestWide;
        lastEvalSmall = evalSmall;
        lastEvalWide = evalWide;

        OuterRestartStrategy.Decision decision = restartStrategy.shouldRestart (ctx);
        if (decision != OuterRestartStrategy.Decision.CONTINUE)
        {
            restartCount++;
            restartStrategy.onRestart ();
            switch (decision)
            {
                case RESTART_BOTH:
                    launchBothCMAES ();
                    break;
                case RESTART_SMALL:
                    launchSingleCMAES (true);
                    break;
                case RESTART_WIDE:
                    launchSingleCMAES (false);
                    break;
                default:
                    break;
            }
        }
    }

    // ================================================================
    //  Optim locale sur top seeds
    // ================================================================
    private void doLocalOptStep ()
    {
        if (localOptIdx >= topSeeds.size ()) return;

        double [] xNew = new double [d];
        for (int i = 0; i < d; i++)
            xNew[i] = localCurrent[i] + rng.nextGaussian () * localStep;
        clamp (xNew);
        double fNew = evaluateAndTrack (xNew);

        if (fNew < localCurrentF) { localCurrent=xNew; localCurrentF=fNew; localStep*=1.2; }
        localEvals++;

        if (localEvals % 20 == 0) localStep *= 0.8;
        localStep = Math.max (localStep, 1e-6);

        if (localEvals >= LOCAL_BUDGET_PER_SEED)
        {
            localOptIdx++;
            localEvals = 0;
            if (localOptIdx < topSeeds.size ())
            {
                localCurrent = topSeeds.get(localOptIdx).clone ();
                localCurrentF = evaluateAndTrack (localCurrent);
                localStep = (ubWide[0]-lbWide[0]) / 20.0;
            }
        }
    }

    // ================================================================
    //  Lancement / restart des CMA-ES
    // ================================================================
    private void launchBothCMAES ()
    {
        cmaesReady = true;
        lastRestartTime = System.currentTimeMillis ();
        fitnessAtLaunch = globalBestFitness;

        double [] initMean = pickRestartMean ();

        AlgorithmParameters ps = new AlgorithmParameters ();
        ps.setMargin (marginSmall);
        AlgorithmParameters pw = new AlgorithmParameters ();
        pw.setMargin (marginWide);

        cmaesSmall = CMAESBuilder.bipop (problem)
                .bounds (lbSmall, ubSmall).parameters (ps).initMean (initMean)
                .covariance (new ActiveCovariance (true))
                .sampling (new MirrorSampling ()).build ();
        cmaesWide = CMAESBuilder.bipop (problem)
                .bounds (lbWide, ubWide).parameters (pw).initMean (initMean)
                .covariance (new ActiveCovariance (true))
                .sampling (new MirrorSampling ()).build ();

        cmaesSmall.init ();
        cmaesWide.init ();

        initialSigmaSmall = cmaesSmall.getState ().sigma;
        initialSigmaWide = cmaesWide.getState ().sigma;
        resetCtxTracking (System.currentTimeMillis ());

        globalBestFitness = Math.min (globalBestFitness, problem.getBestEvaluation ());
    }

    /**
     * Relance un seul CMA-ES (pour restart partiel, T7).
     * @param small true = relancer cmaesSmall, false = relancer cmaesWide.
     */
    void launchSingleCMAES (boolean small)
    {
        lastRestartTime = System.currentTimeMillis ();
        fitnessAtLaunch = globalBestFitness;

        double [] initMean = pickRestartMean ();

        if (small)
        {
            AlgorithmParameters ps = new AlgorithmParameters ();
            ps.setMargin (marginSmall);
            cmaesSmall = CMAESBuilder.bipop (problem)
                    .bounds (lbSmall, ubSmall).parameters (ps).initMean (initMean)
                    .covariance (new ActiveCovariance (true))
                    .sampling (new MirrorSampling ()).build ();
            cmaesSmall.init ();
            initialSigmaSmall = cmaesSmall.getState ().sigma;
        }
        else
        {
            AlgorithmParameters pw = new AlgorithmParameters ();
            pw.setMargin (marginWide);
            cmaesWide = CMAESBuilder.bipop (problem)
                    .bounds (lbWide, ubWide).parameters (pw).initMean (initMean)
                    .covariance (new ActiveCovariance (true))
                    .sampling (new MirrorSampling ()).build ();
            cmaesWide.init ();
            initialSigmaWide = cmaesWide.getState ().sigma;
        }

        resetCtxTracking (System.currentTimeMillis ());

        globalBestFitness = Math.min (globalBestFitness, problem.getBestEvaluation ());
    }

    private void resetCtxTracking (long now)
    {
        lastRestartCheckMs = now;
        lastCtxSnapshotMs = 0L;
        OptimizerState sState = cmaesSmall != null ? cmaesSmall.getState () : null;
        OptimizerState wState = cmaesWide != null ? cmaesWide.getState () : null;
        lastBestSmall = cmaesSmall != null ? cmaesSmall.getBestFitness () : Double.POSITIVE_INFINITY;
        lastBestWide = cmaesWide != null ? cmaesWide.getBestFitness () : Double.POSITIVE_INFINITY;
        lastEvalSmall = sState != null ? sState.evaluations : 0;
        lastEvalWide = wState != null ? wState.evaluations : 0;
    }

    /**
     * Choisit un mean de demarrage different a chaque restart pour
     * maximiser la diversite et les chances de trouver le bon bassin.
     */
    private double [] pickRestartMean ()
    {
        if (topSeeds == null || topSeeds.isEmpty ())
            return defaultMean ();

        // Si le bassin courant est nettement pire que le best-ever global,
        // repartir pres du best-ever (critere relatif, robuste a l'echelle/signe).
        double [] anchorBest = (bestFeasibleX != null) ? bestFeasibleX : globalBestX;
        double basinBest = Double.POSITIVE_INFINITY;
        if (cmaesSmall != null) basinBest = Math.min (basinBest, cmaesSmall.getBestFitness ());
        if (cmaesWide != null) basinBest = Math.min (basinBest, cmaesWide.getBestFitness ());
        if (!Double.isFinite (basinBest)) basinBest = fitnessAtLaunch;
        double relGap = (basinBest - globalBestFitness)
                / (Math.abs (globalBestFitness) + 1e-9);
        if (anchorBest != null && relGap > 2.0)
        {
            double [] p = anchorBest.clone ();
            double noise = domainDiag * 0.02;
            for (int i = 0; i < d; i++)
                p[i] += rng.nextGaussian () * noise;
            clamp (p);
            return p;
        }

        // Fallback A* : utiliser le seed A* regulierement et quand la qualite est mauvaise
        if (aStarSeed != null && (basinBest > 3.0 * domainDiag
                || (bestFeasibleX == null && restartCount > 2)
                || (restartCount % 3 == 2)))
        {
            double [] p = aStarSeed.clone ();
            double noise = domainDiag * 0.015;
            for (int i = 0; i < d; i++)
                p[i] += rng.nextGaussian () * noise;
            clamp (p);
            return p;
        }

        // 1 restart sur 2 : repartir pres du meilleur resultat global
        if (restartCount % 2 == 1 && anchorBest != null)
        {
            double [] p = anchorBest.clone ();
            double noise = domainDiag * 0.03;
            for (int i = 0; i < d; i++)
                p[i] += rng.nextGaussian () * noise;
            clamp (p);
            return p;
        }

        // Premier lancement : meilleur seed
        // Restarts suivants : cycler parmi les top seeds + aleatoires
        int seedIdx = restartCount % (topSeeds.size () + 2);

        if (seedIdx < topSeeds.size ())
            return topSeeds.get (seedIdx).clone ();

        // Seeds aleatoires dans les bornes etendues pour diversite
        double [] p = new double [d];
        for (int i = 0; i < d; i++)
            p[i] = lbWide[i] + rng.nextDouble () * (ubWide[i] - lbWide[i]);
        clamp (p);
        return p;
    }

    // ================================================================
    //  Seeding obstacle-aware
    // ================================================================

    /**
     * Detecte les murs (obstacles alignes) et genere des seeds
     * qui routent a travers les gaps de chaque mur.
     */
    private void addObstacleAwareSeeds (ArrayList<double []> seeds,
                                         ArrayList<Double> fits,
                                         double sx, double sy, double ex, double ey)
    {
        int nObs = problem.getNObstacles ();
        if (nObs < 3) return;

        // Collecter les obstacles
        double [] ox = new double [nObs], oy = new double [nObs], or_ = new double [nObs];
        for (int i = 0; i < nObs; i++)
        {
            bezier.evaluation.Obstacle o = problem.getObstacle (i);
            ox[i] = o.getX (); oy[i] = o.getY (); or_[i] = o.getRadius ();
        }

        // Detecter murs verticaux ET horizontaux, garder la meilleure orientation
        ArrayList<double []> vWalls = detectWalls (ox, oy, or_, nObs, true);
        ArrayList<double []> hWalls = detectWalls (oy, ox, or_, nObs, false);
        boolean vertical = vWalls.size () >= hWalls.size ();
        ArrayList<double []> walls = vertical ? vWalls : hWalls;

        // Si les deux orientations ont des murs, utiliser celle qui couvre le plus
        // de la trajectoire start→end (mesure par projection sur l'axe principal)
        if (!vWalls.isEmpty () && !hWalls.isEmpty ())
        {
            double dxPath = Math.abs (ex - sx);
            double dyPath = Math.abs (ey - sy);
            vertical = (dxPath >= dyPath) ? (vWalls.size () >= hWalls.size ())
                                          : (vWalls.size () > hWalls.size ());
            walls = vertical ? vWalls : hWalls;
        }

        // Si aucun mur V/H trouve, essayer la detection de murs diagonaux
        if (walls.isEmpty ())
        {
            walls = detectDiagonalWalls (ox, oy, or_, nObs, sx, sy, ex, ey);
            if (!walls.isEmpty ()) vertical = false; // flag special, traitement ci-dessous
        }

        if (walls.isEmpty ()) return;

        // Trier les murs dans le sens start→end pour eviter les zigzags inverses.
        if (vertical)
        {
            if (ex >= sx) walls.sort ((a, b) -> Double.compare (a[0], b[0]));
            else walls.sort ((a, b) -> Double.compare (b[0], a[0]));
        }
        else
        {
            if (ey >= sy) walls.sort ((a, b) -> Double.compare (a[0], b[0]));
            else walls.sort ((a, b) -> Double.compare (b[0], a[0]));
        }

        // Construire les waypoints : start → gap1 → gap2 → ... → end

        double [][] waypoints = new double [walls.size () + 2][2];
        waypoints[0][0] = sx; waypoints[0][1] = sy;
        waypoints[waypoints.length - 1][0] = ex; waypoints[waypoints.length - 1][1] = ey;

        for (int w = 0; w < walls.size (); w++)
        {
            double [] wall = walls.get (w);
            if (wall.length >= 5)
            {
                // Mur diagonal : coords absolues stockees en [3] et [4]
                waypoints[w + 1][0] = wall[3];
                waypoints[w + 1][1] = wall[4];
            }
            else if (vertical)
            { waypoints[w + 1][0] = wall[0]; waypoints[w + 1][1] = wall[1]; }
            else
            { waypoints[w + 1][0] = wall[1]; waypoints[w + 1][1] = wall[0]; }
        }

        // Calculer les distances entre waypoints
        double totalDist = 0.0;
        double [] segDists = new double [waypoints.length - 1];
        for (int s = 0; s < waypoints.length - 1; s++)
        {
            segDists[s] = Math.hypot (waypoints[s+1][0] - waypoints[s][0],
                                      waypoints[s+1][1] - waypoints[s][1]);
            totalDist += segDists[s];
        }
        if (totalDist < 1e-9) totalDist = 1.0;

        // Stocker pour usage ulterieur (rescue, etc.)
        this.detectedWaypoints = waypoints;
        double directDist = Math.hypot (ex - sx, ey - sy);
        this.pathComplexity = (directDist > 1e-9) ? totalDist / directDist : 1.0;

        // Calculer les positions de base des CPs par interpolation
        double [] baseXs = new double [nCP], baseYs = new double [nCP];
        for (int i = 0; i < nCP; i++)
        {
            double t = (double)(i + 1) / (nCP + 1);
            double target = t * totalDist;
            double cumul = 0.0;
            int seg = 0;
            for (seg = 0; seg < segDists.length - 1; seg++)
            {
                if (cumul + segDists[seg] >= target) break;
                cumul += segDists[seg];
            }
            double localT = (segDists[seg] > 1e-9) ? (target - cumul) / segDists[seg] : 0.5;
            baseXs[i] = waypoints[seg][0] + localT * (waypoints[seg+1][0] - waypoints[seg][0]);
            baseYs[i] = waypoints[seg][1] + localT * (waypoints[seg+1][1] - waypoints[seg][1]);
        }

        // Strategie 1 : overshoot (amplifier les deviations)
        double lineY0 = sy, lineDy = ey - sy;
        double lineX0 = sx, lineDx = ex - sx;
        // Overshoot adaptatif : plus le chemin est complexe, plus on compense le lissage Bezier
        double [] baseOvershoots = {1.0, 1.3, 1.5, 1.8};
        double [] extraOvershoots = (pathComplexity > 2.0)
                ? new double [] {2.0, 2.5, 3.0, 3.5}
                : (pathComplexity > 1.5)
                    ? new double [] {2.0, 2.5}
                    : new double [0];
        double [] allOvershoots = new double [baseOvershoots.length + extraOvershoots.length];
        System.arraycopy (baseOvershoots, 0, allOvershoots, 0, baseOvershoots.length);
        System.arraycopy (extraOvershoots, 0, allOvershoots, baseOvershoots.length, extraOvershoots.length);
        for (double overshoot : allOvershoots)
        {
            for (int v = 0; v < 5; v++)
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    double t = (double)(i + 1) / (nCP + 1);
                    double straightX = lineX0 + t * lineDx;
                    double straightY = lineY0 + t * lineDy;
                    double devX = baseXs[i] - straightX;
                    double devY = baseYs[i] - straightY;

                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.5;
                    p[2*i]     = straightX + devX * overshoot + noise;
                    p[2*i + 1] = straightY + devY * overshoot + noise;
                }
                addSeed (seeds, fits, p);
            }
        }

        // Strategie 2 : waypoint-clamp — assigner chaque CP au waypoint
        // le plus proche et placer PLUSIEURS CPs consecutifs au gap.
        // Ca force la courbe Bezier a passer par les gaps car
        // si N CPs consecutifs sont au meme endroit, la courbe y passe.
        int nWalls = walls.size ();
        if (nWalls > 0)
        {
            // Si nCP < nWalls, selectionner les murs les plus critiques (gaps les plus etroits)
            ArrayList<double []> activeWalls = walls;
            double [][] activeWaypoints = waypoints;
            if (nCP < nWalls)
            {
                activeWalls = new ArrayList<> (walls);
                activeWalls.sort ((a, b) -> Double.compare (a[2], b[2])); // trier par taille de gap croissante
                activeWalls = new ArrayList<> (activeWalls.subList (0, Math.min (nCP, activeWalls.size ())));
                // Re-trier dans le sens start→end
                if (vertical)
                {
                    if (ex >= sx) activeWalls.sort ((a, b) -> Double.compare (a[0], b[0]));
                    else activeWalls.sort ((a, b) -> Double.compare (b[0], a[0]));
                }
                else
                {
                    if (ey >= sy) activeWalls.sort ((a, b) -> Double.compare (a[0], b[0]));
                    else activeWalls.sort ((a, b) -> Double.compare (b[0], a[0]));
                }
                // Reconstruire les waypoints avec les murs actifs seulement
                activeWaypoints = new double [activeWalls.size () + 2][2];
                activeWaypoints[0][0] = sx; activeWaypoints[0][1] = sy;
                activeWaypoints[activeWaypoints.length - 1][0] = ex;
                activeWaypoints[activeWaypoints.length - 1][1] = ey;
                for (int w = 0; w < activeWalls.size (); w++)
                {
                    double wallPos = activeWalls.get (w)[0];
                    double gapCenter = activeWalls.get (w)[1];
                    if (vertical)
                    { activeWaypoints[w + 1][0] = wallPos; activeWaypoints[w + 1][1] = gapCenter; }
                    else
                    { activeWaypoints[w + 1][0] = gapCenter; activeWaypoints[w + 1][1] = wallPos; }
                }
            }

            // Repartir les CPs entre les segments waypoint
            int nActiveWalls = activeWalls.size ();
            for (int v = 0; v < 10; v++)
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    // Determiner a quel waypoint ce CP est le plus lie
                    double t = (double)(i + 1) / (nCP + 1);
                    int wpIdx = (int) Math.round (t * (activeWaypoints.length - 1));
                    wpIdx = Math.max (0, Math.min (wpIdx, activeWaypoints.length - 1));

                    double targetX = activeWaypoints[wpIdx][0];
                    double targetY = activeWaypoints[wpIdx][1];

                    // Petit offset en x pour que les CPs ne soient pas empiles
                    double cpX = targetX + (t - (double) wpIdx / (activeWaypoints.length - 1))
                                           * (ex - sx) * 0.3;

                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 2.0;
                    p[2*i]     = cpX + noise;
                    p[2*i + 1] = targetY + noise;
                }
                addSeed (seeds, fits, p);
            }
        }

        // Strategie 3 : clustering adaptatif aux virages
        addClusteredTurnSeeds (seeds, fits, waypoints);

        // Strategie 4 : spline Hermite lisse a travers les waypoints
        addHermiteSplineSeeds (seeds, fits, waypoints);
    }

    /**
     * Clustering adaptatif : concentre les CPs aux virages serres.
     * Plus l'angle est aigu, plus on y place de CPs.
     */
    private void addClusteredTurnSeeds (ArrayList<double []> seeds,
                                         ArrayList<Double> fits,
                                         double [][] waypoints)
    {
        int nWp = waypoints.length;
        if (nWp < 3) return; // pas de virage

        // Calculer l'angle de virage a chaque waypoint interieur
        double [] turnAngles = new double [nWp];
        double totalAngle = 0.0;
        for (int w = 1; w < nWp - 1; w++)
        {
            double ax = waypoints[w][0] - waypoints[w-1][0];
            double ay = waypoints[w][1] - waypoints[w-1][1];
            double bx = waypoints[w+1][0] - waypoints[w][0];
            double by = waypoints[w+1][1] - waypoints[w][1];
            double magA = Math.hypot (ax, ay);
            double magB = Math.hypot (bx, by);
            if (magA < 1e-9 || magB < 1e-9) continue;
            double cos = (ax * bx + ay * by) / (magA * magB);
            cos = Math.max (-1.0, Math.min (1.0, cos));
            turnAngles[w] = Math.PI - Math.acos (cos); // 0 = tout droit, PI = demi-tour
            totalAngle += turnAngles[w];
        }
        if (totalAngle < 0.1) return; // virages negligeables

        // Assigner un poids a chaque segment et virage
        // Segments droits : poids = longueur normalisee
        // Virages : poids = angle normalise (pondere 3x pour forcer le clustering)
        double totalLen = 0.0;
        double [] segLen = new double [nWp - 1];
        for (int s = 0; s < nWp - 1; s++)
        {
            segLen[s] = Math.hypot (waypoints[s+1][0] - waypoints[s][0],
                                    waypoints[s+1][1] - waypoints[s][1]);
            totalLen += segLen[s];
        }
        if (totalLen < 1e-9) return;

        // Construire une liste de "slots" : segments + virages
        // Chaque slot a un poids qui determine combien de CPs y sont assignes
        int nSlots = 2 * (nWp - 1) - 1; // segments + virages intercales
        double [] slotWeights = new double [nSlots];
        double totalWeight = 0.0;
        for (int s = 0; s < nWp - 1; s++)
        {
            int si = 2 * s;
            slotWeights[si] = segLen[s] / totalLen; // poids segment
            totalWeight += slotWeights[si];
            if (s < nWp - 2)
            {
                int ti = 2 * s + 1;
                slotWeights[ti] = 3.0 * turnAngles[s + 1] / Math.PI; // poids virage
                totalWeight += slotWeights[ti];
            }
        }
        if (totalWeight < 1e-9) return;

        // Normaliser et distribuer les CPs
        int [] cpPerSlot = new int [nSlots];
        int assigned = 0;
        for (int s = 0; s < nSlots; s++)
        {
            cpPerSlot[s] = (int) Math.round ((slotWeights[s] / totalWeight) * nCP);
            assigned += cpPerSlot[s];
        }
        // Corriger les arrondis
        while (assigned < nCP) { cpPerSlot[0]++; assigned++; }
        while (assigned > nCP) { for (int s = nSlots - 1; s >= 0 && assigned > nCP; s--)
            { if (cpPerSlot[s] > 0) { cpPerSlot[s]--; assigned--; } } }

        // Generer les seeds
        for (int v = 0; v < 15; v++)
        {
            double [] p = new double [d];
            int cpIdx = 0;
            for (int s = 0; s < nSlots && cpIdx < nCP; s++)
            {
                boolean isTurn = (s % 2 == 1);
                int wpBefore = s / 2;
                int wpAfter = wpBefore + 1;

                for (int c = 0; c < cpPerSlot[s] && cpIdx < nCP; c++)
                {
                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * (0.5 + v * 0.3);
                    if (isTurn)
                    {
                        int wpTurn = wpAfter; // waypoint du virage
                        // Direction entrante et sortante normalisees
                        double inX = waypoints[wpTurn][0] - waypoints[wpTurn-1][0];
                        double inY = waypoints[wpTurn][1] - waypoints[wpTurn-1][1];
                        double outX = waypoints[wpTurn+1][0] - waypoints[wpTurn][0];
                        double outY = waypoints[wpTurn+1][1] - waypoints[wpTurn][1];
                        double inMag = Math.hypot (inX, inY);
                        double outMag = Math.hypot (outX, outY);
                        if (inMag > 1e-9) { inX /= inMag; inY /= inMag; }
                        if (outMag > 1e-9) { outX /= outMag; outY /= outMag; }

                        // Offset le long de la direction entrante/sortante
                        double spread = 1.5;
                        double frac = (cpPerSlot[s] == 1) ? 0.0
                                : (double) c / (cpPerSlot[s] - 1) * 2.0 - 1.0; // -1 a +1
                        double dirX = (frac < 0) ? inX : outX;
                        double dirY = (frac < 0) ? inY : outY;
                        p[2 * cpIdx] = waypoints[wpTurn][0] + Math.abs (frac) * spread * dirX + noise;
                        p[2 * cpIdx + 1] = waypoints[wpTurn][1] + Math.abs (frac) * spread * dirY + noise;
                    }
                    else
                    {
                        // CP sur segment droit : repartition lineaire
                        double frac = (cpPerSlot[s] <= 1) ? 0.5
                                : (double) c / (cpPerSlot[s] - 1);
                        // Petit offset pour ne pas etre exactement au waypoint
                        double tt = 0.1 + frac * 0.8;
                        p[2 * cpIdx] = waypoints[wpBefore][0]
                                + tt * (waypoints[wpAfter][0] - waypoints[wpBefore][0]) + noise;
                        p[2 * cpIdx + 1] = waypoints[wpBefore][1]
                                + tt * (waypoints[wpAfter][1] - waypoints[wpBefore][1]) + noise;
                    }
                    cpIdx++;
                }
            }
            repelControlPointsFromObstacles (p, 2);
            addSeed (seeds, fits, p);
        }
    }

    /**
     * Seeding par spline Hermite cubique lisse a travers les waypoints.
     * Genere des CPs echantillonnes le long d'une courbe lisse
     * qui minimise la courbure aux virages.
     */
    private void addHermiteSplineSeeds (ArrayList<double []> seeds,
                                         ArrayList<Double> fits,
                                         double [][] waypoints)
    {
        int nWp = waypoints.length;
        if (nWp < 3) return;

        // Calculer les tangentes a chaque waypoint (Catmull-Rom style)
        double [][] tangents = new double [nWp][2];
        for (int w = 0; w < nWp; w++)
        {
            if (w == 0)
            {
                tangents[w][0] = waypoints[1][0] - waypoints[0][0];
                tangents[w][1] = waypoints[1][1] - waypoints[0][1];
            }
            else if (w == nWp - 1)
            {
                tangents[w][0] = waypoints[nWp-1][0] - waypoints[nWp-2][0];
                tangents[w][1] = waypoints[nWp-1][1] - waypoints[nWp-2][1];
            }
            else
            {
                // Bisectrice des directions entrante/sortante
                tangents[w][0] = (waypoints[w+1][0] - waypoints[w-1][0]) * 0.5;
                tangents[w][1] = (waypoints[w+1][1] - waypoints[w-1][1]) * 0.5;
            }
        }

        // Varier l'amplitude des tangentes pour explorer differentes rondeurs
        for (double tangentScale : new double [] {0.3, 0.6, 1.0, 1.5, 2.0})
        {
            for (int v = 0; v < 2; v++)
            {
                // Calculer la longueur totale de la spline (approximation par echantillonnage)
                int samplesPerSeg = 20;
                double totalLen = 0.0;
                double [] cumLen = new double [samplesPerSeg * (nWp - 1) + 1];
                double [] sampX = new double [cumLen.length];
                double [] sampY = new double [cumLen.length];
                int idx = 0;
                for (int seg = 0; seg < nWp - 1; seg++)
                {
                    for (int k = 0; k < samplesPerSeg; k++)
                    {
                        double t = (double) k / samplesPerSeg;
                        double [] pt = hermitePoint (waypoints[seg], waypoints[seg+1],
                                tangents[seg], tangents[seg+1], tangentScale, t);
                        sampX[idx] = pt[0];
                        sampY[idx] = pt[1];
                        if (idx > 0)
                            totalLen += Math.hypot (sampX[idx] - sampX[idx-1],
                                                    sampY[idx] - sampY[idx-1]);
                        cumLen[idx] = totalLen;
                        idx++;
                    }
                }
                // Dernier point
                sampX[idx] = waypoints[nWp-1][0];
                sampY[idx] = waypoints[nWp-1][1];
                if (idx > 0)
                    totalLen += Math.hypot (sampX[idx] - sampX[idx-1],
                                            sampY[idx] - sampY[idx-1]);
                cumLen[idx] = totalLen;
                idx++;
                int totalSamples = idx;

                if (totalLen < 1e-9) continue;

                // Echantillonner nCP CPs a intervalles d'arc-length uniformes
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    double target = ((double) (i + 1) / (nCP + 1)) * totalLen;
                    int si = 1;
                    while (si < totalSamples && cumLen[si] < target) si++;
                    si = Math.max (1, Math.min (si, totalSamples - 1));
                    double denom = Math.max (1e-9, cumLen[si] - cumLen[si-1]);
                    double a = (target - cumLen[si-1]) / denom;
                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.5;
                    p[2 * i] = (1.0 - a) * sampX[si-1] + a * sampX[si] + noise;
                    p[2 * i + 1] = (1.0 - a) * sampY[si-1] + a * sampY[si] + noise;
                }
                repelControlPointsFromObstacles (p, 2);
                addSeed (seeds, fits, p);
            }
        }
    }

    /** Evaluation d'un point sur un segment de spline Hermite cubique. */
    private static double [] hermitePoint (double [] p0, double [] p1,
                                            double [] m0, double [] m1,
                                            double scale, double t)
    {
        double t2 = t * t;
        double t3 = t2 * t;
        double h00 = 2*t3 - 3*t2 + 1;
        double h10 = t3 - 2*t2 + t;
        double h01 = -2*t3 + 3*t2;
        double h11 = t3 - t2;
        return new double [] {
            h00 * p0[0] + h10 * m0[0] * scale + h01 * p1[0] + h11 * m1[0] * scale,
            h00 * p0[1] + h10 * m0[1] * scale + h01 * p1[1] + h11 * m1[1] * scale
        };
    }

    /**
     * Genere des seeds de contournement pour les obstacles qui bloquent le chemin direct.
     * Fonctionne pour TOUTE configuration d'obstacles (y compris isolees, gros rayon, etc.).
     * Strategie : detecter les obstacles sur le segment start→end, calculer des waypoints
     * de contournement (passer au-dessus/en-dessous de chaque obstacle), puis interpoler les CPs.
     */
    private void addDetourSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                  double sx, double sy, double ex, double ey)
    {
        if (nObs == 0) return;

        // Trouver les obstacles qui intersectent le segment start→end
        // (distance du centre au segment <= rayon + marge de securite)
        double dx = ex - sx, dy = ey - sy;
        double segLen = Math.hypot (dx, dy);
        if (segLen < 1e-9) return;
        double nx = -dy / segLen, ny = dx / segLen; // normale au segment

        ArrayList<int[]> blocking = new ArrayList<> (); // [index, side] side: +1 ou -1
        for (int i = 0; i < nObs; i++)
        {
            // Projection du centre obstacle sur le segment
            double px = obsX[i] - sx, py = obsY[i] - sy;
            double t = (px * dx + py * dy) / (segLen * segLen);
            t = Math.max (0.0, Math.min (1.0, t));
            double closestX = sx + t * dx, closestY = sy + t * dy;
            double dist = Math.hypot (obsX[i] - closestX, obsY[i] - closestY);
            if (dist < obsR[i] + 1.5) // obstacle bloque ou presque
                blocking.add (new int [] {i});
        }
        if (blocking.isEmpty ()) return;

        // Trier les obstacles bloquants par leur position le long du segment
        blocking.sort ((a, b) -> {
            double ta = ((obsX[a[0]] - sx) * dx + (obsY[a[0]] - sy) * dy) / (segLen * segLen);
            double tb = ((obsX[b[0]] - sx) * dx + (obsY[b[0]] - sy) * dy) / (segLen * segLen);
            return Double.compare (ta, tb);
        });

        // Pour chaque combinaison de cotes (passer a gauche ou a droite de chaque obstacle),
        // generer un set de waypoints de contournement.
        // Limiter a 2^min(nBlocking, 4) combinaisons pour eviter l'explosion.
        int nBlock = blocking.size ();
        int maxBits = Math.min (nBlock, 4);
        int nCombos = 1 << maxBits;

        for (int combo = 0; combo < nCombos; combo++)
        {
            // Construire les waypoints : start → detours → end
            ArrayList<double []> waypoints = new ArrayList<> ();
            waypoints.add (new double [] {sx, sy});

            for (int b = 0; b < nBlock; b++)
            {
                int idx = blocking.get (b)[0];
                int side = ((combo >> (b % maxBits)) & 1) == 0 ? 1 : -1;

                // Point de contournement : centre obstacle + offset perpendiculaire
                double clearance = obsR[idx] + 1.5; // passer hors de la soft zone
                double wpX = obsX[idx] + side * nx * clearance;
                double wpY = obsY[idx] + side * ny * clearance;

                // Clamper dans le domaine
                wpX = Math.max (problem.getMinX () + 0.5, Math.min (problem.getMaxX () - 0.5, wpX));
                wpY = Math.max (problem.getMinY () + 0.5, Math.min (problem.getMaxY () - 0.5, wpY));

                waypoints.add (new double [] {wpX, wpY});
            }
            waypoints.add (new double [] {ex, ey});

            // Interpoler les CPs le long des waypoints
            double totalDist = 0.0;
            double [] segDists = new double [waypoints.size () - 1];
            for (int s = 0; s < waypoints.size () - 1; s++)
            {
                segDists[s] = Math.hypot (waypoints.get(s+1)[0] - waypoints.get(s)[0],
                                           waypoints.get(s+1)[1] - waypoints.get(s)[1]);
                totalDist += segDists[s];
            }
            if (totalDist < 1e-9) continue;

            double [] p = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i + 1) / (nCP + 1);
                double target = t * totalDist;
                double cumul = 0.0;
                int seg = 0;
                for (seg = 0; seg < segDists.length - 1; seg++)
                {
                    if (cumul + segDists[seg] >= target) break;
                    cumul += segDists[seg];
                }
                double localT = (segDists[seg] > 1e-9) ? (target - cumul) / segDists[seg] : 0.5;
                p[2*i]     = waypoints.get(seg)[0] + localT * (waypoints.get(seg+1)[0] - waypoints.get(seg)[0]);
                p[2*i + 1] = waypoints.get(seg)[1] + localT * (waypoints.get(seg+1)[1] - waypoints.get(seg)[1]);
            }
            addSeed (seeds, fits, p);

            // Variante avec overshoot (amplifier le contournement)
            for (double overshoot : new double [] {1.3, 1.6, 2.0})
            {
                double [] q = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    double t = (double)(i + 1) / (nCP + 1);
                    double straightX = sx + t * dx;
                    double straightY = sy + t * dy;
                    q[2*i]     = straightX + (p[2*i] - straightX) * overshoot;
                    q[2*i + 1] = straightY + (p[2*i+1] - straightY) * overshoot;
                }
                addSeed (seeds, fits, q);
            }

            // Variante avec bruit
            for (int v = 0; v < 3; v++)
            {
                double [] q = p.clone ();
                for (int i = 0; i < d; i++)
                    q[i] += rng.nextGaussian () * 1.5;
                addSeed (seeds, fits, q);
            }
        }

        // Aussi stocker les waypoints du meilleur combo comme detectedWaypoints si pas deja set
        if (detectedWaypoints == null && nBlock > 0)
        {
            // Utiliser combo 0 (tout d'un meme cote) comme reference
            ArrayList<double []> wp0 = new ArrayList<> ();
            wp0.add (new double [] {sx, sy});
            for (int b = 0; b < nBlock; b++)
            {
                int idx = blocking.get (b)[0];
                double clearance = obsR[idx] + 1.5;
                double wpX = obsX[idx] + nx * clearance;
                double wpY = obsY[idx] + ny * clearance;
                wpX = Math.max (problem.getMinX () + 0.5, Math.min (problem.getMaxX () - 0.5, wpX));
                wpY = Math.max (problem.getMinY () + 0.5, Math.min (problem.getMaxY () - 0.5, wpY));
                wp0.add (new double [] {wpX, wpY});
            }
            wp0.add (new double [] {ex, ey});
            this.detectedWaypoints = wp0.toArray (new double [0][]);
            double directDist = Math.hypot (ex - sx, ey - sy);
            double totalD = 0;
            for (int s = 0; s < detectedWaypoints.length - 1; s++)
                totalD += Math.hypot (detectedWaypoints[s+1][0] - detectedWaypoints[s][0],
                                       detectedWaypoints[s+1][1] - detectedWaypoints[s][1]);
            this.pathComplexity = (directDist > 1e-9) ? totalD / directDist : 1.0;
        }
    }

    /**
     * Detecte les murs : groupes de 3+ obstacles alignes sur une meme coordonnee.
     * Retourne pour chaque mur [position_du_mur, centre_du_plus_grand_gap].
     *
     * @param primary coordonnee d'alignement (x pour murs verticaux, y pour horizontaux)
     * @param secondary coordonnee perpendiculaire
     * @param radii rayons des obstacles
     * @param n nombre d'obstacles
     * @param isVertical true si on cherche des murs verticaux
     */
    private ArrayList<double []> detectWalls (double [] primary, double [] secondary,
                                               double [] radii, int n, boolean isVertical)
    {
        ArrayList<double []> walls = new ArrayList<> ();

        // Grouper par primary avec tolerance
        boolean [] used = new boolean [n];
        for (int i = 0; i < n; i++)
        {
            if (used[i]) continue;
            double tol = 1.5 * radii[i];
            ArrayList<Integer> group = new ArrayList<> ();
            group.add (i);
            used[i] = true;
            for (int j = i + 1; j < n; j++)
            {
                if (!used[j] && Math.abs (primary[j] - primary[i]) < tol)
                { group.add (j); used[j] = true; }
            }

            if (group.size () < 3) continue; // Pas un mur

            // Position moyenne du mur
            double wallPos = 0.0;
            for (int idx : group) wallPos += primary[idx];
            wallPos /= group.size ();

            // Trouver le plus grand gap le long de secondary
            double domMin = isVertical ? problem.getMinY () : problem.getMinX ();
            double domMax = isVertical ? problem.getMaxY () : problem.getMaxX ();

            // Collecter les intervalles bloques [sec - r, sec + r]
            ArrayList<double []> blocked = new ArrayList<> ();
            for (int idx : group)
                blocked.add (new double [] {secondary[idx] - radii[idx] - 1.0,
                                            secondary[idx] + radii[idx] + 1.0});
            blocked.sort ((a, b) -> Double.compare (a[0], b[0]));

            // Fusionner les intervalles
            ArrayList<double []> merged = new ArrayList<> ();
            for (double [] iv : blocked)
            {
                if (!merged.isEmpty () && iv[0] <= merged.get (merged.size () - 1)[1])
                    merged.get (merged.size () - 1)[1] = Math.max (merged.get (merged.size () - 1)[1], iv[1]);
                else
                    merged.add (new double [] {iv[0], iv[1]});
            }

            // Trouver le plus grand gap
            double bestGapCenter = (domMin + domMax) / 2.0;
            double bestGapSize = 0.0;

            // Gap avant le premier intervalle
            if (!merged.isEmpty () && merged.get (0)[0] > domMin)
            {
                double sz = merged.get (0)[0] - domMin;
                if (sz > bestGapSize) { bestGapSize = sz; bestGapCenter = domMin + sz / 2.0; }
            }
            // Gaps entre intervalles
            for (int k = 0; k < merged.size () - 1; k++)
            {
                double gStart = merged.get (k)[1];
                double gEnd = merged.get (k + 1)[0];
                double sz = gEnd - gStart;
                if (sz > bestGapSize) { bestGapSize = sz; bestGapCenter = (gStart + gEnd) / 2.0; }
            }
            // Gap apres le dernier intervalle
            if (!merged.isEmpty () && merged.get (merged.size () - 1)[1] < domMax)
            {
                double sz = domMax - merged.get (merged.size () - 1)[1];
                if (sz > bestGapSize) { bestGapSize = sz; bestGapCenter = merged.get (merged.size () - 1)[1] + sz / 2.0; }
            }

            walls.add (new double [] {wallPos, bestGapCenter, bestGapSize});
        }

        return walls;
    }

    /**
     * Detecte les murs diagonaux : groupes de 3+ obstacles alignes sur un axe arbitraire.
     * Retourne des pseudo-waypoints compatibles avec le pipeline obstacle-aware.
     * Chaque entree : [projectionOnPath, gapCenterPerp, gapSize].
     * Note : les waypoints sont deja en coordonnees (x,y) dans le tableau retourne,
     * donc le code appelant doit utiliser walls[i][0]=x, walls[i][1]=y directement.
     */
    private ArrayList<double []> detectDiagonalWalls (double [] ox, double [] oy,
                                                       double [] radii, int n,
                                                       double sx, double sy,
                                                       double ex, double ey)
    {
        ArrayList<double []> result = new ArrayList<> ();
        if (n < 3) return result;

        // Chercher des groupes de 3+ obstacles alignes selon un axe quelconque.
        // Pour chaque paire (i,j), verifier combien d'autres obstacles sont proches de la ligne i-j.
        double bestScore = 0;
        ArrayList<Integer> bestGroup = null;
        double bestAngle = 0;

        boolean [] usedInBest = new boolean [n];

        for (int i = 0; i < n && i < 30; i++) // limiter la complexite
        {
            for (int j = i + 1; j < n && j < 30; j++)
            {
                double dx = ox[j] - ox[i];
                double dy = oy[j] - oy[i];
                double len = Math.hypot (dx, dy);
                if (len < 1e-9) continue;
                double ux = dx / len, uy = dy / len; // direction du mur
                double nx = -uy, ny = ux; // normale au mur

                // Trouver tous les obstacles proches de cette ligne
                ArrayList<Integer> group = new ArrayList<> ();
                for (int k = 0; k < n; k++)
                {
                    double perpDist = Math.abs ((ox[k] - ox[i]) * nx + (oy[k] - oy[i]) * ny);
                    double tol = Math.max (radii[k] * 1.5, 2.0);
                    if (perpDist < tol) group.add (k);
                }

                if (group.size () >= 3 && group.size () > bestScore)
                {
                    bestScore = group.size ();
                    bestGroup = group;
                    bestAngle = Math.atan2 (uy, ux);
                }
            }
        }

        if (bestGroup == null || bestGroup.size () < 3) return result;

        // Calculer le centre et la direction du mur
        double cx = 0, cy = 0;
        for (int idx : bestGroup) { cx += ox[idx]; cy += oy[idx]; }
        cx /= bestGroup.size (); cy /= bestGroup.size ();

        double ux = Math.cos (bestAngle), uy = Math.sin (bestAngle);
        double nx = -uy, ny = ux; // normale

        // Projeter les obstacles du groupe sur la direction du mur pour trouver l'etendue
        double minProj = Double.POSITIVE_INFINITY, maxProj = Double.NEGATIVE_INFINITY;
        for (int idx : bestGroup)
        {
            double proj = (ox[idx] - cx) * ux + (oy[idx] - cy) * uy;
            minProj = Math.min (minProj, proj - radii[idx] - 1.0);
            maxProj = Math.max (maxProj, proj + radii[idx] + 1.0);
        }

        // Trouver le gap dans la direction perpendiculaire a la trajectoire start→end
        // On projette les obstacles sur la normale du mur et cherche le plus grand espace
        double domMin = Math.min (
                Math.min (problem.getMinX (), problem.getMinY ()),
                Math.min (sx, ex)) - 2.0;
        double domMax = Math.max (
                Math.max (problem.getMaxX (), problem.getMaxY ()),
                Math.max (sy, ey)) + 2.0;

        // Projeter start et end sur la normale pour savoir de quel cote passer
        double startPerp = (sx - cx) * nx + (sy - cy) * ny;
        double endPerp = (ex - cx) * nx + (ey - cy) * ny;

        // Calculer les intervalles bloques perpendiculairement au mur
        ArrayList<double []> blocked = new ArrayList<> ();
        for (int idx : bestGroup)
        {
            double perpProj = (ox[idx] - cx) * nx + (oy[idx] - cy) * ny;
            blocked.add (new double [] {perpProj - radii[idx] - 1.0,
                                         perpProj + radii[idx] + 1.0});
        }
        blocked.sort ((a, b) -> Double.compare (a[0], b[0]));

        // Fusionner
        ArrayList<double []> merged = new ArrayList<> ();
        for (double [] iv : blocked)
        {
            if (!merged.isEmpty () && iv[0] <= merged.get (merged.size () - 1)[1])
                merged.get (merged.size () - 1)[1] = Math.max (merged.get (merged.size () - 1)[1], iv[1]);
            else
                merged.add (new double [] {iv[0], iv[1]});
        }

        // Trouver le meilleur gap (le plus accessible depuis start et end)
        double bestGapPerp = 0.0;
        double bestGapSize = 0.0;

        // Gaps entre intervalles
        for (int k = 0; k < merged.size () - 1; k++)
        {
            double gStart = merged.get (k)[1];
            double gEnd = merged.get (k + 1)[0];
            double sz = gEnd - gStart;
            if (sz > bestGapSize)
            {
                bestGapSize = sz;
                bestGapPerp = (gStart + gEnd) / 2.0;
            }
        }
        // Gaps aux extremites (passer par dessus/dessous le mur)
        if (!merged.isEmpty ())
        {
            double aboveGap = 10.0; // taille estimee au-dessus
            double belowGap = 10.0; // taille estimee en-dessous
            double aboveCenter = merged.get (merged.size () - 1)[1] + 3.0;
            double belowCenter = merged.get (0)[0] - 3.0;
            if (aboveGap > bestGapSize)
            {
                bestGapSize = aboveGap;
                bestGapPerp = aboveCenter;
            }
        }

        if (bestGapSize < 0.5) return result; // pas de gap exploitable

        // Construire le waypoint : le point de passage du gap
        double gapX = cx + bestGapPerp * nx;
        double gapY = cy + bestGapPerp * ny;

        // Retourner comme un pseudo-mur avec waypoint en coords absolues.
        // Le format est [wallPos, gapCenter, gapSize] mais ici on stocke directement
        // les coords x,y du gap car le mur est diagonal.
        // On utilise une convention speciale : wallPos = projection sur l'axe path.
        double pathDx = ex - sx, pathDy = ey - sy;
        double pathLen = Math.hypot (pathDx, pathDy);
        if (pathLen < 1e-9) return result;
        double projOnPath = ((gapX - sx) * pathDx + (gapY - sy) * pathDy) / pathLen;

        result.add (new double [] {projOnPath, 0.0, bestGapSize, gapX, gapY});
        return result;
    }

    /**
     * Seed deterministe par planification A* sur une grille.
     * Objectif: fournir au moins une trajectoire faisable "de secours".
     */
    private void addGridPlannerSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                      double sx, double sy, double ex, double ey)
    {
        // Resolution adaptative : plus fine quand les gaps entre obstacles sont etroits
        double minGap = estimateMinGap ();
        double domainSize = Math.max (problem.getMaxX () - problem.getMinX (),
                                       problem.getMaxY () - problem.getMinY ());
        int adaptiveGrid = (minGap > 0.5)
                ? (int) (domainSize / minGap * 4)
                : 24 + nObs;
        int grid = Math.max (40, Math.min (160, adaptiveGrid));
        double minX = problem.getMinX (), maxX = problem.getMaxX ();
        double minY = problem.getMinY (), maxY = problem.getMaxY ();
        double stepX = (maxX - minX) / Math.max (1, grid - 1);
        double stepY = (maxY - minY) / Math.max (1, grid - 1);

        boolean [] blocked = new boolean [grid * grid];
        double inflate = 0.8;
        for (int gy = 0; gy < grid; gy++)
        {
            double y = minY + gy * stepY;
            for (int gx = 0; gx < grid; gx++)
            {
                double x = minX + gx * stepX;
                int id = gy * grid + gx;
                blocked[id] = false;
                for (int j = 0; j < nObs; j++)
                {
                    double dx = x - obsX[j];
                    double dy = y - obsY[j];
                    double rr = obsR[j] + inflate;
                    if (dx * dx + dy * dy <= rr * rr)
                    {
                        blocked[id] = true;
                        break;
                    }
                }
            }
        }

        int sxi = (int) Math.round ((sx - minX) / Math.max (1e-9, stepX));
        int syi = (int) Math.round ((sy - minY) / Math.max (1e-9, stepY));
        int exi = (int) Math.round ((ex - minX) / Math.max (1e-9, stepX));
        int eyi = (int) Math.round ((ey - minY) / Math.max (1e-9, stepY));
        sxi = Math.max (0, Math.min (grid - 1, sxi));
        syi = Math.max (0, Math.min (grid - 1, syi));
        exi = Math.max (0, Math.min (grid - 1, exi));
        eyi = Math.max (0, Math.min (grid - 1, eyi));

        int start = syi * grid + sxi;
        int goal = eyi * grid + exi;
        blocked[start] = false;
        blocked[goal] = false;

        int n = grid * grid;
        double [] gScore = new double [n];
        int [] parent = new int [n];
        boolean [] closed = new boolean [n];
        for (int i = 0; i < n; i++)
        {
            gScore[i] = Double.POSITIVE_INFINITY;
            parent[i] = -1;
            closed[i] = false;
        }

        class Node
        {
            final int id;
            final double f;
            Node (int id, double f) { this.id = id; this.f = f; }
        }
        PriorityQueue<Node> open = new PriorityQueue<> ((a, b) -> Double.compare (a.f, b.f));
        gScore[start] = 0.0;
        open.add (new Node (start, heuristic (sxi, syi, exi, eyi)));

        int [] dxs = {-1, 0, 1, -1, 1, -1, 0, 1};
        int [] dys = {-1, -1, -1, 0, 0, 1, 1, 1};
        while (!open.isEmpty ())
        {
            Node cur = open.poll ();
            int id = cur.id;
            if (closed[id]) continue;
            closed[id] = true;
            if (id == goal) break;

            int cx = id % grid;
            int cy = id / grid;
            for (int k = 0; k < 8; k++)
            {
                int nx = cx + dxs[k];
                int ny = cy + dys[k];
                if (nx < 0 || nx >= grid || ny < 0 || ny >= grid) continue;
                int nid = ny * grid + nx;
                if (blocked[nid] || closed[nid]) continue;
                double w = Math.hypot (dxs[k], dys[k]);
                double cand = gScore[id] + w;
                if (cand + 1e-12 < gScore[nid])
                {
                    gScore[nid] = cand;
                    parent[nid] = id;
                    double f = cand + heuristic (nx, ny, exi, eyi);
                    open.add (new Node (nid, f));
                }
            }
        }

        if (parent[goal] == -1) return;

        ArrayList<double []> path = new ArrayList<> ();
        int cur = goal;
        while (cur != -1)
        {
            int gx = cur % grid;
            int gy = cur / grid;
            path.add (0, new double [] {minX + gx * stepX, minY + gy * stepY});
            cur = parent[cur];
        }
        if (path.size () < 2) return;

        double [] cumul = new double [path.size ()];
        cumul[0] = 0.0;
        for (int i = 1; i < path.size (); i++)
            cumul[i] = cumul[i - 1] + Math.hypot (path.get(i)[0] - path.get(i - 1)[0],
                    path.get(i)[1] - path.get(i - 1)[1]);
        double total = cumul[path.size () - 1];
        if (total < 1e-9) return;

        double [] p = new double [d];
        for (int i = 0; i < nCP; i++)
        {
            double target = ((double) (i + 1) / (nCP + 1)) * total;
            int seg = 1;
            while (seg < cumul.length && cumul[seg] < target) seg++;
            seg = Math.max (1, Math.min (seg, cumul.length - 1));
            double den = Math.max (1e-9, cumul[seg] - cumul[seg - 1]);
            double a = (target - cumul[seg - 1]) / den;
            double x = (1.0 - a) * path.get(seg - 1)[0] + a * path.get(seg)[0];
            double y = (1.0 - a) * path.get(seg - 1)[1] + a * path.get(seg)[1];
            p[2 * i] = x;
            p[2 * i + 1] = y;
        }
        repelControlPointsFromObstacles (p, 3);
        this.aStarSeed = p.clone ();
        addSeed (seeds, fits, p);

        // Variantes legeres autour de la trajectoire A*.
        for (int v = 0; v < 12; v++)
        {
            double [] q = p.clone ();
            double noise = domainDiag * (0.004 + 0.003 * v);
            for (int i = 0; i < d; i++)
                q[i] += rng.nextGaussian () * noise;
            repelControlPointsFromObstacles (q, 3);
            addSeed (seeds, fits, q);
        }

        // Variante A* avec spline Hermite : detecter les virages dans le path A*
        // et utiliser la meme strategie de spline lisse
        if (path.size () >= 4)
        {
            // Simplifier le path A* en waypoints (start, virages significatifs, end)
            ArrayList<double []> keyPoints = new ArrayList<> ();
            keyPoints.add (path.get (0));
            for (int i = 1; i < path.size () - 1; i++)
            {
                double ax = path.get(i)[0] - path.get(i-1)[0];
                double ay = path.get(i)[1] - path.get(i-1)[1];
                double bx = path.get(i+1)[0] - path.get(i)[0];
                double by = path.get(i+1)[1] - path.get(i)[1];
                double magA = Math.hypot (ax, ay);
                double magB = Math.hypot (bx, by);
                if (magA > 1e-9 && magB > 1e-9)
                {
                    double cos = (ax * bx + ay * by) / (magA * magB);
                    if (cos < 0.85) // virage significatif (> ~30 deg)
                        keyPoints.add (path.get (i));
                }
            }
            keyPoints.add (path.get (path.size () - 1));

            if (keyPoints.size () >= 3)
            {
                double [][] kpArr = keyPoints.toArray (new double [0][]);
                addHermiteSplineSeeds (seeds, fits, kpArr);
            }
        }
    }

    private static double heuristic (int x, int y, int tx, int ty)
    {
        return Math.hypot (x - tx, y - ty);
    }

    // ================================================================
    //  Helpers
    // ================================================================

    /**
     * Estime le plus petit gap entre obstacles proches.
     * Retourne la distance minimale bord-a-bord entre paires d'obstacles
     * dont les centres sont a moins de 2*(r1+r2) l'un de l'autre.
     */
    private double estimateMinGap ()
    {
        double minGap = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nObs; i++)
        {
            for (int j = i + 1; j < nObs; j++)
            {
                double dist = Math.hypot (obsX[i] - obsX[j], obsY[i] - obsY[j]);
                double sumR = obsR[i] + obsR[j];
                // Ne considerer que les obstacles "proches" (potentiellement un mur)
                if (dist < 2.0 * sumR)
                {
                    double gap = dist - sumR;
                    if (gap > 0 && gap < minGap) minGap = gap;
                }
            }
        }
        return Double.isFinite (minGap) ? minGap : 0.0;
    }

    private double [] makePath (double x, double y)
    { double[]p=new double[d]; for(int i=0;i<nCP;i++){p[2*i]=x;p[2*i+1]=y;} return p; }

    private void clamp (double [] p)
    { for(int i=0;i<d;i++){if(p[i]<lbWide[i])p[i]=lbWide[i];if(p[i]>ubWide[i])p[i]=ubWide[i];} }

    private void maybeRescueFeasibility ()
    {
        long now = System.currentTimeMillis ();
        if (!cmaesReady) return;
        // Demarrer le rescue plus tot quand le probleme est complexe
        long rescueStart = (pathComplexity > 1.5) ? RESCUE_START_MS / 2 : RESCUE_START_MS;
        if (now - startTime < rescueStart) return;

        boolean noFeasible = (bestFeasibleX == null);
        boolean stalled = (now - lastGlobalImproveMs) > 5_000L;
        // Aussi considerer comme stalled si la meilleure solution faisable a un score
        // bien pire que la longueur estimee du chemin (penalites probables)
        boolean poorQuality = (bestFeasibleFitness > 3.0 * domainDiag);
        if (!noFeasible && !stalled && !poorQuality) return;
        long period = noFeasible ? 900L : RESCUE_PERIOD_MS;
        if (pathComplexity > 1.5) period = (long) (period / Math.min (pathComplexity, 2.0));
        if (lastRescueBurstMs != 0L && now - lastRescueBurstMs < period) return;

        double [] base = bestFeasibleX != null ? bestFeasibleX
                : (globalBestX != null ? globalBestX : defaultMean ());
        long elapsed = now - startTime;
        int tries = noFeasible ? 220 : RESCUE_BURST_TRIES;
        if (noFeasible && elapsed > 25_000L) tries = 600;
        // Adapter le nombre de tries a la complexite du probleme (cap a 2x)
        if (pathComplexity > 1.3)
            tries = (int) (tries * Math.min (pathComplexity, 2.0));

        for (int k = 0; k < tries; k++)
        {
            double [] p;

            // Rescue structure via waypoints detectes (1 sur 3)
            if (detectedWaypoints != null && pathComplexity > 1.3 && (k % 3) == 1)
            {
                p = generateWaypointRescueSeed ();
            }
            else
            {
                p = base.clone ();
                double noise = domainDiag * (0.02 + 0.22 * (k / (double) Math.max (1, tries)));

                for (int i = 0; i < d; i++)
                    p[i] += rng.nextGaussian () * noise;

                // Diversification structurelle: lignes de niveau pour forcer des passages differents.
                if ((k % 7) == 0)
                {
                    double yy = problem.getMinY ()
                            + rng.nextDouble () * (problem.getMaxY () - problem.getMinY ());
                    for (int i = 0; i < nCP; i++)
                    {
                        double t = (double) (i + 1) / (nCP + 1);
                        p[2 * i] = problem.getStartPoint ().getX ()
                                + t * (problem.getEndPoint ().getX () - problem.getStartPoint ().getX ());
                        p[2 * i + 1] = yy;
                    }
                }
            }

            repelControlPointsFromObstacles (p, 2);
            clamp (p);
            evaluateAndTrack (p);
            if (noFeasible && bestFeasibleX != null)
                break;
        }
        lastRescueBurstMs = now;
    }

    /**
     * Genere un seed structure a partir des waypoints detectes.
     * Alterne entre clustering aux virages et spline lisse avec bruit modere.
     */
    private double [] generateWaypointRescueSeed ()
    {
        double [] p = new double [d];
        int nWp = detectedWaypoints.length;

        // Calculer longueurs des segments
        double totalLen = 0.0;
        double [] cumLen = new double [nWp];
        cumLen[0] = 0.0;
        for (int s = 1; s < nWp; s++)
        {
            cumLen[s] = cumLen[s-1] + Math.hypot (
                    detectedWaypoints[s][0] - detectedWaypoints[s-1][0],
                    detectedWaypoints[s][1] - detectedWaypoints[s-1][1]);
        }
        totalLen = cumLen[nWp - 1];
        if (totalLen < 1e-9) return defaultMean ();

        // Placer les CPs par interpolation le long des waypoints avec bruit
        double noiseScale = domainDiag * (0.02 + rng.nextDouble () * 0.08);
        for (int i = 0; i < nCP; i++)
        {
            double target = ((double) (i + 1) / (nCP + 1)) * totalLen;
            int seg = 1;
            while (seg < nWp && cumLen[seg] < target) seg++;
            seg = Math.max (1, Math.min (seg, nWp - 1));
            double denom = Math.max (1e-9, cumLen[seg] - cumLen[seg-1]);
            double a = (target - cumLen[seg-1]) / denom;
            p[2 * i] = (1.0 - a) * detectedWaypoints[seg-1][0]
                    + a * detectedWaypoints[seg][0]
                    + rng.nextGaussian () * noiseScale;
            p[2 * i + 1] = (1.0 - a) * detectedWaypoints[seg-1][1]
                    + a * detectedWaypoints[seg][1]
                    + rng.nextGaussian () * noiseScale;
        }
        return p;
    }

    private void maybeRefineElite ()
    {
        if (bestFeasibleX == null) return;
        long now = System.currentTimeMillis ();
        long elapsed = now - startTime;
        // Commencer le raffinement plus tot pour les problemes complexes
        double refineStart = (pathComplexity > 1.5) ? 0.20 : 0.35;
        if (elapsed < (long) (refineStart * TOTAL_TIME_MS)) return;
        if (lastRefineMs != 0L && now - lastRefineMs < 10L) return;

        // Phase 1 : raffinement aleatoire classique
        int tries = (elapsed > (long) (0.75 * TOTAL_TIME_MS)) ? 4 : 2;
        double before = bestFeasibleFitness;
        for (int t = 0; t < tries; t++)
        {
            double [] p = bestFeasibleX.clone ();
            int edits = 1 + rng.nextInt (Math.max (2, nCP / 2));
            for (int e = 0; e < edits; e++)
            {
                int cp = rng.nextInt (nCP);
                int ix = 2 * cp;
                p[ix] += rng.nextGaussian () * refineSigma;
                p[ix + 1] += rng.nextGaussian () * refineSigma;
            }
            repelControlPointsFromObstacles (p, 1);
            clamp (p);
            evaluateAndTrack (p);
        }

        // Phase 2 : raffinement coordonnee par coordonnee (1 CP a la fois)
        // Beaucoup plus efficace en haute dimension avec corridors etroits
        if (elapsed > (long) (0.4 * TOTAL_TIME_MS))
        {
            int cp = rng.nextInt (nCP);
            int ix = 2 * cp;
            double step = refineSigma;
            // Tester 8 directions + 2 tailles de pas
            double [][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
            for (double [] dir : dirs)
            {
                for (double scale : new double [] {step, step * 0.3})
                {
                    double [] p = bestFeasibleX.clone ();
                    p[ix] += dir[0] * scale;
                    p[ix + 1] += dir[1] * scale;
                    clamp (p);
                    evaluateAndTrack (p);
                }
            }
        }

        if (bestFeasibleFitness + 1e-9 < before)
            refineSigma = Math.min (domainDiag * 0.08, refineSigma * 1.06);
        else
            refineSigma = Math.max (domainDiag * 0.0008, refineSigma * 0.992);
        lastRefineMs = now;
    }

    private void repelControlPointsFromObstacles (double [] p, int iters)
    {
        double clearance = 0.8;
        for (int it = 0; it < iters; it++)
        {
            for (int i = 0; i < nCP; i++)
            {
                int ix = 2 * i;
                double x = p[ix];
                double y = p[ix + 1];
                for (int j = 0; j < nObs; j++)
                {
                    double dx = x - obsX[j];
                    double dy = y - obsY[j];
                    double dist = Math.hypot (dx, dy);
                    double minDist = obsR[j] + clearance;
                    if (dist < minDist)
                    {
                        double ux = (dist > 1e-9) ? (dx / dist) : (rng.nextBoolean () ? 1.0 : -1.0);
                        double uy = (dist > 1e-9) ? (dy / dist) : (rng.nextBoolean () ? 1.0 : -1.0);
                        double push = minDist - dist;
                        x += ux * push;
                        y += uy * push;
                    }
                }
                p[ix] = x;
                p[ix + 1] = y;
            }
            clamp (p);
        }
    }

    private double evaluateAndTrack (double [] p)
    {
        double f = problem.evaluate (p);
        trackBest (f);
        updateFeasible (p, f);
        return f;
    }

    private void updateFeasible (double [] p, double fitness)
    {
        if (p == null || !Double.isFinite (fitness)) return;
        if (!isLikelyFeasible (p)) return;
        if (fitness < bestFeasibleFitness)
        {
            bestFeasibleFitness = fitness;
            bestFeasibleX = p.clone ();
            if (fitness < globalBestFitness)
            {
                globalBestFitness = fitness;
                globalBestX = bestFeasibleX.clone ();
                lastGlobalImproveMs = System.currentTimeMillis ();
            }
        }
    }

    private boolean isLikelyFeasible (double [] p)
    {
        if (p == null || p.length != d) return false;

        double [] cx = new double [nCP + 2];
        double [] cy = new double [nCP + 2];
        cx[0] = problem.getStartPoint ().getX ();
        cy[0] = problem.getStartPoint ().getY ();
        for (int i = 0; i < nCP; i++)
        {
            cx[i + 1] = p[2 * i];
            cy[i + 1] = p[2 * i + 1];
        }
        cx[nCP + 1] = problem.getEndPoint ().getX ();
        cy[nCP + 1] = problem.getEndPoint ().getY ();

        for (int s = 0; s < FEASIBILITY_SAMPLES; s++)
        {
            double t = (FEASIBILITY_SAMPLES == 1) ? 0.0 : (double) s / (FEASIBILITY_SAMPLES - 1);
            double [] q = evalBezierPoint (cx, cy, t);
            double x = q[0], y = q[1];
            if (x < problem.getMinX () || x > problem.getMaxX ()) return false;
            if (y < problem.getMinY () || y > problem.getMaxY ()) return false;
            for (int j = 0; j < nObs; j++)
            {
                double dx = x - obsX[j];
                double dy = y - obsY[j];
                if (dx * dx + dy * dy <= obsR[j] * obsR[j]) return false;
            }
        }
        return true;
    }

    private static double [] evalBezierPoint (double [] px, double [] py, double t)
    {
        int n = px.length - 1;
        double omt = 1.0 - t;
        double x = 0.0, y = 0.0;
        for (int k = 0; k <= n; k++)
        {
            double c = binomial (n, k) * Math.pow (t, k) * Math.pow (omt, n - k);
            x += c * px[k];
            y += c * py[k];
        }
        return new double [] {x, y};
    }

    private static double binomial (int n, int k)
    {
        if (k < 0 || k > n) return 0.0;
        if (k == 0 || k == n) return 1.0;
        int kk = Math.min (k, n - k);
        double c = 1.0;
        for (int i = 1; i <= kk; i++)
            c = c * (n - kk + i) / i;
        return c;
    }

    private void trackBest (double f)
    {
        if (f < globalBestFitness)
        {
            globalBestFitness = f;
            lastGlobalImproveMs = System.currentTimeMillis ();
        }
    }

    private double [] defaultMean ()
    { double[]m=new double[d];double sx=problem.getStartPoint().getX(),sy=problem.getStartPoint().getY(),ex=problem.getEndPoint().getX(),ey=problem.getEndPoint().getY();for(int k=0;k<nCP;k++){double t=(double)(k+1)/(nCP+1);m[2*k]=sx+t*(ex-sx);m[2*k+1]=sy+t*(ey-sy);}return m; }

    private double [] buildLb (double m)
    { double[]l=new double[d];for(int i=0;i<d;i++)l[i]=(i%2==0)?(problem.getMinX()-m):(problem.getMinY()-m);return l; }

    private double [] buildUb (double m)
    { double[]u=new double[d];for(int i=0;i<d;i++)u[i]=(i%2==0)?(problem.getMaxX()+m):(problem.getMaxY()+m);return u; }

    public Optimizer getOptimizer ()
    { return cmaesReady ? cmaesSmall : null; }

    public double getInitialSigmaSmall () { return initialSigmaSmall; }
    public double getInitialSigmaWide () { return initialSigmaWide; }
    public OuterRestartStrategy getRestartStrategy () { return restartStrategy; }
}
