package bench;

import bezier.evaluation.Problem;
import engine.core.Optimizer;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Benchmark comparatif : CMAESOptimizer (baseline) vs CMAESOptimizerExtendedBounds.
 * Teste margin={3,5,8} sur tous les problèmes.
 *
 * Usage : java -cp "lib/*;bin" bench.BenchmarkExtendedBounds [--seconds N] [--runs N]
 */
public class BenchmarkExtendedBounds
{
    public static void main (String [] args) throws Exception
    {
        int seconds = 60;
        int runs    = 3;

        for (int i = 0; i < args.length; i++)
        {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                seconds = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--runs") && i + 1 < args.length)
                runs = Integer.parseInt (args [++i]);
        }

        Problem.headless = true;

        String [] configs = {"baseline", "margin3", "margin5", "margin8"};
        double [] margins = {0, 3.0, 5.0, 8.0};

        ArrayList<Problem> problems = Problem.getProblems ();
        String [] probNames = new String [problems.size ()];
        for (int i = 0; i < probNames.length; i++)
            probNames [i] = problems.get (i).getName ();

        // scores[config][problem][run]
        double [][][] allScores = new double [configs.length][problems.size ()][runs];

        String csvPath = "results/bench_ExtendedBounds.csv";
        try (PrintWriter csv = new PrintWriter (new FileWriter (csvPath)))
        {
            csv.println ("Config,Problem,Run,Score");

            for (int c = 0; c < configs.length; c++)
            {
                for (int p = 0; p < problems.size (); p++)
                {
                    for (int r = 0; r < runs; r++)
                    {
                        Problem problem = problems.get (p);
                        problem.reset ();

                        int nCP = problem.getNControlPoints ();
                        int d   = 2 * nCP;

                        double [] lb = new double [d];
                        double [] ub = new double [d];
                        for (int i = 0; i < d; i++)
                        {
                            if (i % 2 == 0)
                            {
                                lb [i] = problem.getMinX ();
                                ub [i] = problem.getMaxX ();
                            }
                            else
                            {
                                lb [i] = problem.getMinY ();
                                ub [i] = problem.getMaxY ();
                            }
                        }

                        double [] initMean = new double [d];
                        double sx = problem.getStartPoint ().getX ();
                        double sy = problem.getStartPoint ().getY ();
                        double ex = problem.getEndPoint ().getX ();
                        double ey = problem.getEndPoint ().getY ();
                        for (int i = 0; i < nCP; i++)
                        {
                            double t = (double) (i + 1) / (nCP + 1);
                            initMean [2 * i]     = sx + t * (ex - sx);
                            initMean [2 * i + 1] = sy + t * (ey - sy);
                        }

                        Optimizer optimizer;
                        if (c == 0) {
                            optimizer = engine.cmaes.CMAESBuilder.ipop(problem).build();
                        } else {
                            engine.core.AlgorithmParameters params = new engine.core.AlgorithmParameters();
                            params.setMargin(margins[c]);
                            optimizer = engine.cmaes.CMAESBuilder.ipop(problem).parameters(params).build();
                        }

                        System.err.print (configs [c] + " / " + probNames [p]
                                + " / run " + (r + 1) + " ... ");

                        optimizer.init ();
                        long deadline = System.currentTimeMillis () + seconds * 1000L;
                        while (System.currentTimeMillis () < deadline)
                            optimizer.step ();

                        double score = problem.getBestEvaluation ();
                        allScores [c][p][r] = score;

                        System.err.println ("score = " + String.format ("%.2f", score));
                        csv.println (configs [c] + "," + probNames [p] + "," + (r + 1) + "," + score);
                        csv.flush ();
                    }
                }
            }
        }

        // Tableau récapitulatif
        System.out.println ();
        System.out.println ("=== RESULTATS EXTENDED BOUNDS ===");
        System.out.println ();
        System.out.printf ("%-10s", "Problem");
        for (String cfg : configs)
            System.out.printf (" | %-16s", cfg);
        System.out.println ();
        System.out.printf ("%-10s", "----------");
        for (int c = 0; c < configs.length; c++)
            System.out.printf (" | %-16s", "----------------");
        System.out.println ();

        for (int p = 0; p < problems.size (); p++)
        {
            System.out.printf ("%-10s", probNames [p]);
            for (int c = 0; c < configs.length; c++)
            {
                double sum = 0;
                double min = Double.MAX_VALUE;
                for (int r = 0; r < runs; r++)
                {
                    sum += allScores [c][p][r];
                    if (allScores [c][p][r] < min) min = allScores [c][p][r];
                }
                double mean = sum / runs;
                System.out.printf (" | %7.2f (%7.2f)", mean, min);
            }
            System.out.println ();
        }

        System.out.println ();
        System.out.println ("Format: mean (best)");
        System.out.println ("CSV saved to: " + csvPath);
    }
}
