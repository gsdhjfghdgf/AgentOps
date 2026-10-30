package com.agentops.tool;

import com.agentops.TestRuns;
import com.agentops.model.ModelPort;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchServiceTest {
    final PublicWebClient web=mock(PublicWebClient.class);
    final WebSearchService service=new WebSearchService(web,Clock.fixed(TestRuns.NOW,ZoneOffset.UTC));
    final ModelPort.ExecutionControl control=new ModelPort.ExecutionControl(){public boolean cancellationRequested(){return false;}public Instant deadlineAt(){return Instant.MAX;}};
    @AfterEach void close(){service.close();}
    void reply(String prefix,String html)throws Exception{when(web.get(startsWith(prefix),anyInt())).thenReturn(new PublicWebClient.Page(prefix,html,"text/html"));}
    @Test void filtersSurnameNoiseAndMergesActualSourcesAcrossProviders()throws Exception{
        reply("https://www.bing.com/","<ol id=b_results><li class=b_algo><h2><a href='https://example.com/zhang'>张（汉字）</a></h2><div class=b_caption><p>张姓的含义</p></div></li><li class=b_algo><h2><a href='https://example.com/notice?utm_source=bing'>张雪峰近况说明</a></h2><div class=b_caption><p>张雪峰相关信息，原文待核对</p></div></li></ol>");
        reply("https://www.so.com/","<li class=res-list><h3><a href='https://www.so.com/link?m=opaque' data-mdurl='https://example.com/notice'>张雪峰近况说明</a></h3><span class=res-list-summary>张雪峰相关信息，原文待核对</span><span class=g-c-gray>2026年10月29日</span></li>");
        reply("https://www.sogou.com/","<div class=vrwrap><h3><a href='https://example.org/second'>张雪峰最新消息</a></h3><p>主体匹配，尚未核对事实</p></div>");
        var result=service.search("张雪峰 最新消息",control);assertThat(result.get("status")).isEqualTo("ok");assertThat(result.get("filteredCount")).isEqualTo(1);
        var hits=(List<?>)result.get("results");assertThat(hits).hasSize(2);assertThat(hits.toString()).doesNotContain("/zhang");assertThat(hits.toString()).contains("bing", "360", "sogou");assertThat(result.get("evidenceNotice").toString()).contains("未核验","不构成独立证据");
    }
    @Test void blockedEmptyAndUnavailableAreNotEvidenceThatAnEventDidNotOccur()throws Exception{
        reply("https://www.bing.com/","<title>Access denied</title>");reply("https://www.so.com/","<p>没有内容</p>");when(web.get(startsWith("https://www.sogou.com/"),anyInt())).thenThrow(new IOException("network down"));
        var result=service.search("张雪峰 去世 传闻",control);assertThat(result.get("status")).isEqualTo("no_relevant_results");assertThat((List<?>)result.get("results")).isEmpty();assertThat(result.get("providers").toString()).contains("blocked","empty","unavailable");assertThat(result.get("warnings").toString()).contains("不代表事件不存在");
    }
    @Test void exactSubjectAndSiteRestrictionsRejectImpostorDomains()throws Exception{
        reply("https://www.bing.com/","<ol id=b_results><li class=b_algo><h2><a href='https://www.news.cn/notice'>张雪峰公告</a></h2></li></ol>");
        reply("https://www.so.com/","<li class=res-list><h3><a href='https://news.cn.evil.example/notice'>张雪峰公告</a></h3></li>");reply("https://www.sogou.com/","<div class=vrwrap><h3><a href='https://news.cn/other'>张某公告</a></h3></div>");
        var hits=(List<?>)service.search("\"张雪峰\" site:news.cn",control).get("results");assertThat(hits).hasSize(1);assertThat(hits.toString()).contains("https://www.news.cn/notice").doesNotContain("evil");
    }
    @Test void stoppedSearchDoesNotWaitForProviders(){
        var stopped=new ModelPort.ExecutionControl(){public boolean cancellationRequested(){return true;}public Instant deadlineAt(){return Instant.MAX;}};
        assertThatThrownBy(()->service.search("张雪峰",stopped)).isInstanceOf(InterruptedException.class);
    }
    @Test void topicalTermsMatterAndJavaDoesNotMatchJavaScript(){
        var person=new SearchQuery("张雪峰 去世 传闻");assertThat(person.score("张雪峰简介","个人资料","https://example.com/")).isNegative();assertThat(person.score("张雪峰相关传闻","未经核验","https://example.com/")).isPositive();
        var english=new SearchQuery("Java CORS configuration");assertThat(english.score("JavaScript CORS configuration","web","https://example.com/")).isNegative();
    }
}
