package bezier.evaluation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYShapeAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import bezier.run.Main;
import bezier.run.MainFrame;

/**
 * @author Alexandre Blansché
 * Affichage de la meilleure solution
 */
public final class BezierChart
{
	private static final int UPDATE_FREQ = 1000;
    
    private static BezierChart instance;
    private Problem problem;
    private JFreeChart chart;
    private XYLineAndShapeRenderer renderer;
    private ChartPanel chartPanel;
    private XYSeries trajectorySeries;
    private XYSeriesCollection dataset;
    private XYPlot plot;
    private Timer updateTimer;
    private Coordinates [] bestBezier;

    private void refreshTrajectorySeries ()
    {
        this.trajectorySeries.clear ();
        if (this.bestBezier != null)
            for (Coordinates p : this.bestBezier)
                this.trajectorySeries.add (p.getX (), p.getY ());
    }
    
    private BezierChart (Problem problem)
    {
        this.problem = problem;
        this.bestBezier = null;
        this.trajectorySeries = new XYSeries ("Trajectoire", false);
        this.dataset = new XYSeriesCollection ();
        this.dataset.addSeries (trajectorySeries);
        
        NumberAxis domainAxis = new NumberAxis ("X");
        NumberAxis rangeAxis = new NumberAxis ("Y");
        domainAxis.setAutoRange (true);
        rangeAxis.setAutoRange (true);
        
        this.renderer = new XYLineAndShapeRenderer ();
        this.renderer.setSeriesPaint (0, Color.BLUE);
        this.renderer.setSeriesStroke (0, new BasicStroke((float)(2.0 * MainFrame.UI_SCALE)));
        this.renderer.setSeriesShapesVisible (0, false);
        
        this.plot = new SquareXYPlot (this.dataset, domainAxis, rangeAxis, this.renderer,
        		this.problem.getMinX (), this.problem.getMaxX (), this.problem.getMinY (), this.problem.getMaxY ()); 

        this.addBoundaryAnnotation ();
        this.addObstaclesAnnotations1 ();
        this.addObstaclesAnnotations2 ();
        
        this.chart = new JFreeChart ("Trajectoire", JFreeChart.DEFAULT_TITLE_FONT, this.plot, true);
        MainFrame.scaleChartFonts (chart);
        this.chart.removeLegend ();
        
        if (Main.DISPLAY_CHART)
        {
        	this.chartPanel = new ChartPanel (chart);
        	this.chartPanel.setPreferredSize(
        		    new Dimension(
        		        MainFrame.getInstance().getWidth (),
        		        MainFrame.getInstance().getWidth ()
        		    )
        		);
        	this.updateTimer = new Timer (BezierChart.UPDATE_FREQ, e ->
        	{
        		if (this.bestBezier != null)
        		{
        			this.trajectorySeries.clear ();
        			for (Coordinates p : this.bestBezier)
        				this.trajectorySeries.add (p.getX (), p.getY ());
        			MainFrame.getInstance ().revalidate ();
        			MainFrame.getInstance ().repaint ();
        		}
        	});
        	this.updateTimer.start ();
        	MainFrame mainFrame = MainFrame.getInstance ();
        	mainFrame.add (this.chartPanel, java.awt.BorderLayout.SOUTH);
        	mainFrame.pack ();
        }
    }

	/**
	 * @return L'instance courante
	 */
    public static BezierChart getInstance ()
    {
		BezierChart instance = BezierChart.instance;
		if (instance == null)
			instance = new BezierChart (null);
		return instance;
    }

	/**
	 * @param problem Le problème
	 * @return Une nouvelle instance
	 */
    public static BezierChart getNewInstance (Problem problem)
    {
		BezierChart.instance = new BezierChart (problem);
		return BezierChart.instance;
    }

	/**
	 * Mise à jour du graphique
	 * @param bezier La nouvelle courbe de Bézier
	 */
    public void changeBezier (final Coordinates [] bezier)
    {
        this.bestBezier = bezier;
        if (!Main.DISPLAY_CHART) return;
        SwingUtilities.invokeLater (new Runnable ()
        {
            @Override
            public void run ()
            {
	            	bestBezier = bezier;
            }
        });
    }

    public void saveImage (String outputPath, int width, int height) throws IOException
    {
        this.refreshTrajectorySeries ();
        File file = new File (outputPath);
        File parent = file.getParentFile ();
        if (parent != null && !parent.exists ())
            parent.mkdirs ();
        ImageIO.write (this.chart.createBufferedImage (width, height), "png", file);
    }

    private void addObstaclesAnnotations1 ()
    {
        for (int i = 0; i < this.problem.getNObstacles (); i++)
        {
        	Obstacle obs = this.problem.getObstacle (i);
            double centerX = obs.getX ();
            double centerY = obs.getY ();
            double radius = obs.getRadius ();
            Ellipse2D circle = new Ellipse2D.Double (centerX - (radius + 1), centerY - (radius + 1), 2 * (radius + 1), 2 * (radius + 1));
            XYShapeAnnotation obstacleAnnotation = new XYShapeAnnotation (circle, new BasicStroke ((float)(2.0 * MainFrame.UI_SCALE)), Color.ORANGE, Color.ORANGE);
            this.renderer.addAnnotation (obstacleAnnotation, Layer.BACKGROUND);
        }
    }

    private void addObstaclesAnnotations2 ()
    {
        for (int i = 0; i < this.problem.getNObstacles (); i++)
        {
        	Obstacle obs = this.problem.getObstacle (i);
            double centerX = obs.getX ();
            double centerY = obs.getY ();
            double radius = obs.getRadius ();
            Ellipse2D circle = new Ellipse2D.Double (centerX - radius, centerY - radius, 2 * radius, 2 * radius);
            XYShapeAnnotation obstacleAnnotation = new XYShapeAnnotation (circle, new BasicStroke ((float)(2.0 * MainFrame.UI_SCALE)), Color.RED, Color.RED);
            this.renderer.addAnnotation (obstacleAnnotation, Layer.BACKGROUND);
        }
    }
    
    private void addBoundaryAnnotation ()
    {
        double minX = this.problem.getMinX ();
        double maxX = this.problem.getMaxX ();
        double minY = this.problem.getMinY ();
        double maxY = this.problem.getMaxY ();
        
        double fullMinX = minX - (maxX - minX);
        double fullMaxX = maxX + (maxX - minX);
        double fullMinY = minY - (maxY - minY);
        double fullMaxY = maxY + (maxY - minY);
        
        Rectangle2D fullBackground = new Rectangle2D.Double (fullMinX, fullMinY, fullMaxX - fullMinX, fullMaxY - fullMinY);
        XYShapeAnnotation fullBackgroundAnnotation = new XYShapeAnnotation (fullBackground, new BasicStroke (0), Color.RED, Color.RED);
        
        Rectangle2D boundary = new Rectangle2D.Double (minX, minY, maxX - minX, maxY - minY);
        XYShapeAnnotation boundaryAnnotation = new XYShapeAnnotation (boundary, new BasicStroke (0), Color.WHITE, Color.WHITE);
        this.renderer.addAnnotation (fullBackgroundAnnotation, Layer.BACKGROUND);
        this.renderer.addAnnotation (boundaryAnnotation, Layer.BACKGROUND);
    }
}
