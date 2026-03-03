package bezier.projects.competitor.optipath;

/**
 * Interface commune pour tous les algorithmes d'optimisation.
 * Chaque implémentation gère une population et fait évoluer les solutions.
 */
public interface Optimizer
{
    /** Initialisation complète de l'algorithme (population, paramètres, premières évaluations). */
    void init ();

    /** Exécute exactement UNE génération (échantillonnage + évaluation + mise à jour). */
    void step ();

    /** Retourne le meilleur vecteur de décision trouvé jusqu'ici (ou null). */
    double [] getBestX ();
}
