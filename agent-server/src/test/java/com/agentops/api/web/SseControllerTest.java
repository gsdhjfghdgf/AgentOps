package com.agentops.api.web;

import com.agentops.config.AgentProperties;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import com.agentops.persistence.StateRepo.EventRow;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.LongStream;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SseControllerTest {
    RunRepo runs;
    RunEventService events;
    SseController controller;
    MockMvc mvc;

    @BeforeEach void setup() {
        runs = mock(RunRepo.class); events = mock(RunEventService.class);
        var props = new AgentProperties(null, null, null, null, null, null, null,
                new AgentProperties.Events(50, 15));
        controller = new SseController(runs, events, props, new Json());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @AfterEach void close() { controller.shutdown(); }

    List<EventRow> page(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(seq -> new EventRow(RUN, seq, "message.delta",
                "{\"delta\":\"文字\",\"seq\":999,\"type\":\"spoofed\"}", NOW)).toList();
    }

    String streamRequest(String header, long query) throws Exception {
        var builder = get("/api/runs/{runId}/events", RUN)
                .header("X-Tenant-Id", OWNER.tenantId()).header("X-User-Id", OWNER.userId())
                .param("lastEventSeq", Long.toString(query));
        if (header != null) builder.header("Last-Event-ID", header);
        var pending = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void replaysEveryPageInOrderAndProtectsEnvelope() throws Exception {
        when(runs.findByIdOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.SUCCEEDED, 205, "hash")));
        when(events.replay(OWNER.tenantId(), RUN, 0, 100)).thenReturn(page(1, 100));
        when(events.replay(OWNER.tenantId(), RUN, 100, 100)).thenReturn(page(101, 200));
        when(events.replay(OWNER.tenantId(), RUN, 200, 100)).thenReturn(page(201, 205));
        var output = streamRequest(null, 0);
        assertThat(output).contains("id:1\n", "id:100\n", "id:101\n", "id:205\n", "event:run.snapshot")
                .doesNotContain("spoofed", "\"seq\":999");
        assertThat(output.indexOf("id:100\n")).isLessThan(output.indexOf("id:101\n"));
        assertThat(output.indexOf("id:200\n")).isLessThan(output.indexOf("id:201\n"));
    }

    @Test void automaticReconnectUsesHeaderInsteadOfOriginalUrlCursor() throws Exception {
        when(runs.findByIdOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.SUCCEEDED, 3, "hash")));
        when(events.replay(OWNER.tenantId(), RUN, 2, 100)).thenReturn(page(3, 3));
        assertThat(streamRequest("2", 0)).contains("id:3\n").doesNotContain("id:1\n", "id:2\n");
        verify(events, never()).replay(OWNER.tenantId(), RUN, 0, 100);
    }

    @Test void expiredCursorReturnsAuthoritativeSnapshot() throws Exception {
        when(runs.findByIdOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.SUCCEEDED, 50, "hash")));
        when(events.earliestSeq(OWNER.tenantId(), RUN)).thenReturn(40L);
        assertThat(streamRequest(null, 2)).contains("id:50\n", "\"reset\":true", "\"status\":\"succeeded\"");
        verify(events, never()).replay(OWNER.tenantId(), RUN, 2, 100);
    }

    @Test void terminalWithoutRemainingEventsStillSignalsCompletion() throws Exception {
        when(runs.findByIdOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.CANCELLED, 5, "hash")));
        assertThat(streamRequest("5", 0)).contains("event:run.snapshot", "\"status\":\"cancelled\"");
    }

    @Test void cursorRejectsInvalidOrNegativeValues() {
        for (String invalid : List.of("-1", "abc", "9223372036854775808")) {
            assertThatThrownBy(() -> SseController.cursor(invalid, 0)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(SseController.cursor("7", 3)).isEqualTo(7);
        assertThat(SseController.cursor(null, 3)).isEqualTo(3);
    }

    @Test void ownerIsCheckedBeforeOpeningStream() throws Exception {
        mvc.perform(get("/api/runs/{runId}/events", RUN)
                        .header("X-Tenant-Id", OWNER.tenantId()).header("X-User-Id", OWNER.userId()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(events);
    }
}
