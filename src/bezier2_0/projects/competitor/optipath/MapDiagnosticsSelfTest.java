package bezier2_0.projects.competitor.optipath;

/**
 * Micro-tests sans framework pour valider les diagnostics de carte.
 */
public final class MapDiagnosticsSelfTest
{
    public static void main (String [] args)
    {
        testSparseVsStructured ();
        System.out.println ("MapDiagnosticsSelfTest OK");
    }

    private static void testSparseVsStructured ()
    {
        double [] sx = {10, 20, 30};
        double [] sy = {10, 20, 30};
        double [] sr = {0.8, 0.8, 0.8};
        MapDiagnostics sparse = MapDiagnostics.compute (
                2, 25, 48, 25,
                0, 0, 50, 50,
                sx, sy, sr);

        // Mini peigne structure
        int n = 26;
        double [] ox = new double [n];
        double [] oy = new double [n];
        double [] or_ = new double [n];
        int idx = 0;
        for (double y = 6; y <= 44.0 + 1e-9; y += 3.8)
        {
            ox[idx] = 12.0; oy[idx] = y; or_[idx] = 0.8; idx++;
            if (idx >= n) break;
        }
        for (double y = 6; y <= 44.0 + 1e-9 && idx < n; y += 3.8)
        {
            ox[idx] = 30.0; oy[idx] = y; or_[idx] = 0.8; idx++;
        }
        for (; idx < n; idx++)
        {
            ox[idx] = 5 + idx;
            oy[idx] = 42;
            or_[idx] = 0.8;
        }

        MapDiagnostics structured = MapDiagnostics.compute (
                2, 25, 48, 25,
                0, 0, 50, 50,
                ox, oy, or_);

        assertTrue (structured.wallAlignmentConfidence >= sparse.wallAlignmentConfidence,
                "alignment confidence should be higher on structured map");
        assertTrue (structured.pathStretch >= 1.0, "path stretch should be >= 1");
        assertTrue (sparse.pathStretch >= 1.0, "path stretch should be >= 1");
    }

    private static void assertTrue (boolean cond, String message)
    {
        if (!cond)
            throw new IllegalStateException ("Assertion failed: " + message);
    }
}
