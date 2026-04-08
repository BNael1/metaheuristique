package bezier.projects.competitor.optipath;

import java.util.ArrayList;
import java.util.EnumMap;

/**
 * Portfolio de seeds avec selection fitness + diversite.
 */
public final class SeedPortfolio
{
    private final int dimension;
    private final ArrayList<SeedCandidate> candidates;

    public SeedPortfolio (int dimension)
    {
        this.dimension = Math.max (1, dimension);
        this.candidates = new ArrayList<> ();
    }

    public void add (SeedFamily family, double [] vector, double fitness)
    {
        if (vector == null || !Double.isFinite (fitness)) return;
        this.candidates.add (new SeedCandidate (family, vector.clone (), fitness));
    }

    public int size ()
    {
        return this.candidates.size ();
    }

    public ArrayList<SeedCandidate> selectTopDiverse (int maxSeeds,
                                                      double minDistance,
                                                      EnumMap<SeedFamily, Integer> familyCaps)
    {
        ArrayList<SeedCandidate> sorted = new ArrayList<> (candidates);
        sorted.sort ((a, b) -> Double.compare (a.fitness, b.fitness));

        ArrayList<SeedCandidate> selected = new ArrayList<> ();
        EnumMap<SeedFamily, Integer> usedByFamily = new EnumMap<> (SeedFamily.class);

        double [] thresholds = new double [] {
                Math.max (0.0, minDistance),
                Math.max (0.0, minDistance * 0.6),
                Math.max (0.0, minDistance * 0.3),
                0.0
        };

        for (double threshold : thresholds)
        {
            if (selected.size () >= maxSeeds) break;
            for (SeedCandidate cand : sorted)
            {
                if (selected.size () >= maxSeeds) break;
                if (containsVector (selected, cand.vector)) continue;
                if (!underFamilyCap (cand.family, usedByFamily, familyCaps, maxSeeds)) continue;
                if (!isDiverseEnough (selected, cand.vector, threshold)) continue;
                selected.add (cand);
                usedByFamily.put (cand.family, usedByFamily.getOrDefault (cand.family, 0) + 1);
            }
        }

        return selected;
    }

    private boolean containsVector (ArrayList<SeedCandidate> selected, double [] vector)
    {
        for (SeedCandidate s : selected)
            if (distanceNorm (s.vector, vector) <= 1e-9) return true;
        return false;
    }

    private boolean underFamilyCap (SeedFamily family,
                                    EnumMap<SeedFamily, Integer> used,
                                    EnumMap<SeedFamily, Integer> caps,
                                    int fallbackCap)
    {
        int cap = (caps != null) ? caps.getOrDefault (family, fallbackCap) : fallbackCap;
        int usedCount = used.getOrDefault (family, 0);
        return usedCount < cap;
    }

    private boolean isDiverseEnough (ArrayList<SeedCandidate> selected, double [] candidate, double threshold)
    {
        if (threshold <= 0.0 || selected.isEmpty ()) return true;
        for (SeedCandidate s : selected)
            if (distanceNorm (s.vector, candidate) < threshold) return false;
        return true;
    }

    private double distanceNorm (double [] a, double [] b)
    {
        int n = Math.min (Math.min (a.length, b.length), this.dimension);
        if (n <= 0) return Double.POSITIVE_INFINITY;
        double sum = 0.0;
        for (int i = 0; i < n; i++)
        {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt (sum / n);
    }
}
