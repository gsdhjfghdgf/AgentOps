package com.agentops.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class EmbeddingCacheTest {
    @Test void cachedVectorsAreClonedAndExpireAndEvictWithoutCachingFailures() {
        var time=new AtomicLong();
        var cache=new EmbeddingCache(1,Duration.ofNanos(100),time::get);
        var calls=new AtomicInteger();
        java.util.function.Supplier<float[]> source=()->{calls.incrementAndGet();return new float[]{1,2};};
        var first=cache.get("a",source);first[0]=99;
        assertThat(cache.get("a",source)).containsExactly(1,2);
        assertThat(calls).hasValue(1);
        time.set(101);cache.get("a",source);assertThat(calls).hasValue(2);
        cache.get("b",source);cache.get("a",source);assertThat(calls).hasValue(4);
        assertThatThrownBy(()->cache.get("fail",()->{throw new IllegalStateException("offline");})).hasMessage("offline");
        assertThat(cache.get("fail",source)).containsExactly(1,2);
    }
    @Test void concurrentIdenticalQueriesUseOneProviderCall() throws Exception {
        var cache=new EmbeddingCache(4,Duration.ofMinutes(1));var calls=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->cache.get("q",()->{
                calls.incrementAndGet();entered.countDown();
                try { if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("test timeout"); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return new float[]{3};
            }));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->cache.get("q",()->{calls.incrementAndGet();return new float[]{3};}));
            release.countDown();
            assertThat(first.get(2,TimeUnit.SECONDS)).containsExactly(3);
            assertThat(second.get(2,TimeUnit.SECONDS)).containsExactly(3);
            assertThat(calls).hasValue(1);
        } finally { pool.shutdownNow(); }
    }
}
