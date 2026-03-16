package bench.cli;

import bench.cli.ui.Terminal;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class StatsCommand implements Command {

    @Override
    public String getName() {
        return "stats";
    }

    @Override
    public String getDescription() {
        return "Analyze benchmark results (CSV) and show statistics.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        String type = "comparison"; // default
        if (args.length > 0) {
            type = args[0];
        }

        String csvPath = "results/bench_results.csv";
        if (type.equals("comparison")) {
            csvPath = "results/bench_results.csv";
        } else if (type.equals("init")) {
            csvPath = "results/bench_InitStrategies.csv";
        } else if (type.equals("bounds")) {
            csvPath = "results/bench_ExtendedBounds.csv";
        } else {
            // Assume it's a direct path
            csvPath = type;
        }

        File file = new File(csvPath);
        if (!file.exists()) {
            System.err.println("Error: Results file not found at " + csvPath);
            return;
        }

        // Data structure: Algorithm -> Problem -> List<Score>
        Map<String, Map<String, List<Double>>> data = new TreeMap<>();
        List<String> problems = new ArrayList<>();

        // Load Data
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // Header
            if (line == null) return;

            String[] header = line.split(",");
            boolean isExtended = header.length >= 6 && header[5].trim().equalsIgnoreCase("BestFitness");
            int scoreIdx = isExtended ? 5 : 3;

            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length <= scoreIdx) continue;

                String algo = parts[0].trim();
                String prob = parts[1].trim();
                double score = 0;
                try {
                    score = Double.parseDouble(parts[scoreIdx].trim());
                } catch (NumberFormatException e) { continue; }

                data.putIfAbsent(algo, new TreeMap<>());
                data.get(algo).putIfAbsent(prob, new ArrayList<>());
                data.get(algo).get(prob).add(score);

                if (!problems.contains(prob)) {
                    problems.add(prob);
                }
            }
        }

        // --- RANKING SYSTEM ---
        // 1. Calculate average scores per (algo, problem)
        // 2. Normalize scores per problem (best = 1.0)
        // 3. Compute global score
        
        List<AlgoStats> ranking = new ArrayList<>();
        
        for (String algo : data.keySet()) {
            AlgoStats stats = new AlgoStats(algo);
            
            for (String prob : problems) {
                List<Double> scores = data.get(algo).get(prob);
                if (scores != null && !scores.isEmpty()) {
                    double sum = 0;
                    for (double s : scores) sum += s;
                    double mean = sum / scores.size();
                    stats.problemScores.put(prob, mean);
                }
            }
            ranking.add(stats);
        }

        // Compute relative performance (1.0 = best for a problem)
        for (String prob : problems) {
            double bestMean = Double.MAX_VALUE;
            // Find best mean for this problem
            for (AlgoStats stats : ranking) {
                if (stats.problemScores.containsKey(prob)) {
                    bestMean = Math.min(bestMean, stats.problemScores.get(prob));
                }
            }
            
            // Assign points
            for (AlgoStats stats : ranking) {
                if (stats.problemScores.containsKey(prob)) {
                    double mean = stats.problemScores.get(prob);
                    // Ratio: best / current (since lower is better)
                    // If best is 100 and current is 200, ratio is 0.5
                    double ratio = (mean == 0) ? 1.0 : (bestMean / mean);
                    if (Double.isNaN(ratio)) ratio = 0.0;
                    stats.totalScore += ratio;
                    stats.solvedCount++;
                }
            }
        }

        // Sort by Total Score (Higher is better)
        ranking.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));

        // --- DISPLAY ---
        Terminal.clearScreen();
        Terminal.printHeader("BENCHMARK ANALYSIS");
        System.out.println("Source: " + csvPath);
        System.out.println("Metric: Normalized Score (1.0 = Best possible performance on all problems)");
        System.out.println("");

        // Print Ranking Table
        System.out.printf("%-4s | %-30s | %-10s | %-10s | %s%n", "Rank", "Algorithm", "Score", "Solved", "Performance");
        System.out.println("--------------------------------------------------------------------------------");

        int maxScore = problems.size();
        
        for (int i = 0; i < ranking.size(); i++) {
            AlgoStats s = ranking.get(i);
            int barLength = 20;
            int filled = (int) ((s.totalScore / maxScore) * barLength);
            String bar = "[";
            for (int k = 0; k < barLength; k++) {
                if (k < filled) bar += "=";
                else bar += " ";
            }
            bar += "]";

            String color = Terminal.RESET;
            if (i == 0) color = Terminal.GREEN;
            else if (i == 1) color = Terminal.CYAN;
            else if (i == 2) color = Terminal.YELLOW;

            System.out.printf("%-4d | %s%-30s%s | %-10.2f | %-10d | %s%n", 
                i + 1, 
                color, s.name, Terminal.RESET,
                s.totalScore, 
                s.solvedCount,
                bar
            );
        }
        
        System.out.println("");
        System.out.println("Detailed Problem Breakdown:");
        System.out.println("---------------------------");
        
        // Paged view for details
        int pageSize = 10;
        int currentProb = 0;
        
        // Flatten details for simple list
        for (String prob : problems) {
            System.out.println(Terminal.BOLD + "Problem: " + prob + Terminal.RESET);
            List<ProbResult> results = new ArrayList<>();
            
            for (String algo : data.keySet()) {
                List<Double> scores = data.get(algo).get(prob);
                if (scores != null && !scores.isEmpty()) {
                    double sum = 0, sumSq = 0;
                    for (double v : scores) { sum += v; sumSq += v*v; }
                    double mean = sum / scores.size();
                    double std = Math.sqrt((sumSq - sum*sum/scores.size())/scores.size());
                    results.add(new ProbResult(algo, mean, std));
                }
            }
            
            results.sort(Comparator.comparingDouble(r -> r.mean));
            
            for (int k = 0; k < Math.min(5, results.size()); k++) {
                ProbResult r = results.get(k);
                System.out.printf("  %d. %-25s : %.4f (±%.4f)%n", k+1, r.name, r.mean, r.std);
            }
            if (results.size() > 5) {
                System.out.println("  ... (" + (results.size() - 5) + " others)");
            }
            System.out.println("");
        }
    }

    private static class AlgoStats {
        String name;
        Map<String, Double> problemScores = new HashMap<>();
        double totalScore = 0;
        int solvedCount = 0;

        AlgoStats(String name) { this.name = name; }
    }
    
    private static class ProbResult {
        String name;
        double mean;
        double std;
        ProbResult(String n, double m, double s) { name = n; mean = m; std = s; }
    }
}
