package com.agentops.operations;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.ToolTypes.*;
import com.agentops.tool.ToolRegistry;
import com.agentops.infra.Json;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;
import java.time.Duration;

@Component
public class OperationsTools {
    private final ToolRegistry tools;private final OperationsService operations;private final McpService mcp;private final ExportService exports;private final SandboxService sandbox;private final Json json;
    private final TaskProgressService progress;
    public OperationsTools(ToolRegistry tools,OperationsService operations,McpService mcp,ExportService exports,SandboxService sandbox,Json json,TaskProgressService progress){this.tools=tools;this.operations=operations;this.mcp=mcp;this.exports=exports;this.sandbox=sandbox;this.json=json;this.progress=progress;}
    private ToolSpec.PropertySpec req(String description){return ToolSpec.PropertySpec.req("string",description);}
    private void register(String name,String description,EffectClass effect,boolean approval,Map<String,ToolSpec.PropertySpec> schema,ToolRegistry.ToolHandler handler){tools.register(new ToolSpec(name,"1.0.0",description,schema,effect,approval,Duration.ofSeconds(name.equals("sandbox_run")?150:name.equals("mcp_call")||name.equals("mcp_tools")?120:40),24000),handler);}
    @PostConstruct void register(){
        register("task_notes","保存任务阶段发现，跨历史裁剪保留。每5～10次读取或确认新问题时更新；保留已有发现、文件路径/行号、验证程度和剩余事项，不保存密钥或大段源码。",EffectClass.IDEMPOTENT_WRITE,false,Map.of("revision",req("系统提示中的阶段笔记版本，初次为0"),"content",req("合并后的完整阶段笔记，最多6000字")),(p,a,c)->ToolResult.success(json.write(progress.saveNotes(p,c.runId(),Long.parseLong(a.get("revision").toString()),a.get("content").toString()))));
        register("plan_update","为复杂任务创建并维护可见计划；先读取当前revision，使用唯一步骤id及pending/doing/done/failed/skipped状态。每完成一步更新；用户调整后按最新计划继续。",EffectClass.IDEMPOTENT_WRITE,false,Map.of("revision",req("当前计划版本，初次为0"),"stepsJson",req("JSON数组，每项{id,title,status}，最多30步")),(p,a,c)->ToolResult.success(json.write(operations.savePlan(p,c.runId(),Long.parseLong(a.get("revision").toString()),a.get("stepsJson").toString()))));
        register("mcp_connections","列出用户已经配置的MCP连接，不返回密钥。使用mcp_tools查看具体工具，再使用mcp_call调用。",EffectClass.READ_ONLY,false,Map.of(),(p,a,c)->ToolResult.success(json.write(mcp.list(p))));
        register("mcp_tools","从指定的已启用MCP服务发现工具与完整参数Schema，仅访问用户保存的连接。",EffectClass.READ_ONLY,false,Map.of("connectionId",req("mcp_connections返回的连接ID")),(p,a,c)->ToolResult.success(json.write(mcp.tools(p,UUID.fromString(a.get("connectionId").toString())))));
        register("mcp_call","调用已配置MCP服务工具。可能产生外部副作用，每次需批准；不能仅凭工具自报只读绕过审批。超时结果未知时须核对，不能自动重复写操作。",EffectClass.NON_IDEMPOTENT_WRITE,true,Map.of("connectionId",req("连接ID"),"toolName",req("发现的远程工具名"),"argumentsJson",req("遵守远程inputSchema的JSON对象字符串")),(p,a,c)->{var result=mcp.call(p,UUID.fromString(a.get("connectionId").toString()),a.get("toolName").toString(),a.get("argumentsJson").toString());return result.path("isError").asBoolean()?ToolResult.failed("MCP_TOOL_ERROR",result.toString()):ToolResult.success(result.toString());});
        register("report_export","生成可下载的真实Word、PDF、Excel、Markdown或HTML产物；不是仅返回文字。PDF为中文文本排版；Excel正文必须是二维JSON数组。",EffectClass.IDEMPOTENT_WRITE,false,Map.of("format",req("docx/pdf/xlsx/md/html"),"title",req("文档标题，不含扩展名"),"content",req("正文；xlsx为二维JSON数组")),(p,a,c)->{var result=exports.export(p,c.runId(),a.get("format").toString(),a.get("title").toString(),a.get("content").toString());return ToolResult.successWithArtifact(json.write(result),result.objectId());});
        register("project_propose_batch","可选的修改前预览：提出最多20个项目文件修改，在任务面板展示差异，由用户一次性应用。仅创建草稿不写项目；若用户要求预览则使用本工具而不是直接写入。",EffectClass.IDEMPOTENT_WRITE,false,Map.of("filesJson",req("JSON数组[{path,content,expectedSha256}]，已有文件必须带读取时的SHA256，新文件为空")),(p,a,c)->ToolResult.success(json.write(operations.propose(p,c.runId(),a.get("filesJson").toString()))));
        register("sandbox_run","在虚拟机Docker隔离环境执行Linux命令，有Node22，无网络，无主机/项目挂载。仅/tmp可写，容器结束销毁；不适合直接修改用户项目。隔离命令单独按任务授权一次，不授予主机命令权限，不能自动降级到主机执行。",EffectClass.NON_IDEMPOTENT_WRITE,true,Map.of("command",req("Linux sh命令"),"timeoutSeconds",ToolSpec.PropertySpec.opt("string","1～120秒，默认30")),(p,a,c)->{var result=sandbox.run(a.get("command").toString(),Integer.parseInt(Objects.toString(a.get("timeoutSeconds"),"30")),c.control());return ToolResult.success(json.write(result));});
    }
}
