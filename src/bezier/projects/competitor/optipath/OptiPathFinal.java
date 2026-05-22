package bezier.projects.competitor.optipath;

import bezier.evaluation.Problem;
import bezier.projects.CompetitorProject;
import bezier.projects.InvalidProjectException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;

public final class OptiPathFinal extends CompetitorProject {

    private static final long FALLBACK_MS = 25_000L;
    private static final int  GRID        = 100;

    // Two internal optimizers
    private LSHADECore         lshade;
    private Optimizer          cmaes;

    private boolean lshadeActive;
    private boolean cmaesActive;
    private boolean fallbackDone;
    private long    startTime;

    public OptiPathFinal(Problem problem) throws InvalidProjectException {
        super(problem);
        addAuthor("BENSAADI");
        addAuthor("RAHALI");
        setMethodName("Fast&Bezier");
    }

    @Override
    public void initialization() {
        startTime    = System.currentTimeMillis();
        lshadeActive = true;
        cmaesActive  = true;
        fallbackDone = false;

        int    nCP    = problem.getNControlPoints();
        int    d      = 2 * nCP;
        double margin = 8.0;
        double[] lb   = new double[d];
        double[] ub   = new double[d];
        for (int i = 0; i < d; i++) {
            lb[i] = (i % 2 == 0) ? problem.getMinX() - margin : problem.getMinY() - margin;
            ub[i] = (i % 2 == 0) ? problem.getMaxX() + margin : problem.getMaxY() + margin;
        }

        lshade = new LSHADECore(problem, d, lb, ub, 500_000);
        lshade.init();

        AlgorithmParameters params = new AlgorithmParameters();
        params.setMargin(1.0);
        params.setInitStrategy(AlgorithmParameters.InitStrategy.CENTER_LINE);

        cmaes = CMAESBuilder.ipop(problem)
                .parameters(params)
                .evaluator(new ProgressivePenaltyEval(problem))
                .sampling(new MirrorSampling())
                .initMean(computeAStarMean(nCP))
                .build();
        cmaes.init();
    }

    @Override
    public void loop() {
        if (!fallbackDone && System.currentTimeMillis() - startTime >= FALLBACK_MS) {
            fallbackDone = true;
            boolean lsFeasible = isFeasible(lshade.getBestX());
            boolean cmFeasible = isFeasible(cmaes.getBestX());
            if (lsFeasible && !cmFeasible)  cmaesActive  = false;
            else if (cmFeasible && !lsFeasible) lshadeActive = false;
        }

        if (lshadeActive) lshade.step();
        if (cmaesActive) {
            cmaes.step();
            if (cmaes.shouldRestart()) cmaes.init();
        }
    }

    private boolean isFeasible(double[] x) {
        if (x == null) return false;
        double[] obj = problem.evaluateMulti(x);
        if (obj == null || obj.length < 4) return false;
        return Math.max(0.0, obj[1]) + Math.max(0.0, obj[3]) < 1e-9;
    }

    // ── A* init mean for CMAES ────────────────────────────────────────────────

    private double[] computeAStarMean(int nCP) {
        double inflate = Math.max(0.06, estimateDiag() * 0.06);
        ArrayList<double[]> path = computeGridPath(inflate);
        if (path == null || path.size() < 2) return defaultMean(nCP);
        return interpCP(path, nCP);
    }

    private double estimateDiag() {
        return Math.hypot(problem.getMaxX() - problem.getMinX(),
                          problem.getMaxY() - problem.getMinY());
    }

    private double[] defaultMean(int nCP) {
        double[] p  = new double[2 * nCP];
        double   sx = problem.getStartPoint().getX(), sy = problem.getStartPoint().getY();
        double   ex = problem.getEndPoint().getX(),   ey = problem.getEndPoint().getY();
        for (int i = 0; i < nCP; i++) {
            double t = (i + 1.0) / (nCP + 1.0);
            p[2 * i]     = sx + t * (ex - sx);
            p[2 * i + 1] = sy + t * (ey - sy);
        }
        return p;
    }

    private ArrayList<double[]> computeGridPath(double inflate) {
        int    grid = GRID;
        double mnX  = problem.getMinX(), mxX = problem.getMaxX();
        double mnY  = problem.getMinY(), mxY = problem.getMaxY();
        double stX  = (mxX - mnX) / Math.max(1, grid - 1);
        double stY  = (mxY - mnY) / Math.max(1, grid - 1);

        boolean[] blocked = new boolean[grid * grid];
        for (int gy = 0; gy < grid; gy++)
            for (int gx = 0; gx < grid; gx++)
                blocked[gy * grid + gx] = isBlocked(mnX + gx * stX, mnY + gy * stY, inflate);

        int sxi = clampI((int) Math.round((problem.getStartPoint().getX() - mnX) / stX), 0, grid - 1);
        int syi = clampI((int) Math.round((problem.getStartPoint().getY() - mnY) / stY), 0, grid - 1);
        int exi = clampI((int) Math.round((problem.getEndPoint().getX()   - mnX) / stX), 0, grid - 1);
        int eyi = clampI((int) Math.round((problem.getEndPoint().getY()   - mnY) / stY), 0, grid - 1);
        int start = syi * grid + sxi, goal = eyi * grid + exi;
        blocked[start] = false;
        blocked[goal]  = false;

        double[]  g      = new double[grid * grid];
        int[]     par    = new int[grid * grid];
        boolean[] closed = new boolean[grid * grid];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        Arrays.fill(par, -1);
        g[start] = 0;

        PriorityQueue<int[]> open = new PriorityQueue<>(
                (a, b) -> Double.compare(g[a[0]] + a[1] * 0.001, g[b[0]] + b[1] * 0.001));
        open.add(new int[]{start, (int) (Math.hypot(sxi - exi, syi - eyi) * 1000)});
        int[] dxs = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dys = {-1, -1, -1, 0, 0, 1, 1, 1};

        while (!open.isEmpty()) {
            int[] cur = open.poll();
            int   id  = cur[0];
            if (closed[id]) continue;
            closed[id] = true;
            if (id == goal) break;
            int cx = id % grid, cy = id / grid;
            for (int k = 0; k < 8; k++) {
                int nx = cx + dxs[k], ny = cy + dys[k];
                if (nx < 0 || ny < 0 || nx >= grid || ny >= grid) continue;
                int    nid  = ny * grid + nx;
                if (blocked[nid] || closed[nid]) continue;
                double cand = g[id] + Math.hypot(dxs[k] * stX, dys[k] * stY);
                if (cand + 1e-12 < g[nid]) {
                    g[nid]   = cand;
                    par[nid] = id;
                    open.add(new int[]{nid, (int) (Math.hypot(nx - exi, ny - eyi) * 1000)});
                }
            }
        }

        if (par[goal] == -1) return null;
        ArrayList<double[]> path = new ArrayList<>();
        int cur = goal;
        while (cur != -1) {
            path.add(0, new double[]{mnX + (cur % grid) * stX, mnY + (cur / grid) * stY});
            cur = par[cur];
        }
        return path;
    }

    private boolean isBlocked(double x, double y, double inflate) {
        double   eff  = inflate * 1.1;
        int      nCP  = problem.getNControlPoints();
        double[] test = new double[2 * nCP];
        double[][] cross = {{x, y}, {x + eff, y}, {x - eff, y}, {x, y + eff}, {x, y - eff}};
        for (int i = 0; i < nCP; i++) {
            test[2 * i]     = cross[i % cross.length][0];
            test[2 * i + 1] = cross[i % cross.length][1];
        }
        double[] obj = problem.evaluateMulti(test);
        return obj != null && obj.length >= 2 && obj[1] > 1e-9;
    }

    private double[] interpCP(ArrayList<double[]> poly, int nCP) {
        double[] out  = new double[2 * nCP];
        int      nSeg = poly.size() - 1;
        double[] segLen = new double[nSeg];
        double   total  = 0;
        for (int s = 0; s < nSeg; s++) {
            segLen[s] = Math.hypot(poly.get(s + 1)[0] - poly.get(s)[0],
                                   poly.get(s + 1)[1] - poly.get(s)[1]);
            total += segLen[s];
        }
        if (total < 1e-9) return defaultMean(nCP);

        int[] cpPerSeg = new int[nSeg];
        Arrays.fill(cpPerSeg, 1);
        int rem = nCP - nSeg;
        while (rem > 0) {
            int best = 0; double bestSc = -1;
            for (int s = 0; s < nSeg; s++) {
                double sc = segLen[s] / Math.max(1, cpPerSeg[s]);
                if (sc > bestSc) { bestSc = sc; best = s; }
            }
            cpPerSeg[best]++;
            rem--;
        }

        int cpIdx = 0;
        for (int s = 0; s < nSeg && cpIdx < nCP; s++) {
            int      c = Math.max(1, cpPerSeg[s]);
            double[] a = poly.get(s), b = poly.get(s + 1);
            for (int k = 0; k < c && cpIdx < nCP; k++) {
                double alpha = (k + 1.0) / (c + 1.0);
                out[2 * cpIdx]     = a[0] + alpha * (b[0] - a[0]);
                out[2 * cpIdx + 1] = a[1] + alpha * (b[1] - a[1]);
                cpIdx++;
            }
        }
        while (cpIdx < nCP) {
            out[2 * cpIdx]     = out[2 * cpIdx - 2];
            out[2 * cpIdx + 1] = out[2 * cpIdx - 1];
            cpIdx++;
        }
        return out;
    }

    private static int clampI(int v, int mn, int mx) {
        return Math.max(mn, Math.min(mx, v));
    }
}
