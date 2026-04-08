package bezier2_0.projects.demo.randomwalk;

import java.util.Random;

import bezier2_0.evaluation.Problem;
import bezier2_0.projects.DemoProject;
import bezier2_0.projects.InvalidProjectException;

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
