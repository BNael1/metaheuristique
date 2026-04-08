package bezier.projects.competitor.optipath;

import java.util.ArrayList;
import java.util.EnumMap;

/**
 * Micro-tests de selection fitness + diversite du portfolio.
 */
public final class SeedPortfolioSelfTest
{
    public static void main (String [] args)
    {
        testDiversityAndFamilyCaps ();
        System.out.println ("SeedPortfolioSelfTest OK");
    }

    private static void testDiversityAndFamilyCaps ()
    {
        SeedPortfolio p = new SeedPortfolio (4);
        for (int i = 0; i < 12; i++)
        {
            double [] v = new double [] {10 + 0.01 * i, 10, 10, 10};
            p.add (SeedFamily.LINEAR_GRID, v, 10.0 + i * 0.01);
        }
        for (int i = 0; i < 8; i++)
        {
            double [] v = new double [] {20 + i, 20, 20, 20};
            p.add (SeedFamily.RANDOM, v, 11.0 + i * 0.1);
        }

        EnumMap<SeedFamily, Integer> caps = new EnumMap<> (SeedFamily.class);
        caps.put (SeedFamily.LINEAR_GRID, 2);
        caps.put (SeedFamily.RANDOM, 4);

        ArrayList<SeedCandidate> selected = p.selectTopDiverse (6, 0.5, caps);
        assertTrue (selected.size () == 6, "expected 6 seeds selected");

        int nLinear = 0;
        int nRandom = 0;
        for (SeedCandidate c : selected)
        {
            if (c.family == SeedFamily.LINEAR_GRID) nLinear++;
            if (c.family == SeedFamily.RANDOM) nRandom++;
        }

        assertTrue (nLinear <= 2, "family cap LINEAR_GRID should be respected");
        assertTrue (nRandom <= 4, "family cap RANDOM should be respected");
    }

    private static void assertTrue (boolean cond, String message)
    {
        if (cond == false)
            throw new IllegalStateException ("Assertion failed: " + message);
    }
}
