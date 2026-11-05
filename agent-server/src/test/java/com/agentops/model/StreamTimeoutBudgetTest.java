package com.agentops.model;

import com.agentops.config.AgentProperties.StreamTimeouts;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class StreamTimeoutBudgetTest {
    final AtomicLong nanos = new AtomicLong();
    final StreamTimeoutBudget budget = new StreamTimeoutBudget(new StreamTimeouts(120, 90, 600), nanos::get);
    void at(long seconds) { nanos.set(TimeUnit.SECONDS.toNanos(seconds)); }

    @Test void firstByteHasItsOwnDeadlineInsteadOfTheOldSixtySecondLimit() {
        at(61); assertThat(budget.expired()).isNull();
        at(120); assertThat(budget.expired()).isEqualTo(StreamTimeoutBudget.Expired.FIRST_BYTE);
    }
    @Test void rawFragmentsRefreshIdleTimeDuringLongToolArguments() {
        for (int second = 60; second <= 540; second += 60) {
            at(second); budget.receivedBytes(3);
            assertThat(budget.expired()).isNull();
        }
        at(599); assertThat(budget.expired()).isNull();
    }
    @Test void anEmptyReadOrEofCannotKeepAStalledStreamAlive() {
        at(10); budget.receivedBytes(1);
        at(80); budget.receivedBytes(0); budget.receivedBytes(-1);
        at(100); assertThat(budget.expired()).isEqualTo(StreamTimeoutBudget.Expired.IDLE);
    }
    @Test void heartbeatsCannotExtendTheAbsoluteRequestLimit() {
        for (int second = 60; second <= 600; second += 60) { at(second); budget.receivedBytes(2); }
        assertThat(budget.expired()).isEqualTo(StreamTimeoutBudget.Expired.TOTAL);
        assertThat(budget.elapsedMillis()).isEqualTo(600000);
    }
    @Test void invalidTimeoutsFailFast() {
        assertThatThrownBy(() -> new StreamTimeouts(0, 90, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamTimeouts(120, -1, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamTimeouts(120, 90, 0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nestedTransportTimeoutsAreDistinguishedFromConnectionFailures() {
        assertThat(OpenAiChatClient.isTimeout(new RuntimeException(new java.net.http.HttpTimeoutException("fixture")))).isTrue();
        assertThat(OpenAiChatClient.isTimeout(new RuntimeException(new java.net.SocketTimeoutException("fixture")))).isTrue();
        assertThat(OpenAiChatClient.isTimeout(new java.net.ConnectException("fixture"))).isFalse();
        assertThat(OpenAiChatClient.isTimeout(new java.io.InterruptedIOException("fixture"))).isFalse();
    }
}
