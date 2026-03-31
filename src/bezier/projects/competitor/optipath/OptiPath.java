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
    private final double marginSmall;
    private final double marginWide;
    private static final long TOTAL_TIME_MS = 58_000;
    private static final long RESTART_CHECK_EVERY_MS = 200;
    private static final long RESCUE_START_MS = 8_000;
    private static final long RESCUE_PERIOD_MS = 1_500;
    private static final int RESCUE_BURST_TRIES = 28;
    private static final int FEASIBILITY_SAMPLES = 64;

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

        // Detecter murs verticaux (obstacles groupes par x)
        ArrayList<double []> walls = detectWalls (ox, oy, or_, nObs, true);
        boolean vertical = !walls.isEmpty ();

        // Si pas de murs verticaux, essayer horizontaux
        if (!vertical)
            walls = detectWalls (oy, ox, or_, nObs, false);

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
            double wallPos = walls.get (w)[0];
            double gapCenter = walls.get (w)[1];
            if (vertical)
            { waypoints[w + 1][0] = wallPos; waypoints[w + 1][1] = gapCenter; }
            else
            { waypoints[w + 1][0] = gapCenter; waypoints[w + 1][1] = wallPos; }
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
        for (double overshoot : new double [] {1.0, 1.3, 1.5, 1.8})
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
        if (nWalls > 0 && nCP >= nWalls)
        {
            // Repartir les CPs entre les segments waypoint
            // Pour chaque gap, assigner ceil(nCP / (nWalls+1)) CPs
            for (int v = 0; v < 10; v++)
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    // Determiner a quel waypoint ce CP est le plus lie
                    double t = (double)(i + 1) / (nCP + 1);
                    int wpIdx = (int) Math.round (t * (waypoints.length - 1));
                    wpIdx = Math.max (0, Math.min (wpIdx, waypoints.length - 1));

                    double targetX = waypoints[wpIdx][0];
                    double targetY = waypoints[wpIdx][1];

                    // Petit offset en x pour que les CPs ne soient pas empiles
                    double cpX = targetX + (t - (double) wpIdx / (waypoints.length - 1))
                                           * (ex - sx) * 0.3;

                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 2.0;
                    p[2*i]     = cpX + noise;
                    p[2*i + 1] = targetY + noise;
                }
                addSeed (seeds, fits, p);
            }
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

            walls.add (new double [] {wallPos, bestGapCenter});
        }

        return walls;
    }

    /**
     * Seed deterministe par planification A* sur une grille.
     * Objectif: fournir au moins une trajectoire faisable "de secours".
     */
    private void addGridPlannerSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                      double sx, double sy, double ex, double ey)
    {
        int grid = Math.max (28, Math.min (56, 24 + nObs));
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
        addSeed (seeds, fits, p);

        // Variantes legeres autour de la trajectoire A*.
        for (int v = 0; v < 8; v++)
        {
            double [] q = p.clone ();
            double noise = domainDiag * (0.006 + 0.004 * v);
            for (int i = 0; i < d; i++)
                q[i] += rng.nextGaussian () * noise;
            repelControlPointsFromObstacles (q, 1);
            addSeed (seeds, fits, q);
        }
    }

    private static double heuristic (int x, int y, int tx, int ty)
    {
        return Math.hypot (x - tx, y - ty);
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private double [] makePath (double x, double y)
    { double[]p=new double[d]; for(int i=0;i<nCP;i++){p[2*i]=x;p[2*i+1]=y;} return p; }

    private void clamp (double [] p)
    { for(int i=0;i<d;i++){if(p[i]<lbWide[i])p[i]=lbWide[i];if(p[i]>ubWide[i])p[i]=ubWide[i];} }

    private void maybeRescueFeasibility ()
    {
        long now = System.currentTimeMillis ();
        if (!cmaesReady) return;
        if (now - startTime < RESCUE_START_MS) return;

        boolean noFeasible = (bestFeasibleX == null);
        boolean stalled = (now - lastGlobalImproveMs) > 7_000L;
        if (!noFeasible && !stalled) return;
        long period = noFeasible ? 900L : RESCUE_PERIOD_MS;
        if (lastRescueBurstMs != 0L && now - lastRescueBurstMs < period) return;

        double [] base = bestFeasibleX != null ? bestFeasibleX
                : (globalBestX != null ? globalBestX : defaultMean ());
        long elapsed = now - startTime;
        int tries = noFeasible ? 220 : RESCUE_BURST_TRIES;
        if (noFeasible && elapsed > 25_000L) tries = 600;

        for (int k = 0; k < tries; k++)
        {
            double [] p = base.clone ();
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

            repelControlPointsFromObstacles (p, 2);
            clamp (p);
            evaluateAndTrack (p);
            if (noFeasible && bestFeasibleX != null)
                break;
        }
        lastRescueBurstMs = now;
    }

    private void maybeRefineElite ()
    {
        if (bestFeasibleX == null) return;
        long now = System.currentTimeMillis ();
        long elapsed = now - startTime;
        if (elapsed < (long) (0.35 * TOTAL_TIME_MS)) return;
        if (lastRefineMs != 0L && now - lastRefineMs < 10L) return;

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
