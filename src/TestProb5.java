import bezier.evaluation.Problem;
import bench.ProjectCatalog;
import bezier.projects.Project;

public class TestProb5 {
    public static void main(String[] args) throws Exception {
        Problem.headless = true;
        Problem p = Problem.getProblems().stream().filter(x -> x.getName().contains("prob5")).findFirst().get();
        Project proj = ProjectCatalog.hybrid(p);
        proj.run();
        System.out.println("Best fit: " + p.getBestEvaluation());
    }
}
