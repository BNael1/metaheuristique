package bezier.projects.competitor.alconst;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

/**
 * Lit le fichier .bzr pour extraire les positions et rayons des obstacles.
 * Permet de calculer les violations de contraintes sans appeler problem.evaluate() en double.
 */
public class ObstacleReader
{
    private final double [] ox, oy, or;
    private final int nObs;

    public ObstacleReader (String problemName)
    {
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
                obsList.add (new double [] {
                    Double.parseDouble (tokens [0].trim ()),
                    Double.parseDouble (tokens [1].trim ()),
                    Double.parseDouble (tokens [2].trim ())
                });
            }
            br.close ();
        }
        catch (Exception e) {}

        nObs = obsList.size ();
        ox = new double [nObs];
        oy = new double [nObs];
        or = new double [nObs];
        for (int i = 0; i < nObs; i++)
        {
            ox [i] = obsList.get (i) [0];
            oy [i] = obsList.get (i) [1];
            or [i] = obsList.get (i) [2];
        }
    }

    /**
     * Calcule la violation de contrainte pour chaque obstacle.
     * g_i(x) > 0 signifie que l'obstacle i est violé.
     *
     * Pour estimer la violation sans évaluer la Bézier complète,
     * on calcule la distance minimale de chaque point de contrôle à chaque obstacle.
     * C'est une approximation (la courbe peut passer par l'obstacle entre les CPs),
     * mais c'est rapide et ne nécessite pas d'évaluation supplémentaire.
     */
    public double [] computeViolations (double [] controlPoints, double startX, double startY,
                                         double endX, double endY)
    {
        int nCP = controlPoints.length / 2;
        double [] violations = new double [nObs];

        // Échantillonner quelques points le long de la Bézier (approximation)
        // Utiliser les CPs directement + quelques points intermédiaires
        int nSamples = 20;
        double [][] samples = new double [nSamples + 2][2];
        samples [0][0] = startX;
        samples [0][1] = startY;
        samples [nSamples + 1][0] = endX;
        samples [nSamples + 1][1] = endY;

        for (int s = 0; s < nSamples; s++)
        {
            double t = (double) (s + 1) / (nSamples + 1);
            // Interpolation linéaire le long des CPs (approximation simple)
            double idx = t * (nCP - 1);
            int lo = (int) idx;
            int hi = Math.min (lo + 1, nCP - 1);
            double frac = idx - lo;
            samples [s + 1][0] = (1 - frac) * controlPoints [2 * lo] + frac * controlPoints [2 * hi];
            samples [s + 1][1] = (1 - frac) * controlPoints [2 * lo + 1] + frac * controlPoints [2 * hi + 1];
        }

        for (int o = 0; o < nObs; o++)
        {
            double maxViol = 0;
            for (double [] sample : samples)
            {
                double dx = sample [0] - ox [o];
                double dy = sample [1] - oy [o];
                double dist = Math.sqrt (dx * dx + dy * dy);
                // Violation = (radius + soft_zone) - dist
                double viol = (or [o] + 1.0) - dist;
                if (viol > maxViol) maxViol = viol;
            }
            violations [o] = maxViol;
        }

        return violations;
    }

    public int getNObstacles () { return nObs; }
}
