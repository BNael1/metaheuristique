package bezier2_0.projects.competitor.optipath;

/**
 * Représentation d'un individu (solution) : vecteur de points de contrôle + fitness.
 * Le vecteur x est un double[] plat : [x0, y0, x1, y1, ...].
 */
public class Individual implements Comparable<Individual>
{
    private double [] x;
    private double fitness;

    public Individual (double [] x, double fitness)
    {
        this.x = x.clone ();
        this.fitness = fitness;
    }

    public Individual (int d)
    {
        this.x = new double [d];
        this.fitness = Double.POSITIVE_INFINITY;
    }

    public double [] getX ()               { return x; }
    public double getX (int i)             { return x [i]; }
    public void setX (int i, double val)   { x [i] = val; }
    public void setX (double [] x)         { this.x = x.clone (); }
    public double getFitness ()            { return fitness; }
    public void setFitness (double f)      { this.fitness = f; }

    public Individual copy ()
    {
        return new Individual (x, fitness);
    }

    @Override
    public int compareTo (Individual other)
    {
        return Double.compare (this.fitness, other.fitness);
    }
}
