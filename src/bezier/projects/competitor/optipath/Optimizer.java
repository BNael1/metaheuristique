package bezier.projects.competitor.optipath;

/**
 * Common optimizer interface used by the L-SHADE core.
 */
public interface Optimizer {
    void init();
    void step();
    double[] getBestX();
    boolean shouldRestart();
}
