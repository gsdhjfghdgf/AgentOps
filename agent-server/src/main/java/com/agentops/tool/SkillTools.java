package com.agentops.tool;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import com.agentops.skill.SkillDownloadService;
import com.agentops.skill.SkillService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SkillTools {
    private final ToolRegistry registry;
    private final SkillService skills;
    private final SkillDownloadService downloads;
    private final Json json;
    private final com.agentops.skill.SkillDependencyService dependencies;private final com.agentops.runtime.RuntimeService runtimes;
    public SkillTools(ToolRegistry registry,SkillService skills,SkillDownloadService downloads,Json json,
                      com.agentops.skill.SkillDependencyService dependencies,com.agentops.runtime.RuntimeService runtimes) {
        this.registry=registry;this.skills=skills;this.downloads=downloads;this.json=json;
        this.dependencies=dependencies;this.runtimes=runtimes;
    }
    private ToolSpec spec(String name,String description,EffectClass effect,boolean approval,int timeoutMillis,
                          Map<String,ToolSpec.PropertySpec> schema) {
        return new ToolSpec(name,"1.0.0",description,schema,effect,approval,java.time.Duration.ofMillis(timeoutMillis),8192);
    }
    @PostConstruct void register() {
        registry.register(spec("skill_dependencies","检查已安装技能的Node、Python、浏览器依赖及版本，不执行技能脚本。",EffectClass.READ_ONLY,false,30000,
                Map.of("name",ToolSpec.PropertySpec.req("string","已安装技能名或ID"))), (p,a,c)->ToolResult.success(json.write(dependencies.check(p,a.get("name").toString()))));
        registry.register(spec("runtime_install_node","从Node.js官方下载并校验SHA256，安装Agent私有的Node运行时；不改变系统Node或全局PATH。须批准。",EffectClass.IDEMPOTENT_WRITE,true,240000,
                Map.of("major",ToolSpec.PropertySpec.req("string","支持22或24，请按技能要求选择"))), (p,a,c)->ToolResult.success(json.write(runtimes.installNode(Integer.parseInt(a.get("major").toString())))));
        registry.register(spec("skill_list","列出本Agent已安装的技能及启用状态；工作区中的克隆仓库不算已安装。",EffectClass.READ_ONLY,false,10000,
                Map.of("query",ToolSpec.PropertySpec.opt("string","可选，按技能名或说明筛选"))), (p,a,c)->{
            String query=Objects.toString(a.get("query"),"").toLowerCase(Locale.ROOT);
            var catalog=skills.catalog(p).stream().filter(s->(s.get("name")+" "+s.get("description")).toLowerCase(Locale.ROOT).contains(query)).toList();
            var items=catalog.stream().limit(20).map(s->Map.of("id",s.get("id"),"name",s.get("name"),"enabled",s.get("enabled"),
                    "description",s.get("description").toString().substring(0,Math.min(100,s.get("description").toString().length())))).toList();
            return ToolResult.success(json.write(Map.of("total",catalog.size(),"skills",items,"hint","用skill_use加载已安装技能；超过20项时用query筛选")));
        });
        registry.register(spec("skill_preview","预览公开GitHub技能并固定版本，自动识别仓库中的SKILL.md。若返回多个候选，请让用户选择。预览不是安装。",EffectClass.READ_ONLY,true,60000,
                Map.of("url",ToolSpec.PropertySpec.req("string","GitHub仓库、技能目录或SKILL.md的HTTPS链接"))), (p,a,c)->{
            var preview=downloads.preview(a.get("url").toString());
            if(Boolean.TRUE.equals(preview.get("selectionRequired")))return ToolResult.success(json.write(preview));
            var summary=new LinkedHashMap<String,Object>();
            for(String key:List.of("url","revision","sha256","name","description"))summary.put(key,preview.get(key));
            summary.put("files",((List<?>)preview.get("files")).size()+1);
            summary.put("next","用返回的url、revision、sha256调用skill_install，批准后才注册并启用。不会在安装时执行技能脚本。");
            return ToolResult.success(json.write(summary));
        });
        registry.register(spec("skill_install","将skill_preview固定版本的技能下载、注册并启用到本Agent技能库。必须经安装批准；不能用git clone代替。脚本依赖须另行检查。",EffectClass.IDEMPOTENT_WRITE,true,180000,
                Map.of("url",ToolSpec.PropertySpec.req("string","skill_preview返回的规范技能目录链接"),
                        "revision",ToolSpec.PropertySpec.req("string","预览返回的40位commit"),
                        "sha256",ToolSpec.PropertySpec.req("string","预览返回的技能说明SHA256"))), (p,a,c)->ToolResult.success(json.write(downloads.install(p,
                new SkillDownloadService.ImportRequest(a.get("url").toString(),a.get("revision").toString(),a.get("sha256").toString())))));
        registry.register(spec("skill_use","加载已安装并启用的技能到当前任务上下文，用于执行相关用户任务；无需重新下载或运行安装脚本。",EffectClass.READ_ONLY,false,10000,
                Map.of("name",ToolSpec.PropertySpec.req("string","skill_list返回的技能名或ID"))), (p,a,c)->ToolResult.success(json.write(skills.activate(p,c.runId(),a.get("name").toString()))));
    }
}
