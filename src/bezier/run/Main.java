package bezier.run;

import java.awt.Font;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.reflections.Reflections;

import bezier.evaluation.BezierChart;
import bezier.evaluation.MonitorChart;
import bezier.evaluation.Problem;
import bezier.evaluation.Solution;
import bezier.output.LogFileOutput;
import bezier.output.OutputWriter;
import bezier.output.StandardOutput;
import bezier.projects.CompetitorProject;
import bezier.projects.Project;
import javassist.Modifier;

/**
 * @author Alexandre Blansché
 *         Programme principal
 */
@SuppressWarnings("unused")
public final class Main extends OutputWriter {
	private static Main instance = null;

	private static final String LOG_FILE = "bezier.log";
	private static final int AWAIT = 1;
	private static final int NB_RUNS = 1;
	//private static final int NB_RUNS = 3;
	private static final int NB_SECONDS = 60;
	// private static final int NB_SECONDS = 10;
	public static final boolean DISPLAY_CHART = true;
	//public static final boolean DISPLAY_CHART = false;
	static final boolean DISPLAY_STD_OUT = true;
	// static final boolean DISPLAY_STD_OUT = false;
	static final boolean COMPETITION = true;
	// static final boolean COMPETITION = false;
	private static final String DATA_DIR = "data";

	// ====== CHOIX DES PROBLEMES A LANCER ======
	// Mode auto (par defaut): laisser MANUAL_SELECTED_PROBLEMS a null.
	// Mode manuel: decommenter la ligne ci-dessous et indiquer un ou plusieurs problemes.
	private static final String[] MANUAL_SELECTED_PROBLEMS = {"prob16", "prob17", "prob18"};
	//private static final String[] MANUAL_SELECTED_PROBLEMS = null;
	/**
	 * @return Retourne l'instance de Main
	 */
	public static Main getInstance() {
		if (Main.instance == null)
			Main.instance = new Main();
		return Main.instance;
	}

	private Main() {
	}

	private static Set<String> getProblemNamesFromDataDir() {
		Set<String> names = new HashSet<String>();
		try {
			Path dataPath = Paths.get(Main.DATA_DIR);
			if (!Files.exists(dataPath) || !Files.isDirectory(dataPath))
				return names;
			try (Stream<Path> paths = Files.list(dataPath)) {
				paths.forEach(path -> {
					String filename = path.getFileName().toString();
					if (filename.startsWith("prob") && filename.endsWith(".bzr"))
						names.add(filename.substring(0, filename.length() - 4));
				});
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return names;
	}

	private static Solution run(Class<?> subClass, Problem problem)
			throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, SecurityException, NoSuchMethodException {
		Project project = (Project) subClass.getConstructor(Problem.class).newInstance(problem);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> future = executor.submit(project);
		Solution solution = null;
		try {
			if (!Main.DISPLAY_STD_OUT) {
				PrintStream stream = new PrintStream(new OutputStream() {
					@Override
					public void write(int b) throws IOException {
					}
				});
				System.setOut(stream);
			}
			future.get(Main.NB_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			executor.shutdownNow();
			future.cancel(true);
			solution = project.getSolution();
			if (!executor.awaitTermination(Main.AWAIT, TimeUnit.MINUTES)) {
				System.setOut(System.out);
				System.out.println("\nNe peut pas tuer le thread.\nAnnulation de l'exécution.");
				System.exit(0);
			}
		}
		return solution;
	}

	/**
	 * @param subClass La classe du projet à évaluer
	 * @param problem  Le problème TSP à résoudre
	 * @return La solution trouvée
	 */
	public static Solution exec(Class<?> subClass, Problem problem) {
		PrintStream out = System.out;
		ArrayList<Solution> solutions = new ArrayList<Solution>();
		for (int i = 0; i < Main.NB_RUNS; i++)
			try {
				problem.reset();
				String title = subClass.toString() + " (" + (i + 1) + ")";
				MainFrame.getInstance().clear();
				MonitorChart.getNewInstance(title);
				BezierChart.getNewInstance(problem);
				Field fields[] = subClass.getDeclaredFields();
				for (int j = 0; j < fields.length; j++) {
					boolean isStatic = java.lang.reflect.Modifier.isStatic(fields[j].getModifiers());
					boolean isFinal = java.lang.reflect.Modifier.isFinal(fields[j].getModifiers());
					if (isStatic && !isFinal) {

						fields[j].setAccessible(true);
						Class<?> type = fields[j].getType();
						if (type == boolean.class)
							fields[j].setBoolean(false, false);
						else if (type == byte.class)
							fields[j].setByte(null, (byte) 0);
						else if (type == char.class)
							fields[j].setChar(null, ' ');
						else if (type == double.class)
							fields[j].setDouble(null, (double) 0);
						else if (type == float.class)
							fields[j].setFloat(null, (float) 0);
						else if (type == int.class)
							fields[j].setInt(null, 0);
						else if (type == long.class)
							fields[j].setLong(null, 0);
						else if (type == short.class)
							fields[j].setShort(null, (short) 0);
						else
							fields[j].set(null, null);
					}
				}
				Solution sol = Main.run(subClass, problem);
					solutions.add(sol);
					out.println("  Run " + (i + 1) + "/" + NB_RUNS + " -> " + sol.getEvaluation());
					out.flush();
				} catch (Exception e) {
					e.printStackTrace();
				}
		System.setOut(out);
		return new Solution(solutions);
	}

	private void launch() {
		this.println("Évaluation des projets");
		this.print(Main.NB_RUNS + " exécution");
		if (Main.NB_RUNS > 1)
			this.println("s");
		else
			this.println("");
		this.print(Main.NB_SECONDS + " seconde");
		if (Main.NB_SECONDS > 1)
			this.println("s");
		else
			this.println("");
		Problem.getProblems();
		ArrayList<Problem> allProblems = Problem.getProblems();
		Set<String> selectedProblemNames = new HashSet<String>();
		if (Main.MANUAL_SELECTED_PROBLEMS != null)
			selectedProblemNames.addAll(Arrays.asList(Main.MANUAL_SELECTED_PROBLEMS));
		else
			selectedProblemNames = Main.getProblemNamesFromDataDir();
		ArrayList<Problem> problems = new ArrayList<Problem>();
		for (Problem p : allProblems)
			if (selectedProblemNames.contains(p.getName()))
				problems.add(p);
		if (problems.isEmpty())
			problems.addAll(allProblems);
		int maxLength = 0;
		for (Problem problem : problems)
			if (problem.getName().length() > maxLength)
				maxLength = problem.getName().length();
		ArrayList<ArrayList<Solution>> solutions = new ArrayList<ArrayList<Solution>>();
		for (int i = 0; i < problems.size(); i++)
			solutions.add(new ArrayList<Solution>());
		Reflections reflections = new Reflections("bezier.projects");
		ArrayList<Class<? extends Project>> subClasses = new ArrayList<Class<? extends Project>>();
		if (Main.COMPETITION) {
			Set<Class<? extends CompetitorProject>> subClassesTmp = reflections.getSubTypesOf(CompetitorProject.class);
			for (Class<? extends Project> subClass : subClassesTmp) {
				if (!Modifier.isAbstract(subClass.getModifiers()))
					subClasses.add(subClass);
			}
		} else {
			Set<Class<? extends Project>> subClassesTmp = reflections.getSubTypesOf(Project.class);
			for (Class<? extends Project> subClass : subClassesTmp) {
				if (!Modifier.isAbstract(subClass.getModifiers()))
					subClasses.add(subClass);
			}
		}
		this.print(subClasses.size() + " projet");
		if (subClasses.size() > 1)
			this.println("s");
		else
			this.println("");
		this.print(problems.size() + " problème");
		if (problems.size() > 1)
			this.println("s");
		else
			this.println("");
		this.print();
		for (Class<? extends Project> subClass : subClasses) {
			this.println(subClass.getName());
			for (int i = 0; i < problems.size(); i++) {
				Problem problem = problems.get(i);
				this.print(problem.getName());
				for (int j = problem.getName().length(); j < maxLength; j++)
					this.print(" ");
				Solution solution = Main.exec(subClass, problem);
				this.println("\t" + solution.getEvaluation());
				System.out.flush();
				solutions.get(i).add(solution);
			}
			this.print();
		}
		ArrayList<Solution> agg = Solution.aggregate(solutions);
		Collections.sort(agg);
		for (Solution solution : agg) {
			this.println(solution);
			this.print();
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Main main = Main.getInstance();
		MainFrame.getInstance();
		main.addOutput(StandardOutput.getInstance());
		main.addOutput(new LogFileOutput(Main.LOG_FILE));
		main.launch();
	}
}
