package bezier.projects.competitor.optipath;

import java.util.Random;

/**
 * Mirror sampling : chaque vecteur z est évalué dans les deux directions (z et -z).
 *
 * Avantage : réduit la variance du gradient naturel estimé de moitié,
 * sans coût supplémentaire en évaluations. Lambda doit être pair.
 *
 * Référence : Brockhoff et al. (2010) "Mirrored Sampling and Sequential Selection"
 */
public class MirrorSampling implements SamplingStrategy
{
    // Buffers pré-alloués (initialisés au premier appel)
    private double [] zBuf;
    private double [] DzBuf;
    private double [][] arxBuf;
    private double [][] aryBuf;
    private int cachedLambda;
    private int cachedD;

    @Override
    public SampleResult sample (double [] mean, double sigma, double [][] B,
                                double [] diagD, int lambda, int d, Random rng)
    {
        int halfLambda = lambda / 2;
        int actualLambda = halfLambda * 2;

        // Allouer une seule fois (ou si lambda/d change apres restart)
        if (zBuf == null || cachedD != d || cachedLambda != actualLambda)
        {
            zBuf = new double [d];
            DzBuf = new double [d];
            arxBuf = new double [actualLambda][d];
            aryBuf = new double [actualLambda][d];
            cachedD = d;
            cachedLambda = actualLambda;
        }

        for (int k = 0; k < halfLambda; k++)
        {
            for (int i = 0; i < d; i++) zBuf [i] = rng.nextGaussian ();
            for (int i = 0; i < d; i++) DzBuf [i] = diagD [i] * zBuf [i];

            // Direction +z
            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += B [i][j] * DzBuf [j];
                aryBuf [2 * k][i] = sum;
                arxBuf [2 * k][i] = mean [i] + sigma * sum;
            }

            // Direction -z (miroir)
            for (int i = 0; i < d; i++)
            {
                aryBuf [2 * k + 1][i] = -aryBuf [2 * k][i];
                arxBuf [2 * k + 1][i] = mean [i] - sigma * aryBuf [2 * k][i];
            }
        }

        return new SampleResult (arxBuf, aryBuf);
    }
}
