package engine.pso;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.Random;

/**
 * Fractional-Order PSO (FPSO) — vélocité de Grünwald-Letnikov.
 *
 * Au lieu du terme d'inertie classique w * v(t), la mise à jour de la vitesse
 * utilise une dérivée fractionnaire d'ordre β sur les M dernières vitesses :
 *
 *   v(t+1) = Σ_{k=1}^{M} (−c_k) · v(t−k+1)  +  c1·r1·(pbest−x)  +  c2·r2·(gbest−x)
 *
 * Coefficients de Grünwald-Letnikov :
 *   c_0 = 1,   c_k = (1 − (1+β)/k) · c_{k−1}
 *
 * Propriétés :
 *   β = 1  →  PSO classique avec w=1 (seul v(t) compte, c_k=0 pour k≥2)
 *   β < 1  →  mémoire longue et douce : meilleure exploration, moins d'oscillations
 *
 * β décroît linéairement de BETA_MAX (0.9) à BETA_MIN (0.4) au fil des générations,
 * combinant exploration forte au début et exploitation fine à la fin.
 *
 * Références :
 *   E.J.S. Pires, J.A.T. Machado, P.B. Moura Oliveira, J.B. Cunha, L. Mendes,
 *   "Particle swarm optimization with fractional-order velocity",
 *   Nonlinear Dynamics 61(1–2):295–301, 2010.
 */
public class FPSOCore implements Optimizer
{
    // ---------------------------------------------------------------
    // Hyperparamètres
    // ---------------------------------------------------------------
    private static final int    M         = 7;    // Fenêtre mémoire GL (5–10 suffit)
    private static final double BETA_MAX  = 0.9;  // Ordre fractionnaire initial
    private static final double BETA_MIN  = 0.4;  // Ordre fractionnaire final
    private static final double C1        = 2.0;
    private static final double C2        = 2.0;
    private static final double VMAX_RATIO = 0.2;
    private static final int    GEN_MAX   = 10_000;

    // ---------------------------------------------------------------
    // Infrastructure
    // ---------------------------------------------------------------
    private final Problem       problem;
    private final int           d;
    private final BoundsChecker bounds;
    private final Random        rng = new Random ();
    private final int           N;

    // ---------------------------------------------------------------
    // État de l'essaim
    // ---------------------------------------------------------------
    private double []   vMax;
    private double [][] x;
    private double [][] pbest;
    private double []   pbestFitness;
    private double []   gbest;
    private double      gbestFitness = Double.POSITIVE_INFINITY;

    /**
     * Historique circulaire des vitesses : vHist[i][j][k] pour la particule i,
     * dimension j, entrée k dans le buffer de taille M.
     * hPtr[i] pointe vers la case où sera écrite la prochaine vitesse (= la plus
     * ancienne, qui sera écrasée).
     */
    private double [][][] vHist;   // [N][d][M]
    private int    []     hPtr;    // [N]

    // Statistiques
    private int generationCount;
    private int totalEvals;
    private int stagnationCounter;
    private final int stagnationThreshold;

    // ---------------------------------------------------------------
    // Constructeur
    // ---------------------------------------------------------------

    public FPSOCore (Problem problem, int d, double [] lb, double [] ub)
    {
        this.problem  = problem;
        this.d        = d;
        this.bounds   = new BoundsChecker (lb, ub);
        this.N        = 10 * d;
        this.stagnationThreshold = N * 10;
    }

    // ---------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------

    @Override
    public void init ()
    {
        vMax         = new double [d];
        x            = new double [N][d];
        pbest        = new double [N][d];
        pbestFitness = new double [N];
        vHist        = new double [N][d][M];
        hPtr         = new int [N];

        gbest        = null;
        gbestFitness = Double.POSITIVE_INFINITY;
        generationCount  = 0;
        totalEvals       = 0;
        stagnationCounter = 0;

        for (int j = 0; j < d; j++)
            vMax [j] = VMAX_RATIO * bounds.getRange (j);

        for (int i = 0; i < N; i++)
        {
            // Position initiale uniforme dans [lb, ub]
            for (int j = 0; j < d; j++)
                x [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);

            // Vitesse initiale faible — peuple tout le buffer d'historique
            for (int j = 0; j < d; j++)
            {
                double v0 = (rng.nextDouble () - 0.5) * vMax [j];
                for (int k = 0; k < M; k++)
                    vHist [i][j][k] = v0;
            }

            hPtr [i] = 0;

            // Évaluation
            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            pbest [i]        = x [i].clone ();
            pbestFitness [i] = fitness;

            if (fitness < gbestFitness)
            {
                gbestFitness = fitness;
                gbest        = x [i].clone ();
            }
        }
    }

    // ---------------------------------------------------------------
    // Boucle principale — une génération
    // ---------------------------------------------------------------

    @Override
    public void step ()
    {
        // β décroissant linéairement
        double beta = BETA_MAX - (BETA_MAX - BETA_MIN) * ((double) generationCount / GEN_MAX);
        if (beta < BETA_MIN) beta = BETA_MIN;

        // Coefficients GL pour cet ordre β : c[0]=1, c[k]=(1−(1+β)/k)·c[k−1]
        double [] gl = glCoeffs (beta, M);

        for (int i = 0; i < N; i++)
        {
            for (int j = 0; j < d; j++)
            {
                // 1. Sauvegarder la vitesse courante dans le buffer circulaire
                //    hPtr[i] pointe sur la case la plus ancienne → on écrase avec la plus récente.
                vHist [i][j][hPtr [i]] = getRecentVelocity (i, j, 0);
            }

            // 2. Calcul de la nouvelle vitesse (fractionnaire)
            for (int j = 0; j < d; j++)
            {
                // Terme fractionnaire : Σ_{k=1}^{M} (−c_k) · v(t−k+1)
                double fracV = 0.0;
                for (int k = 1; k <= M; k++)
                {
                    // v(t−k+1) = entrée k-1 en remontant depuis le head
                    int idx = (hPtr [i] - (k - 1) + 10 * M) % M;
                    fracV += (-gl [k]) * vHist [i][j][idx];
                }

                // Termes cognitif et social
                double r1 = rng.nextDouble ();
                double r2 = rng.nextDouble ();
                double newV = fracV
                        + C1 * r1 * (pbest [i][j] - x [i][j])
                        + C2 * r2 * (gbest [j]    - x [i][j]);

                // Clamp vitesse
                if (newV >  vMax [j]) newV =  vMax [j];
                if (newV < -vMax [j]) newV = -vMax [j];

                // Stocker la nouvelle vitesse dans le buffer (écrase la plus récente sauvée)
                vHist [i][j][hPtr [i]] = newV;

                // Mise à jour de la position
                x [i][j] += newV;
            }

            // 3. Avancer le pointeur circulaire (la prochaine écriture écrasera la plus ancienne)
            hPtr [i] = (hPtr [i] + 1) % M;

            // 4. Évaluation
            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            // 5. Mise à jour pbest
            if (fitness < pbestFitness [i])
            {
                pbestFitness [i] = fitness;
                pbest [i]        = x [i].clone ();
            }

            // 6. Mise à jour gbest
            if (fitness < gbestFitness)
            {
                gbestFitness     = fitness;
                gbest            = x [i].clone ();
                stagnationCounter = 0;
            }
        }

        generationCount++;
        stagnationCounter++;

        if (stagnationCounter >= stagnationThreshold)
            reinitializeHalfSwarm (beta);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Retourne la vitesse de la particule i, dimension j, il y a `offset` pas en arrière
     * (0 = la plus récente sauvegardée, 1 = l'avant-dernière, etc.).
     * Note : avant l'avancement de hPtr, la case hPtr[i] contient la plus récente.
     */
    private double getRecentVelocity (int i, int j, int offset)
    {
        int idx = (hPtr [i] - offset + 10 * M) % M;
        return vHist [i][j][idx];
    }

    /**
     * Calcule les M+1 coefficients de Grünwald-Letnikov pour l'ordre β.
     * c[0] = 1,  c[k] = (1 − (1+β)/k) · c[k−1]
     * Le terme inertiel effectif est −c[k] (positif pour β ∈ (0,1)).
     */
    private static double [] glCoeffs (double beta, int M)
    {
        double [] c = new double [M + 1];
        c [0] = 1.0;
        for (int k = 1; k <= M; k++)
            c [k] = (1.0 - (1.0 + beta) / k) * c [k - 1];
        return c;
    }

    /**
     * Anti-stagnation : réinitialise la moitié des pires particules.
     * Le buffer d'historique est également réinitialisé pour la petite vitesse.
     */
    private void reinitializeHalfSwarm (double beta)
    {
        int half = N / 2;

        Integer [] indices = new Integer [N];
        for (int i = 0; i < N; i++) indices [i] = i;
        java.util.Arrays.sort (indices,
                (a, b) -> Double.compare (pbestFitness [b], pbestFitness [a]));

        for (int k = 0; k < half; k++)
        {
            int i = indices [k];

            for (int j = 0; j < d; j++)
                x [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);

            // Réinitialiser tout l'historique avec une petite vitesse
            for (int j = 0; j < d; j++)
            {
                double v0 = (rng.nextDouble () - 0.5) * vMax [j];
                for (int h = 0; h < M; h++)
                    vHist [i][j][h] = v0;
            }
            hPtr [i] = 0;

            double fitness = problem.evaluate (x [i]);
            totalEvals++;

            pbest [i]        = x [i].clone ();
            pbestFitness [i] = fitness;

            if (fitness < gbestFitness)
            {
                gbestFitness = fitness;
                gbest        = x [i].clone ();
            }
        }

        stagnationCounter = 0;
    }

    // ---------------------------------------------------------------
    // Accesseurs Optimizer
    // ---------------------------------------------------------------

    @Override
    public double [] getBestX ()     { return gbest != null ? gbest.clone () : null; }

    @Override
    public double getBestFitness ()  { return gbestFitness; }

    @Override
    public boolean shouldRestart ()  { return false; }

    @Override
    public OptimizerState getState ()
    {
        return new OptimizerState (generationCount, totalEvals, 0,
                gbestFitness, 0.0, 0.0, gbest);
    }
}
