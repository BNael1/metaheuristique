package bezier.run;

import bezier.evaluation.BezierChart;
import bezier.evaluation.MonitorChart;
import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.competitor.optipath.OptiPath;
import bezier.projects.competitor.optipath.CMAESOptimizer;
import bezier.projects.competitor.optipath.Optimizer;
import bezier.projects.competitor.de.OptiPathDE;
import bezier.projects.competitor.ga.OptiPathGA;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Banc d'essai headless pour comparer CMA-ES / DE / GA sur les 4 instances.
 * Main.DISPLAY_CHART doit être false pour éviter l'affichage graphique.
 *
 * Usage :
 *   java -cp "lib/*:bin" bezier.run.BenchmarkRunner [algo1 algo2 ...] [--seconds N] [--runs N]
 *
 * Exemples :
 *   java -cp "lib/*:bin" bezier.run.BenchmarkRunner CMAES DE GA --seconds 60 --runs 10
 *   java -cp "lib/*:bin" bezier.run.BenchmarkRunner CMAES --seconds 10 --runs 3
 *
 * Sortie CSV sur stdout : Algorithm,Problem,Run,Score
 * Résumé (moyennes ± écart-type) écrit dans bench_summary.txt
 */
public class BenchmarkRunner
{
    private static int NB_SECONDS = 10;
    private static int NB_RUNS    = 3;

    public static void main (String [] args) throws Exception
    {
        // Parser les arguments
        ArrayList<String> algos = new ArrayList<> ();
        for (int i = 0; i < args.length; i++)
        {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--runs") && i + 1 < args.length)
                NB_RUNS = Integer.parseInt (args [++i]);
            else
                algos.add (args [i].toUpperCase ());
        }
        if (algos.isEmpty ())
        {
            algos.add ("CMAES");
            algos.add ("DE");
            algos.add ("GA");
        }

        ArrayList<Problem> problems = Problem.getProblems ();

        // Stocker tous les scores pour le résumé final
        // clé = "ALGO|prob" → liste de scores
        Map<String, ArrayList<Double>> allScores = new LinkedHashMap<> ();

        System.out.println ("Algorithm,Problem,Run,Score");
        System.err.println ("=== Benchmark: " + algos + " | "
                + NB_RUNS + " runs × " + NB_SECONDS + "s ===");

        for (String algo : algos)
        {
            for (Problem problem : problems)
            {
                String key = algo + "|" + problem.getName ();
                allScores.put (key, new ArrayList<> ());

                for (int run = 1; run <= NB_RUNS; run++)
                {
                    System.err.print (algo + " / " + problem.getName ()
                            + " / run " + run + " ... ");

                    problem.reset ();

                    // Initialiser les singletons charts (nécessaire même sans affichage
                    // car Problem.evaluate() les appelle en interne)
                    MonitorChart.getNewInstance (algo + " " + problem.getName () + " #" + run);
                    BezierChart.getNewInstance (problem);

                    try
                    {
                        CompetitorProject project;
                        switch (algo)
                        {
                            case "DE":    project = new OptiPathDE (problem); break;
                            case "GA":    project = new OptiPathGA (problem); break;
                            default:      project = new OptiPath   (problem); break;
                        }
                        ExecutorService exec = Executors.newSingleThreadExecutor ();
                        Future <?> future = exec.submit ((Runnable) project);

                        try
                        {
                            future.get (NB_SECONDS, TimeUnit.SECONDS);
                        }
                        catch (TimeoutException e)
                        {
                            exec.shutdownNow ();
                            future.cancel (true);
                        }

                        if (!exec.awaitTermination (30, TimeUnit.SECONDS))
                        {
                            System.err.println ("WARNING: thread non terminé !");
                        }

                        double score = problem.getBestEvaluation ();
                        System.out.println (algo + "," + problem.getName ()
                                + "," + run + "," + score);
                        System.err.println ("score = " + score);
                        allScores.get (key).add (score);

                        // Log best solution vector for prob4 (CMA-ES only)
                        if (problem.getName ().equals ("prob4")
                                && project instanceof OptiPath)
                        {
                            Optimizer opt = ((OptiPath) project).getOptimizer ();
                            if (opt instanceof CMAESOptimizer)
                            {
                                CMAESOptimizer cma = (CMAESOptimizer) opt;
                                double [] bx = cma.getBestX ();
                                if (bx != null)
                                {
                                    StringBuilder sb = new StringBuilder ();
                                    sb.append ("  prob4 bestX: [");
                                    for (int k = 0; k < bx.length; k += 2)
                                        sb.append (String.format ("(%.2f,%.2f)", bx [k], bx [k + 1]));
                                    sb.append ("]");
                                    System.err.println (sb.toString ());
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        System.err.println ("ERREUR : " + ex.getMessage ());
                        System.out.println (algo + "," + problem.getName ()
                                + "," + run + ",ERROR");
                    }
                }
            }
        }

        // ===== Résumé avec moyennes et écarts-types =====
        StringBuilder summary = new StringBuilder ();
        summary.append (String.format ("=== Résumé benchmark : %d runs × %ds ===%n%n", NB_RUNS, NB_SECONDS));
        summary.append (String.format ("%-8s %-8s %12s %12s %12s %12s%n",
                "Algo", "Problem", "Mean", "Std", "Min", "Max"));
        summary.append ("---------------------------------------------------------------\n");

        for (Map.Entry<String, ArrayList<Double>> entry : allScores.entrySet ())
        {
            String [] parts = entry.getKey ().split ("\\|");
            ArrayList<Double> scores = entry.getValue ();

            double mean = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double s : scores) { mean += s; min = Math.min (min, s); max = Math.max (max, s); }
            mean /= scores.size ();

            double variance = 0;
            for (double s : scores) variance += (s - mean) * (s - mean);
            double std = Math.sqrt (variance / scores.size ());

            summary.append (String.format ("%-8s %-8s %12.4f %12.4f %12.4f %12.4f%n",
                    parts [0], parts [1], mean, std, min, max));
        }

        // Écrire le résumé dans un fichier et sur stderr
        String summaryStr = summary.toString ();
        System.err.println ("\n" + summaryStr);

        try (PrintWriter pw = new PrintWriter (new File ("bench_summary.txt")))
        {
            pw.print (summaryStr);
        }

        System.err.println ("=== Benchmark terminé. Résumé dans bench_summary.txt ===");
        System.exit (0);
    }
}
