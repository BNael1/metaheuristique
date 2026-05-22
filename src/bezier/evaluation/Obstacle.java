package bezier.evaluation;

/**
 * @author Alexandre Blansché
 * Obstacle circulaire dans l'espace de recherche
 */
public class Obstacle
{
	private Coordinates center;
	private double radius;
	
	Obstacle (double x, double y, double radius)
	{
		this.center = new Coordinates (x, y);
		this.radius = radius;
	}
	
	Obstacle (Coordinates center, double radius)
	{
		this.center = center;
		this.radius = radius;
	}
	
	boolean contains (Coordinates c)
	{
		return this.center.distance (c) <= this.radius;
	}
	
	double penalty (Coordinates c)
	{
		double penalty = 0;
		double dist = this.center.distance (c);
		if (dist <= this.radius)
			penalty = 2 + this.radius - dist;
		else if (dist < (this.radius + 1))
			penalty = 1 - (dist - this.radius);
		return penalty;
	}

	double getX ()
	{
		return this.center.getX ();
	}

	double getY ()
	{
		return this.center.getY ();
	}

	double getRadius ()
	{
		return this.radius;
	}
}
