package bench.cli;

import bezier.evaluation.Problem;
import java.io.File;
import java.util.ArrayList;

public class ListProblemsCommand implements Command {

    @Override
    public String getName() {
        return "list-problems";
    }

    @Override
    public String getDescription() {
        return "List all available problems (.bzr files) in the data directory.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        File dataDir = new File("data");
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            System.err.println("Error: 'data' directory not found.");
            return;
        }

        File[] files = dataDir.listFiles((d, n) -> n.endsWith(".bzr"));
        if (files == null || files.length == 0) {
            System.out.println("No .bzr files found in 'data' directory.");
            return;
        }

        System.out.println("Available Problems:");
        System.out.println("-------------------");
        
        // Try to load problem details
        ArrayList<Problem> allProblems = Problem.getProblems();
        
        for (File f : files) {
            String name = f.getName().replace(".bzr", "");
            Problem p = null;
            for (Problem prob : allProblems) {
                if (prob.getName().equals(name)) {
                    p = prob;
                    break;
                }
            }

            if (p != null) {
                System.out.printf("%-20s | %d CPs | Dim: %d | %d Obstacles%n", 
                    name, 
                    p.getNControlPoints(), 
                    2 * p.getNControlPoints(), 
                    p.getNObstacles());
            } else {
                System.out.printf("%-20s (Could not load details)%n", name);
            }
        }
    }
}
