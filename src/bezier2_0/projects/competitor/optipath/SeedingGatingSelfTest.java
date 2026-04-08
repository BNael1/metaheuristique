package bezier2_0.projects.competitor.optipath;

/**
 * Validation du gating anti-overfit des seeds specialises zigzag.
 */
public final class SeedingGatingSelfTest
{
    public static void main (String [] args)
    {
        testGatingDecision ();
        System.out.println ("SeedingGatingSelfTest OK");
    }

    private static void testGatingDecision ()
    {
        // Carte structuree avec "dents" verticales alternantes
        int nA = 28;
        double [] ax = new double [nA];
        double [] ay = new double [nA];
        double [] ar = new double [nA];
        int idx = 0;
        for (double y = 6; y <= 44.0 + 1e-9 && idx < nA; y += 2.6)
        {
            ax[idx] = 10; ay[idx] = y; ar[idx] = 0.8; idx++;
            if (idx >= nA) break;
            ax[idx] = 24; ay[idx] = 50 - y; ar[idx] = 0.8; idx++;
        }
        while (idx < nA)
        {
            ax[idx] = 40; ay[idx] = 6 + (idx % 8) * 4; ar[idx] = 0.8;
            idx++;
        }

        MapDiagnostics structured = MapDiagnostics.compute (
                2, 25, 48, 25,
                0, 0, 50, 50,
                ax, ay, ar);

        // Carte diffuse
        double [] bx = {8, 18, 28, 36, 44};
        double [] by = {8, 19, 31, 12, 40};
        double [] br = {0.8, 0.8, 0.8, 0.8, 0.8};
        MapDiagnostics diffuse = MapDiagnostics.compute (
                2, 25, 48, 25,
                0, 0, 50, 50,
                bx, by, br);

        assertTrue (structured.wallAlignmentConfidence >= diffuse.wallAlignmentConfidence,
                "structured map should have higher alignment confidence");
        // La decision de gating depend de plusieurs signaux, mais la carte diffuse
        // ne doit en general pas activer le mode specialise.
        assertTrue (diffuse.shouldEnableSpecializedZigzag () == false,
                "diffuse map should not enable specialized zigzag");
    }

    private static void assertTrue (boolean cond, String message)
    {
        if (cond == false)
            throw new IllegalStateException ("Assertion failed: " + message);
    }
}
