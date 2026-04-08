package bench;

import bezier.evaluation.Coordinates;
import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Benchmark de generalisation pour limiter le surapprentissage des problemes connus.
 *
 * - split fixe train/holdout sur les problemes existants
 * - generation de problemes synthetiques reproductibles dans un dossier temporaire
 * - reporting score, score normalise, faisabilite et variance inter-runs
 */
public class BenchmarkGeneralization
{
    private static int NB_SECONDS = 6;
    private static int NB_RUNS = 3;
    private static long SYNTH_SEED = 123456789L;
    private static String ALGO = "OptiPathFinal";
    private static final String OUT_CSV = "results/bench_generalization.csv";

    private static final Set<String> HOLDOUT = new HashSet<> (
            Arrays.asList ("prob4", "prob12", "prob16", "prob17"));

    public static void main (String [] args) throws Exception
    {
        parseArgs (args);
        Problem.headless = true;
        new File ("results").mkdirs ();

        ArrayList<Problem> all = Problem.getProblems ();
        all.sort ((a, b) -> a.getName ().compareTo (b.getName ()));

        ArrayList<Problem> train = new ArrayList<> ();
        ArrayList<Problem> holdout = new ArrayList<> ();
        for (Problem p : all)
        {
            if (HOLDOUT.contains (p.getName ())) holdout.add (p);
            else train.add (p);
        }

        Path synthDir = Files.createTempDirectory ("optipath-generalization-");
        ArrayList<Problem> synthetic = generateSyntheticProblems (synthDir, SYNTH_SEED);

        ArrayList<Row> rows = new ArrayList<> ();
        runGroup (rows, "train", train);
        runGroup (rows, "holdout", holdout);
        runGroup (rows, "synthetic", synthetic);

        writeCsv (rows, OUT_CSV);
        printSummary (rows);

        // Nettoyage du dossier temporaire synthétique
        deleteRecursively (synthDir);
        System.err.println ("Benchmark termine. Resultats: " + OUT_CSV);
    }

    private static void parseArgs (String [] args)
    {
        for (int i = 0; i < args.length; i++)
        {
            if ("--seconds".equals (args[i]) && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args[++i]);
            else if ("--runs".equals (args[i]) && i + 1 < args.length)
                NB_RUNS = Integer.parseInt (args[++i]);
            else if ("--seed".equals (args[i]) && i + 1 < args.length)
                SYNTH_SEED = Long.parseLong (args[++i]);
            else if ("--algo".equals (args[i]) && i + 1 < args.length)
                ALGO = args[++i];
        }
    }

    private static final class Row
    {
        String split;
        String problem;
        int run;
        double score;
        double normalized;
        int feasible;
    }

    private static void runGroup (ArrayList<Row> rows, String split, List<Problem> problems) throws Exception
    {
        for (Problem problem : problems)
        {
            for (int run = 1; run <= NB_RUNS; run++)
            {
                problem.reset ();
                CompetitorProject project = createProject (ALGO, problem);

                long deadline = System.currentTimeMillis () + NB_SECONDS * 1000L;
                project.initialization ();
                while (System.currentTimeMillis () < deadline)
                    project.loop ();

                double score = problem.getBestEvaluation ();
                double direct = Math.hypot (
                        problem.getEndPoint ().getX () - problem.getStartPoint ().getX (),
                        problem.getEndPoint ().getY () - problem.getStartPoint ().getY ());
                if (direct <= 1e-9) direct = 1.0;

                Row row = new Row ();
                row.split = split;
                row.problem = problem.getName ();
                row.run = run;
                row.score = score;
                row.normalized = score / direct;
                row.feasible = isBestTrajectoryFeasible (problem) ? 1 : 0;
                rows.add (row);

                System.err.printf (Locale.US,
                        "%s / %s / run %d -> score=%.3f norm=%.3f feasible=%d%n",
                        split, problem.getName (), run, row.score, row.normalized, row.feasible);
            }
        }
    }

    private static CompetitorProject createProject (String algo, Problem problem) throws Exception
    {
        switch (algo)
        {
            case "OptiPathFinal":
            case "OptiPath":
                return ProjectCatalog.hybrid (problem);
            case "BIPOP":
                return ProjectCatalog.bipop (problem);
            case "CMAES":
                return ProjectCatalog.ipop (problem);
            case "DE":
                return ProjectCatalog.de (problem);
            case "GA":
                return ProjectCatalog.ga (problem);
            default:
                throw new IllegalArgumentException ("Algo non supporte: " + algo);
        }
    }

    private static boolean isBestTrajectoryFeasible (Problem problem)
    {
        try
        {
            Field bestBezierField = Problem.class.getDeclaredField ("bestBezier");
            bestBezierField.setAccessible (true);
            Object bezier = bestBezierField.get (problem);
            if (bezier == null) return false;

            Method getTraj = bezier.getClass ().getDeclaredMethod ("getTrajectory");
            getTraj.setAccessible (true);
            Coordinates [] traj = (Coordinates[]) getTraj.invoke (bezier);
            if (traj == null || traj.length == 0) return false;

            for (Coordinates p : traj)
            {
                double x = p.getX (), y = p.getY ();
                if (x < problem.getMinX () || x > problem.getMaxX ()) return false;
                if (y < problem.getMinY () || y > problem.getMaxY ()) return false;
                for (int i = 0; i < problem.getNObstacles (); i++)
                {
                    bezier.evaluation.Obstacle o = problem.getObstacle (i);
                    double dx = x - o.getX ();
                    double dy = y - o.getY ();
                    if (dx * dx + dy * dy <= o.getRadius () * o.getRadius ())
                        return false;
                }
            }
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static void writeCsv (ArrayList<Row> rows, String path) throws Exception
    {
        try (PrintWriter pw = new PrintWriter (new FileWriter (path)))
        {
            pw.println ("Split,Problem,Run,Score,NormalizedScore,Feasible");
            for (Row r : rows)
            {
                pw.printf (Locale.US, "%s,%s,%d,%.12f,%.12f,%d%n",
                        r.split, r.problem, r.run, r.score, r.normalized, r.feasible);
            }
        }
    }

    private static void printSummary (ArrayList<Row> rows)
    {
        LinkedHashMap<String, ArrayList<Row>> bySplit = new LinkedHashMap<> ();
        bySplit.put ("train", new ArrayList<> ());
        bySplit.put ("holdout", new ArrayList<> ());
        bySplit.put ("synthetic", new ArrayList<> ());
        for (Row r : rows)
            bySplit.computeIfAbsent (r.split, k -> new ArrayList<> ()).add (r);

        System.out.println ("\n=== Generalization Summary ===");
        for (String split : bySplit.keySet ())
        {
            ArrayList<Row> rs = bySplit.get (split);
            if (rs == null || rs.isEmpty ()) continue;

            ArrayList<Double> norms = new ArrayList<> ();
            double feasible = 0.0;
            for (Row r : rs)
            {
                norms.add (r.normalized);
                feasible += r.feasible;
            }
            Collections.sort (norms);

            double mean = 0.0;
            for (double v : norms) mean += v;
            mean /= norms.size ();

            double median = norms.get (norms.size () / 2);
            double var = 0.0;
            for (double v : norms) var += (v - mean) * (v - mean);
            var /= norms.size ();
            double std = Math.sqrt (var);

            double feasibleRate = feasible / rs.size ();
            System.out.printf (Locale.US,
                    "%s -> n=%d | norm_mean=%.3f | norm_median=%.3f | norm_std=%.3f | feasible_rate=%.2f%%%n",
                    split, rs.size (), mean, median, std, 100.0 * feasibleRate);
        }
    }

    private static ArrayList<Problem> generateSyntheticProblems (Path dir, long seed) throws Exception
    {
        Random rng = new Random (seed);
        ArrayList<Problem> res = new ArrayList<> ();

        writeProblem (dir.resolve ("synth_comb_1.bzr"),
                2, 25, 48, 25, 0, 0, 50, 50, 20,
                buildComb (rng, 0.8, 1.5, 5));
        writeProblem (dir.resolve ("synth_spiral_1.bzr"),
                2, 25, 25, 25, 0, 0, 50, 50, 20,
                buildSpiral (rng, 0.8, 1.5));
        writeProblem (dir.resolve ("synth_corridor_1.bzr"),
                2, 24, 48, 24, 0, 0, 50, 50, 20,
                buildCorridor (rng, 0.75, 1.5));
        writeProblem (dir.resolve ("synth_diag_1.bzr"),
                2, 6, 46, 46, 0, 0, 50, 50, 20,
                buildDiagonalBarrier (rng, 0.8, 1.5));
        writeProblem (dir.resolve ("synth_slalom_1.bzr"),
                2, 25, 48, 25, 0, 0, 50, 50, 20,
                buildSlalom (rng, 0.8, 1.5));
        writeProblem (dir.resolve ("synth_rooms_1.bzr"),
                3, 3, 47, 47, 0, 0, 50, 50, 20,
                buildRooms (rng, 0.8, 1.5));

        try (Stream<Path> stream = Files.list (dir))
        {
            stream.filter (p -> p.getFileName ().toString ().endsWith (".bzr"))
                    .sorted ()
                    .forEach (p -> {
                        try
                        {
                            res.add (loadProblemFromPath (p));
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException (e);
                        }
                    });
        }

        return res;
    }

    private static Problem loadProblemFromPath (Path path) throws Exception
    {
        Constructor<Problem> c = Problem.class.getDeclaredConstructor (String.class);
        c.setAccessible (true);
        return c.newInstance (path.toAbsolutePath ().toString ());
    }

    private static ArrayList<double []> buildComb (Random rng, double r, double step, int teeth)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        addLineH (obs, 0, 50, 44, step, r);
        addLineH (obs, 0, 50, 6, step, r);
        for (int i = 0; i < teeth; i++)
        {
            double x = 8 + i * (34.0 / Math.max (1, teeth - 1));
            if (i % 2 == 0) addLineV (obs, x, 44, 13, step, r);
            else addLineV (obs, x, 6, 37, step, r);
        }
        dedup (obs);
        return obs;
    }

    private static ArrayList<double []> buildSpiral (Random rng, double r, double step)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        addRectangle (obs, 5, 5, 45, 44, step, r, "left", 20, 30);
        addRectangle (obs, 12, 12, 38, 37, step, r, "right", 19.5, 30.0);
        addRectangle (obs, 19, 20, 31, 30, step, r, "left", 22.3, 27.7);
        dedup (obs);
        return obs;
    }

    private static ArrayList<double []> buildCorridor (Random rng, double r, double step)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        addLineH (obs, 0, 50, 47, step, r);
        addLineH (obs, 0, 50, 3, step, r);
        addLineV (obs, 10, 47, 10, step, r);
        addLineV (obs, 20, 40, 3, step, r);
        addLineV (obs, 30, 47, 10, step, r);
        addLineV (obs, 40, 40, 3, step, r);
        dedup (obs);
        return obs;
    }

    private static ArrayList<double []> buildDiagonalBarrier (Random rng, double r, double step)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        int n = 28;
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / Math.max (1, n - 1);
            if (t > 0.42 && t < 0.58) continue; // gap central
            double x = 6 + t * 38;
            double y = 10 + t * 34;
            obs.add (new double [] {x, y, r});
        }
        addLineH (obs, 0, 50, 49, step, r);
        addLineV (obs, 49, 0, 50, step, r);
        dedup (obs);
        return obs;
    }

    private static ArrayList<double []> buildSlalom (Random rng, double r, double step)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        for (int i = 0; i < 6; i++)
        {
            double x = 8 + i * 7;
            if (i % 2 == 0) addLineV (obs, x, 46, 24, step, r);
            else addLineV (obs, x, 26, 4, step, r);
        }
        addLineH (obs, 0, 50, 49, step, r);
        addLineH (obs, 0, 50, 1, step, r);
        dedup (obs);
        return obs;
    }

    private static ArrayList<double []> buildRooms (Random rng, double r, double step)
    {
        ArrayList<double []> obs = new ArrayList<> ();
        addRectangle (obs, 4, 4, 46, 46, step, r, "right", 20, 30);
        addRectangle (obs, 14, 14, 36, 36, step, r, "left", 17, 27);
        addLineV (obs, 25, 46, 30, step, r);
        addLineV (obs, 25, 20, 4, step, r);
        dedup (obs);
        return obs;
    }

    private static void writeProblem (Path file,
                                      double sx, double sy,
                                      double ex, double ey,
                                      double minX, double minY,
                                      double maxX, double maxY,
                                      int nCP,
                                      ArrayList<double []> obstacles) throws Exception
    {
        try (PrintWriter pw = new PrintWriter (new FileWriter (file.toFile ())))
        {
            pw.printf (Locale.US, "%.6f,%.6f%n", sx, sy);
            pw.printf (Locale.US, "%.6f,%.6f%n", ex, ey);
            pw.printf (Locale.US, "%.6f,%.6f,%.6f,%.6f%n", minX, minY, maxX, maxY);
            pw.println (nCP);
            for (double [] o : obstacles)
                pw.printf (Locale.US, "%.6f,%.6f,%.6f%n", o[0], o[1], o[2]);
        }
    }

    private static void addRectangle (ArrayList<double []> obs,
                                      double x1, double y1,
                                      double x2, double y2,
                                      double step, double r,
                                      String openingSide,
                                      double openingA, double openingB)
    {
        addLineH (obs, x1, x2, y1, step, r);
        addLineH (obs, x1, x2, y2, step, r);
        if (!"left".equals (openingSide)) addLineV (obs, x1, y1, y2, step, r);
        else addLineVGap (obs, x1, y1, y2, openingA, openingB, step, r);

        if (!"right".equals (openingSide)) addLineV (obs, x2, y1, y2, step, r);
        else addLineVGap (obs, x2, y1, y2, openingA, openingB, step, r);
    }

    private static void addLineH (ArrayList<double []> obs,
                                  double x1, double x2, double y,
                                  double step, double r)
    {
        double lo = Math.min (x1, x2), hi = Math.max (x1, x2);
        for (double x = lo; x <= hi + 1e-9; x += step)
            obs.add (new double [] {x, y, r});
        if (Math.abs (obs.get (obs.size () - 1)[0] - hi) > 1e-6)
            obs.add (new double [] {hi, y, r});
    }

    private static void addLineV (ArrayList<double []> obs,
                                  double x, double y1, double y2,
                                  double step, double r)
    {
        double lo = Math.min (y1, y2), hi = Math.max (y1, y2);
        for (double y = lo; y <= hi + 1e-9; y += step)
            obs.add (new double [] {x, y, r});
        if (Math.abs (obs.get (obs.size () - 1)[1] - hi) > 1e-6)
            obs.add (new double [] {x, hi, r});
    }

    private static void addLineVGap (ArrayList<double []> obs,
                                     double x, double y1, double y2,
                                     double g1, double g2,
                                     double step, double r)
    {
        double lo = Math.min (y1, y2), hi = Math.max (y1, y2);
        for (double y = lo; y <= hi + 1e-9; y += step)
            if (y < g1 || y > g2)
                obs.add (new double [] {x, y, r});
        if (hi < g1 || hi > g2)
            obs.add (new double [] {x, hi, r});
    }

    private static void dedup (ArrayList<double []> obs)
    {
        HashSet<String> seen = new HashSet<> ();
        ArrayList<double []> out = new ArrayList<> ();
        for (double [] o : obs)
        {
            String key = String.format (Locale.US, "%.3f_%.3f", o[0], o[1]);
            if (seen.add (key)) out.add (o);
        }
        obs.clear ();
        obs.addAll (out);
    }

    private static void deleteRecursively (Path root) throws Exception
    {
        if (root == null || !Files.exists (root)) return;
        Files.walk (root)
                .sorted ((a, b) -> b.getNameCount () - a.getNameCount ())
                .forEach (p -> {
                    try { Files.deleteIfExists (p); }
                    catch (Exception ignored) {}
                });
    }
}
