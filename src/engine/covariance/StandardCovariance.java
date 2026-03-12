package engine.covariance;

/**
 * Mise à jour standard de la covariance CMA-ES : rank-1 + rank-mu.
 * C'est la version classique sans poids négatifs.
 */
public class StandardCovariance implements CovarianceUpdate
{
    @Override
    public void updateCovariance (double [][] C, double [] pc, double [][] ary,
                                  Integer [] idx, double [] weights, int mu,
                                  double c1, double cmu, int hsig, double cc)
    {
        int d = pc.length;
        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j <= i; j++)
            {
                double rank1 = c1 * pc [i] * pc [j];

                double rankmu = 0;
                for (int k = 0; k < mu; k++)
                {
                    int ii = idx [k];
                    rankmu += weights [k] * ary [ii][i] * ary [ii][j];
                }
                rankmu *= cmu;

                C [i][j] = cOld * C [i][j] + rank1 + rankmu;
                C [j][i] = C [i][j];
            }
        }
    }
}
