package com.agentops.filetools;

import com.agentops.domain.Primitives.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.TextShape.TextAutofit;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import static com.agentops.filetools.PresentationService.*;

/** Native editable slide compositions; file registration and inspection stay in PresentationService. */
final class PresentationLayoutRenderer {
    static final Set<String> LAYOUTS=Set.of("cover","bullets","table","chart","image","metric","comparison","process","timeline","statement","section","image_split");
    private static final String FONT="Microsoft YaHei";
    private final FileToolSupport files;
    PresentationLayoutRenderer(FileToolSupport files){this.files=files;}
    void addSlide(Principal owner, XMLSlideShow deck, JsonNode spec, String theme, int page) throws Exception {
        String layout = spec.path("layout").asText("bullets");
        if (!LAYOUTS.contains(layout)) throw new IllegalArgumentException("未知幻灯片布局");
        var palette=PresentationTheme.of(theme); boolean tech=palette.modern(), lightCover=palette.lightCover();
        Color accent=palette.accent(),text=palette.text(),muted=palette.muted();
        var slide = deck.createSlide();
        slide.getXmlObject().getCSld().setName("AgentOps|"+theme+"|"+layout);
        // getBackground() otherwise inherits a shared layout/master background.
        // Changing that inherited object would recolor every slide, including the dark cover.
        if (!slide.getXmlObject().getCSld().isSetBg()) slide.getXmlObject().getCSld().addNewBg().addNewBgPr();
        boolean dark=tech && Set.of("cover","section","statement").contains(layout);
        slide.getBackground().setFillColor(dark?palette.dark():layout.equals("cover")&&!lightCover?accent:palette.background());
        if((tech || lightCover || spec.hasNonNull("imageArtifactId")) && layout.equals("cover")) {
            boolean image=spec.hasNonNull("imageArtifactId");
            if(image) picture(owner,deck,slide,requiredText(spec,"imageArtifactId",36),520,0,440,540,spec.path("imageFit").asText("cover"));
            box(slide,requiredText(spec,"title",40),52,115,image?445:850,245,image?44:56,lightCover?text:Color.WHITE,true,"title");
            box(slide,text(spec,"subtitle",140),55,390,image?410:790,100,22,lightCover?muted:new Color(220,228,240),false,"subtitle");
            if(theme.equals("editorial")&&!image) {
                for(var shape:slide.getShapes())if(shape instanceof XSLFTextShape textShape)
                    for(var paragraph:textShape.getTextParagraphs())paragraph.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
            }
        } else if(layout.equals("section") || layout.equals("statement")) {
            Color main=dark?Color.WHITE:text;
            box(slide,requiredText(spec,"title",40),52,55,850,95,30,dark?new Color(165,187,227):muted,false,"title");
            box(slide,requiredText(spec,"body",80),52,185,850,205,46,main,true,"statement");
            box(slide,text(spec,"caption",140),55,425,800,65,18,dark?new Color(193,204,225):muted,false,"caption");
        } else if (layout.equals("cover")) {
            box(slide, requiredText(spec, "title", 40), 60, 146, 840, 116, 44, Color.WHITE, true, "title");
            box(slide, text(spec, "subtitle", 180), 62, 293, 830, 98, 23, Color.WHITE, false, "subtitle");
        } else {
            box(slide, requiredText(spec, "title", 40), 48, 34, 864, 86, tech?36:32, text, true, "title");
            switch (layout) {
                case "bullets" -> {
                    JsonNode bullets = spec.path("bullets");
                    if (!bullets.isArray() || bullets.isEmpty() || bullets.size() > 6) throw new IllegalArgumentException("正文需要1～6条bullets");
                    double rowHeight = 337.0 / bullets.size(); int index = 0;
                    for (var bullet : bullets) {
                        if(tech)box(slide,String.format(java.util.Locale.ROOT,"%02d",index+1),52,138+rowHeight*index,45,rowHeight-10,14,accent,true,"ordinal");
                        box(slide, scalar(bullet, 100), tech?112:53, 138 + rowHeight * index++, tech?780:850, rowHeight - 10, tech&&bullets.size()<=3?28:24, text, false, "body");
                    }
                }
                case "table" -> table(slide, spec.path("rows"), accent,text);
                case "chart" -> chart(deck, slide, spec.path("chart"),palette,true);
                case "metric" -> {
                    String value=requiredText(spec,"metric",12);
                    box(slide,value,48,140,410,220,value.length()>6?72:104,accent,true,"metric");
                    box(slide,requiredText(spec,"label",60),500,175,405,112,30,text,true,"metric-label");
                    box(slide,text(spec,"body",160),503,320,395,145,21,muted,false,"body");
                    box(slide,text(spec,"caption",140),52,460,400,42,15,muted,false,"caption");
                }
                case "comparison" -> {
                    column(slide,spec,"left",52,accent,text);column(slide,spec,"right",510,palette.secondary(),text);
                }
                case "process","timeline" -> steps(slide,spec,layout,palette);
                case "image_split" -> {
                    boolean left=spec.path("imageSide").asText("right").equals("left");
                    if(spec.has("imageSide")&&!Set.of("left","right").contains(spec.path("imageSide").asText()))throw new IllegalArgumentException("imageSide为left或right");
                    picture(owner,deck,slide,requiredText(spec,"imageArtifactId",36),left?48:502,140,410,320,spec.path("imageFit").asText("contain"));
                    box(slide,requiredText(spec,"body",180),left?510:52,155,390,255,28,text,false,"body");
                    box(slide,text(spec,"caption",100),52,475,850,28,15,muted,false,"caption");
                }
                case "image" -> {
                    var source = files.input(owner, requiredText(spec, "imageArtifactId", 36), "png", "jpg", "jpeg");
                    BufferedImage image = FileToolSupport.image(source.file());
                    var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
                    var picture = slide.createPicture(deck.addPicture(bytes.toByteArray(), PictureType.PNG));
                    double scale = Math.min(840.0 / image.getWidth(), 320.0 / image.getHeight());
                    double w = image.getWidth() * scale, h = image.getHeight() * scale;
                    picture.setAnchor(new Rectangle2D.Double(60 + (840 - w) / 2, 130 + (320 - h) / 2, w, h));
                    box(slide, text(spec, "caption", 100), 60, 459, 840, 33, 16, muted, false, "caption");
                }
                default -> throw new IllegalArgumentException("未知布局");
            }
        }
        box(slide, Integer.toString(page), tech&&layout.equals("cover")?52:868, 510, 48, 20, 10, dark||layout.equals("cover")&&!lightCover ? Color.WHITE : muted, false, "folio");
        String notes = text(spec, "notes", 2000);
        JsonNode sources = spec.path("sources");
        if (!sources.isMissingNode()) {
            if (!sources.isArray() || sources.size() > 5) throw new IllegalArgumentException("sources最多5条");
            var sourceNotes = new StringBuilder("\n资料来源（作者提供，尚需事实核验）：\n");
            for (var source : sources) sourceNotes.append(scalar(source, 400)).append('\n');
            notes += sourceNotes;
        }
        if (!notes.isBlank()) {
            var noteSlide = deck.getNotesSlide(slide);
            var body = noteSlide.createTextBox(); body.setText(notes);
        }
    }

    private void table(XSLFSlide slide, JsonNode rows, Color accent,Color text) {
        if (!rows.isArray() || rows.size() < 1 || rows.size() > 8 || !rows.get(0).isArray()) throw new IllegalArgumentException("rows须为1～8行二维数组");
        int cols = rows.get(0).size();
        if (cols < 1 || cols > 5) throw new IllegalArgumentException("表格最多5列");
        var table = slide.createTable(rows.size(), cols);
        table.setAnchor(new Rectangle2D.Double(48, 142, 864, 324));
        for (int c = 0; c < cols; c++) table.setColumnWidth(c, 864.0 / cols);
        for (int r = 0; r < rows.size(); r++) {
            if (!rows.get(r).isArray() || rows.get(r).size() != cols) throw new IllegalArgumentException("表格行列数须一致");
            table.setRowHeight(r, 324.0 / rows.size());
            for (int c = 0; c < cols; c++) {
                var cell = table.getCell(r, c); String content = scalar(rows.get(r).get(c), 120);
                cell.setText(content); cell.setFillColor(r == 0 ? accent : Color.WHITE);
                cell.setLeftInset(10); cell.setRightInset(10); cell.setTopInset(8); cell.setBottomInset(8);
                var run = cell.getTextParagraphs().get(0).getTextRuns().get(0);
                run.setFontFamily(FONT); run.setFontSize(18.0); run.setBold(r == 0); run.setFontColor(r == 0 ? Color.WHITE : text);
            }
        }
    }

    private void chart(XMLSlideShow deck, XSLFSlide slide, JsonNode input,PresentationTheme palette,boolean styled) {
        validateFields(input, Set.of("type", "categories", "series"));
        String type = input.path("type").asText("bar");
        if (!Set.of("bar", "line").contains(type)) throw new IllegalArgumentException("原生图表支持bar或line");
        var categories = input.path("categories"); var series = input.path("series");
        if (!categories.isArray() || categories.isEmpty() || categories.size() > 8 || !series.isArray() || series.isEmpty() || series.size() > 3)
            throw new IllegalArgumentException("图表须有1～8个类别、1～3个序列");
        var chart = deck.createChart(slide);
        if(styled){
            var textStyle=chart.getCTChartSpace().isSetTxPr()?chart.getCTChartSpace().getTxPr():chart.getCTChartSpace().addNewTxPr();
            textStyle.addNewBodyPr();textStyle.addNewLstStyle();
            var defaults=textStyle.addNewP().addNewPPr().addNewDefRPr();defaults.setSz(1600);defaults.addNewLatin().setTypeface(FONT);defaults.addNewEa().setTypeface(FONT);
        }
        var categoryAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        var valueAxis = chart.createValueAxis(AxisPosition.LEFT);
        valueAxis.setCrosses(AxisCrosses.AUTO_ZERO);
        var data = chart.createData(type.equals("bar") ? ChartTypes.BAR : ChartTypes.LINE, categoryAxis, valueAxis);
        if (data instanceof XDDFBarChartData bars) { bars.setBarDirection(BarDirection.COL); bars.setBarGrouping(BarGrouping.CLUSTERED); }
        String[] labels = new String[categories.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = scalar(categories.get(i), 20);
        int seriesIndex=0;
        for (var item : series) {
            validateFields(item, Set.of("name", "values")); var values = item.path("values");
            if (!values.isArray() || values.size() != labels.length) throw new IllegalArgumentException("序列长度必须与类别一致");
            Double[] numbers = new Double[labels.length];
            for (int i = 0; i < numbers.length; i++) {
                if (!values.get(i).isNumber() || !Double.isFinite(values.get(i).asDouble())) throw new IllegalArgumentException("图表数据须为有限数值");
                numbers[i] = values.get(i).asDouble();
            }
            var line = data.addSeries(XDDFDataSourcesFactory.fromArray(labels), XDDFDataSourcesFactory.fromArray(numbers));
            line.setTitle(requiredText(item, "name", 40), null);
            if(styled){
                Color color=seriesIndex++==0?palette.accent():seriesIndex==2?palette.secondary():palette.muted();
                var properties=new org.apache.poi.xddf.usermodel.XDDFShapeProperties();
                properties.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(org.apache.poi.xddf.usermodel.XDDFColor.from(new byte[]{(byte)color.getRed(),(byte)color.getGreen(),(byte)color.getBlue()})));
                if(data instanceof XDDFLineChartData){var stroke=new org.apache.poi.xddf.usermodel.XDDFLineProperties();stroke.setWidth(2.0);stroke.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(org.apache.poi.xddf.usermodel.XDDFColor.from(new byte[]{(byte)color.getRed(),(byte)color.getGreen(),(byte)color.getBlue()})));properties.setLineProperties(stroke);}
                line.setShapeProperties(properties);
            }
        }
        chart.plot(data); chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        // Unlike XSLFSimpleShape.setAnchor, this POI overload takes raw EMUs.
        slide.addChart(chart, new Rectangle2D.Double(org.apache.poi.util.Units.toEMU(60), org.apache.poi.util.Units.toEMU(136),
                org.apache.poi.util.Units.toEMU(840), org.apache.poi.util.Units.toEMU(338)));
    }

    private void column(XSLFSlide slide,JsonNode spec,String side,int x,Color accent,Color text){
        box(slide,requiredText(spec,side+"Title",24),x,142,395,70,28,accent,true,side+"-title");
        var items=spec.path(side);if(!items.isArray()||items.isEmpty()||items.size()>4)throw new IllegalArgumentException(side+"需要1～4条");
        double height=265.0/items.size();int i=0;
        for(var item:items)box(slide,scalar(item,80),x,232+height*i++,395,height-12,22,text,false,side+"-body");
    }

    private void steps(XSLFSlide slide,JsonNode spec,String layout,PresentationTheme palette){
        var items=spec.path("steps");if(!items.isArray()||items.size()<2||items.size()>4)throw new IllegalArgumentException("steps需要2～4项");
        int i=0;double width=864.0/items.size();
        for(var step:items){
            validateFields(step,Set.of("title","body","date"));
            if(layout.equals("timeline")){
                double y=145+i*85;
                box(slide,requiredText(step,"date",20),52,y,145,48,22,palette.accent(),true,"date");
                box(slide,requiredText(step,"title",24),220,y,655,42,25,palette.text(),true,"step-title");
                box(slide,text(step,"body",70),223,y+40,650,39,17,palette.muted(),false,"step-body");
            }else{
                double x=52+i*width;
                box(slide,String.format(java.util.Locale.ROOT,"%02d",i+1),x,144,width-22,110,56,palette.accent(),true,"step-number");
                box(slide,requiredText(step,"title",20),x,260,width-28,76,25,palette.text(),true,"step-title");
                box(slide,text(step,"body",70),x,365,width-30,105,19,palette.muted(),false,"step-body");
                if(i<items.size()-1)box(slide,"→",x+width-42,180,30,40,24,palette.muted(),false,"flow-arrow");
            }
            i++;
        }
    }

    private void picture(Principal owner,XMLSlideShow deck,XSLFSlide slide,String id,double x,double y,double w,double h,String fit)throws Exception{
        if(!Set.of("contain","cover").contains(fit))throw new IllegalArgumentException("imageFit为contain或cover");
        var input=files.input(owner,id,"png","jpg","jpeg");var image=FileToolSupport.image(input.file());
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        var picture=slide.createPicture(deck.addPicture(bytes.toByteArray(),PictureType.PNG));
        if(fit.equals("contain")){
            double scale=Math.min(w/image.getWidth(),h/image.getHeight()),width=image.getWidth()*scale,height=image.getHeight()*scale;
            picture.setAnchor(new Rectangle2D.Double(x+(w-width)/2,y+(h-height)/2,width,height));return;
        }
        picture.setAnchor(new Rectangle2D.Double(x,y,w,h));
        var crop=((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture)picture.getXmlObject()).getBlipFill().addNewSrcRect();
        double ratio=(double)image.getWidth()/image.getHeight(),target=w/h;
        if(ratio>target){int inset=(int)Math.round((1-target/ratio)*50000);crop.setL(inset);crop.setR(inset);}
        else{int inset=(int)Math.round((1-ratio/target)*50000);crop.setT(inset);crop.setB(inset);}
    }

    private static XSLFTextBox box(XSLFSlide slide, String content, double x, double y, double w, double h,
                                    double size, Color color, boolean bold, String name) {
        var shape = slide.createTextBox(); shape.setAnchor(new Rectangle2D.Double(x, y, w, h));
        ((CTShape) shape.getXmlObject()).getNvSpPr().getCNvPr().setName(name);
        shape.setLeftInset(0); shape.setRightInset(0); shape.setTopInset(0); shape.setBottomInset(0);
        shape.setWordWrap(true); shape.setTextAutofit(TextAutofit.NONE);
        shape.setText(content);
        for(var paragraph:shape.getTextParagraphs()){
            paragraph.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.LEFT);
            for(var run:paragraph.getTextRuns()){
                run.setFontFamily(FONT);run.setFontFamily(FONT,org.apache.poi.common.usermodel.fonts.FontGroup.EAST_ASIAN);
                run.setFontSize(size);run.setFontColor(color);run.setBold(bold);
            }
        }
        return shape;
    }

}
