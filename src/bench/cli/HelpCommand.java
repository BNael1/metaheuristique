package bench.cli;

import java.util.Map;

public class HelpCommand implements Command {

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "Show this help message.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        System.out.println("Available Commands:");
        System.out.println("-------------------");
        
        Map<String, Command> commands = CLIApp.getCommands();
        for (Command cmd : commands.values()) {
            System.out.printf("%-15s %s%n", cmd.getName(), cmd.getDescription());
        }
    }
}
