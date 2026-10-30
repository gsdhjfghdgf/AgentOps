package com.agentops.api.web;

import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.CapabilityRepo;
import com.agentops.skill.SkillService;
import com.agentops.skill.SkillDownloadService;
import com.agentops.tool.ToolRegistry;
import com.agentops.tool.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CapabilitiesController {
    private final ToolRegistry tools;private final CapabilityRepo repo;private final SkillService skills;
    private final WorkspaceService workspace;private final SkillDownloadService downloads;
    public CapabilitiesController(ToolRegistry tools,CapabilityRepo repo,SkillService skills,WorkspaceService workspace,SkillDownloadService downloads){
        this.tools=tools;this.repo=repo;this.skills=skills;this.workspace=workspace;this.downloads=downloads;
    }
    @GetMapping("/capabilities") public Map<String,Object> list(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user) {
        var owner=ApiController.principal(tenant,user);var prefs=repo.preferences(owner,"tool");
        var catalog=tools.all().stream().map(t->{var spec=t.spec();var preference=prefs.get(spec.name());
            return Map.<String,Object>of("name",spec.name(),"description",spec.description(),"effect",spec.effectClass().name().toLowerCase(),
                    "approvalRequired",spec.requiresApproval(),"enabled",preference==null?!"command_run".equals(spec.name()):preference.enabled(),
                    "askApproval",!com.agentops.project.ProjectTools.isProjectFileTool(spec.name())&&(spec.requiresApproval() || (preference!=null && preference.askApproval())),"schema",spec.inputSchema());
        }).sorted(Comparator.comparing(t->t.get("name").toString())).toList();
        return Map.of("tools",catalog,"skills",skills.catalog(owner));
    }
    public record ToolPreference(boolean enabled,boolean askApproval) {}
    @PutMapping("/capabilities/tools/{name}") public Map<String,Object> preference(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,
                                                                                 @PathVariable String name,@RequestBody ToolPreference preference) {
        var spec=tools.spec(name).orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("工具不存在"));
        repo.preference(ApiController.principal(tenant,user),"tool",name,preference.enabled(),!com.agentops.project.ProjectTools.isProjectFileTool(name)&&(preference.askApproval()||spec.requiresApproval()));
        return Map.of("ok",true);
    }
    @PostMapping("/skills") public Map<String,Object> save(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody SkillService.SkillInput input) {
        return Map.of("id","user:"+skills.save(ApiController.principal(tenant,user),input));
    }
    public record Enabled(boolean enabled) {}
    @PutMapping("/skills/{id}/enabled") public Map<String,Object> enabled(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,
                                                                         @PathVariable String id,@RequestBody Enabled enabled) {
        skills.enabled(ApiController.principal(tenant,user),id,enabled.enabled());return Map.of("ok",true);
    }
    @DeleteMapping("/skills/{id}") public Map<String,Object> delete(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id) {
        skills.delete(ApiController.principal(tenant,user),id);return Map.of("ok",true);
    }
    public record Preview(String url) {}
    @PostMapping("/skills/preview-url") public Map<String,Object> preview(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody Preview input) throws Exception {
        ApiController.principal(tenant,user);return downloads.preview(input.url());
    }
    @PostMapping("/skills/import-url") public Map<String,Object> install(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,
                                                                       @RequestBody SkillDownloadService.ImportRequest input) throws Exception {
        return downloads.install(ApiController.principal(tenant,user),input);
    }
    @GetMapping("/workspace/files") public Map<String,Object> files(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user) throws Exception {
        return Map.of("files",workspace.list(ApiController.principal(tenant,user)));
    }
    @GetMapping("/workspace/file") public Map<String,Object> read(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestParam String path) throws Exception {
        return workspace.read(ApiController.principal(tenant,user),path);
    }
    public record FileInput(String path,String content,String expectedSha256) {}
    @PutMapping("/workspace/file") public Map<String,Object> upload(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody FileInput input) throws Exception {
        return workspace.write(ApiController.principal(tenant,user),input.path(),input.content(),input.expectedSha256());
    }
}
