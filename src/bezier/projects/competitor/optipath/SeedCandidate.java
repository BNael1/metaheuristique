package bezier.projects.competitor.optipath;

/**
 * Candidat seed evalue, avec sa famille d'origine.
 */
public final class SeedCandidate
{
    public final SeedFamily family;
    public final double [] vector;
    public final double fitness;

    public SeedCandidate (SeedFamily family, double [] vector, double fitness)
    {
        this.family = family;
        this.vector = vector;
        this.fitness = fitness;
    }
}
