package bezier2_0.projects.competitor.optipath;

/**
 * T12 : restart par credibilite bayesienne du progres.
 *
 * On convertit chaque fenetre en succes/echec d'amelioration relative,
 * puis on suit un posterior Beta-Bernoulli sur la proba de succes.
 */
public class BayesianCredibilityRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 5_000;
    private static final long CHECK_MS = 1_300;
    private static final double SATISFACTION_RATIO = 0.72;

    private double seedBest;
    private long lastCheck;
    private double lastFitness;
    private boolean initialized;

    // Posterior Beta(alpha, beta)
    private double alpha;
    private double beta;

    // Historique de gains pour seuil adaptatif de succes
    private static final int GAIN_BUF = 160;
    private final double [] relGains = new double [GAIN_BUF];
    private int gainHead;
    private int gainCount;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        lastCheck = 0;
        initialized = false;
        alpha = 2.0;
        beta = 2.0;
        gainHead = 0;
        gainCount = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        // L'echantillonnage est synchronise sur shouldRestart() pour cadence stable.
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

        if (!initialized)
        {
            lastFitness = ctx.globalBestFitness;
            initialized = true;
            return Decision.CONTINUE;
        }

        double rel = (lastFitness - ctx.globalBestFitness) / (Math.abs (lastFitness) + 1e-12);
        lastFitness = ctx.globalBestFitness;
        pushGain (Math.max (rel, 0.0));

        double q = adaptiveSuccessThreshold ();
        int y = (rel > q) ? 1 : 0;
        alpha += y;
        beta += (1 - y);

        double upper90 = upperCredibleBound90 (alpha, beta);
        double remainingRatio = (ctx.totalBudgetMs > 0)
                ? Math.max (0.0, (double) (ctx.totalBudgetMs - ctx.elapsedTotalMs) / ctx.totalBudgetMs)
                : 0.0;

        // Plus agressif en debut de budget, plus conservateur en fin.
        double kappa = 0.08 + 0.16 * remainingRatio;
        if (upper90 < kappa && ctx.sinceLaunchMs > MIN_RUN_MS)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }

    @Override
    public void onRestart ()
    {
        lastCheck = 0;
        initialized = false;
        alpha = 2.0;
        beta = 2.0;
        gainHead = 0;
        gainCount = 0;
    }

    private void pushGain (double g)
    {
        relGains [gainHead] = g;
        gainHead = (gainHead + 1) % GAIN_BUF;
        if (gainCount < GAIN_BUF) gainCount++;
    }

    private double adaptiveSuccessThreshold ()
    {
        if (gainCount < 8) return 0.002;
        double [] vals = new double [gainCount];
        for (int i = 0; i < gainCount; i++)
            vals [i] = relGains [i];
        java.util.Arrays.sort (vals);

        // Quantile robuste centre-bas pour rester sensible.
        int qIdx = (int) Math.floor (0.40 * (vals.length - 1));
        double q = vals [qIdx];
        return Math.max (q, 5e-4);
    }

    private double upperCredibleBound90 (double a, double b)
    {
        double mean = a / (a + b);
        double var = (a * b) / (((a + b) * (a + b)) * (a + b + 1.0));
        double z = 1.2815515655446004; // 90% one-sided
        return Math.max (0.0, Math.min (1.0, mean + z * Math.sqrt (Math.max (var, 1e-12))));
    }
}