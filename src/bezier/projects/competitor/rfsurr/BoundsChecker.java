package bezier.projects.competitor.rfsurr;

public class BoundsChecker
{
    private final double [] lb;
    private final double [] ub;

    public BoundsChecker (double [] lb, double [] ub) { this.lb = lb.clone (); this.ub = ub.clone (); }

    public void clampInPlace (double [] x)
    {
        for (int i = 0; i < x.length; i++)
        {
            if (x [i] < lb [i]) x [i] = lb [i];
            if (x [i] > ub [i]) x [i] = ub [i];
        }
    }

    public double getRange (int i) { return ub [i] - lb [i]; }
    public double [] getLb () { return lb; }
    public double [] getUb () { return ub; }
}
