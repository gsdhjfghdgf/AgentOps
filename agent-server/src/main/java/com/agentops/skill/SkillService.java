package com.agentops.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 服务（文档 12.2）：
 * 启动时只加载名称与描述；匹配任务后再加载完整内容，脚本与参考资料按需读取。
 *
 * Skill 指导模型，但不自动赋予工具权限，也不保证严格业务流程；
 * 顺序和校验必须确定的部分写在 Java 工作流或业务服务里。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    public record SkillMeta(String name, String description, String location) {
    }

    public record LoadedSkill(SkillMeta meta, String content) {
    }

    private final Map<String, String> contents = new LinkedHashMap<>();
    private final com.agentops.persistence.CapabilityRepo repo;

    public SkillService(com.agentops.persistence.CapabilityRepo repo) {
        this.repo=repo;
        loadAll();
    }

    public List<Map<String,Object>> catalog(com.agentops.domain.Primitives.Principal owner) {
        var preferences=repo.preferences(owner,"skill");
        var result=new ArrayList<Map<String,Object>>();
        for(var entry:contents.entrySet()) {
            String name=extractName(entry.getValue(),"unknown"),id="builtin:"+name;
            result.add(item(id,name,extractDescription(entry.getValue()),field(entry.getValue(),"keywords"),entry.getValue(),"",true,preferences));
        }
        for(var skill:repo.skills(owner))result.add(item("user:"+skill.id(),skill.name(),skill.description(),skill.keywords(),
                skill.content(),skill.sourceUrl(),false,preferences));
        return result;
    }
    private Map<String,Object> item(String id,String name,String description,String keywords,String content,String source,
                                    boolean builtin,Map<String,com.agentops.persistence.CapabilityRepo.Preference> preferences) {
        var preference=preferences.get(id);
        return Map.of("id",id,"name",name,"description",description,"keywords",keywords,"content",content,
                "sourceUrl",source,"builtin",builtin,"enabled",preference==null || preference.enabled());
    }
    private String field(String text,String name) {
        for(String line:text.split("\r?\n"))if(line.startsWith(name+":"))return line.substring(name.length()+1).strip();
        return "";
    }
    public List<LoadedSkill> matchAll(com.agentops.domain.Primitives.Principal owner,String request) {
        if(request==null || request.isBlank())return List.of();
        String query=request.toLowerCase(java.util.Locale.ROOT);
        return catalog(owner).stream().filter(s->Boolean.TRUE.equals(s.get("enabled")))
                .filter(s->query.contains(s.get("name").toString().toLowerCase(java.util.Locale.ROOT))
                        || java.util.Arrays.stream(s.get("keywords").toString().toLowerCase(java.util.Locale.ROOT).split("[,，、\\s]+"))
                        .anyMatch(word->word.length()>=2 && query.contains(word)))
                .sorted(java.util.Comparator.comparingInt(s->query.contains("$"+s.get("name").toString().toLowerCase(java.util.Locale.ROOT))?0:
                        query.contains(s.get("name").toString().toLowerCase(java.util.Locale.ROOT))?1:2))
                .limit(3).map(s->new LoadedSkill(new SkillMeta(s.get("name").toString(),s.get("description").toString(),s.get("id").toString()),s.get("content").toString())).toList();
    }
    public record SkillInput(String name,String description,String keywords,String content,String sourceUrl) {}
    public Map<String,Object> activate(com.agentops.domain.Primitives.Principal owner,java.util.UUID runId,String name) {
        var skill=catalog(owner).stream().filter(s->name.equals(s.get("id")) || name.equals(s.get("name")))
                .findFirst().orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("技能未安装，请先预览并安装到技能库"));
        if(!Boolean.TRUE.equals(skill.get("enabled")))throw new com.agentops.api.RunApplicationService.BadRequestException("技能已禁用，请先在技能库启用");
        repo.activateSkill(owner,runId,skill.get("id").toString());
        return Map.of("id",skill.get("id"),"name",skill.get("name"),"loaded",true,
                "message","技能已绑定当前任务，完整指导将在下一次模型请求中加载。安装不等于外部脚本依赖就绪，执行前仍须检查依赖和权限。");
    }
    public List<LoadedSkill> matchForRun(com.agentops.domain.Primitives.Principal owner,java.util.UUID runId,String request) {
        var active=repo.activeSkills(owner,runId);var selected=new LinkedHashMap<String,LoadedSkill>();
        catalog(owner).stream().filter(s->active.contains(s.get("id").toString()) && Boolean.TRUE.equals(s.get("enabled")))
                .forEach(s->selected.put(s.get("id").toString(),new LoadedSkill(new SkillMeta(s.get("name").toString(),s.get("description").toString(),s.get("id").toString()),s.get("content").toString())));
        if(request!=null && !(request.matches("(?is).*(安装|下载|导入|卸载|删除|列出).*") && request.matches("(?is).*(skill|技能).*")))
            matchAll(owner,request).forEach(s->selected.putIfAbsent(s.meta().location(),s));
        return selected.values().stream().limit(3).toList();
    }
    public String enabledSummary(com.agentops.domain.Primitives.Principal owner) {
        return catalog(owner).stream().filter(s->Boolean.TRUE.equals(s.get("enabled"))).map(s->{
            String description=s.get("description").toString();
            return "- "+s.get("name")+"："+description.substring(0,Math.min(140,description.length()));
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    public record Guidance(String catalog, List<String> recommended, List<LoadedSkill> active) {}

    /** Metadata is cheap; full instructions are attached only after an explicit skill_use. */
    public Guidance guidance(com.agentops.domain.Primitives.Principal owner,java.util.UUID run,String request) {
        var enabled=catalog(owner).stream().filter(s->Boolean.TRUE.equals(s.get("enabled"))).toList();
        var activated=repo.activeSkills(owner,run);
        String query=request==null?"":request.toLowerCase(java.util.Locale.ROOT);
        var suggested=enabled.stream().filter(skill -> query.contains(skill.get("name").toString().toLowerCase(java.util.Locale.ROOT))
                || java.util.Arrays.stream(skill.get("keywords").toString().toLowerCase(java.util.Locale.ROOT).split("[,，、\\s]+"))
                .anyMatch(word -> word.length()>=2 && query.contains(word)))
                .limit(3).map(skill->skill.get("name").toString()).toList();
        String summary=enabled.stream().limit(20).map(skill -> {
            String description=skill.get("description").toString();
            return skill.get("name")+": "+description.substring(0,Math.min(70,description.length()));
        }).collect(java.util.stream.Collectors.joining("\n"));
        var active=enabled.stream().filter(skill->activated.contains(skill.get("id").toString())).limit(3)
                .map(skill->new LoadedSkill(new SkillMeta(skill.get("name").toString(),skill.get("description").toString(),
                        skill.get("id").toString()),skill.get("content").toString())).toList();
        return new Guidance(summary,suggested,active);
    }
    public java.util.UUID save(com.agentops.domain.Primitives.Principal owner,SkillInput skill) {
        if(skill==null || skill.name()==null || skill.name().isBlank() || !skill.name().matches("[\\p{L}\\p{N}_. -]{1,100}")
                || skill.description()==null || skill.description().isBlank() || skill.description().length()>500
                || skill.keywords()==null || skill.keywords().length()>500 || skill.content()==null
                || skill.content().isBlank() || skill.content().length()>98000)
            throw new com.agentops.api.RunApplicationService.BadRequestException("技能须包含名称、说明、关键词和不超过98000字的内容");
        if(repo.skills(owner).size()>=50 && repo.skills(owner).stream().noneMatch(s->s.name().equals(skill.name())))
            throw new com.agentops.api.RunApplicationService.BadRequestException("最多保存50个自定义技能");
        return repo.saveSkill(owner,skill.name(),skill.description(),skill.keywords(),skill.content(),skill.sourceUrl()==null?"":skill.sourceUrl());
    }
    public void enabled(com.agentops.domain.Primitives.Principal owner,String id,boolean enabled) {
        if(catalog(owner).stream().noneMatch(s->id.equals(s.get("id"))))throw new com.agentops.api.RunApplicationService.NotFoundException("技能不存在");
        repo.preference(owner,"skill",id,enabled,false);
    }
    public void delete(com.agentops.domain.Primitives.Principal owner,java.util.UUID id) {
        if(!repo.deleteSkill(owner,id))throw new com.agentops.api.RunApplicationService.NotFoundException("技能不存在");
    }

    private void loadAll() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
            for (var resource : resources) {
                try (var in = resource.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String uri = resource.getURI().toString();
                    String name = resource.getFilename() == null ? uri : resource.getFilename();
                    contents.put(uri, text);
                    log.info("Skill 已加载: {}", extractName(text, name));
                }
            }
        } catch (IOException e) {
            log.warn("Skill 扫描失败: {}", e.getMessage());
        }
    }

    public List<SkillMeta> list() {
        var metas = new ArrayList<SkillMeta>();
        for (var entry : contents.entrySet()) {
            metas.add(new SkillMeta(extractName(entry.getValue(), "unknown"),
                    extractDescription(entry.getValue()), entry.getKey()));
        }
        return metas;
    }

    /** 关键词匹配（简化：命中则注入完整 Skill 内容到系统提示）。 */
    public Optional<LoadedSkill> match(String userRequest, List<String> toolNames) {
        if (userRequest == null) return Optional.empty();
        for (var entry : contents.entrySet()) {
            String name = extractName(entry.getValue(), "");
            // 名称或描述中的关键词命中（例如 sales-report ↔ "销售周报"）
            String haystack = name.toLowerCase() + " " + extractDescription(entry.getValue()).toLowerCase();
            String[] tokens = userRequest.toLowerCase().split("[\\s\\uFF0C\\u3002\\uFF1F\\uFF01,.?!]+");
            for (String token : tokens) {
                if (token.length() >= 2 && haystack.contains(token)) {
                    return Optional.of(new LoadedSkill(
                            new SkillMeta(name, extractDescription(entry.getValue()), entry.getKey()),
                            entry.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    /** 兼容中文短语匹配：直接检查常见任务词。 */
    public Optional<LoadedSkill> matchByPhrase(String phrase, String keyword) {
        if (phrase != null && phrase.contains(keyword)) {
            return contents.entrySet().stream()
                    .filter(e -> extractName(e.getValue(), "").toLowerCase().contains(keyword.toLowerCase())
                            || e.getKey().toLowerCase().contains(keyword.toLowerCase()))
                    .findFirst()
                    .map(e -> new LoadedSkill(
                            new SkillMeta(extractName(e.getValue(), "unknown"),
                                    extractDescription(e.getValue()), e.getKey()), e.getValue()));
        }
        return Optional.empty();
    }

    private String extractName(String content, String fallback) {
        for (String line : content.split("\r?\n")) {
            if (line.startsWith("name:")) {
                return line.substring(5).trim();
            }
        }
        return fallback;
    }

    private String extractDescription(String content) {
        for (String line : content.split("\r?\n")) {
            if (line.startsWith("description:")) {
                return line.substring(12).trim();
            }
        }
        return "";
    }
}
