package com.agentops.tool;

import org.junit.jupiter.api.Test;
import java.net.*;
import static org.assertj.core.api.Assertions.*;

class PublicWebProxyTest {
    @Test void explicitProxyRoutesPublicRequestsWithoutChangingTargetValidation()throws Exception{
        var selector=PublicWebClient.proxySelector("http://127.0.0.1:33210");
        var proxy=selector.select(URI.create("https://example.org/image.png")).get(0);
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);assertThat(((InetSocketAddress)proxy.address()).getPort()).isEqualTo(33210);
        assertThatThrownBy(()->PublicWebClient.validate("http://127.0.0.1/private")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PublicWebClient.proxySelector("")).isNull();
    }
    @Test void proxyErrorsDoNotExposeCredentialsOrAcceptUnsupportedSchemes(){
        for(String input:java.util.List.of("http://user:PRIVATE_CANARY@proxy.example:80","socks://localhost:1080","http://localhost:80?key=PRIVATE_CANARY"))
            assertThatThrownBy(()->PublicWebClient.proxySelector(input)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE_CANARY");
    }
}
