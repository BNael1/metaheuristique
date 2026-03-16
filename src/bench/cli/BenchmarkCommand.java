package bench.cli;

import bench.cli.runner.AlgorithmFactory;
import bench.cli.runner.AlgorithmRunner;
import bezier.evaluation.Problem;
import engine.core.Optimizer;
import engine.core.OptimizerState;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkCommand implements Command {

    @Override
    public String getName() {
        return "benchmark";
    }

    @Override
    public String getDescription() {
        return "Run a full benchmark with multiple algorithms and problems.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        String problemPattern = "*";
        String algoPattern = "*";
        int timeLimit = 60;
        int repeats = 3;
        String outputFile = "benchmark_results.csv";
        boolean parallel = false;
        int threads = Runtime.getRuntime().availableProcessors();

        String type = "custom"; // custom, comparison, init, bounds

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--type":
                    if (i + 1 < args.length) type = args[++i];
                    break;
                case "--problems":
                case "-p":
                    if (i + 1 < args.length) problemPattern = args[++i];
                    break;
                case "--algos":
                case "-a":
                    if (i + 1 < args.length) algoPattern = args[++i];
                    break;
                case "--time":
                case "-t":
                    if (i + 1 < args.length) timeLimit = Integer.parseInt(args[++i]);
                    break;
                case "--repeats":
                case "-r":
                    if (i + 1 < args.length) repeats = Integer.parseInt(args[++i]);
                    break;
                case "--output":
                case "-o":
                    if (i + 1 < args.length) outputFile = args[++i];
                    break;
                case "--parallel":
                    parallel = true;
                    break;
                case "--threads":
                    if (i + 1 < args.length) threads = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    return;
            }
        }

        Problem.headless = true;

        if (!type.equals("custom")) {
            runPredefinedBenchmark(type, args);
            return;
        }

        // Filter problems
        List<Problem> allProblems = Problem.getProblems();
        List<String> problemNames = new ArrayList<>();
        PathMatcher probMatcher = FileSystems.getDefault().getPathMatcher("glob:" + problemPattern);
        
        for (Problem p : allProblems) {
            if (problemPattern.equals("*") || probMatcher.matches(Paths.get(p.getName()))) {
                problemNames.add(p.getName());
            }
        }

        if (problemNames.isEmpty()) {
            System.err.println("No problems found matching pattern: " + problemPattern);
            return;
        }

        // Filter algorithms
        List<String> algoNames = new ArrayList<>();
        PathMatcher algoMatcher = FileSystems.getDefault().getPathMatcher("glob:" + algoPattern);
        
        for (String name : BenchConfig.ALGO_NAMES) {
            if (algoPattern.equals("*") || algoMatcher.matches(Paths.get(name))) {
                algoNames.add(name);
            }
        }

        if (algoNames.isEmpty()) {
            System.err.println("No algorithms found matching pattern: " + algoPattern);
            return;
        }

        System.out.println("Benchmark Configuration:");
        System.out.println("------------------------");
        System.out.println("Problems (" + problemNames.size() + "): " + problemNames);
        System.out.println("Algorithms (" + algoNames.size() + "): " + algoNames);
        System.out.println("Time Limit: " + timeLimit + "s");
        System.out.println("Repeats: " + repeats);
        System.out.println("Parallel: " + parallel + (parallel ? " (" + threads + " threads)" : ""));
        System.out.println("Output: " + outputFile);
        System.out.println("------------------------");

        final String finalOutputFile = outputFile;

        // Initialize CSV
        File csvFile = new File(outputFile);
        boolean fileExists = csvFile.exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile, true))) {
            if (!fileExists) {
                pw.println("Algorithm,Problem,Run,Time,Evaluations,BestFitness");
            }
        }

        int totalTasks = problemNames.size() * algoNames.size() * repeats;
        AtomicInteger completedTasks = new AtomicInteger(0);
        final long startTimeFinal = System.currentTimeMillis();

        ExecutorService executor = parallel ? Executors.newFixedThreadPool(threads) : Executors.newSingleThreadExecutor();

        for (String pName : problemNames) {
            for (String aName : algoNames) {
                for (int r = 1; r <= repeats; r++) {
                    final int runId = r;
                    final String currentProblem = pName;
                    final String currentAlgo = aName;
                    final int limit = timeLimit;

                    executor.submit(() -> {
                        try {
                            // Load a fresh problem instance for thread safety
                            Problem problem = loadProblem(currentProblem);
                            if (problem == null) {
                                System.err.println("Failed to load problem: " + currentProblem);
                                return;
                            }
                            problem.reset();

                            Optimizer optimizer = AlgorithmFactory.create(currentAlgo, problem, 0.0);
                            optimizer.init();

                            long startRun = System.currentTimeMillis();
                            long limitMs = limit * 1000L;
                            
                            while (System.currentTimeMillis() - startRun < limitMs) {
                                optimizer.step();
                            }

                            OptimizerState state = optimizer.getState();
                            
                            synchronized (BenchmarkCommand.class) {
                                try (PrintWriter pw = new PrintWriter(new FileWriter(finalOutputFile, true))) {
                                    pw.printf("%s,%s,%d,%d,%d,%.6f%n", 
                                        currentAlgo, currentProblem, runId, limit, state.evaluations, state.bestFitness);
                                }
                            }

                            int completed = completedTasks.incrementAndGet();
                            printProgress(completed, totalTasks, startTimeFinal);

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("\nBenchmark completed. Results saved to " + outputFile);
    }

    private void runPredefinedBenchmark(String type, String[] args) throws Exception {
        System.out.println("Running predefined benchmark: " + type);
        System.out.println("----------------------------------------");
        
        switch (type) {
            case "comparison":
                bench.BenchmarkComparison.main(new String[]{});
                break;
            case "init":
                bench.BenchmarkInitStrategies.main(new String[]{});
                break;
            case "bounds":
                bench.BenchmarkExtendedBounds.main(new String[]{});
                break;
            default:
                System.err.println("Unknown benchmark type: " + type);
        }
    }

    private Problem loadProblem(String name) {
        ArrayList<Problem> all = Problem.getProblems();
        for (Problem p : all) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    private void printProgress(int completed, int total, long startTime) {
        int width = 40;
        int progress = (completed * width) / total;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < progress) bar.append("=");
            else if (i == progress) bar.append(">");
            else bar.append(" ");
        }
        bar.append("]");
        
        long elapsed = System.currentTimeMillis() - startTime;
        double avgTimePerTask = (double) elapsed / completed;
        long remaining = (long) (avgTimePerTask * (total - completed));
        
        String remainingStr = String.format("%02d:%02d:%02d", 
            TimeUnit.MILLISECONDS.toHours(remaining),
            TimeUnit.MILLISECONDS.toMinutes(remaining) % 60,
            TimeUnit.MILLISECONDS.toSeconds(remaining) % 60);

        System.out.print("\r" + bar + String.format(" %d/%d | ETA: %s", completed, total, remainingStr));
    }
}
