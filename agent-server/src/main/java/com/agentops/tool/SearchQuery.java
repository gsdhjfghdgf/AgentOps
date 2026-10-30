package com.agentops.tool;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative lexical checks, not a claim that search snippets are factually true. */
final class SearchQuery {
    private static final Set<String> STOP=Set.of("的","了","吗","是什么","怎么","如何","最新","消息","新闻","官方","查询","搜索","the","a","an","and","or","what","how","latest","news","official","documentation");
    private static final Pattern TERMS=Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9][a-zA-Z0-9.+#_-]{1,}");
    private final List<String> terms;
    private final List<String> exact;
    private final List<String> domains;
    private final String anchor;

    SearchQuery(String query){
        var quoted=new ArrayList<String>();var matcher=Pattern.compile("[\"“]([^\"”]+)[\"”]").matcher(query);
        while(matcher.find())quoted.add(fold(matcher.group(1)));
        exact=List.copyOf(quoted);var sites=new ArrayList<String>();matcher=Pattern.compile("(?i)(?<!-)\\bsite:([a-z0-9.-]+)").matcher(query);
        while(matcher.find())sites.add(matcher.group(1).toLowerCase(Locale.ROOT));domains=List.copyOf(sites);
        String cleaned=query.replaceAll("(?i)-?site:[a-z0-9.-]+","");var tokens=new LinkedHashSet<String>();matcher=TERMS.matcher(cleaned);
        while(matcher.find()){
            String word=matcher.group().toLowerCase(Locale.ROOT);
            if(word.matches("[\\p{IsHan}]+")&&!Set.of("去世","传闻","近况").contains(word))word=word.replaceAll("^(请问|帮我查一下|搜索一下|查一下|如何|怎么)","").replaceAll("(最新消息|最新信息|官方消息|去世了吗|去世传闻|去世|传闻|近况|是什么|怎么样|了吗|吗)$","");
            if(!word.isBlank()&&!STOP.contains(word))tokens.add(word);
        }
        terms=List.copyOf(tokens);anchor=exact.isEmpty()?(terms.isEmpty()?"":terms.get(0)):exact.get(0);
    }
    List<String> terms(){return terms;}
    List<String> matched(String title,String snippet){String all=fold(title+" "+snippet);return terms.stream().filter(t->contains(all,t)).toList();}
    double score(String title,String snippet,String url){
        String all=fold(title+" "+snippet);if(exact.stream().anyMatch(term->!contains(all,term)))return -1;
        if(!anchor.isEmpty()&&!contains(all,anchor))return -1;
        if(!domains.isEmpty()){String host=URI.create(url).getHost();if(host==null||domains.stream().noneMatch(d->host.equalsIgnoreCase(d)||host.toLowerCase(Locale.ROOT).endsWith("."+d)))return -1;}
        var matches=matched(title,snippet);if(!terms.isEmpty()&&matches.size()<Math.min(2,terms.size())&&terms.size()>2)return -1;
        double coverage=terms.isEmpty()?1:(double)matches.size()/terms.size();
        return coverage*10+matches.stream().filter(t->contains(fold(title),t)).count()*2;
    }
    private static boolean contains(String text,String term){
        if(term.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))return text.replaceAll("\\s+","").contains(term.replaceAll("\\s+",""));
        return Pattern.compile("(?<![a-z0-9])"+Pattern.quote(term)+"(?![a-z0-9])").matcher(text).find();
    }
    private static String fold(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
}
