package com.fanzuixianchang.core;

import java.time.*;
import java.util.*;

/** Bounded single-instance limiter. Distributed quotas belong in the production gateway. */
public final class WindowLimiter {
    private record Window(long started,int count){}
    private final int limit;private final long millis;private final Clock clock;
    private final Map<String,Window> windows=new HashMap<>();
    public WindowLimiter(int limit,Duration period,Clock clock){if(limit<1||period.isNegative()||period.isZero())throw new IllegalArgumentException();this.limit=limit;this.millis=period.toMillis();this.clock=clock;}
    public synchronized boolean allow(String key){
        long now=clock.millis();
        Window old=windows.get(key);
        if(old==null||now-old.started()>=millis){
            if(windows.size()>=2048){windows.entrySet().removeIf(e->now-e.getValue().started()>=millis);if(windows.size()>=2048&&old==null)return false;}
            windows.put(key,new Window(now,1));return true;
        }
        if(old.count()>=limit)return false;
        windows.put(key,new Window(old.started(),old.count()+1));return true;
    }
}
