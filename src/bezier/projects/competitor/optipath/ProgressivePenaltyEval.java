package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

/**
 * Progressive penalty evaluation to prioritize feasibility over smooth curves.
 */
public class ProgressivePenaltyEval implements EvalWrapper {
    private static final double FEAS_TOL = 1e-12;
    private static final double PENALTY_SCALE = 200000.0;
    private static final double PENALTY_POWER = 1.2;

    private final Problem problem;
    private int evalCount = 0;

    public ProgressivePenaltyEval(Problem problem) {
        this.problem = problem;
    }

    @Override
    public double[] evaluateBatch(double[][] candidates, int lambda) {
        double[] fitness = new double[lambda];
        for (int k = 0; k < lambda; k++) {
            double[] objectives = problem.evaluateMulti(candidates[k]);
            evalCount++;
            if (objectives == null || objectives.length < 4) {
                fitness[k] = Double.POSITIVE_INFINITY;
                continue;
            }
            double length = objectives[0];
            double smooth = objectives[2];
            double violation = Math.max(0.0, objectives[1]) + Math.max(0.0, objectives[3]);

            if (violation <= FEAS_TOL) {
                fitness[k] = length + objectives[1] + smooth + objectives[3];
            } else {
                double penalty = PENALTY_SCALE * Math.pow(violation, PENALTY_POWER);
                fitness[k] = length + smooth + penalty;
            }
        }
        return fitness;
    }

    @Override
    public int getEvalCount() {
        return evalCount;
    }
}
