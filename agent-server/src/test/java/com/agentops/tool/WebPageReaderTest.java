package com.agentops.tool;

import com.agentops.infra.Json;
import com.agentops.TestRuns;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.charset.*;
import static org.assertj.core.api.Assertions.*;

class WebPageReaderTest {
    final WebPageReader reader=new WebPageReader(null,new Json(),Clock.fixed(TestRuns.NOW,ZoneOffset.UTC));
    PublicWebClient.Page html(String body){return new PublicWebClient.Page("https://example.com/article",body,"text/html");}
    @Test void articlePreservesParagraphsBylineAndPublicationDate(){
        var result=reader.extract(html("<title>原始公告</title><meta property='article:published_time' content='2026-10-29T10:00:00+08:00'><meta name=author content='公告机构'><nav>导航噪声</nav><article><header><h1>公告标题</h1></header><p>这里是可核对的原文段落，内容需要由读者结合来源和时间判断，不是自动确认结论。</p><p>第二段包含进一步的说明与证据，保留段落结构方便引用。</p></article><script>PRIVATE_SCRIPT</script>"));
        assertThat(result).containsEntry("status","ok").containsEntry("author","公告机构").containsEntry("publishedAt","2026-10-29T10:00:00+08:00");assertThat(result.get("content").toString()).contains("公告标题\n","第二段").doesNotContain("导航噪声","PRIVATE_SCRIPT");
    }
    @Test void structuredArticleIsAvailableEvenWhenVisibleBodyIsEmpty(){
        var result=reader.extract(html("<title>动态报道</title><div id=app></div><script type='application/ld+json'>{\"@graph\":[{\"@type\":\"NewsArticle\",\"articleBody\":\"这是页面实际提供的结构化正文，包含足够长的完整说明以供核对。检索工具只提取原文，不推断尚未验证的事实。\",\"datePublished\":\"2026-10-29\",\"author\":{\"name\":\"原作者\"}}]}</script>"));
        assertThat(result).containsEntry("status","ok").containsEntry("publishedAt","2026-10-29").containsEntry("author","原作者");assertThat(result.get("content").toString()).contains("结构化正文");
    }
    @Test void titleOnlyAndNavigationOnlyAreNotSuccessfulReadings(){
        var empty=reader.extract(html("<title>百度百科</title><div id=app></div>"));assertThat(empty).containsEntry("status","empty").containsEntry("content","");assertThat(empty.get("message").toString()).contains("browser_open");
        var thin=reader.extract(html("<div>"+"<a href='/x'>只有导航没有正文</a>".repeat(30)+"</div>"));assertThat(thin.get("status")).isNotEqualTo("ok");
    }
    @Test void captchaIsReportedInsteadOfPassingItsTitleAsEvidence(){
        var result=reader.extract(html("<title>百度安全验证</title><p>请输入验证码后继续</p>"));assertThat(result).containsEntry("status","blocked").containsEntry("content","");assertThat(result.get("message").toString()).contains("不要绕过验证码");
    }
    @Test void declaredAndHtmlMetaChineseEncodingsDecodeCorrectly(){
        String body="<meta charset=gb2312><p>张雪峰中文资料</p>";byte[] bytes=body.getBytes(Charset.forName("GB18030"));assertThat(PublicWebClient.decode(bytes,"text/html; charset=gb2312")).contains("张雪峰中文资料");assertThat(PublicWebClient.decode(bytes,"text/html")).contains("张雪峰中文资料");
    }
    @Test void shortPlainTextJsonStillIsARealResponse(){
        assertThat(reader.extract(new PublicWebClient.Page("https://example.com/api","{\"ok\":1}","application/json"))).containsEntry("status","ok").containsEntry("content","{\"ok\":1}");
    }
    @Test void missingPageReportsFetchFailureWithoutInventingEvidence()throws Exception{
        var web=org.mockito.Mockito.mock(PublicWebClient.class);org.mockito.Mockito.when(web.get(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenThrow(new java.io.IOException("网页返回 HTTP 404"));
        var value=new WebPageReader(web,new Json(),Clock.fixed(TestRuns.NOW,ZoneOffset.UTC)).read("https://example.com/old");assertThat(value).containsEntry("status","unavailable").containsEntry("content","");assertThat(value.get("message").toString()).contains("404","其他候选来源","不能把失败当成事件不存在");
    }
    @Test void markdownArticleWinsOverFooterPromotionalArticle(){
        var result=reader.extract(html("<title>CORS guide</title><div id=main><div class=markdown><h1>CORS configuration</h1><p>Cross-origin resource sharing allows specific origins to access server resources. This is the actual article, not a promotional footer.</p><p>Configure allowed origins carefully and consult the original documentation for examples.</p></div></div><article><h2>Get support</h2><p>Buy an enterprise subscription.</p></article>"));
        assertThat(result).containsEntry("status","ok");assertThat(result.get("content").toString()).contains("Cross-origin resource sharing","actual article").doesNotContain("Buy an enterprise subscription");
    }
}
