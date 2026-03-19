package bench.cli;

public class BenchConfig {
    // Clé = nom affiché, valeur = identifiant interne
    public static final String [] ALGO_NAMES = {
        "OptiPath Final",
        "IPOP-CMA-ES",
        "BIPOP-CMA-ES",
        "Active CMA-ES",
        "Surrogate CMA-ES",
        "Adaptive BIPOP CMA-ES",
        "Grid BIPOP CMA-ES",
        "Sep-Warmup CMA-ES",
        "Full CMA-ES (Active+Surr+AdaBIPOP)",
        "StochRank CMA-ES",
        "DE jDE",
        "DE SHADE",
        "L-SHADE",
        "GA SBX",
        "PSO",
        "CLPSO",
        "FPSO",
        "LMCMA",
        "NBIPOP",
        "AStarSeeds",
        "ALConstraint",
        "RFSurrogate",
        "Islands",
        "Hybrid PSO-CMA-ES"
    };
}
