package com.fanzuixianchang.api;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/activity")
public final class ActivityController {
 private final ModelGateway model;private final boolean enabled;
 public ActivityController(ModelGateway model,@Value("${app.ai.activity-estimate-enabled:true}") boolean enabled){this.model=model;this.enabled=enabled;}
 @PostMapping("/estimate") public Map<String,Object> estimate(@RequestBody Contracts.ActivityRequest request){
  if(!enabled)throw new Problem(503,"ESTIMATE_DISABLED","运动估算暂不可用，可以先手填消耗。");
  return model.estimateActivity(ActivityRules.validate(request));
 }
}
