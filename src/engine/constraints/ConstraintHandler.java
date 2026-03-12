package engine.constraints;

import java.util.Comparator;

/**
 * Gestion des contraintes pour le tri de la population.
 * Par défaut, compare par fitness. StochasticRanking et d'autres techniques
 * peuvent fournir un comparateur alternatif.
 */
public interface ConstraintHandler
{
    /**
     * Retourne un comparateur pour trier les indices de la population.
     * Le comparateur opère sur les indices (Integer) et accède aux tableaux
     * de fitness et de violation en interne.
     *
     * @param fitness   tableau de fitness
     * @param lambda    taille de la population
     * @return comparateur d'indices
     */
    Comparator<Integer> getComparator (double [] fitness, int lambda);

    /**
     * Tri de la population. Par défaut, tri standard avec le comparateur.
     * StochasticRanking utilise un bubble sort probabiliste.
     *
     * @param idx     indices à trier (modifié en place)
     * @param fitness tableau de fitness
     * @param arx     positions des individus (lambda × d)
     */
    default void sortPopulation (Integer [] idx, double [] fitness, double [][] arx)
    {
        java.util.Arrays.sort (idx, getComparator (fitness, idx.length));
    }
}
