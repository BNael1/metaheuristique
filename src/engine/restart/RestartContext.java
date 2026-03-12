package engine.restart;

import engine.constraints.BoundsChecker;
import java.util.ArrayList;

/**
 * Contexte passé à la stratégie de restart pour qu'elle puisse décider
 * de la prochaine configuration sans accéder directement au CMAESCore.
 */
public class RestartContext
{
    public final int d;
    public final int lambda0;
    public final double sigma0;
    public final int restartCount;
    public final double [] bestX;
    public final double bestFitness;
    public final BoundsChecker bounds;
    public final ArrayList<double []> cachedSeeds;
    public final double currentSigma;

    public RestartContext (int d, int lambda0, double sigma0, int restartCount,
                           double [] bestX, double bestFitness,
                           BoundsChecker bounds, ArrayList<double []> cachedSeeds,
                           double currentSigma)
    {
        this.d = d;
        this.lambda0 = lambda0;
        this.sigma0 = sigma0;
        this.restartCount = restartCount;
        this.bestX = bestX;
        this.bestFitness = bestFitness;
        this.bounds = bounds;
        this.cachedSeeds = cachedSeeds;
        this.currentSigma = currentSigma;
    }
}
