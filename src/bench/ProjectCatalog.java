package bench;

import engine.cmaes.CMAESBuilder;
import engine.de.DECore;
import engine.de.DESHADECore;
import engine.de.LSHADECore;
import engine.ga.GACore;
import engine.pso.PSOCore;
import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import bench.competitors.lmcma.LMCMAProject;
import bench.competitors.nbipop.NBIPOPProject;
import bench.competitors.astarseeds.AStarSeedsProject;
import bench.competitors.alconst.ALConstraintProject;
import bench.competitors.rfsurr.RFSurrogateProject;
import bench.competitors.islands.IslandProject;
import engine.core.AlgorithmParameters;

/**
 * Catalogue de tous les projets disponibles via la nouvelle architecture.
 *
 * Chaque méthode retourne un OptimizerProject prêt à exécuter (init + loop).
 * Les anciens fichiers OptiPath.java, OptiPathDE.java, etc. sont remplacés.
 *
 * Utilisation :
 * <pre>
 *   Project p = ProjectCatalog.ipop(problem);
 *   // ou
 *   Project p = ProjectCatalog.full(problem);
 * </pre>
 */
public class ProjectCatalog
{
    private ProjectCatalog () {}

    // === CMA-ES variantes ===

    public static OptimizerProject ipop (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "IPOP-CMA-ES",
                () -> CMAESBuilder.ipop (problem).build ());
    }

    public static OptimizerProject ipop (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "IPOP-CMA-ES",
                () -> CMAESBuilder.ipop (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject bipop (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "BIPOP-CMA-ES",
                () -> CMAESBuilder.bipop (problem).build ());
    }

    public static OptimizerProject bipop (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "BIPOP-CMA-ES",
                () -> CMAESBuilder.bipop (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject active (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Active CMA-ES",
                () -> CMAESBuilder.active (problem).build ());
    }

    public static OptimizerProject active (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Active CMA-ES",
                () -> CMAESBuilder.active (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject surrogate (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Surrogate CMA-ES",
                () -> CMAESBuilder.surrogate (problem).build ());
    }

    public static OptimizerProject surrogate (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Surrogate CMA-ES",
                () -> CMAESBuilder.surrogate (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject adaptiveBipop (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Adaptive BIPOP CMA-ES",
                () -> CMAESBuilder.adaptiveBipop (problem).build ());
    }

    public static OptimizerProject adaptiveBipop (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Adaptive BIPOP CMA-ES",
                () -> CMAESBuilder.adaptiveBipop (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject gridBipop (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Grid BIPOP CMA-ES",
                () -> CMAESBuilder.gridBipop (problem).build ());
    }

    public static OptimizerProject gridBipop (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Grid BIPOP CMA-ES",
                () -> CMAESBuilder.gridBipop (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject stochRank (Problem problem, double threshold)
            throws InvalidProjectException
    {
        return new OptimizerProject (problem, "StochRank CMA-ES",
                () -> CMAESBuilder.stochRank (problem, threshold).build ());
    }

    public static OptimizerProject stochRank (Problem problem, double threshold, double margin)
            throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "StochRank CMA-ES",
                () -> CMAESBuilder.stochRank (problem, threshold)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject sepWarmup (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Sep-Warmup CMA-ES",
                () -> CMAESBuilder.sepWarmup (problem).build ());
    }

    public static OptimizerProject sepWarmup (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Sep-Warmup CMA-ES",
                () -> CMAESBuilder.sepWarmup (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    public static OptimizerProject full (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "Full CMA-ES (Active+Surrogate+AdaBIPOP)",
                () -> CMAESBuilder.full (problem).build ());
    }

    public static OptimizerProject full (Problem problem, double margin) throws InvalidProjectException
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = buildLb (problem, d, margin);
        double [] ub = buildUb (problem, d, margin);
        return new OptimizerProject (problem, "Full CMA-ES (Active+Surrogate+AdaBIPOP)",
                () -> CMAESBuilder.full (problem)
                        .bounds (lb, ub)
                        .parameters (paramsWithMargin (margin))
                        .build ());
    }

    // === DE variantes ===

    public static OptimizerProject de (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "DE jDE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d);
                    double [] ub = buildUb (problem, d);
                    return new DECore (problem, d, lb, ub);
                });
    }

    public static OptimizerProject de (Problem problem, double margin) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "DE jDE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d, margin);
                    double [] ub = buildUb (problem, d, margin);
                    return new DECore (problem, d, lb, ub);
                });
    }

    public static OptimizerProject shade (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "DE SHADE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d);
                    double [] ub = buildUb (problem, d);
                    return new DESHADECore (problem, d, lb, ub);
                });
    }

    public static OptimizerProject shade (Problem problem, double margin) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "DE SHADE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d, margin);
                    double [] ub = buildUb (problem, d, margin);
                    return new DESHADECore (problem, d, lb, ub);
                });
    }

    public static OptimizerProject lshade (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "L-SHADE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d);
                    double [] ub = buildUb (problem, d);
                    // Estimation : 300,000 evals en 60s
                    return new LSHADECore (problem, d, lb, ub, 300_000);
                });
    }

    public static OptimizerProject lshade (Problem problem, double margin) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "L-SHADE",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d, margin);
                    double [] ub = buildUb (problem, d, margin);
                    // Estimation : 300,000 evals en 60s
                    return new LSHADECore (problem, d, lb, ub, 300_000);
                });
    }

    // === GA ===

    public static OptimizerProject ga (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "GA SBX",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d);
                    double [] ub = buildUb (problem, d);
                    return new GACore (problem, d, lb, ub);
                });
    }

    public static OptimizerProject ga (Problem problem, double margin) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "GA SBX",
                () -> {
                    int d = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d, margin);
                    double [] ub = buildUb (problem, d, margin);
                    return new GACore (problem, d, lb, ub);
                });
    }

    // === PSO ===

    public static OptimizerProject pso (Problem problem) throws InvalidProjectException
    {
        return new OptimizerProject (problem, "PSO",
                () -> {
                    int d        = 2 * problem.getNControlPoints ();
                    double [] lb = buildLb (problem, d);
                    double [] ub = buildUb (problem, d);
                    return new PSOCore (problem, d, lb, ub);
                });
    }

    // === Compétiteurs auto-contenus ===

    public static CompetitorProject lmcma (Problem problem) throws InvalidProjectException
    {
        return new LMCMAProject (problem);
    }

    public static CompetitorProject lmcma (Problem problem, double margin) throws InvalidProjectException
    {
        return new LMCMAProject (problem, margin);
    }

    public static CompetitorProject nbipop (Problem problem) throws InvalidProjectException
    {
        return new NBIPOPProject (problem);
    }

    public static CompetitorProject nbipop (Problem problem, double margin) throws InvalidProjectException
    {
        return new NBIPOPProject (problem, margin);
    }

    public static CompetitorProject astarSeeds (Problem problem) throws InvalidProjectException
    {
        return new AStarSeedsProject (problem);
    }

    public static CompetitorProject astarSeeds (Problem problem, double margin) throws InvalidProjectException
    {
        return new AStarSeedsProject (problem, margin);
    }

    public static CompetitorProject alConstraint (Problem problem) throws InvalidProjectException
    {
        return new ALConstraintProject (problem);
    }

    public static CompetitorProject alConstraint (Problem problem, double margin) throws InvalidProjectException
    {
        return new ALConstraintProject (problem, margin);
    }

    public static CompetitorProject rfSurrogate (Problem problem) throws InvalidProjectException
    {
        return new RFSurrogateProject (problem);
    }

    public static CompetitorProject rfSurrogate (Problem problem, double margin) throws InvalidProjectException
    {
        return new RFSurrogateProject (problem, margin);
    }

    public static CompetitorProject islands (Problem problem) throws InvalidProjectException
    {
        return new IslandProject (problem);
    }

    public static CompetitorProject islands (Problem problem, double margin) throws InvalidProjectException
    {
        return new IslandProject (problem, margin);
    }

    // === Helpers ===

    private static double [] buildLb (Problem p, int d)
    {
        double [] lb = new double [d];
        for (int i = 0; i < d; i++)
            lb [i] = (i % 2 == 0) ? p.getMinX () : p.getMinY ();
        return lb;
    }

    private static double [] buildUb (Problem p, int d)
    {
        double [] ub = new double [d];
        for (int i = 0; i < d; i++)
            ub [i] = (i % 2 == 0) ? p.getMaxX () : p.getMaxY ();
        return ub;
    }

    private static double [] buildLb (Problem p, int d, double margin)
    {
        double [] lb = new double [d];
        for (int i = 0; i < d; i++)
            lb [i] = (i % 2 == 0) ? (p.getMinX () - margin) : (p.getMinY () - margin);
        return lb;
    }

    private static double [] buildUb (Problem p, int d, double margin)
    {
        double [] ub = new double [d];
        for (int i = 0; i < d; i++)
            ub [i] = (i % 2 == 0) ? (p.getMaxX () + margin) : (p.getMaxY () + margin);
        return ub;
    }

    private static AlgorithmParameters paramsWithMargin (double margin)
    {
        AlgorithmParameters params = new AlgorithmParameters ();
        params.setMargin (margin);
        return params;
    }

    private static double [] buildInitMean (Problem p, int d)
    {
        int nCP = d / 2;
        double [] m = new double [d];
        double sx = p.getStartPoint ().getX (), sy = p.getStartPoint ().getY ();
        double ex = p.getEndPoint ().getX (),   ey = p.getEndPoint ().getY ();
        for (int i = 0; i < nCP; i++)
        {
            double t = (double) (i + 1) / (nCP + 1);
            m [2 * i]     = sx + t * (ex - sx);
            m [2 * i + 1] = sy + t * (ey - sy);
        }
        return m;
    }
}
