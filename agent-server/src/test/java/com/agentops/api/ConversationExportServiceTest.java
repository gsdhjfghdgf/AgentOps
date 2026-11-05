package com.agentops.api;

import com.agentops.infra.Json;
import com.agentops.persistence.MessageRepo;
import com.agentops.domain.AgentDomain.MessageRole;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationExportServiceTest {
    final RunApplicationService app=mock(RunApplicationService.class);final MessageRepo messages=mock(MessageRepo.class);
    final ConversationExportService service=new ConversationExportService(app,messages,new Json());
    MessageRepo.MessageRow row(long seq,MessageRole role,String payload){return new MessageRepo.MessageRow(OWNER.tenantId(),OWNER.userId(),SESSION,seq,RUN,role,payload,1,null,null,NOW);}
    @Test void exportsEveryPageWithoutToolArgumentsOrInternalCommentary(){
        when(app.getSession(OWNER,SESSION)).thenReturn(new RunApplicationService.SessionView(SESSION.toString(),"测试会话",NOW,NOW,false,null,null));
        when(messages.listAfter(OWNER,SESSION,0,200)).thenReturn(List.of(row(1,MessageRole.USER,"{\"content\":\"第一个问题\"}"),row(2,MessageRole.TOOL,"{\"content\":\"secret-tool-key\"}"),row(3,MessageRole.ASSISTANT,"{\"kind\":\"commentary\",\"content\":\"internal-progress\"}")));
        when(messages.listAfter(OWNER,SESSION,3,200)).thenReturn(List.of(row(4,MessageRole.ASSISTANT,"{\"content\":\"最终回答\",\"toolCalls\":[]}")));
        var export=service.export(OWNER,SESSION);
        assertThat(export.filename()).isEqualTo("测试会话.md");assertThat(export.markdown()).contains("第一个问题","最终回答").doesNotContain("secret-tool-key","internal-progress");
    }
    @Test void foreignConversationIsRejectedBeforeReadingMessages(){
        when(app.getSession(OWNER,SESSION)).thenThrow(new RunApplicationService.NotFoundException("会话不存在"));
        assertThatThrownBy(()->service.export(OWNER,SESSION)).isInstanceOf(RunApplicationService.NotFoundException.class);verifyNoInteractions(messages);
    }
}
