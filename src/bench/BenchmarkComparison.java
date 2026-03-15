package bench;

import bezier.evaluation.BezierChart;
import bezier.evaluation.MonitorChart;
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
 * Benchmark unifie et incremental.
 *
 * Tous les resultats (algos de base + variantes) vont dans un seul fichier :
 *   results/bench_results.csv   (format : Algorithm,Problem,Run,Score)
 *
 * Au lancement il charge le CSV existant, saute les combinaisons deja faites,
 * et ne lance que les manquantes. Chaque resultat est ecrit immediatement
 * (flush apres chaque run).
 *
 * Usage :
 *   java -cp "lib/*:bin" bench.BenchmarkComparison [--seconds N] [--runs N] [--reset]
 *   java -cp "lib/*:bin" bench.BenchmarkComparison --seconds 5 --runs 1   # test rapide
 */
public class BenchmarkComparison
{
    private static int NB_SECONDS = 60;
    private static int NB_RUNS    = 3;
    private static String CSV_PATH = "results/bench_results.csv";

    // Tous les algorithmes : base + variantes + OptiPathFinal
    private static final String [] ALL_ALGOS =
            {"CMAES", "DE", "GA", "MultiSegment", "BIPOP", "SHADE", "BipopAdaptif",
             "LMCMA", "AStarSeeds", "NBIPOP", "ALConstraint", "RFSurrogate", "Islands",
             "GridBIPOP", "StochRank", "AStarRepair", "SepWarmup", "OptiPathFinal", "PSO", "FPSO"};

    public static void main (String [] args) throws Exception
    {
        boolean reset = false;
        boolean headless = false;
        String onlyAlgo = null;

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
        }

        if (headless)
        {
            System.err.println ("Mode headless : pas de fenetres.");
            Problem.headless = true;
        }

        if (onlyAlgo != null)
            CSV_PATH = "results/bench_" + onlyAlgo + ".csv";

        new File ("results").mkdirs ();
        File csvFile = new File (CSV_PATH);

        // --- Charger les resultats existants ---
        // Cle = "Algorithm,Problem,Run"  Valeur = score
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
                        try
                        {
                            existing.put (key, Double.parseDouble (parts [3].trim ()));
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

        if (!csvFile.exists ())
        {
            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile)))
            {
                pw.println ("Algorithm,Problem,Run,Score");
            }
        }

        ArrayList<Problem> problems = Problem.getProblems ();
        String [] probNames = new String [problems.size ()];
        for (int i = 0; i < probNames.length; i++)
            probNames [i] = problems.get (i).getName ();

        // scores[algo][problem] = liste de scores par run
        @SuppressWarnings ("unchecked")
        ArrayList<Double> [][] scores = new ArrayList [ALL_ALGOS.length][problems.size ()];
        for (int a = 0; a < ALL_ALGOS.length; a++)
            for (int p = 0; p < problems.size (); p++)
                scores [a][p] = new ArrayList<> ();

        // Remplir avec les resultats existants
        for (Map.Entry<String, Double> entry : existing.entrySet ())
        {
            String [] parts = entry.getKey ().split (",");
            int a = indexOf (ALL_ALGOS, parts [0]);
            int p = indexOf (probNames, parts [1]);
            if (a >= 0 && p >= 0)
                scores [a][p].add (entry.getValue ());
        }

        // Compter combien restent a faire
        int totalMissing = 0;
        for (int a = 0; a < ALL_ALGOS.length; a++)
            for (int p = 0; p < problems.size (); p++)
                totalMissing += Math.max (0, NB_RUNS - scores [a][p].size ());

        System.err.println ("=== Benchmark : " + NB_RUNS + " runs x " + NB_SECONDS + "s ===");
        if (totalMissing == 0)
            System.err.println ("Tout est deja complet. Rien a calculer.");
        else
            System.err.println (totalMissing + " run(s) restant(s) a calculer.");
        System.err.println ();

        // --- Executer les combinaisons manquantes ---
        for (int a = 0; a < ALL_ALGOS.length; a++)
        {
            if (onlyAlgo != null && !ALL_ALGOS [a].equals (onlyAlgo)) continue;
            for (int p = 0; p < problems.size (); p++)
            {
                Problem problem = problems.get (p);
                int alreadyDone = scores [a][p].size ();

                for (int run = alreadyDone + 1; run <= NB_RUNS; run++)
                {
                    System.err.print (ALL_ALGOS [a] + " / " + problem.getName ()
                            + " / run " + run + " ... ");

                    problem.reset ();
                    if (!headless)
                    {
                        MonitorChart.getNewInstance (ALL_ALGOS [a] + " " + problem.getName () + " #" + run);
                        BezierChart.getNewInstance (problem);
                    }

                    try
                    {
                        CompetitorProject project = createProject (ALL_ALGOS [a], problem);

                        project.initialization ();
                        long deadline = System.currentTimeMillis () + NB_SECONDS * 1000L;
                        while (System.currentTimeMillis () < deadline)
                        {
                            project.loop ();
                        }

                        double score = problem.getBestEvaluation ();
                        scores [a][p].add (score);
                        System.err.println ("score = " + String.format ("%.2f", score));

                        try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile, true)))
                        {
                            pw.println (ALL_ALGOS [a] + "," + problem.getName ()
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

        // --- Tableau recapitulatif ---
        System.out.println ();
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
                ArrayList<Double> s = scores [a][p];
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

        System.out.println ();
        System.err.println ("=== Benchmark termine. Resultats dans " + CSV_PATH + " ===");
    }

    private static int indexOf (String [] arr, String val)
    {
        for (int i = 0; i < arr.length; i++)
            if (arr [i].equals (val)) return i;
        return -1;
    }

    private static CompetitorProject createProject (String algo, Problem problem) throws Exception
    {
        switch (algo)
        {
            case "CMAES":        return ProjectCatalog.ipop           (problem);
            case "DE":           return ProjectCatalog.de             (problem);
            case "GA":           return ProjectCatalog.ga             (problem);
            case "MultiSegment": return ProjectCatalog.ipop           (problem); // même encodage
            case "BIPOP":        return ProjectCatalog.bipop          (problem);
            case "SHADE":        return ProjectCatalog.shade          (problem);
            case "BipopAdaptif": return ProjectCatalog.adaptiveBipop  (problem);
            case "LMCMA":        return ProjectCatalog.lmcma          (problem);
            case "AStarSeeds":   return ProjectCatalog.astarSeeds     (problem);
            case "NBIPOP":       return ProjectCatalog.nbipop         (problem);
            case "ALConstraint": return ProjectCatalog.alConstraint   (problem);
            case "RFSurrogate":  return ProjectCatalog.rfSurrogate    (problem);
            case "Islands":      return ProjectCatalog.islands        (problem);
            case "GridBIPOP":    return ProjectCatalog.gridBipop      (problem);
            case "StochRank":    return ProjectCatalog.stochRank      (problem, 0.45);
            case "AStarRepair":  return ProjectCatalog.surrogate      (problem);
            case "SepWarmup":    return ProjectCatalog.sepWarmup      (problem);
            case "OptiPathFinal":return ProjectCatalog.full           (problem);
            case "PSO":          return ProjectCatalog.pso            (problem);
            case "FPSO":         return ProjectCatalog.fpso           (problem);
            default:             return ProjectCatalog.ipop           (problem);
        }
    }
}
