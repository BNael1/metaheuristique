package bench.gui;

import engine.core.Optimizer;
import bezier.evaluation.Problem;
import engine.core.AlgorithmParameters;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Onglet "Tuner" - Optimisation des hyperparamètres (Grid Search).
 * Teste toutes les sous-combinaisons sélectionnées dans le Dashboard.
 */
public class TunerPane extends JPanel {

    private final ConfigPane config;

    private final DefaultTableModel tableModel;
    private final JTable resultTable;

    private final JTextField txtControlPoints = new JTextField("10, 15, 20");
    private final JTextField txtMargins = new JTextField("0.0, 1.0");

    private final Map<AlgorithmParameters.InitStrategy, JCheckBox> initChecks = new LinkedHashMap<>();
    private final Map<AlgorithmParameters.RestartStrategy, JCheckBox> restartChecks = new LinkedHashMap<>();
    private final Map<AlgorithmParameters.RepairStrategy, JCheckBox> repairChecks = new LinkedHashMap<>();
    private final Map<Boolean, JCheckBox> hybridChecks = new LinkedHashMap<>();

    private final JSpinner timeSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JSpinner runsSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));

    private final JButton btnStart = new JButton("\u25B6 Lancer Grid Search");
    private final JButton btnStop = new JButton("\u25A0 Arrêter");
    private final JButton btnSave = new JButton("Enregistrer Best Params");

    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel(" ");

    private volatile boolean running;
    private Thread tunerThread;

    private AlgorithmParameters globalBestParams = null;
    private double globalBestScore = Double.MAX_VALUE;

    public TunerPane(ConfigPane config) {
        this.config = config;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // === Panel Haut: Configuration Grid Search ===
        JPanel topPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        topPanel.setBorder(BorderFactory.createTitledBorder("Espaces de recherche (Grid Search)"));

        // Numériques
        JPanel pnlNum = new JPanel(new GridLayout(2, 2, 5, 5));
        pnlNum.add(new JLabel("Points de Contrôle (csv):"));
        pnlNum.add(txtControlPoints);
        pnlNum.add(new JLabel("Marges (csv):"));
        pnlNum.add(txtMargins);
        topPanel.add(pnlNum);

        // Booleans & Enums 
        JPanel pnlEnums = new JPanel(new GridLayout(2, 2, 5, 5));
        
        JPanel pnlInit = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlInit.setBorder(BorderFactory.createTitledBorder("Init"));
        for (AlgorithmParameters.InitStrategy val : AlgorithmParameters.InitStrategy.values()) {
            JCheckBox cb = new JCheckBox(val.name());
            cb.setSelected(val == AlgorithmParameters.InitStrategy.RANDOM); // default
            initChecks.put(val, cb);
            pnlInit.add(cb);
        }
        
        JPanel pnlRestart = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlRestart.setBorder(BorderFactory.createTitledBorder("Restart"));
        for (AlgorithmParameters.RestartStrategy val : AlgorithmParameters.RestartStrategy.values()) {
            JCheckBox cb = new JCheckBox(val.name());
            cb.setSelected(val == AlgorithmParameters.RestartStrategy.NONE);
            restartChecks.put(val, cb);
            pnlRestart.add(cb);
        }

        JPanel pnlRepair = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlRepair.setBorder(BorderFactory.createTitledBorder("Repair"));
        for (AlgorithmParameters.RepairStrategy val : AlgorithmParameters.RepairStrategy.values()) {
            JCheckBox cb = new JCheckBox(val.name());
            cb.setSelected(val == AlgorithmParameters.RepairStrategy.NONE);
            repairChecks.put(val, cb);
            pnlRepair.add(cb);
        }

        JPanel pnlHybrid = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlHybrid.setBorder(BorderFactory.createTitledBorder("Hybride"));
        JCheckBox cbFalse = new JCheckBox("FALSE", true);
        JCheckBox cbTrue = new JCheckBox("TRUE", false);
        hybridChecks.put(false, cbFalse);
        hybridChecks.put(true, cbTrue);
        pnlHybrid.add(cbFalse);
        pnlHybrid.add(cbTrue);

        pnlEnums.add(pnlInit);
        pnlEnums.add(pnlRestart);
        pnlEnums.add(pnlRepair);
        pnlEnums.add(pnlHybrid);

        topPanel.add(pnlEnums);
        add(topPanel, BorderLayout.NORTH);

        // === Centre: Tableau de monitoring ===
        tableModel = new DefaultTableModel(new String[]{"Algorithme", "Params (CP, M, Init, Restart, Rep, Hyb)", "Score Moyen \u00B1 StdDev"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        resultTable = new JTable(tableModel);
        resultTable.setFont(new Font("Inter", Font.PLAIN, 12));
        resultTable.setRowHeight(24);
        resultTable.setAutoCreateRowSorter(true);
        add(new JScrollPane(resultTable), BorderLayout.CENTER);

        // === Bas: Controls ===
        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel pnlControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        
        pnlControls.add(new JLabel("Temps / Essai (s):"));
        pnlControls.add(timeSpinner);
        pnlControls.add(new JLabel("Essais / Param :"));
        pnlControls.add(runsSpinner);

        btnStop.setEnabled(false);
        btnStart.addActionListener(e -> startTuner());
        btnStop.addActionListener(e -> stopTuner());
        btnSave.addActionListener(e -> saveBestParams());
        btnSave.setEnabled(false);

        pnlControls.add(btnStart);
        pnlControls.add(btnStop);
        pnlControls.add(btnSave);

        JPanel pnlProgress = new JPanel(new BorderLayout());
        progressBar.setStringPainted(true);
        pnlProgress.add(progressBar, BorderLayout.NORTH);
        pnlProgress.add(statusLabel, BorderLayout.SOUTH);

        bottomPanel.add(pnlControls, BorderLayout.WEST);
        bottomPanel.add(pnlProgress, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private List<Integer> parseCSVInts(String csv) {
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { list.add(Integer.parseInt(s.trim())); } catch(Exception ignored) {}
        }
        if (list.isEmpty()) list.add(15);
        return list;
    }

    private List<Double> parseCSVDoubles(String csv) {
        List<Double> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { list.add(Double.parseDouble(s.trim())); } catch(Exception ignored) {}
        }
        if (list.isEmpty()) list.add(1.0);
        return list;
    }

    private void startTuner() {
        Problem problem = config.getProblem();
        if (problem == null) {
            JOptionPane.showMessageDialog(this, "Préparez un problème dans l'onglet Configuration.", "Erreur", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> algos = config.getSelectedAlgorithms();
        if (algos.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Sélectionnez au moins un algorithme.", "Erreur", JOptionPane.WARNING_MESSAGE);
            return;
        }

        tableModel.setRowCount(0);
        globalBestParams = null;
        globalBestScore = Double.MAX_VALUE;
        btnSave.setEnabled(false);
        running = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        int timeSec = (int) timeSpinner.getValue();
        int runs = (int) runsSpinner.getValue();

        List<Integer> cps = parseCSVInts(txtControlPoints.getText());
        List<Double> margins = parseCSVDoubles(txtMargins.getText());

        List<AlgorithmParameters.InitStrategy> inits = new ArrayList<>();
        for (Map.Entry<AlgorithmParameters.InitStrategy, JCheckBox> e : initChecks.entrySet())
            if (e.getValue().isSelected()) inits.add(e.getKey());

        List<AlgorithmParameters.RestartStrategy> restarts = new ArrayList<>();
        for (Map.Entry<AlgorithmParameters.RestartStrategy, JCheckBox> e : restartChecks.entrySet())
            if (e.getValue().isSelected()) restarts.add(e.getKey());

        List<AlgorithmParameters.RepairStrategy> repairs = new ArrayList<>();
        for (Map.Entry<AlgorithmParameters.RepairStrategy, JCheckBox> e : repairChecks.entrySet())
            if (e.getValue().isSelected()) repairs.add(e.getKey());

        List<Boolean> hybrids = new ArrayList<>();
        for (Map.Entry<Boolean, JCheckBox> e : hybridChecks.entrySet())
            if (e.getValue().isSelected()) hybrids.add(e.getKey());

        // Generate combinations
        List<AlgorithmParameters> grid = new ArrayList<>();
        for (int cp : cps) {
            for (double m : margins) {
                for (AlgorithmParameters.InitStrategy ini : inits) {
                    for (AlgorithmParameters.RestartStrategy res : restarts) {
                        for (AlgorithmParameters.RepairStrategy rep : repairs) {
                            for (boolean hyb : hybrids) {
                                grid.add(new AlgorithmParameters(cp, m, ini, res, rep, hyb));
                            }
                        }
                    }
                }
            }
        }

        if (grid.isEmpty()) {
            JOptionPane.showMessageDialog(this, "La grille de recherche est vide. Cochez des options.", "Erreur", JOptionPane.WARNING_MESSAGE);
            stopTuner();
            return;
        }

        tunerThread = new Thread(() -> runGridSearch(algos, problem, grid, timeSec, runs));
        tunerThread.setDaemon(true);
        tunerThread.start();
    }

    private void runGridSearch(List<String> algos, Problem problem, List<AlgorithmParameters> grid, int timeSec, int runs) {
        int totalCombos = algos.size() * grid.size() * runs;
        int completed = 0;

        for (String algoName : algos) {
            for (AlgorithmParameters params : grid) {
                if (!running) return;

                double[] results = new double[runs];
                for (int r = 0; r < runs; r++) {
                    if (!running) return;

                    completed++;
                    final int c = completed;
                    final String stat = String.format("Grille %d / %d | %s | %s", c, totalCombos, algoName, params.toString());
                    
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(c * 100 / totalCombos);
                        progressBar.setString(c + " / " + totalCombos);
                        statusLabel.setText(stat);
                    });

                    // Write the parameters temporarily to a grid_search_temp.properties
                    // and have the Optimizer load from it!
                    params.saveToFile("grid_search_temp.properties");

                    // Create the optimizer which will read the properties file in its constructor.
                    Optimizer opt = AlgorithmFactory.create(algoName, problem, params.getMargin());
                    
                    opt.init();
                    long deadline = System.currentTimeMillis() + timeSec * 1000L;
                    while (running && System.currentTimeMillis() < deadline) {
                        opt.step();
                    }
                    results[r] = opt.getBestFitness();
                }

                double sum = 0, sum2 = 0;
                for (double v : results) { sum += v; sum2 += v * v; }
                double mean = sum / runs;
                double std = (runs > 1) ? Math.sqrt((sum2 - sum * sum / runs) / (runs - 1)) : 0;

                if (mean < globalBestScore) {
                    globalBestScore = mean;
                    globalBestParams = params;
                }

                final String dispScore = String.format("%.4f \u00B1 %.4f", mean, std);
                SwingUtilities.invokeLater(() -> tableModel.addRow(new Object[]{algoName, params.toString(), dispScore}));
            }
        }

        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(100);
            statusLabel.setText("Optimisation terminée. Meilleur score = " + String.format("%.4f", globalBestScore));
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            btnSave.setEnabled(globalBestParams != null);
            running = false;
        });
    }

    private void stopTuner() {
        running = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        statusLabel.setText("Recherche arrêtée.");
    }

    private void saveBestParams() {
        if (globalBestParams != null) {
            globalBestParams.saveToFile("best_params.properties");
            JOptionPane.showMessageDialog(this, "Paramètres sauvegardés avec succès dans best_params.properties", "Sauvegardé", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
