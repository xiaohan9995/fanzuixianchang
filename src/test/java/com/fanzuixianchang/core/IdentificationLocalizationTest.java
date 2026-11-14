package com.fanzuixianchang.core;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IdentificationLocalizationTest {
 @Test void candidatesHaveChineseNamesWithoutChangingNutrition(){
  var catalog=FoodCatalog.load();
  var input=IdentificationRules.validate(new Contracts.IdentifyRequest("meal",1,"一罐汽水",List.of(),"solo"));
  var result=IdentificationRules.apply(Map.of("items",List.of(Map.of("name","汽水","preparation","罐装","searchTerms",List.of("tonic water","water")))),input,catalog);
  var row=(Map<?,?>)((List<?>)result.get("items")).get(0);
  var candidates=(List<?>)row.get("candidates");
  assertFalse(candidates.isEmpty());
  for(var entry:candidates){var food=(FoodCatalog.Food)entry;assertTrue(food.name().matches(".*[\\p{IsHan}].*"));assertEquals(catalog.get(food.id()).energyKcalPer100(),food.energyKcalPer100());}
  assertEquals("汤力汽水（含糖）",catalog.get("171869").name());
  assertEquals(34,catalog.get("171869").energyKcalPer100());
 }
}
