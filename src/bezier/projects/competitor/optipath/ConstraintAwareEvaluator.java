package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

/**
 * Objectif de recherche "constraint-first" :
 * - candidats faisables -> vraie fitness Problem.evaluate(...)
 * - candidats non faisables -> score proxy avec tres forte penalite dure.
 */
final class ConstraintAwareEvaluator
{
    private static final double HARD_PENALTY = 1_000_000.0;
    private static final double FEASIBILITY_TOL = 1e-12;
    private static final double OVERLAP_TOL = 1e-6;

    private ConstraintAwareEvaluator () {}

    static double evaluateForSearch (Problem problem, double [] candidate)
    {
        double [] objectives = problem.evaluateMulti (candidate);
        if (objectives == null || objectives.length < 4)
            return Double.POSITIVE_INFINITY;

        double length = objectives [0];
        double hardViolation = Math.max (0.0, objectives [1]) + Math.max (0.0, objectives [3]);

        if (hardViolation <= FEASIBILITY_TOL)
            return objectives [0] + objectives [1] + objectives [2] + objectives [3];

        // Base > 1e6 pour distinguer facilement les candidats non faisables.
        return HARD_PENALTY + length + HARD_PENALTY * hardViolation;
    }

    static boolean isStrictlyFeasible (Problem problem, double [] candidate)
    {
        double [] objectives = problem.evaluateMulti (candidate);
        if (objectives == null || objectives.length < 4)
            return false;

        return objectives [1] <= FEASIBILITY_TOL && objectives [3] <= FEASIBILITY_TOL;
    }
}
