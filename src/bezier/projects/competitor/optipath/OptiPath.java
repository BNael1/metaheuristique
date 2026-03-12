package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import engine.core.Optimizer;
import engine.cmaes.CMAESBuilder;

/**
 * Classe principale du projet — étend CompetitorProject.
 *
 * Algorithme retenu : IPOP-CMA-ES (Covariance Matrix Adaptation Evolution Strategy
 * avec restarts à population croissante). Sélectionné après comparaison avec DE et GA.
 */
public class OptiPath extends CompetitorProject
{
    private Optimizer optimizer;

    public OptiPath (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("OptiPath (IPOP-CMA-ES)");
    }

    // ================================================================
    //  initialization() — appelée UNE SEULE FOIS au début des 60 s
    // ================================================================
    @Override
    public void initialization ()
    {
        optimizer = CMAESBuilder.ipop(problem).build();
        optimizer.init();
    }

    // ================================================================
    //  loop() — rappelée en boucle pendant le reste des 60 s
    // ================================================================
    @Override
    public void loop ()
    {
        optimizer.step ();
    }

    public Optimizer getOptimizer () { return optimizer; }
}
