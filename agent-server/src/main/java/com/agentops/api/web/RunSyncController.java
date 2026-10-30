package com.agentops.api.web;

import com.agentops.api.RunApplicationService;
import com.agentops.persistence.ArtifactRepo;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One round trip for state, newly committed messages and ready artifacts. */
@RestController
@RequestMapping("/api/runs")
public class RunSyncController {
    private final RunApplicationService app;
    private final ArtifactRepo artifacts;
    public RunSyncController(RunApplicationService app, ArtifactRepo artifacts) {
        this.app = app; this.artifacts = artifacts;
    }

    public record ArtifactView(String objectId, String name, String kind, long byteSize) {}
    public record Snapshot(Map<String,Object> run, List<RunApplicationService.MessageView> messages,
                           List<ArtifactView> artifacts, boolean hasMore) {}

    @GetMapping("/{runId}/sync")
    public Snapshot sync(@RequestHeader("X-Tenant-Id") String tenant,
                         @RequestHeader("X-User-Id") String user, @PathVariable UUID runId,
                         @RequestParam(defaultValue = "0") long afterSeq) {
        var owner = ApiController.principal(tenant, user);
        var run = app.getRun(owner, runId); // Ownership before reading any related rows.
        var delta = app.messageDelta(owner, UUID.fromString(run.sessionId()), afterSeq, 100);
        var files = artifacts.listReadyByOwnerRun(owner.tenantId(), owner.userId(), runId).stream()
                .map(row -> new ArtifactView(row.objectId().toString(),
                        row.originalName() == null ? "产物" : row.originalName(), row.kind(), row.byteSize())).toList();
        return new Snapshot(RunResponses.run(run, app.questionOptions(owner, run)),
                delta.messages(), files, delta.hasMore());
    }
}
