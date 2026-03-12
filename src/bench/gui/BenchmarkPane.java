package bench.gui;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import bezier.evaluation.Problem;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Onglet "Benchmark" : lance chaque algorithme sélectionné sur chaque
 * fichier .bzr du dossier data/, avec N répétitions, et affiche les
 * résultats dans un tableau (moyenne ± écart-type par algo × problème).
 */
public class BenchmarkPane extends JPanel
{
    private final ConfigPane config;

    private final DefaultTableModel tableModel;
    private final JTable resultTable;


    public BenchmarkPane (ConfigPane config)
    {
        this.config = config;
        setLayout (new BorderLayout (10, 10));
        setBorder (BorderFactory.createEmptyBorder (10, 10, 10, 10));

        // L'onglet Benchmark est maintenant juste un visionneur de CSV.
        // Les fonctionnalités de run sont dans ComparePane (ou via script).

        // === Tableau de résultats ===
        tableModel = new DefaultTableModel () {
            @Override public boolean isCellEditable (int row, int col) { return false; }
        };
        resultTable = new JTable (tableModel);
        resultTable.setFont (new Font ("Inter", Font.PLAIN, 13));
        resultTable.getTableHeader().setFont(new Font("Inter", Font.BOLD, 13));
        resultTable.setRowHeight (28);
        resultTable.setSelectionBackground(new Color(68, 71, 90));
        resultTable.setSelectionForeground(Color.WHITE);
        resultTable.setAutoCreateRowSorter (true);
        add (new JScrollPane (resultTable), BorderLayout.CENTER);

        // === Boutons Import/Export en bas ===
        JPanel bottomPanel = new JPanel (new FlowLayout (FlowLayout.RIGHT));
        JButton loadBtn = new JButton ("Charger CSV");
        loadBtn.addActionListener (e -> loadCSV ());
        bottomPanel.add (loadBtn);
        
        JButton exportBtn = new JButton ("Exporter CSV");
        exportBtn.addActionListener (e -> exportCSV ());
        bottomPanel.add (exportBtn);
        add (bottomPanel, BorderLayout.SOUTH);

        // Auto-load default CSV if it exists
        File defaultCsv = new File ("results/bench_results.csv");
        if (defaultCsv.exists()) {
            loadCSVFile(defaultCsv, false);
        }
    }



    private void exportCSV ()
    {
        if (tableModel.getRowCount () == 0) return;

        JFileChooser chooser = new JFileChooser ();
        chooser.setSelectedFile (new File ("benchmark_results.csv"));
        if (chooser.showSaveDialog (this) != JFileChooser.APPROVE_OPTION) return;

        try (java.io.PrintWriter pw = new java.io.PrintWriter (chooser.getSelectedFile ()))
        {
            // Header
            StringBuilder sb = new StringBuilder ();
            for (int c = 0; c < tableModel.getColumnCount (); c++)
            {
                if (c > 0) sb.append (",");
                sb.append (tableModel.getColumnName (c));
            }
            pw.println (sb);

            // Data
            for (int r = 0; r < tableModel.getRowCount (); r++)
            {
                sb.setLength (0);
                for (int c = 0; c < tableModel.getColumnCount (); c++)
                {
                    if (c > 0) sb.append (",");
                    Object val = tableModel.getValueAt (r, c);
                    sb.append (val != null ? val.toString () : "");
                }
                pw.println (sb);
            }

            JOptionPane.showMessageDialog (this,
                    "Exporté vers " + chooser.getSelectedFile ().getName (),
                    "Export CSV", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog (this,
                    "Erreur d'export : " + ex.getMessage (),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadCSV ()
    {
        JFileChooser chooser = new JFileChooser ();
        chooser.setCurrentDirectory (new File ("results"));
        if (chooser.showOpenDialog (this) != JFileChooser.APPROVE_OPTION) return;
        
        loadCSVFile(chooser.getSelectedFile(), true);
    }

    private void loadCSVFile (File file, boolean showCurrentSuccessMessage)
    {
        try (java.io.BufferedReader br = new java.io.BufferedReader (new java.io.FileReader (file)))
        {
            String headerLine = br.readLine ();
            if (headerLine == null) return;

            String[] cols = headerLine.split (",");
            tableModel.setRowCount (0);
            tableModel.setColumnCount (0);
            
            // Detection du format "Long" de BenchmarkComparison 
            // ex: Algorithm,Problem,Run,Score ou Config,Problem,Run,Score
            if (cols.length >= 4 
                && (cols[0].trim().equalsIgnoreCase("Algorithm") || cols[0].trim().equalsIgnoreCase("Config") || cols[0].trim().equalsIgnoreCase("Algo")) 
                && cols[1].trim().equalsIgnoreCase("Problem") 
                && cols[3].trim().equalsIgnoreCase("Score")) 
            {
                java.util.Map<String, java.util.Map<String, java.util.List<Double>>> data = new java.util.LinkedHashMap<>();
                java.util.List<String> problemNamesList = new java.util.ArrayList<>();
                
                String line;
                while ((line = br.readLine ()) != null)
                {
                    String[] parts = line.split (",", -1);
                    if (parts.length >= 4) {
                        String algo = parts[0].trim();
                        String prob = parts[1].trim();
                        double score = 0;
                        try { score = Double.parseDouble(parts[3].trim()); } catch(Exception e) { continue; }
                        
                        data.computeIfAbsent(algo, k -> new java.util.LinkedHashMap<>())
                            .computeIfAbsent(prob, k -> new java.util.ArrayList<>()).add(score);
                        if (!problemNamesList.contains(prob)) {
                            problemNamesList.add(prob);
                        }
                    }
                }
                
                tableModel.addColumn ("Algorithme");
                for (String p : problemNamesList) tableModel.addColumn (p);
                
                for (String algo : data.keySet()) {
                    Object[] row = new Object[problemNamesList.size() + 1];
                    row[0] = algo;
                    for (int i = 0; i < problemNamesList.size(); i++) {
                        java.util.List<Double> scores = data.get(algo).get(problemNamesList.get(i));
                        if (scores == null || scores.isEmpty()) {
                            row[i+1] = "---";
                        } else {
                            double sum = 0, sum2 = 0;
                            for (double v : scores) { sum += v; sum2 += v*v; }
                            double mean = sum / scores.size();
                            double std = (scores.size() > 1) ? Math.sqrt((sum2 - sum*sum/scores.size())/(scores.size()-1)) : 0;
                            row[i+1] = String.format(java.util.Locale.US, "%.4f ± %.4f", mean, std);
                        }
                    }
                    tableModel.addRow(row);
                }
            } 
            else 
            {
                // Chargement normal "Wide" format (celui exporté par DefaultTableModel)
                for (String col : cols)
                    tableModel.addColumn (col);

                String line;
                while ((line = br.readLine ()) != null)
                {
                    String[] values = line.split (",", -1);
                    tableModel.addRow (values);
                }
            }

            if (showCurrentSuccessMessage) {
                JOptionPane.showMessageDialog (this,
                        "Données chargées avec succès depuis " + file.getName (),
                        "Chargement CSV", JOptionPane.INFORMATION_MESSAGE);
            }
        }
        catch (Exception ex)
        {
            if (showCurrentSuccessMessage) {
                JOptionPane.showMessageDialog (this,
                        "Erreur de chargement : " + ex.getMessage (),
                        "Erreur", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
