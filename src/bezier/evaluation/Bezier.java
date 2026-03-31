package bezier.evaluation;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * @author Alexandre Blansché & Mathis Saillot
 * Courbe de Bézier
 */
public class Bezier
{
	private static final int MIN_CP_MATRIX = 3;
	private static final int MAX_CP_MATRIX = 65;

	private static final int NB_POINTS = 1000;
	private static final HashMap<Integer, double[][]> matrix = new HashMap<>();
	private Coordinates [] controlPoints;

	private void initControlPoints(ArrayList<Coordinates> controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		this.controlPoints = new Coordinates [controlPoints.size () + 2];
		this.controlPoints [0] = startPoint;
		for (int i = 0; i < controlPoints.size (); i++)
			this.controlPoints [i + 1] = controlPoints.get (i);
		this.controlPoints [this.controlPoints.length - 1] = endPoints;
		computeMatrix(getSize());
	}

	private void initControlPoints(Coordinates [] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		this.controlPoints = new Coordinates [controlPoints.length + 2];
		this.controlPoints [0] = startPoint;
		System.arraycopy(controlPoints, 0, this.controlPoints, 1, controlPoints.length);
		this.controlPoints [this.controlPoints.length - 1] = endPoints;
		computeMatrix(getSize());
	}

	private void initControlPoints(double [][] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		this.controlPoints = new Coordinates [2 + controlPoints.length];
		this.controlPoints [0] = startPoint;
		for (int i = 0; i < controlPoints.length; i++)
			this.controlPoints [i + 1] = new Coordinates (controlPoints [i][0], controlPoints [i][1]);
		this.controlPoints [this.controlPoints.length - 1] = endPoints;
		computeMatrix(getSize());
	}

	private void initControlPoints(double [] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		int nbControlPoints = controlPoints.length / 2;
		this.controlPoints = new Coordinates [2 + nbControlPoints];
		this.controlPoints [0] = startPoint;
		for (int i = 0; i < nbControlPoints; i++)
			this.controlPoints [i + 1] = new Coordinates (controlPoints [2 * i], controlPoints [2 * i + 1]);
		this.controlPoints [this.controlPoints.length - 1] = endPoints;
		computeMatrix(getSize());
	}

	Bezier (ArrayList<Coordinates> controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		initControlPoints(controlPoints, startPoint, endPoints);
	}

	Bezier(Coordinates [] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		initControlPoints(controlPoints, startPoint, endPoints);
	}

	Bezier(Coordinates [] controlPoints)
	{
		this.controlPoints = new Coordinates [controlPoints.length];
		System.arraycopy(controlPoints, 0, this.controlPoints, 0, controlPoints.length);
		computeMatrix(getSize());
	}

	Bezier(double [][] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		initControlPoints(controlPoints, startPoint, endPoints);
	}

	Bezier(double [] controlPoints, Coordinates startPoint, Coordinates endPoints)
	{
		initControlPoints(controlPoints, startPoint, endPoints);
	}

	Bezier(ArrayList<Coordinates> controlPoints, Problem problem)
	{
		initControlPoints(controlPoints, problem.getStartPoint(), problem.getEndPoint());
	}

	Bezier(Coordinates [] controlPoints, Problem problem)
	{
		initControlPoints(controlPoints, problem.getStartPoint(), problem.getEndPoint());
	}

	Bezier(double [][] controlPoints, Problem problem)
	{
		initControlPoints(controlPoints, problem.getStartPoint(), problem.getEndPoint());
	}

	Bezier(double [] controlPoints, Problem problem)
	{
		initControlPoints(controlPoints, problem.getStartPoint(), problem.getEndPoint());
	}

	private static boolean testNbCP(int n)
	{
		return n >= MIN_CP_MATRIX && n <= MAX_CP_MATRIX;
	}

	private static void computeMatrix(int n)
	{
		if (matrix.get(n) != null) return;
		if (!testNbCP(n)) return;
		double[][] mat = new double[NB_POINTS][n];
		int order = n-1;
		for (int i = 0; i < NB_POINTS; i++) {
			double t = (double) i / (NB_POINTS - 1);
			double t_hat = 1 - t;
			double t_pow = 1.;
			for (int k = 0; k < n; k++) {
				mat[i][k] = t_pow * PascalTriangle.binomial(order, k);
				t_pow *= t;
			}
			t_pow = 1.;
			for (int k = n-1; k >= 0; k--) {
				mat[i][k] *= t_pow;
				t_pow *= t_hat;
			}
		}
		matrix.put(n, mat);
		//PascalTriangle.print();
	}

	private Coordinates evaluateBezier (double t)
	{
		int n = getSize();
		Coordinates [] temp = new Coordinates [n];
		for (int i = 0; i < n; i++)
			temp [i] = new Coordinates(controlPoints [i].getX (), controlPoints [i].getY ());

		for (int k = 1; k < n; k++) 
			for (int i = 0; i < n - k; i++)
			{
				double x = (1 - t) * temp[i].getX () + t * temp [i + 1].getX ();
				double y = (1 - t) * temp[i].getY () + t * temp [i + 1].getY ();
				temp[i] = new Coordinates (x, y);
			}
		return temp[0];
	}

	int getSize ()
	{
		return this.controlPoints.length;
	}

	private Coordinates[] computeTrajectoryMatrix()
	{
		Coordinates [] trajectory = new Coordinates [NB_POINTS];
		for (int i = 0; i < NB_POINTS; i++) {
			double x = 0, y = 0;
			for (int j = 0; j < getSize(); j++) {
				x += this.controlPoints[j].getX() * matrix.get(getSize())[i][j];
				y += this.controlPoints[j].getY() * matrix.get(getSize())[i][j];
			}
			trajectory[i] = new Coordinates(x, y);
		}

		return trajectory;
	}

	private Coordinates[] computeTrajectoryClassic()
	{
		Coordinates [] trajectory = new Coordinates [NB_POINTS];
		for (int i = 0; i < NB_POINTS; i++)
			trajectory [i] = evaluateBezier ((double) i / (NB_POINTS - 1));
		return trajectory;
	}

	Coordinates [] getTrajectory ()
	{
		if (testNbCP(getSize()))
			return computeTrajectoryMatrix();

		return computeTrajectoryClassic();
	}
}

class PascalTriangle
{
	private static final ArrayList<long[]> triangle = new ArrayList<>();

	private static synchronized void compute_line(int n)
	{
		if (triangle.size() < n-1)
			compute_line(n-1);
		long[] line = new long[n];
		for (int i=0; i<n; i++)
			line[i] = binomial(n-1, i-1) + binomial(n-1, i);
		triangle.add(line);
	}

	/**
	 * k parmi n
	 * @param n
	 * @param k
	 * @return k parmi n
	 */
	static synchronized long binomial(int n, int k)
	{

		if (n < 0 || k < 0 ) return 0;
		if (n == 0 || k == 0 || n == k) return 1;
		if (triangle.size() < n)
			compute_line(n);
		return triangle.get(n-1)[k];
	}

	static void print()
	{
		for (long[] ints : triangle)
		{
			for (long anInt : ints)
				System.out.print(anInt + " ");
			System.out.println();
		}
	}
}