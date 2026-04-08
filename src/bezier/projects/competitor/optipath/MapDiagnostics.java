package bezier.projects.competitor.optipath;

import java.util.ArrayList;

/**
 * Diagnostic structurel de carte pour piloter le portefeuille de seeding.
 */
public final class MapDiagnostics
{
    public final double obstacleDensity;
    public final double straightBlockageRatio;
    public final double estimatedCorridorWidth;
    public final double wallAlignmentConfidence;
    public final double pathStretch;
    public final double alternatingCorridorConfidence;

    private MapDiagnostics (double obstacleDensity,
                            double straightBlockageRatio,
                            double estimatedCorridorWidth,
                            double wallAlignmentConfidence,
                            double pathStretch,
                            double alternatingCorridorConfidence)
    {
        this.obstacleDensity = obstacleDensity;
        this.straightBlockageRatio = straightBlockageRatio;
        this.estimatedCorridorWidth = estimatedCorridorWidth;
        this.wallAlignmentConfidence = wallAlignmentConfidence;
        this.pathStretch = pathStretch;
        this.alternatingCorridorConfidence = alternatingCorridorConfidence;
    }

    public static MapDiagnostics compute (double sx, double sy,
                                          double ex, double ey,
                                          double minX, double minY,
                                          double maxX, double maxY,
                                          double [] ox, double [] oy, double [] or_)
    {
        int nObs = (ox == null) ? 0 : ox.length;
        if (nObs == 0)
            return new MapDiagnostics (0.0, 0.0,
                    Math.max (1e-9, Math.min (maxX - minX, maxY - minY)),
                    0.0, 1.0, 0.0);

        double area = Math.max (1e-9, (maxX - minX) * (maxY - minY));
        double obstacleArea = 0.0;
        double avgR = 0.0;
        for (int i = 0; i < nObs; i++)
        {
            obstacleArea += Math.PI * or_[i] * or_[i];
            avgR += or_[i];
        }
        avgR /= nObs;
        double density = clamp01 (obstacleArea / area);

        double blockage = straightBlockageRatio (sx, sy, ex, ey, ox, oy, or_, 192, 0.0);
        double minGap = estimateMinPositiveGap (ox, oy, or_);
        double corridorWidth = (Double.isFinite (minGap) && minGap > 0.0)
                ? minGap : (2.5 * avgR);
        corridorWidth = Math.max (0.1, corridorWidth);

        WallStats wallStats = detectAlignedWalls (sx, sy, ex, ey, ox, oy, or_);
        double pathStretch = computePathStretchGrid (sx, sy, ex, ey, minX, minY, maxX, maxY, ox, oy, or_);

        return new MapDiagnostics (density, blockage, corridorWidth,
                clamp01 (wallStats.alignmentConfidence),
                Math.max (1.0, pathStretch),
                clamp01 (wallStats.alternatingConfidence));
    }

    public boolean shouldEnableSpecializedZigzag ()
    {
        return this.wallAlignmentConfidence >= 0.55
                && this.pathStretch >= 1.35
                && this.alternatingCorridorConfidence >= 0.30;
    }

    public boolean hasStrongStructure ()
    {
        return (this.wallAlignmentConfidence >= 0.45 && this.pathStretch >= 1.2)
                || (this.pathStretch >= 1.8 && this.straightBlockageRatio >= 0.25);
    }

    private static double straightBlockageRatio (double sx, double sy, double ex, double ey,
                                                 double [] ox, double [] oy, double [] or_,
                                                 int samples,
                                                 double inflate)
    {
        int blocked = 0;
        int total = Math.max (2, samples);
        for (int k = 0; k < total; k++)
        {
            double t = (double) k / (total - 1);
            double x = sx + t * (ex - sx);
            double y = sy + t * (ey - sy);
            if (isBlocked (x, y, ox, oy, or_, inflate)) blocked++;
        }
        return (double) blocked / total;
    }

    private static double estimateMinPositiveGap (double [] ox, double [] oy, double [] or_)
    {
        int n = ox.length;
        double minGap = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
            {
                double dist = Math.hypot (ox[i] - ox[j], oy[i] - oy[j]);
                double gap = dist - (or_[i] + or_[j]);
                if (gap > 0.0 && gap < minGap) minGap = gap;
            }
        return minGap;
    }

    private static final class WallStats
    {
        final double alignmentConfidence;
        final double alternatingConfidence;

        WallStats (double alignmentConfidence, double alternatingConfidence)
        {
            this.alignmentConfidence = alignmentConfidence;
            this.alternatingConfidence = alternatingConfidence;
        }
    }

    private static WallStats detectAlignedWalls (double sx, double sy, double ex, double ey,
                                                 double [] ox, double [] oy, double [] or_)
    {
        int n = ox.length;
        double meanR = 0.0;
        for (double r : or_) meanR += r;
        meanR /= Math.max (1, n);
        double tol = Math.max (0.5, 1.5 * meanR);

        boolean horizontalPath = Math.abs (ex - sx) >= Math.abs (ey - sy);
        double [] primary = horizontalPath ? ox : oy;
        double [] secondary = horizontalPath ? oy : ox;
        double pathPrimaryStart = horizontalPath ? sx : sy;
        double pathPrimaryDelta = horizontalPath ? (ex - sx) : (ey - sy);
        double pathSecondaryStart = horizontalPath ? sy : sx;
        double pathSecondaryDelta = horizontalPath ? (ey - sy) : (ex - sx);

        boolean [] used = new boolean [n];
        ArrayList<double []> walls = new ArrayList<> (); // [primaryMean, secondaryMean, size]

        for (int i = 0; i < n; i++)
        {
            if (used[i]) continue;
            ArrayList<Integer> group = new ArrayList<> ();
            group.add (i);
            used[i] = true;
            for (int j = i + 1; j < n; j++)
                if (!used[j] && Math.abs (primary[j] - primary[i]) <= tol)
                {
                    used[j] = true;
                    group.add (j);
                }

            if (group.size () < 3) continue;

            double p = 0.0, s = 0.0;
            for (int idx : group)
            {
                p += primary[idx];
                s += secondary[idx];
            }
            p /= group.size ();
            s /= group.size ();
            walls.add (new double [] {p, s, group.size ()});
        }

        int strongest = 0;
        for (double [] wall : walls)
            strongest = Math.max (strongest, (int) wall[2]);
        double alignConf = clamp01 ((strongest - 2.0) / 8.0);
        if (walls.isEmpty ()) return new WallStats (alignConf, 0.0);

        walls.sort ((a, b) -> Double.compare (a[0], b[0]));
        int signChanges = 0;
        int validPairs = 0;
        double prevSign = 0.0;
        boolean hasPrev = false;
        for (double [] wall : walls)
        {
            double primT = (Math.abs (pathPrimaryDelta) > 1e-9)
                    ? (wall[0] - pathPrimaryStart) / pathPrimaryDelta
                    : 0.5;
            primT = Math.max (0.0, Math.min (1.0, primT));
            double straightSecondary = pathSecondaryStart + primT * pathSecondaryDelta;
            double delta = wall[1] - straightSecondary;
            if (Math.abs (delta) < 0.5 * meanR) continue;
            double sign = (delta >= 0.0) ? 1.0 : -1.0;
            if (hasPrev)
            {
                validPairs++;
                if (sign * prevSign < 0.0) signChanges++;
            }
            prevSign = sign;
            hasPrev = true;
        }
        double alt = (validPairs > 0) ? ((double) signChanges / validPairs) : 0.0;
        double altConf = clamp01 (alt * Math.max (alignConf, 0.25));
        return new WallStats (alignConf, altConf);
    }

    private static double computePathStretchGrid (double sx, double sy, double ex, double ey,
                                                  double minX, double minY, double maxX, double maxY,
                                                  double [] ox, double [] oy, double [] or_)
    {
        double direct = Math.hypot (ex - sx, ey - sy);
        if (direct < 1e-9) return 1.0;

        int grid = 72;
        double dx = (maxX - minX) / Math.max (1, grid - 1);
        double dy = (maxY - minY) / Math.max (1, grid - 1);
        if (dx <= 0.0 || dy <= 0.0) return 1.0;

        boolean [] blocked = new boolean [grid * grid];
        double inflate = 1.0;
        for (int gy = 0; gy < grid; gy++)
        {
            double y = minY + gy * dy;
            for (int gx = 0; gx < grid; gx++)
            {
                double x = minX + gx * dx;
                blocked[gy * grid + gx] = isBlocked (x, y, ox, oy, or_, inflate);
            }
        }

        int sxi = clampInt ((int) Math.round ((sx - minX) / dx), 0, grid - 1);
        int syi = clampInt ((int) Math.round ((sy - minY) / dy), 0, grid - 1);
        int exi = clampInt ((int) Math.round ((ex - minX) / dx), 0, grid - 1);
        int eyi = clampInt ((int) Math.round ((ey - minY) / dy), 0, grid - 1);
        int start = syi * grid + sxi;
        int goal = eyi * grid + exi;
        blocked[start] = false;
        blocked[goal] = false;

        int n = grid * grid;
        double [] g = new double [n];
        boolean [] closed = new boolean [n];
        for (int i = 0; i < n; i++) g[i] = Double.POSITIVE_INFINITY;

        class Node
        {
            final int id;
            final double f;
            Node (int id, double f) { this.id = id; this.f = f; }
        }

        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<> ((a, b) -> Double.compare (a.f, b.f));
        g[start] = 0.0;
        open.add (new Node (start, heuristic (sxi, syi, exi, eyi)));

        int [] dxs = {-1, 0, 1, -1, 1, -1, 0, 1};
        int [] dys = {-1, -1, -1, 0, 0, 1, 1, 1};
        while (!open.isEmpty ())
        {
            Node cur = open.poll ();
            if (closed[cur.id]) continue;
            closed[cur.id] = true;
            if (cur.id == goal) break;
            int cx = cur.id % grid;
            int cy = cur.id / grid;
            for (int k = 0; k < 8; k++)
            {
                int nx = cx + dxs[k];
                int ny = cy + dys[k];
                if (nx < 0 || ny < 0 || nx >= grid || ny >= grid) continue;
                int nid = ny * grid + nx;
                if (blocked[nid] || closed[nid]) continue;
                double step = Math.hypot (dxs[k], dys[k]);
                double cand = g[cur.id] + step;
                if (cand + 1e-12 < g[nid])
                {
                    g[nid] = cand;
                    open.add (new Node (nid, cand + heuristic (nx, ny, exi, eyi)));
                }
            }
        }

        if (!Double.isFinite (g[goal])) return 2.5;
        double pathLen = g[goal] * Math.min (dx, dy);
        return Math.max (1.0, pathLen / direct);
    }

    private static double heuristic (int x, int y, int tx, int ty)
    {
        return Math.hypot (x - tx, y - ty);
    }

    private static int clampInt (int v, int lo, int hi)
    {
        return Math.max (lo, Math.min (hi, v));
    }

    private static boolean isBlocked (double x, double y,
                                      double [] ox, double [] oy, double [] or_,
                                      double inflate)
    {
        for (int i = 0; i < ox.length; i++)
        {
            double dx = x - ox[i];
            double dy = y - oy[i];
            double rr = or_[i] + inflate;
            if (dx * dx + dy * dy <= rr * rr) return true;
        }
        return false;
    }

    private static double clamp01 (double x)
    {
        return Math.max (0.0, Math.min (1.0, x));
    }
}
