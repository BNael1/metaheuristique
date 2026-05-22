package bezier.evaluation;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.PlotState;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.xy.XYDataset;

class SquareXYPlot extends XYPlot
{
    private double minX, maxX, minY, maxY;

    SquareXYPlot ()
    {
        super();
    }

    SquareXYPlot (XYDataset dataset, ValueAxis domainAxis, ValueAxis rangeAxis, XYItemRenderer renderer, 
            double minX, double maxX, double minY, double maxY)
    {
        super (dataset, domainAxis, rangeAxis, renderer);
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        domainAxis.setRange (minX, maxX);
        rangeAxis.setRange (minY, maxY);
    }

    @Override
    public void draw (Graphics2D g2, Rectangle2D area, Point2D anchor, PlotState parentState, PlotRenderingInfo info)
    {
    	double dataWidth = maxX - minX;
        double dataHeight = maxY - minY;
        double dataAspect = dataWidth / dataHeight;

        double areaAspect = area.getWidth () / area.getHeight ();
        double newWidth, newHeight;

        if (areaAspect > dataAspect)
        {
            newHeight = area.getHeight ();
            newWidth = newHeight * dataAspect;
        }
        else
        {
            newWidth = area.getWidth ();
            newHeight = newWidth / dataAspect;
        }

        double centerX = area.getCenterX ();
        double centerY = area.getCenterY ();
        Rectangle2D adjustedArea = new Rectangle2D.Double (centerX - newWidth / 2, centerY - newHeight / 2, newWidth, newHeight);

        super.draw (g2, adjustedArea, anchor, parentState, info);
    }
}
