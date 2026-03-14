package bench;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benchmark comparatif "Extended Bounds" sur tous les algorithmes.
 * Teste margin={3,5,8} sur tous les problèmes, en mode incremental.
 *
 * Usage :
 *   java -cp "lib/*:bin" bench.BenchmarkExtendedBounds [--seconds N] [--runs N] [--reset]
 *   java -cp "lib/*:bin" bench.BenchmarkExtendedBounds --seconds 5 --runs 1   # test rapide
 */
public class BenchmarkExtendedBounds
{
    private static int NB_SECONDS = 60;
    private static int NB_RUNS    = 3;
    private static String CSV_PATH = "results/bench_ExtendedBounds.csv";

    private static final String [] CONFIGS = {"baseline", "margin3", "margin5", "margin8"};
    private static final double [] MARGINS = {0.0, 3.0, 5.0, 8.0};

    private static final String [] ALL_ALGOS =
            {"CMAES", "DE", "GA", "MultiSegment", "BIPOP", "SHADE", "L-SHADE", "BipopAdaptif",
             "LMCMA", "AStarSeeds", "NBIPOP", "ALConstraint", "RFSurrogate", "Islands",
             "GridBIPOP", "StochRank", "AStarRepair", "SepWarmup", "OptiPathFinal"};

    public static void main (String [] args) throws Exception
    {
        boolean reset = false;
        boolean headless = true;
        String onlyAlgo = null;
        String onlyConfig = null;

        for (int i = 0; i < args.length; i++)
        {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--runs") && i + 1 < args.length)
                NB_RUNS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--reset"))
                reset = true;
            else if (args [i].equals ("--headless"))
                headless = true;
            else if (args [i].equals ("--only") && i + 1 < args.length)
                onlyAlgo = args [++i];
            else if (args [i].equals ("--config") && i + 1 < args.length)
                onlyConfig = args [++i];
        }

        if (headless)
        {
            System.err.println ("Mode headless : pas de fenetres.");
            Problem.headless = true;
        }

        new File ("results").mkdirs ();
        File csvFile = new File (CSV_PATH);

        // --- Charger les resultats existants ---
        // Cle = "Config,Algorithm,Problem,Run"  Valeur = score
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
                    if (parts.length >= 5)
                    {
                        String key = parts [0].trim () + "," + parts [1].trim () + "," +
                                parts [2].trim () + "," + parts [3].trim ();
                        try
                        {
                            existing.put (key, Double.parseDouble (parts [4].trim ()));
                        }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            System.err.println ("Charge " + existing.size () + " resultats depuis " + CSV_PATH);
        }
        else if (reset && csvFile.exists ())
        {
            csvFile.delete ();
            System.err.println ("Fichier " + CSV_PATH + " supprime (--reset).");
        }

        // --- Charger bench_results.csv pour la baseline ---
        // Cle = "Algorithm,Problem,Run"  Valeur = score
        LinkedHashMap<String, Double> mainResults = new LinkedHashMap<> ();
        File mainCsv = new File ("results/bench_results.csv");
        if (!reset && mainCsv.exists ())
        {
            try (BufferedReader br = new BufferedReader (new FileReader (mainCsv)))
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
                        try
                        {
                            mainResults.put (key, Double.parseDouble (parts [3].trim ()));
                        }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            System.err.println ("Charge " + mainResults.size () + " resultats baseline depuis bench_results.csv");
        }

        if (!csvFile.exists ())
        {
            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile)))
            {
                pw.println ("Config,Algorithm,Problem,Run,Score");
            }
        }

        ArrayList<Problem> problems = Problem.getProblems ();
        String [] probNames = new String [problems.size ()];
        for (int i = 0; i < probNames.length; i++)
            probNames [i] = problems.get (i).getName ();

        // scores[config][algo][problem] = liste de scores par run
        @SuppressWarnings ("unchecked")
        ArrayList<Double> [][][] scores = new ArrayList [CONFIGS.length][ALL_ALGOS.length][problems.size ()];
        for (int c = 0; c < CONFIGS.length; c++)
            for (int a = 0; a < ALL_ALGOS.length; a++)
                for (int p = 0; p < problems.size (); p++)
                    scores [c][a][p] = new ArrayList<> ();

        // Remplir avec les resultats existants (bench_ExtendedBounds.csv)
        for (Map.Entry<String, Double> entry : existing.entrySet ())
        {
            String [] parts = entry.getKey ().split (",");
            int c = indexOf (CONFIGS, parts [0]);
            int a = indexOf (ALL_ALGOS, parts [1]);
            int p = indexOf (probNames, parts [2]);
            if (c >= 0 && a >= 0 && p >= 0)
                scores [c][a][p].add (entry.getValue ());
        }

        // Alimenter la baseline depuis bench_results.csv si pas deja presents
        if (!mainResults.isEmpty ())
        {
            int baselineIdx = indexOf (CONFIGS, "baseline");
            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile, true)))
            {
                for (int a = 0; a < ALL_ALGOS.length; a++)
                {
                    for (int p = 0; p < problems.size (); p++)
                    {
                        for (int run = 1; run <= NB_RUNS; run++)
                        {
                            if (scores [baselineIdx][a][p].size () >= run) continue;
                            String key = ALL_ALGOS [a] + "," + probNames [p] + "," + run;
                            Double score = mainResults.get (key);
                            if (score != null)
                            {
                                scores [baselineIdx][a][p].add (score);
                                pw.println ("baseline," + ALL_ALGOS [a] + "," + probNames [p]
                                        + "," + run + "," + score);
                            }
                        }
                    }
                }
            }
        }

        // Compter combien restent a faire
        int totalMissing = 0;
        for (int c = 0; c < CONFIGS.length; c++)
            for (int a = 0; a < ALL_ALGOS.length; a++)
                for (int p = 0; p < problems.size (); p++)
                    totalMissing += Math.max (0, NB_RUNS - scores [c][a][p].size ());

        System.err.println ("=== Benchmark Extended Bounds : " + NB_RUNS + " runs x " + NB_SECONDS + "s ===");
        if (totalMissing == 0)
            System.err.println ("Tout est deja complet. Rien a calculer.");
        else
            System.err.println (totalMissing + " run(s) restant(s) a calculer.");
        System.err.println ();

        // --- Executer les combinaisons manquantes ---
        for (int c = 0; c < CONFIGS.length; c++)
        {
            if (onlyConfig != null && !CONFIGS [c].equals (onlyConfig)) continue;

            for (int a = 0; a < ALL_ALGOS.length; a++)
            {
                if (onlyAlgo != null && !ALL_ALGOS [a].equals (onlyAlgo)) continue;

                for (int p = 0; p < problems.size (); p++)
                {
                    Problem problem = problems.get (p);
                    int alreadyDone = scores [c][a][p].size ();

                    for (int run = alreadyDone + 1; run <= NB_RUNS; run++)
                    {
                        System.err.print (CONFIGS [c] + " / " + ALL_ALGOS [a] + " / " + problem.getName ()
                                + " / run " + run + " ... ");

                        problem.reset ();

                        try
                        {
                            CompetitorProject project = createProject (ALL_ALGOS [a], problem, MARGINS [c]);

                            project.initialization ();
                            long deadline = System.currentTimeMillis () + NB_SECONDS * 1000L;
                            while (System.currentTimeMillis () < deadline)
                                project.loop ();

                            double score = problem.getBestEvaluation ();
                            scores [c][a][p].add (score);
                            System.err.println ("score = " + String.format ("%.2f", score));

                            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile, true)))
                            {
                                pw.println (CONFIGS [c] + "," + ALL_ALGOS [a] + "," + problem.getName ()
                                        + "," + run + "," + score);
                            }
                        }
                        catch (Exception ex)
                        {
                            System.err.println ("ERREUR : " + ex.getMessage ());
                        }
                    }
                }
            }
        }

        // --- Tableaux recapitulatif (par config) ---
        for (int c = 0; c < CONFIGS.length; c++)
        {
            if (onlyConfig != null && !CONFIGS [c].equals (onlyConfig)) continue;

            System.out.println ();
            System.out.println ("=== RESULTATS " + CONFIGS [c] + " ===");
            StringBuilder hdr = new StringBuilder ();
            hdr.append (String.format ("%-10s", "Instance"));
            for (String algo : ALL_ALGOS)
                hdr.append (String.format (" | %-12s", algo));
            System.out.println (hdr);
            StringBuilder sep = new StringBuilder ();
            sep.append ("----------");
            for (int i = 0; i < ALL_ALGOS.length; i++)
                sep.append (" | ------------");
            System.out.println (sep);

            for (int p = 0; p < problems.size (); p++)
            {
                StringBuilder row = new StringBuilder ();
                row.append (String.format ("%-10s", probNames [p]));
                for (int a = 0; a < ALL_ALGOS.length; a++)
                {
                    ArrayList<Double> s = scores [c][a][p];
                    if (s.isEmpty ())
                    {
                        row.append (String.format (" | %12s", "---"));
                    }
                    else
                    {
                        double mean = 0;
                        for (double val : s) mean += val;
                        mean /= s.size ();
                        String cell = String.format ("%10.2f", mean);
                        cell += s.size () < NB_RUNS ? "?" : " ";
                        row.append (" | " + cell);
                    }
                }
                System.out.println (row);
            }
        }

        System.out.println ();
        System.err.println ("=== Benchmark termine. Resultats dans " + CSV_PATH + " ===");
    }

    private static int indexOf (String [] arr, String val)
    {
        for (int i = 0; i < arr.length; i++)
            if (arr [i].equals (val)) return i;
        return -1;
    }

    private static CompetitorProject createProject (String algo, Problem problem, double margin) throws Exception
    {
        switch (algo)
        {
            case "CMAES":        return ProjectCatalog.ipop           (problem, margin);
            case "DE":           return ProjectCatalog.de             (problem, margin);
            case "GA":           return ProjectCatalog.ga             (problem, margin);
            case "MultiSegment": return ProjectCatalog.ipop           (problem, margin); // meme encodage
            case "BIPOP":        return ProjectCatalog.bipop          (problem, margin);
            case "SHADE":        return ProjectCatalog.shade          (problem, margin);
            case "L-SHADE":      return ProjectCatalog.lshade         (problem, margin);
            case "BipopAdaptif": return ProjectCatalog.adaptiveBipop  (problem, margin);
            case "LMCMA":        return ProjectCatalog.lmcma          (problem, margin);
            case "AStarSeeds":   return ProjectCatalog.astarSeeds     (problem, margin);
            case "NBIPOP":       return ProjectCatalog.nbipop         (problem, margin);
            case "ALConstraint": return ProjectCatalog.alConstraint   (problem, margin);
            case "RFSurrogate":  return ProjectCatalog.rfSurrogate    (problem, margin);
            case "Islands":      return ProjectCatalog.islands        (problem, margin);
            case "GridBIPOP":    return ProjectCatalog.gridBipop      (problem, margin);
            case "StochRank":    return ProjectCatalog.stochRank      (problem, 0.45, margin);
            case "AStarRepair":  return ProjectCatalog.surrogate      (problem, margin);
            case "SepWarmup":    return ProjectCatalog.sepWarmup      (problem, margin);
            case "OptiPathFinal":return ProjectCatalog.full           (problem, margin);
            default:              return ProjectCatalog.ipop           (problem, margin);
        }
    }
}
