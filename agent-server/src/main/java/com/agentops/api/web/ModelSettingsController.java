package com.agentops.api.web;

import com.agentops.model.ModelSettingsService;
import com.agentops.model.ModelConnectionVerifier;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/model-settings")
public class ModelSettingsController {
    private final ModelSettingsService settings;
    private final ModelConnectionVerifier verifier;
    public ModelSettingsController(ModelSettingsService settings,ModelConnectionVerifier verifier) {
        this.settings=settings;this.verifier=verifier;
    }
    @GetMapping public Map<String,Object> get(@RequestHeader("X-Tenant-Id") String tenant,
                                             @RequestHeader("X-User-Id") String user) {
        return settings.view(ApiController.principal(tenant,user));
    }
    @PutMapping public Map<String,Object> save(@RequestHeader("X-Tenant-Id") String tenant,
                                              @RequestHeader("X-User-Id") String user,
                                              @RequestBody ModelSettingsService.Update update) {
        var owner=ApiController.principal(tenant,user);
        verifier.require(owner,settings.draft(owner,update));
        return settings.save(owner,update);
    }
    @DeleteMapping public Map<String,Object> reset(@RequestHeader("X-Tenant-Id") String tenant,
                                                @RequestHeader("X-User-Id") String user) {
        var owner=ApiController.principal(tenant,user);settings.reset(owner);return settings.view(owner);
    }
    @PostMapping("/profiles") public Map<String,Object> createProfile(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@RequestBody ModelSettingsService.ProfileUpdate update) {
        var owner=ApiController.principal(tenant,user);
        verifier.require(owner,settings.profileDraft(owner,null,update));
        return settings.createProfile(owner,update);
    }
    @PutMapping("/profiles/{id}") public Map<String,Object> updateProfile(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@PathVariable UUID id,@RequestBody ModelSettingsService.ProfileUpdate update) {
        var owner=ApiController.principal(tenant,user);
        verifier.require(owner,settings.profileDraft(owner,id,update));
        return settings.updateProfile(owner,id,update);
    }
    @DeleteMapping("/profiles/{id}") public Map<String,Object> deleteProfile(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@PathVariable UUID id) {
        return settings.deleteProfile(ApiController.principal(tenant,user),id);
    }
    public record Selection(String profileId) {}
    @PutMapping("/selection") public Map<String,Object> selectProfile(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@RequestBody Selection selection) {
        var owner=ApiController.principal(tenant,user);
        if(selection.profileId()==null || selection.profileId().isBlank())
            throw new com.agentops.api.RunApplicationService.BadRequestException("请选择模型配置");
        if(!"default".equals(selection.profileId()))verifier.require(owner,settings.saved(owner,UUID.fromString(selection.profileId())));
        return settings.selectProfile(owner,selection.profileId());
    }
    @PostMapping({"/profiles/test","/profiles/{id}/test"}) public Map<String,Object> testProfile(
            @RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,
            @PathVariable(required=false) UUID id,@RequestBody ModelSettingsService.ProfileUpdate update) {
        var owner=ApiController.principal(tenant,user);
        return verifier.test(owner,settings.profileDraft(owner,id,update));
    }
    @PostMapping("/test") public Map<String,Object> test(@RequestHeader("X-Tenant-Id") String tenant,
                                                       @RequestHeader("X-User-Id") String user,
                                                       @RequestBody ModelSettingsService.Update update) {
        var owner=ApiController.principal(tenant,user);
        return verifier.test(owner,settings.draft(owner,update));
    }
}
