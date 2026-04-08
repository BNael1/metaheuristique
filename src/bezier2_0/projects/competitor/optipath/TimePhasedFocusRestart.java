package bezier2_0.projects.competitor.optipath;

/**
 * T14 : restart sans seuil absolu, pilote uniquement par le temps et
 * la dynamique relative entre branches.
 *
 * Phase 1 (exploration)  : restarts periodiques des deux CMA-ES.
 * Phase 2 (focus)        : proteger la meilleure branche et restart
 *                          seulement la moins bonne.
 * Phase 3 (finale)       : stabilisation, tres peu de restarts.
 */
public class TimePhasedFocusRestart implements OuterRestartStrategy
{
    private static final long MIN_RUN_MS = 3_000;
    private static final long MIN_REMAINING_FOR_RESTART_MS = 4_000;

    private static final double EXPLORATION_RATIO = 0.60;
    private static final double FINAL_RATIO = 0.90;

    private static final double EXPLORATION_SLICE_RATIO = 0.12;
    private static final long FOCUS_PARTIAL_COOLDOWN_MS = 8_000;
    private static final long FINAL_PARTIAL_COOLDOWN_MS = 10_000;

    private static final double GAP_TRIGGER = 0.05;
    private static final double STRONG_GAP_TRIGGER = 0.12;

    private long nextExplorationRestartMs;
    private long lastPartialRestartMs;

    @Override
    public void init (SeedingStats stats)
    {
        nextExplorationRestartMs = 0;
        lastPartialRestartMs = 0;
    }

    @Override
    public void onFitnessUpdate (double fitness, long timestampMs)
    {
        // Pas d'etat supplementaire requis.
    }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs < MIN_RUN_MS)
            return Decision.CONTINUE;

        long remaining = ctx.totalBudgetMs - ctx.elapsedTotalMs;
        if (remaining < MIN_REMAINING_FOR_RESTART_MS)
            return Decision.CONTINUE;

        double progress = (ctx.totalBudgetMs > 0)
                ? ((double) ctx.elapsedTotalMs / ctx.totalBudgetMs)
                : 1.0;

        if (progress < EXPLORATION_RATIO)
            return explorationDecision (ctx);

        if (progress < FINAL_RATIO)
            return focusDecision (ctx, FOCUS_PARTIAL_COOLDOWN_MS, GAP_TRIGGER);

        return focusDecision (ctx, FINAL_PARTIAL_COOLDOWN_MS, STRONG_GAP_TRIGGER);
    }

    @Override
    public void onRestart ()
    {
        // Maintenir le rythme exploration/focus sans reset agressif des horloges.
    }

    private Decision explorationDecision (OuterRestartContext ctx)
    {
        long sliceMs = (long) Math.max (4_500, ctx.totalBudgetMs * EXPLORATION_SLICE_RATIO);
        if (nextExplorationRestartMs == 0)
            nextExplorationRestartMs = sliceMs;

        if (ctx.sinceLaunchMs >= nextExplorationRestartMs)
        {
            nextExplorationRestartMs = ctx.sinceLaunchMs + sliceMs;
            return Decision.RESTART_BOTH;
        }
        return Decision.CONTINUE;
    }

    private Decision focusDecision (OuterRestartContext ctx, long cooldownMs, double gapTrigger)
    {
        if (ctx.bestXSmall == null || ctx.bestXWide == null)
            return Decision.CONTINUE;

        if (lastPartialRestartMs != 0 && (ctx.elapsedTotalMs - lastPartialRestartMs) < cooldownMs)
            return Decision.CONTINUE;

        double small = ctx.bestFitnessSmall;
        double wide = ctx.bestFitnessWide;
        double best = Math.min (small, wide);
        double worst = Math.max (small, wide);

        double relGap = (worst - best) / (Math.abs (best) + 1e-12);
        if (relGap < gapTrigger)
            return Decision.CONTINUE;

        lastPartialRestartMs = ctx.elapsedTotalMs;
        if (small <= wide)
            return Decision.RESTART_WIDE;
        return Decision.RESTART_SMALL;
    }
}