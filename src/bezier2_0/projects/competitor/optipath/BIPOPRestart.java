package bezier2_0.projects.competitor.optipath;

import java.util.Random;

/**
 * BIPOP restart : alternance entre régimes large (exploration) et small (exploitation).
 *
 * - Restarts impairs  : large λ (double à chaque fois), σ = σ₀
 * - Restarts pairs     : small λ = λ₀, σ = σ₀/10
 *
 * Référence : Hansen (2009) "Benchmarking a BI-Population CMA-ES"
 */
public class BIPOPRestart implements RestartStrategy
{
    private int largeLambda;
    private final Random rng = new Random ();

    @Override
    public RestartConfig nextRestart (RestartContext ctx)
    {
        if (largeLambda == 0)
            largeLambda = ctx.lambda0;

        int newLambda;
        double newSigma;

        if (ctx.restartCount % 2 == 1)
        {
            // Large restart : exploration
            largeLambda = Math.min (largeLambda * 2, 512);
            newLambda = largeLambda;
            newSigma = ctx.sigma0;
        }
        else
        {
            // Small restart : exploitation
            newLambda = ctx.lambda0;
            newSigma = ctx.sigma0 / 10.0;
        }

        // Mean calculé par le defaultRestartMean
        return new RestartConfig (newLambda, newSigma, null);
    }
}
