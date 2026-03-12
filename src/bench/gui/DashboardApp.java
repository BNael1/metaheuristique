package bench.gui;

import bezier.evaluation.Problem;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Dashboard principal — fenêtre Swing avec 4 onglets :
 *   1. Configuration : choix du problème et des algorithmes
 *   2. Live Run      : exécution en temps réel d'un algorithme
 *   3. Comparaison   : plusieurs algorithmes côte à côte
 *   4. Benchmark     : benchmark complet sur tous les problèmes
 */
public class DashboardApp extends JFrame
{
    private static final String TITLE = "OptiPath Dashboard";
    private static final int W = 1400, H = 900;

    private final JTabbedPane tabs;
    private ConfigPane configPane;
    private LiveRunPane liveRunPane;
    private ComparePane comparePane;
    private BenchmarkPane benchmarkPane;
    private TunerPane tunerPane;

    public DashboardApp ()
    {
        super (TITLE);
        setDefaultCloseOperation (JFrame.EXIT_ON_CLOSE);
        setSize (W, H);
        setLocationRelativeTo (null);

        tabs = new JTabbedPane ();
        configPane = new ConfigPane ();
        liveRunPane = new LiveRunPane (configPane);
        comparePane = new ComparePane (configPane);
        benchmarkPane = new BenchmarkPane (configPane);
        tunerPane = new TunerPane (configPane);

        tabs.addTab ("\u2699 Configuration", configPane);
        tabs.addTab ("\u25B6 Live Run", liveRunPane);
        tabs.addTab ("\u2194 Comparaison", comparePane);
        tabs.addTab ("\uD83D\uDCCA Benchmark", benchmarkPane);
        tabs.addTab ("\u2692 Tuner", tunerPane);

        add (tabs);
    }

    /** Passe sur l'onglet Live Run et lance l'algorithme sélectionné. */
    public void switchToLiveRun ()
    {
        tabs.setSelectedComponent (liveRunPane);
    }

    public static void main (String [] args)
    {
        Problem.headless = true; // Disable legacy legacy charts hooks for DashboardApp
        try { 
            // FlatLaf custom properties for a more modern, rounded look
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 12);
            UIManager.put("ProgressBar.arc", 12);
            UIManager.put("TextComponent.arc", 12);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("ScrollBar.thumbArc", 10);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            
            // Set FlatDarkLaf
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf"); 
        }
        catch (Exception ignored) {
            System.err.println("Failed to initialize FlatLaf");
        }

        SwingUtilities.invokeLater (() -> {
            DashboardApp app = new DashboardApp ();
            app.setVisible (true);
        });
    }
}
