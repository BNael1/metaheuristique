package bezier2_0.projects.competitor.optipath;

/**
 * Wrapper d'évaluation.
 * Permet d'intercaler du pré-screening (surrogate),
 * du caching, ou d'autres transformations avant l'évaluation réelle.
 */
public interface EvalWrapper
{
    /**
     * Évalue un batch de candidats et retourne leurs fitness.
     * L'implémentation peut filtrer, pré-screener, etc.
     *
     * @param candidates matrice lambda × d
     * @param lambda     nombre de candidats
     * @return fitness[lambda]
     */
    double [] evaluateBatch (double [][] candidates, int lambda);

    /** Nombre total d'évaluations réelles effectuées. */
    int getEvalCount ();

    /** Reset du compteur et de l'état interne (pour restart). */
    default void onRestart () {}
}
