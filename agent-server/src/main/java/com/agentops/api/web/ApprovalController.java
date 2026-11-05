package com.agentops.api.web;

import com.agentops.approval.ApprovalService;
import com.agentops.domain.Primitives.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/**
 * 批准接口（文档 15.2 / 21.1）：
 * 用户查看待批准操作的完整摘要，然后批准或拒绝；
 * 参数变化必须重新评估（digest 不匹配则不命中）。
 */
@RestController
@RequestMapping("/api")
public class ApprovalController {

    private final ApprovalService approvals;
    private final com.agentops.persistence.ExecutionRepo execution;
    private final com.agentops.tool.ToolRegistry tools;
    private final com.agentops.infra.Json json;
    private final com.agentops.project.ProjectService projects;

    public ApprovalController(ApprovalService approvals,com.agentops.persistence.ExecutionRepo execution,
                               com.agentops.tool.ToolRegistry tools,com.agentops.infra.Json json,com.agentops.project.ProjectService projects) {
        this.approvals = approvals;
        this.execution=execution;this.tools=tools;this.json=json;
        this.projects=projects;
    }

    /** 待批准列表（按 run）。 */
    @GetMapping("/runs/{runId}/approvals")
    public Map<String, Object> listPending(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID runId) {
        var owner = ApiController.principal(tenantId, userId);
        var list = approvals.listPending(owner, runId).stream()
                .map(row -> summary(owner,row))
                .toList();
        return Map.of("approvals", list);
    }

    private Map<String,Object> summary(Principal owner,com.agentops.persistence.ApprovalRepo.ApprovalRow row) {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("approvalId",row.id().toString());result.put("tool",row.toolName());result.put("digest",row.actionDigest());
        result.put("expiresAt",row.expiresAt().toString());
        boolean network=com.agentops.persistence.RunRepo.isPublicWebTool(row.toolName());
        boolean command="command_run".equals(row.toolName());
        boolean browser=com.agentops.persistence.RunRepo.isBrowserInteraction(row.toolName());
        result.put("scope",network?"task_network":command?"task_command":browser?"task_browser":"sandbox_run".equals(row.toolName())?"task_sandbox":"operation");
        var tool=tools.spec(row.toolName());
        result.put("description",network?"允许当前任务搜索互联网、读取公开网页与图片，并将图片保存为本任务的文件成果。后续公开网址不再逐次申请；不授权项目或系统文件写入、命令执行或访问内网。":tool.map(s->s.description()).orElse(row.toolName()));
        if(command)result.put("description","允许当前任务在工作区执行终端命令，后续命令不再逐条申请。命令以当前系统用户权限执行，工作目录不是安全沙箱，可能修改文件或访问网络。仅对当前任务有效。");
        if(browser)result.put("description","允许当前任务操作独立浏览器，包括点击和填写普通文字，可能提交表单或改变网页状态。当前任务内仅申请一次；不会读取日常浏览器的登录状态。");
        result.put("effectClass",tool.map(s->s.effectClass().name().toLowerCase()).orElse("unknown"));
        result.put("arguments",execution.findToolCall(owner.tenantId(),row.runId(),row.callId()).map(c->json.map(c.argumentsJson())).orElse(Map.of()));
        var project=projects.forRun(owner,row.runId());project.ifPresent(p->result.put("projectDirectory",p.root()));
        if(network){String origin=projects.previewOriginForRun(owner,row.runId());if(!origin.isBlank())result.put("description",result.get("description")+" 独立浏览器同时允许此任务选择的本机预览源："+origin);}
        if(Set.of("project_write","project_patch").contains(row.toolName())&&project.isPresent())try {
            @SuppressWarnings("unchecked") var args=(Map<String,Object>)result.get("arguments");
            String path=Objects.toString(args.get("path"),""),content=Objects.toString(args.get("content"),"");
            if(row.toolName().equals("project_patch"))content=projects.patchedContent(project.get(),path,Objects.toString(args.get("find"),""),content);
            result.put("previewDiff",projects.preview(project.get(),path,content,Objects.toString(args.get("expectedSha256"),"")));
        }catch(Exception e){result.put("previewError",e.getMessage());}
        return result;
    }

    /** 查看单个批准详情（完整操作摘要）。 */
    @GetMapping("/approvals/{approvalId}")
    public ResponseEntity<Map<String, Object>> view(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID approvalId) {
        var owner = ApiController.principal(tenantId, userId);
        return approvals.view(owner, approvalId).map(view -> {
                    var detail=new java.util.LinkedHashMap<>(view);
                    approvals.listPending(owner,UUID.fromString(view.get("runId").toString())).stream()
                            .filter(row->row.id().equals(approvalId)).findFirst().ifPresent(row->detail.putAll(summary(owner,row)));
                    return (Map<String,Object>) detail;
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record DecideRequest(String decision, String reason) {
    }

    /** 批准决策：approved / rejected（文档 15.2）。 */
    @PostMapping("/approvals/{approvalId}/decision")
    public Map<String, Object> decide(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID approvalId,
            @RequestBody DecideRequest req) {
        var owner = ApiController.principal(tenantId, userId);
        String decision = req.decision() == null ? "" : req.decision().toLowerCase(java.util.Locale.ROOT);
        var result = approvals.decide(owner, approvalId, decision, req.reason());
        if (!result.ok()) {
            throw new ApiController.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT, result.reason());
        }
        return Map.of("ok", true, "decision", decision,
                "runId", result.runId() == null ? "" : result.runId().toString());
    }
}
