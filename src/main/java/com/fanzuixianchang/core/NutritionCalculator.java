package com.fanzuixianchang.core;
import java.math.*;
import java.util.*;

/** Deterministic edible-weight/label calculation. Unknown is a state, not a zero-valued meal. */
public final class NutritionCalculator {
 public static final String VERSION="nutrition-v2";
 private NutritionCalculator(){}
 @SuppressWarnings("unchecked")
 public static Map<String,Object> calculate(Map<String,Object> request,FoodCatalog catalog){
  if(request==null||!(request.get("items") instanceof List<?> input)||input.isEmpty()||input.size()>30)throw Problem.input("请提供1至30项实际食物。");
  var rows=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();BigDecimal low=BigDecimal.ZERO,high=BigDecimal.ZERO;int unknown=0;var nutrients=new LinkedHashMap<String,NutrientSum>();for(String key:List.of("proteinG","fatG","carbG"))nutrients.put(key,new NutrientSum());
  for(Object raw:input){
   if(!(raw instanceof Map<?,?>))throw Problem.input("食物项目格式无效。");var item=(Map<String,Object>)raw;
   String id=string(item.get("id"),80,"项目编号"),name=string(item.get("name"),200,"食物名称"),type=string(item.get("type"),20,"来源类型");
   if(!id.matches("[a-zA-Z0-9_-]+")||!ids.add(id))throw Problem.input("食物项目编号重复或无效。");
   var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("name",name);
   if(type.equals("unknown")){row.put("status","unknown");row.put("energyKcal",null);row.put("reason","食物、配料或实际份量尚待核对");unknown++;row.put("foodGroup","unknown");for(var entry:nutrients.entrySet()){row.put(entry.getKey(),null);entry.getValue().missing++;}rows.add(row);continue;}
   if(!type.equals("food")&&!type.equals("label"))throw Problem.input("请选择食物条目、标签或待核对项目。");
   double amountMin=number(item.get("amountMin"),.000001,100000,"实际份量"),amountMax=item.get("amountMax")==null?amountMin:number(item.get("amountMax"),.000001,100000,"份量上限");
   if(amountMax<amountMin)throw Problem.input("份量上限不能小于下限。");
   String unit=string(item.get("unit"),10,"份量单位");if(!Set.of("g","mL").contains(unit))throw Problem.input("份量使用g或mL。");
   BigDecimal per100;Map<String,Object> source;var grams=new LinkedHashMap<String,Double>();String group="unknown";
   if(type.equals("food")){
    var f=catalog.get(string(item.get("foodId"),80,"食物条目"));
    if(!unit.equals(f.basisUnit()))throw Problem.input("目录按克计量，不能无依据换算毫升。");
    per100=BigDecimal.valueOf(f.energyKcalPer100());source=f.source();group=f.foodGroup();grams.put("proteinG",f.proteinGPer100());grams.put("fatG",f.fatGPer100());grams.put("carbG",f.carbGPer100());
   }else{
    if(!(item.get("label") instanceof Map<?,?> label))throw Problem.input("请核对标签数值与口径。");
    double value=number(label.get("per100"),0,100000,"每100单位能量");String energy=string(label.get("energyUnit"),10,"标签能量单位");
    if(!Set.of("kcal","kJ").contains(energy))throw Problem.input("标签能量使用kcal或kJ，营养素克数不是热量。");
    if(!unit.equals(label.get("basisUnit")))throw Problem.input("实际份量与标签口径不一致。");
    per100=BigDecimal.valueOf(value);if(energy.equals("kJ"))per100=per100.divide(new BigDecimal("4.184"),MathContext.DECIMAL128);
    for(String key:nutrients.keySet())grams.put(key,label.get(key+"Per100")==null?null:number(label.get(key+"Per100"),0,1000,"标签营养素"));
    source=Map.of("title","用户确认的营养标签","version",VERSION,"id",id,"url","");
   }
   BigDecimal a=per100.multiply(BigDecimal.valueOf(amountMin)).divide(BigDecimal.valueOf(100)),b=per100.multiply(BigDecimal.valueOf(amountMax)).divide(BigDecimal.valueOf(100));
   row.put("foodGroup",group);for(var entry:nutrients.entrySet())row.put(entry.getKey(),entry.getValue().add(grams.get(entry.getKey()),amountMin,amountMax));
   low=low.add(a);high=high.add(b);row.put("status","calculated");row.put("energyKcal",range(a,b));row.put("source",source);rows.add(row);
  }
  var result=new LinkedHashMap<String,Object>();result.putAll(Map.of("version",VERSION,"items",List.copyOf(rows),"energyKcal",range(low,high),"complete",unknown==0,"unknownCount",unknown));
  var missing=new LinkedHashMap<String,Integer>();for(var entry:nutrients.entrySet()){result.put(entry.getKey(),entry.getValue().value());missing.put(entry.getKey(),entry.getValue().missing);}result.put("nutrientUnknownCounts",missing);return Collections.unmodifiableMap(result);
 }
 private static final class NutrientSum {
  BigDecimal min=BigDecimal.ZERO,max=BigDecimal.ZERO;int missing,known;
  Object add(Double grams,double lo,double hi){if(grams==null){missing++;return null;}var value=BigDecimal.valueOf(grams);var a=value.multiply(BigDecimal.valueOf(lo)).divide(BigDecimal.valueOf(100));var b=value.multiply(BigDecimal.valueOf(hi)).divide(BigDecimal.valueOf(100));min=min.add(a);max=max.add(b);known++;return range(a,b);}
  Object value(){return known==0?null:range(min,max);}
 }
 static Map<String,Object> range(BigDecimal a,BigDecimal b){return Map.of("min",a.doubleValue(),"max",b.doubleValue());}
 static double number(Object value,double min,double max,String field){if(!(value instanceof Number n))throw Problem.input(field+"需为数值。");double d=n.doubleValue();if(!Double.isFinite(d)||d<min||d>max)throw Problem.input(field+"超出可用范围。");return d;}
 static String string(Object value,int max,String field){if(!(value instanceof String s)||s.isBlank()||s.length()>max)throw Problem.input(field+"无效。");return s.trim();}
}
