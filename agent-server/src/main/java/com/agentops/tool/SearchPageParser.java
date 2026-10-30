package com.agentops.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

final class SearchPageParser {
    record Hit(String title,String url,String snippet,String dateLabel,String provider){}
    private static final Pattern DATE=Pattern.compile("^(?:20\\d{2}[-年/.]\\d{1,2}[-月/.]\\d{1,2}日?|\\d+\\s*(?:小时|分钟|天|hours?|days?|minutes?)\\s*(?:前|ago)).*");
    static List<Hit> parse(String provider,PublicWebClient.Page page){
        var doc=Jsoup.parse(page.content(),page.url());var hits=new ArrayList<Hit>();
        String selector=switch(provider){case "bing"->"#b_results .b_algo";case "360"->".res-list";case "sogou"->".vrwrap,.rb";default->".result";};
        for(var item:doc.select(selector)){
            if(item.selectFirst(".ec-ad,.ad-label,[data-ad]")!=null)continue;
            Element link=item.selectFirst(provider.equals("bing")?"h2 a[href]":provider.equals("duckduckgo")?".result__a[href]":"h3 a[href]");
            if(link==null)continue;String url=link.attr("data-mdurl");if(url.isBlank())url=link.attr("data-landurl");if(url.isBlank())url=link.absUrl("href");
            url=canonical(url);if(url.isEmpty())continue;
            String title=shorten(link.text(),180);if(title.isBlank())continue;
            Element snippet=item.selectFirst(".b_caption p,.b_snippet,.res-list-summary,.res-desc,.str_info,.str_txt,.text-layout .fz-mid,.text-layout .space-txt,.result__snippet");
            if(snippet==null)snippet=item.selectFirst("p");String text=snippet==null?"":shorten(snippet.text(),420);
            String date="";for(var node:item.select(".news_dt,time,.g-c-gray,.cite-date")){String label=node.hasAttr("datetime")?node.attr("datetime"):node.text();if(DATE.matcher(label).matches()||node.tagName().equals("time")){date=shorten(label,60);break;}}
            hits.add(new Hit(title,url,text,date,provider));if(hits.size()>=15)break;
        }
        return List.copyOf(hits);
    }
    static String canonical(String input){
        try{
            URI uri=URI.create(input);String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
            Map<String,String> params=new LinkedHashMap<>();if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String[] kv=pair.split("=",2);params.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length==2?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}
            if((host.equals("bing.com")||host.endsWith(".bing.com"))&&uri.getPath().equals("/ck/a")){
                String encoded=params.getOrDefault("u","");if(encoded.startsWith("a1"))return canonical(new String(Base64.getUrlDecoder().decode(encoded.substring(2)),StandardCharsets.UTF_8));
            }
            if((host.equals("duckduckgo.com")||host.endsWith(".duckduckgo.com"))&&params.containsKey("uddg"))return canonical(params.get("uddg"));
            if(!Set.of("http","https").contains(uri.getScheme())||host.isBlank()||uri.getUserInfo()!=null||input.length()>2048||input.getBytes(StandardCharsets.UTF_8).length>4096||!Set.of(-1,80,443).contains(uri.getPort()))return "";
            if(host.equals("localhost")||host.endsWith(".local")||host.contains(":")||host.matches("[0-9.]+"))return "";
            if(Set.of("fanyi.so.com","fanyi.baidu.com","translate.google.com").contains(host))return "";
            if((host.endsWith("bing.com")&&uri.getPath().equals("/search"))||(host.endsWith("so.com")&&uri.getPath().equals("/s"))||(host.endsWith("sogou.com")&&uri.getPath().equals("/web")))return "";
            String path=uri.getRawPath().isBlank()?"/":uri.getRawPath();var keep=new ArrayList<String>();
            if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String key=URLDecoder.decode(pair.split("=",2)[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);if(!key.startsWith("utm_")&&!Set.of("spm","fbclid","msclkid","gclid").contains(key))keep.add(pair);}
            return uri.getScheme()+"://"+host+(uri.getPort()==-1?"":":"+uri.getPort())+path+(keep.isEmpty()?"":"?"+String.join("&",keep));
        }catch(IllegalArgumentException e){return "";}
    }
    static String shorten(String text,int maximum){return text.length()<=maximum?text:text.substring(0,maximum);}
}
