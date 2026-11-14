package com.fanzuixianchang.core;
import java.util.*;
public final class ProfessionalChecks {
 private static int count;
 public static void main(String[] args){
  var catalog=FoodCatalog.load();
  var rice=Map.<String,Object>of("id","rice","name","米饭","type","food","foodId","168878","amountMin",100,"amountMax",200,"unit","g");
  var r=NutritionCalculator.calculate(Map.of("items",List.of(rice)),catalog);
  var p=catalog.get("168878").proteinGPer100();
  range(r.get("proteinG"),p,2*p,"canonical protein scales actual portion interval");
  var label=new LinkedHashMap<String,Object>(Map.of("per100",418.4,"energyUnit","kJ","basisUnit","mL","proteinGPer100",0,"fatGPer100",3.5));
  var item=Map.<String,Object>of("id","milk","name","标签","type","label","amountMin",200,"amountMax",300,"unit","mL","label",label);
  r=NutritionCalculator.calculate(Map.of("items",List.of(item)),catalog);
  range(r.get("energyKcal"),200,300,"kJ exact canonical kcal");range(r.get("proteinG"),0,0,"explicit zero is known");range(r.get("fatG"),7,10.5,"label fat grams");
  check(r.containsKey("carbG")&&r.get("carbG")==null,"missing carbohydrate remains null");
  var counts=(Map<?,?>)r.get("nutrientUnknownCounts");check(((Number)counts.get("carbG")).intValue()==1&&((Number)counts.get("proteinG")).intValue()==0,"per nutrient missing counts");
  check(Boolean.TRUE.equals(r.get("complete")),"macro missing does not invalidate known energy");
  r=NutritionCalculator.calculate(Map.of("items",List.of(rice,Map.of("id","u","name","酱料","type","unknown"))),catalog);
  range(r.get("proteinG"),p,2*p,"partial protein sum retained");check(((Number)((Map<?,?>)r.get("nutrientUnknownCounts")).get("proteinG")).intValue()==1,"unknown item missing each nutrient");
  for(Object value:List.of(-1,1001,"3",Double.NaN,Double.POSITIVE_INFINITY)){label.put("proteinGPer100",value);rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(item)),catalog),"invalid label nutrient");}
  r=NutritionCalculator.calculate(Map.of("items",List.of(Map.of("id","u","name","未知","type","unknown"))),catalog);
  check(r.get("proteinG")==null&&r.get("fatG")==null&&r.get("carbG")==null,"all unknown nutrients cannot masquerade as zero");
  check(catalog.get("168878").foodGroup().equals("staple")&&catalog.get("169986").foodGroup().equals("vegetable"),"reviewed basic food groups");
  check(catalog.search("白饭").stream().anyMatch(f->f.id().equals("168878")),"verified rice synonym");
  System.out.println("PASS "+count+" professional nutrition checks");
 }
 static void range(Object raw,double a,double b,String why){check(raw instanceof Map<?,?>,why+" range present");var v=(Map<?,?>)raw;check(Math.abs(((Number)v.get("min")).doubleValue()-a)<1e-6&&Math.abs(((Number)v.get("max")).doubleValue()-b)<1e-6,why);}
 static void check(boolean b,String why){count++;if(!b)throw new AssertionError(why);}
 static void rejects(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(Problem expected){check(true,why);}}
}
