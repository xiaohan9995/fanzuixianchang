package com.fanzuixianchang.api;
import com.fanzuixianchang.core.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Authenticated deterministic tools; independent of model credentials. */
@RestController
@RequestMapping("/api")
public final class NutritionController {
 private final FoodCatalog catalog;
 public NutritionController(){this(FoodCatalog.load());}
 NutritionController(FoodCatalog catalog){this.catalog=catalog;}
 @GetMapping("/foods") public Map<String,Object> foods(@RequestParam(name="q",defaultValue="")String q){return Map.of("version",FoodCatalog.VERSION,"foods",catalog.search(q));}
 @PostMapping("/nutrition/calculate") public Map<String,Object> calculate(@RequestBody Map<String,Object> request){return NutritionCalculator.calculate(request,catalog);}
 @PostMapping("/goals/estimate") public Map<String,Object> estimate(@RequestBody Map<String,Object> request){return GoalEstimator.estimate(request);}
}
