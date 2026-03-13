package bench.competitors.alconst;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Lagrangien Augmenté + IPOP-CMA-ES.
 *
 * Transforme les pénalités dures (obstacles) en contraintes gᵢ(x) ≤ 0
 * et utilise un objectif augmenté :
 *   L(x, λ, ρ) = f_eval(x) + Σᵢ λᵢ·max(0, gᵢ(x)) + (ρ/2)·Σᵢ max(0, gᵢ(x))²
 *
 * L'idée : lisser le paysage autour des obstacles via les multiplicateurs λ
 * plutôt que la pénalité fixe de 100× du framework.
 *
 * Note : f_eval(x) = problem.evaluate(x) inclut DÉJÀ les pénalités.
 * On ajoute un terme supplémentaire de Lagrangien pour guider davantage
 * la recherche en dehors des zones d'obstacles.
 */
public class ALConstraintProject extends CompetitorProject
{
    private int d;
    private BoundsChecker bounds;
    private Random rng;
    private final double margin;

    // CMA-ES state
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

    private ArrayList<double []> cachedSeeds;
    private double startX, startY, endX, endY;

    // Lagrangien augmenté
    private ObstacleReader obsReader;
    private double [] lagMultipliers;   // λ_i pour chaque obstacle
    private double rho;                  // paramètre de pénalité
    private int alUpdateCounter;         // compteur de générations pour la mise à jour
    private static final int AL_UPDATE_INTERVAL = 10;
    private static final double RHO_INCREASE = 1.2;
    private static final double RHO_MAX = 1000.0;
    private static final double EPSILON_TOL = 0.01;

    public ALConstraintProject (Problem problem) throws InvalidProjectException
    {
        this (problem, 0.0);
    }

    public ALConstraintProject (Problem problem, double margin) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("AL-Constraint IPOP-CMA-ES");
        this.margin = margin;
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
            if (i % 2 == 0) { lb [i] = problem.getMinX () - margin; ub [i] = problem.getMaxX () + margin; }
            else             { lb [i] = problem.getMinY () - margin; ub [i] = problem.getMaxY () + margin; }
        }
        bounds = new BoundsChecker (lb, ub);

        lambda0 = 4 + (int) (3.0 * Math.log (d));
        sigma0 = (ub [0] - lb [0]) / 6.0;
        chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));

        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        // Initialiser le reader d'obstacles
        obsReader = new ObstacleReader (problem.getName ());

        // Initialiser les multiplicateurs de Lagrange
        int nObs = obsReader.getNObstacles ();
        lagMultipliers = new double [nObs];
        rho = 1.0;
        alUpdateCounter = 0;

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;

        // Seeding classique
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

        setupCMAES (lambda0, localBestX, sigma0);
    }

    @Override
    public void loop ()
    {
        double [][] arx = new double [lambda][d];
        double [][] ary = new double [lambda][d];
        double [] fitness = new double [lambda];        // fitness originale (problem.evaluate)
        double [] augFitness = new double [lambda];     // fitness augmentée (pour le tri)

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

            // Évaluer avec la fonction originale (met à jour bestEvaluation automatiquement)
            fitness [k] = problem.evaluate (arx [k]);
            updateBest (arx [k], fitness [k]);

            // Calculer la fitness augmentée SANS évaluation supplémentaire
            double [] violations = obsReader.computeViolations (arx [k], startX, startY, endX, endY);
            double alPenalty = 0;
            for (int o = 0; o < violations.length; o++)
            {
                double g = Math.max (0, violations [o]);
                alPenalty += lagMultipliers [o] * g + (rho / 2.0) * g * g;
            }
            augFitness [k] = fitness [k] + alPenalty;
        }

        // Trier par fitness AUGMENTÉE (guide CMA-ES vers des zones sans violation)
        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (augFitness [a], augFitness [b]));

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
        if (eigenCounter >= 1) { eigenDecomposition (); eigenCounter = 0; }

        generation++;
        alUpdateCounter++;

        // Mise à jour des multiplicateurs de Lagrange toutes les K générations
        if (alUpdateCounter >= AL_UPDATE_INTERVAL && bestX != null)
        {
            alUpdateCounter = 0;
            double [] violations = obsReader.computeViolations (bestX, startX, startY, endX, endY);
            double totalViol = 0;
            for (int o = 0; o < violations.length; o++)
            {
                double g = Math.max (0, violations [o]);
                lagMultipliers [o] = Math.max (0, lagMultipliers [o] + rho * g);
                totalViol += g;
            }
            if (totalViol > EPSILON_TOL)
                rho = Math.min (rho * RHO_INCREASE, RHO_MAX);
        }

        // Stagnation et restart
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

        if (shouldRestart ()) restart ();
    }

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

        ps = new double [d]; pc = new double [d];
        C = new double [d][d]; B = new double [d][d];
        diagD = new double [d]; invsqrtC = new double [d][d];
        for (int i = 0; i < d; i++) { C[i][i]=1; B[i][i]=1; diagD[i]=1; invsqrtC[i][i]=1; }

        generation = 0; eigenCounter = 0;
        stagnationCounter = 0;
        maxStagnation = 10 + (int) (30.0 * d / lambda);
        prevBestGen = Double.POSITIVE_INFINITY;
    }

    private boolean shouldRestart ()
    {
        if (stagnationCounter > maxStagnation) return true;
        double maxD = diagD[0], minD = diagD[0];
        for (int i = 1; i < d; i++) { if(diagD[i]>maxD)maxD=diagD[i]; if(diagD[i]<minD)minD=diagD[i]; }
        if (minD > 0 && (maxD/minD) > 1e7) return true;
        if (sigma * maxD < 1e-12) return true;
        return false;
    }

    private void restart ()
    {
        restartCount++;
        int newLambda = lambda0 * (1 << Math.min(restartCount, 8));
        newLambda = Math.min(newLambda, 512);

        // Reset Lagrangien au restart
        for (int i = 0; i < lagMultipliers.length; i++) lagMultipliers[i] = 0;
        rho = 1.0;
        alUpdateCounter = 0;

        double [] newMean;
        double newSigma = sigma0;
        double choice = rng.nextDouble();

        if (choice < 0.40 && cachedSeeds != null && !cachedSeeds.isEmpty())
        {
            int idx = restartCount % cachedSeeds.size();
            newMean = quickLocalOptimize(cachedSeeds.get(idx).clone(), 500);
        }
        else if (choice < 0.70 && bestX != null)
        {
            newMean = bestX.clone();
            for (int i = 0; i < d; i++) newMean[i] += rng.nextGaussian() * bounds.getRange(i) * 0.15;
            bounds.clampInPlace(newMean);
            newSigma = sigma0 / 2.0;
        }
        else
        {
            double [] bSeed = problem.getRandomControlPoints1DArray();
            bounds.clampInPlace(bSeed);
            double bF = problem.evaluate(bSeed);
            updateBest(bSeed, bF);
            for (int r = 0; r < 7; r++)
            {
                double [] s = problem.getRandomControlPoints1DArray();
                bounds.clampInPlace(s);
                double f = problem.evaluate(s);
                updateBest(s, f);
                if (f < bF) { bF = f; bSeed = s; }
            }
            newMean = bSeed;
        }

        setupCMAES(newLambda, newMean, newSigma);
    }

    // Seeds, local optimize, eigen, utils — same patterns as other implementations

    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2;
        ArrayList<double []> seeds = new ArrayList<> ();

        double lbY = bounds.getLb()[1], ubY = bounds.getUb()[1];
        double centerY = (lbY+ubY)/2.0, halfY = (ubY-lbY)/2.0;

        double [] periods = {0.5,1.0,1.5,2.0,2.5};
        double [] phases = {0,Math.PI/4,Math.PI/2,3*Math.PI/4,Math.PI,5*Math.PI/4,3*Math.PI/2,7*Math.PI/4};
        double [] amplitudes = {0.35,0.65,0.85,0.95};

        for (double per : periods) for (double ph : phases) for (double amp : amplitudes)
        {
            double [] p = new double[d];
            for (int i = 0; i < nCP; i++)
            {
                double t = (double)(i+1)/(nCP+1);
                p[2*i] = startX+t*(endX-startX);
                p[2*i+1] = centerY+amp*halfY*Math.sin(2*Math.PI*per*t+ph);
            }
            seeds.add(p);
        }

        double lbX = bounds.getLb()[0], ubX = bounds.getUb()[0];
        double [] yLevels = {lbY,lbY+2,lbY+5,centerY,ubY-5,ubY-2,ubY};
        for (double yVal : yLevels)
        {
            double [] p = new double[d];
            for (int i = 0; i < nCP; i++) { p[2*i]=(i%2==0)?ubX:lbX; p[2*i+1]=yVal; }
            seeds.add(p);
        }
        for (int r = 0; r < 30; r++)
        {
            double [] p = new double[d];
            for (int i = 0; i < nCP; i++)
            { p[2*i]=rng.nextBoolean()?lbX:ubX; p[2*i+1]=lbY+rng.nextDouble()*(ubY-lbY); }
            seeds.add(p);
        }
        for (int r = 0; r < 20; r++) seeds.add(problem.getRandomControlPoints1DArray());

        return seeds;
    }

    private double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x = x0.clone(); bounds.clampInPlace(x);
        double fx = problem.evaluate(x); updateBest(x,fx);
        double step = sigma0/2.0; int succ=0,win=0;
        for (int e = 0; e < maxEvals; e++)
        {
            double [] xN = new double[d];
            for (int i=0;i<d;i++) xN[i]=x[i]+rng.nextGaussian()*step;
            bounds.clampInPlace(xN);
            double fN = problem.evaluate(xN); updateBest(xN,fN);
            if(fN<fx){x=xN;fx=fN;succ++;}
            win++;
            if(win>=20){if((double)succ/win>0.2)step*=1.3;else step*=0.7;step=Math.max(step,1e-10);step=Math.min(step,sigma0*2);succ=0;win=0;}
        }
        return x;
    }

    private void eigenDecomposition ()
    {
        double [][] V = new double[d][d];
        for(int i=0;i<d;i++) for(int j=0;j<d;j++) V[i][j]=C[i][j];
        double [] dd=new double[d],ee=new double[d];
        tred2(V,dd,ee); tql2(V,dd,ee);
        for(int i=0;i<d;i++) diagD[i]=Math.sqrt(Math.max(dd[i],1e-20));
        B=V;
        for(int i=0;i<d;i++) for(int j=0;j<=i;j++)
        {
            double sum=0;
            for(int k=0;k<d;k++) sum+=B[i][k]*(1.0/diagD[k])*B[j][k];
            invsqrtC[i][j]=sum; invsqrtC[j][i]=sum;
        }
    }

    private void tred2(double[][]V,double[]d,double[]e){int n=this.d;for(int j=0;j<n;j++)d[j]=V[n-1][j];for(int i=n-1;i>0;i--){double scale=0,h=0;for(int k=0;k<i;k++)scale+=Math.abs(d[k]);if(scale==0.0){e[i]=d[i-1];for(int j=0;j<i;j++){d[j]=V[i-1][j];V[i][j]=0;V[j][i]=0;}}else{for(int k=0;k<i;k++){d[k]/=scale;h+=d[k]*d[k];}double f=d[i-1],g=Math.sqrt(h);if(f>0)g=-g;e[i]=scale*g;h-=f*g;d[i-1]=f-g;for(int j=0;j<i;j++)e[j]=0;for(int j=0;j<i;j++){f=d[j];V[j][i]=f;g=e[j]+V[j][j]*f;for(int k=j+1;k<=i-1;k++){g+=V[k][j]*d[k];e[k]+=V[k][j]*f;}e[j]=g;}f=0;for(int j=0;j<i;j++){e[j]/=h;f+=e[j]*d[j];}double hh=f/(h+h);for(int j=0;j<i;j++)e[j]-=hh*d[j];for(int j=0;j<i;j++){f=d[j];g=e[j];for(int k=j;k<=i-1;k++)V[k][j]-=f*e[k]+g*d[k];d[j]=V[i-1][j];V[i][j]=0;}}d[i]=h;}for(int i=0;i<n-1;i++){V[n-1][i]=V[i][i];V[i][i]=1;double h=d[i+1];if(h!=0){for(int k=0;k<=i;k++)d[k]=V[k][i+1]/h;for(int j=0;j<=i;j++){double g=0;for(int k=0;k<=i;k++)g+=V[k][i+1]*V[k][j];for(int k=0;k<=i;k++)V[k][j]-=g*d[k];}}for(int k=0;k<=i;k++)V[k][i+1]=0;}for(int j=0;j<n;j++){d[j]=V[n-1][j];V[n-1][j]=0;}V[n-1][n-1]=1;e[0]=0;}
    private void tql2(double[][]V,double[]d,double[]e){int n=this.d;for(int i=1;i<n;i++)e[i-1]=e[i];e[n-1]=0;double f=0,tst1=0,eps=Math.pow(2.0,-52.0);for(int l=0;l<n;l++){tst1=Math.max(tst1,Math.abs(d[l])+Math.abs(e[l]));int m=l;while(m<n){if(Math.abs(e[m])<=eps*tst1)break;m++;}if(m>l){int iter=0;do{iter++;double g=d[l],p=(d[l+1]-g)/(2.0*e[l]);double r=Math.hypot(p,1.0);if(p<0)r=-r;d[l]=e[l]/(p+r);d[l+1]=e[l]*(p+r);double dl1=d[l+1],h=g-d[l];for(int i=l+2;i<n;i++)d[i]-=h;f+=h;p=d[m];double c=1,c2=c,c3=c;double el1=e[l+1];double s=0,s2=0;for(int i=m-1;i>=l;i--){c3=c2;c2=c;s2=s;g=c*e[i];h=c*p;r=Math.hypot(p,e[i]);e[i+1]=s*r;s=e[i]/r;c=p/r;p=c*d[i]-s*g;d[i+1]=h+s*(c*g+s*d[i]);for(int k=0;k<n;k++){h=V[k][i+1];V[k][i+1]=s*V[k][i]+c*h;V[k][i]=c*V[k][i]-s*h;}}p=-s*s2*c3*el1*e[l]/dl1;e[l]=s*p;d[l]=c*p;}while(Math.abs(e[l])>eps*tst1 && iter<200*n);}d[l]=d[l]+f;e[l]=0;}for(int i=0;i<n-1;i++){int k=i;double p=d[i];for(int j=i+1;j<n;j++)if(d[j]<p){k=j;p=d[j];}if(k!=i){d[k]=d[i];d[i]=p;for(int j=0;j<n;j++){p=V[j][i];V[j][i]=V[j][k];V[j][k]=p;}}}}

    private double [] matVecMul(double[][]M,double[]v){double[]r=new double[d];for(int i=0;i<d;i++)for(int j=0;j<d;j++)r[i]+=M[i][j]*v[j];return r;}
    private double vecNorm(double[]v){double s=0;for(double vi:v)s+=vi*vi;return Math.sqrt(s);}
    private void updateBest(double[]x,double f){if(f<bestFitness){bestFitness=f;bestX=x.clone();}}
}
