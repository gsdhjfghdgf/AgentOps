package com.agentops.engine;
import com.agentops.domain.ToolTypes.ToolCallRequest;
import java.util.*;

/** Action intent only; never claims a tool has executed before its result arrives. */
final class PublicProgress {
    private PublicProgress(){}
    static String describe(List<ToolCallRequest> calls){
        var labels=new LinkedHashSet<String>();for(var call:calls){String label=switch(call.toolName()){
            case "project_read","workspace_read","knowledge_read"->"读取关键文件并核对原文";
            case "project_list","workspace_list"->"查看目录结构，确定检查范围";
            case "project_search","workspace_search","knowledge_search"->"搜索相关代码和资料";
            case "plan_update"->"更新任务计划与当前步骤";case "task_notes"->"整理阶段发现和证据";
            case "project_write","project_patch","workspace_write"->"准备文件修改并核对版本";
            case "command_run","sandbox_run"->"准备运行验证命令，按当前授权执行";
            case "web_read","web_search","browser_open"->"查阅网页资料，核对来源";
            case "report_export","report_generate"->"整理并生成交付文件";
            default->"继续处理当前步骤，核对工具返回结果";};labels.add(label);if(labels.size()>=2)break;}
        return labels.isEmpty()?"正在准备下一步。":"接下来"+String.join("；",labels)+"。";
    }
}
