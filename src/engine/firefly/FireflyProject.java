package engine.firefly;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.Random;

/**
 * Firefly Algorithm (FA - Xin-She Yang 2009) pour trajectoires Bézier.
 * 
 * Paramètres :
 * - 30 lucioles
 * - β0 = 1.0
 * - γ calculé dynamiquement : 1 / (range_moyen_par_dimension²) pour que β ≈ β₀/e à mi-portée
 * - α décroît de 0.5 → 0.01 × range_moyen (adapté à l'échelle du problème)
 * - α décroît sur 5000 itérations
 */
public class FireflyProject extends CompetitorProject {

    private static final int POP_SIZE = 30;
    private static final double BETA0 = 1.0;
    private static final double ALPHA_START_FRAC = 0.5;  // fraction de la plage
    private static final double ALPHA_END_FRAC   = 0.01;

    private double[][][] fireflies;
    private double[] fitness;
    private Random random;
    private int nControlPoints;
    private int iteration;
    private static final int MAX_ITERATIONS = 5000;
    private final double margin;
    private double gamma;        // calculé à l'init selon les bornes du problème
    private double alphaStart;   // pas absolu calculé à l'init
    private double alphaEnd;

    public FireflyProject(Problem problem) throws InvalidProjectException {
        this(problem, 0.0);
    }

    public FireflyProject(Problem problem, double margin) throws InvalidProjectException {
        super(problem);
        this.margin = margin;
        this.addAuthor("TonNom1 TonNom2");           // ← CHANGE PAR VOS VRAIS NOMS
        this.setMethodName("Firefly");
    }

    @Override
    public void initialization() {
        this.random = new Random();
        this.nControlPoints = this.problem.getNControlPoints() > 0 
                            ? this.problem.getNControlPoints() 
                            : 8;

        // γ tel que β ≈ β₀/e quand deux lucioles sont à mi-portée (moyenne des plages X et Y)
        double rangeX = problem.getMaxX() - problem.getMinX() + 2 * margin;
        double rangeY = problem.getMaxY() - problem.getMinY() + 2 * margin;
        double avgRange2 = (rangeX * rangeX + rangeY * rangeY) / 2.0;
        this.gamma = 1.0 / avgRange2;

        // α absolu = fraction de la plage moyenne
        double avgRange = (rangeX + rangeY) / 2.0;
        this.alphaStart = ALPHA_START_FRAC * avgRange;
        this.alphaEnd   = ALPHA_END_FRAC   * avgRange;

        this.fireflies = new double[POP_SIZE][][];
        this.fitness = new double[POP_SIZE];

        // Population initiale
        for (int i = 0; i < POP_SIZE; i++) {
            this.fireflies[i] = this.problem.getRandomControlPoints2DArray();
            this.fitness[i] = this.problem.evaluate(this.fireflies[i]);
        }
        this.iteration = 0;
    }

    @Override
    public void loop() {
        double alpha = alphaStart - (alphaStart - alphaEnd) * Math.min(1.0, (double) iteration / MAX_ITERATIONS);

        for (int i = 0; i < POP_SIZE; i++) {
            boolean moved = false;
            for (int j = 0; j < POP_SIZE; j++) {
                if (i != j && fitness[j] < fitness[i]) {
                    moveFirefly(i, j, alpha);
                    moved = true;
                }
            }
            if (moved) {
                fitness[i] = problem.evaluate(fireflies[i]);
            }
        }

        iteration++;
    }

    private void moveFirefly(int i, int j, double alpha) {
        double[][] fi = fireflies[i];
        double[][] fj = fireflies[j];

        // Distance euclidienne au carré, normalisée par nControlPoints
        // pour rendre γ indépendant de la dimension du problème
        double r2 = 0.0;
        for (int k = 0; k < nControlPoints; k++) {
            double dx = fi[k][0] - fj[k][0];
            double dy = fi[k][1] - fj[k][1];
            r2 += dx * dx + dy * dy;
        }
        r2 /= nControlPoints;

        double beta = BETA0 * Math.exp(-gamma * r2);

        double minX = problem.getMinX() - margin;
        double maxX = problem.getMaxX() + margin;
        double minY = problem.getMinY() - margin;
        double maxY = problem.getMaxY() + margin;

        for (int k = 0; k < nControlPoints; k++) {
            // Attraction + bruit
            fi[k][0] += beta * (fj[k][0] - fi[k][0]) + alpha * (random.nextDouble() - 0.5);
            fi[k][1] += beta * (fj[k][1] - fi[k][1]) + alpha * (random.nextDouble() - 0.5);

            // Clamping strict
            fi[k][0] = Math.max(minX, Math.min(maxX, fi[k][0]));
            fi[k][1] = Math.max(minY, Math.min(maxY, fi[k][1]));
        }
    }
}