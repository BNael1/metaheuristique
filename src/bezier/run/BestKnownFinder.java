package bezier.run;

import bezier.evaluation.BezierChart;
import bezier.evaluation.MonitorChart;
import bezier.evaluation.Problem;
import bezier.projects.competitor.optipath.CMAESOptimizer;
import bezier.projects.competitor.optipath.Optimizer;
import bezier.projects.competitor.de.DEOptimizer;
import bezier.projects.competitor.ga.GAOptimizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Recherche de Best Known Solutions (BKS) avec budget prolongé.
 * ─────────────────────────────────────────────────────────────
 * ▸ Reprend automatiquement là où il s'est arrêté (lit bks_all.csv).
 * ▸ Barre de progression en temps réel pour chaque restart et globale.
 * ▸ Résultats écrits incrémentalement dans results/bks_all.csv.
 *
 * Usage :
 *   nohup java -cp "lib/*:bin" bezier.run.BestKnownFinder > results/bks_log.txt 2>&1 &
 *
 * Budget :
 *   prob1-3 : 900s (15 min) × 5 restarts
 *   prob4   : 2700s (45 min) × 10 restarts
 */
public class BestKnownFinder
{
    // ───── Configuration ─────
    private static final int SECONDS_EASY  = 900;
    private static final int SECONDS_HARD  = 2700;
    private static final int RESTARTS_EASY = 5;
    private static final int RESTARTS_HARD = 10;
    private static final String [] ALGOS = { "CMAES", "DE", "GA" };
    private static final String CSV_PATH = "results/bks_all.csv";

    // ───── ANSI couleurs ─────
    private static final String RST = "\033[0m";
    private static final String B   = "\033[1m";
    private static final String G   = "\033[32m";
    private static final String C   = "\033[36m";
    private static final String D   = "\033[2m";

    public static void main (String [] args) throws Exception
    {
        new File ("results").mkdirs ();

        // ═══ 1. Charger les résultats déjà terminés ═══
        Set<String> done = new LinkedHashSet<> ();
        Map<String, String> prevLines = new LinkedHashMap<> ();
        File csvFile = new File (CSV_PATH);
        if (csvFile.exists ())
        {
            try (BufferedReader br = new BufferedReader (new FileReader (csvFile)))
            {
                br.readLine (); // skip header
                String line;
                while ((line = br.readLine ()) != null)
                {
                    if (line.trim ().isEmpty ()) continue;
                    String [] p = line.split (",", 3);
                    if (p.length >= 2)
                    {
                        String key = p [0] + "|" + p [1];
                        done.add (key);
                        prevLines.put (key, line);
                    }
                }
            }
        }

        // ═══ 2. Compteurs ═══
        ArrayList<Problem> problems = Problem.getProblems ();
        int totalTasks = ALGOS.length * problems.size ();
        int completed  = done.size ();

        int etaSec = 0;
        for (String a : ALGOS)
            for (Problem pr : problems)
                if (!done.contains (a + "|" + pr.getName ()))
                    etaSec += pr.getName ().equals ("prob4")
                            ? SECONDS_HARD * RESTARTS_HARD
                            : SECONDS_EASY * RESTARTS_EASY;

        // ═══ 3. En-tête ═══
        printHeader ();

        if (!done.isEmpty ())
        {
            System.err.println (C + "  ⟳  Reprise : " + done.size ()
                    + "/" + totalTasks + " déjà terminées" + RST);
            for (String k : done)
            {
                String sc = prevLines.get (k).split (",") [2];
                System.err.println (D + "     ✓ " + k.replace ("|", " / ")
                        + "  →  BKS = " + sc + RST);
            }
            System.err.println ();
        }

        System.err.printf ("  Temps restant estimé : %s%n%n", fmtDur (etaSec));
        printGlobal (completed, totalTasks);
        System.err.println ();

        // ═══ 4. CSV en mode append ═══
        boolean needHdr = !csvFile.exists () || csvFile.length () == 0;
        PrintWriter csv = new PrintWriter (new FileWriter (csvFile, true));
        if (needHdr) csv.println ("Algo,Problem,BKS,BestX,nRestarts");

        // ═══ 5. Boucle principale ═══
        long t0 = System.currentTimeMillis ();
        int doneBefore = done.size ();

        for (String algo : ALGOS)
        {
            for (Problem problem : problems)
            {
                String key = algo + "|" + problem.getName ();
                if (done.contains (key)) continue;

                boolean hard = problem.getName ().equals ("prob4");
                int budget   = hard ? SECONDS_HARD : SECONDS_EASY;
                int nRst     = hard ? RESTARTS_HARD : RESTARTS_EASY;

                double bestScore = Double.POSITIVE_INFINITY;
                double [] bestX  = null;

                System.err.println (B + "━".repeat (52) + RST);
                System.err.printf (B + "  %s / %s" + RST
                        + "  ─  %d restarts × %s%n",
                        algo, problem.getName (), nRst, fmtDur (budget));
                System.err.println (B + "━".repeat (52) + RST);

                for (int r = 1; r <= nRst; r++)
                {
                    problem.reset ();
                    MonitorChart.getNewInstance (key + "_r" + r);
                    BezierChart.getNewInstance (problem);

                    Optimizer opt = makeOptimizer (algo, problem);
                    opt.init ();

                    // ── thread de progression ──
                    final int bgt = budget, rr = r, nr = nRst;
                    Thread prog = new Thread (() -> showProgress (bgt, rr, nr));
                    prog.setDaemon (true);
                    prog.start ();

                    // ── exécution ──
                    ExecutorService ex = Executors.newSingleThreadExecutor ();
                    Future <?> fut = ex.submit (() -> {
                        while (!Thread.currentThread ().isInterrupted ())
                            opt.step ();
                    });
                    try { fut.get (budget, TimeUnit.SECONDS); }
                    catch (TimeoutException e)
                    { ex.shutdownNow (); fut.cancel (true); }
                    ex.awaitTermination (60, TimeUnit.SECONDS);
                    prog.interrupt ();

                    double sc = problem.getBestEvaluation ();
                    double [] bx = opt.getBestX ();
                    String star = "";
                    if (sc < bestScore && bx != null)
                    { bestScore = sc; bestX = bx.clone (); star = G + " ★ NEW BEST" + RST; }

                    System.err.printf (
                            "\r  restart %d/%d  %s 100%%  score = %.4f%s%n",
                            r, nRst, bar (100, 30), sc, star);
                }

                // ── sauvegarder ──
                csv.printf ("%s,%s,%.6f,\"%s\",%d%n",
                        algo, problem.getName (), bestScore,
                        vecToStr (bestX), nRst);
                csv.flush ();
                completed++;

                System.err.println ();
                System.err.printf (G + "  ✓ BKS %s/%s = %.4f" + RST + "%n%n",
                        algo, problem.getName (), bestScore);
                printGlobal (completed, totalTasks);

                // ETA
                int doneNow = completed - doneBefore;
                if (doneNow > 0)
                {
                    long elapsed = (System.currentTimeMillis () - t0) / 1000;
                    long eta = elapsed / doneNow * (totalTasks - completed);
                    System.err.printf ("  ETA : ~%s%n%n", fmtDur ((int) eta));
                }
            }
        }

        csv.close ();

        // ═══ 6. Résumé final ═══
        System.err.println ();
        System.err.println (B + "╔════════════════════════════════════════════════╗" + RST);
        System.err.println (B + "║        BKS TERMINÉ — RÉSULTATS FINAUX         ║" + RST);
        System.err.println (B + "╚════════════════════════════════════════════════╝" + RST);
        System.err.println ();
        System.err.printf ("  %-8s %-8s %12s%n", "Algo", "Problem", "BKS");
        System.err.println ("  " + "─".repeat (32));

        try (BufferedReader br = new BufferedReader (new FileReader (CSV_PATH)))
        {
            br.readLine ();
            String line;
            while ((line = br.readLine ()) != null)
            {
                if (line.trim ().isEmpty ()) continue;
                String [] p = line.split (",", 4);
                System.err.printf ("  %-8s %-8s %12s%n", p [0], p [1], p [2]);
            }
        }

        System.err.println ();
        System.err.println (G + "  Résultats dans : " + CSV_PATH + RST);
        System.err.println ();
        System.exit (0);
    }

    // ═════════════════════════════════════════════════════
    //  Fabrique d'optimiseurs
    // ═════════════════════════════════════════════════════
    private static Optimizer makeOptimizer (String algo, Problem problem)
    {
        int nCP = problem.getNControlPoints ();
        int d   = 2 * nCP;
        double [] lb = new double [d], ub = new double [d];
        for (int j = 0; j < d; j++)
        {
            lb [j] = (j % 2 == 0) ? problem.getMinX () : problem.getMinY ();
            ub [j] = (j % 2 == 0) ? problem.getMaxX () : problem.getMaxY ();
        }
        double [] init = new double [d];
        double sx = problem.getStartPoint ().getX (),
               sy = problem.getStartPoint ().getY (),
               ex = problem.getEndPoint ().getX (),
               ey = problem.getEndPoint ().getY ();
        for (int k = 0; k < nCP; k++)
        {
            double t = (double) (k + 1) / (nCP + 1);
            init [2 * k]     = sx + t * (ex - sx);
            init [2 * k + 1] = sy + t * (ey - sy);
        }
        switch (algo)
        {
            case "DE":  return new DEOptimizer  (problem, d, lb, ub, init);
            case "GA":  return new GAOptimizer  (problem, d, lb, ub, init);
            default:    return new CMAESOptimizer (problem, d, lb, ub, init);
        }
    }

    // ═════════════════════════════════════════════════════
    //  Affichage
    // ═════════════════════════════════════════════════════
    private static void showProgress (int budget, int r, int nRst)
    {
        try
        {
            long t0 = System.currentTimeMillis ();
            while (!Thread.currentThread ().isInterrupted ())
            {
                Thread.sleep (5000);
                long el = (System.currentTimeMillis () - t0) / 1000;
                int pct = (int) Math.min (99, el * 100 / budget);
                System.err.printf ("\r  restart %d/%d  %s %2d%%  [%s/%s]",
                        r, nRst, bar (pct, 30), pct,
                        fmtDur ((int) el), fmtDur (budget));
            }
        }
        catch (InterruptedException ignored) { }
    }

    private static void printHeader ()
    {
        System.err.println ();
        System.err.println (B + "╔════════════════════════════════════════════════╗" + RST);
        System.err.println (B + "║        BEST KNOWN SOLUTION FINDER              ║" + RST);
        System.err.println (B + "╠════════════════════════════════════════════════╣" + RST);
        System.err.printf  (B + "║" + RST + "  prob1-3 : %4ds × %d restarts = %s/prob  "
                + B + "║%n" + RST, SECONDS_EASY, RESTARTS_EASY,
                fmtDur (SECONDS_EASY * RESTARTS_EASY));
        System.err.printf  (B + "║" + RST + "  prob4   : %4ds × %2d restarts = %s/prob "
                + B + "║%n" + RST, SECONDS_HARD, RESTARTS_HARD,
                fmtDur (SECONDS_HARD * RESTARTS_HARD));
        System.err.println (B + "╚════════════════════════════════════════════════╝" + RST);
        System.err.println ();
    }

    private static void printGlobal (int done, int total)
    {
        int pct = total == 0 ? 100 : done * 100 / total;
        System.err.printf ("  " + B + "GLOBAL" + RST + "  %s %3d%%  (%d/%d)%n",
                bar (pct, 40), pct, done, total);
    }

    private static String bar (int pct, int w)
    {
        int f = pct * w / 100;
        StringBuilder sb = new StringBuilder ();
        sb.append (G).append ("▐").append (RST);
        for (int i = 0; i < w; i++)
            sb.append (i < f ? G + "█" + RST : D + "░" + RST);
        sb.append (G).append ("▌").append (RST);
        return sb.toString ();
    }

    private static String fmtDur (int s)
    {
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format ("%dh%02dm%02ds", h, m, sec);
        if (m > 0) return String.format ("%dm%02ds", m, sec);
        return sec + "s";
    }

    private static String vecToStr (double [] x)
    {
        if (x == null) return "[]";
        StringBuilder sb = new StringBuilder ("[");
        for (int i = 0; i < x.length; i++)
        {
            if (i > 0) sb.append (",");
            sb.append (String.format ("%.6f", x [i]));
        }
        return sb.append ("]").toString ();
    }
}
