package bezier.projects.competitor.astarseeds;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * A* sur grille grossière pour générer des chemins topologiquement corrects.
 * Lit directement le fichier .bzr pour récupérer les positions et rayons des obstacles.
 */
public class AStarGrid
{
    private final int gridSize;
    private final double minX, maxX, minY, maxY;
    private final double cellW, cellH;
    private final boolean [][] blocked;

    // Obstacles lus depuis le fichier
    private final double [] obsX;
    private final double [] obsY;
    private final double [] obsR;

    /**
     * Construit la grille en lisant les obstacles du fichier .bzr.
     */
    public AStarGrid (String problemName, double minX, double maxX, double minY, double maxY,
                      int gridSize, double safetyMargin)
    {
        this.gridSize = gridSize;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.cellW = (maxX - minX) / gridSize;
        this.cellH = (maxY - minY) / gridSize;

        // Lire les obstacles depuis le fichier
        ArrayList<double []> obsList = new ArrayList<> ();
        try
        {
            File f = new File ("data" + File.separator + problemName + ".bzr");
            BufferedReader br = new BufferedReader (new FileReader (f));
            br.readLine (); // start
            br.readLine (); // end
            br.readLine (); // bounds
            br.readLine (); // nCP
            String line;
            while ((line = br.readLine ()) != null)
            {
                line = line.trim ();
                if (line.isEmpty ()) continue;
                String [] tokens = line.split (",");
                double ox = Double.parseDouble (tokens [0].trim ());
                double oy = Double.parseDouble (tokens [1].trim ());
                double or = Double.parseDouble (tokens [2].trim ());
                obsList.add (new double [] {ox, oy, or});
            }
            br.close ();
        }
        catch (Exception e)
        {
            // Si le fichier n'est pas lisible, on continue sans obstacles
        }

        obsX = new double [obsList.size ()];
        obsY = new double [obsList.size ()];
        obsR = new double [obsList.size ()];
        for (int i = 0; i < obsList.size (); i++)
        {
            obsX [i] = obsList.get (i) [0];
            obsY [i] = obsList.get (i) [1];
            obsR [i] = obsList.get (i) [2];
        }

        // Marquer les cellules bloquées
        blocked = new boolean [gridSize][gridSize];
        for (int gx = 0; gx < gridSize; gx++)
        {
            for (int gy = 0; gy < gridSize; gy++)
            {
                double cx = minX + (gx + 0.5) * cellW;
                double cy = minY + (gy + 0.5) * cellH;
                for (int o = 0; o < obsX.length; o++)
                {
                    double dx = cx - obsX [o];
                    double dy = cy - obsY [o];
                    double dist = Math.sqrt (dx * dx + dy * dy);
                    if (dist < obsR [o] + safetyMargin)
                    {
                        blocked [gx][gy] = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Exécute A* de start à goal et retourne une liste de waypoints (coordonnées monde).
     * Retourne null si aucun chemin trouvé.
     */
    public double [][] findPath (double startX, double startY, double goalX, double goalY)
    {
        int sx = worldToGridX (startX);
        int sy = worldToGridY (startY);
        int gx = worldToGridX (goalX);
        int gy = worldToGridY (goalY);

        sx = clampGrid (sx);
        sy = clampGrid (sy);
        gx = clampGrid (gx);
        gy = clampGrid (gy);

        // A* avec 8 directions
        double [][] gScore = new double [gridSize][gridSize];
        int [][] cameFromX = new int [gridSize][gridSize];
        int [][] cameFromY = new int [gridSize][gridSize];
        for (double [] row : gScore) Arrays.fill (row, Double.POSITIVE_INFINITY);
        for (int [] row : cameFromX) Arrays.fill (row, -1);
        for (int [] row : cameFromY) Arrays.fill (row, -1);

        gScore [sx][sy] = 0;

        // PQ : [fScore, x, y]
        PriorityQueue<double []> open = new PriorityQueue<> ((a, b) -> Double.compare (a [0], b [0]));
        open.add (new double [] {heuristic (sx, sy, gx, gy), sx, sy});

        int [][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        while (!open.isEmpty ())
        {
            double [] current = open.poll ();
            int cx = (int) current [1];
            int cy = (int) current [2];

            if (cx == gx && cy == gy)
            {
                // Reconstruire le chemin
                ArrayList<int []> path = new ArrayList<> ();
                int px = gx, py = gy;
                while (px != sx || py != sy)
                {
                    path.add (new int [] {px, py});
                    int nx = cameFromX [px][py];
                    int ny = cameFromY [px][py];
                    px = nx;
                    py = ny;
                }
                path.add (new int [] {sx, sy});

                // Inverser et convertir en coordonnées monde
                double [][] waypoints = new double [path.size ()][2];
                for (int i = 0; i < path.size (); i++)
                {
                    int [] p = path.get (path.size () - 1 - i);
                    waypoints [i][0] = minX + (p [0] + 0.5) * cellW;
                    waypoints [i][1] = minY + (p [1] + 0.5) * cellH;
                }
                return waypoints;
            }

            for (int [] dir : dirs)
            {
                int nx = cx + dir [0];
                int ny = cy + dir [1];
                if (nx < 0 || nx >= gridSize || ny < 0 || ny >= gridSize) continue;
                if (blocked [nx][ny]) continue;

                double moveCost = (dir [0] != 0 && dir [1] != 0) ? 1.414 : 1.0;
                double tentG = gScore [cx][cy] + moveCost;

                if (tentG < gScore [nx][ny])
                {
                    gScore [nx][ny] = tentG;
                    cameFromX [nx][ny] = cx;
                    cameFromY [nx][ny] = cy;
                    open.add (new double [] {tentG + heuristic (nx, ny, gx, gy), nx, ny});
                }
            }
        }

        return null; // Pas de chemin trouvé
    }

    /**
     * Version A* avec pénalité de proximité aux obstacles (pour obtenir des chemins différents).
     */
    public double [][] findPathWithBias (double startX, double startY, double goalX, double goalY,
                                          double yBias)
    {
        int sx = worldToGridX (startX);
        int sy = worldToGridY (startY);
        int gx = worldToGridX (goalX);
        int gy = worldToGridY (goalY);

        sx = clampGrid (sx);
        sy = clampGrid (sy);
        gx = clampGrid (gx);
        gy = clampGrid (gy);

        double [][] gScore = new double [gridSize][gridSize];
        int [][] cameFromX = new int [gridSize][gridSize];
        int [][] cameFromY = new int [gridSize][gridSize];
        for (double [] row : gScore) Arrays.fill (row, Double.POSITIVE_INFINITY);
        for (int [] row : cameFromX) Arrays.fill (row, -1);
        for (int [] row : cameFromY) Arrays.fill (row, -1);

        gScore [sx][sy] = 0;

        PriorityQueue<double []> open = new PriorityQueue<> ((a, b) -> Double.compare (a [0], b [0]));
        open.add (new double [] {heuristic (sx, sy, gx, gy), sx, sy});

        int [][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        while (!open.isEmpty ())
        {
            double [] current = open.poll ();
            int cx = (int) current [1];
            int cy = (int) current [2];

            if (cx == gx && cy == gy)
            {
                ArrayList<int []> path = new ArrayList<> ();
                int px = gx, py = gy;
                while (px != sx || py != sy)
                {
                    path.add (new int [] {px, py});
                    int tmpX = cameFromX [px][py];
                    int tmpY = cameFromY [px][py];
                    px = tmpX;
                    py = tmpY;
                }
                path.add (new int [] {sx, sy});

                double [][] waypoints = new double [path.size ()][2];
                for (int i = 0; i < path.size (); i++)
                {
                    int [] p = path.get (path.size () - 1 - i);
                    waypoints [i][0] = minX + (p [0] + 0.5) * cellW;
                    waypoints [i][1] = minY + (p [1] + 0.5) * cellH;
                }
                return waypoints;
            }

            for (int [] dir : dirs)
            {
                int nx = cx + dir [0];
                int ny = cy + dir [1];
                if (nx < 0 || nx >= gridSize || ny < 0 || ny >= gridSize) continue;
                if (blocked [nx][ny]) continue;

                double moveCost = (dir [0] != 0 && dir [1] != 0) ? 1.414 : 1.0;

                // Biais Y : pénaliser les cellules éloignées de yBias
                double cellY = minY + (ny + 0.5) * cellH;
                double yPenalty = Math.abs (cellY - yBias) * 0.1;

                double tentG = gScore [cx][cy] + moveCost + yPenalty;

                if (tentG < gScore [nx][ny])
                {
                    gScore [nx][ny] = tentG;
                    cameFromX [nx][ny] = cx;
                    cameFromY [nx][ny] = cy;
                    open.add (new double [] {tentG + heuristic (nx, ny, gx, gy), nx, ny});
                }
            }
        }

        return null;
    }

    /**
     * Convertit un chemin de waypoints en points de contrôle Bézier (flat array).
     * Sous-échantillonne les waypoints pour correspondre à nCP points de contrôle.
     */
    public static double [] waypointsToControlPoints (double [][] waypoints, int nCP,
                                                       double startX, double startY,
                                                       double endX, double endY)
    {
        if (waypoints == null || waypoints.length < 2) return null;

        double [] result = new double [2 * nCP];

        // Sous-échantillonnage uniforme : prendre nCP points répartis sur le chemin
        // (en excluant start et end qui sont fixes)
        double totalDist = 0;
        double [] segDist = new double [waypoints.length - 1];
        for (int i = 0; i < waypoints.length - 1; i++)
        {
            double dx = waypoints [i + 1][0] - waypoints [i][0];
            double dy = waypoints [i + 1][1] - waypoints [i][1];
            segDist [i] = Math.sqrt (dx * dx + dy * dy);
            totalDist += segDist [i];
        }

        if (totalDist < 1e-10) return null;

        for (int cp = 0; cp < nCP; cp++)
        {
            // t ∈ (0, 1) : position relative sur le chemin
            double t = (double) (cp + 1) / (nCP + 1);
            double targetDist = t * totalDist;

            double cumDist = 0;
            for (int i = 0; i < segDist.length; i++)
            {
                if (cumDist + segDist [i] >= targetDist)
                {
                    // Interpoler sur ce segment
                    double frac = (targetDist - cumDist) / segDist [i];
                    result [2 * cp]     = waypoints [i][0] + frac * (waypoints [i + 1][0] - waypoints [i][0]);
                    result [2 * cp + 1] = waypoints [i][1] + frac * (waypoints [i + 1][1] - waypoints [i][1]);
                    break;
                }
                cumDist += segDist [i];
            }
        }

        return result;
    }

    private double heuristic (int x1, int y1, int x2, int y2)
    {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt (dx * dx + dy * dy);
    }

    private int worldToGridX (double wx)
    {
        return (int) ((wx - minX) / cellW);
    }

    private int worldToGridY (double wy)
    {
        return (int) ((wy - minY) / cellH);
    }

    private int clampGrid (int v)
    {
        return Math.max (0, Math.min (gridSize - 1, v));
    }
}
