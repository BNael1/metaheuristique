package bench.cli.runner;

import engine.core.Optimizer;
import engine.cmaes.CMAESBuilder;
import engine.de.DECore;
import engine.de.DESHADECore;
import engine.de.LSHADECore;
import engine.ga.GACore;
import engine.pso.PSOCore;
import engine.pso.CLPSOCore;
import engine.pso.FPSOCore;
import bezier.evaluation.Problem;
import bench.ProjectCatalog;
import bezier.projects.InvalidProjectException;

/**
 * Factory qui crée un Optimizer à partir d'un nom d'algorithme.
 * Migré depuis bench.gui pour usage CLI.
 */
public class AlgorithmFactory
{
    private AlgorithmFactory () {}

    public static Optimizer create (String algoName, Problem problem, double margin) throws Exception
    {
        int d = 2 * problem.getNControlPoints ();
        double [] lb = new double [d], ub = new double [d];
        for (int i = 0; i < d; i++)
        {
            lb [i] = ((i % 2 == 0) ? problem.getMinX () : problem.getMinY ()) - margin;
            ub [i] = ((i % 2 == 0) ? problem.getMaxX () : problem.getMaxY ()) + margin;
        }

        switch (algoName)
        {
            case "OptiPath Final":
                return CMAESBuilder.full (problem).bounds(lb, ub).build();

            // === CMA-ES ===
            case "IPOP-CMA-ES":
                return CMAESBuilder.ipop (problem).bounds(lb, ub).build();
            case "BIPOP-CMA-ES":
                return CMAESBuilder.bipop (problem).bounds(lb, ub).build();
            case "Active CMA-ES":
                return CMAESBuilder.active (problem).bounds(lb, ub).build();
            case "Surrogate CMA-ES":
                return CMAESBuilder.surrogate (problem).bounds(lb, ub).build();
            case "Adaptive BIPOP CMA-ES":
                return CMAESBuilder.adaptiveBipop (problem).bounds(lb, ub).build();
            case "Grid BIPOP CMA-ES":
                return CMAESBuilder.gridBipop (problem).bounds(lb, ub).build();
            case "Sep-Warmup CMA-ES":
                return CMAESBuilder.sepWarmup (problem).bounds(lb, ub).build();
            case "Full CMA-ES (Active+Surr+AdaBIPOP)":
                return CMAESBuilder.full (problem).bounds(lb, ub).build();
            case "StochRank CMA-ES":
                return CMAESBuilder.stochRank (problem, 0.45).bounds(lb, ub).build();

            // === DE ===
            case "DE jDE":
                return new DECore (problem, d, lb, ub);
            case "DE SHADE":
                return new DESHADECore (problem, d, lb, ub);
            case "L-SHADE":
                return new LSHADECore (problem, d, lb, ub, 300_000); // Default budget

            // === GA ===
            case "GA SBX":
                return new GACore (problem, d, lb, ub);

            // === PSO ===
            case "PSO":
                return new PSOCore (problem, d, lb, ub);
            case "CLPSO":
                return new CLPSOCore (problem, d, lb, ub);
            case "FPSO":
                return new FPSOCore (problem, d, lb, ub);

            // === Competitors (Auto-contained) ===
            case "LMCMA":
                return wrapProject(ProjectCatalog.lmcma(problem, margin));
            case "NBIPOP":
                return wrapProject(ProjectCatalog.nbipop(problem, margin));
            case "AStarSeeds":
                return wrapProject(ProjectCatalog.astarSeeds(problem, margin));
            case "ALConstraint":
                return wrapProject(ProjectCatalog.alConstraint(problem, margin));
            case "RFSurrogate":
                return wrapProject(ProjectCatalog.rfSurrogate(problem, margin));
            case "Islands":
                return wrapProject(ProjectCatalog.islands(problem, margin));
                
            default:
                throw new IllegalArgumentException ("Algorithme inconnu : " + algoName);
        }
    }

    private static Optimizer wrapProject(final bezier.projects.CompetitorProject project) {
        return new Optimizer() {
            @Override
            public void init() {
                project.initialization();
            }

            @Override
            public void step() {
                project.loop();
            }

            @Override
            public double[] getBestX() {
                return null;
            }

            @Override
            public double getBestFitness() {
                return project.getSolution().getEvaluation();
            }

            @Override
            public boolean shouldRestart() {
                return false;
            }

            @Override
            public engine.core.OptimizerState getState() {
                return new engine.core.OptimizerState(
                    0, // gen unknown
                    0, // evals unknown
                    0, // restarts unknown
                    project.getSolution().getEvaluation(),
                    0, 0, null
                );
            }
        };
    }
}
