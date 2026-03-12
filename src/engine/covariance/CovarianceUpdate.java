package engine.covariance;

/**
 * Stratégie de mise à jour de la matrice de covariance.
 * Permet de basculer entre full CMA-ES, separable, active, etc.
 */
public interface CovarianceUpdate
{
    /** Mode de mise à jour : plein (d×d) ou séparable (diagonale seule). */
    enum Mode { FULL, SEPARABLE }

    /**
     * Retourne le mode courant. CMAESCore adapte l'échantillonnage
     * et l'eigendécomposition en conséquence.
     */
    default Mode getMode () { return Mode.FULL; }

    /**
     * Met à jour la covariance après une génération (mode FULL).
     *
     * @param C       matrice de covariance d×d (modifiée en place)
     * @param pc      chemin d'évolution pour C
     * @param ary     vecteurs y_k = (x_k - mean_old) / sigma pour chaque offspring
     * @param idx     indices triés par fitness croissante
     * @param weights poids de recombinaison (taille mu)
     * @param mu      nombre de parents sélectionnés
     * @param c1      taux rank-1
     * @param cmu     taux rank-mu
     * @param hsig    indicateur h_sigma (0 ou 1)
     * @param cc      taux de cumulation pour C
     */
    void updateCovariance (double [][] C, double [] pc, double [][] ary,
                           Integer [] idx, double [] weights, int mu,
                           double c1, double cmu, int hsig, double cc);

    /**
     * Met à jour la diagonale seule (mode SEPARABLE). O(d) au lieu de O(d²).
     * Implémentation par défaut : ne fait rien (override dans SeparableWarmupCovariance).
     *
     * @param diagC diagonale de C (modifiée en place)
     * @param diagD sqrt(diagC) (modifié en place)
     * @param pc    chemin d'évolution pour C
     * @param arx   positions des offspring
     * @param oldMean ancien mean (avant sélection)
     * @param sigma sigma courant
     * @param idx    indices triés par fitness
     * @param weights poids
     * @param mu     nombre de parents
     * @param c1     taux rank-1
     * @param cmu    taux rank-mu
     * @param hsig   h_sigma
     * @param cc     taux de cumulation
     */
    default void updateDiagonal (double [] diagC, double [] diagD, double [] pc,
                                 double [][] arx, double [] oldMean, double sigma,
                                 Integer [] idx, double [] weights, int mu,
                                 double c1, double cmu, int hsig, double cc) {}

    /**
     * Callback au restart, pour sauvegarder/restaurer la covariance.
     * @param C matrice courante (à lire ou modifier)
     */
    default void onRestart (double [][] C) {}

    /**
     * Callback au restart en mode SEPARABLE.
     * @param diagC diagonale de C (à modifier si besoin)
     */
    default void onRestartSep (double [] diagC) {}

    /**
     * Appelé avant l'initialisation du restart pour permettre de réinitialiser le mode interne.
     */
    default void prepareRestart () {}
}
