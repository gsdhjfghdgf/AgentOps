package com.agentops.model;

import com.agentops.config.AgentProperties.StreamTimeouts;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Monotonic deadlines; tool-argument fragments and SSE heartbeats also count as activity. */
final class StreamTimeoutBudget {
    enum Expired { FIRST_BYTE, IDLE, TOTAL }

    private final StreamTimeouts limits;
    private final LongSupplier nanoTime;
    private final long started;
    private volatile long lastByte;
    private volatile boolean received;

    StreamTimeoutBudget(StreamTimeouts limits, LongSupplier nanoTime) {
        this.limits = limits;
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
        this.lastByte = started;
    }

    void receivedBytes(int count) {
        if (count > 0) {
            lastByte = nanoTime.getAsLong();
            received = true;
        }
    }

    Expired expired() {
        long now = nanoTime.getAsLong();
        if (now - started >= TimeUnit.SECONDS.toNanos(limits.maxDurationSeconds())) return Expired.TOTAL;
        if (!received) return now - started >= TimeUnit.SECONDS.toNanos(limits.firstByteSeconds()) ? Expired.FIRST_BYTE : null;
        return now - lastByte >= TimeUnit.SECONDS.toNanos(limits.idleSeconds()) ? Expired.IDLE : null;
    }

    long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - started); }
}
