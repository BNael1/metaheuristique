package bezier2_0.projects.competitor.optipath;


/**
 * Holds all strategic hyperparameters for an algorithm run.
 * Can be serialized/deserialized to "best_params.properties" to pass Tuner values automatically to the Master's benchmark loop.
 */
public class AlgorithmParameters {

    public enum InitStrategy { RANDOM, ASTAR_PATH, CENTER_LINE }
    public enum RestartStrategy { NONE, ON_STAGNATION }
    public enum RepairStrategy { NONE, PUSH_FROM_OBSTACLE }

    private int controlPoints;
    private double margin;
    private InitStrategy initStrategy;
    private RestartStrategy restartStrategy;
    private RepairStrategy repairStrategy;
    private boolean hybridMode;

    public AlgorithmParameters() {
        // Defaults
        this.controlPoints = 15;
        this.margin = 1.0;
        this.initStrategy = InitStrategy.RANDOM;
        this.restartStrategy = RestartStrategy.NONE;
        this.repairStrategy = RepairStrategy.NONE;
        this.hybridMode = false;
    }

    public AlgorithmParameters(int controlPoints, double margin, InitStrategy initStrategy, RestartStrategy restartStrategy, RepairStrategy repairStrategy, boolean hybridMode) {
        this.controlPoints = controlPoints;
        this.margin = margin;
        this.initStrategy = initStrategy;
        this.restartStrategy = restartStrategy;
        this.repairStrategy = repairStrategy;
        this.hybridMode = hybridMode;
    }

    public int getControlPoints() { return controlPoints; }
    public void setControlPoints(int controlPoints) { this.controlPoints = controlPoints; }

    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }

    public InitStrategy getInitStrategy() { return initStrategy; }
    public void setInitStrategy(InitStrategy initStrategy) { this.initStrategy = initStrategy; }

    public RestartStrategy getRestartStrategy() { return restartStrategy; }
    public void setRestartStrategy(RestartStrategy restartStrategy) { this.restartStrategy = restartStrategy; }

    public RepairStrategy getRepairStrategy() { return repairStrategy; }
    public void setRepairStrategy(RepairStrategy repairStrategy) { this.repairStrategy = repairStrategy; }

    public boolean isHybridMode() { return hybridMode; }
    public void setHybridMode(boolean hybridMode) { this.hybridMode = hybridMode; }

    @Override
    public String toString() {
        return "CP=" + controlPoints + ", M=" + margin + ", Init=" + initStrategy + ", Restart=" + restartStrategy + ", Repair=" + repairStrategy + ", Hybrid=" + hybridMode;
    }
}
