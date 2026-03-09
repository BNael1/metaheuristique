package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.competitor.astarseeds.AStarGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * BIPOP-CMA-ES avec réparation A* in-loop.
 *
 * À chaque génération, ~20% des offspring sont "réparés" : chaque point de
 * contrôle (x,y) est tiré vers le segment A* le plus proche. CMA-ES adapte
 * naturellement sa covariance pour tenir compte de cet entonnoir d'attraction.
 *
 * Ceci maintient la cohérence topologique du chemin tout au long de l'évolution,
 * là où AStarSeeds ne bénéficie de A* qu'à l'initialisation.
 */
public class CMAESAStarRepair implements Optimizer
{
    private final Problem problem;
    private final int d;
    private final BoundsChecker bounds;
    private final double [] initMean;
    private final Random rng;
    private final String problemName;

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

    private double startX, startY, endX, endY;
    private ArrayList<double []> cachedSeeds;

    private final int lambda0;
    private final double sigma0;
    private int restartCount;
    private int stagnationCounter, maxStagnation;
    private double prevBestGen;
    private int largeLambda;

    // A* paths (multiple Y-biased paths for diversity)
    private double [][][] astarPaths; // [nPaths][nWaypoints][2]
    private double repairFraction = 0.20; // fraction of offspring to repair
    private double repairStrength = 0.5;  // how much to pull toward A* (0=no, 1=full snap)

    public CMAESAStarRepair (Problem problem, int d, double [] lb, double [] ub,
                              double [] initMean, String problemName)
    {
        this.problem = problem;
        this.d = d;
        this.bounds = new BoundsChecker (lb, ub);
        this.initMean = initMean.clone ();
        this.rng = new Random ();
        this.problemName = problemName;

        this.lambda0 = 4 + (int) (3.0 * Math.log (d));
        this.sigma0 = (ub [0] - lb [0]) / 6.0;
        this.chiN = Math.sqrt (d) * (1.0 - 1.0 / (4.0 * d) + 1.0 / (21.0 * d * d));
    }

    @Override
    public void init ()
    {
        startX = problem.getStartPoint ().getX ();
        startY = problem.getStartPoint ().getY ();
        endX   = problem.getEndPoint ().getX ();
        endY   = problem.getEndPoint ().getY ();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        restartCount = 0;
        largeLambda = lambda0;

        // Compute A* paths with different Y biases
        buildAStarPaths ();

        // Seeding
        ArrayList<double []> seeds = generateDiverseSeeds ();
        seeds.add (0, initMean.clone ());

        // Add A* seeds
        int nCP = d / 2;
        if (astarPaths != null)
        {
            for (double [][] waypoints : astarPaths)
            {
                double [] cp = AStarGrid.waypointsToControlPoints (
                    waypoints, nCP, startX, startY, endX, endY);
                if (cp != null) seeds.add (cp);
            }
        }

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

    // ================================================================
    //  A* Path Construction
    // ================================================================
    private void buildAStarPaths ()
    {
        try
        {
            double minXb = bounds.getLb () [0], maxXb = bounds.getUb () [0];
            double minYb = bounds.getLb () [1], maxYb = bounds.getUb () [1];
            AStarGrid grid = new AStarGrid (problemName, minXb, maxXb, minYb, maxYb, 50, 0.3);

            ArrayList<double [][]> paths = new ArrayList<> ();

            // Standard path
            double [][] path0 = grid.findPath (startX, startY, endX, endY);
            if (path0 != null) paths.add (path0);

            // Biased paths (push through different Y corridors)
            double centerY = (minYb + maxYb) / 2.0;
            for (double yBias : new double [] {minYb + 2, centerY, maxYb - 2, minYb + 5, maxYb - 5})
            {
                double [][] biased = grid.findPathWithBias (startX, startY, endX, endY, yBias);
                if (biased != null) paths.add (biased);
            }

            if (!paths.isEmpty ())
            {
                astarPaths = paths.toArray (new double [0][][]);
            }
        }
        catch (Exception e)
        {
            astarPaths = null; // Fall back to standard CMA-ES
        }
    }

    // ================================================================
    //  IN-LOOP REPAIR
    // ================================================================

    /**
     * Repairs an offspring by pulling each control point toward the nearest
     * point on the best A* path.
     *
     * @param x the offspring (flat array [x0,y0,x1,y1,...])
     * @param strength how much to pull (0 = no change, 1 = snap to A*)
     */
    private void repairTowardAStar (double [] x, double strength)
    {
        if (astarPaths == null || astarPaths.length == 0) return;

        // Pick the best A* path (first one, or we could rotate)
        double [][] waypoints = astarPaths [rng.nextInt (astarPaths.length)];
        int nCP = d / 2;

        for (int cp = 0; cp < nCP; cp++)
        {
            double cx = x [2 * cp];
            double cy = x [2 * cp + 1];

            // Find nearest waypoint on the A* path
            double bestDist = Double.POSITIVE_INFINITY;
            double nearX = cx, nearY = cy;

            for (int w = 0; w < waypoints.length - 1; w++)
            {
                double [] nearest = nearestPointOnSegment (
                    cx, cy,
                    waypoints [w][0], waypoints [w][1],
                    waypoints [w + 1][0], waypoints [w + 1][1]);
                double dx = cx - nearest [0];
                double dy = cy - nearest [1];
                double dist = dx * dx + dy * dy;
                if (dist < bestDist) { bestDist = dist; nearX = nearest [0]; nearY = nearest [1]; }
            }

            // Pull toward nearest point on A* path
            x [2 * cp]     = cx + strength * (nearX - cx);
            x [2 * cp + 1] = cy + strength * (nearY - cy);
        }
    }

    /** Nearest point on segment [A, B] to point P. */
    private double [] nearestPointOnSegment (double px, double py,
                                              double ax, double ay,
                                              double bx, double by)
    {
        double abx = bx - ax, aby = by - ay;
        double abLen2 = abx * abx + aby * aby;
        if (abLen2 < 1e-20) return new double [] {ax, ay};

        double t = ((px - ax) * abx + (py - ay) * aby) / abLen2;
        t = Math.max (0, Math.min (1, t));
        return new double [] {ax + t * abx, ay + t * aby};
    }

    // ================================================================
    //  UNE GÉNÉRATION (avec repair in-loop)
    // ================================================================
    @Override
    public void step ()
    {
        double [][] arx = new double [lambda][d];
        double [][] ary = new double [lambda][d];
        double [] fitness = new double [lambda];

        int repairCount = (int) (lambda * repairFraction);

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

            // Repair the last repairCount individuals
            if (k >= lambda - repairCount)
            {
                repairTowardAStar (arx [k], repairStrength);
                // Recompute ary after repair for correct covariance update
                for (int i = 0; i < d; i++)
                    ary [k][i] = (arx [k][i] - mean [i]) / sigma;
            }

            bounds.clampInPlace (arx [k]);
            fitness [k] = problem.evaluate (arx [k]);
            updateBest (arx [k], fitness [k]);
        }

        Integer [] idx = new Integer [lambda];
        for (int i = 0; i < lambda; i++) idx [i] = i;
        Arrays.sort (idx, (a, b) -> Double.compare (fitness [a], fitness [b]));

        double [] oldMean = mean.clone ();
        mean = new double [d];
        for (int j = 0; j < mu; j++)
        {
            int ii = idx [j]; for (int i = 0; i < d; i++) mean [i] += weights [j] * arx [ii][i];
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
                for (int k = 0; k < mu; k++) { int ii = idx [k]; rankmu += weights [k] * ary [ii][i] * ary [ii][j]; }
                rankmu *= cmu;
                C [i][j] = cOld * C [i][j] + rank1 + rankmu;
                C [j][i] = C [i][j];
            }

        sigma *= Math.exp ((csig / dsig) * (psNorm / chiN - 1.0));
        sigma = Math.max (sigma, 1e-20); sigma = Math.min (sigma, 1e6);

        eigenCounter++;
        if (eigenCounter >= 1) { eigenDecomposition (); eigenCounter = 0; }

        generation++;

        // Decay repair strength over time (let CMA-ES take over)
        if (generation % 50 == 0 && repairStrength > 0.1)
            repairStrength *= 0.95;

        double genBest = fitness [idx [0]];
        if (genBest < prevBestGen - 1e-12) { stagnationCounter = 0; prevBestGen = genBest; }
        else stagnationCounter++;

        if (shouldRestart ()) restart ();
    }

    // ================================================================
    //  Seeds, local opt, setup, restart, eigen — BIPOP pattern
    // ================================================================
    private ArrayList<double []> generateDiverseSeeds ()
    {
        int nCP = d / 2; ArrayList<double []> seeds = new ArrayList<> ();
        double lbY = bounds.getLb()[1], ubY = bounds.getUb()[1];
        double centerY = (lbY+ubY)/2.0, halfY = (ubY-lbY)/2.0;

        double [] periods = {0.5,1.0,1.5,2.0,2.5};
        double [] phases = {0,Math.PI/4,Math.PI/2,3*Math.PI/4,Math.PI,5*Math.PI/4,3*Math.PI/2,7*Math.PI/4};
        double [] amplitudes = {0.35,0.65,0.85,0.95};

        for (double period:periods) for (double phase:phases) for (double amp:amplitudes)
        {
            double [] path = new double [d];
            for (int i=0;i<nCP;i++) { double t=(double)(i+1)/(nCP+1); path[2*i]=startX+t*(endX-startX); path[2*i+1]=centerY+amp*halfY*Math.sin(2*Math.PI*period*t+phase); }
            seeds.add(path);
        }

        for (int nUp=1;nUp<=nCP/2;nUp++)
        {
            double [] pathA=new double[d], pathB=new double[d];
            for (int i=0;i<nCP;i++) { double t=(double)(i+1)/(nCP+1); boolean up=((i/nUp)%2==0); pathA[2*i]=startX+t*(endX-startX); pathA[2*i+1]=up?(ubY-1):(lbY+1); pathB[2*i]=startX+t*(endX-startX); pathB[2*i+1]=up?(lbY+1):(ubY-1); }
            seeds.add(pathA); seeds.add(pathB);
        }

        for (double yy:new double[]{lbY+1.5,ubY-1.5,centerY})
        { double [] path=new double[d]; for(int i=0;i<nCP;i++){double t=(double)(i+1)/(nCP+1);path[2*i]=startX+t*(endX-startX);path[2*i+1]=yy;} seeds.add(path); }

        for (int r=0;r<20;r++) seeds.add(problem.getRandomControlPoints1DArray());

        double lbX=bounds.getLb()[0], ubX=bounds.getUb()[0];
        double [] yLevels={lbY,lbY+2,lbY+5,centerY,ubY-5,ubY-2,ubY};

        for (double yVal:yLevels)
        { double [] path=new double[d]; for(int i=0;i<nCP;i++){path[2*i]=(i%2==0)?ubX:lbX;path[2*i+1]=yVal;} seeds.add(path); }

        for (double y1:new double[]{lbY,lbY+1,lbY+3})
            for (double y2:new double[]{lbY,lbY+2,lbY+5,centerY})
            { double [] path=new double[d]; for(int i=0;i<nCP;i++){path[2*i]=(i%2==0)?ubX:lbX;path[2*i+1]=(i<nCP/2)?y1:y2;} seeds.add(path); }

        for (double xVal:new double[]{lbX,ubX})
            for (double yVal:yLevels)
            { double [] path=new double[d]; for(int i=0;i<nCP;i++){path[2*i]=xVal;path[2*i+1]=yVal;} seeds.add(path); }

        for (int r=0;r<30;r++)
        { double [] path=new double[d]; for(int i=0;i<nCP;i++){path[2*i]=rng.nextBoolean()?lbX:ubX;path[2*i+1]=lbY+rng.nextDouble()*(ubY-lbY);} seeds.add(path); }

        return seeds;
    }

    private double [] quickLocalOptimize (double [] x0, int maxEvals)
    {
        double [] x=x0.clone(); bounds.clampInPlace(x);
        double fx=problem.evaluate(x); updateBest(x,fx);
        double step=sigma0/2.0; int successes=0,window=0;
        for(int e=0;e<maxEvals;e++)
        {
            double [] xNew=new double[d]; for(int i=0;i<d;i++)xNew[i]=x[i]+rng.nextGaussian()*step;
            bounds.clampInPlace(xNew); double fNew=problem.evaluate(xNew); updateBest(xNew,fNew);
            if(fNew<fx){x=xNew;fx=fNew;successes++;} window++;
            if(window>=20){double rate=(double)successes/window;if(rate>0.2)step*=1.3;else step*=0.7;step=Math.max(step,1e-10);step=Math.min(step,sigma0*2);successes=0;window=0;}
        }
        return x;
    }

    private void setupCMAES (int newLambda, double [] startMean, double startSigma)
    {
        lambda=newLambda; mu=lambda/2; sigma=startSigma; mean=startMean.clone();
        weights=new double[mu]; double sumW=0;
        for(int i=0;i<mu;i++){weights[i]=Math.log(mu+0.5)-Math.log(i+1.0);sumW+=weights[i];}
        for(int i=0;i<mu;i++)weights[i]/=sumW;
        double sumW2=0; for(int i=0;i<mu;i++)sumW2+=weights[i]*weights[i]; mueff=1.0/sumW2;

        csig=(mueff+2.0)/(d+mueff+5.0);
        dsig=1.0+2.0*Math.max(0,Math.sqrt((mueff-1.0)/(d+1.0))-1.0)+csig;
        cc=(4.0+mueff/d)/(d+4.0+2.0*mueff/d);
        c1=2.0/((d+1.3)*(d+1.3)+mueff);
        cmu=Math.min(1.0-c1,2.0*(mueff-2.0+1.0/mueff)/((d+2.0)*(d+2.0)+mueff));

        ps=new double[d]; pc=new double[d];
        C=new double[d][d]; B=new double[d][d]; diagD=new double[d]; invsqrtC=new double[d][d];
        for(int i=0;i<d;i++){C[i][i]=1;B[i][i]=1;diagD[i]=1;invsqrtC[i][i]=1;}

        generation=0; eigenCounter=0; stagnationCounter=0;
        maxStagnation=10+(int)(30.0*d/lambda); prevBestGen=Double.POSITIVE_INFINITY;
    }

    private boolean shouldRestart ()
    {
        if(stagnationCounter>maxStagnation)return true;
        double maxD=diagD[0],minD=diagD[0];
        for(int i=1;i<d;i++){if(diagD[i]>maxD)maxD=diagD[i];if(diagD[i]<minD)minD=diagD[i];}
        if(minD>0&&(maxD/minD)>1e7)return true;
        if(sigma*maxD<1e-12)return true;
        return false;
    }

    private void restart ()
    {
        restartCount++;
        int newLambda; double newSigma; double [] newMean;
        repairStrength = 0.5; // Reset repair strength on restart

        if(restartCount%2==1)
        {
            largeLambda=Math.min(largeLambda*2,512); newLambda=largeLambda; newSigma=sigma0;
            double choice=rng.nextDouble();
            if(choice<0.30 && astarPaths!=null && astarPaths.length>0)
            {
                // Restart from A* path
                int nCP=d/2;
                double [][] wp = astarPaths[rng.nextInt(astarPaths.length)];
                double [] cp = AStarGrid.waypointsToControlPoints(wp,nCP,startX,startY,endX,endY);
                if (cp!=null) { newMean=cp; for(int i=0;i<d;i++)newMean[i]+=rng.nextGaussian()*bounds.getRange(i)*0.10; bounds.clampInPlace(newMean); }
                else newMean=problem.getRandomControlPoints1DArray();
            }
            else if(choice<0.55 && cachedSeeds!=null && !cachedSeeds.isEmpty())
            {
                int idxS=restartCount%cachedSeeds.size();
                newMean=quickLocalOptimize(cachedSeeds.get(idxS).clone(),500);
            }
            else if(choice<0.75 && bestX!=null)
            {
                newMean=bestX.clone(); for(int i=0;i<d;i++)newMean[i]+=rng.nextGaussian()*bounds.getRange(i)*0.15;
                bounds.clampInPlace(newMean);
            }
            else
            {
                double [] bSeed=problem.getRandomControlPoints1DArray(); bounds.clampInPlace(bSeed);
                double bF=problem.evaluate(bSeed); updateBest(bSeed,bF);
                for(int r=0;r<3;r++){double[]s=problem.getRandomControlPoints1DArray();bounds.clampInPlace(s);double f=problem.evaluate(s);updateBest(s,f);if(f<bF){bF=f;bSeed=s;}}
                int nCP=d/2;
                for(int r=0;r<4;r++){double[]s=new double[d];for(int i=0;i<nCP;i++){s[2*i]=rng.nextBoolean()?bounds.getLb()[0]:bounds.getUb()[0];s[2*i+1]=bounds.getLb()[1]+rng.nextDouble()*bounds.getRange(1);}bounds.clampInPlace(s);double f=problem.evaluate(s);updateBest(s,f);if(f<bF){bF=f;bSeed=s;}}
                newMean=bSeed;
            }
        }
        else
        {
            newLambda=lambda0; newSigma=sigma0/10.0;
            if(bestX!=null){newMean=bestX.clone();for(int i=0;i<d;i++)newMean[i]+=rng.nextGaussian()*bounds.getRange(i)*0.05;bounds.clampInPlace(newMean);}
            else newMean=initMean.clone();
        }

        setupCMAES(newLambda,newMean,newSigma);
    }

    // ================================================================
    //  EIGENDECOMPOSITION
    // ================================================================
    private void eigenDecomposition ()
    {
        double[][]V=new double[d][d]; for(int i=0;i<d;i++)for(int j=0;j<d;j++)V[i][j]=C[i][j];
        double[]dd=new double[d];double[]ee=new double[d]; tred2(V,dd,ee);tql2(V,dd,ee);
        for(int i=0;i<d;i++)diagD[i]=Math.sqrt(Math.max(dd[i],1e-20)); B=V;
        for(int i=0;i<d;i++)for(int j=0;j<=i;j++){double sum=0;for(int k=0;k<d;k++)sum+=B[i][k]*(1.0/diagD[k])*B[j][k];invsqrtC[i][j]=sum;invsqrtC[j][i]=sum;}
    }

    private void tred2(double[][]V,double[]dArr,double[]e)
    {
        int n=d;for(int j=0;j<n;j++)dArr[j]=V[n-1][j];
        for(int i=n-1;i>0;i--){double scale=0,h=0;for(int k=0;k<i;k++)scale+=Math.abs(dArr[k]);
        if(scale==0.0){e[i]=dArr[i-1];for(int j=0;j<i;j++){dArr[j]=V[i-1][j];V[i][j]=0;V[j][i]=0;}}
        else{for(int k=0;k<i;k++){dArr[k]/=scale;h+=dArr[k]*dArr[k];}double f=dArr[i-1];double g=Math.sqrt(h);if(f>0)g=-g;e[i]=scale*g;h-=f*g;dArr[i-1]=f-g;for(int j=0;j<i;j++)e[j]=0;
        for(int j=0;j<i;j++){f=dArr[j];V[j][i]=f;g=e[j]+V[j][j]*f;for(int k=j+1;k<=i-1;k++){g+=V[k][j]*dArr[k];e[k]+=V[k][j]*f;}e[j]=g;}f=0;for(int j=0;j<i;j++){e[j]/=h;f+=e[j]*dArr[j];}
        double hh=f/(h+h);for(int j=0;j<i;j++)e[j]-=hh*dArr[j];for(int j=0;j<i;j++){f=dArr[j];g=e[j];for(int k=j;k<=i-1;k++)V[k][j]-=f*e[k]+g*dArr[k];dArr[j]=V[i-1][j];V[i][j]=0;}}dArr[i]=h;}
        for(int i=0;i<n-1;i++){V[n-1][i]=V[i][i];V[i][i]=1;double h=dArr[i+1];if(h!=0){for(int k=0;k<=i;k++)dArr[k]=V[k][i+1]/h;for(int j=0;j<=i;j++){double g=0;for(int k=0;k<=i;k++)g+=V[k][i+1]*V[k][j];for(int k=0;k<=i;k++)V[k][j]-=g*dArr[k];}}for(int k=0;k<=i;k++)V[k][i+1]=0;}
        for(int j=0;j<n;j++){dArr[j]=V[n-1][j];V[n-1][j]=0;}V[n-1][n-1]=1;e[0]=0;
    }

    private void tql2(double[][]V,double[]dArr,double[]e)
    {
        int n=d;for(int i=1;i<n;i++)e[i-1]=e[i];e[n-1]=0;double f=0,tst1=0,eps=Math.pow(2.0,-52.0);
        for(int l=0;l<n;l++){tst1=Math.max(tst1,Math.abs(dArr[l])+Math.abs(e[l]));int m=l;while(m<n){if(Math.abs(e[m])<=eps*tst1)break;m++;}
        if(m>l){int iter=0;do{iter++;double g=dArr[l];double p=(dArr[l+1]-g)/(2.0*e[l]);double r=Math.hypot(p,1.0);if(p<0)r=-r;dArr[l]=e[l]/(p+r);dArr[l+1]=e[l]*(p+r);double dl1=dArr[l+1];double h=g-dArr[l];for(int i=l+2;i<n;i++)dArr[i]-=h;f+=h;p=dArr[m];double c=1,c2=c,c3=c;double el1=e[l+1];double s=0,s2=0;for(int i=m-1;i>=l;i--){c3=c2;c2=c;s2=s;g=c*e[i];h=c*p;r=Math.hypot(p,e[i]);e[i+1]=s*r;s=e[i]/r;c=p/r;p=c*dArr[i]-s*g;dArr[i+1]=h+s*(c*g+s*dArr[i]);for(int kk=0;kk<n;kk++){h=V[kk][i+1];V[kk][i+1]=s*V[kk][i]+c*h;V[kk][i]=c*V[kk][i]-s*h;}}p=-s*s2*c3*el1*e[l]/dl1;e[l]=s*p;dArr[l]=c*p;}while(Math.abs(e[l])>eps*tst1);}
        dArr[l]=dArr[l]+f;e[l]=0;}
        for(int i=0;i<n-1;i++){int k=i;double p=dArr[i];for(int j=i+1;j<n;j++)if(dArr[j]<p){k=j;p=dArr[j];}if(k!=i){dArr[k]=dArr[i];dArr[i]=p;for(int j=0;j<n;j++){p=V[j][i];V[j][i]=V[j][k];V[j][k]=p;}}}
    }

    private double[]matVecMul(double[][]M,double[]v){double[]r=new double[d];for(int i=0;i<d;i++)for(int j=0;j<d;j++)r[i]+=M[i][j]*v[j];return r;}
    private double vecNorm(double[]v){double s=0;for(double vi:v)s+=vi*vi;return Math.sqrt(s);}
    private void updateBest(double[]x,double f){if(f<bestFitness){bestFitness=f;bestX=x.clone();}}

    @Override public double[]getBestX(){return bestX;}
    public double getBestFitness(){return bestFitness;}
}
