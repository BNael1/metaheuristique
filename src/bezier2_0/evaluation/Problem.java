package bezier2_0.evaluation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * @author Alexandre Blansché
 * Instance du problème
 */
public final class Problem
{
    private static final String DIRECTORY = "data";
    private static final double OBSTACLE = 100.;
    private static final double CURVATURE = 1.;
    private static final double BOUNDARY = 100.;

    private Random random;
    private double bestEvaluation;
    private Bezier bestBezier;
    private String name;
    private int nControlPoints;
    private int size;
    private Coordinates startPoint;
    private Coordinates endPoint;
    private double minX, maxX, minY, maxY;
    private ArrayList<Obstacle> obstacles;

    /**
     * @return La liste des problèmes disponibles
     */
    public static ArrayList<Problem> getProblems ()
    {
        File directory = new File (Problem.DIRECTORY);
        File [] files = directory.listFiles ();
        Arrays.sort (files);
        ArrayList<Problem> problems = new ArrayList<Problem> ();
        for (File file : files)
            problems.add (new Problem (file.getAbsolutePath ()));
        return problems;
    }

    private static double expandedMin (double min, double max, double expansion)
    {
        return min - expansion * (max - min);
    }

    private static double expandedMax (double min, double max, double expansion)
    {
        return max + expansion * (max - min);
    }

    private double randomInRange (double min, double max)
    {
        return min + this.random.nextDouble () * (max - min);
    }

    /**
     * @return Remet le problème dans son état initial
     */
    public void reset ()
    {
        this.bestBezier = null;
        this.random = new Random ();
        this.setBestEvaluation (Double.POSITIVE_INFINITY);
    }

    private Problem (String filename)
    {
        String [] parts = filename.split ("/|\\.");
        this.name = parts [parts.length - 2];
        try (BufferedReader in = new BufferedReader (new FileReader (new File (filename))))
        {
            String line = in.readLine ();
            String [] tokens = line.split (",");
            this.startPoint = new Coordinates (Double.parseDouble (tokens [0].trim ()), Double.parseDouble (tokens [1].trim ()));
            line = in.readLine ();
            tokens = line.split (",");
            this.endPoint = new Coordinates (Double.parseDouble (tokens [0].trim ()), Double.parseDouble (tokens [1].trim ()));
            line = in.readLine ();
            tokens = line.split (",");
            double x1 = Double.parseDouble (tokens [0].trim ());
            double y1 = Double.parseDouble (tokens [1].trim ());
            double x2 = Double.parseDouble (tokens [2].trim ());
            double y2 = Double.parseDouble (tokens [3].trim ());
            this.minX = Math.min (x1, x2);
            this.minY = Math.min (y1, y2);
            this.maxX = Math.max (x1, x2);
            this.maxY = Math.max (y1, y2);
            line = in.readLine ();
            this.nControlPoints = Integer.parseInt (line);
            this.size = this.nControlPoints + 2;
            this.obstacles = new ArrayList<Obstacle> ();
            while ((line = in.readLine ()) != null)
            {
                tokens = line.split (",");
                double x = Double.parseDouble (tokens [0].trim ());
                double y = Double.parseDouble (tokens [1].trim ());
                double radius = Double.parseDouble (tokens [2].trim ());
                this.obstacles.add (new Obstacle (x, y, radius));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace ();
        }
    }

    /**
     * @return Le nom du problème
     */
    public String getName ()
    {
        return this.name;
    }

    @Override
    public String toString ()
    {
        return this.name + ": " + this.nControlPoints;
    }

    /**
     * @return Les coordonnées du point de départ
     */
    public Coordinates getStartPoint ()
    {
        return startPoint;
    }

    /**
     * @return Les coordonnées du point d'arrivée
     */
    public Coordinates getEndPoint ()
    {
        return endPoint;
    }

    Obstacle getObstacle (int index)
    {
        return this.obstacles.get (index);
    }

    /**
     * @return Le nombre d'obstacles
     */
    public int getNObstacles ()
    {
        return this.obstacles.size ();
    }

    private int getSize ()
    {
        return this.size;
    }

    /**
     * @return Le nombre de points de contrôle internes (en excluant les points de départ et d'arrivée)
     */
    public int getNControlPoints ()
    {
        return this.nControlPoints;
    }

    /**
     * @return La borne inférieure en X du rectangle
     */
    public double getMinX ()
    {
        return this.minX;
    }

    /**
     * @return La borne supérieure en X du rectangle
     */
    public double getMaxX ()
    {
        return this.maxX;
    }

    /**
     * @return La borne inférieure en Y du rectangle
     */
    public double getMinY ()
    {
        return this.minY;
    }

    /**
     * @return La borne supérieure en Y du rectangle
     */
    public double getMaxY ()
    {
        return this.maxY;
    }

    /**
     * @param point Un point
     * @return Indique si le point est à l'intérieur de la zone valide ou pas
     */
    public boolean isOutsideBounds (Coordinates point)
    {
        return (point.getX () < this.minX || point.getX () > this.maxX || point.getY () < this.minY || point.getY () > this.maxY);
    }

    /**
     * @param bezier Une courbe de Bézier
     * @return Indique si la courbe de Bézier est valide ou pas
     */
    public boolean isValid (Bezier bezier)
    {
        return bezier.getSize () == this.getSize ();
    }

    /**
     * @param controlPoints Une liste de points de contrôle
     * @return Retourne la distance parcourue
     */
    public double evaluate (ArrayList<Coordinates> controlPoints)
    {
        return this.evaluate (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau de points de contrôle
     * @return Retourne la distance parcourue
     */
    public double evaluate (Coordinates [] controlPoints)
    {
        return this.evaluate (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau 1D de coordonnées (x0, y0, x1, y1, ...)
     * @return Retourne la distance parcourue
     */
    public double evaluate (double [] controlPoints)
    {
        return this.evaluate (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau 2D de coordonnées ([i][0] = x, [i][1] = y)
     * @return Retourne la distance parcourue
     */
    public double evaluate (double [][] controlPoints)
    {
        return this.evaluate (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param bezier Une courbe de Bézier
     * @return Retourne la distance parcouru
     */
    public double evaluate (Bezier bezier)
    {
        Coordinates [] trajectory = bezier.getTrajectory ();
        double length = this.computeLength (trajectory);
        double obstaclePenalty = this.computeObstaclePenalty (trajectory);
        double curvaturePenalty = this.computeCurvaturePenalty (trajectory);
        double boundaryPenalty = this.computeBoundaryPenalty (trajectory);
        double evaluation = length +
                Problem.OBSTACLE * obstaclePenalty +
                Problem.CURVATURE * curvaturePenalty +
                Problem.BOUNDARY * boundaryPenalty;
        if (evaluation < this.getBestEvaluation ())
        {
            if (this.isValid (bezier))
            {
                if (!Thread.currentThread ().isInterrupted ())
                {
                    this.setBestEvaluation (evaluation);
                    this.bestBezier = bezier;
                }
            }
        }
        BezierChart.getInstance ().changeBezier (this.bestBezier.getTrajectory ());
        MonitorChart.getInstance ().addData (evaluation, this.getBestEvaluation ());
        return evaluation;
    }
    
    /**
     * @param controlPoints Une liste de points de contrôle
     * @return Retourne un tableau des critères d'évaluation [longueur, pénalité obstacles, pénalité courbure, pénalité limites]
     */
    public double [] evaluateMulti (ArrayList<Coordinates> controlPoints)
    {
        return this.evaluateMulti (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau de points de contrôle
     * @return Retourne un tableau des critères d'évaluation [longueur, pénalité obstacles, pénalité courbure, pénalité limites]
     */
    public double [] evaluateMulti (Coordinates [] controlPoints)
    {
        return this.evaluateMulti (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau 1D de coordonnées (x0, y0, x1, y1, ...)
     * @return Retourne un tableau des critères d'évaluation [longueur, pénalité obstacles, pénalité courbure, pénalité limites]
     */
    public double [] evaluateMulti (double [] controlPoints)
    {
        return this.evaluateMulti (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param controlPoints Un tableau 2D de coordonnées ([i][0] = x, [i][1] = y)
     * @return Retourne un tableau des critères d'évaluation [longueur, pénalité obstacles, pénalité courbure, pénalité limites]
     */
    public double [] evaluateMulti (double [][] controlPoints)
    {
        return this.evaluateMulti (new Bezier (controlPoints, this.startPoint, this.endPoint));
    }

    /**
     * @param bezier Une courbe de Bézier
     * @return Retourne un tableau des critères d'évaluation [longueur, pénalité obstacles, pénalité courbure, pénalité limites]
     */
    public double [] evaluateMulti (Bezier bezier)
    {
        Coordinates [] trajectory = bezier.getTrajectory ();
        double [] objectives = new double []
        {
            this.computeLength (trajectory),
            Problem.OBSTACLE * this.computeObstaclePenalty (trajectory),
            Problem.CURVATURE * this.computeCurvaturePenalty (trajectory),
            Problem.BOUNDARY * this.computeBoundaryPenalty (trajectory)
        };
        double evaluation = objectives [0] + objectives [1] + objectives [2] + objectives [3];
        if (evaluation < this.getBestEvaluation ())
        {
            if (this.isValid (bezier))
            {
                if (!Thread.currentThread ().isInterrupted ())
                {
                    this.setBestEvaluation (evaluation);
                    this.bestBezier = bezier;
                }
            }
        }
        BezierChart.getInstance ().changeBezier (this.bestBezier.getTrajectory ());
        MonitorChart.getInstance ().addData (evaluation, this.getBestEvaluation ());
        return objectives;
    }

    private double computeLength (Coordinates [] trajectory)
    {
        double length = 0;
        for (int i = 1; i < trajectory.length; i++)
            length += trajectory [i - 1].distance (trajectory [i]);
        return length;
    }

    private double computeObstaclePenalty (Coordinates [] trajectory)
    {
        double penalty = 0;
        for (Obstacle obstacle : this.obstacles)
            for (Coordinates point : trajectory)
                penalty += obstacle.penalty (point);
        return penalty;
    }

    private double computeCurvaturePenalty (Coordinates [] trajectory)
    {
        double penalty = 0;
        for (int i = 1; i < trajectory.length - 1; i++)
        {
            Coordinates prev = trajectory [i - 1];
            Coordinates current = trajectory [i];
            Coordinates next = trajectory [i + 1];

            double cosine = Problem.cosine (prev, current, next);
            double deviation = 1 - cosine;
            penalty += deviation;
        }
        return penalty;
    }

    private static double cosine (Coordinates a, Coordinates b, Coordinates c)
    {
        double abx = b.getX () - a.getX ();
        double aby = b.getY () - a.getY ();
        double bcx = c.getX () - b.getX ();
        double bcy = c.getY () - b.getY ();

        double dotProduct = (abx * bcx + aby * bcy);
        double magA2 = abx * abx + aby * aby;
        double magB2 = bcx * bcx + bcy * bcy;

        return dotProduct / Math.sqrt (magA2 * magB2);
    }

    private double computeBoundaryPenalty (Coordinates [] trajectory)
    {
        double penalty = 0;
        for (Coordinates point : trajectory)
            if (this.isOutsideBounds (point))
                penalty += 1;
        return penalty;
    }

    /**
     * @return L'évaluation de la meilleure solution
     */
    public double getBestEvaluation ()
    {
        return this.bestEvaluation;
    }

    private void setBestEvaluation (double bestEvaluation)
    {
        this.bestEvaluation = bestEvaluation;
    }

    /**
     * @return Une valeur aléatoire de X dans le rectangle de recherche
     */
    public double getRandomX ()
    {
        return this.getRandomX (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Une valeur aléatoire de X dans le rectangle de recherche agrandi
     */
    public double getRandomX (double expansion)
    {
        return this.randomInRange (
                Problem.expandedMin (this.minX, this.maxX, expansion),
                Problem.expandedMax (this.minX, this.maxX, expansion));
    }

    /**
     * @return Une valeur aléatoire de Y dans le rectangle de recherche
     */
    public double getRandomY ()
    {
        return this.getRandomY (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Une valeur aléatoire de Y dans le rectangle de recherche agrandi
     */
    public double getRandomY (double expansion)
    {
        return this.randomInRange (
                Problem.expandedMin (this.minY, this.maxY, expansion),
                Problem.expandedMax (this.minY, this.maxY, expansion));
    }

    /**
     * @return Un point aléatoire dans le rectangle de recherche
     */
    public Coordinates getRandomPoint ()
    {
        return this.getRandomPoint (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Un point aléatoire dans le rectangle de recherche agrandi
     */
    public Coordinates getRandomPoint (double expansion)
    {
        return new Coordinates (this.getRandomX (expansion), this.getRandomY (expansion));
    }

    /**
     * @return Un tableau de points de contrôle aléatoires dans le rectangle de recherche
     */
    public Coordinates [] getRandomControlPoints ()
    {
        return this.getRandomControlPoints (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Un tableau de points de contrôle aléatoires dans le rectangle de recherche agrandi
     */
    public Coordinates [] getRandomControlPoints (double expansion)
    {
        Coordinates [] res = new Coordinates [this.nControlPoints];
        for (int i = 0; i < res.length; i++)
            res [i] = this.getRandomPoint (expansion);
        return res;
    }

    /**
     * @return Une liste de points de contrôle aléatoires dans le rectangle de recherche
     */
    public ArrayList<Coordinates> getRandomControlPointsList ()
    {
        return this.getRandomControlPointsList (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Une liste de points de contrôle aléatoires dans le rectangle de recherche agrandi
     */
    public ArrayList<Coordinates> getRandomControlPointsList (double expansion)
    {
        ArrayList<Coordinates> res = new ArrayList<Coordinates> (this.nControlPoints);
        for (int i = 0; i < this.nControlPoints; i++)
            res.add (this.getRandomPoint (expansion));
        return res;
    }

    /**
     * @return Un tableau 2D de points de contrôle aléatoires dans le rectangle de recherche
     */
    public double [][] getRandomControlPoints2DArray ()
    {
        return this.getRandomControlPoints2DArray (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Un tableau 2D de points de contrôle aléatoires dans le rectangle de recherche agrandi
     */
    public double [][] getRandomControlPoints2DArray (double expansion)
    {
        double [][] res = new double [this.nControlPoints][2];
        double exMinX = Problem.expandedMin (this.minX, this.maxX, expansion);
        double exMaxX = Problem.expandedMax (this.minX, this.maxX, expansion);
        double exMinY = Problem.expandedMin (this.minY, this.maxY, expansion);
        double exMaxY = Problem.expandedMax (this.minY, this.maxY, expansion);
        for (int i = 0; i < res.length; i++)
        {
            res [i][0] = this.randomInRange (exMinX, exMaxX);
            res [i][1] = this.randomInRange (exMinY, exMaxY);
        }
        return res;
    }

    /**
     * @return Un tableau 1D de coordonnées aléatoires (x0, y0, x1, y1, ...) dans le rectangle de recherche
     */
    public double [] getRandomControlPoints1DArray ()
    {
        return this.getRandomControlPoints1DArray (0.);
    }

    /**
     * @param expansion Facteur d'agrandissement du rectangle de recherche (0 = pas d'agrandissement)
     * @return Un tableau 1D de coordonnées aléatoires (x0, y0, x1, y1, ...) dans le rectangle de recherche agrandi
     */
    public double [] getRandomControlPoints1DArray (double expansion)
    {
        double [] res = new double [2 * this.nControlPoints];
        double exMinX = Problem.expandedMin (this.minX, this.maxX, expansion);
        double exMaxX = Problem.expandedMax (this.minX, this.maxX, expansion);
        double exMinY = Problem.expandedMin (this.minY, this.maxY, expansion);
        double exMaxY = Problem.expandedMax (this.minY, this.maxY, expansion);
        for (int i = 0; i < res.length; i += 2)
        {
            res [i]     = this.randomInRange (exMinX, exMaxX);
            res [i + 1] = this.randomInRange (exMinY, exMaxY);
        }
        return res;
    }
}
