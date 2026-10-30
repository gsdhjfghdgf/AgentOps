package com.agentops.model;

import com.agentops.api.RunApplicationService.BadRequestException;
import com.agentops.config.AgentProperties;
import com.agentops.config.ModelKeyCipher;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.infra.Json;
import com.agentops.persistence.ModelSettingsRepo;
import com.agentops.persistence.RunRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

@Service
public class ModelSettingsService {
    public record ChatConfig(String profileId,String provider,String url,String model,String key) {
        public boolean isMock(){return "mock".equals(provider);}
        @Override public String toString(){return "ChatConfig[profileId="+profileId+", model="+model+"]";}
    }
    public record Update(String url,String apiKey,String model,boolean clearKey) {
        @Override public String toString(){return "ModelSettingsUpdate[model="+model+"]";}
    }
    public record ProfileUpdate(String name,String url,String apiKey,String model,boolean clearKey,
                                UUID versionId,boolean activate) {
        public Update settings(){return new Update(url,apiKey,model,clearKey);}
        @Override public String toString(){return "ProfileUpdate[name="+name+", model="+model+"]";}
    }
    private final ModelSettingsRepo repo;
    private final ModelKeyCipher cipher;
    private final AgentProperties props;
    private final RunRepo runs;
    private final Json json;
    public ModelSettingsService(ModelSettingsRepo repo,ModelKeyCipher cipher,AgentProperties props,RunRepo runs,Json json) {
        this.repo=repo;this.cipher=cipher;this.props=props;this.runs=runs;this.json=json;
    }
    public static ChatConfig defaults(AgentProperties props) {
        var m=props.model();
        return new ChatConfig("default",m.isMock()?"mock":"openai",m.isMock()?"":m.chatEndpoint(),m.chatModel(),m.apiKey()==null?"":m.apiKey());
    }
    public ChatConfig current(Principal owner){return repo.active(owner).map(p->decode(owner,p)).orElseGet(()->defaults(props));}
    public ChatConfig saved(Principal owner,UUID id) {
        var entry=ownedEntry(owner,id,false);
        return decode(owner,repo.find(owner,entry.profileId()).orElseThrow());
    }
    private ChatConfig decode(Principal owner,ModelSettingsRepo.Profile p) {
        return new ChatConfig(p.id().toString(),"openai",p.url(),p.model(),cipher.decrypt(p.keyCipher(),aad(owner,p.id())));
    }
    public ChatConfig forRun(RunSnapshot snapshot) {
        var owner=snapshot.key().owner();
        var row=runs.findByIdOwner(owner,snapshot.key().runId()).orElseThrow();
        String id=json.tree(row.configSnapshotJson()).path("chatProfileId").asText("default");
        return "default".equals(id)?defaults(props):repo.find(owner,UUID.fromString(id)).map(p->decode(owner,p))
                .orElseThrow(()->new IllegalStateException("任务的模型配置不存在"));
    }
    public Map<String,Object> view(Principal owner) {
        var active=repo.active(owner);
        var c=active.map(p->new ChatConfig(p.id().toString(),"openai",p.url(),p.model(),"")).orElseGet(()->defaults(props));
        var keyStatus=active.map(p->keyStatus(owner,p)).orElseGet(()->new KeyStatus(!c.key().isBlank(),false));
        var profiles=repo.catalog(owner);
        var selected=profiles.stream().filter(p->p.profile().id().toString().equals(c.profileId())).findFirst();
        var result=new LinkedHashMap<String,Object>();
        result.put("url",c.url());result.put("model",c.model());result.put("keyConfigured",keyStatus.configured());
        result.put("keyUnavailable",keyStatus.unavailable());
        result.put("custom",!"default".equals(c.profileId()));result.put("provider",c.provider());
        result.put("profileId",c.profileId());
        result.put("selectedProfileId",selected.map(p->p.entry().id().toString()).orElse("default"));
        result.put("profiles",profiles.stream().map(p->profileView(owner,p)).toList());
        var system=defaults(props);
        result.put("defaultProfile",Map.of("id","default","name","系统默认","model",system.model(),"url",system.url(),
                "keyConfigured",!system.key().isBlank(),"provider",system.provider()));
        return result;
    }
    private Map<String,Object> profileView(Principal owner,ModelSettingsRepo.SavedEntry saved) {
        var p=saved.profile();var status=keyStatus(owner,p);
        return Map.of("id",saved.entry().id().toString(),"versionId",saved.profile().id().toString(),
                "name",saved.entry().name(),"url",p.url(),"model",p.model(),
                "keyConfigured",status.configured(),"keyUnavailable",status.unavailable());
    }
    private record KeyStatus(boolean configured,boolean unavailable) {}
    private KeyStatus keyStatus(Principal owner,ModelSettingsRepo.Profile profile) {
        try{return new KeyStatus(!decode(owner,profile).key().isBlank(),false);}
        catch(IllegalStateException e){return new KeyStatus(false,true);}
    }
    private boolean inheritsKey(Update update) {
        return update!=null && !update.clearKey() && (update.apiKey()==null || update.apiKey().isBlank());
    }
    public ChatConfig draft(Principal owner,Update update) {
        return draft(owner,update,inheritsKey(update)?current(owner):null);
    }
    private ChatConfig draft(Principal owner,Update update,ChatConfig previous) {
        if(update==null)throw new BadRequestException("请提供模型配置");
        String url=normalizeUrl(update.url());
        String model=update.model()==null?"":update.model().strip();
        if(model.isEmpty() || model.length()>200 || model.chars().anyMatch(Character::isWhitespace)
                || model.chars().anyMatch(Character::isISOControl))throw new BadRequestException("请输入有效模型名（最多200字符，不含空白）");
        String key=update.apiKey()==null?"":update.apiKey().strip();
        if(key.length()>8192 || key.chars().anyMatch(Character::isISOControl))throw new BadRequestException("Key 格式无效");
        if(update.clearKey())key="";
        else if(key.isEmpty()) {
            var old=previous;
            if(old==null)throw new BadRequestException("请输入此配置的 Key，或选择此服务无需 Key");
            if(!old.isMock() && url.equals(normalizeUrl(old.url())))key=old.key();
            else throw new BadRequestException("地址已改变，请重新输入 Key，或选择此服务无需 Key");
        }
        return new ChatConfig("draft","openai",url,model,key);
    }
    @Transactional
    public Map<String,Object> save(Principal owner,Update update) {
        var c=draft(owner,update);var id=UUID.randomUUID();
        repo.save(owner,new ModelSettingsRepo.Profile(id,c.url(),c.model(),cipher.encrypt(c.key(),aad(owner,id))));
        repo.createEntry(owner,new ModelSettingsRepo.Entry(id,c.model().substring(0,Math.min(100,c.model().length())),id));
        return view(owner);
    }
    public ChatConfig profileDraft(Principal owner,UUID id,ProfileUpdate update) {
        if(update==null)throw new BadRequestException("请填写模型配置");
        ChatConfig previous=null;
        if(id!=null) {
            var entry=ownedEntry(owner,id,false);
            checkVersion(entry,update);
            if(inheritsKey(update.settings()))previous=decode(owner,repo.find(owner,entry.profileId()).orElseThrow());
        }
        return draft(owner,update.settings(),previous);
    }
    @Transactional
    public Map<String,Object> createProfile(Principal owner,ProfileUpdate update) {
        if(repo.list(owner).size()>=50)throw new BadRequestException("最多保存50个模型配置");
        String name=name(update);var c=profileDraft(owner,null,update);
        UUID entry=UUID.randomUUID(),version=UUID.randomUUID();
        repo.insert(owner,new ModelSettingsRepo.Profile(version,c.url(),c.model(),cipher.encrypt(c.key(),aad(owner,version))));
        repo.createEntry(owner,new ModelSettingsRepo.Entry(entry,name,version));
        if(update.activate())repo.selectVersion(owner,version);
        var result=new LinkedHashMap<>(view(owner));result.put("savedProfileId",entry.toString());return result;
    }
    @Transactional
    public Map<String,Object> updateProfile(Principal owner,UUID id,ProfileUpdate update) {
        var entry=ownedEntry(owner,id,true);checkVersion(entry,update);
        String name=name(update);
        var old=inheritsKey(update.settings())?decode(owner,repo.find(owner,entry.profileId()).orElseThrow()):null;
        var c=draft(owner,update.settings(),old);UUID version=UUID.randomUUID();
        repo.insert(owner,new ModelSettingsRepo.Profile(version,c.url(),c.model(),cipher.encrypt(c.key(),aad(owner,version))));
        repo.updateEntry(owner,entry,name,version);
        if(update.activate())repo.selectVersion(owner,version);
        var result=new LinkedHashMap<>(view(owner));result.put("savedProfileId",id.toString());return result;
    }
    @Transactional
    public Map<String,Object> selectProfile(Principal owner,String id) {
        if(id==null || id.isBlank())throw new BadRequestException("请选择模型配置");
        if("default".equals(id))repo.reset(owner);
        else {
            var entry=ownedEntry(owner,UUID.fromString(id),true);
            if(keyStatus(owner,repo.find(owner,entry.profileId()).orElseThrow()).unavailable())
                throw new BadRequestException("此配置的密钥无法读取，请在管理模型中重新填写 Key");
            repo.selectVersion(owner,entry.profileId());
        }
        return view(owner);
    }
    @Transactional
    public Map<String,Object> deleteProfile(Principal owner,UUID id) {
        repo.removeEntry(owner,ownedEntry(owner,id,true));
        return view(owner);
    }
    private ModelSettingsRepo.Entry ownedEntry(Principal owner,UUID id,boolean lock) {
        return (lock?repo.lockEntry(owner,id):repo.entry(owner,id))
                .orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("模型配置不存在"));
    }
    private void checkVersion(ModelSettingsRepo.Entry entry,ProfileUpdate update) {
        if(update==null)throw new BadRequestException("请填写模型配置");
        if(update.versionId()!=null && !update.versionId().equals(entry.profileId()))
            throw new com.agentops.api.RunApplicationService.ConflictException("配置已在其他窗口更新，请重新打开后编辑");
    }
    private String name(ProfileUpdate update) {
        if(update==null || update.name()==null || update.name().isBlank() || update.name().strip().length()>100
                || update.name().chars().anyMatch(Character::isISOControl))throw new BadRequestException("请填写1～100字的配置名称");
        return update.name().strip();
    }
    @Transactional public void reset(Principal owner){repo.reset(owner);}
    private static String aad(Principal owner,UUID id){return owner.tenantId()+":"+owner.userId()+":"+id;}
    public static String normalizeUrl(String input) {
        try {
            if(input==null || input.isBlank() || input.length()>2048 || input.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            var uri=URI.create(input.strip());
            if(!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();
            String url=uri.toString().replaceAll("/+$", "");
            if(url.endsWith("/chat/completions"))return url;
            return url+(uri.getPath()==null || uri.getPath().isBlank() || "/".equals(uri.getPath())?"/v1":"")+"/chat/completions";
        } catch(IllegalArgumentException e){throw new BadRequestException("URL 须为 HTTP/HTTPS 服务地址，不含账号、查询参数或片段");}
    }
}
