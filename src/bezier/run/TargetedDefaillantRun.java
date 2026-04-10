package bezier.run;

import bezier.evaluation.Problem;
import bezier.evaluation.Solution;
import bezier.projects.competitor.optipath.OptiPath;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Lance OptiPath uniquement sur les problemes defaillants.
 */
public final class TargetedDefaillantRun
{
    private static final HashSet<String> TARGETS = new HashSet<> ();

    static
    {
        TARGETS.add ("prob9");
        TARGETS.add ("prob16");
        TARGETS.add ("prob17");
        TARGETS.add ("prob18");
        TARGETS.add ("prob20");
        TARGETS.add ("prob21");
    }

    private TargetedDefaillantRun () {}

    public static void main (String [] args)
    {
        ArrayList<Problem> problems = Problem.getProblems ();
        System.out.println ("=== Targeted OptiPath Run (defaillants) ===");
        for (Problem p : problems)
        {
            String name = p.getName ();
            if (!TARGETS.contains (name)) continue;

            p.reset ();
            Solution sol = Main.exec (OptiPath.class, p);
            double score = (sol != null)
                    ? sol.getEvaluation ()
                    : p.getBestEvaluation ();
            System.out.println (name + "," + score);
        }
        System.out.println ("=== End ===");
    }
}
