package com.agentops.filetools;

import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import com.agentops.tool.PublicWebClient;
import com.agentops.tool.ToolRegistry;
import com.agentops.tool.ToolRegistry.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageAssetToolsTest {
    @TempDir Path directory;
    final PublicWebClient web=mock(PublicWebClient.class);final FileToolSupport files=mock(FileToolSupport.class);final Json json=new Json();
    final ToolRegistry registry=new ToolRegistry();final ImageAssetTools tools=new ImageAssetTools(registry,web,files,json);
    final String id="00000000-0000-0000-0000-000000000012";
    String details(String license){return json.write(Map.of("id",id,"url","https://images.example/photo.jpg","license",license,"creator","来源作者","attribution","<b>署名信息</b>"));}

    @Test void imagesShareNetworkApprovalAndDownloadingRemainsBuildOnly(){
        tools.register();assertThat(registry.spec("image_search").orElseThrow().requiresApproval()).isTrue();
        assertThat(registry.spec("image_download").orElseThrow().requiresApproval()).isTrue();
        assertThat(com.agentops.persistence.RunRepo.isPublicWebTool("image_search")).isTrue();
        assertThat(com.agentops.persistence.RunRepo.isPublicWebTool("image_download")).isTrue();
        assertThat(com.agentops.domain.RunMode.PLAN.allows("image_download")).isFalse();
    }
    @Test void searchKeepsAttributionAndFiltersUnsupportedLicenses()throws Exception{
        when(web.get(anyString(),anyInt())).thenReturn(new PublicWebClient.Page("https://api.openverse.org", "{\"results\":["+details("by")+","+details("by-nc")+"]}","application/json"));
        var result=tools.search("robotics");assertThat((List<?>)result.get("images")).hasSize(1);assertThat(json.write(result)).contains("来源作者","署名信息").doesNotContain("<b>","by-nc");
    }
    @Test void downloadRechecksMetadataAndSavesARealDecodedPng()throws Exception{
        when(web.get(anyString(),anyInt())).thenReturn(new PublicWebClient.Page("https://api.openverse.org",details("by"),"application/json"));
        var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(900,500,BufferedImage.TYPE_INT_RGB),"jpg",image);
        when(web.downloadImage(anyString(),anyInt())).thenReturn(new PublicWebClient.Resource("https://images.example/photo.jpg",image.toByteArray(),"image/jpeg"));
        var saved=directory.resolve("saved.png");when(files.output(eq(OWNER),any(),eq("配图.png"),any())).thenAnswer(invocation->{((FileToolSupport.Writer)invocation.getArgument(3)).write(saved);return new ArtifactRef(id,"配图.png","image/png",Files.size(saved),"test");});
        var result=tools.download(OWNER,new ToolContext(RUN,UUID.randomUUID(),"test",null),id,"配图");
        assertThat(Files.readAllBytes(saved)).startsWith((byte)137,(byte)80,(byte)78,(byte)71);assertThat(ImageIO.read(saved.toFile()).getWidth()).isEqualTo(900);
        assertThat(json.write(result)).contains("image/png","署名信息","https://images.example/photo.jpg");
    }
    @Test void unsupportedLicenseAndActivePayloadCannotBecomeImageArtifacts()throws Exception{
        when(web.get(anyString(),anyInt())).thenReturn(new PublicWebClient.Page("https://api.openverse.org",details("by-nc"),"application/json"));
        assertThatThrownBy(()->tools.download(OWNER,new ToolContext(RUN,UUID.randomUUID(),"test",null),id,"配图")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(files);verify(web,never()).downloadImage(anyString(),anyInt());
        assertThatThrownBy(()->new PublicWebClient().downloadImage("http://127.0.0.1/secrets",1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
