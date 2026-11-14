package com.fanzuixianchang.core;
import java.util.*;
public final class NutritionChecks {
 private static int count;
 public static void main(String[] args){
  var catalog=FoodCatalog.load();
  check(catalog.size()==7793,"all official calculable SR entries load");
  check(catalog.search("米饭").stream().anyMatch(f->f.id().equals("168878")),"Chinese alias maps to documented cooked rice");
  check(catalog.get("168878").energyKcalPer100()==130,"official rice reference130kcal/100g");
  check(catalog.search("燕麦").stream().anyMatch(f->f.id().equals("173904")),"Chinese dry oats alias");
  check(catalog.search("花菜").stream().anyMatch(f->f.id().equals("169986"))&&!catalog.search("花菜").stream().anyMatch(f->f.id().equals("169967")),"cauliflower is not silently broccoli");
  var result=NutritionCalculator.calculate(Map.of("items",List.of(food("rice","168878",100,200,"g"))),catalog);
  check(range(result,130,260),"food portion interval calculates correctly");
  var label=new LinkedHashMap<String,Object>();label.putAll(Map.of("id","drink","name","标签饮料","type","label","amountMin",250,"amountMax",500,"unit","mL","label",Map.of("per100",200,"energyUnit","kJ","basisUnit","mL")));
  check(range(NutritionCalculator.calculate(Map.of("items",List.of(label)),catalog),500/4.184,1000/4.184),"label kJ converts without rounded intermediate");
  var mixed=NutritionCalculator.calculate(Map.of("items",List.of(food("a","168878",100,100,"g"),Map.of("id","b","name","未知酱料","type","unknown"))),catalog);
  check(Boolean.FALSE.equals(mixed.get("complete"))&&((Number)mixed.get("unknownCount")).intValue()==1,"unknown is retained not a zero complete meal");
  rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(food("a","168878",100,50,"g"))),catalog),"reversed interval");
  rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(food("a","168878",100,100,"mL"))),catalog),"cannot turn volume into rice weight");
  rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(food("a","fake",100,100,"g"))),catalog),"unrecognized catalogid");
  rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(food("a","168878",Double.NaN,100,"g"))),catalog),"nan");
  var goal=GoalEstimator.estimate(profile(30,"male",170,70,1.6,false));
  check(((Number)goal.get("reeKcal")).doubleValue()==1617.5,"Mifflin equation");
  check(((Number)goal.get("maintenanceKcal")).doubleValue()==2600,"nearest50 maintenance reference");
  check(Boolean.FALSE.equals(GoalEstimator.estimate(profile(17,"male",170,70,1.6,false)).get("eligible")),"under18 not auto eligible");
  check(Boolean.FALSE.equals(GoalEstimator.estimate(profile(30,"female",170,45,1.6,false)).get("eligible")),"underweight not auto eligible");
  check(Boolean.FALSE.equals(GoalEstimator.estimate(profile(30,"female",170,70,1.6,true)).get("eligible")),"screening blocks auto reference");
  rejects(()->GoalEstimator.estimate(profile(30,"male",170,70,3,false)),"activity limits");
  rejects(()->GoalEstimator.estimate(Map.of("age",30)),"missing screening and inputs");
  check(DailyContextRules.sanitize(Map.of()).isEmpty(),"optional context absent");
  var ctx=Map.<String,Object>of("date","2026-11-14","goalKcal",2000,"recordedEnergyKcal",Map.of("min",500,"max",700),"unknownCount",1,"coverageComplete",false);
  check(DailyContextRules.sanitize(ctx).get("unknownCount").equals(1),"valid partial day context");
  rejects(()->DailyContextRules.sanitize(Map.of("date","2026-02-30","recordedEnergyKcal",Map.of("min",0,"max",0),"unknownCount",0,"coverageComplete",false)),"invalid real date");
  rejects(()->DailyContextRules.sanitize(Map.of("date","2026-11-14","recordedEnergyKcal",Map.of("min",700,"max",500),"unknownCount",0,"coverageComplete",false)),"reverse context interval");
  var forged=new LinkedHashMap<String,Object>(food("a","168878",100,100,"g"));forged.put("energyKcalPer100",1);
  check(range(NutritionCalculator.calculate(Map.of("items",List.of(forged)),catalog),130,130),"client cannot forge catalog energy");
  rejects(()->NutritionCalculator.calculate(Map.of("items",List.of(food("a","168878",100,100,"g"),food("a","168878",100,100,"g"))),catalog),"duplicate ids do not double count");
  rejects(()->DailyContextRules.sanitize(Map.of("date","2026-11-14","heightCm",170)),"raw profile cannot enter daily context");
  var withDaily=new Contracts.AnalyzeRequest("c1",1,"before","solo","选择午饭",Map.of(),List.of(),Map.of(),null,Map.of(),ctx);
  check(RequestRules.validate(withDaily).dailyContext().containsKey("date"),"daily context reaches valid before request");
  var afterDaily=new Contracts.AnalyzeRequest("c1",1,"after","solo","这一顿饭",Map.of(),List.of(),Map.of(),null,Map.of(),ctx);
  check(RequestRules.validate(afterDaily).dailyContext().isEmpty(),"after analysis does not reuse next-meal summary");
  System.out.println("PASS "+count+" nutrition/goal/context checks");
 }
 static Map<String,Object> food(String id,String food,double low,double high,String unit){return Map.of("id",id,"name","米饭","type","food","foodId",food,"amountMin",low,"amountMax",high,"unit",unit);}
 static Map<String,Object> profile(int age,String sex,double h,double w,double activity,boolean risk){return Map.of("age",age,"sex",sex,"heightCm",h,"weightKg",w,"activityFactor",activity,"screening",Map.of("pregnantOrBreastfeeding",risk,"medicalNutrition",false,"eatingDisorder",false));}
 @SuppressWarnings("unchecked") static boolean range(Map<String,Object> m,double low,double high){var r=(Map<String,Object>)m.get("energyKcal");return Math.abs(((Number)r.get("min")).doubleValue()-low)<.000001&&Math.abs(((Number)r.get("max")).doubleValue()-high)<.000001;}
 static void check(boolean condition,String label){count++;if(!condition)throw new AssertionError(label);}
 static void rejects(Runnable task,String label){boolean failed=false;try{task.run();}catch(Problem p){failed=p.status()==400;}check(failed,label);}
}
