package com.fanzuixianchang.api;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cases")
public final class FoodIdentificationController {
 private final ModelGateway model;
 public FoodIdentificationController(ModelGateway model){this.model=model;}
 @PostMapping("/identify-foods") public Map<String,Object> identify(@RequestBody Contracts.IdentifyRequest request){
  var input=IdentificationRules.validate(request);
  if(!model.configured())throw new Problem(503,"NOT_CONFIGURED","食物识别服务尚未配置，可以先从食物库或标签手动核算。");
  return model.identifyFoods(input);
 }
}
