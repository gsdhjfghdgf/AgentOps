package com.agentops.filetools;

import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTSerTx;

import java.awt.*;
import java.awt.geom.Rectangle2D;

/** POI does not paint native charts itself. Draw their cached values for previews without altering editable PPTX charts. */
final class NativeChartPreview {
    private static final Color[] COLORS = {new Color(49, 100, 71), new Color(55, 111, 163), new Color(180, 119, 46)};
    private NativeChartPreview() {}

    static void draw(Graphics2D canvas, Rectangle2D frame, XSLFChart chart) {
        var groups = chart.getChartSeries();
        if (groups.size() != 1 || !(groups.get(0) instanceof XDDFBarChartData || groups.get(0) instanceof XDDFLineChartData))
            throw new IllegalArgumentException("当前预览仅支持简单柱状和折线图；复杂图表请下载用Office查看");
        var data = groups.get(0); boolean bars = data instanceof XDDFBarChartData;
        if (bars && (((XDDFBarChartData) data).getBarDirection() != BarDirection.COL ||
                ((XDDFBarChartData) data).getBarGrouping() != BarGrouping.CLUSTERED))
            throw new IllegalArgumentException("当前预览仅支持簇状纵向柱图；水平或堆叠图请下载用Office查看");
        int count = data.getSeriesCount();
        if (count < 1 || count > 3) throw new IllegalArgumentException("预览图表支持1～3条序列");
        int points = data.getSeries(0).getCategoryData().getPointCount();
        if (points < 1 || points > 12) throw new IllegalArgumentException("预览图表支持1～12个类别");
        double[][] numbers = new double[count][points]; double min = 0, max = 0;
        for (int s = 0; s < count; s++) {
            var values = data.getSeries(s).getValuesData();
            if (values.getPointCount() != points) throw new IllegalArgumentException("图表缓存长度不一致");
            for (int i = 0; i < points; i++) {
                var value = values.getPointAt(i);
                if (value == null || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("图表缓存数据缺失，不能生成可信预览");
                numbers[s][i] = value.doubleValue(); min = Math.min(min, numbers[s][i]); max = Math.max(max, numbers[s][i]);
            }
        }
        if (min == max) max = min + 1;
        var g = (Graphics2D) canvas.create();
        try {
            g.translate(frame.getX(), frame.getY()); g.setClip(0, 0, (int) frame.getWidth(), (int) frame.getHeight());
            g.setColor(Color.WHITE); g.fillRect(0, 0, (int) frame.getWidth(), (int) frame.getHeight());
            double x0 = 67, y0 = 18, width = frame.getWidth() - 85, height = frame.getHeight() - 98;
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            for (int tick = 0; tick <= 4; tick++) {
                int y = (int) (y0 + tick * height / 4);
                g.setColor(new Color(222, 228, 222)); g.drawLine((int) x0, y, (int) (x0 + width), y);
                g.setColor(Color.DARK_GRAY); g.drawString(String.format(java.util.Locale.ROOT, "%.4g", max - tick * (max - min) / 4), 0, y + 5);
            }
            for (int s = 0; s < count; s++) {
                int previousX = 0, previousY = 0;
                for (int i = 0; i < points; i++) {
                    int x = (int) (x0 + (i + 0.5) * width / points), y = (int) (y0 + (max - numbers[s][i]) / (max - min) * height);
                    int zero = (int) (y0 + max / (max - min) * height);
                    g.setColor(seriesColor(chart,bars,s)); g.setStroke(new BasicStroke(2));
                    if (bars) {
                        double barWidth = Math.min(35, width / points * 0.7 / count);
                        int barX = (int) (x + (s - count / 2.0) * barWidth);
                        g.fillRect(barX, Math.min(zero, y), Math.max(2, (int) barWidth - 2), Math.max(1, Math.abs(zero - y)));
                    } else { if (i > 0) g.drawLine(previousX, previousY, x, y); g.fillOval(x - 3, y - 3, 6, 6); }
                    previousX = x; previousY = y;
                }
                CTSerTx title = bars ? chart.getCTChart().getPlotArea().getBarChartArray(0).getSerArray(s).getTx()
                        : chart.getCTChart().getPlotArea().getLineChartArray(0).getSerArray(s).getTx();
                String label = title == null ? "序列" + (s + 1) : title.isSetV() ? title.getV() :
                        title.isSetStrRef() && title.getStrRef().isSetStrCache() && title.getStrRef().getStrCache().sizeOfPtArray() > 0 ? title.getStrRef().getStrCache().getPtArray(0).getV() : "序列" + (s + 1);
                g.setColor(seriesColor(chart,bars,s)); g.drawString(PresentationService.clip(label, 20), (int) x0 + s * 220, (int) frame.getHeight() - 8);
            }
            g.setColor(Color.DARK_GRAY); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            var labels = data.getSeries(0).getCategoryData();
            for (int i = 0; i < points; i++) {
                String label = String.valueOf(labels.getPointAt(i)); int x = (int) (x0 + (i + 0.5) * width / points) - 25;
                g.drawString(PresentationService.clip(label, 8), x, (int) (y0 + height + 20));
                if (label.length() > 8) g.drawString(PresentationService.clip(label.substring(8), 8), x, (int) (y0 + height + 35));
            }
        } finally { g.dispose(); }
    }
    private static Color seriesColor(XSLFChart chart,boolean bars,int index){
        var properties=bars?chart.getCTChart().getPlotArea().getBarChartArray(0).getSerArray(index).getSpPr():chart.getCTChart().getPlotArea().getLineChartArray(0).getSerArray(index).getSpPr();
        if(properties!=null&&properties.isSetSolidFill()&&properties.getSolidFill().isSetSrgbClr()){
            byte[] rgb=properties.getSolidFill().getSrgbClr().getVal();if(rgb.length==3)return new Color(rgb[0]&255,rgb[1]&255,rgb[2]&255);
        }
        return COLORS[index];
    }
}
