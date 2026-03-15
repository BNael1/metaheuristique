package engine.pso;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;
import engine.constraints.BoundsChecker;

import java.util.Random;

/**
 * CLPSO : Comprehensive Learning Particle Swarm Optimization.
 *
 * Contrairement au PSO standard qui apprend uniquement de son propre pbest et du gbest global,
 * chaque particule dans CLPSO apprend de l'expérience (pbest) de toutes les autres particules,
 * dimension par dimension. Cela permet de préserver la diversité et d'éviter les optima locaux
 * sur les problèmes multimodaux complexes.
 *
 * Référence : J. J. Liang, A. K. Qin, P. N. Suganthan and S. Baskar,
 * "Comprehensive learning particle swarm optimizer for global optimization of multimodal functions,"
 * IEEE Transactions on Evolutionary Computation, vol. 10, no. 3, pp. 281-295, June 2006.
 */
public class CLPSOCore implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final Random rng = new Random ();

    // Population
    private final int N; // Swarm size
    private double [][] x;
    private double [][] v;
    private double [][] pbest;
    private double [] pbestFitness;
    
    // Global Best
    private double [] gbest;
    private double gbestFitness = Double.POSITIVE_INFINITY;
    
    // Best found so far (can be same as gbest)
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double [] bestX;

    // CLPSO parameters
    private double w; // Inertia weight
    private final double wMax = 0.9;
    private final double wMin = 0.4;
    private final double c = 1.49445; // Acceleration coefficient
    private double [] Pc; // Learning probability for each particle
    private int [][] exemplars; // [particle][dim] -> index of the particle to learn from
    private int m = 7; // Refresh gap

    // State tracking
    private int generationCount;
    private int totalEvals;
    private int [] refreshGap; // Counter for refreshing exemplars

    public CLPSOCore (Problem problem, int d, double [] lb, double [] ub)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.N = 40; // Recommended population size for CLPSO (usually 10-40)
    }

    @Override
    public void init ()
    {
        x = new double [N][d];
        v = new double [N][d];
        pbest = new double [N][d];
        pbestFitness = new double [N];
        Pc = new double [N];
        exemplars = new int [N][d];
        refreshGap = new int [N];

        bestFitness = Double.POSITIVE_INFINITY;
        gbestFitness = Double.POSITIVE_INFINITY;
        gbest = null;
        bestX = null;
        generationCount = 0;
        totalEvals = 0;

        // Initialize Pc according to rank
        for (int i = 0; i < N; i++) {
            Pc[i] = 0.05 + 0.45 * (Math.exp(10.0 * i / (N - 1)) - 1) / (Math.exp(10.0) - 1);
        }

        for (int i = 0; i < N; i++)
        {
            for (int j = 0; j < d; j++) {
                x [i][j] = bounds.getLb () [j] + rng.nextDouble () * bounds.getRange (j);
                v [i][j] = (rng.nextDouble() - 0.5) * bounds.getRange(j) * 0.2;
            }
            bounds.clampInPlace(x[i]);
            
            double f = problem.evaluate (x [i]);
            totalEvals++;
            
            pbest[i] = x[i].clone();
            pbestFitness[i] = f;

            if (f < gbestFitness)
            {
                gbestFitness = f;
                gbest = x [i].clone ();
            }
            
            refreshGap[i] = 0;
            updateExemplars(i);
        }
        
        bestX = gbest.clone();
        bestFitness = gbestFitness;
    }

    private void updateExemplars(int i) {
        for (int j = 0; j < d; j++) {
            if (rng.nextDouble() < Pc[i]) {
                // Tournament selection for exemplar
                int p1 = rng.nextInt(N);
                int p2 = rng.nextInt(N);
                if (pbestFitness[p1] < pbestFitness[p2]) {
                    exemplars[i][j] = p1;
                } else {
                    exemplars[i][j] = p2;
                }
            } else {
                exemplars[i][j] = i; // Learn from own pbest
            }
        }
        
        // Ensure at least one dimension learns from another particle
        boolean learnsFromOther = false;
        for (int j = 0; j < d; j++) {
            if (exemplars[i][j] != i) {
                learnsFromOther = true;
                break;
            }
        }
        
        if (!learnsFromOther) {
            int dim = rng.nextInt(d);
            int other;
            do { other = rng.nextInt(N); } while (other == i);
            exemplars[i][dim] = other;
        }
    }

    @Override
    public void step ()
    {
        // Update inertia weight linearly
        // Assuming max evals approx 300,000 for 60s, gen size 40 -> 7500 gens
        double maxGen = 7500; 
        w = wMax - (wMax - wMin) * (generationCount / maxGen);
        if (w < wMin) w = wMin;
        if (generationCount > maxGen) w = wMin;

        for (int i = 0; i < N; i++)
        {
            // If gap exceeded, refresh exemplars
            if (refreshGap[i] >= m) {
                updateExemplars(i);
                refreshGap[i] = 0;
            }

            for (int j = 0; j < d; j++)
            {
                // CLPSO Velocity Update
                // v_id = w * v_id + c * rand * (pbest_exemplar_d - x_id)
                int exemplarIdx = exemplars[i][j];
                double pbestVal = pbest[exemplarIdx][j];
                
                v[i][j] = w * v[i][j] + c * rng.nextDouble() * (pbestVal - x[i][j]);
                
                // Velocity clamping (optional but recommended)
                double vMax = 0.2 * bounds.getRange(j);
                if (v[i][j] > vMax) v[i][j] = vMax;
                if (v[i][j] < -vMax) v[i][j] = -vMax;
                
                // Position Update
                x[i][j] = x[i][j] + v[i][j];
            }
            
            // Boundary handling
            bounds.clampInPlace(x[i]);
            
            // Evaluation
            double f = problem.evaluate(x[i]);
            totalEvals++;
            
            // Update pbest
            if (f < pbestFitness[i]) {
                pbestFitness[i] = f;
                pbest[i] = x[i].clone();
                refreshGap[i] = 0; // Reset gap on improvement
                
                // Update gbest
                if (f < gbestFitness) {
                    gbestFitness = f;
                    gbest = x[i].clone();
                    bestFitness = f;
                    bestX = x[i].clone();
                }
            } else {
                refreshGap[i]++;
            }
        }
        
        generationCount++;
    }

    @Override
    public double [] getBestX ()       { return bestX; }
    @Override
    public double getBestFitness ()    { return bestFitness; }
    @Override
    public boolean shouldRestart ()    { return false; }

    @Override
    public OptimizerState getState ()
    {
        return new OptimizerState (generationCount, totalEvals, 0,
                bestFitness, 0, 0, bestX);
    }
}
