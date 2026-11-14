package com.fanzuixianchang.ops;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.FoodCatalog;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public final class OpsController {
 private final OpsStore store;private final ModelGateway model;@org.springframework.beans.factory.annotation.Value("${app.ops.storage:file}") private String storage="file";private final OpsMetrics metrics;
 public OpsController(OpsStore store,ModelGateway model){this(store,model,new OpsMetrics());}
 @org.springframework.beans.factory.annotation.Autowired
 public OpsController(OpsStore store,ModelGateway model,OpsMetrics metrics){this.store=store;this.model=model;this.metrics=metrics;}
 @GetMapping("/api/ops/metrics") public Map<String,Object> metrics(){return metrics.snapshot();}
 @GetMapping("/api/content/starters") public Map<String,Object> published(){var s=store.get();return Map.of("version",s.version(),"entries",s.published());}
 @GetMapping("/api/ops/overview") public Map<String,Object> overview(){return Map.of("status","ok","modelConfigured",model.configured(),"catalogVersion",FoodCatalog.VERSION,"metricsAvailable",true,"storage",storage.equals("cloudbase-pg")?"cloudbase-postgresql":storage.equals("mysql")?"wechat-cloudrun-mysql":"single-instance-file","nutritionEditingAvailable",false);}
 @GetMapping("/api/ops/content") public OpsStore.State content(){return store.get();}
 @PutMapping("/api/ops/content") public OpsStore.State save(@RequestBody OpsStore.Change change){return store.save(change);}
 @PostMapping("/api/ops/content/publish") public OpsStore.State publish(@RequestBody OpsStore.Revision revision){return store.publish(revision.version());}
 @PostMapping("/api/ops/content/rollback") public OpsStore.State rollback(@RequestBody OpsStore.Revision revision){return store.rollback(revision);}
}
