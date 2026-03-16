package bench.cli;

import bench.cli.runner.AlgorithmFactory;
import bench.cli.runner.AlgorithmRunner;
import bezier.evaluation.Problem;
import engine.core.Optimizer;
import engine.core.OptimizerState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunCommand implements Command {

    @Override
    public String getName() {
        return "run";
    }

    @Override
    public String getDescription() {
        return "Run a specific algorithm on a problem.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        String problemName = null;
        String algoName = "OptiPath Final";
        int timeLimit = 60;
        double margin = 0.0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--problem":
                case "-p":
                    if (i + 1 < args.length) problemName = args[++i];
                    break;
                case "--algo":
                case "-a":
                    if (i + 1 < args.length) algoName = args[++i];
                    break;
                case "--time":
                case "-t":
                    if (i + 1 < args.length) timeLimit = Integer.parseInt(args[++i]);
                    break;
                case "--margin":
                case "-m":
                    if (i + 1 < args.length) margin = Double.parseDouble(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    return;
            }
        }

        if (problemName == null) {
            System.err.println("Error: Problem name is required (--problem <name>)");
            return;
        }

        // Validate algorithm
        boolean validAlgo = false;
        for (String name : BenchConfig.ALGO_NAMES) {
            if (name.equals(algoName)) {
                validAlgo = true;
                break;
            }
        }
        if (!validAlgo) {
            System.err.println("Error: Unknown algorithm '" + algoName + "'");
            return;
        }

        // Load problem
        Problem problem = null;
        ArrayList<Problem> allProblems = Problem.getProblems();
        for (Problem p : allProblems) {
            if (p.getName().equals(problemName) || p.getName().equals(problemName.replace(".bzr", ""))) {
                problem = p;
                break;
            }
        }

        if (problem == null) {
            System.err.println("Error: Problem '" + problemName + "' not found.");
            return;
        }
        
        problem.reset();

        System.out.println("Starting execution...");
        System.out.println("Problem: " + problem.getName());
        System.out.println("Algorithm: " + algoName);
        System.out.println("Time Limit: " + timeLimit + "s");
        System.out.println("Margin: " + margin);
        System.out.println("--------------------------------------------------");

        Optimizer optimizer = AlgorithmFactory.create(algoName, problem, margin);
        AlgorithmRunner runner = new AlgorithmRunner(algoName, optimizer, timeLimit * 1000L);

        final long totalTime = timeLimit * 1000L;
        
        runner.addListener(new AlgorithmRunner.StateListener() {
            @Override
            public void onStateUpdate(String name, OptimizerState state, long elapsedMs) {
                printProgress(elapsedMs, totalTime, state);
            }

            @Override
            public void onFinished(String name, OptimizerState finalState, long elapsedMs) {
                printProgress(elapsedMs, totalTime, finalState);
                System.out.println();
                System.out.println("Execution finished.");
                System.out.println("Best Fitness: " + finalState.bestFitness);
                System.out.println("Evaluations: " + finalState.evaluations);
            }
        });

        runner.start();

        // Wait for completion
        while (runner.isRunning()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    private void printProgress(long elapsed, long total, OptimizerState state) {
        int width = 30;
        int progress = (int) ((elapsed * width) / total);
        if (progress > width) progress = width;
        
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < progress) bar.append("=");
            else if (i == progress) bar.append(">");
            else bar.append(" ");
        }
        bar.append("]");

        double percent = (double) elapsed / total * 100;
        String fitness = state != null ? String.format("%.6f", state.bestFitness) : "N/A";
        
        System.out.print("\r" + bar + String.format(" %.1f%% | Fit: %s | Time: %.1fs", percent, fitness, elapsed / 1000.0));
    }
}
