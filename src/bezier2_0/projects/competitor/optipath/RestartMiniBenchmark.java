package bezier2_0.projects.competitor.optipath;

import bezier2_0.evaluation.Problem;
import bezier2_0.projects.CompetitorProject;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Mini benchmark iteratif pour choisir rapidement une strategie de restart
 * robuste (moyenne + anti-catastrophe).
 *
 * Usage:
 *   java -cp "lib/*:bin" bezier2_0.projects.competitor.optipath.RestartMiniBenchmark --seconds 12 --runs 3
 */
public final class RestartMiniBenchmark
{
    private RestartMiniBenchmark () {}

    private interface StrategyFactory
    {
        OuterRestartStrategy create ();
    }

    private static class ProblemStats
    {
        final String problem;
        final double mean;
        final double median;
        final double worst;
        final double jScore;
        final double rScore;

        ProblemStats (String problem, double mean, double median, double worst, double jScore, double rScore)
        {
            this.problem = problem;
            this.mean = mean;
            this.median = median;
            this.worst = worst;
            this.jScore = jScore;
            this.rScore = rScore;
        }
    }

    private static class VariantResult
    {
        final String name;
        final ProblemStats [] perProblem;

        VariantResult (String name, ProblemStats [] perProblem)
        {
            this.name = name;
            this.perProblem = perProblem;
        }
    }

    public static void main (String [] args) throws Exception
    {
        int seconds = 60;
        int runs = 10;
        for (int i = 0; i < args.length; i++)
        {
            if ("--seconds".equals (args [i]) && i + 1 < args.length)
                seconds = Integer.parseInt (args [++i]);
            else if ("--runs".equals (args [i]) && i + 1 < args.length)
                runs = Integer.parseInt (args [++i]);
        }

        Problem.headless = true;

        VariantResult baseline = runVariant ("Baseline_Legacy", () -> new LegacyThresholdRestart (), seconds, runs);
        VariantResult v1 = runVariant ("V1_DynFrust", HybridMultiSignalRestart::v1, seconds, runs);
        VariantResult v2 = runVariant ("V2_AddGeo", HybridMultiSignalRestart::v2, seconds, runs);
        VariantResult v3 = runVariant ("V3_AddUCB", HybridMultiSignalRestart::v3, seconds, runs);

        VariantResult [] ordered = new VariantResult [] {v1, v2, v3};
        VariantResult selected = null;
        for (VariantResult cand : ordered)
        {
            if (betterThanBaseline (cand, baseline))
            {
                selected = cand;
                break;
            }
        }
        if (selected == null)
            selected = bestAggregate (ordered);
        printWinner (baseline, selected);
    }

    private static VariantResult runVariant (String name, StrategyFactory factory, int seconds, int runs)
            throws Exception
    {
        ArrayList<Problem> problems = Problem.getProblems ();
        ProblemStats [] stats = new ProblemStats [problems.size ()];

        System.out.println ();
        System.out.println ("=== " + name + " ===");
        for (int p = 0; p < problems.size (); p++)
        {
            Problem problem = problems.get (p);
            double [] vals = new double [runs];
            for (int r = 0; r < runs; r++)
            {
                problem.reset ();
                CompetitorProject project = new OptiPath (problem, factory.create ());
                project.initialization ();
                long deadline = System.currentTimeMillis () + seconds * 1000L;
                while (System.currentTimeMillis () < deadline)
                    project.loop ();
                vals [r] = problem.getBestEvaluation ();
                System.out.printf ("%s run %d/%d -> %.6f%n", problem.getName (), r + 1, runs, vals [r]);
            }
            stats [p] = computeStats (problem.getName (), vals);
            System.out.printf ("  %s mean=%.4f median=%.4f worst=%.4f J=%.4f R=%.4f%n",
                    stats [p].problem, stats [p].mean, stats [p].median, stats [p].worst,
                    stats [p].jScore, stats [p].rScore);
        }
        return new VariantResult (name, stats);
    }

    private static ProblemStats computeStats (String problemName, double [] vals)
    {
        double [] sorted = vals.clone ();
        Arrays.sort (sorted);

        double mean = 0.0;
        for (double v : vals) mean += v;
        mean /= Math.max (1, vals.length);

        double median;
        if (sorted.length % 2 == 0)
            median = 0.5 * (sorted [sorted.length / 2 - 1] + sorted [sorted.length / 2]);
        else
            median = sorted [sorted.length / 2];
        double worst = sorted [sorted.length - 1];
        int k = Math.min (3, sorted.length);
        double worstMean = 0.0;
        for (int i = 0; i < k; i++)
            worstMean += sorted [sorted.length - 1 - i];
        worstMean /= Math.max (1, k);

        double jScore = mean + 0.7 * worstMean;
        double rScore = (Math.abs (median) > 1e-12) ? worst / median : Double.POSITIVE_INFINITY;
        return new ProblemStats (problemName, mean, median, worst, jScore, rScore);
    }

    private static boolean betterThanBaseline (VariantResult candidate, VariantResult baseline)
    {
        double bJ = aggregateJ (baseline);
        double bR = aggregateR (baseline);
        double cJ = aggregateJ (candidate);
        double cR = aggregateR (candidate);
        return cJ < bJ && cR <= bR;
    }

    private static VariantResult bestAggregate (VariantResult [] variants)
    {
        VariantResult best = variants [0];
        double bestJ = aggregateJ (best);
        double bestR = aggregateR (best);
        for (int i = 1; i < variants.length; i++)
        {
            VariantResult v = variants [i];
            double j = aggregateJ (v);
            double r = aggregateR (v);
            if (j < bestJ - 1e-9 || (Math.abs (j - bestJ) <= 1e-9 && r < bestR))
            {
                best = v;
                bestJ = j;
                bestR = r;
            }
        }
        return best;
    }

    private static double aggregateJ (VariantResult v)
    {
        double s = 0.0;
        for (ProblemStats p : v.perProblem) s += p.jScore;
        return s / Math.max (1, v.perProblem.length);
    }

    private static double aggregateR (VariantResult v)
    {
        double s = 0.0;
        for (ProblemStats p : v.perProblem) s += p.rScore;
        return s / Math.max (1, v.perProblem.length);
    }

    private static void printWinner (VariantResult baseline, VariantResult chosen)
    {
        System.out.println ();
        System.out.println ("=== Baseline vs Selected ===");
        for (int i = 0; i < baseline.perProblem.length; i++)
        {
            ProblemStats b = baseline.perProblem [i];
            ProblemStats c = chosen.perProblem [i];
            System.out.printf ("%s | base J=%.4f R=%.4f || chosen J=%.4f R=%.4f%n",
                    b.problem, b.jScore, b.rScore, c.jScore, c.rScore);
        }
        System.out.println ("Selected variant: " + chosen.name);
    }
}
