package bezier.projects.competitor.optipath;

/**
 * Utilitaire pour construire et echantillonner une spline de Bezier multi-segments
 * avec continuite C1 aux points de jonction.
 *
 * Pour K=2 segments :
 *   Segment 1 : start -> a_1, ..., a_m1 -> J (junction)
 *   Segment 2 : J -> b_1 (contraint par C1), b_2, ..., b_m2 -> end
 *   Continuite C1 : b_1 = 2*J - a_m1
 *
 * Vecteur de decision (dimension 2*nControlPoints) :
 *   [J_x, J_y, a1_x, a1_y, ..., am1_x, am1_y, b2_x, b2_y, ..., bm2_x, bm2_y]
 */
public class MultiSegmentBezier
{
    private final int m1;          // CPs internes du segment 1
    private final int m2;          // CPs internes du segment 2 (y compris b_1 contraint)
    private final int nTargetCPs;  // nControlPoints du probleme
    private final double startX, startY, endX, endY;

    public MultiSegmentBezier (int nControlPoints,
                               double startX, double startY,
                               double endX, double endY)
    {
        this.nTargetCPs = nControlPoints;
        this.m1 = (nControlPoints + 1) / 2;
        this.m2 = nControlPoints - m1;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
    }

    /** Dimension du vecteur de decision = 2 * nControlPoints. */
    public int getDimension ()
    {
        return 2 * nTargetCPs;
    }

    /**
     * Convertit un vecteur de decision multi-segment en points de controle standard
     * compatibles avec Problem.evaluate(double[]).
     *
     * @param decision Vecteur de decision de dimension 2*nControlPoints
     * @return Tableau plat [x0,y0, x1,y1, ...] de 2*nControlPoints doubles
     */
    public double [] toControlPoints (double [] decision)
    {
        // --- Decoder le vecteur de decision ---
        double jx = decision [0];
        double jy = decision [1];

        // Segment 1 : [start, a_1, ..., a_m1, J]  ->  m1 + 2 CPs
        int seg1Size = m1 + 2;
        double [] seg1X = new double [seg1Size];
        double [] seg1Y = new double [seg1Size];
        seg1X [0] = startX;
        seg1Y [0] = startY;
        for (int i = 0; i < m1; i++)
        {
            seg1X [i + 1] = decision [2 + 2 * i];
            seg1Y [i + 1] = decision [2 + 2 * i + 1];
        }
        seg1X [seg1Size - 1] = jx;
        seg1Y [seg1Size - 1] = jy;

        // Segment 2 : [J, b_1, b_2, ..., b_m2, end]  ->  m2 + 2 CPs
        // b_1 = 2*J - a_m1 (continuite C1)
        double b1x = 2 * jx - decision [2 + 2 * (m1 - 1)];
        double b1y = 2 * jy - decision [2 + 2 * (m1 - 1) + 1];

        int seg2Size = m2 + 2;
        double [] seg2X = new double [seg2Size];
        double [] seg2Y = new double [seg2Size];
        seg2X [0] = jx;
        seg2Y [0] = jy;
        seg2X [1] = b1x;
        seg2Y [1] = b1y;
        int offset = 2 + 2 * m1;
        for (int i = 0; i < m2 - 1; i++)
        {
            seg2X [i + 2] = decision [offset + 2 * i];
            seg2Y [i + 2] = decision [offset + 2 * i + 1];
        }
        seg2X [seg2Size - 1] = endX;
        seg2Y [seg2Size - 1] = endY;

        // --- Echantillonner la spline a nTargetCPs points, repartis par segment ---
        // On repartit equitablement les points entre les 2 segments
        // et on echantillonne a des t interieurs (evite clustering a la jonction).
        int fromSeg1 = nTargetCPs / 2;
        int fromSeg2 = nTargetCPs - fromSeg1;

        double [] result = new double [2 * nTargetCPs];
        int idx = 0;

        for (int i = 0; i < fromSeg1; i++)
        {
            double t = (double) (i + 1) / (fromSeg1 + 1);
            result [2 * idx]     = deCasteljau (seg1X, t);
            result [2 * idx + 1] = deCasteljau (seg1Y, t);
            idx++;
        }
        for (int i = 0; i < fromSeg2; i++)
        {
            double t = (double) (i + 1) / (fromSeg2 + 1);
            result [2 * idx]     = deCasteljau (seg2X, t);
            result [2 * idx + 1] = deCasteljau (seg2Y, t);
            idx++;
        }
        return result;
    }

    /**
     * Convertit des CPs standard en vecteur de decision multi-segment.
     * La jonction est placee au CP du milieu.
     *
     * @param stdCPs Tableau plat de 2*nControlPoints doubles
     * @return Vecteur de decision multi-segment
     */
    public double [] fromStandardCPs (double [] stdCPs)
    {
        double [] ms = new double [2 * nTargetCPs];

        // Junction = CP a l'indice m1 (0-base)
        ms [0] = stdCPs [2 * m1];
        ms [1] = stdCPs [2 * m1 + 1];

        // a_1..a_m1 = premiers m1 CPs standard
        for (int i = 0; i < m1; i++)
        {
            ms [2 + 2 * i]     = stdCPs [2 * i];
            ms [2 + 2 * i + 1] = stdCPs [2 * i + 1];
        }

        // b_2..b_m2 = CPs standard aux indices m1+1 .. nCP-1
        for (int i = 0; i < m2 - 1; i++)
        {
            int srcIdx = m1 + 1 + i;
            ms [2 + 2 * m1 + 2 * i]     = stdCPs [2 * srcIdx];
            ms [2 + 2 * m1 + 2 * i + 1] = stdCPs [2 * srcIdx + 1];
        }

        return ms;
    }

    /**
     * Algorithme de De Casteljau pour evaluer un polynome de Bezier 1D.
     */
    private static double deCasteljau (double [] cps, double t)
    {
        int n = cps.length;
        double [] temp = new double [n];
        System.arraycopy (cps, 0, temp, 0, n);
        for (int k = 1; k < n; k++)
            for (int i = 0; i < n - k; i++)
                temp [i] = (1 - t) * temp [i] + t * temp [i + 1];
        return temp [0];
    }
}
