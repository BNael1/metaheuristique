package bezier2_0.projects.competitor.optipath;

import bezier2_0.evaluation.Problem;
import bezier2_0.projects.CompetitorProject;
import bezier2_0.projects.InvalidProjectException;
import java.util.ArrayList;
import java.util.EnumMap;
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
    private static final long TOTAL_TIME_MS = 60_000;
    private static final long RESTART_CHECK_EVERY_MS = 200;
    private static final long RESCUE_START_MS = 8_000;
    private static final long RESCUE_PERIOD_MS = 1_500;
    private static final int RESCUE_BURST_TRIES = 28;

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
    /** Fraction de budget effectivement allouee au seeding (adaptative). */
    private double seedRatioBudget;

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
    private double incumbentRefineSigma;
    private long lastRefineMs;
    private long lastIncumbentRefineMs;
    /** Timestamp du dernier gain global et du dernier burst de rescue. */
    private long lastGlobalImproveMs;
    private long lastRescueBurstMs;

    /** Waypoints detectes par l'analyse obstacle-aware (start, gaps, end). */
    private double [][] detectedWaypoints;
    /** Ratio longueur_waypoints / distance_directe (1.0 = ligne droite). */
    private double pathComplexity;
    /** Seed A* garde comme fallback garanti pour les restarts. */
    private double [] aStarSeed;
    /** Waypoints extraits du path A* pour rescue structurel. */
    private double [][] aStarWaypoints;
    /** Diagnostic structurel de carte (calcule une fois au demarrage). */
    private MapDiagnostics mapDiagnostics;
    /** Contexte d'annotation des seeds pendant le seeding massif. */
    private SeedFamily currentSeedFamily;
    private ArrayList<SeedFamily> activeSeedFamilies;

    // Seeding : top seeds + optim locale
    private ArrayList<double []> topSeeds;
    private int localOptIdx;
    private double [] localCurrent;
    private double localCurrentF;
    private double localStep;
    private int localEvals;
    private static final int LOCAL_BUDGET_PER_SEED = 200;

    // Archive faisable diversifiee pour restart mean plus generique
    private static final int FEASIBLE_ARCHIVE_MAX = 24;
    private final ArrayList<double []> feasibleArchive = new ArrayList<> ();
    private final ArrayList<Double> feasibleArchiveFitness = new ArrayList<> ();

    private static final class RankedArchiveEntry
    {
        final double [] x;
        final double fitness;

        RankedArchiveEntry (double [] x, double fitness)
        {
            this.x = x;
            this.fitness = fitness;
        }
    }

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
        seedRatioBudget = SEED_RATIO;
        restartCount = 0;
        lastGlobalImproveMs = startTime;
        lastRescueBurstMs = 0L;
        lastRefineMs = 0L;
        lastIncumbentRefineMs = 0L;
        detectedWaypoints = null;
        pathComplexity = 1.0;
        aStarSeed = null;
        aStarWaypoints = null;
        mapDiagnostics = null;
        currentSeedFamily = SeedFamily.GENERIC;
        activeSeedFamilies = null;
        feasibleArchive.clear ();
        feasibleArchiveFitness.clear ();

        nCP = problem.getNControlPoints ();
        d = 2 * nCP;
        nObs = problem.getNObstacles ();
        obsX = new double [nObs];
        obsY = new double [nObs];
        obsR = new double [nObs];
        for (int i = 0; i < nObs; i++)
        {
            bezier2_0.evaluation.Obstacle o = problem.getObstacle (i);
            obsX[i] = o.getX ();
            obsY[i] = o.getY ();
            obsR[i] = o.getRadius ();
        }
        mapDiagnostics = MapDiagnostics.compute (
                problem.getStartPoint ().getX (), problem.getStartPoint ().getY (),
                problem.getEndPoint ().getX (), problem.getEndPoint ().getY (),
                problem.getMinX (), problem.getMinY (),
                problem.getMaxX (), problem.getMaxY (),
                obsX, obsY, obsR);
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
        incumbentRefineSigma = domainDiag * 0.012;
        lastRestartCheckMs = 0L;
        lastCtxSnapshotMs = 0L;
        lastBestSmall = Double.POSITIVE_INFINITY;
        lastBestWide = Double.POSITIVE_INFINITY;
        lastEvalSmall = 0;
        lastEvalWide = 0;

        if (mapDiagnostics != null && mapDiagnostics.shouldEnableSpecializedZigzag ())
            seedRatioBudget = 0.16;

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
        activeSeedFamilies = new ArrayList<> ();
        currentSeedFamily = SeedFamily.GENERIC;
        boolean allowSpecializedZigzag = mapDiagnostics != null && mapDiagnostics.shouldEnableSpecializedZigzag ();

        // Fast-path : sans obstacles, le chemin optimal est la ligne droite
        if (nObs == 0)
        {
            currentSeedFamily = SeedFamily.LINEAR_GRID;
            double [] straight = defaultMean ();
            addSeed (allSeeds, allFitness, straight);
            currentSeedFamily = SeedFamily.RANDOM;
            for (int r = 0; r < 15; r++)
            {
                double [] p = straight.clone ();
                for (int i = 0; i < d; i++)
                    p[i] += rng.nextGaussian () * domainDiag * (0.005 + 0.01 * r);
                addSeed (allSeeds, allFitness, p);
            }
            finalizeSeedPortfolio (allSeeds, allFitness);
            return;
        }

        double [] xVals = {lbWide[0], lbWide[0]+2, problem.getMinX (), problem.getMinX ()+2,
                           (problem.getMinX ()+problem.getMaxX ())/2,
                           problem.getMaxX ()-2, problem.getMaxX (), ubWide[0]-2, ubWide[0]};
        double [] yVals = {lbWide[1], lbWide[1]+2, problem.getMinY (), problem.getMinY ()+2,
                           (problem.getMinY ()+problem.getMaxY ())/2,
                           problem.getMaxY ()-2, problem.getMaxY (), ubWide[1]-2, ubWide[1]};

        // 1. Grille x constant, y constant
        currentSeedFamily = SeedFamily.LINEAR_GRID;
        for (double xv : xVals)
            for (double yv : yVals)
                addSeed (allSeeds, allFitness, makePath (xv, yv));

        // 2. Alternance X extreme
        currentSeedFamily = SeedFamily.ZIGZAG_GENERIC;
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
        currentSeedFamily = SeedFamily.LINEAR_GRID;
        for (double yy : yVals)
        {
            double [] p = new double [d];
            for (int i = 0; i < nCP; i++)
            { double t=(double)(i+1)/(nCP+1); p[2*i]=sx+t*(ex-sx); p[2*i+1]=yy; }
            addSeed (allSeeds, allFitness, p);
        }

        // 4. Sinusoides
        currentSeedFamily = SeedFamily.SINUSOIDAL;
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
        currentSeedFamily = SeedFamily.RANDOM;
        for (int r = 0; r < 80; r++)
        {
            double [] p = new double [d];
            for (int i=0;i<d;i++) p[i] = lbWide[i] + rng.nextDouble()*(ubWide[i]-lbWide[i]);
            addSeed (allSeeds, allFitness, p);
        }

        // 6. Zigzags : alternance haut/bas le long du chemin start→end
        if (allowSpecializedZigzag
                || (mapDiagnostics != null && mapDiagnostics.hasStrongStructure ())
                || (mapDiagnostics != null && mapDiagnostics.pathStretch >= 1.15))
        {
            currentSeedFamily = SeedFamily.ZIGZAG_GENERIC;
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
        }

        // 7. Obstacle-aware : detection de murs et routage par les gaps
        currentSeedFamily = SeedFamily.OBSTACLE_AWARE;
        addObstacleAwareSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        // 7b. Detour seeds : contournement d'obstacles bloquant le chemin direct
        currentSeedFamily = SeedFamily.DETOUR;
        addDetourSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        // 8. Seed deterministe via A* sur grille (garde-fou anti-catastrophe)
        currentSeedFamily = SeedFamily.GRID_ASTAR;
        addGridPlannerSeeds (allSeeds, allFitness, sx, sy, ex, ey);

        finalizeSeedPortfolio (allSeeds, allFitness);
    }

    private void addSeed (ArrayList<double []> seeds, ArrayList<Double> fits, double [] p)
    {
        clamp (p);
        double f = evaluateAndTrack (p);
        seeds.add (p.clone ());
        fits.add (f);
        if (activeSeedFamilies != null)
            activeSeedFamilies.add (currentSeedFamily != null ? currentSeedFamily : SeedFamily.GENERIC);
    }

    private void finalizeSeedPortfolio (ArrayList<double []> allSeeds, ArrayList<Double> allFitness)
    {
        if (allSeeds.isEmpty ())
        {
            topSeeds = new ArrayList<> ();
            topSeeds.add (defaultMean ());
            globalBestX = topSeeds.get (0).clone ();
            double baseline = evaluateAndTrack (topSeeds.get (0));
            seedStats = new SeedingStats (baseline, baseline, 0.0, baseline);
            restartStrategy.init (seedStats);
            activeSeedFamilies = null;
            return;
        }

        SeedPortfolio portfolio = new SeedPortfolio (d);
        for (int i = 0; i < allSeeds.size (); i++)
        {
            SeedFamily fam = (activeSeedFamilies != null && i < activeSeedFamilies.size ())
                    ? activeSeedFamilies.get (i)
                    : SeedFamily.GENERIC;
            portfolio.add (fam, allSeeds.get (i), allFitness.get (i));
        }

        EnumMap<SeedFamily, Integer> caps = buildDynamicFamilyCaps (20);
        double diversityThreshold = computeSeedDiversityThreshold ();
        ArrayList<SeedCandidate> selected = portfolio.selectTopDiverse (20, diversityThreshold, caps);

        if (selected.isEmpty ())
        {
            ArrayList<Integer> idx = new ArrayList<> ();
            for (int i = 0; i < allSeeds.size (); i++) idx.add (i);
            idx.sort ((a, b) -> Double.compare (allFitness.get (a), allFitness.get (b)));
            selected = new ArrayList<> ();
            int lim = Math.min (20, idx.size ());
            for (int i = 0; i < lim; i++)
            {
                int id = idx.get (i);
                SeedFamily fam = (activeSeedFamilies != null && id < activeSeedFamilies.size ())
                        ? activeSeedFamilies.get (id)
                        : SeedFamily.GENERIC;
                selected.add (new SeedCandidate (fam, allSeeds.get (id).clone (), allFitness.get (id)));
            }
        }

        selected.sort ((a, b) -> Double.compare (a.fitness, b.fitness));
        topSeeds = new ArrayList<> ();
        for (SeedCandidate c : selected)
            topSeeds.add (c.vector.clone ());

        if (!topSeeds.isEmpty ())
            globalBestX = topSeeds.get (0).clone ();

        double [] sorted = new double [allFitness.size ()];
        for (int i = 0; i < allFitness.size (); i++) sorted[i] = allFitness.get (i);
        java.util.Arrays.sort (sorted);
        double best = sorted[0];
        double median = sorted[sorted.length / 2];
        double p25 = sorted[sorted.length / 4];
        double mean = 0.0;
        for (double v : sorted) mean += v;
        mean /= sorted.length;
        double variance = 0.0;
        for (double v : sorted) variance += (v - mean) * (v - mean);
        variance /= sorted.length;
        seedStats = new SeedingStats (best, median, Math.sqrt (variance), p25);
        restartStrategy.init (seedStats);

        activeSeedFamilies = null;
    }

    private EnumMap<SeedFamily, Integer> buildDynamicFamilyCaps (int totalCap)
    {
        EnumMap<SeedFamily, Integer> caps = new EnumMap<> (SeedFamily.class);

        // Base neutre, robuste sur problemes heterogenes
        caps.put (SeedFamily.LINEAR_GRID, 6);
        caps.put (SeedFamily.RANDOM, 5);
        caps.put (SeedFamily.SINUSOIDAL, 4);
        caps.put (SeedFamily.ZIGZAG_GENERIC, 2);
        caps.put (SeedFamily.OBSTACLE_AWARE, 5);
        caps.put (SeedFamily.DETOUR, 4);
        caps.put (SeedFamily.GRID_ASTAR, 4);
        caps.put (SeedFamily.SPECIALIZED_ZIGZAG, 1);
        caps.put (SeedFamily.GENERIC, 4);

        if (mapDiagnostics == null)
            return caps;

        if (mapDiagnostics.pathStretch >= 1.4)
        {
            caps.put (SeedFamily.GRID_ASTAR, 6);
            caps.put (SeedFamily.DETOUR, 5);
            caps.put (SeedFamily.OBSTACLE_AWARE, 6);
        }

        if (mapDiagnostics.wallAlignmentConfidence < 0.30)
        {
            caps.put (SeedFamily.ZIGZAG_GENERIC, 1);
            caps.put (SeedFamily.OBSTACLE_AWARE, 4);
            caps.put (SeedFamily.SINUSOIDAL, 5);
        }

        if (mapDiagnostics.shouldEnableSpecializedZigzag ())
        {
            // Cas peigne/zigzag alterne: prioriser les familles structurelles.
            caps.put (SeedFamily.SPECIALIZED_ZIGZAG, 6);
            caps.put (SeedFamily.ZIGZAG_GENERIC, 5);
            caps.put (SeedFamily.OBSTACLE_AWARE, 8);
            caps.put (SeedFamily.DETOUR, 6);
            caps.put (SeedFamily.GRID_ASTAR, 6);
            caps.put (SeedFamily.LINEAR_GRID, 3);
            caps.put (SeedFamily.SINUSOIDAL, 2);
            caps.put (SeedFamily.RANDOM, 2);
        }
        else if (mapDiagnostics.hasStrongStructure ())
        {
            // Cas labyrinthiques non alternants (ex: spirale).
            caps.put (SeedFamily.OBSTACLE_AWARE, 8);
            caps.put (SeedFamily.DETOUR, 6);
            caps.put (SeedFamily.GRID_ASTAR, 6);
            caps.put (SeedFamily.ZIGZAG_GENERIC, 3);
            caps.put (SeedFamily.SINUSOIDAL, 2);
            caps.put (SeedFamily.RANDOM, 2);
        }
        else
        {
            caps.put (SeedFamily.SPECIALIZED_ZIGZAG, 0);
        }

        // Bornes de securite
        for (SeedFamily family : SeedFamily.values ())
        {
            int v = caps.getOrDefault (family, 0);
            caps.put (family, Math.max (0, Math.min (totalCap, v)));
        }
        return caps;
    }

    private double computeSeedDiversityThreshold ()
    {
        if (mapDiagnostics == null)
            return Math.max (0.12, domainDiag * 0.008);

        double stretch = Math.max (1.0, mapDiagnostics.pathStretch);
        double byStretch = domainDiag * (0.004 + 0.001 * Math.min (2.0, stretch - 1.0));
        double byCorridor = Math.max (0.05, mapDiagnostics.estimatedCorridorWidth * 0.8);
        double threshold = Math.max (byStretch, byCorridor);

        // Dans des corridors etroits, on laisse davantage de seeds proches coexister.
        if (mapDiagnostics.estimatedCorridorWidth < 0.6)
            threshold = Math.min (threshold, 0.30);

        return Math.min (domainDiag * 0.01, Math.max (0.05, threshold));
    }

    // ================================================================
    //  loop()
    // ================================================================
    @Override
    public void loop ()
    {
        long elapsed = System.currentTimeMillis () - startTime;
        double ratio = (double) elapsed / TOTAL_TIME_MS;

        if (!cmaesReady && ratio < seedRatioBudget)
        {
            doLocalOptStep ();
            return;
        }

        if (!cmaesReady)
            launchBothCMAES ();

        maybeCheckExternalRestart ();
        maybeRescueFeasibility ();

        // Alterner small/wide pour stabiliser la progression inter-runs.
        if (stepSmallNext) cmaesSmall.step ();
        else cmaesWide.step ();
        stepSmallNext = !stepSmallNext;
        maybeRefineIncumbent ();
        maybeRefineElite ();

        // Tracker le meilleur (CMA-ES met a jour problem en interne via evaluate)
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
        // forcer un restart depuis le seed A* sous stagnation + difficulte de faisabilite.
        long elapsed = now - startTime;
        boolean stagnating = (now - lastGlobalImproveMs) > 7_000L;
        boolean weakFeasible = (bestFeasibleX == null) || (bestFeasibleFitness > 2.2 * domainDiag);
        if (aStarSeed != null && elapsed > 20_000L && stagnating && weakFeasible)
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
        boolean hasTopSeeds = topSeeds != null && !topSeeds.isEmpty ();
        boolean hasArchive = !feasibleArchive.isEmpty ();
        if (!hasTopSeeds && !hasArchive)
            return defaultMean ();

        double [] anchorBest = (bestFeasibleX != null) ? bestFeasibleX : globalBestX;
        double basinBest = Double.POSITIVE_INFINITY;
        if (cmaesSmall != null) basinBest = Math.min (basinBest, cmaesSmall.getBestFitness ());
        if (cmaesWide != null) basinBest = Math.min (basinBest, cmaesWide.getBestFitness ());
        if (!Double.isFinite (basinBest)) basinBest = fitnessAtLaunch;

        long now = System.currentTimeMillis ();
        boolean stagnating = (now - lastGlobalImproveMs) > 4_500L;
        boolean weakFeasible = (bestFeasibleX == null) || !Double.isFinite (bestFeasibleFitness);

        // Fallback A* : seulement sous stagnation + difficulte de faisabilite.
        if (aStarSeed != null && (weakFeasible || (stagnating && basinBest > 2.2 * domainDiag)))
            return noisyClone (aStarSeed, 0.015);

        double relGap = (basinBest - globalBestFitness) / (Math.abs (globalBestFitness) + 1e-9);
        if (anchorBest != null && relGap > 2.0)
            return noisyClone (anchorBest, 0.02);

        if (hasArchive)
        {
            double exploreProb = stagnating ? 0.55 : 0.30;
            RankedArchiveEntry choice = (rng.nextDouble () < (1.0 - exploreProb))
                    ? sampleArchiveExploit ()
                    : sampleArchiveExplore (anchorBest);
            if (choice != null)
                return noisyClone (choice.x, stagnating ? 0.035 : 0.025);
        }

        if (hasTopSeeds)
        {
            int topK = Math.min (5, topSeeds.size ());
            int idx = rng.nextInt (Math.max (1, topK));
            return noisyClone (topSeeds.get (idx), 0.03);
        }

        double [] p = new double [d];
        for (int i = 0; i < d; i++)
            p[i] = lbWide[i] + rng.nextDouble () * (ubWide[i] - lbWide[i]);
        clamp (p);
        return p;
    }

    private RankedArchiveEntry sampleArchiveExploit ()
    {
        if (feasibleArchive.isEmpty ()) return null;
        ArrayList<RankedArchiveEntry> ranked = buildRankedArchive ();
        int topK = Math.min (Math.max (1, ranked.size ()), 5);
        // Tirage biaise vers les meilleurs rangs.
        double sum = 0.0;
        for (int i = 0; i < topK; i++) sum += 1.0 / (1.0 + i);
        double r = rng.nextDouble () * sum;
        double acc = 0.0;
        for (int i = 0; i < topK; i++)
        {
            acc += 1.0 / (1.0 + i);
            if (r <= acc) return ranked.get (i);
        }
        return ranked.get (0);
    }

    private RankedArchiveEntry sampleArchiveExplore (double [] anchor)
    {
        if (feasibleArchive.isEmpty ()) return null;
        ArrayList<RankedArchiveEntry> ranked = buildRankedArchive ();
        if (anchor == null) return ranked.get (rng.nextInt (ranked.size ()));

        ranked.sort ((a, b) -> Double.compare (
                distanceNorm (b.x, anchor),
                distanceNorm (a.x, anchor)));
        int k = Math.min (Math.max (1, ranked.size ()), 5);
        return ranked.get (rng.nextInt (k));
    }

    private ArrayList<RankedArchiveEntry> buildRankedArchive ()
    {
        ArrayList<RankedArchiveEntry> ranked = new ArrayList<> ();
        for (int i = 0; i < feasibleArchive.size (); i++)
            ranked.add (new RankedArchiveEntry (feasibleArchive.get (i), feasibleArchiveFitness.get (i)));
        ranked.sort ((a, b) -> Double.compare (a.fitness, b.fitness));
        return ranked;
    }

    private double [] noisyClone (double [] base, double sigmaRatio)
    {
        double [] p = base.clone ();
        double noise = domainDiag * sigmaRatio;
        for (int i = 0; i < d; i++)
            p[i] += rng.nextGaussian () * noise;
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
            bezier2_0.evaluation.Obstacle o = problem.getObstacle (i);
            ox[i] = o.getX (); oy[i] = o.getY (); or_[i] = o.getRadius ();
        }

        // Detecter murs verticaux ET horizontaux, garder la meilleure orientation
        ArrayList<double []> vWalls = detectWalls (ox, oy, or_, nObs, true);
        ArrayList<double []> hWalls = detectWalls (oy, ox, or_, nObs, false);

        // Choisir l'orientation : comparer le total d'obstacles couverts par les murs detectes
        // (pas juste le nombre de murs, car des faux positifs peuvent apparaitre
        // quand des obstacles de murs differents partagent la meme coordonnee secondaire)
        int vObsCount = countWallObstacles (ox, or_, nObs, vWalls);
        int hObsCount = countWallObstacles (oy, or_, nObs, hWalls);
        boolean vertical = vObsCount >= hObsCount;
        ArrayList<double []> walls = vertical ? vWalls : hWalls;

        // Si egalite stricte, privilegier l'orientation alignee au chemin
        if (vObsCount == hObsCount && !vWalls.isEmpty () && !hWalls.isEmpty ())
        {
            double dxPath = Math.abs (ex - sx);
            double dyPath = Math.abs (ey - sy);
            vertical = dxPath >= dyPath;
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
        double bestAwareFit = Double.POSITIVE_INFINITY;
        double [] bestAwareP = null;
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
                double lastF = fits.get (fits.size () - 1);
                if (lastF < bestAwareFit) { bestAwareFit = lastF; bestAwareP = p.clone (); }
            }
        }
        // DEBUG obstacle-aware seeds
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

        // Strategie 5 : seeds cosinus pour chemins zigzag complexes.
        // Au lieu de placer les CPs sur le zigzag (oscillations de Runge),
        // utiliser une fonction cosinus dont les CPs suivent un profil lisse
        // qui correspond naturellement au pattern haut-bas-haut des gaps.
        boolean enableSpecialized = mapDiagnostics != null && mapDiagnostics.shouldEnableSpecializedZigzag ();
        if (enableSpecialized && pathComplexity > 1.4 && nCP >= 6)
        {
            SeedFamily prevFamily = currentSeedFamily;
            currentSeedFamily = SeedFamily.SPECIALIZED_ZIGZAG;
            addCosineZigzagSeeds (seeds, fits, waypoints, sx, sy, ex, ey);
            addZigzagLocalOptSeeds (seeds, fits, waypoints, sx, sy, ex, ey);
            currentSeedFamily = prevFamily;
        }
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
     * Seeds par fitting least-squares inverse pour chemins zigzag complexes.
     *
     * Probleme : placer les CPs sur le chemin desire ne fait PAS passer la courbe
     * Bezier par ce chemin (CPs != points de la courbe pour degre > 1).
     *
     * Solution : generer un chemin lisse cible (spline Hermite a travers les gaps),
     * echantillonner N points cibles, puis resoudre le probleme inverse :
     *   trouver P_1..P_nCP tels que ||A * P - b||² soit minimise
     * ou A[k][i] = B_{i+1, n}(t_k) (poids de Bernstein).
     *
     * Le resultat : les CPs qui font que la courbe Bezier PASSE reellement par
     * (ou au plus pres de) le chemin cible.
     */
    private void addCosineZigzagSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                        double [][] waypoints,
                                        double sx, double sy, double ex, double ey)
    {
        int nWp = waypoints.length;
        if (nWp < 4) return;

        int n = nCP + 1; // degre du Bezier (n+1 = nCP+2 points de controle total)

        // --- Etape 1 : generer le chemin cible lisse (spline Hermite) ---
        double [][] tangents = new double [nWp][2];
        for (int w = 0; w < nWp; w++)
        {
            if (w == 0)
            { tangents[w][0] = waypoints[1][0] - waypoints[0][0]; tangents[w][1] = waypoints[1][1] - waypoints[0][1]; }
            else if (w == nWp - 1)
            { tangents[w][0] = waypoints[nWp-1][0] - waypoints[nWp-2][0]; tangents[w][1] = waypoints[nWp-1][1] - waypoints[nWp-2][1]; }
            else
            { tangents[w][0] = (waypoints[w+1][0] - waypoints[w-1][0]) * 0.5; tangents[w][1] = (waypoints[w+1][1] - waypoints[w-1][1]) * 0.5; }
        }

        for (double tangentScale : new double [] {0.5, 1.0, 1.5, 2.0, 3.0})
        {
            // Echantillonner le chemin cible
            int sampPerSeg = 30;
            int totalMax = sampPerSeg * (nWp - 1) + 1;
            double [] pathX = new double [totalMax];
            double [] pathY = new double [totalMax];
            double [] cumLen = new double [totalMax];
            int nSamp = 0;
            double totalLen = 0.0;
            for (int seg = 0; seg < nWp - 1; seg++)
            {
                int kMax = (seg == nWp - 2) ? sampPerSeg : sampPerSeg;
                for (int k = 0; k <= ((seg == nWp - 2) ? sampPerSeg : sampPerSeg - 1); k++)
                {
                    double u = (double) k / sampPerSeg;
                    double u2 = u * u, u3 = u2 * u;
                    double h00 = 2*u3 - 3*u2 + 1, h10 = u3 - 2*u2 + u;
                    double h01 = -2*u3 + 3*u2, h11 = u3 - u2;
                    pathX[nSamp] = h00*waypoints[seg][0] + h10*tangents[seg][0]*tangentScale
                                 + h01*waypoints[seg+1][0] + h11*tangents[seg+1][0]*tangentScale;
                    pathY[nSamp] = h00*waypoints[seg][1] + h10*tangents[seg][1]*tangentScale
                                 + h01*waypoints[seg+1][1] + h11*tangents[seg+1][1]*tangentScale;
                    if (nSamp > 0)
                        totalLen += Math.hypot (pathX[nSamp] - pathX[nSamp-1],
                                                pathY[nSamp] - pathY[nSamp-1]);
                    cumLen[nSamp] = totalLen;
                    nSamp++;
                }
            }
            if (totalLen < 1e-9 || nSamp < 5) continue;

            // --- Etape 2 : parametriser les echantillons via la coordonnee x ---
            // Pour des CPs x lineaires, B_x(t) ≈ sx + t*(ex-sx), donc t ≈ (x-sx)/(ex-sx).
            // Cette param correspond naturellement au Bernstein et evite le Runge.
            double dxPath = ex - sx;
            double [] tSamp = new double [nSamp];
            if (Math.abs (dxPath) > 1e-9)
                for (int k = 0; k < nSamp; k++)
                    tSamp[k] = Math.max (0.001, Math.min (0.999,
                            (pathX[k] - sx) / dxPath));
            else
                for (int k = 0; k < nSamp; k++)
                    tSamp[k] = cumLen[k] / totalLen;

            // --- Etape 3 : construire la matrice de Bernstein et le vecteur cible ---
            // B(t) = B_{0,n}(t)*P_0 + sum_{i=1..nCP} B_{i,n}(t)*P_i + B_{n,n}(t)*P_{n+1}
            // On cherche P_1..P_nCP qui minimisent ||sum B_i * P_i - (target - B_0*start - B_n*end)||²
            double [][] A = new double [nSamp][nCP];
            double [] bx = new double [nSamp], by = new double [nSamp];
            for (int k = 0; k < nSamp; k++)
            {
                double [] bw = bernsteinWeights (n, tSamp[k]);
                for (int i = 0; i < nCP; i++)
                    A[k][i] = bw[i + 1];
                bx[k] = pathX[k] - bw[0] * sx - bw[n] * ex;
                by[k] = pathY[k] - bw[0] * sy - bw[n] * ey;
            }

            // --- Etape 4 : resoudre par ridge regression (A^T A + lambda*I) P = A^T b ---
            // Lambda empeche les CPs d'exploser (regularisation de Tikhonov)
            // CPs de reference : ligne droite start→end
            double [] refX = new double [nCP], refY = new double [nCP];
            for (int i = 0; i < nCP; i++)
            { double t = (double)(i+1)/(nCP+1); refX[i] = sx + t*(ex-sx); refY[i] = sy + t*(ey-sy); }

            for (double lambda : new double [] {0.01, 0.1, 1.0, 5.0, 20.0, 100.0})
            {
                double [][] ATA = new double [nCP][nCP];
                double [] ATbx = new double [nCP], ATby = new double [nCP];
                for (int i = 0; i < nCP; i++)
                {
                    for (int j = i; j < nCP; j++)
                    {
                        double s = 0;
                        for (int k = 0; k < nSamp; k++) s += A[k][i] * A[k][j];
                        ATA[i][j] = s;
                        ATA[j][i] = s;
                    }
                    ATA[i][i] += lambda;
                    for (int k = 0; k < nSamp; k++)
                    { ATbx[i] += A[k][i] * bx[k]; ATby[i] += A[k][i] * by[k]; }
                    // Regulariser vers la ligne droite
                    ATbx[i] += lambda * refX[i];
                    ATby[i] += lambda * refY[i];
                }

                double [][] inv = invertMatrix (ATA, nCP);
                if (inv == null) continue;

                double [] cpXr = new double [nCP], cpYr = new double [nCP];
                for (int i = 0; i < nCP; i++)
                    for (int j = 0; j < nCP; j++)
                    { cpXr[i] += inv[i][j] * ATbx[j]; cpYr[i] += inv[i][j] * ATby[j]; }

                for (int v = 0; v < 2; v++)
                {
                    double [] p = new double [d];
                    for (int i = 0; i < nCP; i++)
                    {
                        double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.5;
                        p[2*i]     = cpXr[i] + noise;
                        p[2*i + 1] = cpYr[i] + noise;
                    }
                    addSeed (seeds, fits, p);
                }
                if (tangentScale == 1.0)
                {
                }
            }

        }
    }

    /**
     * Seeds zigzag avec pre-optimisation locale rapide.
     *
     * Probleme : les CPs placees sur un zigzag donnent un score catastrophique
     * (76000+) a cause du Runge, et ne rentrent jamais dans le top-20 seeds.
     * CMA-ES ne les voit donc jamais.
     *
     * Solution : partir du zigzag CP brut, faire une mini-optimisation locale
     * (hill climbing) pour descendre le score a quelques milliers, puis injecter
     * ce seed pre-optimise. CMA-ES partira alors dans le bon bassin.
     */
    private void addZigzagLocalOptSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                          double [][] waypoints,
                                          double sx, double sy, double ex, double ey)
    {
        int nWp = waypoints.length;
        if (nWp < 4) return;

        // Construire plusieurs seeds zigzag initiaux avec differents decoupages
        // On essaie: repartition uniforme et repartition proportionnelle a la longueur
        ArrayList<double []> zigzagStarts = new ArrayList<> ();

        // Variante 1 : blocs uniformes (nCP/nSegments CPs par segment)
        {
            int nSeg = nWp - 1;
            double [] p = new double [d];
            int cpIdx = 0;
            for (int s = 0; s < nSeg && cpIdx < nCP; s++)
            {
                int cpsInSeg = (s < nSeg - 1) ? nCP / nSeg : nCP - cpIdx;
                for (int c = 0; c < cpsInSeg && cpIdx < nCP; c++)
                {
                    double t = (double)(c + 1) / (cpsInSeg + 1);
                    p[2*cpIdx]     = waypoints[s][0] + t * (waypoints[s+1][0] - waypoints[s][0]);
                    p[2*cpIdx + 1] = waypoints[s][1] + t * (waypoints[s+1][1] - waypoints[s][1]);
                    cpIdx++;
                }
            }
            zigzagStarts.add (p);
        }

        // Variante 2 : blocs haut/bas/haut (premier tiers haut, milieu bas, dernier haut)
        {
            double [] p = new double [d];
            int third = nCP / 3;
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i + 1) / (nCP + 1);
                p[2*i] = sx + t * (ex - sx);
                if (i < third)
                    p[2*i+1] = waypoints[1][1]; // y du premier gap (haut ou bas)
                else if (i < 2 * third)
                    p[2*i+1] = waypoints[2][1]; // y du deuxieme gap
                else
                    p[2*i+1] = waypoints[nWp-2][1]; // y du dernier gap
            }
            zigzagStarts.add (p);
        }

        // Variante 3 : CPs a Y extreme (amplifie) pour compenser le lissage
        for (double amp : new double [] {1.5, 2.0})
        {
            double yMid = (waypoints[1][1] + waypoints[2][1]) / 2.0;
            double [] p = new double [d];
            int third = nCP / 3;
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i + 1) / (nCP + 1);
                p[2*i] = sx + t * (ex - sx);
                double yTarget;
                if (i < third) yTarget = waypoints[1][1];
                else if (i < 2 * third) yTarget = waypoints[2][1];
                else yTarget = waypoints[nWp-2][1];
                p[2*i+1] = yMid + (yTarget - yMid) * amp;
            }
            zigzagStarts.add (p);
        }

        // Pour chaque seed zigzag, faire un hill climbing rapide
        double bestLocalF = Double.POSITIVE_INFINITY;
        double [] bestLocalP = null;
        int localIters = 3000;

        for (double [] start : zigzagStarts)
        {
            clamp (start);
            double curF = evaluateAndTrack (start);
            double [] cur = start.clone ();
            double step = 3.0;

            for (int iter = 0; iter < localIters; iter++)
            {
                double [] trial = cur.clone ();
                // Perturber 1 a 3 coordonnees
                int nPerturb = 1 + rng.nextInt (3);
                for (int pp = 0; pp < nPerturb; pp++)
                {
                    int idx = rng.nextInt (d);
                    trial[idx] += rng.nextGaussian () * step;
                }
                clamp (trial);
                double trialF = evaluateAndTrack (trial);
                if (trialF < curF)
                {
                    cur = trial;
                    curF = trialF;
                }
                if (iter % 2000 == 1999) step *= 0.7;
            }

            if (curF < bestLocalF)
            {
                bestLocalF = curF;
                bestLocalP = cur.clone ();
            }

            // Ajouter ce seed pre-optimise
            addSeed (seeds, fits, cur);
        }

        // Generer des variantes bruitees du meilleur
        if (bestLocalP != null)
        {
            for (int v = 0; v < 5; v++)
            {
                double [] p = bestLocalP.clone ();
                double noise = 1.0 + v * 0.5;
                for (int i = 0; i < d; i++)
                    p[i] += rng.nextGaussian () * noise;
                addSeed (seeds, fits, p);
            }
        }
    }

    /**
     * Seeding "anti-Runge" pour Bezier haut degre avec zigzag complexe.
     *
     * Probleme : un Bezier de degre N avec CPs le long d'un zigzag produit
     * d'enormes oscillations (phenomene de Runge). Les CPs "interpolees" donnent
     * une courbe qui depasse les waypoints et traverse les obstacles.
     *
     * Solution : au lieu d'interpoler les CPs, on genere des seeds qui SUBDIVISE
     * le probleme en segments et place les CPs pour que la courbe resultante
     * passe aux bons endroits. On essaie plusieurs strategies :
     * - Exaggeration : CPs au-dela des waypoints pour compenser le lissage
     * - Segments lineaires entre les gaps : les CPs forment une ligne droite
     *   entre gap_i et gap_{i+1}, pas d'interpolation globale
     */
    private void addAntiRungSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                     double [][] waypoints)
    {
        int nWp = waypoints.length;
        if (nWp < 4) return; // besoin d'au moins 2 waypoints intermediaires

        // Strategie A : ligne droite par segment
        // Distribuer les CPs entre les segments proportionnellement a la longueur.
        // Dans chaque segment, les CPs sont distribues lineairement SANS influence
        // des autres segments.
        double [] segLen = new double [nWp - 1];
        double totalLen = 0;
        for (int s = 0; s < nWp - 1; s++)
        {
            segLen[s] = Math.hypot (waypoints[s+1][0] - waypoints[s][0],
                                     waypoints[s+1][1] - waypoints[s][1]);
            totalLen += segLen[s];
        }
        if (totalLen < 1e-9) return;

        // Repartir CPs par segment (proportionnel a la longueur)
        int [] cpPerSeg = new int [nWp - 1];
        int assigned = 0;
        for (int s = 0; s < nWp - 1; s++)
        {
            cpPerSeg[s] = Math.max (1, (int) Math.round (nCP * segLen[s] / totalLen));
            assigned += cpPerSeg[s];
        }
        // Ajuster si on a trop/pas assez de CPs
        while (assigned > nCP)
        {
            int longest = 0;
            for (int s = 1; s < nWp - 1; s++)
                if (cpPerSeg[s] > cpPerSeg[longest]) longest = s;
            if (cpPerSeg[longest] <= 1) break;
            cpPerSeg[longest]--;
            assigned--;
        }
        while (assigned < nCP)
        {
            int shortest = 0;
            for (int s = 1; s < nWp - 1; s++)
                if (cpPerSeg[s] < cpPerSeg[shortest]) shortest = s;
            cpPerSeg[shortest]++;
            assigned++;
        }

        // Generer le seed : CPs distribues dans chaque segment
        for (int v = 0; v < 6; v++)
        {
            double [] p = new double [d];
            int cpIdx = 0;
            for (int s = 0; s < nWp - 1; s++)
            {
                for (int c = 0; c < cpPerSeg[s] && cpIdx < nCP; c++)
                {
                    // Position relative dans le segment [0.1, 0.9] pour eviter
                    // d'etre exactement sur les waypoints
                    double t = (cpPerSeg[s] == 1) ? 0.5
                                : 0.1 + 0.8 * c / (cpPerSeg[s] - 1);
                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.0;
                    p[2 * cpIdx]     = waypoints[s][0] + t * (waypoints[s+1][0] - waypoints[s][0]) + noise;
                    p[2 * cpIdx + 1] = waypoints[s][1] + t * (waypoints[s+1][1] - waypoints[s][1]) + noise;
                    cpIdx++;
                }
            }
            addSeed (seeds, fits, p);
        }

        // Strategie B : CPs clusterises aux waypoints avec exageration
        // Pour chaque waypoint interieur, on place ~3 CPs au-dela du waypoint
        // (dans la direction du virage) pour compenser le lissage Bezier
        int nInner = nWp - 2; // waypoints interieurs (les gaps)
        int cpsPerGap = Math.max (2, nCP / (nInner + 1));
        int cpsTransit = nCP - cpsPerGap * nInner;

        for (double exagFactor : new double [] {1.2, 1.5, 2.0, 2.5})
        {
            for (int v = 0; v < 3; v++)
            {
                double [] p = new double [d];
                int cpIdx2 = 0;
                // CPs de transit avant le premier gap
                int transitBefore = cpsTransit / 2;
                for (int c = 0; c < transitBefore && cpIdx2 < nCP; c++)
                {
                    double t = (double) (c + 1) / (transitBefore + 1);
                    p[2 * cpIdx2]     = waypoints[0][0] + t * (waypoints[1][0] - waypoints[0][0]);
                    p[2 * cpIdx2 + 1] = waypoints[0][1] + t * (waypoints[1][1] - waypoints[0][1]);
                    cpIdx2++;
                }

                // CPs clusterises a chaque gap avec exageration
                for (int g = 0; g < nInner; g++)
                {
                    double gx = waypoints[g + 1][0];
                    double gy = waypoints[g + 1][1];
                    // Direction du virage : deviation par rapport a la ligne droite start→end
                    double midX = (waypoints[0][0] + waypoints[nWp-1][0]) / 2.0;
                    double midY = (waypoints[0][1] + waypoints[nWp-1][1]) / 2.0;
                    double devX = gx - midX;
                    double devY = gy - midY;

                    for (int c = 0; c < cpsPerGap && cpIdx2 < nCP; c++)
                    {
                        double frac = (cpsPerGap == 1) ? 0.0
                                    : (double) c / (cpsPerGap - 1) - 0.5; // -0.5 a +0.5
                        // Exagerer la position du waypoint
                        double exX = midX + devX * exagFactor;
                        double exY = midY + devY * exagFactor;
                        // Petit spread le long de l'axe x pour eviter le clustering exact
                        double spreadX = frac * (waypoints[Math.min (g+2, nWp-1)][0] - waypoints[g][0]) * 0.3;
                        double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.0;
                        p[2 * cpIdx2]     = exX + spreadX + noise;
                        p[2 * cpIdx2 + 1] = exY + noise;
                        cpIdx2++;
                    }
                }

                // CPs de transit apres le dernier gap
                while (cpIdx2 < nCP)
                {
                    double t = (double) (cpIdx2 - (nCP - cpsTransit + transitBefore) + 1)
                               / (cpsTransit - transitBefore + 1);
                    t = Math.max (0.1, Math.min (0.9, t));
                    p[2 * cpIdx2]     = waypoints[nWp-2][0]
                            + t * (waypoints[nWp-1][0] - waypoints[nWp-2][0]);
                    p[2 * cpIdx2 + 1] = waypoints[nWp-2][1]
                            + t * (waypoints[nWp-1][1] - waypoints[nWp-2][1]);
                    cpIdx2++;
                }

                addSeed (seeds, fits, p);
            }
        }
    }

    /**
     * Seeds "extreme" pour forcer un Bezier haut degre a traverser les gaps.
     *
     * Utilise la pseudo-inverse de la matrice de Bernstein pour resoudre
     * simultanement toutes les contraintes de passage par les gaps.
     * Pour nGaps contraintes et nCP inconnues (nGaps << nCP), on minimise
     * la distance aux CPs de reference tout en satisfaisant les contraintes.
     */
    private void addExtremeZigzagSeeds (ArrayList<double []> seeds, ArrayList<Double> fits,
                                          double [][] waypoints,
                                          double sx, double sy, double ex, double ey)
    {
        int nWp = waypoints.length;
        if (nWp < 4) return;
        int n = nCP + 1; // degre du Bezier

        // Parametres t pour chaque gap
        // Estimation basee sur la coordonnee x (la plus fiable car les CPs
        // de reference sont distribues lineairement en x de start a end)
        int nGaps = nWp - 2;
        double dxPath = ex - sx;
        if (Math.abs (dxPath) < 1e-9) return;

        double [] tGap = new double [nGaps];
        for (int g = 0; g < nGaps; g++)
            tGap[g] = Math.max (0.02, Math.min (0.98,
                        (waypoints[g + 1][0] - sx) / dxPath));

        // Matrice de Bernstein A[g][i] = B_{i+1}(tGap[g]) pour i=0..nCP-1
        // On ne corrige QUE les Y (les X restent sur la ligne droite)
        double [][] A = new double [nGaps][nCP];
        double [] residY = new double [nGaps];
        for (int g = 0; g < nGaps; g++)
        {
            double [] bw = bernsteinWeights (n, tGap[g]);
            for (int i = 0; i < nCP; i++)
                A[g][i] = bw[i + 1];
            residY[g] = waypoints[g + 1][1] - bw[0] * sy - bw[n] * ey;
        }

        // CPs de reference (ligne droite start→end)
        double [] refX = new double [nCP], refY = new double [nCP];
        for (int i = 0; i < nCP; i++)
        {
            double t = (double) (i + 1) / (nCP + 1);
            refX[i] = sx + t * (ex - sx);
            refY[i] = sy + t * (ey - sy);
        }

        // Residu Y : cible - contribution actuelle des CPs de reference
        double [] rY = new double [nGaps];
        for (int g = 0; g < nGaps; g++)
        {
            rY[g] = residY[g];
            for (int i = 0; i < nCP; i++)
                rY[g] -= A[g][i] * refY[i];
        }

        // Pseudo-inverse : deltaY = A^T * (A*A^T)^{-1} * rY
        double [][] AAT = new double [nGaps][nGaps];
        for (int g = 0; g < nGaps; g++)
            for (int h = 0; h < nGaps; h++)
                for (int i = 0; i < nCP; i++)
                    AAT[g][h] += A[g][i] * A[h][i];

        double [][] inv = invertMatrix (AAT, nGaps);
        if (inv == null) return;

        double [] lambdaY = new double [nGaps];
        for (int g = 0; g < nGaps; g++)
            for (int h = 0; h < nGaps; h++)
                lambdaY[g] += inv[g][h] * rY[h];

        double [] deltaY = new double [nCP];
        for (int i = 0; i < nCP; i++)
            for (int g = 0; g < nGaps; g++)
                deltaY[i] += A[g][i] * lambdaY[g];

        // DEBUG: afficher les t-values et delta Y
        StringBuilder dbg = new StringBuilder ("DEBUG extremeZigzag: tGap=");
        for (int g = 0; g < nGaps; g++)
            dbg.append (String.format ("%.3f ", tGap[g]));
        dbg.append (" deltaY range=[");
        double minDY = Double.MAX_VALUE, maxDY = -Double.MAX_VALUE;
        for (int i = 0; i < nCP; i++)
        {
            if (deltaY[i] < minDY) minDY = deltaY[i];
            if (deltaY[i] > maxDY) maxDY = deltaY[i];
        }
        dbg.append (String.format ("%.1f, %.1f", minDY, maxDY)).append ("]");
        System.err.println (dbg.toString ());

        // Generer les seeds avec differentes echelles
        double bestF = Double.POSITIVE_INFINITY;
        for (double scale : new double [] {0.5, 0.7, 0.85, 1.0, 1.15, 1.3, 1.5, 2.0})
        {
            for (int v = 0; v < 3; v++)
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    double noise = (v == 0) ? 0.0 : rng.nextGaussian () * 1.5;
                    p[2 * i]     = refX[i] + noise;
                    p[2 * i + 1] = refY[i] + deltaY[i] * scale + noise;
                }
                addSeed (seeds, fits, p);
                double lastF = fits.get (fits.size () - 1);
                if (lastF < bestF) bestF = lastF;
            }
        }
        System.err.println ("DEBUG extremeZigzag: bestF=" + String.format ("%.1f", bestF));
    }

    /** Inverse une matrice n×n par Gauss-Jordan. Retourne null si singuliere. */
    private static double [][] invertMatrix (double [][] m, int n)
    {
        double [][] a = new double [n][2 * n];
        for (int i = 0; i < n; i++)
        {
            System.arraycopy (m[i], 0, a[i], 0, n);
            a[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++)
        {
            int pivot = col;
            for (int row = col + 1; row < n; row++)
                if (Math.abs (a[row][col]) > Math.abs (a[pivot][col])) pivot = row;
            double [] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
            if (Math.abs (a[col][col]) < 1e-12) return null;
            double div = a[col][col];
            for (int j = 0; j < 2 * n; j++) a[col][j] /= div;
            for (int row = 0; row < n; row++)
            {
                if (row == col) continue;
                double factor = a[row][col];
                for (int j = 0; j < 2 * n; j++) a[row][j] -= factor * a[col][j];
            }
        }
        double [][] result = new double [n][n];
        for (int i = 0; i < n; i++)
            System.arraycopy (a[i], n, result[i], 0, n);
        return result;
    }

    /** Calcule les n+1 poids de Bernstein B_i,n(t) pour i=0..n. */
    private static double [] bernsteinWeights (int n, double t)
    {
        double [] w = new double [n + 1];
        // Log-space pour eviter overflow des combinaisons
        double [] logC = new double [n + 1];
        logC[0] = 0;
        for (int i = 1; i <= n; i++)
            logC[i] = logC[i - 1] + Math.log (n - i + 1) - Math.log (i);
        double logT = Math.log (Math.max (t, 1e-300));
        double log1T = Math.log (Math.max (1 - t, 1e-300));
        for (int i = 0; i <= n; i++)
            w[i] = Math.exp (logC[i] + i * logT + (n - i) * log1T);
        return w;
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
     * Compte le nombre total d'obstacles couverts par un ensemble de murs detectes.
     * Utilise pour comparer murs verticaux vs horizontaux.
     */
    private int countWallObstacles (double [] primary, double [] radii, int n,
                                     ArrayList<double []> walls)
    {
        int count = 0;
        for (double [] wall : walls)
        {
            double wallPos = wall[0];
            for (int i = 0; i < n; i++)
            {
                if (Math.abs (primary[i] - wallPos) < 1.5 * radii[i])
                    count++;
            }
        }
        return count;
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
                this.aStarWaypoints = kpArr;
                if (this.detectedWaypoints == null)
                    this.detectedWaypoints = kpArr;
                addHermiteSplineSeeds (seeds, fits, kpArr);
                addPolylineAnchorSeeds (seeds, fits, kpArr);
            }
            else
                this.aStarWaypoints = null;
        }
        else
            this.aStarWaypoints = null;
    }

    /**
     * Seeds ancrees sur les virages d'une polyline (souvent issue d'A*).
     * Idee: clusteriser des CPs autour des coins pour limiter les coupes
     * de virage dues au lissage Bezier de haut degre.
     */
    private void addPolylineAnchorSeeds (ArrayList<double []> seeds,
                                         ArrayList<Double> fits,
                                         double [][] waypoints)
    {
        if (waypoints == null || waypoints.length < 3) return;

        int nWp = waypoints.length;
        double [] cum = new double [nWp];
        cum[0] = 0.0;
        for (int i = 1; i < nWp; i++)
            cum[i] = cum[i - 1] + Math.hypot (
                    waypoints[i][0] - waypoints[i - 1][0],
                    waypoints[i][1] - waypoints[i - 1][1]);
        double total = cum[nWp - 1];
        if (total < 1e-9) return;

        int [] clusterRadii = (pathComplexity > 1.6)
                ? new int [] {2, 3, 4}
                : new int [] {1, 2, 3};
        for (int radius : clusterRadii)
        {
            for (int variant = 0; variant < 2; variant++)
            {
                double [] p = new double [d];

                // 1) Base: interpolation reguliere le long de la polyline.
                for (int cp = 0; cp < nCP; cp++)
                {
                    double target = ((double) (cp + 1) / (nCP + 1)) * total;
                    int seg = 1;
                    while (seg < nWp && cum[seg] < target) seg++;
                    seg = Math.max (1, Math.min (seg, nWp - 1));
                    double den = Math.max (1e-9, cum[seg] - cum[seg - 1]);
                    double a = (target - cum[seg - 1]) / den;
                    p[2 * cp] = (1.0 - a) * waypoints[seg - 1][0] + a * waypoints[seg][0];
                    p[2 * cp + 1] = (1.0 - a) * waypoints[seg - 1][1] + a * waypoints[seg][1];
                }

                // 2) Ancrage des CPs autour des virages interieurs.
                for (int w = 1; w < nWp - 1; w++)
                {
                    double t = cum[w] / total;
                    int center = (int) Math.round (t * (nCP + 1)) - 1;
                    center = Math.max (0, Math.min (nCP - 1, center));
                    double wx = waypoints[w][0];
                    double wy = waypoints[w][1];
                    for (int off = -radius; off <= radius; off++)
                    {
                        int cp = center + off;
                        if (cp < 0 || cp >= nCP) continue;
                        double frac = Math.abs (off) / (double) (radius + 1);
                        double noise = (variant == 0) ? 0.0 : rng.nextGaussian () * 0.10 * (1.0 + frac);
                        p[2 * cp] = wx + noise;
                        p[2 * cp + 1] = wy + noise;
                    }
                }

                repelControlPointsFromObstacles (p, 3);
                clamp (p);
                addSeed (seeds, fits, p);
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
            else if (noFeasible && aStarWaypoints != null && aStarWaypoints.length >= 3 && (k % 3) == 2)
            {
                p = generateRescueSeedFromWaypoints (aStarWaypoints);
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
        return generateRescueSeedFromWaypoints (detectedWaypoints);
    }

    private double [] generateRescueSeedFromWaypoints (double [][] waypoints)
    {
        double [] p = new double [d];
        if (waypoints == null || waypoints.length < 2)
            return defaultMean ();
        int nWp = waypoints.length;

        // Calculer longueurs des segments
        double totalLen = 0.0;
        double [] cumLen = new double [nWp];
        cumLen[0] = 0.0;
        for (int s = 1; s < nWp; s++)
        {
            cumLen[s] = cumLen[s-1] + Math.hypot (
                    waypoints[s][0] - waypoints[s-1][0],
                    waypoints[s][1] - waypoints[s-1][1]);
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
            p[2 * i] = (1.0 - a) * waypoints[seg-1][0]
                    + a * waypoints[seg][0]
                    + rng.nextGaussian () * noiseScale;
            p[2 * i + 1] = (1.0 - a) * waypoints[seg-1][1]
                    + a * waypoints[seg][1]
                    + rng.nextGaussian () * noiseScale;
        }
        return p;
    }

    /**
     * Raffinement court du meilleur incumbent connu (faisable ou non).
     * Objectif: gagner des points en fin de run sans attendre un nouveau restart.
     */
    private void maybeRefineIncumbent ()
    {
        if (!cmaesReady) return;
        long now = System.currentTimeMillis ();
        long elapsed = now - startTime;
        boolean specialized = mapDiagnostics != null && mapDiagnostics.shouldEnableSpecializedZigzag ();
        if (!specialized) return;
        double startRatio = specialized ? 0.28 : 0.55;
        if (elapsed < (long) (startRatio * TOTAL_TIME_MS)) return;

        long cooldown = specialized
                ? ((elapsed > (long) (0.78 * TOTAL_TIME_MS)) ? 18L : 35L)
                : ((elapsed > (long) (0.78 * TOTAL_TIME_MS)) ? 40L : 70L);
        if (lastIncumbentRefineMs != 0L && now - lastIncumbentRefineMs < cooldown) return;

        double [] anchor = null;
        double anchorFit = Double.POSITIVE_INFINITY;

        if (globalBestX != null && Double.isFinite (globalBestFitness))
        {
            anchor = globalBestX.clone ();
            anchorFit = globalBestFitness;
        }
        if (cmaesSmall != null)
        {
            double [] x = cmaesSmall.getBestX ();
            double f = cmaesSmall.getBestFitness ();
            if (x != null && Double.isFinite (f) && f < anchorFit)
            {
                anchor = x.clone ();
                anchorFit = f;
            }
        }
        if (cmaesWide != null)
        {
            double [] x = cmaesWide.getBestX ();
            double f = cmaesWide.getBestFitness ();
            if (x != null && Double.isFinite (f) && f < anchorFit)
            {
                anchor = x.clone ();
                anchorFit = f;
            }
        }
        if (bestFeasibleX != null && Double.isFinite (bestFeasibleFitness) && bestFeasibleFitness < anchorFit)
        {
            anchor = bestFeasibleX.clone ();
            anchorFit = bestFeasibleFitness;
        }
        if (anchor == null) return;

        clamp (anchor);
        double before = evaluateAndTrack (anchor);
        double bestLocal = before;
        double [] bestX = anchor.clone ();

        int tries = specialized
                ? ((elapsed > (long) (0.80 * TOTAL_TIME_MS)) ? 4 : 2)
                : ((elapsed > (long) (0.80 * TOTAL_TIME_MS)) ? 2 : 1);
        for (int t = 0; t < tries; t++)
        {
            double [] p = bestX.clone ();
            double step = incumbentRefineSigma * (1.0 + 0.25 * t);
            int edits = 1 + rng.nextInt (Math.max (2, nCP / 2));
            for (int e = 0; e < edits; e++)
            {
                int cp = rng.nextInt (nCP);
                int ix = 2 * cp;
                p[ix] += rng.nextGaussian () * step;
                p[ix + 1] += rng.nextGaussian () * step;
            }
            if (nObs > 0 && (bestFeasibleX == null || rng.nextDouble () < 0.7))
                repelControlPointsFromObstacles (p, 1);
            clamp (p);
            double f = evaluateAndTrack (p);
            if (f + 1e-9 < bestLocal)
            {
                bestLocal = f;
                bestX = p;
            }
        }

        // Mini-pattern search autour d'un CP, surtout utile en fin de budget.
        if (elapsed > (long) (0.55 * TOTAL_TIME_MS))
        {
            int cp = rng.nextInt (nCP);
            int ix = 2 * cp;
            double s = incumbentRefineSigma * 0.7;
            double [][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            for (double [] dir : dirs)
            {
                double [] p = bestX.clone ();
                p[ix] += dir[0] * s;
                p[ix + 1] += dir[1] * s;
                clamp (p);
                double f = evaluateAndTrack (p);
                if (f + 1e-9 < bestLocal)
                {
                    bestLocal = f;
                    bestX = p;
                }
            }
        }

        if (bestLocal + 1e-9 < before)
            incumbentRefineSigma = Math.min (domainDiag * 0.06, incumbentRefineSigma * 1.06);
        else
            incumbentRefineSigma = Math.max (domainDiag * 0.0006, incumbentRefineSigma * 0.992);
        lastIncumbentRefineMs = now;
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

        addToFeasibleArchive (p, fitness);

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

    private void addToFeasibleArchive (double [] p, double fitness)
    {
        double minDist = Math.max (0.20, domainDiag * 0.03);
        int nearestIdx = -1;
        double nearestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < feasibleArchive.size (); i++)
        {
            double dist = distanceNorm (p, feasibleArchive.get (i));
            if (dist < nearestDist)
            {
                nearestDist = dist;
                nearestIdx = i;
            }
        }

        if (nearestIdx >= 0 && nearestDist < minDist)
        {
            if (fitness < feasibleArchiveFitness.get (nearestIdx))
            {
                feasibleArchive.set (nearestIdx, p.clone ());
                feasibleArchiveFitness.set (nearestIdx, fitness);
            }
            return;
        }

        feasibleArchive.add (p.clone ());
        feasibleArchiveFitness.add (fitness);
        if (feasibleArchive.size () <= FEASIBLE_ARCHIVE_MAX) return;

        int worstIdx = 0;
        double worstFit = feasibleArchiveFitness.get (0);
        for (int i = 1; i < feasibleArchiveFitness.size (); i++)
        {
            if (feasibleArchiveFitness.get (i) > worstFit)
            {
                worstFit = feasibleArchiveFitness.get (i);
                worstIdx = i;
            }
        }
        feasibleArchive.remove (worstIdx);
        feasibleArchiveFitness.remove (worstIdx);
    }

    private double distanceNorm (double [] a, double [] b)
    {
        int n = Math.min (a.length, b.length);
        if (n <= 0) return Double.POSITIVE_INFINITY;
        double sum = 0.0;
        for (int i = 0; i < n; i++)
        {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt (sum / n);
    }

    private boolean isLikelyFeasible (double [] p)
    {
        if (p == null || p.length != d) return false;

        // 1) Filtre rapide sur les points de controle (lenient).
        for (int i = 0; i < nCP; i++)
        {
            double x = p[2 * i];
            double y = p[2 * i + 1];
            if (x < problem.getMinX () || x > problem.getMaxX ()) return false;
            if (y < problem.getMinY () || y > problem.getMaxY ()) return false;
            for (int j = 0; j < nObs; j++)
            {
                double dx = x - obsX[j];
                double dy = y - obsY[j];
                double rr = obsR[j] * 0.90;
                if (dx * dx + dy * dy <= rr * rr) return false;
            }
        }

        // 2) Echantillonnage grossier de la courbe pour eviter les faux positifs flagrants.
        bezier2_0.evaluation.Coordinates [] traj = problem.computeTrajectory (p);
        if (traj == null || traj.length == 0) return false;
        int stride = 20; // ~50 points sur 1000
        for (int idx = 0; idx < traj.length; idx += stride)
        {
            double x = traj[idx].getX ();
            double y = traj[idx].getY ();
            if (x < problem.getMinX () || x > problem.getMaxX ()) return false;
            if (y < problem.getMinY () || y > problem.getMaxY ()) return false;
            for (int j = 0; j < nObs; j++)
            {
                double dx = x - obsX[j];
                double dy = y - obsY[j];
                double rr = obsR[j] * 0.90;
                if (dx * dx + dy * dy <= rr * rr) return false;
            }
        }

        return true;
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
