package bezier2_0.projects.competitor.optipath;

import java.util.Random;

/**
 * Grid MAP-Elites BIPOP : archive Quality-Diversity pour guider les restarts.
 *
 * Maintient une grille 2D (20×20) indexée par le "descripteur comportemental"
 * de chaque solution (point de contrôle médian). Les cellules peu visitées
 * sont préférées pour les restarts, favorisant la diversité.
 */
public class GridMapElitesRestart implements RestartStrategy
{
    private static final int GRID_SIZE = 20;

    private int largeLambda;
    private final Random rng = new Random ();

    // Archive MAP-Elites
    private final int totalCells = GRID_SIZE * GRID_SIZE;
    private final double [][] archiveSolutions;
    private final double [] archiveFitness;
    private final int [] archiveVisits;

    private double gridMinX, gridMinY, gridCellW, gridCellH;
    private int d;
    private boolean initialized = false;

    public GridMapElitesRestart ()
    {
        archiveSolutions = new double [totalCells][];
        archiveFitness = new double [totalCells];
        archiveVisits = new int [totalCells];
        for (int i = 0; i < totalCells; i++)
            archiveFitness [i] = Double.POSITIVE_INFINITY;
    }

    private void ensureInit (RestartContext ctx)
    {
        if (!initialized)
        {
            this.d = ctx.d;
            BoundsChecker b = ctx.bounds;
            gridMinX = b.getLb () [0];
            gridMinY = b.getLb () [1];
            gridCellW = (b.getUb () [0] - b.getLb () [0]) / GRID_SIZE;
            gridCellH = (b.getUb () [1] - b.getLb () [1]) / GRID_SIZE;
            initialized = true;
        }
    }

    @Override
    public void onEvaluation (double [] x, double fitness)
    {
        if (!initialized || x == null) return;
        int cell = computeGridCell (x);
        if (cell < 0 || cell >= totalCells) return;

        archiveVisits [cell]++;
        if (fitness < archiveFitness [cell])
        {
            archiveSolutions [cell] = x.clone ();
            archiveFitness [cell] = fitness;
        }
    }

    @Override
    public RestartConfig nextRestart (RestartContext ctx)
    {
        ensureInit (ctx);

        if (largeLambda == 0)
            largeLambda = ctx.lambda0;

        int newLambda;
        double newSigma;

        if (ctx.restartCount % 2 == 1)
        {
            // Large restart : QD-guided
            largeLambda = Math.min (largeLambda * 2, 512);
            newLambda = largeLambda;
            newSigma = ctx.sigma0;
        }
        else
        {
            // Small restart
            newLambda = ctx.lambda0;
            newSigma = ctx.sigma0 / 10.0;
        }

        // Tenter de sélectionner un mean depuis l'archive QD
        double [] archiveMean = selectFromArchive ();
        if (archiveMean != null)
            return new RestartConfig (newLambda, newSigma, archiveMean);

        return new RestartConfig (newLambda, newSigma, null);
    }

    /**
     * Sélection inversement proportionnelle aux visites : favorise les cellules sous-explorées.
     */
    private double [] selectFromArchive ()
    {
        double totalWeight = 0;
        for (int i = 0; i < totalCells; i++)
        {
            if (archiveSolutions [i] != null)
                totalWeight += 1.0 / (1 + archiveVisits [i]);
        }
        if (totalWeight == 0) return null;

        double r = rng.nextDouble () * totalWeight;
        double cumul = 0;
        for (int i = 0; i < totalCells; i++)
        {
            if (archiveSolutions [i] != null)
            {
                cumul += 1.0 / (1 + archiveVisits [i]);
                if (cumul >= r)
                    return archiveSolutions [i].clone ();
            }
        }
        return null;
    }

    private int computeGridCell (double [] x)
    {
        int midIdx = d / 4; // milieu des CPs
        if (2 * midIdx + 1 >= x.length) return -1;

        double mx = x [2 * midIdx];
        double my = x [2 * midIdx + 1];

        int gx = (int) ((mx - gridMinX) / gridCellW);
        int gy = (int) ((my - gridMinY) / gridCellH);

        gx = Math.max (0, Math.min (GRID_SIZE - 1, gx));
        gy = Math.max (0, Math.min (GRID_SIZE - 1, gy));

        return gy * GRID_SIZE + gx;
    }
}
