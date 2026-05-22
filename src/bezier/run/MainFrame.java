package bezier.run;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;

import javax.swing.JFrame;

import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;

/**
 * @author Alexandre Blansché
 * Fenêtre principale de l'application (singleton)
 */
public class MainFrame extends JFrame
{
	
	public static final double UI_SCALE = Toolkit.getDefaultToolkit().getScreenResolution() / 120.0;
    private static final long serialVersionUID = 1L;
    private static MainFrame instance = null;

    private MainFrame ()
    {
        this.setLayout (new BorderLayout ());
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int height = (int)(screen.height * 0.75);
        int width  = (int)(height * 3.0 / 4.0);
        this.setSize(width, height);
        this.setSize (width, height);
        this.setLocationRelativeTo (null);
        this.setDefaultCloseOperation (JFrame.EXIT_ON_CLOSE);
        this.setVisible (Main.DISPLAY_CHART);
    }
    
    public static void scaleChartFonts(JFreeChart chart)
    {
        float scale = (float) MainFrame.UI_SCALE;
        Font base = chart.getTitle().getFont();
        Font scaled = base.deriveFont(base.getSize2D() * scale);
        chart.getTitle().setFont(scaled);
        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLabelFont(scaled);
        plot.getDomainAxis().setTickLabelFont(scaled);
        plot.getRangeAxis().setLabelFont(scaled);
        plot.getRangeAxis().setTickLabelFont(scaled);
    }

    /**
     * @return L'instance unique de la fenêtre principale
     */
    public static MainFrame getInstance ()
    {
		if (Main.DISPLAY_CHART)
	        UIInitializer.init();
        if (MainFrame.instance == null)
            MainFrame.instance = new MainFrame ();
        return MainFrame.instance;
    }

    void clear ()
    {
        if (this.getContentPane ().getComponentCount () > 0)
            this.getContentPane ().removeAll ();
    }
}