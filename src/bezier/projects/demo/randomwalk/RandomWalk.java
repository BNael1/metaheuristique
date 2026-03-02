package bezier.projects.demo.randomwalk;

import java.util.Random;

import bezier.evaluation.Problem;
import bezier.projects.DemoProject;
import bezier.projects.InvalidProjectException;

/**
 * @author Alexandre Blansché
 * Marche aléatoire
 */
public class RandomWalk extends DemoProject
{
	Random random;
	double [] solution;
	
	/**
	 * 
	 * @param problem 
	 * @throws InvalidProjectException
	 */
	public RandomWalk (Problem problem) throws InvalidProjectException
	{
		super (problem);
		this.addAuthor ("Alexandre Blansché");
		this.setMethodName ("Random Walk");
	}

	@Override
	public void initialization ()
	{
		this.random = new Random ();
		this.solution = this.problem.getRandomControlPoints1DArray ();
		this.problem.evaluate (this.solution);
	}

	@Override
	public void loop ()
	{
		int pos = this.random.nextInt (this.solution.length);
		this.solution [pos] += this.random.nextGaussian ();
		this.problem.evaluate (this.solution);
	}
}
