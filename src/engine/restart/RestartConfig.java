package engine.restart;

/**
 * Configuration retournée par une stratégie de restart.
 */
public class RestartConfig
{
    public final int lambda;
    public final double sigma;
    public final double [] mean;

    public RestartConfig (int lambda, double sigma, double [] mean)
    {
        this.lambda = lambda;
        this.sigma = sigma;
        this.mean = mean;
    }
}
