package com.agentops.skill;

import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.tool.PublicWebClient;
import com.agentops.tool.WorkspaceService;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 从公开GitHub下载说明及有界文本附件，固定commit；不执行下载内容。 */
@Service
public class SkillDownloadService {
    private final PublicWebClient web;private final Json json;private final WorkspaceService workspace;private final SkillService skills;
    public SkillDownloadService(PublicWebClient web,Json json,WorkspaceService workspace,SkillService skills){this.web=web;this.json=json;this.workspace=workspace;this.skills=skills;}
    record Source(String owner,String repo,String ref,String directory) {}
    record Asset(String path,String sha,long size) {}
    public record ImportRequest(String url,String revision,String sha256) {}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private String encodePath(String path){return Arrays.stream(path.split("/")).map(this::encode).collect(java.util.stream.Collectors.joining("/"));}
    private Source source(String url) throws Exception {
        if(url==null || url.isBlank() || url.length()>2048)throw new IllegalArgumentException("请提供GitHub技能链接");
        var uri=URI.create(url);String host=uri.getHost();
        if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !Set.of("github.com","raw.githubusercontent.com").contains(Objects.toString(host,"")))throw new IllegalArgumentException("请输入公开GitHub仓库、技能目录或SKILL.md链接");
        var parts=Arrays.stream(uri.getPath().split("/")).filter(s->!s.isBlank()).toList();
        if(parts.size()<2)throw new IllegalArgumentException("GitHub链接不完整");
        String ref,path;
        if("raw.githubusercontent.com".equals(host)) {
            if(parts.size()<4)throw new IllegalArgumentException("raw链接须指向SKILL.md");ref=parts.get(2);path=String.join("/",parts.subList(3,parts.size()));
        } else if(parts.size()==2) {
            var metadata=json.tree(web.get("https://api.github.com/repos/"+parts.get(0)+"/"+parts.get(1),256000).content());
            ref=metadata.path("default_branch").asText();path="";
        } else {
            if(parts.size()<4 || !Set.of("tree","blob").contains(parts.get(2)))throw new IllegalArgumentException("链接须指向技能目录或SKILL.md");
            ref=parts.get(3);path=String.join("/",parts.subList(4,parts.size()));
        }
        if(path.endsWith("/SKILL.md"))path=path.substring(0,path.length()-9);else if("SKILL.md".equals(path))path="";
        else if("blob".equals(parts.size()>2?parts.get(2):""))throw new IllegalArgumentException("请选择SKILL.md文件");
        if(!parts.get(0).matches("[a-zA-Z0-9_.-]+") || !parts.get(1).matches("[a-zA-Z0-9_.-]+")
                || !ref.matches("[a-zA-Z0-9_.-]{1,120}") || path.contains("..") || path.contains("\\") || path.contains(":"))throw new IllegalArgumentException("GitHub路径或分支无效；可使用commit链接");
        return new Source(parts.get(0),parts.get(1),ref,path);
    }
    private String file(Source source,String path) throws Exception {
        String text=web.get("https://raw.githubusercontent.com/"+source.owner()+"/"+source.repo()+"/"+source.ref()+"/"+encodePath(path),2000000).content();
        if(text.indexOf(0)>=0)throw new IllegalArgumentException("技能仅支持普通UTF-8文本资源");
        return text;
    }
    private final Map<String,com.fasterxml.jackson.databind.JsonNode> trees=Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,com.fasterxml.jackson.databind.JsonNode> entry){return size()>16;}
    });
    private com.fasterxml.jackson.databind.JsonNode tree(Source source) throws Exception {
        String key=source.owner()+"/"+source.repo()+"/"+source.ref();var found=trees.get(key);if(found!=null)return found;
        var node=json.tree(web.get("https://api.github.com/repos/"+source.owner()+"/"+source.repo()+"/git/trees/"+source.ref()+"?recursive=1",2000000).content());
        if(node.path("truncated").asBoolean() || !node.path("tree").isArray())throw new IllegalArgumentException("仓库目录过大或不可读取，请选择独立技能仓库");
        trees.put(key,node.path("tree"));return node.path("tree");
    }
    private List<Asset> assets(Source source) throws Exception {
        var out=new ArrayList<Asset>();String prefix=source.directory().isBlank()?"":source.directory()+"/";
        for(var entry:tree(source)) {
            String full=entry.path("path").asText(),mode=entry.path("mode").asText();
            if(!full.startsWith(prefix) || full.equals(main(source)) || !"blob".equals(entry.path("type").asText())
                    || !Set.of("100644","100755").contains(mode))continue;
            String relative=full.substring(prefix.length());
            if(Arrays.stream(relative.split("/")).anyMatch(s->s.startsWith(".")))continue;
            if(!relative.matches("(?i).+\\.(md|txt|json|py|js|mjs|cjs|ts|sh|ps1|yaml|yml|toml|csv|html|css|svg|sql|xml|java|properties|c|cpp|h)")
                    && !Set.of("LICENSE","NOTICE").contains(relative))continue;
            if(entry.path("size").asLong()>2000000)throw new IllegalArgumentException("技能资源超过2MB："+relative);
            out.add(new Asset(full,entry.path("sha").asText(),entry.path("size").asLong()));
            if(out.size()>100)throw new IllegalArgumentException("技能资源超过100个文件，请选择更具体的技能目录");
        }
        return out;
    }
    private String main(Source source){return (source.directory().isBlank()?"":source.directory()+"/")+"SKILL.md";}
    private Map<String,String> metadata(String content) {
        var result=new HashMap<String,String>();
        if(content.startsWith("---")) {
            int end=content.indexOf("\n---",3);
            if(end>0) {
                var options=new org.yaml.snakeyaml.LoaderOptions();options.setCodePointLimit(24000);options.setAllowDuplicateKeys(false);
                Object yaml=new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(content.substring(3,end));
                if(yaml instanceof Map<?,?> map)for(String key:List.of("name","description","keywords"))if(map.get(key)!=null)result.put(key,Objects.toString(map.get(key)));
            }
        }
        return result;
    }
    public Map<String,Object> preview(String url) throws Exception {
        Source origin=source(url);
        var commit=json.tree(web.get("https://api.github.com/repos/"+origin.owner()+"/"+origin.repo()+"/commits?sha="+encode(origin.ref())+"&per_page=1",512000).content()).path(0).path("sha").asText();
        if(!commit.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("无法固定技能版本");
        var pinned=new Source(origin.owner(),origin.repo(),commit,origin.directory());
        if(origin.directory().isBlank()) {
            var directories=new ArrayList<String>();
            for(var entry:tree(pinned))if("blob".equals(entry.path("type").asText()) && Set.of("100644","100755").contains(entry.path("mode").asText())) {
                String path=entry.path("path").asText();
                if(path.equals("SKILL.md")){directories.clear();directories.add("");break;}
                if(path.endsWith("/SKILL.md") && !path.contains("/node_modules/") && !path.startsWith("."))directories.add(path.substring(0,path.length()-9));
            }
            if(directories.isEmpty())throw new IllegalArgumentException("仓库没有SKILL.md，不能作为技能安装");
            if(directories.size()>1)return Map.of("selectionRequired",true,"candidates",directories.stream().limit(30)
                    .map(path->Map.of("name",path,"url","https://github.com/"+origin.owner()+"/"+origin.repo()+"/tree/"+commit+"/"+path)).toList());
            pinned=new Source(origin.owner(),origin.repo(),commit,directories.get(0));
        }
        String content=file(pinned,main(pinned));
        if(content.length()>96000)throw new IllegalArgumentException("技能说明超过96000字，请选择较小的技能");
        var metadata=metadata(content);String name=metadata.getOrDefault("name",origin.directory().isBlank()?origin.repo():origin.directory().substring(origin.directory().lastIndexOf('/')+1));
        String description=metadata.getOrDefault("description","从GitHub导入的技能");if(description.length()>500)description=description.substring(0,500);
        var assets=assets(pinned);
        String canonical="https://github.com/"+origin.owner()+"/"+origin.repo()+"/tree/"+commit+(pinned.directory().isBlank()?"":"/"+pinned.directory());
        return Map.of("url",canonical,"revision",commit,"sha256",WorkspaceService.sha(content.getBytes(StandardCharsets.UTF_8)),"name",name,
                "description",description,"keywords",metadata.getOrDefault("keywords",name.replace('-',' ')),"content",content,
                "files",assets.stream().map(a->Map.of("path",a.path(),"bytes",a.size())).toList());
    }
    public Map<String,Object> previewLatest(String url) throws Exception {
        Source current=source(url);var metadata=json.tree(web.get("https://api.github.com/repos/"+current.owner()+"/"+current.repo(),256000).content());String branch=metadata.path("default_branch").asText();
        if(!branch.matches("[a-zA-Z0-9_.-]{1,120}"))throw new IllegalArgumentException("无法识别默认分支，请重新提供技能目录链接");
        return preview("https://github.com/"+current.owner()+"/"+current.repo()+"/tree/"+branch+(current.directory().isBlank()?"":"/"+current.directory()));
    }
    public Map<String,Object> install(Principal owner,ImportRequest request) throws Exception {
        Source origin=source(request.url());
        if(request.revision()==null || !request.revision().matches("[a-f0-9]{40}") || request.sha256()==null)throw new IllegalArgumentException("请先预览技能再下载");
        var pinned=new Source(origin.owner(),origin.repo(),request.revision(),origin.directory());
        String content=file(pinned,main(pinned));
        if(content.length()>96000 || !WorkspaceService.sha(content.getBytes(StandardCharsets.UTF_8)).equals(request.sha256()))throw new IllegalArgumentException("技能内容已变化，请重新预览");
        var metadata=metadata(content);String name=metadata.getOrDefault("name",origin.repo());
        if(!name.matches("[\\p{L}\\p{N}_. -]{1,100}"))throw new IllegalArgumentException("技能名格式无效");
        String folder="skills/"+name.replaceAll("[^a-zA-Z0-9_-]","_")+"-"+request.revision().substring(0,12);
        var files=assets(pinned);long total=0;
        // Fetch before writing so failed downloads do not leave half a skill registered.
        var downloaded=new LinkedHashMap<String,String>();
        downloaded.put("SKILL.md",content);
        for(var asset:files)if((total+=asset.size())>8000000)throw new IllegalArgumentException("技能资源总量超过8MB");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<String>>();
            for(var asset:files)futures.add(pool.submit(()->file(pinned,asset.path())));
            for(int i=0;i<files.size();i++) {
                var asset=files.get(i);String relative=origin.directory().isBlank()?asset.path():asset.path().substring(origin.directory().length()+1);
                downloaded.put(relative,futures.get(i).get());
            }
        } finally {pool.shutdownNow();}
        var existing=workspace.list(owner).stream().map(WorkspaceService.FileItem::path).collect(java.util.stream.Collectors.toSet());
        long newFiles=downloaded.keySet().stream().filter(path->!existing.contains(folder+"/"+path)).count();
        if(existing.size()+newFiles>200)throw new IllegalArgumentException("工作区剩余文件数量不足");
        for(var entry:downloaded.entrySet())workspace.resolve(owner,folder+"/"+entry.getKey());
        for(var entry:downloaded.entrySet()) {
            String path=folder+"/"+entry.getKey();
            if(existing.contains(path)) {
                if(!WorkspaceService.sha(entry.getValue().getBytes(StandardCharsets.UTF_8)).equals(workspace.read(owner,path).get("sha256")))
                    throw new IllegalArgumentException("已安装资源被修改，未覆盖："+path);
            } else workspace.write(owner,path,entry.getValue(),null);
        }
        String instructions=content+"\n\n技能资源位于专属工作区："+folder+"。关联文本附件已下载；脚本不会在安装时执行，依赖须另行检查，执行脚本须有当前任务的终端授权。仅用于用户任务，不能扩展工具权限。";
        String description=metadata.getOrDefault("description","从GitHub导入的技能");if(description.length()>500)description=description.substring(0,500);
        UUID id=skills.save(owner,new SkillService.SkillInput(name,description,metadata.getOrDefault("keywords",name.replace('-',' ')),instructions,request.url()));
        skills.enabled(owner,"user:"+id,true);
        return Map.of("id","user:"+id,"name",name,"directory",folder,"downloadedFiles",downloaded.size(),"revision",request.revision(),"registered",true,"enabled",true,"scriptsExecuted",false,"dependenciesChecked",false);
    }
}
