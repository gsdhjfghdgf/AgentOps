package com.agentops.filetools;

import java.awt.Color;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;

/** A palette, not arbitrary model-supplied colors: consistent contrast across text, charts and previews. */
record PresentationTheme(Color accent, Color text, Color muted, Color background, Color dark, Color secondary) {
    static final List<String> ORDERED_NAMES=List.of("tech","business","academic","editorial","warm","vivid","forest","ocean","ink");
    static final Set<String> NAMES=Set.copyOf(ORDERED_NAMES);
    static List<Map<String,String>> catalog() {
        return List.of(
                style("tech","科技产品","AI、软件、产品演示","深蓝封面，大标题与数字，蓝青强调"),
                style("business","商务汇报","经营分析、项目汇报、决策建议","藏青封面，蓝金配色，克制的正文层级"),
                style("academic","学术研究","论文答辩、研究方法、实验结果","白底靛蓝，结论与证据并重，适合图表"),
                style("editorial","杂志人文","文化、人物、历史、品牌故事","米白底与朱红，居中封面，大幅图文留白"),
                style("warm","温暖生活","教育、健康、生活科普、社区","暖白底与陶土色，亲和的文字与图片"),
                style("vivid","活力创意","创意提案、活动、营销","紫色深底封面，紫橙强调，大标题"),
                style("forest","自然生态","环保、可持续发展、农业","森林绿封面，浅色正文，绿色图表"),
                style("ocean","海洋清新","旅行、海洋、地理","海蓝封面，浅蓝正文，蓝青配色"),
                style("ink","极简黑白","正式说明、文字报告、通用简报","浅灰封面，黑白灰，减少色彩干扰"));
    }
    private static Map<String,String> style(String id,String name,String use,String appearance) {
        return Map.of("id",id,"name",name,"suitableFor",use,"appearance",appearance);
    }
    /** AI can choose any catalog entry. This is only the deterministic fallback for omitted/auto themes. */
    static String resolve(String requested, String title, JsonNode input) {
        if (!requested.equals("auto")) {
            if (!NAMES.contains(requested)) throw new IllegalArgumentException("未知主题，请使用presentation_templates");
            return requested;
        }
        String subject=(title+" "+input.path("purpose").asText()+" "+input.path("audience").asText()+" "+
                input.path("slides").path(0).path("title").asText()).toLowerCase(Locale.ROOT);
        if(matches(subject,"论文","答辩","学术","实验","research"))return "academic";
        if(matches(subject,"经营","商务","财务","销售","季度","投资","business"))return "business";
        if(matches(subject,"环保","生态","农业","可持续","环境保护"))return "forest";
        if(matches(subject,"旅行","旅游","海洋","风景","地理"))return "ocean";
        if(matches(subject,"健康","生活","教育","科普","社区","医疗"))return "warm";
        if(matches(subject,"文化","人物","历史","艺术","品牌故事"))return "editorial";
        if(matches(subject,"创意","活动","营销","creative"))return "vivid";
        if(matches(subject,"科技","ai","agent","软件","产品","技术"))return "tech";
        return "ink";
    }
    private static boolean matches(String subject,String... words) {
        return java.util.Arrays.stream(words).anyMatch(subject::contains);
    }
    boolean modern() { return this.equals(of("tech"))||this.equals(of("business"))||this.equals(of("vivid")); }
    boolean lightCover() { return this.equals(of("academic"))||this.equals(of("editorial"))||this.equals(of("warm"))||this.equals(of("ink")); }
    static PresentationTheme of(String name) {
        return switch(name) {
            case "tech" -> new PresentationTheme(c("2563EB"),c("101828"),c("667085"),c("F7F9FC"),c("0B1224"),c("14B8A6"));
            case "business" -> new PresentationTheme(c("244D70"),c("152D43"),c("5D6E7C"),c("F5F8FB"),c("142B40"),c("A56821"));
            case "academic" -> new PresentationTheme(c("4338A0"),c("20213D"),c("686B80"),c("FAFAFE"),c("252345"),c("167D8D"));
            case "editorial" -> new PresentationTheme(c("AA342C"),c("332D28"),c("786B61"),c("FBF7EF"),c("392A25"),c("8C7041"));
            case "warm" -> new PresentationTheme(c("A54E32"),c("3B302B"),c("786B62"),c("FFF9F2"),c("53392D"),c("517A57"));
            case "vivid" -> new PresentationTheme(c("7C3AED"),c("29203F"),c("71627F"),c("FCF9FF"),c("281245"),c("C65B16"));
            case "ocean" -> new PresentationTheme(c("224E78"),c("24382F"),c("65746B"),c("F7F8F5"),c("16344F"),c("3F91B4"));
            case "ink" -> new PresentationTheme(c("333B48"),c("24382F"),c("65746B"),c("F7F8F5"),c("20252E"),c("667085"));
            case "forest" -> new PresentationTheme(c("27533D"),c("24382F"),c("65746B"),c("F7F8F5"),c("183324"),c("4B8F6B"));
            default -> throw new IllegalArgumentException("未知主题，请使用presentation_templates");
        };
    }
    private static Color c(String hex){return Color.decode("#"+hex);}
}
