package bezier2_0.projects.competitor.optipath;

/**
 * Résultat d'un échantillonnage : positions et différences normalisées.
 */
public class SampleResult
{
    /** Positions des offspring : arx[k][i] = mean[i] + sigma * ary[k][i] */
    public final double [][] arx;

    /** Différences normalisées : ary[k] = B * D * z_k */
    public final double [][] ary;

    public SampleResult (double [][] arx, double [][] ary)
    {
        this.arx = arx;
        this.ary = ary;
    }
}
