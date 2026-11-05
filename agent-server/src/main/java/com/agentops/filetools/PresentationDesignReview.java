package com.agentops.filetools;

import org.apache.poi.xslf.usermodel.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Deterministic design warnings, explicitly distinct from a vision-model review. */
final class PresentationDesignReview {
    private PresentationDesignReview(){}
    static Map<String,Object> review(XMLSlideShow deck){
        var warnings=new ArrayList<Map<String,Object>>();var layouts=new LinkedHashMap<String,Integer>();
        int pictureSlides=0,streak=0;String previous="";
        for(var slide:deck.getSlides()){
            String name=slide.getXmlObject().getCSld().getName();String[] parts=name==null?new String[0]:name.split("\\|");
            String layout=parts.length==3&&parts[0].equals("AgentOps")?parts[2]:"unknown";
            layouts.merge(layout,1,Integer::sum);streak=layout.equals(previous)?streak+1:1;previous=layout;
            if(streak==3&&!layout.equals("unknown"))warnings.add(warning(slide,"repeated_layout","连续三页相同版式，请考虑重点数字、对比、图文或图表"));
            int characters=0;boolean hasImage=false;
            for(var shape:slide.getShapes()){
                if(shape instanceof XSLFTextShape text&&!"folio".equals(shape.getShapeName()))characters+=text.getText().length();
                if(shape instanceof XSLFPictureShape picture){
                    hasImage=true;
                    try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(picture.getPictureData().getData()))){
                        var readers=ImageIO.getImageReaders(in);
                        if(readers.hasNext()){var reader=readers.next();try{reader.setInput(in);if(reader.getWidth(0)<600||reader.getHeight(0)<350)warnings.add(warning(slide,"low_resolution_image","图片清晰度偏低，请换更大的原图"));}finally{reader.dispose();}}
                    }catch(java.io.IOException e){warnings.add(warning(slide,"image_metadata_unavailable","无法检查图片尺寸，请查看渲染结果"));}
                }
            }
            if(hasImage)pictureSlides++;
            if(characters>280)warnings.add(warning(slide,"dense_text","单页文字较多，请保留核心信息并把细节放入讲稿"));
        }
        long types=layouts.keySet().stream().filter(x->!x.equals("unknown")).count();
        if(deck.getSlides().size()>=6&&types>0&&types<4)warnings.add(Map.of("slide",0,"code","limited_layout_variety","suggestion","长文稿建议至少4种布局，避免整份都是条目列表"));
        return Map.of("mode","rules_only","visionReviewed",false,"layoutCounts",layouts,"pictureSlides",pictureSlides,
                "warnings",warnings,"notice","这是文字密度、版式重复与图片尺寸检查；不能替代逐页视觉审阅，也不证明资料真实。");
    }
    private static Map<String,Object> warning(XSLFSlide slide,String code,String suggestion){return Map.of("slide",slide.getSlideNumber(),"code",code,"suggestion",suggestion);}
}
