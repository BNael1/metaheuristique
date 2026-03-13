package bench.competitors.islands;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Island Model : 3 îles CMA-ES avec migration.
 *
 * Mono-thread strict : on exécute les îles en round-robin (1 génération chacune
 * par appel à loop()). Migration = ring topology tous les T_MIGRATE appels.
 *
 * Île 0 : sigma petit (exploitation locale)
 * Île 1 : sigma standard (exploration modérée)
 * Île 2 : sigma grand (exploration large)
 *
 * Migration : la meilleure solution d'une île remplace le pire individu
 * de la génération suivante de l'île voisine (ring 0→1→2→0).
 */
public class IslandProject extends CompetitorProject
{
    private static final int N_ISLANDS = 3;
    private static final int T_MIGRATE = 20; // migrate toutes les T_MIGRATE générations par île

    private int d;
    private BoundsChecker bounds;
    private Random rng;
    private final double margin;

    // Par île
    private int [] isLambda, isMu;
    private double [][] isWeights;
    private double [] isMueff;
    private double [] isCsig, isDsig, isCc, isC1, isCmu;
    private double [][] isMean;
    private double [] isSigma;
    private double [][][] isC, isB, isInvsqrtC;
    private double [][] isDiagD, isPs, isPc;
    private int [] isGeneration, isEigenCounter;
    private double [] isBestFitness;
    private double [][] isBestX;
    private int [] isStagnation;
    private int [] isMaxStagnation;
    private double [] isPrevBestGen;
    private int [] isRestartCount;

    // Global
    private double chiN;
    private int lambda0;
    private double sigma0;
    private double bestFitness;
    private double [] bestX;
    private int currentIsland; // round-robin index
    private int totalLoops;    // total calls to loop()

    // Migration buffer
    private double [][] migrantX;  // meilleur X à envoyer de chaque île
    private double [] migrantF;

    private ArrayList<double []> cachedSeeds;
    private double startX, startY, endX, endY;

    public IslandProject (Problem problem) throws InvalidProjectException
    {
        this (problem, 0.0);
    }

    public IslandProject (Problem problem, double margin) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("BENSAADI");
        this.addAuthor ("RAHALI");
        this.setMethodName ("Island CMA-ES");
        this.margin = margin;
    }

    @Override
    public void initialization ()
    {
        rng = new Random ();
        int nCP = problem.getNControlPoints ();
        d = 2 * nCP;

        double [] lb = new double [d], ub = new double [d];
        for (int i = 0; i < d; i++)
        {
            if (i % 2 == 0) { lb[i] = problem.getMinX() - margin; ub[i] = problem.getMaxX() + margin; }
            else             { lb[i] = problem.getMinY() - margin; ub[i] = problem.getMaxY() + margin; }
        }
        bounds = new BoundsChecker (lb, ub);
        lambda0 = 4 + (int)(3.0 * Math.log(d));
        sigma0 = (ub[0] - lb[0]) / 6.0;
        chiN = Math.sqrt(d)*(1.0-1.0/(4.0*d)+1.0/(21.0*d*d));

        startX = problem.getStartPoint().getX();
        startY = problem.getStartPoint().getY();
        endX   = problem.getEndPoint().getX();
        endY   = problem.getEndPoint().getY();

        bestFitness = Double.POSITIVE_INFINITY;
        bestX = null;
        currentIsland = 0;
        totalLoops = 0;

        // Seeding phase
        double [] initMean = new double [d];
        for (int i = 0; i < nCP; i++)
        {
            double t = (double)(i+1)/(nCP+1);
            initMean[2*i] = startX+t*(endX-startX);
            initMean[2*i+1] = startY+t*(endY-startY);
        }

        ArrayList<double []> seeds = generateDiverseSeeds();
        seeds.add(0, initMean);

        double [] bestSeed = null;
        double bestSeedF = Double.POSITIVE_INFINITY;
        ArrayList<double []> evaluated = new ArrayList<>();
        ArrayList<Double> fitnesses = new ArrayList<>();

        for (double [] seed : seeds)
        {
            bounds.clampInPlace(seed);
            double f = problem.evaluate(seed);
            updateBest(seed, f);
            evaluated.add(seed);
            fitnesses.add(f);
            if (f < bestSeedF) { bestSeedF = f; bestSeed = seed.clone(); }
        }

        Integer [] indices = new Integer [evaluated.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a,b) -> Double.compare(fitnesses.get(a), fitnesses.get(b)));
        cachedSeeds = new ArrayList<>();
        for (int i = 0; i < Math.min(25, indices.length); i++)
            cachedSeeds.add(evaluated.get(indices[i]).clone());

        int nLocal = Math.min(5, cachedSeeds.size());
        double [] localBestX = bestSeed.clone();
        double localBestF = bestSeedF;
        for (int s = 0; s < nLocal; s++)
        {
            double [] result = quickLocalOptimize(cachedSeeds.get(s).clone(), 800);
            double fRes = problem.evaluate(result);
            updateBest(result, fRes);
            if (fRes < localBestF) { localBestF = fRes; localBestX = result.clone(); }
        }

        // Initialiser les 3 îles avec des sigmas différents
        isLambda = new int [N_ISLANDS]; isMu = new int [N_ISLANDS];
        isWeights = new double [N_ISLANDS][];
        isMueff = new double [N_ISLANDS];
        isCsig = new double [N_ISLANDS]; isDsig = new double [N_ISLANDS];
        isCc = new double [N_ISLANDS]; isC1 = new double [N_ISLANDS]; isCmu = new double [N_ISLANDS];
        isMean = new double [N_ISLANDS][];
        isSigma = new double [N_ISLANDS];
        isC = new double [N_ISLANDS][][]; isB = new double [N_ISLANDS][][]; isInvsqrtC = new double [N_ISLANDS][][];
        isDiagD = new double [N_ISLANDS][]; isPs = new double [N_ISLANDS][]; isPc = new double [N_ISLANDS][];
        isGeneration = new int [N_ISLANDS]; isEigenCounter = new int [N_ISLANDS];
        isBestFitness = new double [N_ISLANDS]; isBestX = new double [N_ISLANDS][];
        isStagnation = new int [N_ISLANDS]; isMaxStagnation = new int [N_ISLANDS];
        isPrevBestGen = new double [N_ISLANDS]; isRestartCount = new int [N_ISLANDS];
        migrantX = new double [N_ISLANDS][]; migrantF = new double [N_ISLANDS];

        double [] sigmas = {sigma0 / 4.0, sigma0, sigma0 * 2.0};

        for (int is = 0; is < N_ISLANDS; is++)
        {
            double [] startMean;
            if (is == 0) startMean = localBestX.clone();
            else if (is == 1 && cachedSeeds.size() > 1)
            {
                startMean = cachedSeeds.get(1).clone();
                for (int i = 0; i < d; i++) startMean[i] += rng.nextGaussian()*bounds.getRange(i)*0.05;
                bounds.clampInPlace(startMean);
            }
            else
            {
                startMean = problem.getRandomControlPoints1DArray();
                bounds.clampInPlace(startMean);
            }

            setupIsland(is, lambda0, startMean, sigmas[is]);
            isBestFitness[is] = Double.POSITIVE_INFINITY;
            isBestX[is] = null;
            isRestartCount[is] = 0;
            migrantX[is] = null;
            migrantF[is] = Double.POSITIVE_INFINITY;
        }
    }

    @Override
    public void loop ()
    {
        int is = currentIsland;

        // Exécuter une génération sur l'île courante
        stepIsland (is);

        // Migration ?
        if (isGeneration [is] > 0 && isGeneration [is] % T_MIGRATE == 0)
        {
            // Envoyer le meilleur de chaque île vers la suivante (ring)
            for (int from = 0; from < N_ISLANDS; from++)
            {
                int to = (from + 1) % N_ISLANDS;
                if (isBestX [from] != null)
                {
                    // Injecter comme perturbation du mean de l'île destination
                    double [] migrated = isBestX [from].clone ();
                    for (int i = 0; i < d; i++)
                        migrated [i] += rng.nextGaussian () * isSigma [to] * 0.1;
                    bounds.clampInPlace (migrated);
                    double fMig = problem.evaluate (migrated);
                    updateBest (migrated, fMig);
                    if (fMig < isBestFitness [to])
                    {
                        isBestFitness [to] = fMig;
                        isBestX [to] = migrated.clone ();
                    }
                }
            }
        }

        // Tour suivant
        totalLoops++;
        currentIsland = (currentIsland + 1) % N_ISLANDS;
    }

    private void stepIsland (int is)
    {
        int lam = isLambda [is];
        int mu = isMu [is];
        double sig = isSigma [is];
        double [] mn = isMean [is];
        double [][] Bi = isB [is];
        double [] dD = isDiagD [is];

        double [][] arx = new double [lam][d];
        double [][] ary = new double [lam][d];
        double [] fitness = new double [lam];

        for (int k = 0; k < lam; k++)
        {
            double [] z = new double [d];
            for (int i = 0; i < d; i++) z[i] = rng.nextGaussian();
            double [] Dz = new double [d];
            for (int i = 0; i < d; i++) Dz[i] = dD[i]*z[i];
            for (int i = 0; i < d; i++)
            {
                double sum = 0;
                for (int j = 0; j < d; j++) sum += Bi[i][j]*Dz[j];
                ary[k][i] = sum;
                arx[k][i] = mn[i]+sig*sum;
            }
            bounds.clampInPlace(arx[k]);
            fitness[k] = problem.evaluate(arx[k]);
            updateBest(arx[k], fitness[k]);
            if (fitness[k] < isBestFitness[is]) { isBestFitness[is]=fitness[k]; isBestX[is]=arx[k].clone(); }
        }

        Integer [] idx = new Integer [lam];
        for (int i = 0; i < lam; i++) idx[i] = i;
        Arrays.sort(idx, (a,b) -> Double.compare(fitness[a], fitness[b]));

        double [] oldMean = mn.clone();
        double [] newMean = new double [d];
        for (int j = 0; j < mu; j++) { int ii=idx[j]; for(int i=0;i<d;i++) newMean[i]+=isWeights[is][j]*arx[ii][i]; }
        isMean[is] = newMean;

        double [] meanDiffNorm = new double [d];
        for (int i = 0; i < d; i++) meanDiffNorm[i] = (newMean[i]-oldMean[i])/sig;

        double [] invDiff = matVecMul(isInvsqrtC[is], meanDiffNorm);
        double csigFac = Math.sqrt(isCsig[is]*(2.0-isCsig[is])*isMueff[is]);
        double [] ps = isPs[is];
        for (int i = 0; i < d; i++) ps[i] = (1.0-isCsig[is])*ps[i]+csigFac*invDiff[i];

        double psNorm = vecNorm(ps);
        double hsigThresh = (1.4+2.0/(d+1.0))*chiN*Math.sqrt(1.0-Math.pow(1.0-isCsig[is],2.0*(isGeneration[is]+1)));
        int hsig = (psNorm < hsigThresh) ? 1 : 0;

        double ccFac = Math.sqrt(isCc[is]*(2.0-isCc[is])*isMueff[is]);
        double [] pc = isPc[is];
        for (int i = 0; i < d; i++) pc[i] = (1.0-isCc[is])*pc[i]+hsig*ccFac*meanDiffNorm[i];

        double deltaHsig = (1-hsig)*isCc[is]*(2.0-isCc[is]);
        double cOld = 1.0-isC1[is]-isCmu[is]+deltaHsig*isC1[is];
        double [][] Ci = isC[is];
        for (int i = 0; i < d; i++)
            for (int j = 0; j <= i; j++)
            {
                double rank1 = isC1[is]*pc[i]*pc[j];
                double rankmu = 0;
                for (int k = 0; k < mu; k++) { int ii=idx[k]; rankmu+=isWeights[is][k]*ary[ii][i]*ary[ii][j]; }
                Ci[i][j] = cOld*Ci[i][j]+rank1+isCmu[is]*rankmu; Ci[j][i]=Ci[i][j];
            }

        sig *= Math.exp((isCsig[is]/isDsig[is])*(psNorm/chiN-1.0));
        sig = Math.max(sig,1e-20); sig = Math.min(sig,1e6);
        isSigma[is] = sig;

        isEigenCounter[is]++;
        if (isEigenCounter[is] >= 1) { eigenDecomposition(is); isEigenCounter[is] = 0; }

        isGeneration[is]++;

        double genBest = fitness[idx[0]];
        if (genBest < isPrevBestGen[is]-1e-12) { isStagnation[is]=0; isPrevBestGen[is]=genBest; }
        else isStagnation[is]++;

        if (shouldRestart(is)) restartIsland(is);
    }

    private void setupIsland (int is, int newLambda, double [] startMean, double startSigma)
    {
        isLambda[is] = newLambda;
        isMu[is] = newLambda/2;
        isSigma[is] = startSigma;
        isMean[is] = startMean.clone();
        int mu = isMu[is];

        double [] w = new double [mu]; double sumW=0;
        for (int i = 0; i < mu; i++) { w[i]=Math.log(mu+0.5)-Math.log(i+1.0); sumW+=w[i]; }
        for (int i = 0; i < mu; i++) w[i]/=sumW;
        isWeights[is] = w;
        double sumW2=0; for (int i=0;i<mu;i++) sumW2+=w[i]*w[i];
        isMueff[is] = 1.0/sumW2;
        double mueff = isMueff[is];

        isCsig[is] = (mueff+2.0)/(d+mueff+5.0);
        isDsig[is] = 1.0+2.0*Math.max(0,Math.sqrt((mueff-1.0)/(d+1.0))-1.0)+isCsig[is];
        isCc[is] = (4.0+mueff/d)/(d+4.0+2.0*mueff/d);
        isC1[is] = 2.0/((d+1.3)*(d+1.3)+mueff);
        isCmu[is] = Math.min(1.0-isC1[is],2.0*(mueff-2.0+1.0/mueff)/((d+2.0)*(d+2.0)+mueff));

        isPs[is] = new double[d]; isPc[is] = new double[d];
        isC[is] = new double[d][d]; isB[is] = new double[d][d];
        isDiagD[is] = new double[d]; isInvsqrtC[is] = new double[d][d];
        for (int i=0;i<d;i++){isC[is][i][i]=1;isB[is][i][i]=1;isDiagD[is][i]=1;isInvsqrtC[is][i][i]=1;}

        isGeneration[is] = 0; isEigenCounter[is] = 0;
        isStagnation[is] = 0; isMaxStagnation[is] = 10+(int)(30.0*d/newLambda);
        isPrevBestGen[is] = Double.POSITIVE_INFINITY;
    }

    private boolean shouldRestart (int is)
    {
        if (isStagnation[is]>isMaxStagnation[is]) return true;
        double maxD=isDiagD[is][0],minD=isDiagD[is][0];
        for(int i=1;i<d;i++){if(isDiagD[is][i]>maxD)maxD=isDiagD[is][i];if(isDiagD[is][i]<minD)minD=isDiagD[is][i];}
        if(minD>0&&(maxD/minD)>1e7)return true;
        if(isSigma[is]*maxD<1e-12)return true;
        return false;
    }

    private void restartIsland (int is)
    {
        isRestartCount[is]++;
        int newLambda = lambda0*(1<<Math.min(isRestartCount[is],6));
        newLambda = Math.min(newLambda, 256); // plus petit que les autres — on a 3 îles
        double [] newMean; double newSigma;

        // Varier la stratégie selon l'île
        double [] sigmas = {sigma0/4.0, sigma0, sigma0*2.0};
        newSigma = sigmas[is];

        double choice = rng.nextDouble();
        if (choice<0.35 && cachedSeeds!=null && !cachedSeeds.isEmpty())
        {
            int idx = (isRestartCount[is]+is) % cachedSeeds.size();
            newMean = quickLocalOptimize(cachedSeeds.get(idx).clone(), 400);
        }
        else if (choice<0.65 && bestX!=null)
        {
            newMean = bestX.clone();
            for(int i=0;i<d;i++) newMean[i]+=rng.nextGaussian()*bounds.getRange(i)*0.15;
            bounds.clampInPlace(newMean);
        }
        else
        {
            double[]bSeed=problem.getRandomControlPoints1DArray();bounds.clampInPlace(bSeed);
            double bF=problem.evaluate(bSeed);updateBest(bSeed,bF);
            for(int r=0;r<5;r++){double[]s=problem.getRandomControlPoints1DArray();bounds.clampInPlace(s);double f=problem.evaluate(s);updateBest(s,f);if(f<bF){bF=f;bSeed=s;}}
            newMean=bSeed;
        }

        setupIsland(is, newLambda, newMean, newSigma);
        isBestFitness[is] = Double.POSITIVE_INFINITY;
        isBestX[is] = null;
    }

    private void eigenDecomposition (int is)
    {
        double[][]V=new double[d][d];for(int i=0;i<d;i++)for(int j=0;j<d;j++)V[i][j]=isC[is][i][j];
        double[]dd=new double[d],ee=new double[d];tred2(V,dd,ee);tql2(V,dd,ee);
        for(int i=0;i<d;i++)isDiagD[is][i]=Math.sqrt(Math.max(dd[i],1e-20));
        isB[is]=V;
        for(int i=0;i<d;i++)for(int j=0;j<=i;j++){double sum=0;for(int k=0;k<d;k++)sum+=V[i][k]*(1.0/isDiagD[is][k])*V[j][k];isInvsqrtC[is][i][j]=sum;isInvsqrtC[is][j][i]=sum;}
    }

    // ===== Seeding & utilities =====

    private ArrayList<double []> generateDiverseSeeds()
    {
        int nCP=d/2; ArrayList<double[]>seeds=new ArrayList<>();
        double lbY=bounds.getLb()[1],ubY=bounds.getUb()[1],centerY=(lbY+ubY)/2.0,halfY=(ubY-lbY)/2.0;
        double[]periods={0.5,1.0,1.5,2.0,2.5};double[]phases={0,Math.PI/4,Math.PI/2,3*Math.PI/4,Math.PI,5*Math.PI/4,3*Math.PI/2,7*Math.PI/4};double[]amplitudes={0.35,0.65,0.85,0.95};
        for(double per:periods)for(double ph:phases)for(double amp:amplitudes)
        {double[]p=new double[d];for(int i=0;i<nCP;i++){double t=(double)(i+1)/(nCP+1);p[2*i]=startX+t*(endX-startX);p[2*i+1]=centerY+amp*halfY*Math.sin(2*Math.PI*per*t+ph);}seeds.add(p);}
        double lbX=bounds.getLb()[0],ubX=bounds.getUb()[0];
        double[]yLevels={lbY,lbY+2,lbY+5,centerY,ubY-5,ubY-2,ubY};
        for(double yVal:yLevels){double[]p=new double[d];for(int i=0;i<nCP;i++){p[2*i]=(i%2==0)?ubX:lbX;p[2*i+1]=yVal;}seeds.add(p);}
        for(int r=0;r<30;r++){double[]p=new double[d];for(int i=0;i<nCP;i++){p[2*i]=rng.nextBoolean()?lbX:ubX;p[2*i+1]=lbY+rng.nextDouble()*(ubY-lbY);}seeds.add(p);}
        for(int r=0;r<20;r++) seeds.add(problem.getRandomControlPoints1DArray());
        return seeds;
    }

    private double [] quickLocalOptimize(double[]x0,int maxEvals)
    {
        double[]x=x0.clone();bounds.clampInPlace(x);double fx=problem.evaluate(x);updateBest(x,fx);
        double step=sigma0/2.0;int succ=0,win=0;
        for(int e=0;e<maxEvals;e++){double[]xN=new double[d];for(int i=0;i<d;i++)xN[i]=x[i]+rng.nextGaussian()*step;bounds.clampInPlace(xN);double fN=problem.evaluate(xN);updateBest(xN,fN);if(fN<fx){x=xN;fx=fN;succ++;}win++;if(win>=20){if((double)succ/win>0.2)step*=1.3;else step*=0.7;step=Math.max(step,1e-10);step=Math.min(step,sigma0*2);succ=0;win=0;}}
        return x;
    }

    private void tred2(double[][]V,double[]d,double[]e){int n=this.d;for(int j=0;j<n;j++)d[j]=V[n-1][j];for(int i=n-1;i>0;i--){double scale=0,h=0;for(int k=0;k<i;k++)scale+=Math.abs(d[k]);if(scale==0.0){e[i]=d[i-1];for(int j=0;j<i;j++){d[j]=V[i-1][j];V[i][j]=0;V[j][i]=0;}}else{for(int k=0;k<i;k++){d[k]/=scale;h+=d[k]*d[k];}double f=d[i-1],g=Math.sqrt(h);if(f>0)g=-g;e[i]=scale*g;h-=f*g;d[i-1]=f-g;for(int j=0;j<i;j++)e[j]=0;for(int j=0;j<i;j++){f=d[j];V[j][i]=f;g=e[j]+V[j][j]*f;for(int k=j+1;k<=i-1;k++){g+=V[k][j]*d[k];e[k]+=V[k][j]*f;}e[j]=g;}f=0;for(int j=0;j<i;j++){e[j]/=h;f+=e[j]*d[j];}double hh=f/(h+h);for(int j=0;j<i;j++)e[j]-=hh*d[j];for(int j=0;j<i;j++){f=d[j];g=e[j];for(int k=j;k<=i-1;k++)V[k][j]-=f*e[k]+g*d[k];d[j]=V[i-1][j];V[i][j]=0;}}d[i]=h;}for(int i=0;i<n-1;i++){V[n-1][i]=V[i][i];V[i][i]=1;double h=d[i+1];if(h!=0){for(int k=0;k<=i;k++)d[k]=V[k][i+1]/h;for(int j=0;j<=i;j++){double g=0;for(int k=0;k<=i;k++)g+=V[k][i+1]*V[k][j];for(int k=0;k<=i;k++)V[k][j]-=g*d[k];}}for(int k=0;k<=i;k++)V[k][i+1]=0;}for(int j=0;j<n;j++){d[j]=V[n-1][j];V[n-1][j]=0;}V[n-1][n-1]=1;e[0]=0;}
    private void tql2(double[][]V,double[]d,double[]e){int n=this.d;for(int i=1;i<n;i++)e[i-1]=e[i];e[n-1]=0;double f=0,tst1=0,eps=Math.pow(2.0,-52.0);for(int l=0;l<n;l++){tst1=Math.max(tst1,Math.abs(d[l])+Math.abs(e[l]));int m=l;while(m<n){if(Math.abs(e[m])<=eps*tst1)break;m++;}if(m>l){int iter=0;do{iter++;double g=d[l],p=(d[l+1]-g)/(2.0*e[l]);double r=Math.hypot(p,1.0);if(p<0)r=-r;d[l]=e[l]/(p+r);d[l+1]=e[l]*(p+r);double dl1=d[l+1],h=g-d[l];for(int i=l+2;i<n;i++)d[i]-=h;f+=h;p=d[m];double c=1,c2=c,c3=c;double el1=e[l+1];double s=0,s2=0;for(int i=m-1;i>=l;i--){c3=c2;c2=c;s2=s;g=c*e[i];h=c*p;r=Math.hypot(p,e[i]);e[i+1]=s*r;s=e[i]/r;c=p/r;p=c*d[i]-s*g;d[i+1]=h+s*(c*g+s*d[i]);for(int k=0;k<n;k++){h=V[k][i+1];V[k][i+1]=s*V[k][i]+c*h;V[k][i]=c*V[k][i]-s*h;}}p=-s*s2*c3*el1*e[l]/dl1;e[l]=s*p;d[l]=c*p;}while(Math.abs(e[l])>eps*tst1 && iter<200*n);}d[l]=d[l]+f;e[l]=0;}for(int i=0;i<n-1;i++){int k=i;double p=d[i];for(int j=i+1;j<n;j++)if(d[j]<p){k=j;p=d[j];}if(k!=i){d[k]=d[i];d[i]=p;for(int j=0;j<n;j++){p=V[j][i];V[j][i]=V[j][k];V[j][k]=p;}}}}

    private double [] matVecMul(double[][]M,double[]v){double[]r=new double[d];for(int i=0;i<d;i++)for(int j=0;j<d;j++)r[i]+=M[i][j]*v[j];return r;}
    private double vecNorm(double[]v){double s=0;for(double vi:v)s+=vi*vi;return Math.sqrt(s);}
    private void updateBest(double[]x,double f){if(f<bestFitness){bestFitness=f;bestX=x.clone();}}
}
