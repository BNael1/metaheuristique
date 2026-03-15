package bench;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bench.OptimizerProject;
import engine.core.AlgorithmParameters;
import engine.core.AlgorithmParameters.InitStrategy;
import engine.cmaes.CMAESBuilder;
import bench.ProjectCatalog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Benchmark comparant les stratégies d'initialisation sur tous les algos.
 *
 * Stratégies : RANDOM, CENTER_LINE, ASTAR_PATH
 * CMA-ES variants : InitStrategy prise en compte via AlgorithmParameters
 * Autres algos (DE, GA, PSO, compétiteurs) : InitStrategy sans effet (RANDOM implicite)
 */
public class BenchmarkInitStrategies
{
    private static int NB_SECONDS = 60; 
    private static int NB_RUNS    = 3;
    private static String CSV_PATH = "results/bench_InitStrategies.csv";

    private static final InitStrategy [] STRATEGIES = {InitStrategy.RANDOM, InitStrategy.CENTER_LINE, InitStrategy.ASTAR_PATH};
    private static final String [] ALGOS =
            {"CMAES", "DE", "GA", "MultiSegment", "BIPOP", "SHADE", "L-SHADE", "CLPSO", "BipopAdaptif",
             "LMCMA", "AStarSeeds", "NBIPOP", "ALConstraint", "RFSurrogate", "Islands",
             "GridBIPOP", "StochRank", "AStarRepair", "SepWarmup", "OptiPathFinal", "PSO", "FPSO"};

    public static void main (String [] args) throws Exception
    {
        boolean headless = true;
        
        for (int i = 0; i < args.length; i++) {
            if (args [i].equals ("--seconds") && i + 1 < args.length)
                NB_SECONDS = Integer.parseInt (args [++i]);
        }

        if (headless) Problem.headless = true;
        new File ("results").mkdirs ();
        File csvFile = new File (CSV_PATH);

        // --- Charger les resultats existants ---
        LinkedHashMap<String, Double> existing = new LinkedHashMap<> ();
        if (csvFile.exists ())
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
                        try { existing.put (key, Double.parseDouble (parts [4].trim ())); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        
        if (!csvFile.exists ()) {
            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile))) {
                pw.println ("Strategy,Algorithm,Problem,Run,Score");
            }
        }

        ArrayList<Problem> problems = Problem.getProblems ();
        
        // Loop
        for (InitStrategy strat : STRATEGIES)
        {
            for (String algo : ALGOS)
            {
                for (Problem problem : problems)
                {
                    for (int run = 1; run <= NB_RUNS; run++)
                    {
                        String key = strat.name() + "," + algo + "," + problem.getName() + "," + run;
                        if (existing.containsKey(key)) continue;
                        
                        System.err.print (strat + " / " + algo + " / " + problem.getName () + " / run " + run + " ... ");
                        problem.reset ();
                        
                        try
                        {
                            CompetitorProject project = createProject (algo, problem, strat);
                            project.initialization ();
                            long deadline = System.currentTimeMillis () + NB_SECONDS * 1000L;
                            while (System.currentTimeMillis () < deadline)
                                project.loop ();

                            double score = problem.getBestEvaluation ();
                            System.err.println ("score = " + String.format ("%.2f", score));

                            try (PrintWriter pw = new PrintWriter (new FileWriter (csvFile, true)))
                            {
                                pw.println (strat.name() + "," + algo + "," + problem.getName () + "," + run + "," + score);
                            }
                        }
                        catch (Exception ex)
                        {
                            System.err.println ("ERREUR : " + ex.getMessage ());
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
        
        System.err.println("Benchmark Init terminé.");
    }
    
    private static CompetitorProject createProject (String algo, Problem problem, InitStrategy strat) throws Exception
    {
        AlgorithmParameters params = new AlgorithmParameters ();
        params.setInitStrategy (strat);
        params.setMargin (1.0);

        switch (algo)
        {
            // CMA-ES variants : InitStrategy est prise en compte
            case "CMAES":         return new OptimizerProject (problem, "IPOP-CMA-ES",
                                      () -> CMAESBuilder.ipop          (problem).parameters (params).build ());
            case "MultiSegment":  return new OptimizerProject (problem, "MultiSegment",
                                      () -> CMAESBuilder.ipop          (problem).parameters (params).build ());
            case "BIPOP":         return new OptimizerProject (problem, "BIPOP-CMA-ES",
                                      () -> CMAESBuilder.bipop         (problem).parameters (params).build ());
            case "BipopAdaptif":  return new OptimizerProject (problem, "Adaptive BIPOP",
                                      () -> CMAESBuilder.adaptiveBipop (problem).parameters (params).build ());
            case "GridBIPOP":     return new OptimizerProject (problem, "Grid BIPOP",
                                      () -> CMAESBuilder.gridBipop     (problem).parameters (params).build ());
            case "StochRank":     return new OptimizerProject (problem, "StochRank CMA-ES",
                                      () -> CMAESBuilder.stochRank     (problem, 0.45).parameters (params).build ());
            case "AStarRepair":   return new OptimizerProject (problem, "Surrogate CMA-ES",
                                      () -> CMAESBuilder.surrogate     (problem).parameters (params).build ());
            case "SepWarmup":     return new OptimizerProject (problem, "Sep-Warmup CMA-ES",
                                      () -> CMAESBuilder.sepWarmup     (problem).parameters (params).build ());
            case "OptiPathFinal": return new OptimizerProject (problem, "Full CMA-ES",
                                      () -> CMAESBuilder.full          (problem).parameters (params).build ());

            // DE / GA / PSO : pas de support InitStrategy, margin=1.0
            case "DE":            return ProjectCatalog.de     (problem, 1.0);
            case "GA":            return ProjectCatalog.ga     (problem, 1.0);
            case "SHADE":         return ProjectCatalog.shade  (problem, 1.0);
            case "L-SHADE":       return ProjectCatalog.lshade (problem, 1.0);
            case "CLPSO":         return ProjectCatalog.clpso  (problem, 1.0);
            case "PSO":           return ProjectCatalog.pso    (problem);
            case "FPSO":          return ProjectCatalog.fpso   (problem, 1.0);

            // Compétiteurs auto-contenus : pas de support InitStrategy, margin=1.0
            case "LMCMA":         return ProjectCatalog.lmcma       (problem, 1.0);
            case "AStarSeeds":    return ProjectCatalog.astarSeeds   (problem, 1.0);
            case "NBIPOP":        return ProjectCatalog.nbipop       (problem, 1.0);
            case "ALConstraint":  return ProjectCatalog.alConstraint (problem, 1.0);
            case "RFSurrogate":   return ProjectCatalog.rfSurrogate  (problem, 1.0);
            case "Islands":       return ProjectCatalog.islands      (problem, 1.0);

            default: throw new IllegalArgumentException ("Unknown algo: " + algo);
        }
    }
}
