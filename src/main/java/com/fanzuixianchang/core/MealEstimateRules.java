package com.fanzuixianchang.core;

import java.util.*;

/** Validates estimated whole-item ranges; provenance and totals are never supplied by the model. */
public final class MealEstimateRules {
 public static final String VERSION="meal-estimate-v1",PROMPT_VERSION="meal-estimate-prompt-v1";
 private static final List<String> NUTRIENTS=List.of("energyKcal","proteinG","fatG","carbG");
 private MealEstimateRules(){}
 public static Map<String,Object> apply(Map<String,Object> raw,FoodCatalog catalog,String modelVersion){
  if(raw==null||!allowed(raw,Set.of("items","question"))||!(raw.get("items") instanceof List<?> items)||items.size()>20)throw Problem.output();
  var result=new ArrayList<Map<String,Object>>();double totalMin=0,totalMax=0;
  for(Object value:items){
   if(!(value instanceof Map<?,?> f)||!allowed(f,Set.of("name","unit","amountMin","amountMax","energyKcal","proteinG","fatG","carbG","portionSource","assumptions","label")))throw Problem.output();
   String name=text(f.get("name"),200),unit=text(f.get("unit"),3),portionSource=text(f.get("portionSource"),30);
   if(!name.matches(".*[\\p{IsHan}].*")||!Set.of("g","mL").contains(unit)||!Set.of("image-estimate","user","label").contains(portionSource))throw Problem.output();
   double min=number(f.get("amountMin"),.001,10000),max=number(f.get("amountMax"),min,10000);
   var assumptions=strings(f.get("assumptions"),6,300);if(assumptions.isEmpty())throw Problem.output();
   var estimate=new LinkedHashMap<String,Object>();
   for(String n:NUTRIENTS)estimate.put(n,range(f.get(n),n.equals("energyKcal"),n.equals("energyKcal")?90000:10000));
   var source=new LinkedHashMap<String,Object>();source.put("kind","ai");source.put("title","AI营养估算");source.put("version",PROMPT_VERSION);
   if(f.get("label")!=null){
    if(!(f.get("label") instanceof Map<?,?> l)||!allowed(l,Set.of("energyUnit","basisUnit","per100","proteinGPer100","fatGPer100","carbGPer100"))||!unit.equals(l.get("basisUnit"))||!Set.of("kcal","kJ").contains(l.get("energyUnit")))throw Problem.output();
    double per100=number(l.get("per100"),0,9000)/(l.get("energyUnit").equals("kJ")?4.184:1);
    if(per100>900)throw Problem.output();estimate.put("energyKcal",scaled(per100,min,max));
    for(String n:NUTRIENTS.subList(1,4)){Object nutrient=l.get(n+"Per100");estimate.put(n,nutrient==null?null:scaled(number(nutrient,0,100),min,max));}
    source.put("kind","label");source.put("title","标签识别数据（可修改）");source.put("version","label-estimate-v1");
   }else{
    var match=catalog.searchLocalized(name).stream().filter(food->food.name().equals(name)&&food.basisUnit().equals(unit)).findFirst();
    if(match.isPresent()){
     var food=match.get();estimate.put("energyKcal",scaled(food.energyKcalPer100(),min,max));
     estimate.put("proteinG",food.proteinGPer100()==null?null:scaled(food.proteinGPer100(),min,max));estimate.put("fatG",food.fatGPer100()==null?null:scaled(food.fatGPer100(),min,max));estimate.put("carbG",food.carbGPer100()==null?null:scaled(food.carbGPer100(),min,max));
     source.put("kind","catalog");source.put("title",food.source().get("title"));source.put("foodId",food.id());source.put("version",FoodCatalog.VERSION);source.put("url",food.source().get("url"));
    }
   }
   // Prevent impossible energy/mass outputs while retaining broad photo uncertainty.
   var energy=castRange(estimate.get("energyKcal"));if(energy.get("max")>max*9.1)throw Problem.output();
   for(String n:NUTRIENTS.subList(1,4))if(estimate.get(n)!=null&&castRange(estimate.get(n)).get("max")>max)throw Problem.output();
   estimate.put("source",Collections.unmodifiableMap(source));estimate.put("portionSource",portionSource);estimate.put("assumptions",assumptions);
   var row=new LinkedHashMap<String,Object>();row.put("id","estimate-"+(result.size()+1));row.put("name",name);row.put("type","estimate");row.put("unit",unit);row.put("amountMin",min);row.put("amountMax",max);row.put("estimate",Collections.unmodifiableMap(estimate));result.add(Collections.unmodifiableMap(row));
   totalMin+=energy.get("min");totalMax+=energy.get("max");
  }
  Object question=null;if(raw.get("question")!=null){if(!(raw.get("question") instanceof Map<?,?> q)||!allowed(q,Set.of("text","options")))throw Problem.output();var options=strings(q.get("options"),4,40);if(options.size()<2)throw Problem.output();question=Map.of("text",text(q.get("text"),200),"options",options);}
  if(result.isEmpty()&&question==null)question=Map.of("text","这次没有看清食物，换张照片或写下吃了什么吧。","options",List.of("重新拍照","文字记录"));
  var response=new LinkedHashMap<String,Object>();response.put("version",VERSION);response.put("items",List.copyOf(result));response.put("energyKcal",Map.of("min",totalMin,"max",totalMax));response.put("representativeKcal",(totalMin+totalMax)/2);response.put("question",question);response.put("modelVersion",modelVersion);response.put("promptVersion",PROMPT_VERSION);return Collections.unmodifiableMap(response);
 }
 @SuppressWarnings("unchecked") private static Map<String,Double> castRange(Object v){return (Map<String,Double>)v;}
 private static Map<String,Double> scaled(double v,double min,double max){return Map.of("min",v*min/100,"max",v*max/100);}
 private static Map<String,Double> range(Object value,boolean required,double upper){if(value==null){if(required)throw Problem.output();return null;}if(!(value instanceof Map<?,?> r)||!allowed(r,Set.of("min","max")))throw Problem.output();double min=number(r.get("min"),0,upper);return Map.of("min",min,"max",number(r.get("max"),min,upper));}
 private static double number(Object v,double min,double max){if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw Problem.output();return n.doubleValue();}
 private static boolean allowed(Map<?,?> m,Set<String> keys){return keys.containsAll(m.keySet());}
 private static String text(Object v,int max){if(!(v instanceof String s)||s.isBlank()||s.length()>max)throw Problem.output();return s.trim();}
 private static List<String> strings(Object v,int max,int length){if(!(v instanceof List<?> list)||list.size()>max)throw Problem.output();return list.stream().map(x->text(x,length)).toList();}
}
