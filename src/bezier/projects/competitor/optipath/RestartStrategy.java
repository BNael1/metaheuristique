package bezier.projects.competitor.optipath;

/**
 * Stratégie de restart pour CMA-ES.
 * Chaque implémentation décide de la taille de population, du sigma
 * et du centroïde initial pour le prochain restart.
 */
public interface RestartStrategy
{
    /** Calcule la configuration du prochain restart. */
    RestartConfig nextRestart (RestartContext ctx);

    /**
     * Callback optionnel appelé après chaque évaluation dans step().
     * Utilisé par les stratégies qui maintiennent un état (ex: GridMapElites archive).
     */
    default void onEvaluation (double [] x, double fitness) {}

    /**
     * Callback optionnel appelé quand le best global s'améliore.
     * Utilisé par SHADE-sigma pour enregistrer le sigma courant.
     */
    default void onImprovement (double currentSigma) {}
}
