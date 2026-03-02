package bezier.projects;

import bezier.evaluation.Problem;

/**
 * @author Alexandre Blansché
 * C'est la classe à étendre pour le projet !
 */
public abstract class CompetitorProject extends Project
{
	public CompetitorProject(Problem problem) throws InvalidProjectException {
		super(problem);
	}
}
