package bezier.projects.competitor.gwo;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.Random;

/**
 * Grey Wolf Optimizer (GWO) pour l'optimisation de trajectoires de Bézier.
 * 
 * Simulation de la chasse en meute des loups gris (Mirjalili 2014).
 * Alpha, Beta, Delta guident le reste de la meute.
 * 
 * Paramètres :
 * - 30 loups
 * - Nombre de points de contrôle internes pris depuis le problème (ou 8 par défaut)
 * - 'a' décroît de 2 à 0 sur 10000 itérations → exploration → exploitation
 */
public class GWOProject extends CompetitorProject {

    private static final int POP_SIZE = 30;

    private double[][][] wolves;
    private double[] fitness;

    private double[][] alphaPos, betaPos, deltaPos;
    private double alphaFit = Double.POSITIVE_INFINITY;
    private double betaFit = Double.POSITIVE_INFINITY;
    private double deltaFit = Double.POSITIVE_INFINITY;

    private int nControlPoints;
    private Random random;
    private int iteration;
    private static final int MAX_ITERATIONS = 10000;
    private final double margin;

    public GWOProject(Problem problem) throws InvalidProjectException {
        this(problem, 0.0);
    }

    public GWOProject(Problem problem, double margin) throws InvalidProjectException {
        super(problem);
        this.margin = margin;
        this.addAuthor("TonNom1 TonNom2");           // ← METS TES VRAIS NOMS ICI
        this.setMethodName("GWO");                   // nom propre pour le classement
    }

    @Override
    public void initialization() {
        this.random = new Random();
        this.nControlPoints = this.problem.getNControlPoints() > 0 
                            ? this.problem.getNControlPoints() 
                            : 8;

        this.wolves = new double[POP_SIZE][nControlPoints][2];
        this.fitness = new double[POP_SIZE];

        // Population initiale aléatoire (méthode fournie par le framework)
        for (int i = 0; i < POP_SIZE; i++) {
            this.wolves[i] = this.problem.getRandomControlPoints2DArray();
            this.fitness[i] = this.problem.evaluate(this.wolves[i]);
            this.updateHierarchy(this.wolves[i], this.fitness[i]);
        }

        this.iteration = 0;
    }

    private void updateHierarchy(double[][] pos, double fit) {
        double[][] posCopy = copy(pos);

        if (fit < alphaFit) {
            deltaFit = betaFit;
            deltaPos = copy(betaPos);
            betaFit = alphaFit;
            betaPos = copy(alphaPos);
            alphaFit = fit;
            alphaPos = posCopy;
        } else if (fit < betaFit) {
            deltaFit = betaFit;
            deltaPos = copy(betaPos);
            betaFit = fit;
            betaPos = posCopy;
        } else if (fit < deltaFit) {
            deltaFit = fit;
            deltaPos = posCopy;
        }
    }

    private double[][] copy(double[][] original) {
        if (original == null) return null;
        double[][] copy = new double[nControlPoints][2];
        for (int i = 0; i < nControlPoints; i++) {
            copy[i][0] = original[i][0];
            copy[i][1] = original[i][1];
        }
        return copy;
    }

    @Override
    public void loop() {
        double a = 2.0 * (1.0 - (double) iteration / MAX_ITERATIONS);
        if (a < 0) a = 0;

        for (int i = 0; i < POP_SIZE; i++) {
            double[][] newPos = new double[nControlPoints][2];

            for (int j = 0; j < nControlPoints; j++) {
                for (int k = 0; k < 2; k++) {
                    // Alpha
                    double r1 = random.nextDouble();
                    double r2 = random.nextDouble();
                    double A1 = 2 * a * r1 - a;
                    double C1 = 2 * r2;
                    double D1 = Math.abs(C1 * alphaPos[j][k] - wolves[i][j][k]);
                    double X1 = alphaPos[j][k] - A1 * D1;

                    // Beta
                    r1 = random.nextDouble();
                    r2 = random.nextDouble();
                    double A2 = 2 * a * r1 - a;
                    double C2 = 2 * r2;
                    double D2 = Math.abs(C2 * betaPos[j][k] - wolves[i][j][k]);
                    double X2 = betaPos[j][k] - A2 * D2;

                    // Delta
                    r1 = random.nextDouble();
                    r2 = random.nextDouble();
                    double A3 = 2 * a * r1 - a;
                    double C3 = 2 * r2;
                    double D3 = Math.abs(C3 * deltaPos[j][k] - wolves[i][j][k]);
                    double X3 = deltaPos[j][k] - A3 * D3;

                    newPos[j][k] = (X1 + X2 + X3) / 3.0;
                }

                newPos[j][0] = Math.max(problem.getMinX() - margin, Math.min(problem.getMaxX() + margin, newPos[j][0]));
                newPos[j][1] = Math.max(problem.getMinY() - margin, Math.min(problem.getMaxY() + margin, newPos[j][1]));
            }

            wolves[i] = newPos;
            fitness[i] = problem.evaluate(wolves[i]);
            updateHierarchy(wolves[i], fitness[i]);
        }

        iteration++;
    }
}