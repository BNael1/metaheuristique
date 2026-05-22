package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * L-SHADE : Linear Success-History based Adaptive Differential Evolution.
 */
public class LSHADECore implements Optimizer {
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random();

    private int NP;
    private final int initNP;
    private final int minNP = 4;
    private double[][] pop;
    private double[] fitness;

    private final ArrayList<double[]> archive;
    private final int archiveSize;

    private static final int H = 6;
    private final double[] MF = new double[H];
    private final double[] MCR = new double[H];
    private int histIndex = 0;

    private double bestFitness = Double.POSITIVE_INFINITY;
    private double[] bestX;
    private int generationCount;
    private int totalEvals;
    private final int maxEvals;

    public LSHADECore(Problem problem, int d, double[] lb, double[] ub, int maxEvals) {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker(lb, ub);
        this.initNP = 18 * d;
        this.NP = initNP;
        this.maxEvals = maxEvals;

        this.archive = new ArrayList<>();
        this.archiveSize = (int) (2.6 * initNP);
    }

    @Override
    public void init() {
        pop = new double[NP][d];
        fitness = new double[NP];
        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        generationCount = 0;
        totalEvals = 0;
        archive.clear();
        histIndex = 0;

        for (int i = 0; i < H; i++) {
            MF[i] = 0.5;
            MCR[i] = 0.5;
        }

        for (int i = 0; i < NP; i++) {
            for (int j = 0; j < d; j++)
                pop[i][j] = bounds.getLb()[j] + rng.nextDouble() * bounds.getRange(j);
            fitness[i] = problem.evaluate(pop[i]);
            totalEvals++;

            if (fitness[i] < bestFitness) {
                bestFitness = fitness[i];
                bestX = pop[i].clone();
            }
        }
    }

    @Override
    public void step() {
        ArrayList<Double> successF = new ArrayList<>();
        ArrayList<Double> successCR = new ArrayList<>();
        ArrayList<Double> successDelta = new ArrayList<>();

        Integer[] sortedIndices = new Integer[NP];
        for (int i = 0; i < NP; i++) sortedIndices[i] = i;
        Arrays.sort(sortedIndices, (a, b) -> Double.compare(fitness[a], fitness[b]));

        double[][] newPop = new double[NP][d];
        double[] newFitness = new double[NP];

        for (int i = 0; i < NP; i++) {
            int r = rng.nextInt(H);
            double Fi = sampleCauchy(MF[r], 0.1);
            double CRi = sampleGaussian(MCR[r], 0.1);
            Fi = Math.max(0.01, Math.min(Fi, 1.0));
            CRi = Math.max(0.0, Math.min(CRi, 1.0));

            int pBestIndex = sortedIndices[rng.nextInt(Math.max(2, (int) (NP * 0.11)))];
            double[] xPBest = pop[pBestIndex];

            int r1;
            do { r1 = rng.nextInt(NP); } while (r1 == i);
            double[] xR1 = pop[r1];

            int r2;
            double[] xR2;
            int unionSize = NP + archive.size();
            do {
                int idx = rng.nextInt(unionSize);
                if (idx < NP) {
                    r2 = idx;
                    xR2 = pop[r2];
                } else {
                    r2 = -1;
                    xR2 = archive.get(idx - NP);
                }
            } while (r2 == i || r2 == r1);

            int jRand = rng.nextInt(d);
            double[] trial = new double[d];
            for (int j = 0; j < d; j++) {
                if (rng.nextDouble() < CRi || j == jRand)
                    trial[j] = pop[i][j] + Fi * (xPBest[j] - pop[i][j]) + Fi * (xR1[j] - xR2[j]);
                else
                    trial[j] = pop[i][j];
            }
            bounds.clampInPlace(trial);

            double fTrial = problem.evaluate(trial);
            totalEvals++;

            if (fTrial <= fitness[i]) {
                newPop[i] = trial;
                newFitness[i] = fTrial;

                if (fTrial < fitness[i]) {
                    successF.add(Fi);
                    successCR.add(CRi);
                    successDelta.add(fitness[i] - fTrial);
                    addToArchive(pop[i].clone());
                }

                if (fTrial < bestFitness) {
                    bestFitness = fTrial;
                    bestX = trial.clone();
                }
            } else {
                newPop[i] = pop[i];
                newFitness[i] = fitness[i];
            }
        }

        pop = newPop;
        fitness = newFitness;

        updateMemory(successF, successCR, successDelta);

        int nextNP = (int) Math.round(((double) (minNP - initNP) / maxEvals) * totalEvals + initNP);
        if (nextNP < minNP) nextNP = minNP;

        if (nextNP < NP) {
            reducePopulation(nextNP);
            NP = nextNP;
        }

        generationCount++;
    }

    private void addToArchive(double[] ind) {
        if (archive.size() < archiveSize) {
            archive.add(ind);
        } else {
            archive.set(rng.nextInt(archiveSize), ind);
        }
    }

    private void reducePopulation(int nextNP) {
        Integer[] idx = new Integer[NP];
        for (int i = 0; i < NP; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(fitness[a], fitness[b]));

        double[][] reducedPop = new double[nextNP][d];
        double[] reducedFitness = new double[nextNP];

        for (int i = 0; i < nextNP; i++) {
            reducedPop[i] = pop[idx[i]];
            reducedFitness[i] = fitness[idx[i]];
        }

        int currentArchiveCap = (int) (2.6 * nextNP);
        if (archive.size() > currentArchiveCap) {
            while (archive.size() > currentArchiveCap) {
                archive.remove(rng.nextInt(archive.size()));
            }
        }

        pop = reducedPop;
        fitness = reducedFitness;
    }

    private void updateMemory(ArrayList<Double> sF, ArrayList<Double> sCR, ArrayList<Double> sDelta) {
        if (!sF.isEmpty()) {
            double totalDelta = 0;
            for (double d : sDelta) totalDelta += d;

            double sumF2 = 0, sumF = 0;
            for (int i = 0; i < sF.size(); i++) {
                double w = sDelta.get(i) / totalDelta;
                sumF2 += w * sF.get(i) * sF.get(i);
                sumF += w * sF.get(i);
            }
            MF[histIndex] = (sumF > 0) ? sumF2 / sumF : 0.5;

            double sumCR = 0;
            for (int i = 0; i < sCR.size(); i++) {
                double w = sDelta.get(i) / totalDelta;
                sumCR += w * sCR.get(i);
            }
            MCR[histIndex] = sumCR;

            histIndex = (histIndex + 1) % H;
        }
    }

    private double sampleCauchy(double loc, double scale) {
        return loc + scale * Math.tan(Math.PI * (rng.nextDouble() - 0.5));
    }

    private double sampleGaussian(double mean, double std) {
        return mean + std * rng.nextGaussian();
    }

    @Override
    public double[] getBestX() { return bestX; }

    @Override
    public boolean shouldRestart() { return false; }
}
