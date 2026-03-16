package bench.cli;

import bench.cli.ui.Terminal;
import bezier.evaluation.Problem;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class InteractiveSession {

    private final Scanner scanner;
    private Problem currentProblem;
    private String currentAlgo;
    private int timeLimit = 30;
    private int repeats = 1;
    private double margin = 0.0;
    
    private boolean running = true;

    public InteractiveSession() {
        this.scanner = new Scanner(System.in);
        // Default algo
        this.currentAlgo = "OptiPath Final";
        // Try load first problem
        try {
            ArrayList<Problem> probs = Problem.getProblems();
            if (!probs.isEmpty()) this.currentProblem = probs.get(0);
        } catch (Exception e) {
            // ignore
        }
    }

    public void start() {
        Terminal.enterAlternateScreen();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Terminal.exitAlternateScreen();
            Terminal.showCursor();
        }));

        while (running) {
            showMainMenu();
        }
        
        Terminal.exitAlternateScreen();
        Terminal.showCursor();
        System.out.println("Goodbye!");
    }

    private void showMainMenu() {
        Terminal.clearScreen();
        
        // --- HEADER ---
        System.out.println(Terminal.BOLD + Terminal.BLUE + "================================================================================" + Terminal.RESET);
        System.out.println(Terminal.BOLD + Terminal.BLUE + "   OPTIPATH CLI DASHBOARD v1.0" + Terminal.RESET);
        System.out.println(Terminal.BOLD + Terminal.BLUE + "================================================================================" + Terminal.RESET);
        System.out.println("");

        // --- DASHBOARD ---
        System.out.println(Terminal.BOLD + "CURRENT CONFIGURATION:" + Terminal.RESET);
        System.out.println("----------------------");
        System.out.printf("Problem   : %s%n", (currentProblem != null ? Terminal.colorize(currentProblem.getName(), Terminal.GREEN) : Terminal.colorize("None", Terminal.RED)));
        System.out.printf("Algorithm : %s%n", (currentAlgo != null ? Terminal.colorize(currentAlgo, Terminal.GREEN) : Terminal.colorize("None", Terminal.RED)));
        System.out.printf("Time Limit: %s seconds%n", Terminal.colorize(String.valueOf(timeLimit), Terminal.YELLOW));
        System.out.printf("Margin    : %s%n", Terminal.colorize(String.valueOf(margin), Terminal.YELLOW));
        System.out.println("");

        // --- MENU ---
        System.out.println(Terminal.BOLD + "ACTIONS:" + Terminal.RESET);
        System.out.println("  " + Terminal.BOLD + "1" + Terminal.RESET + ". Select Problem");
        System.out.println("  " + Terminal.BOLD + "2" + Terminal.RESET + ". Select Algorithm");
        System.out.println("  " + Terminal.BOLD + "3" + Terminal.RESET + ". Configure Parameters (Time, Margin)");
        System.out.println("  " + Terminal.BOLD + "4" + Terminal.RESET + ". " + Terminal.GREEN + "RUN Single Execution" + Terminal.RESET + " (Live View)");
        System.out.println("  " + Terminal.BOLD + "5" + Terminal.RESET + ". " + Terminal.PURPLE + "BENCHMARK Campaign" + Terminal.RESET + " (Batch)");
        System.out.println("  " + Terminal.BOLD + "6" + Terminal.RESET + ". " + Terminal.CYAN + "VIEW STATS" + Terminal.RESET + " (Analysis)");
        System.out.println("  " + Terminal.BOLD + "0" + Terminal.RESET + ". Exit");
        System.out.println("");
        System.out.print(Terminal.BOLD + "> Enter choice: " + Terminal.RESET);

        String choice = scanner.nextLine().trim();
        handleMenuChoice(choice);
    }

    private void handleMenuChoice(String choice) {
        try {
            switch (choice) {
                case "1":
                    menuSelectProblem();
                    break;
                case "2":
                    menuSelectAlgo();
                    break;
                case "3":
                    menuConfigure();
                    break;
                case "4":
                    runSingle();
                    break;
                case "5":
                    runBenchmark();
                    break;
                case "6":
                    runStats();
                    break;
                case "0":
                    running = false;
                    break;
                default:
                    // Invalid choice, loop will refresh
                    break;
            }
        } catch (Exception e) {
            Terminal.printError(e.getMessage());
            pressEnterToContinue();
        }
    }

    private void menuSelectProblem() {
        Terminal.clearScreen();
        Terminal.printHeader("Select Problem");
        
        ArrayList<Problem> problems = Problem.getProblems();
        if (problems.isEmpty()) {
            Terminal.printError("No problems found in data/ directory.");
            pressEnterToContinue();
            return;
        }

        for (int i = 0; i < problems.size(); i++) {
            Problem p = problems.get(i);
            System.out.printf("  %s%2d%s. %-20s (CPs: %d | Obs: %d)%n", 
                Terminal.BOLD, i + 1, Terminal.RESET, 
                p.getName(), p.getNControlPoints(), p.getNObstacles());
        }
        System.out.println("");
        System.out.print("Enter number (0 to cancel): ");
        
        try {
            String input = scanner.nextLine().trim();
            int idx = Integer.parseInt(input);
            if (idx > 0 && idx <= problems.size()) {
                this.currentProblem = problems.get(idx - 1);
            }
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    private void menuSelectAlgo() {
        Terminal.clearScreen();
        Terminal.printHeader("Select Algorithm");

        String[] algos = BenchConfig.ALGO_NAMES;
        for (int i = 0; i < algos.length; i++) {
            System.out.printf("  %s%2d%s. %s%n", Terminal.BOLD, i + 1, Terminal.RESET, algos[i]);
        }
        System.out.println("");
        System.out.print("Enter number (0 to cancel): ");

        try {
            String input = scanner.nextLine().trim();
            int idx = Integer.parseInt(input);
            if (idx > 0 && idx <= algos.length) {
                this.currentAlgo = algos[idx - 1];
            }
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    private void menuConfigure() {
        Terminal.clearScreen();
        Terminal.printHeader("Configuration");
        
        System.out.println("Current Time Limit: " + timeLimit + "s");
        System.out.println("Current Margin    : " + margin);
        System.out.println("");
        
        System.out.print("New Time Limit (s) [Enter to keep]: ");
        String tStr = scanner.nextLine().trim();
        if (!tStr.isEmpty()) {
            try {
                int t = Integer.parseInt(tStr);
                if (t > 0) this.timeLimit = t;
            } catch (Exception e) { Terminal.printError("Invalid number"); }
        }

        System.out.print("New Margin [Enter to keep]: ");
        String mStr = scanner.nextLine().trim();
        if (!mStr.isEmpty()) {
            try {
                double m = Double.parseDouble(mStr);
                if (m >= 0) this.margin = m;
            } catch (Exception e) { Terminal.printError("Invalid number"); }
        }
    }

    private void runSingle() {
        try {
            if (currentProblem == null) {
                Terminal.printError("Please select a problem first.");
                pressEnterToContinue();
                return;
            }
            
            Terminal.clearScreen();
            // Construct args for the existing command
            String[] args = {
                "--problem", currentProblem.getName(),
                "--algo", currentAlgo,
                "--time", String.valueOf(timeLimit),
                "--margin", String.valueOf(margin)
            };
            
            new RunCommand().execute(args);
        } catch (Exception e) {
            Terminal.printError(e.getMessage());
        }
        
        System.out.println("");
        pressEnterToContinue();
    }

    private void runStats() {
        try {
            Terminal.clearScreen();
            Terminal.printHeader("View Benchmark Stats");
            
            System.out.println("Select stats type:");
            System.out.println("  1. Comparison Results (bench_results.csv)");
            System.out.println("  2. Init Strategies (bench_InitStrategies.csv)");
            System.out.println("  3. Extended Bounds (bench_ExtendedBounds.csv)");
            System.out.println("");
            System.out.print("Choice (default 1): ");
            
            String choice = scanner.nextLine().trim();
            String type = "comparison";
            
            if (choice.equals("2")) type = "init";
            else if (choice.equals("3")) type = "bounds";
            
            Terminal.clearScreen();
            new StatsCommand().execute(new String[]{type});
        } catch (Exception e) {
            Terminal.printError(e.getMessage());
        }
        
        System.out.println("");
        pressEnterToContinue();
    }

    private void runBenchmark() {
        try {
            Terminal.clearScreen();
            Terminal.printHeader("Benchmark Campaign");
            
            System.out.println("Select benchmark type:");
            System.out.println("  1. Custom Benchmark (Select patterns, algos, etc.)");
            System.out.println("  2. Standard Comparison (BenchmarkComparison)");
            System.out.println("  3. Init Strategies (BenchmarkInitStrategies)");
            System.out.println("  4. Extended Bounds (BenchmarkExtendedBounds)");
            System.out.println("");
            System.out.print("Choice (default 1): ");
            
            String choice = scanner.nextLine().trim();
            if (choice.isEmpty()) choice = "1";
            
            if (choice.equals("2")) {
                new BenchmarkCommand().execute(new String[]{"--type", "comparison"});
                pressEnterToContinue();
                return;
            } else if (choice.equals("3")) {
                new BenchmarkCommand().execute(new String[]{"--type", "init"});
                pressEnterToContinue();
                return;
            } else if (choice.equals("4")) {
                new BenchmarkCommand().execute(new String[]{"--type", "bounds"});
                pressEnterToContinue();
                return;
            }

            System.out.println("");
            System.out.println("This will run a custom benchmark.");
            System.out.print("Problem Pattern (glob, default '*'): ");
            String pPat = scanner.nextLine().trim();
            if (pPat.isEmpty()) pPat = "*";

            System.out.print("Algorithm Pattern (glob, default '*'): ");
            String aPat = scanner.nextLine().trim();
            if (aPat.isEmpty()) aPat = "*";
            
            System.out.print("Repeats per config (default 3): ");
            String rStr = scanner.nextLine().trim();
            int r = 3;
            if (!rStr.isEmpty()) {
                try { r = Integer.parseInt(rStr); } catch (Exception e) {}
            }

            System.out.print("Run in Parallel? (y/N): ");
            String par = scanner.nextLine().trim();
            boolean isParallel = par.equalsIgnoreCase("y");
            
            System.out.print("Time Limit (s) (default " + timeLimit + "): ");
            String tStr = scanner.nextLine().trim();
            int t = timeLimit;
            if (!tStr.isEmpty()) {
                try { t = Integer.parseInt(tStr); } catch (Exception e) {}
            }


            List<String> argList = new ArrayList<>();
            argList.add("--problems"); argList.add(pPat);
            argList.add("--algos"); argList.add(aPat);
            argList.add("--time"); argList.add(String.valueOf(t));
            argList.add("--repeats"); argList.add(String.valueOf(r));
            if (isParallel) argList.add("--parallel");
            
            new BenchmarkCommand().execute(argList.toArray(new String[0]));
        } catch (Exception e) {
            Terminal.printError(e.getMessage());
        }
        
        System.out.println("");
        pressEnterToContinue();
    }

    private void pressEnterToContinue() {
        System.out.println(Terminal.DIM + "Press Enter to continue..." + Terminal.RESET);
        // Clear buffer before waiting
        if (scanner.hasNextLine()) {
             // scanner.nextLine(); 
        }
        scanner.nextLine();
    }
}
