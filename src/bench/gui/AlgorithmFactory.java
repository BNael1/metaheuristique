package bench.gui;

import engine.core.Optimizer;
import engine.cmaes.CMAESBuilder;
import engine.de.DECore;
import engine.de.DESHADECore;
import engine.ga.GACore;
import bezier.evaluation.Problem;

/**
 * Factory qui crée un Optimizer à partir d'un nom d'algorithme
 * (tel qu'affiché dans le ConfigPane).
 */
public class AlgorithmFactory
{
    private AlgorithmFactory () {}

    public static Optimizer create (String algoName, Problem problem, double margin)
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
            case "IPOP-CMA-ES":
                return CMAESBuilder.ipop (problem).build ();
            case "BIPOP-CMA-ES":
                return CMAESBuilder.bipop (problem).build ();
            case "Active CMA-ES":
                return CMAESBuilder.active (problem).build ();
            case "Surrogate CMA-ES":
                return CMAESBuilder.surrogate (problem).build ();
            case "Adaptive BIPOP CMA-ES":
                return CMAESBuilder.adaptiveBipop (problem).build ();
            case "Grid BIPOP CMA-ES":
                return CMAESBuilder.gridBipop (problem).build ();
            case "Sep-Warmup CMA-ES":
                return CMAESBuilder.sepWarmup (problem).build ();
            case "Full CMA-ES (Active+Surr+AdaBIPOP)":
                return CMAESBuilder.full (problem).build ();
            case "DE jDE":
                return new DECore (problem, d, lb, ub);
            case "DE SHADE":
                return new DESHADECore (problem, d, lb, ub);
            case "GA SBX":
                return new GACore (problem, d, lb, ub);
            case "OptiPath Final":
                return CMAESBuilder.full (problem).build ();
            default:
                throw new IllegalArgumentException ("Algorithme inconnu : " + algoName);
        }
    }
}
