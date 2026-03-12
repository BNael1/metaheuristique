package engine.restart;

import java.util.Random;

/**
 * BIPOP Adaptatif : BIPOP conditionnel (seulement si d ≤ 16) + SHADE-style sigma history.
 *
 * - Si d ≤ 16 : BIPOP classique (alternance small/large).
 * - Si d > 16 : IPOP pur (lambda double exponentiellement).
 * - Le sigma au restart est échantillonné depuis un historique circulaire
 *   des sigmas qui ont mené à des améliorations du best global.
 */
public class AdaptiveBIPOP implements RestartStrategy
{
    private static final int H_SIGMA = 6;

    private final boolean useBipop;
    private int largeLambda;
    private final Random rng = new Random ();

    // SHADE-style sigma history
    private final double [] MSigma = new double [H_SIGMA];
    private int sigmaHistIndex = 0;
    private int sigmaHistCount = 0;

    public AdaptiveBIPOP (int d)
    {
        this.useBipop = (d <= 16);
    }

    @Override
    public void onImprovement (double currentSigma)
    {
        MSigma [sigmaHistIndex] = currentSigma;
        sigmaHistIndex = (sigmaHistIndex + 1) % H_SIGMA;
        if (sigmaHistCount < H_SIGMA) sigmaHistCount++;
    }

    @Override
    public RestartConfig nextRestart (RestartContext ctx)
    {
        if (largeLambda == 0)
            largeLambda = ctx.lambda0;

        int newLambda;
        double newSigma;

        if (useBipop && ctx.restartCount % 2 == 0)
        {
            // Small restart (exploitation)
            newLambda = ctx.lambda0;
            newSigma = sampleSigmaFromHistory (ctx.sigma0);
            if (newSigma < 0) newSigma = ctx.sigma0 / 10.0;
        }
        else
        {
            // Large restart (exploration)
            largeLambda = Math.min (largeLambda * 2, 512);
            newLambda = largeLambda;
            newSigma = sampleSigmaFromHistory (ctx.sigma0);
            if (newSigma < 0) newSigma = ctx.sigma0;
        }

        return new RestartConfig (newLambda, newSigma, null);
    }

    /**
     * Échantillonne sigma depuis l'historique SHADE : Cauchy(loc=MSigma[r], scale=0.2×loc).
     * Retourne -1 si l'historique est vide.
     */
    private double sampleSigmaFromHistory (double sigma0)
    {
        if (sigmaHistCount == 0) return -1;

        int r = rng.nextInt (sigmaHistCount);
        double loc = MSigma [r];
        double scale = 0.2 * loc;
        double sampled = loc + scale * Math.tan (Math.PI * (rng.nextDouble () - 0.5));

        // Clamper
        sampled = Math.max (sampled, sigma0 / 20.0);
        sampled = Math.min (sampled, sigma0 * 3.0);
        return sampled;
    }
}
