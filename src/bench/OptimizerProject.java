package bench;

import engine.core.Optimizer;
import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

/**
 * Wrapper Project générique : encapsule n'importe quel Optimizer de la
 * nouvelle architecture (CMAESCore, DECore, GACore, etc.).
 *
 * Remplace les dizaines de classes OptiPath* quasi-identiques par une
 * seule classe paramétrable via un Supplier<Optimizer>.
 *
 * Utilisation :
 * <pre>
 *   Project p = new OptimizerProject(problem, "OptiPath (IPOP)",
 *       () -> CMAESBuilder.ipop(problem).build());
 * </pre>
 */
public class OptimizerProject extends CompetitorProject
{
    private final OptimizerFactory factory;
    private Optimizer optimizer;

    @FunctionalInterface
    public interface OptimizerFactory
    {
        Optimizer create ();
    }

    public OptimizerProject (Problem problem, String methodName,
                             OptimizerFactory factory)
            throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName (methodName);
        this.factory = factory;
    }

    @Override
    public void initialization ()
    {
        optimizer = factory.create ();
        optimizer.init ();
    }

    @Override
    public void loop ()
    {
        optimizer.step ();
    }

    /** Expose l'optimiseur pour le monitoring GUI et les benchmarks. */
    public Optimizer getOptimizer () { return optimizer; }
}
