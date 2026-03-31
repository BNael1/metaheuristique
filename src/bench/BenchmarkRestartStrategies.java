package bench;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.competitor.optipath.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benchmark des strategies de restart externe pour OptiPath.
 *
 * Compare les strategies (T0-T14) sur les 4 problemes.
 * Resultats dans results/bench_restart_strategies.csv
 *
 * Usage :
 *   java -cp "lib/*:bin" bench.BenchmarkRestartStrategies [--seconds N] [--runs N] [--reset] [--only NAME]
 */
public class BenchmarkRestartStrategies
{
    private static int NB_SECONDS = 60;
    private static int NB_RUNS    = 5;
    private static String CSV_PATH = "results/bench_restart_strategies.csv";

    private static final String [] STRATEGY_NAMES =
            {"T0_Legacy", "T1_RelativeImprv", "T2_SeedCalibrated",
             "T3_Percentile", "T4_TimeSlice", "T5_GatedSlice",
             "T6_SigmaCollapse", "T7_PortfolioAgrmt", "T8_Hybrid",
             "T9_SpectralPlateau", "T10_OccEntropy", "T11_CurvePersist",
             "T12_BayesCred", "T13_StepFrustr", "T14_TimePhasedFocus"};

    public static void main (String [] args) throws Exception
    {
        boolean reset = false;
        String onlyStrategy = null;

        for (int i = 0; i < args.length; i++)
        {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--runs") && i + 1 < args.length)
                NB_RUNS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--reset"))
                reset = true;
            else if (args [i].equals ("--only") && i + 1 < args.length)
                onlyStrategy = args [++i];
        }

        String [] strategyNames = STRATEGY_NAMES;
        if (onlyStrategy != null)
        {
            if (indexOf (STRATEGY_NAMES, onlyStrategy) < 0)
                throw new IllegalArgumentException ("Strategie inconnue: " + onlyStrategy);
            strategyNames = new String[]{onlyStrategy};
        }

        Problem.headless = true;

        new File ("results").mkdirs ();
        File csvFile = new File (CSV_PATH);

        // --- Charger les resultats existants ---
        LinkedHashMap<String, Double> existing = new LinkedHashMap<> ();
        if (!reset && csvFile.exists ())
        {
            try (BufferedReader br = new BufferedReader (new FileReader (csvFile)))
            {
                String line;
                boolean header = true;
                while ((line = br.readLine ()) != null)
                {
                    if (header) { header = false; continue; }
                    String [] parts = line.split (",");
                    if (parts.length >= 4)
                    {
                        String key = parts [0].trim () + "," + parts [1].trim () + "," + parts [2].trim ();
                        try { existing.put (key, Double.parseDouble (parts [3].trim ())); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            System.err.println ("Charge " + existing.size () + " resultats depuis " + CSV_PATH);
        }
        else if (reset && csvFile.exists ())
        {
            csvFile.delete ();
        }

        if (!csvFile.exists ())
        {
            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile)))
            {
                pw.println ("Strategy,Problem,Run,Score");
            }
        }

        ArrayList<Problem> problems = Problem.getProblems ();
        String [] probNames = new String [problems.size ()];
        for (int i = 0; i < probNames.length; i++)
            probNames [i] = problems.get (i).getName ();

        @SuppressWarnings ("unchecked")
        ArrayList<Double> [][] scores = new ArrayList [strategyNames.length][problems.size ()];
        for (int s = 0; s < strategyNames.length; s++)
            for (int p = 0; p < problems.size (); p++)
                scores [s][p] = new ArrayList<> ();

        for (Map.Entry<String, Double> entry : existing.entrySet ())
        {
            String [] parts = entry.getKey ().split (",");
            int s = indexOf (strategyNames, parts [0]);
            int p = indexOf (probNames, parts [1]);
            if (s >= 0 && p >= 0)
                scores [s][p].add (entry.getValue ());
        }

        int totalMissing = 0;
        for (int s = 0; s < strategyNames.length; s++)
            for (int p = 0; p < problems.size (); p++)
                totalMissing += Math.max (0, NB_RUNS - scores [s][p].size ());

        System.err.println ("=== Benchmark Restart Strategies : " + NB_RUNS + " runs x " + NB_SECONDS + "s ===");
        if (totalMissing == 0)
        {
            System.err.println ("Tout est deja complet.");
            printSummary (scores, probNames, strategyNames);
            return;
        }
        System.err.println (totalMissing + " run(s) restant(s).");
        System.err.println ();

        // --- Executer ---
        for (int s = 0; s < strategyNames.length; s++)
        {
            for (int p = 0; p < problems.size (); p++)
            {
                Problem problem = problems.get (p);
                int alreadyDone = scores [s][p].size ();

                for (int run = alreadyDone + 1; run <= NB_RUNS; run++)
                {
                        System.err.print (strategyNames [s] + " / " + problem.getName ()
                            + " / run " + run + " ... ");

                    problem.reset ();

                    try
                    {
                        OuterRestartStrategy strategy = createStrategy (strategyNames [s]);
                        CompetitorProject project = new OptiPath (problem, strategy);

                        project.initialization ();
                        long deadline = System.currentTimeMillis () + NB_SECONDS * 1000L;
                        while (System.currentTimeMillis () < deadline)
                        {
                            project.loop ();
                        }

                        double score = problem.getBestEvaluation ();
                        scores [s][p].add (score);
                        System.err.println ("score = " + String.format ("%.2f", score));

                        try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile, true)))
                        {
                            pw.println (strategyNames [s] + "," + problem.getName ()
                                    + "," + run + "," + score);
                        }
                    }
                    catch (Exception ex)
                    {
                        System.err.println ("ERREUR : " + ex.getMessage ());
                        ex.printStackTrace (System.err);
                    }
                }
            }
        }

        printSummary (scores, probNames, strategyNames);
        System.err.println ("=== Benchmark termine. Resultats dans " + CSV_PATH + " ===");
    }

    private static OuterRestartStrategy createStrategy (String name)
    {
        switch (name)
        {
            case "T0_Legacy":          return new LegacyThresholdRestart ();
            case "T1_RelativeImprv":   return new RelativeImprovementRestart ();
            case "T2_SeedCalibrated":  return new SeedCalibratedRestart ();
            case "T3_Percentile":      return new PercentileThresholdRestart ();
            case "T4_TimeSlice":       return new TimeSliceRestart ();
            case "T5_GatedSlice":      return new GatedTimeSliceRestart ();
            case "T6_SigmaCollapse":   return new SigmaCollapseRestart ();
            case "T7_PortfolioAgrmt":  return new PortfolioAgreementRestart ();
            case "T8_Hybrid":          return new HybridMultiSignalRestart ();
            case "T9_SpectralPlateau": return new SpectralPlateauRestart ();
            case "T10_OccEntropy":     return new OccupancyEntropyRestart ();
            case "T11_CurvePersist":   return new CurveShapePersistenceRestart ();
            case "T12_BayesCred":      return new BayesianCredibilityRestart ();
            case "T13_StepFrustr":     return new StepFrustrationRestart ();
            case "T14_TimePhasedFocus": return new TimePhasedFocusRestart ();
            default:                   return new LegacyThresholdRestart ();
        }
    }

    private static void printSummary (ArrayList<Double> [][] scores, String [] probNames,
                                      String [] strategyNames)
    {
        System.out.println ();
        System.out.println ("=== RESULTATS : Mean (Best / Worst) ===");
        System.out.println ();

        // Header
        StringBuilder hdr = new StringBuilder ();
        hdr.append (String.format ("%-20s", "Strategy"));
        for (String pn : probNames)
            hdr.append (String.format (" | %-24s", pn));
        hdr.append (" | %-12s");
        System.out.println (String.format (hdr.toString (), "TOTAL_MEAN"));

        StringBuilder sep = new StringBuilder ();
        sep.append ("--------------------");
        for (int i = 0; i < probNames.length; i++)
            sep.append ("-|-------------------------");
        sep.append ("-|--------------");
        System.out.println (sep);

        // Rows
        for (int s = 0; s < strategyNames.length; s++)
        {
            StringBuilder row = new StringBuilder ();
            row.append (String.format ("%-20s", strategyNames [s]));
            double totalMean = 0;
            int totalCount = 0;

            for (int p = 0; p < probNames.length; p++)
            {
                ArrayList<Double> vals = scores [s][p];
                if (vals.isEmpty ())
                {
                    row.append (String.format (" | %-24s", "---"));
                }
                else
                {
                    double mean = 0, best = Double.MAX_VALUE, worst = Double.MIN_VALUE;
                    for (double v : vals)
                    {
                        mean += v;
                        best = Math.min (best, v);
                        worst = Math.max (worst, v);
                    }
                    mean /= vals.size ();
                    totalMean += mean;
                    totalCount++;
                    String cell = String.format ("%.1f (%.1f/%.1f)", mean, best, worst);
                    row.append (String.format (" | %-24s", cell));
                }
            }

            if (totalCount > 0)
                row.append (String.format (" | %-12.1f", totalMean / totalCount));
            else
                row.append (String.format (" | %-12s", "---"));

            System.out.println (row);
        }
        System.out.println ();
    }

    private static int indexOf (String [] arr, String val)
    {
        for (int i = 0; i < arr.length; i++)
            if (arr [i].equals (val)) return i;
        return -1;
    }
}
