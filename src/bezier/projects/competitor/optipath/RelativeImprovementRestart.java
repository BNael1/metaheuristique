package bezier.projects.competitor.optipath;

/**
 * T1 : Detection de plateau par taux d'amelioration relative.
 * Maintient un historique (timestamp, fitness) et calcule le taux
 * d'amelioration sur une fenetre glissante. Restart si < MIN_RATE.
 * Guard : pas de restart si deja bien converge (< 70% du best seed).
 */
public class RelativeImprovementRestart implements OuterRestartStrategy
{
    private static final long CHECK_MS = 3_000;
    private static final long WINDOW_MS = 5_000;
    private static final long MIN_RUN_MS = 4_000;
    private static final double MIN_RATE = 0.001;
    private static final double SATISFACTION_RATIO = 0.7;

    // Buffer circulaire (timestamp, fitness)
    private static final int BUF_SIZE = 200;
    private final long [] timestamps = new long [BUF_SIZE];
    private final double [] fitnesses = new double [BUF_SIZE];
    private int bufHead = 0;
    private int bufCount = 0;

    private double seedBest;
    private long lastCheckMs = 0;

    @Override
    public void init (SeedingStats stats)
    {
        seedBest = stats.bestFitness;
        bufHead = 0;
        bufCount = 0;
        lastCheckMs = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        timestamps[bufHead] = timestampMs;
        fitnesses[bufHead] = fitness;
        bufHead = (bufHead + 1) % BUF_SIZE;
        if (bufCount < BUF_SIZE) bufCount++;
    }

    @Override public void onRestart ()
    {
        lastCheckMs = 0;
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        // Guard : deja bien converge
        if (ctx.globalBestFitness < seedBest * SATISFACTION_RATIO)
            return Decision.CONTINUE;

        // Throttle : verifier toutes les CHECK_MS
        long now = System.currentTimeMillis ();
        if (lastCheckMs != 0 && (now - lastCheckMs) < CHECK_MS)
            return Decision.CONTINUE;
        lastCheckMs = now;

        // Chercher la fitness au debut de la fenetre
        long windowStart = now - WINDOW_MS;
        double fitnessAtWindowStart = ctx.globalBestFitness; // defaut si pas de donnee
        for (int i = 0; i < bufCount; i++)
        {
            int idx = (bufHead - bufCount + i + BUF_SIZE) % BUF_SIZE;
            if (timestamps[idx] >= windowStart)
            {
                fitnessAtWindowStart = fitnesses[idx];
                break;
            }
        }

        // Si pas de donnee dans la fenetre, utiliser la fitness au lancement
        if (fitnessAtWindowStart <= ctx.globalBestFitness)
            fitnessAtWindowStart = ctx.fitnessAtLaunch;

        if (fitnessAtWindowStart <= 0) return Decision.CONTINUE;

        double rate = (fitnessAtWindowStart - ctx.globalBestFitness) / fitnessAtWindowStart;
        if (rate < MIN_RATE)
            return Decision.RESTART_BOTH;

        return Decision.CONTINUE;
    }
}
