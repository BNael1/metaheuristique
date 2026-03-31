package bezier.projects.competitor.optipath;

/**
 * T11 : detection de piege geometrique via persistance de forme.
 *
 * Proxy topologique : si la signature de courbure des points de controle
 * reste quasi identique sur plusieurs checks, malgre des deplacements
 * significatifs des vecteurs, la recherche est probablement bloquee.
 */
public class CurveShapePersistenceRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final long CHECK_MS = 1_000;
    private static final long WINDOW_MS = 6_000;
    private static final double SATISFACTION_RATIO = 0.72;

    private static final int BUF = 96;
    private final long [] ts = new long [BUF];
    private final double [][] sig = new double [BUF][];
    private final double [] fit = new double [BUF];
    private int head = 0;
    private int count = 0;

    private double [] prevX;
    private double seedBest;
    private long lastCheck;

    @Override
    public void init (SeedingStats stats)
    {
        head = 0;
        count = 0;
        prevX = null;
        seedBest = stats.bestFitness;
        lastCheck = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        // Echantillonnage fait dans shouldRestart avec acces aux bestX.
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
        if (x == null || x.length < 6)
            return Decision.CONTINUE;

        double [] signature = curvatureSignature (x);
        push (now, signature, ctx.globalBestFitness);

        if (count < 5)
        {
            prevX = x.clone ();
            return Decision.CONTINUE;
        }

        // Similarite de signature sur la fenetre recente
        int n = 0;
        double simSum = 0.0;
        double dispSum = 0.0;

        double [] latestSig = sig [(head - 1 + BUF) % BUF];
        for (int i = 1; i < count; i++)
        {
            int idx = (head - 1 - i + BUF) % BUF;
            if (now - ts [idx] > WINDOW_MS) break;
            simSum += cosineSim (latestSig, sig [idx]);
            n++;
        }
        if (n == 0)
        {
            prevX = x.clone ();
            return Decision.CONTINUE;
        }

        if (prevX != null && prevX.length == x.length)
            dispSum = normalizedDistance (prevX, x);

        double avgSim = simSum / n;
        double progress = recentProgress (now - WINDOW_MS, ctx.globalBestFitness);

        boolean persistentShape = avgSim > 0.92;
        boolean movedButNoGain = dispSum > 0.02 && progress < 0.003;

        prevX = x.clone ();
        if (persistentShape && movedButNoGain)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }

    @Override
    public void onRestart ()
    {
        lastCheck = 0;
        prevX = null;
    }

    private double [] chooseBestVector (OuterRestartContext ctx)
    {
        if (ctx.bestXSmall == null) return ctx.bestXWide;
        if (ctx.bestXWide == null) return ctx.bestXSmall;
        return (ctx.bestFitnessSmall <= ctx.bestFitnessWide) ? ctx.bestXSmall : ctx.bestXWide;
    }

    private void push (long t, double [] signature, double f)
    {
        ts [head] = t;
        sig [head] = signature;
        fit [head] = f;
        head = (head + 1) % BUF;
        if (count < BUF) count++;
    }

    private double [] curvatureSignature (double [] x)
    {
        int n = x.length / 2;
        int bins = 10;
        double [] h = new double [bins];
        if (n < 3) return h;

        for (int i = 1; i < n - 1; i++)
        {
            double ax = x [2 * i] - x [2 * (i - 1)];
            double ay = x [2 * i + 1] - x [2 * (i - 1) + 1];
            double bx = x [2 * (i + 1)] - x [2 * i];
            double by = x [2 * (i + 1) + 1] - x [2 * i + 1];

            double na = Math.sqrt (ax * ax + ay * ay) + 1e-12;
            double nb = Math.sqrt (bx * bx + by * by) + 1e-12;
            double c = (ax * bx + ay * by) / (na * nb);
            c = Math.max (-1.0, Math.min (1.0, c));
            double angle = Math.acos (c); // [0, pi]

            int b = (int) Math.floor ((angle / Math.PI) * bins);
            if (b >= bins) b = bins - 1;
            if (b < 0) b = 0;
            h [b] += 1.0;
        }

        double sum = 0.0;
        for (double v : h) sum += v;
        if (sum <= 0) return h;
        for (int i = 0; i < h.length; i++) h [i] /= sum;
        return h;
    }

    private double cosineSim (double [] a, double [] b)
    {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++)
        {
            dot += a [i] * b [i];
            na += a [i] * a [i];
            nb += b [i] * b [i];
        }
        return dot / (Math.sqrt (na) * Math.sqrt (nb) + 1e-12);
    }

    private double normalizedDistance (double [] a, double [] b)
    {
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < a.length; i++)
        {
            double d = a [i] - b [i];
            num += d * d;
            den += a [i] * a [i];
        }
        return Math.sqrt (num) / (Math.sqrt (den) + 1e-12);
    }

    private double recentProgress (long sinceMs, double fallback)
    {
        if (count == 0) return 0.0;
        double first = fallback;
        double last = fit [(head - 1 + BUF) % BUF];
        for (int i = count - 1; i >= 0; i--)
        {
            int idx = (head - 1 - i + BUF) % BUF;
            if (ts [idx] >= sinceMs)
            {
                first = fit [idx];
                break;
            }
        }
        return (first - last) / (Math.abs (first) + 1e-12);
    }
}