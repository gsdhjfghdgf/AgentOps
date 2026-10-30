package com.agentops.tool;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class SearchPageParserTest {
    @Test void bingRedirectsBecomeOriginalLinksAndDateLabelsRemainSourceLabels(){
        String url="https://example.com/article?id=42&utm_source=bing#part",wrapped="https://www.bing.com/ck/a?u=a1"+Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
        var hits=SearchPageParser.parse("bing",new PublicWebClient.Page("https://www.bing.com/search","<ol id=b_results><li class=b_algo><h2><a href='"+wrapped+"'>完整名称 资料</a></h2><div class=b_caption><span class=news_dt>2026年10月29日</span><p>实际摘要</p></div></li></ol>","text/html"));
        assertThat(hits).hasSize(1);assertThat(hits.get(0).url()).isEqualTo("https://example.com/article?id=42");assertThat(hits.get(0).snippet()).isEqualTo("实际摘要");assertThat(hits.get(0).dateLabel()).isEqualTo("2026年10月29日");
    }
    @Test void duckRedirectsDecodeWithoutExecutingOrFollowingThem(){
        assertThat(SearchPageParser.canonical("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Foriginal%3Fid%3D1&rut=tracking")).isEqualTo("https://example.com/original?id=1");
    }
    @Test void executableCredentialedAndLocalResultLinksAreDiscarded(){
        for(String url:new String[]{"javascript:alert(1)","file:///secret","https://user:secret@example.com/","http://127.0.0.1/","http://localhost/","https://example.com:8080/","https://fanyi.so.com/?src=onebox","https://www.bing.com/search?q=echo"})assertThat(SearchPageParser.canonical(url)).isEmpty();
    }
}
