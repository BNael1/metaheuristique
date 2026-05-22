package bezier.projects.competitor.optipath;

/**
 * Stratégie de mise à jour de la matrice de covariance.
 * Permet de basculer entre full CMA-ES, separable, active, etc.
 */
public interface CovarianceUpdate
{
    /** Mode de mise à jour : plein (d×d). */
    enum Mode { FULL }

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
     * Callback au restart, pour sauvegarder/restaurer la covariance.
     * @param C matrice courante (à lire ou modifier)
     */
    default void onRestart (double [][] C) {}


    /**
     * Appelé avant l'initialisation du restart pour permettre de réinitialiser le mode interne.
     */
    default void prepareRestart () {}
}
