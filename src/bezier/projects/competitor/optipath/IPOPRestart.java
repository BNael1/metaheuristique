package bezier.projects.competitor.optipath;

import java.util.Random;

/**
 * IPOP restart : lambda double exponentiellement à chaque restart.
 * Stratégie par défaut de CMA-ES.
 */
public class IPOPRestart implements RestartStrategy
{
    private final Random rng = new Random ();

    @Override
    public RestartConfig nextRestart (RestartContext ctx)
    {
        int newLambda = ctx.lambda0 * (1 << Math.min (ctx.restartCount, 8));
        newLambda = Math.min (newLambda, 512);

        // Mean calculé par le defaultRestartMean de CMAESCore
        return new RestartConfig (newLambda, ctx.sigma0, null);
    }
}
