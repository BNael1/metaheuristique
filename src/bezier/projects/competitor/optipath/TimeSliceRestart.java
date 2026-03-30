package bezier.projects.competitor.optipath;

/**
 * T4 : Tranches de temps fixes — restart inconditionnel toutes les N secondes.
 * Garantit un nombre fixe de tentatives independantes.
 */
public class TimeSliceRestart implements OuterRestartStrategy
{
    private final int numSlices;
    private long sliceDurationMs;

    public TimeSliceRestart ()
    {
        this (4);
    }

    public TimeSliceRestart (int numSlices)
    {
        this.numSlices = numSlices;
    }

    @Override
    public void init (SeedingStats stats)
    {
        // Budget CMA-ES = 90% de 58s = ~52s, divise en N tranches
        long cmaesBudgetMs = (long) (58_000 * 0.90);
        sliceDurationMs = cmaesBudgetMs / numSlices;
    }

    @Override public void onFitnessUpdate (double fitness, long timestampMs) { }
    @Override public void onRestart () { }

    @Override
    public Decision shouldRestart (OuterRestartContext ctx)
    {
        if (ctx.sinceLaunchMs > sliceDurationMs)
            return Decision.RESTART_BOTH;
        return Decision.CONTINUE;
    }
}
