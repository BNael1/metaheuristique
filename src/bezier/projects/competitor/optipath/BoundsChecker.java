package bezier.projects.competitor.optipath;

/**
 * Utilitaire de gestion des bornes de l'espace de recherche.
 * Indices pairs = coordonnées X, indices impairs = coordonnées Y.
 */
public class BoundsChecker
{
    private final double [] lb;
    private final double [] ub;

    public BoundsChecker (double [] lb, double [] ub)
    {
        this.lb = lb;
        this.ub = ub;
    }

    /** Retourne une copie clampée de x dans [lb, ub]. */
    public double [] clamp (double [] x)
    {
        double [] result = x.clone ();
        clampInPlace (result);
        return result;
    }

    /** Clampe x en place dans [lb, ub]. */
    public void clampInPlace (double [] x)
    {
        for (int i = 0; i < x.length; i++)
        {
            if (x [i] < lb [i]) x [i] = lb [i];
            if (x [i] > ub [i]) x [i] = ub [i];
        }
    }

    public double [] getLb () { return lb; }
    public double [] getUb () { return ub; }
    public int getDimension () { return lb.length; }

    /** Range ub[i] - lb[i] pour la dimension i. */
    public double getRange (int i) { return ub [i] - lb [i]; }
}
