package com.agentops.api.web;

import com.agentops.api.RunControlService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunControlController {
    private final RunControlService service;
    public RunControlController(RunControlService service) { this.service=service; }
    public record ControlRequest(UUID commandId,String text) {}
    @PostMapping("/{id}/pause")
    public Map<String,Object> pause(@RequestHeader("X-Tenant-Id")String tenant,@RequestHeader("X-User-Id")String user,
                                   @PathVariable UUID id,@RequestBody ControlRequest request) {
        return service.pause(ApiController.principal(tenant,user),id,request.commandId());
    }
    @PostMapping("/{id}/resume")
    public Map<String,Object> resume(@RequestHeader("X-Tenant-Id")String tenant,@RequestHeader("X-User-Id")String user,
                                    @PathVariable UUID id,@RequestBody ControlRequest request) {
        return service.resume(ApiController.principal(tenant,user),id,request.commandId(),request.text());
    }
}
