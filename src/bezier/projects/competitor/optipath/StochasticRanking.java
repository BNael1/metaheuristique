package bezier.projects.competitor.optipath;

import java.util.Comparator;
import java.util.Random;

/**
 * Stochastic Ranking : tri probabiliste qui balance fitness et violation de contraintes.
 *
 * Avec probabilité Pf, deux individus infaisables sont comparés par fitness
 * (encourage l'exploration des zones potentiellement bonnes même si infaisables).
 * Sinon, ils sont comparés par violation.
 *
 * Référence : Runarsson & Yao (2000) "Stochastic Ranking for Constrained Evolutionary Optimization"
 */
public class StochasticRanking implements ConstraintHandler
{
    private static final double PF = 0.45;
    private final Random rng = new Random ();

    /**
     * Estimateur de violation de contrainte.
     * Doit être fourni pour décomposer fitness vs violation.
     */
    public interface ViolationEstimator
    {
        /**
         * Estime la violation de contrainte d'un individu.
         * @param x position de l'individu
         * @param fitness fitness (officielle) de l'individu
         * @return violation ≥ 0 (0 = faisable)
         */
        double estimateViolation (double [] x, double fitness);
    }

    private final ViolationEstimator violEstimator;

    public StochasticRanking (ViolationEstimator violEstimator)
    {
        this.violEstimator = violEstimator;
    }

    /**
     * Constructeur avec un seuil de faisabilité fixe.
     * violation = max(0, fitness - threshold).
     */
    public StochasticRanking (double feasibilityThreshold)
    {
        this.violEstimator = (x, f) -> Math.max (0, f - feasibilityThreshold);
    }

    @Override
    public Comparator<Integer> getComparator (double [] fitness, int lambda)
    {
        // Non utilisé directement car on override sortPopulation
        return (a, b) -> Double.compare (fitness [a], fitness [b]);
    }

    @Override
    public void sortPopulation (Integer [] idx, double [] fitness, double [][] arx)
    {
        int lambda = idx.length;

        // Calculer les violations
        double [] violation = new double [lambda];
        for (int i = 0; i < lambda; i++)
            violation [i] = violEstimator.estimateViolation (arx [i], fitness [i]);

        // Bubble sort avec comparaison stochastique (Runarsson & Yao)
        for (int i = 0; i < lambda; i++)
        {
            boolean swapped = false;
            for (int j = 0; j < lambda - 1 - i; j++)
            {
                int a = idx [j];
                int b = idx [j + 1];

                boolean doSwap;
                boolean aFeas = (violation [a] <= 0);
                boolean bFeas = (violation [b] <= 0);

                if (aFeas && bFeas)
                {
                    // Deux faisables : compare par fitness
                    doSwap = fitness [a] > fitness [b];
                }
                else if (aFeas)
                {
                    doSwap = false; // faisable toujours mieux
                }
                else if (bFeas)
                {
                    doSwap = true;  // faisable toujours mieux
                }
                else
                {
                    // Deux infaisables : probabilistiquement par fitness ou violation
                    if (rng.nextDouble () < PF)
                        doSwap = fitness [a] > fitness [b];
                    else
                        doSwap = violation [a] > violation [b];
                }

                if (doSwap)
                {
                    idx [j] = b;
                    idx [j + 1] = a;
                    swapped = true;
                }
            }
            if (!swapped) break;
        }
    }
}
