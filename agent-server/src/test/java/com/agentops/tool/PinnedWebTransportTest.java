package com.agentops.tool;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PinnedWebTransportTest {
    @Test void proxyReceivesACheckedIpAndRedirectToMetadataIsRejected()throws Exception{
        try(var server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));var web=new PublicWebClient("http://127.0.0.1:"+server.getLocalPort())){
            var pool=Executors.newSingleThreadExecutor();
            try{
                var observed=pool.submit(()->{try(var socket=server.accept()){socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));String first=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254/latest/meta-data/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));return first;}});
                assertThatThrownBy(()->web.get("http://8.8.8.8/document",2000)).isInstanceOf(IllegalArgumentException.class);
                assertThat(observed.get(5,TimeUnit.SECONDS)).startsWith("GET http://8.8.8.8:80/document ");
            }finally{pool.shutdownNow();}
        }
    }
    @Test void aPermittedResponseStillArrivesThroughTheGuard()throws Exception{
        try(var server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));var web=new PublicWebClient("http://127.0.0.1:"+server.getLocalPort())){
            var pool=Executors.newSingleThreadExecutor();
            try{
                var observed=pool.submit(()->{try(var socket=server.accept()){socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));String first=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));return first;}});
                assertThat(web.get("http://1.1.1.1/document",2000).content()).isEqualTo("ok");assertThat(observed.get(5,TimeUnit.SECONDS)).contains("http://1.1.1.1:80/document");
            }finally{pool.shutdownNow();}
        }
    }
}
