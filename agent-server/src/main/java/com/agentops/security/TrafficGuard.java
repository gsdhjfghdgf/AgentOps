package com.agentops.security;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** Bounded local burst control; password attempts additionally use durable database counters. */
@Component
public class TrafficGuard {
    private final Clock clock;private final Map<String,Window> windows=new HashMap<>();
    private final Map<UUID,Integer> streams=new HashMap<>();private int activeStreams;
    private record Window(long start,int count){}
    public TrafficGuard(Clock clock){this.clock=clock;}
    public synchronized boolean allow(String scope,int maximum){
        long now=clock.millis();Window previous=windows.get(scope);
        if(previous!=null&&now-previous.start()<60000){if(previous.count()>=maximum)return false;windows.put(scope,new Window(previous.start(),previous.count()+1));return true;}
        if(windows.size()>=10000){windows.entrySet().removeIf(e->now-e.getValue().start()>=60000);if(windows.size()>=10000&&!windows.containsKey(scope))return false;}
        windows.put(scope,new Window(now,1));return true;
    }
    public synchronized StreamLease acquireStream(UUID account){
        if(activeStreams>=64||streams.getOrDefault(account,0)>=4)return null;
        activeStreams++;streams.merge(account,1,Integer::sum);return new StreamLease(account);
    }
    public final class StreamLease implements AutoCloseable {
        private final UUID account;private boolean released;
        private StreamLease(UUID account){this.account=account;}
        @Override public void close(){synchronized(TrafficGuard.this){if(released)return;released=true;activeStreams--;int remaining=streams.getOrDefault(account,1)-1;if(remaining<=0)streams.remove(account);else streams.put(account,remaining);}}
    }
}
