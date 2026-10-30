package com.agentops.outbox;
import com.agentops.infra.Json;
import com.agentops.persistence.OutboxRepo;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    @Test void dispatcherUsesDurableOwnerRatherThanMissingOrForgedPayloadIdentity() {
        var repo=mock(OutboxRepo.class);
        var handler=mock(OutboxDispatcher.Handler.class);
        when(handler.type()).thenReturn("memory.extract");
        var payload="{\"runId\":\""+RUN+"\",\"userId\":\""+UUID.randomUUID()+"\"}";
        when(repo.claim(anyInt(),any(),any())).thenReturn(List.of(new OutboxRepo.OutboxRow(new UUID(0,10),OWNER.tenantId(),OWNER.userId(),"event","memory.extract",payload,0)));
        new OutboxDispatcher(repo,new Json(),List.of(handler)).dispatch();
        verify(handler).handle(eq(OWNER.tenantId()),eq(OWNER.userId()),eq("event"),anyMap());
    }
}
