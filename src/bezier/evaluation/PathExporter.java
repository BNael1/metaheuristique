package bezier.evaluation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYShapeAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import bezier.projects.competitor.optipath.CMAESOptimizer;
import bezier.projects.competitor.optipath.Optimizer;
import bezier.projects.competitor.de.DEOptimizer;
import bezier.projects.competitor.ga.GAOptimizer;

/**
 * Génère des images PNG des meilleures trajectoires trouvées par chaque
 * méthode (CMA-ES, DE, GA) sur chaque instance de problème (prob1 à prob4).
 *
 * Utilisation :
 *   java -cp "lib/*:bin" bezier.evaluation.PathExporter [--seconds N] [--algos CMAES,DE,GA]
 *
 * Les images sont sauvegardées dans le dossier rapport/figures/.
 */
public class PathExporter
{
    private static final Pattern BKS_LINE_FR = Pattern.compile (
            "^([^,]+),([^,]+),([+-]?[0-9]+),([0-9]+),\\\"(.*)\\\",([0-9]+)$");
    private static final Pattern BKS_LINE_EN = Pattern.compile (
            "^([^,]+),([^,]+),([+-]?[0-9]+(?:\\.[0-9]+)?),\\\"(.*)\\\",([0-9]+)$");

    private static class BksEntry
    {
        final String algo;
        final String problem;
        final double score;
        final double [] bestX;

        BksEntry (String algo, String problem, double score, double [] bestX)
        {
            this.algo = algo;
            this.problem = problem;
            this.score = score;
            this.bestX = bestX;
        }
    }

    /** Taille de l'image PNG (carrée) en pixels. */
    private static final int IMG_SIZE = 800;

    /** Budget par défaut (secondes) pour l'optimisation de chaque instance. */
    private static int NB_SECONDS = 60;

    /** Noms affichés pour chaque algorithme. */
    private static String algoDisplayName (String algo)
    {
        switch (algo)
        {
            case "CMAES": return "CMA-ES";
            case "DE":    return "DE";
            case "GA":    return "GA";
            default:      return algo;
        }
    }

    /** Couleur de la trajectoire selon l'algorithme. */
    private static Color algoColor (String algo)
    {
        switch (algo)
        {
            case "CMAES": return new Color (30, 80, 220);   // bleu
            case "DE":    return new Color (220, 120, 0);    // orange
            case "GA":    return new Color (0, 150, 80);     // vert
            default:      return Color.DARK_GRAY;
        }
    }

    // =====================================================================
    //  main
    // =====================================================================
    public static void main (String [] args) throws Exception
    {
        ArrayList<String> algos = new ArrayList<> (Arrays.asList ("CMAES", "DE", "GA"));
        String bksCsvPath = null;

        for (int i = 0; i < args.length; i++)
        {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args [++i]);
            else if (args [i].equals ("--bks-csv") && i + 1 < args.length)
                bksCsvPath = args [++i];
            else if (args [i].equals ("--algos") && i + 1 < args.length)
            {
                algos.clear ();
                for (String a : args [++i].split (","))
                    algos.add (a.trim ().toUpperCase ());
            }
        }

        if (bksCsvPath != null)
        {
            exportFromBksCsv (algos, bksCsvPath);
            System.exit (0);
        }

        // Créer les dossiers de sortie
        File outDir = new File ("rapport/figures");
        outDir.mkdirs ();
        new File ("rapport/figures/60s").mkdirs ();

        ArrayList<Problem> problems = Problem.getProblems ();

        for (String algo : algos)
        {
            for (Problem problem : problems)
            {
                System.out.println ("=== " + algoDisplayName (algo)
                        + " / " + problem.getName () + " ===");

                problem.reset ();
                MonitorChart.getNewInstance (problem.getName ());
                BezierChart.getNewInstance (problem);

                // --- Créer l'optimiseur selon l'algo -------------------------
                int nCP = problem.getNControlPoints ();
                int d   = 2 * nCP;
                double [] lb = new double [d], ub = new double [d];
                for (int j = 0; j < d; j++)
                {
                    lb [j] = (j % 2 == 0) ? problem.getMinX () : problem.getMinY ();
                    ub [j] = (j % 2 == 0) ? problem.getMaxX () : problem.getMaxY ();
                }
                double [] initMean = new double [d];
                double sx = problem.getStartPoint ().getX ();
                double sy = problem.getStartPoint ().getY ();
                double ex = problem.getEndPoint ().getX ();
                double ey = problem.getEndPoint ().getY ();
                for (int k = 0; k < nCP; k++)
                {
                    double t = (double) (k + 1) / (nCP + 1);
                    initMean [2 * k]     = sx + t * (ex - sx);
                    initMean [2 * k + 1] = sy + t * (ey - sy);
                }

                Optimizer optimizer;
                switch (algo)
                {
                    case "DE":
                        optimizer = new DEOptimizer (problem, d, lb, ub, initMean);
                        break;
                    case "GA":
                        optimizer = new GAOptimizer (problem, d, lb, ub, initMean);
                        break;
                    default: // CMAES
                        optimizer = new CMAESOptimizer (problem, d, lb, ub, initMean);
                        break;
                }
                optimizer.init ();

                // --- Exécuter dans un thread avec timeout --------------------
                Runnable runner = () ->
                {
                    while (!Thread.currentThread ().isInterrupted ())
                        optimizer.step ();
                };
                ExecutorService exec = Executors.newSingleThreadExecutor ();
                Future <?> future = exec.submit (runner);
                try
                {
                    future.get (NB_SECONDS, TimeUnit.SECONDS);
                }
                catch (TimeoutException e)
                {
                    exec.shutdownNow ();
                    future.cancel (true);
                }
                exec.awaitTermination (30, TimeUnit.SECONDS);

                double score = problem.getBestEvaluation ();
                System.out.printf ("  Score : %.4f%n", score);

                double [] bestX = optimizer.getBestX ();
                if (bestX == null)
                {
                    System.out.println ("  Aucune solution, passage au suivant.");
                    continue;
                }

                // --- Construire et sauvegarder le PNG ------------------------
                Bezier bezier = new Bezier (bestX, problem.getStartPoint (),
                                            problem.getEndPoint ());
                Coordinates [] trajectory = bezier.getTrajectory ();

                JFreeChart chart = buildChart (problem, trajectory, bestX, score,
                                              algoDisplayName (algo), algoColor (algo));
                String filename = "rapport/figures/60s/path_" + problem.getName ()
                                  + "_" + algo.toLowerCase () + ".png";
                ChartUtils.saveChartAsPNG (new File (filename), chart, IMG_SIZE, IMG_SIZE);
                System.out.println ("  -> " + filename);
            }
        }

        System.out.println ("\nTerminé ! Images 60s dans rapport/figures/60s/");
        System.exit (0);
    }

    // =====================================================================
    //  Export direct depuis results/bks_all.csv
    // =====================================================================
    private static void exportFromBksCsv (ArrayList<String> algos, String bksCsvPath) throws Exception
    {
        File outDir = new File ("rapport/figures/bks");
        outDir.mkdirs ();

        ArrayList<Problem> problems = Problem.getProblems ();
        Map<String, Problem> byName = new LinkedHashMap<> ();
        for (Problem p : problems)
            byName.put (p.getName (), p);

        ArrayList<BksEntry> entries = readBksEntries (bksCsvPath);
        if (entries.isEmpty ())
        {
            System.out.println ("Aucune entrée BKS trouvée dans " + bksCsvPath);
            return;
        }

        for (BksEntry entry : entries)
        {
            if (!algos.contains (entry.algo))
                continue;

            Problem problem = byName.get (entry.problem);
            if (problem == null)
            {
                System.out.println ("[WARN] Problème inconnu: " + entry.problem + " (skip)");
                continue;
            }

            int expectedD = 2 * problem.getNControlPoints ();
            if (entry.bestX == null || entry.bestX.length != expectedD)
            {
                System.out.println ("[WARN] BestX invalide pour " + entry.algo + "/" + entry.problem
                        + " (d=" + (entry.bestX == null ? 0 : entry.bestX.length)
                        + ", attendu=" + expectedD + ")");
                continue;
            }

            problem.reset ();
            MonitorChart.getNewInstance (entry.problem + "_" + entry.algo + "_bks");
            BezierChart.getNewInstance (problem);

            Bezier bezier = new Bezier (entry.bestX, problem.getStartPoint (),
                                        problem.getEndPoint ());
            Coordinates [] trajectory = bezier.getTrajectory ();

            JFreeChart chart = buildChart (problem, trajectory, entry.bestX, entry.score,
                    algoDisplayName (entry.algo) + " BKS", algoColor (entry.algo));

            String filename = "rapport/figures/bks/"
                    + entry.algo.toLowerCase () + "_" + entry.problem + ".png";
            ChartUtils.saveChartAsPNG (new File (filename), chart, IMG_SIZE, IMG_SIZE);
            System.out.println ("-> " + filename + "  (score=" + String.format ("%.4f", entry.score) + ")");
        }

        System.out.println ("\nTerminé ! Images BKS dans rapport/figures/bks/");
    }

    private static ArrayList<BksEntry> readBksEntries (String csvPath) throws Exception
    {
        ArrayList<BksEntry> entries = new ArrayList<> ();

        try (BufferedReader br = new BufferedReader (new FileReader (csvPath)))
        {
            String line = br.readLine (); // header
            if (line == null)
                return entries;

            while ((line = br.readLine ()) != null)
            {
                line = line.trim ();
                if (line.isEmpty ())
                    continue;

                Matcher mFr = BKS_LINE_FR.matcher (line);
                if (mFr.matches ())
                {
                    String algo = mFr.group (1).trim ().toUpperCase ();
                    String problem = mFr.group (2).trim ();
                    double score = Double.parseDouble (mFr.group (3) + "." + mFr.group (4));
                    double [] bestX = parseBestX (mFr.group (5));
                    entries.add (new BksEntry (algo, problem, score, bestX));
                    continue;
                }

                Matcher mEn = BKS_LINE_EN.matcher (line);
                if (mEn.matches ())
                {
                    String algo = mEn.group (1).trim ().toUpperCase ();
                    String problem = mEn.group (2).trim ();
                    double score = Double.parseDouble (mEn.group (3));
                    double [] bestX = parseBestX (mEn.group (4));
                    entries.add (new BksEntry (algo, problem, score, bestX));
                    continue;
                }

                System.out.println ("[WARN] Ligne BKS ignorée (format inattendu): " + line);
            }
        }

        return entries;
    }

    private static double [] parseBestX (String raw)
    {
        String s = raw.trim ();
        if (s.startsWith ("[")) s = s.substring (1);
        if (s.endsWith ("]")) s = s.substring (0, s.length () - 1);
        s = s.replaceAll ("\\s+", "");

        if (s.isEmpty ())
            return new double [0];

        // Format normal: 1.23,4.56,7.89
        if (s.contains ("."))
        {
            String [] tokens = s.split (",");
            double [] out = new double [tokens.length];
            for (int i = 0; i < tokens.length; i++)
                out [i] = Double.parseDouble (tokens [i]);
            return out;
        }

        // Format FR issu de String.format locale FR: 1,230000,4,560000...
        // => on reconstruit par paires (entier, décimales)
        String [] tokens = s.split (",");
        if (tokens.length % 2 == 0)
        {
            double [] out = new double [tokens.length / 2];
            for (int i = 0, j = 0; i < tokens.length; i += 2, j++)
                out [j] = Double.parseDouble (tokens [i] + "." + tokens [i + 1]);
            return out;
        }

        // Fallback: parse direct après remplacement , -> .
        double [] out = new double [tokens.length];
        for (int i = 0; i < tokens.length; i++)
            out [i] = Double.parseDouble (tokens [i].replace (',', '.'));
        return out;
    }

    // =====================================================================
    //  Construction du graphique JFreeChart
    // =====================================================================
    private static JFreeChart buildChart (Problem problem, Coordinates [] trajectory,
                                          double [] bestX, double score,
                                          String algoName, Color trajColor)
    {
        // --- Séries de données -------------------------------------------

        // 0 : trajectoire (courbe de Bézier échantillonnée)
        XYSeries trajSeries = new XYSeries ("Trajectoire", false);
        for (Coordinates p : trajectory)
            trajSeries.add (p.getX (), p.getY ());

        // 1 : points de contrôle internes
        XYSeries cpSeries = new XYSeries ("Points de contrôle", false);
        for (int i = 0; i < bestX.length; i += 2)
            cpSeries.add (bestX [i], bestX [i + 1]);

        // 2 : points de départ et d'arrivée
        XYSeries seSeries = new XYSeries ("Départ / Arrivée", false);
        seSeries.add (problem.getStartPoint ().getX (), problem.getStartPoint ().getY ());
        seSeries.add (problem.getEndPoint ().getX (), problem.getEndPoint ().getY ());

        XYSeriesCollection dataset = new XYSeriesCollection ();
        dataset.addSeries (trajSeries);   // index 0
        dataset.addSeries (cpSeries);     // index 1
        dataset.addSeries (seSeries);     // index 2

        // --- Axes --------------------------------------------------------
        NumberAxis xAxis = new NumberAxis ("X");
        NumberAxis yAxis = new NumberAxis ("Y");

        // --- Renderer (style de chaque série) ----------------------------
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer ();

        // Trajectoire : trait épais, pas de marqueurs
        renderer.setSeriesPaint (0, trajColor);
        renderer.setSeriesStroke (0, new BasicStroke (2.5f));
        renderer.setSeriesShapesVisible (0, false);

        // Points de contrôle : petits carrés rouges, sans ligne
        renderer.setSeriesPaint (1, new Color (220, 50, 50));
        renderer.setSeriesLinesVisible (1, false);
        renderer.setSeriesShapesVisible (1, true);
        renderer.setSeriesShape (1, new Rectangle2D.Double (-4, -4, 8, 8));

        // Départ / Arrivée : losanges verts, sans ligne
        renderer.setSeriesPaint (2, new Color (0, 160, 0));
        renderer.setSeriesLinesVisible (2, false);
        renderer.setSeriesShapesVisible (2, true);
        int [] dx = { 0, 6, 0, -6 };
        int [] dy = { -6, 0, 6, 0 };
        renderer.setSeriesShape (2, new java.awt.Polygon (dx, dy, 4));

        // --- Plot (carré pour respecter l'aspect ratio) ------------------
        SquareXYPlot plot = new SquareXYPlot (dataset, xAxis, yAxis, renderer,
                problem.getMinX (), problem.getMaxX (), problem.getMinY (), problem.getMaxY ());

        // Annotations de fond : zone hors limites, obstacles
        addBoundaryAnnotation (renderer, problem);
        addObstacleAnnotations (renderer, problem);

        // --- Chart -------------------------------------------------------
        String title = String.format ("%s [%s]  —  score = %.2f", problem.getName (), algoName, score);
        JFreeChart chart = new JFreeChart (title,
                new Font ("SansSerif", Font.BOLD, 18), plot, true);
        chart.setBackgroundPaint (Color.WHITE);
        return chart;
    }

    // =====================================================================
    //  Zone hors limites (fond rose pâle) + zone valide (blanc)
    // =====================================================================
    private static void addBoundaryAnnotation (XYLineAndShapeRenderer renderer, Problem problem)
    {
        double minX = problem.getMinX (), maxX = problem.getMaxX ();
        double minY = problem.getMinY (), maxY = problem.getMaxY ();
        double w = maxX - minX, h = maxY - minY;

        // Rectangle extérieur couvrant tout le cadre
        Rectangle2D outer = new Rectangle2D.Double (minX - w, minY - h, 3 * w, 3 * h);
        XYShapeAnnotation bgAnnotation = new XYShapeAnnotation (outer,
                new BasicStroke (0), new Color (255, 210, 210), new Color (255, 210, 210));
        renderer.addAnnotation (bgAnnotation, Layer.BACKGROUND);

        // Rectangle intérieur = zone valide (blanc)
        Rectangle2D inner = new Rectangle2D.Double (minX, minY, w, h);
        XYShapeAnnotation validAnnotation = new XYShapeAnnotation (inner,
                new BasicStroke (1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10.0f, new float [] { 6f, 4f }, 0.0f),
                new Color (120, 120, 120), Color.WHITE);
        renderer.addAnnotation (validAnnotation, Layer.BACKGROUND);
    }

    // =====================================================================
    //  Obstacles : zone de pénalité (orange) + noyau dur (rouge)
    // =====================================================================
    private static void addObstacleAnnotations (XYLineAndShapeRenderer renderer, Problem problem)
    {
        for (int i = 0; i < problem.getNObstacles (); i++)
        {
            Obstacle obs = problem.getObstacle (i);
            double cx = obs.getX (), cy = obs.getY (), r = obs.getRadius ();

            // Zone de pénalité douce (rayon + 1) — orange clair
            Ellipse2D penaltyZone = new Ellipse2D.Double (cx - (r + 1), cy - (r + 1),
                    2 * (r + 1), 2 * (r + 1));
            XYShapeAnnotation softAnnotation = new XYShapeAnnotation (penaltyZone,
                    new BasicStroke (1.0f),
                    new Color (255, 180, 50), new Color (255, 220, 150));
            renderer.addAnnotation (softAnnotation, Layer.BACKGROUND);

            // Noyau dur (rayon) — rouge
            Ellipse2D core = new Ellipse2D.Double (cx - r, cy - r, 2 * r, 2 * r);
            XYShapeAnnotation hardAnnotation = new XYShapeAnnotation (core,
                    new BasicStroke (1.0f),
                    new Color (220, 60, 60), new Color (220, 100, 100));
            renderer.addAnnotation (hardAnnotation, Layer.BACKGROUND);
        }
    }
}
