package bench.cli;

public class ListAlgosCommand implements Command {

    @Override
    public String getName() {
        return "list-algos";
    }

    @Override
    public String getDescription() {
        return "List all available algorithms.";
    }

    @Override
    public void execute(String[] args) throws Exception {
        System.out.println("Available Algorithms:");
        System.out.println("---------------------");
        for (String algo : BenchConfig.ALGO_NAMES) {
            System.out.println("- " + algo);
        }
    }
}
