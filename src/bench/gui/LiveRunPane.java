package bench.gui;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import java.awt.*;

/**
 * Onglet "Live Run" : exécute UN algorithme et montre en temps réel :
 *   - Gauche : courbe de convergence (fitness vs temps)
 *   - Droite : infos textuelles (génération, évaluations, restarts, meilleure fitness)
 *   - Bas    : boutons Start / Stop
 */
public class LiveRunPane extends JPanel implements AlgorithmRunner.StateListener
{
    private final ConfigPane config;

    private final XYSeries fitnessSeries;
    private final JFreeChart chart;
    private final ChartPanel chartPanel;
    
    private final XYSeries trajectorySeries;
    private final ChartPanel trajectoryChartPanel;

    private final JLabel lblAlgo   = new JLabel ("-");
    private final JLabel lblGen    = new JLabel ("-");
    private final JLabel lblEvals  = new JLabel ("-");
    private final JLabel lblRestart = new JLabel ("-");
    private final JLabel lblFitness = new JLabel ("-");
    private final JLabel lblSigma   = new JLabel ("-");
    private final JLabel lblTime    = new JLabel ("-");

    private final JButton btnStart = new JButton ("\u25B6 Lancer");
    private final JButton btnStop  = new JButton ("\u25A0 Arrêter");

    private AlgorithmRunner runner;

    public LiveRunPane (ConfigPane config)
    {
        this.config = config;
        setLayout (new BorderLayout (10, 10));
        setBorder (BorderFactory.createEmptyBorder (10, 10, 10, 10));

        // === Graphique de convergence ===
        fitnessSeries = new XYSeries ("Meilleure fitness");
        XYSeriesCollection dataset = new XYSeriesCollection (fitnessSeries);
        chart = ChartFactory.createXYLineChart (
                "Convergence", "Temps (s)", "Fitness",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.getXYPlot ().getRangeAxis ().setAutoRange (true);
        ThemeUtils.applyDarkThemeToChart(chart);
        chartPanel = new ChartPanel (chart);
        chartPanel.setPreferredSize (new Dimension (450, 600));

        // === Graphique de la trajectoire ===
        trajectorySeries = new XYSeries ("Trajectoire", false);
        trajectoryChartPanel = new ChartPanel (null);
        trajectoryChartPanel.setPreferredSize (new Dimension (450, 600));

        JPanel chartsContainer = new JPanel(new GridLayout(1, 2, 10, 0));
        chartsContainer.add(chartPanel);
        chartsContainer.add(trajectoryChartPanel);
        
        add (chartsContainer, BorderLayout.CENTER);

        // === Panneau d'infos à droite ===
        JPanel infoPanel = new JPanel (new GridLayout (0, 1, 5, 8));
        infoPanel.setBorder (BorderFactory.createTitledBorder ("État"));
        infoPanel.setPreferredSize (new Dimension (300, 0));

        addInfoRow (infoPanel, "Algorithme :", lblAlgo);
        addInfoRow (infoPanel, "Génération :", lblGen);
        addInfoRow (infoPanel, "Évaluations :", lblEvals);
        addInfoRow (infoPanel, "Restarts :", lblRestart);
        addInfoRow (infoPanel, "Meilleure fitness :", lblFitness);
        addInfoRow (infoPanel, "Sigma :", lblSigma);
        addInfoRow (infoPanel, "Temps écoulé :", lblTime);

        add (infoPanel, BorderLayout.EAST);

        // === Boutons en bas ===
        JPanel btnPanel = new JPanel (new FlowLayout (FlowLayout.CENTER, 20, 5));
        btnStop.setEnabled (false);
        btnStart.addActionListener (e -> startRun ());
        btnStop.addActionListener (e -> stopRun ());
        btnPanel.add (btnStart);
        btnPanel.add (btnStop);
        add (btnPanel, BorderLayout.SOUTH);
    }

    private void addInfoRow (JPanel panel, String label, JLabel value)
    {
        JPanel row = new JPanel (new BorderLayout ());
        JLabel lbl = new JLabel (label);
        lbl.setFont (lbl.getFont ().deriveFont (Font.BOLD, 13f));
        value.setFont (new Font (Font.MONOSPACED, Font.BOLD, 14));
        value.setForeground(new Color(139, 233, 253)); // Cyan
        row.add (lbl, BorderLayout.WEST);
        row.add (value, BorderLayout.EAST);
        panel.add (row);
    }

    private void startRun ()
    {
        Problem problem = config.getProblem ();
        if (problem == null)
        {
            JOptionPane.showMessageDialog (this,
                    "Veuillez charger un problème dans l'onglet Configuration.",
                    "Pas de problème", JOptionPane.WARNING_MESSAGE);
            return;
        }

        java.util.List<String> algos = config.getSelectedAlgorithms ();
        if (algos.isEmpty ())
        {
            JOptionPane.showMessageDialog (this,
                    "Veuillez sélectionner au moins un algorithme.",
                    "Pas d'algorithme", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Prendre le premier algorithme sélectionné
        String algoName = algos.get (0);
        Optimizer opt = AlgorithmFactory.create (algoName, problem, config.getMargin());

        fitnessSeries.clear ();
        setupTrajectoryChart (problem);
        
        long timeMs = config.getTimeLimitSeconds () * 1000L;
        runner = new AlgorithmRunner (algoName, opt, timeMs);
        runner.addListener (this);

        lblAlgo.setText (algoName);
        btnStart.setEnabled (false);
        btnStop.setEnabled (true);
        runner.start ();
    }

    private void stopRun ()
    {
        if (runner != null) runner.stop ();
        btnStart.setEnabled (true);
        btnStop.setEnabled (false);
    }

    private void setupTrajectoryChart (Problem problem)
    {
        trajectorySeries.clear ();
        XYSeriesCollection dataset = new XYSeriesCollection ();
        dataset.addSeries (trajectorySeries);
        
        org.jfree.chart.axis.NumberAxis domainAxis = new org.jfree.chart.axis.NumberAxis ("X");
        org.jfree.chart.axis.NumberAxis rangeAxis = new org.jfree.chart.axis.NumberAxis ("Y");
        domainAxis.setAutoRange (true);
        rangeAxis.setAutoRange (true);
        
        org.jfree.chart.renderer.xy.XYLineAndShapeRenderer renderer = new org.jfree.chart.renderer.xy.XYLineAndShapeRenderer ();
        renderer.setSeriesPaint (0, Color.CYAN);
        renderer.setSeriesStroke (0, new BasicStroke (2.0f));
        renderer.setSeriesShapesVisible (0, false);
        
        bezier.evaluation.SquareXYPlot plot = new bezier.evaluation.SquareXYPlot (dataset, domainAxis, rangeAxis, renderer,
                problem.getMinX (), problem.getMaxX (), problem.getMinY (), problem.getMaxY ());
                
        double minX = problem.getMinX (), maxX = problem.getMaxX (), minY = problem.getMinY (), maxY = problem.getMaxY ();
        double fullMinX = minX - (maxX - minX);
        double fullMaxX = maxX + (maxX - minX);
        double fullMinY = minY - (maxY - minY);
        double fullMaxY = maxY + (maxY - minY);
        
        java.awt.geom.Rectangle2D fullBackground = new java.awt.geom.Rectangle2D.Double (fullMinX, fullMinY, fullMaxX - fullMinX, fullMaxY - fullMinY);
        java.awt.geom.Rectangle2D boundary = new java.awt.geom.Rectangle2D.Double (minX, minY, maxX - minX, maxY - minY);
        plot.getRenderer ().addAnnotation (new org.jfree.chart.annotations.XYShapeAnnotation (fullBackground, new BasicStroke (0), Color.RED, Color.RED), org.jfree.chart.ui.Layer.BACKGROUND);
        plot.getRenderer ().addAnnotation (new org.jfree.chart.annotations.XYShapeAnnotation (boundary, new BasicStroke (0), Color.WHITE, Color.WHITE), org.jfree.chart.ui.Layer.BACKGROUND);
        
        for (int i = 0; i < problem.getNObstacles (); i++) {
            bezier.evaluation.Obstacle obs = problem.getObstacle (i);
            
            // Outer Orange ring
            java.awt.geom.Ellipse2D circleOuter = new java.awt.geom.Ellipse2D.Double (obs.getX () - (obs.getRadius () + 1), obs.getY () - (obs.getRadius () + 1), 2 * (obs.getRadius () + 1), 2 * (obs.getRadius () + 1));
            plot.getRenderer ().addAnnotation (new org.jfree.chart.annotations.XYShapeAnnotation (circleOuter, new BasicStroke (2.0f), Color.ORANGE, Color.ORANGE), org.jfree.chart.ui.Layer.BACKGROUND);
            
            // Inner Red ring
            java.awt.geom.Ellipse2D circleInner = new java.awt.geom.Ellipse2D.Double (obs.getX () - obs.getRadius (), obs.getY () - obs.getRadius (), 2 * obs.getRadius (), 2 * obs.getRadius ());
            plot.getRenderer ().addAnnotation (new org.jfree.chart.annotations.XYShapeAnnotation (circleInner, new BasicStroke (2.0f), Color.RED, Color.RED), org.jfree.chart.ui.Layer.BACKGROUND);
        }
        
        JFreeChart tChart = new JFreeChart ("Trajectoire", JFreeChart.DEFAULT_TITLE_FONT, plot, false);
        ThemeUtils.applyDarkThemeToChart (tChart);
        trajectoryChartPanel.setChart (tChart);
    }

    // === StateListener callbacks (appelés depuis le thread runner) ===

    @Override
    public void onStateUpdate (String name, OptimizerState state, long elapsedMs)
    {
        SwingUtilities.invokeLater (() -> {
            double timeSec = elapsedMs / 1000.0;
            fitnessSeries.add (timeSec, state.bestFitness);

            if (state.bestX != null && config.getProblem () != null)
            {
                trajectorySeries.clear ();
                bezier.evaluation.Coordinates[] traj = config.getProblem ().computeTrajectory (state.bestX);
                for (bezier.evaluation.Coordinates c : traj) {
                    trajectorySeries.add (c.getX (), c.getY ());
                }
            }

            lblGen.setText (String.valueOf (state.generation));
            lblEvals.setText (String.valueOf (state.evaluations));
            lblRestart.setText (String.valueOf (state.restarts));
            lblFitness.setText (String.format ("%.6f", state.bestFitness));
            lblSigma.setText (String.format ("%.4e", state.sigma));
            lblTime.setText (String.format ("%.1f s", timeSec));
        });
    }

    @Override
    public void onFinished (String name, OptimizerState finalState, long elapsedMs)
    {
        SwingUtilities.invokeLater (() -> {
            onStateUpdate (name, finalState, elapsedMs);
            btnStart.setEnabled (true);
            btnStop.setEnabled (false);
            lblTime.setText (String.format ("%.1f s (terminé)", elapsedMs / 1000.0));
        });
    }
}
