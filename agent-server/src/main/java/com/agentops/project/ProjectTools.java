package com.agentops.project;
import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.ToolTypes.*;
import com.agentops.tool.ToolRegistry;
import com.agentops.infra.Json;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ProjectTools {
    public static boolean isProjectFileTool(String name){return Set.of("project_list","project_read","project_search","project_write","project_patch","project_git_status","project_git_diff","project_undo").contains(name);}
    private final ToolRegistry tools;private final ProjectService projects;private final Json json;
    private final com.agentops.operations.TaskProgressService progress;
    public ProjectTools(ToolRegistry tools,ProjectService projects,Json json,com.agentops.operations.TaskProgressService progress){this.tools=tools;this.projects=projects;this.json=json;this.progress=progress;}
    private ToolSpec.PropertySpec req(String d){return ToolSpec.PropertySpec.req("string",d);}private ToolSpec.PropertySpec opt(String d){return ToolSpec.PropertySpec.opt("string",d);}
    private void tool(String name,String description,boolean write,Map<String,ToolSpec.PropertySpec> schema,ToolRegistry.ToolHandler handler){tools.register(ToolRegistry.spec(name,description,write?EffectClass.IDEMPOTENT_WRITE:EffectClass.READ_ONLY,false,name.equals("project_read")?49152:8192,schema),handler);}
    @PostConstruct void register(){
        tool("project_list","列出当前任务固定项目的文件和子目录。用户必须先选择项目，不是专属工作区。",false,Map.of("directory",opt("项目内相对子目录，默认根目录")),(p,a,c)->ToolResult.success(json.write(projects.files(p,c.runId(),Objects.toString(a.get("directory"),"")))));
        tool("project_read","每次读取最多6000字符，返回行号、SHA256和eof。eof=true即已读完；读取台账与阶段笔记跨历史裁剪保留，避免反复扫描。",false,Map.of("path",req("项目内相对路径"),"offset",opt("字符起点，默认0；只有eof=false时用nextOffset继续")),(p,a,c)->{String path=a.get("path").toString();int offset=Integer.parseInt(Objects.toString(a.get("offset"),"0"));return ToolResult.success(json.write(progress.observe(p,c.runId(),path,offset,projects.read(p,c.runId(),path,offset))));});
        tool("project_search","在当前项目查找文字，跳过Git内部、依赖和构建目录。",false,Map.of("query",req("要查找的原文，非正则")),(p,a,c)->ToolResult.success(json.write(projects.search(p,c.runId(),a.get("query").toString()))));
        tool("project_write","修改真实项目文件，项目内直接执行并保留撤销记录。已有文件必须提供读取时的SHA256。",true,Map.of("path",req("项目内相对路径"),"content",req("修改后完整正文"),"expectedSha256",opt("已有文件当前SHA256，新建留空")),(p,a,c)->ToolResult.success(json.write(projects.write(p,c.runId(),a.get("path").toString(),a.get("content").toString(),Objects.toString(a.get("expectedSha256"),"")))));
        tool("project_patch","替换真实项目文件中的唯一原文片段，适用于大文件的小范围修改。项目内直接执行并保留差异与撤销记录。",true,Map.of("path",req("相对路径"),"find",req("要替换的唯一原文片段"),"content",req("替换后的片段"),"expectedSha256",req("读取时的全文件SHA256")),(p,a,c)->ToolResult.success(json.write(projects.patch(p,c.runId(),a.get("path").toString(),a.get("find").toString(),a.get("content").toString(),a.get("expectedSha256").toString()))));
        tool("project_git_status","查看当前项目的Git修改状态，不提交或重置文件。",false,Map.of(),(p,a,c)->ToolResult.success(json.write(projects.git(p,projects.required(p,c.runId()).id(),false))));
        tool("project_git_diff","查看当前项目未暂存的Git差异，完整差异也可在项目面板查看。",false,Map.of(),(p,a,c)->{var result=projects.git(p,projects.required(p,c.runId()).id(),true);String text=result.get("text").toString();return ToolResult.success(text.substring(0,Math.min(2200,text.length())));});
        tool("project_undo","撤销当前项目的一次Agent文件修改；直接执行，文件不能有后续变更。",true,Map.of("changeId",req("project_write返回的changeId")),(p,a,c)->{projects.undo(p,projects.required(p,c.runId()).id(),UUID.fromString(a.get("changeId").toString()));return ToolResult.success("项目文件修改已撤销");});
    }
}
