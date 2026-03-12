package engine.sampling;

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
    @Override
    public SampleResult sample (double [] mean, double sigma, double [][] B,
                                double [] diagD, int lambda, int d, Random rng)
    {
        // Forcer lambda pair
        int halfLambda = lambda / 2;
        int actualLambda = halfLambda * 2;

        double [][] arx = new double [actualLambda][d];
        double [][] ary = new double [actualLambda][d];

        for (int k = 0; k < halfLambda; k++)
        {
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

            double [] Dz = new double [d];
            for (int i = 0; i < d; i++) Dz [i] = diagD [i] * z [i];

            // Direction +z
            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += B [i][j] * Dz [j];
                ary [2 * k][i] = sum;
                arx [2 * k][i] = mean [i] + sigma * sum;
            }

            // Direction -z (miroir)
            for (int i = 0; i < d; i++)
            {
                ary [2 * k + 1][i] = -ary [2 * k][i];
                arx [2 * k + 1][i] = mean [i] - sigma * ary [2 * k][i];
            }
        }

        return new SampleResult (arx, ary);
    }
}
