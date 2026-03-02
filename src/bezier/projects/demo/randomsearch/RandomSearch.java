package bezier.projects.demo.randomsearch;

import bezier.evaluation.Problem;
import bezier.projects.DemoProject;
import bezier.projects.InvalidProjectException;

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
