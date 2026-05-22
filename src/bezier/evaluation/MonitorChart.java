package bezier.evaluation;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.title.LegendTitle;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import bezier.run.Main;
import bezier.run.MainFrame;

/**
 * @author Alexandre Blansché
 * Graphique de suivi de l'évaluation au cours du temps (singleton)
 */
public class MonitorChart
{
    private static final int UPDATE_FREQ = 10;

    private static MonitorChart instance = null;
    private TimeSeries bestEvaluation;
    private TimeSeries currentEvaluation;
    private ChartPanel chartPanel;
    private Timer updateTimer;
    private volatile boolean updatePending = false;

    /**
     * @return L'instance unique du graphique de suivi
     */
    public static MonitorChart getInstance ()
    {
        if (MonitorChart.instance == null)
            MonitorChart.instance = new MonitorChart ("");
        return MonitorChart.instance;
    }

    /**
     * @param title Le titre du graphique
     * @return Une nouvelle instance unique du graphique de suivi
     */
    public static MonitorChart getNewInstance (String title)
    {
        MonitorChart.instance = new MonitorChart (title);
        return MonitorChart.instance;
    }

    private void updateRangeAxis ()
    {
        double newMin = Double.POSITIVE_INFINITY;
        double newMax = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < this.bestEvaluation.getItemCount (); i++)
        {
            Number y = this.bestEvaluation.getValue (i);
            if (y != null)
            {
                double val = y.doubleValue ();
                if (val < newMin)
                    newMin = val;
                if (val > newMax)
                    newMax = val;
            }
        }
        if (newMin != Double.POSITIVE_INFINITY && newMax != Double.NEGATIVE_INFINITY && newMin != newMax)
            this.chartPanel.getChart ().getXYPlot ().getRangeAxis ().setRange (newMin, newMax);
    }

    private MonitorChart (String title)
    {
        if (Main.DISPLAY_CHART)
        {
            this.bestEvaluation = new TimeSeries ("Meilleure évaluation");
            this.currentEvaluation = new TimeSeries ("Évaluation courante");

            TimeSeriesCollection tsc = new TimeSeriesCollection ();
            tsc.addSeries (this.currentEvaluation);
            tsc.addSeries (this.bestEvaluation);

            JFreeChart chart = ChartFactory.createTimeSeriesChart (
                    title,
                    "Temps",
                    "Évaluation",
                    tsc,
                    false,
                    false,
                    false
            );
            MainFrame.scaleChartFonts (chart);
            chart.removeLegend ();
            LegendTitle legend = new LegendTitle (chart.getPlot ());
            legend.setPosition (org.jfree.chart.ui.RectangleEdge.BOTTOM);
            chart.addSubtitle (legend);
            this.chartPanel = new ChartPanel (chart);
            this.chartPanel.setPreferredSize (new Dimension (MainFrame.getInstance().getWidth (), MainFrame.getInstance().getWidth () / 3));
            this.updateTimer = new Timer (MonitorChart.UPDATE_FREQ, e ->
            {
                if (this.updatePending)
                {
                    MainFrame.getInstance ().revalidate ();
                    MainFrame.getInstance ().repaint ();
                    this.updatePending = false;
                }
            });
            this.updateTimer.start ();
            MainFrame mainFrame = MainFrame.getInstance ();
            mainFrame.add (this.chartPanel, BorderLayout.NORTH);
            mainFrame.pack ();
        }
    }

    /**
     * @param current L'évaluation courante à ajouter au graphique
     * @param best La meilleure évaluation à ajouter au graphique
     */
    public void addData (final double current, final double best)
    {
        if (Main.DISPLAY_CHART)
        {
            SwingUtilities.invokeLater (new Runnable ()
            {
                @Override
                public void run ()
                {
                    if (!updatePending)
                    {
                        Millisecond now = new Millisecond ();
                        currentEvaluation.addOrUpdate (now, current);
                        bestEvaluation.addOrUpdate (now, best);
                        updateRangeAxis ();
                    }
                    updatePending = true;
                }
            });
        }
    }
}
