package com.fanzuixianchang.ops;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded process-local aggregates. No user identity, request data or query strings. */
@Component
public final class OpsMetrics {
 private static final Set<String> PATHS=Set.of("/api/cases/analyze","/api/cases/estimate-meal","/api/cases/identify-foods","/api/activity/estimate","/api/foods","/api/nutrition/calculate","/api/goals/estimate","/api/feedback");
 private final Map<String,Long> estimateSources=new TreeMap<>();
 private final String since=Instant.now().toString();
 private final Map<String,Counter> counters=new TreeMap<>();
 private static final class Counter {long requests,successes,totalNanos,maxNanos;final Map<String,Long> statuses=new TreeMap<>();}
 public synchronized void record(String path,int status,long elapsedNanos){
  if(!PATHS.contains(path))return;
  var c=counters.computeIfAbsent(path,k->new Counter());long elapsed=Math.max(0,elapsedNanos);c.requests++;if(status<400)c.successes++;c.totalNanos+=elapsed;c.maxNanos=Math.max(c.maxNanos,elapsed);
  String key=status>=100&&status<=599?Integer.toString(status):"other";c.statuses.merge(key,1L,Long::sum);
 }
 public synchronized void recordEstimate(Map<String,Object> response){
  if(!(response.get("items") instanceof List<?> items))return;
  for(Object value:items)if(value instanceof Map<?,?> row&&row.get("estimate") instanceof Map<?,?> estimate&&estimate.get("source") instanceof Map<?,?> source&&source.get("kind") instanceof String kind&&Set.of("ai","catalog","label").contains(kind))estimateSources.merge(kind,1L,Long::sum);
 }
 public synchronized Map<String,Object> snapshot(){
  var routes=new ArrayList<Map<String,Object>>();
  for(String path:new TreeSet<>(PATHS)){var c=counters.getOrDefault(path,new Counter());routes.add(Map.of("path",path,"requests",c.requests,"successes",c.successes,"failures",c.requests-c.successes,"averageMs",c.requests==0?0.0:c.totalNanos/1_000_000.0/c.requests,"maxMs",c.maxNanos/1_000_000.0,"statusCounts",Map.copyOf(c.statuses)));}
  return Map.of("since",since,"scope","current-process","resetsOnRestart",true,"routes",List.copyOf(routes),"estimateSources",Map.copyOf(estimateSources));
 }
}
