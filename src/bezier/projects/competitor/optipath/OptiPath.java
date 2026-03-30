package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.ArrayList;
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

    /** Fraction du temps pour le seeding initial. */
    private static final double SEED_RATIO = 0.10;

    /** Strategie de restart externe (remplace le seuil fixe 500). */
    private final OuterRestartStrategy restartStrategy;

    // ================================================================
    //  ETAT
    // ================================================================
    private int d, nCP;
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
        this (problem, 5.0, 15.0, HybridMultiSignalRestart.v1 ());
    }

    OptiPath (Problem problem, OuterRestartStrategy strategy)
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
        stepSmallNext = true;
        restartCount = 0;

        nCP = problem.getNControlPoints ();
        d = 2 * nCP;
        lbSmall = buildLb (marginSmall);
        ubSmall = buildUb (marginSmall);
        lbWide  = buildLb (marginWide);
        ubWide  = buildUb (marginWide);
        domainDiag = Math.hypot (problem.getMaxX () - problem.getMinX (),
                                 problem.getMaxY () - problem.getMinY ());
        if (domainDiag <= 0.0) domainDiag = 1.0;
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
            localCurrentF = problem.evaluate (localCurrent);
            trackBest (localCurrentF);
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

        // Trier et garder top-15
        Integer [] idx = new Integer [allSeeds.size ()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort (idx, (a,b) -> Double.compare (allFitness.get(a), allFitness.get(b)));

        topSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (15, idx.length); i++)
            topSeeds.add (allSeeds.get (idx[i]).clone ());

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
        double f = problem.evaluate (p);
        trackBest (f);
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

        // Alterner small/wide
        if (stepSmallNext) cmaesSmall.step ();
        else cmaesWide.step ();
        stepSmallNext = !stepSmallNext;

        // Tracker le meilleur (CMA-ES met a jour problem en interne)
        double prev = globalBestFitness;
        globalBestFitness = Math.min (globalBestFitness, problem.getBestEvaluation ());
        if (globalBestFitness < prev)
            restartStrategy.onFitnessUpdate (globalBestFitness, System.currentTimeMillis ());
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
        double fNew = problem.evaluate (xNew);
        trackBest (fNew);

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
                localCurrentF = problem.evaluate (localCurrent);
                trackBest (localCurrentF);
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
    //  Helpers
    // ================================================================

    private double [] makePath (double x, double y)
    { double[]p=new double[d]; for(int i=0;i<nCP;i++){p[2*i]=x;p[2*i+1]=y;} return p; }

    private void clamp (double [] p)
    { for(int i=0;i<d;i++){if(p[i]<lbWide[i])p[i]=lbWide[i];if(p[i]>ubWide[i])p[i]=ubWide[i];} }

    private void trackBest (double f)
    { if(f<globalBestFitness) globalBestFitness=f; }

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
