package com.agentops.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded in-process vectors only; no raw text, document results or permissions are cached. */
final class EmbeddingCache {
    private record Entry(float[] vector, long createdAt) {}
    private final LinkedHashMap<String,Entry> values=new LinkedHashMap<>(16,.75f,true);
    private final ConcurrentHashMap<String,CompletableFuture<float[]>> pending=new ConcurrentHashMap<>();
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier ticker;
    EmbeddingCache(int capacity, Duration ttl) { this(capacity,ttl,System::nanoTime); }
    EmbeddingCache(int capacity, Duration ttl, LongSupplier ticker) {
        this.capacity=capacity;this.ttlNanos=ttl.toNanos();this.ticker=ticker;
    }
    float[] get(String key, Supplier<float[]> source) {
        synchronized(values) {
            var hit=values.get(key);
            if(hit!=null && ticker.getAsLong()-hit.createdAt()<ttlNanos) return hit.vector().clone();
            values.remove(key);
        }
        var candidate=new CompletableFuture<float[]>();
        var existing=pending.putIfAbsent(key,candidate);
        if(existing!=null) {
            try { return existing.join().clone(); }
            catch(CompletionException error) {
                if(error.getCause() instanceof RuntimeException cause) throw cause;
                throw error;
            }
        }
        try {
            // A previous computation may have finished between the initial cache check and putIfAbsent.
            synchronized(values) {
                var hit=values.get(key);
                if(hit!=null && ticker.getAsLong()-hit.createdAt()<ttlNanos) {
                    candidate.complete(hit.vector());
                    return hit.vector().clone();
                }
            }
            var vector=source.get().clone();
            synchronized(values) {
                values.put(key,new Entry(vector,ticker.getAsLong()));
                while(values.size()>capacity) values.remove(values.keySet().iterator().next());
            }
            candidate.complete(vector);
            return vector.clone();
        } catch(RuntimeException|Error error) {
            candidate.completeExceptionally(error); throw error;
        } finally { pending.remove(key,candidate); }
    }
}
