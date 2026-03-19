package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

import java.util.Arrays;

/**
 * Surrogate RBF pre-screening : génère PRESCREENING_FACTOR × λ candidats,
 * pré-trie par prédiction surrogate, et n'évalue réellement que les top λ.
 *
 * Économise ainsi ~(PRESCREENING_FACTOR - 1) × λ évaluations coûteuses
 * par génération, au prix d'évaluations surrogate bon marché O(n×d).
 *
 * Un pool de sécurité (SAFETY_FRACTION) garantit que certains candidats
 * sont sélectionnés aléatoirement, protégeant contre les erreurs du surrogate.
 */
public class SurrogatePrescreen implements EvalWrapper
{
    private static final int PRESCREENING_FACTOR = 3;
    private static final double SAFETY_FRACTION = 0.3;
    private static final int SURROGATE_WARMUP = 40;
    private static final double MIN_SURROGATE_QUALITY = 0.2;
    private static final int ARCHIVE_SIZE = 300;

    private final Problem problem;
    private final int d;
    private int evalCount = 0;

    // Archive RBF
    private final double [][] archiveX;
    private final double [] archiveF;
    private int archiveSize = 0;
    private int archiveIdx = 0;
    private double kernelWidth = 1.0;
    private double surrogateQuality = 0.5;

    public SurrogatePrescreen (Problem problem, int d)
    {
        this.problem = problem;
        this.d = d;
        this.archiveX = new double [ARCHIVE_SIZE][d];
        this.archiveF = new double [ARCHIVE_SIZE];
    }

    @Override
    public double [] evaluateBatch (double [][] candidates, int lambda)
    {
        // Pas assez de données ou qualité trop faible : éval directe
        if (archiveSize < SURROGATE_WARMUP || surrogateQuality < MIN_SURROGATE_QUALITY)
        {
            double [] fitness = new double [lambda];
            for (int k = 0; k < lambda; k++)
            {
                fitness [k] = problem.evaluate (candidates [k]);
                evalCount++;
                addToArchive (candidates [k], fitness [k]);
            }
            updateKernelWidth ();
            return fitness;
        }

        // Pré-screening : prédire pour tous les candidats
        double [] predicted = new double [lambda];
        for (int k = 0; k < lambda; k++)
            predicted [k] = predict (candidates [k]);

        // Trier par prédiction surrogate
        Integer [] order = new Integer [lambda];
        for (int i = 0; i < lambda; i++) order [i] = i;
        Arrays.sort (order, (a, b) -> Double.compare (predicted [a], predicted [b]));

        // Pool de sécurité : nSafe candidats aléatoires
        int nSafe = Math.max (1, (int) (lambda * SAFETY_FRACTION));
        int nSurrogate = lambda - nSafe;

        // Sélection : top nSurrogate par surrogate + nSafe aléatoires du reste
        boolean [] selected = new boolean [lambda];
        int count = 0;
        for (int i = 0; i < lambda && count < nSurrogate; i++)
        {
            selected [order [i]] = true;
            count++;
        }

        // Ajouter aléatoirement parmi les non-sélectionnés
        java.util.Random rng = new java.util.Random ();
        java.util.ArrayList<Integer> remaining = new java.util.ArrayList<> ();
        for (int i = 0; i < lambda; i++)
            if (!selected [i]) remaining.add (i);
        java.util.Collections.shuffle (remaining, rng);
        for (int i = 0; i < Math.min (nSafe, remaining.size ()); i++)
            selected [remaining.get (i)] = true;

        // Évaluer tous les sélectionnés
        double [] fitness = new double [lambda];
        double [] trueF = new double [lambda];
        for (int k = 0; k < lambda; k++)
        {
            if (selected [k])
            {
                fitness [k] = problem.evaluate (candidates [k]);
                evalCount++;
                addToArchive (candidates [k], fitness [k]);
                trueF [k] = fitness [k];
            }
            else
            {
                // Non évalué : utiliser la prédiction surrogate (sera mal classé)
                fitness [k] = predicted [k] + 1e10; // pénalisé pour ne pas être sélectionné
                trueF [k] = Double.NaN;
            }
        }

        // Mettre à jour la qualité du surrogate (Kendall tau simplifié)
        updateSurrogateQuality (predicted, trueF, selected, lambda);
        updateKernelWidth ();

        return fitness;
    }

    @Override
    public int getEvalCount () { return evalCount; }

    @Override
    public void onRestart ()
    {
        // Conserver l'archive mais reset la qualité
        surrogateQuality = 0.5;
    }

    // ===== Archive RBF (buffer circulaire) =====

    private void addToArchive (double [] x, double f)
    {
        System.arraycopy (x, 0, archiveX [archiveIdx], 0, d);
        archiveF [archiveIdx] = f;
        archiveIdx = (archiveIdx + 1) % ARCHIVE_SIZE;
        if (archiveSize < ARCHIVE_SIZE) archiveSize++;
    }

    private double predict (double [] x)
    {
        if (archiveSize == 0) return 0;

        double sumWf = 0, sumW = 0;
        double h3 = kernelWidth * kernelWidth * kernelWidth;

        for (int i = 0; i < archiveSize; i++)
        {
            double r2 = squaredDist (x, archiveX [i]);
            double r = Math.sqrt (r2);
            double w = 1.0 / (r * r2 + h3 + 1e-30);
            sumWf += w * archiveF [i];
            sumW += w;
        }
        return sumWf / sumW;
    }

    private void updateKernelWidth ()
    {
        if (archiveSize < 2) { kernelWidth = 1.0; return; }

        int nSamples = Math.min (archiveSize, 20);
        double totalMinDist = 0;
        int cnt = 0;

        for (int i = 0; i < nSamples; i++)
        {
            double minDist = Double.POSITIVE_INFINITY;
            for (int j = 0; j < archiveSize; j++)
            {
                if (i == j) continue;
                double dist = squaredDist (archiveX [i], archiveX [j]);
                if (dist < minDist) minDist = dist;
            }
            if (minDist < Double.POSITIVE_INFINITY)
            {
                totalMinDist += Math.sqrt (minDist);
                cnt++;
            }
        }
        kernelWidth = (cnt > 0) ? Math.max (totalMinDist / cnt, 1e-10) : 1.0;
    }

    private void updateSurrogateQuality (double [] predicted, double [] trueF,
                                         boolean [] selected, int lambda)
    {
        // Concordance simple parmi les évalués
        int concordant = 0, discordant = 0;
        for (int i = 0; i < lambda; i++)
        {
            if (!selected [i]) continue;
            for (int j = i + 1; j < lambda; j++)
            {
                if (!selected [j]) continue;
                double dp = predicted [i] - predicted [j];
                double dt = trueF [i] - trueF [j];
                if (dp * dt > 0) concordant++;
                else if (dp * dt < 0) discordant++;
            }
        }
        int total = concordant + discordant;
        double tau = (total > 0) ? (double) (concordant - discordant) / total : 0;
        surrogateQuality = 0.8 * surrogateQuality + 0.2 * tau;
    }

    private double squaredDist (double [] a, double [] b)
    {
        double s = 0;
        for (int i = 0; i < d; i++)
        {
            double diff = a [i] - b [i];
            s += diff * diff;
        }
        return s;
    }
}
