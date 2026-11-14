package com.fanzuixianchang.ops;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OpsMetricsTest {
 @Test void aggregatesLatencyAndStatusWithoutUnboundedPaths(){
  var metrics=new OpsMetrics();metrics.record("/api/cases/analyze",200,2_000_000);metrics.record("/api/cases/analyze",503,6_000_000);metrics.record("/api/cases/analyze?private=input",200,1);metrics.record("/api/session",401,1);metrics.record("/api/ops/content",200,1);
  var result=metrics.snapshot();assertEquals("current-process",result.get("scope"));assertEquals(true,result.get("resetsOnRestart"));
  var routes=(List<Map<String,Object>>)result.get("routes");assertEquals(6,routes.size());var row=routes.stream().filter(r->r.get("path").equals("/api/cases/analyze")).findFirst().orElseThrow();assertEquals(2L,row.get("requests"));assertEquals(1L,row.get("failures"));assertEquals(4.0,row.get("averageMs"));assertEquals(6.0,row.get("maxMs"));assertEquals(Map.of("200",1L,"503",1L),row.get("statusCounts"));
  metrics.record("/api/cases/analyze",400,0);assertEquals(2L,row.get("requests"));assertEquals(0L,((List<Map<String,Object>>)new OpsMetrics().snapshot().get("routes")).get(0).get("requests"));
 }
}
