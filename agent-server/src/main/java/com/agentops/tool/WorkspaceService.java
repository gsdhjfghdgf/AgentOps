package com.agentops.tool;

import com.agentops.domain.Primitives.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 文件工具仅访问服务端专属工作区；命令工具不提供操作系统沙箱。 */
@Service
public class WorkspaceService {
    private final Path base;
    public WorkspaceService(@Value("${agent.workspace-dir:./data/workspaces}") String directory) {
        base=Path.of(directory).toAbsolutePath().normalize();
    }
    public Path root(Principal owner) throws IOException {
        Path root=base.resolve(owner.tenantId().toString()).resolve(owner.userId().toString());
        Path cursor=root.getRoot();
        for(Path component:root){cursor=cursor.resolve(component);if(Files.isSymbolicLink(cursor))throw new IllegalArgumentException("工作区不能使用符号链接");}
        Files.createDirectories(root);
        return root.toRealPath();
    }
    public Path resolve(Principal owner,String name) throws IOException {
        if(name==null || name.isBlank() || name.length()>240 || name.contains(":") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl) || name.startsWith("/"))throw new IllegalArgumentException("请提供工作区内的相对路径");
        Path relative=Path.of(name);
        for(Path part:relative)if(java.util.Set.of("..",".").contains(part.toString()))throw new IllegalArgumentException("路径不能包含 . 或 ..");
        Path root=root(owner),target=root.resolve(relative).normalize();
        if(!target.startsWith(root) || target.equals(root))throw new IllegalArgumentException("路径超出工作区");
        Path cursor=root;
        for(Path part:root.relativize(target)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IllegalArgumentException("不允许访问符号链接");}
        return target;
    }
    public record FileItem(String path,long bytes,String sha256) {}
    public List<FileItem> list(Principal owner) throws IOException {
        var result=new ArrayList<FileItem>();Path root=root(owner);
        try(var walk=Files.walk(root,8)) {
            for(Path path:walk.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).limit(201).toList()) {
                if(Files.size(path)>2000000)continue;
                resolve(owner,root.relativize(path).toString().replace('\\','/'));
                result.add(new FileItem(root.relativize(path).toString().replace('\\','/'),Files.size(path),sha(Files.readAllBytes(path))));
            }
        }
        return result;
    }
    public Map<String,Object> read(Principal owner,String name) throws IOException {
        Path path=resolve(owner,name);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("文件不存在");
        if(Files.size(path)>2000000)throw new IllegalArgumentException("文件超过2MB，请缩小文件");
        byte[] bytes=Files.readAllBytes(path);
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return Map.of("path",name,"content",text,"sha256",sha(bytes),"bytes",bytes.length);
    }
    public synchronized Map<String,Object> write(Principal owner,String name,String content,String expectedHash) throws IOException {
        if(content==null || content.getBytes(StandardCharsets.UTF_8).length>2000000)throw new IllegalArgumentException("文本不能超过2MB");
        Path path=resolve(owner,name);
        String extension=name==null?"":name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Set.of("txt","md","csv","json","java","py","js","ts","sql","html","css","yaml","yml","sh","ps1","xml","properties","c","cpp","h",
                "mjs","cjs","svg","go","rs","kt","kts","gradle","toml","vue","tsx","jsx","ini","config","r","rb","php","cs","swift","lock","bat","cmd").contains(extension)
                && !Set.of("LICENSE","NOTICE","Makefile","Dockerfile",".gitignore",".gitattributes",".editorconfig",".dockerignore").contains(Path.of(name).getFileName().toString()))
            throw new IllegalArgumentException("只支持文本、代码与脚本文件");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("目标不是普通文件");
            if(Files.size(path)>2000000)throw new IllegalArgumentException("已有文件超过2MB，不能覆盖");
            if(expectedHash==null || !expectedHash.equals(sha(Files.readAllBytes(path))))throw new IllegalArgumentException("文件已存在或已变化，请先读取并提供当前 SHA256");
        } else if(expectedHash!=null && !expectedHash.isBlank())throw new IllegalArgumentException("文件已被移除，请重新确认");
        if(list(owner).size()>=200 && !Files.exists(path))throw new IllegalArgumentException("工作区最多200个文件");
        Files.createDirectories(path.getParent());resolve(owner,name);
        Path temp=Files.createTempFile(path.getParent(),".agentops-",".tmp");
        try { Files.writeString(temp,content,StandardCharsets.UTF_8);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        finally {Files.deleteIfExists(temp);}
        return read(owner,name);
    }
    public List<Map<String,Object>> search(Principal owner,String query) throws IOException {
        if(query==null || query.isBlank() || query.length()>200)throw new IllegalArgumentException("搜索词须为1～200字");
        var hits=new ArrayList<Map<String,Object>>();int visited=0;
        for(var file:list(owner)) {
            if((visited+=file.bytes())>2000000)break;
            String[] lines=read(owner,file.path()).get("content").toString().split("\r?\n");
            for(int i=0;i<lines.length;i++)if(lines[i].toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                hits.add(Map.of("path",file.path(),"line",i+1,"text",lines[i].substring(0,Math.min(500,lines[i].length()))));
                if(hits.size()>=30)return hits;
            }
        }
        return hits;
    }
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
