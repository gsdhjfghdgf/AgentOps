package com.agentops.event;

import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import com.agentops.persistence.StateRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunEventServiceTest {
    @AfterEach void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void subscribersOnlyReceiveCommittedEvents() {
        var service = new RunEventService(mock(StateRepo.class), new Json(), mock(RunRepo.class));
        var received = new ArrayList<Long>();
        service.subscribe((run, type, seq, payload) -> received.add(seq));
        TransactionSynchronizationManager.initSynchronization();
        service.persistAndDispatch(OWNER.tenantId(), OWNER.userId(), SESSION, RUN, 1, "run.succeeded", Map.of());
        assertThat(received).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(received).containsExactly(1L);
    }

    @Test void rollbackDoesNotAnnounceSuccess() {
        var service = new RunEventService(mock(StateRepo.class), new Json(), mock(RunRepo.class));
        var received = new ArrayList<Long>();
        service.subscribe((run, type, seq, payload) -> received.add(seq));
        TransactionSynchronizationManager.initSynchronization();
        service.persistAndDispatch(OWNER.tenantId(), OWNER.userId(), SESSION, RUN, 1, "run.succeeded", Map.of());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(received).isEmpty();
    }
}
