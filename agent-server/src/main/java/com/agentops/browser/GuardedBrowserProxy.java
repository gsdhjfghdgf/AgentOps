package com.agentops.browser;
import com.agentops.security.PublicNetworkPolicy;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Every browser connection, including redirected requests and WebSockets, crosses this proxy. */
public final class GuardedBrowserProxy implements AutoCloseable {
    private final ServerSocket server;private final String localOrigin;private final URI upstreamProxy;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"browser-proxy");t.setDaemon(true);return t;});
    private volatile boolean closed;
    public GuardedBrowserProxy(String localOrigin) throws IOException {
        this(localOrigin,null);
    }
    public GuardedBrowserProxy(String localOrigin,URI upstreamProxy) throws IOException {
        this.localOrigin=localOrigin==null?"":localOrigin;this.upstreamProxy=upstreamProxy;
        server=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));
        workers.submit(()->{while(!closed)try{Socket client=server.accept();if(sockets.size()>96){client.close();continue;}sockets.add(client);workers.submit(()->handle(client));}catch(IOException e){if(closed)break;}});
    }
    public String address(){return "http://127.0.0.1:"+server.getLocalPort();}
    public static InetAddress validate(URI uri,String localOrigin) throws Exception {
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getScheme()==null||!Set.of("http","https").contains(uri.getScheme()))throw new IOException("URL无效");
        int port=uri.getPort()==-1?(uri.getScheme().equals("https")?443:80):uri.getPort();
        String origin=uri.getScheme()+"://"+uri.getHost()+":"+port;
        if(!localOrigin.isBlank()&&origin.equals(localOrigin)&&Set.of("localhost","127.0.0.1").contains(uri.getHost()))return InetAddress.getByName("127.0.0.1");
        PublicNetworkPolicy.uri(uri.toString());
        // Resolve once, check every address, then connect to that exact checked IP.
        return PublicNetworkPolicy.resolve(uri);
    }
    private void handle(Socket client){Socket upstream=null;
        try{
            client.setSoTimeout(60000);var in=client.getInputStream();var header=new ByteArrayOutputStream();int value,state=0;
            while((value=in.read())!=-1){header.write(value);if(header.size()>32768)throw new IOException("请求头过大");state=switch(state){case 0->value==13?1:0;case 1->value==10?2:0;case 2->value==13?3:0;default->value==10?4:0;};if(state==4)break;}
            String[] lines=header.toString(StandardCharsets.ISO_8859_1).split("\r\n");if(lines.length==0)return;String[] start=lines[0].split(" ");if(start.length!=3)throw new IOException("代理请求无效");
            boolean tunnel=start[0].equals("CONNECT");URI uri=URI.create(tunnel?"https://"+start[1]:start[1]);InetAddress target=validate(uri,localOrigin);int port=uri.getPort()==-1?(uri.getScheme().equals("https")?443:80):uri.getPort();
            upstream=new Socket();
            upstream.connect(upstreamProxy==null?new InetSocketAddress(target,port):new InetSocketAddress(upstreamProxy.getHost(),upstreamProxy.getPort()<0?80:upstreamProxy.getPort()),8000);
            upstream.setSoTimeout(15000);sockets.add(upstream);
            if(tunnel&&upstreamProxy!=null){
                String authority=authority(target,port);
                upstream.getOutputStream().write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));upstream.getOutputStream().flush();
                String reply=readHeader(upstream.getInputStream());if(!reply.matches("(?s)^HTTP/1\\.[01] 200(?: |\\r).*"))throw new IOException("上游代理拒绝受控连接");
            }
            if(tunnel){client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));client.getOutputStream().flush();}
            else {
                String path=uri.getRawPath();if(path==null||path.isBlank())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                String destination=upstreamProxy==null?path:uri.getScheme()+"://"+authority(target,port)+path;
                var rewritten=new StringBuilder(start[0]+" "+destination+" "+start[2]+"\r\nHost: "+uri.getRawAuthority()+"\r\n");
                for(int i=1;i<lines.length;i++)if(!lines[i].toLowerCase(Locale.ROOT).startsWith("host:")&&!lines[i].toLowerCase(Locale.ROOT).startsWith("proxy-")&&!lines[i].toLowerCase(Locale.ROOT).startsWith("connection:"))rewritten.append(lines[i]).append("\r\n");rewritten.append(Arrays.stream(lines).anyMatch(l->l.toLowerCase(Locale.ROOT).startsWith("upgrade:"))?"Connection: Upgrade\r\n\r\n":"Connection: close\r\n\r\n");upstream.getOutputStream().write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));upstream.getOutputStream().flush();
            }
            Socket remote=upstream;workers.submit(()->pipe(client,remote));pipe(remote,client);
        }catch(Exception e){try{client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));}catch(IOException ignored){}}
        finally{close(client);if(upstream!=null)close(upstream);}
    }
    private static String authority(InetAddress address,int port){String host=address.getHostAddress();return (address instanceof Inet6Address?"["+host+"]":host)+":"+port;}
    private static String readHeader(InputStream input)throws IOException{
        var bytes=new ByteArrayOutputStream();int value,state=0;
        while((value=input.read())!=-1){bytes.write(value);if(bytes.size()>32768)throw new IOException("代理响应头过大");state=switch(state){case 0->value==13?1:0;case 1->value==10?2:0;case 2->value==13?3:0;default->value==10?4:0;};if(state==4)return bytes.toString(StandardCharsets.ISO_8859_1);}
        throw new IOException("代理响应不完整");
    }
    private void pipe(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());to.shutdownOutput();}catch(IOException ignored){}}
    private void close(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    @Override public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket socket:sockets)close(socket);workers.shutdownNow();}
}
