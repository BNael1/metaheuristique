package bezier.projects.competitor.rfsurr;

import java.util.Random;

/**
 * Random Forest pour surrogate model — implémenté from scratch.
 * 10 arbres, max_depth=8, bootstrap, sqrt(d) features par split.
 */
public class RandomForest
{
    private final int nTrees;
    private final int maxDepth;
    private final int d;
    private final Random rng;

    // Archive circulaire
    private double [][] xs;
    private double [] fs;
    private int size;
    private int maxSize;
    private int insertIdx;

    // Arbres
    private Node [] trees;
    private boolean trained;

    public RandomForest (int d, int maxArchive)
    {
        this.d = d;
        this.nTrees = 10;
        this.maxDepth = 8;
        this.rng = new Random ();
        this.maxSize = maxArchive;
        this.xs = new double [maxArchive][d];
        this.fs = new double [maxArchive];
        this.size = 0;
        this.insertIdx = 0;
        this.trees = null;
        this.trained = false;
    }

    public void addPoint (double [] x, double f)
    {
        System.arraycopy (x, 0, xs [insertIdx], 0, d);
        fs [insertIdx] = f;
        insertIdx = (insertIdx + 1) % maxSize;
        if (size < maxSize) size++;
        trained = false; // marquer comme non-entraîné
    }

    public int getSize () { return size; }
    public boolean isTrained () { return trained; }

    /**
     * Entraîner la forêt sur l'archive courante.
     */
    public void train ()
    {
        if (size < 5) { trained = false; return; }

        trees = new Node [nTrees];
        int nFeatures = Math.max (1, (int) Math.sqrt (d));

        for (int t = 0; t < nTrees; t++)
        {
            // Bootstrap sample
            int [] indices = new int [size];
            for (int i = 0; i < size; i++) indices [i] = rng.nextInt (size);
            trees [t] = buildTree (indices, size, nFeatures, 0);
        }
        trained = true;
    }

    /**
     * Prédire la moyenne et la variance de la forêt.
     * @return double[2] : {mean, variance}
     */
    public double [] predict (double [] x)
    {
        if (!trained || trees == null) return new double [] {0, 1e10};

        double sum = 0, sumSq = 0;
        int count = 0;
        for (int t = 0; t < nTrees; t++)
        {
            if (trees [t] != null)
            {
                double val = predictTree (trees [t], x);
                sum += val;
                sumSq += val * val;
                count++;
            }
        }
        if (count == 0) return new double [] {0, 1e10};
        double mean = sum / count;
        double variance = sumSq / count - mean * mean;
        variance = Math.max (variance, 1e-20);
        return new double [] {mean, variance};
    }

    /**
     * Expected Improvement : EI(x) = (fBest - mu) * Phi(z) + sigma * phi(z)
     * où z = (fBest - mu) / sigma
     */
    public double expectedImprovement (double [] x, double fBest)
    {
        double [] pred = predict (x);
        double mu = pred [0];
        double sigma = Math.sqrt (pred [1]);
        if (sigma < 1e-15) return Math.max (0, fBest - mu);
        double z = (fBest - mu) / sigma;
        double phi = Math.exp (-0.5 * z * z) / Math.sqrt (2 * Math.PI);
        double phiCDF = 0.5 * (1.0 + erf (z / Math.sqrt (2)));
        return (fBest - mu) * phiCDF + sigma * phi;
    }

    /**
     * Kendall tau entre predictions et vraies valeurs pour évaluer la qualité.
     */
    public double kendallTau (double [] trueF, double [] predF, int n)
    {
        int concordant = 0, discordant = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
            {
                double da = trueF [i] - trueF [j];
                double db = predF [i] - predF [j];
                if (da * db > 0) concordant++;
                else if (da * db < 0) discordant++;
            }
        int total = concordant + discordant;
        if (total == 0) return 0;
        return (double) (concordant - discordant) / total;
    }

    public void clear ()
    {
        size = 0; insertIdx = 0; trained = false; trees = null;
    }

    // ===== Construction d'arbre =====

    private Node buildTree (int [] indices, int n, int nFeatures, int depth)
    {
        if (n <= 2 || depth >= maxDepth) return makeLeaf (indices, n);

        // Vérifier la variance
        double sumF = 0, sumF2 = 0;
        for (int i = 0; i < n; i++) { double f = fs[indices[i]]; sumF += f; sumF2 += f*f; }
        double meanF = sumF / n;
        double varF = sumF2/n - meanF*meanF;
        if (varF < 1e-20) return makeLeaf (indices, n);

        // Choisir nFeatures features aléatoires
        int [] featureIdx = new int [nFeatures];
        boolean [] chosen = new boolean [d];
        int fc = 0;
        while (fc < nFeatures)
        {
            int f = rng.nextInt (d);
            if (!chosen [f]) { chosen [f] = true; featureIdx [fc++] = f; }
        }

        // Trouver le meilleur split
        double bestImpurity = Double.POSITIVE_INFINITY;
        int bestFeature = -1;
        double bestThreshold = 0;

        for (int fi = 0; fi < nFeatures; fi++)
        {
            int feat = featureIdx [fi];

            // 5 seuils candidats (quantiles)
            double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++)
            {
                double v = xs [indices [i]][feat];
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            if (maxV - minV < 1e-15) continue;

            for (int q = 1; q <= 5; q++)
            {
                double threshold = minV + q * (maxV - minV) / 6.0;
                double sumL=0, sumL2=0, nL=0, sumR=0, sumR2=0, nR=0;
                for (int i = 0; i < n; i++)
                {
                    double val = xs [indices [i]][feat];
                    double f = fs [indices [i]];
                    if (val <= threshold) { sumL+=f; sumL2+=f*f; nL++; }
                    else                  { sumR+=f; sumR2+=f*f; nR++; }
                }
                if (nL < 1 || nR < 1) continue;
                double varL = sumL2/nL - (sumL/nL)*(sumL/nL);
                double varR = sumR2/nR - (sumR/nR)*(sumR/nR);
                double impurity = (nL * varL + nR * varR) / n;
                if (impurity < bestImpurity)
                {
                    bestImpurity = impurity; bestFeature = feat; bestThreshold = threshold;
                }
            }
        }

        if (bestFeature < 0) return makeLeaf (indices, n);

        // Partitionner
        int [] leftIdx = new int [n];
        int [] rightIdx = new int [n];
        int nLeft = 0, nRight = 0;
        for (int i = 0; i < n; i++)
        {
            if (xs [indices [i]][bestFeature] <= bestThreshold)
                leftIdx [nLeft++] = indices [i];
            else
                rightIdx [nRight++] = indices [i];
        }

        if (nLeft == 0 || nRight == 0) return makeLeaf (indices, n);

        Node node = new Node ();
        node.feature = bestFeature;
        node.threshold = bestThreshold;
        node.left = buildTree (leftIdx, nLeft, nFeatures, depth + 1);
        node.right = buildTree (rightIdx, nRight, nFeatures, depth + 1);
        return node;
    }

    private Node makeLeaf (int [] indices, int n)
    {
        Node leaf = new Node ();
        leaf.isLeaf = true;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += fs [indices [i]];
        leaf.value = (n > 0) ? sum / n : 0;
        return leaf;
    }

    private double predictTree (Node node, double [] x)
    {
        if (node.isLeaf) return node.value;
        if (x [node.feature] <= node.threshold) return predictTree (node.left, x);
        else return predictTree (node.right, x);
    }

    // Approximation rapide de erf
    private static double erf (double x)
    {
        double a1=0.254829592, a2=-0.284496736, a3=1.421413741, a4=-1.453152027, a5=1.061405429;
        double p=0.3275911;
        int sign = 1;
        if (x < 0) { sign = -1; x = -x; }
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5*t+a4)*t)+a3)*t+a2)*t+a1)*t * Math.exp(-x*x);
        return sign * y;
    }

    private static class Node
    {
        boolean isLeaf;
        double value;       // si feuille
        int feature;        // si nœud interne
        double threshold;   // si nœud interne
        Node left, right;
    }
}
