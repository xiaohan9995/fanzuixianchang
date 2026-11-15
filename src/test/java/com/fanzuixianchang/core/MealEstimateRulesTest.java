package com.fanzuixianchang.core;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MealEstimateRulesTest {
 private final FoodCatalog catalog=FoodCatalog.load();
 private Map<String,Object> food(){var f=new LinkedHashMap<String,Object>();f.put("name","番茄炒蛋");f.put("unit","g");f.put("amountMin",180);f.put("amountMax",240);f.put("energyKcal",Map.of("min",220,"max",340));f.put("proteinG",null);f.put("fatG",null);f.put("carbG",null);f.put("portionSource","image-estimate");f.put("assumptions",List.of("按一份家常做法估算，用油量不确定"));return f;}
 private Map<String,Object> apply(Map<String,Object> food){return MealEstimateRules.apply(Map.of("items",List.of(food)),catalog,"test-model");}
 @Test void computesMidpointAndPreservesUnknownMacros(){var result=apply(food());assertEquals("meal-estimate-v1",result.get("version"));assertEquals(280.0,result.get("representativeKcal"));var estimate=(Map<?,?>)((Map<?,?>)((List<?>)result.get("items")).get(0)).get("estimate");assertNull(estimate.get("proteinG"));assertEquals("ai",((Map<?,?>)estimate.get("source")).get("kind"));}
 @Test void rejectsForgedProvenanceAndInvalidUnits(){var f=food();f.put("source",Map.of("kind","catalog"));var forged=f;assertThrows(Problem.class,()->apply(forged));f=food();f.put("unit","cup");var broken=f;assertThrows(Problem.class,()->apply(broken));}
 @Test void rejectsReversedNegativeAndNonFiniteRanges(){for(double n:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}){var f=food();f.put("energyKcal",Map.of("min",n,"max",340));assertThrows(Problem.class,()->apply(f));}var f=food();f.put("amountMin",500);assertThrows(Problem.class,()->apply(f));}
 @Test void calibratesOnlyExactKnownNameAndMatchingUnit(){var f=food();f.put("name",catalog.get("168878").name());var result=apply(f);var estimate=(Map<?,?>)((Map<?,?>)((List<?>)result.get("items")).get(0)).get("estimate");assertEquals("catalog",((Map<?,?>)estimate.get("source")).get("kind"));var kcal=(Map<?,?>)estimate.get("energyKcal");assertEquals(catalog.get("168878").energyKcalPer100()*1.8,((Number)kcal.get("min")).doubleValue(),.001);}
 @Test void labelNumbersUseDeterministicConversion(){var f=food();f.put("unit","mL");f.put("label",Map.of("energyUnit","kJ","basisUnit","mL","per100",100));var result=apply(f);var estimate=(Map<?,?>)((Map<?,?>)((List<?>)result.get("items")).get(0)).get("estimate");assertEquals("label",((Map<?,?>)estimate.get("source")).get("kind"));assertEquals(100/4.184*1.8,((Number)((Map<?,?>)estimate.get("energyKcal")).get("min")).doubleValue(),.001);}
 @Test void emptyImageResultCanRequestRetakeWithoutFakeCalories(){var result=MealEstimateRules.apply(Map.of("items",List.of(),"question",Map.of("text","这张图看不清，请换个角度或描述吃了什么。","options",List.of("重新拍照","文字记录"))),catalog,"test");assertEquals(0.0,result.get("representativeKcal"));assertTrue(((List<?>)result.get("items")).isEmpty());}
}
