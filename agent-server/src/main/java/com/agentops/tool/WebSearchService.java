package com.agentops.tool;

import com.agentops.model.ModelPort.ExecutionControl;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class WebSearchService {
    private record Provider(String name,String endpoint){}
    private record Reply(String provider,String status,List<SearchPageParser.Hit> hits){}
    private record Ranked(SearchPageParser.Hit hit,double score){}
    private static final List<Provider> PROVIDERS=List.of(
            new Provider("bing","https://www.bing.com/search?ensearch=1&q="),
            new Provider("360","https://www.so.com/s?q="),new Provider("sogou","https://www.sogou.com/web?query="));
    private final ExecutorService workers=new ThreadPoolExecutor(3,3,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(24),r->{var t=new Thread(r,"web-search");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final PublicWebClient web;private final Clock clock;
    public WebSearchService(PublicWebClient web,Clock clock){this.web=web;this.clock=clock;}
    @PreDestroy void close(){workers.shutdownNow();}

    public Map<String,Object> search(String input,ExecutionControl control)throws InterruptedException{
        String query=input.strip();if(query.isEmpty()||query.length()>400)throw new IllegalArgumentException("搜索词须为1～400字符");
        if(control.cancellationRequested()||control.exceeded(Instant.now(clock)))throw new InterruptedException("搜索已停止");
        var completion=new ExecutorCompletionService<Reply>(workers);var futures=new HashMap<Future<Reply>,String>();var completed=new HashMap<String,Reply>();long deadline=System.nanoTime()+20_000_000_000L;
        for(var provider:PROVIDERS)try{futures.put(completion.submit(()->fetch(provider,query)),provider.name());}catch(RejectedExecutionException e){completed.put(provider.name(),new Reply(provider.name(),"busy",List.of()));}
        try{
            while(completed.size()<PROVIDERS.size()&&System.nanoTime()<deadline){
                if(control.cancellationRequested()||control.exceeded(Instant.now(clock)))throw new InterruptedException("搜索已停止");
                var future=completion.poll(Math.min(200,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))),TimeUnit.MILLISECONDS);
                if(future==null)continue;String provider=futures.get(future);
                try{completed.put(provider,future.get());}catch(ExecutionException e){completed.put(provider,new Reply(provider,"unavailable",List.of()));}
            }
        }finally{futures.keySet().forEach(f->f.cancel(true));}
        var replies=PROVIDERS.stream().map(p->completed.getOrDefault(p.name(),new Reply(p.name(),"timeout",List.of()))).toList();
        return merge(query,replies);
    }
    private Reply fetch(Provider provider,String query){try{
        var page=web.get(provider.endpoint()+URLEncoder.encode(query,StandardCharsets.UTF_8),1_000_000);
        var hits=SearchPageParser.parse(provider.name(),page);String status=hits.isEmpty()?(WebPageReader.blocked(page)?"blocked":"empty"):"ok";
        return new Reply(provider.name(),status,hits);
    }catch(InterruptedException e){Thread.currentThread().interrupt();return new Reply(provider.name(),"stopped",List.of());}
    catch(java.io.IOException|IllegalArgumentException e){return new Reply(provider.name(),"unavailable",List.of());}}

    private Map<String,Object> merge(String query,List<Reply> replies){
        var profile=new SearchQuery(query);var candidates=new ArrayList<Ranked>();int rejected=0;
        for(var reply:replies)for(var hit:reply.hits()){
            double score=profile.score(hit.title(),hit.snippet(),hit.url());if(score<0){rejected++;continue;}candidates.add(new Ranked(hit,score+sourceBias(hit.url())));
        }
        candidates.sort(Comparator.comparingDouble(Ranked::score).reversed());var urls=new LinkedHashMap<String,Map<String,Object>>();
        for(var item:candidates){var hit=item.hit();var existing=urls.get(hit.url());if(existing!=null){@SuppressWarnings("unchecked") var sources=(List<String>)existing.get("searchProviders");if(!sources.contains(hit.provider()))sources.add(hit.provider());if(existing.get("dateLabel").toString().isBlank()&&!hit.dateLabel().isBlank())existing.put("dateLabel",hit.dateLabel());continue;}
            if(urls.size()>=8)continue;var row=new LinkedHashMap<String,Object>();row.put("id","S"+(urls.size()+1));row.put("title",hit.title());row.put("url",hit.url());row.put("domain",URI.create(hit.url()).getHost());row.put("snippet",hit.snippet());row.put("dateLabel",hit.dateLabel());row.put("matchedTerms",profile.matched(hit.title(),hit.snippet()));row.put("searchProviders",new ArrayList<>(List.of(hit.provider())));urls.put(hit.url(),row);
        }
        var warnings=new ArrayList<String>();if(rejected>0)warnings.add("已过滤"+rejected+"条未匹配完整检索主体或关键词的偏题结果。");
        if(replies.stream().anyMatch(r->!r.status().equals("ok")))warnings.add("部分搜索源未返回可解析结果，检索覆盖可能不完整。");
        if(urls.isEmpty())warnings.add("本次没有找到足够相关的候选网页。这不代表事件不存在或主张已被证伪；请保留完整主体，调整关键词或指定来源。");
        var output=new LinkedHashMap<String,Object>();output.put("query",query);output.put("searchedAt",Instant.now(clock).toString());output.put("status",urls.isEmpty()?"no_relevant_results":"ok");output.put("results",List.copyOf(urls.values()));output.put("providers",replies.stream().map(r->Map.of("name",r.provider(),"status",r.status(),"candidates",r.hits().size())).toList());output.put("filteredCount",rejected);output.put("warnings",warnings);output.put("evidenceNotice","以下为未核验的搜索摘要，仅用于定位候选来源；日期标签可能不是发布时间。应使用web_read核对原文、作者及发布日期。多引擎命中或同稿转载不构成独立证据。");return output;
    }
    private static double sourceBias(String url){
        String host=URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if(host.endsWith(".gov.cn")||host.endsWith(".edu.cn"))return 2;
        for(String domain:List.of("news.cn","xinhuanet.com","cctv.com","people.com.cn"))if(host.equals(domain)||host.endsWith("."+domain))return 2;
        for(String domain:List.of("sogou.com","so.com","zhihu.com","bilibili.com","toutiao.com","baike.baidu.com"))if(host.equals(domain)||host.endsWith("."+domain))return -1;
        return 0;
    }
}
