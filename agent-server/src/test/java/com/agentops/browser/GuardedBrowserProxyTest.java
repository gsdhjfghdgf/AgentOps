package com.agentops.browser;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class GuardedBrowserProxyTest {
    @Test void onlyExplicitProjectLoopbackOriginIsAllowed() throws Exception {
        assertThat(GuardedBrowserProxy.validate(URI.create("http://127.0.0.1:5173/path"),"http://127.0.0.1:5173").isLoopbackAddress()).isTrue();
        for(String url:new String[]{"http://127.0.0.1:8080/api","http://10.0.0.1/","http://169.254.169.254/","file:///etc/passwd"})assertThatThrownBy(()->GuardedBrowserProxy.validate(URI.create(url),"http://127.0.0.1:5173")).isInstanceOf(Exception.class);
    }
    @Test void proxyRefusesUnconfiguredLoopbackEvenForHttpsTunnel() throws Exception {
        try(var proxy=new GuardedBrowserProxy("");var client=new Socket("127.0.0.1",URI.create(proxy.address()).getPort())){
            client.setSoTimeout(3000);client.getOutputStream().write("CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(client.getInputStream().readNBytes(12),StandardCharsets.ISO_8859_1)).contains("403");
        }
    }
}
