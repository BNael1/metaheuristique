package engine.covariance;

/**
 * Active CMA-ES : utilise des poids négatifs pour les pires individus.
 *
 * La direction des pires individus est "repoussée" dans la covariance,
 * accélérant la convergence dans les paysages multimodaux.
 *
 * Optionnellement, sauvegarde la covariance au restart et la blende
 * avec la nouvelle (covariance memory).
 *
 * Référence : Jastrebski & Arnold (2006), Arnold & Hansen (2010)
 */
public class ActiveCovariance implements CovarianceUpdate
{
    private static final double COV_MEMORY_ALPHA = 0.3;
    private final boolean useCovarianceMemory;
    private double [][] savedC;

    public ActiveCovariance (boolean useCovarianceMemory)
    {
        this.useCovarianceMemory = useCovarianceMemory;
    }

    public ActiveCovariance ()
    {
        this (false);
    }

    @Override
    public void onRestart (double [][] C)
    {
        int d = C.length;

        // Sauvegarder puis blender avec l'identité
        if (useCovarianceMemory && savedC != null)
        {
            for (int i = 0; i < d; i++)
                for (int j = 0; j < d; j++)
                    C [i][j] = COV_MEMORY_ALPHA * savedC [i][j]
                            + (1 - COV_MEMORY_ALPHA) * ((i == j) ? 1.0 : 0.0);
        }

        // Sauvegarder la C courante pour le prochain restart
        savedC = new double [d][d];
        for (int i = 0; i < d; i++)
            System.arraycopy (C [i], 0, savedC [i], 0, d);
    }

    @Override
    public void updateCovariance (double [][] C, double [] pc, double [][] ary,
                                  Integer [] idx, double [] weights, int mu,
                                  double c1, double cmu, int hsig, double cc)
    {
        int d = pc.length;
        int lambda = idx.length;

        // Calcul des poids négatifs pour les (lambda - mu) pires
        int nNeg = lambda - mu;
        double [] wNeg = new double [nNeg];
        double sumNeg = 0;
        for (int i = 0; i < nNeg; i++)
        {
            wNeg [i] = Math.log (lambda + 0.5) - Math.log (lambda - i);
            sumNeg += wNeg [i];
        }
        if (sumNeg > 0)
            for (int i = 0; i < nNeg; i++) wNeg [i] /= sumNeg;

        // mueff négatif
        double sumNeg2 = 0;
        for (int i = 0; i < nNeg; i++) sumNeg2 += wNeg [i] * wNeg [i];
        double mueffNeg = (sumNeg2 > 0) ? 1.0 / sumNeg2 : 0;

        // Facteur de sécurité pour éviter que les poids négatifs ne dominent
        double alphaNeg = 1.0;
        if (mueffNeg > 0)
        {
            double mueffPos = 0;
            double sumPos2 = 0;
            for (int k = 0; k < mu; k++) sumPos2 += weights [k] * weights [k];
            mueffPos = (sumPos2 > 0) ? 1.0 / sumPos2 : 1;
            alphaNeg = Math.min (1.0, Math.min (mueffPos / mueffNeg,
                    (1.0 + 2.0 / (d + 1.0)) / (2.0 + 2.0 / (d + 1.0))));
        }

        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j <= i; j++)
            {
                // rank-1 (positive)
                double rank1 = c1 * pc [i] * pc [j];

                // rank-mu positive
                double rankmuPos = 0;
                for (int k = 0; k < mu; k++)
                {
                    int ii = idx [k];
                    rankmuPos += weights [k] * ary [ii][i] * ary [ii][j];
                }

                // rank-mu negative (active)
                double rankmuNeg = 0;
                for (int k = 0; k < nNeg; k++)
                {
                    int ii = idx [lambda - 1 - k]; // pires individus
                    rankmuNeg += wNeg [k] * ary [ii][i] * ary [ii][j];
                }

                double rankmu = cmu * (rankmuPos - alphaNeg * rankmuNeg);

                C [i][j] = cOld * C [i][j] + rank1 + rankmu;
                C [j][i] = C [i][j];
            }
        }
    }
}
