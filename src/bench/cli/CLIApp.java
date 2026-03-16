package bench.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import bezier.evaluation.Problem;

public class CLIApp {
    
    private static final Map<String, Command> commands = new HashMap<>();

    static {
        register(new ListProblemsCommand());
        register(new ListAlgosCommand());
        register(new RunCommand());
        register(new BenchmarkCommand());
        register(new StatsCommand());
        register(new HelpCommand());
    }

    private static void register(Command cmd) {
        commands.put(cmd.getName(), cmd);
    }

    public static void main(String[] args) {
        // Force headless mode for CLI
        Problem.headless = true;

        if (args.length == 0) {
            // Launch Interactive Mode
            new InteractiveSession().start();
            return;
        }

        String cmdName = args[0];
        Command cmd = commands.get(cmdName);

        if (cmd == null) {
            System.err.println("Unknown command: " + cmdName);
            System.err.println("Use 'help' to see available commands.");
            System.exit(1);
        }

        try {
            String[] cmdArgs = Arrays.copyOfRange(args, 1, args.length);
            cmd.execute(cmdArgs);
        } catch (Exception e) {
            System.err.println("Error executing command '" + cmdName + "': " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static Map<String, Command> getCommands() {
        return commands;
    }
}
