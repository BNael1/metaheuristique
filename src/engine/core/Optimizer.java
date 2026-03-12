package engine.core;

/**
 * Interface commune pour tous les algorithmes d'optimisation.
 * Chaque implémentation gère une population et fait évoluer les solutions.
 */
public interface Optimizer
{
    /** Initialisation complète (population, paramètres, premières évaluations). */
    void init ();

    /** Exécute exactement UNE génération. */
    void step ();

    /** Retourne le meilleur vecteur de décision trouvé (ou null). */
    double [] getBestX ();

    /** Retourne le meilleur coût trouvé. */
    double getBestFitness ();

    /** Indique si l'algo est en condition de restart. */
    boolean shouldRestart ();

    /** État observable pour monitoring/GUI. */
    OptimizerState getState ();
}
