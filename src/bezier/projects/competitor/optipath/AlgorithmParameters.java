package bezier.projects.competitor.optipath;


/**
 * Hyperparamètres utilisés par OptiPathFinal pour l'initialisation CMA-ES.
 */
public class AlgorithmParameters {

    public enum InitStrategy { RANDOM, ASTAR_PATH, CENTER_LINE }

    private double margin;
    private InitStrategy initStrategy;

    public AlgorithmParameters() {
        this.margin = 1.0;
        this.initStrategy = InitStrategy.RANDOM;
    }

    public AlgorithmParameters(double margin, InitStrategy initStrategy) {
        this.margin = margin;
        this.initStrategy = initStrategy;
    }

    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }

    public InitStrategy getInitStrategy() { return initStrategy; }
    public void setInitStrategy(InitStrategy initStrategy) { this.initStrategy = initStrategy; }

    @Override
    public String toString() {
        return "M=" + margin + ", Init=" + initStrategy;
    }
}
