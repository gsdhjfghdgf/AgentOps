package com.agentops.model;

import com.agentops.api.RunApplicationService.ConflictException;
import com.agentops.api.RunApplicationService.NotFoundException;
import com.agentops.config.AgentProperties;
import com.agentops.config.ModelKeyCipher;
import com.agentops.domain.Primitives.RunKey;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.infra.Json;
import com.agentops.persistence.ModelSettingsRepo;
import com.agentops.persistence.RunRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedModelProfilesTest {
    @TempDir Path directory;
    ModelSettingsRepo repo;
    RunRepo runs;
    ModelKeyCipher cipher;
    ModelSettingsService service;
    final UUID entryA=new UUID(1,1),entryB=new UUID(1,2),versionA=new UUID(2,1),versionB=new UUID(2,2);
    @BeforeEach void setup() {
        repo=mock(ModelSettingsRepo.class);runs=mock(RunRepo.class);
        cipher=new ModelKeyCipher(directory.resolve("key").toString());
        var props=new AgentProperties(new AgentProperties.Model("openai","https://default.test/v1",
                "system-secret","default-model","fixed-vector",1024,30,1),null,null,null,null,null,null,null);
        service=new ModelSettingsService(repo,cipher,props,runs,new Json());
    }
    ModelSettingsRepo.Profile profile(UUID version,String key) {
        return new ModelSettingsRepo.Profile(version,"https://same.test/v1/chat/completions","model-"+version.getLeastSignificantBits(),
                cipher.encrypt(key,OWNER.tenantId()+":"+OWNER.userId()+":"+version));
    }
    ModelSettingsService.ProfileUpdate update(String key,UUID version,boolean activate) {
        return new ModelSettingsService.ProfileUpdate("代码助手","https://same.test/v1",key,"code-model",false,version,activate);
    }
    @Test void catalogContainsBothModelsAndSelectionWithoutReturningSecrets() {
        var a=profile(versionA,"secret-a");var b=profile(versionB,"secret-b");
        when(repo.active(OWNER)).thenReturn(Optional.of(b));
        when(repo.catalog(OWNER)).thenReturn(List.of(
                new ModelSettingsRepo.SavedEntry(new ModelSettingsRepo.Entry(entryA,"日常聊天",versionA),a),
                new ModelSettingsRepo.SavedEntry(new ModelSettingsRepo.Entry(entryB,"代码助手",versionB),b)));
        var view=service.view(OWNER);
        assertThat(view.get("selectedProfileId")).isEqualTo(entryB.toString());
        assertThat((List<?>)view.get("profiles")).hasSize(2);
        assertThat(new Json().write(view)).contains("日常聊天","代码助手").doesNotContain("secret-a","secret-b","system-secret","keyCipher","apiKey");
    }
    @Test void testingInactiveEntryUsesItsOwnKeyEvenWhenBothServicesHaveSameUrl() {
        when(repo.entry(OWNER,entryA)).thenReturn(Optional.of(new ModelSettingsRepo.Entry(entryA,"A",versionA)));
        when(repo.find(OWNER,versionA)).thenReturn(Optional.of(profile(versionA,"secret-a")));
        assertThat(service.profileDraft(OWNER,entryA,update("",versionA,false)).key()).isEqualTo("secret-a");
        verify(repo,never()).active(any());
    }
    @Test void newEntryNeverInheritsAnotherModelsKey() {
        assertThatThrownBy(()->service.profileDraft(OWNER,null,update("",null,false))).hasMessageContaining("此配置的 Key");
        verify(repo,never()).active(any());
    }
    @Test void creatingInactiveEntryPreservesSelectionAndEncryptsItsOwnSecret() {
        service.createProfile(OWNER,update("secret-a",null,false));
        var saved=ArgumentCaptor.forClass(ModelSettingsRepo.Profile.class);
        verify(repo).insert(eq(OWNER),saved.capture());
        assertThat(saved.getValue().keyCipher()).doesNotContain("secret-a");
        assertThat(cipher.decrypt(saved.getValue().keyCipher(),OWNER.tenantId()+":"+OWNER.userId()+":"+saved.getValue().id())).isEqualTo("secret-a");
        verify(repo,never()).selectVersion(any(),any());
        verify(repo).createEntry(eq(OWNER),argThat(e->e.name().equals("代码助手") && e.profileId().equals(saved.getValue().id())));
    }
    @Test void unreadableSavedKeyDoesNotHideOtherModelsAndCanBeReplaced() {
        var broken=new ModelSettingsRepo.Profile(versionA,"https://same.test/v1/chat/completions","model-a","broken");
        var entry=new ModelSettingsRepo.Entry(entryA,"A",versionA);
        when(repo.active(OWNER)).thenReturn(Optional.of(broken));
        when(repo.catalog(OWNER)).thenReturn(List.of(new ModelSettingsRepo.SavedEntry(entry,broken),
                new ModelSettingsRepo.SavedEntry(new ModelSettingsRepo.Entry(entryB,"B",versionB),profile(versionB,"good-key"))));
        var view=service.view(OWNER);
        assertThat(view.get("keyUnavailable")).isEqualTo(true);assertThat((List<?>)view.get("profiles")).hasSize(2);
        when(repo.lockEntry(OWNER,entryA)).thenReturn(Optional.of(entry));
        service.updateProfile(OWNER,entryA,update("replacement-key",versionA,false));
        var saved=ArgumentCaptor.forClass(ModelSettingsRepo.Profile.class);verify(repo).insert(eq(OWNER),saved.capture());
        assertThat(cipher.decrypt(saved.getValue().keyCipher(),OWNER.tenantId()+":"+OWNER.userId()+":"+saved.getValue().id())).isEqualTo("replacement-key");
    }
    @Test void editingCreatesImmutableRevisionAndKeepsOldProfileReadable() {
        var entry=new ModelSettingsRepo.Entry(entryA,"A",versionA);
        when(repo.lockEntry(OWNER,entryA)).thenReturn(Optional.of(entry));
        when(repo.find(OWNER,versionA)).thenReturn(Optional.of(profile(versionA,"secret-a")));
        service.updateProfile(OWNER,entryA,update("",versionA,false));
        var saved=ArgumentCaptor.forClass(ModelSettingsRepo.Profile.class);
        verify(repo).insert(eq(OWNER),saved.capture());
        assertThat(saved.getValue().id()).isNotEqualTo(versionA);
        assertThat(cipher.decrypt(saved.getValue().keyCipher(),OWNER.tenantId()+":"+OWNER.userId()+":"+saved.getValue().id())).isEqualTo("secret-a");
        verify(repo).updateEntry(OWNER,entry,"代码助手",saved.getValue().id());
        verify(repo,never()).selectVersion(any(),any());
        assertThat(repo.find(OWNER,versionA)).isPresent();
    }
    @Test void staleEditorCannotOverwriteNewerRevision() {
        when(repo.lockEntry(OWNER,entryA)).thenReturn(Optional.of(new ModelSettingsRepo.Entry(entryA,"A",versionB)));
        assertThatThrownBy(()->service.updateProfile(OWNER,entryA,update("replacement",versionA,false))).isInstanceOf(ConflictException.class);
        verify(repo,never()).insert(any(),any());verify(repo,never()).updateEntry(any(),any(),any(),any());
    }
    @Test void selectingModelPersistsItsRevisionAndDefaultDoesNotDeleteCatalog() {
        when(repo.lockEntry(OWNER,entryA)).thenReturn(Optional.of(new ModelSettingsRepo.Entry(entryA,"A",versionA)));
        when(repo.find(OWNER,versionA)).thenReturn(Optional.of(profile(versionA,"secret-a")));
        service.selectProfile(OWNER,entryA.toString());verify(repo).selectVersion(OWNER,versionA);
        service.selectProfile(OWNER,"default");verify(repo).reset(OWNER);
        verify(repo,never()).removeEntry(any(),any());
    }
    @Test void foreignOrDeletedEntryCannotBeSelectedEditedTestedOrDeleted() {
        assertThatThrownBy(()->service.selectProfile(OWNER,entryA.toString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.updateProfile(OWNER,entryA,update("secret",null,true))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.profileDraft(OWNER,entryA,update("secret",null,true))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.deleteProfile(OWNER,entryA)).isInstanceOf(NotFoundException.class);
        verify(repo,never()).selectVersion(any(),any());verify(repo,never()).insert(any(),any());
    }
    @Test void runningTaskResolvesPinnedVersionInsteadOfCurrentlySelectedEntry() {
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{\"chatProfileId\":\""+versionA+"\"}")));
        when(repo.find(OWNER,versionA)).thenReturn(Optional.of(profile(versionA,"secret-a")));
        var snapshot=mock(RunSnapshot.class);when(snapshot.key()).thenReturn(new RunKey(OWNER,SESSION,RUN));
        var config=service.forRun(snapshot);
        assertThat(config.profileId()).isEqualTo(versionA.toString());assertThat(config.key()).isEqualTo("secret-a");
        verify(repo,never()).active(any());verify(repo,never()).catalog(any());
    }
}
