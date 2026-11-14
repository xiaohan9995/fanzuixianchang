package com.fanzuixianchang.core;
import java.time.*;
import java.util.*;
/** Provisional client-owned day summary, never independently verified nutrition evidence. */
public final class DailyContextRules {
 private DailyContextRules(){}
 public static Map<String,Object> sanitize(Map<String,Object> raw){
  if(raw==null||raw.isEmpty())return Map.of();
  if(!Set.of("date","goalKcal","recordedEnergyKcal","unknownCount","coverageComplete").containsAll(raw.keySet()))throw Problem.input("当天摘要包含不支持的字段。");
  String date=NutritionCalculator.string(raw.get("date"),10,"记录日期");try{if(!LocalDate.parse(date).toString().equals(date))throw new IllegalArgumentException();}catch(RuntimeException e){throw Problem.input("记录日期无效。");}
  if(!(raw.get("recordedEnergyKcal") instanceof Map<?,?> energy))throw Problem.input("当天能量摘要无效。");
  double low=NutritionCalculator.number(energy.get("min"),0,1000000,"已记能量"),high=NutritionCalculator.number(energy.get("max"),0,1000000,"已记能量上限");if(high<low)throw Problem.input("当天能量范围无效。");
  double count=NutritionCalculator.number(raw.get("unknownCount"),0,1000,"待核对数");if(count!=Math.floor(count))throw Problem.input("待核对数应为整数。");
  if(!(raw.get("coverageComplete") instanceof Boolean complete))throw Problem.input("请明确记录覆盖情况。");
  var result=new LinkedHashMap<String,Object>();result.put("date",date);result.put("recordedEnergyKcal",Map.of("min",low,"max",high));result.put("unknownCount",(int)count);result.put("coverageComplete",complete);
  result.put("goalKcal",raw.get("goalKcal")==null?null:NutritionCalculator.number(raw.get("goalKcal"),1000,10000,"当天参考目标"));return Collections.unmodifiableMap(result);
 }
}
