package com.agentops.project;

import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import com.agentops.tool.WorkspaceService;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ProjectService {
    private static final Set<String> SKIP=Set.of(".git","node_modules",".venv","venv","target","dist","build","release",".idea",".runtime","__pycache__");
    private final ProjectRepo repo;private final RunRepo runs;private final Json json;
    public ProjectService(ProjectRepo repo,RunRepo runs,Json json){this.repo=repo;this.runs=runs;this.json=json;}
    public ProjectRepo repository(){return repo;}
    public ProjectRepo.Project attach(Principal p,String name,String directory) throws Exception {
        if(directory==null||directory.length()>1000)throw new IllegalArgumentException("请选择本地项目目录");
        Path root=Path.of(directory).toAbsolutePath().normalize().toRealPath();
        if(root.getParent()==null||!Files.isDirectory(root))throw new IllegalArgumentException("请选择项目文件夹，不能选择整个磁盘");
        String label=name==null||name.isBlank()?root.getFileName().toString():name;
        if(label.length()>100)throw new IllegalArgumentException("项目名过长");
        UUID id=repo.attach(p,label,root.toString());repo.select(p,id);return repo.get(p,id);
    }
    public Optional<ProjectRepo.Project> forRun(Principal p,UUID runId){
        var row=runs.findByIdOwner(p,runId).orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("任务不存在"));
        String id=json.tree(row.configSnapshotJson()).path("projectId").asText("");
        return id.isBlank()?Optional.empty():Optional.of(repo.get(p,UUID.fromString(id)));
    }
    public String previewOriginForRun(Principal p,UUID runId){var row=runs.findByIdOwner(p,runId).orElseThrow();return json.tree(row.configSnapshotJson()).path("projectPreviewOrigin").asText("");}
    public ProjectRepo.Project required(Principal p,UUID run){return forRun(p,run).orElseThrow(()->new IllegalArgumentException("请先在输入框旁选择项目，再发送新任务"));}
    public Path root(ProjectRepo.Project project) throws Exception {
        Path root=Path.of(project.root()).toAbsolutePath().normalize();
        if(!Files.isDirectory(root)||!root.toRealPath().equals(root))throw new IllegalArgumentException("项目目录已变化，请重新选择");return root;
    }
    public Path resolve(ProjectRepo.Project project,String relative) throws Exception {
        if(relative==null||relative.isBlank()||relative.length()>500||relative.contains(":")||relative.contains("\\")||relative.startsWith("/")||relative.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("请输入项目内的相对路径");
        Path path=Path.of(relative);for(Path part:path)if(Set.of("..",".",".git").contains(part.toString()))throw new IllegalArgumentException("路径不能越界或访问Git内部目录");
        Path base=root(project),target=base.resolve(path).normalize(),cursor=base;
        if(!target.startsWith(base)||target.equals(base))throw new IllegalArgumentException("路径超出项目目录");
        for(Path part:base.relativize(target)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor)||Files.exists(cursor)&&!cursor.toRealPath().startsWith(base))throw new IllegalArgumentException("项目工具不跟随符号链接或外部目录链接");}
        return target;
    }
    private String text(Path file) throws Exception {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>256000)throw new IllegalArgumentException("仅支持256KB以内的普通文本文件");return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();}
    public Map<String,Object> read(Principal p,UUID run,String path,int offset) throws Exception {
        var project=required(p,run);Path file=resolve(project,path);String content=text(file);int start=Math.min(Math.max(offset,0),content.length()),end=Math.min(content.length(),start+6000);
        int fromLine=1+(int)content.substring(0,start).chars().filter(c->c=='\n').count();
        int toLine=fromLine+(int)content.substring(start,end).chars().filter(c->c=='\n').count();
        return Map.of("path",path,"content",content.substring(start,end),"offset",start,"totalChars",content.length(),"nextOffset",end,"eof",end>=content.length(),"fromLine",fromLine,"toLine",toLine,"sha256",WorkspaceService.sha(Files.readAllBytes(file)));
    }
    public List<Map<String,Object>> files(Principal p,UUID run,String directory) throws Exception {
        var project=required(p,run);Path base=root(project),start=directory==null||directory.isBlank()?base:resolve(project,directory);var result=new ArrayList<Map<String,Object>>();
        if(!Files.isDirectory(start))throw new IllegalArgumentException("目录不存在");
        try(var list=Files.list(start)){for(Path file:list.sorted().limit(1000).toList()){
            if(SKIP.contains(file.getFileName().toString())||Files.isSymbolicLink(file))continue;
            String path=base.relativize(file).toString().replace('\\','/');resolve(project,path);
            result.add(Map.of("path",path,"directory",Files.isDirectory(file),"bytes",Files.isRegularFile(file)?Files.size(file):0));if(result.size()>=50)break;
        }}return result;
    }
    public List<Map<String,Object>> search(Principal p,UUID run,String query) throws Exception {
        if(query==null||query.isBlank()||query.length()>200)throw new IllegalArgumentException("请输入1～200字的查找文本");var project=required(p,run);Path base=root(project);var matches=new ArrayList<Map<String,Object>>();
        Files.walkFileTree(base,EnumSet.noneOf(FileVisitOption.class),12,new SimpleFileVisitor<>() {
            int visited;
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs){return !dir.equals(base)&&(SKIP.contains(dir.getFileName().toString())||attrs.isSymbolicLink()||attrs.isOther())?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs){if(++visited>10000||matches.size()>=12)return FileVisitResult.TERMINATE;
                if(attrs.isRegularFile()&&attrs.size()<=256000)try{String relative=base.relativize(file).toString().replace('\\','/');resolve(project,relative);String[] lines=text(file).split("\\R");for(int i=0;i<lines.length;i++)if(lines[i].toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))){matches.add(Map.of("path",relative,"line",i+1,"text",lines[i].substring(0,Math.min(160,lines[i].length()))));if(matches.size()>=12)break;}}catch(Exception ignored){}return FileVisitResult.CONTINUE;}
        });return matches;
    }
    public String preview(ProjectRepo.Project project,String path,String content,String expectedSha) throws Exception {
        Path target=resolve(project,path);String before=Files.exists(target)?text(target):null;
        if(before!=null&&!Objects.equals(expectedSha,WorkspaceService.sha(Files.readAllBytes(target)))||before==null&&expectedSha!=null&&!expectedSha.isBlank())throw new IllegalArgumentException("文件已变化，请重新读取后提交修改");
        if(content==null||content.getBytes(StandardCharsets.UTF_8).length>256000)throw new IllegalArgumentException("修改内容过大");
        var oldLines=before==null?List.<String>of():before.lines().toList();var newLines=content.lines().toList();int prefix=0;
        while(prefix<oldLines.size()&&prefix<newLines.size()&&oldLines.get(prefix).equals(newLines.get(prefix)))prefix++;
        int oldEnd=oldLines.size(),newEnd=newLines.size();while(oldEnd>prefix&&newEnd>prefix&&oldLines.get(oldEnd-1).equals(newLines.get(newEnd-1))){oldEnd--;newEnd--;}
        var view=new StringBuilder("--- "+path+" (原内容)\n+++ "+path+" (修改后)\n@@ 修改行 "+(prefix+1)+" @@\n");
        for(int i=Math.max(0,prefix-3);i<prefix;i++)view.append("  ").append(oldLines.get(i)).append('\n');
        for(int i=prefix;i<oldEnd;i++)view.append("- ").append(oldLines.get(i)).append('\n');
        for(int i=prefix;i<newEnd;i++)view.append("+ ").append(newLines.get(i)).append('\n');
        for(int i=oldEnd;i<Math.min(oldLines.size(),oldEnd+3);i++)view.append("  ").append(oldLines.get(i)).append('\n');
        String diff=view.toString();
        return diff.length()>50000?diff.substring(0,50000)+"\n[预览已截断]":diff;
    }
    public synchronized Map<String,Object> write(Principal p,UUID run,String path,String content,String expected) throws Exception {
        var project=required(p,run);preview(project,path,content,expected);Path target=resolve(project,path);
        String before=Files.exists(target)?text(target):null,after=WorkspaceService.sha(content.getBytes(StandardCharsets.UTF_8));UUID id=UUID.randomUUID();
        repo.record(p,id,project.id(),run,path,before,before==null?null:WorkspaceService.sha(before.getBytes(StandardCharsets.UTF_8)),after);
        try{atomicWrite(project,path,content);repo.status(p,id,"applied");}catch(Exception e){repo.status(p,id,"needs_check");throw e;}
        return Map.of("path",path,"sha256",after,"changeId",id.toString());
    }
    public String patchedContent(ProjectRepo.Project project,String path,String find,String replacement) throws Exception {
        if(find==null||find.isEmpty())throw new IllegalArgumentException("请提供要替换的原文");String before=text(resolve(project,path));int index=before.indexOf(find);
        if(index<0||before.indexOf(find,index+find.length())>=0)throw new IllegalArgumentException("原文不存在或匹配多处，请重新读取并提供唯一片段");return before.substring(0,index)+replacement+before.substring(index+find.length());
    }
    public Map<String,Object> patch(Principal p,UUID run,String path,String find,String replacement,String expected) throws Exception{return write(p,run,path,patchedContent(required(p,run),path,find,replacement),expected);}
    private void atomicWrite(ProjectRepo.Project project,String path,String content) throws Exception {
        Path target=resolve(project,path);Files.createDirectories(target.getParent());resolve(project,path);Path temp=Files.createTempFile(target.getParent(),".agentops-",".tmp");
        try{Files.writeString(temp,content,StandardCharsets.UTF_8);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    public synchronized void undo(Principal p,UUID projectId,UUID changeId) throws Exception {
        var project=repo.get(p,projectId);var change=repo.changes(p,projectId).stream().filter(c->c.id().equals(changeId)).findFirst().orElseThrow();
        if(!Set.of("applied","needs_check").contains(change.status()))throw new IllegalArgumentException("此修改不可撤销或已撤销");Path file=resolve(project,change.path());
        if(!Files.isRegularFile(file)||!WorkspaceService.sha(Files.readAllBytes(file)).equals(change.afterSha()))throw new IllegalArgumentException("文件已有后续修改，不能覆盖，请先检查差异");
        if(change.before()==null)Files.delete(file);else atomicWrite(project,change.path(),change.before());repo.status(p,changeId,"reverted");
    }
    public Map<String,Object> git(Principal p,UUID projectId,boolean diff) throws Exception {
        var project=repo.get(p,projectId);Path base=root(project);var args=new ArrayList<>(List.of("git","-c","core.hooksPath="+(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"NUL":"/dev/null"),"-c","core.fsmonitor=false","-c","core.pager=cat","-C",base.toString()));
        var configArgs=new ArrayList<>(args);configArgs.addAll(List.of("config","--name-only","--get-regexp","^filter\\..*\\.(clean|smudge|process|required)$"));
        // Git can otherwise execute clean/process filters while merely reading a diff.
        var config=gitProcess(configArgs);for(String key:config.text().lines().toList()){
            if(!key.matches("filter\\..+\\.(clean|smudge|process|required)")||key.length()>500||key.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Git过滤器配置名称不受支持");
            args.add("-c");args.add(key+"="+(key.endsWith(".required")?"false":""));
        }
        args.addAll(diff?List.of("diff","--ignore-submodules=all","--no-ext-diff","--no-textconv","--no-color","--","."):List.of("status","--ignore-submodules=all","--porcelain=v1","--untracked-files=normal","--","."));
        var result=gitProcess(args);return Map.of("repository",result.code()==0,"text",result.text().substring(0,Math.min(result.text().length(),50000)),"warning",result.warning(),"project",project.name());
    }
    private record GitResult(int code,String text,String warning){}
    private GitResult gitProcess(List<String> args) throws Exception {
        var builder=new ProcessBuilder(args);com.agentops.security.ProcessEnvironment.sanitize(builder);builder.environment().put("GIT_OPTIONAL_LOCKS","0");var process=builder.start();var reader=Executors.newFixedThreadPool(2);
        var output=reader.submit(()->drain(process.getInputStream()));var warning=reader.submit(()->drain(process.getErrorStream()));
        try{if(!process.waitFor(12,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalArgumentException("Git读取超时");}return new GitResult(process.exitValue(),output.get(2,TimeUnit.SECONDS),warning.get(2,TimeUnit.SECONDS));}finally{process.destroy();reader.shutdownNow();}
    }
    private String drain(java.io.InputStream stream) throws Exception {try(var in=stream;var collected=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){int allowed=Math.min(n,100000-collected.size());if(allowed>0)collected.write(buffer,0,allowed);}return collected.toString(StandardCharsets.UTF_8);}}
}
