package bezier.run;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.evaluation.BezierChart;
import bezier.evaluation.MonitorChart;
import bezier.projects.competitor.lmcma.LMCMAProject;
import bezier.projects.competitor.astarseeds.AStarSeedsProject;
import bezier.projects.competitor.nbipop.NBIPOPProject;
import bezier.projects.competitor.alconst.ALConstraintProject;
import bezier.projects.competitor.rfsurr.RFSurrogateProject;
import bezier.projects.competitor.islands.IslandProject;
import java.util.ArrayList;

/**
 * Test rapide des 6 nouvelles métaheuristiques.
 * Usage : java -cp "lib/*;bin" bezier.run.QuickTest [seconds]
 */
public class QuickTest
{
    public static void main (String [] args) throws Exception
    {
        int seconds = 5;
        if (args.length > 0) seconds = Integer.parseInt (args [0]);

        String [] names = {"LMCMA", "AStarSeeds", "NBIPOP", "ALConstraint", "RFSurrogate", "Islands"};

        ArrayList<Problem> problems = Problem.getProblems ();

        for (String algoName : names)
        {
            for (Problem problem : problems)
            {
                problem.reset ();
                MonitorChart.getNewInstance (algoName + " " + problem.getName ());
                BezierChart.getNewInstance (problem);
                System.err.print (algoName + " / " + problem.getName () + " (" + seconds + "s) ... ");
                try
                {
                    CompetitorProject project = createProject (algoName, problem);
                    project.initialization ();
                    long deadline = System.currentTimeMillis () + seconds * 1000L;
                    while (System.currentTimeMillis () < deadline)
                    {
                        project.loop ();
                    }
                    double score = problem.getBestEvaluation ();
                    System.err.println ("score = " + String.format ("%.4f", score));
                }
                catch (Exception ex)
                {
                    System.err.println ("ERREUR : " + ex.getClass ().getSimpleName () + " : " + ex.getMessage ());
                    ex.printStackTrace (System.err);
                }
            }
        }
        System.err.println ("=== Quick test done ===");
    }

    private static CompetitorProject createProject (String algo, Problem problem) throws Exception
    {
        switch (algo)
        {
            case "LMCMA":        return new LMCMAProject          (problem);
            case "AStarSeeds":   return new AStarSeedsProject     (problem);
            case "NBIPOP":       return new NBIPOPProject          (problem);
            case "ALConstraint": return new ALConstraintProject    (problem);
            case "RFSurrogate":  return new RFSurrogateProject     (problem);
            case "Islands":      return new IslandProject          (problem);
            default:             throw new Exception ("Unknown: " + algo);
        }
    }
}
