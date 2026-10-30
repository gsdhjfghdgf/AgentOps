package com.agentops.api.web;
import com.agentops.api.SteeringService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
public class SteeringController {
    private final SteeringService service;
    public SteeringController(SteeringService service) { this.service=service; }
    public record Request(UUID commandId,String text) {}
    @PostMapping("/api/runs/{runId}/steer")
    public Map<String,Object> steer(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@PathVariable UUID runId,@RequestBody Request request) {
        return service.submit(ApiController.principal(tenant,user),runId,request.commandId(),request.text());
    }
}
