package bench.gui;

import bezier.evaluation.Problem;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Panneau de configuration :
 *   - Sélection du fichier problème (.bzr)
 *   - Choix des algorithmes à utiliser (checkboxes)
 *   - Durée d'exécution (slider)
 */
public class ConfigPane extends JPanel
{
    // Clé = nom affiché, valeur = identifiant interne
    private static final String [] ALGO_NAMES = {
        "IPOP-CMA-ES",
        "BIPOP-CMA-ES",
        "Active CMA-ES",
        "Surrogate CMA-ES",
        "Adaptive BIPOP CMA-ES",
        "Grid BIPOP CMA-ES",
        "Sep-Warmup CMA-ES",
        "Full CMA-ES (Active+Surr+AdaBIPOP)",
        "DE jDE",
        "DE SHADE",
        "GA SBX",
        "OptiPath Final"
    };

    private final JComboBox<String> problemSelector;
    private final Map<String, JCheckBox> algoChecks = new LinkedHashMap<> ();
    private final JSpinner timeSpinner;
    private final JSpinner marginSpinner;
    private final JLabel problemInfoLabel;

    private Problem currentProblem;

    public ConfigPane ()
    {
        setLayout (new BorderLayout (10, 10));
        setBorder (BorderFactory.createEmptyBorder (15, 15, 15, 15));

        // === Haut : sélection du problème ===
        JPanel topPanel = new JPanel (new FlowLayout (FlowLayout.LEFT, 10, 5));
        topPanel.setBorder (BorderFactory.createTitledBorder ("Problème"));

        topPanel.add (new JLabel ("Fichier .bzr :"));
        problemSelector = new JComboBox<> ();
        problemSelector.setPreferredSize (new Dimension (250, 28));
        loadProblemFiles ();
        topPanel.add (problemSelector);

        JButton loadBtn = new JButton ("Charger");
        loadBtn.addActionListener (e -> loadSelectedProblem ());
        topPanel.add (loadBtn);

        problemInfoLabel = new JLabel ("Aucun problème chargé");
        problemInfoLabel.setFont (new Font (Font.MONOSPACED, Font.PLAIN, 12));
        topPanel.add (Box.createHorizontalStrut (20));
        topPanel.add (problemInfoLabel);

        add (topPanel, BorderLayout.NORTH);

        // === Centre : sélection des algorithmes ===
        JPanel algoPanel = new JPanel (new GridLayout (0, 3, 10, 5));
        algoPanel.setBorder (BorderFactory.createTitledBorder ("Algorithmes"));

        for (String name : ALGO_NAMES)
        {
            JCheckBox cb = new JCheckBox (name);
            // Cocher OptiPath Final par défaut (notre champion)
            if (name.equals ("OptiPath Final") || name.equals ("IPOP-CMA-ES")) cb.setSelected (true);
            algoChecks.put (name, cb);
            algoPanel.add (cb);
        }

        JButton selectAll = new JButton ("Tout sélectionner");
        selectAll.addActionListener (e -> algoChecks.values ().forEach (c -> c.setSelected (true)));
        JButton deselectAll = new JButton ("Tout désélectionner");
        deselectAll.addActionListener (e -> algoChecks.values ().forEach (c -> c.setSelected (false)));

        JPanel btnPanel = new JPanel (new FlowLayout (FlowLayout.LEFT));
        btnPanel.add (selectAll);
        btnPanel.add (deselectAll);

        JPanel centerWrapper = new JPanel (new BorderLayout ());
        centerWrapper.add (algoPanel, BorderLayout.CENTER);
        centerWrapper.add (btnPanel, BorderLayout.SOUTH);
        add (centerWrapper, BorderLayout.CENTER);

        // === Bas : Paramètres d'exécution ===
        JPanel bottomPanel = new JPanel (new FlowLayout (FlowLayout.LEFT, 15, 5));
        bottomPanel.setBorder (BorderFactory.createTitledBorder ("Paramètres d'exécution"));

        bottomPanel.add (new JLabel ("Durée (secondes) :"));
        timeSpinner = new JSpinner (new SpinnerNumberModel (60, 5, 600, 5));
        timeSpinner.setPreferredSize (new Dimension (80, 28));
        bottomPanel.add (timeSpinner);

        bottomPanel.add (new JLabel ("Marge CPs :"));
        marginSpinner = new JSpinner (new SpinnerNumberModel (0.0, 0.0, 50.0, 1.0));
        marginSpinner.setPreferredSize (new Dimension (80, 28));
        bottomPanel.add (marginSpinner);

        add (bottomPanel, BorderLayout.SOUTH);
    }

    private void loadProblemFiles ()
    {
        File dataDir = new File ("data");
        if (dataDir.isDirectory ())
        {
            File [] files = dataDir.listFiles ((d, n) -> n.endsWith (".bzr"));
            if (files != null)
                for (File f : files)
                    problemSelector.addItem (f.getName ());
        }
        if (problemSelector.getItemCount () == 0)
            problemSelector.addItem ("(aucun fichier .bzr trouvé)");
    }

    private void loadSelectedProblem ()
    {
        String filename = (String) problemSelector.getSelectedItem ();
        if (filename == null || filename.startsWith ("(")) return;

        try
        {
            // Problem constructor is package-private; use getProblems() to load
            java.util.ArrayList<Problem> all = Problem.getProblems ();
            currentProblem = null;
            for (Problem p : all)
                if (p.getName ().equals (filename.replace (".bzr", "")))
                { currentProblem = p; break; }
            if (currentProblem == null) { problemInfoLabel.setText ("Problème non trouvé"); return; }
            problemInfoLabel.setText (String.format (
                    "  %s  |  %d points de contrôle  |  dim = %d  |  %d obstacles",
                    filename,
                    currentProblem.getNControlPoints (),
                    2 * currentProblem.getNControlPoints (),
                    currentProblem.getNObstacles ()
            ));
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog (this, "Erreur : " + ex.getMessage (),
                    "Chargement échoué", JOptionPane.ERROR_MESSAGE);
        }
    }

    // === Accesseurs pour les autres panneaux ===

    public Problem getProblem () { return currentProblem; }

    public int getTimeLimitSeconds () { return (int) timeSpinner.getValue (); }

    public List<String> getSelectedAlgorithms ()
    {
        List<String> selected = new ArrayList<> ();
        for (Map.Entry<String, JCheckBox> e : algoChecks.entrySet ())
            if (e.getValue ().isSelected ())
                selected.add (e.getKey ());
        return selected;
    }

    public double getMargin () { return ((Number) marginSpinner.getValue()).doubleValue(); }
}
