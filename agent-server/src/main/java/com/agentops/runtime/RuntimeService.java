package com.agentops.runtime;

import com.agentops.infra.Json;
import com.agentops.tool.PublicWebClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

@Service
public class RuntimeService {
    private final Path directory;private final PublicWebClient web;private final Json json;
    public RuntimeService(@Value("${agent.runtime-dir:./data/runtimes}") String directory,PublicWebClient web,Json json){this.directory=Path.of(directory).toAbsolutePath().normalize();this.web=web;this.json=json;}
    public Optional<Path> managedNode(){try{Path active=directory.resolve("node-active.txt");if(!Files.isRegularFile(active))return Optional.empty();String name=Files.readString(active).strip();if(!name.matches("node-v(22|24)\\.\\d+\\.\\d+-win-x64"))return Optional.empty();Path node=directory.resolve(name).resolve("node.exe");return Files.isRegularFile(node)?Optional.of(node):Optional.empty();}catch(Exception e){return Optional.empty();}}
    public void environment(ProcessBuilder builder){managedNode().ifPresent(node->{var env=builder.environment();String key=env.containsKey("Path")?"Path":"PATH";env.put(key,node.getParent()+java.io.File.pathSeparator+env.getOrDefault(key,""));});builder.environment().put("PYTHONIOENCODING","utf-8");}
    public Optional<Path> browser(){
        var paths=new ArrayList<String>();for(String name:List.of("ProgramFiles","ProgramFiles(x86)","LOCALAPPDATA")){String base=System.getenv(name);if(base!=null){paths.add(base+"/Microsoft/Edge/Application/msedge.exe");paths.add(base+"/Google/Chrome/Application/chrome.exe");}}
        for(String path:paths)if(Files.isRegularFile(Path.of(path)))return Optional.of(Path.of(path));return Optional.empty();
    }
    public Map<String,Object> probe(String kind){
        List<String> command=switch(kind){case "node"->List.of(managedNode().map(Path::toString).orElse("node"),"--version");case "python"->List.of("python","--version");case "git"->List.of("git","--version");case "browser"->browser().isPresent()?List.of():List.of();default->throw new IllegalArgumentException("运行时无效");};
        if(kind.equals("browser"))return Map.of("installed",browser().isPresent(),"version",browser().map(Path::toString).orElse(""),"managed",false);
        var executor=Executors.newSingleThreadExecutor();Process process=null;
        try{var builder=new ProcessBuilder(command).redirectErrorStream(true);environment(builder);process=builder.start();var running=process;var output=executor.submit(()->new String(running.getInputStream().readNBytes(4096),StandardCharsets.UTF_8));
            if(!process.waitFor(6,TimeUnit.SECONDS)){process.destroyForcibly();return Map.of("installed",false,"version","检查超时","managed",false);}String text=output.get(1,TimeUnit.SECONDS).strip();return Map.of("installed",process.exitValue()==0,"version",text,"managed",kind.equals("node")&&managedNode().isPresent());
        }catch(Exception e){return Map.of("installed",false,"version","未找到可用运行时","managed",false);}finally{if(process!=null&&process.isAlive())process.destroyForcibly();executor.shutdownNow();}
    }
    public synchronized Map<String,Object> installNode(int major) throws Exception {
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")||!Set.of(22,24).contains(major))throw new IllegalArgumentException("当前支持安装Windows x64的Node 22或24私有运行时");
        String sums=web.get("https://nodejs.org/dist/latest-v"+major+".x/SHASUMS256.txt",100000).content();
        var matcher=java.util.regex.Pattern.compile("(?m)^([a-fA-F0-9]{64})\\s+(node-v"+major+"\\.\\d+\\.\\d+-win-x64)\\.zip\\s*$").matcher(sums);
        if(!matcher.find())throw new IllegalArgumentException("官方校验清单中未找到兼容版本");String sha=matcher.group(1),name=matcher.group(2),version=name.substring(5,name.length()-8);
        Files.createDirectories(directory);Path target=directory.resolve(name),node=target.resolve("node.exe");
        if(!Files.isRegularFile(node)){
            Path zip=Files.createTempFile(directory,"node-",".zip"),staging=Files.createTempDirectory(directory,"extract-");
            try{
                var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(HttpRequest.newBuilder(URI.create("https://nodejs.org/dist/"+version+"/"+name+".zip")).timeout(Duration.ofMinutes(3)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                if(response.statusCode()!=200){response.body().close();throw new IllegalArgumentException("Node下载失败 HTTP "+response.statusCode());}
                var digest=java.security.MessageDigest.getInstance("SHA-256");long total=0;
                try(var in=response.body();var out=Files.newOutputStream(zip)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if((total+=n)>80000000)throw new IllegalArgumentException("Node安装包超过大小限制");digest.update(buffer,0,n);out.write(buffer,0,n);}}
                if(!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha))throw new IllegalArgumentException("Node官方SHA256校验失败，未安装");
                long extracted=0;try(var input=new ZipInputStream(Files.newInputStream(zip))){ZipEntry entry;while((entry=input.getNextEntry())!=null){String path=entry.getName();if(!path.startsWith(name+"/")||path.contains("..")||path.contains("\\")||path.contains(":"))throw new IllegalArgumentException("安装包路径无效");Path output=staging.resolve(path).normalize();if(!output.startsWith(staging))throw new IllegalArgumentException("安装包路径越界");if(entry.isDirectory()){Files.createDirectories(output);continue;}Files.createDirectories(output.getParent());try(var out=Files.newOutputStream(output)){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1){if((extracted+=n)>300000000)throw new IllegalArgumentException("解压内容超过限制");out.write(buffer,0,n);}}}}
                if(!Files.isRegularFile(staging.resolve(name).resolve("node.exe")))throw new IllegalArgumentException("安装包缺少Node可执行文件");Files.move(staging.resolve(name),target);
            } finally {Files.deleteIfExists(zip);if(staging.normalize().startsWith(directory))try(var walk=Files.walk(staging)){for(Path item:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);}}
        }
        Files.writeString(directory.resolve("node-active.txt"),name,StandardCharsets.UTF_8);return Map.of("version",version,"path",node.toString(),"sha256",sha,"managed",true);
    }
}
