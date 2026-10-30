package com.agentops.browser;
import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.ToolTypes.*;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class BrowserTools {
    private final ToolRegistry tools;private final BrowserService browser;private final Json json;
    public BrowserTools(ToolRegistry tools,BrowserService browser,Json json){this.tools=tools;this.browser=browser;this.json=json;}
    private ToolSpec.PropertySpec req(String d){return ToolSpec.PropertySpec.req("string",d);}
    private void tool(String name,String description,boolean approval,Map<String,ToolSpec.PropertySpec> schema,ToolRegistry.ToolHandler handler){tools.register(new ToolSpec(name,"1.0.0",description,schema,Set.of("browser_click","browser_fill").contains(name)?EffectClass.NON_IDEMPOTENT_WRITE:EffectClass.READ_ONLY,approval,java.time.Duration.ofSeconds(60),24000),handler);}
    @PostConstruct void register(){
        tool("browser_open","用独立真实浏览器打开网页，执行页面脚本，返回可点击元素ref。共用任务联网许可；本机地址仅允许用户为当前项目配置的预览源。",true,Map.of("url",req("完整公开网页URL，或项目配置的本机预览地址")),(p,a,c)->ToolResult.success(json.write(browser.open(p,c.runId(),a.get("url").toString()))));
        tool("browser_snapshot","读取真实浏览器当前页面正文和元素ref，处理动态页面。",false,Map.of(),(p,a,c)->ToolResult.success(json.write(browser.snapshot(p,c.runId()))));
        tool("browser_click","点击最新快照中的元素，可能提交表单或改变远端状态，须当前任务浏览器交互授权。结果未知时先核对，不能自动重复提交。",true,Map.of("ref",req("快照中的元素ref，例如e1")),(p,a,c)->{try{return ToolResult.success(json.write(browser.interact(p,c.runId(),a.get("ref").toString(),null,true)));}catch(IllegalArgumentException e){throw e;}catch(Exception e){return ToolResult.unknown("浏览器点击结果未确认，请先核对页面，不要重复提交");}});
        tool("browser_fill","在浏览器输入框填写文字，须任务浏览器交互授权。不要在对话工具参数中提交密码或密钥。",true,Map.of("ref",req("输入框ref"),"text",req("填写的普通文字")),(p,a,c)->{try{return ToolResult.success(json.write(browser.interact(p,c.runId(),a.get("ref").toString(),a.get("text").toString(),false)));}catch(IllegalArgumentException e){throw e;}catch(Exception e){return ToolResult.unknown("填写结果未确认，请先核对页面");}});
        tool("browser_screenshot","保存真实浏览器截图为可预览、可下载的PNG产物。",false,Map.of(),(p,a,c)->{var image=browser.screenshot(p,c.runId());return ToolResult.successWithArtifact(json.write(image),image.get("objectId").toString());});
        tool("browser_close","关闭当前任务的独立浏览器会话。",false,Map.of(),(p,a,c)->{browser.close(p,c.runId());return ToolResult.success("浏览器已关闭");});
    }
}
