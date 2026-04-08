package bezier2_0.projects.demo.randomsearch;

import bezier2_0.evaluation.Problem;
import bezier2_0.projects.DemoProject;
import bezier2_0.projects.InvalidProjectException;

/**
 * @author Alexandre Blansché
 * Recherche aléatoire
 */
public class RandomSearch extends DemoProject
{
	/**
	 * 
	 * @param problem 
	 * @throws InvalidProjectException
	 */
	public RandomSearch (Problem problem) throws InvalidProjectException
	{
		super (problem);
		this.addAuthor ("Alexandre Blansché");
		this.setMethodName ("Random Search");
	}

	@Override
	public void initialization ()
	{
		this.problem.evaluate (this.problem.getRandomControlPoints (1));
	}

	@Override
	public void loop ()
	{
		this.problem.evaluate (this.problem.getRandomControlPoints (1));
	}
}
