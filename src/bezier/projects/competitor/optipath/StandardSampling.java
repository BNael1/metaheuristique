package bezier.projects.competitor.optipath;

import java.util.Random;

/**
 * Échantillonnage standard CMA-ES : z ~ N(0,I), y = B·D·z, x = mean + σ·y.
 */
public class StandardSampling implements SamplingStrategy
{
    @Override
    public SampleResult sample (double [] mean, double sigma, double [][] B,
                                double [] diagD, int lambda, int d, Random rng)
    {
        double [][] arx = new double [lambda][d];
        double [][] ary = new double [lambda][d];

        for (int k = 0; k < lambda; k++)
        {
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

            double [] Dz = new double [d];
            for (int i = 0; i < d; i++) Dz [i] = diagD [i] * z [i];

            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += B [i][j] * Dz [j];
                ary [k][i] = sum;
                arx [k][i] = mean [i] + sigma * sum;
            }
        }
        return new SampleResult (arx, ary);
    }
}
