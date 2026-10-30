package com.agentops.browser;

import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.RunRepo;
import com.agentops.project.ProjectService;
import com.agentops.runtime.RuntimeService;
import com.agentops.artifact.ArtifactService;
import com.agentops.infra.Json;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@Service
public class BrowserService {
    private final ExecutorService driver=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"agent-browser");t.setDaemon(true);return t;});
    private final Map<UUID,Session> sessions=new LinkedHashMap<>();private Playwright playwright;private Browser browser;
    private final RuntimeService runtime;private final RunRepo runs;private final ProjectService projects;private final ArtifactService artifacts;private final Json json;
    private static final class Session {Principal owner;BrowserContext context;Page page;GuardedBrowserProxy proxy;long touched=System.currentTimeMillis();}
    public BrowserService(RuntimeService runtime,RunRepo runs,ProjectService projects,ArtifactService artifacts,Json json){this.runtime=runtime;this.runs=runs;this.projects=projects;this.artifacts=artifacts;this.json=json;}
    private <T>T call(Callable<T> action) throws Exception {var task=driver.submit(action);try{return task.get(55,TimeUnit.SECONDS);}catch(ExecutionException e){if(e.getCause() instanceof Exception cause)throw cause;throw e;}catch(InterruptedException|TimeoutException e){task.cancel(true);throw e;}}
    private Session session(Principal p,UUID run,boolean create) throws Exception {
        runs.findByIdOwner(p,run).orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("任务不存在"));var session=sessions.get(run);
        if(session!=null){if(!session.owner.equals(p))throw new IllegalArgumentException("浏览器会话不属于当前账号");session.touched=System.currentTimeMillis();return session;}
        if(!create)throw new IllegalArgumentException("请先调用browser_open打开网页");if(sessions.size()>=4)throw new IllegalArgumentException("浏览器并发会话已满，请先关闭其他会话");
        if(playwright==null){playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));}
        if(browser==null||!browser.isConnected())browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setExecutablePath(runtime.browser().orElseThrow(()->new IllegalArgumentException("未找到Edge或Chrome，请先安装浏览器"))).setChromiumSandbox(true).setHeadless(true).setArgs(List.of("--proxy-bypass-list=<-loopback>","--force-webrtc-ip-handling-policy=disable_non_proxied_udp")));
        var proxy=new GuardedBrowserProxy(projects.previewOriginForRun(p,run));BrowserContext context=null;
        try{
            context=browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK).setProxy(new Proxy(proxy.address()).setBypass("<-loopback>")).setViewportSize(1280,800));
            context.setDefaultTimeout(12000);context.setDefaultNavigationTimeout(25000);Page page=context.newPage();
            context.onPage(popup->{if(popup!=page)popup.close();});page.onDownload(Download::cancel);
            session=new Session();session.owner=p;session.context=context;session.page=page;session.proxy=proxy;sessions.put(run,session);return session;
        }catch(Exception e){if(context!=null)context.close();proxy.close();throw e;}
    }
    public Map<String,Object> open(Principal p,UUID run,String url) throws Exception {
        GuardedBrowserProxy.validate(URI.create(url),projects.previewOriginForRun(p,run));
        return call(()->{var s=session(p,run,true);s.page.navigate(url,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));s.page.waitForTimeout(350);return snapshot(s);});
    }
    public Map<String,Object> snapshot(Principal p,UUID run) throws Exception{return call(()->snapshot(session(p,run,false)));}
    @SuppressWarnings("unchecked") private Map<String,Object> snapshot(Session session){
        session.touched=System.currentTimeMillis();
        // Stable DOM references are generated for visible elements; action tools never evaluate supplied JavaScript.
        var elements=(List<Map<String,Object>>)session.page.evaluate("""
                () => { const result=[];let id=0;for(const e of document.querySelectorAll('[data-agentops-ref]'))e.removeAttribute('data-agentops-ref');
                for(const e of document.querySelectorAll('a,button,input,textarea,select,[role=button]')) {
                  if(result.length>=35)break;if(!e.getBoundingClientRect().width||!e.getBoundingClientRect().height)continue;
                  const ref='e'+(++id);e.setAttribute('data-agentops-ref',ref);
                  result.push({ref,tag:e.tagName.toLowerCase(),text:(e.innerText||e.getAttribute('aria-label')||e.placeholder||e.name||'').slice(0,100),type:e.type||''});
                } return result; }
                """);
        String body=Objects.toString(session.page.locator("body").innerText(),"");return Map.of("url",session.page.url(),"title",session.page.title().substring(0,Math.min(120,session.page.title().length())),"text",body.substring(0,Math.min(1800,body.length())),"elements",elements);
    }
    public Map<String,Object> interact(Principal p,UUID run,String ref,String value,boolean click) throws Exception {
        if(!ref.matches("e[1-9][0-9]{0,2}"))throw new IllegalArgumentException("请使用最新快照中的元素ref");if(value!=null&&value.length()>4000)throw new IllegalArgumentException("填写内容过长");
        return call(()->{var s=session(p,run,false);var locator=s.page.locator("[data-agentops-ref=\""+ref+"\"]");if(locator.count()!=1)throw new IllegalArgumentException("元素已变化，请重新获取快照");
            if(click)locator.click(new Locator.ClickOptions().setTimeout(10000).setNoWaitAfter(true));else locator.fill(value,new Locator.FillOptions().setTimeout(10000));s.page.waitForTimeout(250);return snapshot(s);
        });
    }
    public Map<String,Object> screenshot(Principal p,UUID run) throws Exception {
        return call(()->{var s=session(p,run,false);var row=runs.findByIdOwner(p,run).orElseThrow();byte[] png=s.page.screenshot(new Page.ScreenshotOptions().setFullPage(false));var temp=artifacts.stagingPath(p.tenantId(),UUID.randomUUID(),".png");Files.write(temp,png);var artifact=artifacts.register(p,row.sessionId(),run,"browser-"+System.currentTimeMillis()+".png","image/png",temp,"output");return Map.of("objectId",artifact.objectId(),"url",s.page.url(),"title",s.page.title(),"message","真实浏览器截图已保存，可在对话中预览或下载");});
    }
    public void close(Principal p,UUID run) throws Exception{call(()->{runs.findByIdOwner(p,run).orElseThrow();close(run);return null;});}
    private void close(UUID run){var s=sessions.remove(run);if(s!=null){try{s.context.close();}finally{s.proxy.close();}}}
    @Scheduled(fixedDelay=60000) public void cleanup(){driver.submit(()->{for(var entry:new ArrayList<>(sessions.entrySet())){var s=entry.getValue();var row=runs.findByIdOwner(s.owner,entry.getKey());if(row.isEmpty()||row.get().status().terminal()||System.currentTimeMillis()-s.touched>900000)close(entry.getKey());}});}
    @PreDestroy public void stop(){try{call(()->{for(UUID id:new ArrayList<>(sessions.keySet()))close(id);if(browser!=null)browser.close();if(playwright!=null)playwright.close();return null;});}catch(Exception ignored){}driver.shutdownNow();}
}
