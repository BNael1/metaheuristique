package bezier.projects.demo.hillclimbing;

import bezier.evaluation.Problem;
import bezier.projects.DemoProject;
import bezier.projects.InvalidProjectException;

import java.util.Random;

/**
 * @author Alexandre Blansché
 * Algorithme de Hill Climbing
 */
public class HillClimbing extends DemoProject
{
    private static final double PERTURBATION_SCALE = .1;
    private static final int MAX_FAILS = 1000;
    
    private double[][] bestControlPoints;
    private double bestEvaluation;
    private double nFails;
    private Random random = new Random();

    /**
     * @param problem Le problème à résoudre
     * @throws InvalidProjectException 
     */
    public HillClimbing (Problem problem) throws InvalidProjectException
    {
        super (problem);
        this.addAuthor ("Alexandre Blansché");
        this.setMethodName ("Hill Climbing");
    }

    @Override
    public void initialization ()
    {
        this.bestControlPoints = this.problem.getRandomControlPoints2DArray ();
        this.bestEvaluation = this.problem.evaluate (this.bestControlPoints);
        this.nFails = 0;
    }

    @Override
    public void loop ()
    {
        double [][] newControlPoints = perturbation (this.bestControlPoints);
        double newEvaluation = this.problem.evaluate (newControlPoints);

        if (newEvaluation < this.bestEvaluation)
        {
            this.bestControlPoints = newControlPoints;
            this.bestEvaluation = newEvaluation;
            this.nFails = 0;
        }
        else
        	this.nFails++;
        if (this.nFails >= HillClimbing.MAX_FAILS)
        	this.initialization ();        	
    }

    private double [][] perturbation (double [][] controlPoints)
    {
        double[][] newControlPoints = new double [controlPoints.length][2];
        for (int i = 0; i < controlPoints.length; i++)
        {
        	newControlPoints [i][0] = controlPoints [i][0];
        	newControlPoints [i][1] = controlPoints [i][1];
        }

        for (int i = 0; i < newControlPoints.length; i++)
        {
            for (int j = 0; j < newControlPoints[i].length; j++)
            {
                double perturbation = (this.random.nextDouble () * 2 - 1) * PERTURBATION_SCALE;
                newControlPoints[i][j] += perturbation;
            }
        }
        return newControlPoints;
    }
}
