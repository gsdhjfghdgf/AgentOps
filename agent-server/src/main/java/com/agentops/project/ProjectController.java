package com.agentops.project;
import com.agentops.api.web.ApiController;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
@RestController @RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;
    public ProjectController(ProjectService service){this.service=service;}
    public record Attach(String name,String directory) {}
    public record Selection(UUID projectId) {}
    public record Preview(String origin) {}
    @GetMapping public Map<String,Object> list(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user){var p=ApiController.principal(tenant,user);var map=new LinkedHashMap<String,Object>();map.put("projects",service.repository().list(p));map.put("selected",service.repository().selected(p).orElse(null));return map;}
    @PostMapping public ProjectRepo.Project attach(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody Attach input) throws Exception{return service.attach(ApiController.principal(tenant,user),input.name(),input.directory());}
    @PutMapping("/selection") public Map<String,Object> select(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@RequestBody Selection input){service.repository().select(ApiController.principal(tenant,user),input.projectId());return Map.of("ok",true);}
    @PutMapping("/{id}/preview-origin") public Map<String,Object> preview(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id,@RequestBody Preview input){
        String origin=input.origin()==null?"":input.origin().strip();if(!origin.isBlank()){
            URI uri=URI.create(origin);if(!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost").contains(uri.getHost())||uri.getPort()<1024||uri.getPort()>65535||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath()))throw new IllegalArgumentException("预览地址只能是本机开发服务，例如http://127.0.0.1:5173");
            origin="http://"+uri.getHost()+":"+uri.getPort();
        }service.repository().previewOrigin(ApiController.principal(tenant,user),id,origin);return Map.of("ok",true);
    }
    @GetMapping("/{id}/git") public Map<String,Object> git(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id,@RequestParam(defaultValue="false") boolean diff) throws Exception{return service.git(ApiController.principal(tenant,user),id,diff);}
    @GetMapping("/{id}/changes") public Map<String,Object> changes(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id){return Map.of("changes",service.repository().changes(ApiController.principal(tenant,user),id).stream().map(c->Map.of("id",c.id(),"path",c.path(),"status",c.status(),"createdAt",c.createdAt())).toList());}
    @PostMapping("/{id}/changes/{change}/undo") public Map<String,Object> undo(@RequestHeader("X-Tenant-Id") String tenant,@RequestHeader("X-User-Id") String user,@PathVariable UUID id,@PathVariable UUID change) throws Exception{service.undo(ApiController.principal(tenant,user),id,change);return Map.of("ok",true);}
}
