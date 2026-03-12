package bench.gui;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Onglet "Comparaison" : lance plusieurs algorithmes en parallèle
 * et affiche leurs courbes de convergence superposées + tableau récapitulatif.
 */
public class ComparePane extends JPanel implements AlgorithmRunner.StateListener
{
    private final ConfigPane config;

    private final XYSeriesCollection dataset;
    private final JFreeChart chart;
    private final ChartPanel chartPanel;

    private final DefaultTableModel tableModel;
    private final JTable resultTable;

    private final JButton btnStart = new JButton ("\u25B6 Lancer la comparaison");
    private final JButton btnStop  = new JButton ("\u25A0 Arrêter tout");

    private final Map<String, XYSeries> seriesMap = new LinkedHashMap<> ();
    private final Map<String, Integer> tableRowMap = new LinkedHashMap<> ();
    private final List<AlgorithmRunner> runners = new ArrayList<> ();

    private static final String [] COLUMNS = {
        "Algorithme", "Fitness", "Générations", "Évaluations", "Restarts", "Temps (s)", "État"
    };

    public ComparePane (ConfigPane config)
    {
        this.config = config;
        setLayout (new BorderLayout (10, 10));
        setBorder (BorderFactory.createEmptyBorder (10, 10, 10, 10));

        // === Graphique superposé ===
        dataset = new XYSeriesCollection ();
        chart = ChartFactory.createXYLineChart (
                "Comparaison des algorithmes", "Temps (s)", "Meilleure fitness",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        XYPlot plot = chart.getXYPlot ();
        plot.getRangeAxis ().setAutoRange (true);
        ThemeUtils.applyDarkThemeToChart(chart);
        chartPanel = new ChartPanel (chart);
        chartPanel.setPreferredSize (new Dimension (900, 450));
        add (chartPanel, BorderLayout.CENTER);

        // === Tableau récapitulatif ===
        tableModel = new DefaultTableModel (COLUMNS, 0) {
            @Override public boolean isCellEditable (int row, int col) { return false; }
        };
        resultTable = new JTable (tableModel);
        resultTable.setFont (new Font ("Inter", Font.PLAIN, 13));
        resultTable.getTableHeader().setFont(new Font("Inter", Font.BOLD, 13));
        resultTable.setRowHeight (28);
        resultTable.setSelectionBackground(new Color(68, 71, 90));
        resultTable.setSelectionForeground(Color.WHITE);
        JScrollPane scrollPane = new JScrollPane (resultTable);
        scrollPane.setPreferredSize (new Dimension (900, 200));
        add (scrollPane, BorderLayout.SOUTH);

        // === Boutons ===
        JPanel btnPanel = new JPanel (new FlowLayout (FlowLayout.CENTER, 20, 5));
        btnStop.setEnabled (false);
        btnStart.addActionListener (e -> startComparison ());
        btnStop.addActionListener (e -> stopAll ());
        btnPanel.add (btnStart);
        btnPanel.add (btnStop);
        add (btnPanel, BorderLayout.NORTH);
    }

    private void startComparison ()
    {
        Problem problem = config.getProblem ();
        if (problem == null)
        {
            JOptionPane.showMessageDialog (this,
                    "Veuillez charger un problème dans l'onglet Configuration.",
                    "Pas de problème", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> algos = config.getSelectedAlgorithms ();
        if (algos.size () < 2)
        {
            JOptionPane.showMessageDialog (this,
                    "Sélectionnez au moins 2 algorithmes pour comparer.",
                    "Pas assez d'algorithmes", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Reset
        dataset.removeAllSeries ();
        seriesMap.clear ();
        tableRowMap.clear ();
        tableModel.setRowCount (0);
        runners.clear ();

        long timeMs = config.getTimeLimitSeconds () * 1000L;

        for (int i = 0; i < algos.size (); i++)
        {
            String algoName = algos.get (i);
            Optimizer opt = AlgorithmFactory.create (algoName, problem, config.getMargin());

            XYSeries series = new XYSeries (algoName);
            dataset.addSeries (series);
            seriesMap.put (algoName, series);

            tableModel.addRow (new Object [] {algoName, "-", "-", "-", "-", "-", "En cours..."});
            tableRowMap.put (algoName, i);

            AlgorithmRunner runner = new AlgorithmRunner (algoName, opt, timeMs);
            runner.addListener (this);
            runners.add (runner);
        }

        btnStart.setEnabled (false);
        btnStop.setEnabled (true);

        // Lancer tous les runners
        for (AlgorithmRunner r : runners)
            r.start ();
    }

    private void stopAll ()
    {
        for (AlgorithmRunner r : runners)
            r.stop ();
        btnStart.setEnabled (true);
        btnStop.setEnabled (false);
    }

    // === StateListener callbacks ===

    @Override
    public void onStateUpdate (String name, OptimizerState state, long elapsedMs)
    {
        SwingUtilities.invokeLater (() -> {
            XYSeries series = seriesMap.get (name);
            if (series != null)
                series.add (elapsedMs / 1000.0, state.bestFitness);

            Integer row = tableRowMap.get (name);
            if (row != null)
            {
                tableModel.setValueAt (String.format ("%.6f", state.bestFitness), row, 1);
                tableModel.setValueAt (state.generation, row, 2);
                tableModel.setValueAt (state.evaluations, row, 3);
                tableModel.setValueAt (state.restarts, row, 4);
                tableModel.setValueAt (String.format ("%.1f", elapsedMs / 1000.0), row, 5);
            }
        });
    }

    @Override
    public void onFinished (String name, OptimizerState finalState, long elapsedMs)
    {
        SwingUtilities.invokeLater (() -> {
            onStateUpdate (name, finalState, elapsedMs);
            Integer row = tableRowMap.get (name);
            if (row != null)
                tableModel.setValueAt ("Terminé", row, 6);

            // Si tous sont terminés, réactiver le bouton
            boolean allDone = runners.stream ().noneMatch (AlgorithmRunner::isRunning);
            if (allDone)
            {
                btnStart.setEnabled (true);
                btnStop.setEnabled (false);
            }
        });
    }
}
