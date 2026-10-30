package com.agentops.runtime;
import com.agentops.api.web.ApiController;
import com.agentops.skill.*;
import com.agentops.persistence.CapabilityRepo;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController("skillEnvironmentController") @RequestMapping("/api")
public class RuntimeController {
    private final SkillDependencyService dependencies;private final RuntimeService runtimes;private final CapabilityRepo repo;private final SkillService skills;private final SkillDownloadService downloads;
    public RuntimeController(SkillDependencyService dependencies,RuntimeService runtimes,CapabilityRepo repo,SkillService skills,SkillDownloadService downloads){this.dependencies=dependencies;this.runtimes=runtimes;this.repo=repo;this.skills=skills;this.downloads=downloads;}
    public record Install(int major){} public record Rollback(UUID versionId){}
    @GetMapping("/skills/{id}/dependencies") public Map<String,Object> check(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable String id){return dependencies.check(ApiController.principal(tenant,user),id);}
    @PostMapping("/runtimes/node") public Map<String,Object> install(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody Install input) throws Exception{ApiController.principal(tenant,user);return runtimes.installNode(input.major());}
    @GetMapping("/skills/{id}/versions") public Map<String,Object> versions(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id){var p=ApiController.principal(tenant,user);if(repo.skills(p).stream().noneMatch(s->s.id().equals(id)))throw new com.agentops.api.RunApplicationService.NotFoundException("技能不存在");return Map.of("versions",repo.versions(p,id));}
    @PostMapping("/skills/{id}/rollback") public Map<String,Object> rollback(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id,@RequestBody Rollback input){var p=ApiController.principal(tenant,user);var old=repo.version(p,id,input.versionId());skills.save(p,new SkillService.SkillInput(old.name(),old.description(),old.keywords(),old.content(),old.sourceUrl()));return Map.of("ok",true);}
    @PostMapping("/skills/{id}/preview-update") public Map<String,Object> update(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id) throws Exception{var p=ApiController.principal(tenant,user);var skill=repo.skills(p).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("技能不存在"));return downloads.previewLatest(skill.sourceUrl());}
}
