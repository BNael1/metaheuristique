package bezier.projects.competitor.nbipop;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * NBIPOP-CMA-ES : BIPOP amélioré avec allocation dynamique du budget
 * entre restarts large-λ et small-λ, basée sur le taux d'amélioration observé.
 *
 * Tracking : après chaque restart, on calcule le improvement_rate de chaque régime.
 * Le ratio entre les deux détermine l'allocation du prochain budget.
 */
public class NBIPOPProject extends CompetitorProject
{
    private int d;
    private BoundsChecker bounds;
    private Random rng;

    // ===== CMA-ES =====
    private int lambda, mu;
    private double [] weights;
    private double mueff;
    private double csig, dsig, cc, c1, cmu, chiN;

    private double [] mean;
    private double sigma;
    private double [][] C, B, invsqrtC;
    private double [] diagD, ps, pc;
    private int generation, eigenCounter;

    private double bestFitness;
    private double [] bestX;

    private int lambda0;
    private double sigma0;
    private int restartCount;
    private int stagnationCounter, maxStagnation;
    private double prevBestGen;

    // ===== NBIPOP specifics =====
    private int largeLambda;
    private boolean currentIsLarge;        // true si le restart courant est large-λ

    // Tracking des performances par régime
    private double largeImproveTotal;      // somme des améliorations des restarts large
    private int largeEvalsTotal;           // nombre total d'évaluations des restarts large
    private double smallImproveTotal;
    private int smallEvalsTotal;

    // Historique glissant des N=5 derniers restarts
    private static final int HISTORY_SIZE = 5;
    private double [] historyImprove;      // amélioration par restart
    private int [] historyEvals;           // évaluations par restart
    private boolean [] historyIsLarge;     // large ou small
    private int historyIdx;
    private int historyCount;

    // Tracking du restart courant
    private double restartStartFitness;    // meilleur fitness au début du restart
    private int restartEvals;              // nombre d'évaluations dans le restart courant

    // ===== Seeds =====
    private ArrayList<double []> cachedSeeds;
    private double startX, startY, endX, endY;

    public NBIPOPProject (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("NBIPOP-CMA-ES");
    }

    @Override
    public void initialization ()
    {
        rng = new Random ();
        int nCP = problem.getNControlPoints ();
        d = 2 * nCP;

        double [] lb = new double [d];
        double [] ub = new double [d];
        for (int i = 0; i < d; i++)
        {
            if (i % 2 == 0) { lb [i] = problem.getMinX (); ub [i] = problem.getMaxX (); }
            else             { lb [i] = problem.getMinY (); ub [i] = problem.getMaxY (); }
        }
        bounds = new BoundsChecker (lb, ub);

        lambda0 = 4 + (int) (3.0 * Math.log (d));
        sigma0 = (ub [0] - lb [0]) / 6.0;
        chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));

        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;
        largeLambda = lambda0;

        // NBIPOP tracking
        largeImproveTotal = 0;
        largeEvalsTotal = 0;
        smallImproveTotal = 0;
        smallEvalsTotal = 0;
        historyImprove = new double [HISTORY_SIZE];
        historyEvals   = new int [HISTORY_SIZE];
        historyIsLarge = new boolean [HISTORY_SIZE];
        historyIdx = 0;
        historyCount = 0;

        // Seeding
        double [] initMean = new double [d];
        for (int i = 0; i < nCP; i++)
        {
            double t = (double) (i + 1) / (nCP + 1);
            initMean [2 * i]     = startX + t * (endX - startX);
            initMean [2 * i + 1] = startY + t * (endY - startY);
        }

        ArrayList<double []> seeds = generateDiverseSeeds ();
        seeds.add (0, initMean);

        double [] bestSeed = null;
        double bestSeedF = Double.POSITIVE_INFINITY;
        ArrayList<double []> evaluated = new ArrayList<> ();
        ArrayList<Double> fitnesses = new ArrayList<> ();

        for (double [] seed : seeds)
        {
            bounds.clampInPlace (seed);
            double f = problem.evaluate (seed);
            updateBest (seed, f);
            evaluated.add (seed);
            fitnesses.add (f);
            if (f < bestSeedF) { bestSeedF = f; bestSeed = seed.clone (); }
        }

        Integer [] indices = new Integer [evaluated.size ()];
        for (int i = 0; i < indices.length; i++) indices [i] = i;
        Arrays.sort (indices, (a, b) -> Double.compare (fitnesses.get (a), fitnesses.get (b)));

        cachedSeeds = new ArrayList<> ();
        for (int i = 0; i < Math.min (25, indices.length); i++)
            cachedSeeds.add (evaluated.get (indices [i]).clone ());

        int nLocalSeeds = Math.min (5, cachedSeeds.size ());
        double [] localBestX = bestSeed.clone ();
        double localBestF = bestSeedF;
        for (int s = 0; s < nLocalSeeds; s++)
        {
            double [] result = quickLocalOptimize (cachedSeeds.get (s).clone (), 1000);
            double fResult = problem.evaluate (result);
            updateBest (result, fResult);
            if (fResult < localBestF) { localBestF = fResult; localBestX = result.clone (); }
        }

        currentIsLarge = false; // premier run = petit (exploitation locale)
        restartStartFitness = bestFitness;
        restartEvals = 0;
        setupCMAES (lambda0, localBestX, sigma0);
    }

    @Override
    public void loop ()
    {
        // --- UNE GÉNÉRATION CMA-ES ---
        double [][] arx = new double [lambda][d];
        double [][] ary = new double [lambda][d];
        double [] fitness = new double [lambda];

        for (int k = 0; k < lambda; k++)
        {
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z [i] = rng.nextGaussian ();

            double [] Dz = new double [d];
            for (int i = 0; i < d; i++) Dz [i] = diagD [i] * z [i];

            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += B [i][j] * Dz [j];
                ary [k][i] = sum;
                arx [k][i] = mean [i] + sigma * sum;
            }

            bounds.clampInPlace (arx [k]);
            fitness [k] = problem.evaluate (arx [k]);
            updateBest (arx [k], fitness [k]);
            restartEvals++;
        }

        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        double [] oldMean = mean.clone ();
        mean = new double [d];
        for (int j = 0; j < mu; j++)
        {
            int ii = idx [j];
            for (int i = 0; i < d; i++) mean [i] += weights [j] * arx [ii][i];
        }

        double [] meanDiffNorm = new double [d];
        for (int i = 0; i < d; i++) meanDiffNorm [i] = (mean [i] - oldMean [i]) / sigma;

        double [] invsqrtCdiff = matVecMul (invsqrtC, meanDiffNorm);
        double csigFac = Math.sqrt (csig * (2.0 - csig) * mueff);
        for (int i = 0; i < d; i++) ps [i] = (1.0 - csig) * ps [i] + csigFac * invsqrtCdiff [i];

        double psNorm = vecNorm (ps);
        double hsigThresh = (1.4 + 2.0 / (d + 1.0)) * chiN
                * Math.sqrt (1.0 - Math.pow (1.0 - csig, 2.0 * (generation + 1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt (cc * (2.0 - cc) * mueff);
        for (int i = 0; i < d; i++) pc [i] = (1.0 - cc) * pc [i] + hsig * ccFac * meanDiffNorm [i];

        double deltaHsig = (1 - hsig) * cc * (2.0 - cc);
        double cOld = 1.0 - c1 - cmu + deltaHsig * c1;

        for (int i = 0; i < d; i++)
            for (int j = 0; j <= i; j++)
            {
                double rank1 = c1 * pc [i] * pc [j];
                double rankmu = 0;
                for (int k = 0; k < mu; k++)
                {
                    int ii = idx [k];
                    rankmu += weights [k] * ary [ii][i] * ary [ii][j];
                }
                C [i][j] = cOld * C [i][j] + rank1 + cmu * rankmu;
                C [j][i] = C [i][j];
            }

        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20);
        sigma = Math.min (sigma, 1e6);

        eigenCounter++;
        if (eigenCounter >= 1)
        {
            eigenDecomposition ();
            eigenCounter = 0;
        }

        generation++;

        double genBest = fitness [idx [0]];
        if (genBest < prevBestGen - 1e-12)
        {
            stagnationCounter = 0;
            prevBestGen = genBest;
        }
        else
        {
            stagnationCounter++;
        }

        if (shouldRestart ())
            restartNBIPOP ();
    }

    // ================================================================
    //  RESTART NBIPOP ADAPTATIF
    // ================================================================
    private void restartNBIPOP ()
    {
        // Enregistrer les stats du restart qui vient de finir
        double improvement = restartStartFitness - bestFitness;
        if (improvement < 0) improvement = 0;

        historyImprove [historyIdx] = improvement;
        historyEvals [historyIdx] = Math.max (restartEvals, 1);
        historyIsLarge [historyIdx] = currentIsLarge;
        historyIdx = (historyIdx + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) historyCount++;

        // Calculer les improvement_rate pour chaque régime
        double largeRate = 0;
        double smallRate = 0;
        int largeCount = 0, smallCount = 0;

        for (int i = 0; i < historyCount; i++)
        {
            if (historyIsLarge [i])
            {
                largeRate += historyImprove [i] / historyEvals [i];
                largeCount++;
            }
            else
            {
                smallRate += historyImprove [i] / historyEvals [i];
                smallCount++;
            }
        }

        if (largeCount > 0) largeRate /= largeCount;
        if (smallCount > 0) smallRate /= smallCount;

        // Décider du prochain régime
        restartCount++;

        if (largeCount == 0 || smallCount == 0)
        {
            // Pas assez d'historique : alterner classique
            currentIsLarge = !currentIsLarge;
        }
        else
        {
            double ratio = (smallRate > 1e-15) ? (largeRate / smallRate) : 10.0;

            if (ratio > 1.5)
            {
                // Large est plus efficace : 2/3 de chance de choisir large
                currentIsLarge = rng.nextDouble () < 0.67;
            }
            else if (ratio < 0.5)
            {
                // Small est plus efficace : 2/3 de chance de choisir small
                currentIsLarge = rng.nextDouble () > 0.67;
            }
            else
            {
                // Alternance classique 50/50
                currentIsLarge = rng.nextBoolean ();
            }
        }

        // Déterminer lambda et sigma pour le restart
        int newLambda;
        double newSigma;

        if (currentIsLarge)
        {
            largeLambda = Math.min (largeLambda * 2, 512);
            newLambda = largeLambda;
            newSigma = sigma0;
        }
        else
        {
            newLambda = lambda0;
            newSigma = sigma0 * (0.01 + rng.nextDouble () * 0.49); // petit sigma
        }

        // Choisir le point de départ
        double [] newMean;
        double choice = rng.nextDouble ();

        if (!currentIsLarge && bestX != null && choice < 0.5)
        {
            // Small restart near best
            newMean = bestX.clone ();
            for (int i = 0; i < d; i++)
                newMean [i] += rng.nextGaussian () * bounds.getRange (i) * 0.1;
            bounds.clampInPlace (newMean);
        }
        else if (choice < 0.70 && cachedSeeds != null && !cachedSeeds.isEmpty ())
        {
            int idx = restartCount % cachedSeeds.size ();
            newMean = quickLocalOptimize (cachedSeeds.get (idx).clone (), 500);
        }
        else
        {
            double [] bSeed = problem.getRandomControlPoints1DArray ();
            bounds.clampInPlace (bSeed);
            double bF = problem.evaluate (bSeed);
            updateBest (bSeed, bF);
            for (int r = 0; r < 7; r++)
            {
                double [] s = problem.getRandomControlPoints1DArray ();
                bounds.clampInPlace (s);
                double f = problem.evaluate (s);
                updateBest (s, f);
                if (f < bF) { bF = f; bSeed = s; }
            }
            int nCP = d / 2;
            for (int r = 0; r < 4; r++)
            {
                double [] s = new double [d];
                for (int i = 0; i < nCP; i++)
                {
                    s [2 * i]     = rng.nextBoolean () ? bounds.getLb () [0] : bounds.getUb () [0];
                    s [2 * i + 1] = bounds.getLb () [1] + rng.nextDouble () * bounds.getRange (1);
                }
                bounds.clampInPlace (s);
                double f = problem.evaluate (s);
                updateBest (s, f);
                if (f < bF) { bF = f; bSeed = s; }
            }
            newMean = bSeed;
        }

        // Reset tracking pour le nouveau restart
        restartStartFitness = bestFitness;
        restartEvals = 0;

        setupCMAES (newLambda, newMean, newSigma);
    }

    // ================================================================
    //  SETUP CMA-ES
    // ================================================================
    private void setupCMAES (int newLambda, double [] startMean, double startSigma)
    {
        lambda = newLambda;
        mu = lambda / 2;
        sigma = startSigma;
        mean = startMean.clone ();

        weights = new double [mu];
        double sumW = 0;
        for (int i = 0; i < mu; i++)
        {
            weights [i] = Math.log (mu + 0.5) - Math.log (i + 1.0);
            sumW += weights [i];
        }
        for (int i = 0; i < mu; i++) weights [i] /= sumW;

        double sumW2 = 0;
        for (int i = 0; i < mu; i++) sumW2 += weights [i] * weights [i];
        mueff = 1.0 / sumW2;

        csig = (mueff + 2.0) / (d + mueff + 5.0);
        dsig = 1.0 + 2.0 * Math.max (0, Math.sqrt ((mueff - 1.0) / (d + 1.0)) - 1.0) + csig;
        cc   = (4.0 + mueff / d) / (d + 4.0 + 2.0 * mueff / d);
        c1   = 2.0 / ((d + 1.3) * (d + 1.3) + mueff);
        cmu  = Math.min (1.0 - c1, 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((d + 2.0) * (d + 2.0) + mueff));

        ps = new double [d];
        pc = new double [d];
        C = new double [d][d];
        B = new double [d][d];
        diagD = new double [d];
        invsqrtC = new double [d][d];
        for (int i = 0; i < d; i++)
        {
            C [i][i] = 1.0; B [i][i] = 1.0; diagD [i] = 1.0; invsqrtC [i][i] = 1.0;
        }

        generation = 0;
        eigenCounter = 0;
        stagnationCounter = 0;
        maxStagnation = 10 + (int) (30.0 * d / lambda);
        prevBestGen = Double.POSITIVE_INFINITY;
    }

    private boolean shouldRestart ()
    {
        if (stagnationCounter > maxStagnation) return true;
        double maxD = diagD [0], minD = diagD [0];
        for (int i = 1; i < d; i++)
        {
            if (diagD [i] > maxD) maxD = diagD [i];
            if (diagD [i] < minD) minD = diagD [i];
        }
        if (minD > 0 && (maxD / minD) > 1e7) return true;
        if (sigma * maxD < 1e-12) return true;
        return false;
    }

    // ================================================================
    //  SEEDS (identique au CMAESOptimizer)
    // ================================================================
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY = bounds.getLb () [1], ubY = bounds.getUb () [1];
        double centerY = (lbY + ubY) / 2.0, halfY = (ubY - lbY) / 2.0;

        double [] periods = {0.5, 1.0, 1.5, 2.0, 2.5};
        double [] phases = {0, Math.PI/4, Math.PI/2, 3*Math.PI/4,
                           Math.PI, 5*Math.PI/4, 3*Math.PI/2, 7*Math.PI/4};
        double [] amplitudes = {0.35, 0.65, 0.85, 0.95};

        for (double period : periods)
            for (double phase : phases)
                for (double amp : amplitudes)
                {
                    double [] path = new double [d];
                    for (int i = 0; i < nCP; i++)
                    {
                        double t = (double)(i+1) / (nCP+1);
                        path [2*i]   = startX + t * (endX - startX);
                        path [2*i+1] = centerY + amp * halfY * Math.sin(2*Math.PI*period*t + phase);
                    }
                    seeds.add (path);
                }

        for (int nUp = 1; nUp <= nCP / 2; nUp++)
        {
            double [] pA = new double [d], pB = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i+1)/(nCP+1);
                boolean up = ((i/nUp)%2==0);
                pA[2*i] = startX+t*(endX-startX); pA[2*i+1] = up?(ubY-1):(lbY+1);
                pB[2*i] = startX+t*(endX-startX); pB[2*i+1] = up?(lbY+1):(ubY-1);
            }
            seeds.add(pA); seeds.add(pB);
        }

        for (double yy : new double [] {lbY+1.5, ubY-1.5, centerY})
        {
            double [] p = new double [d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i+1)/(nCP+1);
                p[2*i] = startX+t*(endX-startX); p[2*i+1] = yy;
            }
            seeds.add(p);
        }

        for (int r = 0; r < 20; r++) seeds.add (problem.getRandomControlPoints1DArray());

        double lbX = bounds.getLb()[0], ubX = bounds.getUb()[0];
        double [] yLevels = {lbY, lbY+2, lbY+5, centerY, ubY-5, ubY-2, ubY};

        for (double yVal : yLevels)
        {
            double [] p = new double [d];
            for (int i = 0; i < nCP; i++) { p[2*i] = (i%2==0)?ubX:lbX; p[2*i+1] = yVal; }
            seeds.add(p);
        }

        for (double y1 : new double[]{lbY, lbY+1, lbY+3})
            for (double y2 : new double[]{lbY, lbY+2, lbY+5, centerY})
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++)
                { p[2*i] = (i%2==0)?ubX:lbX; p[2*i+1] = (i<nCP/2)?y1:y2; }
                seeds.add(p);
            }

        for (double xVal : new double[]{lbX, ubX})
            for (double yVal : yLevels)
            {
                double [] p = new double [d];
                for (int i = 0; i < nCP; i++) { p[2*i] = xVal; p[2*i+1] = yVal; }
                seeds.add(p);
            }

        for (int r = 0; r < 30; r++)
        {
            double [] p = new double [d];
            for (int i = 0; i < nCP; i++)
            { p[2*i] = rng.nextBoolean()?lbX:ubX; p[2*i+1] = lbY+rng.nextDouble()*(ubY-lbY); }
            seeds.add(p);
        }

        return seeds;
    }

    private double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x = x0.clone ();
        bounds.clampInPlace (x);
        double fx = problem.evaluate (x);
        updateBest (x, fx);
        double step = sigma0 / 2.0;
        int succ = 0, win = 0;
        for (int e = 0; e < maxEvals; e++)
        {
            double [] xN = new double [d];
            for (int i = 0; i < d; i++) xN[i] = x[i] + rng.nextGaussian()*step;
            bounds.clampInPlace (xN);
            double fN = problem.evaluate (xN);
            updateBest (xN, fN);
            if (fN < fx) { x=xN; fx=fN; succ++; }
            win++;
            if (win >= 20)
            {
                if ((double)succ/win > 0.2) step*=1.3; else step*=0.7;
                step=Math.max(step,1e-10); step=Math.min(step,sigma0*2);
                succ=0; win=0;
            }
        }
        return x;
    }

    // ================================================================
    //  EIGENDECOMPOSITION (tred2 + tql2, JAMA domaine public)
    // ================================================================
    private void eigenDecomposition ()
    {
        double [][] V = new double [d][d];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < d; j++) V[i][j] = C[i][j];
        double [] dd = new double [d], ee = new double [d];
        tred2(V,dd,ee); tql2(V,dd,ee);
        for (int i = 0; i < d; i++) diagD[i] = Math.sqrt(Math.max(dd[i],1e-20));
        B = V;
        for (int i = 0; i < d; i++)
            for (int j = 0; j <= i; j++)
            {
                double sum = 0;
                for (int k = 0; k < d; k++) sum += B[i][k]*(1.0/diagD[k])*B[j][k];
                invsqrtC[i][j] = sum; invsqrtC[j][i] = sum;
            }
    }

    private void tred2 (double [][] V, double [] d, double [] e)
    {
        int n = this.d;
        for (int j = 0; j < n; j++) d[j] = V[n-1][j];
        for (int i = n-1; i > 0; i--)
        {
            double scale=0,h=0;
            for (int k=0;k<i;k++) scale+=Math.abs(d[k]);
            if (scale==0.0)
            {
                e[i]=d[i-1];
                for (int j=0;j<i;j++){d[j]=V[i-1][j];V[i][j]=0;V[j][i]=0;}
            }
            else
            {
                for (int k=0;k<i;k++){d[k]/=scale;h+=d[k]*d[k];}
                double f=d[i-1],g=Math.sqrt(h);
                if(f>0)g=-g;
                e[i]=scale*g;h-=f*g;d[i-1]=f-g;
                for (int j=0;j<i;j++) e[j]=0;
                for (int j=0;j<i;j++)
                {
                    f=d[j];V[j][i]=f;g=e[j]+V[j][j]*f;
                    for(int k=j+1;k<=i-1;k++){g+=V[k][j]*d[k];e[k]+=V[k][j]*f;}
                    e[j]=g;
                }
                f=0;
                for(int j=0;j<i;j++){e[j]/=h;f+=e[j]*d[j];}
                double hh=f/(h+h);
                for(int j=0;j<i;j++) e[j]-=hh*d[j];
                for(int j=0;j<i;j++)
                {
                    f=d[j];g=e[j];
                    for(int k=j;k<=i-1;k++) V[k][j]-=f*e[k]+g*d[k];
                    d[j]=V[i-1][j];V[i][j]=0;
                }
            }
            d[i]=h;
        }
        for(int i=0;i<n-1;i++)
        {
            V[n-1][i]=V[i][i];V[i][i]=1;
            double h=d[i+1];
            if(h!=0)
            {
                for(int k=0;k<=i;k++) d[k]=V[k][i+1]/h;
                for(int j=0;j<=i;j++)
                {
                    double g=0;
                    for(int k=0;k<=i;k++) g+=V[k][i+1]*V[k][j];
                    for(int k=0;k<=i;k++) V[k][j]-=g*d[k];
                }
            }
            for(int k=0;k<=i;k++) V[k][i+1]=0;
        }
        for(int j=0;j<n;j++){d[j]=V[n-1][j];V[n-1][j]=0;}
        V[n-1][n-1]=1;e[0]=0;
    }

    private void tql2 (double [][] V, double [] d, double [] e)
    {
        int n = this.d;
        for(int i=1;i<n;i++) e[i-1]=e[i];
        e[n-1]=0;
        double f=0,tst1=0,eps=Math.pow(2.0,-52.0);
        for(int l=0;l<n;l++)
        {
            tst1=Math.max(tst1,Math.abs(d[l])+Math.abs(e[l]));
            int m=l;
            while(m<n){if(Math.abs(e[m])<=eps*tst1)break;m++;}
            if(m>l)
            {
                int iter=0;
                do
                {
                    iter++;
                    double g=d[l],p=(d[l+1]-g)/(2.0*e[l]);
                    double r=Math.hypot(p,1.0);if(p<0)r=-r;
                    d[l]=e[l]/(p+r);d[l+1]=e[l]*(p+r);
                    double dl1=d[l+1],h=g-d[l];
                    for(int i=l+2;i<n;i++) d[i]-=h;
                    f+=h;
                    p=d[m];double c=1,c2=c,c3=c;
                    double el1=e[l+1];double s=0,s2=0;
                    for(int i=m-1;i>=l;i--)
                    {
                        c3=c2;c2=c;s2=s;
                        g=c*e[i];h=c*p;
                        r=Math.hypot(p,e[i]);
                        e[i+1]=s*r;s=e[i]/r;c=p/r;
                        p=c*d[i]-s*g;
                        d[i+1]=h+s*(c*g+s*d[i]);
                        for(int k=0;k<n;k++)
                        {
                            h=V[k][i+1];
                            V[k][i+1]=s*V[k][i]+c*h;
                            V[k][i]=c*V[k][i]-s*h;
                        }
                    }
                    p=-s*s2*c3*el1*e[l]/dl1;
                    e[l]=s*p;d[l]=c*p;
                }while(Math.abs(e[l])>eps*tst1 && iter<200*n);
            }
            d[l]=d[l]+f;e[l]=0;
        }
        for(int i=0;i<n-1;i++)
        {
            int k=i;double p=d[i];
            for(int j=i+1;j<n;j++) if(d[j]<p){k=j;p=d[j];}
            if(k!=i){d[k]=d[i];d[i]=p;for(int j=0;j<n;j++){p=V[j][i];V[j][i]=V[j][k];V[j][k]=p;}}
        }
    }

    private double [] matVecMul (double [][] M, double [] v)
    {
        double [] r = new double [d];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < d; j++) r[i] += M[i][j]*v[j];
        return r;
    }

    private double vecNorm (double [] v)
    {
        double s = 0; for (double vi : v) s += vi*vi; return Math.sqrt(s);
    }

    private void updateBest (double [] x, double f)
    {
        if (f < bestFitness) { bestFitness = f; bestX = x.clone(); }
    }
}
