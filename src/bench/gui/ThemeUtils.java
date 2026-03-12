package bench.gui;

import org.jfree.chart.JFreeChart;
import java.awt.Color;
import java.awt.Font;

public class ThemeUtils {
    public static void applyDarkThemeToChart(JFreeChart chart) {
        Color bgColor = new Color(43, 45, 48); // FlatDarkLaf background approx
        Color fgColor = Color.LIGHT_GRAY;
        Color gridColor = new Color(75, 77, 81);

        chart.setBackgroundPaint(bgColor);
        chart.getTitle().setPaint(Color.WHITE);
        chart.getTitle().setFont(new Font("Inter", Font.BOLD, 16));

        if (chart.getLegend() != null) {
            chart.getLegend().setBackgroundPaint(bgColor);
            chart.getLegend().setItemPaint(Color.WHITE);
            chart.getLegend().setItemFont(new Font("Inter", Font.PLAIN, 12));
        }

        var plot = chart.getXYPlot();
        plot.setBackgroundPaint(bgColor);
        plot.setDomainGridlinePaint(gridColor);
        plot.setRangeGridlinePaint(gridColor);
        plot.setOutlineVisible(false);

        var domainAxis = plot.getDomainAxis();
        domainAxis.setTickLabelPaint(fgColor);
        domainAxis.setTickLabelFont(new Font("Inter", Font.PLAIN, 11));
        domainAxis.setLabelPaint(Color.WHITE);
        domainAxis.setLabelFont(new Font("Inter", Font.BOLD, 12));
        domainAxis.setAxisLinePaint(gridColor);

        var rangeAxis = plot.getRangeAxis();
        rangeAxis.setTickLabelPaint(fgColor);
        rangeAxis.setTickLabelFont(new Font("Inter", Font.PLAIN, 11));
        rangeAxis.setLabelPaint(Color.WHITE);
        rangeAxis.setLabelFont(new Font("Inter", Font.BOLD, 12));
        rangeAxis.setAxisLinePaint(gridColor);
    }
}
