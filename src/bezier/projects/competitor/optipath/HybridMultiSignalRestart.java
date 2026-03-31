package bezier.projects.competitor.optipath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Restart multi-signaux sans seuil absolu de fitness.
 *
 * V1 : dynamique + frustration
 * V2 : V1 + signal geometrique/topologique
 * V3 : V2 + selection d'action via UCB en ligne
 */
public class HybridMultiSignalRestart implements OuterRestartStrategy
{
    public enum Variant
    {
        V1_DYN_FRUST,
        V2_DYN_FRUST_GEO,
        V3_FULL_UCB
    }

    private static final double EPS = 1e-12;
    private static final long MIN_RUN_MS_EASY = 3_000;
    private static final long MIN_RUN_MS_HARD = 5_000;
    private static final long COOLDOWN_MS = 1_500;
    private static final long STUCK_FORCE_MS_EASY = 18_000;
    private static final long STUCK_FORCE_MS_HARD = 25_000;
    private static final long UCB_HORIZON_MS = 2_000;
    private static final double MAX_RUN_RATIO = 0.22;
    private static final double HARD_RATIO_THRESHOLD = 0.70;

    /** Seuils effectifs adaptes a la difficulte du probleme. */
    private long effectiveMinRunMs = MIN_RUN_MS_EASY;
    private long effectiveStuckMs = STUCK_FORCE_MS_EASY;

    private static final double ALPHA_R = 0.25;
    private static final double ALPHA_F = 0.20;
    private static final double UCB_C = 0.85;

    private static final int SIG_SAMPLES = 32;
    private static final int SIG_BINS = 8;
    private static final int CURV_BINS = 5;

    private static final int ACT_SMALL = 0;
    private static final int ACT_WIDE = 1;
    private static final int ACT_BOTH = 2;

    private final Variant variant;

    // Historique online
    private final RingStats positiveReturns = new RingStats (192);
    private final RingStats accelHist = new RingStats (192);
    private final RingStats sigmaMoveHist = new RingStats (192);
    private final RingStats frustHist = new RingStats (192);
    private final RingStats hazHist = new RingStats (192);
    private final RingStats launchGainHist = new RingStats (192);

    // Geometrie/topologie
    private final RingStats dispHist = new RingStats (160);
    private final RingStats driftHist = new RingStats (160);
    private final RingStats noveltyHist = new RingStats (160);
    private double [] prevGlobalBestX;
    private int [] prevShapeSig;

    // EWMAs
    private double ewmaReturn;
    private double prevEwmaReturn;
    private double ewmaFrustration;

    // Memo temporaire
    private double prevGlobalBestFitness;
    private double prevSigmaSmall;
    private double prevSigmaWide;

    // Timing
    private long lastImprovementMs;
    private long lastDecisionMs;

    // Bandit UCB
    private final double [] banditMu = new double [] {0.0, 0.0, 0.0};
    private final int [] banditN = new int [] {0, 0, 0};
    private int banditT = 0;
    private final RingStats improvementHist = new RingStats (192);
    private final ArrayList<PendingReward> pendingRewards = new ArrayList<> ();

    private static class PendingReward
    {
        final int action;
        final long dueMs;
        final double fitnessBefore;

        PendingReward (int action, long dueMs, double fitnessBefore)
        {
            this.action = action;
            this.dueMs = dueMs;
            this.fitnessBefore = fitnessBefore;
        }
    }

    public HybridMultiSignalRestart ()
    {
        this (Variant.V3_FULL_UCB);
    }

    public HybridMultiSignalRestart (Variant variant)
    {
        this.variant = variant;
    }

    public static HybridMultiSignalRestart v1 ()
    {
        return new HybridMultiSignalRestart (Variant.V1_DYN_FRUST);
    }

    public static HybridMultiSignalRestart v2 ()
    {
        return new HybridMultiSignalRestart (Variant.V2_DYN_FRUST_GEO);
    }

    public static HybridMultiSignalRestart v3 ()
    {
        return new HybridMultiSignalRestart (Variant.V3_FULL_UCB);
    }

    @Override
    public void init (SeedingStats stats)
    {
        resetAllState ();
        // Adapter les seuils a la difficulte du probleme
        if (stats != null && stats.percentile25 > 0.0)
        {
            double ratio = stats.bestFitness / stats.percentile25;
            if (ratio > HARD_RATIO_THRESHOLD)
            {
                effectiveMinRunMs = MIN_RUN_MS_HARD;
                effectiveStuckMs = STUCK_FORCE_MS_HARD;
            }
            else
            {
                effectiveMinRunMs = MIN_RUN_MS_EASY;
                effectiveStuckMs = STUCK_FORCE_MS_EASY;
            }
        }
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        lastImprovementMs = timestampMs;
    }

    @Override
    public void onRestart ()
    {
        resetLaunchState (System.currentTimeMillis ());
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        updateBanditRewards (ctx);

        if (ctx.sinceLaunchMs < effectiveMinRunMs)
        {
            updateSignals (ctx);
            return Decision.CONTINUE;
        }

        if (ctx.nowMs - lastImprovementMs >= effectiveStuckMs)
        {
            updateSignals (ctx);
            lastDecisionMs = ctx.nowMs;
            return Decision.RESTART_BOTH;
        }

        if (ctx.nowMs - lastDecisionMs < COOLDOWN_MS)
        {
            updateSignals (ctx);
            return Decision.CONTINUE;
        }

        SignalPack sp = updateSignals (ctx);
        long adaptiveMaxRun = Math.max (3_500L, (long) (MAX_RUN_RATIO * ctx.totalBudgetMs));
        if (ctx.sinceLaunchMs > adaptiveMaxRun)
        {
            double lg = launchGain (ctx);
            double medGain = launchGainHist.quantile (0.50, lg);
            double q25Return = positiveReturns.quantile (0.25, 0.0);
            if (launchGainHist.size () >= 8
                    && (lg < 0.5 * medGain || ewmaReturn < 0.5 * q25Return))
            {
                lastDecisionMs = ctx.nowMs;
                return Decision.RESTART_BOTH;
            }
        }

        double threshold = hazardThreshold ();
        boolean trigger = hazHist.size () >= 12 && sp.hazard > threshold;

        if (!trigger)
            return Decision.CONTINUE;

        return decideAction (ctx, false);
    }

    private void resetAllState ()
    {
        resetLaunchState (System.currentTimeMillis ());
        Arrays.fill (banditMu, 0.0);
        Arrays.fill (banditN, 0);
        banditT = 0;
        improvementHist.clear ();
        pendingRewards.clear ();
    }

    private void resetLaunchState (long nowMs)
    {
        positiveReturns.clear ();
        accelHist.clear ();
        sigmaMoveHist.clear ();
        frustHist.clear ();
        hazHist.clear ();
        launchGainHist.clear ();
        dispHist.clear ();
        driftHist.clear ();
        noveltyHist.clear ();

        ewmaReturn = 0.0;
        prevEwmaReturn = 0.0;
        ewmaFrustration = 0.0;

        prevGlobalBestFitness = Double.NaN;
        prevSigmaSmall = Double.NaN;
        prevSigmaWide = Double.NaN;
        prevGlobalBestX = null;
        prevShapeSig = null;

        lastImprovementMs = nowMs;
        lastDecisionMs = nowMs;
    }

    private static class SignalPack
    {
        final double dyn;
        final double geo;
        final double frust;
        final double hazard;

        SignalPack (double dyn, double geo, double frust, double hazard)
        {
            this.dyn = dyn;
            this.geo = geo;
            this.frust = frust;
            this.hazard = hazard;
        }
    }

    private SignalPack updateSignals (OuterRestartContext ctx)
    {
        double prevBest = Double.isFinite (prevGlobalBestFitness) ? prevGlobalBestFitness : ctx.fitnessAtLaunch;
        if (!Double.isFinite (prevBest) || prevBest <= 0.0)
            prevBest = ctx.globalBestFitness;

        double r = Math.log ((prevBest + EPS) / (ctx.globalBestFitness + EPS));
        ewmaReturn = ALPHA_R * r + (1.0 - ALPHA_R) * ewmaReturn;
        double accel = ewmaReturn - prevEwmaReturn;
        prevEwmaReturn = ewmaReturn;

        if (r > 0.0) positiveReturns.add (r);
        accelHist.add (accel);

        double q25Pos = positiveReturns.quantile (0.25, 0.0);
        double iqrPos = Math.max (positiveReturns.iqr (1e-6), 1e-6);
        double madA = Math.max (accelHist.mad (1e-6), 1e-6);
        double sDyn = sigmoid ((q25Pos - ewmaReturn) / iqrPos)
                * sigmoid ((-accel) / madA);

        double sigmaMove = 0.0;
        if (Double.isFinite (prevSigmaSmall) && Double.isFinite (prevSigmaWide)
                && prevSigmaSmall > 0.0 && prevSigmaWide > 0.0
                && ctx.sigmaSmall > 0.0 && ctx.sigmaWide > 0.0)
        {
            sigmaMove = Math.abs (Math.log ((ctx.sigmaSmall + EPS) / (prevSigmaSmall + EPS)))
                    + Math.abs (Math.log ((ctx.sigmaWide + EPS) / (prevSigmaWide + EPS)));
        }
        sigmaMoveHist.add (sigmaMove);
        double q50SigmaMove = sigmaMoveHist.quantile (0.50, sigmaMove);
        double q50Pos = positiveReturns.quantile (0.50, 0.0);
        boolean success = q50Pos > 0.0 ? (r > 0.25 * q50Pos) : (r > 0.0);
        double frustrated = (sigmaMove > q50SigmaMove && !success) ? 1.0 : 0.0;
        ewmaFrustration = ALPHA_F * frustrated + (1.0 - ALPHA_F) * ewmaFrustration;
        frustHist.add (ewmaFrustration);
        double sFrust = highSignal (ewmaFrustration, frustHist);

        double sGeo = 0.0;
        if (variant != Variant.V1_DYN_FRUST)
            sGeo = computeGeoSignal (ctx);

        double hazard;
        if (variant == Variant.V1_DYN_FRUST)
            hazard = 0.65 * sDyn + 0.35 * sFrust;
        else
            hazard = 0.45 * sDyn + 0.30 * sGeo + 0.25 * sFrust;
        hazHist.add (hazard);
        launchGainHist.add (launchGain (ctx));

        prevGlobalBestFitness = ctx.globalBestFitness;
        prevSigmaSmall = ctx.sigmaSmall;
        prevSigmaWide = ctx.sigmaWide;

        return new SignalPack (sDyn, sGeo, sFrust, hazard);
    }

    private double computeGeoSignal (OuterRestartContext ctx)
    {
        double disp = 1.0;
        if (ctx.bestXSmall != null && ctx.bestXWide != null)
            disp = normDistance (ctx.bestXSmall, ctx.bestXWide)
                    / Math.max (ctx.domainDiag, 1e-9);
        dispHist.add (disp);

        double [] globalBestX = pickGlobalBestX (ctx);
        double drift = 1.0;
        if (globalBestX != null && prevGlobalBestX != null)
            drift = normDistance (globalBestX, prevGlobalBestX)
                    / Math.max (ctx.domainDiag, 1e-9);
        driftHist.add (drift);

        int [] sig = buildShapeSignature (globalBestX, ctx);
        double novelty = 1.0;
        if (sig != null && prevShapeSig != null && sig.length == prevShapeSig.length)
            novelty = hammingNovelty (sig, prevShapeSig);
        noveltyHist.add (novelty);

        prevGlobalBestX = globalBestX != null ? globalBestX.clone () : prevGlobalBestX;
        prevShapeSig = sig;

        double sDisp = lowSignal (disp, dispHist);
        double sDrift = lowSignal (drift, driftHist);
        double sNov = lowSignal (novelty, noveltyHist);
        return (sDisp + sDrift + sNov) / 3.0;
    }

    private Decision decideAction (OuterRestartContext ctx, boolean forced)
    {
        int action = selectActionIndex (ctx, forced);
        lastDecisionMs = ctx.nowMs;

        if (variant == Variant.V3_FULL_UCB)
            pendingRewards.add (new PendingReward (action, ctx.nowMs + UCB_HORIZON_MS, ctx.globalBestFitness));

        switch (action)
        {
            case ACT_SMALL: return Decision.RESTART_SMALL;
            case ACT_WIDE: return Decision.RESTART_WIDE;
            default: return Decision.RESTART_BOTH;
        }
    }

    private int selectActionIndex (OuterRestartContext ctx, boolean forced)
    {
        int heuristic = heuristicAction (ctx);
        if (variant != Variant.V3_FULL_UCB)
            return heuristic;

        if (forced)
            return ACT_BOTH;

        double smallRate = ctx.deltaBestSmall / Math.max (1, ctx.deltaEvalSmall);
        double wideRate = ctx.deltaBestWide / Math.max (1, ctx.deltaEvalWide);
        double maxRate = Math.max (smallRate, wideRate);
        double minRate = Math.min (smallRate, wideRate);
        if (ctx.sinceLaunchMs > 4_500 && maxRate > 0.0 && minRate < 0.20 * maxRate)
            return (smallRate < wideRate) ? ACT_SMALL : ACT_WIDE;

        double [] ucb = new double [3];
        for (int a = 0; a < 3; a++)
        {
            double bonus = UCB_C * Math.sqrt (Math.log (1.0 + banditT + 1.0)
                    / (1.0 + banditN [a]));
            ucb [a] = banditMu [a] + bonus;
        }
        ucb [ACT_BOTH] += 0.12;
        ucb [heuristic] += 0.10;

        int best = 0;
        if (ucb [1] > ucb [best]) best = 1;
        if (ucb [2] > ucb [best]) best = 2;
        return best;
    }

    private int heuristicAction (OuterRestartContext ctx)
    {
        if (ctx.bestXSmall == null && ctx.bestXWide != null) return ACT_SMALL;
        if (ctx.bestXWide == null && ctx.bestXSmall != null) return ACT_WIDE;

        double smallRate = ctx.deltaBestSmall / Math.max (1, ctx.deltaEvalSmall);
        double wideRate = ctx.deltaBestWide / Math.max (1, ctx.deltaEvalWide);

        if (smallRate > 0.0 && wideRate > 0.0)
        {
            if (smallRate < 0.25 * wideRate) return ACT_SMALL;
            if (wideRate < 0.25 * smallRate) return ACT_WIDE;
        }

        if (ctx.bestXSmall != null && ctx.bestXWide != null)
        {
            double disp = normDistance (ctx.bestXSmall, ctx.bestXWide)
                    / Math.max (ctx.domainDiag, 1e-9);
            if (disp < 0.03)
                return (ctx.bestFitnessSmall > ctx.bestFitnessWide) ? ACT_SMALL : ACT_WIDE;
        }

        return ACT_BOTH;
    }

    private void updateBanditRewards (OuterRestartContext ctx)
    {
        if (variant != Variant.V3_FULL_UCB || pendingRewards.isEmpty ())
            return;

        Iterator<PendingReward> it = pendingRewards.iterator ();
        while (it.hasNext ())
        {
            PendingReward p = it.next ();
            if (ctx.nowMs < p.dueMs)
                continue;

            double delta = Math.max (0.0, p.fitnessBefore - ctx.globalBestFitness);
            improvementHist.add (delta);
            double scale = Math.max (improvementHist.mad (1e-6), 1e-6);
            double reward = delta / scale;

            int a = p.action;
            banditT++;
            banditN [a]++;
            banditMu [a] += (reward - banditMu [a]) / banditN [a];
            it.remove ();
        }
    }

    private double hazardThreshold ()
    {
        if (hazHist.size () < 12)
            return Double.POSITIVE_INFINITY;
        return hazHist.quantile (0.80, 1.0);
    }

    private double [] pickGlobalBestX (OuterRestartContext ctx)
    {
        if (ctx.bestXSmall == null) return ctx.bestXWide;
        if (ctx.bestXWide == null) return ctx.bestXSmall;
        return (ctx.bestFitnessSmall <= ctx.bestFitnessWide) ? ctx.bestXSmall : ctx.bestXWide;
    }

    private int [] buildShapeSignature (double [] bestX, OuterRestartContext ctx)
    {
        if (bestX == null || bestX.length < 2) return null;
        int nCP = bestX.length / 2;
        int n = nCP + 2;
        double [] px = new double [n];
        double [] py = new double [n];
        px [0] = ctx.startX; py [0] = ctx.startY;
        for (int i = 0; i < nCP; i++)
        {
            px [i + 1] = bestX [2 * i];
            py [i + 1] = bestX [2 * i + 1];
        }
        px [n - 1] = ctx.endX; py [n - 1] = ctx.endY;

        double [] sx = new double [SIG_SAMPLES];
        double [] sy = new double [SIG_SAMPLES];
        for (int i = 0; i < SIG_SAMPLES; i++)
        {
            double t = (SIG_SAMPLES == 1) ? 0.0 : (double) i / (SIG_SAMPLES - 1);
            double [] p = evalBezier (px, py, t);
            sx [i] = p [0];
            sy [i] = p [1];
        }

        int [] sig = new int [SIG_SAMPLES];
        double minX = ctx.lbWide != null && ctx.lbWide.length > 0 ? ctx.lbWide [0] : ctx.startX;
        double maxX = ctx.ubWide != null && ctx.ubWide.length > 0 ? ctx.ubWide [0] : ctx.endX;
        double minY = ctx.lbWide != null && ctx.lbWide.length > 1 ? ctx.lbWide [1] : ctx.startY;
        double maxY = ctx.ubWide != null && ctx.ubWide.length > 1 ? ctx.ubWide [1] : ctx.endY;
        double rangeX = Math.max (maxX - minX, 1e-9);
        double rangeY = Math.max (maxY - minY, 1e-9);

        for (int i = 0; i < SIG_SAMPLES; i++)
        {
            double nx = (sx [i] - minX) / rangeX;
            double ny = (sy [i] - minY) / rangeY;
            nx = clamp01 (nx);
            ny = clamp01 (ny);
            int bx = Math.min (SIG_BINS - 1, (int) (nx * SIG_BINS));
            int by = Math.min (SIG_BINS - 1, (int) (ny * SIG_BINS));
            int spatialCode = by * SIG_BINS + bx;

            double kappa = 0.0;
            if (i > 0 && i < SIG_SAMPLES - 1)
                kappa = signedTurn (sx [i - 1], sy [i - 1], sx [i], sy [i], sx [i + 1], sy [i + 1]);
            int curvCode = curvatureBin (kappa);
            sig [i] = spatialCode * CURV_BINS + curvCode;
        }
        return sig;
    }

    private static double [] evalBezier (double [] px, double [] py, double t)
    {
        int n = px.length - 1;
        double omt = 1.0 - t;
        double x = 0.0, y = 0.0;
        for (int k = 0; k <= n; k++)
        {
            double c = binomial (n, k) * Math.pow (t, k) * Math.pow (omt, n - k);
            x += c * px [k];
            y += c * py [k];
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

    private static double signedTurn (double ax, double ay, double bx, double by, double cx, double cy)
    {
        double v1x = bx - ax, v1y = by - ay;
        double v2x = cx - bx, v2y = cy - by;
        double n1 = Math.hypot (v1x, v1y);
        double n2 = Math.hypot (v2x, v2y);
        if (n1 < 1e-12 || n2 < 1e-12) return 0.0;
        double cross = v1x * v2y - v1y * v2x;
        double dot = v1x * v2x + v1y * v2y;
        return Math.atan2 (cross, dot) / Math.PI;
    }

    private static int curvatureBin (double kappa)
    {
        if (kappa <= -0.30) return 0;
        if (kappa < -0.02) return 1;
        if (kappa <= 0.02) return 2;
        if (kappa < 0.30) return 3;
        return 4;
    }

    private static double hammingNovelty (int [] a, int [] b)
    {
        int same = 0;
        int n = Math.min (a.length, b.length);
        for (int i = 0; i < n; i++)
            if (a [i] == b [i]) same++;
        double similarity = (n > 0) ? ((double) same / n) : 0.0;
        return 1.0 - similarity;
    }

    private static double normDistance (double [] a, double [] b)
    {
        if (a == null || b == null) return 0.0;
        int n = Math.min (a.length, b.length);
        double s = 0.0;
        for (int i = 0; i < n; i++)
        {
            double d = a [i] - b [i];
            s += d * d;
        }
        return Math.sqrt (s);
    }

    private static double sigmoid (double z)
    {
        if (z >= 25.0) return 1.0;
        if (z <= -25.0) return 0.0;
        return 1.0 / (1.0 + Math.exp (-z));
    }

    private static double lowSignal (double current, RingStats hist)
    {
        double q25 = hist.quantile (0.25, current);
        double iqr = Math.max (hist.iqr (1e-6), 1e-6);
        return sigmoid ((q25 - current) / iqr);
    }

    private static double highSignal (double current, RingStats hist)
    {
        double q75 = hist.quantile (0.75, current);
        double iqr = Math.max (hist.iqr (1e-6), 1e-6);
        return sigmoid ((current - q75) / iqr);
    }

    private static double clamp01 (double x)
    {
        if (x < 0.0) return 0.0;
        if (x > 0.999999) return 0.999999;
        return x;
    }

    private static double launchGain (OuterRestartContext ctx)
    {
        double den = Math.abs (ctx.fitnessAtLaunch) + EPS;
        return (ctx.fitnessAtLaunch - ctx.globalBestFitness) / den;
    }

    private static class RingStats
    {
        private final double [] data;
        private int head;
        private int size;

        RingStats (int capacity)
        {
            this.data = new double [Math.max (8, capacity)];
            this.head = 0;
            this.size = 0;
        }

        void clear ()
        {
            head = 0;
            size = 0;
        }

        void add (double v)
        {
            data [head] = v;
            head = (head + 1) % data.length;
            if (size < data.length) size++;
        }

        int size ()
        {
            return size;
        }

        double quantile (double q, double fallback)
        {
            if (size == 0) return fallback;
            double [] arr = snapshot ();
            Arrays.sort (arr);
            double qq = Math.max (0.0, Math.min (1.0, q));
            int idx = (int) Math.round (qq * (arr.length - 1));
            return arr [idx];
        }

        double iqr (double fallback)
        {
            if (size < 4) return fallback;
            double q75 = quantile (0.75, fallback);
            double q25 = quantile (0.25, fallback);
            return Math.max (q75 - q25, fallback);
        }

        double mad (double fallback)
        {
            if (size < 4) return fallback;
            double med = quantile (0.50, fallback);
            double [] arr = snapshot ();
            for (int i = 0; i < arr.length; i++)
                arr [i] = Math.abs (arr [i] - med);
            Arrays.sort (arr);
            double mad = arr [arr.length / 2];
            return Math.max (mad, fallback);
        }

        private double [] snapshot ()
        {
            double [] out = new double [size];
            int start = (head - size + data.length) % data.length;
            for (int i = 0; i < size; i++)
                out [i] = data [(start + i) % data.length];
            return out;
        }
    }
}
