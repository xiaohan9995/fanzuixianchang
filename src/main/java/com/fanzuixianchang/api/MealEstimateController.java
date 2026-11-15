package com.fanzuixianchang.api;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/cases")
public final class MealEstimateController {
 private final ModelGateway model;private final boolean enabled;private final com.fanzuixianchang.ops.OpsMetrics metrics;
 public MealEstimateController(ModelGateway model,boolean enabled){this(model,enabled,new com.fanzuixianchang.ops.OpsMetrics());}
 @org.springframework.beans.factory.annotation.Autowired
 public MealEstimateController(ModelGateway model,@Value("${app.ai.meal-estimate-enabled:true}") boolean enabled,com.fanzuixianchang.ops.OpsMetrics metrics){this.model=model;this.enabled=enabled;this.metrics=metrics;}
 @PostMapping("/estimate-meal") public Map<String,Object> estimate(@RequestBody Contracts.IdentifyRequest request){
  if(!enabled)throw new Problem(503,"ESTIMATE_DISABLED","餐食估算暂不可用，可以先保存草稿或手动记录。");
  var result=model.estimateMeal(IdentificationRules.validate(request));metrics.recordEstimate(result);return result;
 }
}
