package bezier.projects.competitor.optipath;

/**
 * T13 : indice de frustration des ajustements de pas.
 *
 * Le restart est declenche quand les variations de sigma et de direction
 * s'accumulent sans gains relatifs suffisants.
 */
public class StepFrustrationRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final long CHECK_MS = 1_000;
    private static final double SATISFACTION_RATIO = 0.72;
    private static final double DECAY = 0.82;

    private static final int HIST = 200;
    private final double [] frustrationHist = new double [HIST];
    private int histHead = 0;
    private int histCount = 0;

    private double seedBest;
    private long lastCheck;
    private boolean initialized;

    private double prevSigmaSmall;
    private double prevSigmaWide;
    private double [] prevX;
    private double [] prevStep;
    private double prevFitness;
    private double frustration;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        lastCheck = 0;
        initialized = false;
        frustration = 0.0;
        histHead = 0;
        histCount = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        // Cadence geree dans shouldRestart().
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        long now = System.currentTimeMillis ();
        if (lastCheck != 0 && now - lastCheck < CHECK_MS)
            return Decision.CONTINUE;
        lastCheck = now;

        double [] x = chooseBestVector (ctx);
        if (x == null) return Decision.CONTINUE;

        if (!initialized)
        {
            prevSigmaSmall = ctx.sigmaSmall;
            prevSigmaWide = ctx.sigmaWide;
            prevFitness = ctx.globalBestFitness;
            prevX = x.clone ();
            prevStep = new double [x.length];
            initialized = true;
            return Decision.CONTINUE;
        }

        double dSigSmall = Math.abs (Math.log ((ctx.sigmaSmall + 1e-12) / (prevSigmaSmall + 1e-12)));
        double dSigWide = Math.abs (Math.log ((ctx.sigmaWide + 1e-12) / (prevSigmaWide + 1e-12)));
        double deltaSigma = 0.5 * (dSigSmall + dSigWide);

        double [] step = vectorDiff (x, prevX);
        double stepNorm = norm (step) / (norm (prevX) + 1e-12);
        double angle = angleBetween (step, prevStep); // [0, pi]

        double relGain = (prevFitness - ctx.globalBestFitness) / (Math.abs (prevFitness) + 1e-12);

        double inc = 1.8 * deltaSigma + 1.2 * (angle / Math.PI) + 1.0 * stepNorm
                - 2.0 * Math.max (relGain, 0.0);
        frustration = DECAY * frustration + Math.max (0.0, inc);

        pushHist (frustration);
        double q85 = quantileHist (0.85);

        boolean weakProgress = relGain < 0.002;
        if (histCount > 20 && frustration > q85 && weakProgress)
            return Decision.RESTART_BOTH;

        prevSigmaSmall = ctx.sigmaSmall;
        prevSigmaWide = ctx.sigmaWide;
        prevFitness = ctx.globalBestFitness;
        prevX = x.clone ();
        prevStep = step;
        return Decision.CONTINUE;
    }

    @Override
    public void onRestart ()
    {
        lastCheck = 0;
        initialized = false;
        frustration = 0.0;
        histHead = 0;
        histCount = 0;
    }

    private void pushHist (double v)
    {
        frustrationHist [histHead] = v;
        histHead = (histHead + 1) % HIST;
        if (histCount < HIST) histCount++;
    }

    private double quantileHist (double q)
    {
        if (histCount == 0) return Double.POSITIVE_INFINITY;
        double [] arr = new double [histCount];
        for (int i = 0; i < histCount; i++) arr [i] = frustrationHist [i];
        java.util.Arrays.sort (arr);
        int idx = (int) Math.floor (Math.max (0.0, Math.min (1.0, q)) * (arr.length - 1));
        return arr [idx];
    }

    private double [] chooseBestVector (OuterRestartContext ctx)
    {
        if (ctx.bestXSmall == null) return ctx.bestXWide;
        if (ctx.bestXWide == null) return ctx.bestXSmall;
        return (ctx.bestFitnessSmall <= ctx.bestFitnessWide) ? ctx.bestXSmall : ctx.bestXWide;
    }

    private double [] vectorDiff (double [] a, double [] b)
    {
        int n = Math.min (a.length, b.length);
        double [] out = new double [n];
        for (int i = 0; i < n; i++) out [i] = a [i] - b [i];
        return out;
    }

    private double norm (double [] v)
    {
        double s = 0.0;
        for (double x : v) s += x * x;
        return Math.sqrt (s);
    }

    private double angleBetween (double [] a, double [] b)
    {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        int n = Math.min (a.length, b.length);
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < n; i++)
        {
            dot += a [i] * b [i];
            na += a [i] * a [i];
            nb += b [i] * b [i];
        }
        double den = Math.sqrt (na) * Math.sqrt (nb) + 1e-12;
        double c = Math.max (-1.0, Math.min (1.0, dot / den));
        return Math.acos (c);
    }
}