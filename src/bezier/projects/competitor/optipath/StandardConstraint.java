package bezier.projects.competitor.optipath;

import java.util.Comparator;

/**
 * Tri standard par fitness : le plus courant, pas de gestion contraintes spéciale.
 */
public class StandardConstraint implements ConstraintHandler
{
    @Override
    public Comparator<Integer> getComparator (double [] fitness, int lambda)
    {
        return (a, b) -> Double.compare (fitness [a], fitness [b]);
    }
}
