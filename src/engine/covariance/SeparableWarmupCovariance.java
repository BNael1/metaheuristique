package engine.covariance;

import java.util.Arrays;

/**
 * Sep-CMA-ES → Full CMA-ES avec transition adaptative.
 *
 * Phase 1 (Sep-CMA-ES) : seuls les termes diagonaux de C sont mis à jour.
 *   Complexité O(d) par génération → beaucoup plus de générations en 60s.
 *   Durée : SEP_BUDGET générations ou jusqu'à stagnation.
 *
 * Phase 2 (Full CMA-ES) : C est initialisé avec 0.3 * diag(appris) + 0.7 * I.
 *   Permet d'apprendre les corrélations entre dimensions.
 *
 * Au restart, retourne toujours en mode Sep (ré-apprentissage rapide de la diagonale).
 *
 * Délègue la mise à jour Full au CovarianceUpdate interne (Standard ou Active).
 */
public class SeparableWarmupCovariance implements CovarianceUpdate
{
    private static final int SEP_BUDGET = 300;
    private static final double BLEND_ALPHA = 0.3;

    private final CovarianceUpdate fullDelegate;
    private boolean sepMode;
    private int sepGenerations;

    public SeparableWarmupCovariance (CovarianceUpdate fullDelegate)
    {
        this.fullDelegate = fullDelegate;
        this.sepMode = true;
        this.sepGenerations = 0;
    }

    /** Commence toujours en mode séparable. */
    public SeparableWarmupCovariance ()
    {
        this (new StandardCovariance ());
    }

    @Override
    public Mode getMode ()
    {
        return sepMode ? Mode.SEPARABLE : Mode.FULL;
    }

    /**
     * Mise à jour diagonale en mode Sep. O(d).
     */
    @Override
    public void updateDiagonal (double [] diagC, double [] diagD, double [] pc,
                                double [][] arx, double [] oldMean, double sigma,
                                Integer [] idx, double [] weights, int mu,
                                double c1, double cmu, int hsig, double cc)
    {
        int d = pc.length;
        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
        {
            double rank1 = c1 * pc [i] * pc [i];

            double rankmu = 0;
            for (int k = 0; k < mu; k++)
            {
                int ii = idx [k];
                double yi = (arx [ii][i] - oldMean [i]) / sigma;
                rankmu += weights [k] * yi * yi;
            }
            rankmu *= cmu;

            diagC [i] = cOld * diagC [i] + rank1 + rankmu;
            diagC [i] = Math.max (diagC [i], 1e-20);
            diagD [i] = Math.sqrt (diagC [i]);
        }

        sepGenerations++;

        // Transition automatique vers Full après SEP_BUDGET générations
        if (sepGenerations >= SEP_BUDGET)
            sepMode = false;
        // Note : CMAESCore appellera transitionToFull() quand sepMode passe à false
    }

    /**
     * Délègue la mise à jour Full au delegate.
     */
    @Override
    public void updateCovariance (double [][] C, double [] pc, double [][] ary,
                                  Integer [] idx, double [] weights, int mu,
                                  double c1, double cmu, int hsig, double cc)
    {
        fullDelegate.updateCovariance (C, pc, ary, idx, weights, mu, c1, cmu, hsig, cc);
    }

    /**
     * Au restart en mode Sep, réinitialise la diagonale.
     */
    @Override
    public void onRestartSep (double [] diagC)
    {
        Arrays.fill (diagC, 1.0);
    }

    /**
     * Au restart en mode Full (ne devrait pas arriver normalement,
     * mais au cas où le restart arrive après la transition).
     */
    @Override
    public void onRestart (double [][] C)
    {
        // Laisser CMAESCore réinitialiser C
    }

    @Override
    public void prepareRestart ()
    {
        sepMode = true;
        sepGenerations = 0;
    }

    /** Construit la matrice C initiale lors de la transition Sep→Full. */
    public void buildTransitionC (double [][] C, double [] diagC, double [] diagD,
                                  double [][] invsqrtC, double [][] B)
    {
        int d = diagC.length;
        for (int i = 0; i < d; i++)
        {
            for (int j = 0; j < d; j++)
            {
                if (i == j)
                    C [i][i] = BLEND_ALPHA * diagC [i] + (1 - BLEND_ALPHA);
                else
                    C [i][j] = 0;
            }
            B [i][i] = 1.0;
            diagD [i] = Math.sqrt (C [i][i]);
            invsqrtC [i][i] = 1.0 / diagD [i];
        }
    }

    public boolean isSepMode () { return sepMode; }
}
